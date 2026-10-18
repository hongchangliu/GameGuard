package com.gameguard

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.app.admin.DevicePolicyManager
import android.util.Log

class PolicyReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val policy = PolicyController(context)
        try {
            when (intent.action) {
                Intent.ACTION_LOCKED_BOOT_COMPLETED,
                Intent.ACTION_BOOT_COMPLETED,
                Intent.ACTION_MY_PACKAGE_REPLACED,
                DevicePolicyManager.ACTION_DEVICE_OWNER_CHANGED,
                ACTION_RELOCK -> policy.enforce()

                Intent.ACTION_PACKAGE_ADDED -> {
                    val name = intent.data?.schemeSpecificPart ?: return
                    policy.onPackageAdded(name, intent.getBooleanExtra(Intent.EXTRA_REPLACING, false))
                }

                Intent.ACTION_PACKAGE_REMOVED -> {
                    val name = intent.data?.schemeSpecificPart ?: return
                    policy.onPackageRemoved(name, intent.getBooleanExtra(Intent.EXTRA_REPLACING, false))
                }

                ACTION_INSTALL_RESULT -> {
                    val name = intent.getStringExtra(EXTRA_PACKAGE) ?: return
                    val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
                    policy.finishApprovedInstall(name, status == PackageInstaller.STATUS_SUCCESS)
                    if (status != PackageInstaller.STATUS_SUCCESS) {
                        policy.setMessage("安装失败：${intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: status}")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("GameGuard", "Policy event failed: ${intent.action}", e)
            policy.setMessage("设备策略执行失败：${e.message}")
            // A later boot, alarm, or Activity resume retries the restrictive policy.
        }
    }

    companion object {
        const val ACTION_RELOCK = "com.gameguard.RELOCK"
        const val ACTION_INSTALL_RESULT = "com.gameguard.INSTALL_RESULT"
        const val EXTRA_PACKAGE = "package_name"
    }
}
