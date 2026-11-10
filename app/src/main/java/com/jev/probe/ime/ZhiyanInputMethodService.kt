package com.jev.probe.ime

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.content.pm.ApplicationInfo
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.jev.probe.ui.Guofeng
import com.jev.probe.session.DesktopMessageIndex
import com.jev.probe.session.DesktopPairingState
import com.jev.probe.session.DesktopReplyInbox
import com.jev.probe.session.NotificationClickTracker
import com.jev.probe.session.SessionBindingResolver
import kotlin.math.roundToInt

/**
 * Optional one-shot IME for inserting an already-generated candidate through
 * Android's standard InputConnection.
 *
 * It does not inspect the target app package, view ids or UI hierarchy. The user
 * must explicitly enable/select this IME through Android settings.
 */
class ZhiyanInputMethodService : InputMethodService() {

    private var candidateBox: LinearLayout? = null
    private var editorEpoch = 0L
    private var focusedWechatEditor: SessionBindingResolver.EditorFocus? = null
    private var pendingExplicitReply: String? = null

    private fun dp(v: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP,
        v.toFloat(),
        resources.displayMetrics
    ).roundToInt()

    override fun onEvaluateFullscreenMode(): Boolean = false

    override fun onCreateInputView(): View {
        val outer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = GradientDrawable().apply {
                setColor(Guofeng.CARD)
                setStroke(dp(1), Guofeng.BORDER_JADE)
            }
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(TextView(this).apply {
            text = "小书童·快捷填入"
            textSize = 15f
            setTextColor(Guofeng.JADE_DEEP)
            typeface = Guofeng.serif(true)
            layoutParams = LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f
            )
        })
        header.addView(action("返回原键盘") {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                if (!switchToPreviousInputMethod()) switchToNextInputMethod(false)
            } else {
                requestHideSelf(0)
            }
        })
        outer.addView(header)

        outer.addView(TextView(this).apply {
            text = "点候选即写入当前文本框；发送仍由你完成"
            textSize = 11.5f
            setTextColor(Guofeng.INK_SOFT)
            setPadding(0, dp(4), 0, dp(8))
        })

        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        candidateBox = box
        outer.addView(ScrollView(this).apply {
            addView(box)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(245)
            )
        })
        renderCandidates()
        return outer
    }

    /** Debug-only, app-owned synthetic editor: not a WeChat UI or fake WeChat package. */
    private fun isSyntheticDebugEditor(info: EditorInfo?): Boolean =
        (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0 &&
            info?.packageName == packageName &&
            info?.privateImeOptions == "com.jev.probe.debug.SYNTHETIC_CHAT_V1"

    private fun isExpectedChatEditor(info: EditorInfo?): Boolean =
        info?.packageName == SessionBindingResolver.WECHAT_PACKAGE ||
            isSyntheticDebugEditor(info)

    private fun refreshEditorIdentity(info: EditorInfo?) {
        editorEpoch = if (editorEpoch == Long.MAX_VALUE) 1L else editorEpoch + 1
        focusedWechatEditor = if (isExpectedChatEditor(info)) {
            // The IME and notification listener must run in the same Android user
            // for this string to match. Clone-user delivery remains unverified.
            val scope = SessionBindingResolver.WECHAT_PACKAGE +
                "@" + android.os.Process.myUserHandle().toString()
            SessionBindingResolver.EditorFocus(
                packageName = SessionBindingResolver.WECHAT_PACKAGE,
                profileScope = scope,
                epoch = editorEpoch,
                startedAtMs = System.currentTimeMillis()
            )
        } else null
        pendingExplicitReply = null
    }

    override fun onStartInput(info: EditorInfo?, restarting: Boolean) {
        super.onStartInput(info, restarting)
        refreshEditorIdentity(info)
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        refreshEditorIdentity(info)
        val armed = ReplyHandoff.consumeArmed()
        if (focusedWechatEditor == null && !armed.isNullOrBlank()) {
            // Existing one-shot behavior is unchanged outside WeChat.
            commitCandidate(armed)
            return
        }
        // For WeChat, an armed reply is NOT proof of the active contact.
        // The user must tap the visible, labelled candidate before insertion.
        pendingExplicitReply = armed
        renderCandidates()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        focusedWechatEditor = null
        pendingExplicitReply = null
        editorEpoch++
        super.onFinishInputView(finishingInput)
    }

    override fun onFinishInput() {
        focusedWechatEditor = null
        pendingExplicitReply = null
        editorEpoch++
        super.onFinishInput()
    }

    private fun renderCandidates() {
        val box = candidateBox ?: return
        box.removeAllViews()
        val focus = focusedWechatEditor
        if (focus == null) {
            val replies = ReplyHandoff.current()
            if (replies.isEmpty()) {
                box.addView(infoText("暂无候选回复。先在小书童悬浮窗完成一次分析。"))
                return
            }
            replies.forEachIndexed { index, reply ->
                box.addView(replyButton((index + 1).toString() + ". " + reply.text, index == 0) {
                    commitCandidate(reply.text)
                })
            }
            return
        }

        // An Android IME sees an editor, not a WeChat conversation ID.
        // Never silently insert the last desktop reply into whichever chat is open.
        box.addView(infoText("请核对当前微信聊天对象。点击下方候选才会填入，JEV 不会自动发送。"))
        val manual = pendingExplicitReply
        if (!manual.isNullOrBlank()) {
            box.addView(infoText("手动选择的候选 · 未验证联系人"))
            box.addView(replyButton(manual, true) {
                if (focusedWechatEditor == focus) commitCandidate(manual)
            })
        }

        val pairedAccount = DesktopPairingState.accountFor(focus.profileScope)
        if (pairedAccount.isNullOrBlank()) {
            if (manual.isNullOrBlank()) box.addView(infoText("暂无已配对的电脑会话候选。"))
            return
        }
        val candidates = DesktopReplyInbox.list(focus.profileScope, pairedAccount)
        val conversations = DesktopMessageIndex.conversations(focus.profileScope, pairedAccount)
        val hint = NotificationClickTracker.latestClick(focus.profileScope, focus.startedAtMs)
        val suggested = SessionBindingResolver.suggest(focus, hint, conversations)
        val suggestedId = (suggested as? SessionBindingResolver.Suggestion.One)?.conversation?.sessionId
        val observedBySession = conversations.associateBy { it.sessionId }
        val sorted = candidates.sortedWith(
            compareByDescending<DesktopReplyInbox.Bundle> { it.sessionId == suggestedId }
                .thenByDescending { it.publishedAtMs }
        ).take(3)
        if (sorted.isEmpty() && manual.isNullOrBlank()) {
            box.addView(infoText("电脑尚未生成候选，或候选已经过期。"))
        }
        sorted.forEach { bundle ->
            val source = observedBySession[bundle.sessionId]
            if (source != null) {
                val isManualSelection = bundle.sessionId.startsWith(
                    SessionBindingResolver.USER_SELECTED_SESSION_PREFIX
                )
                val label = when {
                    isManualSelection -> "用户主动选取 · "
                    bundle.sessionId == suggestedId -> "通知建议 · "
                    else -> "电脑会话 · "
                }
                val warning = if (isManualSelection) {
                    "（联系人身份未验证；请核对当前聊天，点击后仅填入）"
                } else {
                    "（点击候选即确认此对象）"
                }
                box.addView(infoText(label + bundle.displayName + warning))
                bundle.replies.forEachIndexed { index, reply ->
                    box.addView(replyButton((index + 1).toString() + ". " + reply, index == 0) {
                        val current = focusedWechatEditor
                        val now = System.currentTimeMillis()
                        if (current != focus || !isExpectedChatEditor(currentInputEditorInfo)) {
                            toastLocal("输入窗口已切换，请重新选择会话")
                            return@replyButton
                        }
                        // The keyboard can remain visible while the user changes
                        // desktop account or the server replaces/withdraws replies.
                        // Re-check the *current* pairing and the exact candidate
                        // before treating this tap as an explicit confirmation.
                        if (DesktopPairingState.accountFor(focus.profileScope) != pairedAccount ||
                            !DesktopReplyInbox.isActiveCandidate(
                                focus.profileScope, pairedAccount, bundle.sessionId,
                                bundle.publishedAtMs, reply, now
                            )) {
                            toastLocal("该联系人候选已变化，请刷新键盘")
                            return@replyButton
                        }
                        // The visible candidate tap is explicit user confirmation.
                        val confirmed = SessionBindingResolver.confirm(
                            current, SessionBindingResolver.Suggestion.One(source),
                            bundle.sessionId, now
                        )
                        if (!SessionBindingResolver.canInsert(
                                current, confirmed, bundle.sessionId, now
                            )) {
                            toastLocal("会话确认已失效，请重新打开键盘")
                            return@replyButton
                        }
                        if (commitCandidate(reply)) {
                            DesktopReplyInbox.consume(
                                focus.profileScope, pairedAccount, bundle.sessionId
                            )
                        }
                    })
                }
            }
        }
    }

    private fun infoText(content: String): TextView = TextView(this).apply {
        text = content
        textSize = 12f
        setTextColor(Guofeng.INK_SOFT)
        setPadding(dp(8), dp(8), dp(8), dp(8))
    }

    private fun replyButton(label: String, top: Boolean, click: () -> Unit): TextView =
        TextView(this).apply {
            text = label
            textSize = 14f
            setTextColor(Guofeng.INK)
            typeface = Typeface.DEFAULT
            setPadding(dp(10), dp(10), dp(10), dp(10))
            background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(if (top) Guofeng.JADE_PALE else Guofeng.CARD_SOFT)
                setStroke(dp(1), Guofeng.BORDER)
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(5) }
            setOnClickListener { click() }
        }

    private fun toastLocal(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun commitCandidate(text: String): Boolean {
        if (focusedWechatEditor != null &&
            !isExpectedChatEditor(currentInputEditorInfo)) {
            toastLocal("当前输入框已变化，未填入")
            return false
        }
        val ic = currentInputConnection
        if (ic == null) {
            toastLocal("当前没有可写入的文本框")
            return false
        }
        val ok = runCatching {
            ic.finishComposingText()
            ic.commitText(text, 1)
        }.getOrDefault(false)
        if (!ok) {
            toastLocal("写入失败，请改用复制")
            return false
        }
        ReplyHandoff.clear()
        pendingExplicitReply = null
        toastLocal("已填入，发送仍由你完成")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            if (!switchToPreviousInputMethod()) requestHideSelf(0)
        } else {
            requestHideSelf(0)
        }
        return true
    }

    private fun action(label: String, onClick: () -> Unit): View = TextView(this).apply {
        text = label
        textSize = 12f
        gravity = Gravity.CENTER
        setTextColor(Color.WHITE)
        setPadding(dp(10), dp(6), dp(10), dp(6))
        background = GradientDrawable().apply {
            cornerRadius = dp(14).toFloat()
            setColor(Guofeng.JADE_DEEP)
        }
        setOnClickListener { onClick() }
    }
}
