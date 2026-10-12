package com.example.service

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.R
import com.example.data.local.AppPreferences
import com.example.data.remote.SupabaseRepository
import com.example.util.NotificationVisuals
import com.example.util.newOfferAssignmentId
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

open class DeliveryPartnerFcmService : FirebaseMessagingService() {

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)
        Log.d(TAG, "FCM message received from: ${message.from}")

        val data = message.data
        val assignmentId = newOfferAssignmentId(data)
        val title = data["title"] ?: message.notification?.title
            ?: if (assignmentId.isNotBlank()) "New Delivery Request" else "Sndmart"
        val body = data["message"] ?: data["body"] ?: message.notification?.body ?: ""
        val repo = SupabaseRepository.getInstance(applicationContext)

        if (assignmentId.isBlank()) {
            showInfoNotification(title, body, data["image_url"])
            repo.addNotification(title, body, "info")
            return
        }

        // New order: sound + full-screen come from OrderAlertNotifier only.
        if (!repo.isAssignmentHandled(assignmentId)) {
            OrderAlertNotifier.show(
                applicationContext,
                OrderAlert(
                    assignmentId = assignmentId,
                    title = title,
                    body = body,
                    storeName = data["store_name"].orEmpty(),
                    storeAddress = data["store_address"].orEmpty(),
                    customerAddress = data["customer_address"].orEmpty(),
                    paymentMode = data["payment_mode"].orEmpty(),
                    totalAmount = data["total_amount"].orEmpty(),
                    deliveryFee = data["delivery_fee"].orEmpty(),
                    expiresAtIso = data["expires_at"]
                )
            )
        }
        repo.addNotification(title, body, "order")

        serviceScope.launch {
            try {
                val target = repo.fetchAssignments().find { it.id == assignmentId }
                when {
                    target == null -> Unit
                    target.status == "assigned" -> repo.setIncomingAssignmentAlert(target)
                    // Already taken back, cancelled or expired on the server: stop ringing.
                    else -> OrderAlertNotifier.cancel(applicationContext, assignmentId)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error syncing assignments on push: ${e.message}")
            }
        }
    }

    private fun showInfoNotification(title: String, body: String, imageUrl: String?) {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val notifyId = (title + body).hashCode()
        val pendingIntent = PendingIntent.getActivity(
            this, notifyId, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = NotificationCompat.Builder(this, SndmartFirebaseMessagingService.CHANNEL_DELIVERY_UPDATES)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(body)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
        NotificationVisuals.apply(this, builder, body, imageUrl)
        val notification = builder.build()
        try {
            getSystemService(NotificationManager::class.java).notify(notifyId, notification)
        } catch (e: SecurityException) {
            Log.w(TAG, "Notification permission missing: ${e.message}")
        }
    }

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        Log.i(TAG, "Firebase issued a new registration token")
        AppPreferences(applicationContext).fcmDeviceToken = token
        serviceScope.launch {
            // Fails quietly when signed out; the token is registered again after login.
            SupabaseRepository.getInstance(applicationContext).registerDeliveryPartnerFcmToken(token)
        }
    }

    companion object {
        private const val TAG = "DeliveryPartnerFcm"
        private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    }
}
