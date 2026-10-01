package com.example

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.data.model.DeliveryAssignment
import com.example.ui.AuthUiState
import com.example.ui.MainViewModel
import com.example.ui.components.DeliveryTopAppBar
import com.example.ui.components.MaintenanceBanner
import com.example.ui.screens.auth.AuthScreen
import com.example.ui.screens.dashboard.DashboardScreen
import com.example.ui.screens.delivery.ActiveDeliveryDetailScreen
import com.example.ui.screens.delivery.DeliveriesScreen
import com.example.ui.screens.earnings.EarningsScreen
import com.example.ui.screens.notifications.NotificationsBottomSheet
import com.example.ui.screens.onboarding.OnboardingScreen
import com.example.ui.screens.onboarding.ReviewStatusScreen
import com.example.ui.screens.profile.ProfileScreen
import com.example.ui.screens.update.BlockingUpdateScreen
import com.example.ui.theme.EmeraldPrimary
import com.example.ui.theme.MyApplicationTheme

enum class MainNavigationTab(val title: String, val icon: ImageVector) {
    DASHBOARD("Dashboard", Icons.Default.Dashboard),
    DELIVERIES("Orders", Icons.Default.TwoWheeler),
    EARNINGS("Earnings", Icons.Default.AccountBalanceWallet),
    PROFILE("Profile", Icons.Default.Person)
}

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleNavigateIntent(intent)

        setContent {
            MyApplicationTheme {
                MainAppRoot(viewModel = viewModel)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleNavigateIntent(intent)
    }

    private fun handleNavigateIntent(intent: Intent?) {
        val assignmentId = intent?.getStringExtra("assignment_id")
            ?: intent?.getStringExtra("assignmentId")
            ?: intent?.getStringExtra("EXTRA_NAVIGATE_ASSIGNMENT_ID")
            ?: intent?.getStringExtra("order_id")
            ?: intent?.getStringExtra("orderId")
        if (!assignmentId.isNullOrBlank()) {
            viewModel.handleIncomingNotificationClick(assignmentId)
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.onAppResume()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainAppRoot(viewModel: MainViewModel) {
    val authState by viewModel.authUiState.collectAsState()
    val userMessage by viewModel.userMessage.collectAsState()
    val selectedAssignment by viewModel.selectedAssignmentForDetail.collectAsState()
    val notifications by viewModel.notifications.collectAsState()
    val dataFetchError by viewModel.dataFetchError.collectAsState()
    val isDataLoading by viewModel.isDataLoading.collectAsState()
    val isUpdateRequired by viewModel.isUpdateRequired.collectAsState()
    val blockingUpdateInfo by viewModel.blockingUpdateInfo.collectAsState()
    val maintenanceModeActive by viewModel.maintenanceModeActive.collectAsState()
    val maintenanceMessage by viewModel.maintenanceMessage.collectAsState()

    val snackbarHostState = remember { SnackbarHostState() }
    var showNotificationsSheet by remember { mutableStateOf(false) }
    var currentTab by remember { mutableStateOf(MainNavigationTab.DASHBOARD) }

    val context = LocalContext.current
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            viewModel.fetchAndRegisterFcmToken()
        }
    }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val hasPermission = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!hasPermission) {
                permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                viewModel.fetchAndRegisterFcmToken()
            }
        } else {
            viewModel.fetchAndRegisterFcmToken()
        }
    }

    LaunchedEffect(userMessage) {
        userMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearUserMessage()
        }
    }

    if (isUpdateRequired) {
        BlockingUpdateScreen(
            updateInfo = blockingUpdateInfo,
            onCheckAgain = { viewModel.runStartupChecks() }
        )
        return
    }

    Box(modifier = Modifier.fillMaxSize()) {
        when (val state = authState) {
            is AuthUiState.Loading -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = androidx.compose.ui.Alignment.Center
                ) {
                    CircularProgressIndicator(color = EmeraldPrimary)
                }
            }

            is AuthUiState.Unauthenticated -> {
                AuthScreen(viewModel = viewModel)
            }

            is AuthUiState.OnboardingRequired -> {
                OnboardingScreen(viewModel = viewModel)
            }

            is AuthUiState.UnderReview -> {
                ReviewStatusScreen(
                    partner = state.partner,
                    isRejected = false,
                    viewModel = viewModel
                )
            }

            is AuthUiState.Rejected -> {
                ReviewStatusScreen(
                    partner = state.partner,
                    isRejected = true,
                    rejectionReason = state.reason,
                    viewModel = viewModel
                )
            }

            is AuthUiState.Authenticated -> {
                val partner = state.partner

                if (selectedAssignment != null) {
                    // Focused Active Assignment Detail & Step-by-Step flow
                    ActiveDeliveryDetailScreen(
                        assignment = selectedAssignment!!,
                        viewModel = viewModel,
                        onBack = { viewModel.selectAssignmentForDetail(null) }
                    )
                } else {
                    Scaffold(
                        snackbarHost = { SnackbarHost(snackbarHostState) },
                        topBar = {
                            DeliveryTopAppBar(
                                title = when (currentTab) {
                                    MainNavigationTab.DASHBOARD -> "Sndmart Partner"
                                    MainNavigationTab.DELIVERIES -> "Deliveries"
                                    MainNavigationTab.EARNINGS -> "Earnings & Payouts"
                                    MainNavigationTab.PROFILE -> "My Profile"
                                },
                                subtitle = if (currentTab == MainNavigationTab.DASHBOARD) {
                                    if (partner.isOnline) "Status: Online (Active)" else "Status: Offline"
                                } else null,
                                isOnline = partner.isOnline,
                                isAvailable = partner.isAvailable,
                                isGpsActive = viewModel.isTrackingLocation.collectAsState().value,
                                unreadNotifCount = notifications.count { !it.isRead },
                                onNotificationsClick = { showNotificationsSheet = true },
                                onRefreshClick = { viewModel.refreshAllData() }
                            )
                        },
                        bottomBar = {
                            NavigationBar(
                                containerColor = MaterialTheme.colorScheme.surface,
                                tonalElevation = 6.dp
                            ) {
                                MainNavigationTab.values().forEach { tab ->
                                    val isSelected = currentTab == tab
                                    NavigationBarItem(
                                        selected = isSelected,
                                        onClick = { currentTab = tab },
                                        icon = {
                                            Icon(
                                                imageVector = tab.icon,
                                                contentDescription = tab.title
                                            )
                                        },
                                        label = {
                                            Text(
                                                text = tab.title,
                                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                            )
                                        },
                                        colors = NavigationBarItemDefaults.colors(
                                            selectedIconColor = EmeraldPrimary,
                                            selectedTextColor = EmeraldPrimary,
                                            indicatorColor = MaterialTheme.colorScheme.primaryContainer
                                        ),
                                        modifier = Modifier.testTag("nav_tab_${tab.name.lowercase()}")
                                    )
                                }
                            }
                        }
                    ) { innerPadding ->
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(innerPadding)
                        ) {
                            AnimatedVisibility(
                                visible = maintenanceModeActive,
                                enter = expandVertically() + fadeIn(),
                                exit = shrinkVertically() + fadeOut()
                            ) {
                                MaintenanceBanner(
                                    message = maintenanceMessage
                                        ?: "New orders are temporarily paused platform-wide. You can still complete any deliveries already assigned to you."
                                )
                            }

                            AnimatedVisibility(
                                visible = dataFetchError != null,
                                enter = expandVertically() + fadeIn(),
                                exit = shrinkVertically() + fadeOut()
                            ) {
                                Surface(
                                    color = MaterialTheme.colorScheme.errorContainer,
                                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 16.dp, vertical = 10.dp),
                                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Row(
                                            modifier = Modifier.weight(1f),
                                            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Warning,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.error,
                                                modifier = Modifier.size(20.dp)
                                            )
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Text(
                                                text = dataFetchError ?: "",
                                                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium)
                                            )
                                        }
                                        TextButton(
                                            onClick = { viewModel.refreshAllData() }
                                        ) {
                                            Text("Retry", fontWeight = FontWeight.Bold)
                                        }
                                    }
                                }
                            }

                            if (isDataLoading) {
                                LinearProgressIndicator(
                                    modifier = Modifier.fillMaxWidth(),
                                    color = EmeraldPrimary
                                )
                            }

                            Box(modifier = Modifier.fillMaxSize()) {
                                when (currentTab) {
                                    MainNavigationTab.DASHBOARD -> {
                                        DashboardScreen(
                                            partner = partner,
                                            viewModel = viewModel,
                                            onNavigateToDeliveries = { currentTab = MainNavigationTab.DELIVERIES },
                                            onNavigateToEarnings = { currentTab = MainNavigationTab.EARNINGS },
                                            onOpenAssignmentDetail = { assignment ->
                                                viewModel.selectAssignmentForDetail(assignment)
                                            }
                                        )
                                    }

                                    MainNavigationTab.DELIVERIES -> {
                                        DeliveriesScreen(
                                            viewModel = viewModel,
                                            onOpenAssignmentDetail = { assignment ->
                                                viewModel.selectAssignmentForDetail(assignment)
                                            }
                                        )
                                    }

                                    MainNavigationTab.EARNINGS -> {
                                        EarningsScreen(viewModel = viewModel)
                                    }

                                    MainNavigationTab.PROFILE -> {
                                        ProfileScreen(
                                            partner = partner,
                                            viewModel = viewModel
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // Screens outside the main tab scaffold have no snackbar host of their own.
        val showsTabScaffold = authState is AuthUiState.Authenticated && selectedAssignment == null
        if (!showsTabScaffold) {
            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier
                    .align(androidx.compose.ui.Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .imePadding()
            )
        }

        // Notifications Bottom Sheet
        if (showNotificationsSheet) {
            NotificationsBottomSheet(
                notifications = notifications,
                onDismiss = { showNotificationsSheet = false }
            )
        }
    }
}
