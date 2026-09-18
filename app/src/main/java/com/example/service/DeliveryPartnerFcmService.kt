package com.example.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Build
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
import kotlinx.coroutines.launch

open class DeliveryPartnerFcmService : FirebaseMessagingService() {

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)
        Log.d(TAG, "FCM message received from: ${message.from}, data: ${message.data}")

        val assignmentId = message.data["assignmentId"] ?: message.data["assignment_id"] ?: message.data["orderId"] ?: ""
        val title = message.data["title"] ?: message.notification?.title ?: "New Delivery Available!"
        val body = message.data["message"] ?: message.data["body"] ?: message.notification?.body ?: "A new delivery is available near you"

        val intent = Intent(this, MainActivity::class.java).apply {
            putExtra("assignment_id", assignmentId)
            putExtra("EXTRA_NAVIGATE_ASSIGNMENT_ID", assignmentId)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            if (assignmentId.isNotBlank()) assignmentId.hashCode() else 2001,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val channelId = "new_delivery_alerts"
        val notificationManager = getSystemService(NotificationManager::class.java)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId, "New Delivery Alerts", NotificationManager.IMPORTANCE_HIGH
            ).apply {
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 1000, 500, 1000, 500, 1000)
                // Set channel sound to null so the short default chime doesn't play over the 10s custom alert
                setSound(null, null)
            }
            notificationManager.createNotificationChannel(channel)
        }

        val notification = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(body)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setOngoing(false)
            .setSound(null)
            .build()

        val notifyId = if (assignmentId.isNotBlank()) assignmentId.hashCode() else 2001
        notificationManager.notify(notifyId, notification)

        // Play continuous ~10s alert sound via MediaPlayer alongside notification
        playLongAlertSound(this)

        // Background data sync to refresh local assignment state
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val repo = SupabaseRepository.getInstance(applicationContext)
                repo.addNotification(title, body, "order")
                val assignments = repo.fetchAssignments()
                if (assignmentId.isNotBlank()) {
                    val target = assignments.find { it.id == assignmentId }
                    if (target != null && target.status == "assigned" && !repo.isAssignmentHandled(target.id) && !repo.isAssignmentExpired(target)) {
                        repo.setIncomingAssignmentAlert(target)
                    }
                }
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

        fun playLongAlertSound(context: Context) {
            try {
                val mediaPlayer = MediaPlayer()
                val afd = context.resources.openRawResourceFd(R.raw.order_alert_sound)
                mediaPlayer.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                afd.close()
                mediaPlayer.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                mediaPlayer.isLooping = false
                mediaPlayer.setOnCompletionListener { it.release() }
                mediaPlayer.prepare()
                mediaPlayer.start()
                Log.d(TAG, "10-second order alert sound playback started successfully")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to play alert sound", e)
            }
        }
    }
}
