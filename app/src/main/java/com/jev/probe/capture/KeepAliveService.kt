package com.jev.probe.capture

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import com.jev.probe.ClipboardImportActivity
import com.jev.probe.core.ChatSnapshot
import com.jev.probe.core.ExplicitClipboardPolicy
import com.jev.probe.core.RecentExplicitCopy
import com.jev.probe.core.Msg
import com.jev.probe.core.Prefs
import com.jev.probe.core.RankedReply
import com.jev.probe.core.kb.ChatContext
import com.jev.probe.core.kb.ContextBuilder
import com.jev.probe.core.kb.HistoryCaptureHint
import com.jev.probe.core.kb.KbStore
import com.jev.probe.jev.JevClient
import com.jev.probe.jev.ReplyClient
import com.jev.probe.session.DesktopBridgeClient
import com.jev.probe.session.DesktopAnalysisCoordinator
import com.jev.probe.session.DesktopMessageIndex
import com.jev.probe.session.DesktopSyncPipeline
import com.jev.probe.overlay.OverlayController
import com.jev.probe.overlay.OverlayRuntime
import java.util.ArrayDeque
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Foreground assistant host.
 *
 * Unlike the old implementation, the floating UI is not owned by
 * AccessibilityService. This service can therefore keep the bubble available
 * for both normal WeChat and vivo clone-user WeChat while the WeChat path itself
 * relies only on standard notification access / explicit clipboard input.
 */
class KeepAliveService : Service() {

    private data class WeChatNotification(
        val title: String,
        val text: String,
        val postTime: Long,
        val userLabel: String
    )

    private lateinit var prefs: Prefs
    private lateinit var overlay: OverlayController
    private val worker = Executors.newSingleThreadExecutor()
    private val epoch = AtomicLong(0L)
    // Lifecycle cleanup cannot share the general analysis worker: onDestroy
    // stops that worker and could otherwise leave old pairing data in memory.
    private val desktopCleanupWorker = Executors.newSingleThreadExecutor()
    private var desktopScheduler: ScheduledExecutorService? = null
    private var desktopPipeline: DesktopSyncPipeline? = null
    private var desktopConfig: DesktopBridgeClient.Config? = null

    /** In-memory only unless the user has separately enabled context history. */
    private val inbox = LinkedHashMap<String, ArrayDeque<WeChatNotification>>()
    private var latestKey: String? = null
    private var latestAt: Long = 0L
    private val recentExplicitCopy = RecentExplicitCopy()

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        startForegroundNotification()

        overlay = OverlayRuntime.get(this)
        overlay.onAnalyzeClipboard = { launchClipboardImport() }

