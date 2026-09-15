package com.example.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.util.Log
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import kotlin.math.max

object ImageUtils {

    private const val TAG = "ImageUtils"
    private const val DEFAULT_MAX_DIMENSION = 1280
    private const val TARGET_MAX_BYTES = 800 * 1024 // ~800 KB

    /**
     * Compresses and resizes an image from a content Uri.
     * Uses inSampleSize sampling to avoid OutOfMemoryError on large photos,
     * corrects orientation using EXIF data, and compresses to JPEG under 1MB.
     */
    fun compressAndResizeUri(
        context: Context,
        uri: Uri,
        maxDimension: Int = DEFAULT_MAX_DIMENSION
    ): ByteArray? {
        return try {
            // Step 1: Query dimensions
            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            context.contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, options)
            }

            val origWidth = options.outWidth
            val origHeight = options.outHeight
            if (origWidth <= 0 || origHeight <= 0) {
                // Fallback to reading bytes directly
                val rawBytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                return rawBytes?.let { compressAndResizeBytes(it, maxDimension) }
            }

            // Step 2: Compute sample size
            var sampleSize = 1
            val maxSide = max(origWidth, origHeight)
            while ((maxSide / sampleSize) > (maxDimension * 2)) {
                sampleSize *= 2
            }

            // Step 3: Decode sampled bitmap
            val decodeOptions = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.RGB_565 // Lower memory footprint
            }
            val sampledBitmap = context.contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, decodeOptions)
            } ?: return null

            // Step 4: Check EXIF orientation
            val orientation = try {
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    val exif = ExifInterface(stream)
                    exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
                } ?: ExifInterface.ORIENTATION_NORMAL
            } catch (e: Exception) {
                ExifInterface.ORIENTATION_NORMAL
            }

            val orientedBitmap = applyExifOrientation(sampledBitmap, orientation)
            compressAndResizeBitmap(orientedBitmap, maxDimension)
        } catch (e: Exception) {
            Log.e(TAG, "Error compressing Uri image: ${e.message}", e)
            null
        }
    }

    /**
     * Compresses and resizes a raw ByteArray image.
     */
    fun compressAndResizeBytes(
        bytes: ByteArray,
        maxDimension: Int = DEFAULT_MAX_DIMENSION
    ): ByteArray {
        return try {
            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)

            var sampleSize = 1
            val maxSide = max(options.outWidth, options.outHeight)
            while ((maxSide / sampleSize) > (maxDimension * 2)) {
                sampleSize *= 2
            }

            val decodeOptions = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.RGB_565
            }
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOptions)
                ?: return bytes

            val orientation = try {
                val exif = ExifInterface(ByteArrayInputStream(bytes))
                exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            } catch (e: Exception) {
                ExifInterface.ORIENTATION_NORMAL
            }

            val orientedBitmap = applyExifOrientation(bitmap, orientation)
            compressAndResizeBitmap(orientedBitmap, maxDimension)
        } catch (e: Exception) {
            Log.e(TAG, "Error compressing ByteArray: ${e.message}", e)
            bytes
        }
    }

    /**
     * Resizes a Bitmap to maxDimension maintaining aspect ratio,
     * then compresses into JPEG bytes targeted under 800KB.
     */
    fun compressAndResizeBitmap(
        bitmap: Bitmap,
        maxDimension: Int = DEFAULT_MAX_DIMENSION
    ): ByteArray {
        val width = bitmap.width
        val height = bitmap.height
        val maxSide = max(width, height)

        val scaledBitmap = if (maxSide > maxDimension) {
            val scale = maxDimension.toFloat() / maxSide
            val newWidth = (width * scale).toInt()
            val newHeight = (height * scale).toInt()
            Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
        } else {
            bitmap
        }

        var quality = 82
        val stream = ByteArrayOutputStream()
        scaledBitmap.compress(Bitmap.CompressFormat.JPEG, quality, stream)
        var resultBytes = stream.toByteArray()

        // If still > target size, step down quality
        if (resultBytes.size > TARGET_MAX_BYTES && quality > 60) {
            quality = 65
            stream.reset()
            scaledBitmap.compress(Bitmap.CompressFormat.JPEG, quality, stream)
            resultBytes = stream.toByteArray()
        }

        if (scaledBitmap != bitmap) {
            scaledBitmap.recycle()
        }

        Log.d(TAG, "Compressed photo to ${resultBytes.size / 1024} KB (${resultBytes.size} bytes)")
        return resultBytes
    }

    private fun applyExifOrientation(bitmap: Bitmap, orientation: Int): Bitmap {
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            else -> return bitmap
        }
        return try {
            val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            if (rotated != bitmap) {
                bitmap.recycle()
            }
            rotated
        } catch (e: Exception) {
            Log.w(TAG, "Failed to apply EXIF matrix: ${e.message}")
            bitmap
        }
    }
}
