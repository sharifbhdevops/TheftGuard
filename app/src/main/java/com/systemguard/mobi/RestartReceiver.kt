package com.systemguard.mobi

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

class RestartReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        Log.d("TheftGuard", "Master RestartReceiver: $action")
        
        // রিবুট বা আনলক—যেটাই হোক, আমরা সার্ভিস স্টার্ট করবো
        val directBootContext = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            context.createDeviceProtectedStorageContext()
        } else {
            context
        }
        
        val prefs = directBootContext.getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
        val isAnyFeatureEnabled = prefs.getBoolean("SimEnabled", false) || 
                                 prefs.getBoolean("CameraEnabled", false) ||
                                 prefs.getBoolean("AlarmEnabled", false) ||
                                 prefs.getBoolean("GmailEnabled", false)

        if (isAnyFeatureEnabled) {
            TheftGuardService.start(context)
        }
    }
}
