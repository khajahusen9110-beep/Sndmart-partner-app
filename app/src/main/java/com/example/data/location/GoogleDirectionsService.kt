package com.example.data.location

import android.location.Location
import android.util.Log
import com.example.BuildConfig
import com.google.android.gms.maps.model.LatLng
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.math.*

data class DirectionsRouteInfo(
    val points: List<LatLng>,
    val distanceText: String,
    val durationText: String,
    val distanceMeters: Long,
    val durationSeconds: Long,
    val isRealDirections: Boolean,
    val errorMessage: String? = null
)

object GoogleDirectionsService {
    private const val TAG = "DirectionsService"
    private val client = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .build()

    fun getApiKey(): String {
        return try {
            val key = BuildConfig.GOOGLE_MAPS_API_KEY
            if (key.isNullOrBlank() || key == "YOUR_GOOGLE_MAPS_API_KEY") {
                ""
            } else {
                key
            }
        } catch (e: Throwable) {
            ""
        }
    }

    suspend fun getRoute(
        originLat: Double,
        originLng: Double,
        destLat: Double,
        destLng: Double,
        waypointLat: Double? = null,
        waypointLng: Double? = null
    ): DirectionsRouteInfo = withContext(Dispatchers.IO) {
        val apiKey = getApiKey()

        if (apiKey.isNotBlank() && apiKey != "YOUR_GOOGLE_MAPS_API_KEY") {
            try {
                val waypointsParam = if (waypointLat != null && waypointLng != null &&
                    waypointLat != 0.0 && waypointLng != 0.0
                ) {
                    "&waypoints=optimize:false|$waypointLat,$waypointLng"
                } else {
                    ""
                }

                val url = "https://maps.googleapis.com/maps/api/directions/json" +
                        "?origin=$originLat,$originLng" +
                        "&destination=$destLat,$destLng" +
                        waypointsParam +
                        "&mode=driving" +
                        "&key=$apiKey"

                val request = Request.Builder()
                    .url(url)
                    .get()
                    .build()

                val response = client.newCall(request).execute()
                val bodyString = response.body?.string()

                if (response.isSuccessful && !bodyString.isNullOrBlank()) {
                    val json = JSONObject(bodyString)
                    val status = json.optString("status")
                    if (status == "OK") {
                        val routes = json.getJSONArray("routes")
                        if (routes.length() > 0) {
                            val route = routes.getJSONObject(0)
                            val overviewPolyline = route.getJSONObject("overview_polyline").getString("points")
                            val decodedPoints = decodePolyline(overviewPolyline)

                            val legs = route.getJSONArray("legs")
                            var totalDistanceMeters = 0L
                            var totalDurationSeconds = 0L

                            for (i in 0 until legs.length()) {
                                val leg = legs.getJSONObject(i)
                                totalDistanceMeters += leg.getJSONObject("distance").optLong("value", 0L)
                                totalDurationSeconds += leg.getJSONObject("duration").optLong("value", 0L)
                            }

                            val distanceKm = totalDistanceMeters / 1000.0
                            val distanceText = if (distanceKm < 1.0) {
                                "${totalDistanceMeters} m"
                            } else {
                                String.format(java.util.Locale.US, "%.1f km", distanceKm)
                            }

                            val durationMins = max(1L, (totalDurationSeconds + 30) / 60)
                            val durationText = if (durationMins >= 60) {
                                "${durationMins / 60} hr ${durationMins % 60} mins"
                            } else {
                                "$durationMins mins"
                            }

                            Log.i(TAG, "Directions API success: $distanceText, $durationText, ${decodedPoints.size} points")
                            return@withContext DirectionsRouteInfo(
                                points = decodedPoints,
                                distanceText = distanceText,
                                durationText = durationText,
                                distanceMeters = totalDistanceMeters,
                                durationSeconds = totalDurationSeconds,
                                isRealDirections = true
                            )
                        }
                    } else {
                        Log.w(TAG, "Directions API returned status: $status, ${json.optString("error_message")}")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error fetching directions from API: ${e.message}")
            }
        }

        // Fallback: Generate smooth interpolated path and calculate haversine distance
        val fallbackPoints = mutableListOf<LatLng>()
        val start = LatLng(originLat, originLng)
        fallbackPoints.add(start)

        var totalMeters = 0.0
        if (waypointLat != null && waypointLng != null && waypointLat != 0.0 && waypointLng != 0.0) {
            val wp = LatLng(waypointLat, waypointLng)
            totalMeters += computeDistanceMeters(originLat, originLng, waypointLat, waypointLng)
            // Interpolate points between start and waypoint
            interpolatePoints(start, wp, fallbackPoints)
            fallbackPoints.add(wp)

            totalMeters += computeDistanceMeters(waypointLat, waypointLng, destLat, destLng)
            val dest = LatLng(destLat, destLng)
            interpolatePoints(wp, dest, fallbackPoints)
            fallbackPoints.add(dest)
        } else {
            val dest = LatLng(destLat, destLng)
            totalMeters += computeDistanceMeters(originLat, originLng, destLat, destLng)
            interpolatePoints(start, dest, fallbackPoints)
            fallbackPoints.add(dest)
        }

        val distanceKm = totalMeters / 1000.0
        val distanceText = if (distanceKm < 1.0) {
            "${totalMeters.roundToInt()} m"
        } else {
            String.format(java.util.Locale.US, "%.1f km", distanceKm)
        }

        // Estimate driving time at ~25 km/h urban speed
        val estDurationMinutes = max(2, (distanceKm / 25.0 * 60).roundToInt())
        val durationText = "$estDurationMinutes mins"

        DirectionsRouteInfo(
            points = fallbackPoints,
            distanceText = distanceText,
            durationText = durationText,
            distanceMeters = totalMeters.toLong(),
            durationSeconds = (estDurationMinutes * 60).toLong(),
            isRealDirections = false,
            errorMessage = if (apiKey.isBlank() || apiKey == "YOUR_GOOGLE_MAPS_API_KEY") {
                "Google Maps API key not yet configured - using GPS path approximation"
            } else null
        )
    }

    private fun computeDistanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val results = FloatArray(1)
        Location.distanceBetween(lat1, lon1, lat2, lon2, results)
        return results[0].toDouble()
    }

    private fun interpolatePoints(from: LatLng, to: LatLng, targetList: MutableList<LatLng>, steps: Int = 8) {
        for (i in 1 until steps) {
            val fraction = i.toDouble() / steps
            val lat = from.latitude + (to.latitude - from.latitude) * fraction
            val lng = from.longitude + (to.longitude - from.longitude) * fraction
            targetList.add(LatLng(lat, lng))
        }
    }

    /**
     * Standard Google Maps polyline decoder
     */
    fun decodePolyline(encoded: String): List<LatLng> {
        val poly = ArrayList<LatLng>()
        var index = 0
        val len = encoded.length
        var lat = 0
        var lng = 0

        while (index < len) {
            var b: Int
            var shift = 0
            var result = 0
            do {
                b = encoded[index++].code - 63
                result = result or (b and 0x1f shl shift)
                shift += 5
            } while (b >= 0x20)
            val dlat = if (result and 1 != 0) (result shr 1).inv() else result shr 1
            lat += dlat

            shift = 0
            result = 0
            do {
                b = encoded[index++].code - 63
                result = result or (b and 0x1f shl shift)
                shift += 5
            } while (b >= 0x20)
            val dlng = if (result and 1 != 0) (result shr 1).inv() else result shr 1
            lng += dlng

            poly.add(LatLng(lat.toDouble() / 1E5, lng.toDouble() / 1E5))
        }
        return poly
    }
}
