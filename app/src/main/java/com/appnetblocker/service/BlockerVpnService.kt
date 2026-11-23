package com.appnetblocker.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.appnetblocker.AppNetBlockerApp
import com.appnetblocker.MainActivity
import com.appnetblocker.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.FileOutputStream
import java.io.IOException

class BlockerVpnService : VpnService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val tunnelMutex = Mutex()

    @Volatile
    private var tunInterface: ParcelFileDescriptor? = null

    @Volatile
    private var stopPipeRead: ParcelFileDescriptor? = null

    @Volatile
    private var stopPipeWrite: ParcelFileDescriptor? = null

    @Volatile
    private var drainJob: Job? = null

    private val settingsRepository by lazy {
        (application as AppNetBlockerApp).container.settingsRepository
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action

        if (action == ACTION_STOP) {
            closeTunnelSync()
            VpnStateHolder.updateStopped()
            serviceScope.launch {
                try {
                    settingsRepository.setVpnEnabled(false)
                } finally {
                    withContext(Dispatchers.Main) {
                        stopForegroundCompat()
                        stopSelf()
                    }
                }
            }
            return START_NOT_STICKY
        }

        // Satisfy Android's foreground service startup deadline before asynchronous setup.
        ensureNotificationChannel()
        val initialCount = VpnStateHolder.state.value.blockedPackages.size
        startForegroundCompat(buildNotification(initialCount))

        val isSystemStickyRestart = intent == null
        serviceScope.launch {
            startOrReloadTunnel(isSystemStickyRestart = isSystemStickyRestart)
        }
        return START_STICKY
    }

    override fun onRevoke() {
        closeTunnelSync()
        VpnStateHolder.updateStopped(error = VpnError.PERMISSION_REVOKED, revoked = true)
        serviceScope.launch {
            try {
                settingsRepository.setVpnEnabled(false)
            } finally {
                withContext(Dispatchers.Main) {
                    stopForegroundCompat()
                    stopSelf()
                }
            }
        }
        super.onRevoke()
    }

    override fun onDestroy() {
        val wasRunning = VpnStateHolder.state.value.isRunning
        closeTunnelSync()
        serviceScope.cancel()
        if (wasRunning) {
            // Leave vpnEnabled=true in DataStore so the UI can detect an unexpected kill
            // and START_STICKY can restore the tunnel if the OS restarts the service.
            VpnStateHolder.updateStopped()
        }
        super.onDestroy()
    }

    private suspend fun startOrReloadTunnel(isSystemStickyRestart: Boolean) {
        tunnelMutex.withLock {
            val settings = settingsRepository.settings.first()

            if (isSystemStickyRestart && !settings.vpnEnabled) {
                closeTunnelSync()
                VpnStateHolder.updateStopped()
                withContext(Dispatchers.Main) {
                    stopForegroundCompat()
                    stopSelf()
                }
                return
            }

            if (settings.selectedPackages.isEmpty()) {
                closeTunnelSync()
                settingsRepository.setVpnEnabled(false)
                VpnStateHolder.updateStopped(error = VpnError.NO_APPS_SELECTED)
                withContext(Dispatchers.Main) {
                    stopForegroundCompat()
                    stopSelf()
                }
                return
            }

            if (prepare(this@BlockerVpnService) != null) {
                closeTunnelSync()
                settingsRepository.setVpnEnabled(false)
                VpnStateHolder.updateStopped(error = VpnError.PERMISSION_REVOKED, revoked = true)
                withContext(Dispatchers.Main) {
                    stopForegroundCompat()
                    stopSelf()
                }
                return
            }

            val builder = Builder()
                .setSession(getString(R.string.app_name))
                .setMtu(VPN_MTU)
                .setBlocking(false)
                .addAddress(IPV4_ADDRESS, 32)
                .addRoute("0.0.0.0", 0)

            try {
                builder.addAddress(IPV6_ADDRESS, 128)
                builder.addRoute("::", 0)
            } catch (_: Exception) {
                // Fall back to IPv4-only if the kernel rejects IPv6 TUN routes.
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                builder.setMetered(false)
            }

            // Do not add DNS servers: blocked apps should fail DNS fast rather than forwarding.
            val actuallyAdded = linkedSetOf<String>()
            for (pkg in settings.selectedPackages) {
                try {
                    builder.addAllowedApplication(pkg)
                    actuallyAdded.add(pkg)
                } catch (_: PackageManager.NameNotFoundException) {
                    // Package was uninstalled after selection; skip safely.
                }
            }

            // Safety guard: an empty allowed list routes ALL device traffic into the blackhole.
            if (actuallyAdded.isEmpty()) {
                closeTunnelSync()
                settingsRepository.setVpnEnabled(false)
                VpnStateHolder.updateStopped(error = VpnError.NO_APPS_SELECTED)
                withContext(Dispatchers.Main) {
                    stopForegroundCompat()
                    stopSelf()
                }
                return
            }

            val newInterface = try {
                builder.establish()
            } catch (_: Exception) {
                closeTunnelSync()
                settingsRepository.setVpnEnabled(false)
                VpnStateHolder.updateStopped(error = VpnError.ESTABLISH_FAILED)
                withContext(Dispatchers.Main) {
                    stopForegroundCompat()
                    stopSelf()
                }
                return
            }

            if (newInterface == null) {
                closeTunnelSync()
                settingsRepository.setVpnEnabled(false)
                VpnStateHolder.updateStopped(error = VpnError.PERMISSION_REVOKED, revoked = true)
                withContext(Dispatchers.Main) {
                    stopForegroundCompat()
                    stopSelf()
                }
                return
            }

            val pipeFds = try {
                ParcelFileDescriptor.createPipe()
            } catch (_: IOException) {
                safeClose(newInterface)
                closeTunnelSync()
                settingsRepository.setVpnEnabled(false)
                VpnStateHolder.updateStopped(error = VpnError.ESTABLISH_FAILED)
                withContext(Dispatchers.Main) {
                    stopForegroundCompat()
                    stopSelf()
                }
                return
            }

            closeTunnelSync()
            tunInterface = newInterface
            stopPipeRead = pipeFds[0]
            stopPipeWrite = pipeFds[1]

            val newDrainJob = serviceScope.launch {
                drainPackets(newInterface, pipeFds[0])
            }
            drainJob = newDrainJob

            VpnStateHolder.updateRunning(actuallyAdded)
            settingsRepository.setVpnEnabled(true)

            withContext(Dispatchers.Main) {
                startForegroundCompat(buildNotification(actuallyAdded.size))
            }
        }
    }

    private fun drainPackets(
        tunPfd: ParcelFileDescriptor,
        wakeReadPfd: ParcelFileDescriptor
    ) {
        val tunFd = tunPfd.fileDescriptor
        val wakeFd = wakeReadPfd.fileDescriptor

        val tunPoll = StructPollfd().apply {
            fd = tunFd
            events = OsConstants.POLLIN.toShort()
        }
        val wakePoll = StructPollfd().apply {
            fd = wakeFd
            events = OsConstants.POLLIN.toShort()
        }
        val pollFds = arrayOf(tunPoll, wakePoll)
        val buffer = ByteArray(PACKET_BUFFER_SIZE)

        while (serviceScope.isActive) {
            tunPoll.revents = 0
            wakePoll.revents = 0

            val ready = try {
                Os.poll(pollFds, -1)
            } catch (e: ErrnoException) {
                if (e.errno == OsConstants.EINTR) continue
                break
            }

            if (ready <= 0) continue
            if (wakePoll.revents.toInt() != 0) break

            val tunEvents = tunPoll.revents.toInt()
            if ((tunEvents and (OsConstants.POLLERR or OsConstants.POLLHUP or OsConstants.POLLNVAL)) != 0) {
                break
            }

            if ((tunEvents and OsConstants.POLLIN) != 0) {
                try {
                    val bytesRead = Os.read(tunFd, buffer, 0, buffer.size)
                    if (bytesRead < 0) break
                    // Drop packet locally: never write or forward anywhere.
                } catch (e: ErrnoException) {
                    if (e.errno == OsConstants.EAGAIN ||
                        e.errno == OsConstants.EINTR
                    ) {
                        continue
                    }
                    break
                }
            }
        }
    }

    private fun closeTunnelSync() {
        stopPipeWrite?.let { writePfd ->
            try {
                FileOutputStream(writePfd.fileDescriptor).write(1)
            } catch (_: Exception) {
                // Pipe may already be closed.
            }
        }
        drainJob?.cancel()
        drainJob = null

        safeClose(stopPipeWrite)
        stopPipeWrite = null
        safeClose(stopPipeRead)
        stopPipeRead = null
        safeClose(tunInterface)
        tunInterface = null
    }

    private fun safeClose(pfd: ParcelFileDescriptor?) {
        try {
            pfd?.close()
        } catch (_: IOException) {
            // Ignore close errors.
        }
    }

    private fun ensureNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.deleteNotificationChannel(LEGACY_NOTIFICATION_CHANNEL_ID)
        val existing = manager.getNotificationChannel(NOTIFICATION_CHANNEL_ID)
        if (existing == null) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_MIN
            ).apply {
                description = getString(R.string.notification_channel_description)
                setShowBadge(false)
                setSound(null, null)
                enableVibration(false)
            }
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(blockedCount: Int): Notification {
        val contentText = if (blockedCount > 0) {
            resources.getQuantityString(R.plurals.notification_blocking_apps, blockedCount, blockedCount)
        } else {
            getString(R.string.notification_starting)
        }

        val launchIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentPendingIntent = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopPendingIntent = PendingIntent.getService(
            this,
            1,
            stopIntent(this),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_vpn_shield)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(contentText)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(contentPendingIntent)
            .addAction(
                R.drawable.ic_stop,
                getString(R.string.notification_action_stop),
                stopPendingIntent
            )
            .build()
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun stopForegroundCompat() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
    }

    companion object {
        const val ACTION_START = "com.appnetblocker.action.START_VPN"
        const val ACTION_STOP = "com.appnetblocker.action.STOP_VPN"
        const val ACTION_RELOAD = "com.appnetblocker.action.RELOAD_VPN"

        private const val LEGACY_NOTIFICATION_CHANNEL_ID = "app_net_blocker_vpn"
        private const val NOTIFICATION_CHANNEL_ID = "app_net_blocker_vpn_quiet"
        private const val NOTIFICATION_ID = 1001
        private const val VPN_MTU = 1500
        private const val PACKET_BUFFER_SIZE = 32767
        private const val IPV4_ADDRESS = "10.0.0.2"
        private const val IPV6_ADDRESS = "fd00::2"

        fun startIntent(context: Context): Intent =
            Intent(context, BlockerVpnService::class.java).apply { action = ACTION_START }

        fun stopIntent(context: Context): Intent =
            Intent(context, BlockerVpnService::class.java).apply { action = ACTION_STOP }

        fun reloadIntent(context: Context): Intent =
            Intent(context, BlockerVpnService::class.java).apply { action = ACTION_RELOAD }
    }
}
