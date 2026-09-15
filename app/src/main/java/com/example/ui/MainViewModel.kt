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

    private val _pickupPhotoUploadState = MutableStateFlow<UploadState>(UploadState.Idle)
    val pickupPhotoUploadState: StateFlow<UploadState> = _pickupPhotoUploadState.asStateFlow()

    private val _dropPhotoUploadState = MutableStateFlow<UploadState>(UploadState.Idle)
    val dropPhotoUploadState: StateFlow<UploadState> = _dropPhotoUploadState.asStateFlow()

    fun resetPickupPhotoUploadState(existingUrl: String? = null) {
        if (!existingUrl.isNullOrBlank()) {
            _pickupPhotoUploadState.value = UploadState.Success(existingUrl)
        } else {
            _pickupPhotoUploadState.value = UploadState.Idle
        }
    }

    fun resetDropPhotoUploadState(existingUrl: String? = null) {
        if (!existingUrl.isNullOrBlank()) {
            _dropPhotoUploadState.value = UploadState.Success(existingUrl)
        } else {
            _dropPhotoUploadState.value = UploadState.Idle
        }
    }

    private val _isActionLoading = MutableStateFlow(false)
    val isActionLoading: StateFlow<Boolean> = _isActionLoading.asStateFlow()

    private val _userMessage = MutableStateFlow<String?>(null)
    val userMessage: StateFlow<String?> = _userMessage.asStateFlow()

    val dataFetchError = repository.dataFetchError
    val isDataLoading = repository.isDataLoading
    val canLoadMoreDeliveries = repository.canLoadMoreAssignments
    val isLoadingMoreDeliveries = repository.isLoadingMoreAssignments

    val activeAssignments = assignments.map { list ->
        list.filter { it.status in listOf("accepted", "arrived_pickup", "picked_up", "out_for_delivery", "arrived_drop") }
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

    companion object {
        const val CURRENT_APP_VERSION = "1.0.0"

        fun compareVersions(a: String, b: String): Int {
            val pa = a.split('.').map { it.trim().toIntOrNull() ?: 0 }
            val pb = b.split('.').map { it.trim().toIntOrNull() ?: 0 }
            for (i in 0 until 3) {
                val valA = pa.getOrElse(i) { 0 }
                val valB = pb.getOrElse(i) { 0 }
                if (valA != valB) return valA - valB
            }
            return 0
        }
    }

    private val _isCheckingStartup = MutableStateFlow(true)
    val isCheckingStartup: StateFlow<Boolean> = _isCheckingStartup.asStateFlow()

    private val _blockingUpdateInfo = MutableStateFlow<AppVersionInfo?>(null)
    val blockingUpdateInfo: StateFlow<AppVersionInfo?> = _blockingUpdateInfo.asStateFlow()

    private val _isUpdateRequired = MutableStateFlow(false)
    val isUpdateRequired: StateFlow<Boolean> = _isUpdateRequired.asStateFlow()

    private val _maintenanceModeActive = MutableStateFlow(false)
    val maintenanceModeActive: StateFlow<Boolean> = _maintenanceModeActive.asStateFlow()

    private val _maintenanceMessage = MutableStateFlow<String?>(null)
    val maintenanceMessage: StateFlow<String?> = _maintenanceMessage.asStateFlow()

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
        runStartupChecks()
        checkInitialSession()
        loadCities()
    }

    fun runStartupChecks() {
        viewModelScope.launch {
            _isCheckingStartup.value = true
            try {
                val versionInfo = repository.checkAppVersion()
                if (versionInfo != null) {
                    val minVersion = versionInfo.minimumSupportedVersion
                    val isBelowMinimum = if (!minVersion.isNullOrBlank()) {
                        compareVersions(CURRENT_APP_VERSION, minVersion) < 0
                    } else false

                    if (isBelowMinimum || versionInfo.forceUpdate) {
                        _blockingUpdateInfo.value = versionInfo
                        _isUpdateRequired.value = true
                        _isCheckingStartup.value = false
                        return@launch
                    }
                }
                _isUpdateRequired.value = false
                _blockingUpdateInfo.value = null

                val settings = repository.checkMaintenanceMode()
                if (settings != null && settings.enabled) {
                    _maintenanceModeActive.value = true
                    _maintenanceMessage.value = settings.message
                        ?: "New orders are temporarily paused platform-wide. You can still complete any deliveries already assigned to you."
                } else {
                    _maintenanceModeActive.value = false
                    _maintenanceMessage.value = null
                }
            } catch (e: Exception) {
                Log.e("MainViewModel", "Error during runStartupChecks: ${e.message}")
            } finally {
                _isCheckingStartup.value = false
            }
        }
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
            repository.fetchAssignments(page = 0)
            _isActionLoading.value = false
            showMessage("Deliveries refreshed from server")
        }
    }

    fun loadMoreDeliveries() {
        viewModelScope.launch {
            repository.loadMoreAssignments()
        }
    }

    fun refreshAllData() {
        viewModelScope.launch {
            _isActionLoading.value = true
            runStartupChecks()
            repository.fetchAllBackendData()
            _isActionLoading.value = false
            showMessage("All data refreshed from server")
        }
    }

    fun signUpWithPhone(phone: String, password: String, onSent: (Boolean) -> Unit) {
        viewModelScope.launch {
            _isActionLoading.value = true
            val res = repository.signUpWithPhone(phone.trim(), password)
            _isActionLoading.value = false
            res.onSuccess {
                val formatted = repository.formatE164Phone(phone)
                showMessage("SMS verification code sent to $formatted")
                onSent(true)
            }.onFailure {
                showMessage(it.message ?: "Signup failed. Please try again.")
                onSent(false)
            }
        }
    }

    fun signInWithPhone(phone: String, password: String) {
        viewModelScope.launch {
            _isActionLoading.value = true
            val res = repository.signInWithPhonePassword(phone.trim(), password)
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
                showMessage(it.message ?: "Invalid mobile number or password.")
            }
        }
    }

    fun resendPhoneOtp(phone: String) {
        viewModelScope.launch {
            _isActionLoading.value = true
            val res = repository.resendPhoneOtp(phone.trim())
            _isActionLoading.value = false
            val formatted = repository.formatE164Phone(phone)
            res.onSuccess {
                showMessage("Verification OTP resent to $formatted")
            }.onFailure {
                showMessage("Failed to resend OTP: ${it.message}")
            }
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
                    _authUiState.value = AuthUiState.OnboardingRequired
                    showMessage("No application found on server. Please submit your KYC details.")
                }
            }.onFailure {
                showMessage("Could not refresh status: ${it.message}")
            }
        }
    }

    fun requestPhoneOtp(phone: String, onSent: (Boolean) -> Unit = {}) {
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
                    showMessage("Mobile number verified! Proceeding to KYC upload.")
                } else {
                    updateAuthStateForPartner(partner)
                    showMessage("Welcome back, ${partner.name}!")
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

    fun skipOtpAndProceedToOnboarding(email: String) {
        val cleanEmail = email.trim().ifBlank { "partner@sndmart.com" }
        prefs.userEmail = cleanEmail
        val currentUid = prefs.userId
        val validUid = if (!currentUid.isNullOrBlank() && currentUid.matches(Regex("^[0-9a-fA-F-]{36}$"))) {
            currentUid
        } else {
            val generated = java.util.UUID.nameUUIDFromBytes("sndmart_partner_$cleanEmail".toByteArray()).toString()
            prefs.userId = generated
            generated
        }
        _authUiState.value = AuthUiState.OnboardingRequired
        showMessage("Proceeding to Partner Registration KYC")
    }

    suspend fun getFreshAuthUserId(): String? {
        return repository.getFreshAuthUser().getOrNull() ?: prefs.userId
    }

    fun submitOnboarding(
        name: String,
        phone: String,
        vehicleType: String,
        vehicleNumber: String,
        cityId: String = "a5cd2248-f0f9-41b4-ad54-a31293a8257f",
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
                // ONLY navigate to "Application Submitted / Under Review" screen HERE, after confirming no error.
                _authUiState.value = AuthUiState.UnderReview(partner)
                showMessage("Application submitted successfully! Under review.")
            }.onFailure { err ->
                Log.e("MainViewModel", "Delivery partner submit error: ${err.message}", err)
                // Show real error reason, do NOT navigate to success screen
                showMessage(err.message ?: "Application submission failed. Please try again.")
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

    fun dismissIncomingAlert(assignmentId: String) {
        repository.dismissIncomingAlert(assignmentId)
    }

    fun acceptOrder(assignment: DeliveryAssignment) {
        viewModelScope.launch {
            _isActionLoading.value = true
            val res = repository.respondToAssignment(
                assignmentId = assignment.id,
                accept = true,
                assignmentSnapshot = assignment
            )
            _isActionLoading.value = false
            res.onSuccess {
                // Immediately close the "New Order Alert" popup and navigate to the active delivery screen
                repository.dismissIncomingAlert(assignment.id)
                val accepted = assignments.value.find { it.id == assignment.id }
                    ?: assignment.copy(status = "accepted", acceptedAt = repository.currentIsoTimestamp())
                _selectedAssignmentForDetail.value = accepted
                showMessage("Order accepted! Navigating to active delivery.")
            }.onFailure { error ->
                Log.e("MainViewModel", "Accept order error: ${error.message}", error)
                showMessage("Accept order error: ${error.message ?: "Failed to accept order"}")
            }
        }
    }

    fun rejectOrder(assignmentId: String) {
        viewModelScope.launch {
            _isActionLoading.value = true
            val res = repository.respondToAssignment(assignmentId, accept = false)
            _isActionLoading.value = false
            res.onSuccess {
                repository.dismissIncomingAlert(assignmentId)
                showMessage("Order declined")
            }.onFailure { error ->
                Log.e("MainViewModel", "Reject order error: ${error.message}", error)
                showMessage("Failed to decline order: ${error.message ?: "Unknown error"}")
            }
        }
    }

    fun respondToAssignment(assignmentId: String, accept: Boolean) {
        if (accept) {
            val target = assignments.value.find { it.id == assignmentId }
                ?: incomingAssignmentAlert.value?.takeIf { it.id == assignmentId }
                ?: DeliveryAssignment(id = assignmentId)
            acceptOrder(target)
        } else {
            rejectOrder(assignmentId)
        }
    }

    fun selectAssignmentById(assignmentId: String) {
        val found = assignments.value.find { it.id == assignmentId }
        if (found != null) {
            _selectedAssignmentForDetail.value = found
        } else {
            viewModelScope.launch {
                val fetched = repository.fetchAssignments()
                val target = fetched.find { it.id == assignmentId }
                if (target != null) {
                    _selectedAssignmentForDetail.value = target
                }
            }
        }
    }

    suspend fun fetchOrderItems(orderId: String): List<OrderItem> {
        return repository.fetchOrderItems(orderId)
    }

    suspend fun uploadPickupPhoto(
        assignmentId: String,
        imageBytes: ByteArray
    ): Result<String> {
        _pickupPhotoUploadState.value = UploadState.Uploading
        _isActionLoading.value = true
        return try {
            val uploadRes = repository.uploadProofPhoto(assignmentId, "pickup", imageBytes)
            if (uploadRes.isFailure) {
                val err = uploadRes.exceptionOrNull()?.message ?: "Upload failed. Please try again."
                Log.e("MainViewModel", "Pickup photo upload failed: $err")
                _pickupPhotoUploadState.value = UploadState.Error(err)
                _isActionLoading.value = false
                showMessage("Upload failed: $err")
                Result.failure(Exception(err))
            } else {
                val photoPath = uploadRes.getOrThrow()
                val updateRes = repository.updatePickupPhotoUrl(assignmentId, photoPath)
                _isActionLoading.value = false
                if (updateRes.isFailure) {
                    val err = updateRes.exceptionOrNull()?.message ?: "Failed to save photo to order record."
                    Log.e("MainViewModel", "Update pickup_photo_url failed: $err")
                    _pickupPhotoUploadState.value = UploadState.Error(err)
                    showMessage(err)
                    Result.failure(Exception(err))
                } else {
                    _selectedAssignmentForDetail.update { it?.copy(pickupPhotoUrl = photoPath) }
                    _pickupPhotoUploadState.value = UploadState.Success(photoPath)
                    showMessage("Pickup photo uploaded successfully!")
                    Result.success(photoPath)
                }
            }
        } catch (e: Exception) {
            val err = e.message ?: "Upload failed. Please try again."
            Log.e("MainViewModel", "Pickup photo upload exception: $err", e)
            _pickupPhotoUploadState.value = UploadState.Error(err)
            _isActionLoading.value = false
            showMessage(err)
            Result.failure(e)
        }
    }

    fun uploadPickupPhoto(
        assignmentId: String,
        imageBytes: ByteArray,
        onSuccess: (String) -> Unit
    ) {
        viewModelScope.launch {
            val res = uploadPickupPhoto(assignmentId, imageBytes)
            res.onSuccess(onSuccess)
        }
    }

    suspend fun uploadDeliveredPhoto(
        assignmentId: String,
        imageBytes: ByteArray
    ): Result<String> {
        _dropPhotoUploadState.value = UploadState.Uploading
        _isActionLoading.value = true
        return try {
            val uploadRes = repository.uploadProofPhoto(assignmentId, "delivered", imageBytes)
            if (uploadRes.isFailure) {
                val err = uploadRes.exceptionOrNull()?.message ?: "Upload failed. Please try again."
                Log.e("MainViewModel", "Delivered photo upload failed: $err")
                _dropPhotoUploadState.value = UploadState.Error(err)
                _isActionLoading.value = false
                showMessage("Upload failed: $err")
                Result.failure(Exception(err))
            } else {
                val photoPath = uploadRes.getOrThrow()
                val updateRes = repository.updateDeliveredPhotoUrl(assignmentId, photoPath)
                _isActionLoading.value = false
                if (updateRes.isFailure) {
                    val err = updateRes.exceptionOrNull()?.message ?: "Failed to save delivery proof."
                    Log.e("MainViewModel", "Update delivered_photo_url failed: $err")
                    _dropPhotoUploadState.value = UploadState.Error(err)
                    showMessage(err)
                    Result.failure(Exception(err))
                } else {
                    _selectedAssignmentForDetail.update { it?.copy(deliveredPhotoUrl = photoPath) }
                    _dropPhotoUploadState.value = UploadState.Success(photoPath)
                    showMessage("Delivery proof photo uploaded successfully!")
                    Result.success(photoPath)
                }
            }
        } catch (e: Exception) {
            val err = e.message ?: "Upload failed. Please try again."
            Log.e("MainViewModel", "Delivered photo upload exception: $err", e)
            _dropPhotoUploadState.value = UploadState.Error(err)
            _isActionLoading.value = false
            showMessage(err)
            Result.failure(e)
        }
    }

    fun uploadDeliveredPhoto(
        assignmentId: String,
        imageBytes: ByteArray,
        onSuccess: (String) -> Unit
    ) {
        viewModelScope.launch {
            val res = uploadDeliveredPhoto(assignmentId, imageBytes)
            res.onSuccess(onSuccess)
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
            }.onFailure { err ->
                Log.e("MainViewModel", "Confirm pickup error: ${err.message}", err)
                showMessage("Confirm pickup error: ${err.message ?: "Failed to mark picked up"}")
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
        val cached = prefs.fcmDeviceToken
        if (cached.isNotBlank()) {
            _fcmTokenState.value = cached
            viewModelScope.launch {
                repository.registerDeliveryPartnerFcmToken(cached)
            }
        }

        val availability = com.google.android.gms.common.GoogleApiAvailability.getInstance()
        val resultCode = availability.isGooglePlayServicesAvailable(app)
        val hasPlayServices = resultCode == com.google.android.gms.common.ConnectionResult.SUCCESS

        if (!hasPlayServices) {
            Log.w("MainViewModel", "Google Play Services not available (code: $resultCode). Using persistent local device token.")
            if (showToast) {
                showMessage("Device registered for order alerts: ${cached.take(16)}...")
            }
            return
        }

        try {
            FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
                try {
                    if (task.isSuccessful && !task.result.isNullOrBlank()) {
                        val token = task.result!!
                        Log.i("MainViewModel", "Fetched FCM token: $token")
                        prefs.fcmDeviceToken = token
                        _fcmTokenState.value = token
                        viewModelScope.launch {
                            val res = repository.registerDeliveryPartnerFcmToken(token)
                            if (res.isSuccess) {
                                Log.i("MainViewModel", "FCM token registered with device_tokens (firebase_project=native)")
                                if (showToast) {
                                    showMessage("Device registered for order alerts!")
                                }
                            } else {
                                Log.w("MainViewModel", "Failed to register FCM token: ${res.exceptionOrNull()?.message}")
                            }
                        }
                    } else {
                        Log.w("MainViewModel", "FCM token retrieval failed: ${task.exception?.message}")
                    }
                } catch (e: Exception) {
                    Log.w("MainViewModel", "FCM token handling error: ${e.message}")
                }
            }
        } catch (e: Exception) {
            Log.w("MainViewModel", "FirebaseMessaging exception: ${e.message}")
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
