package com.example.ui.screens.auth

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.MainViewModel
import com.example.ui.theme.*
import kotlinx.coroutines.delay

enum class AuthTab {
    LOGIN,
    SIGNUP,
    VERIFY_OTP
}

@Composable
fun AuthScreen(
    viewModel: MainViewModel
) {
    var activeTab by remember { mutableStateOf(AuthTab.LOGIN) }
    var mobileInput by remember {
        mutableStateOf(viewModel.prefs.userPhone?.filter { it.isDigit() }?.takeLast(10) ?: "")
    }
    var passwordInput by remember { mutableStateOf("") }
    var confirmPasswordInput by remember { mutableStateOf("") }
    var otpInput by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    var confirmPasswordVisible by remember { mutableStateOf(false) }

    var showConfigDialog by remember { mutableStateOf(false) }
    var showForgotPasswordDialog by remember { mutableStateOf(false) }

    // Resend OTP cooldown timer
    var resendCooldown by remember { mutableIntStateOf(0) }

    LaunchedEffect(resendCooldown) {
        if (resendCooldown > 0) {
            delay(1000)
            resendCooldown -= 1
        }
    }

    val isActionLoading by viewModel.isActionLoading.collectAsState()
    val scrollState = rememberScrollState()
    val focusManager = LocalFocusManager.current

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(scrollState)
                .padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Top Bar: Back button (if on OTP) & Settings
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (activeTab == AuthTab.VERIFY_OTP) {
                    IconButton(
                        onClick = { activeTab = AuthTab.SIGNUP },
                        modifier = Modifier.testTag("auth_back_button")
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                } else {
                    Spacer(modifier = Modifier.size(48.dp))
                }

                IconButton(
                    onClick = { showConfigDialog = true },
                    modifier = Modifier.testTag("backend_config_button")
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Settings,
                        contentDescription = "Backend Settings",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Hero Brand Badge
            Box(
                modifier = Modifier
                    .size(76.dp)
                    .clip(RoundedCornerShape(22.dp))
                    .background(
                        Brush.linearGradient(
                            listOf(EmeraldPrimary, TealSecondary)
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.TwoWheeler,
                    contentDescription = "Sndmart Partner",
                    tint = Color.White,
                    modifier = Modifier.size(42.dp)
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            Text(
                text = "Sndmart Partner",
                style = MaterialTheme.typography.headlineMedium.copy(
                    fontWeight = FontWeight.ExtraBold,
                    color = MaterialTheme.colorScheme.onBackground
                )
            )

            Text(
                text = "Fast Quick-Commerce Delivery Fleet",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(24.dp))

            // Tab Selector: Login vs Sign Up (when not verifying OTP)
            if (activeTab != AuthTab.VERIFY_OTP) {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(4.dp)
                    ) {
                        Surface(
                            onClick = {
                                focusManager.clearFocus()
                                activeTab = AuthTab.LOGIN
                            },
                            shape = RoundedCornerShape(10.dp),
                            color = if (activeTab == AuthTab.LOGIN) MaterialTheme.colorScheme.surface else Color.Transparent,
                            shadowElevation = if (activeTab == AuthTab.LOGIN) 2.dp else 0.dp,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("login_tab_button")
                        ) {
                            Text(
                                text = "Log In",
                                modifier = Modifier.padding(vertical = 10.dp),
                                textAlign = TextAlign.Center,
                                style = MaterialTheme.typography.titleSmall.copy(
                                    fontWeight = if (activeTab == AuthTab.LOGIN) FontWeight.Bold else FontWeight.Medium,
                                    color = if (activeTab == AuthTab.LOGIN) EmeraldPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            )
                        }

                        Surface(
                            onClick = {
                                focusManager.clearFocus()
                                activeTab = AuthTab.SIGNUP
                            },
                            shape = RoundedCornerShape(10.dp),
                            color = if (activeTab == AuthTab.SIGNUP) MaterialTheme.colorScheme.surface else Color.Transparent,
                            shadowElevation = if (activeTab == AuthTab.SIGNUP) 2.dp else 0.dp,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("signup_tab_button")
                        ) {
                            Text(
                                text = "Sign Up",
                                modifier = Modifier.padding(vertical = 10.dp),
                                textAlign = TextAlign.Center,
                                style = MaterialTheme.typography.titleSmall.copy(
                                    fontWeight = if (activeTab == AuthTab.SIGNUP) FontWeight.Bold else FontWeight.Medium,
                                    color = if (activeTab == AuthTab.SIGNUP) EmeraldPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))
            }

            // Auth Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp)
                ) {
                    when (activeTab) {
                        AuthTab.LOGIN -> {
                            Text(
                                text = "Welcome Back",
                                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
                            )
                            Text(
                                text = "Enter your registered mobile number and password",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            Spacer(modifier = Modifier.height(18.dp))

                            OutlinedTextField(
                                value = mobileInput,
                                onValueChange = { input ->
                                    val digits = input.filter { it.isDigit() }
                                    mobileInput = if (digits.startsWith("91") && digits.length > 10) {
                                        digits.drop(2).take(10)
                                    } else {
                                        digits.take(10)
                                    }
                                },
                                label = { Text("Mobile Number") },
                                placeholder = { Text("9876543210") },
                                prefix = {
                                    Text("+91 ", fontWeight = FontWeight.Bold, color = EmeraldPrimary)
                                },
                                leadingIcon = {
                                    Icon(Icons.Default.Phone, contentDescription = null, tint = EmeraldPrimary)
                                },
                                keyboardOptions = KeyboardOptions(
                                    keyboardType = KeyboardType.Phone,
                                    imeAction = ImeAction.Next
                                ),
                                keyboardActions = KeyboardActions(
                                    onNext = { focusManager.moveFocus(FocusDirection.Down) }
                                ),
                                singleLine = true,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("login_phone_input"),
                                shape = RoundedCornerShape(12.dp)
                            )

                            Spacer(modifier = Modifier.height(14.dp))

                            OutlinedTextField(
                                value = passwordInput,
                                onValueChange = { passwordInput = it },
                                label = { Text("Password") },
                                leadingIcon = {
                                    Icon(Icons.Default.Lock, contentDescription = null)
                                },
                                trailingIcon = {
                                    IconButton(onClick = { passwordVisible = !passwordVisible }) {
                                        Icon(
                                            imageVector = if (passwordVisible) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                                            contentDescription = if (passwordVisible) "Hide password" else "Show password"
                                        )
                                    }
                                },
                                visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                                keyboardOptions = KeyboardOptions(
                                    keyboardType = KeyboardType.Password,
                                    imeAction = ImeAction.Done
                                ),
                                keyboardActions = KeyboardActions(
                                    onDone = {
                                        focusManager.clearFocus()
                                        if (mobileInput.length == 10 && passwordInput.isNotBlank()) {
                                            viewModel.signInWithPhone(mobileInput, passwordInput)
                                        }
                                    }
                                ),
                                singleLine = true,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("login_password_input"),
                                shape = RoundedCornerShape(12.dp)
                            )

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End
                            ) {
                                TextButton(
                                    onClick = { showForgotPasswordDialog = true },
                                    modifier = Modifier.testTag("forgot_password_button")
                                ) {
                                    Text(
                                        text = "Forgot Password?",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = EmeraldPrimary
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(8.dp))

                            Button(
                                onClick = {
                                    focusManager.clearFocus()
                                    if (mobileInput.length != 10) {
                                        viewModel.showMessage("Please enter a valid 10-digit mobile number")
                                        return@Button
                                    }
                                    if (passwordInput.isBlank()) {
                                        viewModel.showMessage("Please enter your password")
                                        return@Button
                                    }
                                    viewModel.signInWithPhone(mobileInput, passwordInput)
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(50.dp)
                                    .testTag("login_submit_button"),
                                shape = RoundedCornerShape(12.dp),
                                enabled = !isActionLoading,
                                colors = ButtonDefaults.buttonColors(containerColor = EmeraldPrimary)
                            ) {
                                if (isActionLoading) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(22.dp),
                                        color = Color.White,
                                        strokeWidth = 2.5.dp
                                    )
                                } else {
                                    Icon(Icons.Default.Login, contentDescription = null)
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("LOG IN", fontWeight = FontWeight.Bold)
                                }
                            }
                        }

                        AuthTab.SIGNUP -> {
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = EmeraldPrimary.copy(alpha = 0.12f)
                            ) {
                                Text(
                                    text = "STEP 2: MOBILE & OTP VERIFICATION",
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontWeight = FontWeight.Bold,
                                        color = EmeraldPrimary
                                    )
                                )
                            }

                            Spacer(modifier = Modifier.height(8.dp))

                            Text(
                                text = "Mobile & OTP Verification",
                                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
                            )
                            Text(
                                text = "Enter your 10-digit mobile number and set a password. We'll send an SMS OTP before KYC document upload.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            Spacer(modifier = Modifier.height(18.dp))

                            OutlinedTextField(
                                value = mobileInput,
                                onValueChange = { input ->
                                    val digits = input.filter { it.isDigit() }
                                    mobileInput = if (digits.startsWith("91") && digits.length > 10) {
                                        digits.drop(2).take(10)
                                    } else {
                                        digits.take(10)
                                    }
                                },
                                label = { Text("Mobile Number (10 digits)") },
                                placeholder = { Text("9876543210") },
                                prefix = {
                                    Text("+91 ", fontWeight = FontWeight.Bold, color = EmeraldPrimary)
                                },
                                leadingIcon = {
                                    Icon(Icons.Default.Phone, contentDescription = null, tint = EmeraldPrimary)
                                },
                                keyboardOptions = KeyboardOptions(
                                    keyboardType = KeyboardType.Phone,
                                    imeAction = ImeAction.Next
                                ),
                                keyboardActions = KeyboardActions(
                                    onNext = { focusManager.moveFocus(FocusDirection.Down) }
                                ),
                                singleLine = true,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("signup_phone_input"),
                                shape = RoundedCornerShape(12.dp)
                            )

                            Spacer(modifier = Modifier.height(12.dp))

                            OutlinedTextField(
                                value = passwordInput,
                                onValueChange = { passwordInput = it },
                                label = { Text("Password (min 6 characters)") },
                                leadingIcon = {
                                    Icon(Icons.Default.Lock, contentDescription = null)
                                },
                                trailingIcon = {
                                    IconButton(onClick = { passwordVisible = !passwordVisible }) {
                                        Icon(
                                            imageVector = if (passwordVisible) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                                            contentDescription = if (passwordVisible) "Hide" else "Show"
                                        )
                                    }
                                },
                                visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                                keyboardOptions = KeyboardOptions(
                                    keyboardType = KeyboardType.Password,
                                    imeAction = ImeAction.Next
                                ),
                                keyboardActions = KeyboardActions(
                                    onNext = { focusManager.moveFocus(FocusDirection.Down) }
                                ),
                                singleLine = true,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("signup_password_input"),
                                shape = RoundedCornerShape(12.dp)
                            )

                            Spacer(modifier = Modifier.height(12.dp))

                            OutlinedTextField(
                                value = confirmPasswordInput,
                                onValueChange = { confirmPasswordInput = it },
                                label = { Text("Confirm Password") },
                                leadingIcon = {
                                    Icon(Icons.Default.LockClock, contentDescription = null)
                                },
                                trailingIcon = {
                                    IconButton(onClick = { confirmPasswordVisible = !confirmPasswordVisible }) {
                                        Icon(
                                            imageVector = if (confirmPasswordVisible) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                                            contentDescription = if (confirmPasswordVisible) "Hide" else "Show"
                                        )
                                    }
                                },
                                visualTransformation = if (confirmPasswordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                                keyboardOptions = KeyboardOptions(
                                    keyboardType = KeyboardType.Password,
                                    imeAction = ImeAction.Done
                                ),
                                keyboardActions = KeyboardActions(
                                    onDone = { focusManager.clearFocus() }
                                ),
                                singleLine = true,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("signup_confirm_password_input"),
                                shape = RoundedCornerShape(12.dp)
                            )

                            Spacer(modifier = Modifier.height(12.dp))

                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        Icons.Default.Security,
                                        contentDescription = null,
                                        tint = EmeraldPrimary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Text(
                                        text = "SMS OTP code will be sent to +91 ${mobileInput.ifBlank { "..." }}. KYC documents upload unlocks after verification.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(18.dp))

                            Button(
                                onClick = {
                                    focusManager.clearFocus()
                                    if (mobileInput.length != 10) {
                                        viewModel.showMessage("Please enter a valid 10-digit mobile number")
                                        return@Button
                                    }
                                    if (passwordInput.length < 6) {
                                        viewModel.showMessage("Password must be at least 6 characters long")
                                        return@Button
                                    }
                                    if (passwordInput != confirmPasswordInput) {
                                        viewModel.showMessage("Passwords do not match")
                                        return@Button
                                    }
                                    viewModel.signUpWithPhone(mobileInput, passwordInput) { success ->
                                        if (success) {
                                            activeTab = AuthTab.VERIFY_OTP
                                            resendCooldown = 45
                                        }
                                    }
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(50.dp)
                                    .testTag("signup_submit_button"),
                                shape = RoundedCornerShape(12.dp),
                                enabled = !isActionLoading,
                                colors = ButtonDefaults.buttonColors(containerColor = EmeraldPrimary)
                            ) {
                                if (isActionLoading) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(22.dp),
                                        color = Color.White,
                                        strokeWidth = 2.5.dp
                                    )
                                } else {
                                    Icon(Icons.Default.Sms, contentDescription = null)
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("SEND SMS OTP & VERIFY", fontWeight = FontWeight.Bold)
                                }
                            }
                        }

                        AuthTab.VERIFY_OTP -> {
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = EmeraldPrimary.copy(alpha = 0.12f)
                            ) {
                                Text(
                                    text = "STEP 2: OTP VERIFICATION",
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontWeight = FontWeight.Bold,
                                        color = EmeraldPrimary
                                    )
                                )
                            }

                            Spacer(modifier = Modifier.height(8.dp))

                            Text(
                                text = "Verify Mobile OTP",
                                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
                            )
                            Text(
                                text = "Enter the 6-digit SMS verification code sent to:",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            Spacer(modifier = Modifier.height(6.dp))

                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = EmeraldPrimary.copy(alpha = 0.1f),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        Icons.Default.PhoneAndroid,
                                        contentDescription = null,
                                        tint = EmeraldPrimary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = "+91 $mobileInput",
                                        style = MaterialTheme.typography.titleMedium.copy(
                                            fontWeight = FontWeight.Bold,
                                            color = EmeraldPrimary
                                        )
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(18.dp))

                            OutlinedTextField(
                                value = otpInput,
                                onValueChange = { otpInput = it.filter { ch -> ch.isDigit() }.take(6) },
                                label = { Text("6-Digit SMS Code") },
                                placeholder = { Text("123456") },
                                leadingIcon = {
                                    Icon(Icons.Default.Key, contentDescription = null, tint = EmeraldPrimary)
                                },
                                keyboardOptions = KeyboardOptions(
                                    keyboardType = KeyboardType.NumberPassword,
                                    imeAction = ImeAction.Done
                                ),
                                keyboardActions = KeyboardActions(
                                    onDone = {
                                        if (otpInput.isNotBlank()) {
                                            viewModel.verifyPhoneOtp(mobileInput, otpInput)
                                        }
                                    }
                                ),
                                singleLine = true,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("phone_otp_input_field"),
                                shape = RoundedCornerShape(12.dp)
                            )

                            Spacer(modifier = Modifier.height(14.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                TextButton(
                                    onClick = { activeTab = AuthTab.SIGNUP }
                                ) {
                                    Text("Change Number", style = MaterialTheme.typography.labelSmall)
                                }

                                if (resendCooldown > 0) {
                                    Text(
                                        text = "Resend in ${resendCooldown}s",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.outline
                                    )
                                } else {
                                    TextButton(
                                        onClick = {
                                            viewModel.resendPhoneOtp(mobileInput)
                                            resendCooldown = 45
                                        },
                                        modifier = Modifier.testTag("resend_phone_otp_button")
                                    ) {
                                        Text(
                                            text = "Resend SMS OTP",
                                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                            color = EmeraldPrimary
                                        )
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(16.dp))

                            Button(
                                onClick = {
                                    focusManager.clearFocus()
                                    if (otpInput.isBlank()) {
                                        viewModel.showMessage("Please enter the 6-digit SMS OTP code")
                                        return@Button
                                    }
                                    viewModel.verifyPhoneOtp(mobileInput, otpInput)
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(50.dp)
                                    .testTag("verify_phone_otp_button"),
                                shape = RoundedCornerShape(12.dp),
                                enabled = !isActionLoading && otpInput.isNotEmpty(),
                                colors = ButtonDefaults.buttonColors(containerColor = EmeraldPrimary)
                            ) {
                                if (isActionLoading) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(22.dp),
                                        color = Color.White,
                                        strokeWidth = 2.5.dp
                                    )
                                } else {
                                    Icon(Icons.Default.VerifiedUser, contentDescription = null)
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("VERIFY OTP & PROCEED TO KYC", fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(26.dp))


        }
    }

    // Forgot Password Dialog
    if (showForgotPasswordDialog) {
        var forgotMobile by remember { mutableStateOf(mobileInput) }
        AlertDialog(
            onDismissRequest = { showForgotPasswordDialog = false },
            title = { Text("Reset Password") },
            text = {
                Column {
                    Text(
                        text = "Enter your registered 10-digit mobile number to receive an SMS verification code to reset your password.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = forgotMobile,
                        onValueChange = { input ->
                            val digits = input.filter { it.isDigit() }
                            forgotMobile = if (digits.startsWith("91") && digits.length > 10) {
                                digits.drop(2).take(10)
                            } else {
                                digits.take(10)
                            }
                        },
                        label = { Text("Mobile Number (10 digits)") },
                        prefix = { Text("+91 ", fontWeight = FontWeight.Bold, color = EmeraldPrimary) },
                        leadingIcon = { Icon(Icons.Default.Phone, contentDescription = null, tint = EmeraldPrimary) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (forgotMobile.length == 10) {
                            viewModel.requestPhoneOtp(forgotMobile)
                            showForgotPasswordDialog = false
                        } else {
                            viewModel.showMessage("Please enter a valid 10-digit mobile number")
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = EmeraldPrimary)
                ) {
                    Text("Send SMS Code")
                }
            },
            dismissButton = {
                TextButton(onClick = { showForgotPasswordDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Supabase Config Dialog
    if (showConfigDialog) {
        SupabaseConfigDialog(
            currentUrl = viewModel.prefs.customSupabaseUrl,
            currentKey = viewModel.prefs.customSupabaseKey,
            onDismiss = { showConfigDialog = false },
            onSave = { url, key ->
                viewModel.updateSupabaseCredentials(url, key)
                showConfigDialog = false
            }
        )
    }
}


@Composable
fun SupabaseConfigDialog(
    currentUrl: String,
    currentKey: String,
    onDismiss: () -> Unit,
    onSave: (String, String) -> Unit
) {
    var url by remember { mutableStateOf(currentUrl) }
    var key by remember { mutableStateOf(currentKey) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Supabase Backend Settings") },
        text = {
            Column {
                Text(
                    text = "Connected Project: Sndmart (ref: zennmoughfhennwgecgo)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("Supabase URL") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(10.dp))
                OutlinedTextField(
                    value = key,
                    onValueChange = { key = it },
                    label = { Text("Anon API Key") },
                    maxLines = 3,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(url, key) },
                colors = ButtonDefaults.buttonColors(containerColor = EmeraldPrimary)
            ) {
                Text("Save & Apply")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
