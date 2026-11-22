package com.example.ui.screens.dashboard

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.HelperJob
import com.example.ui.MainViewModel
import com.example.ui.components.OpenMapsButton
import com.example.ui.components.PhoneCallButton
import com.example.ui.theme.*
import com.example.util.parseIsoMillis
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Quick Helper work: short bookings (30 / 60 / 90 min). A helper sees open requests nearby, accepts one, goes to the
 * customer, starts the work with the customer's start code and ends it with the end code, then marks the cash/UPI as
 * received. Shows nothing for partners who are not helpers and have no bookings.
 */
@Composable
fun HelperSection(viewModel: MainViewModel) {
    val repo = viewModel.repository
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var requests by remember { mutableStateOf<List<HelperJob>>(emptyList()) }
    var mine by remember { mutableStateOf<List<HelperJob>>(emptyList()) }
    var busyId by remember { mutableStateOf<String?>(null) }
    var tick by remember { mutableIntStateOf(0) }
    var codeFor by remember { mutableStateOf<Pair<HelperJob, String>?>(null) } // job + action (start / complete)

    LaunchedEffect(tick) {
        while (true) {
            repo.getHelperRequests().onSuccess { requests = it }
            repo.getHelperBookings().onSuccess { mine = it }
            delay(15_000)
        }
    }

    val active = mine.filter { it.status in setOf("accepted", "on_the_way", "started") }
    val toCollect = mine.filter { it.status == "completed" && !it.paymentCollected }
    // while a booking is in hand, new requests wait
    val open = if (active.isEmpty()) requests else emptyList()
    if (active.isEmpty() && open.isEmpty() && toCollect.isEmpty()) return

    fun act(job: HelperJob, action: String, code: String? = null, done: String) {
        busyId = job.id
        scope.launch {
            repo.updateHelperBooking(job.id, action, code)
                .onSuccess { viewModel.showMessage(done) }
                .onFailure { viewModel.showMessage(it.message ?: "Could not update") }
            busyId = null
            tick++
        }
    }

    fun openPhoto(path: String) {
        scope.launch {
            repo.helperPhotoLink(path).onSuccess { url ->
                try { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                catch (e: ActivityNotFoundException) { viewModel.showMessage("No app to open the photo") }
            }.onFailure { viewModel.showMessage(it.message ?: "Could not open the photo") }
        }
    }

    codeFor?.let { (job, action) ->
        CodeDialog(
            title = if (action == "start") "Enter the start code" else "Enter the end code",
            hint = if (action == "start") "Ask the customer for the code shown in their app" else "Ask the customer for the end code in their app",
            onDismiss = { codeFor = null },
            onSubmit = { code ->
                codeFor = null
                act(job, action, code, if (action == "start") "Work started" else "Work completed")
            }
        )
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Quick Helper", fontSize = 18.sp, fontWeight = FontWeight.Bold)

        active.forEach { job ->
            JobCard(job, busyId == job.id, onPhoto = ::openPhoto) {
                when (job.status) {
                    "accepted" -> ActionButton("I am on the way", busyId == job.id) { act(job, "on_the_way", done = "Customer informed") }
                    "on_the_way" -> ActionButton("Start work (enter code)", busyId == job.id) { codeFor = job to "start" }
                    "started" -> {
                        TimeLeft(job)
                        ActionButton("Finish work (enter code)", busyId == job.id) { codeFor = job to "complete" }
                    }
                }
                if (job.status == "accepted") ActionButton("Start work (enter code)", busyId == job.id) { codeFor = job to "start" }
            }
        }

        toCollect.forEach { job ->
            Card(shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = AmberContainer)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Collect ₹${job.priceTotal.toInt()} from the customer by ${payLabel(job)}", fontWeight = FontWeight.Bold)
                    Text("${job.bookingNumber} · ${payLabel(job)}. You keep ₹${job.helperAmount.toInt()}; the rest is Sndmart's share.", fontSize = 13.sp, color = Slate600)
                    ActionButton("Payment received", busyId == job.id) { act(job, "collected", done = "Saved") }
                }
            }
        }

        open.forEach { job ->
            JobCard(job, busyId == job.id, onPhoto = ::openPhoto) {
                ActionButton("Accept request", busyId == job.id) {
                    busyId = job.id
                    scope.launch {
                        repo.acceptHelperRequest(job.id)
                            .onSuccess { viewModel.showMessage("Accepted. Go to the customer.") }
                            .onFailure { viewModel.showMessage(it.message ?: "Could not accept") }
                        busyId = null
                        tick++
                    }
                }
            }
        }
    }
}

