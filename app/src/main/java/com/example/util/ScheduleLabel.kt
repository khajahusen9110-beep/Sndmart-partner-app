package com.example.util

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/** Plain-words delivery window for a scheduled order, e.g. "Kal 4:00-6:00 PM" or "Aaj 4:00-6:00 PM". */
object ScheduleLabel {
    private const val DAY_MS = 86_400_000L

    fun isScheduled(deliveryType: String?, scheduledFor: String?): Boolean =
        deliveryType.equals("scheduled", true) || !scheduledFor.isNullOrBlank()

    fun window(
        scheduledFor: String?,
        scheduledUntil: String?,
        nowMs: Long = System.currentTimeMillis(),
        zone: TimeZone = TimeZone.getDefault()
    ): String? {
        val start = parseIsoMillis(scheduledFor) ?: return null
        val end = parseIsoMillis(scheduledUntil)
        val day = dayWord(start, nowMs, zone)
        val clock = SimpleDateFormat("h:mm", Locale.US).apply { timeZone = zone }
        val ampm = SimpleDateFormat("a", Locale.US).apply { timeZone = zone }
        val from = clock.format(start)
        return if (end == null || end <= start) "$day $from ${ampm.format(start)}"
        else {
            val sameHalf = ampm.format(start) == ampm.format(end)
            if (sameHalf) "$day $from-${clock.format(end)} ${ampm.format(end)}"
            else "$day $from ${ampm.format(start)}-${clock.format(end)} ${ampm.format(end)}"
        }
    }

    /** "Aaj 2:00 PM" when the shop starts preparing in the future (a rider should not arrive earlier); null when already past or missing. */
    fun readyFrom(prepareFrom: String?, nowMs: Long = System.currentTimeMillis(), zone: TimeZone = TimeZone.getDefault()): String? {
        val t = parseIsoMillis(prepareFrom) ?: return null
        if (t <= nowMs) return null
        val clock = SimpleDateFormat("h:mm a", Locale.US).apply { timeZone = zone }
        return "${dayWord(t, nowMs, zone)} ${clock.format(t)}"
    }

    /** Pickup place in plain words: a shop is a "Shop", a hotel a "Restaurant". */
    fun placeWord(vendorType: String?, storeCategoryId: String?): String =
        if (!storeCategoryId.isNullOrBlank() || (vendorType != null && !vendorType.equals("hotel", true))) "Shop" else "Restaurant"

    private fun dayWord(startMs: Long, nowMs: Long, zone: TimeZone): String {
        fun dayStart(ms: Long) = Calendar.getInstance(zone).apply {
            timeInMillis = ms; set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val diff = Math.round((dayStart(startMs) - dayStart(nowMs)).toDouble() / DAY_MS).toInt()
        return when (diff) {
            0 -> "Aaj"
            1 -> "Kal"
            -1 -> "Kal (beeta)"
            else -> SimpleDateFormat("EEE d MMM", Locale.US).apply { timeZone = zone }.format(startMs)
        }
    }
}
