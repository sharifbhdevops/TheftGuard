package com.systemguard.mobi

import android.Manifest
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import com.systemguard.mobi.ui.theme.TheftGuardTheme
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Scope

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            TheftGuardTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color(0xFF0F172A)
                ) {
                    TheftGuardDashboard()
                }
            }
        }
    }
}

@Composable
fun TheftGuardDashboard() {
    val context = LocalContext.current
    val sharedPreferences = remember { context.getSharedPreferences("AppPrefs", Context.MODE_PRIVATE) }

    var isCameraEnabled by remember { mutableStateOf(sharedPreferences.getBoolean("CameraEnabled", false)) }
    var isAlarmEnabled by remember { mutableStateOf(sharedPreferences.getBoolean("AlarmEnabled", false)) }
    var isGmailEnabled by remember { mutableStateOf(sharedPreferences.getBoolean("GmailEnabled", false)) }
    var isSimEnabled by remember { mutableStateOf(sharedPreferences.getBoolean("SimEnabled", false)) }
    
    var gmailId by remember { mutableStateOf(sharedPreferences.getString("UserEmail", "Not Logged In") ?: "Not Logged In") }
    var showInfoDialog by remember { mutableStateOf(false) }
    var showLogoutDialog by remember { mutableStateOf(false) }
    
    // পারমিশন পাওয়ার পর কোন ফিচারটি অন করতে হবে তা ট্র্যাক করার জন্য
    var pendingFeature by remember { mutableStateOf<String?>(null) }

    var hasCameraPermission by remember { 
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) 
    }
    var hasLocationPermission by remember { 
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) 
    }
    var hasPhoneStatePermission by remember { 
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED) 
    }
    
    val powerManager = remember { context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager }
    var isBatteryOptimized by remember { 
        mutableStateOf(!powerManager.isIgnoringBatteryOptimizations(context.packageName)) 
    }
    
    val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
    val adminComponent = ComponentName(context, MyAdminReceiver::class.java)
    var isAdminActive by remember { mutableStateOf(dpm.isAdminActive(adminComponent)) }

    // Refresh admin and battery status when activity resumes
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                isAdminActive = dpm.isAdminActive(adminComponent)
                isBatteryOptimized = !powerManager.isIgnoringBatteryOptimizations(context.packageName)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    val isAppActive = (isCameraEnabled || isAlarmEnabled || isGmailEnabled || isSimEnabled) && isAdminActive

    // Start or stop service based on protection status
    LaunchedEffect(isAppActive) {
        if (isAppActive) {
            val serviceIntent = Intent(context, TheftGuardService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent)
                } else {
                    context.startService(serviceIntent)
                }
            } catch (e: Exception) {
                Log.e("TheftGuard", "Service start failed: ${e.message}")
            }
        } else {
            context.stopService(Intent(context, TheftGuardService::class.java))
        }
    }

    // Permission check helper function
    fun checkPermissions(permissions: Array<String>): Boolean {
        return permissions.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val cameraGranted = permissions[Manifest.permission.CAMERA] ?: hasCameraPermission
        val locationGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] ?: hasLocationPermission
        val phoneGranted = permissions[Manifest.permission.READ_PHONE_STATE] ?: hasPhoneStatePermission

        hasCameraPermission = cameraGranted
        hasLocationPermission = locationGranted
        hasPhoneStatePermission = phoneGranted

        // অটো-এনেবল লজিক
        when (pendingFeature) {
            "CAMERA_LOC" -> {
                if (cameraGranted && locationGranted) {
                    isCameraEnabled = true
                    sharedPreferences.edit { putBoolean("CameraEnabled", true) }
                }
            }
            "SIM" -> {
                if (phoneGranted) {
                    isSimEnabled = true
                    sharedPreferences.edit { putBoolean("SimEnabled", true) }
                }
            }
        }
        pendingFeature = null
    }

    // Google Sign In Setup - Updated Scope to fix Authentication Error
    val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
        .requestEmail()
        .requestScopes(Scope("https://mail.google.com/"))
        .build()
    val mGoogleSignInClient = GoogleSignIn.getClient(context, gso)

    val googleSignInLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
        try {
            val account: GoogleSignInAccount? = task.getResult(ApiException::class.java)
            account?.email?.let {
                gmailId = it
                sharedPreferences.edit { putString("UserEmail", it) }
                Toast.makeText(context, "Logged in as $it", Toast.LENGTH_SHORT).show()
            }
        } catch (e: ApiException) {
            // Error 10 is usually due to missing SHA-1 in Google Console
            Log.e("TheftGuard", "Sign in failed code: ${e.statusCode}")
            Toast.makeText(context, "Sign in failed: ${e.message} (Code: ${e.statusCode})", Toast.LENGTH_LONG).show()
        }
    }

    val mainBackgroundGradient = Brush.verticalGradient(
        colors = listOf(Color(0xFF0F172A), Color(0xFF1E293B), Color(0xFF0F172A))
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(mainBackgroundGradient)
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 24.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Text(
                    text = "TheftGuard",
                    fontSize = 28.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = Color.White
                )
                Text(
                    text = "Advanced Security AI",
                    fontSize = 12.sp,
                    color = Color(0xFF38BDF8),
                    fontWeight = FontWeight.Bold
                )
            }
            Box(
                modifier = Modifier
                    .size(45.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF1E293B))
                    .border(1.dp, Color(0xFF334155), CircleShape)
                    .clickable { showInfoDialog = true },
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.Info, contentDescription = "Info", tint = Color(0xFF38BDF8))
            }
        }

        ProtectionStatusCard(isAppActive)
        
        Spacer(modifier = Modifier.height(24.dp))
        
        Text(
            text = "Active Protections",
            modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
            color = Color.White,
            fontWeight = FontWeight.Bold,
            fontSize = 18.sp
        )

        Column {
            ModernFeatureCard("Capture Photo & Location", Icons.Default.CameraAlt, isCameraEnabled, Color(0xFF10B981)) { enabled ->
                if (enabled) {
                    // Enable after checking camera and location permissions
                    if (checkPermissions(arrayOf(Manifest.permission.CAMERA, Manifest.permission.ACCESS_FINE_LOCATION))) {
                        isCameraEnabled = true
                        sharedPreferences.edit { putBoolean("CameraEnabled", true) }
                    } else {
                        pendingFeature = "CAMERA_LOC"
                        permissionLauncher.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.ACCESS_FINE_LOCATION))
                    }
                } else {
                    isCameraEnabled = false
                    sharedPreferences.edit { putBoolean("CameraEnabled", false) }
                }
            }
            ModernFeatureCard("Security Alarm", Icons.Default.NotificationsActive, isAlarmEnabled, Color(0xFF10B981)) {
                isAlarmEnabled = it
                sharedPreferences.edit { putBoolean("AlarmEnabled", it) }
            }
            ModernFeatureCard("SIM Unplug Alarm", Icons.Default.SimCard, isSimEnabled, Color(0xFF10B981)) { enabled ->
                if (enabled) {
                    // Check phone state permission for SIM detection
                    if (checkPermissions(arrayOf(Manifest.permission.READ_PHONE_STATE))) {
                        isSimEnabled = true
                        sharedPreferences.edit { putBoolean("SimEnabled", true) }
                    } else {
                        pendingFeature = "SIM"
                        permissionLauncher.launch(arrayOf(Manifest.permission.READ_PHONE_STATE))
                    }
                } else {
                    isSimEnabled = false
                    sharedPreferences.edit { putBoolean("SimEnabled", false) }
                }
            }
            ModernFeatureCard("Email Alert", Icons.Default.Email, isGmailEnabled, Color(0xFF10B981)) { enabled ->
                if (enabled) {
                    if (gmailId == "Not Logged In" || gmailId.isBlank()) {
                        Toast.makeText(context, "Please login with Google first to enable Email Alerts", Toast.LENGTH_SHORT).show()
                    } else {
                        isGmailEnabled = true
                        sharedPreferences.edit { putBoolean("GmailEnabled", true) }
                    }
                } else {
                    isGmailEnabled = false
                    sharedPreferences.edit { putBoolean("GmailEnabled", false) }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
        
        // OAuth Button Section - Manual Config Removed
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
            shape = RoundedCornerShape(20.dp),
            border = BorderStroke(1.dp, Color(0xFF334155))
        ) {
            Column(modifier = Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(text = "Account Configuration", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Spacer(modifier = Modifier.height(12.dp))
                
                val isLoggedIn = gmailId != "Not Logged In" && gmailId.isNotBlank()
                Text(text = if (isLoggedIn) "Logged in as: $gmailId" else "No account connected", color = Color.LightGray, fontSize = 14.sp)
                
                Spacer(modifier = Modifier.height(16.dp))

                Button(
                    onClick = { 
                        if (isLoggedIn) {
                            showLogoutDialog = true
                        } else {
                            googleSignInLauncher.launch(mGoogleSignInClient.signInIntent)
                        }
                    },
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isLoggedIn) Color(0xFFEF4444) else Color.White
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = if (isLoggedIn) Icons.Default.Logout else Icons.Default.AccountCircle, 
                            contentDescription = null, 
                            tint = if (isLoggedIn) Color.White else Color(0xFF0F172A)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (isLoggedIn) "Logout from Google" else "Login with Google", 
                            color = if (isLoggedIn) Color.White else Color(0xFF0F172A), 
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        PermissionsStatusSection(isAdminActive, hasCameraPermission && hasLocationPermission, hasPhoneStatePermission, !isBatteryOptimized,
            onAdminRequest = {
                val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                    putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent)
                    putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "Required to detect failed login attempts.")
                }
                context.startActivity(intent)
            },
            onCameraLocationRequest = {
                permissionLauncher.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.ACCESS_FINE_LOCATION))
            },
            onPhoneStateRequest = {
                permissionLauncher.launch(arrayOf(Manifest.permission.READ_PHONE_STATE))
            },
            onBatteryRequest = {
                val intent = Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:${context.packageName}")
                }
                context.startActivity(intent)
            }
        )
        
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "Note: This app does not collect or share any user data. To uninstall, you must first deactivate 'Device Administrator' from the app settings.",
            color = Color.Gray,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 4.dp)
        )
        
        Spacer(modifier = Modifier.height(40.dp))
    }
    
    if (showLogoutDialog) {
        AlertDialog(
            onDismissRequest = { showLogoutDialog = false },
            title = { Text("Logout Confirmation", color = Color.White) },
            text = { Text("Are you sure you want to logout? This will disable Email Alerts.", color = Color.LightGray) },
            confirmButton = {
                TextButton(
                    onClick = {
                        mGoogleSignInClient.signOut().addOnCompleteListener {
                            gmailId = "Not Logged In"
                            isGmailEnabled = false
                            sharedPreferences.edit { 
                                putString("UserEmail", "Not Logged In")
                                putBoolean("GmailEnabled", false)
                            }
                            showLogoutDialog = false
                            Toast.makeText(context, "Logged out successfully", Toast.LENGTH_SHORT).show()
                        }
                    }
                ) {
                    Text("Logout", color = Color(0xFFEF4444), fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showLogoutDialog = false }) {
                    Text("Cancel", color = Color.White)
                }
            },
            containerColor = Color(0xFF1E293B)
        )
    }

    if (showInfoDialog) {
        AlertDialog(
            onDismissRequest = { showInfoDialog = false },
            title = { Text("TheftGuard: AI Security") },
            text = { 
                Column {
                    Text("TheftGuard provides multi-layered protection for your device:\n\n" +
                         "1. Intruder Detection: If someone fails to unlock your phone 5 times, " +
                         "the app captures their photo and location silently.\n\n" +
                         "2. SIM Protection: If the SIM card is removed while the phone is locked, " +
                         "a loud emergency alarm will trigger instantly.\n\n" +
                         "3. Email Alerts: Captured intruder info is sent directly to your " +
                         "connected Google account for remote tracking.\n\n" +
                         "All photos are saved in your Gallery (DCIM/Camera).")
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(text = "Version: V1.2", fontWeight = FontWeight.Bold, color = Color(0xFF38BDF8))
                }
            },
            confirmButton = {
                TextButton(onClick = { showInfoDialog = false }) {
                    Text("Got it")
                }
            },
            containerColor = Color(0xFF1E293B),
            titleContentColor = Color.White,
            textContentColor = Color.LightGray
        )
    }
    
    LaunchedEffect(Unit) {
        isAdminActive = dpm.isAdminActive(adminComponent)
    }
}

