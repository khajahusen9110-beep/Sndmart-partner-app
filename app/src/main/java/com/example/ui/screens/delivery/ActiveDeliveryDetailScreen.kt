package com.example.ui.screens.delivery

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import coil.compose.AsyncImage
import com.example.BuildConfig
import com.example.data.model.DeliveryAssignment
import com.example.data.model.OrderItem
import com.example.data.model.OrderQrResponse
import com.example.data.model.UploadState
import com.example.ui.MainViewModel
import com.example.ui.components.*
import com.example.ui.theme.*
import com.example.util.ImageUtils
import com.example.util.isCashOnDelivery
import com.example.util.isPaidOnline
import com.example.util.paymentLabel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActiveDeliveryDetailScreen(
    assignment: DeliveryAssignment,
    viewModel: MainViewModel,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val coroutineScope = rememberCoroutineScope()

    var deliveryOtp by remember { mutableStateOf("") }

    val pickupUploadState by viewModel.pickupPhotoUploadState.collectAsState()
    val dropUploadState by viewModel.dropPhotoUploadState.collectAsState()

    var lastPickupPhotoBytes by remember { mutableStateOf<ByteArray?>(null) }
    var lastDropPhotoBytes by remember { mutableStateOf<ByteArray?>(null) }

    LaunchedEffect(assignment.id, assignment.pickupPhotoUrl) {
        viewModel.resetPickupPhotoUploadState(assignment.pickupPhotoUrl)
    }

    LaunchedEffect(assignment.id, assignment.deliveredPhotoUrl) {
        viewModel.resetDropPhotoUploadState(assignment.deliveredPhotoUrl)
    }

    val isUploadingPickupPhoto = pickupUploadState is UploadState.Uploading
    val currentPickupPhotoPath = (pickupUploadState as? UploadState.Success)?.path
        ?: assignment.pickupPhotoUrl.orEmpty()
    val isPickupPhotoSuccess = pickupUploadState is UploadState.Success || currentPickupPhotoPath.isNotBlank()

    val isUploadingDropPhoto = dropUploadState is UploadState.Uploading
    val currentDropPhotoPath = (dropUploadState as? UploadState.Success)?.path
        ?: assignment.deliveredPhotoUrl.orEmpty()
    val isDropPhotoSuccess = dropUploadState is UploadState.Success || currentDropPhotoPath.isNotBlank()

    var showPickupPhotoOptionsDialog by remember { mutableStateOf(false) }
    var showDropPhotoOptionsDialog by remember { mutableStateOf(false) }

    var showCodCashDialog by remember { mutableStateOf(false) }
    var showQrDialog by remember { mutableStateOf(false) }
    val isActionLoading by viewModel.isActionLoading.collectAsState()
    val currentLocation by viewModel.currentLocation.collectAsState()

    var orderItems by remember(assignment.id, assignment.orderId) {
        mutableStateOf(assignment.orderItems)
    }
    var isLoadingItems by remember { mutableStateOf(false) }

    LaunchedEffect(assignment.id, assignment.orderId, assignment.orderNumber) {
        val effectiveId = assignment.orderId.ifBlank { assignment.orders?.id.orEmpty() }
        if (orderItems.isEmpty() && effectiveId.isNotBlank()) {
            isLoadingItems = true
            val items = viewModel.fetchOrderItems(effectiveId)
            if (items.isNotEmpty()) {
                orderItems = items
            }
            isLoadingItems = false
        }
    }

    fun startPickupPhotoUpload(bytes: ByteArray) {
        lastPickupPhotoBytes = bytes
        coroutineScope.launch {
            viewModel.uploadPickupPhoto(assignment.id, bytes)
        }
    }

    fun retryPickupPhotoUpload() {
        val bytes = lastPickupPhotoBytes
        if (bytes != null && bytes.isNotEmpty()) {
            coroutineScope.launch {
                viewModel.uploadPickupPhoto(assignment.id, bytes)
            }
        } else {
            showPickupPhotoOptionsDialog = true
        }
    }

    // Safe pickup camera & photo picker launchers
    // Full-resolution capture into a FileProvider file (TakePicturePreview only returns a
    // thumbnail, too small to serve as delivery proof). Saveable: survives the camera app
    // pushing this screen out of memory.
    var pickupCaptureUri by rememberSaveable { mutableStateOf<String?>(null) }
    val pickupCameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture()
    ) { saved ->
        val uri = pickupCaptureUri?.let(Uri::parse)
        if (saved && uri != null) {
            try {
                val compressed = ImageUtils.compressAndResizeUri(context, uri)
                if (compressed != null && compressed.isNotEmpty()) {
                    startPickupPhotoUpload(compressed)
                } else {
                    viewModel.showMessage("Unable to read the photo, please try again")
                }
            } catch (e: Exception) {
                Log.e("ActiveDeliveryDetail", "Failed to process pickup photo: ${e.message}", e)
                viewModel.showMessage("Failed to process photo: ${e.message}")
            }
        }
    }

    fun launchPickupCamera() {
        val uri = ImageUtils.newCaptureUri(context)
        pickupCaptureUri = uri.toString()
        pickupCameraLauncher.launch(uri)
    }

    val pickupCameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            try {
                launchPickupCamera()
            } catch (e: ActivityNotFoundException) {
                Log.e("ActiveDeliveryDetail", "No camera app found: ${e.message}", e)
                viewModel.showMessage("No camera app found on device. Please select from gallery.")
            } catch (e: Exception) {
                Log.e("ActiveDeliveryDetail", "Failed to open camera: ${e.message}", e)
                viewModel.showMessage("Couldn't open camera, please try again")
            }
        } else {
            viewModel.showMessage("Camera permission is required to photograph the order bag")
        }
    }

    val pickupGalleryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            try {
                val compressed = ImageUtils.compressAndResizeUri(context, uri)
                if (compressed != null && compressed.isNotEmpty()) {
                    startPickupPhotoUpload(compressed)
                } else {
                    viewModel.showMessage("Unable to read selected photo")
                }
            } catch (e: Exception) {
                Log.e("ActiveDeliveryDetail", "Error reading image URI: ${e.message}", e)
                viewModel.showMessage("Failed to load photo: ${e.message}")
            }
        }
    }

    fun launchPickupCameraSafely() {
        try {
            val hasCameraPermission = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED

            if (hasCameraPermission) {
                launchPickupCamera()
            } else {
                pickupCameraPermissionLauncher.launch(Manifest.permission.CAMERA)
            }
        } catch (e: ActivityNotFoundException) {
            Log.e("ActiveDeliveryDetail", "No camera app found: ${e.message}", e)
            viewModel.showMessage("No camera app available. Please choose from gallery.")
        } catch (e: Exception) {
            Log.e("ActiveDeliveryDetail", "Camera launch error: ${e.message}", e)
            viewModel.showMessage("Couldn't open camera, please try again")
        }
    }

    fun uploadDemoPickupPhoto() {
        try {
            val rawBytes = createFallbackProofBytes("PICKUP_BAG_VERIFICATION", assignment.orderNumber)
            val compressed = ImageUtils.compressAndResizeBytes(rawBytes)
            startPickupPhotoUpload(compressed)
        } catch (e: Exception) {
            viewModel.showMessage("Failed to create demo photo: ${e.message}")
        }
    }

    fun startDropPhotoUpload(bytes: ByteArray) {
        lastDropPhotoBytes = bytes
        coroutineScope.launch {
            viewModel.uploadDeliveredPhoto(assignment.id, bytes)
        }
    }

    fun retryDropPhotoUpload() {
        val bytes = lastDropPhotoBytes
        if (bytes != null && bytes.isNotEmpty()) {
            coroutineScope.launch {
                viewModel.uploadDeliveredPhoto(assignment.id, bytes)
            }
        } else {
            showDropPhotoOptionsDialog = true
        }
    }

    // Safe doorstep photo launchers
    // Full-resolution capture into a FileProvider file (TakePicturePreview only returns a
    // thumbnail, too small to serve as delivery proof). Saveable: survives the camera app
    // pushing this screen out of memory.
    var dropCaptureUri by rememberSaveable { mutableStateOf<String?>(null) }
    val dropCameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture()
    ) { saved ->
        val uri = dropCaptureUri?.let(Uri::parse)
        if (saved && uri != null) {
            try {
                val compressed = ImageUtils.compressAndResizeUri(context, uri)
                if (compressed != null && compressed.isNotEmpty()) {
                    startDropPhotoUpload(compressed)
                } else {
                    viewModel.showMessage("Unable to read the photo, please try again")
                }
            } catch (e: Exception) {
                Log.e("ActiveDeliveryDetail", "Failed to process drop photo: ${e.message}", e)
                viewModel.showMessage("Failed to process photo: ${e.message}")
            }
        }
    }

    fun launchDropCamera() {
        val uri = ImageUtils.newCaptureUri(context)
        dropCaptureUri = uri.toString()
        dropCameraLauncher.launch(uri)
    }

    val dropCameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            try {
                launchDropCamera()
            } catch (e: ActivityNotFoundException) {
                viewModel.showMessage("No camera app found on device. Please select from gallery.")
            } catch (e: Exception) {
                viewModel.showMessage("Couldn't open camera, please try again")
            }
        } else {
            viewModel.showMessage("Camera permission is required to photograph doorstep delivery")
        }
    }

    val dropGalleryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            try {
                val compressed = ImageUtils.compressAndResizeUri(context, uri)
                if (compressed != null && compressed.isNotEmpty()) {
                    startDropPhotoUpload(compressed)
                }
            } catch (e: Exception) {
                viewModel.showMessage("Failed to load photo: ${e.message}")
            }
        }
    }

    fun launchDropCameraSafely() {
        try {
            val hasCameraPermission = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED

            if (hasCameraPermission) {
                launchDropCamera()
            } else {
                dropCameraPermissionLauncher.launch(Manifest.permission.CAMERA)
            }
        } catch (e: ActivityNotFoundException) {
            viewModel.showMessage("No camera app available. Please choose from gallery.")
        } catch (e: Exception) {
            viewModel.showMessage("Couldn't open camera, please try again")
        }
    }

    fun uploadDemoDropPhoto() {
        try {
            val rawBytes = createFallbackProofBytes("DOORSTEP_DROP_PROOF", assignment.orderNumber)
            val compressed = ImageUtils.compressAndResizeBytes(rawBytes)
            startDropPhotoUpload(compressed)
        } catch (e: Exception) {
            viewModel.showMessage("Failed to create demo photo: ${e.message}")
        }
    }

    val currentStatus = assignment.status

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "Order: ${assignment.orderNumber}",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                        )
                        Text(
                            text = "Payout: ₹${assignment.deliveryFee.toInt()} • ${paymentLabel(assignment.paymentMode, assignment.paymentStatus)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = EmeraldPrimary
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    DeliveryStatusBadge(status = currentStatus)
                    Spacer(modifier = Modifier.width(12.dp))
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Countdown from pickup (server-set deadline)
            DeliveryTimerCard(assignment)

            // Google Maps Live Delivery Route with 3 Markers & Directions Route
            ActiveDeliveryMapView(
                assignment = assignment,
                partnerCurrentLat = currentLocation.first,
                partnerCurrentLng = currentLocation.second
            )

            // Step Details Card (Pickup & Drop contact / instructions)
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Map, contentDescription = null, tint = TealSecondary)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Live Delivery Route",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                            )
                        }

                        val targetLat = if (currentStatus in listOf("assigned", "accepted")) assignment.storeLat else assignment.customerLat
                        val targetLng = if (currentStatus in listOf("assigned", "accepted")) assignment.storeLng else assignment.customerLng
                        val targetLabel = if (currentStatus in listOf("assigned", "accepted")) assignment.storeName else assignment.customerName

                        OpenMapsButton(
                            destinationLat = targetLat,
                            destinationLng = targetLng,
                            label = targetLabel
                        )
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // Step 1: Store Pickup Info
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = if (currentStatus in listOf("assigned", "accepted")) TealContainer.copy(alpha = 0.4f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.Top, modifier = Modifier.weight(1f)) {
                                Box(
                                    modifier = Modifier
                                        .size(32.dp)
                                        .clip(CircleShape)
                                        .background(TealSecondary),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(Icons.Default.Storefront, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                                }
                                Spacer(modifier = Modifier.width(10.dp))
                                Column {
                                    Text(text = "1. Pickup: ${assignment.storeName}", style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold))
                                    Text(text = assignment.storeAddress, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            PhoneCallButton(phoneNumber = assignment.storePhone)
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // Step 2: Customer Drop Info
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = if (currentStatus in listOf("picked_up", "out_for_delivery", "arrived_drop")) AmberContainer.copy(alpha = 0.4f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.Top, modifier = Modifier.weight(1f)) {
                                Box(
                                    modifier = Modifier
                                        .size(32.dp)
                                        .clip(CircleShape)
                                        .background(AmberAccent),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(Icons.Default.LocationOn, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                                }
                                Spacer(modifier = Modifier.width(10.dp))
                                Column {
                                    Text(text = "2. Drop: ${assignment.customerName}", style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold))
                                    Text(text = assignment.customerAddress, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    val deliveryInstr = assignment.orders?.deliveryInstructions?.takeIf { it.isNotBlank() }
                                    if (deliveryInstr != null) {
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(
                                            text = "📝 $deliveryInstr",
                                            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                                            color = AmberAccent
                                        )
                                    }
                                }
                            }
                            PhoneCallButton(phoneNumber = assignment.customerPhone)
                        }
                    }
                }
            }

            // Order Items & Checklist Card
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("order_items_card"),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.Inventory2,
                                contentDescription = null,
                                tint = EmeraldPrimary,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            val totalItemQty = if (orderItems.isNotEmpty()) orderItems.sumOf { it.quantity } else assignment.itemCount
                            Text(
                                text = "Order Items ($totalItemQty items)",
                                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
                            )
                        }
                        val paidOnline = isPaidOnline(assignment.paymentMode, assignment.paymentStatus)
                        Surface(
                            color = if (paidOnline) EmeraldContainer else AmberContainer,
                            shape = RoundedCornerShape(6.dp)
                        ) {
                            Text(
                                text = when {
                                    isCashOnDelivery(assignment.paymentMode) -> "CASH ON DELIVERY\nCOLLECT ₹${assignment.totalAmount.toInt()}"
                                    paidOnline -> "UPI PAID\nDON'T COLLECT CASH"
                                    else -> "UPI NOT PAID YET"
                                },
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontWeight = FontWeight.Bold,
                                    color = if (paidOnline) EmeraldOnContainer else AmberOnContainer
                                ),
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                    Spacer(modifier = Modifier.height(10.dp))

                    if (isLoadingItems) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 12.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                color = EmeraldPrimary,
                                strokeWidth = 2.dp
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                "Loading order items...",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    } else if (orderItems.isNotEmpty()) {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            orderItems.forEachIndexed { index, item ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = item.productName.ifBlank { "Item #${index + 1}" },
                                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            if (!item.variantLabel.isNullOrBlank()) {
                                                Surface(
                                                    shape = RoundedCornerShape(4.dp),
                                                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)
                                                ) {
                                                    Text(
                                                        text = item.variantLabel,
                                                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                    )
                                                }
                                            }
                                            val unitPriceText = if (item.unitPrice > 0) " × ₹${item.unitPrice.toInt()}" else ""
                                            Text(
                                                text = "Qty: ${item.quantity}$unitPriceText",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                    val itemTotal = if (item.totalPrice > 0) item.totalPrice else (item.unitPrice * item.quantity)
                                    Text(
                                        text = "₹${itemTotal.toInt()}",
                                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                }
                            }
                        }
                    } else {
                        Text(
                            text = assignment.itemsSummary.ifBlank { "Standard DarkStore Package (${assignment.itemCount} items)" },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Spacer(modifier = Modifier.height(14.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                    Spacer(modifier = Modifier.height(10.dp))

                    // Grand Total Row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Order Total Amount",
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        val total = if (assignment.totalAmount > 0) {
                            assignment.totalAmount
                        } else if (orderItems.isNotEmpty()) {
                            orderItems.sumOf { it.totalPrice }
                        } else {
                            0.0
                        }
                        Text(
                            text = "₹${total.toInt()}",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.ExtraBold),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    // Payout Row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Your Delivery Payout",
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                            color = EmeraldPrimary
                        )
                        Text(
                            text = "+ ₹${assignment.deliveryFee.toInt()}",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = EmeraldPrimary
                        )
                    }
                }
            }

            // Collect a COD order online: show a UPI QR. Money goes to the Sndmart account,
            // not the partner's phone, and the order becomes "paid online" (no cash to hand over).
            val alreadyPaid = assignment.paymentStatus.equals("paid", ignoreCase = true)
            if (isCashOnDelivery(assignment.paymentMode) && !alreadyPaid &&
                currentStatus !in listOf("delivered", "cancelled", "rejected")
            ) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(containerColor = TealContainer.copy(alpha = 0.3f))
                ) {
                    Column(modifier = Modifier.padding(18.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.QrCode2, contentDescription = null, tint = TealSecondary)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Customer online pay karega?",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "UPI QR dikhao — paisa seedha Sndmart account me jaayega, aapke paas cash nahi aayega. Pay hote hi apne aap verify ho jaayega.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(14.dp))
                        Button(
                            onClick = { showQrDialog = true },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(50.dp)
                                .testTag("collect_online_qr_button"),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = TealSecondary)
                        ) {
                            Icon(Icons.Default.QrCode2, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                "Show UPI QR • ₹${assignment.totalAmount.toInt()}",
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }

            // Dynamic Action Steps according to Status
            when (currentStatus) {
                "assigned", "accepted" -> {
                    // Step: Reach store and mark picked up with photo
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(18.dp),
                        colors = CardDefaults.cardColors(containerColor = EmeraldContainer.copy(alpha = 0.2f))
                    ) {
                        Column(modifier = Modifier.padding(18.dp)) {
                            Text(
                                text = "Step 1: Store Pickup Confirmation",
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontWeight = FontWeight.Bold,
                                    color = EmeraldPrimary
                                )
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Verify packed bag with darkstore supervisor and take a photo of the bag.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            Spacer(modifier = Modifier.height(14.dp))

                            // Photo capture with genuine server upload
                            OutlinedButton(
                                onClick = {
                                    if (!isUploadingPickupPhoto) {
                                        showPickupPhotoOptionsDialog = true
                                    }
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("capture_pickup_photo_button"),
                                shape = RoundedCornerShape(12.dp),
                                enabled = !isActionLoading && !isUploadingPickupPhoto,
                                colors = ButtonDefaults.outlinedButtonColors(
                                    containerColor = when {
                                        isPickupPhotoSuccess -> EmeraldContainer
                                        pickupUploadState is UploadState.Error -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
                                        else -> Color.Transparent
                                    }
                                )
                            ) {
                                when {
                                    isUploadingPickupPhoto -> {
                                        CircularProgressIndicator(modifier = Modifier.size(18.dp), color = EmeraldPrimary, strokeWidth = 2.dp)
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text("Uploading Bag Photo...", fontWeight = FontWeight.Bold)
                                    }
                                    isPickupPhotoSuccess -> {
                                        Icon(
                                            imageVector = Icons.Default.CheckCircle,
                                            contentDescription = null,
                                            tint = EmeraldPrimary
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = "Pickup Photo Attached ✓",
                                            fontWeight = FontWeight.Bold,
                                            color = EmeraldPrimary
                                        )
                                    }
                                    pickupUploadState is UploadState.Error -> {
                                        Icon(
                                            imageVector = Icons.Default.ErrorOutline,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.error
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = "Upload Failed (Tap to Change)",
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.error
                                        )
                                    }
                                    else -> {
                                        Icon(
                                            imageVector = Icons.Default.CameraAlt,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.onSurface
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = "Take / Upload Bag Photo (Mandatory)",
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                            }

                            // Error card with retry affordance
                            if (pickupUploadState is UploadState.Error) {
                                val errorMsg = (pickupUploadState as UploadState.Error).message
                                Spacer(modifier = Modifier.height(10.dp))
                                Card(
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(12.dp),
                                    colors = CardDefaults.cardColors(
                                        containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.85f)
                                    )
                                ) {
                                    Column(modifier = Modifier.padding(12.dp)) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Icon(
                                                imageVector = Icons.Default.ErrorOutline,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.error,
                                                modifier = Modifier.size(20.dp)
                                            )
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Text(
                                                text = "Upload Failed",
                                                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                                color = MaterialTheme.colorScheme.onErrorContainer
                                            )
                                        }
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(
                                            text = errorMsg,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onErrorContainer
                                        )
                                        Spacer(modifier = Modifier.height(8.dp))
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.End
                                        ) {
                                            OutlinedButton(
                                                onClick = { showPickupPhotoOptionsDialog = true },
                                                modifier = Modifier.height(36.dp),
                                                shape = RoundedCornerShape(8.dp),
                                                contentPadding = PaddingValues(horizontal = 10.dp)
                                            ) {
                                                Text("Choose Other", style = MaterialTheme.typography.labelMedium)
                                            }
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Button(
                                                onClick = { retryPickupPhotoUpload() },
                                                modifier = Modifier
                                                    .height(36.dp)
                                                    .testTag("retry_pickup_upload_button"),
                                                shape = RoundedCornerShape(8.dp),
                                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                                                contentPadding = PaddingValues(horizontal = 12.dp)
                                            ) {
                                                Icon(
                                                    Icons.Default.Refresh,
                                                    contentDescription = null,
                                                    modifier = Modifier.size(16.dp)
                                                )
                                                Spacer(modifier = Modifier.width(4.dp))
                                                Text("Retry Upload", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                                            }
                                        }
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(14.dp))

                            Button(
                                onClick = {
                                    if (!isPickupPhotoSuccess || currentPickupPhotoPath.isBlank()) {
                                        viewModel.showMessage("Mandatory: Please photograph and upload the packed order bag first")
                                        return@Button
                                    }
                                    viewModel.markPickedUp(
                                        assignment.id,
                                        currentPickupPhotoPath
                                    )
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(52.dp)
                                    .testTag("mark_picked_up_button"),
                                shape = RoundedCornerShape(12.dp),
                                enabled = !isActionLoading && isPickupPhotoSuccess && currentPickupPhotoPath.isNotBlank(),
                                colors = ButtonDefaults.buttonColors(containerColor = EmeraldPrimary)
                            ) {
                                if (isActionLoading) {
                                    CircularProgressIndicator(modifier = Modifier.size(22.dp), color = Color.White)
                                } else {
                                    Icon(Icons.Default.ShoppingBag, contentDescription = null)
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("CONFIRM PICKUP & START", fontWeight = FontWeight.ExtraBold)
                                }
                            }
                        }
                    }
                }

                "picked_up" -> {
                    // Step: Start navigation towards customer
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(18.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                    ) {
                        Column(modifier = Modifier.padding(18.dp)) {
                            Text(
                                text = "Step 2: Start Delivery Ride",
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontWeight = FontWeight.Bold,
                                    color = EmeraldPrimary
                                )
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Items secured in delivery bag. Navigate to customer drop location.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            Spacer(modifier = Modifier.height(16.dp))

                            Button(
                                onClick = { viewModel.startDelivery(assignment.id) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(52.dp)
                                    .testTag("start_delivery_button"),
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = EmeraldPrimary)
                            ) {
                                Icon(Icons.Default.TwoWheeler, contentDescription = null)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("START DELIVERY RIDE", fontWeight = FontWeight.ExtraBold)
                            }
                        }
                    }
                }

                "out_for_delivery" -> {
                    // Step: Arrived at drop location
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(18.dp),
                        colors = CardDefaults.cardColors(containerColor = AmberContainer.copy(alpha = 0.25f))
                    ) {
                        Column(modifier = Modifier.padding(18.dp)) {
                            Text(
                                text = "Step 3: Arrived at Customer Location",
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontWeight = FontWeight.Bold,
                                    color = AmberAccent
                                )
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Tap when you have arrived at the customer doorstep / building gate.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            Spacer(modifier = Modifier.height(16.dp))

                            Button(
                                onClick = { viewModel.arriveAtDrop(assignment.id) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(52.dp)
                                    .testTag("arrived_at_drop_button"),
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = AmberAccent)
                            ) {
                                Icon(Icons.Default.Place, contentDescription = null)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("I HAVE ARRIVED AT DROP", fontWeight = FontWeight.ExtraBold)
                            }
                        }
                    }
                }

                "arrived_drop" -> {
                    // Step 4: OTP Verification & Delivery Completion
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(18.dp),
                        colors = CardDefaults.cardColors(containerColor = EmeraldContainer.copy(alpha = 0.3f))
                    ) {
                        Column(modifier = Modifier.padding(18.dp)) {
                            Text(
                                text = "Step 4: Customer OTP Verification",
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontWeight = FontWeight.Bold,
                                    color = EmeraldPrimary
                                )
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Ask the customer for their 4-digit delivery verification PIN code.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            Spacer(modifier = Modifier.height(16.dp))

                            OutlinedTextField(
                                value = deliveryOtp,
                                onValueChange = { deliveryOtp = it.take(6) },
                                label = { Text("Enter Customer OTP (e.g. 4821)") },
                                leadingIcon = { Icon(Icons.Default.Key, contentDescription = null) },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("delivery_otp_input"),
                                shape = RoundedCornerShape(12.dp)
                            )

                            Spacer(modifier = Modifier.height(12.dp))

                            // Delivery Doorstep Photo Proof with genuine server upload
                            OutlinedButton(
                                onClick = {
                                    if (!isUploadingDropPhoto) {
                                        showDropPhotoOptionsDialog = true
                                    }
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("capture_delivered_photo_button"),
                                shape = RoundedCornerShape(12.dp),
                                enabled = !isActionLoading && !isUploadingDropPhoto,
                                colors = ButtonDefaults.outlinedButtonColors(
                                    containerColor = when {
                                        isDropPhotoSuccess -> EmeraldContainer
                                        dropUploadState is UploadState.Error -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
                                        else -> Color.Transparent
                                    }
                                )
                            ) {
                                when {
                                    isUploadingDropPhoto -> {
                                        CircularProgressIndicator(modifier = Modifier.size(18.dp), color = EmeraldPrimary, strokeWidth = 2.dp)
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text("Uploading Doorstep Photo...", fontWeight = FontWeight.Bold)
                                    }
                                    isDropPhotoSuccess -> {
                                        Icon(
                                            imageVector = Icons.Default.CheckCircle,
                                            contentDescription = null,
                                            tint = EmeraldPrimary
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = "Doorstep Photo Attached ✓",
                                            fontWeight = FontWeight.Bold,
                                            color = EmeraldPrimary
                                        )
                                    }
                                    dropUploadState is UploadState.Error -> {
                                        Icon(
                                            imageVector = Icons.Default.ErrorOutline,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.error
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = "Upload Failed (Tap to Change)",
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.error
                                        )
                                    }
                                    else -> {
                                        Icon(
                                            imageVector = Icons.Default.CameraAlt,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.onSurface
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = "Take Doorstep Photo (Mandatory)",
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                            }

                            // Error card with retry affordance
                            if (dropUploadState is UploadState.Error) {
                                val errorMsg = (dropUploadState as UploadState.Error).message
                                Spacer(modifier = Modifier.height(10.dp))
                                Card(
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(12.dp),
                                    colors = CardDefaults.cardColors(
                                        containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.85f)
                                    )
                                ) {
                                    Column(modifier = Modifier.padding(12.dp)) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Icon(
                                                imageVector = Icons.Default.ErrorOutline,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.error,
                                                modifier = Modifier.size(20.dp)
                                            )
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Text(
                                                text = "Upload Failed",
                                                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                                color = MaterialTheme.colorScheme.onErrorContainer
                                            )
                                        }
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(
                                            text = errorMsg,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onErrorContainer
                                        )
                                        Spacer(modifier = Modifier.height(8.dp))
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.End
                                        ) {
                                            OutlinedButton(
                                                onClick = { showDropPhotoOptionsDialog = true },
                                                modifier = Modifier.height(36.dp),
                                                shape = RoundedCornerShape(8.dp),
                                                contentPadding = PaddingValues(horizontal = 10.dp)
                                            ) {
                                                Text("Choose Other", style = MaterialTheme.typography.labelMedium)
                                            }
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Button(
                                                onClick = { retryDropPhotoUpload() },
                                                modifier = Modifier
                                                    .height(36.dp)
                                                    .testTag("retry_drop_upload_button"),
                                                shape = RoundedCornerShape(8.dp),
                                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                                                contentPadding = PaddingValues(horizontal = 12.dp)
                                            ) {
                                                Icon(
                                                    Icons.Default.Refresh,
                                                    contentDescription = null,
                                                    modifier = Modifier.size(16.dp)
                                                )
                                                Spacer(modifier = Modifier.width(4.dp))
                                                Text("Retry Upload", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                                            }
                                        }
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(16.dp))

                            Button(
                                onClick = {
                                    focusManager.clearFocus()
                                    if (!isDropPhotoSuccess || currentDropPhotoPath.isBlank()) {
                                        viewModel.showMessage("Mandatory: Please photograph the delivered packet at doorstep")
                                        return@Button
                                    }
                                    if (deliveryOtp.length < 4) {
                                        viewModel.showMessage("Please enter valid 4-digit OTP from customer")
                                        return@Button
                                    }
                                    viewModel.completeDelivery(
                                        assignmentId = assignment.id,
                                        otp = deliveryOtp,
                                        photoUrl = currentDropPhotoPath
                                    ) { success ->
                                        if (success) {
                                            if (isCashOnDelivery(assignment.paymentMode)) {
                                                showCodCashDialog = true
                                            } else {
                                                onBack()
                                            }
                                        }
                                    }
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(52.dp)
                                    .testTag("complete_delivery_button"),
                                shape = RoundedCornerShape(12.dp),
                                enabled = !isActionLoading && deliveryOtp.length >= 4 && isDropPhotoSuccess && currentDropPhotoPath.isNotBlank(),
                                colors = ButtonDefaults.buttonColors(containerColor = EmeraldPrimary)
                            ) {
                                if (isActionLoading) {
                                    CircularProgressIndicator(modifier = Modifier.size(22.dp), color = Color.White)
                                } else {
                                    Icon(Icons.Default.CheckCircle, contentDescription = null)
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("VERIFY OTP & COMPLETE DELIVERY", fontWeight = FontWeight.ExtraBold)
                                }
                            }
                        }
                    }
                }

                "delivered" -> {
                    // Completed state banner
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = EmeraldContainer)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(20.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(Icons.Default.CheckCircle, contentDescription = null, tint = EmeraldPrimary, modifier = Modifier.size(48.dp))
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(text = "Order Successfully Delivered!", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold, color = EmeraldOnContainer))
                            Text(text = "Your earning for this order will appear in Earnings shortly.", style = MaterialTheme.typography.bodyMedium, color = EmeraldOnContainer)
                        }
                    }
                }
            }
        }
    }

    if (showCodCashDialog) {
        AlertDialog(
            onDismissRequest = {
                showCodCashDialog = false
                onBack()
            },
            icon = { Icon(Icons.Default.Payments, contentDescription = null, tint = AmberAccent, modifier = Modifier.size(36.dp)) },
            title = { Text("Collect Cash: ₹${assignment.totalAmount.toInt()}") },
            text = {
                Column {
                    Text("Please make sure you have collected ₹${assignment.totalAmount.toInt()} in cash from ${assignment.customerName}.")
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "It has been added to your Cash Ledger automatically. Hand it over at the Sndmart hub and request a remittance from Earnings.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showCodCashDialog = false
                        onBack()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = EmeraldPrimary)
                ) {
                    Text("OK, Cash Collected")
                }
            }
        )
    }

    if (showQrDialog) {
        CollectByQrDialog(
            assignment = assignment,
            viewModel = viewModel,
            onDismiss = { showQrDialog = false }
        )
    }

    if (showPickupPhotoOptionsDialog) {
        AlertDialog(
            onDismissRequest = { showPickupPhotoOptionsDialog = false },
            icon = { Icon(Icons.Default.CameraAlt, contentDescription = null, tint = EmeraldPrimary, modifier = Modifier.size(32.dp)) },
            title = { Text("Take / Upload Bag Photo") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "Photograph the packed bag to verify items before starting pickup.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    OutlinedButton(
                        onClick = {
                            showPickupPhotoOptionsDialog = false
                            launchPickupCameraSafely()
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(Icons.Default.CameraAlt, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Open Camera")
                    }
                    OutlinedButton(
                        onClick = {
                            showPickupPhotoOptionsDialog = false
                            try {
                                pickupGalleryLauncher.launch("image/*")
                            } catch (e: Exception) {
                                Log.e("ActiveDeliveryDetail", "Gallery launch error: ${e.message}", e)
                                viewModel.showMessage("Couldn't open gallery")
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(Icons.Default.PhotoLibrary, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Choose from Gallery")
                    }
                    if (BuildConfig.DEBUG) TextButton(
                        onClick = {
                            showPickupPhotoOptionsDialog = false
                            uploadDemoPickupPhoto()
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.BugReport, contentDescription = null)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Use Test Bag Photo (Emulator/Test)")
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showPickupPhotoOptionsDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (showDropPhotoOptionsDialog) {
        AlertDialog(
            onDismissRequest = { showDropPhotoOptionsDialog = false },
            icon = { Icon(Icons.Default.CameraAlt, contentDescription = null, tint = EmeraldPrimary, modifier = Modifier.size(32.dp)) },
            title = { Text("Take Doorstep Photo") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "Photograph the delivered package at the customer doorstep.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    OutlinedButton(
                        onClick = {
                            showDropPhotoOptionsDialog = false
                            launchDropCameraSafely()
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(Icons.Default.CameraAlt, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Open Camera")
                    }
                    OutlinedButton(
                        onClick = {
                            showDropPhotoOptionsDialog = false
                            try {
                                dropGalleryLauncher.launch("image/*")
                            } catch (e: Exception) {
                                Log.e("ActiveDeliveryDetail", "Gallery launch error: ${e.message}", e)
                                viewModel.showMessage("Couldn't open gallery")
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(Icons.Default.PhotoLibrary, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Choose from Gallery")
                    }
                    if (BuildConfig.DEBUG) TextButton(
                        onClick = {
                            showDropPhotoOptionsDialog = false
                            uploadDemoDropPhoto()
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.BugReport, contentDescription = null)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Use Test Doorstep Photo (Emulator/Test)")
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showDropPhotoOptionsDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

/**
 * Door-step "scan to pay": shows a Razorpay UPI QR for the order's amount and polls the server
 * until the payment arrives (the razorpay-webhook confirms it automatically; this also re-checks
 * on demand). Once paid, the order becomes online-paid so no cash is collected.
 */
@Composable
private fun CollectByQrDialog(
    assignment: DeliveryAssignment,
    viewModel: MainViewModel,
    onDismiss: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val orderId = assignment.orderId.ifBlank { assignment.orders?.id.orEmpty() }
    val amount = assignment.totalAmount.toInt()

    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var qr by remember { mutableStateOf<OrderQrResponse?>(null) }
    var paid by remember { mutableStateOf(false) }
    var checking by remember { mutableStateOf(false) }
    var attempt by remember { mutableStateOf(0) }

    // Create (or re-fetch) the QR; re-runs when the user taps Retry.
    LaunchedEffect(orderId, attempt) {
        if (orderId.isBlank()) {
            error = "Order not found."
            loading = false
            return@LaunchedEffect
        }
        loading = true
        error = null
        viewModel.createOrderQr(orderId)
            .onSuccess {
                qr = it
                if (it.isPaid) paid = true
            }
            .onFailure { error = it.message ?: "Could not create the payment QR." }
        loading = false
    }

    // Auto-poll for the payment every few seconds while the QR is on screen.
    LaunchedEffect(qr?.qrId, paid) {
        if (qr?.qrId == null || paid) return@LaunchedEffect
        while (!paid) {
            delay(4000)
            val ok = viewModel.checkOrderQrPaid(orderId).getOrDefault(false)
            if (ok) {
                paid = true
                break
            }
        }
    }

    // Close shortly after the payment is confirmed.
    LaunchedEffect(paid) {
        if (paid) {
            delay(1800)
            onDismiss()
        }
    }

    AlertDialog(
        onDismissRequest = { if (!paid) onDismiss() },
        icon = {
            Icon(
                imageVector = if (paid) Icons.Default.CheckCircle else Icons.Default.QrCode2,
                contentDescription = null,
                tint = if (paid) EmeraldPrimary else TealSecondary,
                modifier = Modifier.size(36.dp)
            )
        },
        title = {
            Text(
                text = if (paid) "Payment received ✓" else "Scan to pay ₹$amount",
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                when {
                    paid -> Text(
                        "₹$amount online receive ho gaya. Ab cash collect karne ki zaroorat nahi — normal tarike se OTP + photo se delivery complete karo.",
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center
                    )

                    loading -> {
                        CircularProgressIndicator(color = TealSecondary)
                        Spacer(Modifier.height(12.dp))
                        Text("Generating QR...", style = MaterialTheme.typography.bodySmall)
                    }

                    error != null -> Text(
                        error ?: "Something went wrong.",
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center
                    )

                    qr?.imageUrl != null -> {
                        Text(
                            "Customer kisi bhi UPI app (GPay / PhonePe / Paytm) se ye QR scan kare.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                        Spacer(Modifier.height(12.dp))
                        AsyncImage(
                            model = qr?.imageUrl,
                            contentDescription = "UPI payment QR",
                            modifier = Modifier
                                .size(230.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(Color.White)
                                .padding(8.dp)
                        )
                        Spacer(Modifier.height(12.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = TealSecondary
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "Payment ka intezaar... (apne aap verify hoga)",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            when {
                paid -> Button(
                    onClick = onDismiss,
                    colors = ButtonDefaults.buttonColors(containerColor = EmeraldPrimary)
                ) { Text("Done") }

                error != null -> Button(
                    onClick = { attempt++ },
                    colors = ButtonDefaults.buttonColors(containerColor = TealSecondary)
                ) { Text("Retry") }

                qr?.imageUrl != null -> Button(
                    onClick = {
                        if (!checking) {
                            checking = true
                            scope.launch {
                                val ok = viewModel.checkOrderQrPaid(orderId).getOrDefault(false)
                                if (ok) paid = true
                                checking = false
                            }
                        }
                    },
                    enabled = !checking,
                    colors = ButtonDefaults.buttonColors(containerColor = TealSecondary)
                ) { Text(if (checking) "Checking..." else "Check now") }

                else -> {}
            }
        },
        dismissButton = {
            if (!paid) TextButton(onClick = onDismiss) { Text("Close") }
        }
    )
}

private fun createFallbackProofBytes(tag: String, orderNum: String): ByteArray {
    val bitmap = Bitmap.createBitmap(600, 600, Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bitmap)
    val paint = android.graphics.Paint().apply {
        color = android.graphics.Color.DKGRAY
        isAntiAlias = true
    }
    canvas.drawRect(0f, 0f, 600f, 600f, paint)
    val textPaint = android.graphics.Paint().apply {
        color = android.graphics.Color.WHITE
        textSize = 28f
        isAntiAlias = true
        textAlign = android.graphics.Paint.Align.CENTER
    }
    canvas.drawText("SNDMART DELIVERY PROOF", 300f, 200f, textPaint)
    canvas.drawText("Order: $orderNum", 300f, 260f, textPaint)
    canvas.drawText("Type: $tag", 300f, 320f, textPaint)
    canvas.drawText(
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()),
        300f,
        380f,
        textPaint
    )
    val stream = ByteArrayOutputStream()
    bitmap.compress(Bitmap.CompressFormat.JPEG, 85, stream)
    return stream.toByteArray()
}
