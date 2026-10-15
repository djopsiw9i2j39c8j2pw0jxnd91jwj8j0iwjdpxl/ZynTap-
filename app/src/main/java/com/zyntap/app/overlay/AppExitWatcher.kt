package com.zyntap.app.overlay

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import com.zyntap.app.data.AdbSession
import com.zyntap.app.data.ZynAdbConnectionManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** App nằm trên cùng, theo góc nhìn của watcher. */
enum class Foreground {
    /** Chính app đã mở qua ZynTap. */
    TARGET,
    /** Màn hình chính (launcher mặc định), không phải màn hình đa nhiệm. */
    HOME,
    /** Màn hình đa nhiệm / System UI: người dùng đang chuyển app -> chưa kết luận gì. */
    RECENTS,
    /** Chính ZynTap (đang chỉnh menu...) -> KHÔNG coi là thoát. */
    OWN,
    /** App khác đang ở trên cùng. Giữ đủ lâu thì coi là đã rời app. */
    OTHER,
    /** Không đọc được / không hiểu kết quả -> tạm dừng, KHÔNG đoán. */
    UNKNOWN,
}

/** Tiến trình của app còn sống không. */
enum class Alive { YES, NO, UNKNOWN }

data class ResumedComponent(val pkg: String, val cls: String)

/** Phần thuần chữ (không dính Android) để đọc đầu ra `dumpsys` và phân loại. */
object ForegroundParser {
    // "  topResumedActivity=ActivityRecord{..}", "  mResumedActivity: ActivityRecord{..}", "  ResumedActivity: ..."
    private val resumedLine =
        Regex("^\\s*m?(?:top)?resumedactivity\\s*[:=](.*)$", RegexOption.IGNORE_CASE)
    private val record =
        Regex("ActivityRecord\\{\\S+\\s+u-?\\d+\\s+([\\w.]+)/([\\w.\\$]+)")

    fun parseResumed(text: String): List<ResumedComponent> {
        val out = LinkedHashSet<ResumedComponent>()
        for (line in text.lineSequence()) {
            val rest = resumedLine.find(line)?.groupValues?.get(1) ?: continue
            val m = record.find(rest) ?: continue
            out += ResumedComponent(m.groupValues[1], m.groupValues[2])
        }
        return out.toList()
    }

    /** Dự phòng: `dumpsys window | grep mFocusedApp`. */
    fun parseFocusedApp(text: String): List<ResumedComponent> {
        val out = LinkedHashSet<ResumedComponent>()
        for (line in text.lineSequence()) {
            if (!line.contains("mFocusedApp", ignoreCase = true)) continue
            val m = record.find(line) ?: continue
            out += ResumedComponent(m.groupValues[1], m.groupValues[2])
        }
        return out.toList()
    }

    private val windowRecord =
        Regex("Window\\{\\S+\\s+u-?\\d+\\s+([\\w.]+)/([\\w.\\$]+)")

    /** Dự phòng thứ 2: `dumpsys window | grep mCurrentFocus` ("mCurrentFocus=Window{.. u0 pkg/cls}"). */
    fun parseCurrentFocus(text: String): List<ResumedComponent> {
        val out = LinkedHashSet<ResumedComponent>()
        for (line in text.lineSequence()) {
            if (!line.contains("mCurrentFocus", ignoreCase = true)) continue
            val m = windowRecord.find(line) ?: continue
            out += ResumedComponent(m.groupValues[1], m.groupValues[2])
        }
        return out.toList()
    }

    fun classify(
        items: List<ResumedComponent>,
        target: String,
        homePkgs: Set<String>,
        own: String = "",
    ): Foreground {
        if (items.isEmpty()) return Foreground.UNKNOWN
        if (items.any { it.pkg == target }) return Foreground.TARGET
        // Màn hình đa nhiệm cũng do launcher vẽ (RecentsActivity) -> không tính là "về màn hình chính"
        if (items.any { it.cls.contains("recents", ignoreCase = true) }) return Foreground.RECENTS
        if (own.isNotEmpty() && items.any { it.pkg == own }) return Foreground.OWN
        if (items.all { it.pkg in homePkgs }) return Foreground.HOME
        if (items.all { it.pkg == "com.android.systemui" }) return Foreground.RECENTS
        return Foreground.OTHER
    }

