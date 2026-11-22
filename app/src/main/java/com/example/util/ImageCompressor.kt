package com.example.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.media.ExifInterface
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * A photo is squeezed to a small size (about 20-30 KB), whatever its size:
 * a 6 MB camera photo, a screenshot or a PNG with transparency. Customers' phones and the website then load the catalogue
 * fast even on a weak network, and storage stays small.
 *
 * It looks for the best-looking result that fits: the largest picture that still keeps a good JPEG quality, and only
 * lowers the quality (then the size) step by step when the photo is too detailed to fit.
 */
object ImageCompressor {
    /** The size a catalogue-style photo is brought under. */
    const val TARGET_BYTES = 20 * 1024

    /**
     * Delivery proof photos (parcel handed over, door-step photo): a little larger than 20 KB so the parcel, the door and
     * the house number stay recognisable if a customer ever disputes a delivery.
     */
    const val PROOF_TARGET_BYTES = 30 * 1024

    private val SIDES = listOf(1024, 896, 768, 640, 560, 480, 420, 360, 320, 280, 240, 200)
    private val QUALITY_FLOORS = listOf(60, 50, 40, 32, 25, 18)

    /** The search itself, apart from Android (so it can be tested): [encode] returns the encoded size for a side and a quality. */
    internal fun choose(maxSide: Int, target: Int, encode: (side: Int, quality: Int) -> Int): Pair<Int, Int> {
        // start from the picture's own size (at most 1024 px), then step down
        val top = minOf(maxSide, 1024).coerceAtLeast(64)
        val sides = listOf(top) + SIDES.filter { it < top }
        for (floor in QUALITY_FLOORS) {
            for (side in sides) {
                if (encode(side, floor) > target) continue // even the lowest allowed quality is too big at this size
                // the best quality (floor..90) that still fits: binary search, sizes only grow with quality
                var lo = floor; var hi = 90
                while (lo < hi) {
                    val mid = (lo + hi + 1) / 2
                    if (encode(side, mid) <= target) lo = mid else hi = mid - 1
                }
                return side to lo
            }
        }
        return sides.last() to QUALITY_FLOORS.last() // best effort; a photo this stubborn is very rare
    }

    /** Result of a compression: JPEG bytes, always. */
    class Result(val bytes: ByteArray, val width: Int, val height: Int, val originalBytes: Int)

    /** JPEG, PNG, GIF, WebP, BMP or HEIF: the first bytes tell what a file is, whatever its name says. */
    internal fun looksLikeImage(b: ByteArray): Boolean {
        if (b.size < 12) return false
        fun at(i: Int) = b[i].toInt() and 0xFF
        return (at(0) == 0xFF && at(1) == 0xD8) ||                                              // JPEG
            (at(0) == 0x89 && at(1) == 0x50 && at(2) == 0x4E && at(3) == 0x47) ||                // PNG
            (at(0) == 'G'.code && at(1) == 'I'.code && at(2) == 'F'.code) ||                     // GIF
            (at(0) == 'B'.code && at(1) == 'M'.code) ||                                          // BMP
            (at(0) == 'R'.code && at(1) == 'I'.code && at(2) == 'F'.code && at(3) == 'F'.code && at(8) == 'W'.code && at(9) == 'E'.code) || // WebP
            (at(4) == 'f'.code && at(5) == 't'.code && at(6) == 'y'.code && at(7) == 'p'.code)   // HEIC / HEIF / AVIF
    }

    /** Returns null when the bytes are not a readable image. */
    fun compress(original: ByteArray, target: Int = TARGET_BYTES): Result? {
        if (!looksLikeImage(original)) return null
        try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(original, 0, original.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            // decode small: the biggest picture we ever keep is 1024 px, so a huge photo is read at a fraction of its size
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 1024) sample *= 2
            val decoded = BitmapFactory.decodeByteArray(original, 0, original.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null

            val orientation = try {
                ExifInterface(ByteArrayInputStream(original)).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            } catch (_: Exception) { ExifInterface.ORIENTATION_NORMAL }
            val degrees = when (orientation) { ExifInterface.ORIENTATION_ROTATE_90 -> 90f; ExifInterface.ORIENTATION_ROTATE_180 -> 180f; ExifInterface.ORIENTATION_ROTATE_270 -> 270f; else -> 0f }
            // JPEG has no transparency: put the picture on white (a transparent PNG would turn black)
            val flat = Bitmap.createBitmap(decoded.width, decoded.height, Bitmap.Config.ARGB_8888).also { b ->
                Canvas(b).apply { drawColor(Color.WHITE); drawBitmap(decoded, 0f, 0f, null) }
            }
            val upright = if (degrees != 0f) Bitmap.createBitmap(flat, 0, 0, flat.width, flat.height, Matrix().apply { postRotate(degrees) }, true) else flat

            val cache = HashMap<Long, ByteArray>()
            fun scaled(side: Int): Bitmap {
                val scale = minOf(1f, side.toFloat() / maxOf(upright.width, upright.height))
                return if (scale >= 1f) upright else Bitmap.createScaledBitmap(upright, maxOf(1, (upright.width * scale).toInt()), maxOf(1, (upright.height * scale).toInt()), true)
            }
            val bitmaps = HashMap<Int, Bitmap>()
            fun encode(side: Int, quality: Int): ByteArray = cache.getOrPut(side.toLong() * 1000 + quality) {
                val bmp = bitmaps.getOrPut(side) { scaled(side) }
                ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.JPEG, quality, it) }.toByteArray()
            }
            val (side, quality) = choose(maxOf(upright.width, upright.height), target) { s, q -> encode(s, q).size }
            val out = encode(side, quality)
            val bmp = bitmaps.getOrPut(side) { scaled(side) }
            return Result(out, bmp.width, bmp.height, original.size)
        } catch (_: OutOfMemoryError) {
            return null
        } catch (_: Exception) {
            return null
        }
    }
}
