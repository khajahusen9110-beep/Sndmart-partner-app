package com.example.util

import org.junit.Assert.assertEquals
import org.junit.Test

class DeliveryTimerTest {

    @Test
    fun countsDownThenShowsLateTime() {
        assertEquals(DeliveryTimerState("14:32", false), deliveryTimerState(872_000L, 0L))
        assertEquals(DeliveryTimerState("00:00", false), deliveryTimerState(1_000L, 1_000L))
        assertEquals(DeliveryTimerState("03:10", true), deliveryTimerState(0L, 190_000L))
        assertEquals(DeliveryTimerState("1:05:00", false), deliveryTimerState(3_900_000L, 0L))
    }
}
