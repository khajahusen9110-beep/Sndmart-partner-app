package com.example.util

import android.app.Activity
import android.util.Log
import com.example.data.model.CashPayOrder
import com.razorpay.Checkout
import org.json.JSONObject

/** Opens Razorpay Checkout (UPI only) for the partner's COD cash settlement. The amount and order come from the server. */
object CashPaymentCheckout {
    fun open(activity: Activity, order: CashPayOrder, phone: String?): Result<Unit> = try {
        val keyId = order.keyId.orEmpty()
        val checkout = Checkout()
        checkout.setKeyID(keyId)
        val options = JSONObject().apply {
            put("key", keyId)
            put("amount", order.amount)
            put("currency", order.currency?.ifBlank { null } ?: "INR")
            put("order_id", order.razorpayOrderId)
            put("name", "Sndmart")
            put("description", "COD cash settlement")
            put("method", JSONObject().put("upi", true).put("card", false).put("netbanking", false).put("wallet", false).put("paylater", false))
            put("modal", JSONObject().put("ondismiss", true))
            val digits = phone.orEmpty().filter { it.isDigit() }.takeLast(10)
            if (digits.length == 10) {
                put("prefill", JSONObject().put("contact", "+91$digits").put("email", "$digits@sndmart.in"))
                put("readonly", JSONObject().put("contact", true).put("email", true))
            }
        }
        checkout.open(activity, options)
        Result.success(Unit)
    } catch (e: Exception) {
        Log.e("CashPaymentCheckout", "Could not open Razorpay", e)
        Result.failure(e)
    }
}
