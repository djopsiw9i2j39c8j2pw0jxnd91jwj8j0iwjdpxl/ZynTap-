package com.zyntap.app.shell

import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.view.InputDevice
import android.view.InputEvent
import android.view.MotionEvent
import java.io.BufferedReader
import java.io.InputStreamReader
import java.lang.reflect.InvocationTargetException

/**
 * Tiến trình phụ chạy bằng QUYỀN SHELL (do app mở qua ADB không dây), không chạy trong app.
 *
 * Chạy thẳng từ file APK của app:
 *   CLASSPATH=<đường dẫn base.apk> app_process /system/bin com.zyntap.app.shell.InjectServer
 *
 * Vì sao cần nó: `input tap` dùng CHUNG device-id với tấm cảm ứng thật. Lệnh UP giả của nó làm hệ thống
 * tưởng cử chỉ của ngón thật đã kết thúc -> ngón đang vuốt bị đứt. Shell lại không có quyền ghi vào
 * /dev/input (không dùng được sendevent/uinput), nên ở đây tiêm thẳng qua InputManager của hệ thống:
 *  - device-id RIÊNG (không trùng tấm cảm ứng thật) -> hệ thống tách thành luồng chạm độc lập;
 *  - pointer-id do app chọn (id còn trống so với các ngón thật);
 *  - pressure / size / touchMajor / touchMinor sao chép từ ngón thật gần nhất.
 *
 * Giao thức (mỗi dòng một lệnh, mỗi lệnh đúng một dòng trả lời):
 *   PING                                                   -> OK
 *   N <tên tấm cảm ứng>                                    -> OK  (dùng đúng device-id của tấm cảm ứng thật)
 *   D x y id pressure size major minor orient n [id x y]*n -> OK | ERR <lý do>   (ngón ảo chạm xuống)
 *   U x y id pressure size major minor orient n [id x y]*n -> OK | ERR <lý do>   (ngón ảo nhả)
 *   Q                                                      -> (thoát, tự nhả ngón ảo đang giữ)
 * `n` + các bộ [id x y] = các ngón THẬT đang chạm (toạ độ mới nhất). Có ngón thật -> ngón ảo đi như ngón
 * thứ hai của cùng cử chỉ (POINTER_DOWN/UP kèm đủ các ngón), không thì chạm đơn (DOWN/UP).
 * Dòng đầu tiên server in ra: ZYN_READY <thông tin> hoặc ZYN_FAIL <lý do>.
 */
object InjectServer {

    @JvmStatic
    fun main(args: Array<String>) {
        val out = System.out
        val injector = try {
            Injector()
        } catch (t: Throwable) {
            out.println("ZYN_FAIL ${describe(t)}")
            out.flush()
            System.exit(1)
            return
        }
        out.println("ZYN_READY ${injector.info()}")
        out.flush()

        val input = BufferedReader(InputStreamReader(System.`in`, Charsets.UTF_8))
        while (true) {
            val line = (input.readLine() ?: break).trim() // đóng luồng ADB = thoát
            if (line.isEmpty()) continue
            if (line == "Q") break
            val reply = try {
                handle(injector, line)
            } catch (t: Throwable) {
                "ERR ${describe(t)}"
            }
            out.println(reply)
            out.flush()
        }
        injector.release()
        System.exit(0)
    }

    private fun handle(injector: Injector, line: String): String {
        val p = line.split(' ')
        return when (p[0]) {
            "PING" -> "OK"
            "N" -> {
                val name = line.removePrefix("N").trim()
                injector.useDevice(name) // không thấy tên thì giữ id tự dò
                "OK ${injector.deviceInfo()}"
            }
            "D", "U" -> {
                if (p.size < 10) return "ERR lệnh ${p[0]} thiếu tham số"
                val x = p[1].toFloat()
                val y = p[2].toFloat()
                val id = p[3].toInt().coerceIn(0, 31)
                val finger = Finger(
                    pressure = p[4].toFloat().coerceIn(0f, 1f),
                    size = p[5].toFloat().coerceIn(0f, 1f),
                    major = p[6].toFloat().coerceAtLeast(0f),
                    minor = p[7].toFloat().coerceAtLeast(0f),
                    orientation = p[8].toFloat(),
                )
                val n = p[9].toInt().coerceIn(0, 10)
                if (p.size < 10 + n * 3) return "ERR lệnh ${p[0]} thiếu ngón thật"
                val others = List(n) { i ->
                    Ptr(p[10 + i * 3].toInt().coerceIn(0, 31), p[11 + i * 3].toFloat(), p[12 + i * 3].toFloat())
                }.filter { it.id != id }
                if (x < 0f || y < 0f || x > 20_000f || y > 20_000f) return "ERR toạ độ không hợp lệ"
                if (p[0] == "D") injector.down(x, y, id, finger, others) else injector.up(x, y, id, finger, others)
                "OK"
            }
            else -> "ERR lệnh lạ: ${p[0]}"
        }
    }

