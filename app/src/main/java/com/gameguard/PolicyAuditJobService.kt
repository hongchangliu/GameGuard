package com.gameguard

import android.app.job.JobParameters
import android.app.job.JobService
import android.util.Log

/** Package-added broadcasts are not delivered reliably to manifest receivers on modern Android. */
class PolicyAuditJobService : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        Thread {
            try {
                PolicyController(this).enforce(scheduleNextAudit = false)
            } catch (e: Exception) {
                Log.e("GameGuard", "Scheduled policy audit failed", e)
                PolicyController(this).setMessage("黑名单定期核对失败：${e.message}")
            } finally {
                jobFinished(params, false)
            }
        }.start()
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean = true
}
