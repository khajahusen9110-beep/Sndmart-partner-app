package com.example.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.R
import java.net.HttpURLConnection
import java.net.URL

/**
 * Zomato-style push: Sndmart logo as the large icon, brand colour, full text and, when the push
 * carries data.image_url (a photo the City Admin attached), that photo as a big picture.
 * Call from FirebaseMessagingService.onMessageReceived, which already runs off the main thread.
 */
object NotificationVisuals {
    private const val TAG = "NotificationVisuals"
    const val BRAND_COLOR = 0xFF0B7A62.toInt()

    fun apply(context: Context, builder: NotificationCompat.Builder, body: String, imageUrl: String?) {
        builder.setColor(BRAND_COLOR)
        loadLogo(context)?.let { builder.setLargeIcon(it) }
        val picture = imageUrl?.takeIf { it.startsWith("https://") }?.let { downloadImage(it) }
        if (picture != null) {
            builder.setStyle(
                NotificationCompat.BigPictureStyle()
                    .bigPicture(picture)
                    .bigLargeIcon(null as Bitmap?)
                    .setSummaryText(body)
            )
        } else {
            builder.setStyle(NotificationCompat.BigTextStyle().bigText(body))
        }
    }

    private fun loadLogo(context: Context): Bitmap? = try {
        BitmapFactory.decodeResource(context.resources, R.drawable.sndmart_logo)
    } catch (e: Throwable) {
        Log.w(TAG, "Could not load notification logo: ${e.message}")
        null
    }

    private fun downloadImage(url: String): Bitmap? = try {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 5_000
            readTimeout = 5_000
        }
        try {
            if (connection.responseCode != 200) null
            else connection.inputStream.use { stream ->
                val bytes = stream.readBytes()
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                var sample = 1
                while (bounds.outWidth / (sample * 2) >= 1024) sample *= 2
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            }
        } finally {
            connection.disconnect()
        }
    } catch (e: Exception) {
        Log.w(TAG, "Could not download notification image: ${e.message}")
        null
    }
}
