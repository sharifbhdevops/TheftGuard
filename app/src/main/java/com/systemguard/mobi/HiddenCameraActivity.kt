package com.systemguard.mobi

import android.Manifest
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.content.edit
import com.google.android.gms.location.LocationServices
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class HiddenCameraActivity : ComponentActivity() {
    private lateinit var cameraExecutor: ExecutorService
    private var currentLocationStr: String = "Location not available"

    override fun onCreate(savedInstanceState: Bundle?) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                        WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
        super.onCreate(savedInstanceState)
        cameraExecutor = Executors.newSingleThreadExecutor()

        val sharedPreferences = getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)

        // অ্যালার্ম (শুধুমাত্র ৪ নম্বর বারের জন্য)
        val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val failedAttempts = dpm.currentFailedPasswordAttempts
        
        if (sharedPreferences.getBoolean("AlarmEnabled", true) && failedAttempts == 4) {
            playLoudAlarm()
        }

        // লোকেশন নেওয়া
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            val fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
            fusedLocationClient.lastLocation.addOnSuccessListener { location ->
                if (location != null) {
                    currentLocationStr = "Lat: ${location.latitude}, Lng: ${location.longitude}\nGoogle Maps: http://maps.google.com/maps?q=${location.latitude},${location.longitude}"
                }
                proceedToCapture(sharedPreferences, failedAttempts)
            }.addOnFailureListener {
                proceedToCapture(sharedPreferences, failedAttempts)
            }
        } else {
            proceedToCapture(sharedPreferences, failedAttempts)
        }
    }

    private fun proceedToCapture(prefs: android.content.SharedPreferences, attempts: Int) {
        if (prefs.getBoolean("CameraEnabled", true) &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            takePhotoSilent(prefs, attempts)
        } else {
            processAlerts(null, prefs, attempts)
        }
    }

    private fun playLoudAlarm() {
        try {
            val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            audioManager.setStreamVolume(AudioManager.STREAM_ALARM, audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0)
            val alarmUri: Uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            val ringtone = RingtoneManager.getRingtone(applicationContext, alarmUri)
            ringtone.play()
            window.decorView.postDelayed({ ringtone.stop() }, 5000)
        } catch (e: Exception) {
            Log.e("TheftGuard", "Alarm failed: ${e.message}")
        }
    }

    private fun takePhotoSilent(prefs: android.content.SharedPreferences, attempts: Int) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            val imageCapture = ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build()
            val cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(this, cameraSelector, imageCapture)

                val photoFile = File(filesDir, "Thief_Attempt_${attempts}_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(System.currentTimeMillis())}.jpg")
                val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()

                imageCapture.takePicture(
                    outputOptions, ContextCompat.getMainExecutor(this),
                    object : ImageCapture.OnImageSavedCallback {
                        override fun onError(exc: ImageCaptureException) {
                            Log.e("TheftGuard", "Capture failed: ${exc.message}")
                            processAlerts(null, prefs, attempts)
                        }

                        override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                            Log.d("TheftGuard", "Photo saved!")
                            processAlerts(photoFile, prefs, attempts)
                        }
                    })
            } catch (exc: Exception) {
                processAlerts(null, prefs, attempts)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun processAlerts(photoFile: File?, prefs: android.content.SharedPreferences, attempts: Int) {
        val isGmailEnabled = prefs.getBoolean("GmailEnabled", true)
        val userEmail = prefs.getString("UserEmail", "")

        // ৪ নম্বর এবং ৫ নম্বর বারের ছবি সেভ করে রাখা হচ্ছে
        if (photoFile != null) {
            if (attempts == 4) {
                prefs.edit { 
                    putString("LastPhoto4", photoFile.absolutePath)
                    putString("LastLocation4", currentLocationStr)
                }
            } else if (attempts == 5) {
                prefs.edit { putString("LastPhoto5", photoFile.absolutePath) }
                
                // ৫ নম্বর বারের পর ২টা ছবিই একসাথে পাঠানো হবে
                val photo4Path = prefs.getString("LastPhoto4", "")
                val location4 = prefs.getString("LastLocation4", "Unknown") ?: "Unknown"
                
                if (isGmailEnabled && !userEmail.isNullOrEmpty()) {
                    sendDoubleEmailAlert(userEmail, photo4Path, photoFile.absolutePath, location4, currentLocationStr)
                }
            }
        }

        finish()
    }

    private fun sendDoubleEmailAlert(email: String, path1: String?, path2: String, loc1: String, loc2: String) {
        val uri1 = if (!path1.isNullOrEmpty()) FileProvider.getUriForFile(this, "${applicationContext.packageName}.provider", File(path1)) else null
        val uri2 = FileProvider.getUriForFile(this, "${applicationContext.packageName}.provider", File(path2))

        val emailIntent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "image/jpeg"
            putExtra(Intent.EXTRA_EMAIL, arrayOf(email))
            putExtra(Intent.EXTRA_SUBJECT, "TheftGuard Security Alert: 5 Failed Attempts!")
            val body = "Multiple unauthorized access attempts detected.\n\n" +
                       "Attempt 4 Location:\n$loc1\n\n" +
                       "Attempt 5 Location:\n$loc2"
            putExtra(Intent.EXTRA_TEXT, body)
            
            val uris = ArrayList<Uri>()
            uri1?.let { uris.add(it) }
            uris.add(uri2)
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        
        try {
            startActivity(Intent.createChooser(emailIntent, "Sending Security Alert...").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            Log.e("TheftGuard", "No Email client found")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }
}
