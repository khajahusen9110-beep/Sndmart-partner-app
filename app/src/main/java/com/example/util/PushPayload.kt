package com.example.util

/**
 * The assignment id if an FCM data payload is a new delivery offer, else "".
 *
 * Only a push with a non-empty `assignment_id` (or `assignmentId`) is an offer; status updates,
 * cancellations and other notices carry none and must not ring the new-order alarm. The value is
 * never taken from `order_id`, and an assignment_id equal to order_id (older backend builds
 * filled one with the other) is not treated as an offer.
 */
fun newOfferAssignmentId(data: Map<String, String>): String {
    val assignmentId = data["assignment_id"]?.trim().takeUnless { it.isNullOrEmpty() }
        ?: data["assignmentId"]?.trim().takeUnless { it.isNullOrEmpty() }
        ?: return ""
    val orderId = data["order_id"] ?: data["orderId"] ?: ""
    return if (assignmentId == orderId.trim()) "" else assignmentId
}
