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

        container.addView(message("最近 20 条 · 本次对话", 23f, true))
        container.addView(message(
            "手动粘贴按时间先后排列的对话，每行以“我：”或“对方：”开头。最多使用最后 20 条。" +
                "最后一条须为对方消息，与你之后在微信主动复制的文字完全一致。",
            14f
        ))
        container.addView(message(
            "JEV 不读取微信聊天列表、不识别联系人。仅当你随后复制的最新消息与末条文字相同时，" +
                "本次分析才会使用导入内容；如果聊天对象变了，请先清除或重新导入。",
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
            text = "同时参考我自己发过的消息语气（需要至少 3 条明确标注为“我”的文字）"
            isChecked = false
            setTextColor(Guofeng.INK)
        }
        container.addView(mimic)
        container.addView(message(
            "只从你明确标注的真实本人消息提取措辞、字数和语气。" +
                "AI 生成的候选不能充当学习样本；此功能不调用额外训练接口。",
            12f
        ))

        val status = message("", 13f)
        fun showStatus(prefix: String = "") {
            val s = ManualConversationContext.status(System.currentTimeMillis())
            status.text = if (!s.active) {
                prefix + "本次尚无导入上下文"
            } else {
                prefix + "已暂存 ${s.messages} 条；风格样本 ${s.styleSamples} 条。" +
                    " 仅保留在内存中，60 分钟后失效。"
            }
        }
        container.addView(Button(this).apply {
            text = "确认使用本次对话（仅当前运行）"
            isAllCaps = false
            setOnClickListener {
                try {
                    val info = ManualConversationContext.import(
                        draft.text.toString(), mimic.isChecked, System.currentTimeMillis()
                    )
                    draft.setText("")
                    val hint = if (mimic.isChecked && info.styleSamples == 0) {
                        "本人样本不足，已仅启用对话上下文。"
                    } else ""
                    showStatus("导入成功。${hint}\n")
                } catch (e: IllegalArgumentException) {
                    status.text = e.message ?: "对话格式不正确"
                }
            }
        })
        container.addView(Button(this).apply {
            text = "清除本次对话与风格样本"
            isAllCaps = false
            setOnClickListener {
                ManualConversationContext.clear()
                draft.setText("")
                showStatus("已清除。")
            }
        })
        container.addView(status)
        container.addView(message(
            "导入只保存在 JEV 进程内存中，关闭应用进程后失效；" +
                "主动发起分析时，相关最近对话及样本会发送给你配置的判断/回复模型服务商。" +
                "没有额外权限，也不会自动发送微信消息。",
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
