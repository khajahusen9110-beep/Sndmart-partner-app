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
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.data.remote.SupabaseRepository
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
import kotlin.coroutines.resume

class LocationTracker(
    private val context: Context,
    private val repository: SupabaseRepository
) {
    private val fusedLocationClient: FusedLocationProviderClient =
        LocationServices.getFusedLocationProviderClient(context)
    private val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
    private val coroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var trackingJob: Job? = null

    // Default to central darkstore hub (Sindhanur: 15.8061517, 76.7650682)
    private val _currentLocation = MutableStateFlow<Pair<Double, Double>>(Pair(15.8061517, 76.7650682))
    val currentLocation: StateFlow<Pair<Double, Double>> = _currentLocation.asStateFlow()

    fun setDefaultLocation(lat: Double, lng: Double) {
        if (lat != 0.0 && lng != 0.0 && !_isTracking.value) {
            _currentLocation.value = Pair(lat, lng)
        }
    }

    private val _isTracking = MutableStateFlow(false)
    val isTracking: StateFlow<Boolean> = _isTracking.asStateFlow()

    private val fusedLocationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val loc = result.lastLocation ?: return
            if (loc.latitude != 0.0 && loc.longitude != 0.0) {
                updateCoordinates(loc.latitude, loc.longitude)
            }
        }
    }

    private val legacyLocationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            if (location.latitude != 0.0 && location.longitude != 0.0) {
                updateCoordinates(location.latitude, location.longitude)
            }
        }
        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }

    fun hasLocationPermission(): Boolean {
        val fine = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        return fine || coarse
    }

    @SuppressLint("MissingPermission")
    suspend fun getCurrentGpsLocation(): Pair<Double, Double> {
        if (!hasLocationPermission()) {
            return _currentLocation.value
        }

        // 1. Try Google Play Services Fused Location Provider (high accuracy current location)
        try {
            val cancelTokenSource = com.google.android.gms.tasks.CancellationTokenSource()
            val task = fusedLocationClient.getCurrentLocation(
                Priority.PRIORITY_HIGH_ACCURACY,
                cancelTokenSource.token
            )
            val loc = task.awaitTask()
            if (loc != null && loc.latitude != 0.0 && loc.longitude != 0.0) {
                updateCoordinates(loc.latitude, loc.longitude)
                return Pair(loc.latitude, loc.longitude)
            }
        } catch (e: Exception) {
            Log.w("LocationTracker", "Fused getCurrentLocation failed: ${e.message}")
        }

        // 2. Try Fused lastLocation
        try {
            val lastTask = fusedLocationClient.lastLocation
            val loc = lastTask.awaitTask()
            if (loc != null && loc.latitude != 0.0 && loc.longitude != 0.0) {
                updateCoordinates(loc.latitude, loc.longitude)
                return Pair(loc.latitude, loc.longitude)
            }
        } catch (e: Exception) {
            Log.w("LocationTracker", "Fused lastLocation failed: ${e.message}")
        }

        // 3. Try Android LocationManager providers as fallback
        try {
            locationManager?.let { lm ->
                val gpsLoc = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                if (gpsLoc != null && gpsLoc.latitude != 0.0 && gpsLoc.longitude != 0.0) {
                    updateCoordinates(gpsLoc.latitude, gpsLoc.longitude)
                    return Pair(gpsLoc.latitude, gpsLoc.longitude)
                }
                val netLoc = lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
                if (netLoc != null && netLoc.latitude != 0.0 && netLoc.longitude != 0.0) {
                    updateCoordinates(netLoc.latitude, netLoc.longitude)
                    return Pair(netLoc.latitude, netLoc.longitude)
                }
            }
        } catch (e: Exception) {
            Log.w("LocationTracker", "LocationManager fallback failed: ${e.message}")
        }

        return _currentLocation.value
    }

    @SuppressLint("MissingPermission")
    fun startTracking(updateIntervalSeconds: Long = 20L) {
        if (_isTracking.value) return
        _isTracking.value = true

        if (hasLocationPermission()) {
            // Register Fused Location updates (every 20s, min 10s)
            try {
                val locationRequest = LocationRequest.Builder(
                    Priority.PRIORITY_HIGH_ACCURACY,
                    updateIntervalSeconds * 1000L
                )
                    .setMinUpdateIntervalMillis(10000L)
                    .build()

                fusedLocationClient.requestLocationUpdates(
                    locationRequest,
                    fusedLocationCallback,
                    Looper.getMainLooper()
                )
            } catch (e: Exception) {
                Log.e("LocationTracker", "Error requesting fused updates: ${e.message}")
            }

            // Register legacy LocationManager as backup
            try {
                locationManager?.let { lm ->
                    if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                        lm.requestLocationUpdates(
                            LocationManager.GPS_PROVIDER,
                            updateIntervalSeconds * 1000L,
                            5f,
                            legacyLocationListener,
                            Looper.getMainLooper()
                        )
                    } else if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                        lm.requestLocationUpdates(
                            LocationManager.NETWORK_PROVIDER,
                            updateIntervalSeconds * 1000L,
                            5f,
                            legacyLocationListener,
                            Looper.getMainLooper()
                        )
                    }
                }
            } catch (e: Exception) {
                Log.e("LocationTracker", "Error requesting locationManager updates: ${e.message}")
            }
        }

        // Periodic continuous location push to Supabase backend every 15-30 seconds
        trackingJob?.cancel()
        trackingJob = coroutineScope.launch {
            while (isActive && _isTracking.value) {
                val (lat, lng) = getCurrentGpsLocation()
                updateCoordinates(lat, lng)
                Log.i("LocationTracker", "Periodic GPS push: lat=$lat, lng=$lng")
                repository.updateLocation(lat, lng)
                delay(updateIntervalSeconds * 1000L)
            }
        }
    }

    fun stopTracking() {
        _isTracking.value = false
        trackingJob?.cancel()
        trackingJob = null
        try {
            fusedLocationClient.removeLocationUpdates(fusedLocationCallback)
        } catch (e: Exception) {
            Log.e("LocationTracker", "Error removing fused updates: ${e.message}")
        }
        try {
            locationManager?.removeUpdates(legacyLocationListener)
        } catch (e: Exception) {
            Log.e("LocationTracker", "Error removing legacy location updates: ${e.message}")
        }
    }

    private fun updateCoordinates(lat: Double, lng: Double) {
        _currentLocation.value = Pair(lat, lng)
    }

    fun simulateLocationStep(latDelta: Double = 0.001, lngDelta: Double = 0.001) {
        val current = _currentLocation.value
        val next = Pair(current.first + latDelta, current.second + lngDelta)
        _currentLocation.value = next
        coroutineScope.launch {
            repository.updateLocation(next.first, next.second)
        }
    }

    private suspend fun <T> com.google.android.gms.tasks.Task<T>.awaitTask(): T? =
        suspendCancellableCoroutine { continuation ->
            addOnSuccessListener { result ->
                if (continuation.isActive) continuation.resume(result)
            }
            addOnFailureListener {
                if (continuation.isActive) continuation.resume(null)
            }
            addOnCanceledListener {
                if (continuation.isActive) continuation.resume(null)
            }
        }
}
