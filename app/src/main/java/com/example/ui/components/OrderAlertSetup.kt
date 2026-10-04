package com.example.ui.components

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.LocationOff
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.example.ui.theme.EmeraldPrimary

/**
 * Everything the partner grants before going online, in the order they are asked.
 * [required] items block going online; the others are strongly recommended.
 */
enum class GoOnlineRequirement(val title: String, val why: String, val required: Boolean) {
    LOCATION(
        "Location",
        "Your location decides which orders you are offered.",
        required = true
    ),
    BACKGROUND_LOCATION(
        "Location: Allow all the time",
        "Keeps sending your location with the screen off, so orders keep coming.",
        required = false
    ),
    NOTIFICATIONS(
        "Notifications",
        "Rings the new order alarm and shows Accept / Decline.",
        required = true
    ),
    OVERLAY(
        "Display over other apps",
        "Opens a new order full screen while you use another app.",
        required = true
    ),
    FULL_SCREEN(
        "Full-screen alerts",
        "Opens a new order full screen on the lock screen.",
        required = true
    ),
    BATTERY(
        "Battery: Don't optimize",
        "Stops the phone from pausing the app, which would stop your location.",
        required = false
    )
}

object GoOnlinePermissions {

    /** Requirements not yet granted, in the order they should be asked. */
    fun missing(context: Context): List<GoOnlineRequirement> = GoOnlineRequirement.entries.filter { !isGranted(context, it) }

    fun isGranted(context: Context, req: GoOnlineRequirement): Boolean = when (req) {
        GoOnlineRequirement.LOCATION ->
            granted(context, Manifest.permission.ACCESS_FINE_LOCATION)
        GoOnlineRequirement.BACKGROUND_LOCATION ->
            Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
                granted(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        GoOnlineRequirement.NOTIFICATIONS ->
            NotificationManagerCompat.from(context).areNotificationsEnabled()
        GoOnlineRequirement.OVERLAY ->
            Settings.canDrawOverlays(context)
        GoOnlineRequirement.FULL_SCREEN ->
            Build.VERSION.SDK_INT < 34 ||
                context.getSystemService(NotificationManager::class.java).canUseFullScreenIntent()
        GoOnlineRequirement.BATTERY ->
            context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)
    }

    private fun granted(context: Context, permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    fun appDetailsIntent(context: Context): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** System screen for the requirements that are granted in Settings rather than a dialog. */
    fun settingsIntent(context: Context, req: GoOnlineRequirement): Intent {
        val pkgUri = Uri.parse("package:${context.packageName}")
        return when (req) {
            GoOnlineRequirement.NOTIFICATIONS -> Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            GoOnlineRequirement.OVERLAY -> Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, pkgUri)
            GoOnlineRequirement.FULL_SCREEN -> if (Build.VERSION.SDK_INT >= 34) {
                Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, pkgUri)
            } else {
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkgUri)
            }
            GoOnlineRequirement.BATTERY -> Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, pkgUri)
            GoOnlineRequirement.LOCATION, GoOnlineRequirement.BACKGROUND_LOCATION ->
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkgUri)
        }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    /** Phones whose own battery manager kills background apps unless Autostart is allowed. */
    fun needsAutostartTip(): Boolean =
        Build.MANUFACTURER.lowercase() in setOf("xiaomi", "redmi", "poco", "vivo", "iqoo", "oppo", "realme", "oneplus")
}

