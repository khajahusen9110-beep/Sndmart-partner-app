package com.example.data.local

import android.content.Context
import android.content.SharedPreferences
import com.example.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class AppPreferences(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("sndmart_partner_prefs", Context.MODE_PRIVATE)

    private val _isOnline = MutableStateFlow(prefs.getBoolean(KEY_IS_ONLINE, false))
    val isOnline: StateFlow<Boolean> = _isOnline.asStateFlow()

    private val _isAvailable = MutableStateFlow(prefs.getBoolean(KEY_IS_AVAILABLE, true))
    val isAvailable: StateFlow<Boolean> = _isAvailable.asStateFlow()

    private val _partnerId = MutableStateFlow(prefs.getString(KEY_PARTNER_ID, null))
    val partnerId: StateFlow<String?> = _partnerId.asStateFlow()

    private val _authToken = MutableStateFlow(prefs.getString(KEY_AUTH_TOKEN, null))
    val authToken: StateFlow<String?> = _authToken.asStateFlow()

    private val _userEmail = MutableStateFlow(prefs.getString(KEY_USER_EMAIL, null))
    val userEmailFlow: StateFlow<String?> = _userEmail.asStateFlow()

    private val _userPhone = MutableStateFlow(prefs.getString(KEY_USER_PHONE, null))
    val userPhoneFlow: StateFlow<String?> = _userPhone.asStateFlow()

    // Backend endpoint is fixed at build time (.env / CI secrets). It is intentionally not
    // user-editable: a runtime override would let anyone point the app at a hostile server.
    val customSupabaseUrl: String
        get() = BuildConfig.SUPABASE_URL.takeIf { it.startsWith("https://") } ?: DEFAULT_SUPABASE_URL

    // The anon key is public by design (row level security protects the data).
    val customSupabaseKey: String
        get() = BuildConfig.SUPABASE_ANON_KEY.takeIf { it.isNotBlank() && !it.contains("placeholder") }
            ?: DEFAULT_ANON_KEY

    var token: String?
        get() = prefs.getString(KEY_AUTH_TOKEN, null)
        set(value) {
            prefs.edit().putString(KEY_AUTH_TOKEN, value).apply()
            _authToken.value = value
        }

    var refreshToken: String?
        get() = prefs.getString(KEY_REFRESH_TOKEN, null)
        set(value) {
            prefs.edit().putString(KEY_REFRESH_TOKEN, value).apply()
        }

    var tokenExpiresAt: Long
        get() = prefs.getLong(KEY_TOKEN_EXPIRES_AT, 0L)
        set(value) {
            prefs.edit().putLong(KEY_TOKEN_EXPIRES_AT, value).apply()
        }

    fun isSessionExpiringSoon(): Boolean {
        val exp = tokenExpiresAt
        if (exp <= 0L) return false
        // Consider expired if within 2 minutes of expiry or already past
        return System.currentTimeMillis() >= (exp - 120_000L)
    }

    var userId: String?
        get() = prefs.getString(KEY_USER_ID, null)
        set(value) = prefs.edit().putString(KEY_USER_ID, value).apply()

    var userEmail: String?
        get() = prefs.getString(KEY_USER_EMAIL, null)
        set(value) {
            prefs.edit().putString(KEY_USER_EMAIL, value).apply()
            _userEmail.value = value
        }

    var userPhone: String?
        get() = prefs.getString(KEY_USER_PHONE, null)
        set(value) {
            prefs.edit().putString(KEY_USER_PHONE, value).apply()
            _userPhone.value = value
        }

    var currentPartnerId: String?
        get() = prefs.getString(KEY_PARTNER_ID, null)
        set(value) {
            prefs.edit().putString(KEY_PARTNER_ID, value).apply()
            _partnerId.value = value
        }

    var partnerName: String
        get() = prefs.getString(KEY_PARTNER_NAME, "") ?: ""
        set(value) = prefs.edit().putString(KEY_PARTNER_NAME, value).apply()

    var partnerVehicleType: String
        get() = prefs.getString(KEY_PARTNER_VEHICLE_TYPE, "bike") ?: "bike"
        set(value) = prefs.edit().putString(KEY_PARTNER_VEHICLE_TYPE, value).apply()

    var partnerVehicleNumber: String
        get() = prefs.getString(KEY_PARTNER_VEHICLE_NUMBER, "") ?: ""
        set(value) = prefs.edit().putString(KEY_PARTNER_VEHICLE_NUMBER, value).apply()

    var approvalStatus: String
        get() = prefs.getString(KEY_APPROVAL_STATUS, "pending") ?: "pending"
        set(value) = prefs.edit().putString(KEY_APPROVAL_STATUS, value).apply()

    var onlineState: Boolean
        get() = prefs.getBoolean(KEY_IS_ONLINE, false)
        set(value) {
            prefs.edit().putBoolean(KEY_IS_ONLINE, value).apply()
            _isOnline.value = value
        }

    var availableState: Boolean
        get() = prefs.getBoolean(KEY_IS_AVAILABLE, true)
        set(value) {
            prefs.edit().putBoolean(KEY_IS_AVAILABLE, value).apply()
            _isAvailable.value = value
        }

    /** Real FCM registration token, or "" until Firebase has issued one. */
    var fcmDeviceToken: String
        get() {
            val token = prefs.getString(KEY_FCM_DEVICE_TOKEN, null).orEmpty()
            // Older builds stored a locally generated placeholder that FCM can never deliver to.
            return if (token.startsWith(LEGACY_FAKE_TOKEN_PREFIX)) "" else token
        }
        set(value) = prefs.edit().putString(KEY_FCM_DEVICE_TOKEN, value).apply()

    var isFcmRegistered: Boolean
        get() = prefs.getBoolean(KEY_FCM_REGISTERED, false)
        set(value) = prefs.edit().putBoolean(KEY_FCM_REGISTERED, value).apply()

    /**
     * The partner chose to be online (set on a successful "go online", cleared on "go offline").
     * Lets the app tell a server-side auto-offline apart from the partner's own choice.
     */
    var onlineIntent: Boolean
        get() = prefs.getBoolean(KEY_ONLINE_INTENT, false)
        set(value) = prefs.edit().putBoolean(KEY_ONLINE_INTENT, value).apply()

    /** One-time tip about Autostart / background settings on Xiaomi, Vivo, Oppo, Realme. */
    var autostartTipShown: Boolean
        get() = prefs.getBoolean(KEY_AUTOSTART_TIP, false)
        set(value) = prefs.edit().putBoolean(KEY_AUTOSTART_TIP, value).apply()

    var soundAlertsEnabled: Boolean
        get() = prefs.getBoolean(KEY_SOUND_ALERTS, true)
        set(value) = prefs.edit().putBoolean(KEY_SOUND_ALERTS, value).apply()

    var isEmailConfirmed: Boolean
        get() = prefs.getBoolean(KEY_EMAIL_CONFIRMED, false)
        set(value) = prefs.edit().putBoolean(KEY_EMAIL_CONFIRMED, value).apply()

    fun getHandledAssignmentIds(): Set<String> {
        return prefs.getStringSet(KEY_HANDLED_ASSIGNMENTS, emptySet()) ?: emptySet()
    }

    fun addHandledAssignmentId(id: String) {
        if (id.isBlank()) return
        val current = prefs.getStringSet(KEY_HANDLED_ASSIGNMENTS, emptySet())?.toMutableSet() ?: mutableSetOf()
        current.add(id)
        // Keep set size reasonable (last 100 handled IDs)
        if (current.size > 100) {
            val trimmed = current.toList().takeLast(100).toSet()
            prefs.edit().putStringSet(KEY_HANDLED_ASSIGNMENTS, trimmed).apply()
        } else {
            prefs.edit().putStringSet(KEY_HANDLED_ASSIGNMENTS, current).apply()
        }
    }

    fun isAssignmentHandled(id: String): Boolean {
        if (id.isBlank()) return false
        return prefs.getStringSet(KEY_HANDLED_ASSIGNMENTS, emptySet())?.contains(id) == true
    }

    fun clearSession() {
        prefs.edit()
            .remove(KEY_AUTH_TOKEN)
            .remove(KEY_REFRESH_TOKEN)
            .remove(KEY_TOKEN_EXPIRES_AT)
            .remove(KEY_USER_ID)
            .remove(KEY_USER_EMAIL)
            .remove(KEY_USER_PHONE)
            .remove(KEY_PARTNER_ID)
            .remove(KEY_PARTNER_NAME)
            .remove(KEY_PARTNER_VEHICLE_TYPE)
            .remove(KEY_PARTNER_VEHICLE_NUMBER)
            .remove(KEY_APPROVAL_STATUS)
            .remove(KEY_FCM_REGISTERED)
            .remove(KEY_HANDLED_ASSIGNMENTS)
            .remove(KEY_ONLINE_INTENT)
            .putBoolean(KEY_IS_ONLINE, false)
            .apply()
        _authToken.value = null
        _userEmail.value = null
        _userPhone.value = null
        _partnerId.value = null
        _isOnline.value = false
    }

    companion object {
        const val DEFAULT_SUPABASE_URL = "https://zennmoughfhennwgecgo.supabase.co"
        const val DEFAULT_ANON_KEY = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6Inplbm5tb3VnaGZoZW5ud2dlY2dvIiwicm9sZSI6ImFub24iLCJpYXQiOjE3ODcwNjI3MjksImV4cCI6MjEwMjYzODcyOX0.xSuD8sYQ4PWgN-YhOaHhwg6hdZYjw2_Jj-XrK3lMERc"

        private const val KEY_AUTH_TOKEN = "auth_token"
        private const val KEY_REFRESH_TOKEN = "refresh_token"
        private const val KEY_TOKEN_EXPIRES_AT = "token_expires_at"
        private const val KEY_USER_ID = "user_id"
        private const val KEY_USER_EMAIL = "user_email"
        private const val KEY_USER_PHONE = "user_phone"
        private const val KEY_PARTNER_ID = "partner_id"
        private const val KEY_PARTNER_NAME = "partner_name"
        private const val KEY_PARTNER_VEHICLE_TYPE = "partner_vehicle_type"
        private const val KEY_PARTNER_VEHICLE_NUMBER = "partner_vehicle_number"
        private const val KEY_APPROVAL_STATUS = "approval_status"
        private const val KEY_IS_ONLINE = "is_online"
        private const val KEY_IS_AVAILABLE = "is_available"
        private const val LEGACY_FAKE_TOKEN_PREFIX = "fcm_sndmart_partner_"
        private const val KEY_FCM_DEVICE_TOKEN = "fcm_device_token"
        private const val KEY_FCM_REGISTERED = "fcm_registered"
        private const val KEY_SOUND_ALERTS = "sound_alerts"
        private const val KEY_EMAIL_CONFIRMED = "email_confirmed"
        private const val KEY_HANDLED_ASSIGNMENTS = "handled_assignments"
        private const val KEY_ONLINE_INTENT = "online_intent"
        private const val KEY_AUTOSTART_TIP = "autostart_tip_shown"
    }
}
