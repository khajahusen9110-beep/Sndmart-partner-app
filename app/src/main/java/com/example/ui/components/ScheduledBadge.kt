package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.data.model.DeliveryAssignment
import com.example.util.ScheduleLabel

/** "Scheduled: Kal 4:00-6:00 PM" strip for a scheduled order; draws nothing for normal orders. */
@Composable
fun ScheduledBadge(assignment: DeliveryAssignment, modifier: Modifier = Modifier) {
    val o = assignment.orders ?: return
    if (!ScheduleLabel.isScheduled(o.deliveryType, o.scheduledFor)) return
    val window = ScheduleLabel.window(o.scheduledFor, o.scheduledUntil) ?: return
    Row(
        modifier = modifier.clip(RoundedCornerShape(8.dp)).background(Color(0xFFFFF3E0)).padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Default.Schedule, contentDescription = null, tint = Color(0xFFE65100), modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(6.dp))
        Text("Scheduled: $window", style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold), color = Color(0xFFE65100))
    }
    val ready = ScheduleLabel.readyFrom(o.prepareFrom)
    if (ready != null && assignment.status in listOf("assigned", "accepted", "arrived_pickup")) {
        Text(
            "Order ready from $ready. Do not reach the shop earlier.",
            style = MaterialTheme.typography.labelSmall, color = Color(0xFFE65100),
            modifier = Modifier.padding(start = 8.dp, top = 2.dp)
        )
    }
}
