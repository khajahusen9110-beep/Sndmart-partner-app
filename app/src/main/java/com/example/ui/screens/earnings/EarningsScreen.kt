package com.example.ui.screens.earnings

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.CashLedgerEntry
import com.example.data.model.DeliveryEarning
import com.example.data.model.PaymentRequest
import com.example.ui.MainViewModel
import com.example.ui.components.*
import com.example.ui.screens.delivery.EmptyDeliveriesState
import com.example.ui.theme.*

@Composable
fun EarningsScreen(
    viewModel: MainViewModel
) {
    var selectedTab by remember { mutableIntStateOf(0) }
    val tabs = listOf("Earnings", "Cash Ledger (COD)", "Payouts")

    val earnings by viewModel.earnings.collectAsState()
    val cashLedger by viewModel.cashLedger.collectAsState()
    val paymentRequests by viewModel.paymentRequests.collectAsState()
    val earningsSummary by viewModel.todayEarningsSummary.collectAsState()
    val cashInHand by viewModel.cashInHandBalance.collectAsState()
    val availablePayout by viewModel.availablePayoutBalance.collectAsState()

    var showPayoutDialog by remember { mutableStateOf(false) }
    var showRemittanceDialog by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // Tab Row
        TabRow(
            selectedTabIndex = selectedTab,
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = EmeraldPrimary
        ) {
            tabs.forEachIndexed { index, title ->
                Tab(
                    selected = selectedTab == index,
                    onClick = { selectedTab = index },
                    text = {
                        Text(
                            text = title,
                            fontWeight = if (selectedTab == index) FontWeight.Bold else FontWeight.Normal
                        )
                    }
                )
            }
        }

        when (selectedTab) {
            0 -> {
                // Earnings Tab
                LazyColumn(
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    item {
                        // Total Balance Hero Card
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(20.dp),
                            colors = CardDefaults.cardColors(containerColor = EmeraldPrimary)
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(20.dp)
                            ) {
                                Text(
                                    text = "AVAILABLE EARNINGS",
                                    style = MaterialTheme.typography.labelMedium.copy(
                                        color = Color.White.copy(alpha = 0.8f),
                                        fontWeight = FontWeight.Bold
                                    )
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "₹${earningsSummary.first.toInt()}",
                                        style = MaterialTheme.typography.headlineLarge.copy(
                                            fontWeight = FontWeight.ExtraBold,
                                            color = Color.White
                                        )
                                    )
                                    Button(
                                        onClick = { showPayoutDialog = true },
                                        shape = RoundedCornerShape(12.dp),
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = Color.White,
                                            contentColor = EmeraldPrimary
                                        ),
                                        modifier = Modifier.testTag("request_payout_button")
                                    ) {
                                        Text("Request Payout", fontWeight = FontWeight.ExtraBold)
                                    }
                                }

                                Spacer(modifier = Modifier.height(14.dp))
                                Divider(color = Color.White.copy(alpha = 0.2f))
                                Spacer(modifier = Modifier.height(10.dp))

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = "${earningsSummary.second} Deliveries Completed",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = Color.White
                                    )
                                    Text(
                                        text = "Weekly Payout Cycle",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = Color.White.copy(alpha = 0.85f)
                                    )
                                }
                            }
                        }
                    }

                    if (earnings.isEmpty()) {
                        item {
                            EmptyDeliveriesState(
                                title = "No Earnings Recorded Yet",
                                subtitle = "Complete your assigned deliveries to see delivery fees and surge incentives appear here.",
                                onRefresh = { viewModel.refreshAllData() }
                            )
                        }
                    } else {
                        item {
                            Text(
                                text = "Order Earning Breakdown (${earnings.size})",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                            )
                        }

                        items(earnings) { item ->
                            EarningItemCard(earning = item)
                        }
                    }
                }
            }

            1 -> {
                // Cash on Delivery (COD) Ledger
                LazyColumn(
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(20.dp),
                            colors = CardDefaults.cardColors(containerColor = AmberAccent)
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(20.dp)
                            ) {
                                Text(
                                    text = "CASH IN HAND (COD BALANCE)",
                                    style = MaterialTheme.typography.labelMedium.copy(
                                        color = Slate900.copy(alpha = 0.8f),
                                        fontWeight = FontWeight.Bold
                                    )
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "₹${cashInHand.toInt()}",
                                    style = MaterialTheme.typography.headlineLarge.copy(
                                        fontWeight = FontWeight.ExtraBold,
                                        color = Slate900
                                    )
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = "Please remit cash balance at your designated darkstore hub at end of shift.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Slate900.copy(alpha = 0.85f)
                                )
                                Spacer(modifier = Modifier.height(14.dp))
                                Button(
                                    onClick = { showRemittanceDialog = true },
                                    shape = RoundedCornerShape(12.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = Slate900,
                                        contentColor = Color.White
                                    ),
                                    modifier = Modifier.testTag("request_remittance_button")
                                ) {
                                    Icon(Icons.Default.AccountBalanceWallet, contentDescription = null)
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Request Cash Remittance", fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }

                    if (cashLedger.isEmpty()) {
                        item {
                            EmptyDeliveriesState(
                                title = "No Cash Transactions",
                                subtitle = "When you complete Cash on Delivery (COD) orders or remit funds at darkstores, they will be logged here.",
                                onRefresh = { viewModel.refreshAllData() }
                            )
                        }
                    } else {
                        item {
                            Text(
                                text = "Cash Transactions History",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                            )
                        }

                        items(cashLedger) { entry ->
                            CashLedgerItemCard(entry = entry)
                        }
                    }
                }
            }

            2 -> {
                // Payout Requests Tab
                LazyColumn(
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Payout History",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                            )
                            Button(
                                onClick = { showPayoutDialog = true },
                                colors = ButtonDefaults.buttonColors(containerColor = EmeraldPrimary)
                            ) {
                                Icon(Icons.Default.Add, contentDescription = null)
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("New Payout")
                            }
                        }
                    }

                    if (paymentRequests.isEmpty()) {
                        item {
                            EmptyDeliveriesState(
                                title = "No Payout Requests",
                                subtitle = "You can request instant withdrawals to your linked Bank Account or UPI ID.",
                                onRefresh = null
                            )
                        }
                    } else {
                        items(paymentRequests) { req ->
                            PaymentRequestCard(request = req)
                        }
                    }
                }
            }
        }
    }

    if (showPayoutDialog) {
        PayoutRequestDialog(
            availableBalance = availablePayout,
            onDismiss = { showPayoutDialog = false },
            onSubmit = { amount ->
                viewModel.requestPayout(amount)
                showPayoutDialog = false
            }
        )
    }

    if (showRemittanceDialog) {
        CashRemittanceDialog(
            currentCashBalance = cashInHand,
            onDismiss = { showRemittanceDialog = false },
            onSubmit = { amount, note ->
                viewModel.requestCashRemittance(amount, note)
                showRemittanceDialog = false
            }
        )
    }
}

