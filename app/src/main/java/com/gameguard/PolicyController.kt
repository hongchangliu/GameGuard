package com.gameguard

import android.app.admin.DevicePolicyManager
import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.UserManager
import android.os.Build
import java.util.Locale

/** All device policy writes go through this class. No policy is changed without Device Owner. */
class PolicyController(private val context: Context) {
    private val dpm = context.getSystemService(DevicePolicyManager::class.java)
    private val admin = ComponentName(context, GameGuardAdmin::class.java)
    private val prefs = context.createDeviceProtectedStorageContext()
        .getSharedPreferences("policy", Context.MODE_PRIVATE)

    init {
        if (!prefs.getBoolean("keyword_rules_migrated", false)) {
            val keywords = nameKeywords().toMutableSet()
            if (!prefs.getBoolean("delta_seeded", false)) keywords.addAll(DEFAULT_NAME_KEYWORDS)
            val unresolved = mutableSetOf<String>()
            val installed = userPackages()
            for (packageName in blockedPackages()) {
                val knownKeyword = LEGACY_PACKAGE_KEYWORDS[packageName]
                if (knownKeyword != null) {
                    keywords.add(knownKeyword)
                    continue
                }
                val label = if (packageName in installed) applicationLabel(packageName) else null
                if (label != null && label.codePointCount(0, label.length) in 2..40) {
                    keywords.add(label.lowercase(Locale.ROOT))
                } else {
                    unresolved.add(packageName)
                }
            }
            check(prefs.edit().putStringSet("name_keywords", keywords)
                .putStringSet("unresolved_legacy_packages", unresolved)
                .putBoolean("keyword_rules_migrated", true).commit())
        }
    }

    fun isOwner(): Boolean = dpm.isDeviceOwnerApp(context.packageName)
    fun isPausedForRemoval(): Boolean = prefs.getBoolean("removal_prepared", false)

    fun isInstallBlocked(): Boolean = context.getSystemService(UserManager::class.java)
        .hasUserRestriction(UserManager.DISALLOW_INSTALL_APPS)

    fun lastMessage(): String = prefs.getString("last_message", "") ?: ""

    fun setMessage(message: String) {
        prefs.edit().putString("last_message", message).apply()
    }

    fun blockedPackages(): Set<String> = prefs.getStringSet("blocked", emptySet())!!.toSet()
    fun nameKeywords(): Set<String> = prefs.getStringSet("name_keywords", emptySet())!!.toSet()
    fun unresolvedLegacyPackages(): Set<String> =
        prefs.getStringSet("unresolved_legacy_packages", emptySet())!!.toSet()
    fun quarantinedPackages(): Set<String> = prefs.getStringSet("quarantined", emptySet())!!.toSet()

    fun addNameKeyword(raw: String) {
        require(isOwner() && !isPausedForRemoval()) { "需要已启用的设备所有者策略" }
        val keyword = raw.trim().lowercase(Locale.ROOT)
        require(keyword.codePointCount(0, keyword.length) in 2..40) { "关键字需为 2 到 40 个字" }
        require(keyword.none { Character.isISOControl(it) }) { "关键字不能包含控制字符" }
        addToSet("name_keywords", keyword)
        enforce()
        setMessage("已添加名称关键字：$keyword")
    }

    fun removeNameKeyword(keyword: String) {
        require(isOwner() && !isPausedForRemoval()) { "需要已启用的设备所有者策略" }
        removeFromSet("name_keywords", keyword)
        enforce()
        setMessage("已移除名称关键字：$keyword")
    }

    /** Called after provisioning, updates, and boot. Normal apps can be installed directly. */
    fun enforce(scheduleNextAudit: Boolean = true) {
        if (!isOwner() || isPausedForRemoval()) return
        // Remove the previous release's global restriction so non-blacklisted apps can install.
        dpm.clearUserRestriction(admin, UserManager.DISALLOW_INSTALL_APPS)
        dpm.setShortSupportMessage(admin, "请和爸爸联系")
        dpm.setLongSupportMessage(admin, "请和爸爸联系")
        if (!prefs.getBoolean("baseline_ready", false)) {
            val existing = userPackages()
            prefs.edit().putStringSet("known", existing).putBoolean("baseline_ready", true).commit()
        }
        dpm.setUninstallBlocked(admin, context.packageName, true)
        if (Build.VERSION.SDK_INT >= 30) {
            try { dpm.setUserControlDisabledPackages(admin, listOf(context.packageName)) }
            catch (e: Exception) { setMessage("无法保护应用使用管理的数据：${e.message}") }
        }
        val legacyQuarantine = quarantinedPackages() - blockedPackages()
        if (legacyQuarantine.isNotEmpty()) {
            dpm.setPackagesSuspended(admin, legacyQuarantine.toTypedArray(), false)
        }
        prefs.edit().remove("quarantined").remove("pending_install")
            .remove("pending_started").commit()
        reconcileSuspensions()
        try { ProtectionService.start(context) }
        catch (e: Exception) { setMessage("实时安装监控启动失败：${e.message}") }
        if (scheduleNextAudit) scheduleAudit()
        DailyReportJobService.schedule(context)
        if (lastMessage().startsWith("设备策略执行失败：")) setMessage("")
    }

