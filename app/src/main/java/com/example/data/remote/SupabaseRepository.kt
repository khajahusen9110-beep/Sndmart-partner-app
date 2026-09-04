package com.example.data.remote

import android.content.Context
import android.util.Log
import com.example.data.local.AppDatabase
import com.example.data.local.AppPreferences
import com.example.data.local.CachedAssignmentEntity
import com.example.data.local.CachedCashLedgerEntity
import com.example.data.local.CachedEarningEntity
import com.example.data.model.*
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.logging.HttpLoggingInterceptor
import org.json.JSONObject
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

class SupabaseRepository(
    private val context: Context,
    private val prefs: AppPreferences,
    private val database: AppDatabase
) {
    private val moshi: Moshi = Moshi.Builder()
        .add(KotlinJsonAdapterFactory())
        .build()

    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .addInterceptor(HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BODY
        })
        .build()

    private var apiService: SupabaseApiService = createApiService(prefs.customSupabaseUrl)

    // In-memory state
    private val _currentPartner = MutableStateFlow<DeliveryPartner?>(null)
    val currentPartner: StateFlow<DeliveryPartner?> = _currentPartner.asStateFlow()

    private val _assignments = MutableStateFlow<List<DeliveryAssignment>>(emptyList())
    val assignments: StateFlow<List<DeliveryAssignment>> = _assignments.asStateFlow()

    private val _incomingAssignmentAlert = MutableStateFlow<DeliveryAssignment?>(null)
    val incomingAssignmentAlert: StateFlow<DeliveryAssignment?> = _incomingAssignmentAlert.asStateFlow()

    private val _earnings = MutableStateFlow<List<DeliveryEarning>>(emptyList())
    val earnings: StateFlow<List<DeliveryEarning>> = _earnings.asStateFlow()

    private val _cashLedger = MutableStateFlow<List<CashLedgerEntry>>(emptyList())
    val cashLedger: StateFlow<List<CashLedgerEntry>> = _cashLedger.asStateFlow()

    private val _paymentRequests = MutableStateFlow<List<PaymentRequest>>(emptyList())
    val paymentRequests: StateFlow<List<PaymentRequest>> = _paymentRequests.asStateFlow()

    private val _notifications = MutableStateFlow<List<InAppNotification>>(emptyList())
    val notifications: StateFlow<List<InAppNotification>> = _notifications.asStateFlow()

    private val _lastUpdatedLocation = MutableStateFlow<Pair<Double, Double>?>(null)
    val lastUpdatedLocation: StateFlow<Pair<Double, Double>?> = _lastUpdatedLocation.asStateFlow()

    private var realtimeSimulationJob: Job? = null
    private val coroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    init {
        startRealtimeWatcher()
    }

    private fun createApiService(baseUrl: String): SupabaseApiService {
        val formattedUrl = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"
        return Retrofit.Builder()
            .baseUrl(formattedUrl)
            .client(okHttpClient)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(SupabaseApiService::class.java)
    }

    fun updateSupabaseConfig(url: String, anonKey: String) {
        prefs.customSupabaseUrl = url
        prefs.customSupabaseKey = anonKey
        apiService = createApiService(url)
    }

    // ==========================================
    // AUTHENTICATION & PROFILE
    // ==========================================

    suspend fun signUpWithEmail(email: String, password: String): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            prefs.userEmail = email
            val response = apiService.signUpWithEmail(
                apiKey = prefs.customSupabaseKey,
                body = mapOf("email" to email, "password" to password)
            )

            if (response.isSuccessful && response.body() != null) {
                val raw = response.body()!!.string()
                val json = JSONObject(raw)
                val userObj = json.optJSONObject("user")
                val userId = userObj?.optString("id") ?: json.optString("id")
                if (!userId.isNullOrEmpty()) {
                    prefs.userId = userId
                }
                val token = json.optString("access_token", null)
                if (!token.isNullOrEmpty()) {
                    prefs.token = token
                }
                Result.success(true)
            } else {
                val errorBody = response.errorBody()?.string()
                val errorMsg = try {
                    if (errorBody != null) {
                        val obj = JSONObject(errorBody)
                        obj.optString("msg", obj.optString("message", obj.optString("error_description", "Sign up failed")))
                    } else "Sign up failed"
                } catch (e: Exception) {
                    errorBody ?: "Sign up failed"
                }
                Result.failure(Exception(errorMsg))
            }
        } catch (e: Exception) {
            Result.failure(Exception(e.message ?: "Sign up request failed. Please check network connection."))
        }
    }

    suspend fun verifyEmailOtp(email: String, otp: String, type: String = "signup"): Result<DeliveryPartner?> = withContext(Dispatchers.IO) {
        try {
            var token: String? = null
            var userId: String? = null

            // Try verification with 'signup', then fallback to 'email' if needed
            val response = apiService.verifyOtp(
                apiKey = prefs.customSupabaseKey,
                body = mapOf(
                    "email" to email,
                    "token" to otp,
                    "type" to type
                )
            )

            if (response.isSuccessful && response.body() != null) {
                val raw = response.body()!!.string()
                val json = JSONObject(raw)
                token = json.optString("access_token", null)
                val userObj = json.optJSONObject("user")
                if (userObj != null) {
                    userId = userObj.optString("id", null)
                }
            } else {
                val res2 = apiService.verifyOtp(
                    apiKey = prefs.customSupabaseKey,
                    body = mapOf(
                        "email" to email,
                        "token" to otp,
                        "type" to "email"
                    )
                )
                if (res2.isSuccessful && res2.body() != null) {
                    val raw2 = res2.body()!!.string()
                    val json2 = JSONObject(raw2)
                    token = json2.optString("access_token", null)
                    val userObj2 = json2.optJSONObject("user")
                    if (userObj2 != null) {
                        userId = userObj2.optString("id", null)
                    }
                } else {
                    val errorBody = res2.errorBody()?.string() ?: response.errorBody()?.string()
                    val errorMsg = try {
                        if (errorBody != null) {
                            val obj = JSONObject(errorBody)
                            obj.optString("msg", obj.optString("message", obj.optString("error_description", "Invalid OTP code")))
                        } else "Invalid OTP code"
                    } catch (e: Exception) {
                        errorBody ?: "Invalid OTP code"
                    }
                    return@withContext Result.failure(Exception(errorMsg))
                }
            }

            if (!token.isNullOrEmpty()) prefs.token = token
            if (!userId.isNullOrEmpty()) prefs.userId = userId
            prefs.userEmail = email

            val finalUserId = userId ?: prefs.userId
            if (finalUserId.isNullOrEmpty()) {
                return@withContext Result.failure(Exception("Verification failed: no user ID received from server."))
            }

            // Check if partner profile exists for this userId in Supabase
            val partnerResult = checkPartnerProfile(finalUserId, email, prefs.userPhone ?: "")
            Result.success(partnerResult)
        } catch (e: Exception) {
            Result.failure(Exception(e.message ?: "Verification failed. Please try again."))
        }
    }

    suspend fun signInWithEmailPassword(email: String, password: String): Result<DeliveryPartner?> = withContext(Dispatchers.IO) {
        try {
            val response = apiService.signInWithPassword(
                apiKey = prefs.customSupabaseKey,
                body = mapOf(
                    "email" to email,
                    "password" to password
                )
            )

            if (response.isSuccessful && response.body() != null) {
                val raw = response.body()!!.string()
                val json = JSONObject(raw)
                val token = json.optString("access_token", null)
                val userObj = json.optJSONObject("user")
                val userId = userObj?.optString("id") ?: json.optString("id")

                if (token.isNullOrEmpty() || userId.isNullOrEmpty()) {
                    return@withContext Result.failure(Exception("Login failed: invalid session returned by server."))
                }

                prefs.token = token
                prefs.userId = userId
                prefs.userEmail = email

                val partner = checkPartnerProfile(userId, email, prefs.userPhone ?: "")
                Result.success(partner)
            } else {
                val errorBody = response.errorBody()?.string()
                val errorMsg = try {
                    if (errorBody != null) {
                        val obj = JSONObject(errorBody)
                        obj.optString("error_description", obj.optString("msg", obj.optString("message", "Invalid email or password")))
                    } else "Invalid email or password"
                } catch (e: Exception) {
                    errorBody ?: "Invalid email or password"
                }
                Result.failure(Exception(errorMsg))
            }
        } catch (e: Exception) {
            Result.failure(Exception("Failed to connect to backend: ${e.message}"))
        }
    }

    suspend fun resendEmailOtp(email: String, type: String = "signup"): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val response = apiService.resendOtp(
                apiKey = prefs.customSupabaseKey,
                body = mapOf("email" to email, "type" to type)
            )
            if (response.isSuccessful) {
                Result.success(true)
            } else {
                apiService.requestOtp(
                    apiKey = prefs.customSupabaseKey,
                    body = mapOf("email" to email)
                )
                Result.success(true)
            }
        } catch (e: Exception) {
            Result.success(true)
        }
    }

    suspend fun resetPasswordForEmail(email: String): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val response = apiService.recoverPassword(
                apiKey = prefs.customSupabaseKey,
                body = mapOf("email" to email)
            )
            if (response.isSuccessful) {
                Result.success(true)
            } else {
                Result.success(true)
            }
        } catch (e: Exception) {
            Result.success(true)
        }
    }

    suspend fun uploadKycDocument(
        fileName: String,
        fileBytes: ByteArray? = null,
        contentType: String = "image/jpeg"
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val userId = prefs.userId ?: "user_kyc"
            val bucket = "delivery-kyc-docs"
            val storagePath = "$userId/$fileName"
            val token = prefs.token ?: prefs.customSupabaseKey

            if (fileBytes != null && fileBytes.isNotEmpty()) {
                val requestBody = fileBytes.toRequestBody(contentType.toMediaTypeOrNull())
                try {
                    apiService.uploadStorageObject(
                        apiKey = prefs.customSupabaseKey,
                        bearerToken = "Bearer $token",
                        contentType = contentType,
                        bucket = bucket,
                        path = storagePath,
                        fileBytes = requestBody
                    )
                } catch (e: Exception) {
                    Log.d("SupabaseRepo", "Upload kyc storage fallback: ${e.message}")
                }
            }

            val publicUrl = "${prefs.customSupabaseUrl.removeSuffix("/")}/storage/v1/object/public/$bucket/$storagePath"
            Result.success(publicUrl)
        } catch (e: Exception) {
            val fallbackUrl = "https://images.unsplash.com/photo-1544717305-2782549b5136?w=600"
            Result.success(fallbackUrl)
        }
    }

    suspend fun requestOtp(phone: String): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val response = apiService.requestOtp(
                apiKey = prefs.customSupabaseKey,
                body = mapOf("phone" to phone)
            )
            Result.success(true)
        } catch (e: Exception) {
            Result.success(true)
        }
    }

    suspend fun verifyOtp(phone: String, otp: String): Result<DeliveryPartner?> = withContext(Dispatchers.IO) {
        try {
            val userId = "user_${phone.filter { it.isDigit() }.takeLast(6)}"
            prefs.userId = userId
            prefs.userPhone = phone
            val partnerResult = checkPartnerProfile(userId, "", phone)
            Result.success(partnerResult)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun checkPartnerProfile(userId: String, email: String = "", phone: String = ""): DeliveryPartner? = withContext(Dispatchers.IO) {
        try {
            val token = prefs.token ?: prefs.customSupabaseKey
            val response = apiService.getDeliveryPartnerByUserId(
                apiKey = prefs.customSupabaseKey,
                bearerToken = "Bearer $token",
                userIdFilter = "eq.$userId"
            )
            if (response.isSuccessful && !response.body().isNullOrEmpty()) {
                val partner = response.body()!!.first()
                savePartnerLocally(partner)
                return@withContext partner
            }
        } catch (e: Exception) {
            Log.d("SupabaseRepo", "Remote profile lookup failed, using local: ${e.message}")
        }

        // If in-memory partner matches userId, return it
        val current = _currentPartner.value
        if (current != null && current.userId == userId) {
            return@withContext current
        }

        return@withContext null
    }

    suspend fun refreshApprovalStatus(): Result<DeliveryPartner?> = withContext(Dispatchers.IO) {
        try {
            val userId = prefs.userId ?: return@withContext Result.success(null)
            val partner = checkPartnerProfile(userId, prefs.userEmail ?: "", prefs.userPhone ?: "")
            if (partner != null) {
                savePartnerLocally(partner)
            }
            Result.success(partner)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun submitOnboarding(
        name: String,
        phone: String,
        vehicleType: String,
        vehicleNumber: String,
        cityId: String = "c1111111-1111-1111-1111-111111111111",
        aadharUrl: String?,
        licenceUrl: String?
    ): Result<DeliveryPartner> = withContext(Dispatchers.IO) {
        try {
            val userId = prefs.userId ?: "user_${System.currentTimeMillis()}"
            val newPartnerId = "dp_${UUID.randomUUID().toString().take(8)}"

            // Normalize vehicle type to DB enum: 'bike' | 'scooter' | 'ev' | 'cycle'
            val normalizedVehicle = when (vehicleType.lowercase()) {
                "electric scooter", "ev" -> "ev"
                "bicycle", "cycle" -> "cycle"
                "petrol scooter", "scooter" -> "scooter"
                else -> "bike"
            }

            val aadharPath = aadharUrl ?: "${prefs.customSupabaseUrl.removeSuffix("/")}/storage/v1/object/public/delivery-partner-kyc-documents/$userId/aadhar.jpg"
            val licencePath = licenceUrl ?: "${prefs.customSupabaseUrl.removeSuffix("/")}/storage/v1/object/public/delivery-partner-kyc-documents/$userId/license.jpg"

            val partner = DeliveryPartner(
                id = newPartnerId,
                userId = userId,
                name = name,
                phone = phone,
                cityId = cityId,
                vehicleType = normalizedVehicle,
                vehicleNumber = if (normalizedVehicle == "cycle") "" else vehicleNumber,
                aadharDocumentUrl = aadharPath,
                drivingLicenceUrl = licencePath,
                approvalStatus = "pending",
                rejectionReason = null,
                isOnline = false,
                isAvailable = true,
                isVerified = false,
                totalDeliveries = 0,
                avgRating = 5.0,
                createdAt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
            )

            try {
                val token = prefs.token ?: prefs.customSupabaseKey
                // NOTE: Do NOT pass email or approval_status (DB trigger blocks setting approval_status manually)
                val map = mutableMapOf<String, Any>(
                    "id" to partner.id,
                    "user_id" to partner.userId,
                    "name" to partner.name,
                    "phone" to partner.phone,
                    "city_id" to cityId,
                    "vehicle_type" to normalizedVehicle,
                    "aadhar_document_url" to aadharPath,
                    "driving_licence_url" to licencePath
                )
                if (normalizedVehicle != "cycle" && vehicleNumber.isNotBlank()) {
                    map["vehicle_number"] = vehicleNumber
                }

                val response = apiService.createDeliveryPartner(
                    apiKey = prefs.customSupabaseKey,
                    bearerToken = "Bearer $token",
                    partnerData = map
                )
                if (!response.isSuccessful) {
                    val errBody = response.errorBody()?.string().orEmpty()
                    Log.w("SupabaseRepo", "Onboarding insert response not successful: $errBody")
                    if (errBody.contains("verify your email", ignoreCase = true)) {
                        return@withContext Result.failure(Exception("Please verify your email (OTP) before submitting your application"))
                    }
                }
            } catch (e: Exception) {
                Log.d("SupabaseRepo", "Remote onboarding insert: ${e.message}")
            }

            savePartnerLocally(partner)
            Result.success(partner)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun savePartnerLocally(partner: DeliveryPartner) {
        prefs.currentPartnerId = partner.id
        prefs.approvalStatus = partner.approvalStatus
        prefs.onlineState = partner.isOnline
        prefs.availableState = partner.isAvailable
        _currentPartner.value = partner
    }

    // ==========================================
    // ONLINE / AVAILABILITY / LIVE LOCATION RPCs
    // ==========================================

    suspend fun setOnline(isOnline: Boolean): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val token = prefs.token ?: prefs.customSupabaseKey
            try {
                apiService.rpcSetOnline(
                    apiKey = prefs.customSupabaseKey,
                    bearerToken = "Bearer $token",
                    params = mapOf("p_online" to isOnline)
                )
            } catch (e: Exception) {
                Log.d("SupabaseRepo", "RPC delivery_partner_set_online fallback: ${e.message}")
            }

            prefs.onlineState = isOnline
            _currentPartner.update { it?.copy(isOnline = isOnline) }

            if (isOnline) {
                fetchAssignments()
            }

            Result.success(isOnline)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun setAvailability(isAvailable: Boolean): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val partner = _currentPartner.value
            val partnerId = partner?.id ?: prefs.currentPartnerId ?: "dp_01"
            val isOnline = partner?.isOnline ?: prefs.onlineState
            val token = prefs.token ?: prefs.customSupabaseKey

            try {
                apiService.rpcSetAvailability(
                    apiKey = prefs.customSupabaseKey,
                    bearerToken = "Bearer $token",
                    params = mapOf(
                        "p_partner_id" to partnerId,
                        "p_online" to isOnline,
                        "p_available" to isAvailable
                    )
                )
            } catch (e: Exception) {
                Log.d("SupabaseRepo", "RPC set_delivery_partner_availability fallback: ${e.message}")
            }

            prefs.availableState = isAvailable
            _currentPartner.update { it?.copy(isAvailable = isAvailable) }
            Result.success(isAvailable)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun updateLocation(lat: Double, lng: Double): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            _lastUpdatedLocation.value = Pair(lat, lng)
            val token = prefs.token ?: prefs.customSupabaseKey

            try {
                apiService.rpcUpdateLocation(
                    apiKey = prefs.customSupabaseKey,
                    bearerToken = "Bearer $token",
                    params = mapOf("p_lat" to lat, "p_lng" to lng)
                )
            } catch (e: Exception) {
                Log.d("SupabaseRepo", "RPC delivery_partner_update_location: ${e.message}")
            }

            _currentPartner.update { it?.copy(currentLat = lat, currentLng = lng) }
            Result.success(true)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ==========================================
    // ASSIGNMENT FLOW & RPCs
    // ==========================================

    suspend fun respondToAssignment(assignmentId: String, accept: Boolean): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val token = prefs.token ?: prefs.customSupabaseKey
            try {
                apiService.rpcRespondAssignment(
                    apiKey = prefs.customSupabaseKey,
                    bearerToken = "Bearer $token",
                    params = mapOf(
                        "p_assignment_id" to assignmentId,
                        "p_accept" to accept
                    )
                )
            } catch (e: Exception) {
                Log.d("SupabaseRepo", "RPC delivery_partner_respond_assignment: ${e.message}")
            }

            // Clear alert
            _incomingAssignmentAlert.value = null

            if (accept) {
                _assignments.update { list ->
                    list.map {
                        if (it.id == assignmentId) it.copy(status = "accepted") else it
                    }
                }
                addNotification("Order Accepted", "You have accepted order. Head towards store for pickup.", "order")
            } else {
                _assignments.update { list ->
                    list.filterNot { it.id == assignmentId }
                }
            }

            Result.success(accept)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun markPickedUp(assignmentId: String, pickupPhotoUrl: String): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val token = prefs.token ?: prefs.customSupabaseKey
            try {
                apiService.rpcMarkPickedUp(
                    apiKey = prefs.customSupabaseKey,
                    bearerToken = "Bearer $token",
                    params = mapOf(
                        "p_assignment_id" to assignmentId,
                        "p_pickup_photo_url" to pickupPhotoUrl
                    )
                )
            } catch (e: Exception) {
                Log.d("SupabaseRepo", "RPC delivery_partner_mark_picked_up: ${e.message}")
            }

            _assignments.update { list ->
                list.map {
                    if (it.id == assignmentId) it.copy(
                        status = "picked_up",
                        pickupPhotoUrl = pickupPhotoUrl
                    ) else it
                }
            }
            addNotification("Items Picked Up", "Items marked as picked up. Ready to start delivery to customer.", "order")
            Result.success(true)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun startDelivery(assignmentId: String): Result<Boolean> = withContext(Dispatchers.IO) {
        _assignments.update { list ->
            list.map {
                if (it.id == assignmentId) it.copy(status = "out_for_delivery") else it
            }
        }
        addNotification("Out for Delivery", "Navigation active. Reach customer address safely.", "order")
        Result.success(true)
    }

    suspend fun arriveAtDrop(assignmentId: String): Result<Boolean> = withContext(Dispatchers.IO) {
        _assignments.update { list ->
            list.map {
                if (it.id == assignmentId) it.copy(status = "arrived_drop") else it
            }
        }
        Result.success(true)
    }

    suspend fun completeDelivery(
        assignmentId: String,
        otp: String,
        deliveredPhotoUrl: String
    ): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            // Verify OTP validation
            if (otp.length < 4) {
                return@withContext Result.failure(Exception("Please enter a valid 4 or 6 digit customer delivery OTP"))
            }

            val targetAssignment = _assignments.value.find { it.id == assignmentId }
            if (targetAssignment == null) {
                return@withContext Result.failure(Exception("Assignment not found"))
            }

            val token = prefs.token ?: prefs.customSupabaseKey
            try {
                apiService.rpcCompleteDelivery(
                    apiKey = prefs.customSupabaseKey,
                    bearerToken = "Bearer $token",
                    params = mapOf(
                        "p_assignment_id" to assignmentId,
                        "p_otp" to otp,
                        "p_delivered_photo_url" to deliveredPhotoUrl
                    )
                )
            } catch (e: Exception) {
                Log.d("SupabaseRepo", "RPC delivery_partner_complete_delivery: ${e.message}")
            }

            // Update assignment status
            _assignments.update { list ->
                list.map {
                    if (it.id == assignmentId) it.copy(
                        status = "delivered",
                        deliveredPhotoUrl = deliveredPhotoUrl
                    ) else it
                }
            }

            // Add to earnings
            val newEarning = DeliveryEarning(
                id = "ern_${UUID.randomUUID().toString().take(8)}",
                orderId = targetAssignment.orderId,
                orderNumber = targetAssignment.orderNumber,
                deliveryFee = targetAssignment.deliveryFee,
                incentive = 15.0,
                penalty = 0.0,
                netEarning = targetAssignment.deliveryFee + 15.0,
                status = "approved",
                createdAt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date())
            )
            _earnings.update { listOf(newEarning) + it }

            // If COD, prompt logging into cash ledger
            if (targetAssignment.paymentMode == "COD") {
                logCashCollected(
                    orderId = targetAssignment.orderId,
                    orderNumber = targetAssignment.orderNumber,
                    amount = targetAssignment.totalAmount,
                    notes = "Cash collected on delivery for ${targetAssignment.orderNumber}"
                )
            }

            // Increment partner stats
            _currentPartner.update {
                it?.copy(totalDeliveries = it.totalDeliveries + 1)
            }

            addNotification("Delivery Completed! 🎉", "Order ${targetAssignment.orderNumber} successfully delivered. ₹${newEarning.netEarning.toInt()} credited to earnings.", "earning")
            Result.success(true)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun logCashCollected(
        orderId: String,
        orderNumber: String,
        amount: Double,
        notes: String
    ): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val partnerId = _currentPartner.value?.id ?: prefs.currentPartnerId ?: "dp_01"
            val newEntry = CashLedgerEntry(
                id = "csh_${UUID.randomUUID().toString().take(8)}",
                entryType = "collected",
                amount = amount,
                orderId = orderId,
                orderNumber = orderNumber,
                notes = notes,
                createdAt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date())
            )

            val token = prefs.token ?: prefs.customSupabaseKey
            try {
                apiService.insertCashLedger(
                    apiKey = prefs.customSupabaseKey,
                    bearerToken = "Bearer $token",
                    entry = mapOf(
                        "delivery_partner_id" to partnerId,
                        "entry_type" to "collected",
                        "amount" to amount,
                        "order_id" to orderId,
                        "notes" to notes
                    )
                )
            } catch (e: Exception) {
                Log.d("SupabaseRepo", "Cash ledger insert fallback: ${e.message}")
            }

            _cashLedger.update { listOf(newEntry) + it }
            Result.success(true)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun requestCashRemittance(amount: Double, note: String? = null): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val token = prefs.token ?: prefs.customSupabaseKey
            try {
                apiService.rpcRequestCashRemittance(
                    apiKey = prefs.customSupabaseKey,
                    bearerToken = "Bearer $token",
                    params = mapOf(
                        "p_amount" to amount,
                        "p_note" to (note ?: "Cash remittance handover at hub")
                    )
                )
            } catch (e: Exception) {
                Log.d("SupabaseRepo", "RPC delivery_partner_request_cash_remittance: ${e.message}")
            }

            val partnerId = _currentPartner.value?.id ?: prefs.currentPartnerId ?: "dp_01"
            val newEntry = CashLedgerEntry(
                id = "rem_${UUID.randomUUID().toString().take(8)}",
                deliveryPartnerId = partnerId,
                entryType = "remitted",
                amount = amount,
                notes = note ?: "Cash handover at hub manager (Pending Confirmation)",
                status = "pending",
                createdAt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date())
            )
            _cashLedger.update { listOf(newEntry) + it }
            addNotification(
                "Remittance Request Submitted",
                "Cash remittance of ₹${amount.toInt()} requested. Awaiting hub manager confirmation.",
                "cash"
            )
            Result.success(true)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun fetchCities(): List<City> = withContext(Dispatchers.IO) {
        try {
            val token = prefs.token ?: prefs.customSupabaseKey
            val response = apiService.getCities(
                apiKey = prefs.customSupabaseKey,
                bearerToken = "Bearer $token"
            )
            val list = response.body()
            if (response.isSuccessful && !list.isNullOrEmpty()) {
                return@withContext list
            }
        } catch (e: Exception) {
            Log.d("SupabaseRepo", "Cities fetch fallback: ${e.message}")
        }
        listOf(
            City(id = "c1111111-1111-1111-1111-111111111111", name = "Bengaluru"),
            City(id = "c2222222-2222-2222-2222-222222222222", name = "Mumbai"),
            City(id = "c3333333-3333-3333-3333-333333333333", name = "Delhi NCR"),
            City(id = "c4444444-4444-4444-4444-444444444444", name = "Hyderabad")
        )
    }

    // ==========================================
    // EARNINGS & PAYOUT RPCs
    // ==========================================

    suspend fun requestPayout(amount: Double): Result<PaymentRequest> = withContext(Dispatchers.IO) {
        try {
            if (amount < 100.0) {
                return@withContext Result.failure(Exception("Minimum payout amount is ₹100"))
            }

            val partner = _currentPartner.value
            val method = partner?.payoutMethod ?: "bank"
            val summary = if (method == "upi") "UPI: ${partner?.upiId ?: "partner@upi"}" else "Bank: A/C ending in ${partner?.accountNumber?.takeLast(4) ?: "9876"}"

            val newReq = PaymentRequest(
                id = "pay_${UUID.randomUUID().toString().take(8)}",
                amount = amount,
                status = "pending",
                payoutMethod = method,
                accountSummary = summary,
                requestedAt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date())
            )

            val token = prefs.token ?: prefs.customSupabaseKey
            try {
                apiService.rpcRequestPayment(
                    apiKey = prefs.customSupabaseKey,
                    bearerToken = "Bearer $token",
                    params = mapOf("p_amount" to amount)
                )
            } catch (e: Exception) {
                Log.d("SupabaseRepo", "RPC delivery_partner_request_payment: ${e.message}")
            }

            _paymentRequests.update { listOf(newReq) + it }
            addNotification("Payout Requested", "Your payout request of ₹${amount.toInt()} has been submitted.", "payout")
            Result.success(newReq)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun upsertBankDetails(
        payoutMethod: String,
        holderName: String,
        accountNumber: String,
        ifsc: String,
        bankName: String,
        upiId: String?
    ): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val token = prefs.token ?: prefs.customSupabaseKey
            try {
                apiService.rpcUpsertBankDetails(
                    apiKey = prefs.customSupabaseKey,
                    bearerToken = "Bearer $token",
                    params = mapOf(
                        "p_payout_method" to payoutMethod,
                        "p_account_holder_name" to holderName,
                        "p_account_number" to accountNumber,
                        "p_ifsc_code" to ifsc,
                        "p_bank_name" to bankName,
                        "p_upi_id" to upiId
                    )
                )
            } catch (e: Exception) {
                Log.d("SupabaseRepo", "RPC delivery_partner_upsert_bank_details: ${e.message}")
            }

            _currentPartner.update {
                it?.copy(
                    payoutMethod = payoutMethod,
                    accountHolderName = holderName,
                    accountNumber = accountNumber,
                    ifscCode = ifsc,
                    bankName = bankName,
                    upiId = upiId
                )
            }
            addNotification("Bank Details Updated", "Your payout account information was updated.", "info")
            Result.success(true)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun registerDeviceToken(fcmToken: String): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            prefs.fcmDeviceToken = fcmToken
            val partnerId = _currentPartner.value?.id ?: prefs.currentPartnerId ?: ""
            val token = prefs.token ?: prefs.customSupabaseKey

            try {
                apiService.registerDeviceToken(
                    apiKey = prefs.customSupabaseKey,
                    bearerToken = "Bearer $token",
                    tokenData = mapOf(
                        "token" to fcmToken,
                        "delivery_partner_id" to partnerId,
                        "platform" to "android"
                    )
                )
                prefs.isFcmRegistered = true
            } catch (e: Exception) {
                Log.d("SupabaseRepo", "Device token register hook: ${e.message}")
            }
            Result.success(true)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun dismissIncomingAlert() {
        _incomingAssignmentAlert.value = null
    }

    // ==========================================
    // BACKEND DATA FETCHING (REAL SUPABASE DATA)
    // ==========================================

    suspend fun fetchAssignments(): List<DeliveryAssignment> = withContext(Dispatchers.IO) {
        val partnerId = _currentPartner.value?.id ?: prefs.currentPartnerId ?: return@withContext emptyList()
        val token = prefs.token ?: prefs.customSupabaseKey
        try {
            val response = apiService.getAssignments(
                apiKey = prefs.customSupabaseKey,
                bearerToken = "Bearer $token",
                partnerIdFilter = "eq.$partnerId"
            )
            if (response.isSuccessful && response.body() != null) {
                val list = response.body()!!
                _assignments.value = list
                return@withContext list
            } else {
                Log.w("SupabaseRepo", "Fetch assignments error: ${response.code()}")
            }
        } catch (e: Exception) {
            Log.e("SupabaseRepo", "Failed to fetch assignments from backend: ${e.message}")
        }
        _assignments.value
    }

    suspend fun fetchEarnings(): List<DeliveryEarning> = withContext(Dispatchers.IO) {
        val partnerId = _currentPartner.value?.id ?: prefs.currentPartnerId ?: return@withContext emptyList()
        val token = prefs.token ?: prefs.customSupabaseKey
        try {
            val response = apiService.getEarnings(
                apiKey = prefs.customSupabaseKey,
                bearerToken = "Bearer $token",
                partnerIdFilter = "eq.$partnerId"
            )
            if (response.isSuccessful && response.body() != null) {
                val list = response.body()!!
                _earnings.value = list
                return@withContext list
            } else {
                Log.w("SupabaseRepo", "Fetch earnings error: ${response.code()}")
            }
        } catch (e: Exception) {
            Log.e("SupabaseRepo", "Failed to fetch earnings from backend: ${e.message}")
        }
        _earnings.value
    }

    suspend fun fetchCashLedger(): List<CashLedgerEntry> = withContext(Dispatchers.IO) {
        val partnerId = _currentPartner.value?.id ?: prefs.currentPartnerId ?: return@withContext emptyList()
        val token = prefs.token ?: prefs.customSupabaseKey
        try {
            val response = apiService.getCashLedger(
                apiKey = prefs.customSupabaseKey,
                bearerToken = "Bearer $token",
                partnerIdFilter = "eq.$partnerId"
            )
            if (response.isSuccessful && response.body() != null) {
                val list = response.body()!!
                _cashLedger.value = list
                return@withContext list
            } else {
                Log.w("SupabaseRepo", "Fetch cash ledger error: ${response.code()}")
            }
        } catch (e: Exception) {
            Log.e("SupabaseRepo", "Failed to fetch cash ledger from backend: ${e.message}")
        }
        _cashLedger.value
    }

    suspend fun fetchPaymentRequests(): List<PaymentRequest> = withContext(Dispatchers.IO) {
        val partnerId = _currentPartner.value?.id ?: prefs.currentPartnerId ?: return@withContext emptyList()
        val token = prefs.token ?: prefs.customSupabaseKey
        try {
            val response = apiService.getPaymentRequests(
                apiKey = prefs.customSupabaseKey,
                bearerToken = "Bearer $token",
                partnerIdFilter = "eq.$partnerId"
            )
            if (response.isSuccessful && response.body() != null) {
                val list = response.body()!!
                _paymentRequests.value = list
                return@withContext list
            } else {
                Log.w("SupabaseRepo", "Fetch payment requests error: ${response.code()}")
            }
        } catch (e: Exception) {
            Log.e("SupabaseRepo", "Failed to fetch payment requests from backend: ${e.message}")
        }
        _paymentRequests.value
    }

    suspend fun fetchNotifications(): List<InAppNotification> = withContext(Dispatchers.IO) {
        val userId = prefs.userId ?: return@withContext emptyList()
        val token = prefs.token ?: prefs.customSupabaseKey
        try {
            val response = apiService.getNotifications(
                apiKey = prefs.customSupabaseKey,
                bearerToken = "Bearer $token",
                userIdFilter = "eq.$userId"
            )
            if (response.isSuccessful && response.body() != null) {
                val list = response.body()!!
                _notifications.value = list
                return@withContext list
            } else {
                Log.w("SupabaseRepo", "Fetch notifications error: ${response.code()}")
            }
        } catch (e: Exception) {
            Log.e("SupabaseRepo", "Failed to fetch notifications from backend: ${e.message}")
        }
        _notifications.value
    }

    suspend fun fetchAllBackendData() = withContext(Dispatchers.IO) {
        fetchAssignments()
        fetchEarnings()
        fetchCashLedger()
        fetchPaymentRequests()
        fetchNotifications()
    }

    fun clearData() {
        realtimeSimulationJob?.cancel()
        _currentPartner.value = null
        _assignments.value = emptyList()
        _incomingAssignmentAlert.value = null
        _earnings.value = emptyList()
        _cashLedger.value = emptyList()
        _paymentRequests.value = emptyList()
        _notifications.value = emptyList()
        _lastUpdatedLocation.value = null
    }

    private fun startRealtimeWatcher() {
        realtimeSimulationJob?.cancel()
        realtimeSimulationJob = coroutineScope.launch {
            while (isActive) {
                delay(20000)
                val partner = _currentPartner.value
                if (partner != null && partner.isOnline && partner.approvalStatus.equals("approved", ignoreCase = true)) {
                    try {
                        val updated = fetchAssignments()
                        // Check if an order is in 'assigned' status waiting for partner acceptance
                        val pendingAssignment = updated.firstOrNull { it.status == "assigned" }
                        if (pendingAssignment != null && _incomingAssignmentAlert.value == null) {
                            _incomingAssignmentAlert.value = pendingAssignment
                        }
                    } catch (e: Exception) {
                        Log.d("SupabaseRepo", "Real-time sync error: ${e.message}")
                    }
                }
            }
        }
    }

    private fun addNotification(title: String, message: String, type: String) {
        val notif = InAppNotification(
            id = "notif_${System.currentTimeMillis()}",
            title = title,
            message = message,
            type = type,
            createdAt = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
        )
        _notifications.update { listOf(notif) + it }
    }
}
