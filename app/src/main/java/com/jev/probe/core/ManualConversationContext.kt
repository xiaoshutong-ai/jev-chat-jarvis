package com.jev.probe.core

/**
 * Explicitly labelled dialogue pasted by the user. Never infer speakers from
 * chat layout, notification order, clipboard metadata, or AI output.
 *
 * This is not an Android/WeChat source. It parses data that a user explicitly
 * selected and confirmed in JEV's own UI.
 */
object ManualTranscriptParser {
    const val MAX_MESSAGES = 20
    const val MAX_TEXT_CHARS = 15_000
    private const val MAX_MESSAGE_CHARS = 500
    private val line = Regex("^(我|对方)[：:]\\s*(.+)$")

    data class Parsed(
        val messages: List<Msg>,
        val ownExamples: List<String>,
        val importedCount: Int
    )

    fun parse(raw: String): Parsed {
        if (raw.isBlank() || raw.length > MAX_TEXT_CHARS) {
            throw IllegalArgumentException("请输入不超过 15000 字的文本对话")
        }
        val rows = raw.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (rows.size !in 2..100) {
            throw IllegalArgumentException("每行一条消息，至少 2 条，最多 100 条")
        }
        val messages = rows.mapIndexed { i, row ->
            val match = line.matchEntire(row)
                ?: throw IllegalArgumentException("第 ${i + 1} 行请以“我：”或“对方：”开头")
            val text = match.groupValues[2].trim()
            if (text.isBlank() || text.length > MAX_MESSAGE_CHARS) {
                throw IllegalArgumentException("第 ${i + 1} 行内容必须为 1–500 字")
            }
            Msg(if (match.groupValues[1] == "我") "me" else "other", text)
        }.takeLast(MAX_MESSAGES)
        if (messages.last().side != "other") {
            throw IllegalArgumentException("最后一条必须是“对方：”消息，作为后续复制核对依据")
        }
        val examples = messages.asSequence()
            .filter { it.side == "me" }
            .map { it.text }
            .filter { it.length in 6..120 }
            .distinct()
            .toList()
            .takeLast(6)
        return Parsed(messages, examples, rows.size)
    }
}

/**
 * User-authorized, process-memory-only context for one manually selected
 * conversation. No disk, network, Accessibility or background clipboard read.
 *
 * A match requires the latest *incoming* imported message to exactly equal
 * the text the user separately copies into the foreground JEV clipboard
 * import Activity. This is not a verified WeChat contact identity.
 */
object ManualConversationContext {
    private const val TTL_MS = 60 * 60_000L

    data class Match(val snapshot: ChatSnapshot, val revision: Long)
    data class Status(
        val active: Boolean,
        val messages: Int = 0,
        val styleSamples: Int = 0,
        val expiresAtMs: Long = 0L
    )
    private data class Entry(
        val messages: List<Msg>,
        val styleSamples: List<String>,
        val expiresAtMs: Long,
        val revision: Long
    )

    private var entry: Entry? = null
    private var revision = 0L

    @Synchronized
    fun import(raw: String, imitateStyle: Boolean, nowMs: Long): Status {
        require(nowMs > 0L) { "设备时间不正确" }
        val parsed = ManualTranscriptParser.parse(raw)
        revision += 1
        entry = Entry(
            parsed.messages.toList(),
            if (imitateStyle && parsed.ownExamples.size >= 3) parsed.ownExamples else emptyList(),
            nowMs + TTL_MS,
            revision
        )
        return status(nowMs)
    }

    @Synchronized
    fun matchCopiedIncoming(text: String, nowMs: Long): Match? {
        val current = activeEntry(nowMs) ?: return null
        if (current.messages.last().text != text.trim()) return null
        return Match(
            ChatSnapshot(
                title = null,
                messages = current.messages.toList(),
                note = "用户主动导入最近 ${current.messages.size} 条文字；方向由用户标注，消息原时间及微信联系人身份未核验。仅用于本次主动分析。",
                styleExamples = current.styleSamples.toList()
            ), current.revision
        )
    }

    @Synchronized
    fun status(nowMs: Long): Status {
        val current = activeEntry(nowMs) ?: return Status(active = false)
        return Status(true, current.messages.size, current.styleSamples.size, current.expiresAtMs)
    }

    @Synchronized
    fun clear() {
        entry = null
        revision += 1
    }

    private fun activeEntry(nowMs: Long): Entry? {
        val current = entry ?: return null
        if (nowMs <= 0 || nowMs >= current.expiresAtMs) {
            clear()
            return null
        }
        return current
    }
}
