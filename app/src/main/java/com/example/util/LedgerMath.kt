package com.example.util

import com.example.data.model.CashLedgerEntry
import com.example.data.model.DeliveryEarning
import com.example.data.model.PaymentRequest
import java.util.Calendar
import java.util.TimeZone

/** Orders paid in cash at the door. The backend enum value is "cash"; older data used "COD". */
fun isCashOnDelivery(paymentMode: String?): Boolean =
    paymentMode.equals("cash", ignoreCase = true) || paymentMode.equals("cod", ignoreCase = true)

/** Paid online (UPI) before delivery: the partner must not collect any cash. */
fun isPaidOnline(paymentMode: String?, paymentStatus: String?): Boolean =
    !isCashOnDelivery(paymentMode) && paymentStatus.equals("paid", ignoreCase = true)

/** Short payment label for order cards: "Cash on Delivery", "UPI · Paid", "UPI · Not paid". */
fun paymentLabel(paymentMode: String?, paymentStatus: String?): String = when {
    isCashOnDelivery(paymentMode) -> "Cash on Delivery"
    isPaidOnline(paymentMode, paymentStatus) -> "UPI · Paid"
    paymentStatus.isNullOrBlank() -> "UPI / Online"
    else -> "UPI · Not paid"
}

/**
 * Cash the partner currently holds: sum(collected) - sum(remitted that is pending or confirmed).
 * Rejected entries are ignored; pending remittances already count as handed over so the
 * partner is not asked to remit the same cash twice. Same rule as
 * `delivery_partner_request_cash_remittance` on the server.
 */
fun cashInHand(entries: List<CashLedgerEntry>): Double {
    val active = entries.filterNot { it.status.equals("rejected", ignoreCase = true) }
    val collected = active.filter { it.entryType == "collected" }.sumOf { it.amount }
    val remitted = active.filter { it.entryType == "remitted" }.sumOf { it.amount }
    return (collected - remitted).coerceAtLeast(0.0)
}

/** Cash handed over to the hub that is still waiting for City Admin confirmation. */
fun pendingHandover(entries: List<CashLedgerEntry>): Double =
    entries.filter { it.entryType == "remitted" && it.status.equals("pending", ignoreCase = true) }
        .sumOf { it.amount }

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
