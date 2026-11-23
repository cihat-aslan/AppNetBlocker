package com.appnetblocker.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class VpnError {
    NO_APPS_SELECTED,
    PERMISSION_REVOKED,
    ESTABLISH_FAILED,
    BOOT_START_RESTRICTED
}

data class VpnServiceState(
    val isRunning: Boolean = false,
    val blockedPackages: Set<String> = emptySet(),
    val lastError: VpnError? = null,
    val wasRevoked: Boolean = false
)

object VpnStateHolder {
    private val _state = MutableStateFlow(VpnServiceState())
    val state: StateFlow<VpnServiceState> = _state.asStateFlow()

    fun updateRunning(packages: Set<String>) {
        _state.value = VpnServiceState(
            isRunning = true,
            blockedPackages = packages.toSet(),
            lastError = null,
            wasRevoked = false
        )
    }

    fun updateStopped(error: VpnError? = null, revoked: Boolean = false) {
        _state.value = VpnServiceState(
            isRunning = false,
            blockedPackages = emptySet(),
            lastError = error,
            wasRevoked = revoked
        )
    }

    fun clearError() {
        val current = _state.value
        _state.value = current.copy(lastError = null, wasRevoked = false)
    }

    fun resetForTesting() {
        _state.value = VpnServiceState()
    }
}
