package com.systemguard.mobi

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.util.Log
import androidx.core.content.edit

class MyAdminReceiver : DeviceAdminReceiver() {
    
    companion object {
        private var lastAttemptTime: Long = 0
    }

    override fun onPasswordFailed(context: Context, intent: Intent, user: android.os.UserHandle) {
        super.onPasswordFailed(context, intent, user)
        handlePasswordFailureInternal(context)
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onPasswordFailed(context: Context, intent: Intent) {
        super.onPasswordFailed(context, intent)
        handlePasswordFailureInternal(context)
    }

    private fun handlePasswordFailureInternal(context: Context) {
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastAttemptTime < 500) return
        lastAttemptTime = currentTime

        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TheftGuard::AdminWakeLock")
        wakeLock.acquire(3000L)

        val directBootContext = context.createDeviceProtectedStorageContext()
        val prefs = directBootContext.getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
        
        val currentCount = prefs.getInt("ManualFailedCount", 0)
        val failedCount = currentCount + 1
        prefs.edit(commit = true) { putInt("ManualFailedCount", failedCount) }

        Log.i("TheftGuard", "Failed Attempt Detected: $failedCount")

        val serviceIntent = Intent(context, TheftGuardService::class.java).apply {
            putExtra("attempt_count", failedCount)
            // ৫, ১০, ১৫... অথবা ১১-এর পর প্রতিবার অ্যালার্ম একশন পাঠানো হবে
            if (failedCount % 5 == 0 || failedCount >= 11) {
                action = "START_EMERGENCY_ALARM"
                Log.w("TheftGuard", "Triggering Emergency Alarm Action for attempt $failedCount")
            }
        }
        
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(serviceIntent)
            else context.startService(serviceIntent)
        } catch (e: Exception) {}
    }

    override fun onPasswordSucceeded(context: Context, intent: Intent, user: android.os.UserHandle) {
        super.onPasswordSucceeded(context, intent, user)
        handlePasswordSuccessInternal(context)
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onPasswordSucceeded(context: Context, intent: Intent) {
        super.onPasswordSucceeded(context, intent)
        handlePasswordSuccessInternal(context)
    }

    private fun handlePasswordSuccessInternal(context: Context) {
        val prefs = context.createDeviceProtectedStorageContext().getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
        prefs.edit(commit = true) { putInt("ManualFailedCount", 0) }

        val stopIntent = Intent(context, TheftGuardService::class.java).apply { action = "STOP_ALARM" }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(stopIntent)
            else context.startService(stopIntent)
        } catch (e: Exception) {}
    }
}
