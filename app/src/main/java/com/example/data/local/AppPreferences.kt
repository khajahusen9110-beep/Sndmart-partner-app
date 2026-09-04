package com.example.data.local

import android.content.Context
import android.content.SharedPreferences
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

    private val _supabaseUrl = MutableStateFlow(prefs.getString(KEY_SUPABASE_URL, DEFAULT_SUPABASE_URL) ?: DEFAULT_SUPABASE_URL)
    val supabaseUrl: StateFlow<String> = _supabaseUrl.asStateFlow()

    private val _supabaseKey = MutableStateFlow(prefs.getString(KEY_SUPABASE_KEY, DEFAULT_ANON_KEY) ?: DEFAULT_ANON_KEY)
    val supabaseKey: StateFlow<String> = _supabaseKey.asStateFlow()

    var customSupabaseUrl: String
        get() = prefs.getString(KEY_SUPABASE_URL, DEFAULT_SUPABASE_URL) ?: DEFAULT_SUPABASE_URL
        set(value) {
            prefs.edit().putString(KEY_SUPABASE_URL, value).apply()
            _supabaseUrl.value = value
        }

    var customSupabaseKey: String
        get() = prefs.getString(KEY_SUPABASE_KEY, DEFAULT_ANON_KEY) ?: DEFAULT_ANON_KEY
        set(value) {
            prefs.edit().putString(KEY_SUPABASE_KEY, value).apply()
            _supabaseKey.value = value
        }

    var token: String?
        get() = prefs.getString(KEY_AUTH_TOKEN, null)
        set(value) {
            prefs.edit().putString(KEY_AUTH_TOKEN, value).apply()
            _authToken.value = value
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
        set(value) = prefs.edit().putString(KEY_USER_PHONE, value).apply()

    var currentPartnerId: String?
        get() = prefs.getString(KEY_PARTNER_ID, null)
        set(value) {
            prefs.edit().putString(KEY_PARTNER_ID, value).apply()
            _partnerId.value = value
        }

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

    var fcmDeviceToken: String
        get() = prefs.getString(KEY_FCM_DEVICE_TOKEN, "fcm_sndmart_partner_${System.currentTimeMillis()}") ?: ""
        set(value) = prefs.edit().putString(KEY_FCM_DEVICE_TOKEN, value).apply()

    var isFcmRegistered: Boolean
        get() = prefs.getBoolean(KEY_FCM_REGISTERED, false)
        set(value) = prefs.edit().putBoolean(KEY_FCM_REGISTERED, value).apply()

    var soundAlertsEnabled: Boolean
        get() = prefs.getBoolean(KEY_SOUND_ALERTS, true)
        set(value) = prefs.edit().putBoolean(KEY_SOUND_ALERTS, value).apply()

    var isEmailConfirmed: Boolean
        get() = prefs.getBoolean(KEY_EMAIL_CONFIRMED, false)
        set(value) = prefs.edit().putBoolean(KEY_EMAIL_CONFIRMED, value).apply()

    fun clearSession() {
        prefs.edit()
            .remove(KEY_AUTH_TOKEN)
            .remove(KEY_USER_ID)
            .remove(KEY_USER_EMAIL)
            .remove(KEY_USER_PHONE)
            .remove(KEY_PARTNER_ID)
            .remove(KEY_APPROVAL_STATUS)
            .putBoolean(KEY_IS_ONLINE, false)
            .apply()
        _authToken.value = null
        _userEmail.value = null
        _partnerId.value = null
        _isOnline.value = false
    }

    companion object {
        const val DEFAULT_SUPABASE_URL = "https://zennmoughfhennwgecgo.supabase.co"
        const val DEFAULT_ANON_KEY = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.e30.placeholder_anon_key"

        private const val KEY_AUTH_TOKEN = "auth_token"
        private const val KEY_USER_ID = "user_id"
        private const val KEY_USER_EMAIL = "user_email"
        private const val KEY_USER_PHONE = "user_phone"
        private const val KEY_PARTNER_ID = "partner_id"
        private const val KEY_APPROVAL_STATUS = "approval_status"
        private const val KEY_IS_ONLINE = "is_online"
        private const val KEY_IS_AVAILABLE = "is_available"
        private const val KEY_SUPABASE_URL = "supabase_url"
        private const val KEY_SUPABASE_KEY = "supabase_key"
        private const val KEY_FCM_DEVICE_TOKEN = "fcm_device_token"
        private const val KEY_FCM_REGISTERED = "fcm_registered"
        private const val KEY_SOUND_ALERTS = "sound_alerts"
        private const val KEY_EMAIL_CONFIRMED = "email_confirmed"
    }
}
