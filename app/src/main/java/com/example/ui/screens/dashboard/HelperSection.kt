package com.example.ui.screens.dashboard

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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

private val HBlue = androidx.compose.ui.graphics.Color(0xFF2563EB)
private val HBlueSoft = androidx.compose.ui.graphics.Color(0xFFDBEAFE)
private val HBlueDark = androidx.compose.ui.graphics.Color(0xFF1D4ED8)

/**
 * The Helper tab: Quick Helper bookings, kept apart from delivery orders. Requests nearby (they also ring like an order),
 * the work in hand (on the way, start code, end code, extra time), the cash still to collect, and the history.
 */
@Composable
fun HelperTab(viewModel: MainViewModel) {
    val repo = viewModel.repository
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val ui by viewModel.helperUi.collectAsState()
    val requests = ui.requests
    val mine = ui.bookings
    var busyId by remember { mutableStateOf<String?>(null) }
    var codeFor by remember { mutableStateOf<Pair<HelperJob, String>?>(null) } // job + action (start / complete)
    var section by remember { mutableIntStateOf(0) } // 0 requests, 1 my work, 2 history
    val tick = { viewModel.refreshHelper() }
    val here by viewModel.currentLocation.collectAsState()
    fun jobDistance(job: HelperJob): Double? {
        val la = job.liveLat ?: job.lat
        val lo = job.liveLng ?: job.lng
        if (la == null || lo == null || (here.first == 0.0 && here.second == 0.0)) return null
        return com.example.util.distanceKm(here.first, here.second, la, lo)
    }

    val active = ui.active
    val toCollect = ui.toCollect
    val history = mine.filter { it.status !in setOf("accepted", "on_the_way", "started") && !(it.status == "completed" && !it.paymentCollected) }
    // while a booking is in hand, new requests wait
    val open = if (active.isEmpty()) requests else emptyList()

    // a request accepted from the alert, or work that started, brings the customer straight to "My work"
    LaunchedEffect(active.isNotEmpty()) { if (active.isNotEmpty()) section = 1 }

    fun act(job: HelperJob, action: String, code: String? = null, done: String) {
        busyId = job.id
        scope.launch {
            repo.updateHelperBooking(job.id, action, code)
                .onSuccess { viewModel.showMessage(done) }
                .onFailure { viewModel.showMessage(it.message ?: "Could not update") }
            busyId = null
            tick()
        }
    }

    fun answerExt(extId: String, accept: Boolean) {
        scope.launch {
            repo.respondHelperExtension(extId, accept)
                .onSuccess { viewModel.showMessage(if (accept) "Time added" else "Declined") }
                .onFailure { viewModel.showMessage(it.message ?: "Could not answer") }
            tick()
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

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        SummaryRow(ui)
        if (ui.enabled) HelperWorkTypes(viewModel)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = section == 0, onClick = { section = 0 }, label = { Text("Requests (${open.size})") }, colors = helperChipColors())
            FilterChip(selected = section == 1, onClick = { section = 1 }, label = { Text("My work (${active.size + toCollect.size})") }, colors = helperChipColors())
            FilterChip(selected = section == 2, onClick = { section = 2 }, label = { Text("History") }, colors = helperChipColors())
        }

        if (section == 1) {
        if (active.isEmpty() && toCollect.isEmpty()) Text("No work in hand. Accept a request to start.", color = Slate600)
        active.forEach { job ->
            JobCard(job, busyId == job.id, onPhoto = ::openPhoto, loadLink = { repo.helperPhotoLink(it).getOrNull() }) {
                when (job.status) {
                    "accepted" -> ActionButton("I am on the way", busyId == job.id) { act(job, "on_the_way", done = "Customer informed") }
                    "on_the_way" -> ActionButton("Start work (enter code)", busyId == job.id) { codeFor = job to "start" }
                    "started" -> {
                        TimeLeft(job)
                        job.pendingExtension?.let { ext ->
                            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(AmberContainer).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("Customer wants ${ext.minutes} more minutes (₹${ext.amount.toInt()} extra, added to your share)", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedButton(onClick = { answerExt(ext.id, false) }, enabled = busyId != job.id, modifier = Modifier.weight(1f)) { Text("Decline") }
                                    Button(onClick = { answerExt(ext.id, true) }, enabled = busyId != job.id, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = EmeraldPrimary)) { Text("Accept") }
                                }
                            }
                        }
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

        }

        if (section == 0) {
        if (open.isEmpty()) Text(if (active.isNotEmpty()) "Finish your current booking to get new requests." else if (ui.enabled) "No requests right now. They ring here when a customer books." else "You are not enabled for Quick Helper.", color = Slate600)
        open.forEach { job ->
            JobCard(job, busyId == job.id, onPhoto = ::openPhoto, loadLink = { repo.helperPhotoLink(it).getOrNull() }, distanceKm = jobDistance(job)) {
                ActionButton("Accept request", busyId == job.id) {
                    busyId = job.id
                    scope.launch {
                        repo.acceptHelperRequest(job.id)
                            .onSuccess { viewModel.showMessage("Accepted. Go to the customer.") }
                            .onFailure { viewModel.showMessage(it.message ?: "Could not accept") }
                        busyId = null
                        tick()
                    }
                }
            }
        }
        }

        if (section == 2) {
            if (history.isEmpty()) Text("No past bookings yet.", color = Slate600)
            history.forEach { job -> HistoryRow(job) }
        }
    }
}

@Composable
private fun SummaryRow(ui: MainViewModel.HelperUi) {
    val s = ui.summary ?: return
    Card(shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = HBlueSoft)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column { Text("Today", fontSize = 12.sp, color = Slate600); Text("₹${s.todayEarned.toInt()} · ${s.todayJobs} job${if (s.todayJobs == 1) "" else "s"}", fontWeight = FontWeight.ExtraBold, fontSize = 18.sp, color = HBlueDark) }
                Column(horizontalAlignment = Alignment.End) { Text("All time", fontSize = 12.sp, color = Slate600); Text("₹${s.totalEarned.toInt()} · ${s.totalJobs}", fontWeight = FontWeight.Bold) }
            }
            if (ui.ratings > 0 && ui.rating != null) Text("Rating ★ ${"%.1f".format(ui.rating)} (${ui.ratings})", fontSize = 12.sp, color = Slate600)
            if (s.toReceive > 0) Text("Sndmart will pay you ₹${s.toReceive.toInt()} (online bookings)", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            if (s.toPay > 0) Text("You owe Sndmart ₹${s.toPay.toInt()} commission (cash bookings)", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = AmberOnContainer)
        }
    }
}

