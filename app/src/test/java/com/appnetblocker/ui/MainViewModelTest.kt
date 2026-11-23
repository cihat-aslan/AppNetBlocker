package com.appnetblocker.ui

import android.graphics.drawable.Drawable
import com.appnetblocker.data.AppInfo
import com.appnetblocker.data.AppRepository
import com.appnetblocker.data.SettingsRepository
import com.appnetblocker.data.UserSettings
import com.appnetblocker.service.EnvironmentChecker
import com.appnetblocker.service.VpnStateHolder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    private val sampleApps = listOf(
        AppInfo("com.alpha.browser", "Alpha Browser", isSystemApp = false),
        AppInfo("com.easybrain.killer.sudoku", "Killer Sudoku", isSystemApp = false),
        AppInfo("com.android.systemui", "System UI", isSystemApp = true),
        AppInfo("com.zeta.notes", "Zeta Notes", isSystemApp = false),
        AppInfo("com.work.chat", "Work Chat", isSystemApp = false, isInWorkOrDualProfile = true)
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        VpnStateHolder.resetForTesting()
    }

    @After
    fun tearDown() {
        VpnStateHolder.resetForTesting()
        Dispatchers.resetMain()
    }

    @Test
    fun systemAppsAreHiddenByDefaultAndShownWhenEnabled() = runTest {
        val settingsRepo = FakeSettingsRepository()
        val viewModel = createViewModel(settingsRepo = settingsRepo)
        val collectJob = backgroundScope.launch(testDispatcher) { viewModel.uiState.collect {} }

        assertFalse(viewModel.uiState.value.visibleUnselectedApps.any { it.isSystemApp })

        viewModel.onShowSystemAppsChange(true)
        assertTrue(viewModel.uiState.value.visibleUnselectedApps.any { it.packageName == "com.android.systemui" })

        collectJob.cancel()
    }

    @Test
    fun selectedAppsArePinnedToTopAndSortedAlphabetically() = runTest {
        val settingsRepo = FakeSettingsRepository(
            UserSettings(selectedPackages = setOf("com.zeta.notes", "com.alpha.browser"))
        )
        val viewModel = createViewModel(settingsRepo = settingsRepo)
        val collectJob = backgroundScope.launch(testDispatcher) { viewModel.uiState.collect {} }

        val state = viewModel.uiState.value
        assertEquals(
            listOf("Alpha Browser", "Zeta Notes"),
            state.visibleSelectedApps.map { it.label }
        )
        assertEquals(
            listOf("Killer Sudoku", "Work Chat"),
            state.visibleUnselectedApps.map { it.label }
        )

        collectJob.cancel()
    }

    @Test
    fun searchFiltersByLabelAndPackageNameCaseInsensitively() = runTest {
        val viewModel = createViewModel()
        val collectJob = backgroundScope.launch(testDispatcher) { viewModel.uiState.collect {} }

        viewModel.onSearchQueryChange("SUDOKU")
        assertEquals(
            listOf("com.easybrain.killer.sudoku"),
            viewModel.uiState.value.visibleUnselectedApps.map { it.packageName }
        )

        viewModel.onSearchQueryChange("com.zeta")
        assertEquals(
            listOf("com.zeta.notes"),
            viewModel.uiState.value.visibleUnselectedApps.map { it.packageName }
        )

        collectJob.cancel()
    }

    @Test
    fun quickPickSelectsValidPackageAndRejectsUnknownPackage() = runTest {
        val viewModel = createViewModel()
        val events = mutableListOf<MainUiEvent>()
        val stateJob = backgroundScope.launch(testDispatcher) { viewModel.uiState.collect {} }
        val eventJob = backgroundScope.launch(testDispatcher) { viewModel.events.collect { events.add(it) } }

        viewModel.onQuickPickOpen()
        assertTrue(viewModel.uiState.value.showQuickPickDialog)

        viewModel.onQuickPickSubmit("com.unknown.app")
        assertEquals("com.unknown.app", viewModel.uiState.value.quickPickErrorPackage)
        assertTrue(viewModel.uiState.value.showQuickPickDialog)
        assertTrue(events.contains(MainUiEvent.ShowMessage(MessageType.PACKAGE_NOT_FOUND, "com.unknown.app")))

        viewModel.onQuickPickSubmit("  com.easybrain.killer.sudoku  ")
        assertFalse(viewModel.uiState.value.showQuickPickDialog)
        assertTrue("com.easybrain.killer.sudoku" in viewModel.uiState.value.selectedPackages)
        assertTrue(events.contains(MainUiEvent.ShowMessage(MessageType.PACKAGE_SELECTED, "com.easybrain.killer.sudoku")))

        stateJob.cancel()
        eventJob.cancel()
    }

    @Test
    fun turningVpnOnWithEmptySelectionEmitsWarningAndNeverRequestsVpn() = runTest {
        val viewModel = createViewModel(
            settingsRepo = FakeSettingsRepository(
                UserSettings(selectedPackages = emptySet(), hasSeenVpnExplanation = true)
            )
        )
        val events = mutableListOf<MainUiEvent>()
        val eventJob = backgroundScope.launch(testDispatcher) { viewModel.events.collect { events.add(it) } }

        viewModel.onToggleVpnSwitch(true)

        assertEquals(listOf(MainUiEvent.ShowMessage(MessageType.NO_APPS_SELECTED)), events)
        assertFalse(events.contains(MainUiEvent.RequestStartVpn))

        eventJob.cancel()
    }

    @Test
    fun firstLaunchShowsVpnExplanationBeforeRequestingVpnPermission() = runTest {
        val settingsRepo = FakeSettingsRepository(
            UserSettings(
                selectedPackages = setOf("com.easybrain.killer.sudoku"),
                hasSeenVpnExplanation = false
            )
        )
        val viewModel = createViewModel(settingsRepo = settingsRepo)
        val events = mutableListOf<MainUiEvent>()
        val stateJob = backgroundScope.launch(testDispatcher) { viewModel.uiState.collect {} }
        val eventJob = backgroundScope.launch(testDispatcher) { viewModel.events.collect { events.add(it) } }

        viewModel.onToggleVpnSwitch(true)
        assertTrue(viewModel.uiState.value.showVpnExplanationDialog)
        assertFalse(events.contains(MainUiEvent.RequestStartVpn))

        viewModel.onConfirmVpnExplanation()
        assertFalse(viewModel.uiState.value.showVpnExplanationDialog)
        assertTrue(events.contains(MainUiEvent.RequestStartVpn))

        stateJob.cancel()
        eventJob.cancel()
    }

    @Test
    fun deselectingLastAppWhileVpnIsRunningStopsVpn() = runTest {
        val settingsRepo = FakeSettingsRepository(
            UserSettings(
                selectedPackages = setOf("com.easybrain.killer.sudoku"),
                vpnEnabled = true,
                hasSeenVpnExplanation = true
            )
        )
        VpnStateHolder.updateRunning(setOf("com.easybrain.killer.sudoku"))

        val viewModel = createViewModel(settingsRepo = settingsRepo)
        val events = mutableListOf<MainUiEvent>()
        val stateJob = backgroundScope.launch(testDispatcher) { viewModel.uiState.collect {} }
        val eventJob = backgroundScope.launch(testDispatcher) { viewModel.events.collect { events.add(it) } }

        viewModel.onToggleApp("com.easybrain.killer.sudoku")

        assertTrue(events.contains(MainUiEvent.StopVpn))
        assertTrue(events.contains(MainUiEvent.ShowMessage(MessageType.VPN_STOPPED_EMPTY)))
        assertTrue(viewModel.uiState.value.selectedPackages.isEmpty())

        stateJob.cancel()
        eventJob.cancel()
    }

    @Test
    fun unexpectedServiceKillSetsKilledWarningFlag() = runTest {
        val settingsRepo = FakeSettingsRepository(
            UserSettings(
                selectedPackages = setOf("com.easybrain.killer.sudoku"),
                vpnEnabled = true
            )
        )
        VpnStateHolder.updateStopped(error = null, revoked = false)

        val viewModel = createViewModel(settingsRepo = settingsRepo)
        val stateJob = backgroundScope.launch(testDispatcher) { viewModel.uiState.collect {} }

        assertTrue(viewModel.uiState.value.wasServiceKilledUnexpectedly)

        viewModel.onDismissVpnError()
        assertFalse(viewModel.uiState.value.wasServiceKilledUnexpectedly)

        stateJob.cancel()
    }

    @Test
    fun selectingWorkOrDualProfileAppShowsProfileNote() = runTest {
        val settingsRepo = FakeSettingsRepository(
            UserSettings(selectedPackages = setOf("com.work.chat"))
        )
        val viewModel = createViewModel(settingsRepo = settingsRepo)
        val stateJob = backgroundScope.launch(testDispatcher) { viewModel.uiState.collect {} }

        assertTrue(viewModel.uiState.value.showWorkProfileNote)

        stateJob.cancel()
    }

    private fun createViewModel(
        appRepo: AppRepository = FakeAppRepository(sampleApps),
        settingsRepo: SettingsRepository = FakeSettingsRepository(),
        envChecker: EnvironmentChecker = FakeEnvironmentChecker()
    ): MainViewModel = MainViewModel(
        appRepository = appRepo,
        settingsRepository = settingsRepo,
        environmentChecker = envChecker,
        vpnStateFlow = VpnStateHolder.state,
        ioDispatcher = testDispatcher
    )
}