    fun enforceIfStale() = enforce()

    fun beginApprovedInstall(packageName: String) {
        require(isOwner() && !isPausedForRemoval()) { "需要已启用的设备所有者策略" }
        require(packageName != context.packageName) { "不能安装应用使用管理本身" }
        // This optional APK route still requires a local password, but no global gate is opened.
    }

    fun finishApprovedInstall(packageName: String, success: Boolean) {
        if (!isOwner() || isPausedForRemoval()) return
        if (success) {
            addToSet("known", packageName)
            enforce()
            setMessage(if (packageName in blockedPackages())
                "安装完成，已按名称或游戏规则禁止运行 $packageName" else "已审核安装 $packageName")
        } else {
            setMessage("安装未完成")
        }
    }

    fun onPackageAdded(packageName: String, replacing: Boolean) {
        if (!isOwner() || isPausedForRemoval()) return
        if (packageName == context.packageName || isSystemPackage(packageName)) return
        if (!replacing) addToSet("known", packageName)
        enforce(scheduleNextAudit = false)
    }

    fun onPackageRemoved(packageName: String, replacing: Boolean) {
        if (!replacing) {
            removeFromSet("known", packageName)
            removeFromSet("quarantined", packageName)
            removeFromSet("blocked", packageName)
        }
    }

    fun allowPackage(packageName: String) {
        require(isOwner() && !isPausedForRemoval()) { "需要已启用的设备所有者策略" }
        require(packageName != context.packageName && !isSystemPackage(packageName)) {
            "不能更改系统应用或应用使用管理"
        }
        val failed = dpm.setPackagesSuspended(admin, arrayOf(packageName), false)
        check(failed.isEmpty()) { "系统拒绝更改此应用的运行状态" }
        if (isGamePackage(packageName)) addToSet("allowed_games", packageName)
        if (matchesNameKeyword(packageName)) addToSet("allowed_keyword_packages", packageName)
        removeFromSet("unresolved_legacy_packages", packageName)
        removeFromSet("blocked", packageName)
        removeFromSet("quarantined", packageName)
        addToSet("known", packageName)
        enforce()
        setMessage("已审核放行 $packageName")
    }

    fun removeAbsentBlock(packageName: String) {
        require(isOwner() && !isPausedForRemoval()) { "需要已启用的设备所有者策略" }
        require(packageName !in userPackages()) { "应用已安装，请从应用列表审核放行" }
        removeFromSet("unresolved_legacy_packages", packageName)
        removeFromSet("blocked", packageName)
        setMessage("已移除无法识别名称的旧规则 $packageName")
    }

    fun userPackages(): Set<String> = context.packageManager.getInstalledApplications(0)
        .filter { it.packageName != context.packageName && it.flags and ApplicationInfo.FLAG_SYSTEM == 0 }
        .map { it.packageName }.toSet()

    fun resumeProtection() {
        require(isOwner()) { "需要设备所有者权限" }
        check(prefs.edit().putBoolean("removal_prepared", false).commit())
        enforce()
        setMessage("设备策略已恢复")
    }

    private fun isSystemPackage(packageName: String): Boolean = try {
        context.packageManager.getApplicationInfo(packageName, 0).flags and ApplicationInfo.FLAG_SYSTEM != 0
    } catch (_: android.content.pm.PackageManager.NameNotFoundException) {
        true
    }

