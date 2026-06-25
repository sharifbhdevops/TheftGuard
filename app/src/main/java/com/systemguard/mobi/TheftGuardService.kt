package com.systemguard.mobi

import android.Manifest
import android.app.AlarmManager
import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.MediaStore
import android.telephony.SubscriptionManager
import android.util.Log
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.lifecycle.LifecycleService
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.Tasks
import java.util.*
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.mail.*
import javax.mail.internet.InternetAddress
import javax.mail.internet.MimeBodyPart
import javax.mail.internet.MimeMessage
import javax.mail.internet.MimeMultipart

class TheftGuardService : LifecycleService() {

    companion object {
        private var mediaPlayer: android.media.MediaPlayer? = null
        private val isEmailSending = AtomicBoolean(false)
        private val isCapturing = AtomicBoolean(false)
        private var isEmergencyAlarmActive = false
        private var simReceiver: SimStateReceiver? = null
        private var wakeLock: PowerManager.WakeLock? = null
        
        fun start(context: Context) {
            val intent = Intent(context, TheftGuardService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                Log.e("TheftGuard", "Service resurrection failed: ${e.message}")
            }
        }
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var subscriptionManager: SubscriptionManager? = null

    // আনলক ডিটেকশন ওয়াচডগ: অ্যালার্ম চলাকালীন প্রতি ৫০০ms পর পর চেক করবে
    private val unlockWatchdog = object : Runnable {
        override fun run() {
            if (isEmergencyAlarmActive) {
                val km = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
                if (!km.isKeyguardLocked) {
                    Log.d("TheftGuard", "Watchdog detected device unlock! Stopping alarm.")
                    stopAlarm()
                } else {
                    mainHandler.postDelayed(this, 500)
                }
            }
        }
    }

    private val simCheckRunnable = object : Runnable {
        override fun run() {
            if (!isEmergencyAlarmActive) {
                checkSimStatus()
            }
            mainHandler.postDelayed(this, 30000)
        }
    }

    private val subListener = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
        object : SubscriptionManager.OnSubscriptionsChangedListener() {
            override fun onSubscriptionsChanged() {
                checkSimStatus()
            }
        }
    } else null

    override fun onCreate() {
        super.onCreate()
        acquireWakeLock()
        registerSimReceiver()
        startSubscriptionListener()
        // Removed redundant simCheckRunnable to prevent repeated false alarms
    }

