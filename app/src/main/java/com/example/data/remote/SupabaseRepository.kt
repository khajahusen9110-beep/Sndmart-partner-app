package com.example.data.remote

import android.app.NotificationManager
import android.content.Context
import android.location.Location
import android.util.Log
import com.example.BuildConfig
import com.example.data.local.AppPreferences
import com.example.data.model.*
import com.example.service.OrderAlert
import com.example.service.OrderAlertNotifier
import com.example.util.ServerClock
import com.example.util.parseIsoMillis
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
    private val prefs: AppPreferences
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
            // Learn the server clock from every response (see ServerClock).
            .addNetworkInterceptor { chain ->
                chain.proceed(chain.request()).also { ServerClock.onServerDate(it.header("Date")) }
            }
            .addInterceptor(HttpLoggingInterceptor().apply {
                // Bodies carry passwords, OTPs and tokens: only log them in debug builds.
                level = if (BuildConfig.DEBUG) HttpLoggingInterceptor.Level.BODY else HttpLoggingInterceptor.Level.NONE
                redactHeader("Authorization")
                redactHeader("apikey")
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

    private val apiService: SupabaseApiService = createApiService(prefs.customSupabaseUrl)

    /** Bearer for user-scoped calls. Never falls back to the anon key: that would silently run as anonymous. */
    private fun bearer(): String {
        val token = prefs.token
        if (token.isNullOrBlank()) throw IllegalStateException("You are signed out. Please log in again.")
        return "Bearer $token"
    }

    /** Bearer for public reads (app_versions, app_settings, cities) that also work before login. */
    private fun bearerOrAnon(): String = "Bearer ${prefs.token ?: prefs.customSupabaseKey}"

    /** Human readable message from a PostgREST / GoTrue error body. */
    private fun errorMessage(response: retrofit2.Response<*>, fallback: String): String {
        val raw = try { response.errorBody()?.string().orEmpty() } catch (e: Exception) { "" }
        if (response.code() == 404 && raw.contains("PGRST202")) {
            return "This feature is not available on the server yet. Please contact your hub."
        }
        return try {
            val obj = JSONObject(raw)
            listOf("message", "msg", "error_description", "error")
                .map { obj.optString(it, "") }
                .firstOrNull { it.isNotBlank() && it != "null" }
                ?: "$fallback (HTTP ${response.code()})"
        } catch (e: Exception) {
            if (raw.isNotBlank() && raw.length < 200) raw else "$fallback (HTTP ${response.code()})"
        }
    }

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

    fun setIncomingAssignmentAlert(assignment: DeliveryAssignment) {
        if (!isAssignmentHandled(assignment.id) && !isAssignmentExpired(assignment)) {
            _incomingAssignmentAlert.value = assignment
        }
    }

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

    /** Stops the ringing order alert (and closes its full-screen screen) for this offer. */
    fun cancelIncomingNotification(assignmentId: String) {
        try {
            OrderAlertNotifier.cancel(context, assignmentId)
        } catch (e: Exception) {
            Log.w("SupabaseRepo", "Notification cancel error: ${e.message}")
        }
    }

    /**
     * Rings for an offer found by polling, in case its FCM push was missed. The notifier
     * ignores offers it already alerted for, so a push plus a poll ring only once.
     */
    private fun ringForOffer(assignment: DeliveryAssignment) {
        if (_currentPartner.value?.isOnline != true) return
        OrderAlertNotifier.show(
            context,
            OrderAlert(
                assignmentId = assignment.id,
                body = "Order #${assignment.orderNumber}".takeIf { assignment.orderNumber.isNotBlank() }.orEmpty(),
                storeName = assignment.storeName,
                storeAddress = assignment.storeAddress,
                customerAddress = assignment.customerAddress,
                paymentMode = assignment.paymentMode,
                totalAmount = assignment.totalAmount.toLong().toString(),
                deliveryFee = assignment.deliveryFee.toLong().toString(),
                expiresAtIso = assignment.expiresAt
            )
        )
    }

    fun isAssignmentExpired(assignment: DeliveryAssignment): Boolean {
        val now = ServerClock.now()
        val expiresAt = parseIsoMillis(assignment.expiresAt)
        if (expiresAt != null) return now >= expiresAt
        // Server did not set expires_at: fall back to the offer window from creation time.
        val createdAt = parseIsoMillis(assignment.assignedAt) ?: parseIsoMillis(assignment.createdAt) ?: return false
        return now - createdAt >= assignment.expiresInSeconds.coerceAtLeast(30) * 1000L
    }

    private val _earnings = MutableStateFlow<List<DeliveryEarning>>(emptyList())
    val earnings: StateFlow<List<DeliveryEarning>> = _earnings.asStateFlow()

    private val _cashLedger = MutableStateFlow<List<CashLedgerEntry>>(emptyList())
    val cashLedger: StateFlow<List<CashLedgerEntry>> = _cashLedger.asStateFlow()

    private val _paymentRequests = MutableStateFlow<List<PaymentRequest>>(emptyList())
    val paymentRequests: StateFlow<List<PaymentRequest>> = _paymentRequests.asStateFlow()

    private val _bankDetails = MutableStateFlow<DeliveryPartnerBankDetails?>(null)
    val bankDetails: StateFlow<DeliveryPartnerBankDetails?> = _bankDetails.asStateFlow()

    private val _notifications = MutableStateFlow<List<InAppNotification>>(emptyList())
    val notifications: StateFlow<List<InAppNotification>> = _notifications.asStateFlow()

    private val _reviews = MutableStateFlow<List<DeliveryPartnerReview>>(emptyList())
    val reviews: StateFlow<List<DeliveryPartnerReview>> = _reviews.asStateFlow()

    private val _lastUpdatedLocation = MutableStateFlow<Pair<Double, Double>?>(null)
    val lastUpdatedLocation: StateFlow<Pair<Double, Double>?> = _lastUpdatedLocation.asStateFlow()

    private var pollingJob: Job? = null
    private val coroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private fun createApiService(baseUrl: String): SupabaseApiService {
        val formattedUrl = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"
        return Retrofit.Builder()
            .baseUrl(formattedUrl)
            .client(okHttpClient)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(SupabaseApiService::class.java)
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
            val response = apiService.resendOtp(
                apiKey = prefs.customSupabaseKey,
                body = mapOf("phone" to formatE164Phone(phone), "type" to "sms")
            )
            if (response.isSuccessful) Result.success(true)
            else Result.failure(Exception(errorMessage(response, "Could not resend the code")))
        } catch (e: Exception) {
            Result.failure(Exception("Network error: ${e.message}"))
        }
    }

    /** Sends a one-time login code to an existing account (used for password reset). */
    suspend fun requestPasswordResetOtp(phone: String): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val response = apiService.requestLoginOtp(
                apiKey = prefs.customSupabaseKey,
                body = mapOf("phone" to formatE164Phone(phone), "create_user" to false)
            )
            if (response.isSuccessful) Result.success(true)
            else Result.failure(Exception(errorMessage(response, "Could not send the reset code")))
        } catch (e: Exception) {
            Result.failure(Exception("Network error: ${e.message}"))
        }
    }

    /** Verifies the reset code (this signs the user in) and then sets the new password. */
    suspend fun resetPasswordWithOtp(phone: String, otp: String, newPassword: String): Result<DeliveryPartner?> =
        withContext(Dispatchers.IO) {
            val verified = verifyPhoneOtp(phone, otp)
            if (verified.isFailure) return@withContext verified
            try {
                val response = apiService.updateUser(
                    apiKey = prefs.customSupabaseKey,
                    bearerToken = bearer(),
                    body = mapOf("password" to newPassword)
                )
                if (response.isSuccessful) verified
                else Result.failure(Exception(errorMessage(response, "Could not update password")))
            } catch (e: Exception) {
                Result.failure(Exception("Network error: ${e.message}"))
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
            if (response.isSuccessful) Result.success(true)
            else Result.failure(Exception(errorMessage(response, "Could not resend the code")))
        } catch (e: Exception) {
            Result.failure(Exception("Network error: ${e.message}"))
        }
    }

    suspend fun resetPasswordForEmail(email: String): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val response = apiService.recoverPassword(apiKey = prefs.customSupabaseKey, body = mapOf("email" to email))
            if (response.isSuccessful) Result.success(true)
            else Result.failure(Exception(errorMessage(response, "Could not send reset email")))
        } catch (e: Exception) {
            Result.failure(Exception("Network error: ${e.message}"))
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
            // Storage policy requires the first folder to be the caller's auth uid.
            val userId = getFreshAuthUser().getOrElse {
                return@withContext Result.failure(Exception("Please sign in again before uploading documents."))
            }
            val bucket = "delivery-partner-kyc-documents"
            val fileName = "${documentType}-${System.currentTimeMillis()}.jpg"
            val response = withTimeout(60_000L) {
                apiService.uploadStorageObject(
                    apiKey = prefs.customSupabaseKey,
                    bearerToken = bearer(),
                    contentType = contentType,
                    upsert = "true",
                    bucket = bucket,
                    folder = userId,
                    fileName = fileName,
                    fileBytes = fileBytes.toRequestBody(contentType.toMediaTypeOrNull())
                )
            }
            if (response.isSuccessful) {
                Result.success("$userId/$fileName")
            } else {
                Result.failure(Exception(errorMessage(response, "Document upload failed")))
            }
        } catch (e: TimeoutCancellationException) {
            Result.failure(Exception("Upload timed out. Please check your connection and retry."))
        } catch (e: Exception) {
            Log.e("SupabaseRepo", "KYC upload exception: ${e.message}", e)
            Result.failure(Exception(e.message ?: "Document upload failed"))
        }
    }

    suspend fun verifyOtp(phone: String, otp: String): Result<DeliveryPartner?> = verifyPhoneOtp(phone, otp)

    suspend fun checkPartnerProfile(userId: String, email: String = "", phone: String = ""): DeliveryPartner? = withContext(Dispatchers.IO) {
        try {
            var response = apiService.getDeliveryPartnerByUserId(
                apiKey = prefs.customSupabaseKey,
                bearerToken = bearer(),
                userIdFilter = "eq.$userId"
            )
            if (response.code() == 401) {
                val refreshed = refreshSession()
                if (refreshed.isSuccess) {
                    val newToken = prefs.token.orEmpty()
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
                cityId = "",
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

            if (!cityId.matches(Regex("^[0-9a-fA-F-]{36}$"))) {
                return@withContext Result.failure(Exception("Please select your delivery city"))
            }
            val validCityId = cityId

            val effectiveVehicleNumber = if (normalizedVehicle == "cycle" && vehicleNumber.isBlank()) {
                "CYCLE"
            } else {
                vehicleNumber.trim().uppercase()
            }

            val effectiveLicenceUrl = when {
                !licenceUrl.isNullOrBlank() -> licenceUrl
                normalizedVehicle == "cycle" -> "cycle_exempt"
                else -> return@withContext Result.failure(Exception("Driving licence document is required"))
            }
            // Only accept documents that were really uploaded to this user's storage folder.
            if (!aadharUrl.startsWith("$userId/") ||
                (effectiveLicenceUrl != "cycle_exempt" && !effectiveLicenceUrl.startsWith("$userId/"))
            ) {
                return@withContext Result.failure(Exception("Please upload your KYC documents again"))
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

    // ==========================================
    // ONLINE / AVAILABILITY / LIVE LOCATION
    // ==========================================

    /**
     * Goes online/offline through the `delivery_partner_set_online` RPC (which also checks the
     * partner is active). When going online with a real GPS fix the location is pushed too.
     * A missing fix is never replaced by made-up coordinates.
     */
    suspend fun setOnline(isOnline: Boolean, lat: Double? = null, lng: Double? = null): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val response = apiService.rpcSetOnline(
                apiKey = prefs.customSupabaseKey,
                bearerToken = bearer(),
                params = mapOf("p_online" to isOnline)
            )
            if (!response.isSuccessful) {
                return@withContext Result.failure(Exception(errorMessage(response, "Could not update online status")))
            }

            if (isOnline) {
                // set_online keeps the previous availability (offline clears it); a partner
                // coming online is available for new orders.
                setAvailability(true, online = true)
                if (lat != null && lng != null) updateLocation(lat, lng)
            }

            prefs.onlineState = isOnline
            _currentPartner.update { it?.copy(isOnline = isOnline, isAvailable = if (isOnline) true else it.isAvailable) }
            if (isOnline) fetchAssignments()
            Result.success(isOnline)
        } catch (e: Exception) {
            Log.e("SupabaseRepo", "setOnline exception: ${e.message}", e)
            Result.failure(e)
        }
    }

    suspend fun setAvailability(isAvailable: Boolean, online: Boolean? = null): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val partnerId = _currentPartner.value?.id ?: prefs.currentPartnerId
                ?: return@withContext Result.failure(Exception("Partner profile not loaded. Please refresh."))
            val isOnline = online ?: _currentPartner.value?.isOnline ?: prefs.onlineState
            val response = apiService.rpcSetAvailability(
                apiKey = prefs.customSupabaseKey,
                bearerToken = bearer(),
                params = mapOf("p_partner_id" to partnerId, "p_online" to isOnline, "p_available" to isAvailable)
            )
            if (!response.isSuccessful) {
                return@withContext Result.failure(Exception(errorMessage(response, "Could not update availability")))
            }
            if (response.body()?.string()?.trim() == "false") {
                return@withContext Result.failure(Exception("Partner profile not found"))
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
            val response = apiService.rpcUpdateLocation(
                apiKey = prefs.customSupabaseKey,
                bearerToken = bearer(),
                params = mapOf("p_lat" to lat, "p_lng" to lng)
            )
            if (!response.isSuccessful) {
                val msg = errorMessage(response, "Location update failed")
                Log.w("SupabaseRepo", "updateLocation failed: $msg")
                return@withContext Result.failure(Exception(msg))
            }
            _lastUpdatedLocation.value = Pair(lat, lng)
            val nowIso = currentIsoTimestamp()
            _currentPartner.update {
                it?.copy(latitude = lat, longitude = lng, lastLocationAt = nowIso, currentLat = lat, currentLng = lng)
            }
            Result.success(true)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ==========================================
    // ASSIGNMENT FLOW (server RPCs are the source of truth)
    // ==========================================

    // "On the way" and "arrived" are app-side progress steps after pickup. The backend only
    // tracks accepted -> picked_up -> delivered, so these are kept locally per assignment.
    private val localStages = java.util.concurrent.ConcurrentHashMap<String, String>()

    private fun applyLocalStage(assignment: DeliveryAssignment): DeliveryAssignment {
        val stage = localStages[assignment.id]
        return when {
            assignment.status in TERMINAL_STATUSES -> {
                localStages.remove(assignment.id)
                assignment
            }
            stage != null && assignment.status in listOf("picked_up", "out_for_delivery") -> assignment.copy(status = stage)
            else -> assignment
        }
    }

    private fun updateLocalAssignment(id: String, transform: (DeliveryAssignment) -> DeliveryAssignment) {
        _assignments.update { list -> list.map { if (it.id == id) transform(it) else it } }
    }

    suspend fun respondToAssignment(
        assignmentId: String,
        accept: Boolean,
        assignmentSnapshot: DeliveryAssignment? = null
    ): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            ensureValidSession()
            // The RPC verifies ownership and that the offer is still in 'assigned' state, so a
            // stale/expired/cancelled offer cannot be accepted.
            val response = apiService.rpcRespondAssignment(
                apiKey = prefs.customSupabaseKey,
                bearerToken = bearer(),
                params = mapOf("p_assignment_id" to assignmentId, "p_accept" to accept)
            )
            // Stop the alert once the server has decided (success, or 4xx such as "already
            // processed"). A 5xx keeps it ringing so the partner can retry while the offer lasts.
            if (response.isSuccessful || response.code() in 400..499) dismissIncomingAlert(assignmentId)
            if (!response.isSuccessful) {
                val msg = errorMessage(response, if (accept) "Could not accept order" else "Could not decline order")
                Log.e("SupabaseRepo", "respondToAssignment($assignmentId, $accept) failed: $msg")
                fetchAssignments()
                return@withContext Result.failure(Exception(msg))
            }

            val newStatus = if (accept) "accepted" else "rejected"
            if (accept) {
                val existing = _assignments.value.find { it.id == assignmentId }
                    ?: assignmentSnapshot
                    ?: DeliveryAssignment(id = assignmentId)
                val updated = existing.copy(status = newStatus, acceptedAt = currentIsoTimestamp())
                _assignments.update { list ->
                    if (list.any { it.id == assignmentId }) list.map { if (it.id == assignmentId) updated else it }
                    else listOf(updated) + list
                }
                addNotification("Order Accepted", "Order #${updated.orderNumber.ifBlank { assignmentId.takeLast(6) }} accepted. Proceed to pickup.", "order")
            } else {
                _assignments.update { list -> list.filterNot { it.id == assignmentId } }
            }
            coroutineScope.launch { fetchAssignments() }
            Result.success(true)
        } catch (e: Exception) {
            Log.e("SupabaseRepo", "respondToAssignment error: ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * Declines an offer whose time ran out so the server reassigns it. The in-app card and the
     * full-screen screen can both time out; only the first call does anything.
     */
    suspend fun declineExpiredOffer(assignmentId: String) {
        if (assignmentId.isBlank() || isAssignmentHandled(assignmentId)) return
        markAssignmentHandled(assignmentId)
        cancelIncomingNotification(assignmentId)
        if (_incomingAssignmentAlert.value?.id == assignmentId) _incomingAssignmentAlert.value = null
        respondToAssignment(assignmentId, accept = false)
            .onFailure { Log.d("SupabaseRepo", "Expired offer decline: ${it.message}") }
    }

    // Proof photo upload to Supabase storage bucket 'delivery-proof-photos'
    suspend fun uploadProofPhoto(
        assignmentId: String,
        photoType: String,
        imageBytes: ByteArray
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            ensureValidSession()
            val bucket = "delivery-proof-photos"
            val fileName = "${photoType}-${System.currentTimeMillis()}.jpg"
            val storagePath = "$assignmentId/$fileName"
            val requestBody = imageBytes.toRequestBody("image/jpeg".toMediaTypeOrNull())

            val response = withTimeout(30_000L) {
                apiService.uploadStorageObject(
                    apiKey = prefs.customSupabaseKey,
                    bearerToken = bearer(),
                    contentType = "image/jpeg",
                    upsert = "true",
                    bucket = bucket,
                    folder = assignmentId,
                    fileName = fileName,
                    fileBytes = requestBody
                )
            }

            if (response.isSuccessful) {
                Result.success(storagePath)
            } else {
                val msg = errorMessage(response, "Upload failed")
                Log.e("SupabaseRepo", "Proof photo upload error ($bucket/$storagePath): $msg")
                Result.failure(Exception(msg))
            }
        } catch (e: TimeoutCancellationException) {
            Result.failure(Exception("Upload timed out after 30 seconds. Please check your network connection and retry."))
        } catch (e: Exception) {
            Log.e("SupabaseRepo", "Proof photo upload exception: ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * Remembers an uploaded pickup photo locally. It is written to the assignment by the
     * `delivery_partner_pickup` RPC when the partner confirms pickup.
     */
    fun rememberPickupPhoto(assignmentId: String, photoPath: String) {
        updateLocalAssignment(assignmentId) { it.copy(pickupPhotoUrl = photoPath) }
    }

    /** Same as [rememberPickupPhoto] for the doorstep photo, saved by `delivery_partner_deliver`. */
    fun rememberDeliveredPhoto(assignmentId: String, photoPath: String) {
        updateLocalAssignment(assignmentId) { it.copy(deliveredPhotoUrl = photoPath) }
    }

    suspend fun markPickedUp(assignmentId: String, pickupPhotoUrl: String): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            if (pickupPhotoUrl.isBlank()) {
                return@withContext Result.failure(Exception("Pickup photo is required"))
            }
            ensureValidSession()
            val response = apiService.rpcPickup(
                apiKey = prefs.customSupabaseKey,
                bearerToken = bearer(),
                params = mapOf("p_assignment_id" to assignmentId, "p_pickup_photo_url" to pickupPhotoUrl)
            )
            if (!response.isSuccessful) {
                val msg = errorMessage(response, "Could not confirm pickup")
                Log.e("SupabaseRepo", "Confirm pickup error ($assignmentId): $msg")
                return@withContext Result.failure(Exception(msg))
            }

            localStages.remove(assignmentId)
            updateLocalAssignment(assignmentId) {
                it.copy(status = "picked_up", pickedUpAt = currentIsoTimestamp(), pickupPhotoUrl = pickupPhotoUrl)
            }
            addNotification("Items Picked Up", "Items marked as picked up. Ready to start delivery to customer.", "order")
            Result.success(true)
        } catch (e: Exception) {
            Log.e("SupabaseRepo", "markPickedUp error: ${e.message}", e)
            Result.failure(e)
        }
    }

    fun startDelivery(assignmentId: String): Result<Boolean> {
        localStages[assignmentId] = "out_for_delivery"
        updateLocalAssignment(assignmentId) { it.copy(status = "out_for_delivery") }
        addNotification("Out for Delivery", "Navigation active. Reach customer address safely.", "order")
        return Result.success(true)
    }

    fun arriveAtDrop(assignmentId: String): Result<Boolean> {
        localStages[assignmentId] = "arrived_drop"
        updateLocalAssignment(assignmentId) { it.copy(status = "arrived_drop") }
        return Result.success(true)
    }

    /**
     * Completes the delivery through `delivery_partner_deliver`, which verifies the customer OTP
     * on the server, settles earnings and records COD cash. Nothing is credited locally: after
     * success the ledgers are re-fetched from the server.
     */
    suspend fun completeDelivery(
        assignmentId: String,
        otp: String,
        deliveredPhotoUrl: String
    ): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            if (deliveredPhotoUrl.isBlank()) {
                return@withContext Result.failure(Exception("Delivery proof photo is required"))
            }
            ensureValidSession()
            val response = apiService.rpcDeliver(
                apiKey = prefs.customSupabaseKey,
                bearerToken = bearer(),
                params = mapOf(
                    "p_assignment_id" to assignmentId,
                    "p_otp" to otp.trim(),
                    "p_delivered_photo_url" to deliveredPhotoUrl
                )
            )
            if (!response.isSuccessful) {
                // Server text is shown as-is, e.g. "Invalid delivery OTP".
                return@withContext Result.failure(Exception(errorMessage(response, "Could not complete delivery")))
            }

            localStages.remove(assignmentId)
            val target = _assignments.value.find { it.id == assignmentId }
            updateLocalAssignment(assignmentId) {
                it.copy(status = "delivered", deliveredAt = currentIsoTimestamp(), deliveredPhotoUrl = deliveredPhotoUrl)
            }
            _currentPartner.update { it?.copy(totalDeliveries = it.totalDeliveries + 1) }
            addNotification(
                "Delivery Completed! 🎉",
                "Order ${target?.orderNumber.orEmpty()} successfully delivered.",
                "earning"
            )
            coroutineScope.launch {
                fetchEarnings()
                fetchCashLedger()
                fetchAssignments()
            }
            Result.success(true)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun requestCashRemittance(amount: Double, note: String? = null): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            if (amount <= 0.0) return@withContext Result.failure(Exception("Enter a valid amount"))
            val response = apiService.rpcRequestCashRemittance(
                apiKey = prefs.customSupabaseKey,
                bearerToken = bearer(),
                params = mapOf("p_amount" to amount, "p_note" to (note ?: "Cash remittance handover at hub"))
            )
            if (!response.isSuccessful) {
                return@withContext Result.failure(Exception(errorMessage(response, "Could not submit remittance request")))
            }
            fetchCashLedger()
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
            val response = apiService.getCities(
                apiKey = prefs.customSupabaseKey,
                bearerToken = bearerOrAnon()
            )
            val list = response.body()
            if (response.isSuccessful && !list.isNullOrEmpty()) {
                cachedCities = list
                return@withContext list
            }
        } catch (e: Exception) {
            Log.d("SupabaseRepo", "Cities fetch fallback: ${e.message}")
        }
        // No fake fallback city: onboarding waits until the real list loads.
        cachedCities.orEmpty()
    }

    // ==========================================
    // EARNINGS & PAYOUT RPCs
    // ==========================================

    /** Payout through `delivery_partner_request_payment`, which enforces the available balance. */
    suspend fun requestPayout(amount: Double): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            if (amount < MIN_PAYOUT_AMOUNT) {
                return@withContext Result.failure(Exception("Minimum payout amount is ₹${MIN_PAYOUT_AMOUNT.toInt()}"))
            }
            val response = apiService.rpcRequestPayment(
                apiKey = prefs.customSupabaseKey,
                bearerToken = bearer(),
                params = mapOf("p_amount" to amount)
            )
            if (!response.isSuccessful) {
                return@withContext Result.failure(Exception(errorMessage(response, "Could not submit payout request")))
            }
            fetchPaymentRequests()
            addNotification("Payout Requested", "Your payout request of ₹${amount.toInt()} has been submitted.", "payout")
            Result.success(true)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getBankDetails(): Result<DeliveryPartnerBankDetails?> = withContext(Dispatchers.IO) {
        try {
            val partnerId = _currentPartner.value?.id ?: prefs.currentPartnerId ?: return@withContext Result.success(null)
            val response = apiService.getBankDetails(
                apiKey = prefs.customSupabaseKey,
                bearerToken = bearer(),
                partnerIdFilter = "eq.$partnerId"
            )
            if (response.isSuccessful) {
                val details = response.body()?.firstOrNull()
                _bankDetails.value = details
                if (details != null) {
                    _currentPartner.update { current ->
                        current?.copy(
                            payoutMethod = details.payoutMethod ?: current.payoutMethod,
                            accountHolderName = details.accountHolderName ?: current.accountHolderName,
                            accountNumber = details.accountNumber ?: current.accountNumber,
                            ifscCode = details.ifscCode ?: current.ifscCode,
                            bankName = details.bankName ?: current.bankName,
                            upiId = details.upiId ?: current.upiId
                        )
                    }
                }
                Result.success(details)
            } else {
                Result.failure(Exception(errorMessage(response, "Failed to load bank details")))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun fetchBankDetails(): DeliveryPartnerBankDetails? = getBankDetails().getOrNull()

    suspend fun upsertBankDetails(
        payoutMethod: String,
        holderName: String,
        accountNumber: String,
        ifsc: String,
        bankName: String,
        upiId: String?
    ): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val response = apiService.rpcUpsertBankDetails(
                apiKey = prefs.customSupabaseKey,
                bearerToken = bearer(),
                params = mapOf(
                    "p_payout_method" to payoutMethod,
                    "p_account_holder_name" to holderName,
                    "p_account_number" to accountNumber,
                    "p_ifsc_code" to ifsc,
                    "p_bank_name" to bankName,
                    "p_upi_id" to upiId
                )
            )
            if (!response.isSuccessful) {
                return@withContext Result.failure(Exception(errorMessage(response, "Could not save bank details")))
            }
            getBankDetails()
            addNotification("Bank Details Updated", "Your payout account was updated and is pending verification.", "info")
            Result.success(true)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun registerDeliveryPartnerFcmToken(fcmToken: String): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            if (fcmToken.isBlank()) {
                return@withContext Result.failure(Exception("FCM token is not available yet"))
            }
            prefs.fcmDeviceToken = fcmToken
            if (prefs.token.isNullOrBlank()) {
                // Registered after the next login.
                return@withContext Result.failure(Exception("Not signed in"))
            }

            ensureValidSession()
            val userId = getFreshAuthUser().getOrNull()
                ?: return@withContext Result.failure(Exception("No authenticated user_id"))

            val tokenData = mapOf(
                "user_id" to userId,
                "user_type" to "delivery_partner",
                "fcm_token" to fcmToken,
                "active" to true,
                "firebase_project" to "native"
            )

            val response = apiService.registerDeviceToken(
                apiKey = prefs.customSupabaseKey,
                bearerToken = bearer(),
                prefer = "resolution=merge-duplicates",
                tokenData = tokenData
            )
            if (response.isSuccessful) {
                prefs.isFcmRegistered = true
                return@withContext Result.success(true)
            }

            val upsertError = errorMessage(response, "Upsert failed")
            Log.w("SupabaseRepo", "device_tokens upsert failed: $upsertError. Trying update.")
            val updateRes = apiService.updateDeviceToken(
                apiKey = prefs.customSupabaseKey,
                bearerToken = bearer(),
                userIdFilter = "eq.$userId",
                userTypeFilter = "eq.delivery_partner",
                tokenData = tokenData
            )
            if (updateRes.isSuccessful) {
                prefs.isFcmRegistered = true
                return@withContext Result.success(true)
            }
            Result.failure(Exception("Failed to register device for order alerts: $upsertError"))
        } catch (e: Exception) {
            Log.e("SupabaseRepo", "Exception in registerDeliveryPartnerFcmToken: ${e.message}", e)
            Result.failure(e)
        }
    }

    /** Stops pushes to this device for the signed-out user. Must run before the session is cleared. */
    suspend fun deactivateDeviceToken(): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val userId = prefs.userId
            if (userId.isNullOrBlank() || prefs.token.isNullOrBlank()) {
                prefs.isFcmRegistered = false
                return@withContext Result.success(false)
            }
            val response = apiService.updateDeviceToken(
                apiKey = prefs.customSupabaseKey,
                bearerToken = bearer(),
                userIdFilter = "eq.$userId",
                userTypeFilter = "eq.delivery_partner",
                tokenData = mapOf("active" to false)
            )
            prefs.isFcmRegistered = false
            if (response.isSuccessful) Result.success(true)
            else Result.failure(Exception(errorMessage(response, "Could not deactivate device token")))
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

    /**
     * page = 0 refreshes everything loaded so far (all pages up to [assignmentsPage]) so a
     * background refresh does not drop rows the user already scrolled to.
     */
    suspend fun fetchAssignments(page: Int = 0, pageSize: Int = ASSIGNMENTS_PAGE_SIZE): List<DeliveryAssignment> = withContext(Dispatchers.IO) {
        val partnerId = _currentPartner.value?.id ?: prefs.currentPartnerId ?: return@withContext emptyList()
        val token = prefs.token ?: return@withContext _assignments.value
        val limit = if (page == 0) pageSize * (assignmentsPage + 1) else pageSize
        val offset = if (page == 0) 0 else page * pageSize
        try {
            var response = apiService.getAssignments(
                apiKey = prefs.customSupabaseKey,
                bearerToken = "Bearer $token",
                partnerIdFilter = "eq.$partnerId",
                limit = limit,
                offset = offset
            )
            if (response.code() == 401) {
                val refreshed = refreshSession()
                if (refreshed.isSuccess) {
                    val newToken = prefs.token.orEmpty()
                    response = apiService.getAssignments(
                        apiKey = prefs.customSupabaseKey,
                        bearerToken = "Bearer $newToken",
                        partnerIdFilter = "eq.$partnerId",
                        limit = limit,
                        offset = offset
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
                _canLoadMoreAssignments.value = rawList.size >= limit

                val enrichedList = enrichAssignmentsBatch(rawList, _lastUpdatedLocation.value, token)
                    .map { applyLocalStage(it) }

                if (page == 0) {
                    _assignments.value = enrichedList
                    // Drop an offer that was taken back, cancelled or has expired on the server.
                    _incomingAssignmentAlert.value?.let { shown ->
                        val latest = enrichedList.find { it.id == shown.id }
                        if (latest != null && (latest.status != "assigned" || isAssignmentExpired(latest))) {
                            _incomingAssignmentAlert.value = null
                        }
                    }
                    // ...and stop any alert still ringing for it.
                    enrichedList
                        .filter { (it.status != "assigned" || isAssignmentExpired(it)) && OrderAlertNotifier.wasShown(it.id) }
                        .forEach { cancelIncomingNotification(it.id) }
                    val pending = enrichedList.firstOrNull {
                        it.status == "assigned" &&
                        !isAssignmentHandled(it.id) &&
                        !isAssignmentExpired(it)
                    }
                    if (pending != null && _incomingAssignmentAlert.value == null) {
                        _incomingAssignmentAlert.value = pending
                    }
                    if (pending != null) ringForOffer(pending)
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
            val before = _assignments.value.size
            fetchAssignments(page = nextPage, pageSize = ASSIGNMENTS_PAGE_SIZE)
            val grew = _assignments.value.size > before
            if (grew) assignmentsPage = nextPage
            return@withContext grew
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
        partnerLocation: Pair<Double, Double>?,
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
                storePhone = assignment.storePhone
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

            val pickupDistKm = partnerLocation?.let { calculateDistanceKm(it.first, it.second, storeLat, storeLng) }
                ?: assignment.estimatedPickupDistanceKm
            val dropDistKm = calculateDistanceKm(storeLat, storeLng, customerLat, customerLng)

            val summary = assignment.itemsSummary.ifBlank { "Standard Delivery Package" }
            val count = assignment.itemCount.coerceAtLeast(1)

            assignment.copy(
                status = normalizeServerStatus(assignment),
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

    /**
     * Older backend logic moves an assignment to 'out_for_delivery' as soon as the order
     * goes out, before the partner has photographed the pickup. Show it as 'accepted' so the
     * pickup step (photo + confirm) is still offered.
     */
    private fun normalizeServerStatus(assignment: DeliveryAssignment): String =
        if (assignment.status == "out_for_delivery" && assignment.pickupPhotoUrl.isNullOrBlank()) "accepted"
        else assignment.status

    suspend fun fetchOrderItems(orderId: String): List<OrderItem> = withContext(Dispatchers.IO) {
        if (orderId.isBlank()) return@withContext emptyList()
        try {
            val token = prefs.token ?: return@withContext emptyList()
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
        val token = prefs.token ?: return@withContext _earnings.value
        try {
            var response = apiService.getEarnings(
                apiKey = prefs.customSupabaseKey,
                bearerToken = "Bearer $token",
                partnerIdFilter = "eq.$partnerId"
            )
            if (response.code() == 401) {
                val refreshed = refreshSession()
                if (refreshed.isSuccess) {
                    val newToken = prefs.token.orEmpty()
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
        val token = prefs.token ?: return@withContext _cashLedger.value
        try {
            // Cash in hand is summed over the whole ledger, so fetch all rows, not one page.
            var response = apiService.getCashLedger(
                apiKey = prefs.customSupabaseKey,
                bearerToken = "Bearer $token",
                partnerIdFilter = "eq.$partnerId",
                limit = CASH_LEDGER_LIMIT
            )
            if (response.code() == 401) {
                val refreshed = refreshSession()
                if (refreshed.isSuccess) {
                    val newToken = prefs.token.orEmpty()
                    response = apiService.getCashLedger(
                        apiKey = prefs.customSupabaseKey,
                        bearerToken = "Bearer $newToken",
                        partnerIdFilter = "eq.$partnerId",
                        limit = CASH_LEDGER_LIMIT
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
        val token = prefs.token ?: return@withContext _paymentRequests.value
        try {
            var response = apiService.getPaymentRequests(
                apiKey = prefs.customSupabaseKey,
                bearerToken = "Bearer $token",
                partnerIdFilter = "eq.$partnerId"
            )
            if (response.code() == 401) {
                val refreshed = refreshSession()
                if (refreshed.isSuccess) {
                    val newToken = prefs.token.orEmpty()
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
        val token = prefs.token ?: return@withContext _notifications.value
        try {
            var response = apiService.getNotifications(
                apiKey = prefs.customSupabaseKey,
                bearerToken = "Bearer $token",
                userIdFilter = "eq.$userId"
            )
            if (response.code() == 401) {
                val refreshed = refreshSession()
                if (refreshed.isSuccess) {
                    val newToken = prefs.token.orEmpty()
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
        val token = prefs.token ?: return@withContext _reviews.value
        try {
            var response = apiService.getReviews(
                apiKey = prefs.customSupabaseKey,
                bearerToken = "Bearer $token",
                partnerIdFilter = "eq.$partnerId"
            )
            if (response.code() == 401) {
                val refreshed = refreshSession()
                if (refreshed.isSuccess) {
                    val newToken = prefs.token.orEmpty()
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
                val bankDeferred = async { fetchBankDetails() }
                awaitAll(
                    assignmentsDeferred,
                    earningsDeferred,
                    ledgerDeferred,
                    payoutsDeferred,
                    notifsDeferred,
                    reviewsDeferred,
                    bankDeferred
                )
            }
        } finally {
            _isDataLoading.value = false
        }
    }

    fun clearData() {
        stopRealtimeWatcher()
        OrderAlertNotifier.cancelAll(context)
        localStages.clear()
        handledAssignmentIds.clear()
        assignmentsPage = 0
        _canLoadMoreAssignments.value = true
        _dataFetchError.value = null
        _currentPartner.value = null
        _assignments.value = emptyList()
        _incomingAssignmentAlert.value = null
        _earnings.value = emptyList()
        _cashLedger.value = emptyList()
        _paymentRequests.value = emptyList()
        _bankDetails.value = null
        _notifications.value = emptyList()
        _reviews.value = emptyList()
        _lastUpdatedLocation.value = null
    }

    /**
     * Polls assignments every 20s while an approved partner is online. FCM pushes give the
     * instant alert; this is the safety net for missed pushes. Idempotent; started after login
     * and stopped on logout.
     */
    fun startRealtimeWatcher() {
        if (pollingJob?.isActive == true) return
        pollingJob = coroutineScope.launch {
            while (isActive) {
                delay(POLL_INTERVAL_MS)
                val partner = _currentPartner.value
                if (partner != null && partner.isOnline && partner.approvalStatus.equals("approved", ignoreCase = true)) {
                    try {
                        fetchAssignments()
                    } catch (e: Exception) {
                        Log.d("SupabaseRepo", "Assignment poll error: ${e.message}")
                    }
                }
            }
        }
    }

    fun stopRealtimeWatcher() {
        pollingJob?.cancel()
        pollingJob = null
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
            val response = apiService.getAppVersions(
                apiKey = prefs.customSupabaseKey,
                bearerToken = bearerOrAnon(),
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
            val response = apiService.getAppSetting(
                apiKey = prefs.customSupabaseKey,
                bearerToken = bearerOrAnon(),
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
        const val MIN_PAYOUT_AMOUNT = 100.0
        private val TERMINAL_STATUSES = setOf("delivered", "cancelled", "rejected")
        private const val POLL_INTERVAL_MS = 20_000L
        private const val CASH_LEDGER_LIMIT = 1000

        @Volatile
        private var INSTANCE: SupabaseRepository? = null

        fun getInstance(context: Context): SupabaseRepository {
            return INSTANCE ?: synchronized(this) {
                val appCtx = context.applicationContext
                val prefs = AppPreferences(appCtx)
                INSTANCE ?: SupabaseRepository(appCtx, prefs).also { INSTANCE = it }
            }
        }
    }
}
