package com.example.util

/**
 * The assignment id if an FCM data payload is a new delivery offer, else "".
 *
 * The push edge function (send-fcm-vendor-native) fills assignment_id with order_id when the
 * notification has no assignment of its own (order status updates, cancellations...). Those
 * must not ring the new-order alarm, so an assignment_id equal to order_id is not an offer.
 */
fun newOfferAssignmentId(data: Map<String, String>): String {
    val assignmentId = data["assignment_id"] ?: data["assignmentId"] ?: return ""
    val orderId = data["order_id"] ?: data["orderId"] ?: ""
    return if (assignmentId.isBlank() || assignmentId == orderId) "" else assignmentId
}
