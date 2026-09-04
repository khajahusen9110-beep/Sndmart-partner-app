package com.example.ui.screens.onboarding

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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.MainViewModel
import com.example.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OnboardingScreen(
    viewModel: MainViewModel
) {
    val cities by viewModel.cities.collectAsState()
    var selectedCityId by remember { mutableStateOf("c1111111-1111-1111-1111-111111111111") }

    // Update selectedCityId when cities load if default not set
    LaunchedEffect(cities) {
        if (cities.isNotEmpty() && selectedCityId == "c1111111-1111-1111-1111-111111111111") {
            selectedCityId = cities.first().id
        }
    }

    var fullName by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf(viewModel.prefs.userPhone ?: "") }
    var selectedVehicleType by remember { mutableStateOf("bike") } // 'bike' | 'scooter' | 'ev' | 'cycle'
    var vehicleNumber by remember { mutableStateOf("") }
    var aadharUploaded by remember { mutableStateOf(false) }
    var licenceUploaded by remember { mutableStateOf(false) }
    var isSubmitting by remember { mutableStateOf(false) }

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
                text = "3. KYC Document Verification (Storage: delivery-partner-kyc-documents)",
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(10.dp))

            DocumentUploadCard(
                title = "Aadhaar Card Photo (Front)",
                subtitle = "Saved to delivery-partner-kyc-documents/\${user_id}/aadhar.jpg",
                isUploaded = aadharUploaded,
                onUploadClick = {
                    aadharUploaded = true
                    viewModel.showMessage("Aadhaar photo attached")
                }
            )

            Spacer(modifier = Modifier.height(12.dp))

            DocumentUploadCard(
                title = "Driving Licence (DL)",
                subtitle = "Saved to delivery-partner-kyc-documents/\${user_id}/license.jpg",
                isUploaded = licenceUploaded,
                onUploadClick = {
                    licenceUploaded = true
                    viewModel.showMessage("Driving Licence photo attached")
                }
            )

            Spacer(modifier = Modifier.height(32.dp))

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
                    if (selectedVehicleType != "cycle" && vehicleNumber.isBlank()) {
                        viewModel.showMessage("Please enter your vehicle registration number")
                        return@Button
                    }
                    if (!aadharUploaded || !licenceUploaded) {
                        viewModel.showMessage("Mandatory: Please attach both Aadhaar and Driving Licence photos")
                        return@Button
                    }
                    isSubmitting = true
                    val userId = viewModel.prefs.userId ?: "user_rider"
                    val aadharUrl = "${viewModel.prefs.customSupabaseUrl.removeSuffix("/")}/storage/v1/object/public/delivery-partner-kyc-documents/$userId/aadhar.jpg"
                    val dlUrl = "${viewModel.prefs.customSupabaseUrl.removeSuffix("/")}/storage/v1/object/public/delivery-partner-kyc-documents/$userId/license.jpg"
                    viewModel.submitOnboarding(
                        name = fullName,
                        phone = phone,
                        vehicleType = selectedVehicleType,
                        vehicleNumber = vehicleNumber,
                        cityId = selectedCityId,
                        aadharUrl = aadharUrl,
                        licenceUrl = dlUrl
                    )
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .testTag("submit_onboarding_button"),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = EmeraldPrimary)
            ) {
                if (isSubmitting) {
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
    subtitle: String,
    isUploaded: Boolean,
    onUploadClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onUploadClick() },
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
                    Icon(
                        imageVector = if (isUploaded) Icons.Default.Check else Icons.Default.CameraAlt,
                        contentDescription = null,
                        tint = if (isUploaded) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold)
                    )
                    Text(
                        text = if (isUploaded) "Document attached ✓ Ready" else subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (isUploaded) EmeraldOnContainer else MaterialTheme.colorScheme.outline
                    )
                }
            }

            OutlinedButton(
                onClick = onUploadClick,
                shape = RoundedCornerShape(10.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Text(if (isUploaded) "Change" else "Upload", fontSize = 12.sp)
            }
        }
    }
}
