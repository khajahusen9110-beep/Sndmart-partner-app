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
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.core.app.ActivityCompat
import com.example.data.location.LocationTracker
import com.example.data.model.DeliveryAssignment
import com.example.data.model.DeliveryBatch
import com.example.data.model.DeliveryPartner
import com.example.ui.MainViewModel
import com.example.ui.components.*
import com.example.ui.theme.*
import com.example.util.paymentLabel

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
    val assignments by viewModel.assignments.collectAsState()

    val pendingOrder = incomingAlert ?: assignments.firstOrNull {
        it.status == "assigned" && !viewModel.isAssignmentHandled(it.id) && !viewModel.isAssignmentExpired(it)
    }

    // Location, notifications and full-screen alerts are required to go online; background
    // location and battery are recommended and asked once per session.
    val missingRequirements = rememberMissingGoOnlineRequirements()
    val missingRequired = missingRequirements.filter { it.required }
    var askedRecommended by rememberSaveable { mutableStateOf(setOf<String>()) }
    var showSetup by remember { mutableStateOf(false) }
    val locationProblem by viewModel.locationNotUpdating.collectAsState()
    val autoOfflineNotice by viewModel.autoOfflineNotice.collectAsState()
    val cashStatus by viewModel.cashStatus.collectAsState()

    fun openSettings(intent: Intent, fallback: Intent? = null) {
        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            fallback?.let { runCatching { context.startActivity(it) } }
        }
    }

    // A permanently denied runtime permission returns at once with no dialog: open Settings instead.
    fun onRuntimeDenied(permission: String) {
        val activity = context.findActivity()
        if (activity != null && !ActivityCompat.shouldShowRequestPermissionRationale(activity, permission)) {
            openSettings(GoOnlinePermissions.appDetailsIntent(context))
        }
    }

    val fineLocationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        if (result[Manifest.permission.ACCESS_FINE_LOCATION] != true) {
            viewModel.onLocationPermissionDenied()
            onRuntimeDenied(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }
    val backgroundLocationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            onRuntimeDenied(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        }
    }
    val notificationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            onRuntimeDenied(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    fun request(req: GoOnlineRequirement) {
        if (!req.required) askedRecommended = askedRecommended + req.name
        when (req) {
            GoOnlineRequirement.LOCATION -> fineLocationLauncher.launch(
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            )
            GoOnlineRequirement.BACKGROUND_LOCATION -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Android only allows asking after foreground location; Android 11+ opens the
                // app's location page where the partner picks "Allow all the time".
                backgroundLocationLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            }
            GoOnlineRequirement.NOTIFICATIONS -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                // Granted but switched off in the system settings.
                openSettings(GoOnlinePermissions.settingsIntent(context, req), GoOnlinePermissions.appDetailsIntent(context))
            }
            GoOnlineRequirement.BATTERY -> openSettings(
                GoOnlinePermissions.settingsIntent(context, req),
                Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            else -> openSettings(GoOnlinePermissions.settingsIntent(context, req), GoOnlinePermissions.appDetailsIntent(context))
        }
    }

    fun closeSetup() {
        showSetup = false
        if (GoOnlinePermissions.needsAutostartTip()) viewModel.prefs.autostartTipShown = true
    }

    /** Toggle on / "Go online": checklist first while anything is missing, then location + RPC. */
    fun startGoOnline() {
        val unaskedRecommended = missingRequirements.any { !it.required && it.name !in askedRecommended }
        if (missingRequired.isNotEmpty() || unaskedRecommended) {
            showSetup = true
        } else {
            viewModel.goOnlineWithLocation()
        }
    }

    if (showSetup) {
        GoOnlineSetupDialog(
            missing = missingRequirements,
            showAutostartTip = GoOnlinePermissions.needsAutostartTip() && !viewModel.prefs.autostartTipShown,
            onRequest = { request(it) },
            onGoOnline = {
                askedRecommended = askedRecommended + missingRequirements.filter { !it.required }.map { it.name }
                closeSetup()
                viewModel.goOnlineWithLocation()
            },
            onDismiss = { closeSetup() }
        )
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        cashStatus?.takeIf { it.blocked }?.let { status ->
            item { CashLimitBanner(status) }
        }

        if (autoOfflineNotice && !isOnline) {
            item {
                AutoOfflineBanner(
                    onGoOnline = { startGoOnline() },
                    onDismiss = { viewModel.dismissAutoOfflineNotice() }
                )
            }
        }

        if (isOnline) {
            locationProblem?.let { problem ->
                item {
                    LocationNotUpdatingBanner(onFix = {
                        when (problem) {
                            LocationTracker.Problem.NO_PERMISSION ->
                                openSettings(GoOnlinePermissions.appDetailsIntent(context))
                            LocationTracker.Problem.NETWORK ->
                                viewModel.showMessage("No internet connection. Turn on mobile data or Wi-Fi.")
                            else -> openSettings(
                                Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                                GoOnlinePermissions.appDetailsIntent(context)
                            )
                        }
                    })
                }
            }
        }

        if (missingRequired.isNotEmpty()) {
            item {
                OrderAlertSetupBanner(
                    missingCount = missingRequired.size,
                    onFix = { showSetup = true }
                )
            }
        }

        // Realtime Incoming Order Alert Banner on Home Tab with 60s live countdown
        pendingOrder?.let { alert ->
            item {
                PendingAssignmentCard(
                    assignment = alert,
                    onAccept = { viewModel.acceptOrder(alert) },
                    onReject = { viewModel.rejectOrder(alert.id) },
                    onTimeout = { viewModel.onOfferTimeout(alert.id) }
                )
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
                                    startGoOnline()
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
                    text = "${assignment.itemCount} items • ${paymentLabel(assignment.paymentMode, assignment.paymentStatus)}",
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

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
