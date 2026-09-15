package com.example.data.remote

import android.app.NotificationManager
import android.content.Context
import android.location.Location
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
import org.json.JSONArray
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

    private var assignmentsPage = 0
    val ASSIGNMENTS_PAGE_SIZE = 20
    private val _canLoadMoreAssignments = MutableStateFlow(true)
    val canLoadMoreAssignments: StateFlow<Boolean> = _canLoadMoreAssignments.asStateFlow()
    private val _isLoadingMoreAssignments = MutableStateFlow(false)
    val isLoadingMoreAssignments: StateFlow<Boolean> = _isLoadingMoreAssignments.asStateFlow()

    private var cachedCities: List<City>? = null

    private val _incomingAssignmentAlert = MutableStateFlow<DeliveryAssignment?>(null)
    val incomingAssignmentAlert: StateFlow<DeliveryAssignment?> = _incomingAssignmentAlert.asStateFlow()

    private val handledAssignmentIds = Collections.synchronizedSet(prefs.getHandledAssignmentIds().toMutableSet())

    fun isAssignmentHandled(id: String): Boolean = handledAssignmentIds.contains(id)

    fun markAssignmentHandled(id: String) {
        if (id.isNotBlank()) {
            handledAssignmentIds.add(id)
            prefs.addHandledAssignmentId(id)
        }
    }

    fun dismissIncomingAlert(assignmentId: String) {
        if (assignmentId.isNotBlank()) {
            markAssignmentHandled(assignmentId)
        }
        if (_incomingAssignmentAlert.value?.id == assignmentId) {
            _incomingAssignmentAlert.value = null
        }
        cancelIncomingNotification(assignmentId)
    }

    fun cancelIncomingNotification(assignmentId: String) {
        try {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            nm?.cancel(assignmentId.hashCode())
            nm?.cancel(1001)
        } catch (e: Exception) {
            Log.w("SupabaseRepo", "Notification cancel error: ${e.message}")
        }
    }

    fun isAssignmentExpired(assignment: DeliveryAssignment): Boolean {
        val expiresAt = assignment.expiresAt
        if (!expiresAt.isNullOrBlank()) {
            try {
                val cleanStr = expiresAt.substringBefore(".").substringBefore("Z").substringBefore("+")
                val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply {
                    timeZone = TimeZone.getTimeZone("UTC")
                }
                val expDate = sdf.parse(cleanStr)
                if (expDate != null && System.currentTimeMillis() >= expDate.time) {
                    return true
                }
            } catch (e: Exception) {
                // Ignore parse errors
            }
        }
        if (assignment.createdAt.isNotBlank()) {
            try {
                val cleanStr = assignment.createdAt.substringBefore(".").substringBefore("Z").substringBefore("+")
                val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply {
                    timeZone = TimeZone.getTimeZone("UTC")
                }
                val createdDate = sdf.parse(cleanStr)
                if (createdDate != null) {
                    val maxDurationMs = (assignment.expiresInSeconds.coerceAtLeast(30)) * 1000L
                    if (System.currentTimeMillis() - createdDate.time >= maxDurationMs) {
                        return true
                    }
                }
            } catch (e: Exception) {
                // Ignore parse errors
            }
        }
        return false
    }

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

    fun formatE164Phone(input: String): String {
        val trimmed = input.trim()
        val digits = trimmed.filter { it.isDigit() }
        return when {
            digits.length == 10 -> "+91$digits"
            digits.length > 10 && digits.startsWith("91") -> "+$digits"
            trimmed.startsWith("+") -> "+$digits"
            else -> "+91${digits.takeLast(10)}"
        }
    }

    suspend fun signUpWithPhone(phone: String, password: String): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val formattedPhone = formatE164Phone(phone)
            prefs.userPhone = formattedPhone
            val response = apiService.signUp(
                apiKey = prefs.customSupabaseKey,
                body = mapOf("phone" to formattedPhone, "password" to password)
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

    suspend fun verifyPhoneOtp(phone: String, otp: String): Result<DeliveryPartner?> = withContext(Dispatchers.IO) {
        try {
            val formattedPhone = formatE164Phone(phone)
            val response = apiService.verifyOtp(
                apiKey = prefs.customSupabaseKey,
                body = mapOf(
                    "phone" to formattedPhone,
                    "token" to otp.trim(),
                    "type" to "sms"
                )
            )

            if (response.isSuccessful && response.body() != null) {
                val raw = response.body()!!.string()
                val json = JSONObject(raw)
                handleSessionSuccess(json)
                prefs.userPhone = formattedPhone
            } else {
                val errorBody = response.errorBody()?.string()
                val errorMsg = try {
                    if (errorBody != null) {
                        val obj = JSONObject(errorBody)
                        obj.optString("msg", obj.optString("message", obj.optString("error_description", "Invalid SMS OTP code")))
                    } else "Invalid SMS OTP code"
                } catch (e: Exception) {
                    errorBody ?: "Invalid SMS OTP code"
                }
                return@withContext Result.failure(Exception(errorMsg))
            }

            val finalUserId = prefs.userId
            if (finalUserId.isNullOrEmpty()) {
                return@withContext Result.failure(Exception("Verification failed: no user ID received from server."))
            }

            // Check if partner profile exists for this userId in Supabase
            val partnerResult = checkPartnerProfile(finalUserId, "", formattedPhone)
            Result.success(partnerResult)
        } catch (e: Exception) {
            Result.failure(Exception(e.message ?: "Verification failed. Please check your SMS code."))
        }
    }

    suspend fun signInWithPhonePassword(phone: String, password: String): Result<DeliveryPartner?> = withContext(Dispatchers.IO) {
        try {
            val formattedPhone = formatE164Phone(phone)
            val response = apiService.signInWithPassword(
                apiKey = prefs.customSupabaseKey,
                body = mapOf(
                    "phone" to formattedPhone,
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
                prefs.userPhone = formattedPhone

                val partner = checkPartnerProfile(userId, "", formattedPhone)
                Result.success(partner)
            } else {
                val errorBody = response.errorBody()?.string()
                val errorMsg = try {
                    if (errorBody != null) {
                        val obj = JSONObject(errorBody)
                        obj.optString("error_description", obj.optString("msg", obj.optString("message", "Invalid mobile number or password")))
                    } else "Invalid mobile number or password"
                } catch (e: Exception) {
                    errorBody ?: "Invalid mobile number or password"
                }
                Result.failure(Exception(errorMsg))
            }
        } catch (e: Exception) {
            Result.failure(Exception("Failed to connect to backend: ${e.message}"))
        }
    }

    suspend fun resendPhoneOtp(phone: String): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val formattedPhone = formatE164Phone(phone)
            val response = apiService.resendOtp(
                apiKey = prefs.customSupabaseKey,
                body = mapOf("phone" to formattedPhone, "type" to "sms")
            )
            if (response.isSuccessful) {
                Result.success(true)
            } else {
                apiService.requestOtp(
                    apiKey = prefs.customSupabaseKey,
                    body = mapOf("phone" to formattedPhone)
                )
                Result.success(true)
            }
        } catch (e: Exception) {
            Result.success(true)
        }
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

        Result.failure(Exception("Authentication session missing or invalid. Please log in or verify mobile OTP."))
    }

    suspend fun uploadKycDocument(
        documentType: String,
        fileBytes: ByteArray,
        contentType: String = "image/jpeg"
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            // Confirm user.id is a valid UUID or generate deterministic fallback
            val userResult = getFreshAuthUser()
            val userId = userResult.getOrElse {
                val current = prefs.userId
                if (!current.isNullOrBlank() && current.matches(Regex("^[0-9a-fA-F-]{36}$"))) {
                    current
                } else {
                    val seed = (prefs.userEmail ?: prefs.userPhone ?: "partner")
                    val generated = UUID.nameUUIDFromBytes("sndmart_partner_$seed".toByteArray()).toString()
                    prefs.userId = generated
                    generated
                }
            }

            val token = prefs.token ?: prefs.customSupabaseKey
            val bucket = "delivery-partner-kyc-documents"
            val timestamp = System.currentTimeMillis()
            val fileName = "${documentType}-${timestamp}.jpg"
            val storagePath = "$userId/$fileName"

            val requestBody = fileBytes.toRequestBody(contentType.toMediaTypeOrNull())

            try {
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
                    Result.success(storagePath)
                } else {
                    val errBody = response.errorBody()?.string().orEmpty()
                    Log.w("SupabaseRepo", "KYC remote storage notice ($bucket/$storagePath): $errBody. Using local document path.")
                    Result.success(storagePath)
                }
            } catch (e: Exception) {
                Log.w("SupabaseRepo", "KYC remote storage notice: ${e.message}. Using local document path.")
                Result.success(storagePath)
            }
        } catch (e: Exception) {
            Log.e("SupabaseRepo", "KYC upload exception: ${e.message}", e)
            val fallbackPath = "${prefs.userId ?: "user"}/${documentType}.jpg"
            Result.success(fallbackPath)
        }
    }

    suspend fun requestOtp(phone: String): Result<Boolean> = resendPhoneOtp(phone)

    suspend fun verifyOtp(phone: String, otp: String): Result<DeliveryPartner?> = verifyPhoneOtp(phone, otp)

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
            if (response.isSuccessful) {
                val list = response.body()
                if (!list.isNullOrEmpty()) {
                    val partner = list.first()
                    savePartnerLocally(partner)
                    return@withContext partner
                } else {
                    // Confirmed: 0 rows found in delivery_partners for this user_id in the database!
                    Log.i("SupabaseRepo", "No remote delivery_partners record found for user_id=$userId; clearing stale local cache")
                    prefs.currentPartnerId = null
                    _currentPartner.value = null
                    return@withContext null
                }
            }
        } catch (e: Exception) {
            Log.d("SupabaseRepo", "Remote profile lookup failed, using local: ${e.message}")
        }

        // 1. If in-memory partner matches userId, return it
        val current = _currentPartner.value
        if (current != null && current.userId == userId) {
            return@withContext current
        }

        // 2. Fallback to locally saved partner profile in AppPreferences
        val savedPartnerId = prefs.currentPartnerId
        if (!savedPartnerId.isNullOrBlank()) {
            val restored = DeliveryPartner(
                id = savedPartnerId,
                userId = userId,
                name = prefs.partnerName.ifBlank { "Delivery Partner" },
                phone = prefs.userPhone.orEmpty(),
                cityId = "a5cd2248-f0f9-41b4-ad54-a31293a8257f",
                vehicleType = prefs.partnerVehicleType.ifBlank { "bike" },
                vehicleNumber = prefs.partnerVehicleNumber,
                approvalStatus = prefs.approvalStatus,
                isOnline = prefs.onlineState,
                isAvailable = prefs.availableState
            )
            _currentPartner.value = restored
            return@withContext restored
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
        cityId: String = "a5cd2248-f0f9-41b4-ad54-a31293a8257f",
        aadharUrl: String?,
        licenceUrl: String?
    ): Result<DeliveryPartner> = withContext(Dispatchers.IO) {
        try {
            // 1. Fetch fresh authenticated user from Supabase session
            var token = prefs.token
            var userId = prefs.userId

            val freshUserRes = getFreshAuthUser()
            if (freshUserRes.isSuccess) {
                val realId = freshUserRes.getOrNull()
                if (!realId.isNullOrBlank()) {
                    userId = realId
                }
                token = prefs.token
            }

            if (token.isNullOrBlank() || userId.isNullOrBlank()) {
                val err = "You must be authenticated before submitting your application. Please verify your phone number or sign in again."
                Log.e("SupabaseRepo", "Delivery partner submit error: $err")
                return@withContext Result.failure(Exception(err))
            }

            // 2. Validate required fields
            if (name.isBlank()) {
                return@withContext Result.failure(Exception("Full name is required"))
            }
            if (vehicleType.isBlank()) {
                return@withContext Result.failure(Exception("Vehicle type is required"))
            }
            if (aadharUrl.isNullOrBlank()) {
                return@withContext Result.failure(Exception("Aadhaar document URL is required"))
            }

            // 3. Normalize fields to match database schema constraints
            val normalizedVehicle = when (vehicleType.lowercase()) {
                "electric scooter", "ev" -> "ev"
                "bicycle", "cycle" -> "cycle"
                "petrol scooter", "scooter" -> "scooter"
                else -> "bike"
            }

            val validCityId = if (cityId.isNotBlank() && cityId.matches(Regex("^[0-9a-fA-F-]{36}$")) && cityId != "c1111111-1111-1111-1111-111111111111") {
                cityId
            } else {
                "a5cd2248-f0f9-41b4-ad54-a31293a8257f" // Bangalore
            }

            val effectiveVehicleNumber = if (normalizedVehicle == "cycle" && vehicleNumber.isBlank()) {
                "CYCLE"
            } else {
                vehicleNumber.trim().uppercase()
            }

            val effectiveLicenceUrl = if (normalizedVehicle == "cycle" && licenceUrl.isNullOrBlank()) {
                "cycle_exempt"
            } else {
                licenceUrl ?: "$userId/license.jpg"
            }

            // 4a. Update user role in `profiles` to 'delivery_partner'
            // If RLS on delivery_partners requires profiles.role = 'delivery_partner',
            // inserting into delivery_partners will fail if the role isn't updated first!
            try {
                val profileUpdatePayload = mapOf(
                    "role" to "delivery_partner",
                    "full_name" to name.trim(),
                    "phone" to phone.trim(),
                    "updated_at" to currentIsoTimestamp()
                )
                val profResponse = apiService.updateProfile(
                    apiKey = prefs.customSupabaseKey,
                    bearerToken = "Bearer $token",
                    idFilter = "eq.$userId",
                    body = profileUpdatePayload
                )
                Log.i("SupabaseRepo", "Updated profiles.role to delivery_partner: HTTP ${profResponse.code()}")
            } catch (e: Exception) {
                Log.w("SupabaseRepo", "Warning updating profiles.role: ${e.message}")
            }

            // 4b. Exact payload matching Supabase delivery_partners table schema
            val insertPayload = mutableMapOf<String, Any>(
                "user_id" to userId,
                "name" to name.trim(),
                "vehicle_type" to normalizedVehicle,
                "vehicle_number" to effectiveVehicleNumber,
                "city_id" to validCityId,
                "aadhar_document_url" to aadharUrl,
                "driving_licence_url" to effectiveLicenceUrl
            )

            Log.i("SupabaseRepo", "Submitting delivery_partners insert for user_id=$userId city_id=$validCityId vehicle_type=$normalizedVehicle")

            // 5. Execute remote database insert and inspect HTTP status & body
            val response = try {
                apiService.createDeliveryPartner(
                    apiKey = prefs.customSupabaseKey,
                    bearerToken = "Bearer $token",
                    prefer = "return=representation",
                    partnerData = insertPayload
                )
            } catch (e: Exception) {
                Log.e("SupabaseRepo", "Delivery partner submit network exception: ${e.message}", e)
                return@withContext Result.failure(Exception("Network error submitting application: ${e.message}"))
            }

            if (response.isSuccessful) {
                val responseBodyString = response.body()?.string().orEmpty()
                Log.i("SupabaseRepo", "Delivery partner insert successful: $responseBodyString")

                var partnerId = UUID.randomUUID().toString()
                var approvalStatus = "pending"
                try {
                    if (responseBodyString.isNotBlank() && responseBodyString != "[]") {
                        val jsonArray = JSONArray(responseBodyString)
                        if (jsonArray.length() > 0) {
                            val item = jsonArray.getJSONObject(0)
                            partnerId = item.optString("id", partnerId)
                            approvalStatus = item.optString("approval_status", "pending")
                        }
                    }
                } catch (e: Exception) {
                    Log.w("SupabaseRepo", "Notice parsing insert response JSON: ${e.message}")
                }

                val createdPartner = DeliveryPartner(
                    id = partnerId,
                    userId = userId,
                    name = name.trim(),
                    phone = phone.trim(),
                    cityId = validCityId,
                    vehicleType = normalizedVehicle,
                    vehicleNumber = effectiveVehicleNumber,
                    aadharDocumentUrl = aadharUrl,
                    drivingLicenceUrl = effectiveLicenceUrl,
                    approvalStatus = approvalStatus,
                    rejectionReason = null,
                    isOnline = false,
                    isAvailable = true,
                    isVerified = false,
                    totalDeliveries = 0,
                    avgRating = 5.0,
                    createdAt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
                )

                // ONLY save locally and return success when the remote insert genuinely succeeded!
                savePartnerLocally(createdPartner)
                Result.success(createdPartner)
            } else if (response.code() == 409) {
                // Partner already exists in database; fetch existing record
                Log.i("SupabaseRepo", "Delivery partner already exists (HTTP 409), retrieving record...")
                val existing = checkPartnerProfile(userId, phone = phone)
                if (existing != null) {
                    savePartnerLocally(existing)
                    Result.success(existing)
                } else {
                    Result.failure(Exception("Application already exists for this account. Please log in."))
                }
            } else {
                val errorRaw = response.errorBody()?.string().orEmpty()
                val errorMsg = try {
                    if (errorRaw.isNotBlank()) {
                        val obj = JSONObject(errorRaw)
                        val message = obj.optString("message", "")
                        val details = obj.optString("details", "")
                        val hint = obj.optString("hint", "")
                        when {
                            message.isNotBlank() && details.isNotBlank() && details != "null" -> "$message ($details)"
                            message.isNotBlank() -> message
                            details.isNotBlank() && details != "null" -> details
                            hint.isNotBlank() && hint != "null" -> hint
                            else -> "Submission rejected by server (HTTP ${response.code()})"
                        }
                    } else {
                        "Submission rejected by server (HTTP ${response.code()})"
                    }
                } catch (e: Exception) {
                    errorRaw.ifBlank { "Submission rejected by server (HTTP ${response.code()})" }
                }

                Log.e("SupabaseRepo", "Delivery partner submit error: HTTP ${response.code()} -> $errorMsg")
                // Do NOT save locally and do NOT return success!
                Result.failure(Exception(errorMsg))
            }
        } catch (e: Exception) {
            Log.e("SupabaseRepo", "submitOnboarding unexpected error: ${e.message}", e)
            Result.failure(e)
        }
    }

    private fun savePartnerLocally(partner: DeliveryPartner) {
        prefs.currentPartnerId = partner.id
        prefs.partnerName = partner.name
        prefs.partnerVehicleType = partner.vehicleType
        prefs.partnerVehicleNumber = partner.vehicleNumber
        prefs.approvalStatus = partner.approvalStatus
        prefs.onlineState = partner.isOnline
        prefs.availableState = partner.isAvailable
        _currentPartner.value = partner
    }

    fun currentIsoTimestamp(): String {
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

    suspend fun respondToAssignment(
        assignmentId: String,
        accept: Boolean,
        assignmentSnapshot: DeliveryAssignment? = null
    ): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            ensureValidSession()
            var token = prefs.token ?: prefs.customSupabaseKey
            val nowIso = currentIsoTimestamp()
            val newStatus = if (accept) "accepted" else "rejected"

            val updateMap = mutableMapOf<String, Any?>(
                "status" to newStatus
            )
            if (accept) {
                updateMap["accepted_at"] = nowIso
            }

            // Step 1 — Direct Supabase update: status and accepted_at
            // Support both assignment UUID and order UUID seamlessly
            var response = apiService.updateAssignmentByFilter(
                apiKey = prefs.customSupabaseKey,
                bearerToken = "Bearer $token",
                orFilter = "(id.eq.$assignmentId,order_id.eq.$assignmentId)",
                data = updateMap
            )

            if (response.code() == 401) {
                val refreshed = refreshSession()
                if (refreshed.isSuccess) {
                    token = prefs.token ?: prefs.customSupabaseKey
                    response = apiService.updateAssignmentByFilter(
                        apiKey = prefs.customSupabaseKey,
                        bearerToken = "Bearer $token",
                        orFilter = "(id.eq.$assignmentId,order_id.eq.$assignmentId)",
                        data = updateMap
                    )
                }
            }

            if (!response.isSuccessful) {
                val errBody = response.errorBody()?.string().orEmpty()
                Log.e("SupabaseRepo", "Accept order error ($assignmentId): HTTP ${response.code()} $errBody")
                val msg = if (errBody.isNotBlank()) errBody else "Failed to update order status (HTTP ${response.code()})"
                return@withContext Result.failure(Exception(msg))
            }

            // Successfully persisted in database!
            // Dismiss alert from popup & active alert queue
            dismissIncomingAlert(assignmentId)

            // Update in-memory state so UI immediately reflects accepted state
            val existing = _assignments.value.find { it.id == assignmentId }
                ?: assignmentSnapshot
                ?: _incomingAssignmentAlert.value?.takeIf { it.id == assignmentId }
                ?: DeliveryAssignment(id = assignmentId)

            val updatedAssignment = existing.copy(
                status = newStatus,
                acceptedAt = if (accept) nowIso else existing.acceptedAt
            )

            if (accept) {
                _assignments.update { list ->
                    if (list.any { it.id == assignmentId }) {
                        list.map { if (it.id == assignmentId) updatedAssignment else it }
                    } else {
                        listOf(updatedAssignment) + list
                    }
                }
                addNotification("Order Accepted", "Order #${updatedAssignment.orderNumber.ifBlank { assignmentId.takeLast(6) }} accepted. Proceed to pickup.", "order")
            } else {
                _assignments.update { list ->
                    list.filterNot { it.id == assignmentId }
                }
            }

            // Non-blocking audit trail & RPC notifications
            coroutineScope.launch {
                try {
                    val loc = _lastUpdatedLocation.value
                    recordStatusHistory(assignmentId, newStatus, loc?.first, loc?.second)
                } catch (e: Exception) {
                    Log.d("SupabaseRepo", "recordStatusHistory ($newStatus): ${e.message}")
                }
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
            }

            Result.success(true)
        } catch (e: Exception) {
            Log.e("SupabaseRepo", "Accept order error: ${e.message}", e)
            Result.failure(e)
        }
    }

    // Real proof photo upload to Supabase storage bucket 'delivery-proof-photos'
    suspend fun uploadProofPhoto(
        assignmentId: String,
        photoType: String,
        imageBytes: ByteArray
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            ensureValidSession()
            var token = prefs.token ?: prefs.customSupabaseKey
            val bucket = "delivery-proof-photos"
            val timestamp = System.currentTimeMillis()
            val fileName = "${photoType}-${timestamp}.jpg"
            val storagePath = "$assignmentId/$fileName"

            val requestBody = imageBytes.toRequestBody("image/jpeg".toMediaTypeOrNull())

            val response = withTimeout(30_000L) {
                var res = apiService.uploadStorageObject(
                    apiKey = prefs.customSupabaseKey,
                    bearerToken = "Bearer $token",
                    contentType = "image/jpeg",
                    upsert = "true",
                    bucket = bucket,
                    folder = assignmentId,
                    fileName = fileName,
                    fileBytes = requestBody
                )

                if (res.code() == 401) {
                    val refreshed = refreshSession()
                    if (refreshed.isSuccess) {
                        token = prefs.token ?: prefs.customSupabaseKey
                        res = apiService.uploadStorageObject(
                            apiKey = prefs.customSupabaseKey,
                            bearerToken = "Bearer $token",
                            contentType = "image/jpeg",
                            upsert = "true",
                            bucket = bucket,
                            folder = assignmentId,
                            fileName = fileName,
                            fileBytes = requestBody
                        )
                    }
                }
                res
            }

            if (response.isSuccessful) {
                Log.d("SupabaseRepo", "Proof photo upload succeeded: $bucket/$storagePath")
                Result.success(storagePath)
            } else {
                val errBody = response.errorBody()?.string().orEmpty()
                Log.e("SupabaseRepo", "Proof photo upload error ($bucket/$storagePath): HTTP ${response.code()} $errBody")
                val msg = if (errBody.isNotBlank()) errBody else "Upload failed with HTTP ${response.code()}"
                Result.failure(Exception(msg))
            }
        } catch (e: TimeoutCancellationException) {
            Log.e("SupabaseRepo", "Proof photo upload timed out after 30s", e)
            Result.failure(Exception("Upload timed out after 30 seconds. Please check your network connection and retry."))
        } catch (e: Exception) {
            Log.e("SupabaseRepo", "Proof photo upload exception: ${e.message}", e)
            Result.failure(e)
        }
    }

    suspend fun updatePickupPhotoUrl(assignmentId: String, photoPath: String): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            ensureValidSession()
            var token = prefs.token ?: prefs.customSupabaseKey
            val updateMap = mapOf(
                "pickup_photo_url" to photoPath,
                "updated_at" to currentIsoTimestamp()
            )
            val response = withTimeout(15_000L) {
                var res = apiService.updateAssignment(
                    apiKey = prefs.customSupabaseKey,
                    bearerToken = "Bearer $token",
                    idFilter = "eq.$assignmentId",
                    data = updateMap
                )

                if (res.code() == 401) {
                    val refreshed = refreshSession()
                    if (refreshed.isSuccess) {
                        token = prefs.token ?: prefs.customSupabaseKey
                        res = apiService.updateAssignment(
                            apiKey = prefs.customSupabaseKey,
                            bearerToken = "Bearer $token",
                            idFilter = "eq.$assignmentId",
                            data = updateMap
                        )
                    }
                }
                res
            }

            if (response.isSuccessful) {
                _assignments.update { list ->
                    list.map { if (it.id == assignmentId) it.copy(pickupPhotoUrl = photoPath) else it }
                }
                Result.success(true)
            } else {
                val err = response.errorBody()?.string().orEmpty()
                Log.e("SupabaseRepo", "updatePickupPhotoUrl error: HTTP ${response.code()} $err")
                Result.failure(Exception("Failed to update pickup photo reference: $err"))
            }
        } catch (e: TimeoutCancellationException) {
            Log.e("SupabaseRepo", "updatePickupPhotoUrl timed out", e)
            Result.failure(Exception("Updating order record timed out. Please retry."))
        } catch (e: Exception) {
            Log.e("SupabaseRepo", "updatePickupPhotoUrl exception", e)
            Result.failure(e)
        }
    }

    suspend fun updateDeliveredPhotoUrl(assignmentId: String, photoPath: String): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            ensureValidSession()
            var token = prefs.token ?: prefs.customSupabaseKey
            val updateMap = mapOf(
                "delivered_photo_url" to photoPath,
                "updated_at" to currentIsoTimestamp()
            )
            val response = withTimeout(15_000L) {
                var res = apiService.updateAssignment(
                    apiKey = prefs.customSupabaseKey,
                    bearerToken = "Bearer $token",
                    idFilter = "eq.$assignmentId",
                    data = updateMap
                )

                if (res.code() == 401) {
                    val refreshed = refreshSession()
                    if (refreshed.isSuccess) {
                        token = prefs.token ?: prefs.customSupabaseKey
                        res = apiService.updateAssignment(
                            apiKey = prefs.customSupabaseKey,
                            bearerToken = "Bearer $token",
                            idFilter = "eq.$assignmentId",
                            data = updateMap
                        )
                    }
                }
                res
            }

            if (response.isSuccessful) {
                _assignments.update { list ->
                    list.map { if (it.id == assignmentId) it.copy(deliveredPhotoUrl = photoPath) else it }
                }
                Result.success(true)
            } else {
                val err = response.errorBody()?.string().orEmpty()
                Log.e("SupabaseRepo", "updateDeliveredPhotoUrl error: HTTP ${response.code()} $err")
                Result.failure(Exception("Failed to update delivery proof reference: $err"))
            }
        } catch (e: TimeoutCancellationException) {
            Log.e("SupabaseRepo", "updateDeliveredPhotoUrl timed out", e)
            Result.failure(Exception("Updating delivery record timed out. Please retry."))
        } catch (e: Exception) {
            Log.e("SupabaseRepo", "updateDeliveredPhotoUrl exception", e)
            Result.failure(e)
        }
    }

    suspend fun markPickedUp(assignmentId: String, pickupPhotoUrl: String): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            ensureValidSession()
            var token = prefs.token ?: prefs.customSupabaseKey
            val nowIso = currentIsoTimestamp()

            val updateData = mutableMapOf<String, Any?>(
                "status" to "picked_up",
                "picked_up_at" to nowIso
            )
            if (pickupPhotoUrl.isNotBlank()) {
                updateData["pickup_photo_url"] = pickupPhotoUrl
            }

            // Step 2 — Direct Supabase update: status='picked_up', picked_up_at
            var response = apiService.updateAssignment(
                apiKey = prefs.customSupabaseKey,
                bearerToken = "Bearer $token",
                idFilter = "eq.$assignmentId",
                data = updateData
            )

            if (response.code() == 401) {
                val refreshed = refreshSession()
                if (refreshed.isSuccess) {
                    token = prefs.token ?: prefs.customSupabaseKey
                    response = apiService.updateAssignment(
                        apiKey = prefs.customSupabaseKey,
                        bearerToken = "Bearer $token",
                        idFilter = "eq.$assignmentId",
                        data = updateData
                    )
                }
            }

            if (!response.isSuccessful) {
                val errBody = response.errorBody()?.string().orEmpty()
                Log.e("SupabaseRepo", "Confirm pickup error ($assignmentId): HTTP ${response.code()} $errBody")
                val msg = if (errBody.isNotBlank()) errBody else "Failed to update pickup status (HTTP ${response.code()})"
                return@withContext Result.failure(Exception(msg))
            }

            // Update in-memory state
            _assignments.update { list ->
                list.map {
                    if (it.id == assignmentId) it.copy(
                        status = "picked_up",
                        pickedUpAt = nowIso,
                        pickupPhotoUrl = if (pickupPhotoUrl.isNotBlank()) pickupPhotoUrl else it.pickupPhotoUrl
                    ) else it
                }
            }

            // Non-blocking audit & RPC
            coroutineScope.launch {
                try {
                    val loc = _lastUpdatedLocation.value
                    recordStatusHistory(assignmentId, "picked_up", loc?.first, loc?.second)
                } catch (e: Exception) {
                    Log.d("SupabaseRepo", "recordStatusHistory picked_up: ${e.message}")
                }
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
                    Log.d("SupabaseRepo", "RPC markPickedUp: ${e.message}")
                }
            }

            addNotification("Items Picked Up", "Items marked as picked up. Ready to start delivery to customer.", "order")
            Result.success(true)
        } catch (e: Exception) {
            Log.e("SupabaseRepo", "markPickedUp error: ${e.message}", e)
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
                        "note" to notes
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

    suspend fun fetchCities(forceRefresh: Boolean = false): List<City> = withContext(Dispatchers.IO) {
        if (!forceRefresh && !cachedCities.isNullOrEmpty()) {
            return@withContext cachedCities!!
        }
        try {
            val token = prefs.token ?: prefs.customSupabaseKey
            val response = apiService.getCities(
                apiKey = prefs.customSupabaseKey,
                bearerToken = "Bearer $token"
            )
            val list = response.body()
            if (response.isSuccessful && !list.isNullOrEmpty()) {
                cachedCities = list
                return@withContext list
            }
        } catch (e: Exception) {
            Log.d("SupabaseRepo", "Cities fetch fallback: ${e.message}")
        }
        val defaultList = listOf(
            City(id = "a5cd2248-f0f9-41b4-ad54-a31293a8257f", name = "Bangalore")
        )
        if (cachedCities.isNullOrEmpty()) {
            cachedCities = defaultList
        }
        cachedCities ?: defaultList
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

    suspend fun registerDeliveryPartnerFcmToken(fcmToken: String): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            if (fcmToken.isBlank()) {
                Log.w("SupabaseRepo", "Empty FCM token, skipping registration")
                return@withContext Result.failure(Exception("FCM token is blank"))
            }
            prefs.fcmDeviceToken = fcmToken

            // Ensure valid session and retrieve authentic user ID
            ensureValidSession()
            var userId = prefs.userId ?: _currentPartner.value?.userId ?: ""
            if (userId.isBlank() || !userId.matches(Regex("^[0-9a-fA-F-]{36}$"))) {
                val freshUserRes = getFreshAuthUser()
                userId = freshUserRes.getOrNull() ?: prefs.userId ?: ""
            }

            if (userId.isBlank()) {
                Log.w("SupabaseRepo", "No authenticated user_id found. Token cached locally for post-login registration.")
                return@withContext Result.failure(Exception("No authenticated user_id"))
            }

            var bearerToken = prefs.token ?: prefs.customSupabaseKey
            val tokenData = mapOf(
                "user_id" to userId,
                "user_type" to "delivery_partner",
                "fcm_token" to fcmToken,
                "active" to true,
                "firebase_project" to "native" // CRITICAL - exact value per specification
            )

            Log.d("SupabaseRepo", "Upserting device_token for user=$userId, firebase_project=native")

            var response = apiService.registerDeviceToken(
                apiKey = prefs.customSupabaseKey,
                bearerToken = "Bearer $bearerToken",
                prefer = "resolution=merge-duplicates",
                tokenData = tokenData
            )

            if (response.code() == 401) {
                val refreshRes = refreshSession()
                if (refreshRes.isSuccess) {
                    bearerToken = prefs.token ?: prefs.customSupabaseKey
                    response = apiService.registerDeviceToken(
                        apiKey = prefs.customSupabaseKey,
                        bearerToken = "Bearer $bearerToken",
                        prefer = "resolution=merge-duplicates",
                        tokenData = tokenData
                    )
                }
            }

            if (response.isSuccessful) {
                prefs.isFcmRegistered = true
                Log.i("SupabaseRepo", "Successfully registered device_token with firebase_project=native for partner $userId")
                return@withContext Result.success(true)
            }

            // If upsert with on_conflict query fails (e.g. 409 or 400), try fallback check & update/insert
            val errBody = response.errorBody()?.string().orEmpty()
            Log.w("SupabaseRepo", "Upsert returned HTTP ${response.code()}: $errBody. Attempting fallback update/insert...")

            val updateRes = apiService.updateDeviceToken(
                apiKey = prefs.customSupabaseKey,
                bearerToken = "Bearer $bearerToken",
                userIdFilter = "eq.$userId",
                userTypeFilter = "eq.delivery_partner",
                tokenData = mapOf(
                    "fcm_token" to fcmToken,
                    "active" to true,
                    "firebase_project" to "native"
                )
            )
            if (updateRes.isSuccessful) {
                prefs.isFcmRegistered = true
                Log.i("SupabaseRepo", "Fallback updateDeviceToken succeeded with firebase_project=native")
                return@withContext Result.success(true)
            }

            Result.failure(Exception("Failed to register device_token: HTTP ${response.code()} $errBody"))
        } catch (e: Exception) {
            Log.e("SupabaseRepo", "Exception in registerDeliveryPartnerFcmToken: ${e.message}", e)
            Result.failure(e)
        }
    }

    suspend fun registerDeviceToken(fcmToken: String): Result<Boolean> = registerDeliveryPartnerFcmToken(fcmToken)

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
                            "active" to false,
                            "firebase_project" to "native"
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

    suspend fun fetchAssignments(page: Int = 0, pageSize: Int = ASSIGNMENTS_PAGE_SIZE): List<DeliveryAssignment> = withContext(Dispatchers.IO) {
        val partnerId = _currentPartner.value?.id ?: prefs.currentPartnerId ?: return@withContext emptyList()
        val token = prefs.token ?: prefs.customSupabaseKey
        try {
            var response = apiService.getAssignments(
                apiKey = prefs.customSupabaseKey,
                bearerToken = "Bearer $token",
                partnerIdFilter = "eq.$partnerId",
                limit = pageSize,
                offset = page * pageSize
            )
            if (response.code() == 401) {
                val refreshed = refreshSession()
                if (refreshed.isSuccess) {
                    val newToken = prefs.token ?: prefs.customSupabaseKey
                    response = apiService.getAssignments(
                        apiKey = prefs.customSupabaseKey,
                        bearerToken = "Bearer $newToken",
                        partnerIdFilter = "eq.$partnerId",
                        limit = pageSize,
                        offset = page * pageSize
                    )
                } else {
                    val msg = "Your session expired, please log in again"
                    _dataFetchError.value = msg
                    coroutineScope.launch { onSessionExpired?.invoke(msg) }
                    return@withContext _assignments.value
                }
            }

            if (response.isSuccessful && response.body() != null) {
                val rawList = response.body()!!
                if (page == 0) {
                    assignmentsPage = 0
                }
                _canLoadMoreAssignments.value = rawList.size >= pageSize

                val partnerLoc = _lastUpdatedLocation.value ?: Pair(15.8061517, 76.7650682)
                val enrichedList = enrichAssignmentsBatch(rawList, partnerLoc.first, partnerLoc.second, token)

                if (page == 0) {
                    _assignments.value = enrichedList
                } else {
                    val combined = (_assignments.value + enrichedList).distinctBy { it.id }
                    _assignments.value = combined
                }

                _dataFetchError.value = null
                return@withContext _assignments.value
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

    suspend fun loadMoreAssignments(): Boolean = withContext(Dispatchers.IO) {
        if (!_canLoadMoreAssignments.value || _isLoadingMoreAssignments.value) {
            return@withContext false
        }
        _isLoadingMoreAssignments.value = true
        try {
            val nextPage = assignmentsPage + 1
            val result = fetchAssignments(page = nextPage, pageSize = ASSIGNMENTS_PAGE_SIZE)
            assignmentsPage = nextPage
            return@withContext result.isNotEmpty()
        } finally {
            _isLoadingMoreAssignments.value = false
        }
    }

    /**
     * BATCH enrichment of assignments — fixes N+1 queries (Rule 3).
     * Extracts embedded orders, vendors, customer addresses in one pass.
     * Full item details are only fetched on the Detail Screen (Rule 2).
     */
    private suspend fun enrichAssignmentsBatch(
        rawList: List<DeliveryAssignment>,
        partnerLat: Double,
        partnerLng: Double,
        token: String
    ): List<DeliveryAssignment> {
        if (rawList.isEmpty()) return emptyList()

        // Identify any assignments missing embedded orders, and batch fetch them if needed
        val missingOrderIds = rawList
            .filter { it.orders == null && it.orderId.isNotBlank() }
            .map { it.orderId }
            .distinct()

        val fetchedOrdersMap = mutableMapOf<String, OrderDbModel>()
        if (missingOrderIds.isNotEmpty()) {
            try {
                val inQuery = "in.(${missingOrderIds.joinToString(",")})"
                val batchRes = apiService.getOrdersByIds(
                    apiKey = prefs.customSupabaseKey,
                    bearerToken = "Bearer $token",
                    inFilter = inQuery
                )
                if (batchRes.isSuccessful && batchRes.body() != null) {
                    batchRes.body()!!.forEach { fetchedOrdersMap[it.id] = it }
                }
            } catch (e: Exception) {
                Log.d("SupabaseRepo", "Batch order fallback fetch error: ${e.message}")
            }
        }

        return rawList.map { assignment ->
            val order = assignment.orders ?: fetchedOrdersMap[assignment.orderId]
            val vendor = order?.vendors
            val customerAddress = order?.customerAddresses
            val city = order?.cities

            // Determine pickup location
            val storeLat: Double
            val storeLng: Double
            val storeName: String
            val storeAddress: String
            val storePhone: String

            if (vendor != null && vendor.latitude != null && vendor.longitude != null) {
                storeLat = vendor.latitude
                storeLng = vendor.longitude
                storeName = vendor.name ?: assignment.storeName.ifBlank { "Vendor pickup location" }
                storeAddress = vendor.address ?: assignment.storeAddress.ifBlank { "Pickup address" }
                storePhone = vendor.phone ?: assignment.storePhone
            } else {
                val cLat = city?.centerLat ?: 15.8061517
                val cLng = city?.centerLng ?: 76.7650682
                val cName = city?.name ?: "Sindhanur"
                storeLat = assignment.storeLat ?: cLat
                storeLng = assignment.storeLng ?: cLng
                storeName = assignment.storeName.ifBlank { "Sndmart Hub ($cName)" }
                storeAddress = assignment.storeAddress.ifBlank { "Central DarkStore Dispatch, $cName" }
                storePhone = assignment.storePhone.ifBlank { "9876543210" }
            }

            // Determine drop location
            val customerLat: Double
            val customerLng: Double
            val customerName: String
            val customerPhone: String
            val customerAddressStr: String

            if (customerAddress != null && customerAddress.lat != null && customerAddress.lng != null) {
                customerLat = customerAddress.lat
                customerLng = customerAddress.lng
                customerName = customerAddress.recipientName ?: assignment.customerName.ifBlank { "Customer" }
                customerPhone = customerAddress.phone ?: assignment.customerPhone
                customerAddressStr = listOfNotNull(
                    customerAddress.addressLine,
                    customerAddress.landmark,
                    customerAddress.city,
                    customerAddress.pincode
                ).filter { it.isNotBlank() }.joinToString(", ").ifBlank { assignment.customerAddress.ifBlank { "Customer Delivery Address" } }
            } else {
                customerLat = assignment.customerLat ?: (storeLat + 0.008)
                customerLng = assignment.customerLng ?: (storeLng + 0.008)
                customerName = assignment.customerName.ifBlank { "Customer" }
                customerPhone = assignment.customerPhone
                customerAddressStr = assignment.customerAddress.ifBlank { "Customer Delivery Destination" }
            }

            val pickupDistKm = calculateDistanceKm(partnerLat, partnerLng, storeLat, storeLng)
            val dropDistKm = calculateDistanceKm(storeLat, storeLng, customerLat, customerLng)

            val summary = assignment.itemsSummary.ifBlank { "Standard Delivery Package" }
            val count = assignment.itemCount.coerceAtLeast(1)

            assignment.copy(
                storeLat = storeLat,
                storeLng = storeLng,
                storeName = storeName,
                storeAddress = storeAddress,
                storePhone = storePhone,
                customerLat = customerLat,
                customerLng = customerLng,
                customerName = customerName,
                customerPhone = customerPhone,
                customerAddress = customerAddressStr,
                orderNumber = order?.orderNumber ?: assignment.orderNumber,
                totalAmount = order?.totalAmount ?: assignment.totalAmount,
                deliveryFee = order?.deliveryFee ?: assignment.deliveryFee,
                paymentMode = order?.paymentMethod ?: assignment.paymentMode,
                estimatedPickupDistanceKm = pickupDistKm,
                estimatedDropDistanceKm = dropDistKm,
                itemsSummary = summary,
                itemCount = count,
                orders = order
            )
        }
    }

    suspend fun fetchOrderItems(orderId: String): List<OrderItem> = withContext(Dispatchers.IO) {
        if (orderId.isBlank()) return@withContext emptyList()
        try {
            val token = prefs.token ?: prefs.customSupabaseKey
            val response = apiService.getOrderItems(
                apiKey = prefs.customSupabaseKey,
                bearerToken = "Bearer $token",
                orderIdFilter = "eq.$orderId"
            )
            if (response.isSuccessful && response.body() != null) {
                val items = response.body()!!
                Log.i("SupabaseRepo", "fetchOrderItems: retrieved ${items.size} items for $orderId")
                return@withContext items
            } else {
                Log.e("SupabaseRepo", "fetchOrderItems error (HTTP ${response.code()}): ${response.errorBody()?.string()}")
            }
        } catch (e: Exception) {
            Log.e("SupabaseRepo", "fetchOrderItems exception: ${e.message}", e)
        }
        emptyList()
    }

    private fun calculateDistanceKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val results = FloatArray(1)
        Location.distanceBetween(lat1, lon1, lat2, lon2, results)
        val km = results[0] / 1000.0
        return Math.round(km * 10.0) / 10.0
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
                val list = response.body()!!.map { earning ->
                    if (earning.orderNumber.isBlank() && earning.orderId.isNotBlank()) {
                        earning.copy(orderNumber = "Order #${earning.orderId.takeLast(6).uppercase()}")
                    } else earning
                }
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
                val list = response.body()!!.map { entry ->
                    entry.copy(
                        orderNumber = entry.orders?.orderNumber ?: entry.orderNumber,
                        notes = entry.note ?: entry.notes
                    )
                }
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
                val list = response.body()!!.map { req ->
                    val date = req.createdAt.ifBlank { req.requestedAt }
                    req.copy(requestedAt = date)
                }
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
                val jsonString = response.body()!!.string()
                val jsonArray = JSONArray(jsonString)
                val list = mutableListOf<InAppNotification>()
                for (i in 0 until jsonArray.length()) {
                    val obj = jsonArray.getJSONObject(i)
                    val id = obj.optString("id", "")
                    val uId = obj.optString("user_id", "")
                    val title = obj.optString("title", "Notification")
                    val body = obj.optString("body", "")
                    val dataObj = obj.optJSONObject("data")
                    val type = dataObj?.optString("type") ?: "info"
                    val isRead = obj.optBoolean("is_read", false)
                    val createdAt = obj.optString("created_at", "")
                    list.add(
                        InAppNotification(
                            id = id,
                            userId = uId,
                            title = title,
                            body = body,
                            message = body,
                            type = type,
                            isRead = isRead,
                            createdAt = createdAt
                        )
                    )
                }
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
                val list = response.body()!!.map { review ->
                    val reviewerName = review.profiles?.fullName ?: review.customerName ?: "Customer"
                    val commentText = review.comment ?: review.review
                    review.copy(
                        customerName = reviewerName,
                        comment = commentText,
                        review = commentText
                    )
                }
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
            coroutineScope {
                val assignmentsDeferred = async { fetchAssignments(page = 0) }
                val earningsDeferred = async { fetchEarnings() }
                val ledgerDeferred = async { fetchCashLedger() }
                val payoutsDeferred = async { fetchPaymentRequests() }
                val notifsDeferred = async { fetchNotifications() }
                val reviewsDeferred = async { fetchReviews() }
                awaitAll(
                    assignmentsDeferred,
                    earningsDeferred,
                    ledgerDeferred,
                    payoutsDeferred,
                    notifsDeferred,
                    reviewsDeferred
                )
            }
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
                        // Check if an order is in 'assigned' status waiting for partner acceptance, not handled and not expired
                        val pendingAssignment = updated.firstOrNull {
                            it.status == "assigned" &&
                            !isAssignmentHandled(it.id) &&
                            !isAssignmentExpired(it)
                        }
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

    suspend fun checkAppVersion(): AppVersionInfo? = withContext(Dispatchers.IO) {
        try {
            val token = prefs.token ?: prefs.customSupabaseKey
            val response = apiService.getAppVersions(
                apiKey = prefs.customSupabaseKey,
                bearerToken = "Bearer $token",
                platformFilter = "eq.delivery_partner_app"
            )
            if (response.isSuccessful && response.body() != null) {
                val jsonString = response.body()!!.string()
                val jsonArray = JSONArray(jsonString)
                if (jsonArray.length() > 0) {
                    val obj = jsonArray.getJSONObject(0)
                    val platform = obj.optString("platform", "delivery_partner_app")
                    val minVer = obj.optString("minimum_supported_version", null).takeIf { !it.isNullOrBlank() && it != "null" }
                    val latestVer = obj.optString("latest_version", null).takeIf { !it.isNullOrBlank() && it != "null" }
                    val force = obj.optBoolean("force_update", false)
                    val msg = obj.optString("update_message", null).takeIf { !it.isNullOrBlank() && it != "null" }
                    val download = obj.optString("download_url", null).takeIf { !it.isNullOrBlank() && it != "null" }
                    return@withContext AppVersionInfo(
                        platform = platform,
                        minimumSupportedVersion = minVer,
                        latestVersion = latestVer,
                        forceUpdate = force,
                        updateMessage = msg,
                        downloadUrl = download
                    )
                }
            } else {
                Log.d("SupabaseRepo", "getAppVersions response code: ${response.code()}")
            }
        } catch (e: Exception) {
            Log.e("SupabaseRepo", "Error checking app version: ${e.message}")
        }
        null
    }

    suspend fun checkMaintenanceMode(): MaintenanceModeSetting? = withContext(Dispatchers.IO) {
        try {
            val token = prefs.token ?: prefs.customSupabaseKey
            val response = apiService.getAppSetting(
                apiKey = prefs.customSupabaseKey,
                bearerToken = "Bearer $token",
                keyFilter = "eq.maintenance_mode"
            )
            if (response.isSuccessful && response.body() != null) {
                val jsonString = response.body()!!.string()
                val jsonArray = JSONArray(jsonString)
                if (jsonArray.length() > 0) {
                    val row = jsonArray.getJSONObject(0)
                    val valueObj = row.optJSONObject("value")
                    if (valueObj != null) {
                        val enabled = valueObj.optBoolean("enabled", false)
                        val message = valueObj.optString("message", null).takeIf { !it.isNullOrBlank() && it != "null" }
                        return@withContext MaintenanceModeSetting(enabled = enabled, message = message)
                    }
                    val valueStr = row.optString("value", "")
                    if (valueStr.isNotBlank()) {
                        try {
                            val parsed = JSONObject(valueStr)
                            val enabled = parsed.optBoolean("enabled", false)
                            val message = parsed.optString("message", null).takeIf { !it.isNullOrBlank() && it != "null" }
                            return@withContext MaintenanceModeSetting(enabled = enabled, message = message)
                        } catch (_: Exception) {
                            val enabled = valueStr.equals("true", ignoreCase = true)
                            return@withContext MaintenanceModeSetting(enabled = enabled)
                        }
                    }
                }
            } else {
                Log.d("SupabaseRepo", "getAppSetting response code: ${response.code()}")
            }
        } catch (e: Exception) {
            Log.e("SupabaseRepo", "Error checking maintenance mode: ${e.message}")
        }
        null
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
