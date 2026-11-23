package com.example.ui.screens.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.data.model.IncentiveStatus
import kotlinx.coroutines.delay
import java.time.ZoneId
import java.time.ZonedDateTime

private val Amber = Color(0xFFF59E0B)
private val AmberDark = Color(0xFFB45309)
private val AmberSoft = Color(0xFFFEF3C7)
private val Green = Color(0xFF16A34A)
private val Slate = Color(0xFF64748B)
private fun money(v: Double) = "₹" + (if (v % 1.0 == 0.0) v.toLong().toString() else "%.1f".format(v))

/** Time until 12:00 AM India time: the daily targets restart then. */
private fun untilMidnight(): String {
    val now = ZonedDateTime.now(ZoneId.of("Asia/Kolkata"))
    val end = now.toLocalDate().plusDays(1).atStartOfDay(ZoneId.of("Asia/Kolkata"))
    val mins = java.time.Duration.between(now, end).toMinutes()
    return "${mins / 60}h ${mins % 60}m"
}

/** Compact card for the dashboard: today's progress at a glance. Tap to open the full screen. */
@Composable
fun IncentiveCard(s: IncentiveStatus, onOpen: () -> Unit) {
    val next = s.slabs.firstOrNull { !it.reached }
    Card(onClick = onOpen, shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = AmberSoft)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.EmojiEvents, null, tint = AmberDark, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(8.dp))
                Text("Today's incentives", fontWeight = FontWeight.ExtraBold, fontSize = 16.sp, color = AmberDark, modifier = Modifier.weight(1f))
                Text(money(s.earnedToday) + " earned", fontWeight = FontWeight.Bold, color = AmberDark, fontSize = 14.sp)
            }
            s.currentPeak?.let { Text("⚡ Peak now: +${money(it.extra)} per order till ${it.endsAt}", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFFC2410C)) }
            if (next != null) {
                val prevBonus = s.slabs.lastOrNull { it.reached }?.bonus ?: 0.0
                LinearProgressIndicator(progress = { (s.todayDeliveries.toFloat() / next.minDeliveries).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)), color = Amber, trackColor = Color.White)
                Text("${s.todayDeliveries} of ${next.minDeliveries} orders · ${next.minDeliveries - s.todayDeliveries} more to earn ${money(next.bonus - prevBonus)} more", fontSize = 12.sp, color = Slate)
            } else if (s.slabs.isNotEmpty()) Text("All of today's targets done 🎉", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Green)
            else if (s.currentPeak == null) Text("Tap to see today's offers", fontSize = 12.sp, color = Slate)
        }
    }
}

