package com.jev.probe

import android.graphics.Bitmap
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputFilter
import android.text.InputType
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.jev.probe.core.ChatSnapshot
import com.jev.probe.core.Msg
import com.jev.probe.core.Prefs
import com.jev.probe.core.ReplyStylePolicy
import com.jev.probe.session.DesktopBridgeClient
import com.jev.probe.capture.KeepAliveService
import com.jev.probe.core.SecuritySelfCheck
import com.jev.probe.capture.ocr.VisionDialogParser
import com.jev.probe.core.kb.KbSelfCheck
import com.jev.probe.core.kb.KbStore
import com.jev.probe.jev.JevClient
import com.jev.probe.jev.ReplyClient
import com.jev.probe.jev.VisionClient
import com.jev.probe.ui.Guofeng
import com.jev.probe.ui.InkPaperDrawable
import java.util.concurrent.Executors
import kotlin.math.roundToInt

class SettingsActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    private val accent = Guofeng.JADE
    private val ink = Guofeng.INK
    private val sub = Guofeng.INK_SOFT
    private val pillOff = Guofeng.GOLD_SOFT

    /** Selected provider index per card, held so Save can read it back. */
    private var judgeProviderIdx = 0

    private fun dp(v: Int) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).roundToInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        Log.i(TAG, "settings opened judgeKey.len=${prefs.judgeKey.length}" +
            " replyKey.len=${prefs.replyKey.length} visionKey.len=${prefs.visionKey.length}")
        Guofeng.applyWindow(this)

        val scroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            background = InkPaperDrawable()
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(22), dp(18), dp(28))
        }
        root.padForSystemBars()   // edge-to-edge: keep the title off the status bar
        scroll.addView(root)

        root.addView(header("设置"))
        root.addView(text("模型服务 · 回复风格 · 隐私 · 外观", 12.5f, Guofeng.GOLD).apply {
            setPadding(0, dp(2), 0, dp(6))
        })

        // =================== 接口 ===================
        root.addView(section("模型服务"))

        // --- 判断引擎 ---
        val judgeCard = card()
        judgeCard.addView(cardTitle("分析对话"))
        judgeCard.addView(text(
            "用于理解对话并比较回复建议。选择你已开通的服务，填写相应密钥。",
            12f, sub
        ))

        val judgeBaseEdit = edit(prefs.judgeBaseUrl, Prefs.DEFAULT_JUDGE_BASE_OPENROUTER)
        val judgeModelEdit = edit(prefs.judgeModel, Prefs.DEFAULT_JUDGE_MODEL_OPENROUTER)
        judgeProviderIdx = when (prefs.judgeProvider) {
            Prefs.PROVIDER_TYPESAFE -> 1
            Prefs.PROVIDER_GLM47 -> 2
            Prefs.PROVIDER_CUSTOM -> 3
            else -> 0
        }
        judgeCard.addView(pills(
            listOf("OpenRouter", "TypeSafe", "GLM-4.7", "自定义 Jev"), judgeProviderIdx) { idx ->
            judgeProviderIdx = idx
            when (idx) {
                0 -> {
                    judgeBaseEdit.setText(Prefs.DEFAULT_JUDGE_BASE_OPENROUTER)
                    judgeModelEdit.setText(Prefs.DEFAULT_JUDGE_MODEL_OPENROUTER)
                }
                1 -> {
                    judgeBaseEdit.setText(Prefs.DEFAULT_JUDGE_BASE_TYPESAFE)
                    judgeModelEdit.setText(Prefs.DEFAULT_JUDGE_MODEL_TYPESAFE)
                }
                2 -> {
                    judgeBaseEdit.setText(Prefs.DEFAULT_JUDGE_BASE_GLM47)
                    judgeModelEdit.setText(Prefs.DEFAULT_JUDGE_MODEL_GLM47)
                }
                // Legacy custom mode speaks the Jev Decisions wire format.
                // Do not silently reinterpret a GLM chat-completions preset as Jev.
                3 -> {
                    val current = judgeBaseEdit.text.toString().trim().trimEnd('/')
                    if (current == Prefs.DEFAULT_JUDGE_BASE_GLM47) {
                        judgeBaseEdit.setText("")
                        judgeModelEdit.setText("")
                    } else {
                        judgeBaseEdit.setText(expandJudgeUrl(judgeBaseEdit.text.toString()))
                    }
                }
            }
        })
        judgeCard.addView(label("服务地址"))
        judgeCard.addView(judgeBaseEdit)
        judgeCard.addView(text(
            "OpenRouter Jev：/alpha/decisions；TypeSafe：/v1/systemone；GLM-4.7：/chat/completions；" +
                "自定义服务地址必须以 https:// 开头（仅本机调试地址例外），并填写完整路径。",
            11f, sub
        ))
        judgeCard.addView(label("授权密钥（API Key）"))
        judgeCard.addView(edit(prefs.judgeKey, "sk-...", password = true).also { judgeKeyEdit = it })
        judgeCard.addView(label("模型"))
        judgeCard.addView(judgeModelEdit)
        val judgeResult = resultText()
        judgeCard.addView(cardBtn("测试判断") {
            val base = judgeBaseEdit.text.toString().trim()
            val key = judgeKeyEdit.text.toString().trim()
            val model = judgeModelEdit.text.toString().trim()
            if (key.isBlank()) { judgeResult.text = "请先填密钥"; return@cardBtn }
            judgeResult.text = "测试中…"
            // Provider follows the address when it is still a known preset host,
            // so a stale pill selection cannot send a TypeSafe path to OpenRouter.
            val provider = resolveJudgeProvider(judgeProviderIdx, base)
            if (provider == Prefs.PROVIDER_CUSTOM && base.isBlank()) {
                judgeResult.text = "请填写服务商提供的完整服务地址"; return@cardBtn
            }
            // Custom means we know nothing about the endpoint — guessing a model
            // name here would test something the user never asked for.
            if (provider == Prefs.PROVIDER_CUSTOM && model.isBlank()) {
                judgeResult.text = "请填写服务商支持的模型名称"; return@cardBtn
            }
            val probe = try {
                draftPrefs(SCRATCH_JUDGE) {
                judgeProvider = provider
                judgeBaseUrl = base.ifBlank { defaultJudgeBase(provider) }
                judgeKey = key
                judgeModel = model.ifBlank { defaultJudgeModel(provider) }
                }
            } catch (e: Exception) {
                runCatching { clearScratch(SCRATCH_JUDGE) }
                judgeResult.text = "安全存储暂不可用，未发起模型测试"
                return@cardBtn
            }
            worker.execute {
                val t0 = System.currentTimeMillis()
                val demo = ChatSnapshot("连通测试", listOf(
                    Msg("other", "在吗？"), Msg("me", "在")))
                val a = try {
                    JevClient(probe).judge(demo, prefs.relationship)
                } finally {
                    clearScratch(SCRATCH_JUDGE)
                }
                val ms = System.currentTimeMillis() - t0
                main.post {
                    judgeResult.text = if (a.error != null) "失败（${ms}ms）：${a.error}"
                    else "成功 ${ms}ms · 意图=${a.trueIntent?.choice ?: "?"}" +
                        "（置信 ${pct(a.trueIntent?.confidence)}）"
                }
            }
        })
        judgeCard.addView(judgeResult)
        root.addView(judgeCard)

        // --- 回复接口 ---
        val replyCard = card()
        replyCard.addView(cardTitle("生成回复"))
        replyCard.addView(text(
            "用于生成 3 条回复建议。可与分析服务选择不同的模型；使用不同服务时，请分别填写密钥。",
            12f, sub
        ))

        val replyBaseEdit = edit(prefs.replyBaseUrl, Prefs.DEFAULT_REPLY_BASE)
        val replyModelEdit = edit(prefs.replyModel, Prefs.DEFAULT_REPLY_MODEL)
        val replyIdx = when (prefs.replyBaseUrl.trim().trimEnd('/')) {
            Prefs.DEFAULT_REPLY_BASE -> 0
            Prefs.DEEPSEEK_BASE -> 1
            Prefs.GLM_BASE -> 2
            Prefs.DASHSCOPE_BASE -> 3
            else -> 4
        }
        replyCard.addView(pills(
            listOf("OpenRouter", "DeepSeek Flash", "GLM-5.3-Flash", "通义兼容", "自定义"), replyIdx) { idx ->
            when (idx) {
                0 -> { replyBaseEdit.setText(Prefs.DEFAULT_REPLY_BASE); replyModelEdit.setText(Prefs.DEFAULT_REPLY_MODEL) }
                1 -> { replyBaseEdit.setText(Prefs.DEEPSEEK_BASE); replyModelEdit.setText(Prefs.DEEPSEEK_MODEL) }
                2 -> { replyBaseEdit.setText(Prefs.GLM_BASE); replyModelEdit.setText(Prefs.GLM_MODEL) }
                3 -> { replyBaseEdit.setText(Prefs.DASHSCOPE_BASE); replyModelEdit.setText(Prefs.DASHSCOPE_MODEL) }
            }
        })
        replyCard.addView(label("服务地址"))
        replyCard.addView(replyBaseEdit)
        replyCard.addView(label("授权密钥（API Key）"))
        replyCard.addView(edit(
            prefs.replyKey,
            "留空时，仅同服务商可继承判断密钥",
            password = true
        ).also { replyKeyEdit = it })
        replyCard.addView(label("模型"))
        replyCard.addView(replyModelEdit)
        val replyResult = resultText()
        replyCard.addView(cardBtn("测试回复") {
            val base = replyBaseEdit.text.toString().trim()
            val model = replyModelEdit.text.toString().trim()
            val judgeBaseNow = judgeBaseEdit.text.toString().trim()
            val judgeProviderNow = resolveJudgeProvider(judgeProviderIdx, judgeBaseNow)
            val probe = try {
                draftPrefs(SCRATCH_REPLY) {
                judgeProvider = judgeProviderNow
                judgeBaseUrl = judgeBaseNow.ifBlank { defaultJudgeBase(judgeProviderNow) }
                judgeKey = judgeKeyEdit.text.toString().trim()
                replyBaseUrl = base.ifBlank { Prefs.DEFAULT_REPLY_BASE }
                replyKey = replyKeyEdit.text.toString().trim()
                replyModel = model.ifBlank { Prefs.DEFAULT_REPLY_MODEL }
                }
            } catch (e: Exception) {
                runCatching { clearScratch(SCRATCH_REPLY) }
                replyResult.text = "安全存储暂不可用，未发起模型测试"
                return@cardBtn
            }
            if (probe.effectiveReplyKey().isBlank()) {
                clearScratch(SCRATCH_REPLY)
                replyResult.text = "请填写回复密钥；仅同服务商时可继承判断密钥"
                return@cardBtn
            }
            replyResult.text = "测试中…"
            worker.execute {
                val t0 = System.currentTimeMillis()
                var err: String? = null
                val out = try {
                    ReplyClient(probe).ping()
                } catch (e: Exception) {
                    err = e.message
                    ""
                } finally {
                    clearScratch(SCRATCH_REPLY)
                }
                val ms = System.currentTimeMillis() - t0
                main.post {
                    replyResult.text = if (err != null) "失败（${ms}ms）：$err"
                    else "成功 ${ms}ms · 返回：${out.replace("\n", " ").take(60)}"
                }
            }
        })
        replyCard.addView(replyResult)

        // A persistent global preference, independent of copied chat history.
        // An empty field always uses the natural human-chat default.
        replyCard.addView(label("我的回复风格（可选）"))
        replyCard.addView(text(
            "留空时使用自然、直接的日常聊天语气。" +
                "填写后优先按你的要求表达；已导入的本人消息仅作参考。",
            12f, sub
        ))
        val stylePromptEdit = edit(
            prefs.replyStylePrompt,
            "例如：像和朋友聊天，短句、直接，偶尔开玩笑，少用客套话"
        ).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 4
            maxLines = 8
            filters = arrayOf(InputFilter.LengthFilter(ReplyStylePolicy.MAX_PROMPT_CHARS))
            setSaveEnabled(false)
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
        }
        replyCard.addView(stylePromptEdit)
        replyCard.addView(text(
            "最多 500 字，保存在这部手机上。生成回复时，会交给你选择的分析服务和回复服务，可能收费。" +
                "已开启的自动分析也按同样规则处理；更改风格后，需要重新生成才能看到新效果。",
            11f, sub
        ))
        val styleResult = resultText()
        replyCard.addView(cardBtn("保存回复风格") {
            prefs.replyStylePrompt = stylePromptEdit.text.toString()
            styleResult.text = if (prefs.replyStylePrompt.isBlank()) {
                "已保存：普通聊天语气"
            } else "回复风格已保存"
        })
        replyCard.addView(cardBtn("恢复默认语气") {
            stylePromptEdit.setText("")
            prefs.replyStylePrompt = ""
            styleResult.text = "已恢复自然聊天语气"
        })
        replyCard.addView(styleResult)
        root.addView(replyCard)

        // --- 视觉接口 ---
        val visionCard = card()
        visionCard.addView(cardTitle("图片识字服务（可选）"))
        visionCard.addView(text(
            "默认在手机上识别文字，不上传图片。改选云端识别后，才会上传你选择的聊天图片区域，可能收费。",
            12f, sub
        ))

        val visionBaseEdit = edit(prefs.visionBaseUrl, Prefs.DEFAULT_VISION_BASE)
        val visionModelEdit = edit(prefs.visionModel, Prefs.DEFAULT_VISION_MODEL)
        val visionIdx = when (prefs.visionBaseUrl.trim().trimEnd('/')) {
            Prefs.DEFAULT_VISION_BASE -> 0
            Prefs.DASHSCOPE_BASE -> 1
            else -> 2
        }
        visionCard.addView(pills(
            listOf("OpenRouter", "通义兼容", "自定义"), visionIdx) { idx ->
            when (idx) {
                0 -> { visionBaseEdit.setText(Prefs.DEFAULT_VISION_BASE); visionModelEdit.setText(Prefs.DEFAULT_VISION_MODEL) }
                1 -> { visionBaseEdit.setText(Prefs.DASHSCOPE_BASE); visionModelEdit.setText(Prefs.DASHSCOPE_VISION_MODEL) }
            }
        })
        visionCard.addView(label("服务地址"))
        visionCard.addView(visionBaseEdit)
        visionCard.addView(label("授权密钥（API Key）"))
        visionCard.addView(edit(
            prefs.visionKey,
            "留空时，仅同服务商可继承回复/判断密钥",
            password = true
        ).also { visionKeyEdit = it })
        visionCard.addView(label("模型"))
        visionCard.addView(visionModelEdit)
        val visionResult = resultText()
        visionCard.addView(cardBtn("测试视觉") {
            val visionBase = visionBaseEdit.text.toString().trim()
            val visionModelNow = visionModelEdit.text.toString().trim().ifBlank { Prefs.DEFAULT_VISION_MODEL }
            if (!VisionClient.supportsVision(visionBase.ifBlank { Prefs.DEFAULT_VISION_BASE }, visionModelNow)) {
                visionResult.text = GUARD_NO_VISION
                return@cardBtn
            }
            val judgeBaseNow = judgeBaseEdit.text.toString().trim()
            val judgeProviderNow = resolveJudgeProvider(judgeProviderIdx, judgeBaseNow)
            val probe = try {
                draftPrefs(SCRATCH_VISION) {
                judgeProvider = judgeProviderNow
                judgeBaseUrl = judgeBaseNow.ifBlank { defaultJudgeBase(judgeProviderNow) }
                judgeKey = judgeKeyEdit.text.toString().trim()
                replyBaseUrl = replyBaseEdit.text.toString().trim().ifBlank { Prefs.DEFAULT_REPLY_BASE }
                replyKey = replyKeyEdit.text.toString().trim()
                visionBaseUrl = visionBase
                visionKey = visionKeyEdit.text.toString().trim()
                visionModel = visionModelNow
                }
            } catch (e: Exception) {
                runCatching { clearScratch(SCRATCH_VISION) }
                visionResult.text = "安全存储暂不可用，未发起模型测试"
                return@cardBtn
            }
            if (probe.effectiveVisionKey().isBlank()) {
                clearScratch(SCRATCH_VISION)
                visionResult.text = "请填写视觉密钥；仅同服务商时可继承其它密钥"
                return@cardBtn
            }
            visionResult.text = "测试中…"
            worker.execute {
                val t0 = System.currentTimeMillis()
                var err: String? = null
                val out = try {
                    VisionClient(probe).ask(whitePixelJpegB64(), "这张图是什么颜色？只回答颜色。")
                } catch (e: Exception) {
                    err = e.message
                    ""
                } finally {
                    clearScratch(SCRATCH_VISION)
                }
                val ms = System.currentTimeMillis() - t0
                main.post {
                    visionResult.text = if (err != null) "失败（${ms}ms）：$err"
                    else "成功 ${ms}ms · 返回：${out.replace("\n", " ").take(60)}"
                }
            }
        })
        visionCard.addView(visionResult)
        root.addView(visionCard)

        // =================== 分析 ===================
        root.addView(section("分析"))
        val card2 = card()
        card2.addView(label("默认聊天关系（可留空）"))
        val relEdit = edit(prefs.relationship, "例如：同事、客户、家人；联系人可单独设置")
        card2.addView(relEdit)
        card2.addView(text(
            "已保存联系人的关系优先。复制微信消息时，助手无法确认当前联系人，请核对聊天对象，不会仅凭昵称猜测关系。",
            11f, sub
        ))
        card2.addView(label("允许分析的会话名称（每行一个，留空不限）"))
        val wlEdit = edit(prefs.whitelist.joinToString("\n"), "留空则对所有会话生效").apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE; minLines = 2
        }
        card2.addView(wlEdit)
        val autoRow = toggleRow("其他适配应用收到消息时自动分析", prefs.autoAnalyze)
        card2.addView(autoRow)
        card2.addView(text("默认关闭。微信通知的自动分析由下方独立开关控制。", 11f, sub))

        val wechatNotificationAutoRow = toggleRow(
            "微信通知到达即自动分析（可选）",
            prefs.wechatNotificationAutoAnalyze
        )
        card2.addView(wechatNotificationAutoRow)
        card2.addView(text(
            "默认关闭。开启后，通知中显示的消息会自动交给所选模型服务商分析，可能收费；不等于完整聊天记录。关闭后由你点击才分析。",
            11f, sub
        ))

        val wechatAutoCopyRow = toggleRow(
            "自动复制推荐回复",
            prefs.wechatAutoCopyTopReply
        )
        card2.addView(wechatAutoCopyRow)
        card2.addView(text(
            "默认关闭。开启后会将推荐回复复制到剪贴板，并收起面板；不会自动填入或发送。请粘贴后检查内容。",
            11f, sub
        ))

        // --- OCR 兜底 ---
        var ocrEngineSelected = prefs.ocrEngine
        card2.addView(label("图片识字方式"))
        card2.addView(pills(
            listOf("手机识别（默认）", "云端识别（上传所选图片区域）"),
            if (ocrEngineSelected == Prefs.OCR_VISION) 1 else 0
        ) { idx ->
            ocrEngineSelected = if (idx == 1) Prefs.OCR_VISION else Prefs.OCR_MLKIT
        })
        card2.addView(text(
            "手机识别无需额外密钥，不上传截图。云端识别会把所选图片区域发送给配置的服务商，可能收费。截图前请确认画面中没有不想提交的内容。",
            11f, sub
        ))

        val ocrFallbackRow = toggleRow("其他应用无法读文字时改用截图识字", prefs.ocrFallback)
        card2.addView(ocrFallbackRow)
        card2.addView(text(
            "默认关闭。微信不会自动截图；需要时可在悬浮球菜单中主动选择截图识字。",
            11f, sub
        ))
        val ocrAutoRow = toggleRow("识别图片文字后自动分析", prefs.ocrAutoAnalyze)
        card2.addView(ocrAutoRow)
        card2.addView(text("仅用于其他应用的自动识字；微信截图仍由你主动发起。", 11f, sub))

        // --- 知识库 / 关联上下文（D 阶段） ---
        val ctxRow = toggleRow("保存长期聊天记录（仅本机）", prefs.contextEnabled)
        card2.addView(ctxRow)
        card2.addView(text("关闭后不保存新的聊天记录；已有记录、联系人和备注不会自动删除。临时补充对话仍可使用。", 11f, sub))
        val autoSummaryRow = toggleRow(
            "自动整理联系人摘要（可能收费）",
            prefs.autoSummary
        )
        card2.addView(autoSummaryRow)
        card2.addView(text(
            "默认关闭。需先保存联系人并开启长期记录；积累一定消息后，将相关记录发送给回复服务商整理摘要，可能收费。",
            11f, sub
        ))
        card2.addView(label("每次参考多少条已保存记录（0–100）"))
        val ctxCountEdit = edit(prefs.contextHistoryCount.toString(), "30").apply {
            inputType = InputType.TYPE_CLASS_NUMBER
        }
        card2.addView(ctxCountEdit)
        card2.addView(cardBtn("联系人与备注") {
            startActivity(android.content.Intent(this, KnowledgeActivity::class.java))
        })
        val kbResult = resultText()
        card2.addView(cardBtn("清空联系人、备注和记录") {
            val c = KbStore.get(this).counts()
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("清空联系人、备注和记录")
                .setMessage("将删除 ${c.notes} 条笔记、${c.contacts} 个联系人、${c.logLines} 条聊天历史。" +
                    "模型密钥和允许分析的会话设置不受影响。删除后无法恢复。")
                .setPositiveButton("清空") { _, _ ->
                    KbStore.get(this).clearAll()
                    kbResult.text = "联系人、备注和记录已清空"
                }
                .setNegativeButton("取消", null)
                .show()
        })
        // Deliberately low-key: a developer aid, not a user feature.
        card2.addView(text("功能自检", 12f, sub).apply {
            setPadding(dp(2), dp(12), dp(8), dp(2))
            setOnClickListener {
                kbResult.text = "自检中…"
                worker.execute {
                    val out = try {
                        val kb = KbSelfCheck.run(this@SettingsActivity)
                        val security = SecuritySelfCheck.run(this@SettingsActivity)
                        val visionParser = if (VisionDialogParser.selfCheck()) {
                            "视觉 OCR 解析自检通过。"
                        } else {
                            "视觉 OCR 解析自检失败。"
                        }
                        kb + "\n" + security + "\n" + visionParser
                    } catch (e: Exception) {
                        "自检异常：${e.javaClass.simpleName} ${e.message ?: ""}"
                    }
                    main.post { kbResult.text = out }
                }
            }
        })
        card2.addView(kbResult)
        card2.addView(cardBtn("问题诊断") {
            startActivity(android.content.Intent(this, DiagnosticsActivity::class.java))
        })
        root.addView(card2)

        // =================== 外观 ===================
        root.addView(section("外观"))
        val card3 = card()
        val opacityLabel = label("悬浮窗不透明度：${prefs.overlayOpacity}%")
        card3.addView(opacityLabel)
        card3.addView(text("越低越透，越能看清下面的聊天", 12f, sub))
        val seek = SeekBar(this).apply {
            max = 40; progress = prefs.overlayOpacity - 60  // 60..100
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, u: Boolean) {
                    opacityLabel.text = "悬浮窗不透明度：${p + 60}%"
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
        card3.addView(seek)
        root.addView(card3)

        // =================== 电脑会话桥（显式授权） ===================
        val showLegacyDesktopSettings = prefs.desktopBridgeEnabled ||
            prefs.desktopBridgeEndpoint.isNotBlank() || prefs.desktopBridgeAccountRef.isNotBlank() ||
            prefs.desktopBridgeToken.isNotBlank()
        if (showLegacyDesktopSettings) root.addView(section("已有电脑连接"))
        val desktopCard = card()
        desktopCard.addView(cardTitle("管理已有电脑连接"))
        desktopCard.addView(text(
            "手机可独立使用。此入口仅用于管理你之前配置的电脑连接；关闭后不再同步电脑消息。",
            12f, sub
        ))
        val desktopBridgeUrlEdit = edit(prefs.desktopBridgeEndpoint, "已配对电脑提供的连接地址")
        val desktopAccountEdit = edit(prefs.desktopBridgeAccountRef, "填写之前配对时使用的账号标识")
        val desktopTokenEdit = edit(prefs.desktopBridgeToken, "配对密钥（至少 32 位）", password = true)
        desktopCard.addView(label("电脑连接地址"))
        desktopCard.addView(desktopBridgeUrlEdit)
        desktopCard.addView(label("电脑账号标识"))
        desktopCard.addView(desktopAccountEdit)
        desktopCard.addView(label("配对密钥（本机加密保存）"))
        desktopCard.addView(desktopTokenEdit)
        val desktopBridgeEnabledRow = toggleRow("接收已配对电脑的消息", prefs.desktopBridgeEnabled)
        desktopCard.addView(desktopBridgeEnabledRow)
        val desktopAutoAnalyzeRow = toggleRow("自动生成回复（可能收费）", prefs.desktopAutoAnalyze)
        desktopCard.addView(desktopAutoAnalyzeRow)
        desktopCard.addView(text(
            "自动分析将把对应会话的文本交给当前配置的模型服务，可能产生费用。"+
                "接收消息与自动分析分别控制；回复仍由你确认并发送。",
            11f, sub
        ))
        if (showLegacyDesktopSettings) root.addView(desktopCard)

        // =================== 保存 ===================
        root.addView(primaryBtn("保存全部设置") {
            val desktopRequested = (desktopBridgeEnabledRow.tag as? Boolean) ?: false
            val desktopUrl = desktopBridgeUrlEdit.text.toString().trim()
            val desktopAccount = desktopAccountEdit.text.toString().trim()
            val desktopToken = desktopTokenEdit.text.toString().trim()
            if (desktopRequested && !DesktopBridgeClient.isEndpointAllowed(desktopUrl)) {
                Toast.makeText(this, "请检查电脑连接地址，需使用加密连接或本机调试连接", Toast.LENGTH_LONG).show()
                return@primaryBtn
            }
            if (desktopRequested && (desktopAccount.isBlank() || desktopToken.length < 32)) {
                Toast.makeText(this, "请填写电脑账号标识和至少 32 位的配对密钥", Toast.LENGTH_LONG).show()
                return@primaryBtn
            }

            // Address wins over the pill: a preset HOST in the box means that
            // preset's provider (and so its path), whatever the pill last said.
            val judgeBaseTyped = judgeBaseEdit.text.toString().trim()
            val judgeProv = resolveJudgeProvider(judgeProviderIdx, judgeBaseTyped)
            val judgeModelTyped = judgeModelEdit.text.toString().trim()
            // Persist credentials first. A Keystore failure must not leave new
            // provider/endpoint settings paired with an old key.
            try {
                prefs.judgeKey = judgeKeyEdit.text.toString()
                prefs.replyKey = replyKeyEdit.text.toString()
                prefs.visionKey = visionKeyEdit.text.toString()
                prefs.desktopBridgeToken = desktopToken
            } catch (e: Exception) {
                Toast.makeText(
                    this,
                    "密钥未全部安全保存，其他设置未更新。请检查手机安全存储后重试。",
                    Toast.LENGTH_LONG
                ).show()
                return@primaryBtn
            }
            prefs.judgeProvider = judgeProv
            // Blank falls back to THIS provider's preset — never OpenRouter's by
            // default. Custom is left exactly as typed (blank included): guessing
            // a URL for it would silently point somewhere the user did not choose.
            prefs.judgeBaseUrl = when {
                judgeBaseTyped.isNotBlank() -> judgeBaseTyped
                judgeProv == Prefs.PROVIDER_CUSTOM -> ""
                else -> defaultJudgeBase(judgeProv)
            }
            prefs.judgeModel = when {
                judgeModelTyped.isNotBlank() -> judgeModelTyped
                judgeProv == Prefs.PROVIDER_CUSTOM -> ""
                else -> defaultJudgeModel(judgeProv)
            }

            prefs.replyBaseUrl = replyBaseEdit.text.toString().trim().ifBlank { Prefs.DEFAULT_REPLY_BASE }
            prefs.replyModel = replyModelEdit.text.toString().trim().ifBlank { Prefs.DEFAULT_REPLY_MODEL }
            prefs.replyStylePrompt = stylePromptEdit.text.toString()

            prefs.visionBaseUrl = visionBaseEdit.text.toString().trim()
            prefs.visionModel = visionModelEdit.text.toString().trim().ifBlank { Prefs.DEFAULT_VISION_MODEL }

            prefs.relationship = relEdit.text.toString()   // blank stays blank, on purpose
            prefs.whitelist = wlEdit.text.toString().split("\n")
                .map { it.trim() }.filter { it.isNotEmpty() }.toSet()
            prefs.autoAnalyze = (autoRow.tag as? Boolean) ?: false
            prefs.wechatNotificationAutoAnalyze =
                (wechatNotificationAutoRow.tag as? Boolean) ?: false
            prefs.wechatAutoCopyTopReply =
                (wechatAutoCopyRow.tag as? Boolean) ?: false
            prefs.ocrEngine = ocrEngineSelected
            prefs.ocrFallback = (ocrFallbackRow.tag as? Boolean) ?: false
            prefs.ocrAutoAnalyze = (ocrAutoRow.tag as? Boolean) ?: false
            prefs.contextEnabled = (ctxRow.tag as? Boolean) ?: false
            prefs.autoSummary = (autoSummaryRow.tag as? Boolean) ?: false
            prefs.contextHistoryCount =
                ctxCountEdit.text.toString().trim().toIntOrNull()?.coerceIn(0, 100) ?: 30
            prefs.overlayOpacity = seek.progress + 60
            prefs.desktopBridgeEndpoint = desktopUrl
            prefs.desktopBridgeAccountRef = desktopAccount
            prefs.desktopAutoAnalyze = desktopRequested &&
                ((desktopAutoAnalyzeRow.tag as? Boolean) ?: false)
            prefs.desktopBridgeEnabled = desktopRequested

            val savedMessage = if (
                prefs.ocrEngine == Prefs.OCR_VISION &&
                prefs.effectiveVisionKey().isBlank()
            ) {
                "已保存；云端识字尚未配置密钥，当前使用手机识别"
            } else {
                "已保存"
            }
            Toast.makeText(this, savedMessage, Toast.LENGTH_SHORT).show()
            if (prefs.enabled) KeepAliveService.start(this)
        })

        // Public privacy policy remains readable offline, including on devices
        // that cannot access GitHub or a browser. No extra permissions or network.
        root.addView(section("关于"))
        val privacyCard = card()
        privacyCard.addView(cardTitle("隐私政策与联系"))
        privacyCard.addView(text("发行品牌：小书童 · 联系邮箱：xiaoshutong2026@foxmail.com", 12f, sub))
        privacyCard.addView(cardBtn("查看隐私政策") {
            val policy = runCatching {
                assets.open("privacy_policy_zhiyan_cn.md")
                    .bufferedReader(Charsets.UTF_8).use { it.readText() }
            }.getOrElse {
                "隐私政策暂不可读取，请联系 xiaoshutong2026@foxmail.com。"
            }
            val readable = policy.replace(Regex("(?m)^#{1,3} +"), "")
                .replace("**", "").replace("`", "")
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("小书童·知言隐私政策")
                .setMessage(readable)
                .setPositiveButton("关闭", null)
                .show()
        })
        root.addView(privacyCard)
        val licenseCard = card()
        licenseCard.addView(text(
            "本应用基于 Jev Chat Assistant 开源项目二次开发。原项目为 MIT 许可。",
            12f, sub
        ))
        licenseCard.addView(cardBtn("查看开源许可") {
            val notice = runCatching {
                assets.open("NOTICE").bufferedReader(Charsets.UTF_8).use { it.readText() }
            }.getOrElse { "NOTICE 文件暂不可读取，请查看应用发布页面。" }
            val license = runCatching {
                assets.open("LICENSE").bufferedReader(Charsets.UTF_8).use { it.readText() }
            }.getOrElse { "LICENSE 文件暂不可读取，请查看应用发布页面。" }
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("开源许可与来源")
                .setMessage(notice + "\n\n" + license)
                .setPositiveButton("关闭", null)
                .show()
        })
        root.addView(licenseCard)

        setContentView(scroll)
    }

    // Held as fields because several test buttons read each other's key box.
    private lateinit var judgeKeyEdit: EditText
    private lateinit var replyKeyEdit: EditText
    private lateinit var visionKeyEdit: EditText

    private fun providerOf(idx: Int) = when (idx) {
        1 -> Prefs.PROVIDER_TYPESAFE
        2 -> Prefs.PROVIDER_GLM47
        3 -> Prefs.PROVIDER_CUSTOM
        else -> Prefs.PROVIDER_OPENROUTER
    }

    /**
     * The provider actually implied by what is in the address box. A preset host
     * carries its own path (`/alpha/decisions`, `/v1/systemone`), so leaving that
     * host in the box while the pill says something else would POST the wrong
     * path — or, for custom, the bare API root.
     */
    private fun resolveJudgeProvider(idx: Int, base: String): String =
        when (base.trim().trimEnd('/')) {
            Prefs.DEFAULT_JUDGE_BASE_OPENROUTER -> Prefs.PROVIDER_OPENROUTER
            Prefs.DEFAULT_JUDGE_BASE_TYPESAFE -> Prefs.PROVIDER_TYPESAFE
            Prefs.DEFAULT_JUDGE_BASE_GLM47 -> Prefs.PROVIDER_GLM47
            else -> providerOf(idx)
        }

    /** The full endpoint a preset host would have been expanded to. */
    private fun expandJudgeUrl(base: String): String = when (base.trim().trimEnd('/')) {
        Prefs.DEFAULT_JUDGE_BASE_OPENROUTER -> Prefs.DEFAULT_JUDGE_BASE_OPENROUTER + "/alpha/decisions"
        Prefs.DEFAULT_JUDGE_BASE_TYPESAFE -> Prefs.DEFAULT_JUDGE_BASE_TYPESAFE + "/v1/systemone"
        else -> base.trim()
    }

    private fun defaultJudgeBase(provider: String): String = when (provider) {
        Prefs.PROVIDER_TYPESAFE -> Prefs.DEFAULT_JUDGE_BASE_TYPESAFE
        Prefs.PROVIDER_GLM47 -> Prefs.DEFAULT_JUDGE_BASE_GLM47
        else -> Prefs.DEFAULT_JUDGE_BASE_OPENROUTER
    }

    private fun defaultJudgeModel(provider: String): String = when (provider) {
        Prefs.PROVIDER_TYPESAFE -> Prefs.DEFAULT_JUDGE_MODEL_TYPESAFE
        Prefs.PROVIDER_GLM47 -> Prefs.DEFAULT_JUDGE_MODEL_GLM47
        else -> Prefs.DEFAULT_JUDGE_MODEL_OPENROUTER
    }

    /**
     * A throwaway [Prefs] view carrying exactly what is in the boxes right now,
     * so a test button probes the typed values rather than the saved ones. Each
     * button gets its OWN scratch file — they used to share one and clear it out
     * from under each other when two tests overlapped. The real config is never
     * touched either way.
     */
    private fun draftPrefs(scratchName: String, fill: Prefs.() -> Unit): Prefs {
        clearScratch(scratchName)
        return Prefs(this, scratchName).apply(fill)
    }

    private fun clearScratch(scratchName: String) {
        getSharedPreferences(scratchName, MODE_PRIVATE).edit().clear().commit()
    }

    /** 1x1 white JPEG for the vision smoke test, via the real encoder path. */
    private fun whitePixelJpegB64(): String {
        val bmp = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(Guofeng.CARD)
        return VisionClient.encodeJpeg(bmp)
    }

    private fun pct(d: Double?): String =
        if (d == null) "?" else "${(d * 100).roundToInt()}%"

    /** Horizontal selectable pills; calls [onPick] with the chosen index. */
    private fun pills(options: List<String>, initial: Int, onPick: (Int) -> Unit): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        val views = ArrayList<TextView>()
        options.forEachIndexed { i, opt ->
            val pill = TextView(this).apply {
                text = opt; textSize = 12.5f; gravity = Gravity.CENTER
                setPadding(dp(13), dp(7), dp(13), dp(7))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT).apply { rightMargin = dp(7) }
            }
            views.add(pill)
            pill.setOnClickListener {
                views.forEachIndexed { j, v -> paintPill(v, j == i) }
                onPick(i)
            }
            row.addView(pill)
        }
        views.forEachIndexed { j, v -> paintPill(v, j == initial) }
        val scroller = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(row)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) }
        }
        return scroller
    }

    private fun paintPill(v: TextView, on: Boolean) {
        v.setTextColor(if (on) Guofeng.CARD else sub)
        v.typeface = Guofeng.sans(on)
        v.background = round(9, if (on) accent else pillOff)
    }

    private fun toggleRow(labelText: String, initial: Boolean): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(12), 0, dp(2)); tag = initial
        }
        val lab = text(labelText, 14f, ink).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val sw = TextView(this).apply {
            text = if (initial) "开" else "关"; textSize = 13f; gravity = Gravity.CENTER
            typeface = Guofeng.sans(true)
            setTextColor(if (initial) Guofeng.CARD else sub)
            background = round(10, if (initial) accent else Guofeng.PAPER_DEEP)
            setPadding(dp(18), dp(6), dp(18), dp(6))
        }
        sw.setOnClickListener {
            val now = !((row.tag as? Boolean) ?: true); row.tag = now
            sw.text = if (now) "开" else "关"
            sw.setTextColor(if (now) Guofeng.CARD else sub)
            sw.background = round(10, if (now) accent else Guofeng.PAPER_DEEP)
        }
        row.addView(lab); row.addView(sw)
        return row
    }

    // atoms
    private fun header(t: String) = text(t, 27f, Guofeng.JADE_DEEP, bold = true, serif = true)
        .apply { setPadding(0, 0, 0, dp(2)) }
    private fun section(t: String) = text(t, 16f, Guofeng.JADE_DEEP, bold = true, serif = true)
        .apply { setPadding(dp(2), dp(18), 0, dp(5)) }
    private fun label(t: String) = text(t, 13f, ink, bold = true).apply {
        setPadding(0, dp(12), 0, dp(4))
    }
    private fun cardTitle(t: String) = text(t, 16f, Guofeng.INK, bold = true, serif = true).apply {
        setPadding(0, dp(10), 0, dp(4))
    }
    private fun resultText() = text("", 12.5f, sub).apply { setPadding(0, dp(10), 0, dp(2)) }

    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = Guofeng.round(this@SettingsActivity, 17, Guofeng.CARD, Guofeng.BORDER)
        elevation = dp(1).toFloat()
        setPadding(dp(14), dp(4), dp(14), dp(14))
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = dp(10) }
    }

    private fun edit(value: String, hint: String, password: Boolean = false) = EditText(this).apply {
        setText(value); this.hint = hint; textSize = 14f; setTextColor(ink)
        setHintTextColor(Guofeng.INK_FAINT)
        background = Guofeng.round(this@SettingsActivity, 10, Guofeng.CARD_SOFT, Guofeng.BORDER)
        setPadding(dp(10), dp(10), dp(10), dp(10))
        // Masked, not VISIBLE_PASSWORD: an API key should not sit in plain sight.
        if (password) inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(2) }
    }

    private fun text(
        t: String,
        size: Float,
        color: Int,
        bold: Boolean = false,
        serif: Boolean = false
    ) = TextView(this).apply {
        text = t
        textSize = size
        setTextColor(color)
        typeface = if (serif) Guofeng.serif(bold) else Guofeng.sans(bold)
    }

    private fun primaryBtn(label: String, onClick: () -> Unit) = TextView(this).apply {
        text = label; textSize = 15f; gravity = Gravity.CENTER; typeface = Guofeng.serif(true)
        setTextColor(Guofeng.CARD); background = round(12, accent)
        setPadding(dp(16), dp(13), dp(16), dp(13))
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(18) }
        setOnClickListener { onClick() }
    }

    /** Outlined button sized for inside a card. */
    private fun cardBtn(label: String, onClick: () -> Unit) = TextView(this).apply {
        text = label; textSize = 14f; gravity = Gravity.CENTER; typeface = Guofeng.sans(true)
        setTextColor(accent); background = round(10, Guofeng.CARD, stroke = true)
        setPadding(dp(14), dp(10), dp(14), dp(10))
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(14) }
        setOnClickListener { onClick() }
    }

    private fun round(radiusDp: Int, color: Int, stroke: Boolean = false) =
        Guofeng.round(
            this,
            radiusDp,
            color,
            if (stroke) Guofeng.BORDER_JADE else null
        )

    override fun onDestroy() { super.onDestroy(); worker.shutdownNow() }

    companion object {
        private const val TAG = "JEVASSIST"

        /** DeepSeek's official API has no vision model; say so instead of a 400. */
        private const val GUARD_NO_VISION =
            "DeepSeek 官方视觉仅支持 deepseek-flash；请改模型名或换 OpenRouter / 通义兼容"

        /** One scratch prefs file per test button; never the real config. */
        private const val SCRATCH_JUDGE = "jev_probe_scratch_judge"
        private const val SCRATCH_REPLY = "jev_probe_scratch_reply"
        private const val SCRATCH_VISION = "jev_probe_scratch_vision"

    }
}
