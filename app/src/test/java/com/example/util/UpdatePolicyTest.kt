package com.example.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdatePolicyTest {

    @Test
    fun belowMinimum_isRequired() {
        assertEquals(UpdateDecision.REQUIRED, decideUpdate("1.0.0", "1.1.0", "1.1.0", forceUpdate = false))
    }

    @Test
    fun forceUpdate_onlyBlocksOlderBuilds() {
        assertEquals(UpdateDecision.REQUIRED, decideUpdate("1.0.0", "1.0.0", "1.1.0", forceUpdate = true))
        // Already on the latest build: never blocked, even with the flag still on.
        assertEquals(UpdateDecision.NONE, decideUpdate("1.1.0", "1.0.0", "1.1.0", forceUpdate = true))
    }

    @Test
    fun newerLatest_isOptional() {
        assertEquals(UpdateDecision.OPTIONAL, decideUpdate("1.1.0", "1.0.0", "1.2.0", forceUpdate = false))
    }

    @Test
    fun current_isNone() {
        assertEquals(UpdateDecision.NONE, decideUpdate("1.1.0", "1.1.0", "1.1.0", forceUpdate = false))
        assertEquals(UpdateDecision.NONE, decideUpdate("1.1.0", null, null, forceUpdate = true))
    }

    @Test
    fun compareVersions_isNumeric() {
        assertTrue(compareVersions("1.10.0", "1.9.9") > 0)
        assertEquals(0, compareVersions("1.1", "1.1.0"))
        assertTrue(compareVersions("1.1.0.1", "1.1.0") > 0)
    }
}
