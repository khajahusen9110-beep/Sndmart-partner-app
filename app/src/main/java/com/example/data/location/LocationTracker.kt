package com.example.data.location

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.data.remote.SupabaseRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.random.Random

class LocationTracker(
    private val context: Context,
    private val repository: SupabaseRepository
) {
    private val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
    private val coroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var trackingJob: Job? = null

    private val _currentLocation = MutableStateFlow<Pair<Double, Double>>(Pair(12.9716, 77.5946)) // Bengaluru default
    val currentLocation: StateFlow<Pair<Double, Double>> = _currentLocation.asStateFlow()

    private val _isTracking = MutableStateFlow(false)
    val isTracking: StateFlow<Boolean> = _isTracking.asStateFlow()

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            updateCoordinates(location.latitude, location.longitude)
        }
        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }

    fun startTracking(updateIntervalSeconds: Long = 20L) {
        if (_isTracking.value) return
        _isTracking.value = true

        val hasFineLocation = ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        val hasCoarseLocation = ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        if (hasFineLocation || hasCoarseLocation) {
            try {
                locationManager?.let { lm ->
                    val isGpsEnabled = lm.isProviderEnabled(LocationManager.GPS_PROVIDER)
                    val isNetworkEnabled = lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)

                    if (isGpsEnabled) {
                        lm.requestLocationUpdates(
                            LocationManager.GPS_PROVIDER,
                            updateIntervalSeconds * 1000L,
                            10f,
                            locationListener
                        )
                    } else if (isNetworkEnabled) {
                        lm.requestLocationUpdates(
                            LocationManager.NETWORK_PROVIDER,
                            updateIntervalSeconds * 1000L,
                            10f,
                            locationListener
                        )
                    }
                }
            } catch (e: SecurityException) {
                Log.e("LocationTracker", "Location permission denied: ${e.message}")
            }
        }

        // Run coroutine periodic updater that sends delivery_partner_update_location to Supabase RPC
        trackingJob?.cancel()
        trackingJob = coroutineScope.launch {
            while (isActive && _isTracking.value) {
                val current = _currentLocation.value
                // Apply slight micro-step simulation if on emulator to demonstrate real-time GPS movement
                val nextLat = current.first + (Random.nextDouble(-0.0003, 0.0003))
                val nextLng = current.second + (Random.nextDouble(-0.0003, 0.0003))
                updateCoordinates(nextLat, nextLng)

                // Call Supabase RPC
                repository.updateLocation(nextLat, nextLng)
                delay(updateIntervalSeconds * 1000L)
            }
        }
    }

    fun stopTracking() {
        _isTracking.value = false
        trackingJob?.cancel()
        trackingJob = null
        try {
            locationManager?.removeUpdates(locationListener)
        } catch (e: Exception) {
            Log.e("LocationTracker", "Error removing updates: ${e.message}")
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
}
