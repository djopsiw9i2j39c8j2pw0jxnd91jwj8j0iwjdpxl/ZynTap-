package com.zyntap.app.macro

/** Chế độ kích hoạt của nút main. */
enum class MainMode(val label: String) {
    TAP("Nhấn"),   // chạm -> chạy chuỗi điểm 1 lần
    HOLD("Giữ"),   // giữ -> lặp chuỗi điểm cho tới khi thả
    RELEASE("Thả"); // giữ -> thả mới chạy chuỗi điểm 1 lần

    companion object {
        fun fromName(name: String?): MainMode =
            values().firstOrNull { it.name == name } ?: TAP
    }
}

/** Một nút main hình tròn. [id] dạng main1, main2, ... ; [script] là nội dung ô nhập điểm/delay. */
data class MainButton(
    val id: String,
    var name: String,
    var mode: MainMode = MainMode.TAP,
    var opacityPct: Int = 85,      // 20..100
    var sizeDp: Int = 56,          // 40..96
    var posX: Int = -1,            // -1 = chưa đặt, dùng vị trí mặc định
    var posY: Int = -1,
    var script: String = "",
)

/** Một bước trong chuỗi: tọa độ hoặc delay. */
sealed class Step {
    data class Point(val x: Int, val y: Int) : Step()
    data class Delay(val ms: Long) : Step()
}

object ScriptParser {
    private val pointRegex = Regex("""^\s*(-?\d+)\s*[,; ]\s*(-?\d+)\s*$""")
    private val delayRegex = Regex("""^\s*(\d+)\s*(ms)?\s*$""", RegexOption.IGNORE_CASE)

    /**
     * Đọc từng dòng: "x, y" = điểm chạm, "300ms" (hoặc "300") = delay.
     * Dòng sai định dạng bị bỏ qua.
     */
    fun parse(script: String): List<Step> {
        val out = mutableListOf<Step>()
        for (raw in script.lines()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            val point = pointRegex.matchEntire(line)
            if (point != null) {
                out += Step.Point(point.groupValues[1].toInt(), point.groupValues[2].toInt())
                continue
            }
            delayRegex.matchEntire(line)?.let {
                out += Step.Delay(it.groupValues[1].toLong())
            }
        }
        return out
    }

    /**
     * Quy tắc delay: một dòng delay áp dụng cho điểm NGAY SAU nó (chờ rồi mới chạm).
     * Trả về danh sách điểm kèm thời gian chờ trước mỗi điểm.
     * Delay cuối chuỗi (không có điểm sau) sẽ áp dụng cho điểm đầu tiên của vòng lặp kế tiếp.
     */
    data class Planned(val x: Int, val y: Int, val waitBeforeMs: Long)

    fun plan(steps: List<Step>): Pair<List<Planned>, Long> {
        val out = mutableListOf<Planned>()
        var pending = 0L
        for (s in steps) {
            when (s) {
                is Step.Delay -> pending = s.ms
                is Step.Point -> {
                    out += Planned(s.x, s.y, pending)
                    pending = 0L
                }
            }
        }
        return out to pending
    }
}
