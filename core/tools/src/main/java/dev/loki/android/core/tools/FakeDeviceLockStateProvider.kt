package dev.loki.android.core.tools

/**
 * Controllable [DeviceLockStateProvider] for unit and integration testing.
 */
class FakeDeviceLockStateProvider(
    private var state: DeviceLockState = DeviceLockState.UNLOCKED
) : DeviceLockStateProvider {

    override fun currentState(): DeviceLockState = state

    fun setState(newState: DeviceLockState) {
        state = newState
    }
}
