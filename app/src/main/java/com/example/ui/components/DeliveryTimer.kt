package com.example.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.data.model.DeliveryAssignment
import com.example.ui.theme.EmeraldPrimary
import com.example.util.ServerClock
import com.example.util.deliveryTimerState
import com.example.util.parseIsoMillis
import kotlinx.coroutines.delay

/**
 * Delivery countdown, shown once the order is picked up. The server restarts
 * estimated_delivery_at at pickup (store -> customer time), so this is the partner's own clock.
 */
@Composable
fun DeliveryTimerCard(assignment: DeliveryAssignment) {
    if (assignment.status !in listOf("picked_up", "out_for_delivery", "arrived_drop")) return
    val deadline = parseIsoMillis(assignment.estimatedDeliveryAt) ?: return

    var now by remember { mutableLongStateOf(ServerClock.now()) }
    LaunchedEffect(deadline) {
        while (true) {
            now = ServerClock.now()
            delay(1_000L)
        }
    }
    val state = deliveryTimerState(deadline, now)
    val color = if (state.isLate) MaterialTheme.colorScheme.error else EmeraldPrimary

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("delivery_timer"),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (state.isLate) MaterialTheme.colorScheme.errorContainer
            else EmeraldPrimary.copy(alpha = 0.12f)
        )
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Timer, contentDescription = null, tint = color, modifier = Modifier.size(32.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    if (state.isLate) "Delivery is late" else "Deliver within",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold
                )
                assignment.estimatedDeliveryMinutes?.let {
                    Text(
                        "$it min allowed from pickup",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            }
            Text(
                (if (state.isLate) "+" else "") + state.text,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.ExtraBold,
                color = color
            )
        }
    }
}
