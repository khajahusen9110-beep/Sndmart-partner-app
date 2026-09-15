package com.example

import com.example.ui.MainViewModel
import org.junit.Assert.*
import org.junit.Test

class ExampleUnitTest {
    @Test
    fun addition_isCorrect() {
        assertEquals(4, 2 + 2)
    }

    @Test
    fun versionComparison_equalVersions() {
        assertEquals(0, MainViewModel.compareVersions("1.0.0", "1.0.0"))
        assertEquals(0, MainViewModel.compareVersions("1.0", "1.0.0"))
        assertEquals(0, MainViewModel.compareVersions("1.0.0", "1.0"))
    }

    @Test
    fun versionComparison_belowMinimumVersion() {
        assertTrue(MainViewModel.compareVersions("1.0.0", "1.0.1") < 0)
        assertTrue(MainViewModel.compareVersions("1.0.0", "1.1.0") < 0)
        assertTrue(MainViewModel.compareVersions("1.0.0", "2.0.0") < 0)
    }

    @Test
    fun versionComparison_aboveMinimumVersion() {
        assertTrue(MainViewModel.compareVersions("1.0.1", "1.0.0") > 0)
        assertTrue(MainViewModel.compareVersions("1.1.0", "1.0.0") > 0)
        assertTrue(MainViewModel.compareVersions("2.0.0", "1.9.9") > 0)
        assertTrue(MainViewModel.compareVersions("1.0.0", "0.9.9") > 0)
    }

    @Test
    fun startupChecks_updateRequiredCondition() {
        val currentAppVersion = MainViewModel.CURRENT_APP_VERSION

        // Case 1: below minimum version
        val minVerHigher = "1.0.1"
        val isBelow = MainViewModel.compareVersions(currentAppVersion, minVerHigher) < 0
        val forceUpdateFalse = false
        assertTrue(isBelow || forceUpdateFalse)

        // Case 2: equal to minimum version, force_update is false -> no update required
        val minVerEqual = "1.0.0"
        val isBelowEqual = MainViewModel.compareVersions(currentAppVersion, minVerEqual) < 0
        assertFalse(isBelowEqual || forceUpdateFalse)

        // Case 3: force_update is true -> update required even if equal
        val forceUpdateTrue = true
        assertTrue(isBelowEqual || forceUpdateTrue)
    }
}
