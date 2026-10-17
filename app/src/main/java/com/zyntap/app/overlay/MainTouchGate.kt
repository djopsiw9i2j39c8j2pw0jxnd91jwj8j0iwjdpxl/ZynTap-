package com.zyntap.app.overlay

/** Physical gesture state. CANCEL never impersonates UP, especially in release mode. */
class MainTouchGate {
    enum class Action { NONE, ONCE, LOOP, STOP }
    private val pressed = mutableMapOf<Int, Int>()

    fun down(id: Int, mode: Int): Action {
        if (pressed.containsKey(id)) return Action.NONE
        pressed[id] = mode
        return when (mode) {
            MainButton.MODE_TAP -> Action.ONCE
            MainButton.MODE_HOLD -> Action.LOOP
            else -> Action.NONE
        }
    }

    fun up(id: Int): Action = when (pressed.remove(id)) {
        MainButton.MODE_HOLD -> Action.STOP
        MainButton.MODE_RELEASE -> Action.ONCE
        else -> Action.NONE
    }

    fun isPressed(id: Int) = pressed.containsKey(id)

    fun cancel(id: Int) { pressed.remove(id) }
    fun clear() { pressed.clear() }
}
