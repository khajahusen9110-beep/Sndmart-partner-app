package com.example.ui.screens.onboarding

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.BuildConfig
import com.example.ui.MainViewModel
import com.example.ui.theme.*
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OnboardingScreen(
    viewModel: MainViewModel
) {
    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current

    val cities by viewModel.cities.collectAsState()
    var selectedCityId by remember { mutableStateOf("") }

    // Fetch fresh authenticated user UUID from session and reload active cities
    var authUserId by remember { mutableStateOf<String?>(viewModel.prefs.userId) }
    LaunchedEffect(Unit) {
        viewModel.loadCities(forceRefresh = true)
        val realId = viewModel.getFreshAuthUserId()
        if (!realId.isNullOrBlank()) {
            authUserId = realId
        }
    }

    // Update selectedCityId when cities load
    LaunchedEffect(cities) {
        if (cities.isNotEmpty()) {
            if (selectedCityId.isBlank() || !cities.any { it.id == selectedCityId }) {
                selectedCityId = cities.first().id
            }
        }
    }

    var fullName by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf(viewModel.prefs.userPhone ?: "") }
    LaunchedEffect(viewModel.prefs.userPhone) {
        val p = viewModel.prefs.userPhone
        if (!p.isNullOrBlank()) {
            phone = p
        }
    }
    var selectedVehicleType by remember { mutableStateOf("bike") } // 'bike' | 'scooter' | 'ev' | 'cycle'
    var vehicleNumber by remember { mutableStateOf("") }

    // Saved relative storage paths under auth.uid() folder
    var aadharPath by remember { mutableStateOf<String?>(null) }
    var licencePath by remember { mutableStateOf<String?>(null) }
    var isUploadingAadhar by remember { mutableStateOf(false) }
    var isUploadingLicence by remember { mutableStateOf(false) }

    var showAadharDialog by remember { mutableStateOf(false) }
    var showLicenceDialog by remember { mutableStateOf(false) }

    val isActionLoading by viewModel.isActionLoading.collectAsState()

    // Helper to generate sample KYC document bytes for test / verification
    fun generateSampleDocBytes(docName: String): ByteArray {
        val bitmap = Bitmap.createBitmap(720, 460, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(android.graphics.Color.WHITE)
        val paint = Paint().apply {
            isAntiAlias = true
            color = android.graphics.Color.parseColor("#064E3B")
            textSize = 28f
        }
        canvas.drawText("SNDMART PARTNER KYC VERIFICATION", 36f, 70f, paint)
        paint.color = android.graphics.Color.parseColor("#059669")
        paint.textSize = 24f
        canvas.drawText("DOCUMENT: $docName", 36f, 130f, paint)
        paint.color = android.graphics.Color.DKGRAY
        paint.textSize = 18f
        canvas.drawText("User ID: ${authUserId ?: "auth.uid()"}", 36f, 190f, paint)
        canvas.drawText("Timestamp: ${Date()}", 36f, 230f, paint)
        paint.color = android.graphics.Color.GRAY
        paint.textSize = 16f
        canvas.drawText("Storage: delivery-partner-kyc-documents", 36f, 280f, paint)
        val output = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 90, output)
        return output.toByteArray()
    }

    // Android Photo Picker for Aadhaar Card
    val aadharPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            coroutineScope.launch {
                isUploadingAadhar = true
                try {
                    val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    if (bytes != null && bytes.isNotEmpty()) {
                        val result = viewModel.uploadKycDocument("aadhar", bytes)
                        result.onSuccess { path ->
                            aadharPath = path
                            viewModel.showMessage("Aadhaar Card uploaded successfully!")
                        }.onFailure { err ->
                            viewModel.showMessage("KYC upload error: ${err.message}")
                        }
                    } else {
                        viewModel.showMessage("Failed to read image file from device")
                    }
                } catch (e: Exception) {
                    viewModel.showMessage("KYC upload error: ${e.message}")
                } finally {
                    isUploadingAadhar = false
                }
            }
        }
    }

    // Android Photo Picker for Driving Licence
    val licencePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            coroutineScope.launch {
                isUploadingLicence = true
                try {
                    val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    if (bytes != null && bytes.isNotEmpty()) {
                        val result = viewModel.uploadKycDocument("license", bytes)
                        result.onSuccess { path ->
                            licencePath = path
                            viewModel.showMessage("Driving Licence uploaded successfully!")
                        }.onFailure { err ->
                            viewModel.showMessage("KYC upload error: ${err.message}")
                        }
                    } else {
                        viewModel.showMessage("Failed to read image file from device")
                    }
                } catch (e: Exception) {
                    viewModel.showMessage("KYC upload error: ${e.message}")
                } finally {
                    isUploadingLicence = false
                }
            }
        }
    }

    if (showAadharDialog) {
        AlertDialog(
            onDismissRequest = { showAadharDialog = false },
            title = { Text("Upload Aadhaar Card Photo") },
            text = {
                Column {
                    Text("Select how you would like to provide your Aadhaar Card photo:")
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = "Destination: delivery-partner-kyc-documents/${authUserId ?: "auth.uid()"}/",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showAadharDialog = false
                        aadharPicker.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        )
                    }
                ) {
                    Icon(Icons.Default.PhotoLibrary, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Select from Photos")
                }
            },
            // Generated sample documents are only for debug builds / emulators.
            dismissButton = {
                if (BuildConfig.DEBUG) OutlinedButton(
                    onClick = {
                        showAadharDialog = false
                        coroutineScope.launch {
                            isUploadingAadhar = true
                            try {
                                val bytes = generateSampleDocBytes("Aadhaar Card Photo")
                                val result = viewModel.uploadKycDocument("aadhar", bytes)
                                result.onSuccess { path ->
                                    aadharPath = path
                                    viewModel.showMessage("Aadhaar Card uploaded to storage!")
                                }.onFailure { err ->
                                    viewModel.showMessage("KYC upload error: ${err.message}")
                                }
                            } catch (e: Exception) {
                                viewModel.showMessage("KYC upload error: ${e.message}")
                            } finally {
                                isUploadingAadhar = false
                            }
                        }
                    }
                ) {
                    Text("Use Sample Photo")
                }
            }
        )
    }

    if (showLicenceDialog) {
        AlertDialog(
            onDismissRequest = { showLicenceDialog = false },
            title = { Text("Upload Driving Licence") },
            text = {
                Column {
                    Text("Select how you would like to provide your Driving Licence photo:")
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = "Destination: delivery-partner-kyc-documents/${authUserId ?: "auth.uid()"}/",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showLicenceDialog = false
                        licencePicker.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        )
                    }
                ) {
                    Icon(Icons.Default.PhotoLibrary, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Select from Photos")
                }
            },
            dismissButton = {
                if (BuildConfig.DEBUG) OutlinedButton(
                    onClick = {
                        showLicenceDialog = false
                        coroutineScope.launch {
                            isUploadingLicence = true
                            try {
                                val bytes = generateSampleDocBytes("Driving Licence DL")
                                val result = viewModel.uploadKycDocument("license", bytes)
                                result.onSuccess { path ->
                                    licencePath = path
                                    viewModel.showMessage("Driving Licence uploaded to storage!")
                                }.onFailure { err ->
                                    viewModel.showMessage("KYC upload error: ${err.message}")
                                }
                            } catch (e: Exception) {
                                viewModel.showMessage("KYC upload error: ${e.message}")
                            } finally {
                                isUploadingLicence = false
                            }
                        }
                    }
                ) {
                    Text("Use Sample Photo")
                }
            }
        )
    }

    val vehicleOptions = listOf(
        Pair("bike", "Motorcycle (Bike)"),
        Pair("scooter", "Scooter"),
        Pair("ev", "Electric Vehicle (EV)"),
        Pair("cycle", "Bicycle")
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Partner Onboarding",
                        fontWeight = FontWeight.Bold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = { viewModel.signOut() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
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
                .padding(20.dp)
        ) {
            // Header Info Banner
            Surface(
                color = EmeraldContainer,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.AssignmentInd,
                        contentDescription = null,
                        tint = EmeraldOnContainer,
                        modifier = Modifier.size(32.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = "Partner KYC & Onboarding",
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.Bold,
                                color = EmeraldOnContainer
                            )
                        )
                        Text(
                            text = "Complete your verification to receive high-earning quick-commerce delivery orders.",
                            style = MaterialTheme.typography.bodySmall,
                            color = EmeraldOnContainer.copy(alpha = 0.85f)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Personal Info Section
            Text(
                text = "1. Personal Information",
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(10.dp))

            OutlinedTextField(
                value = fullName,
                onValueChange = { fullName = it },
                label = { Text("Full Name (as per Govt ID)") },
                placeholder = { Text("e.g. Ramesh Kumar") },
                leadingIcon = { Icon(Icons.Default.Person, contentDescription = null) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("onboarding_name_input"),
                shape = RoundedCornerShape(12.dp)
            )

            Spacer(modifier = Modifier.height(12.dp))

            OutlinedTextField(
                value = phone,
                onValueChange = { phone = it },
                label = { Text("Contact Phone Number") },
                placeholder = { Text("+91 9876543210") },
                leadingIcon = { Icon(Icons.Default.Phone, contentDescription = null) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("onboarding_phone_input"),
                shape = RoundedCornerShape(12.dp)
            )

            Spacer(modifier = Modifier.height(14.dp))

            // City Selection (From cities table)
            Text(
                text = "Operating Delivery City (from Sndmart network):",
                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(6.dp))

            if (cities.isEmpty()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(vertical = 4.dp)
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = EmeraldPrimary)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Loading active operating cities...",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    cities.forEach { city ->
                        val isCitySelected = selectedCityId == city.id
                        FilterChip(
                            selected = isCitySelected,
                            onClick = { selectedCityId = city.id },
                            label = { Text(city.name) },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Default.LocationCity,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = EmeraldPrimary,
                                selectedLabelColor = Color.White
                            )
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Vehicle Section
            Text(
                text = "2. Vehicle Details",
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(10.dp))

            Text(
                text = "Select Vehicle Type ('bike' | 'scooter' | 'ev' | 'cycle'):",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(6.dp))

            // Vehicle selection chips
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                vehicleOptions.forEach { (code, label) ->
                    val isSelected = selectedVehicleType == code
                    FilterChip(
                        selected = isSelected,
                        onClick = { selectedVehicleType = code },
                        label = { Text(label) },
                        leadingIcon = {
                            Icon(
                                imageVector = if (code == "cycle") Icons.Default.PedalBike else Icons.Default.TwoWheeler,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = EmeraldPrimary,
                            selectedLabelColor = Color.White
                        )
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            if (selectedVehicleType != "cycle") {
                OutlinedTextField(
                    value = vehicleNumber,
                    onValueChange = { vehicleNumber = it.uppercase() },
                    label = { Text("Vehicle Registration No. (e.g. KA-01-AB-1234)") },
                    leadingIcon = { Icon(Icons.Default.DirectionsCar, contentDescription = null) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("onboarding_vehicle_no_input"),
                    shape = RoundedCornerShape(12.dp)
                )
            } else {
                Surface(
                    color = EmeraldContainer.copy(alpha = 0.4f),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "Bicycle delivery selected — registration plate number is not required.",
                        style = MaterialTheme.typography.bodySmall,
                        color = EmeraldOnContainer,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // KYC Documents Upload Section
            Text(
                text = "3. KYC Document Verification",
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(10.dp))

            DocumentUploadCard(
                title = "Aadhaar Card Photo (Front)",
                targetBucket = "delivery-partner-kyc-documents",
                authUserId = authUserId,
                savedPath = aadharPath,
                isUploading = isUploadingAadhar,
                onUploadClick = { showAadharDialog = true }
            )

            Spacer(modifier = Modifier.height(12.dp))

            if (selectedVehicleType == "cycle") {
                Card(
                    colors = CardDefaults.cardColors(containerColor = EmeraldContainer),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.CheckCircle, contentDescription = null, tint = EmeraldPrimary)
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = "Driving Licence exempt: Not required for bicycle delivery partners.",
                            style = MaterialTheme.typography.bodySmall,
                            color = EmeraldOnContainer
                        )
                    }
                }
            } else {
                DocumentUploadCard(
                    title = "Driving Licence (DL)",
                    targetBucket = "delivery-partner-kyc-documents",
                    authUserId = authUserId,
                    savedPath = licencePath,
                    isUploading = isUploadingLicence,
                    onUploadClick = { showLicenceDialog = true }
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            Button(
                onClick = {
                    if (fullName.isBlank()) {
                        viewModel.showMessage("Please enter your full name")
                        return@Button
                    }
                    if (phone.isBlank()) {
                        viewModel.showMessage("Please enter your phone number")
                        return@Button
                    }
                    val effectiveCityId = if (selectedCityId.isNotBlank() && selectedCityId != "c1111111-1111-1111-1111-111111111111") {
                        selectedCityId
                    } else {
                        cities.firstOrNull()?.id ?: ""
                    }
                    if (effectiveCityId.isBlank()) {
                        viewModel.showMessage("Please wait for operating delivery cities to load, or select a city")
                        return@Button
                    }
                    if (selectedVehicleType != "cycle" && vehicleNumber.isBlank()) {
                        viewModel.showMessage("Please enter your vehicle registration number")
                        return@Button
                    }
                    if (aadharPath.isNullOrBlank()) {
                        viewModel.showMessage("Please upload your Aadhaar Card photo")
                        return@Button
                    }
                    if (selectedVehicleType != "cycle" && licencePath.isNullOrBlank()) {
                        viewModel.showMessage("Please upload your Driving Licence")
                        return@Button
                    }

                    val finalVehicleNumber = if (selectedVehicleType == "cycle" && vehicleNumber.isBlank()) {
                        "CYCLE"
                    } else {
                        vehicleNumber.trim().uppercase()
                    }

                    val finalLicence = if (selectedVehicleType == "cycle") {
                        licencePath ?: "cycle_exempt"
                    } else {
                        licencePath!!
                    }

                    viewModel.submitOnboarding(
                        name = fullName.trim(),
                        phone = phone.trim(),
                        vehicleType = selectedVehicleType,
                        vehicleNumber = finalVehicleNumber,
                        cityId = effectiveCityId,
                        aadharUrl = aadharPath!!,
                        licenceUrl = finalLicence
                    )
                },
                enabled = !isActionLoading,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .testTag("submit_onboarding_button"),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = EmeraldPrimary)
            ) {
                if (isActionLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(22.dp),
                        color = Color.White,
                        strokeWidth = 2.5.dp
                    )
                } else {
                    Icon(Icons.Default.Send, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("SUBMIT KYC APPLICATION", fontWeight = FontWeight.Bold)
                }
            }

            Spacer(modifier = Modifier.height(20.dp))
        }
    }
}

@Composable
fun DocumentUploadCard(
    title: String,
    targetBucket: String = "delivery-partner-kyc-documents",
    authUserId: String?,
    savedPath: String?,
    isUploading: Boolean,
    onUploadClick: () -> Unit
) {
    val isUploaded = !savedPath.isNullOrBlank()
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !isUploading) { onUploadClick() },
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isUploaded) EmeraldContainer.copy(alpha = 0.5f) else MaterialTheme.colorScheme.surface
        ),
        border = if (isUploaded) ButtonDefaults.outlinedButtonBorder.copy(
            brush = androidx.compose.ui.graphics.SolidColor(EmeraldPrimary)
        ) else null
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (isUploaded) EmeraldPrimary else MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center
                ) {
                    if (isUploading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            color = MaterialTheme.colorScheme.primary,
                            strokeWidth = 2.dp
                        )
                    } else {
                        Icon(
                            imageVector = if (isUploaded) Icons.Default.Check else Icons.Default.CameraAlt,
                            contentDescription = null,
                            tint = if (isUploaded) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold)
                    )
                    if (isUploading) {
                        Text(
                            text = "Uploading to $targetBucket...",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    } else if (isUploaded) {
                        Text(
                            text = "Saved to: $targetBucket/$savedPath ✓",
                            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                            color = EmeraldOnContainer
                        )
                    } else {
                        val folderName = authUserId ?: "auth.uid()"
                        Text(
                            text = "Uploads to: $targetBucket/$folderName/",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                }
            }

            OutlinedButton(
                onClick = onUploadClick,
                enabled = !isUploading,
                shape = RoundedCornerShape(10.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Text(
                    text = if (isUploading) "..." else if (isUploaded) "Replace" else "Upload",
                    fontSize = 12.sp
                )
            }
        }
    }
}
