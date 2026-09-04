package com.example.data.remote

import com.example.data.model.*
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Multipart
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Part
import retrofit2.http.Path
import retrofit2.http.Query

interface SupabaseApiService {

    // Auth endpoints
    @POST("auth/v1/signup")
    suspend fun signUpWithEmail(
        @Header("apikey") apiKey: String,
        @Body body: Map<String, String>
    ): Response<ResponseBody>

    @POST("auth/v1/token?grant_type=password")
    suspend fun signInWithPassword(
        @Header("apikey") apiKey: String,
        @Body body: Map<String, String>
    ): Response<ResponseBody>

    @POST("auth/v1/verify")
    suspend fun verifyOtp(
        @Header("apikey") apiKey: String,
        @Body body: Map<String, String>
    ): Response<ResponseBody>

    @POST("auth/v1/resend")
    suspend fun resendOtp(
        @Header("apikey") apiKey: String,
        @Body body: Map<String, String>
    ): Response<ResponseBody>

    @POST("auth/v1/otp")
    suspend fun requestOtp(
        @Header("apikey") apiKey: String,
        @Body body: Map<String, String>
    ): Response<ResponseBody>

    @POST("auth/v1/recover")
    suspend fun recoverPassword(
        @Header("apikey") apiKey: String,
        @Body body: Map<String, String>
    ): Response<ResponseBody>

    // PostgREST tables
    @GET("rest/v1/cities")
    suspend fun getCities(
        @Header("apikey") apiKey: String,
        @Header("Authorization") bearerToken: String,
        @Query("select") select: String = "id,name",
        @Query("order") order: String = "name.asc"
    ): Response<List<City>>

    @GET("rest/v1/delivery_partners")
    suspend fun getDeliveryPartnerByUserId(
        @Header("apikey") apiKey: String,
        @Header("Authorization") bearerToken: String,
        @Query("user_id") userIdFilter: String,
        @Query("select") select: String = "*"
    ): Response<List<DeliveryPartner>>

    @GET("rest/v1/delivery_partners")
    suspend fun getDeliveryPartnerById(
        @Header("apikey") apiKey: String,
        @Header("Authorization") bearerToken: String,
        @Query("id") idFilter: String,
        @Query("select") select: String = "*"
    ): Response<List<DeliveryPartner>>

    @POST("rest/v1/delivery_partners")
    suspend fun createDeliveryPartner(
        @Header("apikey") apiKey: String,
        @Header("Authorization") bearerToken: String,
        @Header("Prefer") prefer: String = "return=representation",
        @Body partnerData: Map<String, Any?>
    ): Response<List<DeliveryPartner>>

    @PATCH("rest/v1/delivery_partners")
    suspend fun updateDeliveryPartner(
        @Header("apikey") apiKey: String,
        @Header("Authorization") bearerToken: String,
        @Query("user_id") userIdFilter: String,
        @Body partnerData: Map<String, Any?>
    ): Response<ResponseBody>

    @GET("rest/v1/delivery_assignments")
    suspend fun getAssignments(
        @Header("apikey") apiKey: String,
        @Header("Authorization") bearerToken: String,
        @Query("delivery_partner_id") partnerIdFilter: String,
        @Query("select") select: String = "*",
        @Query("order") order: String = "created_at.desc"
    ): Response<List<DeliveryAssignment>>

    @GET("rest/v1/delivery_partner_bank_details")
    suspend fun getBankDetails(
        @Header("apikey") apiKey: String,
        @Header("Authorization") bearerToken: String,
        @Query("delivery_partner_id") partnerIdFilter: String,
        @Query("select") select: String = "*"
    ): Response<List<DeliveryPartnerBankDetails>>

    @GET("rest/v1/delivery_partner_earnings")
    suspend fun getEarnings(
        @Header("apikey") apiKey: String,
        @Header("Authorization") bearerToken: String,
        @Query("delivery_partner_id") partnerIdFilter: String,
        @Query("select") select: String = "*",
        @Query("order") order: String = "created_at.desc"
    ): Response<List<DeliveryEarning>>

    @GET("rest/v1/delivery_partner_payment_requests")
    suspend fun getPaymentRequests(
        @Header("apikey") apiKey: String,
        @Header("Authorization") bearerToken: String,
        @Query("delivery_partner_id") partnerIdFilter: String,
        @Query("select") select: String = "*",
        @Query("order") order: String = "requested_at.desc"
    ): Response<List<PaymentRequest>>

    @POST("rest/v1/delivery_partner_cash_ledger")
    suspend fun insertCashLedger(
        @Header("apikey") apiKey: String,
        @Header("Authorization") bearerToken: String,
        @Body entry: Map<String, Any?>
    ): Response<ResponseBody>

    @GET("rest/v1/delivery_partner_cash_ledger")
    suspend fun getCashLedger(
        @Header("apikey") apiKey: String,
        @Header("Authorization") bearerToken: String,
        @Query("delivery_partner_id") partnerIdFilter: String,
        @Query("order") order: String = "created_at.desc"
    ): Response<List<CashLedgerEntry>>

    @GET("rest/v1/delivery_partner_reviews")
    suspend fun getReviews(
        @Header("apikey") apiKey: String,
        @Header("Authorization") bearerToken: String,
        @Query("delivery_partner_id") partnerIdFilter: String,
        @Query("select") select: String = "*",
        @Query("order") order: String = "created_at.desc"
    ): Response<List<DeliveryPartnerReview>>

