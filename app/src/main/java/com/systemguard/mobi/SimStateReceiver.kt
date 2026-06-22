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

        // আনলক ডিটেকশন (সবচেয়ে নির্ভুল পদ্ধতি অ্যালার্ম অফ করার জন্য)
        if (action == Intent.ACTION_USER_PRESENT || action == Intent.ACTION_SCREEN_ON) {
            val keyguardManager = context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
            // যদি ফোন সত্যি আনলক হয়ে থাকে (User Present) অথবা স্ক্রিন অন হওয়ার পর লক না থাকে
            if (action == Intent.ACTION_USER_PRESENT || !keyguardManager.isKeyguardLocked) {
                Log.d("TheftGuard", "Device Unlocked! Stopping alarm...")
                val stopIntent = Intent(context, TheftGuardService::class.java).apply {
                    this.action = "STOP_ALARM"
                }
                context.startService(stopIntent)
                return
            }
        }

        // সিম সুরক্ষা অন আছে কি না চেক
        if (!sharedPreferences.getBoolean("SimEnabled", false)) return

        // সিম কার্ড খোলা হয়েছে কি না তা চেক (সরাসরি ABSENT স্টেট ধরবে)
        val stateExtra = intent.getStringExtra("ss")
        if (stateExtra == "ABSENT") {
            val keyguardManager = context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
            if (keyguardManager.isKeyguardLocked) {
                Log.d("TheftGuard", "SIM Unplugged while locked! Starting Emergency Alarm.")
                val serviceIntent = Intent(context, TheftGuardService::class.java).apply {
                    this.action = "START_EMERGENCY_ALARM"
                }
                context.startService(serviceIntent)
            }
        }
    }
}
