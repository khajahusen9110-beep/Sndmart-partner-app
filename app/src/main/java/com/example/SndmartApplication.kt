package com.example

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import android.util.Log
import com.example.service.SndmartFirebaseMessagingService
import com.google.firebase.FirebaseApp

class SndmartApplication : Application() {

    override fun onCreate() {
        super.onCreate()

        // 1. Initialize Firebase App safely
        try {
            if (FirebaseApp.getApps(this).isEmpty()) {
                FirebaseApp.initializeApp(this)
                Log.d(TAG, "Firebase initialized successfully")
            } else {
                Log.d(TAG, "Firebase already initialized")
            }

            // Always keep auto-init disabled to prevent hard failure exceptions
            // on emulators and unconfigured environments. Tokens are fetched safely on demand.
            try {
                com.google.firebase.messaging.FirebaseMessaging.getInstance().isAutoInitEnabled = false
            } catch (ignored: Exception) {
            }
        } catch (e: Exception) {
            Log.e(TAG, "Firebase initialization error: ${e.message}", e)
        }

        // 2. Initialize Notification Channels on App Start
        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            // Channel 1: Incoming Delivery Orders (High importance + Ringtone + Vibration + Lockscreen visibility)
            val ringtoneSoundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            val audioAttributes = AudioAttributes.Builder()
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                .build()

            val orderChannel = NotificationChannel(
                SndmartFirebaseMessagingService.CHANNEL_INCOMING_ORDERS,
                "Incoming Delivery Orders",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Urgent full-screen alerts for newly assigned deliveries"
                setSound(ringtoneSoundUri, audioAttributes)
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 500, 250, 500, 250, 500)
                lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
            }
            notificationManager.createNotificationChannel(orderChannel)

            // Channel 2: Delivery Updates & Payouts (Default priority)
            val updateChannel = NotificationChannel(
                SndmartFirebaseMessagingService.CHANNEL_DELIVERY_UPDATES,
                "Delivery Updates & Payouts",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Order status updates, payout approvals, and notifications"
            }
            notificationManager.createNotificationChannel(updateChannel)

            Log.d(TAG, "Notification channels registered successfully")
        }
    }

    companion object {
        private const val TAG = "SndmartApp"
    }
}
