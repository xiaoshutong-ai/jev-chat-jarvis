package com.jev.probe.session

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.net.URL
import java.nio.charset.StandardCharsets

/**
 * Read-only phone-side consumer of desktop_bridge/relay.py.
 *
 * No credentials are embedded or persisted here; configuration must come from
 * an explicit, user-approved pairing flow and a protected secret store.
 * Caller must run [pollOnce] on a background thread (never the Android UI).
 */
class DesktopBridgeClient(private val config: Config) {
    data class Config(
        val endpoint: String,
        val bearerToken: String,
        val pairedAccountRef: String,
        val androidProfileScope: String
    )

    data class PollBatch(
        val generation: String,
        val cursor: Long,
        val gap: Boolean,
        val hasMore: Boolean,
        val messages: List<DesktopMessageIndex.Incoming>
    )

    companion object {
        private const val MAX_RESPONSE_BYTES = 256 * 1024
        private const val MAX_ITEMS = 100

        fun isEndpointAllowed(endpoint: String): Boolean = runCatching {
            val uri = URI(endpoint.trim())
            val local = uri.host == "127.0.0.1" || uri.host == "localhost"
            (uri.scheme == "https" || (uri.scheme == "http" && local)) &&
                uri.rawUserInfo == null && uri.rawFragment == null && uri.rawQuery == null &&
                uri.host != null && uri.host.isNotBlank() && uri.port <= 65535
        }.getOrDefault(false)

        fun parseResponse(body: String, expectedAccountRef: String): PollBatch {
            val json = JSONObject(body)
            require(json.optInt("version", -1) == 1) { "unsupported relay version" }
            require((json.opt("account_ref") as? String) == expectedAccountRef) { "account mismatch" }
            val generation = json.opt("generation") as? String ?: throw IllegalArgumentException("missing generation")
            require(generation.length == 16 && generation.all { it.isDigit() || it in 'a'..'f' }) {
                "invalid relay generation"
            }
            val cursor = numericLong(json.opt("cursor")) ?: throw IllegalArgumentException("invalid cursor")
            require(cursor >= 0L) { "negative cursor" }
            val gap = json.opt("gap") as? Boolean ?: throw IllegalArgumentException("missing gap flag")
            val hasMore = json.opt("has_more") as? Boolean ?: throw IllegalArgumentException("missing has_more flag")
            val array = json.optJSONArray("items") ?: throw IllegalArgumentException("missing items")
            require(array.length() <= MAX_ITEMS) { "too many messages" }
            val messages = ArrayList<DesktopMessageIndex.Incoming>(array.length())
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: throw IllegalArgumentException("invalid message type")
                val account = item.opt("account_ref") as? String ?: throw IllegalArgumentException("missing source account")
                require(account == expectedAccountRef) { "item account mismatch" }
                val session = item.opt("session_id") as? String ?: throw IllegalArgumentException("missing session")
                val id = item.opt("message_id") as? String ?: throw IllegalArgumentException("missing message ID")
                val name = item.opt("display_name") as? String ?: throw IllegalArgumentException("missing display name")
                val text = item.opt("text") as? String ?: throw IllegalArgumentException("missing message text")
                val timestamp = numericLong(item.opt("time_ms")) ?: throw IllegalArgumentException("invalid timestamp")
                val direction = when (item.opt("direction") as? String) {
                    "me" -> DesktopMessageIndex.Direction.ME
                    "other" -> DesktopMessageIndex.Direction.OTHER
                    else -> throw IllegalArgumentException("invalid direction")
                }
                require(session.isNotBlank() && session.length <= 200 &&
                    id.isNotBlank() && id.length <= 200 && name.length <= 120 &&
                    text.isNotBlank() && text.length <= 4000 && timestamp > 0L) {
                    "invalid message dimensions"
                }
                messages += DesktopMessageIndex.Incoming(
                    accountRef = account, sessionId = session, messageId = id,
                    displayName = name, direction = direction, text = text, timeMs = timestamp
                )
            }
            return PollBatch(generation, cursor, gap, hasMore, messages)
        }

        private fun numericLong(raw: Any?): Long? = when (raw) {
            is Long -> raw
            is Int -> raw.toLong()
            else -> null
        }
    }

    init {
        require(isEndpointAllowed(config.endpoint)) { "relay endpoint must be HTTPS or localhost" }
        require(config.bearerToken.length >= 32) { "relay token too short" }
        require(config.pairedAccountRef.isNotBlank() &&
            config.androidProfileScope.startsWith("com.tencent.mm@")) { "invalid pairing scope" }
    }

    private var generation = ""
    private var cursor = 0L
    private var lastFetched: PollBatch? = null

    /**
     * Exactly one successful downstream acceptance may advance this cursor.
     * A failed validation or index write retries the same source position.
     */
    @Synchronized
    fun acknowledgeProcessed(batch: PollBatch) {
        require(lastFetched === batch) { "only the last fetched batch may be acknowledged" }
        require(batch.cursor >= 0L) { "invalid acknowledged cursor" }
        if (batch.generation == generation) {
            require(batch.cursor >= cursor) { "cursor must not move backward" }
        }
        generation = batch.generation
        cursor = batch.cursor
        lastFetched = null
    }

    @Synchronized
    fun pollOnce(nowMs: Long = System.currentTimeMillis()): PollBatch {
        val path = config.endpoint.trim().trimEnd('/') + "/v1/poll?cursor=" + cursor +
            "&generation=" + URLEncoder.encode(generation, "UTF-8") + "&limit=100"
        val connection = URL(path).openConnection() as HttpURLConnection
        connection.instanceFollowRedirects = false
        connection.connectTimeout = 3000
        connection.readTimeout = 3000
        connection.setRequestProperty("Authorization", "Bearer " + config.bearerToken)
        connection.setRequestProperty("Accept", "application/json")
        connection.useCaches = false
        try {
            require(connection.responseCode == 200) { "relay unavailable or unauthorized" }
            val body = connection.inputStream.use { stream ->
                val out = java.io.ByteArrayOutputStream()
                val bytes = ByteArray(4096)
                while (true) {
                    val read = stream.read(bytes)
                    if (read < 0) break
                    require(out.size() + read <= MAX_RESPONSE_BYTES) { "relay response too large" }
                    out.write(bytes, 0, read)
                }
                out.toString(StandardCharsets.UTF_8.name())
            }
            val batch = parseResponse(body, config.pairedAccountRef)
            require(!batch.gap) { "desktop relay gap requires backfill" }
            // Reject an entire poll if any item is outside the index contract.
            // A zero insert count is otherwise indistinguishable from all duplicates.
            require(batch.messages.all {
                it.timeMs <= nowMs + 120_000L && it.timeMs > 0L
            }) { "message timestamp outside the trusted window" }
            // Transport only. The caller chooses when the authenticated data
            // may enter the per-profile index or trigger opt-in model analysis.
            // Do not consume data here: the pipeline must first validate
            // the entire batch and import it into the right account/profile.
            lastFetched = batch
            return batch
        } finally {
            connection.disconnect()
        }
    }
}
