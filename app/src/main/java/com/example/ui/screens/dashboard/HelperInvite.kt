package com.example.ui.screens.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val Blue = Color(0xFF2563EB)
private val BlueSoft = Color(0xFFDBEAFE)
private val BlueDark = Color(0xFF1D4ED8)

/** English + Kannada benefits of working as a Quick Helper. */
private val BENEFITS = listOf(
    "Extra earning on top of deliveries" to "ಡೆಲಿವರಿ ಜೊತೆಗೆ ಹೆಚ್ಚುವರಿ ಗಳಿಕೆ",
    "Fixed price for every 30 / 60 / 90 minutes" to "ಪ್ರತಿ 30 / 60 / 90 ನಿಮಿಷಕ್ಕೆ ನಿಗದಿತ ಬೆಲೆ",
    "Simple work: stand in a queue, carry things, pick up and drop parcels, wait at home" to "ಸುಲಭ ಕೆಲಸ: ಕ್ಯೂ ನಿಲ್ಲುವುದು, ಸಾಮಾನು ಹೊರುವುದು, ಪಾರ್ಸೆಲ್ ಪಿಕಪ್ ಮತ್ತು ಡ್ರಾಪ್, ಮನೆಯಲ್ಲಿ ಕಾಯುವುದು",
    "Customers pay in advance in the app, so your money is safe" to "ಗ್ರಾಹಕರು ಆಪ್‌ನಲ್ಲಿ ಮುಂಗಡ ಹಣ ಪಾವತಿಸುತ್ತಾರೆ, ನಿಮ್ಮ ಹಣ ಸುರಕ್ಷಿತ",
    "Your time, your choice: take only the bookings you want" to "ನಿಮ್ಮ ಸಮಯ, ನಿಮ್ಮ ಆಯ್ಕೆ: ಬೇಕಾದ ಬುಕಿಂಗ್ ಮಾತ್ರ ತೆಗೆದುಕೊಳ್ಳಿ",
    "Good ratings bring more bookings" to "ಒಳ್ಳೆಯ ರೇಟಿಂಗ್ ಇದ್ದರೆ ಹೆಚ್ಚು ಬುಕಿಂಗ್"
)

@Composable
private fun Benefits() {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        BENEFITS.forEach { (en, kn) ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
                Box(Modifier.padding(top = 2.dp).size(20.dp).clip(androidx.compose.foundation.shape.CircleShape).background(BlueSoft), contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.Check, null, tint = BlueDark, modifier = Modifier.size(14.dp))
                }
                Column {
                    Text(kn, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    Text(en, fontSize = 12.sp, color = Color(0xFF64748B))
                }
            }
        }
    }
}

/** The popup: shown to a partner who is not a helper yet (once a day), with the benefits in Kannada and English. */
@Composable
fun HelperInviteDialog(status: String, onRequest: () -> Unit, onLater: () -> Unit) {
    AlertDialog(
        onDismissRequest = onLater,
        title = {
            Column {
                Text("ಹೆಲ್ಪರ್ ಆಗಿ ಸೇರಿ, ಹೆಚ್ಚು ಗಳಿಸಿ", fontWeight = FontWeight.ExtraBold, fontSize = 20.sp, color = BlueDark)
                Text("Join as a Helper and earn more", fontSize = 13.sp, color = Color(0xFF64748B))
            }
        },
        text = { Column(Modifier.verticalScroll(rememberScrollState())) {
            if (status == "rejected") Text("ಹಿಂದಿನ ವಿನಂತಿಯನ್ನು ಅನುಮೋದಿಸಲಾಗಿಲ್ಲ. 7 ದಿನಗಳ ನಂತರ ಮತ್ತೆ ಕೇಳಬಹುದು.\nYour last request was not approved. You can ask again after 7 days.", fontSize = 12.sp, color = Color(0xFFB45309), modifier = Modifier.padding(bottom = 8.dp))
            Benefits()
        } },
        confirmButton = { Button(onClick = onRequest, colors = ButtonDefaults.buttonColors(containerColor = Blue), shape = RoundedCornerShape(12.dp)) { Text("ಸೇರಲು ವಿನಂತಿ ಕಳುಹಿಸಿ\nSend request to join", fontWeight = FontWeight.Bold, fontSize = 13.sp) } },
        dismissButton = { TextButton(onClick = onLater) { Text("ನಂತರ / Later") } }
    )
}

/** The card on the dashboard: invitation, or the state of the request. */
@Composable
fun HelperInviteCard(status: String, onRequest: () -> Unit) {
    Card(shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = BlueSoft)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("ಹೆಲ್ಪರ್ ಆಗಿ ಸೇರಿ – ಹೆಚ್ಚುವರಿ ಆದಾಯ", fontWeight = FontWeight.ExtraBold, fontSize = 17.sp, color = BlueDark)
            Text("Join as a Helper – extra income", fontSize = 12.sp, color = Color(0xFF475569))
            when (status) {
                "requested" -> Text("ನಿಮ್ಮ ವಿನಂತಿ ಕಳುಹಿಸಲಾಗಿದೆ. ಸಿಟಿ ಅಡ್ಮಿನ್ ಅನುಮೋದನೆಗಾಗಿ ಕಾಯಿರಿ.\nRequest sent. Waiting for the City Admin.", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                else -> {
                    Text("ಕ್ಯೂ ನಿಲ್ಲುವುದು, ಸಾಮಾನು ಹೊರುವುದು, ಪಾರ್ಸೆಲ್ ಪಿಕಪ್ – ಪ್ರತಿ 30 ನಿಮಿಷಕ್ಕೆ ನಿಗದಿತ ಬೆಲೆ.\nQueues, carrying, parcel pickup – fixed price per 30 minutes.", fontSize = 13.sp)
                    Button(onClick = onRequest, colors = ButtonDefaults.buttonColors(containerColor = Blue), shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
                        Text("ಸೇರಲು ವಿನಂತಿ ಕಳುಹಿಸಿ / Send request", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}
