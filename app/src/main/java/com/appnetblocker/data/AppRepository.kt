package com.appnetblocker.data

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Process
import android.os.UserManager
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

interface AppRepository {
    suspend fun getInstalledApps(): List<AppInfo>
    suspend fun loadAppIcon(packageName: String): Drawable?
    fun isRunningInSecondaryProfile(): Boolean
    fun hasOtherUserProfiles(): Boolean
}

class AndroidAppRepository(
    private val context: Context
) : AppRepository {

    private val packageManager: PackageManager = context.packageManager
    private val iconCache = LruCache<String, Drawable>(160)

    override suspend fun getInstalledApps(): List<AppInfo> = withContext(Dispatchers.IO) {
        val installedApps = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(0L))
        } else {
            @Suppress("DEPRECATION")
            packageManager.getInstalledApplications(0)
        }

        val otherProfilePackages = queryOtherProfilePackages()
        val ownPackage = context.packageName

        installedApps
            .asSequence()
            .filter { it.packageName != ownPackage }
            .map { appInfo ->
                val rawLabel = try {
                    packageManager.getApplicationLabel(appInfo).toString()
                } catch (_: Exception) {
                    appInfo.packageName
                }
                val label = rawLabel.ifBlank { appInfo.packageName }
                val isSystemApp = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                AppInfo(
                    packageName = appInfo.packageName,
                    label = label,
                    isSystemApp = isSystemApp,
                    isInWorkOrDualProfile = appInfo.packageName in otherProfilePackages
                )
            }
            .sortedBy { it.label.lowercase() }
            .toList()
    }

    override suspend fun loadAppIcon(packageName: String): Drawable? = withContext(Dispatchers.IO) {
        iconCache.get(packageName)?.let { cached ->
            return@withContext cached.constantState?.newDrawable(context.resources) ?: cached
        }
        val loaded = try {
            packageManager.getApplicationIcon(packageName)
        } catch (_: PackageManager.NameNotFoundException) {
            null
        } catch (_: Exception) {
            null
        }
        if (loaded != null) {
            iconCache.put(packageName, loaded)
            loaded.constantState?.newDrawable(context.resources) ?: loaded
        } else {
            null
        }
    }

    override fun isRunningInSecondaryProfile(): Boolean {
        val userManager = context.getSystemService(Context.USER_SERVICE) as? UserManager
        val nonPrimaryUid = (Process.myUid() / 100_000) != 0
        val managedProfile = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                userManager?.isManagedProfile == true
            } catch (_: Exception) {
                false
            }
        } else {
            false
        }
        return nonPrimaryUid || managedProfile
    }

    override fun hasOtherUserProfiles(): Boolean {
        val userManager = context.getSystemService(Context.USER_SERVICE) as? UserManager ?: return false
        return try {
            userManager.userProfiles.size > 1
        } catch (_: Exception) {
            false
        }
    }

    private fun queryOtherProfilePackages(): Set<String> {
        val userManager = context.getSystemService(Context.USER_SERVICE) as? UserManager ?: return emptySet()
        val launcherApps = context.getSystemService(Context.LAUNCHER_APPS_SERVICE) as? LauncherApps
            ?: return emptySet()
        val currentHandle = Process.myUserHandle()
        val result = mutableSetOf<String>()

        val profiles = try {
            userManager.userProfiles
        } catch (_: Exception) {
            emptyList()
        }

        for (profile in profiles) {
            if (profile == currentHandle) continue
            try {
                val activities = launcherApps.getActivityList(null, profile)
                for (activity in activities) {
                    result.add(activity.applicationInfo.packageName)
                }
            } catch (_: Exception) {
                // Restricted profiles on some OEMs may throw SecurityException; ignore gracefully.
            }
        }
        return result
    }
}
