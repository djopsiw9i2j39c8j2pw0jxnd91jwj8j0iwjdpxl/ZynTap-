package com.zyntap.app.overlay

internal class TargetAppState(private val transient: (String, String) -> Boolean) {
    private data class Activity(val pkg: String, val cls: String, val resumedAt: Long, var resumed: Boolean)
    private val visible = HashMap<String, Activity>()
    private val eventTimes = HashMap<String, Long>()
    private val lastTargetResume = HashMap<String, Long>()

    private fun accept(id: String, time: Long?): Boolean {
        if (time == null) return true 
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

        if (visible[id]?.resumed == false) visible.remove(id)
    }
    fun hasHistory(pkg: String): Boolean = lastTargetResume.containsKey(pkg)
    fun isResumed(pkg: String): Boolean = visible.values.any { it.pkg == pkg && it.resumed }

    fun leaveCandidate(pkg: String, startedAt: Long? = null): String? {
        val lastResume = lastTargetResume[pkg] ?: startedAt ?: return null
        val top = visible.values.filter { it.resumed }.maxByOrNull { it.resumedAt } ?: return null
        if (top.pkg == pkg) return null
        
        if (top.resumedAt <= lastResume || transient(top.pkg, top.cls)) return null
        return "${top.pkg}/${top.cls}@${top.resumedAt}"
    }
}
