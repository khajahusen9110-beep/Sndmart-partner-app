package com.example.util

/** Countdown to the delivery deadline: "12:05" left, or late by "03:10". */
data class DeliveryTimerState(val text: String, val isLate: Boolean)

fun deliveryTimerState(deadlineMillis: Long, nowMillis: Long): DeliveryTimerState {
    val diffSeconds = (deadlineMillis - nowMillis) / 1000L
    val late = diffSeconds < 0
    val total = if (late) -diffSeconds else diffSeconds
    val hours = total / 3600
    val minutes = (total % 3600) / 60
    val seconds = total % 60
    val text = if (hours > 0) {
        String.format(java.util.Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(java.util.Locale.US, "%02d:%02d", minutes, seconds)
    }
    return DeliveryTimerState(text, late)
}
