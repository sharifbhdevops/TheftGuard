package com.systemguard.mobi

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.core.content.edit

class RestartReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        Log.i("TheftGuard", "RestartReceiver triggered: $action")
        
        val directBootContext = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            context.createDeviceProtectedStorageContext()
        } else {
            context
        }
        val prefs = directBootContext.getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)

        // আনলক হলে কাউন্টার রিসেট
        if (action == Intent.ACTION_USER_PRESENT || action == "android.intent.action.USER_UNLOCKED") {
            prefs.edit(commit = true) { putInt("ManualFailedCount", 0) }
            Log.d("TheftGuard", "Counter reset on unlock.")
        }

        // সুরক্ষা সচল থাকলে সার্ভিস স্টার্ট করা
        val isEnabled = prefs.getBoolean("SimEnabled", false) || 
                        prefs.getBoolean("CameraEnabled", false) ||
                        prefs.getBoolean("AlarmEnabled", false) ||
                        prefs.getBoolean("GmailEnabled", false)

        if (isEnabled) {
            forceStartService(context)
        }
    }

    private fun forceStartService(context: Context) {
        val serviceIntent = Intent(context, TheftGuardService::class.java)
        val pendingIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            PendingIntent.getForegroundService(context, 1001, serviceIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        } else {
            PendingIntent.getService(context, 1001, serviceIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }
        
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val triggerTime = SystemClock.elapsedRealtime() + 1000

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarmManager.canScheduleExactAlarms()) {
                alarmManager.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerTime, pendingIntent)
            } else {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerTime, pendingIntent)
            }
        } catch (e: Exception) {
            Log.e("TheftGuard", "Alarm start failed: ${e.message}")
            TheftGuardService.start(context)
        }
    }
}