@Composable
fun ProtectionStatusCard(isAppActive: Boolean) {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 0.9f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "alpha"
    )

    val glowColor = if (isAppActive) Color(0xFF10B981) else Color(0xFFEF4444)
    val statusText = if (isAppActive) "TheftGuard Active" else "TheftGuard is not active"

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(if (isAppActive) 20.dp else 0.dp, RoundedCornerShape(24.dp), ambientColor = glowColor, spotColor = glowColor),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
        shape = RoundedCornerShape(24.dp),
        border = BorderStroke(1.dp, Color(0xFF334155))
    ) {
        Row(
            modifier = Modifier.padding(24.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(60.dp)
                    .clip(CircleShape)
                    .background(glowColor.copy(alpha = 0.1f))
                    .border(2.dp, glowColor.copy(alpha = alpha), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (isAppActive) Icons.Default.GppGood else Icons.Default.GppBad,
                    contentDescription = null,
                    tint = glowColor,
                    modifier = Modifier.size(32.dp)
                )
            }
            Spacer(modifier = Modifier.width(20.dp))
            Column {
                Text(text = statusText, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Text(
                    text = if (isAppActive) "AI Guarding your device" else "Action required for safety",
                    color = Color.Gray,
                    fontSize = 13.sp
                )
            }
        }
    }
}