    @GET("rest/v1/notifications")
    suspend fun getNotifications(
        @Header("apikey") apiKey: String,
        @Header("Authorization") bearerToken: String,
        @Query("user_id") userIdFilter: String,
        @Query("select") select: String = "*",
        @Query("order") order: String = "created_at.desc"
    ): Response<List<InAppNotification>>

    @PATCH("rest/v1/notifications")
    suspend fun markNotificationRead(
        @Header("apikey") apiKey: String,
        @Header("Authorization") bearerToken: String,
        @Query("id") idFilter: String,
        @Query("user_id") userIdFilter: String,
        @Body body: Map<String, Boolean>
    ): Response<ResponseBody>

    // =========================================================================
    // RPC Functions (Section 5 Spec - SECURITY DEFINER)
    // =========================================================================
    @POST("rest/v1/rpc/delivery_partner_set_online")
    suspend fun rpcSetOnline(
        @Header("apikey") apiKey: String,
        @Header("Authorization") bearerToken: String,
        @Body params: Map<String, Boolean>
    ): Response<ResponseBody>

    @POST("rest/v1/rpc/delivery_partner_set_availability")
    suspend fun rpcSetAvailability(
        @Header("apikey") apiKey: String,
        @Header("Authorization") bearerToken: String,
        @Body params: Map<String, Any>
    ): Response<ResponseBody>

    @POST("rest/v1/rpc/delivery_partner_update_location")
    suspend fun rpcUpdateLocation(
        @Header("apikey") apiKey: String,
        @Header("Authorization") bearerToken: String,
        @Body params: Map<String, Double>
    ): Response<ResponseBody>

    @POST("rest/v1/rpc/get_delivery_assignment_details")
    suspend fun rpcGetAssignmentDetails(
        @Header("apikey") apiKey: String,
        @Header("Authorization") bearerToken: String,
        @Body params: Map<String, String>
    ): Response<ResponseBody>

    @POST("rest/v1/rpc/delivery_partner_respond_assignment")
    suspend fun rpcRespondAssignment(
        @Header("apikey") apiKey: String,
        @Header("Authorization") bearerToken: String,
        @Body params: Map<String, Any>
    ): Response<ResponseBody>

    @POST("rest/v1/rpc/delivery_partner_pickup")
    suspend fun rpcPickup(
        @Header("apikey") apiKey: String,
        @Header("Authorization") bearerToken: String,
        @Body params: Map<String, String>
    ): Response<ResponseBody>

    // Retain alias for compatibility if needed
    @POST("rest/v1/rpc/delivery_partner_pickup")
    suspend fun rpcMarkPickedUp(
        @Header("apikey") apiKey: String,
        @Header("Authorization") bearerToken: String,
        @Body params: Map<String, String>
    ): Response<ResponseBody>

    @POST("rest/v1/rpc/delivery_partner_deliver")
    suspend fun rpcDeliver(
        @Header("apikey") apiKey: String,
        @Header("Authorization") bearerToken: String,
        @Body params: Map<String, String>
    ): Response<ResponseBody>

    // Retain alias for compatibility if needed
    @POST("rest/v1/rpc/delivery_partner_deliver")
    suspend fun rpcCompleteDelivery(
        @Header("apikey") apiKey: String,
        @Header("Authorization") bearerToken: String,
        @Body params: Map<String, String>
    ): Response<ResponseBody>

    @POST("rest/v1/rpc/delivery_partner_upsert_bank_details")
    suspend fun rpcUpsertBankDetails(
        @Header("apikey") apiKey: String,
        @Header("Authorization") bearerToken: String,
        @Body params: Map<String, String?>
    ): Response<ResponseBody>

    @POST("rest/v1/rpc/delivery_partner_request_payment")
    suspend fun rpcRequestPayment(
        @Header("apikey") apiKey: String,
        @Header("Authorization") bearerToken: String,
        @Body params: Map<String, Double>
    ): Response<ResponseBody>

    @POST("rest/v1/rpc/delivery_partner_request_cash_remittance")
    suspend fun rpcRequestCashRemittance(
        @Header("apikey") apiKey: String,
        @Header("Authorization") bearerToken: String,
        @Body params: Map<String, Any?>
    ): Response<ResponseBody>

    @POST("rest/v1/rpc/get_delivery_partner_cash_balance")
    suspend fun rpcGetCashBalance(
        @Header("apikey") apiKey: String,
        @Header("Authorization") bearerToken: String,
        @Body params: Map<String, String>
    ): Response<ResponseBody>

    // Storage Upload
    @POST("storage/v1/object/{bucket}/{path}")
    suspend fun uploadStorageObject(
        @Header("apikey") apiKey: String,
        @Header("Authorization") bearerToken: String,
        @Header("Content-Type") contentType: String = "image/jpeg",
        @Path(value = "bucket", encoded = true) bucket: String,
        @Path(value = "path", encoded = true) path: String,
        @Body fileBytes: RequestBody
    ): Response<ResponseBody>

    // Device token registration (UPSERT on conflict user_id, user_type)
    @POST("rest/v1/device_tokens?on_conflict=user_id,user_type")
    suspend fun registerDeviceToken(
        @Header("apikey") apiKey: String,
        @Header("Authorization") bearerToken: String,
        @Header("Prefer") prefer: String = "resolution=merge-duplicates",
        @Body tokenData: Map<String, Any?>
    ): Response<ResponseBody>
}