    private fun describe(t: Throwable): String {
        val c = (t as? InvocationTargetException)?.targetException ?: t
        return "${c.javaClass.simpleName}: ${c.message ?: ""}".replace('\n', ' ').take(240)
    }

    /** Thông số ngón thật đã đổi sang đơn vị MotionEvent. */
    private class Finger(
        val pressure: Float,
        val size: Float,
        val major: Float,
        val minor: Float,
        val orientation: Float,
    )

    /** Một ngón thật đang chạm (id + toạ độ pixel). */
    private class Ptr(val id: Int, val x: Float, val y: Float)

    private class Injector {
        private val send: (InputEvent) -> Boolean
        private val route: String
        private var deviceId: Int
        private var touchName: String

        /** Lần chạm ảo đang mở (đã DOWN, chưa UP). */
        private class Open(val downTime: Long, val id: Int, val joined: Boolean, val f: Finger, val x: Float, val y: Float)
        private var openTap: Open? = null

        init {
            val resolved = resolveInject()
            send = resolved.first
            route = resolved.second
            val touch = findTouchDevice()
            touchName = touch?.let { "${it.name}#${it.id}" } ?: "?"
            // CÙNG device-id với tấm cảm ứng thật: lần chạm ảo là ngón thêm trong cùng cử chỉ, không cắt ngón thật
            deviceId = touch?.id ?: -1
        }

        fun deviceInfo(): String = "device=$deviceId touch=$touchName"

        fun info(): String = "sdk=${Build.VERSION.SDK_INT} route=$route touch=$touchName device=$deviceId"

        /** Chọn tấm cảm ứng theo tên app đã dò từ /dev/input (chính xác hơn đoán theo tên). */
        fun useDevice(name: String): Boolean {
            if (name.isBlank()) return false
            return runCatching {
                for (id in InputDevice.getDeviceIds()) {
                    val d = InputDevice.getDevice(id) ?: continue
                    if (d.isVirtual || d.name != name) continue
                    if ((d.sources and InputDevice.SOURCE_TOUCHSCREEN) != InputDevice.SOURCE_TOUCHSCREEN) continue
                    deviceId = id
                    touchName = "${d.name}#$id"
                    return@runCatching true
                }
                false
            }.getOrDefault(false)
        }

