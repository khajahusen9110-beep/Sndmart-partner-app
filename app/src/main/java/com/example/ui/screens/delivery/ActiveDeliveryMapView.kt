package com.example.ui.screens.delivery

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.example.data.location.DirectionsRouteInfo
import com.example.data.location.GoogleDirectionsService
import com.example.data.model.DeliveryAssignment
import com.example.ui.theme.*
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.MapView
import com.google.android.gms.maps.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun ActiveDeliveryMapView(
    assignment: DeliveryAssignment,
    partnerCurrentLat: Double,
    partnerCurrentLng: Double,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val coroutineScope = rememberCoroutineScope()

    // Determine target based on status
    val isHeadingToPickup = assignment.status in listOf("assigned", "accepted")
    val targetLat = if (isHeadingToPickup) assignment.storeLat else assignment.customerLat
    val targetLng = if (isHeadingToPickup) assignment.storeLng else assignment.customerLng
    val targetName = if (isHeadingToPickup) assignment.storeName else assignment.customerName

    // Directions route state
    var routeInfo by remember { mutableStateOf<DirectionsRouteInfo?>(null) }
    var isLoadingRoute by remember { mutableStateOf(false) }

    // Map instances
    var googleMapInstance by remember { mutableStateOf<GoogleMap?>(null) }
    val mapView = remember { MapView(context) }

    // Manage MapView Lifecycle
    DisposableEffect(lifecycleOwner, mapView) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_CREATE -> mapView.onCreate(Bundle())
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                Lifecycle.Event.ON_DESTROY -> mapView.onDestroy()
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            try {
                mapView.onDestroy()
            } catch (e: Exception) {
                Log.w("ActiveDeliveryMapView", "Error destroying mapView: ${e.message}")
            }
        }
    }

    // Function to render markers and polyline onto GoogleMap
    fun updateMapVisuals(map: GoogleMap, currentRoute: DirectionsRouteInfo?) {
        map.clear()

        val boundsBuilder = LatLngBounds.Builder()
        var hasPoints = false

        // 1. Delivery Partner (Live Position) Marker
        val partnerLatLng = LatLng(partnerCurrentLat, partnerCurrentLng)
        map.addMarker(
            MarkerOptions()
                .position(partnerLatLng)
                .title("You (Live GPS)")
                .snippet("Your current location")
                .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_AZURE))
        )
        boundsBuilder.include(partnerLatLng)
        hasPoints = true

        // 2. Pickup Location (Vendor) Marker
        if (assignment.storeLat != 0.0 && assignment.storeLng != 0.0) {
            val storeLatLng = LatLng(assignment.storeLat, assignment.storeLng)
            map.addMarker(
                MarkerOptions()
                    .position(storeLatLng)
                    .title("Pickup: ${assignment.storeName}")
                    .snippet(assignment.storeAddress)
                    .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_CYAN))
            )
            boundsBuilder.include(storeLatLng)
        }

        // 3. Drop-off Location (Customer) Marker
        if (assignment.customerLat != 0.0 && assignment.customerLng != 0.0) {
            val dropLatLng = LatLng(assignment.customerLat, assignment.customerLng)
            map.addMarker(
                MarkerOptions()
                    .position(dropLatLng)
                    .title("Drop-off: ${assignment.customerName}")
                    .snippet(assignment.customerAddress)
                    .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_ROSE))
            )
            boundsBuilder.include(dropLatLng)
        }

        // 4. Polyline from Directions API
        val pointsToDraw = currentRoute?.points ?: emptyList()
        if (pointsToDraw.isNotEmpty()) {
            val polylineColor = if (isHeadingToPickup) {
                android.graphics.Color.parseColor("#00897B") // Teal for Pickup
            } else {
                android.graphics.Color.parseColor("#D97706") // Amber/Orange for Drop-off
            }

            map.addPolyline(
                PolylineOptions()
                    .addAll(pointsToDraw)
                    .color(polylineColor)
                    .width(14f)
                    .geodesic(true)
            )

            // Include route points in bounds
            for (pt in pointsToDraw) {
                boundsBuilder.include(pt)
            }
        }

        // 5. Fit bounds with smooth camera animation
        if (hasPoints) {
            try {
                val bounds = boundsBuilder.build()
                map.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds, 120))
            } catch (e: Exception) {
                // If bounds calculation fails (e.g., view not laid out yet), center on partner
                map.animateCamera(CameraUpdateFactory.newLatLngZoom(partnerLatLng, 15f))
            }
        }
    }

    // Fetch Directions Route whenever partner location or target changes
    LaunchedEffect(partnerCurrentLat, partnerCurrentLng, targetLat, targetLng, assignment.status) {
        if (targetLat == 0.0 || targetLng == 0.0) return@LaunchedEffect
        isLoadingRoute = true
        val route = GoogleDirectionsService.getRoute(
            originLat = partnerCurrentLat,
            originLng = partnerCurrentLng,
            destLat = targetLat,
            destLng = targetLng,
            waypointLat = if (isHeadingToPickup && assignment.customerLat != 0.0) null else null,
            waypointLng = null
        )
        routeInfo = route
        isLoadingRoute = false

        googleMapInstance?.let { map ->
            updateMapVisuals(map, route)
        }
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .testTag("active_delivery_map_card"),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 3.dp)
    ) {
        Column {
            // Map Container Box
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(280.dp)
                    .clip(RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp))
            ) {
                AndroidView(
                    factory = {
                        mapView.apply {
                            getMapAsync { googleMap ->
                                googleMapInstance = googleMap
                                googleMap.uiSettings.isZoomControlsEnabled = false
                                googleMap.uiSettings.isCompassEnabled = true
                                googleMap.uiSettings.isMyLocationButtonEnabled = false
                                googleMap.uiSettings.isMapToolbarEnabled = false
                                updateMapVisuals(googleMap, routeInfo)
                            }
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )

                // Top Map Overlay: Live status badge
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                    shadowElevation = 3.dp,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(12.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(if (isHeadingToPickup) TealSecondary else AmberAccent)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (isHeadingToPickup) "Route to Pickup" else "Route to Customer",
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }

                // Map Control: Recenter Button
                IconButton(
                    onClick = {
                        googleMapInstance?.let { map ->
                            val target = LatLng(partnerCurrentLat, partnerCurrentLng)
                            map.animateCamera(CameraUpdateFactory.newLatLngZoom(target, 16f))
                        }
                    },
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(12.dp)
                        .size(38.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.92f))
                        .testTag("map_recenter_button")
                ) {
                    Icon(
                        Icons.Default.MyLocation,
                        contentDescription = "Recenter on Me",
                        tint = TealSecondary,
                        modifier = Modifier.size(20.dp)
                    )
                }

                // Map Legend Overlay at Bottom-Start
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.88f),
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(10.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(modifier = Modifier.size(6.dp).clip(CircleShape).background(Color(0xFF2196F3)))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("You", style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp))
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(modifier = Modifier.size(6.dp).clip(CircleShape).background(Color(0xFF00897B)))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Store", style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp))
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(modifier = Modifier.size(6.dp).clip(CircleShape).background(Color(0xFFE53935)))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Customer", style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp))
                        }
                    }
                }
            }

            // Bottom Route Summary & External Google Maps Navigation Button
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (isHeadingToPickup) "Pickup: ${assignment.storeName}" else "Drop: ${assignment.customerName}",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            maxLines = 1
                        )
                        Spacer(modifier = Modifier.height(2.dp))

                        val distanceText = routeInfo?.distanceText ?: "Calculating..."
                        val durationText = routeInfo?.durationText ?: "Calculating..."
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.DirectionsCar,
                                contentDescription = null,
                                tint = if (isHeadingToPickup) TealSecondary else AmberAccent,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "$distanceText • $durationText away",
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }

                    Spacer(modifier = Modifier.width(12.dp))

                    // "Navigate" button that opens Google Maps app directly with pre-filled destination
                    Button(
                        onClick = {
                            val uri = Uri.parse("https://www.google.com/maps/dir/?api=1&destination=$targetLat,$targetLng")
                            val mapIntent = Intent(Intent.ACTION_VIEW, uri).apply {
                                setPackage("com.google.android.apps.maps")
                            }
                            try {
                                context.startActivity(mapIntent)
                            } catch (e: Exception) {
                                // Fallback without package restriction (browser / default map handler)
                                val fallbackIntent = Intent(Intent.ACTION_VIEW, uri)
                                context.startActivity(fallbackIntent)
                            }
                        },
                        modifier = Modifier.testTag("navigate_google_maps_button"),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isHeadingToPickup) TealSecondary else AmberAccent,
                            contentColor = Color.White
                        ),
                        shape = RoundedCornerShape(12.dp),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Navigation,
                            contentDescription = "Navigate in Google Maps",
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Navigate",
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        )
                    }
                }

                // Step Description Box
                Spacer(modifier = Modifier.height(10.dp))
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = if (isHeadingToPickup) Icons.Default.Storefront else Icons.Default.Home,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (isHeadingToPickup) assignment.storeAddress else assignment.customerAddress,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2
                        )
                    }
                }
            }
        }
    }
}
