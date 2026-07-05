package com.systemguard.mobi

import android.content.Context
import androidx.work.*
import android.util.Log
import java.util.concurrent.TimeUnit

class TheftGuardWorker(context: Context, workerParams: WorkerParameters) : Worker(context, workerParams) {
    override fun doWork(): Result {
        // ১. সার্ভিস স্টার্ট করা
        TheftGuardService.start(applicationContext)
        
        // ২. ২ মিনিট পর নিজেকে আবার সিডিউল করা (অ্যান্ড্রয়েডের ১৫ মিনিটের লিমিট বাইপাস)
        val nextWork = OneTimeWorkRequestBuilder<TheftGuardWorker>()
            .setInitialDelay(2, TimeUnit.MINUTES)
            .build()
            
        WorkManager.getInstance(applicationContext).enqueueUniqueWork(
            "TheftGuardKeepAlive",
            ExistingWorkPolicy.REPLACE,
            nextWork
        )

        return Result.success()
    }
}
