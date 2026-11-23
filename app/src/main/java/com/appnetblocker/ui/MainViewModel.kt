package com.appnetblocker.ui

import android.graphics.drawable.Drawable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.appnetblocker.AppContainer
import com.appnetblocker.data.AppInfo
import com.appnetblocker.data.AppRepository
import com.appnetblocker.data.SettingsRepository
import com.appnetblocker.data.UserSettings
import com.appnetblocker.service.EnvironmentChecker
import com.appnetblocker.service.VpnError
import com.appnetblocker.service.VpnServiceState
import com.appnetblocker.service.VpnStateHolder
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class MessageType {
    NO_APPS_SELECTED,
    VPN_STOPPED_EMPTY,
    PACKAGE_SELECTED,
    PACKAGE_NOT_FOUND,
    VPN_PERMISSION_DENIED
}

sealed interface MainUiEvent {
    data object RequestStartVpn : MainUiEvent
    data object StopVpn : MainUiEvent
    data object ReloadVpn : MainUiEvent
    data class ShowMessage(val type: MessageType, val arg: String? = null) : MainUiEvent
}

data class MainUiState(
    val isLoadingApps: Boolean = true,
    val searchQuery: String = "",
    val showSystemApps: Boolean = false,
    val selectedPackages: Set<String> = emptySet(),
    val visibleSelectedApps: List<AppInfo> = emptyList(),
    val visibleUnselectedApps: List<AppInfo> = emptyList(),
    val allInstalledApps: List<AppInfo> = emptyList(),
    val isVpnRunning: Boolean = false,
    val blockedCount: Int = 0,
    val vpnEnabledSetting: Boolean = false,
    val autoStartOnBoot: Boolean = false,
    val isAnotherVpnActive: Boolean = false,
    val isIgnoringBatteryOptimizations: Boolean = false,
    val wasServiceKilledUnexpectedly: Boolean = false,
    val vpnError: VpnError? = null,
    val wasVpnRevoked: Boolean = false,
    val showWorkProfileNote: Boolean = false,
    val showVpnExplanationDialog: Boolean = false,
    val showQuickPickDialog: Boolean = false,
    val quickPickErrorPackage: String? = null
)

private data class LocalUiFlags(
    val isLoadingApps: Boolean = true,
    val searchQuery: String = "",
    val isIgnoringBatteryOptimizations: Boolean = false,
    val showVpnExplanationDialog: Boolean = false,
    val showQuickPickDialog: Boolean = false,
    val quickPickErrorPackage: String? = null
)

private data class CombinedInputs(
    val apps: List<AppInfo>,
    val settings: UserSettings,
    val vpnState: VpnServiceState,
    val anotherVpnActive: Boolean,
    val localFlags: LocalUiFlags
)

