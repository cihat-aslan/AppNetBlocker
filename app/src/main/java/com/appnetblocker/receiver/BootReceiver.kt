package com.appnetblocker.receiver

import android.app.ForegroundServiceStartNotAllowedException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import androidx.core.content.ContextCompat
import com.appnetblocker.AppNetBlockerApp
import com.appnetblocker.service.BlockerVpnService
import com.appnetblocker.service.VpnError
import com.appnetblocker.service.VpnStateHolder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return

        val pendingResult = goAsync()
        val appContext = context.applicationContext
        val settingsRepository = (appContext as AppNetBlockerApp).container.settingsRepository

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val settings = settingsRepository.settings.first()
                if (!settings.autoStartOnBoot || !settings.vpnEnabled || settings.selectedPackages.isEmpty()) {
                    return@launch
                }

                // Only start if VPN permission was already granted by the user prior to reboot.
                if (VpnService.prepare(appContext) != null) {
                    settingsRepository.setVpnEnabled(false)
                    VpnStateHolder.updateStopped(error = VpnError.PERMISSION_REVOKED, revoked = true)
                    return@launch
                }

                try {
                    ContextCompat.startForegroundService(
                        appContext,
                        BlockerVpnService.startIntent(appContext)
                    )
                } catch (e: Exception) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                        e is ForegroundServiceStartNotAllowedException
                    ) {
                        VpnStateHolder.updateStopped(error = VpnError.BOOT_START_RESTRICTED)
                    } else if (e is SecurityException || e is IllegalStateException) {
                        VpnStateHolder.updateStopped(error = VpnError.BOOT_START_RESTRICTED)
                    } else {
                        throw e
                    }
                }
            } finally {
                pendingResult.finish()
            }
        }
    }
}