    /** Ghép 2 phép đo "pidof" và "ps" thành 1 kết luận; chỉ NO khi cả hai cùng khẳng định không còn. */
    fun parsePidof(out: String): Alive {
        val toks = out.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        return when {
            toks.isEmpty() -> Alive.NO
            toks.all { t -> t.all(Char::isDigit) } -> Alive.YES
            else -> Alive.UNKNOWN // lỗi chữ kiểu "pidof: not found"
        }
    }

    fun parsePs(out: String, pkg: String): Alive {
        val lines = out.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        if (lines.none { it.equals("NAME", ignoreCase = true) }) return Alive.UNKNOWN // đầu ra không đáng tin
        return if (lines.any { it == pkg || it.startsWith("$pkg:") }) Alive.YES else Alive.NO
    }
}

/**
 * Máy trạng thái quyết định khi nào coi là "đã thoát app". Thuần Kotlin, thời gian truyền vào -> test được.
 *
 * Chỉ đóng khi (đã từng thấy app ở trên cùng = armed):
 *  1. Về MÀN HÌNH CHÍNH liên tục [Limits.homeMs], hoặc
 *  2. Một app KHÁC (không phải ZynTap) ở trên cùng liên tục [Limits.otherMs], hoặc
 *  3. Tiến trình app biến mất liên tục [Limits.deadMs] (vuốt tắt / bị kill / force stop), hoặc
 *  4. Mở app mà mãi không thấy app lên (mở lỗi) sau [Limits.launchMs].
 *
 * Không coi là thoát: đang load game (app chưa lên trên cùng), kéo thanh thông báo, bàn phím, màn hình đa nhiệm,
 * chính ZynTap, tắt màn hình / khoá máy, mất kết nối ADB, đọc lỗi.
 */
class ExitTracker(
    private val startedAt: Long,
    private val limits: Limits = Limits(),
    startArmed: Boolean = false,
) {
    data class Limits(
        val homeMs: Long = 1_500,
        val otherMs: Long = 5_000,
        val deadMs: Long = 3_000,
        val launchMs: Long = 45_000,
        val launchAliveMs: Long = 180_000,
    )

    /** startArmed: theo dõi lại app đã chạy từ trước (service bị dựng lại) -> không cần chờ thấy app lên trên cùng. */
    var armed = startArmed
        private set
    private var homeSince = NONE
    private var otherSince = NONE
    private var deadSince = NONE

    /** Không đo được tin cậy (tắt màn hình, mất ADB...): xoá đồng hồ đếm, KHÔNG tính là thoát. */
    fun pause() {
        homeSince = NONE
        otherSince = NONE
        deadSince = NONE
    }

    /** @return true = nên đóng menu nổi. */
    fun onSample(now: Long, fg: Foreground, alive: Alive): Boolean {
        when (fg) {
            Foreground.TARGET -> {
                armed = true
                homeSince = NONE
                otherSince = NONE
                deadSince = NONE
                return false
            }
            Foreground.UNKNOWN -> {
                pause()
                return false
            }
            Foreground.HOME -> {
                otherSince = NONE
                if (!armed) homeSince = NONE
                else if (homeSince == NONE) homeSince = now
            }
            Foreground.OTHER -> {
                homeSince = NONE
                if (!armed) otherSince = NONE
                else if (otherSince == NONE) otherSince = now
            }
            Foreground.RECENTS, Foreground.OWN -> {
                homeSince = NONE
                otherSince = NONE
            }
        }
        if (armed) {
            if (homeSince != NONE && now - homeSince >= limits.homeMs) return true
            if (otherSince != NONE && now - otherSince >= limits.otherMs) return true
            if (alive == Alive.NO) {
                if (deadSince == NONE) deadSince = now
                if (now - deadSince >= limits.deadMs) return true
            } else {
                deadSince = NONE
            }
        } else {
            val limit = if (alive == Alive.YES) limits.launchAliveMs else limits.launchMs
            if (now - startedAt >= limit) return true
        }
        return false
    }

    private companion object {
        const val NONE = -1L
    }
}

