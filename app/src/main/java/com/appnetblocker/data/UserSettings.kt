package com.appnetblocker.data

data class UserSettings(
    val selectedPackages: Set<String> = emptySet(),
    val vpnEnabled: Boolean = false,
    val autoStartOnBoot: Boolean = false,
    val showSystemApps: Boolean = false,
    val hasSeenVpnExplanation: Boolean = false
)
