package com.example.ui.screens.dashboard

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
import com.example.data.model.HelperJob
import com.example.data.model.HelperTag
import com.example.ui.MainViewModel
import com.example.ui.components.OpenMapsButton
import kotlinx.coroutines.launch

/**
 * "The work I do": the City Admin's helper categories as tick chips. Requests are sent to the nearest available helper who ticked
 * the job's kind (nothing ticked = every kind). Saved at once on each tap.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HelperWorkTypes(viewModel: MainViewModel) {
    val repo = viewModel.repository
    val scope = rememberCoroutineScope()
    var tags by remember { mutableStateOf<List<HelperTag>?>(null) }
    var saving by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { tags = repo.getHelperTags().getOrNull() }
    val list = tags ?: return
    if (list.isEmpty()) return
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        modifier = Modifier.fillMaxWidth().testTag("helper_work_types")
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("The work I do", fontWeight = FontWeight.ExtraBold, fontSize = 15.sp)
            Text(
                if (list.none { it.selected }) "Nothing ticked: you get every kind of request. Tick only what you do and requests will match."
                else "You get only these requests, nearest first.",
                fontSize = 12.sp, color = Color(0xFF64748B)
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                list.forEach { t ->
                    FilterChip(
                        selected = t.selected, enabled = !saving, label = { Text(t.name) },
                        onClick = {
                            val next = list.map { if (it.id == t.id) it.copy(selected = !it.selected) else it }
                            tags = next
                            saving = true
                            scope.launch {
                                repo.setHelperTags(next.filter { it.selected }.map { it.id })
                                    .onFailure { viewModel.showMessage(it.message ?: "Could not save"); tags = list }
                                saving = false
                            }
                        }
                    )
                }
            }
        }
    }
}

/** The customer's real position at booking time, with how accurate it is; the saved pin may be wrong. */
@Composable
fun HelperLiveLocation(job: HelperJob, modifier: Modifier = Modifier) {
    val lat = job.liveLat ?: return
    val lng = job.liveLng ?: return
    val acc = job.liveAccuracyM
    val (color, label) = when {
        acc == null -> Color(0xFF757575) to "Accuracy unknown"
        acc <= 30 -> Color(0xFF2E7D32) to "±${acc.toInt()} m · Accurate"
        acc <= 100 -> Color(0xFFF9A825) to "±${acc.toInt()} m · Approx"
        else -> Color(0xFFC62828) to "±${acc.toInt()} m · Low accuracy"
    }
    val far = (job.liveDistanceM ?: 0.0) > 150
    Surface(shape = RoundedCornerShape(12.dp), color = color.copy(alpha = 0.10f), modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row {
                Text("📍 Customer's live location", fontWeight = FontWeight.Bold, fontSize = 14.sp, modifier = Modifier.weight(1f))
                Text(label, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = color)
            }
            if (far) Text("The saved address pin is ${job.liveDistanceM!!.toInt()} m away from where the customer booked. Use the live location.", fontSize = 12.sp, color = Color(0xFFC62828))
            OpenMapsButton(lat, lng, "Navigate to live location", Modifier.fillMaxWidth())
        }
    }
}
