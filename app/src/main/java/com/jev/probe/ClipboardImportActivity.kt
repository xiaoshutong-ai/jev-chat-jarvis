package com.jev.probe

import android.app.Activity
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.WindowManager
import android.widget.Toast
import com.jev.probe.capture.KeepAliveService
import com.jev.probe.core.ExplicitClipboardPolicy

/**
 * One deliberate clipboard read while JEV owns the foreground Activity.
 *
 * The user initiates this from the visible bubble. This Activity does not
 * watch clipboard changes, touch WeChat's UI, or persist any copied text.
 * Results are handed to a manifest non-exported JEV foreground service.
 */
class ClipboardImportActivity : Activity() {
    private var readAttempted = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private val focusTimeout = Runnable {
        if (!readAttempted && !isFinishing) {
            reportStatus("NO_FOCUS")
            closeQuietly()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        window.decorView.setBackgroundColor(Color.TRANSPARENT)
        window.setWindowAnimations(0)
        // Android 10+ prevents background clipboard reads. Wait for focus.
        mainHandler.postDelayed(focusTimeout, 3_000L)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus || readAttempted) return
        readAttempted = true
        mainHandler.removeCallbacks(focusTimeout)
        Log.i(TAG, "clipboard_state=FOREGROUND_FOCUSED")
        window.decorView.post {
            if (!hasWindowFocus()) {
                reportStatus("NO_FOCUS")
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
            val plainText = clip?.description?.let {
                it.hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN) ||
                    it.hasMimeType(ClipDescription.MIMETYPE_TEXT_HTML)
            } == true

            if (!plainText || clip == null || clip.itemCount == 0) {
                reportStatus("NO_TEXT")
                return
            }

            if (clip.description.label?.toString() == "jev_reply") {
                reportStatus("OWN_REPLY")
                return
            }

            val text = clip.getItemAt(0).coerceToText(this)?.toString().orEmpty().trim()
            when {
                text.isBlank() -> reportStatus("NO_TEXT")
                text.length > ExplicitClipboardPolicy.MAX_TEXT_CHARS -> reportStatus("TOO_LONG")
                else -> {
                    startInternalService(
                        Intent(this, KeepAliveService::class.java)
                            .setAction(ACTION_CLIPBOARD_READY)
                            .putExtra(EXTRA_TEXT, text)
                    )
                    Log.i(TAG, "clipboard_state=HANDOFF_SENT")
                }
            }
        } catch (failure: Exception) {
            // Only an exception class is logged; never any clipboard value.
            Log.w(TAG, "clipboard_state=READ_FAILED_" + failure.javaClass.simpleName)
            reportStatus("UNAVAILABLE")
        } finally {
            closeQuietly()
        }
    }

    private fun startInternalService(intent: Intent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun reportStatus(code: String) {
        val intent = Intent(this, KeepAliveService::class.java)
            .setAction(ACTION_CLIPBOARD_STATUS)
            .putExtra(EXTRA_STATUS, code)
        runCatching { startInternalService(intent) }.onFailure {
            Log.w(TAG, "clipboard_state=STATUS_HANDOFF_FAILED_" + it.javaClass.simpleName)
            Toast.makeText(this, "暂时无法显示剪贴板状态，请重新打开 JEV", Toast.LENGTH_SHORT).show()
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
        private const val TAG = "JEVASSIST"
        const val ACTION_CLIPBOARD_READY = "com.jev.probe.action.CLIPBOARD_READY"
        const val ACTION_CLIPBOARD_STATUS = "com.jev.probe.action.CLIPBOARD_STATUS"
        const val EXTRA_TEXT = "text"
        const val EXTRA_STATUS = "status"
    }
}
