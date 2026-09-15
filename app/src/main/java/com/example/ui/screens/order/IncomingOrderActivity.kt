package com.example.ui.screens.order

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.widget.Toast
import com.example.MainActivity
import com.example.data.local.AppDatabase
import com.example.data.local.AppPreferences
import com.example.data.model.DeliveryAssignment
import com.example.data.remote.SupabaseRepository
import com.example.ui.theme.*
import kotlinx.coroutines.*
import java.time.Instant

class IncomingOrderActivity : ComponentActivity() {

    private var ringtone: Ringtone? = null
    private var vibrator: Vibrator? = null
    private var activityScope = CoroutineScope(Dispatchers.Main + Job())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Wake screen and show over lockscreen for urgent order dispatch
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
            val keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
            keyguardManager.requestDismissKeyguard(this, null)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                        WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
                        WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                        WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_ALLOW_LOCK_WHILE_SCREEN_ON
        )

        val orderId = intent.getStringExtra("order_id")
            ?: intent.getStringExtra("orderId")
            ?: intent.getStringExtra(EXTRA_ORDER_ID)
            ?: ""
        val assignmentId = intent.getStringExtra(EXTRA_ASSIGNMENT_ID)
            ?: intent.getStringExtra("assignment_id")
            ?: intent.getStringExtra("assignmentId")
            ?: orderId

        val storeName = intent.getStringExtra(EXTRA_STORE_NAME) ?: "Sndmart DarkStore - Koramangala"
        val storeAddress = intent.getStringExtra(EXTRA_STORE_ADDRESS) ?: "100 Feet Rd, Indiranagar, Bengaluru"
        val customerAddress = intent.getStringExtra(EXTRA_CUSTOMER_ADDRESS) ?: "#42, 3rd Cross, Indiranagar, Bengaluru"
        val paymentMode = intent.getStringExtra(EXTRA_PAYMENT_MODE) ?: "PREPAID"
        val totalAmount = intent.getDoubleExtra(EXTRA_TOTAL_AMOUNT, 450.0)
        val deliveryFee = intent.getDoubleExtra(EXTRA_DELIVERY_FEE, 55.0)
        val pickupDistance = intent.getStringExtra("pickup_distance") ?: "0.8 km away"
        val dropDistance = intent.getStringExtra("drop_distance") ?: "2.4 km trip"
        val expiresAtIso = intent.getStringExtra("expires_at") ?: ""

        val prefs = AppPreferences(applicationContext)
        val database = AppDatabase.getInstance(applicationContext)
        val repository = SupabaseRepository(applicationContext, prefs, database)

        if (assignmentId.isNotBlank() && (prefs.isAssignmentHandled(assignmentId) || repository.isAssignmentHandled(assignmentId))) {
            Log.d("IncomingOrderActivity", "Assignment $assignmentId already handled. Dismissing.")
            finish()
            return
        }

        // Calculate countdown based on delivery_assignments.expires_at (55-60s per specification)
        var initialCountdown = 60
        if (expiresAtIso.isNotBlank() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                val expiryTime = Instant.parse(expiresAtIso).toEpochMilli()
                val diffSeconds = ((expiryTime - System.currentTimeMillis()) / 1000).toInt()
                if (diffSeconds in 5..120) {
                    initialCountdown = diffSeconds.coerceIn(5, 60)
                }
            } catch (e: Exception) {
                Log.w("IncomingOrderActivity", "Could not parse expires_at: ${e.message}")
            }
        }

        // Start loud ringtone + vibration alert
        startAlertSoundAndVibration(prefs)

        setContent {
            MyApplicationTheme {
                IncomingOrderScreen(
                    assignmentId = assignmentId,
                    storeName = storeName,
                    storeAddress = storeAddress,
                    customerAddress = customerAddress,
                    paymentMode = paymentMode,
                    totalAmount = totalAmount,
                    deliveryFee = deliveryFee,
                    pickupDistance = pickupDistance,
                    dropDistance = dropDistance,
                    initialCountdown = initialCountdown,
                    onAccept = {
                        stopAlertSoundAndVibration()
                        activityScope.launch {
                            val targetId = assignmentId.ifBlank { orderId }
                            val snapshot = DeliveryAssignment(
                                id = targetId,
                                orderId = orderId,
                                storeName = storeName,
                                storeAddress = storeAddress,
                                customerAddress = customerAddress,
                                paymentMode = paymentMode,
                                totalAmount = totalAmount,
                                deliveryFee = deliveryFee
                            )
                            val res = repository.respondToAssignment(targetId, true, snapshot)
                            if (res.isSuccess) {
                                val mainIntent = Intent(this@IncomingOrderActivity, MainActivity::class.java).apply {
                                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                                    putExtra("EXTRA_NAVIGATE_ASSIGNMENT_ID", targetId)
                                }
                                startActivity(mainIntent)
                                finish()
                            } else {
                                Toast.makeText(
                                    this@IncomingOrderActivity,
                                    "Accept order error: ${res.exceptionOrNull()?.message ?: "Failed to accept order"}",
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        }
                    },
                    onReject = {
                        stopAlertSoundAndVibration()
                        activityScope.launch {
                            val targetId = assignmentId.ifBlank { orderId }
                            repository.respondToAssignment(targetId, false)
                            finish()
                        }
                    }
                )
            }
        }
    }

    private fun startAlertSoundAndVibration(prefs: AppPreferences) {
        if (prefs.soundAlertsEnabled) {
            try {
                val ringtoneUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
                    ?: Settings.System.DEFAULT_RINGTONE_URI
                ringtone = RingtoneManager.getRingtone(applicationContext, ringtoneUri)?.apply {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                        audioAttributes = AudioAttributes.Builder()
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                            .build()
                    }
                    play()
                }
            } catch (e: Exception) {
                Log.w("IncomingOrderActivity", "Ringtone error: ${e.message}")
            }
        }

        try {
            vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vibratorManager?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }

            val pattern = longArrayOf(0, 800, 400, 800, 400, 800)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator?.vibrate(VibrationEffect.createWaveform(pattern, 0))
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(pattern, 0)
            }
        } catch (e: Exception) {
            Log.w("IncomingOrderActivity", "Vibrator error: ${e.message}")
        }
    }

    private fun stopAlertSoundAndVibration() {
        try {
            ringtone?.stop()
        } catch (ignored: Exception) {}
        try {
            vibrator?.cancel()
        } catch (ignored: Exception) {}
    }

    override fun onDestroy() {
        super.onDestroy()
        stopAlertSoundAndVibration()
        activityScope.cancel()
    }

    companion object {
        const val EXTRA_ASSIGNMENT_ID = "extra_assignment_id"
        const val EXTRA_ORDER_ID = "extra_order_id"
        const val EXTRA_TITLE = "extra_title"
        const val EXTRA_MESSAGE = "extra_message"
        const val EXTRA_STORE_NAME = "extra_store_name"
        const val EXTRA_STORE_ADDRESS = "extra_store_address"
        const val EXTRA_CUSTOMER_ADDRESS = "extra_customer_address"
        const val EXTRA_PAYMENT_MODE = "extra_payment_mode"
        const val EXTRA_TOTAL_AMOUNT = "extra_total_amount"
        const val EXTRA_DELIVERY_FEE = "extra_delivery_fee"
        const val EXTRA_EXPIRES_AT = "extra_expires_at"
    }
}

