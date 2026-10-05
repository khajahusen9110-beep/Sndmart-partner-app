package com.example.data.remote

import com.example.data.model.*
import okhttp3.RequestBody
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query

interface SupabaseApiService {

    // Auth endpoints
    @POST("auth/v1/signup")
    suspend fun signUp(
        @Header("apikey") apiKey: String,
        @Body body: Map<String, String>
    ): Response<ResponseBody>

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

    @POST("auth/v1/token?grant_type=refresh_token")
    suspend fun refreshToken(
        @Header("apikey") apiKey: String,
        @Body body: Map<String, String>
    ): Response<ResponseBody>

    @GET("auth/v1/user")
    suspend fun getUser(
        @Header("apikey") apiKey: String,
        @Header("Authorization") bearerToken: String
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

    /** Passwordless login code for an existing account (create_user=false). */
    @POST("auth/v1/otp")
    suspend fun requestLoginOtp(
        @Header("apikey") apiKey: String,
        @Body body: Map<String, @JvmSuppressWildcards Any>
    ): Response<ResponseBody>

    @PUT("auth/v1/user")
    suspend fun updateUser(
        @Header("apikey") apiKey: String,
        @Header("Authorization") bearerToken: String,
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
        @Query("status") status: String = "eq.active",
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
        @Body partnerData: Map<String, @JvmSuppressWildcards Any?>
    ): Response<ResponseBody>


    @GET("rest/v1/delivery_assignments")
    suspend fun getAssignments(
        @Header("apikey") apiKey: String,
        @Header("Authorization") bearerToken: String,
        @Query("delivery_partner_id") partnerIdFilter: String,
        @Query("select") select: String = "id,order_id,delivery_partner_id,status,assigned_at,accepted_at,picked_up_at,delivered_at,pickup_photo_url,delivered_photo_url,expires_at,batch_id,estimated_delivery_minutes,estimated_delivery_at,orders(id,order_number,total_amount,delivery_fee,payment_method,vendor_id,address_id,city_id,vendors(id,name,latitude,longitude,address,phone),customer_addresses(id,lat,lng,recipient_name,phone,address_line,landmark))",
        @Query("order") order: String = "created_at.desc",
        @Query("limit") limit: Int = 20,
        @Query("offset") offset: Int = 0
    ): Response<List<DeliveryAssignment>>

    @GET("rest/v1/orders")
    suspend fun getOrderById(
        @Header("apikey") apiKey: String,
        @Header("Authorization") bearerToken: String,
        @Query("id") idFilter: String,
        @Query("select") select: String = "id,order_number,vendor_id,address_id,city_id,total_amount,delivery_fee,payment_method,vendors(id,name,latitude,longitude,address,phone),customer_addresses(id,lat,lng,recipient_name,phone,address_line,landmark,city,pincode),cities(id,name,center_lat,center_lng)"
    ): Response<List<OrderDbModel>>

    @GET("rest/v1/orders")
    suspend fun getOrdersByIds(
        @Header("apikey") apiKey: String,
        @Header("Authorization") bearerToken: String,
        @Query("id") inFilter: String,
        @Query("select") select: String = "id,order_number,vendor_id,address_id,city_id,total_amount,delivery_fee,payment_method,vendors(id,name,latitude,longitude,address,phone),customer_addresses(id,lat,lng,recipient_name,phone,address_line,landmark,city,pincode),cities(id,name,center_lat,center_lng)"
    ): Response<List<OrderDbModel>>

    @GET("rest/v1/orders")
    suspend fun getOrderByOrderNumber(
        @Header("apikey") apiKey: String,
        @Header("Authorization") bearerToken: String,
        @Query("order_number") orderNumberFilter: String,
        @Query("select") select: String = "id,order_number,vendor_id,address_id,city_id,total_amount,delivery_fee,payment_method,vendors(id,name,latitude,longitude,address,phone),customer_addresses(id,lat,lng,recipient_name,phone,address_line,landmark,city,pincode),cities(id,name,center_lat,center_lng)"
    ): Response<List<OrderDbModel>>

    @GET("rest/v1/order_items")
    suspend fun getOrderItems(
        @Header("apikey") apiKey: String,
        @Header("Authorization") bearerToken: String,
        @Query("order_id") orderIdFilter: String,
        @Query("select") select: String = "id,order_id,product_name,variant_label,quantity,unit_price,total_price"
    ): Response<List<OrderItem>>

    @GET("rest/v1/customer_addresses")
    suspend fun getCustomerAddressById(
        @Header("apikey") apiKey: String,
        @Header("Authorization") bearerToken: String,
        @Query("id") idFilter: String,
        @Query("select") select: String = "id,user_id,lat,lng,recipient_name,phone,address_line,landmark,city,pincode"
    ): Response<List<CustomerAddressDbModel>>

    @GET("rest/v1/vendors")
    suspend fun getVendorById(
        @Header("apikey") apiKey: String,
        @Header("Authorization") bearerToken: String,
        @Query("id") idFilter: String,
        @Query("select") select: String = "id,name,latitude,longitude,address,phone"
    ): Response<List<VendorDbModel>>

    @GET("rest/v1/vendors")
    suspend fun getVendorsByIds(
        @Header("apikey") apiKey: String,
        @Header("Authorization") bearerToken: String,
        @Query("id") inFilter: String,
        @Query("select") select: String = "id,name,latitude,longitude,address,phone"
    ): Response<List<VendorDbModel>>

    @GET("rest/v1/cities")
    suspend fun getCityById(
        @Header("apikey") apiKey: String,
        @Header("Authorization") bearerToken: String,
        @Query("id") idFilter: String,
        @Query("select") select: String = "id,name,center_lat,center_lng"
    ): Response<List<CityDbModel>>

    @GET("rest/v1/delivery_partner_bank_details")
    suspend fun getBankDetails(
        @Header("apikey") apiKey: String,
        @Header("Authorization") bearerToken: String,
        @Query("delivery_partner_id") partnerIdFilter: String,
        @Query("select") select: String = "delivery_partner_id,payout_method,account_holder_name,account_number,ifsc_code,bank_name,upi_id,is_verified"
    ): Response<List<DeliveryPartnerBankDetails>>

    @GET("rest/v1/delivery_partner_earnings")
    suspend fun getEarnings(
        @Header("apikey") apiKey: String,
        @Header("Authorization") bearerToken: String,
        @Query("delivery_partner_id") partnerIdFilter: String,
        @Query("select") select: String = "id,delivery_partner_id,order_id,delivery_fee,incentive,penalty,net_earning,status,paid_at,created_at",
        @Query("order") order: String = "created_at.desc",
        @Query("limit") limit: Int = 20,
        @Query("offset") offset: Int = 0
    ): Response<List<DeliveryEarning>>

    @GET("rest/v1/delivery_partner_payment_requests")
    suspend fun getPaymentRequests(
        @Header("apikey") apiKey: String,
        @Header("Authorization") bearerToken: String,
        @Query("delivery_partner_id") partnerIdFilter: String,
        @Query("select") select: String = "id,delivery_partner_id,amount,status,note,created_at,paid_at",
        @Query("order") order: String = "created_at.desc",
        @Query("limit") limit: Int = 20,
        @Query("offset") offset: Int = 0
    ): Response<List<PaymentRequest>>

    @PATCH("rest/v1/profiles")
    suspend fun updateProfile(
        @Header("apikey") apiKey: String,
        @Header("Authorization") bearerToken: String,
        @Query("id") idFilter: String,
        @Body body: Map<String, @JvmSuppressWildcards Any?>
    ): Response<ResponseBody>

    @GET("rest/v1/profiles")
    suspend fun getProfile(
        @Header("apikey") apiKey: String,
        @Header("Authorization") bearerToken: String,
        @Query("id") idFilter: String,
        @Query("select") select: String = "*"
    ): Response<ResponseBody>

    @GET("rest/v1/delivery_partner_cash_ledger")
    suspend fun getCashLedger(
        @Header("apikey") apiKey: String,
        @Header("Authorization") bearerToken: String,
        @Query("delivery_partner_id") partnerIdFilter: String,
        @Query("select") select: String = "id,delivery_partner_id,order_id,entry_type,amount,status,note,confirmed_at,created_at,orders(order_number)",
        @Query("order") order: String = "created_at.desc",
        @Query("limit") limit: Int = 20,
        @Query("offset") offset: Int = 0
    ): Response<List<CashLedgerEntry>>

    @GET("rest/v1/delivery_partner_reviews")
    suspend fun getReviews(
        @Header("apikey") apiKey: String,
        @Header("Authorization") bearerToken: String,
        @Query("delivery_partner_id") partnerIdFilter: String,
        @Query("select") select: String = "id,delivery_partner_id,customer_id,order_id,rating,comment,created_at,profiles!delivery_partner_reviews_customer_id_fkey(full_name)",
        @Query("order") order: String = "created_at.desc",
        @Query("limit") limit: Int = 20,
        @Query("offset") offset: Int = 0
    ): Response<List<DeliveryPartnerReview>>

    @GET("rest/v1/notifications")
    suspend fun getNotifications(
        @Header("apikey") apiKey: String,
        @Header("Authorization") bearerToken: String,
        @Query("user_id") userIdFilter: String,
        @Query("select") select: String = "id,user_id,title,body,data,is_read,created_at",
        @Query("order") order: String = "created_at.desc",
        @Query("limit") limit: Int = 20,
        @Query("offset") offset: Int = 0
    ): Response<ResponseBody>

    @PATCH("rest/v1/notifications")
    suspend fun markNotificationRead(
        @Header("apikey") apiKey: String,
        @Header("Authorization") bearerToken: String,
        @Query("id") idFilter: String,
        @Query("user_id") userIdFilter: String,
        @Body body: Map<String, Boolean>
    ): Response<ResponseBody>

    @GET("rest/v1/app_versions")
    suspend fun getAppVersions(
        @Header("apikey") apiKey: String,
        @Header("Authorization") bearerToken: String,
        @Query("platform") platformFilter: String = "eq.delivery_partner_app",
        @Query("select") select: String = "*",
        @Query("limit") limit: Int = 1
    ): Response<ResponseBody>

    @GET("rest/v1/app_settings")
    suspend fun getAppSetting(
        @Header("apikey") apiKey: String,
        @Header("Authorization") bearerToken: String,
        @Query("key") keyFilter: String = "eq.maintenance_mode",
        @Query("select") select: String = "value",
        @Query("limit") limit: Int = 1
    ): Response<ResponseBody>

    // =========================================================================
    // RPC functions (SECURITY DEFINER; they check auth.uid() ownership and state)
    // =========================================================================
    @POST("rest/v1/rpc/delivery_partner_set_online")
    suspend fun rpcSetOnline(
        @Header("apikey") apiKey: String,
        @Header("Authorization") bearerToken: String,
        @Body params: Map<String, Boolean>
    ): Response<ResponseBody>

    /** Availability toggle (busy/available). Authorised for the partner's own row. */
    @POST("rest/v1/rpc/set_delivery_partner_availability")
    suspend fun rpcSetAvailability(
        @Header("apikey") apiKey: String,
        @Header("Authorization") bearerToken: String,
        @Body params: Map<String, @JvmSuppressWildcards Any>
    ): Response<ResponseBody>

    @POST("rest/v1/rpc/delivery_partner_update_location")
    suspend fun rpcUpdateLocation(
        @Header("apikey") apiKey: String,
        @Header("Authorization") bearerToken: String,
        @Body params: Map<String, Double>
    ): Response<ResponseBody>

    @POST("rest/v1/rpc/delivery_partner_respond_assignment")
    suspend fun rpcRespondAssignment(
        @Header("apikey") apiKey: String,
        @Header("Authorization") bearerToken: String,
        @Body params: Map<String, @JvmSuppressWildcards Any>
    ): Response<ResponseBody>

    @POST("rest/v1/rpc/delivery_partner_mark_picked_up")
    suspend fun rpcPickup(
        @Header("apikey") apiKey: String,
        @Header("Authorization") bearerToken: String,
        @Body params: Map<String, String>
    ): Response<ResponseBody>

    @POST("rest/v1/rpc/delivery_partner_complete_delivery")
    suspend fun rpcDeliver(
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
        @Body params: Map<String, @JvmSuppressWildcards Any?>
    ): Response<ResponseBody>

    // Storage Upload
    @POST("storage/v1/object/{bucket}/{folder}/{fileName}")
    suspend fun uploadStorageObject(
        @Header("apikey") apiKey: String,
        @Header("Authorization") bearerToken: String,
        @Header("Content-Type") contentType: String = "image/jpeg",
        @Header("x-upsert") upsert: String = "true",
        @Path("bucket") bucket: String,
        @Path("folder") folder: String,
        @Path("fileName") fileName: String,
        @Body fileBytes: RequestBody
    ): Response<ResponseBody>

    // Device token registration (UPSERT on conflict user_id, user_type)
    @POST("rest/v1/device_tokens?on_conflict=user_id,user_type")
    suspend fun registerDeviceToken(
        @Header("apikey") apiKey: String,
        @Header("Authorization") bearerToken: String,
        @Header("Prefer") prefer: String = "resolution=merge-duplicates",
        @Body tokenData: Map<String, @JvmSuppressWildcards Any?>
    ): Response<ResponseBody>

    @GET("rest/v1/device_tokens")
    suspend fun getDeviceToken(
        @Header("apikey") apiKey: String,
        @Header("Authorization") bearerToken: String,
        @Query("user_id") userIdFilter: String,
        @Query("user_type") userTypeFilter: String = "eq.delivery_partner",
        @Query("select") select: String = "user_id,user_type,fcm_token,active,firebase_project"
    ): Response<ResponseBody>

    @PATCH("rest/v1/device_tokens")
    suspend fun updateDeviceToken(
        @Header("apikey") apiKey: String,
        @Header("Authorization") bearerToken: String,
        @Query("user_id") userIdFilter: String,
        @Query("user_type") userTypeFilter: String = "eq.delivery_partner",
        @Body tokenData: Map<String, @JvmSuppressWildcards Any?>
    ): Response<ResponseBody>

}