@Composable
private fun HistoryRow(job: HelperJob) {
    Card(shape = RoundedCornerShape(14.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row {
                Text("${job.taskType} · ${job.durationMinutes} min", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Text(if (job.status == "completed") "₹${job.helperAmount.toInt()}" else job.status.replace('_', ' '), fontWeight = FontWeight.SemiBold, color = if (job.status == "completed") EmeraldPrimary else Slate600)
            }
            Text("${job.bookingNumber} · ${job.addressLine}", fontSize = 12.sp, color = Slate600, maxLines = 1)
            job.createdAt?.let { parseIsoMillis(it) }?.let { Text(SimpleDateFormat("d MMM, h:mm a", Locale.getDefault()).format(Date(it)), fontSize = 11.sp, color = Slate600) }
        }
    }
}


@Composable
private fun JobCard(job: HelperJob, busy: Boolean, onPhoto: (String) -> Unit, loadLink: suspend (String) -> String?, distanceKm: Double? = null, actions: @Composable ColumnScope.() -> Unit) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            JobSteps(job.status)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.clip(RoundedCornerShape(8.dp)).background(HBlueSoft).padding(horizontal = 8.dp, vertical = 3.dp)) {
                    Text("${job.durationMinutes} min · ${job.taskType}", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = HBlueDark)
                }
                Spacer(Modifier.weight(1f))
                Text("You earn ₹${job.helperAmount.toInt()} · ${payLabel(job)}", fontWeight = FontWeight.Bold)
            }
            Text(job.taskDescription, fontSize = 15.sp)
            if (job.paymentStatus == "paid") Text("Paid online in advance. Do not take any money from the customer.", fontSize = 12.sp, color = EmeraldOnContainer, fontWeight = FontWeight.SemiBold)
            val whenText = job.requestedFor?.let { parseIsoMillis(it) }?.let { "Needed at " + SimpleDateFormat("EEE d MMM, h:mm a", Locale.getDefault()).format(Date(it)) } ?: "Needed as soon as possible"
            Text(whenText, fontSize = 12.sp, color = Slate600)
            Text(listOfNotNull(job.customerName, job.addressLine, job.landmark?.takeIf { it.isNotBlank() }?.let { "Near $it" }).joinToString("\n"), fontSize = 14.sp)
            distanceKm?.let { Text("📍 About ${"%.1f".format(it)} km from you", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = HBlueDark) }
            if (job.photoPaths.isNotEmpty()) {
                if (job.status == "requested") Text("${job.photoPaths.size} photo(s) from the customer. You can see them after you accept.", fontSize = 12.sp, color = HBlueDark, fontWeight = FontWeight.SemiBold)
                else {
                    Text("Customer's photos", fontSize = 12.sp, color = Slate600, fontWeight = FontWeight.SemiBold)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        job.photoPaths.forEach { p -> HelperPhotoThumb(p, loadLink, onClick = { onPhoto(p) }) }
                    }
                }
            }
            if (job.status != "requested") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val lat = job.lat
                    val lng = job.lng
                    if (lat != null && lng != null) OpenMapsButton(lat, lng, job.customerName ?: "Customer", Modifier.weight(1f))
                    job.contactPhone?.takeIf { it.isNotBlank() }?.let { PhoneCallButton(it, Modifier.weight(1f)) }
                }
                HelperLiveLocation(job)
            }
            actions()
        }
    }
}

