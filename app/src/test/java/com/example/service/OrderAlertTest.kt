package com.example.service

import org.junit.Assert.assertEquals
import org.junit.Test

class OrderAlertTest {

    @Test
    fun expiresAt_usesServerExpiry() {
        val alert = OrderAlert(assignmentId = "a", expiresAtIso = "2026-10-01T03:27:00Z")
        assertEquals(1_790_825_220_000L, alert.expiresAtMillis(now = 0L))
    }

    @Test
    fun expiresAt_fallsBackToDefaultWindow() {
        assertEquals(1_000L + OrderAlert.DEFAULT_WINDOW_MS, OrderAlert("a").expiresAtMillis(now = 1_000L))
        assertEquals(
            1_000L + OrderAlert.DEFAULT_WINDOW_MS,
            OrderAlert("a", expiresAtIso = "garbage").expiresAtMillis(now = 1_000L)
        )
    }
}
