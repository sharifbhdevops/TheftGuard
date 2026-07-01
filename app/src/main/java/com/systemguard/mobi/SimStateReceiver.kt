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
        val directBootContext = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            context.createDeviceProtectedStorageContext()
        } else {
            context
        }
        val sharedPreferences = directBootContext.getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
        val action = intent.action ?: return

        // আনলক হলে কাউন্টার ও অ্যালার্ম রিসেট (শুধুমাত্র USER_PRESENT ব্যবহার করা হলো)
        if (action == Intent.ACTION_USER_PRESENT) {
            sharedPreferences.edit(commit = true) { putInt("ManualFailedCount", 0) }
            context.startService(Intent(context, TheftGuardService::class.java).apply { this.action = "STOP_ALARM" })
            return
        }

        val stateExtra = intent.getStringExtra("ss")
        val simState = intent.getIntExtra("android.telephony.extra.SIM_STATE", -1)

        if (stateExtra == "LOADED" || stateExtra == "READY" || simState == 5) {
            sharedPreferences.edit(commit = true) { putBoolean("SimAbsentTriggered", false) }
            context.startService(Intent(context, TheftGuardService::class.java).apply { this.action = "STOP_ALARM_BY_SIM" })
        } else if (stateExtra == "ABSENT") {
            if (sharedPreferences.getBoolean("SimEnabled", false)) {
                val km = context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
                if (km.isKeyguardLocked && !sharedPreferences.getBoolean("SimAbsentTriggered", false)) {
                    sharedPreferences.edit(commit = true) { putBoolean("SimAbsentTriggered", true) }
                    context.startService(Intent(context, TheftGuardService::class.java).apply { this.action = "START_EMERGENCY_ALARM" })
                }
            }
        }
    }
}