/** Live list of missing requirements, re-checked whenever the screen resumes (back from Settings). */
@Composable
fun rememberMissingGoOnlineRequirements(): List<GoOnlineRequirement> {
    val context = LocalContext.current
    var missing by remember { mutableStateOf(GoOnlinePermissions.missing(context)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        missing = GoOnlinePermissions.missing(context)
    }
    return missing
}

/**
 * Checklist shown before going online. [onRequest] asks for one requirement (runtime dialog or
 * the right Settings screen); [onGoOnline] is enabled once every required item is granted.
 */
@Composable
fun GoOnlineSetupDialog(
    missing: List<GoOnlineRequirement>,
    showAutostartTip: Boolean,
    onRequest: (GoOnlineRequirement) -> Unit,
    onGoOnline: () -> Unit,
    onDismiss: () -> Unit
) {
    val nextToAsk = missing.firstOrNull()
    val canGoOnline = missing.none { it.required }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.NotificationsActive, contentDescription = null, tint = EmeraldPrimary) },
        title = { Text("Before you go online") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("Allow these so your location keeps updating and you never miss a new order:")
                GoOnlineRequirement.entries.forEach { req ->
                    val granted = req !in missing
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            when {
                                granted -> Icons.Default.CheckCircle
                                req.required -> Icons.Default.ErrorOutline
                                else -> Icons.Default.RadioButtonUnchecked
                            },
                            contentDescription = null,
                            tint = when {
                                granted -> EmeraldPrimary
                                req.required -> MaterialTheme.colorScheme.error
                                else -> MaterialTheme.colorScheme.outline
                            }
                        )
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                req.title + if (req.required) "" else " (recommended)",
                                fontWeight = FontWeight.Bold
                            )
                            Text(req.why, style = MaterialTheme.typography.bodySmall)
                        }
                        if (!granted) {
                            // Asked one at a time, in order, so each system screen makes sense.
                            TextButton(
                                onClick = { onRequest(req) },
                                enabled = req == nextToAsk,
                                modifier = Modifier.testTag("allow_${req.name.lowercase()}")
                            ) { Text("Allow") }
                        }
                    }
                }
                if (showAutostartTip) {
                    Text(
                        "Tip for Xiaomi, Vivo, Oppo, Realme phones: in Settings → Apps → Sndmart Partner, " +
                            "turn on Autostart and allow \"Display pop-up windows while running in the background\". " +
                            "Without it the phone may stop the app and you will stop receiving orders.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onGoOnline,
                enabled = canGoOnline,
                colors = ButtonDefaults.buttonColors(containerColor = EmeraldPrimary),
                modifier = Modifier.testTag("setup_go_online")
            ) { Text("Go online") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
}

/** Warning shown while a required permission is missing. */
@Composable
fun OrderAlertSetupBanner(missingCount: Int, onFix: () -> Unit) {
    WarningBanner(
        title = "Order alerts are not fully on",
        message = "$missingCount permission${if (missingCount > 1) "s" else ""} missing. You may miss new orders.",
        action = "Fix",
        testTag = "order_alert_setup_banner",
        onClick = onFix
    )
}

/** Red banner while online but the server has not received a location for 3 minutes. */
@Composable
fun LocationNotUpdatingBanner(onFix: () -> Unit) {
    WarningBanner(
        title = "Location is not updating",
        message = "You will NOT receive new orders. Tap to fix.",
        action = "Fix",
        testTag = "location_not_updating_banner",
        onClick = onFix,
        icon = { Icon(Icons.Default.LocationOff, contentDescription = null, tint = MaterialTheme.colorScheme.error) }
    )
}

/** Shown when the server set the partner offline because their location stopped updating. */
@Composable
fun AutoOfflineBanner(onGoOnline: () -> Unit, onDismiss: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("auto_offline_banner"),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
    ) {
        Column(Modifier.padding(14.dp)) {
            Text("You were set offline", fontWeight = FontWeight.Bold)
            Text(
                "Your location stopped updating, so you were set offline and stopped receiving orders.",
                style = MaterialTheme.typography.bodySmall
            )
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onGoOnline,
                    colors = ButtonDefaults.buttonColors(containerColor = EmeraldPrimary)
                ) { Text("Go online") }
                TextButton(onClick = onDismiss) { Text("Stay offline") }
            }
        }
    }
}

@Composable
private fun WarningBanner(
    title: String,
    message: String,
    action: String,
    testTag: String,
    onClick: () -> Unit,
    icon: @Composable () -> Unit = {
        Icon(Icons.Default.ErrorOutline, contentDescription = null, tint = MaterialTheme.colorScheme.error)
    }
) {
    Card(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .testTag(testTag),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            icon()
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.Bold)
                Text(message, style = MaterialTheme.typography.bodySmall)
            }
            Button(onClick = onClick, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) {
                Text(action)
            }
        }
    }
}

/** Permissions without which the app does not open at all (new orders must open full screen). */
val AppGateRequirements = listOf(GoOnlineRequirement.OVERLAY, GoOnlineRequirement.FULL_SCREEN)

/**
 * Blocking screen shown instead of the app until "Display over other apps" and, on Android 14+,
 * full-screen alerts are allowed. Re-checked every time the partner returns from Settings.
 */
@Composable
fun AppPermissionGateScreen(missing: List<GoOnlineRequirement>) {
    val context = LocalContext.current
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                Icons.Default.NotificationsActive,
                contentDescription = null,
                tint = EmeraldPrimary,
                modifier = Modifier.size(64.dp)
            )
            Spacer(Modifier.height(16.dp))
            Text(
                "Allow full-screen order alerts",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "New delivery requests open full screen, like an incoming call, even when you are " +
                    "using another app or the phone is locked. Allow the permissions below to use the app.",
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(Modifier.height(24.dp))
            AppGateRequirements.forEach { req ->
                val granted = req !in missing
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            if (granted) Icons.Default.CheckCircle else Icons.Default.ErrorOutline,
                            contentDescription = null,
                            tint = if (granted) EmeraldPrimary else MaterialTheme.colorScheme.error
                        )
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(req.title, fontWeight = FontWeight.Bold)
                            Text(req.why, style = MaterialTheme.typography.bodySmall)
                        }
                        if (!granted) {
                            Button(
                                onClick = {
                                    try {
                                        context.startActivity(GoOnlinePermissions.settingsIntent(context, req))
                                    } catch (e: Exception) {
                                        runCatching { context.startActivity(GoOnlinePermissions.appDetailsIntent(context)) }
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = EmeraldPrimary),
                                modifier = Modifier.testTag("gate_allow_${req.name.lowercase()}")
                            ) { Text("Allow") }
                        }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            Text(
                "In the screen that opens, find Sndmart Partner and turn the switch on, then come back.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline
            )
        }
    }
}
