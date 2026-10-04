package com.example.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PushAndClockTest {

    @Test
    fun newOffer_needsItsOwnAssignmentId() {
        assertEquals("a-1", newOfferAssignmentId(mapOf("assignment_id" to "a-1", "order_id" to "o-1")))
        // Edge function fallback: assignment_id copied from order_id -> not an offer.
        assertEquals("", newOfferAssignmentId(mapOf("assignment_id" to "o-1", "order_id" to "o-1")))
        assertEquals("", newOfferAssignmentId(mapOf("assignment_id" to "", "order_id" to "")))
        assertEquals("", newOfferAssignmentId(mapOf("title" to "Payout approved")))
    }

    @Test
    fun httpDate_parses() {
        // Thu, 01 Oct 2026 03:26:00 GMT
        assertEquals(1_790_825_160_000L, ServerClock.parseHttpDate("Thu, 01 Oct 2026 03:26:00 GMT"))
        assertNull(ServerClock.parseHttpDate("not a date"))
        assertNull(ServerClock.parseHttpDate(null))
    }

    @Test
    fun offset_ignoresSmallJitter() {
        ServerClock.updateOffset(0L)
        ServerClock.updateOffset(1_500L)
        assertEquals(0L, ServerClock.offset())
        ServerClock.updateOffset(90_000L)
        assertEquals(90_000L, ServerClock.offset())
        ServerClock.updateOffset(0L)
    }
}