@Composable
fun ModernFeatureCard(title: String, icon: ImageVector, isChecked: Boolean, accentColor: Color, onCheckedChange: (Boolean) -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B).copy(alpha = 0.7f)),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, if (isChecked) accentColor.copy(alpha = 0.3f) else Color(0xFF334155))
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .background(if (isChecked) accentColor.copy(alpha = 0.15f) else Color(0xFF334155).copy(alpha = 0.3f), RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(imageVector = icon, contentDescription = null, tint = if (isChecked) accentColor else Color.Gray, modifier = Modifier.size(24.dp))
            }
            Spacer(modifier = Modifier.width(16.dp))
            Text(text = title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Color.White, modifier = Modifier.weight(1f))
            Switch(
                checked = isChecked, 
                onCheckedChange = onCheckedChange, 
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.White, 
                    checkedTrackColor = accentColor,
                    uncheckedThumbColor = Color.LightGray,
                    uncheckedTrackColor = Color(0xFF334155)
                )
            )
        }
    }
}

@Composable
fun PermissionsStatusSection(isAdmin: Boolean, hasCameraLocation: Boolean, hasPhone: Boolean, isBatteryOptimized: Boolean, onAdminRequest: () -> Unit, onCameraLocationRequest: () -> Unit, onPhoneStateRequest: () -> Unit, onBatteryRequest: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, Color(0xFF334155))
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text(text = "Permissions", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Spacer(modifier = Modifier.height(12.dp))
            
            PermissionRow("Device Administrator", isAdmin, onAdminRequest)
            PermissionRow("Camera & Location", hasCameraLocation, onCameraLocationRequest)
            PermissionRow("Phone State (for SIM)", hasPhone, onPhoneStateRequest)
            PermissionRow("Battery Optimization (Off)", isBatteryOptimized, onBatteryRequest)
        }
    }
}

@Composable
fun PermissionRow(label: String, isGranted: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
            .clickable(enabled = !isGranted) { onClick() },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = if (isGranted) Icons.Default.CheckCircle else Icons.Default.Error,
            contentDescription = null,
            tint = if (isGranted) Color(0xFF34D399) else Color(0xFFFB7185),
            modifier = Modifier.size(20.dp)
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(text = label, color = Color.White, fontSize = 14.sp, modifier = Modifier.weight(1f))
        if (!isGranted) {
            Text(text = "REQUIRED", color = Color(0xFF38BDF8), fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
    }
}
