package com.example.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.DeliveryAssignment

/**
 * A customer who ordered from several shops at once: one rider takes all of them. Lists every pickup of the group with its
 * state and the cash to collect in total; the customer gives one delivery code for all.
 */
@Composable
fun GroupPickupsCard(current: DeliveryAssignment, all: List<DeliveryAssignment>, modifier: Modifier = Modifier) {
    val gid = current.orders?.groupId ?: return
    val mine = all.filter { it.orders?.groupId == gid && it.status !in setOf("cancelled", "rejected") }
    if (mine.size < 2) return
    val cashTotal = mine.filter { (it.orders?.paymentMethod ?: "").lowercase() in setOf("cash", "cod") }.sumOf { it.orders?.totalAmount ?: it.totalAmount }
    Surface(shape = RoundedCornerShape(14.dp), color = Color(0xFFEDE9FE), modifier = modifier.fillMaxWidth().testTag("group_pickups")) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Multi-shop order: ${mine.size} pickups, one customer", fontWeight = FontWeight.ExtraBold, fontSize = 14.sp, color = Color(0xFF5B21B6))
            mine.forEach { a ->
                val done = a.status in setOf("picked_up", "out_for_delivery", "arrived_drop", "delivered")
                Row {
                    Text((if (done) "✓ " else "• ") + a.storeName + if (a.orderNumber.isNotBlank()) "  #${a.orderNumber}" else "", fontSize = 13.sp, modifier = Modifier.weight(1f),
                        fontWeight = if (a.id == current.id) FontWeight.Bold else FontWeight.Normal)
                    Text(if (done) "picked up" else a.status.replace('_', ' '), fontSize = 12.sp, color = Color(0xFF6B7280))
                }
            }
            if (cashTotal > 0) Text("Cash to collect from the customer in all: ₹${cashTotal.toInt()}", fontSize = 13.sp, fontWeight = FontWeight.Bold)
            Text("Pick up from every shop first, then go to the customer. The customer gives one delivery code for all these orders.", fontSize = 12.sp, color = Color(0xFF4B5563))
        }
    }
}
