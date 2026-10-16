package com.zyntap.app.shell;

import android.os.SystemClock;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.MotionEvent;

import java.io.BufferedReader;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Helper chạy với quyền SHELL qua ADB (không cần root, không cần quyền ghi /dev/input hay /dev/uinput):
 *
 * <pre>CLASSPATH=/data/app/.../base.apk app_process /system/bin com.zyntap.app.shell.InputProxy dev=... maxx=... </pre>
 *
 * Làm 3 việc:
 * <ol>
 *   <li>Mở tấm cảm ứng thật (đường dẫn do app tự dò), {@code EVIOCGRAB} để hệ thống KHÔNG nhận trực tiếp
 *       sự kiện của ngón thật nữa.</li>
 *   <li>Tự đọc sự kiện ngón thật rồi phát lại vào hệ thống dưới dạng {@link MotionEvent} (cùng deviceId,
 *       source cảm ứng của máy). Ngón thật = nguồn slot 0..15.</li>
 *   <li>Click của app (lệnh {@code TAP}) được nhét vào CHÍNH luồng đó dưới dạng một pointer thêm
 *       (nguồn 16..23), mang áp lực / kích cỡ clone từ ngón thật gần nhất. Chỉ còn MỘT luồng chạm duy nhất
 *       nên ngón thật không bị mất khi app đang click.</li>
 *   <li>Pointer id gửi cho hệ thống được cấp lại như máy thật (số nhỏ nhất còn trống), nên click của app và
 *       ngón thật hòa vào nhau như hai ngón thật, không phụ thuộc ai chạm trước.</li>
 * </ol>
 *
 * An toàn: grab là của file descriptor, tiến trình chết / thoát là kernel tự nhả grab. Mất heartbeat
 * (PING) quá {@link #WATCHDOG_MS} hoặc stdin đóng thì tự thoát.
 *
 * Giao thức (mỗi dòng, UTF-8):
 * <pre>
 *   app -> helper:  PING | ROT r w h | TAP id x y holdMs [gapMs] | QUIT
 *   (gapMs: click chờ cho tới khi ngón thật đã nhả ít nhất gapMs, để game kịp xử lý ngón thật trước)
 *   helper -> app:  READY grab=0|1 inj=0|1 dev=n | FAIL msg | F slot 1|0 x y | OK id | ERR id msg | LOG msg
 * </pre>
 * Tọa độ x,y (F, TAP) là PIXEL màn hình hiện tại (đã tính xoay).
 *
 * Nếu grab hoặc inject không dùng được (ROM chặn) -> chế độ THỤ ĐỘNG: không grab, không tự phát lại,
 * chỉ báo ngón chạm (F) như cũ; TAP trả ERR để app lùi về {@code input tap}.
 */
public final class InputProxy {

    // ---- hằng số linux/input ---------------------------------------------------------------
    private static final int EV_SYN = 0x00, EV_ABS = 0x03;
    private static final int SYN_REPORT = 0x00, SYN_DROPPED = 0x03;
    private static final int ABS_MT_SLOT = 0x2f, ABS_MT_TOUCH_MAJOR = 0x30, ABS_MT_TOUCH_MINOR = 0x31;
    private static final int ABS_MT_POSITION_X = 0x35, ABS_MT_POSITION_Y = 0x36;
    private static final int ABS_MT_TRACKING_ID = 0x39, ABS_MT_PRESSURE = 0x3a;
    /** _IOW('E', 0x90, int) */
    private static final int EVIOCGRAB = 0x40044590;

    private static final int REAL_SLOTS = 16;          // ngón thật: pointer id 0..15
    private static final int SYNTH_BASE = 16;          // khoá nguồn của click app: 16..23 (id gửi đi được cấp lại, xem shown)
    private static final int SYNTH_COUNT = 8;
    private static final long WATCHDOG_MS = 3000;
    /** Số lần phát sự kiện thất bại liên tiếp thì coi bộ phát đã hỏng và nhả grab NGAY (tránh liệt cảm ứng toàn máy). */
    private static final int MAX_INJECT_FAILS = 4;

    // ---- cấu hình (từ tham số dòng lệnh) ----------------------------------------------------
    private static String devPath = "";
    private static String devName = "";
    private static int maxX = 1, maxY = 1, maxPressure = 0, maxMajor = 0;
    private static volatile int rot = 0, scrW = 1, scrH = 1;

    // ---- trạng thái ------------------------------------------------------------------------
    private static final Object LOCK = new Object();
    private static final PrintStream OUT = new PrintStream(System.out, true);
    /**
     * Mọi dòng báo về app đi qua hàng đợi + luồng ghi riêng. TUYỆT ĐỐI không ghi stdout trực tiếp trong
     * đường phát cảm ứng: nếu kết nối ADB nghẽn (Wi-Fi chập chờn, app đứng hình) thì println bị chặn
     * khi đang giữ LOCK -> ngón thật không được phát lại nữa trong khi grab vẫn còn = liệt cảm ứng cả máy.
     */
    private static final BlockingQueue<String> OUTQ = new ArrayBlockingQueue<>(512);
    private static volatile boolean outThreadOn = false;
    private static volatile boolean urgentRelease = false;
    private static int injectFails = 0;   // chỉ đụng dưới LOCK

    private static void out(String line) {
        if (!outThreadOn) { OUT.println(line); return; }
        OUTQ.offer(line); // đầy thì bỏ dòng, không bao giờ chờ
    }

    private static void startOutThread() {
        Thread t = new Thread(() -> {
            try {
                while (true) {
                    String l = OUTQ.take();
                    OUT.println(l);
                }
            } catch (Throwable ignored) { }
        }, "zyn-out");
        t.setDaemon(true);
        outThreadOn = true;
        t.start();
    }

    // Ngón thật (thô, theo slot) — chỉ đụng dưới LOCK
    private static final int[] tid = new int[REAL_SLOTS];
    private static final boolean[] rawActive = new boolean[REAL_SLOTS];
    private static final boolean[] hasX = new boolean[REAL_SLOTS];
    private static final boolean[] hasY = new boolean[REAL_SLOTS];
    private static final int[] rawX = new int[REAL_SLOTS];
    private static final int[] rawY = new int[REAL_SLOTS];
    private static final int[] rawP = new int[REAL_SLOTS];
    private static final int[] rawMaj = new int[REAL_SLOTS];
    private static final int[] rawMin = new int[REAL_SLOTS];
    private static final boolean[] reported = new boolean[REAL_SLOTS]; // đã báo F down
    private static final int[] lastRepX = new int[REAL_SLOTS];
    private static final int[] lastRepY = new int[REAL_SLOTS];
    private static int curSlot = 0;

    /** Một pointer đã/đang gửi cho hệ thống. */
    private static final class P {
        float x, y, pressure, size, major, minor, toolMajor;
        P copy() {
            P p = new P();
            p.x = x; p.y = y; p.pressure = pressure; p.size = size;
            p.major = major; p.minor = minor; p.toolMajor = toolMajor;
            return p;
        }
        boolean same(P o) { return x == o.x && y == o.y && pressure == o.pressure && major == o.major; }
    }

    /**
     * Hệ thống đang thấy, khoá = pointer id GỬI ĐI (không phải id nguồn). Id gửi đi được cấp lại theo kiểu máy
     * thật: luôn là số nhỏ nhất còn trống (0,1,2...), bất kể là ngón thật hay click của app. Nhờ vậy game
     * không bao giờ thấy "ngón số 16" lạ, và dù click của app chạm trước hay ngón thật chạm trước thì luồng
     * MotionEvent vẫn y hệt hai ngón thật chạm lần lượt (DOWN -> POINTER_DOWN -> POINTER_UP...).
     */
    private static final TreeMap<Integer, P> shown = new TreeMap<>();
    private static final HashMap<Integer, Integer> idOfSrc = new HashMap<>();  // khoá nguồn -> id gửi đi
    private static final HashMap<Integer, Integer> srcOfId = new HashMap<>();  // id gửi đi -> khoá nguồn
    private static final HashMap<Integer, P> synth = new HashMap<>();   // click của app đang giữ
    private static long downTime = 0;
    private static long lastRealUpAt = 0;   // uptime lúc ngón thật gần nhất vừa nhả (chỉ đụng dưới LOCK)
    private static P lastReal = null;       // áp lực / kích cỡ của ngón thật gần nhất (để clone)
    private static int deviceId = 0;

    private static FileInputStream devIn;
    private static boolean injecting = false;
    private static boolean grabbed = false;
    private static volatile boolean stopping = false;
    private static volatile long lastPing = SystemClock.uptimeMillis();
    private static final ExecutorService TAPS = Executors.newSingleThreadExecutor();

    // ---- phát hiện kích thước bản ghi input_event ---------------------------------------------
    private static int recSize = 0;

    public static void main(String[] args) {
        try {
            for (String a : args) {
                int eq = a.indexOf('=');
                if (eq <= 0) continue;
                String k = a.substring(0, eq), v = a.substring(eq + 1);
                switch (k) {
                    case "dev": devPath = v; break;
                    case "name": devName = java.net.URLDecoder.decode(v, "UTF-8"); break;
                    case "maxx": maxX = Math.max(1, Integer.parseInt(v)); break;
                    case "maxy": maxY = Math.max(1, Integer.parseInt(v)); break;
                    case "maxp": maxPressure = Math.max(0, Integer.parseInt(v)); break;
                    case "maxmaj": maxMajor = Math.max(0, Integer.parseInt(v)); break;
                    case "w": scrW = Math.max(1, Integer.parseInt(v)); break;
                    case "h": scrH = Math.max(1, Integer.parseInt(v)); break;
                    case "rot": rot = Integer.parseInt(v) & 3; break;
                    default: break;
                }
            }
            if (!devPath.startsWith("/dev/input/event")) { OUT.println("FAIL bad dev"); return; }
            run();
        } catch (Throwable t) {
            OUT.println("FAIL " + t);
        } finally {
            shutdown();
        }
    }

    private static void run() throws Exception {
        for (int i = 0; i < REAL_SLOTS; i++) tid[i] = -1;
        // SIGTERM / SIGHUP (adb đóng, bị kill mềm): vẫn huỷ ngón giả + nhả grab. SIGKILL thì kernel tự nhả grab.
        Runtime.getRuntime().addShutdownHook(new Thread(() -> { urgentRelease = true; shutdown(); }, "zyn-hook"));
        devIn = new FileInputStream(devPath);
        deviceId = findDeviceId(devName);

        // Chuẩn bị bộ phát; nếu không có thì không grab (grab mà không phát lại được = mất cảm ứng).
        Injector inj = null;
        try { inj = new Injector(); } catch (Throwable t) { log("inject: " + t); }
        if (inj != null) {
            injector = inj;
            grabbed = grab(devIn.getFD());
            injecting = grabbed;
        }
        OUT.println("READY grab=" + (grabbed ? 1 : 0) + " inj=" + (injecting ? 1 : 0) + " dev=" + deviceId);

        startOutThread();
        Thread reader = new Thread(InputProxy::readLoop, "zyn-reader");
        reader.setDaemon(true);
        reader.start();
        Thread dog = new Thread(InputProxy::watchdog, "zyn-dog");
        dog.setDaemon(true);
        dog.start();

        BufferedReader in = new BufferedReader(new InputStreamReader(System.in, "UTF-8"));
        String line;
        while (!stopping && (line = in.readLine()) != null) {
            lastPing = SystemClock.uptimeMillis();
            handle(line.trim());
        }
    }

    private static void handle(String line) {
        if (line.isEmpty() || line.equals("PING")) return;
        String[] t = line.split(" ");
        try {
            switch (t[0]) {
                case "ROT":
                    synchronized (LOCK) {
                        rot = Integer.parseInt(t[1]) & 3;
                        scrW = Math.max(1, Integer.parseInt(t[2]));
                        scrH = Math.max(1, Integer.parseInt(t[3]));
                    }
                    break;
                case "TAP": {
                    final String id = t[1];
                    final float x = Float.parseFloat(t[2]), y = Float.parseFloat(t[3]);
                    final int hold = Math.max(5, Math.min(2000, Integer.parseInt(t[4])));
                    final int gap = t.length > 5 ? Math.max(0, Math.min(500, Integer.parseInt(t[5]))) : 0;
                    TAPS.execute(() -> doTap(id, x, y, hold, gap));
                    break;
                }
                case "QUIT":
                    stopping = true;
                    shutdown();
                    break;
                default:
                    break;
            }
        } catch (Throwable e) {
            log("bad cmd: " + line);
        }
    }

    private static void watchdog() {
        while (!stopping) {
            if (SystemClock.uptimeMillis() - lastPing > WATCHDOG_MS) {
                log("watchdog");
                urgentRelease = true; // mất nhịp tim: nhả grab ngay, không chờ ngón thật nhấc lên
                shutdown();
                return;
            }
            SystemClock.sleep(500);
        }
    }

    // ---- ngón thật -----------------------------------------------------------------------------
    private static void readLoop() {
        byte[] buf = new byte[4800]; // chia hết cho 16 và 24
        byte[] acc = new byte[9600];
        int n = 0;
        try {
            while (!stopping) {
                int r = devIn.read(buf);
                if (r < 0) break;
                if (r == 0) continue;
                if (n + r > acc.length) n = 0; // không thể xảy ra, phòng hờ
                System.arraycopy(buf, 0, acc, n, r);
                n += r;
                if (recSize == 0) {
                    if (n < 48) continue;
                    recSize = valid(acc, 24) ? 24 : valid(acc, 16) ? 16 : -1;
                    if (recSize < 0) { out("FAIL not input_event"); shutdown(); return; }
                }
                int off = 0;
                while (n - off >= recSize) {
                    int t = (recSize == 24 ? off + 16 : off + 8);
                    onEvent(u16(acc, t), u16(acc, t + 2), s32(acc, t + 4));
                    off += recSize;
                }
                if (off > 0) { System.arraycopy(acc, off, acc, 0, n - off); n -= off; }
            }
        } catch (IOException e) {
            log("read: " + e);
        }
        shutdown();
    }

    private static int u16(byte[] b, int o) { return (b[o] & 0xff) | ((b[o + 1] & 0xff) << 8); }
    private static long u32(byte[] b, int o) { return (u16(b, o) & 0xffffL) | ((long) u16(b, o + 2) << 16); }
    private static int s32(byte[] b, int o) { return (int) u32(b, o); }

    private static boolean valid(byte[] b, int size) {
        for (int o = 0; o + size <= 48; o += size) {
            boolean ok = size == 24
                    ? (u32(b, o + 4) == 0 && u32(b, o + 12) == 0 && u32(b, o + 8) < 1_000_000L && u16(b, o + 16) <= 0x1f)
                    : (u32(b, o + 4) < 1_000_000L && u16(b, o + 8) <= 0x1f);
            if (!ok) return false;
        }
        return true;
    }

    private static void onEvent(int type, int code, int value) {
        synchronized (LOCK) {
            if (type == EV_ABS) {
                switch (code) {
                    case ABS_MT_SLOT:
                        curSlot = Math.max(0, Math.min(REAL_SLOTS, value));
                        break;
                    case ABS_MT_TRACKING_ID:
                        if (curSlot >= REAL_SLOTS) break;
                        if (value == -1) {
                            rawActive[curSlot] = false;
                            tid[curSlot] = -1;
                        } else if (!rawActive[curSlot]) {
                            rawActive[curSlot] = true;
                            tid[curSlot] = value;
                            hasX[curSlot] = false;
                            hasY[curSlot] = false;
                            rawP[curSlot] = 0;
                            rawMaj[curSlot] = 0;
                            rawMin[curSlot] = 0;
                        }
                        break;
                    case ABS_MT_POSITION_X:
                        if (curSlot < REAL_SLOTS) { rawX[curSlot] = value; hasX[curSlot] = true; }
                        break;
                    case ABS_MT_POSITION_Y:
                        if (curSlot < REAL_SLOTS) { rawY[curSlot] = value; hasY[curSlot] = true; }
                        break;
                    case ABS_MT_PRESSURE:
                        if (curSlot < REAL_SLOTS) rawP[curSlot] = value;
                        break;
                    case ABS_MT_TOUCH_MAJOR:
                        if (curSlot < REAL_SLOTS) rawMaj[curSlot] = value;
                        break;
                    case ABS_MT_TOUCH_MINOR:
                        if (curSlot < REAL_SLOTS) rawMin[curSlot] = value;
                        break;
                    default:
                        break;
                }
            } else if (type == EV_SYN) {
                if (code == SYN_DROPPED) {
                    // Mất đồng bộ: coi như mọi ngón đã nhả, đợi ngón mới
                    for (int i = 0; i < REAL_SLOTS; i++) { rawActive[i] = false; tid[i] = -1; }
                } else if (code == SYN_REPORT) {
                    sync();
                }
            }
        }
    }

    // ---- gộp ngón thật + click của app -> một luồng MotionEvent ---------------------------------
    private static float mapX(int rx, int ry) {
        float nx = (rx + 0.5f) / (maxX + 1f), ny = (ry + 0.5f) / (maxY + 1f);
        switch (rot) {
            case 1: return ny * scrW;
            case 2: return (1f - nx) * scrW;
            case 3: return (1f - ny) * scrW;
            default: return nx * scrW;
        }
    }

    private static float mapY(int rx, int ry) {
        float nx = (rx + 0.5f) / (maxX + 1f), ny = (ry + 0.5f) / (maxY + 1f);
        switch (rot) {
            case 1: return (1f - nx) * scrH;
            case 2: return (1f - ny) * scrH;
            case 3: return nx * scrH;
            default: return ny * scrH;
        }
    }

    /** Pixel màn hình ứng với 1 đơn vị thô của trục "to" (xấp xỉ, dùng cho touchMajor/minor). */
    private static float pxPerRaw() {
        boolean swap = (rot & 1) == 1;
        float s = swap ? (float) scrW / (maxY + 1f) : (float) scrW / (maxX + 1f);
        return s;
    }

    private static P realPointer(int s) {
        P p = new P();
        p.x = clampX(mapX(rawX[s], rawY[s]));
        p.y = clampY(mapY(rawX[s], rawY[s]));
        p.pressure = maxPressure > 0 ? Math.min(1f, rawP[s] / (float) maxPressure) : 1f;
        if (maxPressure > 0 && p.pressure <= 0f) p.pressure = 0.05f;
        float k = pxPerRaw();
        p.major = maxMajor > 0 ? rawMaj[s] * k : 0f;
        p.minor = rawMin[s] > 0 ? rawMin[s] * k : p.major;
        p.toolMajor = p.major;
        p.size = maxMajor > 0 ? Math.min(1f, rawMaj[s] / (float) maxMajor) : 0f;
        return p;
    }

    private static float clampX(float v) { return Math.max(0f, Math.min(scrW - 1f, v)); }
    private static float clampY(float v) { return Math.max(0f, Math.min(scrH - 1f, v)); }

    /** Gọi dưới LOCK sau mỗi SYN_REPORT hoặc khi click của app đổi trạng thái. */
    private static void sync() {
        TreeMap<Integer, P> want = new TreeMap<>();
        for (int s = 0; s < REAL_SLOTS; s++) {
            if (rawActive[s] && hasX[s] && hasY[s]) {
                P p = realPointer(s);
                want.put(s, p);
                lastReal = p;
                // báo cho phần "chạm xuyên" của app (toạ độ pixel)
                if (!reported[s]) {
                    reported[s] = true;
                    lastRepX[s] = (int) p.x; lastRepY[s] = (int) p.y;
                    out("F " + s + " 1 " + lastRepX[s] + " " + lastRepY[s]);
                } else {
                    lastRepX[s] = (int) p.x; lastRepY[s] = (int) p.y;
                }
            } else if (reported[s] && !rawActive[s]) {
                reported[s] = false;
                lastRealUpAt = SystemClock.uptimeMillis();
                out("F " + s + " 0 " + lastRepX[s] + " " + lastRepY[s]);
            }
        }
        if (!injecting) return; // chế độ thụ động: hệ thống vẫn tự nhận ngón thật

        for (Map.Entry<Integer, P> e : synth.entrySet()) want.put(e.getKey(), e.getValue());

        // Khoá nguồn: 0..15 = ngón thật (slot), 16..23 = click của app
        // 1) nguồn biến mất -> UP (giữ nguyên id gửi đi của các ngón còn lại)
        for (Integer src : new ArrayList<>(idOfSrc.keySet())) {
            if (!want.containsKey(src)) {
                int id = idOfSrc.get(src);
                emit(shown.size() == 1 ? MotionEvent.ACTION_UP : MotionEvent.ACTION_POINTER_UP, id);
                shown.remove(id);
                idOfSrc.remove(src);
                srcOfId.remove(id);
            }
        }
        // 2) nguồn mới -> cấp id nhỏ nhất còn trống rồi DOWN
        for (Map.Entry<Integer, P> e : want.entrySet()) {
            if (!idOfSrc.containsKey(e.getKey())) {
                int id = 0;
                while (shown.containsKey(id)) id++;
                if (shown.isEmpty()) downTime = SystemClock.uptimeMillis();
                idOfSrc.put(e.getKey(), id);
                srcOfId.put(id, e.getKey());
                shown.put(id, e.getValue().copy());
                emit(shown.size() == 1 ? MotionEvent.ACTION_DOWN : MotionEvent.ACTION_POINTER_DOWN, id);
            }
        }
        // 3) di chuyển
        boolean moved = false;
        for (Map.Entry<Integer, P> e : shown.entrySet()) {
            Integer src = srcOfId.get(e.getKey());
            P w = src == null ? null : want.get(src);
            if (w != null && !w.same(e.getValue())) { e.setValue(w.copy()); moved = true; }
        }
        if (moved) emit(MotionEvent.ACTION_MOVE, -1);
    }

    private static void emit(int baseAction, int pointerId) {
        int n = shown.size();
        if (n == 0) return;
        MotionEvent.PointerProperties[] props = new MotionEvent.PointerProperties[n];
        MotionEvent.PointerCoords[] coords = new MotionEvent.PointerCoords[n];
        int idx = 0, actionIndex = 0;
        for (Map.Entry<Integer, P> e : shown.entrySet()) {
            MotionEvent.PointerProperties pp = new MotionEvent.PointerProperties();
            pp.id = e.getKey();
            pp.toolType = MotionEvent.TOOL_TYPE_FINGER;
            MotionEvent.PointerCoords pc = new MotionEvent.PointerCoords();
            P p = e.getValue();
            pc.x = p.x; pc.y = p.y;
            pc.pressure = p.pressure;
            pc.size = p.size;
            pc.touchMajor = p.major; pc.touchMinor = p.minor;
            pc.toolMajor = p.toolMajor; pc.toolMinor = p.minor;
            props[idx] = pp; coords[idx] = pc;
            if (e.getKey() == pointerId) actionIndex = idx;
            idx++;
        }
        int action = baseAction;
        if (baseAction == MotionEvent.ACTION_POINTER_DOWN || baseAction == MotionEvent.ACTION_POINTER_UP) {
            action = baseAction | (actionIndex << MotionEvent.ACTION_POINTER_INDEX_SHIFT);
        }
        long now = SystemClock.uptimeMillis();
        MotionEvent ev = MotionEvent.obtain(downTime, now, action, n, props, coords,
                0, 0, 1f, 1f, deviceId, 0, InputDevice.SOURCE_TOUCHSCREEN, 0);
        try {
            injector.inject(ev);
            injectFails = 0;
        } catch (Throwable t) {
            log("inject fail: " + t);
            if (++injectFails >= MAX_INJECT_FAILS && injecting) {
                // Bộ phát hỏng mà grab vẫn còn = cảm ứng chết cả máy. Nhả grab ngay (đóng fd), huỷ ngón kẹt.
                injecting = false;
                urgentRelease = true;
                log("inject hỏng liên tiếp -> nhả cảm ứng ngay");
                new Thread(InputProxy::shutdown, "zyn-bail").start();
            }
        } finally {
            ev.recycle();
        }
    }

    // ---- click của app --------------------------------------------------------------------------
    private static void doTap(String id, float x, float y, int hold, int gap) {
        int sid = -1;
        try {
            // Click đến ngay sau khi ngón thật vừa nhả (chế độ Thả + chạm xuyên): game đang bận xử lý ngón thật
            // (vd. nút bắn gây khung hình nặng) nên DOWN+UP quá sát/quá ngắn bị nuốt. Chờ cho đủ khoảng cách.
            if (gap > 0) {
                long wait = 0;
                synchronized (LOCK) {
                    boolean realDown = false;
                    for (int i = 0; i < REAL_SLOTS; i++) realDown |= rawActive[i];
                    if (!realDown && lastRealUpAt > 0) wait = lastRealUpAt + gap - SystemClock.uptimeMillis();
                }
                if (wait > 0) SystemClock.sleep(Math.min(wait, gap));
            }
            synchronized (LOCK) {
                if (!injecting) { out("ERR " + id + " passive"); return; }
                for (int i = 0; i < SYNTH_COUNT; i++) {
                    if (!synth.containsKey(SYNTH_BASE + i)) { sid = SYNTH_BASE + i; break; }
                }
                if (sid < 0) { out("ERR " + id + " busy"); return; }
                P p = lastReal != null ? lastReal.copy() : defaultPointer();
                p.x = clampX(x); p.y = clampY(y);
                synth.put(sid, p);
                sync();
            }
            SystemClock.sleep(hold);
            synchronized (LOCK) {
                synth.remove(sid);
                sync();
            }
            out("OK " + id);
        } catch (Throwable t) {
            synchronized (LOCK) { if (sid >= 0) { synth.remove(sid); sync(); } }
            out("ERR " + id + " " + t);
        }
    }

    private static P defaultPointer() {
        P p = new P();
        p.pressure = maxPressure > 0 ? 0.5f : 1f;
        p.size = maxMajor > 0 ? 0.1f : 0f;
        return p;
    }

    // ---- thoát an toàn --------------------------------------------------------------------------
    private static boolean down = false;

    private static void shutdown() {
        synchronized (OUT) {
            if (down) return;
            down = true;
        }
        stopping = true;
        // Chốt chặn cuối: dù bước dọn dưới đây kẹt (khoá LOCK, bộ phát treo...) tiến trình vẫn phải chết,
        // nếu không helper mồ côi giữ grab cảm ứng và lần mở app sau không bơm được.
        Thread killer = new Thread(() -> {
            SystemClock.sleep(3000);
            Runtime.getRuntime().halt(0);
        }, "zyn-kill");
        killer.setDaemon(true);
        killer.start();
        try {
            // Nhả grab khi đã không còn ngón thật chạm (tối đa ~1.5s) để hệ thống không bị kẹt ngón "ma".
            long end = SystemClock.uptimeMillis() + 1500;
            while (!urgentRelease && SystemClock.uptimeMillis() < end) {
                boolean any = false;
                synchronized (LOCK) { for (int i = 0; i < REAL_SLOTS; i++) any |= rawActive[i]; }
                if (!any) break;
                SystemClock.sleep(30);
            }
            synchronized (LOCK) {
                synth.clear();
                if (injecting && !shown.isEmpty()) {
                    emit(MotionEvent.ACTION_CANCEL, -1);
                    shown.clear();
                    idOfSrc.clear();
                    srcOfId.clear();
                }
            }
        } catch (Throwable ignored) {
        }
        try { if (devIn != null) devIn.close(); } catch (Throwable ignored) { } // đóng fd = nhả grab
        try { OUT.println("LOG bye"); } catch (Throwable ignored) { }
        Runtime.getRuntime().halt(0);
    }

    // ---- grab ------------------------------------------------------------------------------------
    /**
     * EVIOCGRAB qua libcore.io.Os.ioctlInt: native truyền CON TRỎ tới int nên arg != 0 -> nghĩa là "grab".
     * Nhả grab = đóng file descriptor (không cần ioctl 0).
     */
    private static boolean grab(FileDescriptor fd) {
        try {
            Class<?> libcore = Class.forName("libcore.io.Libcore");
            Object os = libcore.getField("os").get(null);
            Class<?> osClass = Class.forName("libcore.io.Os");
            for (Method m : osClass.getMethods()) {
                if (!m.getName().equals("ioctlInt")) continue;
                Class<?>[] pt = m.getParameterTypes();
                if (pt.length == 2) {
                    m.invoke(os, fd, EVIOCGRAB);
                    return true;
                } else if (pt.length == 3) {
                    Object ref = pt[2].getConstructor(int.class).newInstance(1);
                    m.invoke(os, fd, EVIOCGRAB, ref);
                    return true;
                }
            }
            log("grab: no ioctlInt");
        } catch (Throwable t) {
            Throwable c = t.getCause() != null ? t.getCause() : t;
            log("grab: " + c);
        }
        return false;
    }

    private static int findDeviceId(String name) {
        try {
            for (int id : InputDevice.getDeviceIds()) {
                InputDevice d = InputDevice.getDevice(id);
                if (d == null) continue;
                boolean touch = (d.getSources() & InputDevice.SOURCE_TOUCHSCREEN) == InputDevice.SOURCE_TOUCHSCREEN;
                if (touch && (name.isEmpty() || name.equals(d.getName()))) return id;
            }
        } catch (Throwable ignored) {
        }
        return 0;
    }

    private static void log(String s) { out("LOG " + s.replace('\n', ' ')); }

    // ---- bộ phát sự kiện vào hệ thống --------------------------------------------------------------
    private static Injector injector;

    /** Tìm hàm injectInputEvent trên InputManagerGlobal (14+), InputManager (≤13) hoặc IInputManager. */
    private static final class Injector {
        private final Object target;
        private final Method method;
        private final boolean withUid;
        private Method setDisplay;

        Injector() throws Exception {
            Object t = null; Method m = null; boolean uid = false;
            String[] classes = {"android.hardware.input.InputManagerGlobal", "android.hardware.input.InputManager"};
            for (String cn : classes) {
                try {
                    Class<?> c = Class.forName(cn);
                    Method gi = c.getDeclaredMethod("getInstance");
                    gi.setAccessible(true);
                    Object inst = gi.invoke(null);
                    Method[] found = find(c);
                    if (inst != null && found[0] != null) { t = inst; m = found[0]; uid = found[1] != null; break; }
                } catch (Throwable ignored) { }
            }
            if (m == null) {
                Class<?> sm = Class.forName("android.os.ServiceManager");
                Object binder = sm.getMethod("getService", String.class).invoke(null, "input");
                Class<?> stub = Class.forName("android.hardware.input.IInputManager$Stub");
                Object svc = stub.getMethod("asInterface", Class.forName("android.os.IBinder")).invoke(null, binder);
                Method[] found = find(Class.forName("android.hardware.input.IInputManager"));
                if (svc != null && found[0] != null) { t = svc; m = found[0]; uid = found[1] != null; }
            }
            if (m == null) throw new IllegalStateException("no injectInputEvent");
            m.setAccessible(true);
            target = t; method = m; withUid = uid;
            try {
                setDisplay = InputEvent.class.getMethod("setDisplayId", int.class);
            } catch (Throwable ignored) { setDisplay = null; }
        }

        /** [0] = hàm dùng được, [1] = non-null nếu hàm có thêm tham số uid. */
        private static Method[] find(Class<?> c) {
            Method two = null, three = null;
            for (Method mm : c.getDeclaredMethods()) {
                String n = mm.getName();
                if (!n.equals("injectInputEvent") && !n.equals("injectInputEventToTarget")) continue;
                Class<?>[] p = mm.getParameterTypes();
                if (p.length >= 2 && InputEvent.class.isAssignableFrom(p[0]) && p[1] == int.class) {
                    if (p.length == 2) two = mm;
                    else if (p.length == 3 && p[2] == int.class) three = mm;
                }
            }
            return two != null ? new Method[]{two, null} : new Method[]{three, three};
        }

        void inject(InputEvent ev) throws Exception {
            if (setDisplay != null) {
                try { setDisplay.invoke(ev, 0); } catch (Throwable ignored) { }
            }
            // mode 0 = ASYNC, uid -1 = INVALID_UID
            Object r = withUid ? method.invoke(target, ev, 0, -1) : method.invoke(target, ev, 0);
            if (r instanceof Boolean && !((Boolean) r)) throw new IllegalStateException("hệ thống từ chối sự kiện");
        }
    }
}
