package com.appnetblocker.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "app_net_blocker_prefs"
)

interface SettingsRepository {
    val settings: Flow<UserSettings>
    suspend fun togglePackage(packageName: String)
    suspend fun selectPackage(packageName: String)
    suspend fun setSelectedPackages(packages: Set<String>)
    suspend fun setVpnEnabled(enabled: Boolean)
    suspend fun setAutoStartOnBoot(enabled: Boolean)
    suspend fun setShowSystemApps(show: Boolean)
    suspend fun setHasSeenVpnExplanation(seen: Boolean)
}

class DataStoreSettingsRepository(
    private val dataStore: DataStore<Preferences>
) : SettingsRepository {

    private object Keys {
        val SELECTED_PACKAGES = stringSetPreferencesKey("selected_packages")
        val VPN_ENABLED = booleanPreferencesKey("vpn_enabled")
        val AUTO_START_ON_BOOT = booleanPreferencesKey("auto_start_on_boot")
        val SHOW_SYSTEM_APPS = booleanPreferencesKey("show_system_apps")
        val HAS_SEEN_VPN_EXPLANATION = booleanPreferencesKey("has_seen_vpn_explanation")
    }

    override val settings: Flow<UserSettings> = dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { prefs ->
            UserSettings(
                selectedPackages = prefs[Keys.SELECTED_PACKAGES] ?: emptySet(),
                vpnEnabled = prefs[Keys.VPN_ENABLED] ?: false,
                autoStartOnBoot = prefs[Keys.AUTO_START_ON_BOOT] ?: false,
                showSystemApps = prefs[Keys.SHOW_SYSTEM_APPS] ?: false,
                hasSeenVpnExplanation = prefs[Keys.HAS_SEEN_VPN_EXPLANATION] ?: false
            )
        }

    override suspend fun togglePackage(packageName: String) {
        dataStore.edit { prefs ->
            val current = prefs[Keys.SELECTED_PACKAGES] ?: emptySet()
            prefs[Keys.SELECTED_PACKAGES] = if (packageName in current) {
                current - packageName
            } else {
                current + packageName
            }
        }
    }

    override suspend fun selectPackage(packageName: String) {
        dataStore.edit { prefs ->
            val current = prefs[Keys.SELECTED_PACKAGES] ?: emptySet()
            prefs[Keys.SELECTED_PACKAGES] = current + packageName
        }
    }

    override suspend fun setSelectedPackages(packages: Set<String>) {
        dataStore.edit { prefs ->
            prefs[Keys.SELECTED_PACKAGES] = packages
        }
    }

    override suspend fun setVpnEnabled(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[Keys.VPN_ENABLED] = enabled
        }
    }

    override suspend fun setAutoStartOnBoot(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[Keys.AUTO_START_ON_BOOT] = enabled
        }
    }

    override suspend fun setShowSystemApps(show: Boolean) {
        dataStore.edit { prefs ->
            prefs[Keys.SHOW_SYSTEM_APPS] = show
        }
    }

    override suspend fun setHasSeenVpnExplanation(seen: Boolean) {
        dataStore.edit { prefs ->
            prefs[Keys.HAS_SEEN_VPN_EXPLANATION] = seen
        }
    }
}
