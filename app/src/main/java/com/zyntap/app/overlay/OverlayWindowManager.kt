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
    private val windows = Collections.newSetFromMap(IdentityHashMap<View, Boolean>())
    private class Desired(var params: ViewGroup.LayoutParams, var retryAt: Long) {
        var detachedSince: Long? = null
        var listener: View.OnAttachStateChangeListener? = null
        var recovery: Runnable? = null
    }
    
    private val desired = IdentityHashMap<View, Desired>()
    private var closed = false
    private var retry: Runnable? = null
    internal val pendingCount: Int get() = windows.size

    override fun addView(view: View, params: ViewGroup.LayoutParams) {
        check(!closed) { "Overlay owner is closed" }
        
        windows.add(view)
        val record = Desired(params, SystemClock.uptimeMillis() + 1500L)
        desired[view] = record
        val recovery = Runnable { if (!closed && desired[view] === record) recoverDetached() }
        record.recovery = recovery
        record.listener = object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {
                record.detachedSince = null
                main.removeCallbacks(recovery)
            }
            override fun onViewDetachedFromWindow(v: View) {
                if (closed || desired[view] !== record) return
                record.detachedSince = SystemClock.uptimeMillis()
                main.removeCallbacks(recovery)
                main.postDelayed(recovery, 1600L)
            }
        }
        view.addOnAttachStateChangeListener(record.listener)
        try {
            delegate.addView(view, params)
        } catch (t: Throwable) {
            if (!view.isAttachedToWindow && view.parent == null) {
                windows.remove(view)
                desired.remove(view)
                record.listener?.let { view.removeOnAttachStateChangeListener(it) }
                main.removeCallbacks(recovery)
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
        
        desired.remove(view)?.let { record ->
            record.listener?.let { view.removeOnAttachStateChangeListener(it) }
            record.recovery?.let { main.removeCallbacks(it) }
        }
        
        if (closed && !windows.contains(view)) return
        try {
            delegate.removeViewImmediate(view)
            windows.remove(view)
        } catch (t: Throwable) {
            if (!view.isAttachedToWindow && view.parent == null) windows.remove(view)
            
            throw t
        }
    }

    fun owns(view: View): Boolean = !closed && desired.containsKey(view)

    fun recoverDetached() {
        if (closed || !canRecover()) return
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

    fun close() {
        if (closed) return
        closed = true
        for ((view, record) in desired) {
            record.listener?.let { view.removeOnAttachStateChangeListener(it) }
            record.recovery?.let { main.removeCallbacks(it) }
        }
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

internal fun WindowManager.isAwaitingRecovery(view: View): Boolean =
    this is OverlayWindowManager && owns(view) && !view.isAttachedToWindow
