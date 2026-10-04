package com.example.ui.incoming

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeliveryDining
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import com.example.MainActivity
import com.example.data.remote.SupabaseRepository
import com.example.service.OrderAlert
import com.example.service.OrderAlertNotifier
import com.example.ui.theme.EmeraldPrimary
import com.example.ui.theme.MyApplicationTheme
import com.example.util.ServerClock
import com.example.util.isCashOnDelivery
import java.lang.ref.WeakReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Full-screen new-order screen. Visual only: the ringing comes from the order notification
 * ([OrderAlertNotifier]) and stops when that notification is cancelled.
 */
class IncomingOrderActivity : ComponentActivity() {

    private var alert by mutableStateOf<OrderAlert?>(null)
    private var busy by mutableStateOf(false)
    private var error by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showOverLockScreen()
        current = WeakReference(this)
        alert = readAlert(intent)
        if (alert == null) {
            finish()
            return
        }
        setContent {
            MyApplicationTheme {
                alert?.let { a ->
                    IncomingOrderScreen(
                        alert = a,
                        busy = busy,
                        error = error,
                        onAccept = { respond(a, accept = true) },
                        onDecline = { respond(a, accept = false) },
                        onTimeout = { onTimeout(a) }
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        readAlert(intent)?.let {
            alert = it
            error = null
            busy = false
        }
    }

    override fun onDestroy() {
        if (current?.get() === this) current = null
        super.onDestroy()
    }

    private fun showOverLockScreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun respond(a: OrderAlert, accept: Boolean) {
        if (busy) return
        busy = true
        error = null
        if (!accept) OrderAlertNotifier.cancel(this, a.assignmentId)
        lifecycleScope.launch {
            val result = SupabaseRepository.getInstance(this@IncomingOrderActivity)
                .respondToAssignment(a.assignmentId, accept)
            busy = false
            result.onSuccess {
                OrderAlertNotifier.cancel(this@IncomingOrderActivity, a.assignmentId)
                if (accept) {
                    // On the lock screen, ask for unlock so the app opens on the accepted order.
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        getSystemService(KeyguardManager::class.java)
                            ?.requestDismissKeyguard(this@IncomingOrderActivity, null)
                    }
                    startActivity(
                        Intent(this@IncomingOrderActivity, MainActivity::class.java).apply {
                            putExtra("EXTRA_NAVIGATE_ASSIGNMENT_ID", a.assignmentId)
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                        }
                    )
                }
                finish()
            }.onFailure {
                // Server text as-is, e.g. "Assignment already processed".
                error = it.message ?: "Something went wrong"
                if (!accept) finish()
            }
        }
    }

    private fun onTimeout(a: OrderAlert) {
        OrderAlertNotifier.cancel(this, a.assignmentId)
        // Same as the in-app offer card: an unanswered offer is declined so it is reassigned.
        // Runs outside lifecycleScope because the screen closes right away.
        backgroundScope.launch {
            SupabaseRepository.getInstance(this@IncomingOrderActivity).declineExpiredOffer(a.assignmentId)
        }
        finish()
    }

    companion object {
        private const val EXTRA_ID = "alert_assignment_id"
        private const val EXTRA_TITLE = "alert_title"
        private const val EXTRA_BODY = "alert_body"
        private const val EXTRA_STORE = "alert_store_name"
        private const val EXTRA_STORE_ADDRESS = "alert_store_address"
        private const val EXTRA_CUSTOMER_ADDRESS = "alert_customer_address"
        private const val EXTRA_PAYMENT = "alert_payment_mode"
        private const val EXTRA_TOTAL = "alert_total_amount"
        private const val EXTRA_FEE = "alert_delivery_fee"
        private const val EXTRA_EXPIRES = "alert_expires_at"

        @Volatile
        private var current: WeakReference<IncomingOrderActivity>? = null

        private val backgroundScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

        fun intent(context: Context, alert: OrderAlert): Intent =
            Intent(context, IncomingOrderActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_NO_USER_ACTION
                putExtra(EXTRA_ID, alert.assignmentId)
                putExtra(EXTRA_TITLE, alert.title)
                putExtra(EXTRA_BODY, alert.body)
                putExtra(EXTRA_STORE, alert.storeName)
                putExtra(EXTRA_STORE_ADDRESS, alert.storeAddress)
                putExtra(EXTRA_CUSTOMER_ADDRESS, alert.customerAddress)
                putExtra(EXTRA_PAYMENT, alert.paymentMode)
                putExtra(EXTRA_TOTAL, alert.totalAmount)
                putExtra(EXTRA_FEE, alert.deliveryFee)
                putExtra(EXTRA_EXPIRES, alert.expiresAtIso)
            }

        private fun readAlert(intent: Intent?): OrderAlert? {
            val id = intent?.getStringExtra(EXTRA_ID).orEmpty()
            if (id.isBlank()) return null
            return OrderAlert(
                assignmentId = id,
                title = intent?.getStringExtra(EXTRA_TITLE).orEmpty().ifBlank { "New Delivery Request" },
                body = intent?.getStringExtra(EXTRA_BODY).orEmpty(),
                storeName = intent?.getStringExtra(EXTRA_STORE).orEmpty(),
                storeAddress = intent?.getStringExtra(EXTRA_STORE_ADDRESS).orEmpty(),
                customerAddress = intent?.getStringExtra(EXTRA_CUSTOMER_ADDRESS).orEmpty(),
                paymentMode = intent?.getStringExtra(EXTRA_PAYMENT).orEmpty(),
                totalAmount = intent?.getStringExtra(EXTRA_TOTAL).orEmpty(),
                deliveryFee = intent?.getStringExtra(EXTRA_FEE).orEmpty(),
                expiresAtIso = intent?.getStringExtra(EXTRA_EXPIRES)
            )
        }

        /** Closes the screen when the offer was handled elsewhere (app, notification, timeout). */
        fun closeIfShowing(assignmentId: String) {
            val activity = current?.get() ?: return
            activity.runOnUiThread {
                // While this screen is answering the offer itself it finishes on its own after the
                // server replies (and opens the app on accept); closing it early would skip that.
                if (activity.busy) return@runOnUiThread
                if (activity.alert?.assignmentId == assignmentId && !activity.isFinishing) activity.finish()
            }
        }
    }
}

@Composable
private fun IncomingOrderScreen(
    alert: OrderAlert,
    busy: Boolean,
    error: String?,
    onAccept: () -> Unit,
    onDecline: () -> Unit,
    onTimeout: () -> Unit
) {
    val expiresAt = remember(alert.assignmentId) { alert.expiresAtMillis() }
    var secondsLeft by remember(alert.assignmentId) {
        mutableLongStateOf(((expiresAt - ServerClock.now()) / 1000).coerceAtLeast(0))
    }
    LaunchedEffect(alert.assignmentId) {
        while (secondsLeft > 0) {
            delay(1_000)
            secondsLeft = ((expiresAt - ServerClock.now()) / 1000).coerceAtLeast(0)
        }
        onTimeout()
    }

    Surface(
        modifier = Modifier
            .fillMaxSize()
            .testTag("incoming_order_screen"),
        color = Color(0xFF0B3D2E)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(12.dp))
            Icon(Icons.Default.DeliveryDining, contentDescription = null, tint = Color.White, modifier = Modifier.size(56.dp))
            Spacer(Modifier.height(8.dp))
            Text(
                text = alert.title,
                color = Color.White,
                fontSize = 24.sp,
                fontWeight = FontWeight.ExtraBold,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(12.dp))
            Surface(shape = RoundedCornerShape(50), color = if (secondsLeft <= 15) Color(0xFFDC2626) else Color.White.copy(alpha = 0.15f)) {
                Text(
                    text = "${secondsLeft}s left",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 6.dp)
                )
            }

            Spacer(Modifier.height(20.dp))

            Card(
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (alert.deliveryFee.isNotBlank()) {
                        Text("Earning ₹${alert.deliveryFee}", fontSize = 26.sp, fontWeight = FontWeight.ExtraBold, color = EmeraldPrimary)
                    }
                    InfoRow(Icons.Default.Storefront, "Pickup", listOf(alert.storeName, alert.storeAddress).filter { it.isNotBlank() }.joinToString("\n"))
                    InfoRow(Icons.Default.LocationOn, "Drop", alert.customerAddress)
                    if (alert.totalAmount.isNotBlank()) {
                        val cash = isCashOnDelivery(alert.paymentMode)
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = if (cash) Color(0xFFFEF3C7) else Color(0xFFD1FAE5)
                        ) {
                            Text(
                                text = if (cash) "COLLECT CASH ₹${alert.totalAmount}" else "PREPAID • ₹${alert.totalAmount}",
                                fontWeight = FontWeight.Bold,
                                color = if (cash) Color(0xFF92400E) else Color(0xFF065F46),
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                            )
                        }
                    }
                    if (alert.body.isNotBlank() && alert.storeName.isBlank()) {
                        Text(alert.body, color = Color(0xFF334155))
                    }
                }
            }

            if (error != null) {
                Spacer(Modifier.height(12.dp))
                Text(error, color = Color(0xFFFCA5A5), fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
            }

            Spacer(Modifier.height(28.dp))

            Button(
                onClick = onAccept,
                enabled = !busy,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp)
                    .testTag("incoming_accept_button"),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF22C55E))
            ) {
                if (busy) {
                    CircularProgressIndicator(color = Color.White, modifier = Modifier.size(26.dp))
                } else {
                    Text("ACCEPT ORDER", fontSize = 20.sp, fontWeight = FontWeight.ExtraBold)
                }
            }
            Spacer(Modifier.height(12.dp))
            OutlinedButton(
                onClick = onDecline,
                enabled = !busy,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .testTag("incoming_decline_button"),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
            ) {
                Icon(Icons.Default.Close, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("Decline", fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun InfoRow(icon: ImageVector, label: String, value: String) {
    if (value.isBlank()) return
    Row(verticalAlignment = Alignment.Top) {
        Box(
            Modifier
                .size(32.dp)
                .background(Color(0xFFF1F5F9), RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = null, tint = EmeraldPrimary, modifier = Modifier.size(18.dp))
        }
        Spacer(Modifier.width(10.dp))
        Column {
            Text(label, fontSize = 12.sp, color = Color(0xFF64748B), fontWeight = FontWeight.SemiBold)
            Text(value, fontSize = 15.sp, color = Color(0xFF0F172A))
        }
    }
}
