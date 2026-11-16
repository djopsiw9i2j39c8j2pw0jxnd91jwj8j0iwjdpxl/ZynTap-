package com.zyntap.app.overlay

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Process
import android.provider.Settings

object UsageAccess {

    fun granted(context: Context): Boolean {
        val ops = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        @Suppress("DEPRECATION")
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        } else {
            ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    fun settingsIntent(context: Context): Intent =
        Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun openSettings(context: Context) {
        runCatching { context.startActivity(settingsIntent(context)) }
    }

    class Tracker(private val context: Context) {
        private val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        private var since = 0L
        private var state = TargetAppState(::isTransient)
        private val consumed = HashSet<String>()
        private val recent = java.util.ArrayDeque<Pair<Long, String>>()

        fun poll() {
            val now = System.currentTimeMillis()
            if (since > now) {
                
                since = 0L
                state = TargetAppState(::isTransient)
                consumed.clear()
                recent.clear()
            }
            val from = if (since == 0L) now - 120_000L else since - 1500L
            val events = usm.queryEvents(from, now) ?: error("UsageEvents unavailable")
            val e = UsageEvents.Event()
            while (events.hasNextEvent()) {
                events.getNextEvent(e)
                val pkg = e.packageName ?: continue
                val cls = e.className.orEmpty()

                val id = "$pkg/$cls"
                val fingerprint = "${e.timeStamp}:${e.eventType}:$id"
                if (!consumed.add(fingerprint)) continue
                recent.addLast(e.timeStamp to fingerprint)
                when (e.eventType) {
                    UsageEvents.Event.ACTIVITY_RESUMED -> state.resume(pkg, cls, id, e.timeStamp)
                    UsageEvents.Event.ACTIVITY_PAUSED -> state.pause(id, e.timeStamp)
                    UsageEvents.Event.ACTIVITY_STOPPED -> state.stop(id, e.timeStamp)
                }
            }
            since = now
            
            while (recent.isNotEmpty() && (recent.peekFirst()?.first ?: Long.MAX_VALUE) < now - 5000L) {
                consumed.remove(recent.removeFirst().second)
            }
        }

        fun isTargetResumed(pkg: String): Boolean = state.isResumed(pkg)
        fun leaveCandidate(pkg: String, startedAt: Long? = null): String? = state.leaveCandidate(pkg, startedAt)
        fun hasLeft(pkg: String): Boolean = leaveCandidate(pkg) != null

        private fun isTransient(pkg: String, cls: String): Boolean {
            val activity = cls.lowercase(java.util.Locale.ROOT)

            val settingsHost = pkg == "com.android.settings" ||
                pkg == "com.samsung.android.settings" || pkg == "com.miui.securitycenter"
            val settingsPanel = settingsHost && listOf(
                "panel", "dialog", "wifi", "internet", "tether", "permission"
            ).any { it in activity }
            return settingsPanel || pkg == context.packageName || pkg == "android" ||
                pkg == "com.android.systemui" || pkg.endsWith(".systemui") ||
                pkg == "com.android.settings.intelligence" ||
                pkg.contains("inputmethod") || pkg.contains("keyboard") ||
                pkg.endsWith("permissioncontroller") ||
                pkg.endsWith("packageinstaller") ||
                pkg == "com.android.captiveportallogin" ||
                pkg == "com.google.android.networkstack.tethering" ||
                pkg == "com.android.networkstack" || pkg == "com.google.android.gms"
        }
    }
}