/**
 * Theo dõi app đã mở qua ZynTap bằng ADB (quyền shell, dùng lại kết nối có sẵn) và gọi [onExit]
 * (trên luồng chính) khi người dùng thật sự thoát app đó. Không có ADB thì KHÔNG làm gì
 * (không có cách nào tin cậy để biết app nào đang ở trên cùng) -> menu nổi giữ nguyên như cũ.
 */
class AppExitWatcher(context: Context, private val onExit: () -> Unit) {

    private val app = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val main = Handler(Looper.getMainLooper())
    private var job: Job? = null
    private var gen = 0

    /** App đang được theo dõi (null = không theo dõi). */
    @Volatile
    var target: String? = null
        private set

    /** Vòng theo dõi còn chạy không (để service tự dựng lại nếu nó chết lặng lẽ). */
    val isRunning: Boolean get() = job?.isActive == true

    /**
     * Bắt đầu (hoặc bắt đầu lại, cho thêm thời gian mở app) theo dõi [pkg]. Gọi từ luồng chính.
     * [resume] = true: app đã chạy từ trước (service bị dựng lại) -> coi như đã thấy app, không chờ nó lên trên cùng.
     */
    fun watch(pkg: String, resume: Boolean = false) {
        job?.cancel()
        job = null
        if (!PKG.matches(pkg)) { // chỉ nhận tên gói hợp lệ vì tên này đi vào lệnh shell
            target = null
            return
        }
        target = pkg
        val myGen = ++gen
        job = scope.launch { loop(pkg, myGen, resume) }
    }

    fun stop() {
        job?.cancel()
        job = null
        target = null
        gen++
    }

    fun close() {
        stop()
        scope.cancel()
    }

    private suspend fun loop(pkg: String, myGen: Int, resume: Boolean) {
        val tracker = ExitTracker(SystemClock.elapsedRealtime(), startArmed = resume)
        var homePkgs = homePackages()
        var homeCheckedAt = SystemClock.elapsedRealtime()
        while (currentCoroutineContext().isActive) {
            delay(POLL_MS)
            val exit = try {
                val t0 = SystemClock.elapsedRealtime()
                if (t0 - homeCheckedAt > HOME_REFRESH_MS) { // người dùng có thể đổi launcher
                    homePkgs = homePackages()
                    homeCheckedAt = t0
                }
                sample(tracker, pkg, homePkgs)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                tracker.pause() // lỗi bất ngờ: bỏ qua nhịp này, KHÔNG để vòng theo dõi chết
                false
            }
            if (exit) {
                main.post {
                    if (gen == myGen && target == pkg) {
                        target = null
                        onExit()
                    }
                }
                return
            }
        }
    }

    /** Một nhịp đo. @return true = nên đóng menu nổi. */
    private suspend fun sample(tracker: ExitTracker, pkg: String, homePkgs: Set<String>): Boolean {
        if (!screenUsable()) { tracker.pause(); return false }
        val manager = ZynAdbConnectionManager.get(app)
        if (!manager.isConnected) {
            AdbSession.ensureInBackground(app)
            tracker.pause()
            return false
        }
        val fg = foreground(manager, pkg, homePkgs)
        val alive = if (fg == Foreground.TARGET) Alive.YES else processAlive(manager, pkg)
        return tracker.onSample(SystemClock.elapsedRealtime(), fg, alive)
    }

