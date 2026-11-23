package com.appnetblocker.data

data class AppInfo(
    val packageName: String,
    val label: String,
    val isSystemApp: Boolean,
    val isInWorkOrDualProfile: Boolean = false
)
