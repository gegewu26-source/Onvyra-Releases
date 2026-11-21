package me.rerere.androidcontrol.ui

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputFilter
import android.text.InputType
import android.text.method.PasswordTransformationMethod
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import me.rerere.androidcontrol.AndroidControl

/** Phone-side setup and manual handoff. Opening this page never grants system permissions. */
class AndroidControlActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val control by lazy { AndroidControl.get(applicationContext) }
    private val uiPreferences by lazy { getSharedPreferences("android_control_setup_ui", MODE_PRIVATE) }
    private lateinit var master: CheckBox
    private lateinit var confirmation: CheckBox
    private lateinit var status: TextView
    private lateinit var feedback: TextView
    private lateinit var host: EditText
    private lateinit var pairPort: EditText
    private lateinit var pairCode: EditText
    private lateinit var connectPort: EditText
    private val connectionButtons = mutableListOf<Button>()
    private var syncing = false
    private var busy = false
    private var lastStatus = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            val routeClass = Class.forName("com.constx.onvyra.RouteActivity")
            val routeIntent = Intent(this, routeClass).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra("open_android_control", true)
            }
            startActivity(routeIntent)
            finish()
            return
        } catch (_: ClassNotFoundException) {
            // Standalone test/library mode fallback
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(8))
            setBackgroundColor(Color.rgb(249, 249, 252))
            setOnApplyWindowInsetsListener { view, insets ->
                if (Build.VERSION.SDK_INT >= 30) {
                    val safe = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout() or WindowInsets.Type.ime())
                    view.setPadding(dp(16) + safe.left, dp(12) + safe.top, dp(16) + safe.right, dp(8) + safe.bottom)
                } else {
                    @Suppress("DEPRECATION")
                    view.setPadding(dp(16) + insets.systemWindowInsetLeft, dp(12) + insets.systemWindowInsetTop,
                        dp(16) + insets.systemWindowInsetRight, dp(8) + insets.systemWindowInsetBottom)
                }
                insets
            }
        }
        // This stop control remains on screen while the setup form scrolls.
        val heading = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        heading.addView(label("手机操作", 22f), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        heading.addView(button("立即停止") {
            control.requestStop()
            feedback.text = "已请求停止所有手机操作。"
            refreshStatus()
        }.apply {
            setTextColor(Color.rgb(179, 38, 30))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        })
        root.addView(heading)
        val form = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(ScrollView(this).apply { isFillViewport = true; addView(form) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        form.addView(label("通过本机无线调试连接手机助手，在后台屏幕执行操作。登录、验证码等需要本人处理时，可接管到主屏。", 14f))
        master = CheckBox(this).apply {
            text = "允许 AI 操作这台手机"
            setOnCheckedChangeListener { _, checked ->
                if (!syncing) {
                    control.enabled = checked
                    if (checked) requestNotificationPermission()
                    refreshStatus()
                }
            }
        }
        form.addView(master)
        confirmation = CheckBox(this).apply {
            text = "发送、删除、付款等敏感操作前确认"
            setOnCheckedChangeListener { _, checked ->
                if (!syncing) control.confirmSensitive = checked
            }
        }
        form.addView(confirmation)
        status = label("正在读取状态…", 14f).apply { setTextIsSelectable(true) }
        form.addView(status)
        feedback = label("", 14f).apply { setTextColor(Color.rgb(34, 89, 126)); setTextIsSelectable(true) }
        form.addView(feedback)

        section(form, "1. 系统权限")
        form.addView(button("打开无线调试设置") {
            openSystemSettings("android.settings.WIRELESS_DEBUGGING_SETTINGS", Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
        })
        form.addView(button("打开无障碍设置") { openSystemSettings(Settings.ACTION_ACCESSIBILITY_SETTINGS) })
        form.addView(label("无线调试和本应用的无障碍服务，需要你在系统页面中亲自开启。配对端口与连接端口不是同一个端口。", 13f))

        section(form, "2. 配对无线调试")
        host = input("手机地址", uiPreferences.getString("host", "127.0.0.1").orEmpty(), InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        form.addView(host)
        pairPort = input("配对端口", "", InputType.TYPE_CLASS_NUMBER)
        form.addView(pairPort)
        pairCode = input("6 位配对码", "", InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD).apply {
            filters = arrayOf(InputFilter.LengthFilter(6))
            transformationMethod = PasswordTransformationMethod.getInstance()
            setSaveEnabled(false)
        }
        form.addView(pairCode)
        connectionButtons += button("配对") {
            val address = readHost() ?: return@button
            val port = readPort(pairPort) ?: return@button
            val code = pairCode.text.toString().trim()
            if (!code.matches(Regex("[0-9]{6}"))) {
                pairCode.error = "请输入系统显示的 6 位配对码"
                return@button
            }
            performAction("正在配对…", "配对完成。请填写连接端口后连接。") {
                try { control.bridge.pair(address, port, code).getOrThrow() }
                finally { withContext(Dispatchers.Main.immediate) { pairCode.text.clear() } }
            }
        }.also(form::addView)

        section(form, "3. 连接手机助手")
        connectPort = input("无线调试连接端口", uiPreferences.getString("connect_port", "").orEmpty(), InputType.TYPE_CLASS_NUMBER)
        form.addView(connectPort)
        connectionButtons += button("连接") {
            val address = readHost() ?: return@button
            val port = readPort(connectPort) ?: return@button
            uiPreferences.edit().putString("host", address).putString("connect_port", port.toString()).apply()
            performAction("正在连接…", "连接成功。") { control.bridge.connect(address, port).getOrThrow() }
        }.also(form::addView)
        form.addView(button("停止并释放当前操作") {
            performAction("正在停止…", "已停止当前操作并释放后台屏幕。") { control.stopAll(revoke = false) }
        })
        form.addView(button("停止并撤销配对凭据") {
            control.requestStop(revoke = true)
            pairCode.text.clear()
            feedback.text = "已请求停止并撤销配对凭据；下次使用需要重新配对。"
            refreshStatus()
        })
        form.addView(button("复制电脑 USB 启动命令") {
            runCatching {
                val command = control.bridge.startUsbScript()
                getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("手机助手启动命令", command))
            }.onSuccess {
                feedback.text = "启动命令已复制。请在已连接手机的电脑上执行。"
            }.onFailure {
                feedback.text = if (Build.VERSION.SDK_INT < 34) "后台手机操控需要 Android 14 或更新版本。"
                    else "启动命令生成失败，请检查存储权限后重试。"
            }
        })

        section(form, "手动接管")
        form.addView(button("接管到主屏") {
            performAction("正在转交主屏…", "已请求接管；自动操作暂停。") { control.takeover() }
        })
        form.addView(button("处理完毕，交还 AI") {
            performAction("正在交还…", "已请求交还。") { control.resumeAfterTakeover() }
        })
        form.addView(label("接管后自行完成登录或验证。交还前先离开正在处理的应用主屏；是否已恢复以实际状态为准。", 13f))

        setContentView(root)
        refreshStatus()
        scope.launch {
            while (isActive) {
                refreshStatus()
                delay(800)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (::status.isInitialized) refreshStatus()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun refreshStatus() {
        if (!::status.isInitialized) return
        syncing = true
        master.isChecked = control.enabled
        confirmation.isChecked = control.confirmSensitive
        syncing = false
        val bridge = control.bridge.status()
        val snapshot = control.state()
        val ready = control.readiness()
        val notifications = Build.VERSION.SDK_INT < 33 || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        val text = buildString {
            append(if (ready.ready) "手机操控：可以操作" else "手机操控：尚未就绪")
            append('\n')
            append("手机助手："); append(if (bridge.connected) "已连接" else "未连接")
            append("  配对："); append(if (bridge.paired) "已保存" else "未配对")
            append("\n无障碍："); append(when {
                ready.accessibilityConnected -> "已连接"
                ready.accessibilityAuthorized -> "系统授权仍在，实际服务未连接"
                else -> "未开启"
            })
            append("\n待机保护："); append(if (AndroidControlNotificationService.running) "运行中" else "未运行")
            append("  通知："); append(if (notifications) "已允许" else "未允许")
            val stage = snapshot.text("state") ?: (snapshot["session"] as? JsonObject)?.text("state")
            stage?.let { append("\n操作状态："); append(humanState(it)) }
            snapshot.text("error")?.takeIf(String::isNotBlank)?.let { append("\n"); append(it.take(240)) }
            bridge.lastError?.takeIf(String::isNotBlank)?.let { append("\n连接状态："); append(it.take(240)) }
        }
        if (lastStatus != text) { status.text = text; lastStatus = text }
    }

    private fun performAction(progress: String, success: String, block: suspend () -> Any?) {
        if (busy) { feedback.text = "上一步仍在处理；可以随时点击立即停止。"; return }
        busy = true
        connectionButtons.forEach { it.isEnabled = false }
        feedback.text = progress
        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) { block() }
                val resultObject = result as? JsonObject
                val failure = resultObject?.text("error")?.takeIf(String::isNotBlank)
                val message = resultObject?.text("message")?.takeIf(String::isNotBlank)
                feedback.text = failure?.take(240) ?: message?.take(240) ?: success
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                // Pairing codes and credentials are never added to this page's diagnostic log.
                feedback.text = error.message?.take(240)?.ifBlank { null } ?: "操作未完成，请检查连接状态后重试。"
            } finally {
                busy = false
                connectionButtons.forEach { it.isEnabled = true }
                refreshStatus()
            }
        }
    }

    private fun readHost(): String? = host.text.toString().trim().takeIf {
        it.isNotEmpty() && it.length <= 253 && !it.any(Char::isWhitespace) && '/' !in it && ':' !in it
    } ?: run { host.error = "请输入无线调试显示的手机地址，不要填写端口或网址"; null }

    private fun readPort(field: EditText): Int? = field.text.toString().trim().toIntOrNull()?.takeIf { it in 1..65535 }
        ?: run { field.error = "端口应为 1–65535"; null }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 701)
        }
    }

    private fun openSystemSettings(action: String, fallback: String? = null) {
        val intent = Intent(action)
        if (intent.resolveActivity(packageManager) != null) startActivity(intent)
        else if (fallback != null) startActivity(Intent(fallback))
        else feedback.text = "系统未提供此设置入口，请在手机设置中打开。"
    }

    private fun section(parent: LinearLayout, text: String) {
        parent.addView(label(text, 17f).apply { setPadding(0, dp(18), 0, dp(6)) })
    }

    private fun label(value: String, size: Float) = TextView(this).apply {
        text = value; textSize = size; setTextColor(Color.rgb(32, 33, 36)); setPadding(0, dp(5), 0, dp(5))
    }

    private fun button(value: String, action: () -> Unit) = Button(this).apply {
        text = value; isAllCaps = false; setOnClickListener { action() }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    private fun input(hint: String, initial: String, type: Int) = EditText(this).apply {
        this.hint = hint; inputType = type; setSingleLine(true); setText(initial)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun JsonObject.text(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull

    private fun humanState(value: String) = when (value.uppercase()) {
        "IDLE", "STOPPED", "NONE" -> "待机"
        "CONNECTING", "STARTING" -> "连接中"
        "RUNNING", "ACTIVE", "READY" -> "运行中"
        "PAUSED", "AWAITING_USER", "USER_TAKEOVER", "TAKEOVER" -> "等待你处理"
        "STOPPING" -> "正在停止"
        "ERROR", "FAILED" -> "操作未完成"
        else -> value.take(120)
    }
}
