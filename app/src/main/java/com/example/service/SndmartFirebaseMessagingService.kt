package com.example.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.R
import com.example.data.local.AppPreferences
import com.example.data.remote.SupabaseApiService
import com.example.ui.screens.order.IncomingOrderActivity
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.util.concurrent.TimeUnit

class SndmartFirebaseMessagingService : FirebaseMessagingService() {

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        Log.d(TAG, "New FCM Token received: $token")
        val prefs = AppPreferences(applicationContext)
        prefs.fcmDeviceToken = token

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val repo = com.example.data.remote.SupabaseRepository.getInstance(applicationContext)
                repo.registerDeviceToken(token)
                Log.d(TAG, "Device token registered with backend via SupabaseRepository")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to register FCM token with backend", e)
            }
        }
    }

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        super.onMessageReceived(remoteMessage)
        Log.d(TAG, "FCM message from: ${remoteMessage.from}, data: ${remoteMessage.data}")

        val data = remoteMessage.data
        val type = data["type"] ?: "delivery_partner"
        val orderId = data["orderId"] ?: data["order_id"] ?: ""
        val assignmentId = data["assignmentId"] ?: data["assignment_id"] ?: orderId
        val title = data["title"] ?: remoteMessage.notification?.title ?: "Sndmart Delivery"
        val message = data["message"] ?: remoteMessage.notification?.body ?: "New delivery assignment"
        val isFullScreen = data["full_screen"] == "true" || type == "delivery_partner" || type == "new_assignment"

        createNotificationChannels()

        // Update in-memory notifications and fetch newest assignments immediately
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val repo = com.example.data.remote.SupabaseRepository.getInstance(applicationContext)
                repo.addNotification(title, message, type)
                repo.fetchAssignments()
            } catch (e: Exception) {
                Log.e(TAG, "Error updating repository state on FCM push: ${e.message}")
            }
        }

        if (isFullScreen && assignmentId.isNotEmpty()) {
            showIncomingOrderFullScreenNotification(assignmentId, title, message, data)
        } else {
            showStandardNotification(title, message)
        }
    }

    private fun showIncomingOrderFullScreenNotification(
        assignmentId: String,
        title: String,
        message: String,
        data: Map<String, String>
    ) {
        val fullScreenIntent = Intent(this, IncomingOrderActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(IncomingOrderActivity.EXTRA_ASSIGNMENT_ID, assignmentId)
            putExtra(IncomingOrderActivity.EXTRA_ORDER_ID, data["orderId"] ?: data["order_id"] ?: "")
            putExtra(IncomingOrderActivity.EXTRA_TITLE, title)
            putExtra(IncomingOrderActivity.EXTRA_MESSAGE, message)
            putExtra(IncomingOrderActivity.EXTRA_STORE_NAME, data["store_name"] ?: "Sndmart DarkStore")
            putExtra(IncomingOrderActivity.EXTRA_STORE_ADDRESS, data["store_address"] ?: "Local Store Hub")
            putExtra(IncomingOrderActivity.EXTRA_CUSTOMER_ADDRESS, data["customer_address"] ?: "Customer Destination")
            putExtra(IncomingOrderActivity.EXTRA_PAYMENT_MODE, data["payment_mode"] ?: "PREPAID")
            putExtra(IncomingOrderActivity.EXTRA_TOTAL_AMOUNT, data["total_amount"]?.toDoubleOrNull() ?: 450.0)
            putExtra(IncomingOrderActivity.EXTRA_DELIVERY_FEE, data["delivery_fee"]?.toDoubleOrNull() ?: 55.0)
        }

        val fullScreenPendingIntent = PendingIntent.getActivity(
            this,
            assignmentId.hashCode(),
            fullScreenIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val soundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)

        val notificationBuilder = NotificationCompat.Builder(this, CHANNEL_INCOMING_ORDERS)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setSound(soundUri)
            .setVibrate(longArrayOf(0, 500, 250, 500, 250, 500))
            .setAutoCancel(true)
            .setFullScreenIntent(fullScreenPendingIntent, true)
            .setContentIntent(fullScreenPendingIntent)

        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(assignmentId.hashCode(), notificationBuilder.build())
    }

    private fun showStandardNotification(title: String, message: String) {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            System.currentTimeMillis().toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notificationBuilder = NotificationCompat.Builder(this, CHANNEL_DELIVERY_UPDATES)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)

        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(System.currentTimeMillis().toInt(), notificationBuilder.build())
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            // Channel 1: Incoming Orders (High Priority + Ringtone sound + Lockscreen visibility)
            val ringtoneSoundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            val audioAttributes = AudioAttributes.Builder()
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                .build()

            val orderChannel = NotificationChannel(
                CHANNEL_INCOMING_ORDERS,
                "Incoming Delivery Orders",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Critical full-screen alerts for new assigned delivery orders"
                setSound(ringtoneSoundUri, audioAttributes)
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 500, 250, 500, 250, 500)
                lockscreenVisibility = NotificationCompat.VISIBILITY_PUBLIC
            }
            notificationManager.createNotificationChannel(orderChannel)

            // Channel 2: Delivery Updates (Default Priority)
            val updateChannel = NotificationChannel(
                CHANNEL_DELIVERY_UPDATES,
                "Delivery Updates",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Standard status updates and earnings notifications"
            }
            notificationManager.createNotificationChannel(updateChannel)
        }
    }

    companion object {
        private const val TAG = "SndmartFCM"
        const val CHANNEL_INCOMING_ORDERS = "incoming_delivery_orders"
        const val CHANNEL_DELIVERY_UPDATES = "delivery_updates"
    }
}
