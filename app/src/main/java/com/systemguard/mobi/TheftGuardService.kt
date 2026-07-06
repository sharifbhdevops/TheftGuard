package com.systemguard.mobi

import android.Manifest
import android.app.*
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
import androidx.lifecycle.LifecycleService
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.Tasks
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
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

class TheftGuardService : LifecycleService() {

    private val emailExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    companion object {
        private var mediaPlayer: android.media.MediaPlayer? = null
        private val isCapturing = AtomicBoolean(false)
        private var lastCaptureTime: Long = 0
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
                Log.e("TheftGuard", "Service start failed: ${e.message}")
            }
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
                    resetAndStopEverything()
                } else {
                    mainHandler.postDelayed(this, 1000)
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        acquireWakeLock()
        registerSimReceiver()
        startSubscriptionListener()
    }

    private fun acquireWakeLock() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TheftGuard::WakeLock")
        if (wakeLock?.isHeld == false) {
            wakeLock?.acquire(5 * 60 * 1000L)
        }
    }

    private fun startSubscriptionListener() {
        subscriptionManager = getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as SubscriptionManager
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED) {
            subscriptionManager?.addOnSubscriptionsChangedListener(object : SubscriptionManager.OnSubscriptionsChangedListener() {
                override fun onSubscriptionsChanged() { 
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
            if (activeList.isNullOrEmpty() && km.isKeyguardLocked && !sharedPreferences.getBoolean("SimAbsentTriggered", false)) {
                sharedPreferences.edit(commit = true) { putBoolean("SimAbsentTriggered", true) }
                playLoudAlarm()
                isEmergencyAlarmActive = true
                mainHandler.post(unlockWatchdog)
            } else if (!activeList.isNullOrEmpty() && (sharedPreferences.getBoolean("SimAbsentTriggered", false) || isEmergencyAlarmActive)) {
                sharedPreferences.edit(commit = true) { putBoolean("SimAbsentTriggered", false) }
                resetAndStopEverything()
            }
        }
    }

    private fun registerSimReceiver() {
        if (simReceiver == null) {
            simReceiver = SimStateReceiver()
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_USER_PRESENT)
                priority = 1000
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) registerReceiver(simReceiver, filter, Context.RECEIVER_EXPORTED)
            else registerReceiver(simReceiver, filter)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        val action = intent?.action
        val attempts = intent?.getIntExtra("attempt_count", 0) ?: 0
        
        val notification = createNotification()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                var fgsType = android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                if (attempts >= 4) {
                    if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) fgsType = fgsType or android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
                    if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) fgsType = fgsType or android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
                }
                startForeground(1, notification, fgsType)
            } else {
                startForeground(1, notification)
            }
        } catch (e: Exception) { }

        val km = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        if (action == "STOP_ALARM") {
            if (!km.isKeyguardLocked) resetAndStopEverything()
        }
        
        if (action == "STOP_ALARM_BY_SIM") stopAlarm()

        // ১, ২, ৩ নম্বর ভুল হলে কিছুই হবে না
        if (attempts >= 4) {
            captureAndProcess(attempts)
        }
        
        if (action == "START_EMERGENCY_ALARM") playEmergencyAlarm()

        scheduleWatchdog()
        return START_STICKY
    }

    private fun resetAndStopEverything() {
        sharedPreferences.edit(commit = true) { 
            putInt("ManualFailedCount", 0)
            putBoolean("EmailPending", false)
            putString("LastPhotoPendingUri", null)
        }
        stopAlarm()
    }

    private fun isNetworkAvailable(): Boolean {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val netInfo = @Suppress("DEPRECATION") cm.activeNetworkInfo
        return (netInfo != null && netInfo.isConnected)
    }

    private fun captureAndProcess(attempts: Int) {
        if (sharedPreferences.getBoolean("AlarmEnabled", false) && (attempts == 5 || attempts == 10 || attempts >= 11)) {
            playLoudAlarm()
        }
        
        if (sharedPreferences.getBoolean("CameraEnabled", false)) {
            if (attempts == 4 || attempts == 5 || attempts == 9 || attempts == 10 || attempts >= 11) {
                mainHandler.postDelayed({ takePhoto(attempts) }, 500)
            }
        }
    }

    private fun takePhoto(attempts: Int) {
        val currentTime = System.currentTimeMillis()
        if (isCapturing.get() && (currentTime - lastCaptureTime > 7000)) isCapturing.set(false)
        if (isCapturing.get()) {
            mainHandler.postDelayed({ takePhoto(attempts) }, 2000)
            return
        }
        
        isCapturing.set(true)
        lastCaptureTime = currentTime

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
                        isCapturing.set(false)
                        mainHandler.post { try { cameraProvider.unbindAll() } catch(e: Exception) {} }
                        saveToGallery(photoFile, attempts)
                    }
                    override fun onError(exc: ImageCaptureException) { isCapturing.set(false) }
                })
            } catch (e: Exception) { isCapturing.set(false) }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun saveToGallery(sourceFile: File, attempts: Int) {
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "Attempt_$attempts.jpg")
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) put(MediaStore.MediaColumns.RELATIVE_PATH, "DCIM/Camera")
        }
        val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
        uri?.let { destUri ->
            try {
                contentResolver.openOutputStream(destUri)?.use { out -> sourceFile.inputStream().use { it.copyTo(out) } }
                handleFinalAlerts(destUri, attempts)
            } catch (e: Exception) { }
        }
    }

    private fun handleFinalAlerts(uri: Uri, attempts: Int) {
        if (attempts == 4 || attempts == 9) {
            sharedPreferences.edit(commit = true) { putString("LastPhotoPendingUri", uri.toString()) }
            return 
        }
        
        if (attempts == 5 || attempts == 10 || attempts >= 11) {
            sharedPreferences.edit(commit = true) { putString("LastPhotoActionUri", uri.toString()) }
            val email = sharedPreferences.getString("UserEmail", "")
            if (sharedPreferences.getBoolean("GmailEnabled", false) && !email.isNullOrEmpty()) {
                emailExecutor.execute {
                    try {
                        val loc = fetchLocationBlocking()
                        sharedPreferences.edit(commit = true) { putString("LastLocation", loc) }
                        val pendingPhotoUri = if (attempts == 5 || attempts == 10) sharedPreferences.getString("LastPhotoPendingUri", null) else null
                        
                        if (isNetworkAvailable()) {
                            sendEmailWithUris(email, pendingPhotoUri, uri.toString(), loc, attempts)
                        } else {
                            sharedPreferences.edit(commit = true) { putBoolean("EmailPending", true); putInt("PendingEmailAttempt", attempts) }
                        }
                        
                        if (attempts == 5 || attempts == 10) sharedPreferences.edit(commit = true) { putString("LastPhotoPendingUri", null) }
                    } catch (e: Exception) { }
                }
            }
        }
    }

    private fun sendEmailWithUris(userEmail: String, uri1: String?, uri2: String, location: String, attempts: Int) {
        try {
            val scope = "oauth2:https://www.googleapis.com/auth/gmail.send"
            
            // টোকেন রিফ্রেশ
            try {
                val oldToken = com.google.android.gms.auth.GoogleAuthUtil.getToken(this, userEmail, scope)
                com.google.android.gms.auth.GoogleAuthUtil.clearToken(this, oldToken)
            } catch (e: Exception) {}

            val accessToken = com.google.android.gms.auth.GoogleAuthUtil.getToken(this, userEmail, scope)

            // ১. MimeMessage তৈরি করা
            val props = Properties()
            val session = Session.getInstance(props)
            val mm = MimeMessage(session).apply {
                setFrom(InternetAddress(userEmail))
                addRecipient(javax.mail.Message.RecipientType.TO, InternetAddress(userEmail))
                subject = "TheftGuard Security Alert! Attempt $attempts"
            }
            val multipart = MimeMultipart()
            multipart.addBodyPart(MimeBodyPart().apply { setText("Intruder Location:\n$location\n\nLatest photos attached.") })
            
            uri1?.let { try { addUriAttachment(multipart, Uri.parse(it), "Attempt_${if (attempts == 5) 4 else 9}.jpg") } catch (e: Exception) { } }
            try { addUriAttachment(multipart, Uri.parse(uri2), "Attempt_$attempts.jpg") } catch (e: Exception) { }
            
            mm.setContent(multipart)
            
            // ২. RAW ডাটা তৈরি
            val baos = ByteArrayOutputStream()
            mm.writeTo(baos)
            val rawMessage = android.util.Base64.encodeToString(baos.toByteArray(), android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP)

            // ৩. Gmail REST API এর মাধ্যমে মেইল পাঠানো (Turbo Speed)
            val url = URL("https://www.googleapis.com/gmail/v1/users/me/messages/send")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Authorization", "Bearer $accessToken")
            conn.setRequestProperty("Content-Type", "application/json")
            conn.doOutput = true
            
            val jsonBody = "{\"raw\":\"$rawMessage\"}"
            conn.outputStream.write(jsonBody.toByteArray())
            
            if (conn.responseCode == 200) {
                sharedPreferences.edit(commit = true) { putBoolean("EmailPending", false) }
                Log.i("TheftGuard", "EMAIL SENT SUCCESS via REST API")
            } else {
                throw Exception("API Response: ${conn.responseCode}")
            }
            conn.disconnect()
        } catch (e: Exception) { 
            Log.e("TheftGuard", "REST Email failed: ${e.message}")
            sharedPreferences.edit(commit = true) { putBoolean("EmailPending", true); putInt("PendingEmailAttempt", attempts) }
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
            multipart.addBodyPart(MimeBodyPart().apply { dataHandler = javax.activation.DataHandler(dataSource); this.fileName = fileName })
        }
    }

    private fun fetchLocationBlocking(): String {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return "No Permission"
        return try {
            val task = LocationServices.getFusedLocationProviderClient(this).getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
            val loc = Tasks.await(task, 5, TimeUnit.SECONDS)
            if (loc != null) "Lat: ${loc.latitude}, Lng: ${loc.longitude}\nMaps: https://www.google.com/maps/search/?api=1&query=${loc.latitude},${loc.longitude}"
            else "Location Timeout"
        } catch (e: Exception) { "Location Error" }
    }

    private fun scheduleWatchdog() {
        val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = PendingIntent.getBroadcast(this, 99, Intent(this, RestartReceiver::class.java).apply { action = "HEARTBEAT_RESTART" }, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val triggerTime = SystemClock.elapsedRealtime() + (15 * 60 * 1000)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && alarmManager.canScheduleExactAlarms()) alarmManager.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerTime, intent)
            else alarmManager.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerTime, intent)
        } catch (e: Exception) { alarmManager.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerTime, intent) }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        val restartIntent = Intent(this, RestartReceiver::class.java).apply { action = "RESTART_THEFTGUARD" }
        val pendingIntent = PendingIntent.getBroadcast(this, 1, restartIntent, PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE)
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
            mediaPlayer = android.media.MediaPlayer().apply { 
                setDataSource(applicationContext, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM))
                setAudioAttributes(android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_ALARM).build())
                isLooping = true; prepare(); start() 
            }
        } catch (e: Exception) { }
    }

    private fun playEmergencyAlarm() { if (!isEmergencyAlarmActive && sharedPreferences.getBoolean("AlarmEnabled", false)) { isEmergencyAlarmActive = true; mainHandler.post(unlockWatchdog); playLoudAlarm() } }

    private fun stopAlarm() { isEmergencyAlarmActive = false; mainHandler.removeCallbacks(unlockWatchdog); mediaPlayer?.let { try { it.stop(); it.release() } catch (e: Exception) {} }; mediaPlayer = null }

    override fun onDestroy() { super.onDestroy(); emailExecutor.shutdown() }
}
