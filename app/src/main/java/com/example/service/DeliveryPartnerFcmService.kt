package com.example.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.R
import com.example.data.local.AppPreferences
import com.example.data.remote.SupabaseRepository
import com.example.ui.screens.order.IncomingOrderActivity
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

open class DeliveryPartnerFcmService : FirebaseMessagingService() {

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)
        Log.d(TAG, "FCM message received from: ${message.from}, data: ${message.data}")

        val orderId = message.data["orderId"] ?: message.data["order_id"] ?: ""
        val assignmentId = message.data["assignmentId"] ?: message.data["assignment_id"] ?: orderId
        val title = message.data["title"] ?: message.notification?.title ?: "New Order Available!"
        val body = message.data["message"] ?: message.data["body"] ?: message.notification?.body ?: "A new delivery is available near you"

        val fullScreenIntent = Intent(this, IncomingOrderActivity::class.java).apply {
            putExtra("order_id", orderId)
            putExtra("orderId", orderId)
            putExtra("assignment_id", assignmentId)
            putExtra("assignmentId", assignmentId)
            putExtra(IncomingOrderActivity.EXTRA_ORDER_ID, orderId)
            putExtra(IncomingOrderActivity.EXTRA_ASSIGNMENT_ID, assignmentId)
            putExtra(IncomingOrderActivity.EXTRA_TITLE, title)
            putExtra(IncomingOrderActivity.EXTRA_MESSAGE, body)

            message.data["store_name"]?.let { putExtra(IncomingOrderActivity.EXTRA_STORE_NAME, it) }
            message.data["store_address"]?.let { putExtra(IncomingOrderActivity.EXTRA_STORE_ADDRESS, it) }
            message.data["customer_address"]?.let { putExtra(IncomingOrderActivity.EXTRA_CUSTOMER_ADDRESS, it) }
            message.data["payment_mode"]?.let { putExtra(IncomingOrderActivity.EXTRA_PAYMENT_MODE, it) }
            message.data["total_amount"]?.toDoubleOrNull()?.let { putExtra(IncomingOrderActivity.EXTRA_TOTAL_AMOUNT, it) }
            message.data["delivery_fee"]?.toDoubleOrNull()?.let { putExtra(IncomingOrderActivity.EXTRA_DELIVERY_FEE, it) }
            message.data["pickup_distance"]?.let { putExtra("pickup_distance", it) }
            message.data["drop_distance"]?.let { putExtra("drop_distance", it) }
            message.data["expires_at"]?.let { putExtra("expires_at", it) }

            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }

        // 1. Direct Activity launch (triggers immediately if screen is on or app has foreground privileges)
        try {
            startActivity(fullScreenIntent)
            Log.d(TAG, "Direct IncomingOrderActivity launched for order $orderId")
        } catch (e: Exception) {
            Log.w(TAG, "Direct startActivity deferred to FullScreenIntent: ${e.message}")
        }

        // 2. High-priority NotificationChannel with loud ringtone & vibration
        val channelId = "new_delivery_alerts"
        val notificationManager = getSystemService(NotificationManager::class.java)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val audioAttributes = AudioAttributes.Builder()
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                .build()

            val ringtoneUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
                ?: Settings.System.DEFAULT_RINGTONE_URI

            val channel = NotificationChannel(
                channelId,
                "New Delivery Alerts",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Urgent full-screen dispatch alerts for assigned delivery orders"
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 800, 400, 800, 400, 800)
                setSound(ringtoneUri, audioAttributes)
                lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
            }
            notificationManager.createNotificationChannel(channel)
        }

        // 3. FullScreenIntent Notification for Lock Screen & Heads-Up Alert
        val pendingIntent = PendingIntent.getActivity(
            this,
            orderId.hashCode().takeIf { it != 0 } ?: 2001,
            fullScreenIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(body)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setFullScreenIntent(pendingIntent, true)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE) ?: Settings.System.DEFAULT_RINGTONE_URI)
            .setVibrate(longArrayOf(0, 800, 400, 800, 400, 800))
            .build()

        val notifyId = if (orderId.isNotBlank()) orderId.hashCode() else 2001
        notificationManager.notify(notifyId, notification)

        // 4. Background data sync to refresh local assignment state
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val repo = SupabaseRepository.getInstance(applicationContext)
                repo.addNotification(title, body, "order")
                repo.fetchAssignments()
            } catch (e: Exception) {
                Log.e(TAG, "Error syncing assignments on push: ${e.message}")
            }
        }
    }

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        Log.i(TAG, "Firebase onNewToken received: $token")
        registerDeliveryPartnerFcmToken(token)
    }

    private fun registerDeliveryPartnerFcmToken(token: String) {
        val prefs = AppPreferences(applicationContext)
        prefs.fcmDeviceToken = token
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val repo = SupabaseRepository.getInstance(applicationContext)
                repo.registerDeliveryPartnerFcmToken(token)
                Log.i(TAG, "Rotated FCM token registered to device_tokens table with firebase_project=native")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to register rotated FCM token: ${e.message}", e)
            }
        }
    }

    companion object {
        private const val TAG = "DeliveryPartnerFcm"
    }
}