/** The customer's reference photo of the task, shown small; tap to open it big. */
@Composable
private fun HelperPhotoThumb(path: String, loadLink: suspend (String) -> String?, onClick: () -> Unit) {
    val url by produceState<String?>(null, path) { value = loadLink(path) }
    Box(Modifier.size(88.dp).clip(RoundedCornerShape(12.dp)).background(HBlueSoft).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        if (url != null) coil.compose.AsyncImage(model = url, contentDescription = "Customer's photo", contentScale = androidx.compose.ui.layout.ContentScale.Crop, modifier = Modifier.fillMaxSize())
        else CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = HBlue)
    }
}

@Composable
private fun TimeLeft(job: HelperJob) {
    val end = job.endsAt?.let { parseIsoMillis(it) } ?: return
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(end) { while (true) { now = System.currentTimeMillis(); delay(1000) } }
    val s = ((end - now) / 1000).coerceAtLeast(0)
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(HBlueSoft).padding(14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("%02d:%02d".format(s / 60, s % 60), fontSize = 32.sp, fontWeight = FontWeight.Black, color = if (s == 0L) AmberOnContainer else HBlue)
        Text(if (s == 0L) "Time is over. Finish the work." else "Time left", fontSize = 12.sp, color = Slate600)
    }
}

/** Requested > Assigned > Arrived > Working > Done, like the customer sees it. */
@Composable
private fun JobSteps(status: String) {
    val labels = listOf("Request", "Accepted", "On way", "Working", "Done")
    val idx = when (status) { "requested" -> 0; "accepted" -> 1; "on_the_way" -> 2; "started" -> 3; "completed" -> 4; else -> -1 }
    if (idx < 0) return
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        labels.forEachIndexed { i, l ->
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Box(Modifier.size(22.dp).clip(androidx.compose.foundation.shape.CircleShape).background(if (i <= idx) HBlue else androidx.compose.ui.graphics.Color(0xFFE5E7EB)), contentAlignment = Alignment.Center) {
                    Text("${i + 1}", color = if (i <= idx) androidx.compose.ui.graphics.Color.White else Slate600, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
                Text(l, fontSize = 10.sp, color = if (i <= idx) HBlueDark else Slate600, fontWeight = if (i == idx) FontWeight.Bold else FontWeight.Medium, maxLines = 1)
            }
        }
    }
}

@Composable
private fun helperChipColors() = FilterChipDefaults.filterChipColors(selectedContainerColor = HBlueSoft, selectedLabelColor = HBlueDark)

@Composable
private fun ActionButton(text: String, busy: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick, enabled = !busy, modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp), colors = ButtonDefaults.buttonColors(containerColor = HBlue), contentPadding = PaddingValues(vertical = 14.dp)
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

private fun payLabel(job: HelperJob) = if (job.paymentStatus == "paid") "paid online" else if (job.paymentMethod == "upi") "UPI" else "cash"