    private fun screenUsable(): Boolean {
        val pm = app.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val km = app.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        return (pm?.isInteractive ?: true) && !(km?.isKeyguardLocked ?: false)
    }

    /** Launcher MẶC ĐỊNH (không lấy mọi app có category HOME: Settings cũng có, sẽ nhận nhầm). */
    private fun homePackages(): Set<String> {
        val pm = app.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val def = runCatching {
            pm.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName
        }.getOrNull()
        if (def != null && def != "android") return setOf(def)
        return runCatching {
            pm.queryIntentActivities(intent, 0)
                .map { it.activityInfo.packageName }
                .filter { it != "android" && it != "com.android.settings" }
                .toSet()
        }.getOrDefault(emptySet())
    }

    private suspend fun foreground(manager: ZynAdbConnectionManager, pkg: String, home: Set<String>): Foreground {
        var answered = false
        var items = emptyList<ResumedComponent>()
        runShell(manager, "dumpsys activity activities | grep -i resumedactivity")?.let {
            answered = true
            items = ForegroundParser.parseResumed(it)
        }
        if (items.isEmpty()) {
            runShell(manager, "dumpsys window | grep -iE 'mfocusedapp|mcurrentfocus'")?.let {
                answered = true
                items = ForegroundParser.parseFocusedApp(it) + ForegroundParser.parseCurrentFocus(it)
            }
        }
        if (!answered) return Foreground.UNKNOWN
        return ForegroundParser.classify(items, pkg, home, app.packageName)
    }

    private suspend fun processAlive(manager: ZynAdbConnectionManager, pkg: String): Alive {
        val p = runShell(manager, "pidof $pkg 2>&1") ?: return Alive.UNKNOWN
        val byPid = ForegroundParser.parsePidof(p)
        if (byPid != Alive.NO) return byPid
        // pidof báo không có -> hỏi thêm ps (tiến trình phụ "pkg:xxx" cũng tính) rồi mới kết luận
        val s = runShell(manager, "ps -A -o NAME 2>&1") ?: return Alive.UNKNOWN
        return ForegroundParser.parsePs(s, pkg)
    }

    /**
     * Chạy 1 lệnh shell, đọc hết đầu ra. Gắn dấu kết thúc để biết lệnh chạy xong thật
     * (đầu ra rỗng do lỗi mạng khác với rỗng do không có gì). Null = không tin được.
     */
    private suspend fun runShell(manager: ZynAdbConnectionManager, cmd: String): String? =
        withContext(Dispatchers.IO) {
            for (prefix in arrayOf("exec:", "shell:")) {
                val stream = try {
                    manager.openStream("$prefix$cmd; echo $END")
                } catch (_: Exception) {
                    continue
                }
                // Đọc chặn có thể treo nếu ADB đứt giữa chừng -> đóng luồng sau hạn chót
                val closer = scope.launch {
                    delay(CMD_TIMEOUT_MS)
                    runCatching { stream.close() }
                }
                var text: String? = null
                try {
                    val sb = StringBuilder()
                    stream.openInputStream().bufferedReader().use { r ->
                        val cb = CharArray(4096)
                        while (sb.length < MAX_CHARS) {
                            val n = r.read(cb)
                            if (n < 0) break
                            sb.append(cb, 0, n)
                        }
                    }
                    val s = sb.toString()
                    if (s.contains(END)) text = s.substringBefore(END)
                } catch (_: Exception) {
                } finally {
                    closer.cancel()
                    runCatching { stream.close() }
                }
                if (text != null) return@withContext text
            }
            null
        }

    private companion object {
        val PKG = Regex("^[A-Za-z0-9_.]+$")
        const val END = "ZYN_END_5c1e"
        const val POLL_MS = 1_000L
        const val HOME_REFRESH_MS = 30_000L
        const val CMD_TIMEOUT_MS = 5_000L
        const val MAX_CHARS = 400_000
    }
}
