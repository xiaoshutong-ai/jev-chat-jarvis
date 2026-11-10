package com.jev.probe

import android.app.Activity
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import com.jev.probe.capture.KeepAliveService
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import android.widget.Toast
import com.jev.probe.core.ExplicitClipboardPolicy

/**
 * Foreground-only clipboard handoff initiated by a deliberate JEV bubble tap.
 *
 * Android 10+ denies background clipboard access. onCreate/onResume run before
 * the Activity necessarily owns focus, so wait for real window focus first.
 * Never monitor clipboard changes, touch WeChat UI, or retain copied text.
 */
class ClipboardImportActivity : Activity() {
    private var readAttempted = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private val focusTimeout = Runnable {
        if (!readAttempted && !isFinishing) {
            Toast.makeText(this, "未能在前台读取剪贴板，请再点一次 JEV 悬浮球", Toast.LENGTH_SHORT).show()
            closeQuietly()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        // Do not access primaryClip here; this transparent Activity may not yet
        // be the focused foreground app.
        mainHandler.postDelayed(focusTimeout, 3_000L)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus || readAttempted) return
        readAttempted = true
        mainHandler.removeCallbacks(focusTimeout)
        window.decorView.post {
            if (!hasWindowFocus()) {
                Toast.makeText(this, "剪贴板前台权限暂不可用，请再试一次", Toast.LENGTH_SHORT).show()
                closeQuietly()
            } else {
                readUserSelectedClipboard()
            }
        }
    }

    private fun readUserSelectedClipboard() {
        try {
            val manager = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            val clip = manager.primaryClip
            val mimeOk = clip?.description?.let {
                it.hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN) ||
                    it.hasMimeType(ClipDescription.MIMETYPE_TEXT_HTML)
            } == true

            if (!mimeOk || clip == null || clip.itemCount == 0) {
                Toast.makeText(this, "请先在微信中复制一条对方消息", Toast.LENGTH_SHORT).show()
                return
            }
            // JEV candidate cards explicitly label their own copy operation.
            // Do not accidentally re-analyze an outgoing JEV-generated reply.
            if (clip.description.label?.toString() == "jev_reply") {
                Toast.makeText(this, "这是已复制的 JEV 候选，请直接粘贴到微信", Toast.LENGTH_SHORT).show()
                return
            }

            val text = clip.getItemAt(0).coerceToText(this)?.toString().orEmpty().trim()
            if (text.isBlank()) {
                Toast.makeText(this, "剪贴板没有可分析的文字", Toast.LENGTH_SHORT).show()
            } else if (text.length > ExplicitClipboardPolicy.MAX_TEXT_CHARS) {
                Toast.makeText(this, "消息超过 4000 字，请只复制一条对方消息", Toast.LENGTH_LONG).show()
            } else {
                // KeepAliveService is manifest non-exported. An explicit
                // same-app service intent prevents other apps from forging
                // pre-Android-13 dynamically registered clipboard broadcasts.
                val delivery = Intent(this, KeepAliveService::class.java)
                    .setAction(ACTION_CLIPBOARD_READY)
                    .putExtra(EXTRA_TEXT, text)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(delivery)
                } else {
                    startService(delivery)
                }
            }
        } finally {
            closeQuietly()
        }
    }

    private fun closeQuietly() {
        mainHandler.removeCallbacks(focusTimeout)
        if (!isFinishing) finishAndRemoveTask()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(focusTimeout)
        super.onDestroy()
    }

    companion object {
        const val ACTION_CLIPBOARD_READY = "com.jev.probe.action.CLIPBOARD_READY"
        const val EXTRA_TEXT = "text"
    }
}
