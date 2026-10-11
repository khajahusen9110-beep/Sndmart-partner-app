package com.example.util

import com.example.data.model.CashStatus
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CashStatusTest {

    @Test
    fun blockedPartner_settlesOnlyAfterActiveDelivery() {
        assertTrue(CashStatus(cashInHand = 2100.0, cashLimit = 2000.0, blocked = true).mustSettle)
        // An accepted delivery is finished first; the lock comes after.
        assertFalse(CashStatus(cashInHand = 2100.0, cashLimit = 2000.0, blocked = true, hasActiveDelivery = true).mustSettle)
        assertFalse(CashStatus(cashInHand = 500.0, cashLimit = 2000.0, blocked = false).mustSettle)
    }

    @Test
    fun pendingSettlement_meansAwaitingVerification() {
        assertTrue(CashStatus(cashInHand = 2100.0, pendingSettlement = 2100.0, blocked = true).awaitingVerification)
        assertFalse(CashStatus(cashInHand = 2100.0, blocked = true).awaitingVerification)
    }
}
