package com.example.data.model

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class City(
    val id: String,
    val name: String
)

@JsonClass(generateAdapter = true)
data class DeliveryPartner(
    val id: String,
    @Json(name = "user_id") val userId: String = "",
    val name: String = "",
    val phone: String = "",
    val email: String? = null,
    @Json(name = "city_id") val cityId: String? = null,
    @Json(name = "vehicle_type") val vehicleType: String = "Bike",
    @Json(name = "vehicle_number") val vehicleNumber: String = "",
    @Json(name = "aadhar_document_url") val aadharDocumentUrl: String? = null,
    @Json(name = "driving_licence_url") val drivingLicenceUrl: String? = null,
    @Json(name = "approval_status") val approvalStatus: String = "pending", // "pending", "approved", "rejected"
    @Json(name = "rejection_reason") val rejectionReason: String? = null,
    @Json(name = "is_online") val isOnline: Boolean = false,
    @Json(name = "is_available") val isAvailable: Boolean = true,
    @Json(name = "is_verified") val isVerified: Boolean = false,
    @Json(name = "total_deliveries") val totalDeliveries: Int = 0,
    @Json(name = "avg_rating") val avgRating: Double = 5.0,
    @Json(name = "total_ratings") val totalRatings: Int = 0,
    @Json(name = "latitude") val latitude: Double? = null,
    @Json(name = "longitude") val longitude: Double? = null,
    @Json(name = "last_location_at") val lastLocationAt: String? = null,
    @Json(name = "current_lat") val currentLat: Double? = null,
    @Json(name = "current_lng") val currentLng: Double? = null,
    @Json(name = "payout_method") val payoutMethod: String? = null,
    @Json(name = "account_holder_name") val accountHolderName: String? = null,
    @Json(name = "account_number") val accountNumber: String? = null,
    @Json(name = "ifsc_code") val ifscCode: String? = null,
    @Json(name = "bank_name") val bankName: String? = null,
    @Json(name = "upi_id") val upiId: String? = null,
    @Json(name = "bank_details_verified") val bankDetailsVerified: Boolean = false,
    @Json(name = "created_at") val createdAt: String? = null
)

@JsonClass(generateAdapter = true)
data class DeliveryAssignmentDetails(
    @Json(name = "assignment_id") val assignmentId: String,
    @Json(name = "assignment_status") val assignmentStatus: String = "assigned",
    @Json(name = "expires_at") val expiresAt: String? = null,
    @Json(name = "order_number") val orderNumber: String = "",
    @Json(name = "total_amount") val totalAmount: Double = 0.0,
    @Json(name = "delivery_fee") val deliveryFee: Double = 0.0,
    @Json(name = "payment_method") val paymentMethod: String = "cash",
    val customer: CustomerInfo? = null,
    @Json(name = "drop_address") val dropAddress: DropAddressInfo? = null,
    val pickup: PickupInfo? = null,
    val items: List<OrderItemInfo> = emptyList(),
    @Json(name = "delivery_otp") val deliveryOtp: String? = null
)

@JsonClass(generateAdapter = true)
data class CustomerInfo(
    val name: String = "",
    val phone: String = ""
)

@JsonClass(generateAdapter = true)
data class DropAddressInfo(
    @Json(name = "address_line") val addressLine: String = "",
    val landmark: String? = null,
    @Json(name = "maps_link") val mapsLink: String? = null
)

@JsonClass(generateAdapter = true)
data class PickupInfo(
    val type: String = "vendor", // vendor or warehouse
    val name: String = "",
    val phone: String = "",
    @Json(name = "maps_link") val mapsLink: String? = null
)

@JsonClass(generateAdapter = true)
data class OrderItemInfo(
    @Json(name = "product_name") val productName: String = "",
    val variant: String? = null,
    val quantity: Int = 1,
    @Json(name = "unit_price") val unitPrice: Double = 0.0,
    @Json(name = "total_price") val totalPrice: Double = 0.0,
    @Json(name = "image_url") val imageUrl: String? = null
)

