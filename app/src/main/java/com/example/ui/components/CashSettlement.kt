package com.example.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.HourglassTop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.example.data.model.CashStatus
import com.example.ui.theme.EmeraldPrimary
import kotlinx.coroutines.delay
import java.util.Locale

private fun rupees(amount: Double): String =
    if (amount % 1.0 == 0.0) amount.toLong().toString() else String.format(Locale.US, "%.2f", amount)

/** upi://pay link for the city's settlement UPI ID, prefilled with the amount. */
internal fun upiPayUri(upiId: String, payeeName: String?, amount: Double): Uri =
    Uri.Builder()
        .scheme("upi")
        .authority("pay")
        .appendQueryParameter("pa", upiId)
        .appendQueryParameter("pn", payeeName?.takeIf { it.isNotBlank() } ?: "Sndmart")
        .appendQueryParameter("am", String.format(Locale.US, "%.2f", amount))
        .appendQueryParameter("cu", "INR")
        .appendQueryParameter("tn", "Sndmart COD cash settlement")
        .build()

private fun openUpiApp(context: Context, uri: Uri) {
    try {
        context.startActivity(
            Intent.createChooser(Intent(Intent.ACTION_VIEW, uri), "Pay with UPI")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    } catch (e: Exception) {
        Toast.makeText(context, "No UPI app found. Pay to the UPI ID manually.", Toast.LENGTH_LONG).show()
    }
}

private fun copyToClipboard(context: Context, text: String) {
    context.getSystemService(ClipboardManager::class.java)
        ?.setPrimaryClip(ClipData.newPlainText("UPI ID", text))
    Toast.makeText(context, "UPI ID copied", Toast.LENGTH_SHORT).show()
}

/**
 * Shown instead of the app while the partner's COD cash is at the city limit (after any
 * accepted delivery is finished). Opens again once the city admin verifies the settlement.
 */
@Composable
fun CashSettlementScreen(
    status: CashStatus,
    isLoading: Boolean,
    onRefresh: () -> Unit,
    onSubmit: (String, (Boolean) -> Unit) -> Unit,
    onSignOut: () -> Unit
) {
    // Picks up the admin's verification without the partner doing anything.
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000L)
            onRefresh()
        }
    }
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(24.dp)
                .testTag("cash_settlement_screen"),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(24.dp))
            Icon(
                Icons.Default.AccountBalanceWallet,
                contentDescription = null,
                tint = EmeraldPrimary,
                modifier = Modifier.size(56.dp)
            )
            Spacer(Modifier.height(12.dp))
            Text(
                "Settle your cash to continue",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "You are holding ₹${rupees(status.cashInHand)} of cash-on-delivery money" +
                    (status.cashLimit?.let { ", the limit for your city is ₹${rupees(it)}" } ?: "") +
                    ". Pay it to Sndmart by UPI to receive orders again.",
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(Modifier.height(20.dp))
            CashSettlementPanel(status = status, isLoading = isLoading, onSubmit = onSubmit)
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onRefresh, enabled = !isLoading) { Text("Check status") }
                TextButton(onClick = onSignOut) { Text("Log out") }
            }
        }
    }
}

/** Amount, UPI payment and UTR entry; also used from the Earnings screen. */
@Composable
fun CashSettlementPanel(
    status: CashStatus,
    isLoading: Boolean,
    onSubmit: (String, (Boolean) -> Unit) -> Unit
) {
    val context = LocalContext.current
    val last = status.lastSettlement
    val upiId = status.settlementUpiId

    when {
        status.awaitingVerification -> InfoCard(
            icon = { Icon(Icons.Default.HourglassTop, contentDescription = null, tint = EmeraldPrimary) },
            title = "Waiting for verification",
            message = "Settlement of ₹${rupees(status.pendingSettlement)}" +
                (last?.utr?.let { " (UTR $it)" } ?: "") +
                " is with your city admin. The app opens as soon as it is verified."
        )

        upiId == null -> InfoCard(
            icon = { Icon(Icons.Default.ErrorOutline, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
            title = "Settlement UPI not set",
            message = "Your city has not set a UPI ID for cash settlement yet. Please contact your city admin."
        )

        else -> {
            var utr by rememberSaveable { mutableStateOf("") }
            val amount = status.cashInHand
            if (last?.status == "rejected") {
                InfoCard(
                    icon = { Icon(Icons.Default.ErrorOutline, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                    title = "Last settlement was rejected",
                    message = (last.utr?.let { "UTR $it. " } ?: "") +
                        (last.note?.takeIf { it.isNotBlank() } ?: "Check the payment and submit again.")
                )
                Spacer(Modifier.height(12.dp))
            }
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Amount to pay", style = MaterialTheme.typography.labelMedium)
                    Text(
                        "₹${rupees(amount)}",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.ExtraBold,
                        modifier = Modifier.testTag("settlement_amount")
                    )
                    Text("Pay to", style = MaterialTheme.typography.labelMedium)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(upiId, fontWeight = FontWeight.Bold)
                            status.settlementUpiName?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                        }
                        IconButton(onClick = { copyToClipboard(context, upiId) }) {
                            Icon(Icons.Default.ContentCopy, contentDescription = "Copy UPI ID")
                        }
                    }
                    Button(
                        onClick = { openUpiApp(context, upiPayUri(upiId, status.settlementUpiName, amount)) },
                        enabled = amount > 0.0,
                        colors = ButtonDefaults.buttonColors(containerColor = EmeraldPrimary),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("pay_with_upi")
                    ) { Text("1. Pay ₹${rupees(amount)} with UPI", fontWeight = FontWeight.Bold) }
                    OutlinedTextField(
                        value = utr,
                        onValueChange = { input -> utr = input.filter { it.isLetterOrDigit() }.uppercase().take(35) },
                        label = { Text("2. UPI transaction ID (UTR)") },
                        placeholder = { Text("12-digit number from your UPI app") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("utr_input")
                    )
                    Button(
                        onClick = { onSubmit(utr) { ok -> if (ok) utr = "" } },
                        enabled = !isLoading && utr.length >= 6 && amount > 0.0,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("submit_settlement")
                    ) { Text("3. Submit for verification") }
                    Text(
                        "Pay the exact amount, then copy the UTR / transaction ID from your UPI app's payment " +
                            "details. Your city admin checks it before the app opens again.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            }
        }
    }
}

/** Dashboard notice while blocked but still finishing an accepted delivery. */
@Composable
fun CashLimitBanner(status: CashStatus) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("cash_limit_banner"),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.AccountBalanceWallet, contentDescription = null, tint = MaterialTheme.colorScheme.error)
            Spacer(Modifier.width(10.dp))
            Column {
                Text("Cash limit reached", fontWeight = FontWeight.Bold)
                Text(
                    "Finish your current delivery, then settle ₹${rupees(status.cashInHand)} by UPI. " +
                        "You will not get new orders until then.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

@Composable
private fun InfoCard(icon: @Composable () -> Unit, title: String, message: String) {
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            icon()
            Spacer(Modifier.width(12.dp))
            Column {
                Text(title, fontWeight = FontWeight.Bold)
                Text(message, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
