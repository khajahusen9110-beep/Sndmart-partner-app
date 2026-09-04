package com.example.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.local.AppDatabase
import com.example.data.local.AppPreferences
import com.example.data.location.LocationTracker
import com.example.data.model.*
import com.example.data.remote.SupabaseRepository
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
    val repository = SupabaseRepository(application, prefs, database)
    val locationTracker = LocationTracker(application, repository)

    private val _authUiState = MutableStateFlow<AuthUiState>(AuthUiState.Loading)
    val authUiState: StateFlow<AuthUiState> = _authUiState.asStateFlow()

    val currentPartner = repository.currentPartner
    val assignments = repository.assignments
    val incomingAssignmentAlert = repository.incomingAssignmentAlert
    val earnings = repository.earnings
    val cashLedger = repository.cashLedger
    val paymentRequests = repository.paymentRequests
    val notifications = repository.notifications
    val currentLocation = locationTracker.currentLocation
    val isTrackingLocation = locationTracker.isTracking

    private val _selectedAssignmentForDetail = MutableStateFlow<DeliveryAssignment?>(null)
    val selectedAssignmentForDetail: StateFlow<DeliveryAssignment?> = _selectedAssignmentForDetail.asStateFlow()

    private val _isActionLoading = MutableStateFlow(false)
    val isActionLoading: StateFlow<Boolean> = _isActionLoading.asStateFlow()

    private val _userMessage = MutableStateFlow<String?>(null)
    val userMessage: StateFlow<String?> = _userMessage.asStateFlow()

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
            delay(400)
            val token = prefs.token
            val userId = prefs.userId
            if (!token.isNullOrBlank() && !userId.isNullOrBlank()) {
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

    private fun updateAuthStateForPartner(partner: DeliveryPartner) {
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

    fun toggleOnline(online: Boolean) {
        viewModelScope.launch {
            val res = repository.setOnline(online)
            res.onSuccess {
                val current = _authUiState.value
                if (current is AuthUiState.Authenticated) {
                    _authUiState.value = AuthUiState.Authenticated(current.partner.copy(isOnline = online))
                }
                if (online) {
                    locationTracker.startTracking()
                    showMessage("You are now ONLINE & ready for orders")
                } else {
                    locationTracker.stopTracking()
                    showMessage("You are now OFFLINE")
                }
            }
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
            repository.registerDeviceToken(token)
            showMessage("Registered device push token to backend")
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

    fun signOut() {
        locationTracker.stopTracking()
        repository.clearData()
        prefs.clearSession()
        _authUiState.value = AuthUiState.Unauthenticated
        showMessage("Signed out successfully")
    }
}
