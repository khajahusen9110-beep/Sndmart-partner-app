package com.example.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.data.model.OrderLiveLocation
import com.example.ui.MainViewModel

/** Customer's real GPS position from order time; hidden when the customer did not share one. */
@Composable
fun CustomerLiveLocation(viewModel: MainViewModel, orderId: String, modifier: Modifier = Modifier) {
    var loc by remember(orderId) { mutableStateOf<OrderLiveLocation?>(null) }
    LaunchedEffect(orderId) { loc = viewModel.repository.getOrderLiveLocation(orderId) }
    val l = loc ?: return
    val acc = l.accuracyM
    val (chipColor, chipText) = when {
        acc == null -> Color(0xFF757575) to "Accuracy unknown"
        acc <= 30 -> Color(0xFF2E7D32) to "±${acc.toInt()} m · Accurate"
        acc <= 100 -> Color(0xFFF9A825) to "±${acc.toInt()} m · Approx"
        else -> Color(0xFFC62828) to "±${acc.toInt()} m · Low accuracy"
    }
    val far = (l.distanceFromAddressM ?: 0.0) > 150
    Surface(shape = RoundedCornerShape(12.dp), color = chipColor.copy(alpha = 0.10f), modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text("📍 Customer's live location", style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold), modifier = Modifier.weight(1f))
                Text(chipText, style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold), color = chipColor)
            }
            if (far) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "Saved address pin is ${l.distanceFromAddressM!!.toInt()} m away from where the customer ordered — use the live location.",
                    style = MaterialTheme.typography.bodySmall, color = Color(0xFFC62828)
                )
            }
            Spacer(Modifier.height(8.dp))
            OpenMapsButton(destinationLat = l.lat, destinationLng = l.lng, label = "Navigate to live location", modifier = Modifier.fillMaxWidth())
        }
    }
}
