package com.example.ui

import android.app.Application
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import android.app.NotificationChannel
import android.media.AudioAttributes
import android.os.Build
import android.provider.Settings
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.BuildConfig
import com.example.MainActivity
import com.example.R
import com.example.data.local.AppPreferences
import com.example.data.location.LocationTracker
import com.example.data.model.*
import com.example.data.remote.SupabaseRepository
import com.example.service.OrderAlert
import com.example.service.OrderAlertNotifier
import com.example.service.SndmartFirebaseMessagingService
import com.example.util.availableForPayout
import com.example.util.UpdateDecision
import com.example.util.cashInHand
import com.example.util.decideUpdate
import com.example.util.pendingHandover
import com.example.util.todayEarnings
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
    val repository = SupabaseRepository.getInstance(application)
    val locationTracker = LocationTracker.getInstance(application)

    private val _authUiState = MutableStateFlow<AuthUiState>(AuthUiState.Loading)
    val authUiState: StateFlow<AuthUiState> = _authUiState.asStateFlow()

    private val _fcmTokenState = MutableStateFlow(prefs.fcmDeviceToken)
    val fcmTokenState: StateFlow<String> = _fcmTokenState.asStateFlow()

    private val _isFcmOnline = MutableStateFlow(prefs.isFcmRegistered)
    val isFcmOnline: StateFlow<Boolean> = _isFcmOnline.asStateFlow()

    val currentPartner = repository.currentPartner
    val assignments = repository.assignments
    val incomingAssignmentAlert = repository.incomingAssignmentAlert
    val earnings = repository.earnings
    val cashLedger = repository.cashLedger
    val paymentRequests = repository.paymentRequests
    val bankDetails = repository.bankDetails
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

    /** (today's net earnings, today's deliveries, today's pending earnings) */
    val todayEarningsSummary = earnings.map { list ->
        val summary = todayEarnings(list)
        Triple(summary.total, summary.deliveries, summary.pending)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), Triple(0.0, 0, 0))

    val availablePayoutBalance = combine(earnings, paymentRequests) { e, r -> availableForPayout(e, r) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0.0)

    val cashInHandBalance = cashLedger.map { cashInHand(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0.0)

    val pendingHandoverBalance = cashLedger.map { pendingHandover(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0.0)

    companion object {
        const val CURRENT_APP_VERSION = BuildConfig.VERSION_NAME
        private const val LOCATION_STALE_AFTER_MS = 3 * 60 * 1000L

        fun compareVersions(a: String, b: String): Int = com.example.util.compareVersions(a, b)
    }

    private val _isCheckingStartup = MutableStateFlow(true)
    val isCheckingStartup: StateFlow<Boolean> = _isCheckingStartup.asStateFlow()

    private val _blockingUpdateInfo = MutableStateFlow<AppVersionInfo?>(null)
    val blockingUpdateInfo: StateFlow<AppVersionInfo?> = _blockingUpdateInfo.asStateFlow()

    private val _isUpdateRequired = MutableStateFlow(false)
    val isUpdateRequired: StateFlow<Boolean> = _isUpdateRequired.asStateFlow()

    /**
     * The server set this partner offline (location stopped updating for 30 min) while the
     * partner had chosen to be online. Shows "You were set offline..." with a Go online button.
     */
    private val _autoOfflineNotice = MutableStateFlow(false)
    val autoOfflineNotice: StateFlow<Boolean> = _autoOfflineNotice.asStateFlow()

    fun dismissAutoOfflineNotice() {
        prefs.onlineIntent = false
        _autoOfflineNotice.value = false
    }

    @Volatile
    private var changingOnline = false

    /**
     * Non-null while online and the server has not accepted a location for 3 minutes; the
     * value says why, for the "Location is not updating" banner. The server only offers
     * orders to partners whose location is under 5 minutes old.
     */
    val locationNotUpdating: StateFlow<LocationTracker.Problem?> = combine(
        locationTracker.isTracking,
        locationTracker.lastPushOkElapsed,
        locationTracker.problem,
        flow { while (true) { emit(Unit); delay(15_000L) } }
    ) { tracking, lastOk, problem, _ ->
        if (!tracking) return@combine null
        val since = maxOf(lastOk, locationTracker.trackingStartedElapsed)
        val stale = android.os.SystemClock.elapsedRealtime() - since > LOCATION_STALE_AFTER_MS
        if (stale) (if (problem == LocationTracker.Problem.NONE) LocationTracker.Problem.NO_FIX else problem) else null
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    /**
     * Keeps the UI and tracking in line with the server row: "Online" is shown only when the
     * server says so. Runs for every server read of the partner (startup, resume, polling).
     */
    private var inactiveNoticeShown = false

    private fun reconcileWithServer(partner: DeliveryPartner) {
        val current = _authUiState.value as? AuthUiState.Authenticated ?: return
        if (changingOnline) return
        if (!partner.isActive && !inactiveNoticeShown) {
            inactiveNoticeShown = true
            showMessage("Your account has been deactivated by Sndmart. Please contact your city admin.")
        }
        // A deactivated partner is never treated as online, whatever the row says.
        if (partner.isOnline && partner.isActive) {
            _autoOfflineNotice.value = false
            if (!locationTracker.isTracking.value && locationTracker.hasLocationPermission()) {
                locationTracker.startTracking()
            }
        } else {
            if (locationTracker.isTracking.value) locationTracker.stopTracking()
            if (prefs.onlineIntent) _autoOfflineNotice.value = true
        }
        val online = partner.isOnline && partner.isActive
        if (current.partner.isOnline != online || current.partner.isAvailable != partner.isAvailable ||
            current.partner.isActive != partner.isActive
        ) {
            _authUiState.value = AuthUiState.Authenticated(
                current.partner.copy(isOnline = online, isAvailable = partner.isAvailable, isActive = partner.isActive)
            )
        }
    }

    /** Set when a newer build exists but is not mandatory; the partner can choose "Later". */
    private val _optionalUpdateInfo = MutableStateFlow<AppVersionInfo?>(null)
    val optionalUpdateInfo: StateFlow<AppVersionInfo?> = _optionalUpdateInfo.asStateFlow()
    private var dismissedOptionalVersion: String? = null

    fun dismissOptionalUpdate() {
        dismissedOptionalVersion = _optionalUpdateInfo.value?.latestVersion
        _optionalUpdateInfo.value = null
    }

    private val _maintenanceModeActive = MutableStateFlow(false)
    val maintenanceModeActive: StateFlow<Boolean> = _maintenanceModeActive.asStateFlow()

    private val _maintenanceMessage = MutableStateFlow<String?>(null)
    val maintenanceMessage: StateFlow<String?> = _maintenanceMessage.asStateFlow()

    private val _cities = MutableStateFlow<List<City>>(emptyList())
    val cities: StateFlow<List<City>> = _cities.asStateFlow()

    init {
        repository.onSessionExpired = { reason ->
            viewModelScope.launch { endSession(reason) }
        }
        runStartupChecks()
        checkInitialSession()
        loadCities()
        viewModelScope.launch {
            repository.currentPartner.filterNotNull().collect { reconcileWithServer(it) }
        }
    }

    fun runStartupChecks() {
        viewModelScope.launch {
            _isCheckingStartup.value = true
            try {
                // A failed check (offline) keeps the previous decision instead of unblocking.
                val versionInfo = repository.checkAppVersion()
                if (versionInfo != null) {
                    val decision = decideUpdate(
                        installedVersion = CURRENT_APP_VERSION,
                        minimumSupportedVersion = versionInfo.minimumSupportedVersion,
                        latestVersion = versionInfo.latestVersion,
                        forceUpdate = versionInfo.forceUpdate
                    )
                    _blockingUpdateInfo.value = versionInfo.takeIf { decision == UpdateDecision.REQUIRED }
                    _isUpdateRequired.value = decision == UpdateDecision.REQUIRED
                    _optionalUpdateInfo.value = versionInfo.takeIf {
                        decision == UpdateDecision.OPTIONAL && it.latestVersion != dismissedOptionalVersion
                    }
                    if (decision == UpdateDecision.REQUIRED) return@launch
                }

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

    fun loadCities(forceRefresh: Boolean = false) {
        viewModelScope.launch {
            val list = repository.fetchCities(forceRefresh = forceRefresh)
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
                    endSession("Your session expired, please log in again")
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
        // Picks up a force update the admin enabled while the app was in the background.
        runStartupChecks()
        viewModelScope.launch {
            if (_authUiState.value is AuthUiState.Authenticated || _authUiState.value is AuthUiState.UnderReview) {
                val sessionValid = repository.ensureValidSession().isSuccess
                if (!sessionValid) {
                    endSession("Your session expired, please log in again")
                } else {
                    // Read the own row first: the server may have set us offline meanwhile.
                    repository.fetchOwnPartnerRow()
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
                if (partner.isOnline && partner.isActive) {
                    if (locationTracker.hasLocationPermission()) {
                        locationTracker.startTracking()
                    } else {
                        // Online on the server but we cannot share location: go offline so
                        // dispatch does not assign orders to a partner it cannot see.
                        toggleOffline()
                    }
                }
                repository.startRealtimeWatcher()
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

    fun requestPasswordReset(phone: String, onSent: (Boolean) -> Unit) {
        viewModelScope.launch {
            _isActionLoading.value = true
            val res = repository.requestPasswordResetOtp(phone.trim())
            _isActionLoading.value = false
            res.onSuccess {
                showMessage("Reset code sent to ${repository.formatE164Phone(phone)}")
            }.onFailure {
                showMessage(it.message ?: "Could not send reset code")
            }
            onSent(res.isSuccess)
        }
    }

    fun resetPasswordWithOtp(phone: String, otp: String, newPassword: String, onDone: (Boolean) -> Unit) {
        viewModelScope.launch {
            _isActionLoading.value = true
            val res = repository.resetPasswordWithOtp(phone.trim(), otp.trim(), newPassword)
            _isActionLoading.value = false
            res.onSuccess { partner ->
                showMessage("Password updated. You are now signed in.")
                if (partner == null) _authUiState.value = AuthUiState.OnboardingRequired
                else updateAuthStateForPartner(partner)
            }.onFailure {
                showMessage(it.message ?: "Could not reset password")
            }
            onDone(res.isSuccess)
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

    suspend fun getFreshAuthUserId(): String? {
        return repository.getFreshAuthUser().getOrNull() ?: prefs.userId
    }

    fun submitOnboarding(
        name: String,
        phone: String,
        vehicleType: String,
        vehicleNumber: String,
        cityId: String,
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
                showMessage("Cash handover of ₹${formatRupees(amount)} sent. Waiting for hub confirmation.")
            }.onFailure {
                showMessage(it.message ?: "Failed to submit remittance request")
            }
        }
    }

    fun goOnlineWithLocation() {
        viewModelScope.launch {
            changingOnline = true
            _isActionLoading.value = true
            // Dispatch assigns by distance, so a real GPS fix is required to go online.
            val fix = locationTracker.getCurrentGpsLocation()
            if (fix == null) {
                _isActionLoading.value = false
                changingOnline = false
                showMessage("Unable to get your GPS location. Turn on location services and try again.")
                return@launch
            }
            val (lat, lng) = fix
            val res = repository.setOnline(isOnline = true, lat = lat, lng = lng)
            _isActionLoading.value = false
            changingOnline = false
            res.onSuccess {
                prefs.onlineIntent = true
                _autoOfflineNotice.value = false
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
                locationTracker.startTracking()
                showMessage("You are now ONLINE. Live GPS location active.")
            }.onFailure { err ->
                showMessage(err.message ?: "Failed to go online")
            }
        }
    }

    fun toggleOffline() {
        viewModelScope.launch {
            changingOnline = true
            _isActionLoading.value = true
            locationTracker.stopTracking()
            prefs.onlineIntent = false
            _autoOfflineNotice.value = false
            val res = repository.setOnline(isOnline = false)
            _isActionLoading.value = false
            changingOnline = false
            res.onSuccess {
                val current = _authUiState.value
                if (current is AuthUiState.Authenticated) {
                    _authUiState.value = AuthUiState.Authenticated(current.partner.copy(isOnline = false))
                }
                showMessage("You are now OFFLINE")
            }.onFailure { err ->
                showMessage(err.message ?: "Failed to go offline")
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
            }.onFailure {
                showMessage(it.message ?: "Could not change availability")
            }
        }
    }

    fun dismissIncomingAlert(assignmentId: String) {
        repository.dismissIncomingAlert(assignmentId)
    }

    fun acceptOrder(assignment: DeliveryAssignment) {
        OrderAlertNotifier.cancel(getApplication(), assignment.id)
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
                showMessage(error.message ?: "Failed to accept order")
            }
        }
    }

    /** The offer card's countdown reached zero. Silent: no message for the partner. */
    fun onOfferTimeout(assignmentId: String) {
        viewModelScope.launch { repository.declineExpiredOffer(assignmentId) }
    }

    fun rejectOrder(assignmentId: String) {
        OrderAlertNotifier.cancel(getApplication(), assignmentId)
        viewModelScope.launch {
            _isActionLoading.value = true
            val res = repository.respondToAssignment(assignmentId, accept = false)
            _isActionLoading.value = false
            res.onSuccess {
                repository.dismissIncomingAlert(assignmentId)
                showMessage("Order declined")
            }.onFailure { error ->
                Log.e("MainViewModel", "Reject order error: ${error.message}", error)
                showMessage(error.message ?: "Failed to decline order")
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
                // Saved to the assignment by the pickup RPC when the partner confirms pickup.
                repository.rememberPickupPhoto(assignmentId, photoPath)
                _isActionLoading.value = false
                _selectedAssignmentForDetail.update { it?.copy(pickupPhotoUrl = photoPath) }
                _pickupPhotoUploadState.value = UploadState.Success(photoPath)
                showMessage("Pickup photo uploaded successfully!")
                Result.success(photoPath)
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
                // Saved to the assignment by the deliver RPC together with the OTP check.
                repository.rememberDeliveredPhoto(assignmentId, photoPath)
                _isActionLoading.value = false
                _selectedAssignmentForDetail.update { it?.copy(deliveredPhotoUrl = photoPath) }
                _dropPhotoUploadState.value = UploadState.Success(photoPath)
                showMessage("Delivery proof photo uploaded successfully!")
                Result.success(photoPath)
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
                showMessage(err.message ?: "Failed to mark picked up")
            }
        }
    }

    fun startDelivery(assignmentId: String) {
        repository.startDelivery(assignmentId)
        _selectedAssignmentForDetail.update { it?.copy(status = "out_for_delivery") }
        showMessage("Out for Delivery! Head to customer location.")
    }

    fun arriveAtDrop(assignmentId: String) {
        repository.arriveAtDrop(assignmentId)
        _selectedAssignmentForDetail.update { it?.copy(status = "arrived_drop") }
        showMessage("Arrived at drop location. Enter delivery OTP.")
    }

    fun completeDelivery(assignmentId: String, otp: String, photoUrl: String, onCompleted: (Boolean) -> Unit) {
        viewModelScope.launch {
            _isActionLoading.value = true
            val res = repository.completeDelivery(assignmentId, otp, photoUrl)
            _isActionLoading.value = false
            res.onSuccess {
                _selectedAssignmentForDetail.update { it?.copy(status = "delivered") }
                showMessage("Order delivered successfully! Earnings will update shortly.")
                onCompleted(true)
            }.onFailure {
                showMessage(it.message ?: "Failed to verify OTP. Please try again.")
                onCompleted(false)
            }
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

    /**
     * Fetches the real FCM token and registers it in device_tokens. Without Google Play
     * Services there is no push channel; polling still delivers new orders while online.
     */
    fun fetchAndRegisterFcmToken(showToast: Boolean = false) {
        val app = getApplication<Application>()
        val playServices = com.google.android.gms.common.GoogleApiAvailability.getInstance()
            .isGooglePlayServicesAvailable(app) == com.google.android.gms.common.ConnectionResult.SUCCESS
        if (!playServices) {
            _isFcmOnline.value = false
            if (showToast) showMessage("Push alerts need Google Play Services. Orders will still sync while online.")
            return
        }

        try {
            FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
                val token = if (task.isSuccessful) task.result.orEmpty() else ""
                if (token.isBlank()) {
                    Log.w("MainViewModel", "FCM token unavailable: ${task.exception?.message}")
                    _isFcmOnline.value = false
                    if (showToast) showMessage("Could not enable push alerts. Check your internet connection.")
                    return@addOnCompleteListener
                }
                prefs.fcmDeviceToken = token
                _fcmTokenState.value = token
                viewModelScope.launch {
                    val res = repository.registerDeliveryPartnerFcmToken(token)
                    _isFcmOnline.value = res.isSuccess
                    if (showToast) {
                        showMessage(
                            if (res.isSuccess) "Device registered for order alerts!"
                            else "Order alert registration failed: ${res.exceptionOrNull()?.message}"
                        )
                    }
                }
            }
        } catch (e: Exception) {
            Log.w("MainViewModel", "FirebaseMessaging unavailable: ${e.message}")
            _isFcmOnline.value = false
        }
    }

    fun isAssignmentHandled(assignmentId: String): Boolean = repository.isAssignmentHandled(assignmentId)
    fun isAssignmentExpired(assignment: DeliveryAssignment): Boolean = repository.isAssignmentExpired(assignment)

    fun handleIncomingNotificationClick(assignmentId: String) {
        viewModelScope.launch {
            val list = repository.fetchAssignments()
            val target = list.find { it.id == assignmentId }
                ?: incomingAssignmentAlert.value?.takeIf { it.id == assignmentId }
            if (target != null) {
                if (target.status == "assigned" && !repository.isAssignmentHandled(target.id) && !repository.isAssignmentExpired(target)) {
                    repository.setIncomingAssignmentAlert(target)
                    _selectedAssignmentForDetail.value = null
                } else if (target.status in listOf("accepted", "arrived_pickup", "picked_up", "out_for_delivery", "arrived_drop")) {
                    _selectedAssignmentForDetail.value = target
                }
            } else {
                _selectedAssignmentForDetail.value = null
            }
        }
    }

    /** Debug-only: rings the real order alert (sound + full-screen) with a fake offer. */
    fun triggerTestIncomingOrderAlert(context: Context) {
        val testId = "test_${System.currentTimeMillis()}"
        val expiresAt = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US).apply {
            timeZone = java.util.TimeZone.getTimeZone("UTC")
        }.format(java.util.Date(System.currentTimeMillis() + 60_000L))
        OrderAlertNotifier.show(
            context,
            OrderAlert(
                assignmentId = testId,
                title = "New Delivery Request (TEST)",
                storeName = "Sndmart DarkStore Hub",
                storeAddress = "Koppal Road, Main Market, Sindhanur",
                customerAddress = "Ward No 12, Gandhi Nagar, Sindhanur",
                paymentMode = "cash",
                totalAmount = "520",
                deliveryFee = "60",
                expiresAtIso = expiresAt
            )
        )
        showMessage("Test order alert ringing for 60 seconds. Accept/Decline will fail: it is not a real order.")
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
                showMessage(it.message ?: "Failed to save bank details")
            }
        }
    }

    fun loadBankDetails() {
        viewModelScope.launch {
            repository.getBankDetails()
        }
    }

    fun selectAssignmentForDetail(assignment: DeliveryAssignment?) {
        _selectedAssignmentForDetail.value = assignment
    }

    fun dismissIncomingAlert() {
        incomingAssignmentAlert.value?.let { OrderAlertNotifier.cancel(getApplication(), it.id) }
        repository.dismissIncomingAlert()
    }

    private fun formatRupees(amount: Double): String =
        if (amount % 1.0 == 0.0) amount.toLong().toString() else String.format(java.util.Locale.US, "%.2f", amount)

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
            if (locationTracker.isTracking.value) {
                repository.setOnline(isOnline = false)
            }
            // Must run while the session is still valid, otherwise pushes keep coming.
            repository.deactivateDeviceToken()
            endSession("Signed out successfully")
        }
    }

    private fun endSession(message: String) {
        OrderAlertNotifier.cancelAll(getApplication())
        locationTracker.stopTracking()
        repository.clearData()
        prefs.clearSession()
        _isFcmOnline.value = false
        _selectedAssignmentForDetail.value = null
        _authUiState.value = AuthUiState.Unauthenticated
        showMessage(message)
    }
}