        if (prefs.enabled) overlay.showCaptureOnly()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ClipboardImportActivity.ACTION_CLIPBOARD_READY -> {
                // Explicit non-exported service call from the app's own
                // foreground clipboard Activity. Never accept external IPC.
                if (prefs.enabled) {
                    handleClipboardText(
                        intent.getStringExtra(ClipboardImportActivity.EXTRA_TEXT).orEmpty()
                    )
                }
            }
            ACTION_WECHAT_NOTIFICATION -> {
                val title = intent.getStringExtra(EXTRA_TITLE).orEmpty().trim()
                val text = intent.getStringExtra(EXTRA_TEXT).orEmpty().trim()
                val ts = intent.getLongExtra(EXTRA_POST_TIME, System.currentTimeMillis())
                val userLabel = intent.getStringExtra(EXTRA_USER_LABEL).orEmpty().ifBlank { "default" }
                if (title.isNotBlank() || text.isNotBlank()) {
                    receiveWeChatNotification(title, text, ts, userLabel)
                }
            }
            ACTION_SHOW -> if (prefs.enabled) overlay.showCaptureOnly()
        }
        refreshDesktopPolling()
        return START_STICKY
    }

    private fun startForegroundNotification() {
        val channelId = "zhiyan_keepalive"
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                channelId,
                "小书童·知言运行中",
                NotificationManager.IMPORTANCE_MIN
            )
            ch.setShowBadge(false)
            nm.createNotificationChannel(ch)
        }
        val notif = Notification.Builder(this, channelId)
            .setContentTitle("小书童·知言运行中")
            .setContentText("微信通知到达后可一键分析；发送仍由你完成")
            .setSmallIcon(android.R.drawable.ic_menu_edit)
            .setOngoing(true)
            .build()
        startForeground(NOTIFICATION_ID, notif)
    }

    private fun receiveWeChatNotification(
        rawTitle: String,
        text: String,
        postTime: Long,
        userLabel: String
    ) {
        if (!prefs.enabled) return
        val title = rawTitle.ifBlank { "微信消息" }
        val key = userLabel + "\u0000" + title
        val q = inbox.getOrPut(key) { ArrayDeque() }
        if (q.lastOrNull()?.let {
                it.text == text && kotlin.math.abs(it.postTime - postTime) < 2500L
            } == true
        ) return

        q.addLast(WeChatNotification(title, text, postTime, userLabel))
        while (q.size > MAX_NOTIFICATION_HISTORY) q.removeFirst()

        latestKey = key
        latestAt = System.currentTimeMillis()

        overlay.setSaveContactHandler(this) { saveLatestContact() }
        overlay.setManualAnalyzeHandler(this) { analyzeNotification(key) }
        overlay.showNotificationReady(title) { analyzeNotification(key) }
        if (prefs.wechatNotificationAutoAnalyze && prefs.hasKey()) {
            analyzeNotification(key)
        }
    }

    private fun analyzeNotification(key: String) {
        val q = inbox[key] ?: return
        val items = q.toList().takeLast(MAX_ANALYSIS_MESSAGES)
        if (items.isEmpty()) return

        val title = items.last().title
        val userLabel = items.last().userLabel
        val messages = items.flatMap { item ->
            val lines = item.text.lineSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .toList()
                .ifEmpty { listOf("（通知未显示正文）") }
            lines.map { line -> Msg("other", line, item.postTime) }
        }.takeLast(MAX_ANALYSIS_MESSAGES)
        val snapshot = ChatSnapshot(
            title = title.takeIf { it != "微信消息" },
            messages = messages,
            note = "微信通知上下文 · 时间来自 Android 通知 · $userLabel；未读取微信内部控件"
        )
        analyzeSnapshot(
            snapshot,
            wechatScope(userLabel),
            HistoryCaptureHint.NEWEST_SCREEN,
            allowAutoCopy = true
        )
    }

    private fun launchClipboardImport() {
        val i = Intent(this, ClipboardImportActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
        runCatching { startActivity(i) }
            .onFailure { overlay.toast("无法打开剪贴板导入：" + it.javaClass.simpleName) }
    }

    private fun handleClipboardText(raw: String) {
        val now = System.currentTimeMillis()
        val snapshot = ExplicitClipboardPolicy.fromUserCopy(raw, now)
        if (snapshot == null) {
            overlay.toast("请先复制一条不超过 4000 字的对方消息")
            return
        }
        if (!prefs.hasKey()) {
            overlay.showError("请先在 JEV 设置中配置模型 API Key")
            return
        }

        val text = snapshot.messages.single().text
        val fingerprint = ExplicitClipboardPolicy.fingerprint(text)
        if (recentExplicitCopy.checkAndMark(text, now)) {
            if (overlay.reopenClipboardResult(fingerprint)) {
                overlay.toast("已显示刚才的候选，不会重复调用模型")
            } else {
                overlay.toast("相同文字正在分析，避免重复调用模型")
            }
            return
        }

        // A notification may be from WeChat A while clipboard is from clone B:
        // NEVER borrow its contact title, userLabel, or relationship.
        overlay.setManualAnalyzeHandler(this) {
            recentExplicitCopy.clear()
            launchClipboardImport()
        }
        analyzeSnapshot(
            snapshot, WECHAT_PKG, HistoryCaptureHint.CONSERVATIVE,
            clipboardFingerprint = fingerprint
        )
    }

    private fun analyzeSnapshot(
        snapshot: ChatSnapshot,
        app: String,
        historyHint: HistoryCaptureHint,
        allowAutoCopy: Boolean = false,
        clipboardFingerprint: String? = null
    ) {
        if (!prefs.hasKey()) {
            overlay.showError("未设置判断接口密钥，去设置里填")
            return
        }
        val myEpoch = epoch.incrementAndGet()
        overlay.resetForNewConversation()
        overlay.showLoading()
        overlay.setNote(snapshot.note)

        worker.execute {
            val ctx = runCatching {
                ContextBuilder.build(this, snapshot, app, prefs, historyHint)
            }.getOrNull()
            if (epoch.get() != myEpoch) return@execute

            val rel = effectiveRelationship(snapshot, ctx)
            val client = JevClient(prefs)
            val judgment = client.judge(snapshot, rel, ctx)
            if (epoch.get() != myEpoch) return@execute

            mainPost {
                if (epoch.get() != myEpoch) return@mainPost
                overlay.setContextInfo(ctx?.notes?.size ?: 0, ctx?.history?.size ?: 0)
                if (judgment.error != null) overlay.showError(judgment.error)
                else overlay.showJudgment(judgment, clipboardFingerprint)
            }
            if (judgment.error != null) return@execute

            val candidates = runCatching {
                client.draftCandidates(snapshot, rel, ctx)
            }.getOrElse { emptyList() }
            if (epoch.get() != myEpoch) return@execute

            if (candidates.isNotEmpty()) {
                val provisional = candidates.map { RankedReply(it, 0.0) }
                mainPost {
                    if (epoch.get() == myEpoch) {
                        overlay.showReplies(provisional, sorting = true)
                    }
                }

                val ranked = runCatching {
                    client.rankCandidates(snapshot, rel, candidates, ctx)
                }.getOrElse { emptyList() }
                if (epoch.get() != myEpoch) return@execute

                val finalReplies = if (ranked.isNotEmpty()) ranked else provisional
                mainPost {
                    if (epoch.get() == myEpoch) {
                        overlay.showReplies(
                            finalReplies,
                            error = if (ranked.isEmpty()) "排序暂不可用" else null,
                            sorting = false
                        )
                        if (
                            allowAutoCopy &&
                            prefs.wechatAutoCopyTopReply &&
                            ranked.isNotEmpty()
                        ) {
                            ranked.firstOrNull()?.text?.let { copyTopReply(it) }
                        }
                    }
                }
            }
        }
    }

    private fun copyTopReply(text: String) {
        val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("小书童首选回复", text))
        overlay.toast("首选回复已复制；粘贴后由你发送")
        overlay.collapsePanel()
    }

    private fun effectiveRelationship(snapshot: ChatSnapshot, ctx: ChatContext?): String {
        if (snapshot.title.isNullOrBlank()) {
            return "联系人与关系均未核验；只根据用户主动复制的文字回答，不能推断此前会话的身份"
        }
        val display = ctx?.contact?.name?.trim().orEmpty()
            .ifBlank { KbStore.displayName(snapshot.title) }
        val relation = ctx?.contact?.relationship?.trim().orEmpty()
            .ifBlank { prefs.relationship.trim() }

        return buildString {
            if (display.isNotBlank()) append("当前会话名称/备注：").append(display).append('；')
            if (relation.isNotBlank()) append("与我的关系：").append(relation)
            else append("与当前会话对象的关系未设置")
        }
    }

    private fun saveLatestContact() {
        val key = latestKey
        val latest = key?.let { inbox[it]?.lastOrNull() }
        val title = latest?.title
        if (title.isNullOrBlank() || title == "微信消息") {
            overlay.toast("当前没有可靠的微信会话名称")
            return
        }
        val appScope = wechatScope(latest.userLabel)
        worker.execute {
            val msg = runCatching {
                KbStore.get(this).saveOrMergeContact(title, appScope)
            }.getOrElse { "保存失败：" + it.javaClass.simpleName }
            mainPost { overlay.toast(msg) }
        }
    }

    private fun wechatScope(userLabel: String): String =
        WECHAT_PKG + "@" + userLabel.trim().ifBlank { "default" }

    private inline fun mainPost(crossinline block: () -> Unit) {
        android.os.Handler(mainLooper).post { block() }
    }

    /**
     * Start a single read-only, user-approved desktop poller. The desktop
     * bridge never controls WeChat or touches its Android window hierarchy.
     * When auto-analysis is disabled, messages stay in the volatile index and
     * no model API call is made.
     */
    private fun refreshDesktopPolling() {
        if (!prefs.enabled || !prefs.desktopBridgeEnabled) {
            stopDesktopPolling()
            return
        }
        val config = DesktopBridgeClient.Config(
            endpoint = prefs.desktopBridgeEndpoint,
            bearerToken = prefs.desktopBridgeToken,
            pairedAccountRef = prefs.desktopBridgeAccountRef,
            androidProfileScope = "com.tencent.mm@" + android.os.Process.myUserHandle().toString()
        )
        if (desktopConfig == config && desktopScheduler?.isShutdown == false) return
        stopDesktopPolling()
        val pipeline = runCatching {
            DesktopSyncPipeline(config) { context: DesktopAnalysisCoordinator.ConversationContext ->
                // The second toggle explicitly authorizes sending selected
                // conversation text to the configured reply-model provider.
                if (!prefs.enabled || !prefs.desktopAutoAnalyze) {
                    emptyList()
                } else {
                    val messages = context.messages.map {
                        Msg(
                            side = if (it.direction == DesktopMessageIndex.Direction.ME) "me" else "other",
                            text = it.text,
                            ts = it.timeMs
                        )
                    }
                    val snapshot = ChatSnapshot(
                        title = context.displayName,
                        messages = messages,
                        note = "已授权的电脑同步会话；不代表手机微信当前联系人"
                    )
                    ReplyClient(prefs).draft(snapshot, prefs.relationship)
                }
            }
        }.getOrNull() ?: return
        desktopConfig = config
        desktopPipeline = pipeline
        desktopScheduler = Executors.newSingleThreadScheduledExecutor().also { scheduler ->
            scheduler.scheduleWithFixedDelay({
                if (!prefs.enabled || !prefs.desktopBridgeEnabled) return@scheduleWithFixedDelay
                // Avoid writing chats, tokens, contact names or provider errors to logcat.
                runCatching {
                    pipeline.pollOnce(prefs.desktopAutoAnalyze)
                }
            }, 0, 5, TimeUnit.SECONDS)
        }
    }

    private fun stopDesktopPolling() {
        desktopScheduler?.shutdownNow()
        desktopScheduler = null
        val previous = desktopPipeline
        desktopPipeline = null
        desktopConfig = null
        if (previous != null && !desktopCleanupWorker.isShutdown) {
            desktopCleanupWorker.execute { previous.stop() }
        }
    }

    override fun onDestroy() {
        stopDesktopPolling()
        desktopCleanupWorker.shutdown()
        epoch.incrementAndGet()
        overlay.setSaveContactHandler(this, null)
        overlay.setManualAnalyzeHandler(this, null)
        overlay.onAnalyzeClipboard = null
        worker.shutdownNow()
        // Keep the process-wide controller instance so an already-bound optional
        // AccessibilityService cannot retain a stale overlay object. Hiding is
        // enough; a later service restart reuses the same controller.
        overlay.hide()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val NOTIFICATION_ID = 1
        private const val WECHAT_PKG = "com.tencent.mm"
        private const val MAX_NOTIFICATION_HISTORY = 20
        private const val MAX_ANALYSIS_MESSAGES = 12

        private const val ACTION_SHOW = "com.jev.probe.action.SHOW_ASSISTANT"
        private const val ACTION_WECHAT_NOTIFICATION = "com.jev.probe.action.WECHAT_NOTIFICATION"
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_TEXT = "text"
        private const val EXTRA_POST_TIME = "post_time"
        private const val EXTRA_USER_LABEL = "user_label"

        fun start(ctx: Context) {
            val i = Intent(ctx, KeepAliveService::class.java).setAction(ACTION_SHOW)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i)
            else ctx.startService(i)
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, KeepAliveService::class.java))
        }

        fun pushWeChatNotification(
            ctx: Context,
            title: String,
            text: String,
            postTime: Long,
            userLabel: String
        ) {
            val i = Intent(ctx, KeepAliveService::class.java)
                .setAction(ACTION_WECHAT_NOTIFICATION)
                .putExtra(EXTRA_TITLE, title)
                .putExtra(EXTRA_TEXT, text)
                .putExtra(EXTRA_POST_TIME, postTime)
                .putExtra(EXTRA_USER_LABEL, userLabel)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i)
            else ctx.startService(i)
        }
    }
}
