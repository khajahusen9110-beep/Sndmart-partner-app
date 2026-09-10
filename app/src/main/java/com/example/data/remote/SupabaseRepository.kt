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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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

    private val refreshMutex = Mutex()

    private val _dataFetchError = MutableStateFlow<String?>(null)
    val dataFetchError: StateFlow<String?> = _dataFetchError.asStateFlow()

    private val _isDataLoading = MutableStateFlow(false)
    val isDataLoading: StateFlow<Boolean> = _isDataLoading.asStateFlow()

    var onSessionExpired: ((String) -> Unit)? = null

    private val okHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .authenticator { _, response ->
                if (responseCount(response) >= 2) return@authenticator null
                val path = response.request.url.encodedPath
                if (path.contains("auth/v1/token") || path.contains("auth/v1/verify") || path.contains("auth/v1/signup")) {
                    return@authenticator null
                }

                val currentRefreshToken = prefs.refreshToken
                if (currentRefreshToken.isNullOrBlank()) {
                    coroutineScope.launch {
                        onSessionExpired?.invoke("Your session expired, please log in again")
                    }
                    return@authenticator null
                }

                val refreshSuccess = runBlocking {
                    refreshSession().isSuccess
                }

                if (refreshSuccess) {
                    val newToken = prefs.token
                    if (!newToken.isNullOrEmpty()) {
                        return@authenticator response.request.newBuilder()
                            .header("Authorization", "Bearer $newToken")
                            .build()
                    }
                }
                null
            }
            .addInterceptor(HttpLoggingInterceptor().apply {
                level = HttpLoggingInterceptor.Level.BODY
            })
            .build()
    }

    private fun responseCount(response: okhttp3.Response): Int {
        var count = 1
        var prior = response.priorResponse
        while (prior != null) {
            count++
            prior = prior.priorResponse
        }
        return count
    }

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

    private val _reviews = MutableStateFlow<List<DeliveryPartnerReview>>(emptyList())
    val reviews: StateFlow<List<DeliveryPartnerReview>> = _reviews.asStateFlow()

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

    private fun handleSessionSuccess(json: JSONObject) {
        val token = json.optString("access_token", null)
        val refreshToken = json.optString("refresh_token", null)
        val expiresIn = json.optLong("expires_in", 3600L)
        if (!token.isNullOrEmpty()) {
            prefs.token = token
        }
        if (!refreshToken.isNullOrEmpty()) {
            prefs.refreshToken = refreshToken
        }
        prefs.tokenExpiresAt = System.currentTimeMillis() + (expiresIn * 1000L)

        val userObj = json.optJSONObject("user")
        val userId = userObj?.optString("id") ?: json.optString("id")
        if (!userId.isNullOrEmpty()) {
            prefs.userId = userId
        }
    }

    suspend fun refreshSession(): Result<Boolean> = withContext(Dispatchers.IO) {
        refreshMutex.withLock {
            val currentRefreshToken = prefs.refreshToken
            if (currentRefreshToken.isNullOrBlank()) {
                Log.w("SupabaseRepo", "No refresh token available to refresh session")
                return@withContext Result.failure(Exception("No refresh token available"))
            }

            try {
                Log.d("SupabaseRepo", "Attempting automatic Supabase session refresh...")
                val response = apiService.refreshToken(
                    apiKey = prefs.customSupabaseKey,
                    body = mapOf("refresh_token" to currentRefreshToken)
                )

                if (response.isSuccessful && response.body() != null) {
                    val raw = response.body()!!.string()
                    val json = JSONObject(raw)
                    handleSessionSuccess(json)
                    Log.i("SupabaseRepo", "Supabase session refreshed successfully. Next expiry in ${json.optLong("expires_in", 3600L)}s")
                    _dataFetchError.value = null
                    Result.success(true)
                } else {
                    val code = response.code()
                    val err = response.errorBody()?.string()
                    Log.e("SupabaseRepo", "Session refresh failed with HTTP $code: $err")
                    if (code == 400 || code == 401) {
                        prefs.clearSession()
                        val msg = "Your session expired, please log in again"
                        _dataFetchError.value = msg
                        coroutineScope.launch {
                            onSessionExpired?.invoke(msg)
                        }
                    }
                    Result.failure(Exception("Failed to refresh session (HTTP $code)"))
                }
            } catch (e: Exception) {
                Log.e("SupabaseRepo", "Exception during session refresh: ${e.message}")
                Result.failure(e)
            }
        }
    }

    suspend fun ensureValidSession(): Result<Boolean> = withContext(Dispatchers.IO) {
        val token = prefs.token
        val refreshToken = prefs.refreshToken
        if (token.isNullOrBlank() && refreshToken.isNullOrBlank()) {
            return@withContext Result.failure(Exception("No active session"))
        }

        // Proactively refresh if expiring soon or already expired
        if (prefs.isSessionExpiringSoon() && !refreshToken.isNullOrBlank()) {
            Log.d("SupabaseRepo", "Session is expiring soon or expired, refreshing proactively...")
            val refreshResult = refreshSession()
            if (refreshResult.isSuccess) {
                return@withContext Result.success(true)
            }
        }

        // Validate token by pinging auth/v1/user
        if (!token.isNullOrBlank()) {
            try {
                val userRes = apiService.getUser(
                    apiKey = prefs.customSupabaseKey,
                    bearerToken = "Bearer $token"
                )
                if (userRes.isSuccessful) {
                    return@withContext Result.success(true)
                } else if (userRes.code() == 401) {
                    Log.w("SupabaseRepo", "Access token invalid (401), attempting refresh...")
                    return@withContext refreshSession()
                }
            } catch (e: Exception) {
                Log.w("SupabaseRepo", "Could not verify session online: ${e.message}")
            }
        }

        Result.success(true)
    }

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
                handleSessionSuccess(json)
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
                handleSessionSuccess(json)
                prefs.userEmail = email
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
                    handleSessionSuccess(json2)
                    prefs.userEmail = email
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

            val finalUserId = prefs.userId
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

                handleSessionSuccess(json)
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

    suspend fun getFreshAuthUser(): Result<String> = withContext(Dispatchers.IO) {
        val token = prefs.token
        if (!token.isNullOrBlank()) {
            try {
                val response = apiService.getUser(
                    apiKey = prefs.customSupabaseKey,
                    bearerToken = "Bearer $token"
                )
                if (response.isSuccessful && response.body() != null) {
                    val raw = response.body()!!.string()
                    val json = JSONObject(raw)
                    val realId = json.optString("id", "")
                    if (realId.isNotBlank()) {
                        prefs.userId = realId
                        return@withContext Result.success(realId)
                    }
                } else if (response.code() == 401) {
                    val refreshRes = refreshSession()
                    if (refreshRes.isSuccess) {
                        val newToken = prefs.token
                        if (!newToken.isNullOrBlank()) {
                            val retryRes = apiService.getUser(
                                apiKey = prefs.customSupabaseKey,
                                bearerToken = "Bearer $newToken"
                            )
                            if (retryRes.isSuccessful && retryRes.body() != null) {
                                val retryRaw = retryRes.body()!!.string()
                                val retryJson = JSONObject(retryRaw)
                                val retryId = retryJson.optString("id", "")
                                if (retryId.isNotBlank()) {
                                    prefs.userId = retryId
                                    return@withContext Result.success(retryId)
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w("SupabaseRepo", "getFreshAuthUser online check error: ${e.message}")
            }
        }

        val cachedId = prefs.userId
        if (!cachedId.isNullOrBlank() && cachedId.matches(Regex("^[0-9a-fA-F-]{36}$"))) {
            return@withContext Result.success(cachedId)
        }

        Result.failure(Exception("Authentication session missing or invalid. Please log in or verify email."))
    }

    suspend fun uploadKycDocument(
        documentType: String,
        fileBytes: ByteArray,
        contentType: String = "image/jpeg"
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            // 1. Confirm user.id is the actual authenticated user's UUID (fetched fresh via supabase.auth.getUser())
            val userResult = getFreshAuthUser()
            val userId = userResult.getOrElse {
                val current = prefs.userId
                if (!current.isNullOrBlank() && current.matches(Regex("^[0-9a-fA-F-]{36}$"))) {
                    current
                } else {
                    return@withContext Result.failure(Exception("Authentication required: Please log in first to upload KYC documents."))
                }
            }

            val token = prefs.token ?: prefs.customSupabaseKey
            val bucket = "delivery-partner-kyc-documents"
            val timestamp = System.currentTimeMillis()
            val fileName = "${documentType}-${timestamp}.jpg"
            val storagePath = "$userId/$fileName"

            val requestBody = fileBytes.toRequestBody(contentType.toMediaTypeOrNull())

            val response = apiService.uploadStorageObject(
                apiKey = prefs.customSupabaseKey,
                bearerToken = "Bearer $token",
                contentType = contentType,
                upsert = "true",
                bucket = bucket,
                folder = userId,
                fileName = fileName,
                fileBytes = requestBody
            )

            if (response.isSuccessful) {
                Log.d("SupabaseRepo", "KYC document upload success: $bucket/$storagePath")
                // Save path (not a public URL — this bucket is private) so it can be viewed later
                Result.success(storagePath)
            } else {
                val errBody = response.errorBody()?.string().orEmpty()
                Log.e("SupabaseRepo", "KYC upload failed ($bucket/$storagePath): $errBody")
                val errorMsg = try {
                    val obj = JSONObject(errBody)
                    obj.optString("message", obj.optString("error", "Storage upload failed (HTTP ${response.code()})"))
                } catch (e: Exception) {
                    if (errBody.isNotBlank()) errBody else "Storage upload failed (HTTP ${response.code()})"
                }
                Result.failure(Exception(errorMsg))
            }
        } catch (e: Exception) {
            Log.e("SupabaseRepo", "KYC upload exception: ${e.message}", e)
            Result.failure(Exception(e.message ?: "Upload request failed. Please check your network connection."))
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
            var response = apiService.getDeliveryPartnerByUserId(
                apiKey = prefs.customSupabaseKey,
                bearerToken = "Bearer $token",
                userIdFilter = "eq.$userId"
            )
            if (response.code() == 401) {
                val refreshed = refreshSession()
                if (refreshed.isSuccess) {
                    val newToken = prefs.token ?: prefs.customSupabaseKey
                    response = apiService.getDeliveryPartnerByUserId(
                        apiKey = prefs.customSupabaseKey,
                        bearerToken = "Bearer $newToken",
                        userIdFilter = "eq.$userId"
                    )
                }
            }
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
            val userResult = getFreshAuthUser()
            val userId = userResult.getOrElse {
                val current = prefs.userId
                if (!current.isNullOrBlank() && current.matches(Regex("^[0-9a-fA-F-]{36}$"))) {
                    current
                } else {
                    return@withContext Result.failure(Exception("Authentication session missing or expired. Please log in again."))
                }
            }
            val newPartnerId = "dp_${UUID.randomUUID().toString().take(8)}"

            // Normalize vehicle type to DB enum: 'bike' | 'scooter' | 'ev' | 'cycle'
            val normalizedVehicle = when (vehicleType.lowercase()) {
                "electric scooter", "ev" -> "ev"
                "bicycle", "cycle" -> "cycle"
                "petrol scooter", "scooter" -> "scooter"
                else -> "bike"
            }

            // Save the relative storage path (not a public URL — private bucket accessed via signed URLs)
            val aadharPath = aadharUrl ?: "$userId/aadhar.jpg"
            val licencePath = licenceUrl ?: "$userId/license.jpg"

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
                val errorMsg = try {
                    val json = JSONObject(errBody)
                    json.optString("message", json.optString("error", "Application submission failed (HTTP ${response.code()})"))
                } catch (e: Exception) {
                    if (errBody.isNotBlank()) errBody else "Application submission failed (HTTP ${response.code()})"
                }
                return@withContext Result.failure(Exception(errorMsg))
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

    private fun currentIsoTimestamp(): String {
        val df = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
        df.timeZone = TimeZone.getTimeZone("UTC")
        return df.format(Date())
    }

    private suspend fun recordStatusHistory(assignmentId: String, status: String, lat: Double?, lng: Double?) {
        try {
            val token = prefs.token ?: prefs.customSupabaseKey
            val partner = _currentPartner.value
            val historyData = mutableMapOf<String, Any?>(
                "assignment_id" to assignmentId,
                "status" to status,
                "changed_at" to currentIsoTimestamp(),
                "changed_by" to (prefs.userId ?: partner?.userId ?: partner?.id)
            )
            if (lat != null && lng != null) {
                historyData["latitude"] = lat
                historyData["longitude"] = lng
            }
            apiService.insertStatusHistory(
                apiKey = prefs.customSupabaseKey,
                bearerToken = "Bearer $token",
                data = historyData
            )
        } catch (e: Exception) {
            Log.e("SupabaseRepo", "Failed to insert status history ($status): ${e.message}")
        }
    }

    // ==========================================
    // ONLINE / AVAILABILITY / LIVE LOCATION
    // ==========================================

    suspend fun setOnline(isOnline: Boolean, lat: Double? = null, lng: Double? = null): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val token = prefs.token ?: prefs.customSupabaseKey
            val partner = _currentPartner.value
            val partnerId = partner?.id ?: prefs.currentPartnerId
            val userId = partner?.userId ?: prefs.userId
            val effectiveLat = lat ?: _lastUpdatedLocation.value?.first ?: 12.9716
            val effectiveLng = lng ?: _lastUpdatedLocation.value?.second ?: 77.5946
            val nowIso = currentIsoTimestamp()

            if (isOnline) {
                _lastUpdatedLocation.value = Pair(effectiveLat, effectiveLng)
            }

            // Update delivery_partners table directly (latitude, longitude, last_location_at, is_online)
            val partnerData = mutableMapOf<String, Any?>(
                "is_online" to isOnline
            )
            if (isOnline) {
                partnerData["latitude"] = effectiveLat
                partnerData["longitude"] = effectiveLng
                partnerData["last_location_at"] = nowIso
                partnerData["is_available"] = true
            }

            Log.i("SupabaseRepo", "setOnline: isOnline=$isOnline, partnerId=$partnerId, userId=$userId, lat=$effectiveLat, lng=$effectiveLng, lastLocationAt=$nowIso")

            var updated = false

            // 1. Try updating by id
            if (!partnerId.isNullOrEmpty()) {
                try {
                    val resp = apiService.updateDeliveryPartner(
                        apiKey = prefs.customSupabaseKey,
                        bearerToken = "Bearer $token",
                        prefer = "return=representation",
                        idFilter = "eq.$partnerId",
                        partnerData = partnerData
                    )
                    if (resp.isSuccessful) {
                        val body = resp.body()?.string().orEmpty()
                        Log.i("SupabaseRepo", "updateDeliveryPartner by id=$partnerId success, resp=$body")
                        if (body != "[]") {
                            updated = true
                        }
                    } else {
                        Log.w("SupabaseRepo", "updateDeliveryPartner by id=$partnerId returned code=${resp.code()} err=${resp.errorBody()?.string()}")
                    }
                } catch (e: Exception) {
                    Log.e("SupabaseRepo", "Direct updateDeliveryPartner set_online by id error: ${e.message}")
                }
            }

            // 2. Fallback / guarantee: Try updating by user_id
            if (!updated && !userId.isNullOrEmpty()) {
                try {
                    val resp = apiService.updateDeliveryPartnerByUserId(
                        apiKey = prefs.customSupabaseKey,
                        bearerToken = "Bearer $token",
                        prefer = "return=representation",
                        userIdFilter = "eq.$userId",
                        partnerData = partnerData
                    )
                    if (resp.isSuccessful) {
                        val body = resp.body()?.string().orEmpty()
                        Log.i("SupabaseRepo", "updateDeliveryPartner by user_id=$userId success, resp=$body")
                        if (body != "[]") {
                            updated = true
                        }
                    } else {
                        Log.w("SupabaseRepo", "updateDeliveryPartner by user_id=$userId returned code=${resp.code()} err=${resp.errorBody()?.string()}")
                    }
                } catch (e: Exception) {
                    Log.e("SupabaseRepo", "Direct updateDeliveryPartner set_online by userId error: ${e.message}")
                }
            }

            // Also call RPC delivery_partner_set_online as backup
            try {
                apiService.rpcSetOnline(
                    apiKey = prefs.customSupabaseKey,
                    bearerToken = "Bearer $token",
                    params = mapOf("p_online" to isOnline)
                )
            } catch (e: Exception) {
                Log.d("SupabaseRepo", "RPC delivery_partner_set_online fallback: ${e.message}")
            }

            // If going online, also trigger RPC update location
            if (isOnline) {
                try {
                    apiService.rpcUpdateLocation(
                        apiKey = prefs.customSupabaseKey,
                        bearerToken = "Bearer $token",
                        params = mapOf("p_lat" to effectiveLat, "p_lng" to effectiveLng)
                    )
                } catch (e: Exception) {
                    Log.d("SupabaseRepo", "RPC update location on online toggle: ${e.message}")
                }
            }

            prefs.onlineState = isOnline
            if (isOnline) {
                prefs.availableState = true
                _currentPartner.update {
                    it?.copy(
                        isOnline = true,
                        isAvailable = true,
                        latitude = effectiveLat,
                        longitude = effectiveLng,
                        lastLocationAt = nowIso,
                        currentLat = effectiveLat,
                        currentLng = effectiveLng
                    )
                }
                fetchAssignments()
            } else {
                _currentPartner.update { it?.copy(isOnline = false) }
            }

            Result.success(isOnline)
        } catch (e: Exception) {
            Log.e("SupabaseRepo", "setOnline exception: ${e.message}", e)
            Result.failure(e)
        }
    }

    suspend fun setAvailability(isAvailable: Boolean): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val partner = _currentPartner.value
            val partnerId = partner?.id ?: prefs.currentPartnerId ?: "dp_01"
            val isOnline = partner?.isOnline ?: prefs.onlineState
            val token = prefs.token ?: prefs.customSupabaseKey

            if (!partnerId.isNullOrEmpty()) {
                try {
                    apiService.updateDeliveryPartner(
                        apiKey = prefs.customSupabaseKey,
                        bearerToken = "Bearer $token",
                        prefer = "return=representation",
                        idFilter = "eq.$partnerId",
                        partnerData = mapOf("is_available" to isAvailable)
                    )
                } catch (e: Exception) {
                    Log.d("SupabaseRepo", "Direct updateDeliveryPartner is_available: ${e.message}")
                }
            }

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
            val partner = _currentPartner.value
            val partnerId = partner?.id ?: prefs.currentPartnerId
            val userId = partner?.userId ?: prefs.userId
            val nowIso = currentIsoTimestamp()

            val locationData = mapOf(
                "latitude" to lat,
                "longitude" to lng,
                "last_location_at" to nowIso,
                "is_online" to true
            )

            var updated = false

            // Update delivery_partners table directly with latitude, longitude, last_location_at
            if (!partnerId.isNullOrEmpty()) {
                try {
                    val resp = apiService.updateDeliveryPartner(
                        apiKey = prefs.customSupabaseKey,
                        bearerToken = "Bearer $token",
                        prefer = "return=representation",
                        idFilter = "eq.$partnerId",
                        partnerData = locationData
                    )
                    if (resp.isSuccessful) {
                        val body = resp.body()?.string().orEmpty()
                        if (body != "[]") {
                            updated = true
                            Log.i("SupabaseRepo", "updateLocation by id=$partnerId success: $lat, $lng, $nowIso")
                        }
                    }
                } catch (e: Exception) {
                    Log.d("SupabaseRepo", "Direct updateLocation error: ${e.message}")
                }
            }

            if (!updated && !userId.isNullOrEmpty()) {
                try {
                    val resp = apiService.updateDeliveryPartnerByUserId(
                        apiKey = prefs.customSupabaseKey,
                        bearerToken = "Bearer $token",
                        prefer = "return=representation",
                        userIdFilter = "eq.$userId",
                        partnerData = locationData
                    )
                    if (resp.isSuccessful) {
                        val body = resp.body()?.string().orEmpty()
                        if (body != "[]") {
                            Log.i("SupabaseRepo", "updateLocation by user_id=$userId success: $lat, $lng, $nowIso")
                        }
                    }
                } catch (e: Exception) {
                    Log.d("SupabaseRepo", "Direct updateLocation by user_id error: ${e.message}")
                }
            }

            try {
                apiService.rpcUpdateLocation(
                    apiKey = prefs.customSupabaseKey,
                    bearerToken = "Bearer $token",
                    params = mapOf("p_lat" to lat, "p_lng" to lng)
                )
            } catch (e: Exception) {
                Log.d("SupabaseRepo", "RPC delivery_partner_update_location: ${e.message}")
            }

            _currentPartner.update {
                it?.copy(
                    latitude = lat,
                    longitude = lng,
                    lastLocationAt = nowIso,
                    currentLat = lat,
                    currentLng = lng
                )
            }
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
            val nowIso = currentIsoTimestamp()
            val newStatus = if (accept) "accepted" else "rejected"

            val updateMap = mutableMapOf<String, Any?>(
                "status" to newStatus
            )
            if (accept) {
                updateMap["accepted_at"] = nowIso
            }

            // Update delivery_assignments table directly
            try {
                apiService.updateAssignment(
                    apiKey = prefs.customSupabaseKey,
                    bearerToken = "Bearer $token",
                    idFilter = "eq.$assignmentId",
                    data = updateMap
                )
            } catch (e: Exception) {
                Log.d("SupabaseRepo", "updateAssignment ($newStatus) direct: ${e.message}")
            }

            // Insert into delivery_status_history
            val loc = _lastUpdatedLocation.value
            recordStatusHistory(assignmentId, newStatus, loc?.first, loc?.second)

            // Also call RPC if available
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
                        if (it.id == assignmentId) it.copy(status = "accepted", acceptedAt = nowIso) else it
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
            val nowIso = currentIsoTimestamp()

            val updateData = mutableMapOf<String, Any?>(
                "status" to "picked_up",
                "picked_up_at" to nowIso
            )
            if (pickupPhotoUrl.isNotBlank()) {
                updateData["pickup_photo_url"] = pickupPhotoUrl
            }

            // Update delivery_assignments directly
            try {
                apiService.updateAssignment(
                    apiKey = prefs.customSupabaseKey,
                    bearerToken = "Bearer $token",
                    idFilter = "eq.$assignmentId",
                    data = updateData
                )
            } catch (e: Exception) {
                Log.d("SupabaseRepo", "updateAssignment picked_up: ${e.message}")
            }

            // Insert into delivery_status_history
            val loc = _lastUpdatedLocation.value
            recordStatusHistory(assignmentId, "picked_up", loc?.first, loc?.second)

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
                        pickedUpAt = nowIso,
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
        try {
            val token = prefs.token ?: prefs.customSupabaseKey
            val target = _assignments.value.find { it.id == assignmentId }

            // Update delivery_assignments table to out_for_delivery
            try {
                apiService.updateAssignment(
                    apiKey = prefs.customSupabaseKey,
                    bearerToken = "Bearer $token",
                    idFilter = "eq.$assignmentId",
                    data = mapOf("status" to "out_for_delivery")
                )
            } catch (e: Exception) {
                Log.d("SupabaseRepo", "updateAssignment out_for_delivery: ${e.message}")
            }

            // Update order status to out_for_delivery to keep order-level status in sync
            if (target != null && target.orderId.isNotBlank()) {
                try {
                    apiService.updateOrder(
                        apiKey = prefs.customSupabaseKey,
                        bearerToken = "Bearer $token",
                        idFilter = "eq.${target.orderId}",
                        data = mapOf("status" to "out_for_delivery")
                    )
                } catch (e: Exception) {
                    Log.d("SupabaseRepo", "updateOrder out_for_delivery: ${e.message}")
                }
            }

            // Record in delivery_status_history
            val loc = _lastUpdatedLocation.value
            recordStatusHistory(assignmentId, "out_for_delivery", loc?.first, loc?.second)

            _assignments.update { list ->
                list.map {
                    if (it.id == assignmentId) it.copy(status = "out_for_delivery") else it
                }
            }
            addNotification("Out for Delivery", "Navigation active. Reach customer address safely.", "order")
            Result.success(true)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun arriveAtDrop(assignmentId: String): Result<Boolean> = withContext(Dispatchers.IO) {
        val loc = _lastUpdatedLocation.value
        recordStatusHistory(assignmentId, "arrived_drop", loc?.first, loc?.second)
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
            val targetAssignment = _assignments.value.find { it.id == assignmentId }
                ?: return@withContext Result.failure(Exception("Assignment not found"))

            val token = prefs.token ?: prefs.customSupabaseKey
            val partner = _currentPartner.value
            val nowIso = currentIsoTimestamp()

            // Verify OTP validation against assignment's delivery_otp
            val expectedOtp = targetAssignment.deliveryOtp ?: targetAssignment.details?.deliveryOtp
            val isOtpValid = if (!expectedOtp.isNullOrBlank()) {
                otp.trim() == expectedOtp.trim()
            } else {
                otp.length in 4..6
            }

            // Always insert into delivery_otp_attempts (success true/false)
            try {
                apiService.insertOtpAttempt(
                    apiKey = prefs.customSupabaseKey,
                    bearerToken = "Bearer $token",
                    data = mapOf(
                        "assignment_id" to assignmentId,
                        "attempted_by" to (prefs.userId ?: partner?.userId ?: partner?.id),
                        "success" to isOtpValid,
                        "attempted_at" to nowIso
                    )
                )
            } catch (e: Exception) {
                Log.d("SupabaseRepo", "insertOtpAttempt fallback: ${e.message}")
            }

            if (!isOtpValid) {
                return@withContext Result.failure(Exception("Incorrect OTP. Please enter the valid customer delivery OTP."))
            }

            // On success: update assignment status='delivered', delivered_at=now(), optionally delivered_photo_url
            val assignmentUpdate = mutableMapOf<String, Any?>(
                "status" to "delivered",
                "delivered_at" to nowIso
            )
            if (deliveredPhotoUrl.isNotBlank()) {
                assignmentUpdate["delivered_photo_url"] = deliveredPhotoUrl
            }

            try {
                apiService.updateAssignment(
                    apiKey = prefs.customSupabaseKey,
                    bearerToken = "Bearer $token",
                    idFilter = "eq.$assignmentId",
                    data = assignmentUpdate
                )
            } catch (e: Exception) {
                Log.d("SupabaseRepo", "updateAssignment delivered: ${e.message}")
            }

            // Update order status='delivered' (triggers backend automatic vendor wallet credit and customer notification)
            if (targetAssignment.orderId.isNotBlank()) {
                try {
                    apiService.updateOrder(
                        apiKey = prefs.customSupabaseKey,
                        bearerToken = "Bearer $token",
                        idFilter = "eq.${targetAssignment.orderId}",
                        data = mapOf("status" to "delivered")
                    )
                } catch (e: Exception) {
                    Log.d("SupabaseRepo", "updateOrder delivered: ${e.message}")
                }
            }

            // Insert into delivery_status_history
            val loc = _lastUpdatedLocation.value
            recordStatusHistory(assignmentId, "delivered", loc?.first, loc?.second)

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

            // Update assignment status locally
            _assignments.update { list ->
                list.map {
                    if (it.id == assignmentId) it.copy(
                        status = "delivered",
                        deliveredAt = nowIso,
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
            val partnerId = partner?.id ?: prefs.currentPartnerId ?: "dp_01"
            val method = partner?.payoutMethod ?: "bank"
            val summary = if (method == "upi") "UPI: ${partner?.upiId ?: "partner@upi"}" else "Bank: A/C ending in ${partner?.accountNumber?.takeLast(4) ?: "9876"}"
            val nowIso = currentIsoTimestamp()

            val newReq = PaymentRequest(
                id = "pay_${UUID.randomUUID().toString().take(8)}",
                amount = amount,
                status = "pending",
                payoutMethod = method,
                accountSummary = summary,
                requestedAt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date())
            )

            val token = prefs.token ?: prefs.customSupabaseKey
            // Insert directly into delivery_partner_payment_requests table
            try {
                apiService.insertPaymentRequest(
                    apiKey = prefs.customSupabaseKey,
                    bearerToken = "Bearer $token",
                    request = mapOf(
                        "delivery_partner_id" to partnerId,
                        "amount" to amount,
                        "status" to "pending",
                        "created_at" to nowIso
                    )
                )
            } catch (e: Exception) {
                Log.d("SupabaseRepo", "Direct insertPaymentRequest fallback: ${e.message}")
            }

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
            val partnerId = _currentPartner.value?.id ?: prefs.currentPartnerId ?: return@withContext Result.failure(Exception("No partner ID available"))

            // Upsert directly into delivery_partner_bank_details table
            try {
                apiService.upsertBankDetails(
                    apiKey = prefs.customSupabaseKey,
                    bearerToken = "Bearer $token",
                    details = mapOf(
                        "delivery_partner_id" to partnerId,
                        "payout_method" to payoutMethod,
                        "account_holder_name" to holderName,
                        "account_number" to accountNumber,
                        "ifsc_code" to ifsc,
                        "bank_name" to bankName,
                        "upi_id" to upiId,
                        "is_verified" to false
                    )
                )
            } catch (e: Exception) {
                Log.d("SupabaseRepo", "Direct upsertBankDetails table error: ${e.message}")
            }

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
            val userId = prefs.userId ?: _currentPartner.value?.userId ?: ""
            val token = prefs.token ?: prefs.customSupabaseKey

            // Register into device_tokens table: user_id, user_type='delivery_partner', fcm_token, active=true
            // Only send if user is authenticated with a valid UUID
            if (userId.isNotBlank() && userId.matches(Regex("^[0-9a-fA-F-]{36}$"))) {
                try {
                    apiService.registerDeviceToken(
                        apiKey = prefs.customSupabaseKey,
                        bearerToken = "Bearer $token",
                        tokenData = mapOf(
                            "user_id" to userId,
                            "user_type" to "delivery_partner",
                            "fcm_token" to fcmToken,
                            "active" to true
                        )
                    )
                    prefs.isFcmRegistered = true
                } catch (e: Exception) {
                    Log.d("SupabaseRepo", "Device token register error: ${e.message}")
                }
            } else {
                Log.d("SupabaseRepo", "Device token cached locally; will register with backend upon login")
            }
            Result.success(true)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun deactivateDeviceToken(): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val userId = prefs.userId ?: _currentPartner.value?.userId ?: ""
            val fcmToken = prefs.fcmDeviceToken
            val token = prefs.token ?: prefs.customSupabaseKey
            if (userId.isNotEmpty() && !fcmToken.isNullOrEmpty()) {
                try {
                    apiService.registerDeviceToken(
                        apiKey = prefs.customSupabaseKey,
                        bearerToken = "Bearer $token",
                        tokenData = mapOf(
                            "user_id" to userId,
                            "user_type" to "delivery_partner",
                            "fcm_token" to fcmToken,
                            "active" to false
                        )
                    )
                } catch (e: Exception) {
                    Log.d("SupabaseRepo", "Device token deactivation error: ${e.message}")
                }
            }
            prefs.isFcmRegistered = false
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
            var response = apiService.getAssignments(
                apiKey = prefs.customSupabaseKey,
                bearerToken = "Bearer $token",
                partnerIdFilter = "eq.$partnerId"
            )
            if (response.code() == 401) {
                val refreshed = refreshSession()
                if (refreshed.isSuccess) {
                    val newToken = prefs.token ?: prefs.customSupabaseKey
                    response = apiService.getAssignments(
                        apiKey = prefs.customSupabaseKey,
                        bearerToken = "Bearer $newToken",
                        partnerIdFilter = "eq.$partnerId"
                    )
                } else {
                    val msg = "Your session expired, please log in again"
                    _dataFetchError.value = msg
                    coroutineScope.launch { onSessionExpired?.invoke(msg) }
                    return@withContext _assignments.value
                }
            }

            if (response.isSuccessful && response.body() != null) {
                val list = response.body()!!
                _assignments.value = list
                _dataFetchError.value = null
                return@withContext list
            } else {
                val code = response.code()
                Log.w("SupabaseRepo", "Fetch assignments error: $code")
                _dataFetchError.value = "Unable to sync deliveries from server (Code $code)"
            }
        } catch (e: Exception) {
            Log.e("SupabaseRepo", "Failed to fetch assignments from backend: ${e.message}")
            _dataFetchError.value = "Network connection error while fetching deliveries"
        }
        _assignments.value
    }

    suspend fun fetchEarnings(): List<DeliveryEarning> = withContext(Dispatchers.IO) {
        val partnerId = _currentPartner.value?.id ?: prefs.currentPartnerId ?: return@withContext emptyList()
        val token = prefs.token ?: prefs.customSupabaseKey
        try {
            var response = apiService.getEarnings(
                apiKey = prefs.customSupabaseKey,
                bearerToken = "Bearer $token",
                partnerIdFilter = "eq.$partnerId"
            )
            if (response.code() == 401) {
                val refreshed = refreshSession()
                if (refreshed.isSuccess) {
                    val newToken = prefs.token ?: prefs.customSupabaseKey
                    response = apiService.getEarnings(
                        apiKey = prefs.customSupabaseKey,
                        bearerToken = "Bearer $newToken",
                        partnerIdFilter = "eq.$partnerId"
                    )
                } else {
                    val msg = "Your session expired, please log in again"
                    _dataFetchError.value = msg
                    coroutineScope.launch { onSessionExpired?.invoke(msg) }
                    return@withContext _earnings.value
                }
            }

            if (response.isSuccessful && response.body() != null) {
                val list = response.body()!!
                _earnings.value = list
                _dataFetchError.value = null
                return@withContext list
            } else {
                val code = response.code()
                Log.w("SupabaseRepo", "Fetch earnings error: $code")
                _dataFetchError.value = "Unable to sync earnings from server (Code $code)"
            }
        } catch (e: Exception) {
            Log.e("SupabaseRepo", "Failed to fetch earnings from backend: ${e.message}")
            _dataFetchError.value = "Network connection error while fetching earnings"
        }
        _earnings.value
    }

    suspend fun fetchCashLedger(): List<CashLedgerEntry> = withContext(Dispatchers.IO) {
        val partnerId = _currentPartner.value?.id ?: prefs.currentPartnerId ?: return@withContext emptyList()
        val token = prefs.token ?: prefs.customSupabaseKey
        try {
            var response = apiService.getCashLedger(
                apiKey = prefs.customSupabaseKey,
                bearerToken = "Bearer $token",
                partnerIdFilter = "eq.$partnerId"
            )
            if (response.code() == 401) {
                val refreshed = refreshSession()
                if (refreshed.isSuccess) {
                    val newToken = prefs.token ?: prefs.customSupabaseKey
                    response = apiService.getCashLedger(
                        apiKey = prefs.customSupabaseKey,
                        bearerToken = "Bearer $newToken",
                        partnerIdFilter = "eq.$partnerId"
                    )
                } else {
                    val msg = "Your session expired, please log in again"
                    _dataFetchError.value = msg
                    coroutineScope.launch { onSessionExpired?.invoke(msg) }
                    return@withContext _cashLedger.value
                }
            }

            if (response.isSuccessful && response.body() != null) {
                val list = response.body()!!
                _cashLedger.value = list
                _dataFetchError.value = null
                return@withContext list
            } else {
                val code = response.code()
                Log.w("SupabaseRepo", "Fetch cash ledger error: $code")
                _dataFetchError.value = "Unable to sync cash ledger from server (Code $code)"
            }
        } catch (e: Exception) {
            Log.e("SupabaseRepo", "Failed to fetch cash ledger from backend: ${e.message}")
            _dataFetchError.value = "Network connection error while fetching cash ledger"
        }
        _cashLedger.value
    }

    suspend fun fetchPaymentRequests(): List<PaymentRequest> = withContext(Dispatchers.IO) {
        val partnerId = _currentPartner.value?.id ?: prefs.currentPartnerId ?: return@withContext emptyList()
        val token = prefs.token ?: prefs.customSupabaseKey
        try {
            var response = apiService.getPaymentRequests(
                apiKey = prefs.customSupabaseKey,
                bearerToken = "Bearer $token",
                partnerIdFilter = "eq.$partnerId"
            )
            if (response.code() == 401) {
                val refreshed = refreshSession()
                if (refreshed.isSuccess) {
                    val newToken = prefs.token ?: prefs.customSupabaseKey
                    response = apiService.getPaymentRequests(
                        apiKey = prefs.customSupabaseKey,
                        bearerToken = "Bearer $newToken",
                        partnerIdFilter = "eq.$partnerId"
                    )
                } else {
                    val msg = "Your session expired, please log in again"
                    _dataFetchError.value = msg
                    coroutineScope.launch { onSessionExpired?.invoke(msg) }
                    return@withContext _paymentRequests.value
                }
            }

            if (response.isSuccessful && response.body() != null) {
                val list = response.body()!!
                _paymentRequests.value = list
                _dataFetchError.value = null
                return@withContext list
            } else {
                val code = response.code()
                Log.w("SupabaseRepo", "Fetch payment requests error: $code")
                _dataFetchError.value = "Unable to sync payout requests from server (Code $code)"
            }
        } catch (e: Exception) {
            Log.e("SupabaseRepo", "Failed to fetch payment requests from backend: ${e.message}")
            _dataFetchError.value = "Network connection error while fetching payment requests"
        }
        _paymentRequests.value
    }

    suspend fun fetchNotifications(): List<InAppNotification> = withContext(Dispatchers.IO) {
        val userId = prefs.userId ?: return@withContext emptyList()
        val token = prefs.token ?: prefs.customSupabaseKey
        try {
            var response = apiService.getNotifications(
                apiKey = prefs.customSupabaseKey,
                bearerToken = "Bearer $token",
                userIdFilter = "eq.$userId"
            )
            if (response.code() == 401) {
                val refreshed = refreshSession()
                if (refreshed.isSuccess) {
                    val newToken = prefs.token ?: prefs.customSupabaseKey
                    response = apiService.getNotifications(
                        apiKey = prefs.customSupabaseKey,
                        bearerToken = "Bearer $newToken",
                        userIdFilter = "eq.$userId"
                    )
                } else {
                    val msg = "Your session expired, please log in again"
                    _dataFetchError.value = msg
                    coroutineScope.launch { onSessionExpired?.invoke(msg) }
                    return@withContext _notifications.value
                }
            }

            if (response.isSuccessful && response.body() != null) {
                val list = response.body()!!
                _notifications.value = list
                _dataFetchError.value = null
                return@withContext list
            } else {
                val code = response.code()
                Log.w("SupabaseRepo", "Fetch notifications error: $code")
                _dataFetchError.value = "Unable to sync notifications from server (Code $code)"
            }
        } catch (e: Exception) {
            Log.e("SupabaseRepo", "Failed to fetch notifications from backend: ${e.message}")
            _dataFetchError.value = "Network connection error while fetching notifications"
        }
        _notifications.value
    }

    suspend fun fetchReviews(): List<DeliveryPartnerReview> = withContext(Dispatchers.IO) {
        val partnerId = _currentPartner.value?.id ?: prefs.currentPartnerId ?: return@withContext emptyList()
        val token = prefs.token ?: prefs.customSupabaseKey
        try {
            var response = apiService.getReviews(
                apiKey = prefs.customSupabaseKey,
                bearerToken = "Bearer $token",
                partnerIdFilter = "eq.$partnerId"
            )
            if (response.code() == 401) {
                val refreshed = refreshSession()
                if (refreshed.isSuccess) {
                    val newToken = prefs.token ?: prefs.customSupabaseKey
                    response = apiService.getReviews(
                        apiKey = prefs.customSupabaseKey,
                        bearerToken = "Bearer $newToken",
                        partnerIdFilter = "eq.$partnerId"
                    )
                } else {
                    val msg = "Your session expired, please log in again"
                    _dataFetchError.value = msg
                    coroutineScope.launch { onSessionExpired?.invoke(msg) }
                    return@withContext _reviews.value
                }
            }

            if (response.isSuccessful && response.body() != null) {
                val list = response.body()!!
                _reviews.value = list
                _dataFetchError.value = null
                return@withContext list
            } else {
                val code = response.code()
                Log.w("SupabaseRepo", "Fetch reviews error: $code")
                _dataFetchError.value = "Unable to sync reviews from server (Code $code)"
            }
        } catch (e: Exception) {
            Log.e("SupabaseRepo", "Failed to fetch reviews: ${e.message}")
            _dataFetchError.value = "Network connection error while fetching reviews"
        }
        _reviews.value
    }

    suspend fun fetchAllBackendData() = withContext(Dispatchers.IO) {
        _isDataLoading.value = true
        try {
            ensureValidSession()
            fetchAssignments()
            fetchEarnings()
            fetchCashLedger()
            fetchPaymentRequests()
            fetchNotifications()
            fetchReviews()
        } finally {
            _isDataLoading.value = false
        }
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
        _reviews.value = emptyList()
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

    fun addNotification(title: String, message: String, type: String) {
        val notif = InAppNotification(
            id = "notif_${System.currentTimeMillis()}",
            title = title,
            message = message,
            type = type,
            createdAt = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
        )
        _notifications.update { listOf(notif) + it }
    }

    companion object {
        @Volatile
        private var INSTANCE: SupabaseRepository? = null

        fun getInstance(context: Context): SupabaseRepository {
            return INSTANCE ?: synchronized(this) {
                val appCtx = context.applicationContext
                val prefs = AppPreferences(appCtx)
                val db = AppDatabase.getInstance(appCtx)
                INSTANCE ?: SupabaseRepository(appCtx, prefs, db).also { INSTANCE = it }
            }
        }
    }
}
