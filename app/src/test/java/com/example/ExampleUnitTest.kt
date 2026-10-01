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
        val current = "1.0.0"

        // Below the minimum supported version -> update required
        assertTrue(MainViewModel.compareVersions(current, "1.0.1") < 0)

        // Equal to the minimum and force_update false -> no update required
        assertFalse(MainViewModel.compareVersions(current, "1.0.0") < 0)
    }

    @Test
    fun currentAppVersion_isSemantic() {
        assertTrue(MainViewModel.CURRENT_APP_VERSION.matches(Regex("""\d+\.\d+\.\d+.*""")))
    }
}
