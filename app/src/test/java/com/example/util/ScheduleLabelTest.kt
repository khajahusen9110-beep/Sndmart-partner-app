package com.example.util

import org.junit.Assert.*
import org.junit.Test
import java.util.TimeZone

class ScheduleLabelTest {
    private val ist = TimeZone.getTimeZone("Asia/Kolkata")
    private val now = parseIsoMillis("2026-11-22T10:00:00+05:30")!!

    @Test fun tomorrowWindow_sameHalfOfDay() =
        assertEquals("Kal 4:00-6:00 PM", ScheduleLabel.window("2026-11-23T16:00:00+05:30", "2026-11-23T18:00:00+05:30", now, ist))

    @Test fun todayWindow_acrossNoon() =
        assertEquals("Aaj 11:00 AM-1:00 PM", ScheduleLabel.window("2026-11-22T11:00:00+05:30", "2026-11-22T13:00:00+05:30", now, ist))

    @Test fun utcTimestamps_areShownInLocalTime() =
        assertEquals("Kal 4:00-6:00 PM", ScheduleLabel.window("2026-11-23T10:30:00+00:00", "2026-11-23T12:30:00+00:00", now, ist))

    @Test fun noEnd_showsStartOnly() =
        assertEquals("Kal 4:00 PM", ScheduleLabel.window("2026-11-23T16:00:00+05:30", null, now, ist))

    @Test fun laterDay_showsDate() =
        assertEquals("Wed 25 Nov", ScheduleLabel.window("2026-11-25T16:00:00+05:30", null, now, ist)!!.substringBefore(" 4:"))

    @Test fun badInput_isNull() = assertNull(ScheduleLabel.window("soon", null, now, ist))

    @Test fun isScheduled_byTypeOrTime() {
        assertTrue(ScheduleLabel.isScheduled("scheduled", null)); assertTrue(ScheduleLabel.isScheduled(null, "2026-11-23T16:00:00Z"))
        assertFalse(ScheduleLabel.isScheduled("express", null)); assertFalse(ScheduleLabel.isScheduled(null, null))
    }

    @Test fun readyFrom_futureOnly() {
        assertEquals("Kal 2:00 PM", ScheduleLabel.readyFrom("2026-11-23T14:00:00+05:30", now, ist))
        assertNull(ScheduleLabel.readyFrom("2026-11-22T09:00:00+05:30", now, ist))
        assertNull(ScheduleLabel.readyFrom(null, now, ist))
    }

    @Test fun placeWord_shopOrRestaurant() {
        assertEquals("Shop", ScheduleLabel.placeWord("grocery", "cat-1")); assertEquals("Shop", ScheduleLabel.placeWord("vegetable", null))
        assertEquals("Restaurant", ScheduleLabel.placeWord("hotel", null)); assertEquals("Restaurant", ScheduleLabel.placeWord(null, null))
    }
}
