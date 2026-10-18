package com.gameguard

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Process
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Records foreground app launches. Android does not expose every background process here. */
class UsageReportStore(private val context: Context) {
    private val zone = ZoneId.systemDefault()
    private val timeFormat = DateTimeFormatter.ofPattern("HH:mm")
    private val reportDir = File(context.filesDir, "usage_reports")

    fun hasAccess(): Boolean {
        val appOps = context.getSystemService(AppOpsManager::class.java)
        return appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(), context.packageName) == AppOpsManager.MODE_ALLOWED
    }

    fun build(date: LocalDate): String {
        check(hasAccess()) { "请先为应用使用管理开启“使用情况访问权限”" }
        val start = date.atStartOfDay(zone).toInstant().toEpochMilli()
        val end = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val usage = context.getSystemService(UsageStatsManager::class.java)
        val events = usage.queryEvents(start, end) ?: error("手机解锁后才能读取使用记录")
        val launches = sortedMapOf<String, MutableList<Long>>()
        val event = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (event.eventType == UsageEvents.Event.ACTIVITY_RESUMED &&
                event.packageName != context.packageName && isThirdParty(event.packageName)) {
                launches.getOrPut(event.packageName) { mutableListOf() }.add(event.timeStamp)
            }
        }
        return buildString {
            appendLine("应用使用记录 · $date")
            appendLine("记录范围：当天切换到前台的第三方应用；不含后台进程或应用内浏览内容。")
            appendLine("应用数：${launches.size}")
            appendLine()
            if (launches.isEmpty()) appendLine("没有记录到前台应用事件。")
            for ((packageName, times) in launches) {
                val label = runCatching {
                    context.packageManager.getApplicationLabel(
                        context.packageManager.getApplicationInfo(packageName, 0)).toString()
                }.getOrDefault(packageName)
                fun clock(ms: Long) = Instant.ofEpochMilli(ms).atZone(zone).format(timeFormat)
                appendLine("$label ($packageName)：${times.size} 次前台事件，首次 ${clock(times.first())}，末次 ${clock(times.last())}")
            }
        }
    }

    private fun isThirdParty(packageName: String): Boolean = try {
        context.packageManager.getApplicationInfo(packageName, 0).flags and ApplicationInfo.FLAG_SYSTEM == 0
    } catch (_: android.content.pm.PackageManager.NameNotFoundException) {
        true
    }

    fun save(date: LocalDate): String {
        val report = build(date)
        check(reportDir.exists() || reportDir.mkdirs()) { "无法创建记录目录" }
        File(reportDir, "$date.txt").writeText(report, Charsets.UTF_8)
        reportDir.listFiles()?.filter { it.name.endsWith(".txt") }
            ?.sortedByDescending { it.name }?.drop(30)?.forEach { it.delete() }
        return report
    }
}
