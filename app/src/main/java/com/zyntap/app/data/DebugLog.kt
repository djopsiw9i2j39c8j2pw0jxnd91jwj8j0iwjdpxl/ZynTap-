package com.zyntap.app.data

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors

object DebugLog {

    enum class Level(val letter: String) { INFO("I"), WARN("W"), ERROR("E") }

    data class Entry(
        val time: Long,
        val level: Level,
        val tag: String,
        val msg: String,
        val count: Int = 1,
    )

    private const val MAX = 1000
    private const val FILE_NAME = "zyntap_debug.log"
    private const val FILE_MAX_BYTES = 200_000L
    private const val NL = '\u0001' 

    private val lock = Any()
    private val buf = ArrayList<Entry>(MAX + 8)
    private val listeners = CopyOnWriteArraySet<() -> Unit>()
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "zyn-debuglog").apply { isDaemon = true } }

    @Volatile private var file: File? = null
    @Volatile private var initialised = false
    @Volatile private var notifyPending = false
    @Volatile var enabled = false
        private set
    private var runtimePrefs: android.content.SharedPreferences? = null
    private val flushPending = java.util.concurrent.atomic.AtomicBoolean(false)
    private val flushTask = Runnable {
        if (enabled) io.execute {
            try { if (enabled) file?.let { f -> runCatching { persistSnapshot(f) } } }
            finally { flushPending.set(false) }
        } else flushPending.set(false)
    }
    fun setEnabled(value: Boolean) {
        if (enabled == value) return
        enabled = value
        runtimePrefs?.edit()?.putBoolean("debug_enabled", value)?.apply()
        if (!value) {
            main.removeCallbacks(flushTask)
            flushPending.set(false)
            synchronized(lock) { buf.clear() }
        }
        if (value) loadHistory()
        notifyChanged()
    }

    private val timeFmt get() = SimpleDateFormat("HH:mm:ss", Locale.US)
    private val fullFmt get() = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    fun init(context: Context) {
        if (initialised) return
        synchronized(lock) {
            if (initialised) return
            runtimePrefs = context.applicationContext.getSharedPreferences("zyntap_runtime", Context.MODE_PRIVATE)
            enabled = runtimePrefs!!.getBoolean("debug_enabled", false)
            file = File(context.applicationContext.filesDir, FILE_NAME)
            initialised = true
        }
        installCrashHandler()
        if (enabled) loadHistory()
        i("App", "Khởi động ZynTap · Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT}) · ${Build.MANUFACTURER} ${Build.MODEL}")
        
        if (enabled) io.execute { UpdateLog.check(context.applicationContext) }
    }

    private fun installCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            if (enabled) runCatching {
                val entry = Entry(System.currentTimeMillis(), Level.ERROR, "CRASH", "Luồng \"${t.name}\": ${describe(e, 18)}")
                synchronized(lock) { buf.add(entry); trim() }
                
                file?.let { f -> f.appendText(serialise(entry) + "\n") }
            }
            previous?.uncaughtException(t, e)
        }
    }

    fun i(tag: String, msg: String) = add(Level.INFO, tag, msg)
    fun w(tag: String, msg: String, t: Throwable? = null) { if (!enabled) return; add(Level.WARN, tag, if (t == null) msg else "$msg — ${describe(t, 6)}") }
    fun e(tag: String, msg: String, t: Throwable? = null) { if (!enabled) return; add(Level.ERROR, tag, if (t == null) msg else "$msg — ${describe(t, 10)}") }

    private fun add(level: Level, tag: String, msg: String) {
        if (!enabled) return
        synchronized(lock) {
            if (!enabled) return
            val last = buf.lastOrNull()
            if (last != null && last.level == level && last.tag == tag && last.msg == msg) {
                buf[buf.lastIndex] = last.copy(time = System.currentTimeMillis(), count = last.count + 1)
            } else {
                buf.add(Entry(System.currentTimeMillis(), level, tag.take(128), msg.take(4096)))
                trim()
            }
        }
        if (flushPending.compareAndSet(false, true)) main.postDelayed(flushTask, 1500L)
        notifyChanged()
    }

    private fun trim() {
        while (buf.size > MAX) buf.removeAt(0)
    }

    fun describe(t: Throwable, maxFrames: Int = 8): String {
        val sb = StringBuilder()
        var cur: Throwable? = t
        var depth = 0
        while (cur != null && depth < 3) {
            if (depth > 0) sb.append("\n  gây ra bởi: ")
            sb.append(cur.javaClass.name)
            cur.message?.let { sb.append(": ").append(it.take(300)) }
            val frames = cur.stackTrace
            val mine = frames.filter { it.className.startsWith("com.zyntap") }.take(maxFrames)
            (if (mine.isNotEmpty()) mine else frames.take(3)).forEach {
                sb.append("\n    at ").append(it.className.substringAfterLast('.')).append('.')
                    .append(it.methodName).append('(').append(it.fileName ?: "?").append(':').append(it.lineNumber).append(')')
            }
            val next = cur.cause
            cur = if (next === cur) null else next
            depth++
        }
        return sb.toString()
    }

    fun snapshot(errorsOnly: Boolean = false): List<Entry> = synchronized(lock) {
        if (errorsOnly) buf.filter { it.level != Level.INFO } else buf.toList()
    }

    fun errorCount(): Int = synchronized(lock) { buf.count { it.level == Level.ERROR } }

    fun clear() {
        synchronized(lock) { buf.clear() }
        io.execute { runCatching { file?.delete() } }
        notifyChanged()
    }

    fun line(e: Entry): String {
        val rep = if (e.count > 1) " ×${e.count}" else ""
        return "${timeFmt.format(Date(e.time))} ${e.level.letter}/${e.tag}$rep: ${e.msg}"
    }

    fun report(extraHeader: String, errorsOnly: Boolean): String {
        val list = snapshot(errorsOnly)
        val sb = StringBuilder()
        sb.append("=== ZynTap debug log · ").append(fullFmt.format(Date())).append(" ===\n")
        sb.append(extraHeader.trim()).append('\n')
        sb.append("Lọc: ").append(if (errorsOnly) "chỉ cảnh báo + lỗi" else "tất cả").append(" · ").append(list.size).append(" dòng\n")
        sb.append("---\n")
        if (list.isEmpty()) sb.append("(trống)\n") else list.forEach { sb.append(line(it)).append('\n') }
        return sb.toString()
    }

    fun addListener(l: () -> Unit) { if (enabled) listeners.add(l) }
    fun removeListener(l: () -> Unit) { listeners.remove(l) }

    private fun notifyChanged() {
        if (listeners.isEmpty() || notifyPending) return
        notifyPending = true
        main.postDelayed({
            notifyPending = false
            listeners.forEach { runCatching { it() } }
        }, 150L)
    }

    private fun serialise(e: Entry): String =
        "${e.time}\t${e.level.letter}\t${e.tag}\t${e.msg.replace('\n', NL)}"

    private fun loadHistory() {
        io.execute {
            if (!enabled) return@execute
            val rows = runCatching {
                val f = file ?: return@runCatching emptyList<Entry>()
                if (!f.exists()) return@runCatching emptyList<Entry>()
                java.io.RandomAccessFile(f, "r").use { input ->
                    val offset = (input.length() - FILE_MAX_BYTES).coerceAtLeast(0L)
                    input.seek(offset)
                    val bytes = ByteArray((input.length() - offset).toInt())
                    input.readFully(bytes)
                    bytes.toString(Charsets.UTF_8).lineSequence().drop(if (offset > 0L) 1 else 0).mapNotNull { row ->
                        val cells = row.split('\t', limit = 4)
                        if (cells.size < 4) null else {
                            val time = cells[0].toLongOrNull()
                            val level = Level.values().firstOrNull { it.letter == cells[1] }
                            if (time == null || level == null) null else Entry(time, level, cells[2].take(128), cells[3].replace(NL, '\n').take(4096))
                        }
                    }.toList().takeLast(MAX)
                }
            }.getOrDefault(emptyList())
            synchronized(lock) {
                if (!enabled) return@execute
                val known = buf.map { Triple(it.time, it.tag, it.msg) }.toHashSet()
                buf.addAll(0, rows.filter { Triple(it.time, it.tag, it.msg) !in known })
                trim()
            }
            notifyChanged()
        }
    }

    private fun persistSnapshot(f: File) {
        val rows = synchronized(lock) { buf.toList() }
        f.writeText(rows.joinToString("\n", postfix = "\n") { serialise(it) }.takeLast(FILE_MAX_BYTES.toInt()))
    }
}
