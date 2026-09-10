package com.example.ui

import android.app.Application
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.MainActivity
import com.example.R
import com.example.data.local.AppDatabase
import com.example.data.local.AppPreferences
import com.example.data.location.LocationTracker
import com.example.data.model.*
import com.example.data.remote.SupabaseRepository
import com.example.service.SndmartFirebaseMessagingService
import com.example.ui.screens.order.IncomingOrderActivity
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

sealed interface AuthUiState {
    object Unauthenticated : AuthUiState
    object Loading : AuthUiState
    object OnboardingRequired : AuthUiState
    data class UnderReview(val partner: DeliveryPartner) : AuthUiState
    data class Rejected(val partner: DeliveryPartner, val reason: String) : AuthUiState
    data class Authenticated(val partner: DeliveryPartner) : AuthUiState
}

class MainViewModel(application: Application) : AndroidViewModel(application) {
    val prefs = AppPreferences(application)
    private val database = AppDatabase.getInstance(application)
    val repository = SupabaseRepository.getInstance(application)
    val locationTracker = LocationTracker(application, repository)

    private val _authUiState = MutableStateFlow<AuthUiState>(AuthUiState.Loading)
    val authUiState: StateFlow<AuthUiState> = _authUiState.asStateFlow()

    private val _fcmTokenState = MutableStateFlow(prefs.fcmDeviceToken)
    val fcmTokenState: StateFlow<String> = _fcmTokenState.asStateFlow()

    val currentPartner = repository.currentPartner
    val assignments = repository.assignments
    val incomingAssignmentAlert = repository.incomingAssignmentAlert
    val earnings = repository.earnings
    val cashLedger = repository.cashLedger
    val paymentRequests = repository.paymentRequests
    val notifications = repository.notifications
    val reviews = repository.reviews
    val currentLocation = locationTracker.currentLocation
    val isTrackingLocation = locationTracker.isTracking

    private val _selectedAssignmentForDetail = MutableStateFlow<DeliveryAssignment?>(null)
    val selectedAssignmentForDetail: StateFlow<DeliveryAssignment?> = _selectedAssignmentForDetail.asStateFlow()

    private val _isActionLoading = MutableStateFlow(false)
    val isActionLoading: StateFlow<Boolean> = _isActionLoading.asStateFlow()

    private val _userMessage = MutableStateFlow<String?>(null)
    val userMessage: StateFlow<String?> = _userMessage.asStateFlow()

    val dataFetchError = repository.dataFetchError
    val isDataLoading = repository.isDataLoading

