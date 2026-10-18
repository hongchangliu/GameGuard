package com.gameguard

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.util.Log
import java.time.LocalDate

class DailyReportJobService : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        Thread {
            try {
                val today = LocalDate.now()
                val yesterday = today.minusDays(1)
                val reports = UsageReportStore(this)
                if (reports.hasAccess()) {
                    val mail = DailyMail(this)
                    if (!mail.isConfigured()) {
                        reports.save(yesterday)
                    } else {
                        val lastSent = mail.lastSentDate()?.let { LocalDate.parse(it) }
                        var date = maxOf(today.minusDays(3), lastSent?.plusDays(1) ?: yesterday)
                        while (!date.isAfter(yesterday)) {
                            val report = reports.save(date)
                            mail.send(date.toString(), report)
                            mail.markSent(date.toString())
                            date = date.plusDays(1)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("GameGuard", "Daily usage report failed", e)
                PolicyController(this).setMessage("每日使用报告失败：${e.message}")
            } finally {
                jobFinished(params, false)
            }
        }.start()
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean = false

    companion object {
        private const val JOB_ID = 2

        fun schedule(context: Context) {
            val scheduler = context.getSystemService(JobScheduler::class.java)
            if (scheduler.getPendingJob(JOB_ID) != null) return
            val job = JobInfo.Builder(JOB_ID, ComponentName(context, DailyReportJobService::class.java))
                .setPeriodic(24 * 60 * 60 * 1000L, 60 * 60 * 1000L)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPersisted(true)
                .build()
            check(scheduler.schedule(job) == JobScheduler.RESULT_SUCCESS) { "无法安排每日邮件任务" }
        }
    }
}
