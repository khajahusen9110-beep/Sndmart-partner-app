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

            // Enable Firebase Cloud Messaging auto-init with official project configuration
            try {
                com.google.firebase.messaging.FirebaseMessaging.getInstance().isAutoInitEnabled = true
                Log.d(TAG, "FirebaseMessaging auto-init enabled for newsndmart project")
            } catch (e: Exception) {
                Log.w(TAG, "FirebaseMessaging auto-init warning: ${e.message}")
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

            val ringtoneSoundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
                ?: android.provider.Settings.System.DEFAULT_RINGTONE_URI
            val audioAttributes = AudioAttributes.Builder()
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                .build()

            // Channel 1: New Delivery Alerts (Step 4 specification)
            val newAlertsChannel = NotificationChannel(
                "new_delivery_alerts",
                "New Delivery Alerts",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Urgent full-screen dispatch alerts for assigned delivery orders"
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 800, 400, 800, 400, 800)
                setSound(ringtoneSoundUri, audioAttributes)
                lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
            }
            notificationManager.createNotificationChannel(newAlertsChannel)

            // Channel 2: Delivery Updates & Payouts (Default priority)
            val updateChannel = NotificationChannel(
                "delivery_updates",
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
