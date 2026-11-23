package com.appnetblocker.data

import androidx.datastore.core.okio.OkioStorage
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.PreferencesSerializer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import okio.FileSystem
import okio.Path.Companion.toPath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SettingsRepositoryTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun createTestRepository(
        fileName: String,
        scope: CoroutineScope
    ): DataStoreSettingsRepository {
        val file = File(tempFolder.newFolder(), fileName)
        val dataStore = PreferenceDataStoreFactory.create(
            storage = OkioStorage(
                fileSystem = FileSystem.SYSTEM,
                serializer = PreferencesSerializer,
                producePath = { file.absolutePath.toPath() }
            ),
            scope = scope
        )
        return DataStoreSettingsRepository(dataStore)
    }

    @Test
    fun defaultValuesAreEmptyAndDisabled() = runTest {
        val dataStoreScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val repo = createTestRepository("defaults.preferences_pb", dataStoreScope)

            val initial = repo.settings.first()
            assertEquals(emptySet<String>(), initial.selectedPackages)
            assertFalse(initial.vpnEnabled)
            assertFalse(initial.autoStartOnBoot)
            assertFalse(initial.showSystemApps)
            assertFalse(initial.hasSeenVpnExplanation)
        } finally {
            dataStoreScope.cancel()
        }
    }

    @Test
    fun togglePackageAddsAndRemovesPackage() = runTest {
        val dataStoreScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val repo = createTestRepository("toggle.preferences_pb", dataStoreScope)

            repo.togglePackage("com.example.game")
            assertEquals(setOf("com.example.game"), repo.settings.first().selectedPackages)

            repo.togglePackage("com.example.other")
            assertEquals(
                setOf("com.example.game", "com.example.other"),
                repo.settings.first().selectedPackages
            )

            repo.togglePackage("com.example.game")
            assertEquals(setOf("com.example.other"), repo.settings.first().selectedPackages)
        } finally {
            dataStoreScope.cancel()
        }
    }

    @Test
    fun selectPackageIsIdempotent() = runTest {
        val dataStoreScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val repo = createTestRepository("select.preferences_pb", dataStoreScope)

            repo.selectPackage("com.easybrain.killer.sudoku")
            repo.selectPackage("com.easybrain.killer.sudoku")

            assertEquals(
                setOf("com.easybrain.killer.sudoku"),
                repo.settings.first().selectedPackages
            )
        } finally {
            dataStoreScope.cancel()
        }
    }

    @Test
    fun booleanFlagsPersistAccurately() = runTest {
        val dataStoreScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val repo = createTestRepository("flags.preferences_pb", dataStoreScope)

            repo.setVpnEnabled(true)
            repo.setAutoStartOnBoot(true)
            repo.setShowSystemApps(true)
            repo.setHasSeenVpnExplanation(true)

            val updated = repo.settings.first()
            assertTrue(updated.vpnEnabled)
            assertTrue(updated.autoStartOnBoot)
            assertTrue(updated.showSystemApps)
            assertTrue(updated.hasSeenVpnExplanation)
        } finally {
            dataStoreScope.cancel()
        }
    }
}
