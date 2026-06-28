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
        // ৫০০ মিলি-সেকেন্ডের গ্যাপ (যাতে দ্রুত চেষ্টাগুলো মিস না হয়)
        if (currentTime - lastAttemptTime < 500) return
        lastAttemptTime = currentTime

        // Wake up CPU immediately
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TheftGuard::AdminWakeLock")
        wakeLock.acquire(3000L)

        val directBootContext = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            context.createDeviceProtectedStorageContext()
        } else {
            context
        }
        val prefs = directBootContext.getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
        
        val currentCount = prefs.getInt("ManualFailedCount", 0)
        val failedCount = currentCount + 1
        prefs.edit(commit = true) { putInt("ManualFailedCount", failedCount) }

        Log.d("TheftGuard", "Wrong Pattern! Count: $failedCount")

        val serviceIntent = Intent(context, TheftGuardService::class.java).apply {
            putExtra("attempt_count", failedCount)
            if (failedCount >= 5) {
                action = "START_EMERGENCY_ALARM"
            }
        }
        
        startSecurityService(context, serviceIntent)
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
        val directBootContext = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            context.createDeviceProtectedStorageContext()
        } else {
            context
        }
        val prefs = directBootContext.getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
        
        // সফল আনলক হলে কাউন্টার ১০০% রিসেট হবে
        prefs.edit(commit = true) { putInt("ManualFailedCount", 0) }
        
        Log.d("TheftGuard", "Correct Password! Counter reset to 0.")

        val stopIntent = Intent(context, TheftGuardService::class.java).apply {
            action = "STOP_ALARM"
        }
        startSecurityService(context, stopIntent)
    }

    private fun startSecurityService(context: Context, intent: Intent) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        } catch (e: Exception) {
            Log.e("TheftGuard", "Admin failed to start service: ${e.message}")
        }
    }

    override fun onEnabled(context: Context, intent: Intent) {
        super.onEnabled(context, intent)
        // অ্যাডমিন এনাবল করার সময় কাউন্টার ০ করে দেওয়া
        val prefs = context.createDeviceProtectedStorageContext().getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
        prefs.edit(commit = true) { putInt("ManualFailedCount", 0) }
    }
}
