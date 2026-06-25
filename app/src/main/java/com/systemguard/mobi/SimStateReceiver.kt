package com.systemguard.mobi

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.content.edit

class SimStateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // Use Device Protected Storage for Direct Boot support
        val directBootContext = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            context.createDeviceProtectedStorageContext()
        } else {
            context
        }
        val sharedPreferences = directBootContext.getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
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

        val stateExtra = intent.getStringExtra("ss") // for SIM_STATE_CHANGED
        val simState = intent.getIntExtra("android.telephony.extra.SIM_STATE", -1) // for SIM_CARD_STATE_CHANGED

        // ১. সিম লাগানো বা রেডি হলে অ্যালার্ম বন্ধ করা
        if (stateExtra == "LOADED" || stateExtra == "READY" || simState == 5 /* SIM_STATE_READY */) {
            Log.d("TheftGuard", "SIM detected/ready! Stopping alarm...")
            sharedPreferences.edit { putBoolean("SimAbsentTriggered", false) } // রিসেট
            val stopIntent = Intent(context, TheftGuardService::class.java).apply {
                this.action = "STOP_ALARM"
            }
            context.startService(stopIntent)
            
            if (action == "android.intent.action.BOOT_COMPLETED") {
                TheftGuardService.start(context)
            }
            return
        }

        // ২. সিম না থাকলে অ্যালার্ম বাজানো (শুধুমাত্র লক স্ক্রিনে এবং একবার)
        if (stateExtra == "ABSENT") {
            val alreadyTriggered = sharedPreferences.getBoolean("SimAbsentTriggered", false)
            if (alreadyTriggered) {
                Log.d("TheftGuard", "SIM already absent and triggered. Ignoring.")
                return
            }

            val keyguardManager = context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
            if (keyguardManager.isKeyguardLocked) {
                Log.d("TheftGuard", "SIM Absent detected while locked!")
                sharedPreferences.edit { putBoolean("SimAbsentTriggered", true) }
                val serviceIntent = Intent(context, TheftGuardService::class.java).apply {
                    this.action = "START_EMERGENCY_ALARM"
                }
                context.startService(serviceIntent)
            }
        }
        
        // ৩. বুট কমপ্লিট হলে সার্ভিস চালু করা
        if (action == "android.intent.action.BOOT_COMPLETED" || action == "android.intent.action.LOCKED_BOOT_COMPLETED") {
            Log.d("TheftGuard", "Boot completed ($action)! Resurrecting service.")
            TheftGuardService.start(context)
        }
    }
}
