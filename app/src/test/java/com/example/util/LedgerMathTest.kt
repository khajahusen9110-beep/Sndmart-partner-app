package com.example.util

import com.example.data.model.CashLedgerEntry
import com.example.data.model.DeliveryEarning
import com.example.data.model.PaymentRequest
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LedgerMathTest {

    private fun cash(type: String, amount: Double, status: String = "confirmed") =
        CashLedgerEntry(id = "$type-$amount-$status", entryType = type, amount = amount, status = status)

    @Test
    fun cashOnDelivery_matchesBackendEnum() {
        assertTrue(isCashOnDelivery("cash"))
        assertTrue(isCashOnDelivery("COD"))
        assertFalse(isCashOnDelivery("upi"))
        assertFalse(isCashOnDelivery(null))
    }

    @Test
    fun cashInHand_ignoresRejectedAndCountsPendingRemittance() {
        val entries = listOf(
            cash("collected", 500.0),
            cash("collected", 300.0),
            cash("remitted", 200.0, status = "pending"),
            cash("remitted", 400.0, status = "rejected")
        )
        assertEquals(600.0, cashInHand(entries), 0.001)
    }

    @Test
    fun availableForPayout_mirrorsServerRule() {
        val earnings = listOf(
            DeliveryEarning(id = "1", netEarning = 60.0, status = "pending"),
            DeliveryEarning(id = "2", netEarning = 75.0, status = "pending"),
            DeliveryEarning(id = "3", netEarning = 999.0, status = "paid")
        )
        val requests = listOf(
            PaymentRequest(id = "a", amount = 100.0, status = "pending"),
            PaymentRequest(id = "b", amount = 500.0, status = "paid")
        )
        assertEquals(35.0, availableForPayout(earnings, requests), 0.001)
        assertEquals(0.0, availableForPayout(emptyList(), requests), 0.001)
    }

    @Test
    fun todayEarnings_onlyCountsToday() {
        val utc = TimeZone.getTimeZone("UTC")
        val now = parseIsoMillis("2026-10-01T12:00:00Z")!!
        val earnings = listOf(
            DeliveryEarning(id = "1", netEarning = 60.0, status = "pending", createdAt = "2026-10-01T09:00:00+00:00"),
            DeliveryEarning(id = "2", netEarning = 40.0, status = "paid", createdAt = "2026-10-01T00:10:00Z"),
            DeliveryEarning(id = "3", netEarning = 80.0, status = "pending", createdAt = "2026-09-30T23:59:00Z"),
            DeliveryEarning(id = "4", netEarning = 10.0, status = "pending", createdAt = "")
        )
        val summary = todayEarnings(earnings, now, utc)
        assertEquals(100.0, summary.total, 0.001)
        assertEquals(2, summary.deliveries)
        assertEquals(1, summary.pending)
    }
}
