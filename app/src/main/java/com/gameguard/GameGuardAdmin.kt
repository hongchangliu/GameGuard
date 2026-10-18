package com.gameguard
import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class GameGuardAdmin : DeviceAdminReceiver() {
    override fun onEnabled(context: Context, intent: Intent) {
        if (PolicyController(context).isOwner()) applyPolicy(context)
    }

    override fun onProfileProvisioningComplete(context: Context, intent: Intent) {
        applyPolicy(context)
    }

    private fun applyPolicy(context: Context) {
        try { PolicyController(context).enforce() }
        catch (e: Exception) { Log.e("GameGuard", "Provisioning policy failed", e) }
    }
}
