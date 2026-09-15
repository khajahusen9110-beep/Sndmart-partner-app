package com.example.ui.screens.dashboard

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import android.Manifest
import android.content.pm.PackageManager
import com.example.data.model.DeliveryAssignment
import com.example.data.model.DeliveryBatch
import com.example.data.model.DeliveryPartner
import com.example.ui.MainViewModel
import com.example.ui.components.*
import com.example.ui.theme.*

@Composable
fun DashboardScreen(
    partner: DeliveryPartner,
    viewModel: MainViewModel,
    onNavigateToDeliveries: () -> Unit,
    onNavigateToEarnings: () -> Unit,
    onOpenAssignmentDetail: (DeliveryAssignment) -> Unit
) {
    val context = LocalContext.current
    val isOnline = partner.isOnline
    val isAvailable = partner.isAvailable
    val activeAssignments by viewModel.activeAssignments.collectAsState()
    val batches by viewModel.batches.collectAsState()
    val earningsSummary by viewModel.todayEarningsSummary.collectAsState()
    val cashInHand by viewModel.cashInHandBalance.collectAsState()
    val isGpsActive by viewModel.isTrackingLocation.collectAsState()
    val currentLocation by viewModel.currentLocation.collectAsState()
    val incomingAlert by viewModel.incomingAssignmentAlert.collectAsState()

    val locationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val fineGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true
        val coarseGranted = permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (fineGranted || coarseGranted) {
            viewModel.goOnlineWithLocation()
        } else {
            viewModel.onLocationPermissionDenied()
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Realtime Incoming Order Alert Banner on Home Tab
        incomingAlert?.let { alert ->
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("home_incoming_alert_banner"),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = AmberAccent),
                    elevation = CardDefaults.cardElevation(defaultElevation = 6.dp)
                ) {
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
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .size(32.dp)
                                        .clip(CircleShape)
                                        .background(Slate900),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.FlashOn,
                                        contentDescription = "New Order",
                                        tint = AmberAccent,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.width(10.dp))
                                Column {
                                    Text(
                                        text = "NEW ORDER ASSIGNED!",
                                        style = MaterialTheme.typography.labelLarge.copy(
                                            fontWeight = FontWeight.ExtraBold,
                                            color = Slate900
                                        )
                                    )
                                    Text(
                                        text = "Order #${alert.orderNumber} • ${alert.itemCount} Items",
                                        style = MaterialTheme.typography.bodySmall.copy(
                                            fontWeight = FontWeight.SemiBold,
                                            color = Slate800
                                        )
                                    )
                                }
                            }

                            Text(
                                text = "₹${alert.deliveryFee.toInt()}",
                                style = MaterialTheme.typography.titleLarge.copy(
                                    fontWeight = FontWeight.ExtraBold,
                                    color = Slate900
                                )
                            )
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        Text(
                            text = "Pickup: ${alert.storeName} (${alert.estimatedPickupDistanceKm} km) ➔ Drop: ${alert.customerName}",
                            style = MaterialTheme.typography.bodySmall,
                            color = Slate800,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )

                        Spacer(modifier = Modifier.height(12.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(
                                onClick = { viewModel.rejectOrder(alert.id) },
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = Slate900),
                                border = androidx.compose.foundation.BorderStroke(1.dp, Slate900.copy(alpha = 0.5f)),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Text("Pass", fontWeight = FontWeight.Bold)
                            }

                            Button(
                                onClick = { viewModel.acceptOrder(alert) },
                                modifier = Modifier.weight(1.5f),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Slate900,
                                    contentColor = Color.White
                                ),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("ACCEPT NOW", fontWeight = FontWeight.ExtraBold)
                            }
                        }
                    }
                }
            }
        }
        // Online / Offline Hero Toggle Banner
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (isOnline) EmeraldPrimary else Slate800
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 3.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(18.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(14.dp)
                                    .clip(CircleShape)
                                    .background(if (isOnline) Color(0xFF6EE7B7) else Slate400)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = if (isOnline) "YOU ARE ONLINE" else "YOU ARE OFFLINE",
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontWeight = FontWeight.ExtraBold,
                                    color = Color.White
                                )
                            )
                        }

                        Switch(
                            checked = isOnline,
                            onCheckedChange = { targetOnline ->
                                if (targetOnline) {
                                    val hasFine = ContextCompat.checkSelfPermission(
                                        context,
                                        Manifest.permission.ACCESS_FINE_LOCATION
                                    ) == PackageManager.PERMISSION_GRANTED
                                    val hasCoarse = ContextCompat.checkSelfPermission(
                                        context,
                                        Manifest.permission.ACCESS_COARSE_LOCATION
                                    ) == PackageManager.PERMISSION_GRANTED

                                    if (hasFine || hasCoarse) {
                                        viewModel.goOnlineWithLocation()
                                    } else {
                                        locationPermissionLauncher.launch(
                                            arrayOf(
                                                Manifest.permission.ACCESS_FINE_LOCATION,
                                                Manifest.permission.ACCESS_COARSE_LOCATION
                                            )
                                        )
                                    }
                                } else {
                                    viewModel.toggleOffline()
                                }
                            },
                            modifier = Modifier.testTag("online_offline_toggle"),
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = EmeraldPrimary,
                                checkedTrackColor = Color.White,
                                uncheckedThumbColor = Slate400,
                                uncheckedTrackColor = Slate700
                            )
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    Text(
                        text = if (isOnline) {
                            "Receiving quick-commerce orders in your delivery zone. Live GPS: ${"%.4f".format(currentLocation.first)}, ${"%.4f".format(currentLocation.second)} (Updating continuously every 20s)"
                        } else {
                            "Location access is required to receive delivery orders. Switch online above to start receiving assignments."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.9f)
                    )

                    if (isOnline) {
                        Spacer(modifier = Modifier.height(14.dp))
                        Divider(color = Color.White.copy(alpha = 0.2f))
                        Spacer(modifier = Modifier.height(10.dp))

                        // Availability Sub-toggle
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = if (isAvailable) Icons.Default.CheckCircle else Icons.Default.PauseCircle,
                                    contentDescription = null,
                                    tint = if (isAvailable) AmberAccent else Color.White,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = if (isAvailable) "Ready for Orders (Available)" else "Paused New Orders (Busy)",
                                    style = MaterialTheme.typography.labelMedium.copy(
                                        color = Color.White,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                )
                            }

                            FilterChip(
                                selected = isAvailable,
                                onClick = { viewModel.toggleAvailable(!isAvailable) },
                                label = { Text(if (isAvailable) "Available" else "Busy", fontSize = 12.sp) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = AmberAccent,
                                    selectedLabelColor = Slate900,
                                    containerColor = Color.White.copy(alpha = 0.15f),
                                    labelColor = Color.White
                                )
                            )
                        }
                    }
                }
            }
        }

        // Today's Key Metrics
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                StatCard(
                    title = "Today's Earnings",
                    value = "₹${earningsSummary.first.toInt()}",
                    subtitle = "${earningsSummary.second} Delivered",
                    icon = Icons.Default.CurrencyRupee,
                    iconBgColor = EmeraldContainer,
                    iconTint = EmeraldPrimary,
                    modifier = Modifier.weight(1f),
                    onClick = onNavigateToEarnings
                )

                StatCard(
                    title = "Cash in Hand (COD)",
                    value = "₹${cashInHand.toInt()}",
                    subtitle = "To Remit Hub",
                    icon = Icons.Default.AccountBalanceWallet,
                    iconBgColor = AmberContainer,
                    iconTint = AmberAccent,
                    modifier = Modifier.weight(1f),
                    onClick = onNavigateToEarnings
                )
            }
        }

        // Active Tasks / Ongoing Delivery Section
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Active Deliveries (${activeAssignments.size})",
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.Bold
                    )
                )

                if (activeAssignments.isNotEmpty()) {
                    TextButton(onClick = onNavigateToDeliveries) {
                        Text("View All", color = EmeraldPrimary, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        if (activeAssignments.isEmpty()) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Moped,
                            contentDescription = null,
                            tint = Slate400,
                            modifier = Modifier.size(48.dp)
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = if (isOnline) "Waiting for Next Order" else "You are Offline",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                        )
                        Text(
                            text = if (isOnline) "Stay near high-demand darkstores for faster assignment." else "Switch Online above to receive quick-commerce orders.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(14.dp))
                        OutlinedButton(
                            onClick = { viewModel.refreshDeliveries() },
                            modifier = Modifier.testTag("refresh_orders_btn")
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = null, tint = EmeraldPrimary, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Refresh Orders")
                        }
                    }
                }
            }
        } else {
            items(activeAssignments) { assignment ->
                ActiveAssignmentCard(
                    assignment = assignment,
                    onClick = { onOpenAssignmentDetail(assignment) }
                )
            }
        }

        // Multi-order Batches Section
        val activeBatches = batches.filter { it.status == "active" }
        if (activeBatches.isNotEmpty()) {
            item {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Delivery Batches (${activeBatches.size})",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                )
            }

            items(activeBatches) { batch ->
                BatchCard(batch = batch, onClick = onNavigateToDeliveries)
            }
        }

        // Live Telemetry / GPS Quick Card
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            imageVector = Icons.Default.GpsFixed,
                            contentDescription = "GPS",
                            tint = if (isGpsActive) EmeraldPrimary else Slate400,
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = "Live Telemetry: ${String.format("%.4f", currentLocation.first)}, ${String.format("%.4f", currentLocation.second)}",
                                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold)
                            )
                            Text(
                                text = if (isGpsActive) "Updating location every 20s to Supabase" else "Location tracking paused while offline",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.outline
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ActiveAssignmentCard(
    assignment: DeliveryAssignment,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .testTag("active_assignment_card_${assignment.orderNumber}"),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = assignment.orderNumber,
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    DeliveryStatusBadge(status = assignment.status)
                }

                Text(
                    text = "₹${assignment.deliveryFee.toInt()}",
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.ExtraBold,
                        color = EmeraldPrimary
                    )
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Route summary
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Storefront, contentDescription = null, tint = TealSecondary, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = assignment.storeName,
                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.LocationOn, contentDescription = null, tint = AmberAccent, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "${assignment.customerName} • ${assignment.customerAddress}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "${assignment.itemCount} items • ${assignment.paymentMode}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline
                )

                Button(
                    onClick = onClick,
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = EmeraldPrimary)
                ) {
                    Text("GO TO TASK", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
fun BatchCard(
    batch: DeliveryBatch,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = TealContainer.copy(alpha = 0.4f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Layers,
                    contentDescription = "Batch",
                    tint = TealSecondary,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        text = "Batch: ${batch.batchCode}",
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
                    )
                    Text(
                        text = "${batch.totalOrders} Combined Drop Orders",
                        style = MaterialTheme.typography.bodySmall,
                        color = TealOnContainer
                    )
                }
            }

            StatusPill(
                text = batch.status,
                statusColor = TealSecondary,
                bgColor = TealContainer
            )
        }
    }
}
