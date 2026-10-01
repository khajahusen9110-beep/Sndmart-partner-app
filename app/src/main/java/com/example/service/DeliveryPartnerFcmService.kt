package com.example.service

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.R
import com.example.data.local.AppPreferences
import com.example.data.remote.SupabaseRepository
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

        val assignmentId = message.data["assignment_id"] ?: message.data["assignmentId"] ?: ""
        val isNewOrder = assignmentId.isNotBlank()
        val title = message.data["title"] ?: message.notification?.title
            ?: if (isNewOrder) "New Delivery Available!" else "Sndmart"
        val body = message.data["message"] ?: message.data["body"] ?: message.notification?.body
            ?: if (isNewOrder) "A new delivery is available near you" else ""

        val intent = Intent(this, MainActivity::class.java).apply {
            if (isNewOrder) {
                putExtra("assignment_id", assignmentId)
                putExtra("EXTRA_NAVIGATE_ASSIGNMENT_ID", assignmentId)
            }
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val notifyId = if (isNewOrder) assignmentId.hashCode() else (title + body).hashCode()
        val pendingIntent = PendingIntent.getActivity(
            this,
            notifyId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Channels are created in SndmartApplication.
        val channelId = if (isNewOrder) SndmartFirebaseMessagingService.CHANNEL_INCOMING_ORDERS
        else SndmartFirebaseMessagingService.CHANNEL_DELIVERY_UPDATES

        val notification = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(if (isNewOrder) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(if (isNewOrder) NotificationCompat.CATEGORY_CALL else NotificationCompat.CATEGORY_STATUS)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        getSystemService(NotificationManager::class.java).notify(notifyId, notification)

        if (isNewOrder && AppPreferences(applicationContext).soundAlertsEnabled) {
            playLongAlertSound(this)
        }

        val repo = SupabaseRepository.getInstance(applicationContext)
        repo.addNotification(title, body, if (isNewOrder) "order" else "info")
        if (isNewOrder) {
            serviceScope.launch {
                try {
                    val target = repo.fetchAssignments().find { it.id == assignmentId }
                    if (target != null && target.status == "assigned") {
                        repo.setIncomingAssignmentAlert(target)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error syncing assignments on push: ${e.message}")
                }
            }
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

        private val playerLock = Any()
        private var activePlayer: MediaPlayer? = null

        /** Plays the ~10s new-order alert once. A new alert replaces one that is still ringing. */
        fun playLongAlertSound(context: Context) {
            synchronized(playerLock) {
                stopAlertSoundLocked()
                try {
                    val afd = context.resources.openRawResourceFd(R.raw.order_alert_sound)
                    val player = MediaPlayer()
                    afd.use { player.setDataSource(it.fileDescriptor, it.startOffset, it.length) }
                    player.setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build()
                    )
                    player.isLooping = false
                    player.setOnCompletionListener { stopAlertSound() }
                    player.prepare()
                    player.start()
                    activePlayer = player
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to play alert sound", e)
                }
            }
        }

        /** Stops the alert, e.g. when the partner accepts or declines the order. */
        fun stopAlertSound() {
            synchronized(playerLock) { stopAlertSoundLocked() }
        }

        private fun stopAlertSoundLocked() {
            activePlayer?.let {
                try {
                    if (it.isPlaying) it.stop()
                } catch (_: IllegalStateException) {
                }
                it.release()
            }
            activePlayer = null
        }
    }
}
