package com.jev.probe.debug

import android.app.Activity
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.content.Context
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.jev.probe.session.DesktopBridgeClient
import com.jev.probe.session.DesktopMessageIndex
import com.jev.probe.session.DesktopPairingState
import com.jev.probe.session.DesktopReplyInbox
import com.jev.probe.session.DesktopSyncPipeline
import java.util.concurrent.Executors

/**
 * Debug-only fixture host that exercises the production IME's chat-bound path.
 * No real chat app, keys, SDK interception, model calls or account content.
 * This activity is not included in release APKs and refuses non-emulators.
 */
class SyntheticChatActivity : Activity() {
    private val worker = Executors.newSingleThreadExecutor()
    private var pipeline: DesktopSyncPipeline? = null
    private lateinit var status: TextView
    private lateinit var editorA: EditText
    private lateinit var editorB: EditText
    private lateinit var labelA: TextView
    private lateinit var labelB: TextView
    private val scope: String get() = "com.tencent.mm@" + Process.myUserHandle().toString()
    private val account = "fixture-account"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val emulator = Build.FINGERPRINT.contains("generic", ignoreCase = true) ||
            Build.HARDWARE.contains("ranchu", ignoreCase = true) ||
            Build.HARDWARE.contains("goldfish", ignoreCase = true)
        if (!emulator) {
            finish()
            return
        }

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 16, 16, 20)
        }
        val scroll = ScrollView(this).apply { isFillViewport = true }
        scroll.addView(column)
        setContentView(scroll)
        column.addView(TextView(this).apply {
            text = "JEV 合成会话测试"
            textSize = 20f
            setTextColor(Color.BLACK)
        })
        column.addView(TextView(this).apply {
            text = "纯模拟消息，无真实微信、无模型调用；不自动发送"
            textSize = 12f
            setTextColor(Color.DKGRAY)
        })
        status = TextView(this).apply {
            text = "SYNC=NOT_RUN"
            textSize = 13f
            setTextColor(Color.BLUE)
            contentDescription = "synthetic_sync_status"
        }
        column.addView(status)

        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        buttons.addView(Button(this).apply {
            text = "同步测试消息"
            setOnClickListener { pollFixture() }
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        buttons.addView(Button(this).apply {
            text = "撤销配对"
            setOnClickListener { revokeFixture() }
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        column.addView(buttons)

        labelA = TextView(this).apply { text = "模拟会话甲 / 合成联系人甲"; textSize = 14f }
        labelB = TextView(this).apply { text = "模拟会话乙 / 合成联系人乙"; textSize = 14f }
        column.addView(labelA)
        editorA = makeEditor("模拟输入框甲")
        column.addView(editorA)
        column.addView(labelB)
        editorB = makeEditor("模拟输入框乙")
        column.addView(editorB)

        val switchRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        switchRow.addView(Button(this).apply {
            text = "切换至甲"
            setOnClickListener { focusChat(editorA) }
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        switchRow.addView(Button(this).apply {
            text = "切换至乙"
            setOnClickListener { focusChat(editorB) }
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        column.addView(switchRow)
        editorA.requestFocus()
    }

    private fun makeEditor(hintLabel: String): EditText = EditText(this).apply {
        hint = hintLabel
        contentDescription = hintLabel
        textSize = 14f
        minLines = 2
        setSingleLine(false)
        setPrivateImeOptions("com.jev.probe.debug.SYNTHETIC_CHAT_V1")
    }

    private fun focusChat(edit: EditText) {
        edit.requestFocus()
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.restartInput(edit) // new editor epoch; old candidate button must become stale
        edit.post { imm.showSoftInput(edit, InputMethodManager.SHOW_IMPLICIT) }
        status.text = "FOCUS=" + if (edit === editorA) "A" else "B"
    }

    private fun pollFixture() {
        if (pipeline != null) {
            status.text = "SYNC=ALREADY_PAIRED"
            return
        }
        // The token is supplied only to this ephemeral debug Activity via ADB.
        // It is never persisted, embedded in source, shown or sent to the model.
        val token = intent.getStringExtra("relay_token").orEmpty()
        if (token.length < 32) {
            status.text = "SYNC=TOKEN_MISSING"
            return
        }
        status.text = "SYNC=CONNECTING"
        worker.execute {
            var candidate: DesktopSyncPipeline? = null
            try {
                val config = DesktopBridgeClient.Config(
                    endpoint = "http://127.0.0.1:8766",
                    bearerToken = token,
                    pairedAccountRef = account,
                    androidProfileScope = scope
                )
                candidate = DesktopSyncPipeline(config) { context ->
                    // Intentionally deterministic. This cannot incur AI cost.
                    listOf("SIM_REPLY_" + context.sessionId)
                }
                val result = candidate.pollOnce(autoAnalysisApproved = true)
                pipeline = candidate
                val sessions = DesktopMessageIndex.conversations(scope, account)
                runOnUiThread {
                    status.text = "SYNC=PASS sessions=" + sessions.size +
                        " analyzed=" + result.analyzedSessions +
                        " messages=" + result.accepted
                    focusChat(editorA)
                }
            } catch (failure: Exception) {
                candidate?.stop()
                runOnUiThread { status.text = "SYNC=ERROR " + failure.javaClass.simpleName }
            }
        }
    }

    private fun revokeFixture() {
        val active = pipeline
        pipeline = null
        worker.execute {
            active?.stop()
            DesktopPairingState.revoke(scope)
            DesktopReplyInbox.clearScoped(scope, account)
            DesktopMessageIndex.clearScoped(scope, account)
            runOnUiThread { status.text = "SYNC=REVOKED"; focusChat(editorA) }
        }
    }

    override fun onDestroy() {
        pipeline?.let { current -> worker.execute { current.stop() } }
        worker.shutdown()
        super.onDestroy()
    }
}
