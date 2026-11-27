package com.example.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.DeliveryAssignment
import com.example.util.isPickedUp
import com.example.util.planPickupRoute

/**
 * A customer who ordered from several shops at once: one rider takes all of them. The trip is planned for the rider: the NEAREST shop
 * first, then the next nearest, the customer last. Every shop shows its address, a navigate button, a call button and its items;
 * the shops already collected are ticked. The customer gives one delivery code for all these orders.
 */
@Composable
fun GroupPickupsCard(
    current: DeliveryAssignment,
    all: List<DeliveryAssignment>,
    partnerLat: Double,
    partnerLng: Double,
    fetchItems: suspend (orderId: String) -> List<com.example.data.model.OrderItem>,
    modifier: Modifier = Modifier
) {
    val gid = current.orders?.groupId ?: return
    val mine = all.filter { it.orders?.groupId == gid && it.status !in setOf("cancelled", "rejected") }
    if (mine.size < 2) return
    val toCollect = mine.filter { !isPickedUp(it) }
    val collected = mine.filter { isPickedUp(it) }
    val route = planPickupRoute(partnerLat, partnerLng, toCollect)
    val cashTotal = mine.filter { (it.orders?.paymentMethod ?: "").lowercase() in setOf("cash", "cod") }.sumOf { it.orders?.totalAmount ?: it.totalAmount }
    val purple = Color(0xFF5B21B6)
    Surface(shape = RoundedCornerShape(14.dp), color = Color(0xFFEDE9FE), modifier = modifier.fillMaxWidth().testTag("group_pickups")) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Multi-shop order: ${mine.size} shops, one customer", fontWeight = FontWeight.ExtraBold, fontSize = 15.sp, color = purple)
            Text("Collect from the nearest shop first, then the next, and go to the customer last. One delivery code for all.", fontSize = 12.sp, color = Color(0xFF4B5563))
            collected.forEach { a -> PickupRow(a, "✓ Collected", null, false, fetchItems) }
            route.forEachIndexed { i, stop ->
                PickupRow(stop.assignment, if (i == 0) "NEXT: go here" else "Then", stop.kmFromPrevious, i == 0, fetchItems, number = collected.size + i + 1)
            }
            Surface(shape = RoundedCornerShape(10.dp), color = Color.White, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(if (route.isEmpty()) "NOW: deliver to the customer" else "Last: the customer", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    Text("${current.customerName}  ${current.customerAddress}".trim(), fontSize = 12.sp, color = Color(0xFF4B5563))
                    OpenMapsButton(current.customerLat, current.customerLng, "Navigate to the customer", Modifier.fillMaxWidth())
                }
            }
            if (cashTotal > 0) Text("Cash to collect from the customer in all: ₹${cashTotal.toInt()}", fontSize = 13.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun PickupRow(
    a: DeliveryAssignment, tag: String, km: Double?, highlight: Boolean,
    fetchItems: suspend (String) -> List<com.example.data.model.OrderItem>, number: Int? = null
) {
    var items by remember(a.orderId) { mutableStateOf<List<com.example.data.model.OrderItem>?>(null) }
    LaunchedEffect(a.orderId) { items = try { fetchItems(a.orderId) } catch (e: Exception) { null } }
    Surface(shape = RoundedCornerShape(10.dp), color = if (highlight) Color(0xFFFEF3C7) else Color.White, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row {
                Text((number?.let { "$it. " } ?: "") + a.storeName + if (a.orderNumber.isNotBlank()) "  #${a.orderNumber}" else "", fontWeight = FontWeight.Bold, fontSize = 14.sp, modifier = Modifier.weight(1f))
                Text(tag, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = if (highlight) Color(0xFFB45309) else Color(0xFF6B7280))
            }
            if (a.storeAddress.isNotBlank()) Text(a.storeAddress + (km?.let { "  ·  ${"%.1f".format(it)} km" } ?: ""), fontSize = 12.sp, color = Color(0xFF4B5563))
            items?.takeIf { it.isNotEmpty() }?.let { list ->
                Text(list.joinToString(", ") { "${it.quantity.toInt()}× ${it.productName}" + if (!it.variantLabel.isNullOrBlank()) " (${it.variantLabel})" else "" }, fontSize = 12.sp)
            }
            if (!isPickedUp(a)) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OpenMapsButton(a.storeLat, a.storeLng, a.storeName, Modifier.weight(1f))
                if (a.storePhone.isNotBlank()) PhoneCallButton(a.storePhone, Modifier.weight(1f))
            }
        }
    }
}
