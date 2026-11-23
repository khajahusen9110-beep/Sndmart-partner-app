package com.example.util

import org.junit.Assert.assertEquals
import org.junit.Test

class HelperPushTest {
    @Test fun helperRequest_givesTheBookingId() =
        assertEquals("b-1", helperRequestBookingId(mapOf("type" to "helper_request", "order_id" to "b-1", "store_name" to "Queue · 60 min")))

    @Test fun otherHelperPushes_doNotRing() {
        assertEquals("", helperRequestBookingId(mapOf("type" to "helper_cancelled", "order_id" to "b-1")))
        assertEquals("", helperRequestBookingId(mapOf("type" to "helper_extension_request", "order_id" to "b-1")))
    }

    @Test fun deliveryOffer_isNotAHelperRequest() {
        assertEquals("", helperRequestBookingId(mapOf("assignment_id" to "a-1", "order_id" to "o-1")))
        assertEquals("a-1", newOfferAssignmentId(mapOf("assignment_id" to "a-1", "order_id" to "o-1")))
    }

    @Test fun helperRequest_isNotADeliveryOffer() =
        assertEquals("", newOfferAssignmentId(mapOf("type" to "helper_request", "order_id" to "b-1")))

    @Test fun missingId_isEmpty() = assertEquals("", helperRequestBookingId(mapOf("type" to "helper_request")))
}
