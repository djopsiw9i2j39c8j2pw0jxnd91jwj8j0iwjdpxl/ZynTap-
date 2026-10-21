package com.zyntap.app.overlay

/** Pure state reducer: PAUSED is still visible; only a STOP following PAUSE removes an activity.
 * Per-activity-class state matters when a game starts B before stopping A, or shows a dialog.
 * No Android/UI-thread dependency, so lifecycle sequences can be regression-tested on host.
 */
internal class TargetAppState(private val transient: (String, String) -> Boolean) {
    private data class Activity(val pkg: String, val cls: String, val resumedAt: Long, var resumed: Boolean)
    private val visible = HashMap<String, Activity>()
    private val eventTimes = HashMap<String, Long>()
    private val lastTargetResume = HashMap<String, Long>()

    private fun accept(id: String, time: Long?): Boolean {
        if (time == null) return true // Convenient for synthetic lifecycle-only host tests.
        if (time < (eventTimes[id] ?: Long.MIN_VALUE)) return false
        eventTimes[id] = time
        return true
    }

    fun resume(pkg: String, cls: String, id: String, time: Long) {
        if (!accept(id, time)) return
        visible[id] = Activity(pkg, cls, time, true)
        lastTargetResume[pkg] = maxOf(lastTargetResume[pkg] ?: Long.MIN_VALUE, time)
    }

    fun pause(id: String, time: Long? = null) {
        if (accept(id, time)) visible[id]?.resumed = false
    }
    fun stop(id: String, time: Long? = null) {
        if (!accept(id, time)) return
        // Public UsageEvents has no instance ID: A and B can share a class name.
        // Ignore an old A.STOP delivered after B.RESUME. Prefer retaining UI over
        // destructive shutdown when OEM lifecycle evidence is incomplete.
        if (visible[id]?.resumed == false) visible.remove(id)
    }
    fun hasHistory(pkg: String): Boolean = lastTargetResume.containsKey(pkg)
    fun isResumed(pkg: String): Boolean = visible.values.any { it.pkg == pkg && it.resumed }

    /** Identity of the latest real replacement, or null when evidence of exit is insufficient. */
    fun leaveCandidate(pkg: String): String? {
        if (visible.values.any { it.pkg == pkg }) return null
        val lastResume = lastTargetResume[pkg] ?: return null
        val top = visible.values.filter { it.resumed }.maxByOrNull { it.resumedAt } ?: return null
        // A historical launcher/app RESUMED record is not evidence of a NEW departure.
        if (top.resumedAt <= lastResume || transient(top.pkg, top.cls)) return null
        return "${top.pkg}/${top.cls}@${top.resumedAt}"
    }
}