    val activeAssignments = assignments.map { list ->
        list.filter { it.status in listOf("assigned", "accepted", "arrived_pickup", "picked_up", "out_for_delivery", "arrived_drop") }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val batches = assignments.map { list ->
        list.groupBy { it.batchId ?: it.id }.map { (batchId, batchAssignments) ->
            val first = batchAssignments.first()
            DeliveryBatch(
                id = batchId,
                batchCode = first.batchCode ?: "BATCH-${first.orderNumber.takeLast(4)}",
                status = if (batchAssignments.all { it.status == "delivered" }) "completed" else "active",
                totalOrders = batchAssignments.size,
                createdAt = first.createdAt,
                assignments = batchAssignments
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val todayEarningsSummary = earnings.map { list ->
        val todayTotal = list.sumOf { it.netEarning }
        val todayDelivered = list.size
        val pendingCount = list.count { it.status == "pending" }
        Triple(todayTotal, todayDelivered, pendingCount)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), Triple(0.0, 0, 0))

    val cashInHandBalance = cashLedger.map { list ->
        val collected = list.filter { it.entryType == "collected" }.sumOf { it.amount }
        val remitted = list.filter { it.entryType == "remitted" }.sumOf { it.amount }
        collected - remitted
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0.0)

    private val _cities = MutableStateFlow<List<City>>(emptyList())
    val cities: StateFlow<List<City>> = _cities.asStateFlow()

    init {
        repository.onSessionExpired = { reason ->
            viewModelScope.launch {
                locationTracker.stopTracking()
                repository.clearData()
                prefs.clearSession()
                _authUiState.value = AuthUiState.Unauthenticated
                showMessage(reason)
            }
        }
        checkInitialSession()
        loadCities()
    }

    fun loadCities() {
        viewModelScope.launch {
            val list = repository.fetchCities()
            _cities.value = list
        }
    }

    private fun checkInitialSession() {
        viewModelScope.launch {
            delay(300)
            val token = prefs.token
            val refreshToken = prefs.refreshToken
            val userId = prefs.userId
            if ((!token.isNullOrBlank() || !refreshToken.isNullOrBlank()) && !userId.isNullOrBlank()) {
                val sessionValid = repository.ensureValidSession().isSuccess
                if (!sessionValid) {
                    locationTracker.stopTracking()
                    repository.clearData()
                    prefs.clearSession()
                    _authUiState.value = AuthUiState.Unauthenticated
                    showMessage("Your session expired, please log in again")
                    return@launch
                }
                val partner = repository.checkPartnerProfile(userId, prefs.userEmail ?: "", prefs.userPhone ?: "")
                if (partner == null) {
                    _authUiState.value = AuthUiState.OnboardingRequired
                } else {
                    updateAuthStateForPartner(partner)
                }
            } else {
                _authUiState.value = AuthUiState.Unauthenticated
            }
        }
    }

    fun onAppResume() {
        viewModelScope.launch {
            if (_authUiState.value is AuthUiState.Authenticated || _authUiState.value is AuthUiState.UnderReview) {
                val sessionValid = repository.ensureValidSession().isSuccess
                if (!sessionValid) {
                    locationTracker.stopTracking()
                    repository.clearData()
                    prefs.clearSession()
                    _authUiState.value = AuthUiState.Unauthenticated
                    showMessage("Your session expired, please log in again")
                } else {
                    repository.fetchAllBackendData()
                }
            }
        }
    }

    private fun updateAuthStateForPartner(partner: DeliveryPartner) {
        fetchAndRegisterFcmToken(showToast = false)
        when (partner.approvalStatus.lowercase()) {
            "pending" -> {
                _authUiState.value = AuthUiState.UnderReview(partner)
                loadAllBackendData()
            }
            "rejected" -> {
                _authUiState.value = AuthUiState.Rejected(partner, partner.rejectionReason ?: "Application could not be approved at this time.")
            }
            else -> {
                _authUiState.value = AuthUiState.Authenticated(partner)
                if (partner.isOnline) {
                    locationTracker.startTracking()
                }
                loadAllBackendData()
            }
        }
    }

    fun loadAllBackendData() {
        viewModelScope.launch {
            repository.fetchAllBackendData()
        }
    }

    fun refreshDeliveries() {
        viewModelScope.launch {
            _isActionLoading.value = true
            repository.fetchAssignments()
            _isActionLoading.value = false
            showMessage("Deliveries refreshed from server")
        }
    }

    fun refreshAllData() {
        viewModelScope.launch {
            _isActionLoading.value = true
            repository.fetchAllBackendData()
            _isActionLoading.value = false
            showMessage("All data refreshed from server")
        }
    }

    fun signUpWithEmail(email: String, password: String, onSent: (Boolean) -> Unit) {
        viewModelScope.launch {
            _isActionLoading.value = true
            val res = repository.signUpWithEmail(email.trim(), password)
            _isActionLoading.value = false
            res.onSuccess {
                showMessage("Verification code sent to $email")
                onSent(true)
            }.onFailure {
                showMessage(it.message ?: "Signup failed. Please try again.")
                onSent(false)
            }
        }
    }

    fun verifyEmailOtp(email: String, otp: String) {
        viewModelScope.launch {
            _isActionLoading.value = true
            val res = repository.verifyEmailOtp(email.trim(), otp.trim())
            _isActionLoading.value = false
            res.onSuccess { partner ->
                if (partner == null) {
                    _authUiState.value = AuthUiState.OnboardingRequired
                    showMessage("Email verified! Please complete your partner KYC.")
                } else {
                    updateAuthStateForPartner(partner)
                    showMessage("Welcome back, ${partner.name}!")
                }
            }.onFailure {
                showMessage(it.message ?: "Invalid OTP. Please try again.")
            }
        }
    }

    fun signInWithEmail(email: String, password: String) {
        viewModelScope.launch {
            _isActionLoading.value = true
            val res = repository.signInWithEmailPassword(email.trim(), password)
            _isActionLoading.value = false
            res.onSuccess { partner ->
                if (partner == null) {
                    _authUiState.value = AuthUiState.OnboardingRequired
                    showMessage("Login successful! Please complete your KYC verification.")
                } else {
                    updateAuthStateForPartner(partner)
                    showMessage("Welcome back, ${partner.name}!")
                }
            }.onFailure {
                showMessage(it.message ?: "Invalid email or password.")
            }
        }
    }

    fun resendEmailOtp(email: String) {
        viewModelScope.launch {
            _isActionLoading.value = true
            val res = repository.resendEmailOtp(email.trim())
            _isActionLoading.value = false
            res.onSuccess {
                showMessage("Verification OTP resent to $email")
            }.onFailure {
                showMessage("Failed to resend OTP: ${it.message}")
            }
        }
    }

    fun forgotPassword(email: String) {
        viewModelScope.launch {
            _isActionLoading.value = true
            val res = repository.resetPasswordForEmail(email.trim())
            _isActionLoading.value = false
            res.onSuccess {
                showMessage("Password reset instructions sent to $email")
            }.onFailure {
                showMessage("Failed to request password reset: ${it.message}")
            }
        }
    }

    fun refreshApprovalStatus() {
        viewModelScope.launch {
            _isActionLoading.value = true
            val res = repository.refreshApprovalStatus()
            _isActionLoading.value = false
            res.onSuccess { partner ->
                if (partner != null) {
                    updateAuthStateForPartner(partner)
                    if (partner.approvalStatus.equals("approved", ignoreCase = true)) {
                        showMessage("Account approved! Welcome to Sndmart.")
                    } else if (partner.approvalStatus.equals("rejected", ignoreCase = true)) {
                        showMessage("Account rejected: ${partner.rejectionReason ?: ""}")
                    } else {
                        showMessage("Status: Application still under review.")
                    }
                } else {
                    showMessage("Status check complete: Profile under verification.")
                }
            }.onFailure {
                showMessage("Could not refresh status: ${it.message}")
            }
        }
    }

    fun requestPhoneOtp(phone: String, onSent: (Boolean) -> Unit) {
        viewModelScope.launch {
            _isActionLoading.value = true
            val res = repository.requestOtp(phone)
            _isActionLoading.value = false
            onSent(res.isSuccess)
            if (res.isSuccess) {
                showMessage("OTP sent to $phone")
            }
        }
    }

    fun verifyPhoneOtp(phone: String, otp: String) {
        viewModelScope.launch {
            _isActionLoading.value = true
            val res = repository.verifyOtp(phone, otp)
            _isActionLoading.value = false
            res.onSuccess { partner ->
                if (partner == null) {
                    _authUiState.value = AuthUiState.OnboardingRequired
                } else {
                    updateAuthStateForPartner(partner)
                }
            }.onFailure {
                showMessage("Verification error: ${it.message}")
            }
        }
    }

    fun retryOnboarding() {
        _authUiState.value = AuthUiState.OnboardingRequired
    }

    suspend fun uploadKycDocument(documentType: String, bytes: ByteArray): Result<String> {
        return repository.uploadKycDocument(documentType, bytes)
    }

    suspend fun getFreshAuthUserId(): String? {
        return repository.getFreshAuthUser().getOrNull() ?: prefs.userId
    }

    fun submitOnboarding(
        name: String,
        phone: String,
        vehicleType: String,
        vehicleNumber: String,
        cityId: String = "c1111111-1111-1111-1111-111111111111",
        aadharUrl: String?,
        licenceUrl: String?
    ) {
        viewModelScope.launch {
            _isActionLoading.value = true
            val res = repository.submitOnboarding(
                name = name,
                phone = phone,
                vehicleType = vehicleType,
                vehicleNumber = vehicleNumber,
                cityId = cityId,
                aadharUrl = aadharUrl,
                licenceUrl = licenceUrl
            )
            _isActionLoading.value = false
            res.onSuccess { partner ->
                _authUiState.value = AuthUiState.UnderReview(partner)
                showMessage("KYC submitted successfully! Application under review.")
            }.onFailure {
                showMessage("Onboarding failed: ${it.message}")
            }
        }
    }

    fun requestCashRemittance(amount: Double, note: String? = null) {
        viewModelScope.launch {
            _isActionLoading.value = true
            val res = repository.requestCashRemittance(amount, note)
            _isActionLoading.value = false
            res.onSuccess {
                showMessage("Remittance request for ₹${amount.toInt()} submitted to darkstore hub!")
            }.onFailure {
                showMessage(it.message ?: "Failed to submit remittance request")
            }
        }
    }

    fun goOnlineWithLocation() {
        viewModelScope.launch {
            _isActionLoading.value = true
            // 1. Immediately get current GPS coordinates
            val (lat, lng) = locationTracker.getCurrentGpsLocation()
            Log.i("MainViewModel", "Going online with GPS coordinates: lat=$lat, lng=$lng")
            
            // 2. Immediately write location, timestamp, and is_online to database
            val res = repository.setOnline(isOnline = true, lat = lat, lng = lng)
            _isActionLoading.value = false
            res.onSuccess {
                val current = _authUiState.value
                if (current is AuthUiState.Authenticated) {
                    _authUiState.value = AuthUiState.Authenticated(
                        current.partner.copy(
                            isOnline = true,
                            isAvailable = true,
                            latitude = lat,
                            longitude = lng,
                            currentLat = lat,
                            currentLng = lng
                        )
                    )
                }
                // 3. Start continuous tracking (every 20s)
                locationTracker.startTracking(updateIntervalSeconds = 20L)
                showMessage("You are now ONLINE. Live GPS location active.")
            }.onFailure { err ->
                showMessage("Failed to go online: ${err.message ?: "Network error"}")
            }
        }
    }

    fun toggleOffline() {
        viewModelScope.launch {
            _isActionLoading.value = true
            locationTracker.stopTracking()
            val res = repository.setOnline(isOnline = false)
            _isActionLoading.value = false
            res.onSuccess {
                val current = _authUiState.value
                if (current is AuthUiState.Authenticated) {
                    _authUiState.value = AuthUiState.Authenticated(current.partner.copy(isOnline = false))
                }
                showMessage("You are now OFFLINE")
            }.onFailure { err ->
                showMessage("Failed to go offline: ${err.message ?: "Network error"}")
            }
        }
    }

    fun onLocationPermissionDenied() {
        showMessage("Location access is required to receive delivery orders")
    }

    fun toggleOnline(online: Boolean) {
        if (online) {
            if (locationTracker.hasLocationPermission()) {
                goOnlineWithLocation()
            } else {
                onLocationPermissionDenied()
            }
        } else {
            toggleOffline()
        }
    }

    fun toggleAvailable(available: Boolean) {
        viewModelScope.launch {
            val res = repository.setAvailability(available)
            res.onSuccess {
                val current = _authUiState.value
                if (current is AuthUiState.Authenticated) {
                    _authUiState.value = AuthUiState.Authenticated(current.partner.copy(isAvailable = available))
                }
                showMessage(if (available) "Status: AVAILABLE for deliveries" else "Status: BUSY (Paused new orders)")
            }
        }
    }

    fun respondToAssignment(assignmentId: String, accept: Boolean) {
        viewModelScope.launch {
            val res = repository.respondToAssignment(assignmentId, accept)
            res.onSuccess {
                if (accept) {
                    val assigned = assignments.value.find { it.id == assignmentId }
                    _selectedAssignmentForDetail.value = assigned
                }
            }
        }
    }

    fun markPickedUp(assignmentId: String, photoUrl: String) {
        viewModelScope.launch {
            _isActionLoading.value = true
            val res = repository.markPickedUp(assignmentId, photoUrl)
            _isActionLoading.value = false
            res.onSuccess {
                _selectedAssignmentForDetail.update { it?.copy(status = "picked_up", pickupPhotoUrl = photoUrl) }
                showMessage("Order marked as Picked Up!")
            }
        }
    }

    fun startDelivery(assignmentId: String) {
        viewModelScope.launch {
            val res = repository.startDelivery(assignmentId)
            res.onSuccess {
                _selectedAssignmentForDetail.update { it?.copy(status = "out_for_delivery") }
                showMessage("Out for Delivery! Head to customer location.")
            }
        }
    }

    fun arriveAtDrop(assignmentId: String) {
        viewModelScope.launch {
            val res = repository.arriveAtDrop(assignmentId)
            res.onSuccess {
                _selectedAssignmentForDetail.update { it?.copy(status = "arrived_drop") }
                showMessage("Arrived at drop location. Enter delivery OTP.")
            }
        }
    }

    fun completeDelivery(assignmentId: String, otp: String, photoUrl: String, onCompleted: (Boolean) -> Unit) {
        viewModelScope.launch {
            _isActionLoading.value = true
            val res = repository.completeDelivery(assignmentId, otp, photoUrl)
            _isActionLoading.value = false
            res.onSuccess {
                _selectedAssignmentForDetail.value = null
                showMessage("Order Delivered successfully! ₹ credited.")
                onCompleted(true)
            }.onFailure {
                showMessage(it.message ?: "Failed to verify OTP. Please try again.")
                onCompleted(false)
            }
        }
    }

    fun logCashCollected(orderId: String, orderNumber: String, amount: Double, notes: String) {
        viewModelScope.launch {
            repository.logCashCollected(orderId, orderNumber, amount, notes)
            showMessage("Recorded ₹${amount.toInt()} cash collected into ledger")
        }
    }

    fun requestPayout(amount: Double) {
        viewModelScope.launch {
            _isActionLoading.value = true
            val res = repository.requestPayout(amount)
            _isActionLoading.value = false
            res.onSuccess {
                showMessage("Payout request of ₹${amount.toInt()} submitted!")
            }.onFailure {
                showMessage(it.message ?: "Failed to submit payout request")
            }
        }
    }

    fun registerFcmToken(token: String) {
        viewModelScope.launch {
            prefs.fcmDeviceToken = token
            _fcmTokenState.value = token
            repository.registerDeviceToken(token)
            showMessage("Push token registered: ${token.take(12)}...")
        }
    }

    fun fetchAndRegisterFcmToken(showToast: Boolean = false) {
        val app = getApplication<Application>()
        try {
            if (com.example.util.DeviceUtils.isEmulator()) {
                Log.d("MainViewModel", "Emulator detected: Using persistent local device token for delivery assignments & alerts")
                val fallback = prefs.fcmDeviceToken
                _fcmTokenState.value = fallback
                viewModelScope.launch {
                    repository.registerDeviceToken(fallback)
                    if (showToast) {
                        showMessage("Device token registered: ${fallback.take(16)}...")
                    }
                }
                return
            }

            val availability = com.google.android.gms.common.GoogleApiAvailability.getInstance()
            val resultCode = availability.isGooglePlayServicesAvailable(app)
            val hasPlayServices = resultCode == com.google.android.gms.common.ConnectionResult.SUCCESS

            if (!hasPlayServices) {
                Log.w("MainViewModel", "Google Play Services not available (code: $resultCode). Using persistent local device token.")
                val fallback = prefs.fcmDeviceToken
                _fcmTokenState.value = fallback
                viewModelScope.launch {
                    repository.registerDeviceToken(fallback)
                    if (showToast) {
                        showMessage("Device token registered: ${fallback.take(16)}...")
                    }
                }
                return
            }

            FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
                try {
                    if (task.isSuccessful && !task.result.isNullOrBlank()) {
                        val token = task.result!!
                        Log.d("MainViewModel", "Fetched FCM token: $token")
                        prefs.fcmDeviceToken = token
                        _fcmTokenState.value = token
                        viewModelScope.launch {
                            repository.registerDeviceToken(token)
                            if (showToast) {
                                showMessage("FCM token registered with backend!")
                            }
                        }
                    } else {
                        Log.w("MainViewModel", "FCM token retrieval not available: ${task.exception?.message}")
                        val fallback = prefs.fcmDeviceToken
                        if (fallback.isNotBlank()) {
                            _fcmTokenState.value = fallback
                            viewModelScope.launch {
                                repository.registerDeviceToken(fallback)
                            }
                        }
                        if (showToast) {
                            showMessage("Using active local device token: ${fallback.take(16)}...")
                        }
                    }
                } catch (e: Exception) {
                    Log.w("MainViewModel", "FCM token handling error: ${e.message}")
                    val fallback = prefs.fcmDeviceToken
                    _fcmTokenState.value = fallback
                }
            }
        } catch (e: Exception) {
            Log.e("MainViewModel", "FirebaseMessaging exception: ${e.message}", e)
            val fallback = prefs.fcmDeviceToken
            _fcmTokenState.value = fallback
            viewModelScope.launch {
                repository.registerDeviceToken(fallback)
            }
            if (showToast) {
                showMessage("Device token: ${fallback.take(16)}...")
            }
        }
    }

    fun triggerTestIncomingOrderAlert(context: Context) {
        val intent = Intent(context, IncomingOrderActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(IncomingOrderActivity.EXTRA_ASSIGNMENT_ID, "test_fcm_${System.currentTimeMillis().toString().takeLast(6)}")
            putExtra(IncomingOrderActivity.EXTRA_ORDER_ID, "ORD-FCM-${(1000..9999).random()}")
            putExtra(IncomingOrderActivity.EXTRA_TITLE, "Incoming Delivery Order!")
            putExtra(IncomingOrderActivity.EXTRA_MESSAGE, "Darkstore Dispatch: Koramangala Hub")
            putExtra(IncomingOrderActivity.EXTRA_STORE_NAME, "Sndmart DarkStore #104")
            putExtra(IncomingOrderActivity.EXTRA_STORE_ADDRESS, "80 Feet Road, 4th Block, Koramangala")
            putExtra(IncomingOrderActivity.EXTRA_CUSTOMER_ADDRESS, "Tower 3, Flat 901, Prestige Oasis, Bengaluru")
            putExtra(IncomingOrderActivity.EXTRA_PAYMENT_MODE, "ONLINE_PREPAID")
            putExtra(IncomingOrderActivity.EXTRA_TOTAL_AMOUNT, 520.0)
            putExtra(IncomingOrderActivity.EXTRA_DELIVERY_FEE, 60.0)
        }
        context.startActivity(intent)
        showMessage("Simulated FCM Full-Screen Order Alert triggered!")
    }

    fun sendTestNotification(context: Context) {
        try {
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val intent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val pendingIntent = PendingIntent.getActivity(
                context,
                1001,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val builder = NotificationCompat.Builder(
                context,
                SndmartFirebaseMessagingService.CHANNEL_DELIVERY_UPDATES
            )
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle("Sndmart Push Notification")
                .setContentText("FCM setup verified! Real-time alerts and orders are active.")
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)

            notificationManager.notify(1001, builder.build())
            repository.addNotification("FCM Test Notification", "Push notifications are operational on this device.", "info")
            showMessage("Test notification posted to notification drawer!")
        } catch (e: Exception) {
            Log.e("MainViewModel", "Failed to send test notification: ${e.message}", e)
            showMessage("Notification error: ${e.message}")
        }
    }

    fun upsertBankDetails(
        method: String,
        holderName: String,
        accountNumber: String,
        ifsc: String,
        bankName: String,
        upiId: String?
    ) {
        viewModelScope.launch {
            _isActionLoading.value = true
            val res = repository.upsertBankDetails(method, holderName, accountNumber, ifsc, bankName, upiId)
            _isActionLoading.value = false
            res.onSuccess {
                val current = _authUiState.value
                if (current is AuthUiState.Authenticated) {
                    _authUiState.value = AuthUiState.Authenticated(
                        current.partner.copy(
                            payoutMethod = method,
                            accountHolderName = holderName,
                            accountNumber = accountNumber,
                            ifscCode = ifsc,
                            bankName = bankName,
                            upiId = upiId
                        )
                    )
                }
                showMessage("Bank details saved successfully")
            }.onFailure {
                showMessage("Failed to save bank details: ${it.message}")
            }
        }
    }

    fun selectAssignmentForDetail(assignment: DeliveryAssignment?) {
        _selectedAssignmentForDetail.value = assignment
    }

    fun dismissIncomingAlert() {
        repository.dismissIncomingAlert()
    }

    fun updateSupabaseCredentials(url: String, key: String) {
        repository.updateSupabaseConfig(url, key)
        showMessage("Supabase endpoints updated!")
    }

    fun showMessage(msg: String) {
        _userMessage.value = msg
    }

    fun clearUserMessage() {
        _userMessage.value = null
    }

    fun refreshData() {
        viewModelScope.launch {
            _isActionLoading.value = true
            repository.fetchAllBackendData()
            _isActionLoading.value = false
        }
    }

    fun signOut() {
        viewModelScope.launch {
            try {
                repository.deactivateDeviceToken()
            } catch (e: Exception) {
                // Ignore failure on logout
            }
            locationTracker.stopTracking()
            repository.clearData()
            prefs.clearSession()
            _authUiState.value = AuthUiState.Unauthenticated
            showMessage("Signed out successfully")
        }
    }
}
