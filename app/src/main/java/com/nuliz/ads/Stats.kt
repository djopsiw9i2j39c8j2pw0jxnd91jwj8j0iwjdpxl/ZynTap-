package com.nuliz.ads

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Loại mục tiêu bị chặn. [file] là tên file blocklist trong assets/blocklists. */
enum class Category(val label: String, val file: String) {
    ADS("Quảng cáo", "ads.txt"),
    MALWARE("Mã độc", "malware.txt"),
}

data class BlockEvent(val domain: String, val category: Category, val time: Long)

data class StatsState(
    val running: Boolean = false,
    val ads: Long = 0,
    val malware: Long = 0,
    val listSize: Int = 0,
    val recent: List<BlockEvent> = emptyList(),
) {
    val total get() = ads + malware
}

/** Nguồn dữ liệu duy nhất cho UI + thông báo: service ghi, UI đọc qua [state]. */
object Stats {
    private const val DEDUPE_MS = 3_000L // app hay gửi lại cùng một truy vấn → chỉ tính 1 lần
    private const val MAX_RECENT = 100
    private const val SAVE_EVERY = 10

    private val _state = MutableStateFlow(StatsState())
    val state: StateFlow<StatsState> = _state.asStateFlow()

    private val lastSeen = HashMap<String, Long>()

    fun init(context: Context) {
        Prefs.init(context)
        _state.update { it.copy(ads = Prefs.ads, malware = Prefs.malware) }
    }

    fun setRunning(running: Boolean) = _state.update { it.copy(running = running) }

    fun setListSize(size: Int) = _state.update { it.copy(listSize = size) }

    /** Ghi nhận một lượt chặn. Trả về sự kiện nếu được tính, `null` nếu là truy vấn lặp. */
    @Synchronized
    fun record(domain: String, category: Category): BlockEvent? {
        val now = System.currentTimeMillis()
        val last = lastSeen[domain]
        if (last != null && now - last < DEDUPE_MS) return null
        lastSeen[domain] = now
        if (lastSeen.size > 512) lastSeen.values.removeAll { now - it >= DEDUPE_MS }

        val event = BlockEvent(domain, category, now)
        _state.update { s ->
            s.copy(
                ads = s.ads + if (category == Category.ADS) 1 else 0,
                malware = s.malware + if (category == Category.MALWARE) 1 else 0,
                recent = (listOf(event) + s.recent).take(MAX_RECENT),
            )
        }
        if (_state.value.total % SAVE_EVERY == 0L) persist()
        return event
    }

    fun persist() = _state.value.let { Prefs.saveCounts(it.ads, it.malware) }

    fun reset() {
        _state.update { it.copy(ads = 0, malware = 0, recent = emptyList()) }
        persist()
    }
}