@Composable
fun EarningItemCard(earning: DeliveryEarning) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(text = earning.orderNumber, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold))
                    Text(text = earning.createdAt, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }

                Text(
                    text = "+₹${earning.netEarning.toInt()}",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.ExtraBold, color = EmeraldPrimary)
                )
            }

            Spacer(modifier = Modifier.height(10.dp))
            Divider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f))
            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(text = "Base Delivery: ₹${earning.deliveryFee.toInt()}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                if (earning.incentive > 0) {
                    Text(text = "Peak Surge: +₹${earning.incentive.toInt()}", style = MaterialTheme.typography.labelSmall.copy(color = EmeraldPrimary, fontWeight = FontWeight.Bold))
                }
                StatusPill(
                    text = earning.status,
                    statusColor = if (earning.status == "paid") StatusOnlineGreen else AmberAccent,
                    bgColor = if (earning.status == "paid") EmeraldContainer else AmberContainer
                )
            }
        }
    }
}

@Composable
fun CashLedgerItemCard(entry: CashLedgerEntry) {
    val isCollected = entry.entryType == "collected"
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .background(if (isCollected) AmberContainer else EmeraldContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (isCollected) Icons.Default.CallReceived else Icons.Default.CallMade,
                        contentDescription = null,
                        tint = if (isCollected) AmberAccent else EmeraldPrimary,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        text = if (isCollected) "COD Collected (${entry.orderNumber ?: "Order"})" else "Remitted to Hub",
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold)
                    )
                    Text(text = entry.notes ?: entry.createdAt, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            Text(
                text = "${if (isCollected) "+" else "-"}₹${entry.amount.toInt()}",
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.Bold,
                    color = if (isCollected) AmberAccent else EmeraldPrimary
                )
            )
        }
    }
}

