package com.example.data.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.data.remote.SupabaseRepository
import com.example.service.LocationTrackingService
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.resume

/**
 * Tracks the partner's GPS position while they are online and pushes it to Supabase.
 *
 * Only real GPS fixes are ever sent to the backend: if no fix is available nothing is
 * pushed, so dispatch never sees a made-up location. A foreground service
 * ([LocationTrackingService]) keeps the process alive while tracking so updates
 * continue when the app is in the background.
 */
class LocationTracker private constructor(
    private val context: Context,
    private val repository: SupabaseRepository
) {
    private val fusedLocationClient: FusedLocationProviderClient =
        LocationServices.getFusedLocationProviderClient(context)
    private val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
    private val coroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var trackingJob: Job? = null
    private var usingLegacyProvider = false

    // Map camera default only (Sindhanur hub). Never sent to the backend.
    private val _currentLocation = MutableStateFlow(Pair(15.8061517, 76.7650682))
    val currentLocation: StateFlow<Pair<Double, Double>> = _currentLocation.asStateFlow()

    private val _hasRealFix = MutableStateFlow(false)
    val hasRealFix: StateFlow<Boolean> = _hasRealFix.asStateFlow()

    private val _isTracking = MutableStateFlow(false)
    val isTracking: StateFlow<Boolean> = _isTracking.asStateFlow()

    @Volatile
    private var lastFixElapsedMs = 0L

    /** Why the last location push failed, for the "Location is not updating" banner. */
    enum class Problem { NONE, NO_PERMISSION, GPS_OFF, NO_FIX, NETWORK }

    private val _problem = MutableStateFlow(Problem.NONE)
    val problem: StateFlow<Problem> = _problem.asStateFlow()

    /** elapsedRealtime of the last location the server accepted (0 = none since going online). */
    private val _lastPushOkElapsed = MutableStateFlow(0L)
    val lastPushOkElapsed: StateFlow<Long> = _lastPushOkElapsed.asStateFlow()

    /** elapsedRealtime when tracking started, so "not updating" can be judged before any push. */
    @Volatile
    var trackingStartedElapsed = 0L
        private set

    private val pushMutex = Mutex()

    @Volatile
    private var lastPushed: Pair<Double, Double>? = null

    @Volatile
    private var lastPushElapsed = 0L

    private val fusedLocationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.lastLocation?.let { onFix(it) }
        }
    }

    private val legacyLocationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) = onFix(location)
        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }

    fun hasLocationPermission(): Boolean {
        val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        return fine || coarse
    }

    private fun hasPlayServices(): Boolean =
        GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context) == ConnectionResult.SUCCESS

    /** Returns a real GPS fix, or null when none could be obtained. */
    @SuppressLint("MissingPermission")
    suspend fun getCurrentGpsLocation(): Pair<Double, Double>? {
        if (!hasLocationPermission()) return null

        if (hasPlayServices()) {
            try {
                val cancelTokenSource = com.google.android.gms.tasks.CancellationTokenSource()
                val loc = withTimeoutOrNull(15_000L) {
                    fusedLocationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, cancelTokenSource.token)
                        .awaitTask()
                }
                if (loc == null) cancelTokenSource.cancel()
                if (loc != null && isValid(loc)) {
                    onFix(loc)
                    return Pair(loc.latitude, loc.longitude)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Fused getCurrentLocation failed: ${e.message}")
            }

            try {
                val loc = fusedLocationClient.lastLocation.awaitTask()
                if (loc != null && isValid(loc) && isRecent(loc)) {
                    onFix(loc)
                    return Pair(loc.latitude, loc.longitude)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Fused lastLocation failed: ${e.message}")
            }
        }

        try {
            locationManager?.let { lm ->
                listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER).forEach { provider ->
                    val loc = lm.getLastKnownLocation(provider)
                    if (loc != null && isValid(loc) && isRecent(loc)) {
                        onFix(loc)
                        return Pair(loc.latitude, loc.longitude)
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "LocationManager fallback failed: ${e.message}")
        }

        return null
    }

    @SuppressLint("MissingPermission")
    fun startTracking(updateIntervalSeconds: Long = UPDATE_INTERVAL_SECONDS) {
        if (!hasLocationPermission()) {
            Log.w(TAG, "startTracking skipped: location permission not granted")
            return
        }
        if (_isTracking.value) return
        _isTracking.value = true
        trackingStartedElapsed = SystemClock.elapsedRealtime()
        _lastPushOkElapsed.value = 0L
        _problem.value = Problem.NONE
        lastPushed = null

        LocationTrackingService.start(context)

        if (hasPlayServices()) {
            try {
                val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, updateIntervalSeconds * 1000L)
                    .setMinUpdateIntervalMillis(10_000L)
                    .build()
                fusedLocationClient.requestLocationUpdates(locationRequest, fusedLocationCallback, Looper.getMainLooper())
            } catch (e: Exception) {
                Log.e(TAG, "Error requesting fused updates: ${e.message}")
                registerLegacyUpdates(updateIntervalSeconds)
            }
        } else {
            registerLegacyUpdates(updateIntervalSeconds)
        }

        trackingJob?.cancel()
        trackingJob = coroutineScope.launch {
            while (isActive && _isTracking.value) {
                when {
                    !hasLocationPermission() -> _problem.value = Problem.NO_PERMISSION
                    !isGpsEnabled() -> _problem.value = Problem.GPS_OFF
                    else -> {
                        val fixIsFresh = lastFixElapsedMs > 0 &&
                            SystemClock.elapsedRealtime() - lastFixElapsedMs < updateIntervalSeconds * 3 * 1000L
                        val position = if (fixIsFresh) _currentLocation.value else getCurrentGpsLocation()
                        // Sent every tick even when stationary, so last_location_at stays fresh:
                        // the server only offers orders to partners seen in the last 5 minutes.
                        if (position != null) push(position) else _problem.value = Problem.NO_FIX
                    }
                }
                delay(updateIntervalSeconds * 1000L)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun registerLegacyUpdates(updateIntervalSeconds: Long) {
        try {
            locationManager?.let { lm ->
                val provider = when {
                    lm.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
                    lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
                    else -> null
                } ?: return
                lm.requestLocationUpdates(provider, updateIntervalSeconds * 1000L, 10f, legacyLocationListener, Looper.getMainLooper())
                usingLegacyProvider = true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error requesting LocationManager updates: ${e.message}")
        }
    }

    fun stopTracking() {
        _isTracking.value = false
        _problem.value = Problem.NONE
        trackingJob?.cancel()
        trackingJob = null
        try {
            fusedLocationClient.removeLocationUpdates(fusedLocationCallback)
        } catch (e: Exception) {
            Log.e(TAG, "Error removing fused updates: ${e.message}")
        }
        if (usingLegacyProvider) {
            try {
                locationManager?.removeUpdates(legacyLocationListener)
            } catch (e: Exception) {
                Log.e(TAG, "Error removing legacy location updates: ${e.message}")
            }
            usingLegacyProvider = false
        }
        LocationTrackingService.stop(context)
    }

    private fun onFix(location: Location) {
        if (!isValid(location)) return
        lastFixElapsedMs = SystemClock.elapsedRealtime()
        val position = Pair(location.latitude, location.longitude)
        _currentLocation.value = position
        _hasRealFix.value = true
        // Push right away after a significant move instead of waiting for the next tick.
        if (_isTracking.value && movedSignificantly(position) &&
            SystemClock.elapsedRealtime() - lastPushElapsed > MIN_MOVE_PUSH_GAP_MS
        ) {
            coroutineScope.launch { push(position) }
        }
    }

    private fun movedSignificantly(position: Pair<Double, Double>): Boolean {
        val previous = lastPushed ?: return true
        val result = FloatArray(1)
        Location.distanceBetween(previous.first, previous.second, position.first, position.second, result)
        return result[0] >= SIGNIFICANT_MOVE_METERS
    }

    /** Sends one location to the server and records the outcome for the health banner. */
    private suspend fun push(position: Pair<Double, Double>) = pushMutex.withLock {
        lastPushElapsed = SystemClock.elapsedRealtime()
        repository.updateLocation(position.first, position.second)
            .onSuccess {
                lastPushed = position
                _lastPushOkElapsed.value = SystemClock.elapsedRealtime()
                _problem.value = Problem.NONE
            }
            .onFailure {
                Log.w(TAG, "Location push failed: ${it.message}")
                _problem.value = Problem.NETWORK
            }
    }

    private fun isGpsEnabled(): Boolean = try {
        val lm = locationManager
        lm == null || lm.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
            lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
    } catch (e: Exception) {
        true
    }

    private fun isValid(loc: Location) =
        loc.latitude != 0.0 && loc.longitude != 0.0 && loc.latitude in -90.0..90.0 && loc.longitude in -180.0..180.0

    // Cached fixes older than 5 minutes are stale for dispatch purposes.
    private fun isRecent(loc: Location) = System.currentTimeMillis() - loc.time < 5 * 60 * 1000L

    private suspend fun <T> com.google.android.gms.tasks.Task<T>.awaitTask(): T? =
        suspendCancellableCoroutine { continuation ->
            addOnSuccessListener { result -> if (continuation.isActive) continuation.resume(result) }
            addOnFailureListener { if (continuation.isActive) continuation.resume(null) }
            addOnCanceledListener { if (continuation.isActive) continuation.resume(null) }
        }

    companion object {
        private const val TAG = "LocationTracker"
        const val UPDATE_INTERVAL_SECONDS = 30L
        private const val SIGNIFICANT_MOVE_METERS = 50f
        private const val MIN_MOVE_PUSH_GAP_MS = 10_000L

        @Volatile
        private var INSTANCE: LocationTracker? = null

        fun getInstance(context: Context): LocationTracker {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: LocationTracker(
                    context.applicationContext,
                    SupabaseRepository.getInstance(context)
                ).also { INSTANCE = it }
            }
        }
    }
}