    private fun acquireWakeLock() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TheftGuard::ServiceWakeLock")
        wakeLock?.acquire(10 * 60 * 60 * 1000L)
    }

    private fun startSubscriptionListener() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            subscriptionManager = getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as SubscriptionManager
            subscriptionManager?.addOnSubscriptionsChangedListener(subListener)
        }
    }

    private fun checkSimStatus() {
        val directBootContext = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            createDeviceProtectedStorageContext()
        } else {
            this
        }
        val sharedPreferences = directBootContext.getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
        if (!sharedPreferences.getBoolean("SimEnabled", false)) return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
            val activeList = subscriptionManager?.activeSubscriptionInfoList
            
            if (activeList.isNullOrEmpty()) {
                // SIM is absent
                if (keyguardManager.isKeyguardLocked) {
                    playEmergencyAlarm()
                }
            } else {
                // SIM is present
                if (isEmergencyAlarmActive) {
                    Log.d("TheftGuard", "SIM reinserted! Stopping alarm.")
                    stopAlarm()
                }
            }
        }
    }

    private fun registerSimReceiver() {
        if (simReceiver == null) {
            simReceiver = SimStateReceiver()
            val filter = android.content.IntentFilter().apply {
                addAction("android.intent.action.SIM_STATE_CHANGED")
                addAction("android.telephony.action.SIM_CARD_STATE_CHANGED")
                addAction(Intent.ACTION_USER_PRESENT)
                addAction(Intent.ACTION_SCREEN_ON)
                priority = 1000
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(simReceiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                registerReceiver(simReceiver, filter)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        
        // Ensure we are using Device Protected Storage for settings check during Direct Boot
        val directBootContext = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            createDeviceProtectedStorageContext()
        } else {
            this
        }
        val sharedPreferences = directBootContext.getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)

        val action = intent?.action
        if (action == "STOP_ALARM") {
            stopAlarm()
            if (!isEmailSending.get() && !isCapturing.get()) stopSelf()
            return START_NOT_STICKY
        }

        val notification = createNotification()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(1, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(1, notification)
            }
        } catch (e: Exception) {
            try { startForeground(1, notification) } catch (e2: Exception) {}
        }

        // If service is started without action (e.g. from boot receiver), check if it should be active
        val isSimEnabled = sharedPreferences.getBoolean("SimEnabled", false)
        val isCameraEnabled = sharedPreferences.getBoolean("CameraEnabled", false)
        val isAlarmEnabled = sharedPreferences.getBoolean("AlarmEnabled", false)
        val isGmailEnabled = sharedPreferences.getBoolean("GmailEnabled", false)
        
        if (!isSimEnabled && !isCameraEnabled && !isAlarmEnabled && !isGmailEnabled && action == null) {
            Log.d("TheftGuard", "No features enabled. Stopping service.")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
            stopSelf()
            return START_NOT_STICKY
        }

        if (action == "START_EMERGENCY_ALARM") {
            playEmergencyAlarm()
            return START_STICKY
        }

        val attempts = intent?.getIntExtra("attempt_count", 0) ?: 0
        if (attempts >= 4) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                try {
                    startForeground(1, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or 
                        android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION or
                        (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0))
                } catch (e: Exception) {}
            }
            captureAndProcess(attempts)
        }

        scheduleWatchdog()
        return START_STICKY
    }

    private fun scheduleWatchdog() {
        val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(this, TheftGuardService::class.java)
        val pendingIntent = PendingIntent.getService(this, 99, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val triggerTime = SystemClock.elapsedRealtime() + 5 * 60 * 1000
        
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && alarmManager.canScheduleExactAlarms()) {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerTime, pendingIntent)
            } else {
                alarmManager.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerTime, pendingIntent)
            }
        } catch (e: Exception) {
            alarmManager.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerTime, pendingIntent)
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        val restartServiceIntent = Intent(applicationContext, this.javaClass)
        restartServiceIntent.setPackage(packageName)
        val restartServicePendingIntent = PendingIntent.getService(applicationContext, 1, restartServiceIntent, PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE)
        val alarmService = applicationContext.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarmService.set(AlarmManager.ELAPSED_REALTIME, SystemClock.elapsedRealtime() + 500, restartServicePendingIntent)
        super.onTaskRemoved(rootIntent)
    }

    private fun createNotification(): Notification {
        val channelId = "TheftGuardServiceChannel"
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "TheftGuard Protection", NotificationManager.IMPORTANCE_LOW).apply {
                setShowBadge(false)
            }
            manager.createNotificationChannel(channel)
        }
        
        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("TheftGuard Active")
            .setContentText("Your device is protected")
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
    }

    private fun playLoudAlarm() {
        val keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        if (!keyguardManager.isKeyguardLocked) return

        try {
            if (mediaPlayer?.isPlaying == true) return
            val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            audioManager.setStreamVolume(AudioManager.STREAM_ALARM, audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0)
            val alarmUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            mediaPlayer = android.media.MediaPlayer().apply {
                setDataSource(applicationContext, alarmUri)
                setAudioAttributes(android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_ALARM).build())
                isLooping = true
                prepare()
                start()
            }
            val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as android.os.Vibrator
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(android.os.VibrationEffect.createWaveform(longArrayOf(0, 500, 200, 500), 0))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(longArrayOf(0, 500, 200, 500), 0)
            }
        } catch (e: Exception) { Log.e("TheftGuard", "Alarm error: ${e.message}") }
    }

    private fun playEmergencyAlarm() {
        if (isEmergencyAlarmActive) return
        isEmergencyAlarmActive = true
        
        // ওয়াচডগ শুরু করা যা আনলক ডিটেক্ট করবে
        mainHandler.post(unlockWatchdog)
        
        playLoudAlarm()
    }

    private fun stopAlarm() {
        isEmergencyAlarmActive = false 
        mainHandler.removeCallbacks(unlockWatchdog) // ওয়াচডগ থামিয়ে দেওয়া
        
        mediaPlayer?.let { 
            try {
                if (it.isPlaying) it.stop()
                it.reset()
                it.release()
            } catch (e: Exception) { }
        }
        mediaPlayer = null
        val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as android.os.Vibrator
        vibrator.cancel()
    }

    override fun onDestroy() {
        super.onDestroy()
        // Removed simCheckRunnable
        mainHandler.removeCallbacks(unlockWatchdog)
        if (wakeLock?.isHeld == true) wakeLock?.release()
        simReceiver?.let { try { unregisterReceiver(it) } catch (e: Exception) {} }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            subscriptionManager?.removeOnSubscriptionsChangedListener(subListener)
        }
    }

    private fun captureAndProcess(attempts: Int) {
        val directBootContext = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            createDeviceProtectedStorageContext()
        } else {
            this
        }
        val prefs = directBootContext.getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)

        if (prefs.getBoolean("AlarmEnabled", false) && attempts >= 5) playLoudAlarm()
        val captureEnabled = prefs.getBoolean("CameraEnabled", false)
        if (captureEnabled) {
            if (attempts == 4) takePhoto(prefs, attempts)
            else {
                Thread {
                    val loc = fetchLocationBlocking()
                    prefs.edit(commit = true) { putString("LastLocation", loc) }
                    ContextCompat.getMainExecutor(this).execute { takePhoto(prefs, attempts) }
                }.start()
            }
        }
    }

    private fun takePhoto(prefs: android.content.SharedPreferences, attempts: Int) {
        if (!prefs.getBoolean("CameraEnabled", false) || ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return
        if (isCapturing.get()) {
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ takePhoto(prefs, attempts) }, 2000)
            return
        }
        isCapturing.set(true)
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            try {
                val cameraProvider = cameraProviderFuture.get()
                val imageCapture = ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build()
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(this, CameraSelector.DEFAULT_FRONT_CAMERA, imageCapture)
                val contentValues = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, "Unlock_attempt_${attempts}_${System.currentTimeMillis()}.jpg")
                    put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) put(MediaStore.MediaColumns.RELATIVE_PATH, "DCIM/Camera")
                }
                val outputOptions = ImageCapture.OutputFileOptions.Builder(contentResolver, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues).build()
                imageCapture.takePicture(outputOptions, ContextCompat.getMainExecutor(this), object : ImageCapture.OnImageSavedCallback {
                    override fun onError(exc: ImageCaptureException) { isCapturing.set(false) }
                    override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                        isCapturing.set(false)
                        output.savedUri?.let { processAlerts(it, prefs, attempts) }
                    }
                })
            } catch (exc: Exception) { isCapturing.set(false) }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun processAlerts(savedUri: Uri, prefs: android.content.SharedPreferences, attempts: Int) {
        if (attempts == 4) prefs.edit(commit = true) { putString("LastPhoto4Uri", savedUri.toString()) }
        else if (attempts >= 5) {
            val email = prefs.getString("UserEmail", "")
            if (prefs.getBoolean("GmailEnabled", false) && !email.isNullOrEmpty()) {
                sendEmailWithUris(email, prefs.getString("LastPhoto4Uri", ""), savedUri.toString(), prefs.getString("LastLocation", "Not available") ?: "Not available")
            }
        }
    }

    private fun sendEmailWithUris(userEmail: String, uri1Str: String?, uri2Str: String, location: String) {
        if (isEmailSending.getAndSet(true)) return
        Thread {
            try {
                val scope = "oauth2:https://www.googleapis.com/auth/gmail.send"
                val accessToken = com.google.android.gms.auth.GoogleAuthUtil.getToken(this, userEmail, scope)
                val props = Properties().apply {
                    put("mail.smtp.host", "smtp.gmail.com")
                    put("mail.smtp.port", "465")
                    put("mail.smtp.ssl.enable", "true")
                    put("mail.smtp.auth", "true")
                    put("mail.smtp.auth.mechanisms", "XOAUTH2")
                }
                val session = Session.getInstance(props)
                val mm = MimeMessage(session).apply {
                    setFrom(InternetAddress(userEmail))
                    addRecipient(Message.RecipientType.TO, InternetAddress(userEmail))
                    subject = "TheftGuard Security Alert!"
                }
                val multipart = MimeMultipart()
                multipart.addBodyPart(MimeBodyPart().apply { setText("Intruder Location:\n$location\n\nPhotos attached.") })
                uri1Str?.let { if (it.isNotEmpty()) addUriAttachment(multipart, Uri.parse(it), "Unlock_attempt_4.jpg") }
                addUriAttachment(multipart, Uri.parse(uri2Str), "Unlock_attempt_5.jpg")
                mm.setContent(multipart)
                val transport = session.getTransport("smtp")
                transport.connect("smtp.gmail.com", userEmail, accessToken)
                transport.sendMessage(mm, mm.allRecipients)
                transport.close()
            } catch (e: Exception) { Log.e("TheftGuard", "Email error: ${e.message}") }
            finally { 
                isEmailSending.set(false)
                if (mediaPlayer?.isPlaying != true && !isCapturing.get()) stopSelf()
            }
        }.start()
    }

    private fun fetchLocationBlocking(): String {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return "No Permission"
        val client = LocationServices.getFusedLocationProviderClient(this)
        return try {
            val task = client.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
            val loc = Tasks.await(task, 7, TimeUnit.SECONDS)
            if (loc != null) "Lat: ${loc.latitude}, Lng: ${loc.longitude}\nMaps: https://www.google.com/maps/search/?api=1&query=${loc.latitude},${loc.longitude}"
            else "Location Timeout"
        } catch (e: Exception) { "Error" }
    }

    private fun addUriAttachment(multipart: MimeMultipart, uri: Uri, fileName: String) {
        try {
            contentResolver.openInputStream(uri)?.use { inputStream ->
                val bytes = inputStream.readBytes()
                val dataSource = object : javax.activation.DataSource {
                    override fun getInputStream() = java.io.ByteArrayInputStream(bytes)
                    override fun getOutputStream() = throw UnsupportedOperationException()
                    override fun getContentType() = "image/jpeg"
                    override fun getName() = fileName
                }
                multipart.addBodyPart(MimeBodyPart().apply {
                    dataHandler = javax.activation.DataHandler(dataSource)
                    this.fileName = fileName
                })
            }
        } catch (e: Exception) { }
    }
}
