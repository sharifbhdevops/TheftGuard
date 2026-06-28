
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
import androidx.compose.material.icons.automirrored.filled.Logout
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
import com.systemguard.mobi.ui.theme.*
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Scope

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Ensure SharedPreferences are in Device Protected Storage for reboot support
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val directBootContext = createDeviceProtectedStorageContext()
            directBootContext.moveSharedPreferencesFrom(this, "AppPrefs")
        }

        setContent {
            TheftGuardTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Navy900
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
    val sharedPreferences = remember { 
        val prefContext = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            context.createDeviceProtectedStorageContext()
        } else {
            context
        }
        prefContext.getSharedPreferences("AppPrefs", Context.MODE_PRIVATE) 
    }

    var isCameraEnabled by remember { mutableStateOf(sharedPreferences.getBoolean("CameraEnabled", false)) }
    var isAlarmEnabled by remember { mutableStateOf(sharedPreferences.getBoolean("AlarmEnabled", false)) }
    var isGmailEnabled by remember { mutableStateOf(sharedPreferences.getBoolean("GmailEnabled", false)) }
    var isSimEnabled by remember { mutableStateOf(sharedPreferences.getBoolean("SimEnabled", false)) }
    
    var gmailId by remember { mutableStateOf(sharedPreferences.getString("UserEmail", "Not Logged In") ?: "Not Logged In") }
    var showInfoDialog by remember { mutableStateOf(false) }
    var showLogoutDialog by remember { mutableStateOf(false) }
    
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
    
    // Auto-start check is manufacturer specific, we can't check it programmatically easily, 
    // but we can provide a way to open the settings.
    
    val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
    val adminComponent = ComponentName(context, MyAdminReceiver::class.java)
    var isAdminActive by remember { mutableStateOf(dpm.isAdminActive(adminComponent)) }

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

    LaunchedEffect(isAppActive) {
        if (isAppActive) {
            TheftGuardService.start(context)
        } else {
            context.stopService(Intent(context, TheftGuardService::class.java))
        }
    }

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

    val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
        .requestEmail()
        // .requestIdToken("YOUR_WEB_CLIENT_ID_HERE.apps.googleusercontent.com") // Error 10 ঠিক করতে এখানে আপনার Web Client ID দিন
        .requestScopes(Scope("https://www.googleapis.com/auth/gmail.send"))
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
            Log.e("TheftGuard", "Sign in failed code: ${e.statusCode}")
            Toast.makeText(context, "Sign in failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    val mainBackgroundGradient = Brush.verticalGradient(
        colors = listOf(Navy900, Navy800, Navy900)
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
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 10.dp, bottom = 24.dp),
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
                    color = SkyBlue,
                    fontWeight = FontWeight.Bold
                )
            }
            Box(
                modifier = Modifier
                    .size(45.dp)
                    .clip(CircleShape)
                    .background(Navy800)
                    .border(1.dp, Navy700, CircleShape)
                    .clickable { showInfoDialog = true },
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.Info, contentDescription = "Info", tint = SkyBlue)
            }
        }

        ProtectionStatusCard(isAppActive)
        
        Spacer(modifier = Modifier.height(24.dp))
        
        Text(
            text = "Active Protections",
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp),
            color = Color.White,
            fontWeight = FontWeight.Bold,
            fontSize = 18.sp
        )

        Column {
            ModernFeatureCard("Capture Photo & Location", Icons.Default.CameraAlt, isCameraEnabled, Emerald) { enabled ->
                if (enabled) {
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
            ModernFeatureCard("Security Alarm", Icons.Default.NotificationsActive, isAlarmEnabled, Emerald) {
                isAlarmEnabled = it
                sharedPreferences.edit { putBoolean("AlarmEnabled", it) }
            }
            ModernFeatureCard("SIM Unplug Alarm", Icons.Default.SimCard, isSimEnabled, Emerald) { enabled ->
                if (enabled) {
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
            ModernFeatureCard("Email Alert", Icons.Default.Email, isGmailEnabled, Emerald) { enabled ->
                if (enabled) {
                    if (gmailId == "Not Logged In" || gmailId.isBlank()) {
                        Toast.makeText(context, "Please login with Google first", Toast.LENGTH_SHORT).show()
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
        
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Navy800),
            shape = RoundedCornerShape(20.dp),
            border = BorderStroke(1.dp, Navy700)
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
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isLoggedIn) Rose else Color.White
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = if (isLoggedIn) Icons.AutoMirrored.Filled.Logout else Icons.Default.AccountCircle, 
                            contentDescription = null, 
                            tint = if (isLoggedIn) Color.White else Navy900
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (isLoggedIn) "Logout from Google" else "Login with Google", 
                            color = if (isLoggedIn) Color.White else Navy900, 
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
                    putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "Required for security.")
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
        Spacer(modifier = Modifier.height(40.dp))
    }
    
    if (showLogoutDialog) {
        AlertDialog(
            onDismissRequest = { showLogoutDialog = false },
            title = { Text("Logout Confirmation", color = Color.White) },
            text = { Text("Are you sure you want to logout?", color = Color.LightGray) },
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
                        }
                    }
                ) {
                    Text("Logout", color = Rose, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showLogoutDialog = false }) {
                    Text("Cancel", color = Color.White)
                }
            },
            containerColor = Navy800
        )
    }

    if (showInfoDialog) {
        AlertDialog(
            onDismissRequest = { showInfoDialog = false },
            title = { 
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Security, contentDescription = null, tint = SkyBlue)
                    Spacer(modifier = Modifier.width(10.dp))
                    Text("TheftGuard: AI Security", fontWeight = FontWeight.Bold)
                }
            },
            text = { 
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    Text(
                        text = "Version: V1.2",
                        fontSize = 12.sp,
                        color = Color.Gray,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    
                    Text("Main Features:", fontWeight = FontWeight.Bold, color = Color.White)
                    BulletPoint("Intruder Detection: Captures photo and location after 4 failed password attempts.")
                    BulletPoint("Email Alerts: Sends intruder's photo and location to your email after 5 failed attempts.")
                    BulletPoint("SIM Protection: Triggers a loud alarm if the SIM card is removed while locked.")
                    BulletPoint("Security Alarm: Automatically sounds an alarm on incorrect password attempts.")

                    Spacer(modifier = Modifier.height(16.dp))
                    
                    Surface(
                        color = Rose.copy(alpha = 0.1f),
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(1.dp, Rose.copy(alpha = 0.3f))
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(
                                text = "⚠️ NOTE (How to Uninstall):",
                                fontWeight = FontWeight.Bold,
                                color = Rose
                            )
                            Text(
                                text = "This app uses 'Device Admin' permission. To uninstall the app, you must first deactivate 'Device Admin' from the settings or within the app permissions. Once deactivated, you can uninstall it like any other regular app.",
                                fontSize = 13.sp,
                                color = Color.LightGray
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showInfoDialog = false }) {
                    Text("Close", color = SkyBlue, fontWeight = FontWeight.Bold)
                }
            },
            containerColor = Navy800,
            titleContentColor = Color.White,
            textContentColor = Color.LightGray
        )
    }
}

