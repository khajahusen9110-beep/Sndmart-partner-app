package com.example.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class IsoTimeTest {
    // 2026-10-01T03:26:00Z
    private val base = 1_790_825_160_000L

    @Test
    fun parsesUtcVariants() {
        assertEquals(base, parseIsoMillis("2026-10-01T03:26:00Z"))
        assertEquals(base, parseIsoMillis("2026-10-01T03:26:00+00:00"))
        assertEquals(base, parseIsoMillis("2026-10-01 03:26:00+00"))
        assertEquals(base, parseIsoMillis("2026-10-01T03:26:00"))
    }

    @Test
    fun keepsMillisecondsFromFraction() {
        assertEquals(base + 123, parseIsoMillis("2026-10-01T03:26:00.123456+00:00"))
        assertEquals(base + 500, parseIsoMillis("2026-10-01T03:26:00.5Z"))
    }

    @Test
    fun appliesOffsets() {
        // 08:56 in India is 03:26 UTC
        assertEquals(base, parseIsoMillis("2026-10-01T08:56:00+05:30"))
        assertEquals(base, parseIsoMillis("2026-09-30T22:26:00-0500"))
    }

    @Test
    fun rejectsGarbage() {
        assertNull(parseIsoMillis(null))
        assertNull(parseIsoMillis(""))
        assertNull(parseIsoMillis("yesterday"))
        assertNull(parseIsoMillis("2026-13-45T99:00:00Z"))
    }
}