    private fun reconcileSuspensions() {
        val installed = userPackages()
        val desired = installed.filterTo(mutableSetOf()) {
            it in unresolvedLegacyPackages() ||
                (isGamePackage(it) && it !in allowedGames()) ||
                (matchesNameKeyword(it) && it !in allowedKeywordPackages())
        }
        val previous = blockedPackages()
        val toRelease = (previous - desired).intersect(installed)
        val failedRelease = if (toRelease.isEmpty()) emptySet() else
            dpm.setPackagesSuspended(admin, toRelease.toTypedArray(), false).toSet()
        val failedBlock = if (desired.isEmpty()) emptySet() else
            dpm.setPackagesSuspended(admin, desired.toTypedArray(), true).toSet()
        check(prefs.edit().putStringSet("blocked", desired + failedRelease).commit())
        if (failedRelease.isNotEmpty() || failedBlock.isNotEmpty()) {
            setMessage("以下应用无法更新运行状态：${(failedRelease + failedBlock).joinToString()}")
        }
    }

    private fun scheduleAudit() {
        val scheduler = context.getSystemService(JobScheduler::class.java)
        if (scheduler.getPendingJob(AUDIT_JOB_ID) != null) return
        val job = JobInfo.Builder(AUDIT_JOB_ID, ComponentName(context, PolicyAuditJobService::class.java))
            .setPeriodic(15 * 60 * 1000L)
            .setPersisted(true)
            .build()
        if (scheduler.schedule(job) != JobScheduler.RESULT_SUCCESS) {
            setMessage("无法安排黑名单定期核对")
        }
    }

    private fun allowedGames(): Set<String> = prefs.getStringSet("allowed_games", emptySet())!!.toSet()
    private fun allowedKeywordPackages(): Set<String> =
        prefs.getStringSet("allowed_keyword_packages", emptySet())!!.toSet()

    private fun applicationLabel(packageName: String): String? = try {
        context.packageManager.getApplicationInfo(packageName, 0)
            .loadLabel(context.packageManager).toString().trim()
    } catch (_: android.content.pm.PackageManager.NameNotFoundException) {
        null
    }

    private fun matchesNameKeyword(packageName: String): Boolean {
        val label = applicationLabel(packageName)?.lowercase(Locale.ROOT) ?: return false
        return nameKeywords().any { it in label }
    }

    private fun isGamePackage(packageName: String): Boolean = try {
        context.packageManager.getApplicationInfo(packageName, 0).let { app ->
            app.category == ApplicationInfo.CATEGORY_GAME ||
                app.flags and ApplicationInfo.FLAG_IS_GAME != 0
        }
    } catch (_: android.content.pm.PackageManager.NameNotFoundException) {
        false
    }

    private fun addToSet(key: String, value: String) {
        val values = prefs.getStringSet(key, emptySet())!!.toMutableSet()
        values.add(value)
        check(prefs.edit().putStringSet(key, values).commit())
    }

    private fun removeFromSet(key: String, value: String) {
        val values = prefs.getStringSet(key, emptySet())!!.toMutableSet()
        if (values.remove(value)) check(prefs.edit().putStringSet(key, values).commit())
    }

    companion object {
        const val AUDIT_JOB_ID = 1
        private val DEFAULT_NAME_KEYWORDS = setOf(
            "三角洲行动", "快手", "快手极速版", "抖音", "抖音极速版",
            "今日头条", "今日头条极速版", "哔哩哔哩", "蛋仔派对",
            "和平精英", "无畏契约", "王者荣耀", "迷你枪战精英"
        )
        /** Used once to migrate old package-based rules; never consulted for new installs. */
        private val LEGACY_PACKAGE_KEYWORDS = mapOf(
            "com.tencent.tmgp.dfm" to "三角洲行动",
            "com.smile.gifmaker" to "快手",
            "com.kuaishou.nebula" to "快手极速版",
            "com.ss.android.ugc.aweme" to "抖音",
            "com.ss.android.ugc.aweme.lite" to "抖音极速版",
            "com.ss.android.article.news" to "今日头条",
            "com.ss.android.article.lite" to "今日头条极速版",
            "tv.danmaku.bili" to "哔哩哔哩",
            "com.netease.party" to "蛋仔派对",
            "com.netease.party.meta" to "蛋仔派对",
            "com.netease.party.honor" to "蛋仔派对",
            "com.tencent.tmgp.pubgmhd" to "和平精英",
            "com.tencent.tmgp.codev" to "无畏契约",
            "com.tencent.tmgp.sgame" to "王者荣耀",
            "com.miniworldroyale.sparkgame.honor" to "迷你枪战精英"
        )
    }
}
