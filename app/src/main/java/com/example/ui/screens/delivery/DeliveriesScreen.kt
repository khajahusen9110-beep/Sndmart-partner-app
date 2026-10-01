package com.example.ui.screens.delivery

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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.DeliveryAssignment
import com.example.data.model.DeliveryBatch
import com.example.ui.MainViewModel
import com.example.ui.components.*
import com.example.ui.theme.*
import com.example.util.isCashOnDelivery

@Composable
fun DeliveriesScreen(
    viewModel: MainViewModel,
    onOpenAssignmentDetail: (DeliveryAssignment) -> Unit
) {
    var selectedTab by remember { mutableIntStateOf(0) }
    val tabs = listOf("Active Tasks", "Batches", "Delivered")

    val assignments by viewModel.assignments.collectAsState()
    val batches by viewModel.batches.collectAsState()
    val isDataLoading by viewModel.isDataLoading.collectAsState()
    val canLoadMore by viewModel.canLoadMoreDeliveries.collectAsState()
    val isLoadingMore by viewModel.isLoadingMoreDeliveries.collectAsState()
    val incomingAlert by viewModel.incomingAssignmentAlert.collectAsState()

    val pendingOrder = incomingAlert ?: assignments.firstOrNull {
        it.status == "assigned" && !viewModel.isAssignmentHandled(it.id) && !viewModel.isAssignmentExpired(it)
    }

    val activeAssignments = assignments.filter {
        it.status in listOf("accepted", "arrived_pickup", "picked_up", "out_for_delivery", "arrived_drop")
    }
    val deliveredAssignments = assignments.filter { it.status == "delivered" }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // Tab row
        TabRow(
            selectedTabIndex = selectedTab,
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = EmeraldPrimary
        ) {
            tabs.forEachIndexed { index, title ->
                val count = when (index) {
                    0 -> activeAssignments.size
                    1 -> batches.size
                    else -> deliveredAssignments.size
                }
                Tab(
                    selected = selectedTab == index,
                    onClick = { selectedTab = index },
                    text = {
                        Text(
                            text = "$title ($count)",
                            fontWeight = if (selectedTab == index) FontWeight.Bold else FontWeight.Normal
                        )
                    }
                )
            }
        }

        if (isDataLoading && assignments.isEmpty()) {
            DeliveriesListSkeleton(count = 3)
        } else {
            when (selectedTab) {
                0 -> {
                    // Active Assignments Tab
                    if (activeAssignments.isEmpty() && pendingOrder == null) {
                        EmptyDeliveriesState(
                            title = "No Active Deliveries",
                            subtitle = "When you accept an assignment, it will appear here with live navigation and pickup steps.",
                            onRefresh = { viewModel.refreshDeliveries() }
                        )
                    } else {
                        LazyColumn(
                            contentPadding = PaddingValues(16.dp),
                            verticalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            pendingOrder?.let { alert ->
                                item {
                                    PendingAssignmentCard(
                                        assignment = alert,
                                        onAccept = { viewModel.acceptOrder(alert) },
                                        onReject = { viewModel.rejectOrder(alert.id) }
                                    )
                                }
                            }
                            items(activeAssignments) { assignment ->
                                ActiveDeliveryCard(
                                    assignment = assignment,
                                    onClick = { onOpenAssignmentDetail(assignment) }
                                )
                            }
                        }
                    }
                }

                1 -> {
                    // Multi-order Batches Tab
                    if (batches.isEmpty()) {
                        EmptyDeliveriesState(
                            title = "No Multi-Order Batches",
                            subtitle = "When the system groups nearby pickups into a single multi-drop route, batches appear here.",
                            onRefresh = null
                        )
                    } else {
                        LazyColumn(
                            contentPadding = PaddingValues(16.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            items(batches) { batch ->
                                BatchDetailCard(
                                    batch = batch,
                                    onSelectAssignment = onOpenAssignmentDetail
                                )
                            }
                        }
                    }
                }

                2 -> {
                    // Delivered History Tab
                    if (deliveredAssignments.isEmpty()) {
                        EmptyDeliveriesState(
                            title = "No Completed Deliveries Yet",
                            subtitle = "Completed tasks with earnings and customer OTP verification will be archived here.",
                            onRefresh = null
                        )
                    } else {
                        LazyColumn(
                            contentPadding = PaddingValues(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            items(deliveredAssignments) { assignment ->
                                DeliveredHistoryCard(assignment = assignment)
                            }
                            if (canLoadMore || isLoadingMore) {
                                item {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 12.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        if (isLoadingMore) {
                                            CircularProgressIndicator(
                                                modifier = Modifier.size(28.dp),
                                                strokeWidth = 3.dp,
                                                color = MaterialTheme.colorScheme.primary
                                            )
                                        } else {
                                            OutlinedButton(
                                                onClick = { viewModel.loadMoreDeliveries() },
                                                shape = RoundedCornerShape(20.dp),
                                                modifier = Modifier.testTag("load_more_deliveries_btn")
                                            ) {
                                                Icon(
                                                    Icons.Default.Refresh,
                                                    contentDescription = "Load More",
                                                    modifier = Modifier.size(16.dp)
                                                )
                                                Spacer(modifier = Modifier.width(8.dp))
                                                Text("Load Earlier Deliveries")
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ActiveDeliveryCard(
    assignment: DeliveryAssignment,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .testTag("delivery_card_${assignment.orderNumber}"),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Header
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
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontWeight = FontWeight.ExtraBold,
                        color = EmeraldPrimary
                    )
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Pickup Row
            Row(verticalAlignment = Alignment.Top) {
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .clip(CircleShape)
                        .background(TealContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.Storefront, contentDescription = null, tint = TealSecondary, modifier = Modifier.size(14.dp))
                }
                Spacer(modifier = Modifier.width(10.dp))
                Column {
                    Text(text = "Pickup: ${assignment.storeName}", style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold))
                    Text(text = assignment.storeAddress, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Drop Row
            Row(verticalAlignment = Alignment.Top) {
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .clip(CircleShape)
                        .background(AmberContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.Place, contentDescription = null, tint = AmberAccent, modifier = Modifier.size(14.dp))
                }
                Spacer(modifier = Modifier.width(10.dp))
                Column {
                    Text(text = "Drop: ${assignment.customerName}", style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold))
                    Text(text = assignment.customerAddress, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    color = if (isCashOnDelivery(assignment.paymentMode)) AmberContainer else Slate100,
                    shape = RoundedCornerShape(6.dp)
                ) {
                    Text(
                        text = "${assignment.paymentMode} • ₹${assignment.totalAmount.toInt()}",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = FontWeight.Bold,
                            color = if (isCashOnDelivery(assignment.paymentMode)) AmberOnContainer else Slate700
                        ),
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                }

                Button(
                    onClick = onClick,
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = EmeraldPrimary)
                ) {
                    Text("START / COMPLETE", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
fun BatchDetailCard(
    batch: DeliveryBatch,
    onSelectAssignment: (DeliveryAssignment) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
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
                    Icon(Icons.Default.Layers, contentDescription = null, tint = TealSecondary)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(text = batch.batchCode, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
                }
                StatusPill(text = batch.status, statusColor = TealSecondary, bgColor = TealContainer)
            }

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = "${batch.totalOrders} Orders in this Batch Route:",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(8.dp))

            batch.assignments.forEachIndexed { idx, item ->
                Surface(
                    onClick = { onSelectAssignment(item) },
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(text = "${idx + 1}. ${item.orderNumber} • ${item.customerName}", style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold))
                            Text(text = item.customerAddress, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        DeliveryStatusBadge(status = item.status)
                    }
                }
            }
        }
    }
}

@Composable
fun DeliveredHistoryCard(
    assignment: DeliveryAssignment
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .background(EmeraldContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.CheckCircle, contentDescription = null, tint = EmeraldPrimary, modifier = Modifier.size(20.dp))
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(text = assignment.orderNumber, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold))
                    Text(text = "${assignment.customerName} • ${assignment.createdAt}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            Column(horizontalAlignment = Alignment.End) {
                Text(text = "+₹${assignment.deliveryFee.toInt()}", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold, color = EmeraldPrimary))
                Text(text = assignment.paymentMode, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
            }
        }
    }
}

@Composable
fun EmptyDeliveriesState(
    title: String,
    subtitle: String,
    onRefresh: (() -> Unit)?
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = Icons.Outlined.Inventory2,
                contentDescription = null,
                tint = Slate400,
                modifier = Modifier.size(64.dp)
            )
            Spacer(modifier = Modifier.height(14.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
            if (onRefresh != null) {
                Spacer(modifier = Modifier.height(18.dp))
                Button(
                    onClick = onRefresh,
                    colors = ButtonDefaults.buttonColors(containerColor = EmeraldPrimary)
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Refresh from Backend")
                }
            }
        }
    }
}
