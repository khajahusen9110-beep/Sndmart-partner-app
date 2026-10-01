package com.example.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.example.MainActivity
import com.example.R

/**
 * Foreground service that keeps the process alive while the partner is online so
 * [com.example.data.location.LocationTracker] keeps pushing live GPS in the background.
 * The tracking itself lives in LocationTracker; this service only owns the ongoing notification.
 */
class LocationTrackingService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            } else {
                0
            }
            ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(), type)
        } catch (e: Exception) {
            // Android 12+ refuses to start a foreground service from the background, and
            // Android 14 refuses a location FGS without location permission.
            Log.w(TAG, "Unable to enter foreground: ${e.message}")
            stopSelf()
        }
        return START_STICKY
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Live location", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Shown while you are online and sharing your location"
                }
            )
        }
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("You are online")
            .setContentText("Sharing live location to receive delivery orders")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    companion object {
        private const val TAG = "LocationTrackingSvc"
        private const val CHANNEL_ID = "live_location"
        private const val NOTIFICATION_ID = 4201

        fun start(context: Context) {
            try {
                ContextCompat.startForegroundService(context, Intent(context, LocationTrackingService::class.java))
            } catch (e: Exception) {
                Log.w(TAG, "Foreground service start refused: ${e.message}")
            }
        }

        fun stop(context: Context) {
            try {
                context.stopService(Intent(context, LocationTrackingService::class.java))
            } catch (e: Exception) {
                Log.w(TAG, "Foreground service stop failed: ${e.message}")
            }
        }
    }
}