@JsonClass(generateAdapter = true)
data class DeliveryAssignment(
    val id: String,
    @Json(name = "order_id") val orderId: String,
    @Json(name = "delivery_partner_id") val deliveryPartnerId: String? = null,
    val status: String = "assigned", // assigned, accepted, arrived_pickup, picked_up, out_for_delivery, arrived_drop, delivered, rejected, cancelled
    @Json(name = "order_number") val orderNumber: String = "",
    @Json(name = "store_name") val storeName: String = "Sndmart Hub",
    @Json(name = "store_address") val storeAddress: String = "",
    @Json(name = "store_phone") val storePhone: String = "",
    @Json(name = "store_lat") val storeLat: Double = 12.9716,
    @Json(name = "store_lng") val storeLng: Double = 77.5946,
    @Json(name = "customer_name") val customerName: String = "",
    @Json(name = "customer_address") val customerAddress: String = "",
    @Json(name = "customer_phone") val customerPhone: String = "",
    @Json(name = "customer_lat") val customerLat: Double = 12.9816,
    @Json(name = "customer_lng") val customerLng: Double = 77.6046,
    @Json(name = "items_summary") val itemsSummary: String = "",
    @Json(name = "item_count") val itemCount: Int = 1,
    @Json(name = "total_amount") val totalAmount: Double = 0.0,
    @Json(name = "payment_mode") val paymentMode: String = "PREPAID", // COD or PREPAID
    @Json(name = "delivery_fee") val deliveryFee: Double = 45.0,
    @Json(name = "estimated_pickup_distance_km") val estimatedPickupDistanceKm: Double = 1.2,
    @Json(name = "estimated_drop_distance_km") val estimatedDropDistanceKm: Double = 3.5,
    @Json(name = "pickup_photo_url") val pickupPhotoUrl: String? = null,
    @Json(name = "delivered_photo_url") val deliveredPhotoUrl: String? = null,
    @Json(name = "batch_id") val batchId: String? = null,
    @Json(name = "batch_code") val batchCode: String? = null,
    @Json(name = "assigned_at") val assignedAt: String? = null,
    @Json(name = "accepted_at") val acceptedAt: String? = null,
    @Json(name = "picked_up_at") val pickedUpAt: String? = null,
    @Json(name = "delivered_at") val deliveredAt: String? = null,
    @Json(name = "expires_at") val expiresAt: String? = null,
    @Json(name = "estimated_delivery_minutes") val estimatedDeliveryMinutes: Int? = null,
    @Json(name = "delivery_otp") val deliveryOtp: String? = null,
    @Json(name = "created_at") val createdAt: String = "",
    @Json(name = "expires_in_seconds") val expiresInSeconds: Int = 60,
    val details: DeliveryAssignmentDetails? = null
)

@JsonClass(generateAdapter = true)
data class DeliveryBatch(
    val id: String,
    @Json(name = "batch_code") val batchCode: String,
    val status: String = "active",
    @Json(name = "total_orders") val totalOrders: Int = 0,
    @Json(name = "created_at") val createdAt: String = "",
    val assignments: List<DeliveryAssignment> = emptyList()
)

@JsonClass(generateAdapter = true)
data class DeliveryEarning(
    val id: String,
    @Json(name = "delivery_partner_id") val deliveryPartnerId: String = "",
    @Json(name = "order_id") val orderId: String = "",
    @Json(name = "order_number") val orderNumber: String = "",
    @Json(name = "delivery_fee") val deliveryFee: Double = 0.0,
    val incentive: Double = 0.0,
    val penalty: Double = 0.0,
    @Json(name = "net_earning") val netEarning: Double = 0.0,
    val status: String = "approved", // pending, approved, paid
    @Json(name = "created_at") val createdAt: String = ""
)

