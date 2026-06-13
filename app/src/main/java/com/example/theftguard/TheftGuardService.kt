package com.example.theftguard

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.lifecycle.LifecycleService
import com.google.android.gms.location.LocationServices
import java.io.File
import java.util.*
import javax.mail.*
import javax.mail.internet.InternetAddress
import javax.mail.internet.MimeBodyPart
import javax.mail.internet.MimeMessage
import javax.mail.internet.MimeMultipart

class TheftGuardService : LifecycleService() {

    private var currentLocationStr: String = "Location not available"

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        
        startForeground(1, createNotification())

        val attempts = intent?.getIntExtra("attempt_count", 0) ?: 0
        val sharedPreferences = getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)

        Log.d("TheftGuard", "Service started for manual attempt: $attempts")

        if (attempts >= 4) {
            captureAndProcess(sharedPreferences, attempts)
        } else {
            stopSelf()
        }

        return START_NOT_STICKY
    }

    private fun createNotification(): Notification {
        val channelId = "TheftGuardServiceChannel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(channelId, "TheftGuard Protection", NotificationManager.IMPORTANCE_LOW)
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(serviceChannel)
        }
        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("TheftGuard is Active")
            .setContentText("Monitoring for unauthorized access...")
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .build()
    }

    private fun captureAndProcess(prefs: android.content.SharedPreferences, attempts: Int) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            val fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
            fusedLocationClient.lastLocation.addOnSuccessListener { location ->
                if (location != null) {
                    currentLocationStr = "Lat: ${location.latitude}, Lng: ${location.longitude}\nGoogle Maps: http://maps.google.com/maps?q=${location.latitude},${location.longitude}"
                }
                takePhoto(prefs, attempts)
            }.addOnFailureListener {
                takePhoto(prefs, attempts)
            }
        } else {
            takePhoto(prefs, attempts)
        }

        if (prefs.getBoolean("AlarmEnabled", true) && attempts == 4) {
            playLoudAlarm()
        }
    }

    private fun playLoudAlarm() {
        try {
            val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            audioManager.setStreamVolume(AudioManager.STREAM_ALARM, audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0)
            val alarmUri: Uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            val ringtone = RingtoneManager.getRingtone(applicationContext, alarmUri)
            ringtone.play()
            Timer().schedule(object : TimerTask() { override fun run() { ringtone.stop() } }, 5000)
        } catch (e: Exception) {
            Log.e("TheftGuard", "Alarm failed: ${e.message}")
        }
    }

    private fun takePhoto(prefs: android.content.SharedPreferences, attempts: Int) {
        if (!prefs.getBoolean("CameraEnabled", true) ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            processAlerts(null, prefs, attempts)
            return
        }

        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            val imageCapture = ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build()
            
            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(this, CameraSelector.DEFAULT_FRONT_CAMERA, imageCapture)

                // MediaStore ব্যবহার করে ইন্টারনাল স্টোরেজ (গ্যালারি/পিকচার্স) এ সেভ করা হচ্ছে
                val filename = "TheftGuard_Attempt_${attempts}_${System.currentTimeMillis()}.jpg"
                val contentValues = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
                    put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/TheftGuard")
                    }
                }

                val outputOptions = ImageCapture.OutputFileOptions.Builder(
                    contentResolver,
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    contentValues
                ).build()

                imageCapture.takePicture(outputOptions, ContextCompat.getMainExecutor(this),
                    object : ImageCapture.OnImageSavedCallback {
                        override fun onError(exc: ImageCaptureException) {
                            Log.e("TheftGuard", "Capture failed: ${exc.message}")
                            processAlerts(null, prefs, attempts)
                        }
                        override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                            val savedUri = output.savedUri
                            Log.d("TheftGuard", "Photo saved to Gallery: $savedUri")
                            
                            // URI থেকে পাথ বের করা (ইমেইলের জন্য)
                            val photoPath = getPathFromUri(savedUri)
                            processAlerts(photoPath?.let { File(it) }, prefs, attempts)
                        }
                    })
            } catch (exc: Exception) {
                Log.e("TheftGuard", "Camera bind failed: ${exc.message}")
                processAlerts(null, prefs, attempts)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun getPathFromUri(uri: Uri?): String? {
        if (uri == null) return null
        val projection = arrayOf(MediaStore.Images.Media.DATA)
        contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val columnIndex = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATA)
                return cursor.getString(columnIndex)
            }
        }
        return null
    }

    private fun processAlerts(photoFile: File?, prefs: android.content.SharedPreferences, attempts: Int) {
        if (photoFile != null) {
            if (attempts == 4) {
                prefs.edit { 
                    putString("LastPhoto4", photoFile.absolutePath)
                    putString("LastLocation4", currentLocationStr)
                }
            } else if (attempts == 5) {
                prefs.edit { putString("LastPhoto5", photoFile.absolutePath) }
                
                val photo4Path = prefs.getString("LastPhoto4", "")
                val location4 = prefs.getString("LastLocation4", "Unknown") ?: "Unknown"
                val userEmail = prefs.getString("UserEmail", "")
                
                // ইমেইল পাঠানোর জন্য App Password লাগবেই যদি OAUTH সেটআপ না থাকে। 
                // যেহেতু ইউজার ম্যানুয়াল অপশন রিমুভ করতে বলেছেন, তাই আপাতত এই পার্টটি কাজ করবে না 
                // যদি না হার্ডকোড করা বা অন্য কোনো সিকিউর ওয়ে থাকে।
                val appPassword = prefs.getString("AppPassword", "")

                if (prefs.getBoolean("GmailEnabled", true) && !userEmail.isNullOrEmpty() && !appPassword.isNullOrEmpty()) {
                    sendEmailInBackground(userEmail, appPassword!!, photo4Path, photoFile.absolutePath, location4, currentLocationStr)
                }
            }
        }
        
        if (attempts >= 5) {
            Timer().schedule(object : TimerTask() { override fun run() { stopSelf() } }, 15000)
        } else {
            stopSelf() 
        }
    }

    private fun sendEmailInBackground(userEmail: String, appPass: String, path1: String?, path2: String, loc1: String, loc2: String) {
        Thread {
            try {
                val props = Properties()
                props["mail.smtp.host"] = "smtp.gmail.com"
                props["mail.smtp.socketFactory.port"] = "465"
                props["mail.smtp.socketFactory.class"] = "javax.net.ssl.SSLSocketFactory"
                props["mail.smtp.auth"] = "true"
                props["mail.smtp.port"] = "465"

                val session = Session.getDefaultInstance(props, object : Authenticator() {
                    override fun getPasswordAuthentication(): PasswordAuthentication {
                        return PasswordAuthentication(userEmail, appPass)
                    }
                })

                val mm = MimeMessage(session)
                mm.setFrom(InternetAddress(userEmail))
                mm.addRecipient(Message.RecipientType.TO, InternetAddress(userEmail))
                mm.subject = "TheftGuard Security Alert: 5 Failed Attempts!"

                val messageBodyPart = MimeBodyPart()
                messageBodyPart.setText("Unauthorized access detected.\n\nAttempt 4 Location:\n$loc1\n\nAttempt 5 Location:\n$loc2")

                val multipart = MimeMultipart()
                multipart.addBodyPart(messageBodyPart)

                path1?.let { if (File(it).exists()) addAttachment(multipart, it) }
                if (File(path2).exists()) addAttachment(multipart, path2)

                mm.setContent(multipart)
                Transport.send(mm)
                Log.d("TheftGuard", "Email sent successfully in background!")
            } catch (e: Exception) {
                Log.e("TheftGuard", "Email failed: ${e.message}")
            }
        }.start()
    }

    private fun addAttachment(multipart: Multipart, filePath: String) {
        val messageBodyPart = MimeBodyPart()
        val source = javax.activation.FileDataSource(filePath)
        messageBodyPart.dataHandler = javax.activation.DataHandler(source)
        messageBodyPart.fileName = File(filePath).name
        multipart.addBodyPart(messageBodyPart)
    }
}
