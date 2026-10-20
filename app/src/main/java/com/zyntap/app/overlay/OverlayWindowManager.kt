package com.zyntap.app.overlay

import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import com.zyntap.app.data.DebugLog
import java.util.Collections
import java.util.IdentityHashMap

/**
 * One owner for ALL windows of a service instance, including windows whose component lost its ref.
 * Main-thread only, like WindowManager/View. No polling, reflection or work on the rendering path.
 * Closing is terminal: a delayed callback cannot attach a new window to a dead service.
 */
internal class OverlayWindowManager(
    private val delegate: WindowManager,
    private val main: Handler = Handler(Looper.getMainLooper()),
) : WindowManager by delegate {
    private val windows = Collections.newSetFromMap(IdentityHashMap<View, Boolean>())
    private var closed = false
    private var retry: Runnable? = null
    internal val pendingCount: Int get() = windows.size

    override fun addView(view: View, params: ViewGroup.LayoutParams) {
        check(!closed) { "Overlay owner is closed" }
        // Register before add: even a partially successful attach must be cleaned up on shutdown.
        windows.add(view)
        try {
            delegate.addView(view, params)
        } catch (t: Throwable) {
            if (!view.isAttachedToWindow && view.parent == null) windows.remove(view)
            throw t
        }
    }

    override fun updateViewLayout(view: View, params: ViewGroup.LayoutParams) {
        if (closed) return
        delegate.updateViewLayout(view, params)
    }

    // All callers run on main. Immediate detach avoids a pending doDie racing with re-attachment.
    override fun removeView(view: View) = removeViewImmediate(view)

    override fun removeViewImmediate(view: View) {
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

    /** Hide first, then detach. Failed windows stay tracked; retry at most three times, only at exit. */
    fun close() {
        if (closed) return
        closed = true
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