@Composable
private fun JobCard(job: HelperJob, busy: Boolean, onPhoto: (String) -> Unit, actions: @Composable ColumnScope.() -> Unit) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.clip(RoundedCornerShape(8.dp)).background(TealContainer).padding(horizontal = 8.dp, vertical = 3.dp)) {
                    Text("${job.durationMinutes} min · ${job.taskType}", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = TealOnContainer)
                }
                Spacer(Modifier.weight(1f))
                Text("You earn ₹${job.helperAmount.toInt()} · ${payLabel(job)}", fontWeight = FontWeight.Bold)
            }
            Text(job.taskDescription, fontSize = 15.sp)
            val whenText = job.requestedFor?.let { parseIsoMillis(it) }?.let { "Needed at " + SimpleDateFormat("EEE d MMM, h:mm a", Locale.getDefault()).format(Date(it)) } ?: "Needed as soon as possible"
            Text(whenText, fontSize = 12.sp, color = Slate600)
            Text(listOfNotNull(job.customerName, job.addressLine, job.landmark?.takeIf { it.isNotBlank() }?.let { "Near $it" }).joinToString("\n"), fontSize = 14.sp)
            if (job.photoPaths.isNotEmpty() && job.status != "requested") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    job.photoPaths.forEachIndexed { i, p -> OutlinedButton(onClick = { onPhoto(p) }, enabled = !busy) { Text("Photo ${i + 1}") } }
                }
            }
            if (job.status != "requested") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val lat = job.lat
                    val lng = job.lng
                    if (lat != null && lng != null) OpenMapsButton(lat, lng, job.customerName ?: "Customer", Modifier.weight(1f))
                    job.contactPhone?.takeIf { it.isNotBlank() }?.let { PhoneCallButton(it, Modifier.weight(1f)) }
                }
            }
            actions()
        }
    }
}

@Composable
private fun TimeLeft(job: HelperJob) {
    val end = job.endsAt?.let { parseIsoMillis(it) } ?: return
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(end) { while (true) { now = System.currentTimeMillis(); delay(1000) } }
    val s = ((end - now) / 1000).coerceAtLeast(0)
    Text("Time left: %d:%02d".format(s / 60, s % 60), fontWeight = FontWeight.Bold, color = if (s == 0L) AmberOnContainer else Slate600)
}

@Composable
private fun ActionButton(text: String, busy: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick, enabled = !busy, modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp), colors = ButtonDefaults.buttonColors(containerColor = EmeraldPrimary)
    ) { Text(if (busy) "Please wait…" else text, fontWeight = FontWeight.Bold) }
}

@Composable
private fun CodeDialog(title: String, hint: String, onDismiss: () -> Unit, onSubmit: (String) -> Unit) {
    var code by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(hint, fontSize = 13.sp)
                OutlinedTextField(
                    value = code, onValueChange = { code = it.filter(Char::isDigit).take(4) }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword), label = { Text("4-digit code") }
                )
            }
        },
        confirmButton = { TextButton(onClick = { onSubmit(code) }, enabled = code.length == 4) { Text("Confirm") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

private fun payLabel(job: HelperJob) = if (job.paymentMethod == "upi") "UPI" else "cash"
