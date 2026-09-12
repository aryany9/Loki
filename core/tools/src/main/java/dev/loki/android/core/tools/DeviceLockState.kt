package dev.loki.android.core.tools

/**
 * Represents whether the host device keyguard is engaged or dismissed.
 */
enum class DeviceLockState {
    LOCKED,
    UNLOCKED
}

/**
 * Contract for querying the real-time device lock state.
 */
fun interface DeviceLockStateProvider {
    fun currentState(): DeviceLockState
}
