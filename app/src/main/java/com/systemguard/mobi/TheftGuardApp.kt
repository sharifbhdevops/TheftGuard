package com.systemguard.mobi

import android.app.Application
import android.content.Context
import android.os.Build
import androidx.work.*
import java.util.concurrent.TimeUnit

class TheftGuardApp : Application() {
    override fun onCreate() {
        super.onCreate()
        
        try {
            val directBootContext = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                createDeviceProtectedStorageContext()
            } else {
                this
            }
            val prefs = directBootContext.getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
            val isEnabled = prefs.getBoolean("SimEnabled", false) ||
                            prefs.getBoolean("CameraEnabled", false) ||
                            prefs.getBoolean("AlarmEnabled", false) ||
                            prefs.getBoolean("GmailEnabled", false)

            if (isEnabled) {
                TheftGuardService.start(this)
                scheduleKeepAlive()
            }
        } catch (e: Exception) {
            // Prevent crash on app startup
        }
    }

    private fun scheduleKeepAlive() {
        val workRequest = OneTimeWorkRequestBuilder<TheftGuardWorker>()
            .setInitialDelay(1, TimeUnit.SECONDS)
            .build()
        
        WorkManager.getInstance(this).enqueueUniqueWork(
            "TheftGuardKeepAlive",
            ExistingWorkPolicy.KEEP,
            workRequest
        )
    }
}
