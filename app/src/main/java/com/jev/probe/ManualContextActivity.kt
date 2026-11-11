package com.jev.probe

import android.os.Bundle
import android.text.InputFilter
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.jev.probe.core.ManualConversationContext
import com.jev.probe.core.ManualTranscriptParser
import com.jev.probe.ui.Guofeng
import com.jev.probe.ui.InkPaperDrawable

/**
 * Explicit one-conversation import. Text is kept in app process memory only,
 * and is never read from or written to WeChat or local persistent storage.
 */
class ManualContextActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Guofeng.applyWindow(this)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(28), dp(20), dp(30))
        }
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            background = InkPaperDrawable()
            addView(container)
        }
        setContentView(scroll)

        container.addView(message("补充最近对话", 23f, true))
        container.addView(message(
            "粘贴同一个人的聊天内容，按先后顺序，每行一条，以“我：”或“对方：”开头。最多使用最近 20 条。" +
                "最后一条应是对方消息。之后回到微信，复制这一条的正文，再点悬浮球分析。",
            14f
        ))
        container.addView(message(
            "助手不会自动读取微信历史或确认聊天对象。复制的消息与这里最后一条相同时，" +
                "才会参考这些内容。切换聊天对象后，请先清除或重新导入，避免混用。",
            12f
        ))
        val draft = EditText(this).apply {
            hint = "我：我晚一点回复你\n对方：你大概什么时候方便"
            minLines = 9
            maxLines = 15
            isSingleLine = false
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            filters = arrayOf(InputFilter.LengthFilter(ManualTranscriptParser.MAX_TEXT_CHARS))
            setSaveEnabled(false)
            importantForAutofill = android.view.View.IMPORTANT_FOR_AUTOFILL_NO
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        container.addView(draft)
        val mimic = CheckBox(this).apply {
            text = "参考我的说话方式（至少 3 条本人消息）"
            isChecked = false
            setTextColor(Guofeng.INK)
        }
        container.addView(mimic)
        container.addView(message(
            "只参考标注为“我”的真实发言；填写了回复风格时，以你的设置为主。" +
                "请不要把 AI 建议的回复当作自己已发过的消息。",
            12f
        ))

        val status = message("", 13f)
        fun showStatus(prefix: String = "") {
            val s = ManualConversationContext.status(System.currentTimeMillis())
            status.text = if (!s.active) {
                prefix + "还没有补充对话"
            } else {
                prefix + "已添加 ${s.messages} 条对话；参考本人消息 ${s.styleSamples} 条。" +
                    " 60 分钟后自动失效，应用被系统关闭后也会失效。"
            }
        }
        container.addView(Button(this).apply {
            text = "添加到本次分析"
            isAllCaps = false
            setOnClickListener {
                try {
                    val info = ManualConversationContext.import(
                        draft.text.toString(), mimic.isChecked, System.currentTimeMillis()
                    )
                    draft.setText("")
                    val hint = if (mimic.isChecked && info.styleSamples == 0) {
                        "本人消息不足 3 条，本次只参考聊天内容。"
                    } else ""
                    showStatus("已添加。${hint}\n")
                } catch (e: IllegalArgumentException) {
                    status.text = e.message ?: "对话格式不正确"
                }
            }
        })
        container.addView(Button(this).apply {
            text = "清除本次对话"
            isAllCaps = false
            setOnClickListener {
                ManualConversationContext.clear()
                draft.setText("")
                showStatus("已清除。")
            }
        })
        container.addView(status)
        container.addView(message(
            "这些对话仅临时保留，不会保存为长期聊天记录。" +
                "点击分析时，对话与参考样本会交给所选模型服务商，可能收费。" +
                "回复由你确认后发送。",
            12f
        ))
        showStatus()
    }

    private fun dp(n: Int) = Guofeng.dp(this, n)

    private fun message(s: String, size: Float, bold: Boolean = false) =
        TextView(this).apply {
            text = s
            textSize = size
            setTextColor(if (bold) Guofeng.JADE_DEEP else Guofeng.INK_SOFT)
            if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
            gravity = Gravity.START
            setPadding(0, dp(6), 0, dp(10))
        }
}
