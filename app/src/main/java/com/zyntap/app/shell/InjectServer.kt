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
 *   PING                                     -> OK
 *   T x y holdMs pointerId pressure size major minor orientation -> OK | ERR <lý do>
 *   Q                                        -> (thoát)
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
        System.exit(0)
    }

    private fun handle(injector: Injector, line: String): String {
        val p = line.split(' ')
        return when (p[0]) {
            "PING" -> "OK"
            "T" -> {
                if (p.size < 10) return "ERR lệnh T thiếu tham số"
                val x = p[1].toFloat()
                val y = p[2].toFloat()
                val hold = p[3].toLong().coerceIn(0L, 2_000L)
                val id = p[4].toInt().coerceIn(0, 31)
                val finger = Finger(
                    pressure = p[5].toFloat().coerceIn(0f, 1f),
                    size = p[6].toFloat().coerceIn(0f, 1f),
                    major = p[7].toFloat().coerceAtLeast(0f),
                    minor = p[8].toFloat().coerceAtLeast(0f),
                    orientation = p[9].toFloat(),
                )
                if (x < 0f || y < 0f || x > 20_000f || y > 20_000f) return "ERR toạ độ không hợp lệ"
                injector.tap(x, y, hold, id, finger)
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

    private class Injector {
        private val send: (InputEvent) -> Boolean
        private val route: String
        private val deviceId: Int
        private val touchName: String

        init {
            val resolved = resolveInject()
            send = resolved.first
            route = resolved.second
            val touch = findTouchDevice()
            touchName = touch?.let { "${it.name}#${it.id}" } ?: "?"
            deviceId = pickDeviceId(touch)
        }

        fun info(): String = "sdk=${Build.VERSION.SDK_INT} route=$route touch=$touchName fakeDevice=$deviceId"

        fun tap(x: Float, y: Float, holdMs: Long, pointerId: Int, f: Finger) {
            val downTime = SystemClock.uptimeMillis()
            if (!inject(MotionEvent.ACTION_DOWN, x, y, downTime, downTime, pointerId, f, f.pressure)) {
                throw IllegalStateException("hệ thống từ chối DOWN")
            }
            var upOk = false
            try {
                if (holdMs > 0) Thread.sleep(holdMs)
            } finally {
                // Luôn nhả, kể cả khi bị ngắt giữa chừng -> không bao giờ kẹt một ngón ảo
                upOk = inject(MotionEvent.ACTION_UP, x, y, downTime, SystemClock.uptimeMillis(), pointerId, f, 0f)
            }
            if (!upOk) throw IllegalStateException("hệ thống từ chối UP")
        }

        private fun inject(
            action: Int, x: Float, y: Float, downTime: Long, eventTime: Long,
            pointerId: Int, f: Finger, pressure: Float,
        ): Boolean {
            val props = MotionEvent.PointerProperties().apply {
                id = pointerId
                toolType = MotionEvent.TOOL_TYPE_FINGER
            }
            val coords = MotionEvent.PointerCoords().apply {
                this.x = x
                this.y = y
                this.pressure = pressure
                size = f.size
                touchMajor = f.major
                touchMinor = f.minor
                toolMajor = f.major
                toolMinor = f.minor
                orientation = f.orientation
            }
            val event = MotionEvent.obtain(
                downTime, eventTime, action, 1,
                arrayOf(props), arrayOf(coords),
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

        /** device-id giả: -1 (thiết bị ảo của hệ thống); nếu lỡ trùng tấm cảm ứng thì lùi dần. */
        private fun pickDeviceId(touch: Dev?): Int {
            var id = -1
            val taken = runCatching { InputDevice.getDeviceIds().toSet() }.getOrDefault(emptySet())
                .filter { InputDevice.getDevice(it)?.isVirtual == false }
                .toSet()
            while (id == touch?.id || id in taken) id--
            return id
        }

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
