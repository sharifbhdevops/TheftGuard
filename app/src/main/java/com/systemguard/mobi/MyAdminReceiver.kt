package com.systemguard.mobi

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.content.edit

class MyAdminReceiver : DeviceAdminReceiver() {
    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onPasswordFailed(context: Context, intent: Intent) {
        super.onPasswordFailed(context, intent)

        val prefs = context.getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
        val failedCount = prefs.getInt("ManualFailedCount", 0) + 1
        prefs.edit { putInt("ManualFailedCount", failedCount) }

        Log.d("TheftGuard", "Manual Failed attempts: $failedCount")

        if (failedCount >= 4) {
            val serviceIntent = Intent(context, TheftGuardService::class.java).apply {
                putExtra("attempt_count", failedCount)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
        }
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onPasswordSucceeded(context: Context, intent: Intent) {
        super.onPasswordSucceeded(context, intent)
        val prefs = context.getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
        prefs.edit { putInt("ManualFailedCount", 0) }
        
        // Stop the alarm when phone is unlocked
        val stopIntent = Intent(context, TheftGuardService::class.java).apply {
            action = "STOP_ALARM"
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(stopIntent)
        } else {
            context.startService(stopIntent)
        }
        
        Log.d("TheftGuard", "Correct Password! Counter reset and alarm stopped.")
    }
}
