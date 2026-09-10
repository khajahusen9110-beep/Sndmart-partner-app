package com.example.ui.screens.delivery

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.DeliveryAssignment
import com.example.ui.MainViewModel
import com.example.ui.components.*
import com.example.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActiveDeliveryDetailScreen(
    assignment: DeliveryAssignment,
    viewModel: MainViewModel,
    onBack: () -> Unit
) {
    val focusManager = LocalFocusManager.current
    var deliveryOtp by remember { mutableStateOf("") }
    var pickupPhotoCaptured by remember { mutableStateOf(assignment.pickupPhotoUrl != null) }
    var dropPhotoCaptured by remember { mutableStateOf(assignment.deliveredPhotoUrl != null) }
    var showCodCashDialog by remember { mutableStateOf(false) }
    val isActionLoading by viewModel.isActionLoading.collectAsState()
    val currentLocation by viewModel.currentLocation.collectAsState()

    val currentStatus = assignment.status

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "Order: ${assignment.orderNumber}",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                        )
                        Text(
                            text = "Payout: ₹${assignment.deliveryFee.toInt()} • ${assignment.paymentMode}",
                            style = MaterialTheme.typography.bodySmall,
                            color = EmeraldPrimary
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    DeliveryStatusBadge(status = currentStatus)
                    Spacer(modifier = Modifier.width(12.dp))
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Google Maps Live Delivery Route with 3 Markers & Directions Route
            ActiveDeliveryMapView(
                assignment = assignment,
                partnerCurrentLat = currentLocation.first,
                partnerCurrentLng = currentLocation.second
            )

            // Step Details Card (Pickup & Drop contact / instructions)
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Map, contentDescription = null, tint = TealSecondary)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Live Delivery Route",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                            )
                        }

                        val targetLat = if (currentStatus in listOf("assigned", "accepted")) assignment.storeLat else assignment.customerLat
                        val targetLng = if (currentStatus in listOf("assigned", "accepted")) assignment.storeLng else assignment.customerLng
                        val targetLabel = if (currentStatus in listOf("assigned", "accepted")) assignment.storeName else assignment.customerName

                        OpenMapsButton(
                            destinationLat = targetLat,
                            destinationLng = targetLng,
                            label = targetLabel
                        )
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // Step 1: Store Pickup Info
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = if (currentStatus in listOf("assigned", "accepted")) TealContainer.copy(alpha = 0.4f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.Top, modifier = Modifier.weight(1f)) {
                                Box(
                                    modifier = Modifier
                                        .size(32.dp)
                                        .clip(CircleShape)
                                        .background(TealSecondary),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(Icons.Default.Storefront, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                                }
                                Spacer(modifier = Modifier.width(10.dp))
                                Column {
                                    Text(text = "1. Pickup: ${assignment.storeName}", style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold))
                                    Text(text = assignment.storeAddress, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            PhoneCallButton(phoneNumber = assignment.storePhone)
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // Step 2: Customer Drop Info
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = if (currentStatus in listOf("picked_up", "out_for_delivery", "arrived_drop")) AmberContainer.copy(alpha = 0.4f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.Top, modifier = Modifier.weight(1f)) {
                                Box(
                                    modifier = Modifier
                                        .size(32.dp)
                                        .clip(CircleShape)
                                        .background(AmberAccent),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(Icons.Default.LocationOn, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                                }
                                Spacer(modifier = Modifier.width(10.dp))
                                Column {
                                    Text(text = "2. Drop: ${assignment.customerName}", style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold))
                                    Text(text = assignment.customerAddress, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            PhoneCallButton(phoneNumber = assignment.customerPhone)
                        }
                    }
                }
            }

            // Order Items & Checklist Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Order Package Checklist (${assignment.itemCount} items)",
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
                        )
                        Surface(
                            color = if (assignment.paymentMode == "COD") AmberContainer else EmeraldContainer,
                            shape = RoundedCornerShape(6.dp)
                        ) {
                            Text(
                                text = if (assignment.paymentMode == "COD") "COLLECT CASH: ₹${assignment.totalAmount.toInt()}" else "PREPAID ORDER",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontWeight = FontWeight.Bold,
                                    color = if (assignment.paymentMode == "COD") AmberOnContainer else EmeraldOnContainer
                                ),
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    Text(
                        text = assignment.itemsSummary,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Dynamic Action Steps according to Status
            when (currentStatus) {
                "assigned", "accepted" -> {
                    // Step: Reach store and mark picked up with photo
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(18.dp),
                        colors = CardDefaults.cardColors(containerColor = EmeraldContainer.copy(alpha = 0.2f))
                    ) {
                        Column(modifier = Modifier.padding(18.dp)) {
                            Text(
                                text = "Step 1: Store Pickup Confirmation",
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontWeight = FontWeight.Bold,
                                    color = EmeraldPrimary
                                )
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Verify packed bag with darkstore supervisor and take a photo of the bag.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            Spacer(modifier = Modifier.height(14.dp))

                            // Photo capture toggle
                            OutlinedButton(
                                onClick = { pickupPhotoCaptured = true },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("capture_pickup_photo_button"),
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.outlinedButtonColors(
                                    containerColor = if (pickupPhotoCaptured) EmeraldContainer else Color.Transparent
                                )
                            ) {
                                Icon(
                                    imageVector = if (pickupPhotoCaptured) Icons.Default.CheckCircle else Icons.Default.CameraAlt,
                                    contentDescription = null,
                                    tint = if (pickupPhotoCaptured) EmeraldPrimary else MaterialTheme.colorScheme.onSurface
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = if (pickupPhotoCaptured) "Pickup Photo Attached ✓" else "Take / Upload Bag Photo (Mandatory)",
                                    fontWeight = FontWeight.Bold
                                )
                            }

                            Spacer(modifier = Modifier.height(14.dp))

                            Button(
                                onClick = {
                                    if (!pickupPhotoCaptured) {
                                        viewModel.showMessage("Mandatory: Please photograph the packed order at the counter")
                                        return@Button
                                    }
                                    viewModel.markPickedUp(
                                        assignment.id,
                                        "https://images.unsplash.com/photo-1544717305-2782549b5136?w=600"
                                    )
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(52.dp)
                                    .testTag("mark_picked_up_button"),
                                shape = RoundedCornerShape(12.dp),
                                enabled = !isActionLoading && pickupPhotoCaptured,
                                colors = ButtonDefaults.buttonColors(containerColor = EmeraldPrimary)
                            ) {
                                if (isActionLoading) {
                                    CircularProgressIndicator(modifier = Modifier.size(22.dp), color = Color.White)
                                } else {
                                    Icon(Icons.Default.ShoppingBag, contentDescription = null)
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("CONFIRM PICKUP & START", fontWeight = FontWeight.ExtraBold)
                                }
                            }
                        }
                    }
                }

                "picked_up" -> {
                    // Step: Start navigation towards customer
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(18.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                    ) {
                        Column(modifier = Modifier.padding(18.dp)) {
                            Text(
                                text = "Step 2: Start Delivery Ride",
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontWeight = FontWeight.Bold,
                                    color = EmeraldPrimary
                                )
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Items secured in delivery bag. Navigate to customer drop location.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            Spacer(modifier = Modifier.height(16.dp))

                            Button(
                                onClick = { viewModel.startDelivery(assignment.id) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(52.dp)
                                    .testTag("start_delivery_button"),
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = EmeraldPrimary)
                            ) {
                                Icon(Icons.Default.TwoWheeler, contentDescription = null)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("START DELIVERY RIDE", fontWeight = FontWeight.ExtraBold)
                            }
                        }
                    }
                }

                "out_for_delivery" -> {
                    // Step: Arrived at drop location
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(18.dp),
                        colors = CardDefaults.cardColors(containerColor = AmberContainer.copy(alpha = 0.25f))
                    ) {
                        Column(modifier = Modifier.padding(18.dp)) {
                            Text(
                                text = "Step 3: Arrived at Customer Location",
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontWeight = FontWeight.Bold,
                                    color = AmberAccent
                                )
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Tap when you have arrived at the customer doorstep / building gate.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            Spacer(modifier = Modifier.height(16.dp))

                            Button(
                                onClick = { viewModel.arriveAtDrop(assignment.id) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(52.dp)
                                    .testTag("arrived_at_drop_button"),
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = AmberAccent)
                            ) {
                                Icon(Icons.Default.Place, contentDescription = null)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("I HAVE ARRIVED AT DROP", fontWeight = FontWeight.ExtraBold)
                            }
                        }
                    }
                }

                "arrived_drop" -> {
                    // Step 4: OTP Verification & Delivery Completion
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(18.dp),
                        colors = CardDefaults.cardColors(containerColor = EmeraldContainer.copy(alpha = 0.3f))
                    ) {
                        Column(modifier = Modifier.padding(18.dp)) {
                            Text(
                                text = "Step 4: Customer OTP Verification",
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontWeight = FontWeight.Bold,
                                    color = EmeraldPrimary
                                )
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Ask the customer for their 4-digit delivery verification PIN code.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            Spacer(modifier = Modifier.height(16.dp))

                            OutlinedTextField(
                                value = deliveryOtp,
                                onValueChange = { deliveryOtp = it.take(6) },
                                label = { Text("Enter Customer OTP (e.g. 4821)") },
                                leadingIcon = { Icon(Icons.Default.Key, contentDescription = null) },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("delivery_otp_input"),
                                shape = RoundedCornerShape(12.dp)
                            )

                            Spacer(modifier = Modifier.height(12.dp))

                            // Delivery Doorstep Photo Proof
                            OutlinedButton(
                                onClick = { dropPhotoCaptured = true },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("capture_delivered_photo_button"),
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.outlinedButtonColors(
                                    containerColor = if (dropPhotoCaptured) EmeraldContainer else Color.Transparent
                                )
                            ) {
                                Icon(
                                    imageVector = if (dropPhotoCaptured) Icons.Default.CheckCircle else Icons.Default.CameraAlt,
                                    contentDescription = null,
                                    tint = if (dropPhotoCaptured) EmeraldPrimary else MaterialTheme.colorScheme.onSurface
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = if (dropPhotoCaptured) "Doorstep Photo Attached ✓" else "Take Doorstep Photo (Mandatory)",
                                    fontWeight = FontWeight.Bold
                                )
                            }

                            Spacer(modifier = Modifier.height(16.dp))

                            Button(
                                onClick = {
                                    focusManager.clearFocus()
                                    if (!dropPhotoCaptured) {
                                        viewModel.showMessage("Mandatory: Please photograph the delivered packet at doorstep")
                                        return@Button
                                    }
                                    if (deliveryOtp.length < 4) {
                                        viewModel.showMessage("Please enter valid 4-digit OTP from customer")
                                        return@Button
                                    }
                                    viewModel.completeDelivery(
                                        assignmentId = assignment.id,
                                        otp = deliveryOtp,
                                        photoUrl = "https://images.unsplash.com/photo-1544717305-2782549b5136?w=600"
                                    ) { success ->
                                        if (success) {
                                            if (assignment.paymentMode == "COD") {
                                                showCodCashDialog = true
                                            } else {
                                                onBack()
                                            }
                                        }
                                    }
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(52.dp)
                                    .testTag("complete_delivery_button"),
                                shape = RoundedCornerShape(12.dp),
                                enabled = !isActionLoading && deliveryOtp.length >= 4 && dropPhotoCaptured,
                                colors = ButtonDefaults.buttonColors(containerColor = EmeraldPrimary)
                            ) {
                                if (isActionLoading) {
                                    CircularProgressIndicator(modifier = Modifier.size(22.dp), color = Color.White)
                                } else {
                                    Icon(Icons.Default.CheckCircle, contentDescription = null)
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("VERIFY OTP & COMPLETE DELIVERY", fontWeight = FontWeight.ExtraBold)
                                }
                            }
                        }
                    }
                }

                "delivered" -> {
                    // Completed state banner
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = EmeraldContainer)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(20.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(Icons.Default.CheckCircle, contentDescription = null, tint = EmeraldPrimary, modifier = Modifier.size(48.dp))
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(text = "Order Successfully Delivered!", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold, color = EmeraldOnContainer))
                            Text(text = "+₹${assignment.deliveryFee.toInt()} Net Payout Credited", style = MaterialTheme.typography.bodyMedium, color = EmeraldOnContainer)
                        }
                    }
                }
            }
        }
    }

    if (showCodCashDialog) {
        AlertDialog(
            onDismissRequest = {
                showCodCashDialog = false
                onBack()
            },
            icon = { Icon(Icons.Default.Payments, contentDescription = null, tint = AmberAccent, modifier = Modifier.size(36.dp)) },
            title = { Text("Cash On Delivery Collected") },
            text = {
                Column {
                    Text("Did you collect ₹${assignment.totalAmount.toInt()} in cash from customer ${assignment.customerName}?")
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "This entry will be recorded in your Cash Ledger until remitted at Sndmart Hub.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.logCashCollected(
                            orderId = assignment.orderId,
                            orderNumber = assignment.orderNumber,
                            amount = assignment.totalAmount,
                            notes = "COD collected for ${assignment.orderNumber}"
                        )
                        showCodCashDialog = false
                        onBack()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = EmeraldPrimary)
                ) {
                    Text("Confirm ₹${assignment.totalAmount.toInt()} Received")
                }
            }
        )
    }
}
