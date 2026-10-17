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
 *       source cảm ứng của máy). Ngón thật = các pointer id 0..15.</li>
 *   <li>Click của app (lệnh {@code TAP}) được nhét vào CHÍNH luồng đó dưới dạng một pointer thêm
 *       (id 16..23), mang áp lực / kích cỡ clone từ ngón thật gần nhất. Chỉ còn MỘT luồng chạm duy nhất
 *       nên ngón thật không bị mất khi app đang click.</li>
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
    private static final int EV_SYN = 0x00, EV_KEY = 0x01, EV_ABS = 0x03;
    private static final int BTN_TOUCH = 0x14a;
    private static final int SYN_REPORT = 0x00, SYN_DROPPED = 0x03;
    private static final int ABS_MT_SLOT = 0x2f, ABS_MT_TOUCH_MAJOR = 0x30, ABS_MT_TOUCH_MINOR = 0x31;
    private static final int ABS_MT_POSITION_X = 0x35, ABS_MT_POSITION_Y = 0x36;
    private static final int ABS_MT_TRACKING_ID = 0x39, ABS_MT_PRESSURE = 0x3a;
    /** _IOW('E', 0x90, int) */
    private static final int EVIOCGRAB = 0x40044590;

    private static final int REAL_SLOTS = 16;          // ngón thật: pointer id 0..15
    private static final int SYNTH_BASE = 16;          // click của app: pointer id 16..23
    private static final int SYNTH_COUNT = 8;
    /** Click giả tối thiểu phải giữ ngần này ms (tính từ lúc DOWN) trước khi được nhả sớm nhường ngón đầu cho ngón thật. */
    private static final long EARLY_MIN_HOLD_MS = 30;
    /**
     * Mất nhịp tim quá ngần này thì helper tự thoát. Helper vẫn phát lại ngón thật bình thường trong lúc chờ nên nới rộng
     * không làm đơ gì; ngắn quá thì Wi-Fi chập chờn / game ngốn CPU là helper bị tắt oan giữa lúc ngón đang chạm.
     */
    private static final long WATCHDOG_MS = 6000;
    /** Thời gian tối đa chờ ngón thật nhấc lên (reader VẪN phát lại) trước khi nhả grab lúc thoát. */
    private static final long DRAIN_MS = 1500;
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
    private static int injectFails = 0;   // chỉ đụng trong luồng gửi (zyn-send)

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

    private static final TreeMap<Integer, P> shown = new TreeMap<>();   // hệ thống đang thấy
    private static final HashMap<Integer, P> synth = new HashMap<>();   // click của app đang giữ
    private static long downTime = 0;
    private static long lastRealUpAt = 0;   // uptime lúc ngón thật gần nhất vừa nhả (chỉ đụng dưới LOCK)
    private static P lastReal = null;       // áp lực / kích cỡ của ngón thật gần nhất (để clone)
    private static int deviceId = 0;

    private static FileInputStream devIn;
    private static volatile boolean injecting = false;
    private static boolean grabbed = false;
    /** Đã yêu cầu thoát: không nhận lệnh nữa, NHƯNG reader vẫn chạy cho tới khi nhả grab ([released]). */
    private static volatile boolean stopping = false;
    /** Đã nhả grab (đóng fd): chỉ lúc này reader mới được dừng. */
    private static volatile boolean released = false;
    /** Sau SYN_DROPPED: bỏ mọi sự kiện cho tới SYN_REPORT kế tiếp (chỉ đụng dưới LOCK). */
    private static boolean discarding = false;
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
            if (grabbed && !clearStaleAfterGrab()) {
                // Grab được mà bộ phát không chạy = mất cảm ứng toàn máy. Nhả grab ngay (đóng + mở lại fd), chạy thụ động.
                log("bộ phát không hoạt động sau grab -> nhả grab, chạy thụ động");
                injecting = false;
                grabbed = false;
                try { devIn.close(); } catch (Throwable ignored) { }
                devIn = new FileInputStream(devPath);
            }
        }
        OUT.println("READY grab=" + (grabbed ? 1 : 0) + " inj=" + (injecting ? 1 : 0) + " dev=" + deviceId);

        startOutThread();
        if (injecting) startSender();
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
                shutdown(); // mất nhịp tim: chờ ngón thật nhấc (reader vẫn phát lại) rồi nhả grab
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
            // Chạy cho tới khi đã nhả grab ([released]), KHÔNG dừng ngay khi [stopping]: đang thoát mà grab còn giữ thì
            // reader mà dừng là cảm ứng cả máy đơ cho tới khi nhả grab.
            while (!released) {
                int r = devIn.read(buf);
                if (r < 0) break;
                if (r == 0) continue;
                if (n + r > acc.length) n = 0; // không thể xảy ra, phòng hờ
                System.arraycopy(buf, 0, acc, n, r);
                n += r;
                if (recSize == 0) {
                    if (n < 48) continue;
                    recSize = valid(acc, 24) ? 24 : valid(acc, 16) ? 16 : -1;
                    if (recSize < 0) { out("FAIL not input_event"); urgentRelease = true; shutdown(); return; }
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
        urgentRelease = true; // reader đã chết: không còn gì để phát lại, nhả grab ngay
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
            if (type == EV_SYN && code == SYN_DROPPED) {
                // Kernel tràn bộ đệm: các sự kiện tới SYN_REPORT kế tiếp phải bỏ. KHÔNG coi mọi ngón là đã nhả
                // (trước đây làm ngón đang kéo bị "chết" cho tới khi nhấc lên chạm lại vì tracking id không gửi lại).
                // Ngón nhả đúng lúc mất sự kiện thì BTN_TOUCH=0 / TRACKING_ID=-1 kế tiếp sẽ dọn.
                discarding = true;
                return;
            }
            if (discarding) {
                if (type == EV_SYN && code == SYN_REPORT) discarding = false;
                return;
            }
            if (type == EV_KEY) {
                // BTN_TOUCH=0: không còn ngón nào chạm -> dọn hết, phòng khi mất sự kiện nhả của một ngón
                if (code == BTN_TOUCH && value == 0) {
                    for (int i = 0; i < REAL_SLOTS; i++) { rawActive[i] = false; tid[i] = -1; }
                }
            } else if (type == EV_ABS) {
                switch (code) {
                    case ABS_MT_SLOT:
                        curSlot = Math.max(0, Math.min(REAL_SLOTS, value));
                        break;
                    case ABS_MT_TRACKING_ID:
                        if (curSlot >= REAL_SLOTS) break;
                        if (value == -1) {
                            rawActive[curSlot] = false;
                            tid[curSlot] = -1;
                        } else if (!rawActive[curSlot] || tid[curSlot] != value) {
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
                if (code == SYN_REPORT) sync();
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

        // Chống kẹt ngón thật: nếu hệ thống đang chỉ thấy click giả (nó là "ngón đầu") mà ngón thật mới chạm xuống,
        // KHÔNG chèn ngón thật làm ngón phụ phía sau click giả. Game hay coi ngón đầu nhả = hết cử chỉ nên bỏ rơi ngón phụ
        // (vuốt camera bị khựng/đơ tới khi nhấc ngón chạm lại). Hoãn ngón thật vài chục ms; ngay khi click giả nhả, ngón thật
        // xuống như một ngón đầu sạch (ACTION_DOWN) và doTap sẽ nhả click sớm cho nó (xem [realWaiting]).
        if (!synth.isEmpty() && !shown.isEmpty() && shown.firstKey() >= SYNTH_BASE) {
            for (java.util.Iterator<Integer> it = want.keySet().iterator(); it.hasNext(); ) {
                int k = it.next();
                if (k < SYNTH_BASE && !shown.containsKey(k)) it.remove();
            }
        }

        // 1) pointer biến mất -> UP
        for (Integer id : new ArrayList<>(shown.keySet())) {
            if (!want.containsKey(id)) {
                emit(shown.size() == 1 ? MotionEvent.ACTION_UP : MotionEvent.ACTION_POINTER_UP, id);
                shown.remove(id);
            }
        }
        // 2) pointer mới -> DOWN
        for (Map.Entry<Integer, P> e : want.entrySet()) {
            if (!shown.containsKey(e.getKey())) {
                if (shown.isEmpty()) downTime = SystemClock.uptimeMillis();
                shown.put(e.getKey(), e.getValue().copy());
                emit(shown.size() == 1 ? MotionEvent.ACTION_DOWN : MotionEvent.ACTION_POINTER_DOWN, e.getKey());
            }
        }
        // 3) di chuyển
        boolean moved = false;
        for (Map.Entry<Integer, P> e : shown.entrySet()) {
            P w = want.get(e.getKey());
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
        enqueue(ev);
    }

    // ---- hàng đợi gửi: tách việc bơm (gọi binder, có thể chậm) khỏi luồng đọc cảm ứng ---------------
    private static final java.util.ArrayDeque<MotionEvent> SENDQ = new java.util.ArrayDeque<>();
    private static final Object QLOCK = new Object();
    private static boolean sending = false;          // chỉ đụng dưới QLOCK
    private static volatile boolean senderOn = false;

    /**
     * Xếp sự kiện vào hàng đợi (gọi dưới LOCK, không bao giờ chặn). Luồng đọc không còn bị kẹt khi system_server bận:
     * trước đây bơm ngay trong LOCK, binder chậm một nhịp là kernel tràn bộ đệm -> SYN_DROPPED -> ngón thật mất.
     * Hàng đợi dài thì gộp các MOVE liền nhau (MOVE mang trọn trạng thái mọi pointer nên gộp không mất gì).
     */
    private static void enqueue(MotionEvent ev) {
        synchronized (QLOCK) {
            MotionEvent last = SENDQ.peekLast();
            if (last != null && SENDQ.size() >= 4
                    && ev.getActionMasked() == MotionEvent.ACTION_MOVE
                    && last.getActionMasked() == MotionEvent.ACTION_MOVE) {
                SENDQ.pollLast().recycle();
            }
            SENDQ.addLast(ev);
            QLOCK.notifyAll();
        }
    }

    private static void startSender() {
        senderOn = true;
        Thread t = new Thread(InputProxy::senderLoop, "zyn-send");
        t.setDaemon(true);
        t.start();
    }

    private static void senderLoop() {
        while (true) {
            MotionEvent ev;
            synchronized (QLOCK) {
                while (SENDQ.isEmpty()) {
                    if (released) return;
                    try { QLOCK.wait(200); } catch (InterruptedException e) { return; }
                }
                ev = SENDQ.pollFirst();
                sending = true;
            }
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
                synchronized (QLOCK) { sending = false; QLOCK.notifyAll(); }
            }
        }
    }

    /** Chờ hàng đợi gửi cạn (tối đa [maxMs]) để CANCEL/UP cuối cùng chắc chắn tới hệ thống trước khi nhả grab. */
    private static void flushQueue(long maxMs) {
        if (!senderOn) return;
        long end = SystemClock.uptimeMillis() + maxMs;
        synchronized (QLOCK) {
            while (!SENDQ.isEmpty() || sending) {
                long left = end - SystemClock.uptimeMillis();
                if (left <= 0) break;
                try { QLOCK.wait(left); } catch (InterruptedException e) { break; }
            }
        }
    }

    /**
     * Ngay sau grab: bơm một ACTION_CANCEL cho thiết bị cảm ứng để xoá trạng thái "ngón đang chạm" cũ trong InputDispatcher.
     * Nếu lúc grab có ngón đang đặt trên màn hình, InputReader không bao giờ nhận được sự kiện nhả của ngón đó -> hệ thống
     * coi thiết bị vẫn đang chạm, và mọi DOWN do helper bơm sau đó bị coi là "down khi đang down" rồi bị bỏ (ngón thật + click
     * đều chết). CANCEL khi không có gì đang chạm thì bị hệ thống lặng lẽ bỏ qua nên vô hại.
     * @return false nếu bơm báo lỗi (bộ phát không dùng được).
     */
    private static boolean clearStaleAfterGrab() {
        MotionEvent.PointerProperties[] props = { new MotionEvent.PointerProperties() };
        MotionEvent.PointerCoords[] coords = { new MotionEvent.PointerCoords() };
        props[0].id = 0;
        props[0].toolType = MotionEvent.TOOL_TYPE_FINGER;
        coords[0].x = scrW / 2f;
        coords[0].y = scrH / 2f;
        long now = SystemClock.uptimeMillis();
        MotionEvent ev = MotionEvent.obtain(now, now, MotionEvent.ACTION_CANCEL, 1, props, coords,
                0, 0, 1f, 1f, deviceId, 0, InputDevice.SOURCE_TOUCHSCREEN, 0);
        try {
            // ACTION_CANCEL hợp lệ cần FLAG_CANCELED (0x20); một số ROM (Android 14+) từ chối CANCEL thiếu cờ này.
            try { MotionEvent.class.getMethod("setFlags", int.class).invoke(ev, 0x20); } catch (Throwable ignored) { }
            boolean accepted = injector.injectRaw(ev);
            if (!accepted) {
                // Hệ thống trả false cho CANCEL khi KHÔNG có ngón nào đang chạm là bình thường trên nhiều ROM -> không phải bộ phát hỏng.
                // Chỉ ngoại lệ (SecurityException, hàm biến mất...) mới là hỏng thật. Nếu bộ phát hỏng thật thì senderLoop
                // đã có cơ chế nhả grab sau MAX_INJECT_FAILS lần lỗi liên tiếp nên vẫn an toàn.
                log("probe inject: hệ thống trả false cho CANCEL (không có ngón đang chạm) -> bỏ qua, vẫn bơm");
            }
            return true;
        } catch (Throwable t) {
            log("probe inject: " + t);
            return false;
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
                if (stopping) { out("ERR " + id + " stopping"); return; }
                for (int i = 0; i < SYNTH_COUNT; i++) {
                    if (!synth.containsKey(SYNTH_BASE + i)) { sid = SYNTH_BASE + i; break; }
                }
                if (sid < 0) { out("ERR " + id + " busy"); return; }
                P p = lastReal != null ? lastReal.copy() : defaultPointer();
                p.x = clampX(x); p.y = clampY(y);
                synth.put(sid, p);
                sync();
            }
            // Giữ đủ [hold], nhưng nếu ngón thật chạm xuống trong lúc này (đang bị hoãn trong sync) thì nhả click sớm
            // (sau tối thiểu EARLY_MIN_HOLD_MS để game vẫn kịp nhận) để ngón thật tiếp quản làm ngón đầu ngay.
            long start = SystemClock.uptimeMillis();
            long minHold = Math.min(hold, EARLY_MIN_HOLD_MS);
            while (true) {
                long el = SystemClock.uptimeMillis() - start;
                if (el >= hold) break;
                if (el >= minHold) {
                    boolean waiting;
                    synchronized (LOCK) { waiting = realWaiting(); }
                    if (waiting) break;
                }
                SystemClock.sleep(Math.max(1, Math.min(4, hold - el)));
            }
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

    /** Có ngón thật đang chạm mà chưa được gửi cho hệ thống (đang bị hoãn vì click giả giữ vai ngón đầu). Gọi dưới LOCK. */
    private static boolean realWaiting() {
        for (int s = 0; s < REAL_SLOTS; s++) {
            if (rawActive[s] && hasX[s] && hasY[s] && !shown.containsKey(s)) return true;
        }
        return false;
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
            SystemClock.sleep(DRAIN_MS + 1500);
            Runtime.getRuntime().halt(0);
        }, "zyn-kill");
        killer.setDaemon(true);
        killer.start();
        try {
            // 1) Huỷ click của app ngay (UP cho pointer giả). Ngón thật KHÔNG bị đụng tới.
            synchronized (LOCK) {
                if (!synth.isEmpty()) {
                    synth.clear();
                    sync();
                }
            }
            // 2) Chờ ngón thật nhấc lên rồi mới nhả grab. Reader VẪN đang chạy và phát lại ngón thật trong lúc chờ
            //    (bản cũ dừng reader trước khi chờ -> cảm ứng cả máy đứng hình tới 1.5s mỗi lần helper tắt/khởi động lại).
            long end = SystemClock.uptimeMillis() + DRAIN_MS;
            while (!urgentRelease && SystemClock.uptimeMillis() < end) {
                boolean any = false;
                synchronized (LOCK) { for (int i = 0; i < REAL_SLOTS; i++) any |= rawActive[i]; }
                if (!any) break;
                SystemClock.sleep(15);
            }
            // 3) Còn ngón thật đặt trên màn hình (quá hạn / khẩn cấp) -> báo CANCEL cho hệ thống để không kẹt ngón.
            synchronized (LOCK) {
                synth.clear();
                if (injecting && !shown.isEmpty()) {
                    emit(MotionEvent.ACTION_CANCEL, -1);
                    shown.clear();
                }
                injecting = false;
            }
            flushQueue(400);
        } catch (Throwable ignored) {
        }
        released = true;
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
            if (!injectRaw(ev)) throw new IllegalStateException("hệ thống từ chối sự kiện");
        }

        /** @return false nếu hệ thống trả false (từ chối); ném ngoại lệ nếu gọi hàm thất bại. */
        boolean injectRaw(InputEvent ev) throws Exception {
            if (setDisplay != null) {
                try { setDisplay.invoke(ev, 0); } catch (Throwable ignored) { }
            }
            // mode 0 = ASYNC, uid -1 = INVALID_UID
            Object r = withUid ? method.invoke(target, ev, 0, -1) : method.invoke(target, ev, 0);
            return !(r instanceof Boolean) || (Boolean) r;
        }
    }
}
