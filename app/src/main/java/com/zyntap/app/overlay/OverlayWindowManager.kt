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

internal class OverlayWindowManager(
    private val delegate: WindowManager,
    private val main: Handler = Handler(Looper.getMainLooper()),
    private val canRecover: () -> Boolean = { true },
) : WindowManager by delegate {
    private class Desired(var params: ViewGroup.LayoutParams, var graceUntil: Long) {
        var badTicks = 0
        var retryAt = 0L
        var listener: View.OnAttachStateChangeListener? = null
    }

    private val windows = Collections.newSetFromMap(IdentityHashMap<View, Boolean>())
    private val desired = IdentityHashMap<View, Desired>()
    private val sweep = Runnable { recoverDetached() }
    private var closed = false
    private var retry: Runnable? = null
    internal val pendingCount: Int get() = windows.size

    override fun addView(view: View, params: ViewGroup.LayoutParams) {
        check(!closed) { "Overlay owner is closed" }
        windows.add(view)
        val record = Desired(params, SystemClock.uptimeMillis() + GRACE_MS)
        record.listener = object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {
                record.badTicks = 0
            }

            override fun onViewDetachedFromWindow(v: View) {
                if (desired[view] === record) requestSweep(DETACH_SWEEP_MS)
            }
        }
        desired[view] = record
        view.addOnAttachStateChangeListener(record.listener)
        try {
            delegate.addView(view, params)
        } catch (t: Throwable) {
            if (!view.isAttachedToWindow && view.parent == null) {
                windows.remove(view)
                forget(view)
            }
            throw t
        }
    }

    override fun updateViewLayout(view: View, params: ViewGroup.LayoutParams) {
        if (closed) return
        desired[view]?.params = params
        delegate.updateViewLayout(view, params)
    }

    override fun removeView(view: View) = removeViewImmediate(view)

    override fun removeViewImmediate(view: View) {
        forget(view)
        if (closed && !windows.contains(view)) return
        try {
            delegate.removeViewImmediate(view)
            windows.remove(view)
        } catch (t: Throwable) {
            if (!view.isAttachedToWindow && view.parent == null) windows.remove(view)
            throw t
        }
    }

    private fun forget(view: View) {
        desired.remove(view)?.listener?.let { view.removeOnAttachStateChangeListener(it) }
    }

    fun owns(view: View): Boolean = !closed && desired.containsKey(view)

    fun requestSweep(delayMs: Long = 0L) {
        if (closed) return
        main.removeCallbacks(sweep)
        main.postDelayed(sweep, delayMs)
    }

    fun recoverDetached() {
        if (closed) return
        val now = SystemClock.uptimeMillis()
        var revived = false
        var waiting = false
        for ((view, record) in desired.entries.toList()) {
            if (closed || desired[view] !== record) continue
            if (view.isAttachedToWindow && view.windowVisibility == View.VISIBLE) {
                record.badTicks = 0
                continue
            }
            if (now < record.graceUntil) {
                waiting = true
                continue
            }
            record.badTicks++
            if (now < record.retryAt) continue
            if (revived || !canRecover()) {
                waiting = true
                continue
            }
            revived = true
            revive(view, record, now)
        }
        if (revived && waiting) requestSweep(STAGGER_MS)
    }

    private fun revive(view: View, record: Desired, now: Long) {
        try {
            if (view.isAttachedToWindow && record.badTicks < 2) {
                delegate.updateViewLayout(view, record.params)
                return
            }
            runCatching { delegate.removeViewImmediate(view) }
            record.graceUntil = now + GRACE_MS
            record.badTicks = 0
            delegate.addView(view, record.params)
            DebugLog.i("Khôi phục", "Đã gắn lại cửa sổ nổi bị mất")
        } catch (t: Throwable) {
            record.retryAt = now + RETRY_MS
            DebugLog.e("Khôi phục", "Chưa gắn lại được cửa sổ; sẽ thử lại", t)
        }
    }

    fun close() {
        if (closed) return
        closed = true
        main.removeCallbacks(sweep)
        desired.entries.toList().forEach { (view, _) -> forget(view) }
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

    private companion object {
        const val GRACE_MS = 2000L
        const val RETRY_MS = 3000L
        const val STAGGER_MS = 120L
        const val DETACH_SWEEP_MS = 250L
    }
}

internal fun WindowManager.isAwaitingRecovery(view: View): Boolean =
    this is OverlayWindowManager && owns(view) && !view.isAttachedToWindow