        /** Ngón ảo chạm xuống. [others] = các ngón thật đang chạm (rỗng = chạm đơn). */
        fun down(x: Float, y: Float, id: Int, f: Finger, others: List<Ptr>) {
            release() // phòng khi lần trước chưa kịp nhả
            val joined = others.isNotEmpty()
            val downTime = SystemClock.uptimeMillis()
            val all = (others + Ptr(id, x, y)).sortedBy { it.id }
            val index = all.indexOfFirst { it.id == id }
            val action = if (joined) MotionEvent.ACTION_POINTER_DOWN or (index shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
            else MotionEvent.ACTION_DOWN
            openTap = Open(downTime, id, joined, f, x, y)
            if (!inject(action, all, downTime, downTime, f, id)) {
                openTap = null
                throw IllegalStateException("hệ thống từ chối DOWN")
            }
        }

        /** Ngón ảo nhả. [others] = các ngón thật còn chạm lúc này, toạ độ MỚI NHẤT. */
        fun up(x: Float, y: Float, id: Int, f: Finger, others: List<Ptr>) {
            val o = openTap ?: return
            openTap = null
            val joined = o.joined && others.isNotEmpty() // ngón thật đã nhả hết -> cử chỉ đã kết thúc, chỉ còn UP đơn
            val all = (if (joined) others + Ptr(id, x, y) else listOf(Ptr(id, x, y))).sortedBy { it.id }
            val index = all.indexOfFirst { it.id == id }
            val action = if (joined) MotionEvent.ACTION_POINTER_UP or (index shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
            else MotionEvent.ACTION_UP
            if (!inject(action, all, o.downTime, SystemClock.uptimeMillis(), f, id)) {
                throw IllegalStateException("hệ thống từ chối UP")
            }
        }

        /** Nhả ngón ảo đang giữ (nếu có): gọi khi thoát / khi có DOWN mới / khi luồng ADB đứt. */
        fun release() {
            val o = openTap ?: return
            openTap = null
            val action = if (o.joined) {
                MotionEvent.ACTION_POINTER_UP
            } else MotionEvent.ACTION_UP
            runCatching { inject(action, listOf(Ptr(o.id, o.x, o.y)), o.downTime, SystemClock.uptimeMillis(), o.f, o.id) }
        }

        /** Tiêm một MotionEvent gồm [pointers] (đã sắp theo id); [activeId] là ngón ảo (dùng thông số ngón gốc [f]). */
        private fun inject(
            action: Int, pointers: List<Ptr>, downTime: Long, eventTime: Long, f: Finger, activeId: Int,
        ): Boolean {
            val props = Array(pointers.size) { i ->
                MotionEvent.PointerProperties().apply {
                    id = pointers[i].id
                    toolType = MotionEvent.TOOL_TYPE_FINGER
                }
            }
            val isUp = (action and MotionEvent.ACTION_MASK).let {
                it == MotionEvent.ACTION_UP || it == MotionEvent.ACTION_POINTER_UP
            }
            val coords = Array(pointers.size) { i ->
                MotionEvent.PointerCoords().apply {
                    x = pointers[i].x
                    y = pointers[i].y
                    // Lúc nhả, áp lực của chính ngón ảo = 0 như ngón thật khi nhấc lên
                    pressure = if (isUp && pointers[i].id == activeId) 0f else f.pressure
                    size = f.size
                    touchMajor = f.major
                    touchMinor = f.minor
                    toolMajor = f.major
                    toolMinor = f.minor
                    orientation = f.orientation
                }
            }
            val event = MotionEvent.obtain(
                downTime, eventTime, action, pointers.size,
                props, coords,
                0, 0, 1f, 1f, deviceId, 0, InputDevice.SOURCE_TOUCHSCREEN, 0,
            )
            return try {
                send(event)
            } finally {
                runCatching { event.recycle() }
            }
        }

        private class Dev(val id: Int, val name: String)

        /** Tự dò tấm cảm ứng chính (để báo cáo, và để chắc chắn device-id giả không trùng nó). */
        private fun findTouchDevice(): Dev? = runCatching {
            var best: Dev? = null
            var bestScore = Int.MIN_VALUE
            for (id in InputDevice.getDeviceIds()) {
                val d = InputDevice.getDevice(id) ?: continue
                if (d.isVirtual || (d.sources and InputDevice.SOURCE_TOUCHSCREEN) != InputDevice.SOURCE_TOUCHSCREEN) continue
                val n = d.name ?: ""
                var s = 0
                if (Regex("touch|screen|fts|goodix|synaptics|atmel|himax|novatek", RegexOption.IGNORE_CASE).containsMatchIn(n)) s += 2
                if (Regex("pen|stylus|finger.?print", RegexOption.IGNORE_CASE).containsMatchIn(n)) s -= 3
                if (s > bestScore) { bestScore = s; best = Dev(id, n) }
            }
            best
        }.getOrNull()

        private companion object {
            const val MODE_ASYNC = 0

            /** Tìm đường tiêm khả dụng: InputManagerGlobal (A14+) / InputManager, rồi binder "input". */
            fun resolveInject(): Pair<(InputEvent) -> Boolean, String> {
                val errors = StringBuilder()
                val int = Int::class.javaPrimitiveType!!

                try {
                    val cls = try {
                        Class.forName("android.hardware.input.InputManagerGlobal")
                    } catch (_: ClassNotFoundException) {
                        Class.forName("android.hardware.input.InputManager")
                    }
                    val get = cls.getDeclaredMethod("getInstance").apply { isAccessible = true }
                    val manager = get.invoke(null) ?: error("getInstance() = null")
                    val m2 = runCatching { manager.javaClass.getMethod("injectInputEvent", InputEvent::class.java, int) }.getOrNull()
                    if (m2 != null) {
                        return ({ e: InputEvent -> (m2.invoke(manager, e, MODE_ASYNC) as? Boolean) ?: true }) to cls.simpleName
                    }
                    val m3 = runCatching { manager.javaClass.getMethod("injectInputEvent", InputEvent::class.java, int, int) }.getOrNull()
                    if (m3 != null) {
                        return ({ e: InputEvent -> (m3.invoke(manager, e, MODE_ASYNC, -1) as? Boolean) ?: true }) to "${cls.simpleName}3"
                    }
                    errors.append("không có injectInputEvent; ")
                } catch (t: Throwable) {
                    errors.append(describe(t)).append("; ")
                }

                try {
                    val binder = Class.forName("android.os.ServiceManager")
                        .getMethod("getService", String::class.java).invoke(null, "input") as IBinder
                    val svc = Class.forName("android.hardware.input.IInputManager\$Stub")
                        .getMethod("asInterface", IBinder::class.java).invoke(null, binder) ?: error("asInterface = null")
                    val two = svc.javaClass.methods.firstOrNull { it.name == "injectInputEvent" && it.parameterTypes.size == 2 }
                    if (two != null) {
                        return ({ e: InputEvent -> (two.invoke(svc, e, MODE_ASYNC) as? Boolean) ?: true }) to "binder"
                    }
                    val three = svc.javaClass.methods.firstOrNull { it.name == "injectInputEventToTarget" && it.parameterTypes.size == 3 }
                    if (three != null) {
                        return ({ e: InputEvent -> (three.invoke(svc, e, MODE_ASYNC, -1) as? Boolean) ?: true }) to "binder3"
                    }
                    errors.append("binder input không có hàm tiêm; ")
                } catch (t: Throwable) {
                    errors.append(describe(t))
                }
                throw IllegalStateException(errors.toString().ifBlank { "không tìm được đường tiêm" })
            }
        }
    }
}
