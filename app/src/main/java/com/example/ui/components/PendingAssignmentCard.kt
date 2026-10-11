package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
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
import com.example.ui.theme.*
import com.example.util.ServerClock
import com.example.util.parseIsoMillis
import com.example.util.paymentLabel
import kotlinx.coroutines.delay
import java.util.*

@Composable
fun PendingAssignmentCard(
    assignment: DeliveryAssignment,
    onAccept: () -> Unit,
    onReject: () -> Unit,
    modifier: Modifier = Modifier,
    onTimeout: () -> Unit = onReject
) {
    val initialSeconds = remember(assignment.id) {
        calculateRemainingWindow(assignment.expiresAt, assignment.assignedAt ?: assignment.createdAt, assignment.expiresInSeconds)
    }
    var secondsLeft by remember(assignment.id) { mutableIntStateOf(initialSeconds) }

    LaunchedEffect(assignment.id) {
        while (secondsLeft > 0) {
            delay(1000)
            secondsLeft -= 1
        }
        if (secondsLeft <= 0) {
            onTimeout()
        }
    }

    val progress = (secondsLeft / 60f).coerceIn(0f, 1f)
    val isUrgent = secondsLeft <= 15

    Card(
        modifier = modifier
            .fillMaxWidth()
            .testTag("home_incoming_alert_banner"),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isUrgent) Color(0xFFFFFBEB) else Color(0xFFF0FDF4)
        ),
        border = androidx.compose.foundation.BorderStroke(
            2.dp,
            if (isUrgent) AmberAccent else EmeraldPrimary
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 6.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            // Header: Status Badge & Live Countdown Timer
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = if (isUrgent) AmberAccent.copy(alpha = 0.2f) else EmeraldContainer,
                    modifier = Modifier.padding(bottom = 4.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.FlashOn,
                            contentDescription = null,
                            tint = if (isUrgent) Color(0xFFB45309) else EmeraldPrimary,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "NEW ORDER AVAILABLE",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = FontWeight.ExtraBold,
                                letterSpacing = 0.5.sp,
                                color = if (isUrgent) Color(0xFFB45309) else EmeraldOnContainer
                            )
                        )
                    }
                }

                // Countdown Timer Pill matching real expires_at time
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = if (isUrgent) StatusRejectedRed else Slate900
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Timer,
                            contentDescription = "Accept Window",
                            tint = Color.White,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "${secondsLeft}s left",
                            style = MaterialTheme.typography.labelMedium.copy(
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Progress bar showing time running out in the 60s window
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp)),
                color = if (isUrgent) StatusRejectedRed else EmeraldPrimary,
                trackColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f)
            )

            Spacer(modifier = Modifier.height(14.dp))

            // Fee Callout & Order Details
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "Order #${assignment.orderNumber}",
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold,
                            color = Slate900
                        )
                    )
                    Text(
                        text = "${assignment.itemCount} Items • ${paymentLabel(assignment.paymentMode, assignment.paymentStatus)}",
                        style = MaterialTheme.typography.bodySmall.copy(
                            color = Slate700
                        )
                    )
                }

                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = "₹${assignment.deliveryFee.toInt()}",
                        style = MaterialTheme.typography.headlineSmall.copy(
                            fontWeight = FontWeight.ExtraBold,
                            color = EmeraldPrimary
                        )
                    )
                    Text(
                        text = "Estimated Earning",
                        style = MaterialTheme.typography.labelSmall.copy(
                            color = Slate700
                        )
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Route Details Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    // Store / Pickup
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(24.dp)
                                .clip(CircleShape)
                                .background(TealSecondary),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Storefront,
                                contentDescription = "Pickup",
                                tint = Color.White,
                                modifier = Modifier.size(14.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = assignment.storeName,
                                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "(${assignment.estimatedPickupDistanceKm} km)",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.outline
                                )
                            }
                            Text(
                                text = assignment.storeAddress,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 8.dp),
                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)
                    )

                    // Customer / Drop
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(24.dp)
                                .clip(CircleShape)
                                .background(AmberAccent),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.LocationOn,
                                contentDescription = "Drop",
                                tint = Color.White,
                                modifier = Modifier.size(14.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = assignment.customerName,
                                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "(${assignment.estimatedDropDistanceKm} km drop)",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.outline
                                )
                            }
                            Text(
                                text = assignment.customerAddress,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Action Buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedButton(
                    onClick = onReject,
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .testTag("reject_assignment_button"),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = StatusRejectedRed),
                    border = androidx.compose.foundation.BorderStroke(1.dp, StatusRejectedRed.copy(alpha = 0.5f))
                ) {
                    Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Pass", fontWeight = FontWeight.Bold)
                }

                Button(
                    onClick = onAccept,
                    modifier = Modifier
                        .weight(1.6f)
                        .height(48.dp)
                        .testTag("accept_assignment_button"),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = EmeraldPrimary,
                        contentColor = Color.White
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("ACCEPT ORDER", fontWeight = FontWeight.ExtraBold)
                }
            }
        }
    }
}

private fun calculateRemainingWindow(expiresAtIso: String?, createdAtIso: String?, defaultDuration: Int = 60): Int {
    val now = ServerClock.now()
    parseIsoMillis(expiresAtIso)?.let { return ((it - now) / 1000).toInt().coerceIn(0, 120) }
    parseIsoMillis(createdAtIso)?.let {
        val window = defaultDuration.coerceIn(30, 120)
        return (window - ((now - it) / 1000).toInt()).coerceIn(0, window)
    }
    return defaultDuration.coerceIn(5, 60)
}
