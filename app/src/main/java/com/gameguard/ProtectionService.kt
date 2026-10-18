package com.gameguard

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log

/** Keeps package-change observation alive while the device owner is active. */
class ProtectionService : Service() {
    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            try {
                val policy = PolicyController(context)
                when (intent.action) {
                    Intent.ACTION_PACKAGE_ADDED -> {
                        val name = intent.data?.schemeSpecificPart ?: return
                        policy.onPackageAdded(name, intent.getBooleanExtra(Intent.EXTRA_REPLACING, false))
                    }
                    Intent.ACTION_PACKAGE_REMOVED -> {
                        val name = intent.data?.schemeSpecificPart ?: return
                        policy.onPackageRemoved(name, intent.getBooleanExtra(Intent.EXTRA_REPLACING, false))
                    }
                }
            } catch (e: Exception) {
                Log.e("GameGuard", "Package policy update failed", e)
                PolicyController(context).setMessage("设备策略执行失败：${e.message}")
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(
            CHANNEL_ID, "应用保护", NotificationManager.IMPORTANCE_LOW
        ))
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentTitle("应用使用管理")
            .setContentText("正在检查新安装的应用")
            .setOngoing(true)
            .build()
        startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED)
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addDataScheme("package")
        }
        registerReceiver(packageReceiver, filter, Context.RECEIVER_EXPORTED)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val policy = PolicyController(this)
        if (!policy.isOwner() || policy.isPausedForRemoval()) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onDestroy() {
        unregisterReceiver(packageReceiver)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL_ID = "app_protection"
        private const val NOTIFICATION_ID = 1001

        fun start(context: Context) {
            context.startForegroundService(Intent(context, ProtectionService::class.java))
        }
    }
}