class MainViewModel(
    private val appRepository: AppRepository,
    private val settingsRepository: SettingsRepository,
    private val environmentChecker: EnvironmentChecker,
    private val vpnStateFlow: StateFlow<VpnServiceState> = VpnStateHolder.state,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : ViewModel() {

    private val installedApps = MutableStateFlow<List<AppInfo>>(emptyList())
    private val localFlags = MutableStateFlow(
        LocalUiFlags(
            isIgnoringBatteryOptimizations = environmentChecker.isIgnoringBatteryOptimizations()
        )
    )
    private val otherVpnFlow = MutableStateFlow(
        environmentChecker.isAnotherVpnActive(vpnStateFlow.value.isRunning)
    )

    private val _events = MutableSharedFlow<MainUiEvent>()
    val events: SharedFlow<MainUiEvent> = _events.asSharedFlow()

    val uiState: StateFlow<MainUiState> = combine(
        installedApps,
        settingsRepository.settings,
        vpnStateFlow,
        otherVpnFlow,
        localFlags
    ) { apps, settings, vpnState, anotherVpnFromCallback, flags ->
        buildUiState(
            CombinedInputs(
                apps = apps,
                settings = settings,
                vpnState = vpnState,
                anotherVpnActive = anotherVpnFromCallback,
                localFlags = flags
            )
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000L),
        initialValue = MainUiState()
    )

    init {
        refreshApps()
        viewModelScope.launch {
            environmentChecker.observeOtherVpnActive { vpnStateFlow.value.isRunning }
                .collect { active ->
                    otherVpnFlow.value = active
                }
        }
        viewModelScope.launch {
            vpnStateFlow.collect { vpnState ->
                otherVpnFlow.value = environmentChecker.isAnotherVpnActive(vpnState.isRunning)
            }
        }
    }

    private fun buildUiState(inputs: CombinedInputs): MainUiState {
        val apps = inputs.apps
        val settings = inputs.settings
        val vpnState = inputs.vpnState
        val flags = inputs.localFlags
        val query = flags.searchQuery.trim()
        val selected = settings.selectedPackages

        fun matchesQuery(app: AppInfo): Boolean {
            if (query.isEmpty()) return true
            return app.label.contains(query, ignoreCase = true) ||
                app.packageName.contains(query, ignoreCase = true)
        }

        val knownPackages = apps.associateBy { it.packageName }
        val visibleSelected = selected
            .mapNotNull { pkg ->
                knownPackages[pkg]
            }
            .filter(::matchesQuery)
            .sortedBy { it.label.lowercase() }

        val visibleUnselected = apps
            .asSequence()
            .filter { it.packageName !in selected }
            .filter { settings.showSystemApps || !it.isSystemApp }
            .filter(::matchesQuery)
            .sortedBy { it.label.lowercase() }
            .toList()

        val anySelectedInOtherProfile = selected.any { pkg ->
            knownPackages[pkg]?.isInWorkOrDualProfile == true
        }
        val showWorkProfileNote = appRepository.isRunningInSecondaryProfile() || anySelectedInOtherProfile

        val wasKilled = settings.vpnEnabled &&
            !vpnState.isRunning &&
            !vpnState.wasRevoked &&
            vpnState.lastError == null

        return MainUiState(
            isLoadingApps = flags.isLoadingApps,
            searchQuery = flags.searchQuery,
            showSystemApps = settings.showSystemApps,
            selectedPackages = selected,
            visibleSelectedApps = visibleSelected,
            visibleUnselectedApps = visibleUnselected,
            allInstalledApps = apps,
            isVpnRunning = vpnState.isRunning,
            blockedCount = if (vpnState.isRunning) vpnState.blockedPackages.size else 0,
            vpnEnabledSetting = settings.vpnEnabled,
            autoStartOnBoot = settings.autoStartOnBoot,
            isAnotherVpnActive = !vpnState.isRunning && inputs.anotherVpnActive,
            isIgnoringBatteryOptimizations = flags.isIgnoringBatteryOptimizations,
            wasServiceKilledUnexpectedly = wasKilled,
            vpnError = vpnState.lastError,
            wasVpnRevoked = vpnState.wasRevoked,
            showWorkProfileNote = showWorkProfileNote,
            showVpnExplanationDialog = flags.showVpnExplanationDialog,
            showQuickPickDialog = flags.showQuickPickDialog,
            quickPickErrorPackage = flags.quickPickErrorPackage
        )
    }

    fun refreshApps() {
        viewModelScope.launch(ioDispatcher) {
            val loaded = appRepository.getInstalledApps()
            installedApps.value = loaded
            localFlags.value = localFlags.value.copy(isLoadingApps = false)
        }
    }

    fun refreshEnvironment() {
        otherVpnFlow.value = environmentChecker.isAnotherVpnActive(vpnStateFlow.value.isRunning)
        localFlags.value = localFlags.value.copy(
            isIgnoringBatteryOptimizations = environmentChecker.isIgnoringBatteryOptimizations()
        )
    }

    suspend fun loadAppIcon(packageName: String): Drawable? =
        appRepository.loadAppIcon(packageName)

    fun onSearchQueryChange(query: String) {
        localFlags.value = localFlags.value.copy(searchQuery = query)
    }

    fun onShowSystemAppsChange(show: Boolean) {
        viewModelScope.launch {
            settingsRepository.setShowSystemApps(show)
        }
    }

    fun onToggleApp(packageName: String) {
        viewModelScope.launch {
            val current = settingsRepository.settings.first().selectedPackages
            val updated = if (packageName in current) current - packageName else current + packageName
            settingsRepository.setSelectedPackages(updated)

            if (vpnStateFlow.value.isRunning) {
                if (updated.isEmpty()) {
                    settingsRepository.setVpnEnabled(false)
                    _events.emit(MainUiEvent.StopVpn)
                    _events.emit(MainUiEvent.ShowMessage(MessageType.VPN_STOPPED_EMPTY))
                } else {
                    _events.emit(MainUiEvent.ReloadVpn)
                }
            }
        }
    }

    fun onQuickPickOpen() {
        localFlags.value = localFlags.value.copy(
            showQuickPickDialog = true,
            quickPickErrorPackage = null
        )
    }

    fun onQuickPickDismiss() {
        localFlags.value = localFlags.value.copy(
            showQuickPickDialog = false,
            quickPickErrorPackage = null
        )
    }

    fun onQuickPickSubmit(packageName: String) {
        val trimmed = packageName.trim()
        if (trimmed.isEmpty()) return

        viewModelScope.launch {
            val installed = installedApps.value.any { it.packageName == trimmed }
            if (!installed) {
                localFlags.value = localFlags.value.copy(quickPickErrorPackage = trimmed)
                _events.emit(MainUiEvent.ShowMessage(MessageType.PACKAGE_NOT_FOUND, trimmed))
                return@launch
            }

            settingsRepository.selectPackage(trimmed)
            localFlags.value = localFlags.value.copy(
                showQuickPickDialog = false,
                quickPickErrorPackage = null
            )
            _events.emit(MainUiEvent.ShowMessage(MessageType.PACKAGE_SELECTED, trimmed))

            if (vpnStateFlow.value.isRunning) {
                _events.emit(MainUiEvent.ReloadVpn)
            }
        }
    }

    fun onToggleVpnSwitch(turnOn: Boolean) {
        viewModelScope.launch {
            if (!turnOn) {
                VpnStateHolder.clearError()
                settingsRepository.setVpnEnabled(false)
                _events.emit(MainUiEvent.StopVpn)
                return@launch
            }

            val currentSettings = settingsRepository.settings.first()
            if (currentSettings.selectedPackages.isEmpty()) {
                _events.emit(MainUiEvent.ShowMessage(MessageType.NO_APPS_SELECTED))
                return@launch
            }

            VpnStateHolder.clearError()
            if (!currentSettings.hasSeenVpnExplanation) {
                localFlags.value = localFlags.value.copy(showVpnExplanationDialog = true)
            } else {
                _events.emit(MainUiEvent.RequestStartVpn)
            }
        }
    }

    fun onConfirmVpnExplanation() {
        viewModelScope.launch {
            settingsRepository.setHasSeenVpnExplanation(true)
            localFlags.value = localFlags.value.copy(showVpnExplanationDialog = false)
            _events.emit(MainUiEvent.RequestStartVpn)
        }
    }

    fun onDismissVpnExplanation() {
        localFlags.value = localFlags.value.copy(showVpnExplanationDialog = false)
    }

    fun onVpnPermissionDenied() {
        viewModelScope.launch {
            settingsRepository.setVpnEnabled(false)
            _events.emit(MainUiEvent.ShowMessage(MessageType.VPN_PERMISSION_DENIED))
        }
    }

    fun onAutoStartOnBootChange(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setAutoStartOnBoot(enabled)
        }
    }

    fun onDismissVpnError() {
        viewModelScope.launch {
            VpnStateHolder.clearError()
            if (!vpnStateFlow.value.isRunning) {
                settingsRepository.setVpnEnabled(false)
            }
        }
    }

    companion object {
        fun provideFactory(container: AppContainer): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return MainViewModel(
                        appRepository = container.appRepository,
                        settingsRepository = container.settingsRepository,
                        environmentChecker = container.environmentChecker
                    ) as T
                }
            }
    }
}
