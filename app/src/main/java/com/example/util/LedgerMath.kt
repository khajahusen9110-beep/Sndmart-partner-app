package com.example.util

import com.example.data.model.CashLedgerEntry
import com.example.data.model.DeliveryEarning
import com.example.data.model.PaymentRequest
import java.util.Calendar
import java.util.TimeZone

/** Orders paid in cash at the door. The backend enum value is "cash"; older data used "COD". */
fun isCashOnDelivery(paymentMode: String?): Boolean =
    paymentMode.equals("cash", ignoreCase = true) || paymentMode.equals("cod", ignoreCase = true)

/**
 * Cash the partner currently holds: collected cash minus remittances handed over.
 * Rejected entries are ignored; pending remittances already count as handed over so the
 * partner is not asked to remit the same cash twice.
 */
fun cashInHand(entries: List<CashLedgerEntry>): Double {
    val active = entries.filterNot { it.status.equals("rejected", ignoreCase = true) }
    val collected = active.filter { it.entryType == "collected" }.sumOf { it.amount }
    val remitted = active.filter { it.entryType == "remitted" }.sumOf { it.amount }
    return collected - remitted
}

/**
 * Mirrors the server rule in `delivery_partner_request_payment`: unpaid ("pending") earnings
 * minus payout requests still pending. The server re-checks this on every request.
 */
fun availableForPayout(earnings: List<DeliveryEarning>, requests: List<PaymentRequest>): Double {
    val unpaid = earnings.filter { it.status == "pending" }.sumOf { it.netEarning }
    val requested = requests.filter { it.status == "pending" }.sumOf { it.amount }
    return (unpaid - requested).coerceAtLeast(0.0)
}

data class EarningsSummary(val total: Double, val deliveries: Int, val pending: Int)

/** Earnings created on the device's current calendar day. */
fun todayEarnings(
    earnings: List<DeliveryEarning>,
    nowMillis: Long = System.currentTimeMillis(),
    timeZone: TimeZone = TimeZone.getDefault()
): EarningsSummary {
    val today = dayKey(nowMillis, timeZone)
    val todays = earnings.filter { earning ->
        parseIsoMillis(earning.createdAt)?.let { dayKey(it, timeZone) == today } ?: false
    }
    return EarningsSummary(
        total = todays.sumOf { it.netEarning },
        deliveries = todays.size,
        pending = todays.count { it.status == "pending" }
    )
}

private fun dayKey(millis: Long, timeZone: TimeZone): Int {
    val cal = Calendar.getInstance(timeZone).apply { timeInMillis = millis }
    return cal.get(Calendar.YEAR) * 1000 + cal.get(Calendar.DAY_OF_YEAR)
}
