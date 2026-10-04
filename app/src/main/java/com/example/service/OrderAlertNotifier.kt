package com.example.service

import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.R
import com.example.data.local.AppPreferences
import com.example.ui.incoming.IncomingOrderActivity
import com.example.util.parseIsoMillis
import java.util.Collections

/** What the full-screen order screen and the notification show for one offer. */
data class OrderAlert(
    val assignmentId: String,
    val title: String = "New Delivery Request",
    val body: String = "",
    val storeName: String = "",
    val storeAddress: String = "",
    val customerAddress: String = "",
    val paymentMode: String = "",
    val totalAmount: String = "",
    val deliveryFee: String = "",
    val expiresAtIso: String? = null
) {
    /** When the offer ends; falls back to [DEFAULT_WINDOW_MS] if the server sent no expiry. */
    fun expiresAtMillis(now: Long = System.currentTimeMillis()): Long =
        parseIsoMillis(expiresAtIso) ?: (now + DEFAULT_WINDOW_MS)

    companion object {
        const val DEFAULT_WINDOW_MS = 60_000L
    }
}

/**
 * The single place that alerts a partner about a new order.
 *
 * Sound: the notification itself. It is posted on an alarm channel that plays
 * `order_alert_sound` with FLAG_INSISTENT (repeats until the notification is gone) and
 * `setTimeoutAfter` (Android removes it when the offer expires). Cancelling the notification
 * is therefore the only way to stop the sound: accept, decline, timeout, server cancel.
 *
 * Screen: [IncomingOrderActivity] has no sound of its own. It is opened as the full-screen
 * intent (locked / screen off) and launched directly when the screen is on, which Android
 * allows because the partner granted "Display over other apps".
 */
object OrderAlertNotifier {
    private const val TAG = "OrderAlertNotifier"

    // Channel settings are frozen once created, so sound changes need a new channel id.
    const val CHANNEL_ALARM = "new_order_alarm_v2"
    const val CHANNEL_SILENT = "new_order_silent_v2"
    private val LEGACY_CHANNELS = listOf("new_delivery_alerts")

    private val shownIds = Collections.synchronizedSet(mutableSetOf<String>())

    fun notificationId(assignmentId: String) = assignmentId.hashCode()

    /** True if an alert was raised for this offer in this process (used to stop it later). */
    fun wasShown(assignmentId: String) = shownIds.contains(assignmentId)

    fun createChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(NotificationManager::class.java)
        LEGACY_CHANNELS.forEach { nm.deleteNotificationChannel(it) }

        val soundUri = Uri.parse(
            "${ContentResolver.SCHEME_ANDROID_RESOURCE}://${context.packageName}/${R.raw.order_alert_sound}"
        )
        val alarmAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ALARM, "New order alerts", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Rings until you accept or decline a new delivery request"
                setSound(soundUri, alarmAttributes)
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 800, 400, 800, 400, 800)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_SILENT, "New order alerts (sound off)", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Used when order alert sound is turned off in the app"
                setSound(null, null)
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 800, 400, 800, 400, 800)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
        )
    }

    /**
     * Shows the alert for one offer. Calling it again for the same offer (FCM push and the
     * polling fallback both report it) does nothing, so the sound never restarts.
     */
    fun show(context: Context, alert: OrderAlert) {
        val appContext = context.applicationContext
        if (alert.assignmentId.isBlank()) return
        val remaining = alert.expiresAtMillis() - System.currentTimeMillis()
        if (remaining <= 1_000L) return
        if (!shownIds.add(alert.assignmentId)) return

        createChannels(appContext)
        val soundOn = AppPreferences(appContext).soundAlertsEnabled
        val nid = notificationId(alert.assignmentId)

        val fullScreen = PendingIntent.getActivity(
            appContext,
            nid,
            IncomingOrderActivity.intent(appContext, alert),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        // Accept opens the app directly (no broadcast trampoline, which Android 12+ blocks).
        val accept = PendingIntent.getActivity(
            appContext,
            nid + 1,
            Intent(appContext, MainActivity::class.java).apply {
                action = MainActivity.ACTION_ACCEPT_ORDER
                putExtra(MainActivity.EXTRA_ASSIGNMENT_ID, alert.assignmentId)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val decline = PendingIntent.getBroadcast(
            appContext,
            nid + 2,
            Intent(appContext, OrderActionReceiver::class.java).apply {
                action = OrderActionReceiver.ACTION_DECLINE
                putExtra(OrderActionReceiver.EXTRA_ASSIGNMENT_ID, alert.assignmentId)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val text = alert.body.ifBlank {
            listOf(alert.storeName, alert.customerAddress).filter { it.isNotBlank() }.joinToString(" → ")
        }
        val notification = NotificationCompat.Builder(appContext, if (soundOn) CHANNEL_ALARM else CHANNEL_SILENT)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(alert.title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setFullScreenIntent(fullScreen, true)
            .setContentIntent(fullScreen)
            .addAction(0, "Decline", decline)
            .addAction(0, "Accept", accept)
            .setOngoing(true)
            .setAutoCancel(false)
            .setTimeoutAfter(remaining)
            .build()
            .apply { flags = flags or Notification.FLAG_INSISTENT }

        try {
            appContext.getSystemService(NotificationManager::class.java).notify(nid, notification)
        } catch (e: SecurityException) {
            Log.w(TAG, "Notification permission missing: ${e.message}")
        }

        // Locked / screen off: Android opens the full-screen intent itself.
        // Screen on (partner using any app): open the screen directly. Allowed from the
        // background only with "Display over other apps".
        // Never cover the screen of an ongoing phone call; the heads-up still shows Accept/Decline.
        if (isScreenOnAndUnlocked(appContext) && Settings.canDrawOverlays(appContext) && !isInCall(appContext)) {
            try {
                appContext.startActivity(IncomingOrderActivity.intent(appContext, alert))
            } catch (e: Exception) {
                Log.w(TAG, "Could not open incoming order screen: ${e.message}")
            }
        }
    }

    /** Stops the sound and removes the alert (accept, decline, timeout, offer withdrawn). */
    fun cancel(context: Context, assignmentId: String) {
        if (assignmentId.isBlank()) return
        context.applicationContext.getSystemService(NotificationManager::class.java)
            .cancel(notificationId(assignmentId))
        IncomingOrderActivity.closeIfShowing(assignmentId)
    }

    fun cancelAll(context: Context) {
        synchronized(shownIds) {
            shownIds.forEach { cancel(context, it) }
        }
    }

    private fun isInCall(context: Context): Boolean {
        val mode = context.getSystemService(AudioManager::class.java).mode
        return mode == AudioManager.MODE_IN_CALL || mode == AudioManager.MODE_IN_COMMUNICATION
    }

    private fun isScreenOnAndUnlocked(context: Context): Boolean {
        val power = context.getSystemService(PowerManager::class.java)
        val keyguard = context.getSystemService(KeyguardManager::class.java)
        return power.isInteractive && !keyguard.isKeyguardLocked
    }
}