/** The full progress screen. */
@Composable
fun IncentiveDialog(s: IncentiveStatus, onClose: () -> Unit) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(Modifier.fillMaxSize(), color = Color(0xFFFFFBEB)) {
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                    Text("Incentives", fontWeight = FontWeight.ExtraBold, fontSize = 20.sp)
                }
                var left by remember { mutableStateOf(untilMidnight()) }
                LaunchedEffect(Unit) { while (true) { left = untilMidnight(); delay(30_000) } }
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    // earned today
                    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Brush.linearGradient(listOf(Color(0xFFF59E0B), Color(0xFFEA580C)))).padding(20.dp)) {
                        Column {
                            Text("Earned today", color = Color.White.copy(alpha = 0.85f), fontSize = 13.sp)
                            Text(money(s.earnedToday), color = Color.White, fontSize = 38.sp, fontWeight = FontWeight.Black)
                            Text("Daily bonus ${money(s.dailyToday)} · Peak ${money(s.peakToday)}", color = Color.White.copy(alpha = 0.9f), fontSize = 13.sp)
                            Spacer(Modifier.height(8.dp))
                            Surface(shape = RoundedCornerShape(50), color = Color.White.copy(alpha = 0.2f)) {
                                Text("Targets reset in $left (12:00 AM)", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
                            }
                        }
                    }

                    // peak hour banner
                    when {
                        s.currentPeak != null -> Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color(0xFFFFEDD5)).border(1.dp, Color(0xFFFB923C), RoundedCornerShape(16.dp)).padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.Bolt, null, tint = Color(0xFFEA580C), modifier = Modifier.size(30.dp))
                            Spacer(Modifier.width(10.dp))
                            Column {
                                Text("${s.currentPeak.name} is on!", fontWeight = FontWeight.ExtraBold, color = Color(0xFFC2410C), fontSize = 16.sp)
                                Text("+${money(s.currentPeak.extra)} on every order till ${s.currentPeak.endsAt}", fontSize = 13.sp)
                            }
                        }
                        s.nextPeak != null -> Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color.White).border(1.dp, Color(0xFFFED7AA), RoundedCornerShape(16.dp)).padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.Bolt, null, tint = Amber, modifier = Modifier.size(26.dp))
                            Spacer(Modifier.width(10.dp))
                            Column {
                                Text("Next peak: ${s.nextPeak.name}", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                                Text("${s.nextPeak.startsAt} to ${s.nextPeak.endsAt} · +${money(s.nextPeak.extra)} per order", fontSize = 13.sp, color = Slate)
                            }
                        }
                    }

                    // daily targets
                    if (s.slabs.isNotEmpty()) {
                        Text("Daily targets", fontWeight = FontWeight.ExtraBold, fontSize = 17.sp)
                        Card(shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = Color.White)) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                                val maxN = s.slabs.last().minDeliveries
                                Row(verticalAlignment = Alignment.Bottom) {
                                    Text("${s.todayDeliveries}", fontSize = 40.sp, fontWeight = FontWeight.Black, color = AmberDark)
                                    Text("  orders completed today", fontSize = 14.sp, color = Slate, modifier = Modifier.padding(bottom = 8.dp))
                                }
                                LinearProgressIndicator(progress = { (s.todayDeliveries.toFloat() / maxN).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp)), color = Amber, trackColor = AmberSoft)
                                s.slabs.forEachIndexed { i, sl ->
                                    val prev = s.slabs.getOrNull(i - 1)?.bonus ?: 0.0
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Box(Modifier.size(32.dp).clip(CircleShape).background(if (sl.reached) Green else AmberSoft), contentAlignment = Alignment.Center) {
                                            if (sl.reached) Icon(Icons.Filled.Check, null, tint = Color.White, modifier = Modifier.size(18.dp))
                                            else Text("${sl.minDeliveries}", fontWeight = FontWeight.Bold, color = AmberDark, fontSize = 13.sp)
                                        }
                                        Spacer(Modifier.width(12.dp))
                                        Column(Modifier.weight(1f)) {
                                            Text("Complete ${sl.minDeliveries} orders", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                                            Text(
                                                if (sl.reached) "Done · ${money(sl.bonus)} total" else "${sl.minDeliveries - s.todayDeliveries} more orders · earn ${money(sl.bonus - prev)} more",
                                                fontSize = 12.sp, color = if (sl.reached) Green else Slate
                                            )
                                        }
                                        Text(money(sl.bonus), fontWeight = FontWeight.ExtraBold, fontSize = 17.sp, color = if (sl.reached) Green else AmberDark)
                                    }
                                }
                                Text("Amounts are the total for the day. Unfinished targets end at 12:00 AM and start again tomorrow.", fontSize = 11.sp, color = Slate)
                            }
                        }
                    }

                    // acceptance rate
                    Text("Acceptance rate", fontWeight = FontWeight.ExtraBold, fontSize = 17.sp)
                    Card(shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = Color.White)) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("${s.acceptancePct}%", fontSize = 28.sp, fontWeight = FontWeight.Black, color = if (s.acceptanceOk) Green else Color(0xFFDC2626), modifier = Modifier.weight(1f))
                                Text("Needed: ${s.minAcceptancePct}%", fontSize = 13.sp, color = Slate)
                            }
                            LinearProgressIndicator(progress = { s.acceptancePct / 100f }, modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)), color = if (s.acceptanceOk) Green else Color(0xFFDC2626), trackColor = Color(0xFFE2E8F0))
                            Text(
                                if (s.acceptanceOk) "Good. You are eligible for the daily bonus. (Last 7 days, ${s.acceptanceOffers} offers)"
                                else "Below ${s.minAcceptancePct}%: the daily bonus is paused. Accept more orders to get it back. (Last 7 days)",
                                fontSize = 12.sp, color = Slate
                            )
                        }
                    }

                    // last 7 days
                    if (s.week.isNotEmpty()) {
                        Text("Last 7 days", fontWeight = FontWeight.ExtraBold, fontSize = 17.sp)
                        Card(shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = Color.White)) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                val maxD = (s.week.maxOfOrNull { it.deliveries } ?: 1).coerceAtLeast(1)
                                s.week.reversed().forEach { d ->
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(d.day.takeLast(5), fontSize = 12.sp, color = Slate, modifier = Modifier.width(48.dp))
                                        Box(Modifier.weight(1f).height(10.dp).clip(RoundedCornerShape(5.dp)).background(AmberSoft)) {
                                            Box(Modifier.fillMaxHeight().fillMaxWidth(d.deliveries.toFloat() / maxD).background(Amber))
                                        }
                                        Text("${d.deliveries}", fontSize = 12.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.End, modifier = Modifier.width(28.dp))
                                        Text(if (d.incentive > 0) money(d.incentive) else "-", fontSize = 12.sp, color = if (d.incentive > 0) Green else Slate, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.End, modifier = Modifier.width(54.dp))
                                    }
                                }
                            }
                        }
                    }
                    Text("One incentive per order: if a peak extra and a daily bonus fall on the same order, you get the larger one.", fontSize = 12.sp, color = Slate)
                    Spacer(Modifier.height(12.dp))
                }
            }
        }
    }
}
