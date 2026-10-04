package com.example.ui.components

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.example.ui.theme.EmeraldPrimary

/** One permission the new-order alert needs. */
enum class OrderAlertRequirement(val title: String, val why: String) {
    NOTIFICATIONS("Notifications", "Rings the order alert sound and shows Accept / Decline."),
    OVERLAY("Display over other apps", "Opens the new order full screen while you use another app."),
    FULL_SCREEN("Full-screen alerts", "Opens the new order full screen on the lock screen.")
}

object OrderAlertPermissions {

    /** Requirements the partner still has to grant. Empty means alerts are fully set up. */
    fun missing(context: Context): List<OrderAlertRequirement> = buildList {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) add(OrderAlertRequirement.NOTIFICATIONS)
        if (!Settings.canDrawOverlays(context)) add(OrderAlertRequirement.OVERLAY)
        if (Build.VERSION.SDK_INT >= 34 &&
            !context.getSystemService(NotificationManager::class.java).canUseFullScreenIntent()
        ) add(OrderAlertRequirement.FULL_SCREEN)
    }

    /** Opens the system screen where the partner turns this permission on. */
    fun settingsIntent(context: Context, requirement: OrderAlertRequirement): Intent {
        val pkgUri = Uri.parse("package:${context.packageName}")
        return when (requirement) {
            OrderAlertRequirement.NOTIFICATIONS -> Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            OrderAlertRequirement.OVERLAY -> Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, pkgUri)
            OrderAlertRequirement.FULL_SCREEN -> if (Build.VERSION.SDK_INT >= 34) {
                Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, pkgUri)
            } else {
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkgUri)
            }
        }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}

/** Live list of missing requirements, re-checked whenever the screen resumes (back from Settings). */
@Composable
fun rememberMissingOrderAlertPermissions(): List<OrderAlertRequirement> {
    val context = LocalContext.current
    var missing by remember { mutableStateOf(OrderAlertPermissions.missing(context)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        missing = OrderAlertPermissions.missing(context)
    }
    return missing
}

/** Blocks going online until every order-alert permission is granted. */
@Composable
fun OrderAlertSetupDialog(
    missing: List<OrderAlertRequirement>,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.NotificationsActive, contentDescription = null, tint = EmeraldPrimary) },
        title = { Text("Turn on order alerts") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("You must allow these to go online, so you never miss a new order:")
                OrderAlertRequirement.entries.forEach { req ->
                    val granted = req !in missing
                    Row(verticalAlignment = Alignment.CenterVertically) {
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
                            TextButton(
                                onClick = {
                                    try {
                                        context.startActivity(OrderAlertPermissions.settingsIntent(context, req))
                                    } catch (e: Exception) {
                                        context.startActivity(
                                            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
                                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                        )
                                    }
                                },
                                modifier = Modifier.testTag("allow_${req.name.lowercase()}")
                            ) { Text("Allow") }
                        }
                    }
                }
                Text(
                    "On Xiaomi, Oppo, Vivo and Realme phones also allow \"Display pop-up windows while running in the background\" in the app's permissions.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(if (missing.isEmpty()) "Done" else "Close") }
        }
    )
}

/** Warning shown while any order-alert permission is missing. */
@Composable
fun OrderAlertSetupBanner(missingCount: Int, onFix: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("order_alert_setup_banner"),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.ErrorOutline, contentDescription = null, tint = MaterialTheme.colorScheme.error)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text("Order alerts are not fully on", fontWeight = FontWeight.Bold)
                Text(
                    "$missingCount permission${if (missingCount > 1) "s" else ""} missing. You may miss new orders.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Button(onClick = onFix, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) {
                Text("Fix")
            }
        }
    }
}
