package com.jev.probe

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat
import com.jev.probe.core.Prefs
import com.jev.probe.capture.KeepAliveService
import com.jev.probe.ui.Guofeng
import com.jev.probe.ui.InkPaperDrawable

/**
 * Home / setup screen.
 *
 * The fork keeps the original behavior but presents it as one coherent
 * Chinese-ink product surface: paper background, jade actions, cinnabar seal
 * accent and restrained status cards.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var container: LinearLayout

    private val a11yComponent: String
        get() = "$packageName/com.jev.probe.capture.ChatCaptureService"

    private fun dp(v: Int) = Guofeng.dp(this, v)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        Guofeng.applyWindow(this)

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            background = InkPaperDrawable()
        }
        container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(32))
        }
        container.padForSystemBars()
        scroll.addView(container)
        setContentView(scroll)
    }

    override fun onResume() {
        super.onResume()
        if (prefs.enabled && Settings.canDrawOverlays(this)) {
            KeepAliveService.start(this)
        }
        build()
    }

    private fun build() {
        container.removeAllViews()

        container.addView(hero())

        val a11y = isA11yEnabled()
        val overlay = Settings.canDrawOverlays(this)
        val notifications = isNotificationAccessEnabled()
        val ime = isZhiyanImeEnabled()
        val key = prefs.hasKey()
        val ready = overlay && key

        container.addView(readinessCard(ready, notifications, overlay, key))

        container.addView(sectionTitle("启用准备", "复制消息后，点悬浮球获取回复建议"))
        container.addView(permissionCard(
            mark = "浮",
            title = "悬浮窗权限",
            desc = "在微信等应用上显示助手入口",
            granted = overlay
        ) {
            startActivity(Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            ))
        })
        container.addView(permissionCard(
            mark = "讯",
            title = "微信消息通知（可选）",
            desc = "接收通知中显示的消息摘要，不等于完整聊天记录",
            granted = notifications
        ) {
            showNotificationDisclosure()
        })
        container.addView(permissionCard(
            mark = "读",
            title = "无障碍权限（可选）",
            desc = "用于其他应用的页面读取或主动截图识字",
            granted = a11y
        ) {
            showAccessibilityDisclosure()
        })
        container.addView(permissionCard(
            mark = "入",
            title = "小书童·快捷填入（可选）",
            desc = "选择专用键盘后，点回复即可填入；由你发送",
            granted = ime
        ) {
            showImeDisclosure()
        })
        container.addView(permissionCard(
            mark = "稳",
            title = "自启动与省电设置",
            desc = "悬浮球容易消失时，可允许自启动和后台运行",
            granted = null
        ) {
            runCatching {
                startActivity(Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:$packageName")
                ))
            }
        })

        container.addView(sectionTitle("常用入口", "补充聊天背景，设置你的回复方式"))
        container.addView(shortcutRow())

        // Optional, user-labelled and memory-only. No new app permissions.
        container.addView(infoStrip(
            "补充最近对话",
            "粘贴最近的对话，最多使用 20 条；不会自动读取微信历史。"
        ).apply {
            isClickable = true
            setOnClickListener {
                startActivity(Intent(this@MainActivity, ManualContextActivity::class.java))
            }
        })

        if (!prefs.contextEnabled) {
            container.addView(infoStrip(
                "长期聊天记录未开启",
                "你仍可临时补充最近对话；长期保存需在设置中另行开启。"
            ))
        }

        container.addView(masterButton(prefs.enabled).apply {
            setOnClickListener {
                prefs.enabled = !prefs.enabled
                if (prefs.enabled && Settings.canDrawOverlays(this@MainActivity)) {
                    KeepAliveService.start(this@MainActivity)
                } else if (!prefs.enabled) {
                    KeepAliveService.stop(this@MainActivity)
                }
                build()
            }
        })

        container.addView(text(
            "复制消息 → 获取回复建议 → 选择或修改后，由你发送",
            11.5f,
            Guofeng.INK_FAINT
        ).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(12), 0, 0)
        })
    }

    private fun hero(): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(2), dp(8), dp(2), dp(8))
        }

        val avatar = ImageView(this).apply {
            setImageResource(R.drawable.zhiyan_mascot_avatar)
            scaleType = ImageView.ScaleType.CENTER_CROP
            contentDescription = "小书童·知言"
            background = Guofeng.round(
                this@MainActivity,
                30,
                Guofeng.CARD,
                Guofeng.BORDER_JADE
            )
            setPadding(dp(2), dp(2), dp(2), dp(2))
            layoutParams = LinearLayout.LayoutParams(dp(58), dp(58)).apply {
                rightMargin = dp(12)
            }
        }

        val left = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f
            )
        }
        left.addView(text("小书童·知言", 26f, Guofeng.JADE_DEEP, bold = true, serif = true))
        left.addView(text("装在手机上的「对话副驾」", 14f, Guofeng.INK_SOFT).apply {
            setPadding(0, dp(4), 0, 0)
        })
        left.addView(text("知言 · 慎答 · 由你发送", 12f, Guofeng.GOLD).apply {
            setPadding(0, dp(6), 0, 0)
        })

        val seal = TextView(this).apply {
            text = "知\n言"
            gravity = Gravity.CENTER
            textSize = 12f
            setTextColor(Guofeng.CARD)
            typeface = Guofeng.serif(true)
            background = Guofeng.round(this@MainActivity, 9, Guofeng.CINNABAR)
            setPadding(dp(9), dp(7), dp(9), dp(7))
            layoutParams = LinearLayout.LayoutParams(dp(44), dp(54)).apply {
                leftMargin = dp(8)
            }
        }

        row.addView(avatar)
        row.addView(left)
        row.addView(seal)
        return row
    }

    private fun readinessCard(
        ready: Boolean,
        notifications: Boolean,
        overlay: Boolean,
        key: Boolean
    ): View {
        val c = card(strong = true)

        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val left = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f
            )
        }
        left.addView(text(
            if (ready) "今日已就绪" else "还差一点准备",
            17f,
            Guofeng.INK,
            bold = true,
            serif = true
        ))
        left.addView(text(
            if (ready) "进入聊天后，悬浮球会在旁边待命。"
            else "把下面缺失的权限或密钥补齐即可。",
            12f,
            Guofeng.INK_SOFT
        ).apply { setPadding(0, dp(3), 0, 0) })

        val badge = TextView(this).apply {
            text = if (ready) "可用" else "待配置"
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(if (ready) Guofeng.JADE_DEEP else Guofeng.CINNABAR)
            typeface = Guofeng.sans(true)
            background = Guofeng.round(
                this@MainActivity,
                18,
                if (ready) Guofeng.JADE_SOFT else Guofeng.CINNABAR_SOFT
            )
            setPadding(dp(13), dp(6), dp(13), dp(6))
        }

        head.addView(left)
        head.addView(badge)
        c.addView(head)

        val statuses = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(14), 0, 0)
        }
        statuses.addView(statusToken("悬浮窗", overlay))
        statuses.addView(statusToken("微信通知", notifications))
        statuses.addView(statusToken("密钥", key))
        c.addView(statuses)

        return c
    }

    private fun statusToken(label: String, ok: Boolean): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setPadding(dp(4), dp(4), dp(4), dp(2))
        }
        box.addView(text(if (ok) "●" else "○", 13f, if (ok) Guofeng.SUCCESS else Guofeng.INK_FAINT).apply {
            gravity = Gravity.CENTER
        })
        box.addView(text(label, 12f, Guofeng.INK_SOFT, bold = ok).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(2), 0, 0)
        })
        return box
    }

    private fun permissionCard(
        mark: String,
        title: String,
        desc: String,
        granted: Boolean?,
        onClick: () -> Unit
    ): View {
        val c = card()
        c.setOnClickListener { onClick() }

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val medallion = TextView(this).apply {
            text = mark
            textSize = 13f
            gravity = Gravity.CENTER
            typeface = Guofeng.serif(true)
            setTextColor(Guofeng.JADE_DEEP)
            background = Guofeng.round(this@MainActivity, 22, Guofeng.JADE_PALE, Guofeng.BORDER_JADE)
            layoutParams = LinearLayout.LayoutParams(dp(42), dp(42)).apply {
                rightMargin = dp(12)
            }
        }

        val copy = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        copy.addView(text(title, 15f, Guofeng.INK, bold = true, serif = true))
        copy.addView(text(desc, 12f, Guofeng.INK_SOFT).apply {
            setPadding(0, dp(3), 0, 0)
        })

        val state = TextView(this).apply {
            text = when (granted) {
                true -> "已开启"
                false -> "去开启"
                null -> "去设置"
            }
            textSize = 12f
            gravity = Gravity.CENTER
            typeface = Guofeng.sans(true)
            setTextColor(if (granted == true) Guofeng.JADE_DEEP else Guofeng.GOLD)
            background = Guofeng.round(
                this@MainActivity,
                16,
                if (granted == true) Guofeng.JADE_SOFT else Guofeng.GOLD_SOFT
            )
            setPadding(dp(11), dp(6), dp(11), dp(6))
        }

        row.addView(medallion)
        row.addView(copy)
        row.addView(state)
        c.addView(row)
        return c
    }

    private fun shortcutRow(): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        row.addView(shortcutCard(
            mark = "册",
            title = "联系人与备注",
            desc = "关系 · 备注 · 历史",
            primary = true
        ) {
            startActivity(Intent(this, KnowledgeActivity::class.java))
        })

        row.addView(shortcutCard(
            mark = "调",
            title = "模型与回复风格",
            desc = "服务设置 · 说话方式",
            primary = false
        ) {
            startActivity(Intent(this, SettingsActivity::class.java))
        })

        return row
    }

    private fun shortcutCard(
        mark: String,
        title: String,
        desc: String,
        primary: Boolean,
        onClick: () -> Unit
    ): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Guofeng.round(
                this@MainActivity,
                18,
                if (primary) Guofeng.JADE_PALE else Guofeng.CARD,
                if (primary) Guofeng.BORDER_JADE else Guofeng.BORDER
            )
            elevation = dp(1).toFloat()
            setPadding(dp(14), dp(13), dp(12), dp(13))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                if (primary) rightMargin = dp(5) else leftMargin = dp(5)
            }
            setOnClickListener { onClick() }

            addView(text(mark, 19f, if (primary) Guofeng.JADE_DEEP else Guofeng.CINNABAR, bold = true, serif = true))
            addView(text(title, 14f, Guofeng.INK, bold = true, serif = true).apply {
                setPadding(0, dp(6), 0, 0)
            })
            addView(text(desc, 11.5f, Guofeng.INK_SOFT).apply {
                setPadding(0, dp(3), 0, 0)
            })
        }
    }

    private fun infoStrip(title: String, desc: String): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Guofeng.round(this@MainActivity, 14, Guofeng.GOLD_SOFT, Guofeng.BORDER)
            setPadding(dp(13), dp(10), dp(13), dp(10))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(12) }

            addView(text(title, 13f, Guofeng.GOLD, bold = true))
            addView(text(desc, 11.5f, Guofeng.INK_SOFT).apply {
                setPadding(0, dp(2), 0, 0)
            })
        }
    }

    private fun masterButton(on: Boolean): View {
        return TextView(this).apply {
            text = if (on) "辅助已开启 · 点击暂停" else "开始辅助"
            textSize = 16f
            gravity = Gravity.CENTER
            typeface = Guofeng.serif(true)
            setTextColor(if (on) Guofeng.CARD else Guofeng.JADE_DEEP)
            background = Guofeng.round(
                this@MainActivity,
                24,
                if (on) Guofeng.JADE_DEEP else Guofeng.JADE_SOFT,
                Guofeng.BORDER_JADE
            )
            setPadding(dp(18), dp(15), dp(18), dp(15))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(20) }
        }
    }

    private fun sectionTitle(title: String, subtitle: String): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(2), dp(20), dp(2), dp(3))
            addView(text(title, 17f, Guofeng.JADE_DEEP, bold = true, serif = true))
            addView(text(subtitle, 11.5f, Guofeng.INK_FAINT).apply {
                setPadding(0, dp(2), 0, 0)
            })
        }
    }

    private fun card(strong: Boolean = false): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = Guofeng.round(
            this@MainActivity,
            18,
            if (strong) Guofeng.CARD else Guofeng.CARD_SOFT,
            if (strong) Guofeng.BORDER_JADE else Guofeng.BORDER
        )
        elevation = dp(if (strong) 2 else 1).toFloat()
        setPadding(dp(15), dp(14), dp(15), dp(14))
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(10) }
    }

    private fun text(
        value: String,
        size: Float,
        color: Int,
        bold: Boolean = false,
        serif: Boolean = false
    ) = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(color)
        typeface = if (serif) Guofeng.serif(bold) else Guofeng.sans(bold)
    }

    private fun isNotificationAccessEnabled(): Boolean =
        NotificationManagerCompat.getEnabledListenerPackages(this).contains(packageName)

    private fun isZhiyanImeEnabled(): Boolean {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        return imm.enabledInputMethodList.any {
            it.serviceInfo.packageName == packageName &&
                it.serviceInfo.name.endsWith(".ZhiyanInputMethodService")
        }
    }

    private fun showImeDisclosure() {
        AlertDialog.Builder(this)
            .setTitle("小书童·快捷填入")
            .setMessage(
                "开启后，系统允许你把“小书童·快捷填入”选为键盘。输入法属于敏感权限，" +
                    "只有你切换到它时，助手才会在当前输入框工作。\n\n" +
                    "点选回复后只填入文字，不读取输入框已有内容，不记录你手工输入的内容，也不会自动发送。" +
                    "填入后会尝试切回原键盘；你也可以点“返回原键盘”。"
            )
            .setNegativeButton("暂不开启", null)
            .setPositiveButton("前往输入法设置") { _, _ ->
                startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
            }
            .show()
    }

    private fun showNotificationDisclosure() {
        AlertDialog.Builder(this)
            .setTitle("微信通知访问")
            .setMessage(
                "开启后，系统会允许此服务接收通知。助手只处理微信通知，忽略其他应用通知。\n\n" +
                    "使用的内容是通知中显示的名称、消息摘要和时间，不是完整聊天记录；不会读取微信聊天页面。\n\n" +
                    "默认由你点击后才分析。你也可以在设置中另行开启自动分析；" +
                    "分析时内容会发送给你选择的模型服务商，可能收费。助手不会自动发送消息。"
            )
            .setNegativeButton("暂不开启", null)
            .setPositiveButton("前往系统设置") { _, _ ->
                startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            }
            .show()
    }

    /**
     * Prominent AccessibilityService disclosure shown immediately before the
     * system permission screen. It is intentionally separate from every other
     * permission/privacy disclosure and requires an affirmative action.
     */
    private fun showAccessibilityDisclosure() {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(8))

            addView(text(
                "这项权限可用于读取其他应用的当前页面，或在你主动选择时截图识字。普通微信复制分析不需要开启。",
                14f,
                Guofeng.INK,
                bold = true
            ))
            addView(text(
                "系统会授予读取屏幕可见文字、页面结构和截图的能力。微信消息仍由你主动复制提供，" +
                    "不会读取微信数据库，也不会自动点击发送。\n\n" +
                    "截图识字需你主动选择。默认在手机上识别，不上传图片；只有你改选“云端识别”后，" +
                    "裁剪的聊天图片才会发送给你配置的图片识别服务。\n\n" +
                    "发起分析时，文字会发送给你选择的模型服务商，用于生成回复建议，可能收费。" +
                    "只有另行开启长期聊天记录，相关内容才会保存到本机。\n\n" +
                    "回复由你确认并发送；你可以随时在系统设置中关闭这项权限。",
                13f,
                Guofeng.INK_SOFT
            ).apply { setPadding(0, dp(12), 0, 0) })
        }

        val scroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = true
            addView(content)
        }

        AlertDialog.Builder(this)
            .setTitle("无障碍权限说明")
            .setView(scroll)
            .setNegativeButton("不同意") { dialog, _ -> dialog.dismiss() }
            .setPositiveButton("同意并前往系统设置") { _, _ ->
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
            .setCancelable(false)
            .show()
    }

    private fun isA11yEnabled(): Boolean {
        val enabled = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabled.contains(a11yComponent)
    }
}
