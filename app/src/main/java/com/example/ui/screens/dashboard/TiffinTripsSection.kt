package com.example.ui.screens.dashboard

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.data.model.TiffinStop
import com.example.data.model.TiffinTrip
import com.example.ui.MainViewModel
import com.example.ui.components.OpenMapsButton
import com.example.ui.components.PhoneCallButton
import com.example.ui.theme.*
import com.example.util.ImageUtils
import kotlinx.coroutines.launch

/**
 * Today's Tiffin Pickup runs: collect a home-made tiffin at the customer's home and hand it over at
 * the school/office (or bring the empty box back). Shows nothing when there are no trips today.
 */
@Composable
fun TiffinTripsSection(viewModel: MainViewModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repo = viewModel.repository
    var trips by remember { mutableStateOf<List<TiffinTrip>>(emptyList()) }
    var busyTripId by remember { mutableStateOf<String?>(null) }
    var refreshTick by remember { mutableIntStateOf(0) }

    LaunchedEffect(refreshTick) {
        repo.getTiffinTrips().onSuccess { trips = it }
    }
    if (trips.isEmpty()) return

    // Photo capture for the handover. The trip being photographed survives the camera app.
    var photoTripId by rememberSaveable { mutableStateOf<String?>(null) }
    var captureUri by rememberSaveable { mutableStateOf<String?>(null) }

    fun deliverWithPhoto(tripId: String, bytes: ByteArray) {
        busyTripId = tripId
        scope.launch {
            val upload = repo.uploadProofPhoto(tripId, "tiffin", bytes)
            val path = upload.getOrNull()
            if (path == null) {
                viewModel.showMessage(upload.exceptionOrNull()?.message ?: "Photo upload failed, please try again")
            } else {
                repo.updateTiffinTrip(tripId, "delivered", path)
                    .onSuccess { viewModel.showMessage("Delivered. Thank you!") }
                    .onFailure { viewModel.showMessage(it.message ?: "Could not mark delivered") }
            }
            busyTripId = null
            refreshTick++
        }
    }

    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { saved ->
        val uri = captureUri?.let(Uri::parse)
        val id = photoTripId
        if (saved && uri != null && id != null) {
            val bytes = try { ImageUtils.compressAndResizeUri(context, uri) } catch (e: Exception) { null }
            if (bytes != null && bytes.isNotEmpty()) deliverWithPhoto(id, bytes)
            else viewModel.showMessage("Unable to read the photo, please try again")
        }
    }

    fun openCamera() {
        try {
            val uri = ImageUtils.newCaptureUri(context)
            captureUri = uri.toString()
            cameraLauncher.launch(uri)
        } catch (e: ActivityNotFoundException) {
            viewModel.showMessage("No camera app found on this device")
        } catch (e: Exception) {
            viewModel.showMessage("Couldn't open camera, please try again")
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) openCamera() else viewModel.showMessage("Camera permission is needed for the handover photo")
    }

    fun startDelivery(tripId: String) {
        photoTripId = tripId
        val ok = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        if (ok) openCamera() else permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Tiffin pickups today", fontSize = 18.sp, fontWeight = FontWeight.Bold)
        trips.forEach { trip ->
            TiffinTripCard(
                trip = trip,
                busy = busyTripId == trip.id,
                onPickedUp = {
                    busyTripId = trip.id
                    scope.launch {
                        repo.updateTiffinTrip(trip.id, "picked_up")
                            .onSuccess { viewModel.showMessage("Marked as picked up") }
                            .onFailure { viewModel.showMessage(it.message ?: "Could not update") }
                        busyTripId = null
                        refreshTick++
                    }
                },
                onDeliver = { startDelivery(trip.id) }
            )
        }
    }
}

@Composable
private fun TiffinTripCard(trip: TiffinTrip, busy: Boolean, onPickedUp: () -> Unit, onDeliver: () -> Unit) {
    val isReturn = trip.leg == "return"
    val done = trip.status == "delivered"
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = if (done) EmeraldContainer else MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.clip(RoundedCornerShape(8.dp)).background(if (isReturn) AmberContainer else TealContainer)
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                ) {
                    Text(
                        if (isReturn) "Bring empty box back" else "Tiffin to ${kindLabel(trip.kind)}",
                        fontSize = 12.sp, fontWeight = FontWeight.Bold,
                        color = if (isReturn) AmberOnContainer else TealOnContainer
                    )
                }
                Spacer(Modifier.weight(1f))
                Text(timeLabel(trip.scheduledTime), fontWeight = FontWeight.Bold)
            }
            Text(
                trip.recipientName + (trip.recipientDetail?.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""),
                fontSize = 16.sp, fontWeight = FontWeight.SemiBold
            )

            val pickedUp = trip.status == "picked_up" || done
            // Before pickup show where to collect; after pickup show where to hand over.
            if (!done) {
                if (!pickedUp) StopBlock("Collect from", trip.from) else StopBlock("Hand over at", trip.to)
            }
            trip.handoverNote?.takeIf { it.isNotBlank() }?.let {
                Text("Note: $it", fontSize = 13.sp, color = Slate600)
            }

            when (trip.status) {
                "scheduled" -> Button(
                    onClick = onPickedUp, enabled = !busy, modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = EmeraldPrimary)
                ) { Text(if (busy) "Please wait…" else "Picked up", fontWeight = FontWeight.Bold) }
                "picked_up" -> Button(
                    onClick = onDeliver, enabled = !busy, modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = TealSecondary)
                ) { Text(if (busy) "Uploading…" else "Take photo & mark delivered", fontWeight = FontWeight.Bold) }
                "delivered" -> Text("Delivered ✓  · earned ₹${trip.earning.toInt()}", color = EmeraldOnContainer, fontWeight = FontWeight.Bold)
                else -> Text("This trip was ${trip.status}", color = Slate600)
            }
        }
    }
}

@Composable
private fun StopBlock(title: String, stop: TiffinStop?) {
    if (stop == null) return
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, fontSize = 12.sp, color = Slate600)
        Text(listOfNotNull(stop.name, stop.address, stop.landmark?.takeIf { it.isNotBlank() }?.let { "Near $it" }).joinToString("\n"), fontSize = 14.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val lat = stop.lat
            val lng = stop.lng
            if (lat != null && lng != null) OpenMapsButton(lat, lng, stop.name ?: "Stop", Modifier.weight(1f))
            stop.phone?.takeIf { it.isNotBlank() }?.let { PhoneCallButton(it, Modifier.weight(1f)) }
        }
    }
}

private fun kindLabel(kind: String) = when (kind) { "school" -> "school"; "office" -> "office"; else -> "drop point" }

private fun timeLabel(t: String): String {
    val parts = t.split(":")
    val h = parts.getOrNull(0)?.toIntOrNull() ?: return t
    val m = parts.getOrNull(1)?.toIntOrNull() ?: 0
    return "%d:%02d %s".format(((h + 11) % 12) + 1, m, if (h >= 12) "PM" else "AM")
}