private class FakeAppRepository(
    private val apps: List<AppInfo>,
    private val secondaryProfile: Boolean = false
) : AppRepository {
    override suspend fun getInstalledApps(): List<AppInfo> = apps
    override suspend fun loadAppIcon(packageName: String): Drawable? = null
    override fun isRunningInSecondaryProfile(): Boolean = secondaryProfile
    override fun hasOtherUserProfiles(): Boolean = apps.any { it.isInWorkOrDualProfile }
}

private class FakeSettingsRepository(
    initial: UserSettings = UserSettings()
) : SettingsRepository {
    private val flow = MutableStateFlow(initial)
    override val settings: Flow<UserSettings> = flow

    override suspend fun togglePackage(packageName: String) {
        flow.update { current ->
            val updated = if (packageName in current.selectedPackages) {
                current.selectedPackages - packageName
            } else {
                current.selectedPackages + packageName
            }
            current.copy(selectedPackages = updated)
        }
    }

    override suspend fun selectPackage(packageName: String) {
        flow.update { it.copy(selectedPackages = it.selectedPackages + packageName) }
    }

    override suspend fun setSelectedPackages(packages: Set<String>) {
        flow.update { it.copy(selectedPackages = packages) }
    }

    override suspend fun setVpnEnabled(enabled: Boolean) {
        flow.update { it.copy(vpnEnabled = enabled) }
    }

    override suspend fun setAutoStartOnBoot(enabled: Boolean) {
        flow.update { it.copy(autoStartOnBoot = enabled) }
    }

    override suspend fun setShowSystemApps(show: Boolean) {
        flow.update { it.copy(showSystemApps = show) }
    }

    override suspend fun setHasSeenVpnExplanation(seen: Boolean) {
        flow.update { it.copy(hasSeenVpnExplanation = seen) }
    }
}

private class FakeEnvironmentChecker(
    anotherVpnActive: Boolean = false,
    private val ignoringBattery: Boolean = false
) : EnvironmentChecker {
    private val otherVpn = MutableStateFlow(anotherVpnActive)

    override fun isAnotherVpnActive(ownVpnRunning: Boolean): Boolean =
        if (ownVpnRunning) false else otherVpn.value

    override fun observeOtherVpnActive(ownVpnRunning: () -> Boolean): Flow<Boolean> = otherVpn

    override fun isIgnoringBatteryOptimizations(): Boolean = ignoringBattery
}
