package com.appnetblocker

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.appnetblocker.service.BlockerVpnService
import com.appnetblocker.ui.MainScreen
import com.appnetblocker.ui.MainUiEvent
import com.appnetblocker.ui.MainViewModel
import com.appnetblocker.ui.MessageType
import com.appnetblocker.ui.SettingsScreen
import com.appnetblocker.ui.theme.AppNetBlockerTheme
import kotlinx.coroutines.launch

private enum class ScreenDestination {
    MAIN,
    SETTINGS
}

class MainActivity : ComponentActivity() {

    private lateinit var activeViewModel: MainViewModel
    private var askedNotificationPermission = false

    private val vpnConsentLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            startVpnService()
        } else if (::activeViewModel.isInitialized) {
            activeViewModel.onVpnPermissionDenied()
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        // Proceed even if notification permission was denied: local VPN still works.
        launchVpnPreparation()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val appContainer = (application as AppNetBlockerApp).container

        setContent {
            AppNetBlockerTheme {
                val vm: MainViewModel = viewModel(
                    factory = MainViewModel.provideFactory(appContainer)
                )
                activeViewModel = vm

                val uiState by vm.uiState.collectAsStateWithLifecycle()
                val snackbarHostState = remember { SnackbarHostState() }
                var destination by rememberSaveable { mutableStateOf(ScreenDestination.MAIN) }
                val lifecycleOwner = LocalLifecycleOwner.current

                DisposableEffect(lifecycleOwner) {
                    val observer = LifecycleEventObserver { _, event ->
                        if (event == Lifecycle.Event.ON_RESUME) {
                            vm.refreshEnvironment()
                            vm.refreshApps()
                        }
                    }
                    lifecycleOwner.lifecycle.addObserver(observer)
                    onDispose {
                        lifecycleOwner.lifecycle.removeObserver(observer)
                    }
                }

                LaunchedEffect(vm) {
                    vm.events.collect { event ->
                        when (event) {
                            MainUiEvent.RequestStartVpn -> startVpnFlow()
                            MainUiEvent.StopVpn -> stopVpnService()
                            MainUiEvent.ReloadVpn -> reloadVpnService()
                            is MainUiEvent.ShowMessage -> {
                                val text = formatMessage(event.type, event.arg)
                                snackbarHostState.showSnackbar(text)
                            }
                        }
                    }
                }

                if (destination == ScreenDestination.SETTINGS) {
                    BackHandler { destination = ScreenDestination.MAIN }
                    SettingsScreen(
                        uiState = uiState,
                        snackbarHostState = snackbarHostState,
                        onBack = { destination = ScreenDestination.MAIN },
                        onAutoStartOnBootChange = vm::onAutoStartOnBootChange,
                        onOpenVpnSettings = { openVpnSettings(snackbarHostState) },
                        onRequestBatteryOptimization = {
                            requestBatteryOptimizationExemption(snackbarHostState)
                        },
                        onOpenUrl = { url -> openExternalUrl(url, snackbarHostState) }
                    )
                } else {
                    MainScreen(
                        uiState = uiState,
                        snackbarHostState = snackbarHostState,
                        loadAppIcon = vm::loadAppIcon,
                        onToggleVpn = vm::onToggleVpnSwitch,
                        onSearchQueryChange = vm::onSearchQueryChange,
                        onShowSystemAppsChange = vm::onShowSystemAppsChange,
                        onToggleApp = vm::onToggleApp,
                        onQuickPickOpen = vm::onQuickPickOpen,
                        onQuickPickDismiss = vm::onQuickPickDismiss,
                        onQuickPickSubmit = vm::onQuickPickSubmit,
                        onConfirmVpnExplanation = vm::onConfirmVpnExplanation,
                        onDismissVpnExplanation = vm::onDismissVpnExplanation,
                        onDismissVpnError = vm::onDismissVpnError,
                        onNavigateToSettings = { destination = ScreenDestination.SETTINGS }
                    )
                }
            }
        }
    }

    private fun startVpnFlow() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !askedNotificationPermission) {
            val granted = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                askedNotificationPermission = true
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                return
            }
        }
        launchVpnPreparation()
    }

    private fun launchVpnPreparation() {
        val prepareIntent = try {
            VpnService.prepare(this)
        } catch (_: Exception) {
            if (::activeViewModel.isInitialized) {
                activeViewModel.onVpnPermissionDenied()
            }
            return
        }

        if (prepareIntent != null) {
            try {
                vpnConsentLauncher.launch(prepareIntent)
            } catch (_: ActivityNotFoundException) {
                if (::activeViewModel.isInitialized) {
                    activeViewModel.onVpnPermissionDenied()
                }
            }
        } else {
            startVpnService()
        }
    }

    private fun startVpnService() {
        ContextCompat.startForegroundService(this, BlockerVpnService.startIntent(this))
    }

    private fun stopVpnService() {
        try {
            startService(BlockerVpnService.stopIntent(this))
        } catch (_: Exception) {
            // Service may already be stopped.
        }
    }

    private fun reloadVpnService() {
        ContextCompat.startForegroundService(this, BlockerVpnService.reloadIntent(this))
    }

    private fun openVpnSettings(snackbarHostState: SnackbarHostState) {
        val vpnIntent = Intent(Settings.ACTION_VPN_SETTINGS)
        try {
            startActivity(vpnIntent)
        } catch (_: ActivityNotFoundException) {
            try {
                startActivity(Intent(Settings.ACTION_SETTINGS))
            } catch (_: ActivityNotFoundException) {
                showSnackbarMessage(snackbarHostState, getString(R.string.error_no_browser))
            }
        }
    }

    @SuppressLint("BatteryLife")
    private fun requestBatteryOptimizationExemption(snackbarHostState: SnackbarHostState) {
        val requestIntent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:$packageName")
        }
        try {
            startActivity(requestIntent)
        } catch (_: ActivityNotFoundException) {
            try {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (_: ActivityNotFoundException) {
                showSnackbarMessage(snackbarHostState, getString(R.string.error_no_browser))
            }
        }
    }

    private fun openExternalUrl(url: String, snackbarHostState: SnackbarHostState) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: ActivityNotFoundException) {
            showSnackbarMessage(snackbarHostState, getString(R.string.error_no_browser))
        }
    }

    private fun showSnackbarMessage(snackbarHostState: SnackbarHostState, text: String) {
        lifecycleScope.launch {
            snackbarHostState.showSnackbar(text)
        }
    }

    private fun formatMessage(type: MessageType, arg: String?): String = when (type) {
        MessageType.NO_APPS_SELECTED -> getString(R.string.msg_no_apps_selected)
        MessageType.VPN_STOPPED_EMPTY -> getString(R.string.msg_vpn_stopped_empty)
        MessageType.PACKAGE_SELECTED -> getString(R.string.msg_package_selected, arg.orEmpty())
        MessageType.PACKAGE_NOT_FOUND -> getString(R.string.msg_package_not_found, arg.orEmpty())
        MessageType.VPN_PERMISSION_DENIED -> getString(R.string.msg_vpn_permission_denied)
    }
}
