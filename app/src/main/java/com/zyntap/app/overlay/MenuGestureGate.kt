package com.zyntap.app.overlay

internal class MenuGestureGate {
    private var blockedUntil = 0L
    var tracking = false
        private set
    fun down(now: Long, busy: Boolean): Boolean {
        tracking = !busy && now >= blockedUntil
        return tracking
    }
    fun blockUntil(time: Long) {
        blockedUntil = maxOf(blockedUntil, time)
        tracking = false
    }
    fun finish() { tracking = false }
}