@Composable
fun IncomingOrderScreen(
    assignmentId: String,
    storeName: String,
    storeAddress: String,
    customerAddress: String,
    paymentMode: String,
    totalAmount: Double,
    deliveryFee: Double,
    pickupDistance: String,
    dropDistance: String,
    initialCountdown: Int = 60,
    onAccept: () -> Unit,
    onReject: () -> Unit
) {
    var secondsLeft by remember { mutableIntStateOf(initialCountdown) }

    LaunchedEffect(Unit) {
        while (secondsLeft > 0) {
            delay(1000)
            secondsLeft -= 1
        }
        if (secondsLeft == 0) {
            onReject()
        }
    }

    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.6f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseAlpha"
    )

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = Slate900
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Top Badge and Countdown
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth()
            ) {
                Surface(
                    color = AmberAccent.copy(alpha = 0.2f),
                    shape = RoundedCornerShape(20.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, AmberAccent.copy(alpha = pulseAlpha))
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.FlashOn,
                            contentDescription = null,
                            tint = AmberAccent,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "NEW ORDER DISPATCH",
                            style = MaterialTheme.typography.labelMedium.copy(
                                fontWeight = FontWeight.ExtraBold,
                                color = AmberAccent,
                                letterSpacing = 1.sp
                            )
                        )
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                // Circular Countdown Timer (matching 55-60s expires_at)
                Box(
                    modifier = Modifier
                        .size(110.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF1E293B))
                        .border(
                            4.dp,
                            if (secondsLeft > 15) EmeraldPrimary else Color(0xFFEF4444),
                            CircleShape
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "${secondsLeft}s",
                            style = MaterialTheme.typography.headlineMedium.copy(
                                fontWeight = FontWeight.Black,
                                color = if (secondsLeft > 15) Color.White else Color(0xFFEF4444)
                            )
                        )
                        Text(
                            text = "REMAINING",
                            style = MaterialTheme.typography.labelSmall.copy(
                                color = Slate400,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold
                            )
                        )
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                // Earnings & Fee Highlight Card
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = EmeraldPrimary)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(20.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = "ESTIMATED EARNING",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    color = Color.White.copy(alpha = 0.85f),
                                    fontWeight = FontWeight.Bold
                                )
                            )
                            Text(
                                text = "₹${deliveryFee.toInt()}",
                                style = MaterialTheme.typography.headlineLarge.copy(
                                    fontWeight = FontWeight.Black,
                                    color = Color.White
                                )
                            )
                        }

                        Surface(
                            color = Color.White.copy(alpha = 0.2f),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Column(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                horizontalAlignment = Alignment.End
                            ) {
                                Text(
                                    text = paymentMode.uppercase(),
                                    style = MaterialTheme.typography.labelMedium.copy(
                                        fontWeight = FontWeight.Black,
                                        color = Color.White
                                    )
                                )
                                Text(
                                    text = "Order: ₹${totalAmount.toInt()}",
                                    style = MaterialTheme.typography.bodySmall.copy(
                                        color = Color.White.copy(alpha = 0.9f)
                                    )
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                // Route Steps Card with distances
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B))
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(18.dp)
                    ) {
                        // Store Pickup
                        Row(verticalAlignment = Alignment.Top) {
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(CircleShape)
                                    .background(EmeraldContainer),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Storefront,
                                    contentDescription = null,
                                    tint = EmeraldPrimary,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(
                                    text = "PICKUP ($pickupDistance)",
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontWeight = FontWeight.Bold,
                                        color = EmeraldPrimary
                                    )
                                )
                                Text(
                                    text = storeName,
                                    style = MaterialTheme.typography.titleMedium.copy(
                                        fontWeight = FontWeight.Bold,
                                        color = Color.White
                                    )
                                )
                                Text(
                                    text = storeAddress,
                                    style = MaterialTheme.typography.bodySmall.copy(
                                        color = Slate400
                                    )
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))
                        Divider(color = Slate700)
                        Spacer(modifier = Modifier.height(16.dp))

                        // Customer Drop
                        Row(verticalAlignment = Alignment.Top) {
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(CircleShape)
                                    .background(AmberContainer),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Place,
                                    contentDescription = null,
                                    tint = AmberAccent,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(
                                    text = "DROP-OFF ($dropDistance)",
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontWeight = FontWeight.Bold,
                                        color = AmberAccent
                                    )
                                )
                                Text(
                                    text = customerAddress,
                                    style = MaterialTheme.typography.bodyMedium.copy(
                                        color = Color.White
                                    )
                                )
                            }
                        }
                    }
                }
            }

            // Action Buttons
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Button(
                    onClick = onAccept,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .testTag("incoming_order_accept_button"),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = EmeraldPrimary)
                ) {
                    Icon(Icons.Default.CheckCircle, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "ACCEPT ORDER",
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.ExtraBold,
                            color = Color.White
                        )
                    )
                }

                OutlinedButton(
                    onClick = onReject,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp)
                        .testTag("incoming_order_reject_button"),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Slate400),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Slate700)
                ) {
                    Text(
                        text = "REJECT / PASS",
                        style = MaterialTheme.typography.bodyLarge.copy(
                            fontWeight = FontWeight.SemiBold,
                            color = Slate400
                        )
                    )
                }
            }
        }
    }
}

