package com.example.util

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs

/**
 * Server time, corrected for a wrong phone clock.
 *
 * Offer expiry (`expires_at`) is set by the server. Comparing it with the phone's own clock
 * breaks on phones whose time is off: a clock one minute fast treats every new offer as
 * already expired (no alert, instant auto-decline). Every Supabase response carries an HTTP
 * `Date` header; the difference to the phone clock is kept here and persisted, so it also
 * applies when a push wakes the app before any network call.
 */
object ServerClock {
    private const val PREFS = "sndmart_server_clock"
    private const val KEY_OFFSET = "offset_ms"

    @Volatile
    private var offsetMs = 0L
    private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
        offsetMs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(KEY_OFFSET, 0L)
    }

    /** Current time on the server's clock (epoch millis). */
    fun now(): Long = System.currentTimeMillis() + offsetMs

    /** Feed the `Date` header of a server response. */
    fun onServerDate(dateHeader: String?) {
        val serverMillis = parseHttpDate(dateHeader) ?: return
        updateOffset(serverMillis - System.currentTimeMillis())
    }

    internal fun updateOffset(newOffset: Long) {
        // The header has 1 s resolution plus network latency; ignore sub-2 s jitter.
        if (abs(newOffset - offsetMs) < 2_000L) return
        offsetMs = newOffset
        appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.edit()?.putLong(KEY_OFFSET, newOffset)?.apply()
    }

    internal fun offset(): Long = offsetMs

    /** RFC 1123 date as sent in HTTP headers, e.g. "Sun, 04 Oct 2026 01:02:03 GMT". */
    fun parseHttpDate(value: String?): Long? {
        if (value.isNullOrBlank()) return null
        return try {
            SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("GMT")
            }.parse(value)?.time
        } catch (e: Exception) {
            null
        }
    }
}
