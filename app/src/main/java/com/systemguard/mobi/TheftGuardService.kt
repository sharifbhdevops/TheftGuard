package com.systemguard.mobi

import android.Manifest
import android.app.AlarmManager
import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.RingtoneManager
import android.net.ConnectivityManager
import android.net.Uri
import android.os.*
import android.provider.MediaStore
import android.telephony.SubscriptionManager
import android.util.Log
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.Tasks
import java.io.File
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.mail.*
import javax.mail.internet.InternetAddress
import javax.mail.internet.MimeBodyPart
import javax.mail.internet.MimeMessage
import javax.mail.internet.MimeMultipart

class TheftGuardService : Service(), LifecycleOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = lifecycleRegistry

    private val emailExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    companion object {
        private var mediaPlayer: android.media.MediaPlayer? = null
        private val isCapturing = AtomicBoolean(false)
        private var lastCaptureTime: Long = 0
        private var isEmergencyAlarmActive = false
        private var simReceiver: SimStateReceiver? = null
        private var wakeLock: PowerManager.WakeLock? = null
        
        fun start(context: Context) {
            Log.i("TheftGuard", "Static start() called")
            val intent = Intent(context, TheftGuardService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
                else context.startService(intent)
            } catch (e: Exception) { Log.e("TheftGuard", "Start failed: ${e.message}") }
        }
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var subscriptionManager: SubscriptionManager? = null

    private val sharedPreferences by lazy {
        createDeviceProtectedStorageContext().getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
    }

    private val unlockWatchdog = object : Runnable {
        override fun run() {
            if (isEmergencyAlarmActive) {
                val km = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
                if (!km.isKeyguardLocked) {
                    Log.i("TheftGuard", "Watchdog: Unlock detected! Cleaning up.")
                    resetAndStopEverything()
                } else {
                    mainHandler.postDelayed(this, 1000)
                }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Log.i("TheftGuard", "Service onCreate")
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        acquireWakeLock()
        registerSimReceiver()
        startSubscriptionListener()
    }

    private fun acquireWakeLock() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TheftGuard::WakeLock")
        if (wakeLock?.isHeld == false) {
            wakeLock?.acquire(10 * 60 * 1000L)
            Log.d("TheftGuard", "WakeLock acquired")
        }
    }

    private fun startSubscriptionListener() {
        subscriptionManager = getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as SubscriptionManager
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED) {
            subscriptionManager?.addOnSubscriptionsChangedListener(object : SubscriptionManager.OnSubscriptionsChangedListener() {
                override fun onSubscriptionsChanged() { 
                    Log.d("TheftGuard", "Subscriptions changed")
                    checkSimStatus() 
                }
            })
        }
    }

    private fun checkSimStatus() {
        if (!sharedPreferences.getBoolean("SimEnabled", false)) return
        val km = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED) {
            val activeList = subscriptionManager?.activeSubscriptionInfoList
            val isSimNowAbsent = activeList.isNullOrEmpty()
            val alreadyTriggered = sharedPreferences.getBoolean("SimAbsentTriggered", false)

            if (isSimNowAbsent) {
                if (km.isKeyguardLocked && !alreadyTriggered) {
                    Log.w("TheftGuard", "SIM ABSENT DETECTED!")
                    sharedPreferences.edit(commit = true) { putBoolean("SimAbsentTriggered", true) }
                    playLoudAlarm()
                    isEmergencyAlarmActive = true
                    mainHandler.post(unlockWatchdog)
                }
            } else {
                if (alreadyTriggered || isEmergencyAlarmActive) {
                    Log.i("TheftGuard", "SIM detected. Resetting.")
                    sharedPreferences.edit(commit = true) { putBoolean("SimAbsentTriggered", false) }
                    resetAndStopEverything()
                }
            }
        }
    }

    private fun registerSimReceiver() {
        if (simReceiver == null) {
            simReceiver = SimStateReceiver()
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_USER_PRESENT)
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
                priority = 1000
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) registerReceiver(simReceiver, filter, Context.RECEIVER_EXPORTED)
            else registerReceiver(simReceiver, filter)
            Log.d("TheftGuard", "SimReceiver registered")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        val action = intent?.action
        val attempts = intent?.getIntExtra("attempt_count", 0) ?: 0
        Log.i("TheftGuard", "onStartCommand: action=$action, attempts=$attempts")
        
        startForeground(1, createNotification())

        val km = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        
        if (action == "STOP_ALARM") {
            if (!km.isKeyguardLocked) {
                Log.i("TheftGuard", "STOP_ALARM action - Unlocked")
                sharedPreferences.edit(commit = true) { putInt("ManualFailedCount", 0) }
                stopAlarm()
            }
        }
        
        if (action == "STOP_ALARM_BY_SIM") {
            Log.i("TheftGuard", "STOP_ALARM_BY_SIM action")
            stopAlarm()
        }

        if (sharedPreferences.getBoolean("EmailPending", false) && isNetworkAvailable()) {
            Log.i("TheftGuard", "Retrying pending email on start")
            retryPendingEmail()
        }

        if (attempts >= 4) {
            Log.d("TheftGuard", "Processing attempts: $attempts")
            captureAndProcess(attempts)
        }
        
        if (action == "START_EMERGENCY_ALARM") {
            Log.w("TheftGuard", "START_EMERGENCY_ALARM action")
            playEmergencyAlarm()
        }

        scheduleWatchdog()
        return START_STICKY
    }

    private fun resetAndStopEverything() {
        Log.i("TheftGuard", "resetAndStopEverything() called")
        sharedPreferences.edit(commit = true) { putInt("ManualFailedCount", 0) }
        stopAlarm()
    }

    private fun isNetworkAvailable(): Boolean {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val netInfo = @Suppress("DEPRECATION") cm.activeNetworkInfo
        return (netInfo != null && netInfo.isConnected)
    }

    private fun retryPendingEmail() {
        val email = sharedPreferences.getString("UserEmail", "")
        val pathPrev = sharedPreferences.getString("LastPhotoPendingUri", "")
        val pathCurrent = sharedPreferences.getString("LastPhotoActionUri", "")
        val loc = sharedPreferences.getString("LastLocation", "Not available")
        val currentAttempts = sharedPreferences.getInt("ManualFailedCount", 0)
        
        if (!email.isNullOrEmpty() && !pathCurrent.isNullOrEmpty()) {
            Log.i("TheftGuard", "Executing pending email retry for attempt $currentAttempts")
            emailExecutor.execute { 
                sendEmailWithUris(email, pathPrev, pathCurrent, loc ?: "Not available", currentAttempts) 
            }
        }
    }

    private fun captureAndProcess(attempts: Int) {
        Log.i("TheftGuard", "captureAndProcess: Processing attempt $attempts")
        if (sharedPreferences.getBoolean("AlarmEnabled", false) && (attempts % 5 == 0 || attempts >= 11)) {
            Log.w("TheftGuard", "Alarm enabled, playing for attempt $attempts")
            playLoudAlarm()
        }
        if (sharedPreferences.getBoolean("CameraEnabled", false)) {
            // ৪, ৯... অথবা ৫, ১০... অথবা ১১-এর পর প্রতিবার
            if (attempts % 5 == 4 || attempts % 5 == 0 || attempts >= 11) {
                Log.d("TheftGuard", "Camera enabled, taking photo for attempt $attempts")
                takePhoto(attempts)
            }
        }
    }

    private fun takePhoto(attempts: Int) {
        val currentTime = System.currentTimeMillis()
        if (isCapturing.get() && (currentTime - lastCaptureTime > 7000)) {
            Log.w("TheftGuard", "Capture stuck? Resetting flag.")
            isCapturing.set(false)
        }
        if (isCapturing.get()) {
            Log.d("TheftGuard", "Already capturing, skipping duplicate call for $attempts")
            return
        }
        
        isCapturing.set(true)
        lastCaptureTime = currentTime
        Log.i("TheftGuard", "Starting camera capture for attempt $attempts")

        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            try {
                val cameraProvider = cameraProviderFuture.get()
                val imageCapture = ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build()
                
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(this, CameraSelector.DEFAULT_FRONT_CAMERA, imageCapture)

                val fileName = "Attempt_$attempts.jpg"
                val photoFile = File(getExternalFilesDir(null), fileName)
                val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()

                imageCapture.takePicture(outputOptions, ContextCompat.getMainExecutor(this), object : ImageCapture.OnImageSavedCallback {
                    override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                        Log.i("TheftGuard", "Photo saved to file for attempt $attempts")
                        isCapturing.set(false)
                        mainHandler.post { 
                            try { cameraProvider.unbindAll() } catch(e: Exception) {} 
                        }
                        saveToGallery(photoFile, attempts)
                    }
                    override fun onError(exc: ImageCaptureException) { 
                        isCapturing.set(false)
                        mainHandler.post { 
                            try { cameraProvider.unbindAll() } catch(e: Exception) {} 
                        }
                        Log.e("TheftGuard", "Camera picture error: ${exc.message}")
                    }
                })
            } catch (e: Exception) { 
                Log.e("TheftGuard", "Camera binding crash: ${e.message}")
                isCapturing.set(false)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun saveToGallery(sourceFile: File, attempts: Int) {
        Log.d("TheftGuard", "Saving photo to MediaStore for attempt $attempts")
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "Attempt_$attempts.jpg")
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) put(MediaStore.MediaColumns.RELATIVE_PATH, "DCIM/Camera")
        }
        val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
        uri?.let { destUri ->
            try {
                contentResolver.openOutputStream(destUri)?.use { out -> sourceFile.inputStream().use { it.copyTo(out) } }
                Log.i("TheftGuard", "Photo successfully visible in gallery: $destUri")
                handleFinalAlerts(destUri, attempts)
            } catch (e: Exception) { Log.e("TheftGuard", "Gallery save failed: ${e.message}") }
        }
    }

    private fun handleFinalAlerts(uri: Uri, attempts: Int) {
        if (attempts % 5 == 4 && attempts < 11) {
            sharedPreferences.edit(commit = true) { putString("LastPhotoPendingUri", uri.toString()) }
            Log.i("TheftGuard", "Buffer photo stored for attempt $attempts")
        } else if (attempts % 5 == 0 || attempts >= 11) {
            Log.i("TheftGuard", "Alert trigger reached at attempt $attempts")
            sharedPreferences.edit(commit = true) { putString("LastPhotoActionUri", uri.toString()) }
            val email = sharedPreferences.getString("UserEmail", "")
            if (sharedPreferences.getBoolean("GmailEnabled", false) && !email.isNullOrEmpty()) {
                emailExecutor.execute {
                    try {
                        Log.i("TheftGuard", "Starting location fetch for alert at attempt $attempts...")
                        val loc = fetchLocationBlocking()
                        sharedPreferences.edit(commit = true) { putString("LastLocation", loc) }
                        
                        val pendingPhotoUri = sharedPreferences.getString("LastPhotoPendingUri", null)
                        
                        if (isNetworkAvailable()) {
                            sendEmailWithUris(email, pendingPhotoUri, uri.toString(), loc, attempts)
                        } else {
                            Log.w("TheftGuard", "Network offline for attempt $attempts. Marking as pending.")
                            sharedPreferences.edit(commit = true) { putBoolean("EmailPending", true) }
                        }

                        // ১১ নম্বর চেষ্টার পর প্রতিবারই ফটো রোটেট করা (যাতে পরের মেইলে কারেন্টটি প্রিভিয়াস হিসেবে থাকে)
                        if (attempts >= 10) {
                            sharedPreferences.edit(commit = true) { putString("LastPhotoPendingUri", uri.toString()) }
                        }
                    } catch (e: Exception) {
                        Log.e("TheftGuard", "handleFinalAlerts thread crash: ${e.message}")
                    }
                }
            } else {
                Log.w("TheftGuard", "Email disabled or email address empty for attempt $attempts.")
            }
        }
    }

    private fun sendEmailWithUris(userEmail: String, uri1: String?, uri2: String, location: String, attempts: Int) {
        Log.i("TheftGuard", "sendEmailWithUris: Preparing to send mail for attempt $attempts")
        try {
            val scope = "oauth2:https://www.googleapis.com/auth/gmail.send"
            val accessToken = com.google.android.gms.auth.GoogleAuthUtil.getToken(this, userEmail, scope)
            Log.d("TheftGuard", "OAuth Token obtained")

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
                addRecipient(javax.mail.Message.RecipientType.TO, InternetAddress(userEmail))
                subject = "TheftGuard Security Alert! Attempt $attempts"
            }
            
            val multipart = MimeMultipart()
            val textPart = MimeBodyPart()
            textPart.setText("Intruder Location:\n$location\n\nLatest photos attached.")
            multipart.addBodyPart(textPart)
            
            uri1?.let { 
                try { 
                    Log.d("TheftGuard", "Attaching prev photo: $it")
                    addUriAttachment(multipart, Uri.parse(it), "Attempt_${attempts - 1}.jpg") 
                } catch (e: Exception) { Log.e("TheftGuard", "Fail attach prev: ${e.message}") } 
            }
            
            try {
                Log.d("TheftGuard", "Attaching current photo: $uri2")
                addUriAttachment(multipart, Uri.parse(uri2), "Attempt_$attempts.jpg")
            } catch (e: Exception) { Log.e("TheftGuard", "Fail attach current: ${e.message}") }
            
            mm.setContent(multipart)
            val transport = session.getTransport("smtp")
            transport.connect("smtp.gmail.com", userEmail, accessToken)
            transport.sendMessage(mm, mm.allRecipients)
            transport.close()
            
            sharedPreferences.edit(commit = true) { putBoolean("EmailPending", false) }
            Log.i("TheftGuard", "EMAIL SENT SUCCESS for attempt $attempts!")
        } catch (e: Exception) { 
            Log.e("TheftGuard", "EMAIL FAILED for attempt $attempts: ${e.message}") 
            sharedPreferences.edit(commit = true) { putBoolean("EmailPending", true) }
        }
    }

    private fun addUriAttachment(multipart: MimeMultipart, uri: Uri, fileName: String) {
        contentResolver.openInputStream(uri)?.use { inputStream ->
            val bytes = inputStream.readBytes()
            val dataSource = object : javax.activation.DataSource {
                override fun getInputStream() = java.io.ByteArrayInputStream(bytes)
                override fun getOutputStream() = throw UnsupportedOperationException()
                override fun getContentType() = "image/jpeg"
                override fun getName() = fileName
            }
            val bodyPart = MimeBodyPart()
            bodyPart.dataHandler = javax.activation.DataHandler(dataSource)
            bodyPart.fileName = fileName
            multipart.addBodyPart(bodyPart)
            Log.d("TheftGuard", "Attachment added: $fileName")
        }
    }

    private fun fetchLocationBlocking(): String {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return "No Permission"
        val client = LocationServices.getFusedLocationProviderClient(this)
        return try {
            Log.d("TheftGuard", "Waiting for GPS...")
            val task = client.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
            val loc = Tasks.await(task, 12, TimeUnit.SECONDS)
            if (loc != null) {
                Log.i("TheftGuard", "GPS Location fixed")
                "Lat: ${loc.latitude}, Lng: ${loc.longitude}\nMaps: https://www.google.com/maps/search/?api=1&query=${loc.latitude},${loc.longitude}"
            } else {
                Log.w("TheftGuard", "GPS Timeout")
                "Location Timeout"
            }
        } catch (e: Exception) { 
            Log.e("TheftGuard", "Location Error: ${e.message}")
            "Location Error" 
        }
    }

    private fun scheduleWatchdog() {
        val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(this, RestartReceiver::class.java).apply { action = "HEARTBEAT_RESTART" }
        val pendingIntent = PendingIntent.getBroadcast(this, 99, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val triggerTime = SystemClock.elapsedRealtime() + 60000 
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
        val restartIntent = Intent(applicationContext, RestartReceiver::class.java).apply { action = "RESTART_THEFTGUARD" }
        val pendingIntent = PendingIntent.getBroadcast(applicationContext, 1, restartIntent, PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE)
        val alarmService = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarmService.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + 1000, pendingIntent)
        super.onTaskRemoved(rootIntent)
    }

    private fun createNotification(): Notification {
        val channelId = "TheftGuardChannel"
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) manager.createNotificationChannel(NotificationChannel(channelId, "Protection", NotificationManager.IMPORTANCE_LOW))
        val intent = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, channelId).setContentTitle("TheftGuard Active").setContentText("Your device is protected").setSmallIcon(android.R.drawable.ic_lock_lock).setContentIntent(intent).setOngoing(true).build()
    }

    private fun playLoudAlarm() {
        try {
            if (mediaPlayer?.isPlaying == true) return
            val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            audioManager.setStreamVolume(AudioManager.STREAM_ALARM, audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0)
            val alarmUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            mediaPlayer = android.media.MediaPlayer().apply { 
                setDataSource(applicationContext, alarmUri)
                setAudioAttributes(android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_ALARM).build())
                isLooping = true; prepare(); start() 
            }
        } catch (e: Exception) {
            Log.e("TheftGuard", "Alarm play failed: ${e.message}")
        }
    }

    private fun playEmergencyAlarm() {
        if (!isEmergencyAlarmActive && sharedPreferences.getBoolean("AlarmEnabled", false)) {
            isEmergencyAlarmActive = true
            mainHandler.post(unlockWatchdog)
            playLoudAlarm()
            Log.w("TheftGuard", "Emergency Alarm Triggered")
        }
    }

    private fun stopAlarm() { 
        isEmergencyAlarmActive = false
        mainHandler.removeCallbacks(unlockWatchdog)
        mediaPlayer?.let { try { it.stop(); it.release() } catch (e: Exception) {} }
        mediaPlayer = null 
        Log.i("TheftGuard", "Alarm stopped")
    }

    override fun onDestroy() { 
        Log.i("TheftGuard", "Service onDestroy")
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        emailExecutor.shutdown()
        super.onDestroy() 
    }
}
