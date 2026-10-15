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

/**
 * Nhật ký lỗi dùng chung cho TOÀN app (tab "Debug log" đọc từ đây).
 *
 * - Giữ [MAX] dòng gần nhất trong bộ nhớ, đồng thời ghi ra file để còn xem được sau khi app/dịch vụ bị tắt
 *   hoặc crash (mở lại là thấy dòng CRASH).
 * - Gọi từ luồng nào cũng được, không bao giờ ném lỗi ra ngoài.
 * - Dòng giống hệt dòng trước (cùng mức + nhãn + nội dung) được gộp lại thành "×N" để không tràn log.
 */
object DebugLog {

    enum class Level(val letter: String) { INFO("I"), WARN("W"), ERROR("E") }

    data class Entry(
        val time: Long,
        val level: Level,
        val tag: String,
        val msg: String,
        val count: Int = 1,
    )

    private const val MAX = 400
    private const val FILE_NAME = "zyntap_debug.log"
    private const val FILE_MAX_BYTES = 200_000L
    private const val NL = '\u0001' // thay cho xuống dòng khi ghi file (mỗi entry đúng 1 dòng)

    private val lock = Any()
    private val buf = ArrayList<Entry>(MAX + 8)
    private val listeners = CopyOnWriteArraySet<() -> Unit>()
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "zyn-debuglog").apply { isDaemon = true } }

    @Volatile private var file: File? = null
    @Volatile private var initialised = false
    @Volatile private var notifyPending = false
    private var fileWrites = 0

    private val timeFmt get() = SimpleDateFormat("HH:mm:ss", Locale.US)
    private val fullFmt get() = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    // ---- khởi tạo ----------------------------------------------------------

    /** Gọi sớm (MainActivity / OverlayService). Gọi nhiều lần vẫn an toàn. */
    fun init(context: Context) {
        if (initialised) return
        synchronized(lock) {
            if (initialised) return
            val f = File(context.applicationContext.filesDir, FILE_NAME)
            file = f
            // Nạp lại log của lần chạy trước (nằm TRƯỚC các dòng mới ghi trong bộ nhớ)
            val old = runCatching { loadFile(f) }.getOrDefault(emptyList())
            if (old.isNotEmpty()) {
                buf.addAll(0, old)
                while (buf.size > MAX) buf.removeAt(0)
            }
            initialised = true
        }
        installCrashHandler()
        i("App", "Khởi động ZynTap · Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT}) · ${Build.MANUFACTURER} ${Build.MODEL}")
    }

    private fun installCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching {
                val entry = Entry(System.currentTimeMillis(), Level.ERROR, "CRASH", "Luồng \"${t.name}\": ${describe(e, 18)}")
                synchronized(lock) { buf.add(entry); trim() }
                // Tiến trình sắp chết -> ghi đồng bộ, không qua executor
                file?.let { f -> f.appendText(serialise(entry) + "\n") }
            }
            previous?.uncaughtException(t, e)
        }
    }

    // ---- ghi log -----------------------------------------------------------

    fun i(tag: String, msg: String) = add(Level.INFO, tag, msg)
    fun w(tag: String, msg: String, t: Throwable? = null) = add(Level.WARN, tag, if (t == null) msg else "$msg — ${describe(t, 6)}")
    fun e(tag: String, msg: String, t: Throwable? = null) = add(Level.ERROR, tag, if (t == null) msg else "$msg — ${describe(t, 10)}")

    private fun add(level: Level, tag: String, msg: String) {
        runCatching {
            val now = System.currentTimeMillis()
            var written: Entry? = null
            synchronized(lock) {
                val last = buf.lastOrNull()
                if (last != null && last.level == level && last.tag == tag && last.msg == msg) {
                    buf[buf.lastIndex] = last.copy(count = last.count + 1)
                    // Không ghi file cho dòng lặp, tránh phình file
                } else {
                    val e = Entry(now, level, tag, msg)
                    buf.add(e)
                    trim()
                    written = e
                }
            }
            written?.let { persist(it) }
            val prio = when (level) {
                Level.INFO -> android.util.Log.INFO
                Level.WARN -> android.util.Log.WARN
                Level.ERROR -> android.util.Log.ERROR
            }
            android.util.Log.println(prio, "ZynTap/$tag", msg)
            notifyChanged()
        }
    }

    private fun trim() {
        while (buf.size > MAX) buf.removeAt(0)
    }

    /** Rút gọn lỗi: loại + nội dung + vài dòng stack thuộc app (com.zyntap) để biết lỗi nằm ở đâu. */
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

    // ---- đọc / xoá ---------------------------------------------------------

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

    /** Toàn bộ log dạng chữ + thông tin máy, để dán cho người sửa lỗi. */
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

    // ---- thông báo thay đổi (gộp, chạy ở luồng chính) -------------------------

    fun addListener(l: () -> Unit) { listeners.add(l) }
    fun removeListener(l: () -> Unit) { listeners.remove(l) }

    private fun notifyChanged() {
        if (listeners.isEmpty() || notifyPending) return
        notifyPending = true
        main.postDelayed({
            notifyPending = false
            listeners.forEach { runCatching { it() } }
        }, 150L)
    }

    // ---- file --------------------------------------------------------------

    private fun serialise(e: Entry): String =
        "${e.time}\t${e.level.letter}\t${e.tag}\t${e.msg.replace('\n', NL)}"

    private fun persist(e: Entry) {
        val f = file ?: return
        io.execute {
            runCatching {
                f.appendText(serialise(e) + "\n")
                // Thỉnh thoảng kiểm tra dung lượng, quá lớn thì cắt còn phần cuối
                if (++fileWrites % 50 == 0 && f.length() > FILE_MAX_BYTES) {
                    val keep = synchronized(lock) { buf.toList() }
                    f.writeText(keep.joinToString("\n", postfix = "\n") { serialise(it) })
                }
            }
        }
    }

    private fun loadFile(f: File): List<Entry> {
        if (!f.exists()) return emptyList()
        val out = ArrayList<Entry>()
        f.useLines { lines ->
            lines.forEach { ln ->
                val p = ln.split('\t', limit = 4)
                if (p.size < 4) return@forEach
                val time = p[0].toLongOrNull() ?: return@forEach
                val lv = Level.values().firstOrNull { it.letter == p[1] } ?: return@forEach
                out.add(Entry(time, lv, p[2], p[3].replace(NL, '\n')))
            }
        }
        return if (out.size > MAX) out.subList(out.size - MAX, out.size).toList() else out
    }
}
