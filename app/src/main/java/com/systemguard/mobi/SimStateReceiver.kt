package com.systemguard.mobi

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class SimStateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val sharedPreferences = context.getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
        val action = intent.action ?: return
        
        Log.d("TheftGuard", "Receiver caught action: $action")

        // ১. আনলক ডিটেকশন (অ্যালার্ম বন্ধের জন্য)
        if (action == Intent.ACTION_USER_PRESENT || action == Intent.ACTION_SCREEN_ON) {
            val keyguardManager = context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
            if (action == Intent.ACTION_USER_PRESENT || !keyguardManager.isKeyguardLocked) {
                Log.d("TheftGuard", "Device Unlocked! Stopping alarm...")
                val stopIntent = Intent(context, TheftGuardService::class.java).apply {
                    this.action = "STOP_ALARM"
                }
                context.startService(stopIntent)
            }
            
            // যদি প্রোটেকশন অন থাকে কিন্তু সার্ভিস বন্ধ থাকে, তবে জাগিয়ে তোলা
            val isSimEnabled = sharedPreferences.getBoolean("SimEnabled", false)
            val isCameraEnabled = sharedPreferences.getBoolean("CameraEnabled", false)
            if (isSimEnabled || isCameraEnabled) {
                TheftGuardService.start(context)
            }
            return
        }

        // ২. সিম সুরক্ষা চেক
        if (!sharedPreferences.getBoolean("SimEnabled", false)) return

        val stateExtra = intent.getStringExtra("ss")
        if (stateExtra == "ABSENT" || action == "android.intent.action.BOOT_COMPLETED") {
            val keyguardManager = context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
            if (keyguardManager.isKeyguardLocked) {
                Log.d("TheftGuard", "SIM Absence or Boot detected while locked!")
                val serviceIntent = Intent(context, TheftGuardService::class.java).apply {
                    this.action = "START_EMERGENCY_ALARM"
                }
                context.startService(serviceIntent)
            }
        }
    }
}
