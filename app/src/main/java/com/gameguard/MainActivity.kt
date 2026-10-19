package com.gameguard

import android.app.Activity
import android.app.AlertDialog
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInstaller
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.view.WindowInsets
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.time.LocalDate

class MainActivity : Activity() {
    private lateinit var policy: PolicyController
    private lateinit var credentials: AdminCredentials
    private lateinit var content: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        policy = PolicyController(this)
        credentials = AdminCredentials(this)
        policy.enforceIfStale()
        val scroll = ScrollView(this)
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(content)
        scroll.setOnApplyWindowInsetsListener { view, insets ->
            val top: Int
            val bottom: Int
            if (Build.VERSION.SDK_INT >= 30) {
                val bars = insets.getInsets(WindowInsets.Type.systemBars())
                top = bars.top; bottom = bars.bottom
            } else {
                @Suppress("DEPRECATION")
                top = insets.systemWindowInsetTop
                @Suppress("DEPRECATION")
                bottom = insets.systemWindowInsetBottom
            }
            view.setPadding(dp(16), top + dp(16), dp(16), bottom + dp(16))
            insets
        }
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        window.statusBarColor = Color.WHITE
        setContentView(scroll)
        scroll.requestApplyInsets()
        refresh()
    }

    override fun onResume() {
        super.onResume()
        if (::content.isInitialized) {
            policy.enforceIfStale()
            refresh()
        }
    }

    private fun refresh() {
        content.removeAllViews()
        text("应用使用管理", 24f)
        text("设备所有者：${if (policy.isOwner()) "已启用" else "未启用"}\n" +
            "全局禁止安装：${if (policy.isInstallBlocked()) "已启用" else "未启用"}\n" +
            "设置中恢复出厂设置：${if (policy.isFactoryResetBlocked()) "已限制" else "未限制"}", 17f)
        if (policy.lastMessage().isNotBlank()) text(policy.lastMessage(), 14f)
        if (!credentials.isSet()) {
            text("请先设置管理员密码（至少 8 位）。忘记后无法在应用内重置。", 15f)
            button("设置管理员密码") { setupPassword() }
        } else button("修改管理员密码") { changePassword() }

        if (!policy.isOwner()) {
            text("当前只显示规则，不会限制这部手机。设备所有者模式请先在专用测试设备验证。", 15f)
        } else if (policy.isPausedForRemoval()) {
            text("设备策略已暂停，请输入管理员密码恢复。", 15f)
            if (credentials.isSet()) button("恢复设备策略") {
                requestAdmin("恢复设备策略") {
                    try { policy.resumeProtection(); refresh() }
                    catch (e: Exception) { showError(e.message ?: "恢复失败") }
                }
            }
        } else if (credentials.isSet()) {
            button("管理员手动安装 APK（可选）") { chooseApk() }
            text("非黑名单应用可从应用市场正常安装；黑名单应用安装后会禁止运行。", 14f)
            val blocked = policy.isFactoryResetBlocked()
            button(if (blocked) "管理员允许恢复出厂设置" else "管理员限制恢复出厂设置") {
                requestAdmin(if (blocked) "允许从系统设置恢复出厂设置" else "限制从系统设置恢复出厂设置") {
                    try { policy.setFactoryResetBlocked(!blocked); refresh() }
                    catch (e: Exception) { showError(e.message ?: "更新失败") }
                }
            }
            text("此开关只限制系统设置中的恢复出厂设置入口。", 14f)
        }

        if (policy.isOwner() && credentials.isSet() && !policy.isPausedForRemoval()) {
            showUsageReportControls()
        }

        text("应用黑名单", 20f)
        showNameKeywordControls()
        for (name in policy.unresolvedLegacyPackages().sorted()) {
            text("旧包名规则无法确定应用名称：$name；请先补充名称关键字。", 15f)
            if (policy.isOwner() && credentials.isSet() && !policy.isPausedForRemoval()) {
                button("移除旧规则：$name") {
                    requestAdmin("移除旧规则 $name") {
                        try { policy.removeAbsentBlock(name); refresh() }
                        catch (e: Exception) { showError(e.message ?: "操作失败") }
                    }
                }
            }
        }
        if (policy.isOwner() && credentials.isSet() && !policy.isPausedForRemoval()) showInstalledApps()
    }

    private fun showUsageReportControls() {
        val reports = UsageReportStore(this)
        val mail = DailyMail(this)
        text("每日使用记录", 20f)
        text("记录打开过的应用和前台时间；${if (mail.address().isBlank()) "配置邮箱后可每日汇总" else "每日汇总发送到 ${mail.address()}"}。", 14f)
        text("使用情况访问权限：${if (reports.hasAccess()) "已开启" else "未开启"}\n" +
            "每日邮件：${if (mail.isConfigured()) "已配置" else "未配置"}", 15f)
        if (!reports.hasAccess()) button("开启使用情况访问权限") {
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        }
        if (reports.hasAccess()) {
            button("查看今日记录") { showUsageReport(LocalDate.now(), false) }
            button("查看昨日记录") { showUsageReport(LocalDate.now().minusDays(1), true) }
        }
        if (!mail.isConfigured()) button("设置 QQ 邮箱授权码") {
            requestAdmin("配置每日邮件") { configureDailyMail() }
        } else {
            button("立即发送今日记录") {
                requestAdmin("发送今日使用记录") { sendUsageReport(LocalDate.now(), false) }
            }
            button("立即发送昨日记录") {
                requestAdmin("发送昨日使用记录") { sendUsageReport(LocalDate.now().minusDays(1), true) }
            }
            button("停止每日邮件") {
                requestAdmin("停止每日邮件") {
                    try { mail.clear(); refresh() }
                    catch (e: Exception) { showError(e.message ?: "操作失败") }
                }
            }
        }
    }

    private fun showNameKeywordControls() {
        text("名称关键字", 18f)
        text("应用名称包含关键字时禁止运行；移除关键字后，若没有其他规则命中会自动放行。", 14f)
        if (policy.isOwner() && credentials.isSet() && !policy.isPausedForRemoval()) {
            button("新增名称关键字") { promptNameKeyword() }
        }
        for (keyword in policy.nameKeywords().sorted()) {
            text("关键字：$keyword", 15f)
            if (policy.isOwner() && credentials.isSet() && !policy.isPausedForRemoval()) {
                button("移除关键字：$keyword") {
                    requestAdmin("移除关键字 $keyword") {
                        try { policy.removeNameKeyword(keyword); refresh() }
                        catch (e: Exception) { showError(e.message ?: "移除失败") }
                    }
                }
            }
        }
    }

    private fun promptNameKeyword() {
        val input = EditText(this).apply {
            hint = "输入应用名称中的关键字（至少 2 个字）"
            inputType = InputType.TYPE_CLASS_TEXT
        }
        AlertDialog.Builder(this).setTitle("新增名称关键字")
            .setView(fields(input)).setNegativeButton("取消", null)
            .setPositiveButton("下一步") { _, _ ->
                val keyword = input.text.toString().trim()
                requestAdmin("确认添加关键字：$keyword") {
                    try { policy.addNameKeyword(keyword); refresh() }
                    catch (e: Exception) { showError(e.message ?: "添加失败") }
                }
            }.show()
    }

    private fun showUsageReport(date: LocalDate, save: Boolean) {
        Thread {
            try {
                val store = UsageReportStore(this)
                val report = if (save) store.save(date) else store.build(date)
                runOnUiThread {
                    AlertDialog.Builder(this).setTitle(if (save) "昨日使用记录" else "今日使用记录")
                        .setMessage(report).setPositiveButton("确定", null).show()
                }
            } catch (e: Exception) {
                runOnUiThread { showError(e.message ?: "读取记录失败") }
            }
        }.start()
    }

    private fun configureDailyMail() {
        val email = EditText(this).apply {
            hint = "QQ 邮箱地址"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
            setText(DailyMail(this@MainActivity).address())
        }
        val input = passwordField("QQ 邮箱 SMTP 授权码，不是 QQ 密码")
        AlertDialog.Builder(this).setTitle("配置每日邮件")
            .setView(fields(email, input)).setNegativeButton("取消", null)
            .setPositiveButton("保存") { _, _ ->
                try {
                    DailyMail(this).configure(email.text.toString(), input.text.toString())
                    input.text.clear()
                    refresh()
                } catch (e: Exception) { showError(e.message ?: "保存失败") }
            }.show()
    }

    private fun sendUsageReport(date: LocalDate, completedDay: Boolean) {
        Toast.makeText(this, if (completedDay) "正在发送昨日记录" else "正在发送今日记录", Toast.LENGTH_SHORT).show()
        Thread {
            try {
                val store = UsageReportStore(this)
                val report = if (completedDay) store.save(date) else store.build(date)
                DailyMail(this).send(date.toString(), report)
                if (completedDay) DailyMail(this).markSent(date.toString())
                runOnUiThread {
                    Toast.makeText(this, "已发送到 ${DailyMail(this).address()}", Toast.LENGTH_LONG).show()
                    refresh()
                }
            } catch (e: Exception) {
                runOnUiThread { showError(e.message ?: "发送失败") }
            }
        }.start()
    }

    private fun showInstalledApps() {
        val blocked = policy.blockedPackages()
        val apps = packageManager.getInstalledApplications(0)
            .filter { it.packageName != packageName && it.flags and ApplicationInfo.FLAG_SYSTEM == 0 }
            .sortedBy { it.loadLabel(packageManager).toString().lowercase() }
        for (app in apps) {
            val name = app.loadLabel(packageManager).toString()
            val isBlocked = app.packageName in blocked
            text("$name · ${if (isBlocked) "禁止运行" else "正常运行"}\n${app.packageName}", 15f)
            button(if (isBlocked) "管理员审核放行" else "按应用名称加入关键字黑名单") {
                requestAdmin("更改 $name 的运行策略") {
                    try {
                        if (isBlocked) policy.allowPackage(app.packageName)
                        else policy.addNameKeyword(name)
                        refresh()
                    }
                    catch (e: Exception) { showError(e.message ?: "策略执行失败") }
                }
            }
        }
    }

    private fun setupPassword() {
        val first = passwordField("管理员密码")
        val second = passwordField("再次输入")
        val dialog = AlertDialog.Builder(this).setTitle("设置管理员密码")
            .setView(fields(first, second)).setNegativeButton("取消", null)
            .setPositiveButton("保存", null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val password = first.text.toString()
                if (password.length < 8) { first.error = "至少 8 位"; return@setOnClickListener }
                if (password != second.text.toString()) { second.error = "两次输入不一致"; return@setOnClickListener }
                try {
                    credentials.create(password)
                    if (policy.isOwner()) policy.enforce()
                    dialog.dismiss(); refresh()
                } catch (e: Exception) { showError(e.message ?: "保存失败") }
            }
        }
        dialog.show()
    }

    private fun changePassword() {
        val old = passwordField("当前密码")
        val next = passwordField("新密码，至少 8 位")
        val confirm = passwordField("再次输入新密码")
        val dialog = AlertDialog.Builder(this).setTitle("修改管理员密码")
            .setView(fields(old, next, confirm)).setNegativeButton("取消", null)
            .setPositiveButton("保存", null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (next.text.length < 8) { next.error = "至少 8 位"; return@setOnClickListener }
                if (next.text.toString() != confirm.text.toString()) {
                    confirm.error = "两次输入不一致"; return@setOnClickListener
                }
                try {
                    if (!credentials.change(old.text.toString(), next.text.toString())) {
                        old.error = credentials.lockMessage(); return@setOnClickListener
                    }
                    dialog.dismiss(); Toast.makeText(this, "密码已更新", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) { showError(e.message ?: "修改失败") }
            }
        }
        dialog.show()
    }

    private fun requestAdmin(title: String, onCancelled: () -> Unit = {}, onAuthorized: () -> Unit) {
        val input = passwordField("管理员密码")
        val dialog = AlertDialog.Builder(this).setTitle(title).setView(fields(input))
            .setNegativeButton("取消") { _, _ -> onCancelled() }
            .setPositiveButton("确认", null).create()
        dialog.setOnCancelListener { onCancelled() }
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (!credentials.verify(input.text.toString())) {
                    input.error = credentials.lockMessage(); return@setOnClickListener
                }
                dialog.dismiss(); onAuthorized()
            }
        }
        dialog.show()
    }

    private fun chooseApk() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        @Suppress("DEPRECATION")
        startActivityForResult(intent, REQUEST_APK)
    }

    @Deprecated("The project has no AndroidX dependency")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_APK || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        Thread {
            val file = File(cacheDir, "review-${System.currentTimeMillis()}.apk")
            try {
                contentResolver.openInputStream(uri).use { input ->
                    requireNotNull(input) { "无法读取所选文件" }
                    file.outputStream().use { output ->
                        val buffer = ByteArray(32 * 1024)
                        var total = 0L
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            total += count
                            require(total <= MAX_APK_BYTES) { "APK 超过 512 MB" }
                            output.write(buffer, 0, count)
                        }
                        require(total > 0) { "APK 文件为空" }
                    }
                }
                val info = packageManager.getPackageArchiveInfo(file.path, 0)
                    ?: error("所选文件不是有效的单文件 APK")
                val name = info.packageName
                require(name != packageName) { "不能通过审核安装替换应用使用管理" }
                require(name !in policy.blockedPackages()) { "此游戏已在黑名单中" }
                runOnUiThread {
                    AlertDialog.Builder(this).setTitle("审核 APK")
                        .setMessage("包名：$name\n大小：${file.length() / 1024 / 1024} MB\n请确认来源可信。")
                        .setNegativeButton("取消") { _, _ -> file.delete() }
                        .setPositiveButton("输入管理员密码") { _, _ ->
                            requestAdmin("批准安装 $name", onCancelled = { file.delete() }) {
                                installReviewedApk(file, name)
                            }
                        }.setOnCancelListener { file.delete() }.show()
                }
            } catch (e: Exception) {
                file.delete()
                runOnUiThread { showError(e.message ?: "读取 APK 失败") }
            }
        }.start()
    }

    private fun installReviewedApk(file: File, name: String) {
        Thread {
            val installer = packageManager.packageInstaller
            var sessionId = -1
            var approvalStarted = false
            try {
                val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
                    .apply { setAppPackageName(name); setSize(file.length()) }
                sessionId = installer.createSession(params)
                installer.openSession(sessionId).use { session ->
                    session.openWrite("base.apk", 0, file.length()).use { output ->
                        file.inputStream().use { it.copyTo(output) }
                        session.fsync(output)
                    }
                    policy.beginApprovedInstall(name)
                    approvalStarted = true
                    val result = Intent(this, PolicyReceiver::class.java).apply {
                        action = PolicyReceiver.ACTION_INSTALL_RESULT
                        putExtra(PolicyReceiver.EXTRA_PACKAGE, name)
                    }
                    val sender = PendingIntent.getBroadcast(this, sessionId, result,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE).intentSender
                    session.commit(sender)
                }
                runOnUiThread { Toast.makeText(this, "已提交安装", Toast.LENGTH_LONG).show() }
            } catch (e: Exception) {
                if (approvalStarted) policy.finishApprovedInstall(name, false)
                if (sessionId >= 0) runCatching { installer.abandonSession(sessionId) }
                runOnUiThread { showError(e.message ?: "安装失败") }
            } finally { file.delete() }
        }.start()
    }

    private fun passwordField(hint: String) = EditText(this).apply {
        this.hint = hint
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
    }

    private fun fields(vararg inputs: EditText) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(20), 0, dp(20), 0)
        inputs.forEach { addView(it) }
    }

    private fun text(value: String, size: Float) {
        content.addView(TextView(this).apply {
            text = value; textSize = size; setTextColor(Color.DKGRAY)
            setPadding(0, dp(10), 0, dp(10))
        })
    }

    private fun button(label: String, action: () -> Unit) {
        content.addView(Button(this).apply { text = label; setOnClickListener { action() } })
    }

    private fun showError(message: String) {
        AlertDialog.Builder(this).setTitle("操作未完成").setMessage(message).setPositiveButton("知道了", null).show()
        refresh()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        const val REQUEST_APK = 1
        const val MAX_APK_BYTES = 512L * 1024 * 1024
    }
}
