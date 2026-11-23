package com.appnetblocker

import android.app.Application
import android.content.Context
import com.appnetblocker.data.AndroidAppRepository
import com.appnetblocker.data.AppRepository
import com.appnetblocker.data.DataStoreSettingsRepository
import com.appnetblocker.data.SettingsRepository
import com.appnetblocker.data.settingsDataStore
import com.appnetblocker.service.AndroidEnvironmentChecker
import com.appnetblocker.service.EnvironmentChecker

class AppContainer(context: Context) {
    val settingsRepository: SettingsRepository = DataStoreSettingsRepository(context.settingsDataStore)
    val appRepository: AppRepository = AndroidAppRepository(context)
    val environmentChecker: EnvironmentChecker = AndroidEnvironmentChecker(context)
}

class AppNetBlockerApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