@JsonClass(generateAdapter = true)
data class CashLedgerEntry(
    val id: String,
    @Json(name = "delivery_partner_id") val deliveryPartnerId: String = "",
    @Json(name = "entry_type") val entryType: String, // "collected", "remitted"
    val amount: Double,
    @Json(name = "order_id") val orderId: String? = null,
    @Json(name = "order_number") val orderNumber: String? = null,
    val notes: String? = null,
    val status: String = "completed", // "pending", "completed" (for remittance requests)
    @Json(name = "created_at") val createdAt: String = ""
)

@JsonClass(generateAdapter = true)
data class DeliveryPartnerBankDetails(
    val id: String? = null,
    @Json(name = "delivery_partner_id") val deliveryPartnerId: String = "",
    @Json(name = "payout_method") val payoutMethod: String = "bank",
    @Json(name = "account_holder_name") val accountHolderName: String? = null,
    @Json(name = "account_number") val accountNumber: String? = null,
    @Json(name = "ifsc_code") val ifscCode: String? = null,
    @Json(name = "bank_name") val bankName: String? = null,
    @Json(name = "upi_id") val upiId: String? = null,
    @Json(name = "is_verified") val isVerified: Boolean = false,
    @Json(name = "created_at") val createdAt: String? = null
)

@JsonClass(generateAdapter = true)
data class PaymentRequest(
    val id: String,
    @Json(name = "delivery_partner_id") val deliveryPartnerId: String = "",
    val amount: Double,
    val status: String = "pending", // pending, processed, rejected
    @Json(name = "payout_method") val payoutMethod: String = "bank",
    @Json(name = "account_summary") val accountSummary: String = "",
    @Json(name = "requested_at") val requestedAt: String = "",
    @Json(name = "processed_at") val processedAt: String? = null
)

@JsonClass(generateAdapter = true)
data class DeliveryPartnerReview(
    val id: String,
    @Json(name = "delivery_partner_id") val deliveryPartnerId: String = "",
    @Json(name = "customer_id") val customerId: String? = null,
    @Json(name = "order_id") val orderId: String? = null,
    val rating: Double = 5.0,
    val comment: String? = null,
    val review: String? = null,
    @Json(name = "customer_name") val customerName: String? = null,
    @Json(name = "created_at") val createdAt: String? = null
)

@JsonClass(generateAdapter = true)
data class InAppNotification(
    val id: String,
    @Json(name = "user_id") val userId: String = "",
    val title: String,
    val message: String,
    val type: String = "info",
    @Json(name = "is_read") val isRead: Boolean = false,
    @Json(name = "created_at") val createdAt: String = ""
)

// Request payloads for RPC and Auth
data class PhoneOtpRequest(
    val phone: String
)

data class VerifyOtpRequest(
    val phone: String,
    val token: String,
    val type: String = "sms"
)

data class SetOnlineRpcParams(
    val p_online: Boolean
)

data class UpdateLocationRpcParams(
    val p_lat: Double,
    val p_lng: Double
)

data class RespondAssignmentRpcParams(
    val p_assignment_id: String,
    val p_accept: Boolean
)

data class GetAssignmentDetailsRpcParams(
    val p_assignment_id: String
)

data class PickupRpcParams(
    val p_assignment_id: String,
    val p_pickup_photo_url: String
)

data class DeliverRpcParams(
    val p_assignment_id: String,
    val p_otp: String,
    val p_delivered_photo_url: String
)

data class UpsertBankDetailsRpcParams(
    val p_payout_method: String,
    val p_account_holder_name: String,
    val p_account_number: String,
    val p_ifsc_code: String,
    val p_bank_name: String,
    val p_upi_id: String? = null
)

data class RequestPaymentRpcParams(
    val p_amount: Double
)

data class RequestCashRemittanceRpcParams(
    val p_amount: Double,
    val p_note: String? = null
)

data class GetCashBalanceRpcParams(
    val p_delivery_partner_id: String
)