@Composable
fun PaymentRequestCard(request: PaymentRequest) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Text(text = "₹${request.amount.toInt()} Payout", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
                val timeLabel = request.paidAt?.let { "Paid: $it" } ?: request.requestedAt.ifBlank { request.createdAt }
                val subText = if (!request.note.isNullOrBlank()) "${request.note} • $timeLabel" else timeLabel
                Text(text = subText, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            val isSuccess = request.status in listOf("paid", "processed", "approved")
            StatusPill(
                text = request.status,
                statusColor = when {
                    isSuccess -> StatusOnlineGreen
                    request.status == "rejected" -> StatusRejectedRed
                    else -> AmberAccent
                },
                bgColor = when {
                    isSuccess -> EmeraldContainer
                    request.status == "rejected" -> Color(0xFFFEE2E2)
                    else -> AmberContainer
                }
            )
        }
    }
}

@Composable
fun PayoutRequestDialog(
    availableBalance: Double,
    onDismiss: () -> Unit,
    onSubmit: (Double) -> Unit
) {
    var amountInput by remember { mutableStateOf(if (availableBalance >= 100) availableBalance.toInt().toString() else "100") }
    val enteredAmount = amountInput.toDoubleOrNull() ?: 0.0
    val isValid = enteredAmount >= 100.0

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Request Earnings Withdrawal") },
        text = {
            Column {
                Text("Available Earnings: ₹${availableBalance.toInt()}")
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = amountInput,
                    onValueChange = { amountInput = it.filter { ch -> ch.isDigit() } },
                    label = { Text("Withdrawal Amount (Min ₹100)") },
                    leadingIcon = { Icon(Icons.Default.CurrencyRupee, contentDescription = null) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    isError = !isValid && amountInput.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth()
                )
                if (!isValid && amountInput.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "Minimum withdrawal amount is ₹100.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    "Payout is transferred to your verified Bank/UPI details. Processing takes under 2 hours.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (isValid) {
                        onSubmit(enteredAmount)
                    }
                },
                enabled = isValid,
                colors = ButtonDefaults.buttonColors(containerColor = EmeraldPrimary)
            ) {
                Text("Confirm Payout")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
fun CashRemittanceDialog(
    currentCashBalance: Double,
    onDismiss: () -> Unit,
    onSubmit: (Double, String?) -> Unit
) {
    var amountInput by remember { mutableStateOf(if (currentCashBalance > 0) currentCashBalance.toInt().toString() else "0") }
    var noteInput by remember { mutableStateOf("") }
    val enteredAmount = amountInput.toDoubleOrNull() ?: 0.0
    val isValid = enteredAmount > 0.0

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Request Cash Remittance") },
        text = {
            Column {
                Text("Cash in Hand (COD): ₹${currentCashBalance.toInt()}")
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = amountInput,
                    onValueChange = { amountInput = it.filter { ch -> ch.isDigit() } },
                    label = { Text("Amount Remitted to Hub (₹)") },
                    leadingIcon = { Icon(Icons.Default.CurrencyRupee, contentDescription = null) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(10.dp))
                OutlinedTextField(
                    value = noteInput,
                    onValueChange = { noteInput = it },
                    label = { Text("Note / Hub Manager Name (Optional)") },
                    placeholder = { Text("Handed over to Manoj at Indiranagar Hub") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    "Submitting this requests cash settlement with darkstore supervisor. Your ledger updates upon admin confirmation.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (isValid) {
                        onSubmit(enteredAmount, noteInput.ifBlank { null })
                    }
                },
                enabled = isValid,
                colors = ButtonDefaults.buttonColors(containerColor = EmeraldPrimary)
            ) {
                Text("Submit Remittance")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