@Composable
fun BulletPoint(text: String) {
    Row(modifier = Modifier.padding(vertical = 4.dp)) {
        Text("• ", color = SkyBlue, fontWeight = FontWeight.Bold)
        Text(text = text, fontSize = 14.sp, color = Color.LightGray)
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

    val glowColor = if (isAppActive) Emerald else Rose
    val statusText = if (isAppActive) "TheftGuard Active" else "TheftGuard Inactive"

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(
                if (isAppActive) 10.dp else 0.dp,
                RoundedCornerShape(24.dp),
                ambientColor = glowColor,
                spotColor = glowColor
            ),
        colors = CardDefaults.cardColors(containerColor = Navy800),
        shape = RoundedCornerShape(24.dp),
        border = BorderStroke(1.dp, Navy700)
    ) {
        Row(modifier = Modifier.padding(24.dp), verticalAlignment = Alignment.CenterVertically) {
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
                Text(text = if (isAppActive) "Device Protected" else "Action Required", color = Color.Gray, fontSize = 13.sp)
            }
        }
    }
}

@Composable
fun ModernFeatureCard(title: String, icon: ImageVector, isChecked: Boolean, accentColor: Color, onCheckedChange: (Boolean) -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        colors = CardDefaults.cardColors(containerColor = Navy800.copy(alpha = 0.7f)),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, if (isChecked) accentColor.copy(alpha = 0.3f) else Navy700)
    ) {
        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .background(
                        if (isChecked) accentColor.copy(alpha = 0.15f) else Navy700.copy(
                            alpha = 0.3f
                        ), RoundedCornerShape(10.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(imageVector = icon, contentDescription = null, tint = if (isChecked) accentColor else Color.Gray, modifier = Modifier.size(24.dp))
            }
            Spacer(modifier = Modifier.width(16.dp))
            Text(text = title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Color.White, modifier = Modifier.weight(1f))
            Switch(
                checked = isChecked, 
                onCheckedChange = onCheckedChange, 
                colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = accentColor)
            )
        }
    }
}

@Composable
fun PermissionsStatusSection(isAdmin: Boolean, hasCameraLocation: Boolean, hasPhone: Boolean, isBatteryOptimized: Boolean, onAdminRequest: () -> Unit, onCameraLocationRequest: () -> Unit, onPhoneStateRequest: () -> Unit, onBatteryRequest: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Navy800),
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, Navy700)
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text(text = "Permissions", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Spacer(modifier = Modifier.height(12.dp))
            PermissionRow("Device Admin", isAdmin, onAdminRequest)
            PermissionRow("Camera & Location", hasCameraLocation, onCameraLocationRequest)
            PermissionRow("Phone State", hasPhone, onPhoneStateRequest)
            PermissionRow("Battery Optimization", isBatteryOptimized, onBatteryRequest)
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
            tint = if (isGranted) SuccessGreen else ErrorRed,
            modifier = Modifier.size(20.dp)
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(text = label, color = Color.White, fontSize = 14.sp, modifier = Modifier.weight(1f))
        if (!isGranted) Text(text = "REQUIRED", color = SkyBlue, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    }
}
