package com.zyntap.app.overlay

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import com.zyntap.app.data.DebugLog
import java.util.Collections
import java.util.IdentityHashMap

/**
 * One owner for ALL windows of a service instance, including windows whose component lost its ref.
 * Main-thread only, like WindowManager/View. Uses the service watchdog, no extra timer/reflection.
 * Closing is terminal: a delayed callback cannot attach a new window to a dead service.
 */
internal class OverlayWindowManager(
    private val delegate: WindowManager,
    private val main: Handler = Handler(Looper.getMainLooper()),
) : WindowManager by delegate {
    private val windows = Collections.newSetFromMap(IdentityHashMap<View, Boolean>())
    private class Desired(var params: ViewGroup.LayoutParams, var retryAt: Long) {
        var detachedSince: Long? = null
    }
    // Intent, not focus/alpha, decides whether a window belongs on screen.
    private val desired = IdentityHashMap<View, Desired>()
    private var closed = false
    private var retry: Runnable? = null
    internal val pendingCount: Int get() = windows.size

    override fun addView(view: View, params: ViewGroup.LayoutParams) {
        check(!closed) { "Overlay owner is closed" }
        // Register before add: even a partially successful attach must be cleaned up on shutdown.
        windows.add(view)
        desired[view] = Desired(params, SystemClock.uptimeMillis() + 1500L)
        try {
            delegate.addView(view, params)
        } catch (t: Throwable) {
            if (!view.isAttachedToWindow && view.parent == null) {
                windows.remove(view)
                desired.remove(view)
            }
            throw t
        }
    }

    override fun updateViewLayout(view: View, params: ViewGroup.LayoutParams) {
        if (closed) return
        desired[view]?.params = params
        delegate.updateViewLayout(view, params)
    }

    // All callers run on main. Immediate detach avoids a pending doDie racing with re-attachment.
    override fun removeView(view: View) = removeViewImmediate(view)

    override fun removeViewImmediate(view: View) {
        // An intentional removal must NEVER be undone, even if the OEM detach throws.
        desired.remove(view)
        // Component cleanup after close() can safely remove the same window again.
        if (closed && !windows.contains(view)) return
        try {
            delegate.removeViewImmediate(view)
            windows.remove(view)
        } catch (t: Throwable) {
            if (!view.isAttachedToWindow && view.parent == null) windows.remove(view)
            // Keep the record on real failure; component code may swallow this exception.
            throw t
        }
    }

    /** True while this owner retains the original view and will retry a lost attachment. */
    fun owns(view: View): Boolean = !closed && desired.containsKey(view)

    /**
     * Called on main by the service watchdog. No focus, alpha, frame-rate or touch heuristics.
     * A stalled UI thread is not evidence of loss. Give a newly observed detach a full grace
     * period, then reattach the SAME view/params (including menu, popup, text and picker state).
     */
    fun recoverDetached() {
        if (closed) return
        val now = SystemClock.uptimeMillis()
        for ((view, record) in desired.entries.toList()) {
            if (closed || desired[view] !== record) continue
            if (view.isAttachedToWindow) {
                record.detachedSince = null
                continue
            }
            val since = record.detachedSince
            if (since == null) { record.detachedSince = now; continue }
            if (now - since < 1500L || now < record.retryAt) continue
            record.retryAt = now + 2000L
            try {
                // Remove a stale ViewRoot before adding; never layer a duplicate on top.
                try { delegate.removeViewImmediate(view) } catch (t: Throwable) {
                    if (view.isAttachedToWindow || view.parent != null) throw t
                }
                delegate.addView(view, record.params)
                record.detachedSince = null
                view.requestLayout()
                view.invalidate()
                DebugLog.i("Khôi phục", "Đã gắn lại cửa sổ nổi bị mất")
            } catch (t: Throwable) {
                DebugLog.e("Khôi phục", "Chưa gắn lại được cửa sổ; sẽ thử ở nhịp sau", t)
            }
        }
    }

    /** Hide first, then detach. Failed windows stay tracked; retry at most three times, only at exit. */
    fun close() {
        if (closed) return
        closed = true
        desired.clear()
        detachRemaining()
        if (windows.isEmpty()) return
        var attempts = 0
        val task = object : Runnable {
            override fun run() {
                detachRemaining()
                attempts++
                if (windows.isNotEmpty() && attempts < 3) {
                    main.postDelayed(this, 120L)
                } else {
                    retry = null
                    if (windows.isNotEmpty()) {
                        DebugLog.e("Dọn dẹp", "Còn ${windows.size} cửa sổ chưa gỡ được; đã hết lượt thử")
                    }
                }
            }
        }
        retry = task
        main.postDelayed(task, 120L)
    }

    private fun detachRemaining() {
        for (v in windows.toList()) {
            runCatching { v.animate().setListener(null).withEndAction(null).cancel() }
            runCatching { v.alpha = 0f; v.visibility = View.GONE }
            runCatching { removeViewImmediate(v) }
                .onFailure { DebugLog.e("Dọn dẹp", "WindowManager từ chối gỡ cửa sổ", it) }
        }
        if (windows.isEmpty()) {
            retry?.let { main.removeCallbacks(it) }
            retry = null
        }
    }
}

/** Components must not discard a view while its service owner is recovering it. */
internal fun WindowManager.isAwaitingRecovery(view: View): Boolean =
    this is OverlayWindowManager && owns(view) && !view.isAttachedToWindow
