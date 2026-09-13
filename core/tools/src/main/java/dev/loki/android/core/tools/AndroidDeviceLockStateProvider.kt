package dev.loki.android.core.tools

import android.app.KeyguardManager
import android.content.Context

/**
 * Android platform implementation of [DeviceLockStateProvider] querying the system [KeyguardManager].
 *
 * If [KeyguardManager] is unavailable, it fails closed to [DeviceLockState.LOCKED].
 */
class AndroidDeviceLockStateProvider(
    private val context: Context
) : DeviceLockStateProvider {

    override fun currentState(): DeviceLockState {
        val keyguardManager = context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
            ?: return DeviceLockState.LOCKED

        return if (keyguardManager.isKeyguardLocked) {
            DeviceLockState.LOCKED
        } else {
            DeviceLockState.UNLOCKED
        }
    }
}
