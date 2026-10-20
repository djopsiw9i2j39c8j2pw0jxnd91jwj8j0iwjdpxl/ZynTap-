package com.zyntap.app.shell;

import android.os.SystemClock;
import android.view.InputDevice;
import android.os.Build;
import android.view.InputEvent;
import android.view.MotionEvent;

import java.io.BufferedReader;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.ArrayList;

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
 *       source cảm ứng của máy).</li>
 *   <li>Click của app (lệnh {@code TAP}) là một NGÓN ẢO nằm chung trong CHÍNH luồng đó: ngón thật và ngón ảo
 *       được xử lý cùng một chỗ ({@link #sync}), gộp vào cùng một {@link MotionEvent}. Không còn ép ngón nào lên đầu:
 *       ngón nào chạm xuống trước thì đứng trước, ngón chạm sau thì đứng sau; không ai phải nhường ai.</li>
 * </ol>
 *
 * Mã pointer (id) KHÔNG còn được chia sẵn cho ngón thật / ngón ảo: mỗi ngón chạm xuống được cấp id nhỏ nhất đang
 * trống (0, 1, 2...) và giữ nguyên tới khi nhấc, đúng như hệ thống cảm ứng của Android tự cấp. Click của app cũng
 * không còn số thứ tự riêng, không còn "clone" áp lực / kích cỡ từ ngón thật.
 *
 * An toàn: grab là của file descriptor, tiến trình chết / thoát là kernel tự nhả grab. Mất heartbeat
 * (PING) quá {@link #WATCHDOG_MS} hoặc stdin đóng thì tự thoát.
 *
 * Giao thức (mỗi dòng, UTF-8):
 * <pre>
 *   app -> helper:  PING | ROT r w h | ZONE ... | ASSIST ... | RUN id repeat holdMs gapMs op... | STOP id | STOPALL | QUIT
 *   (op: T:x,y = 1 click | M:x,y;x,y;... = nhiều click CÙNG LÚC | W:ms = chờ. Cả kịch bản chạy TRONG helper: giữa các click
 *    không còn độ trễ ADB, không còn nghỉ nào ngoài W do người dùng đặt)
 *   (gapMs: click chờ cho tới khi ngón thật đã nhả ít nhất gapMs, để game kịp xử lý ngón thật trước)
 *   helper -> app:  READY grab=0|1 inj=0|1 dev=n | FAIL msg | F slot 1|0 x y | ERR msg | LOG msg
 * </pre>
 * RUN là "bắn rồi quên": không có số thứ tự, không có phản hồi. Click lỗi (bộ phát từ chối, hàng đợi đầy, hết chỗ
 * pointer, chế độ thụ động...) thì CHỈ BỎ click đó (kèm một dòng {@code ERR}), không thử lại, không có đường lui nào khác.
 * Tọa độ x,y (F, TAP) là PIXEL màn hình hiện tại (đã tính xoay).
 *
 * Nếu grab hoặc inject không dùng được (ROM chặn) -> chế độ THỤ ĐỘNG: không grab, không tự phát lại,
 * chỉ báo ngón chạm (F) như cũ; RUN bị bỏ.
 */
public final class InputProxy {

    // ---- hằng số linux/input ---------------------------------------------------------------
    private static final int EV_SYN = 0x00, EV_KEY = 0x01, EV_ABS = 0x03;
    private static final int SYN_REPORT = 0x00, SYN_DROPPED = 0x03;
    private static final int BTN_TOUCH = 0x14a;
    private static final int ABS_MT_SLOT = 0x2f, ABS_MT_TOUCH_MAJOR = 0x30, ABS_MT_TOUCH_MINOR = 0x31;
    private static final int ABS_MT_POSITION_X = 0x35, ABS_MT_POSITION_Y = 0x36;
    private static final int ABS_MT_TRACKING_ID = 0x39, ABS_MT_PRESSURE = 0x3a;
    /** _IOW('E', 0x90, int) */
    private static final int EVIOCGRAB = 0x40044590;

    private static final int REAL_SLOTS = 16;          // số slot ngón thật theo dõi
    /** Hệ thống chỉ nhận tối đa chừng này pointer trong một MotionEvent (MAX_POINTERS của InputDispatcher). */
    private static final int MAX_POINTERS = 16;
    private static final long WATCHDOG_MS = 3000;
    /** Số lần phát sự kiện thất bại liên tiếp thì coi bộ phát đã hỏng và nhả grab NGAY (tránh liệt cảm ứng toàn máy). */
    private static final int MAX_INJECT_FAILS = 4;

    // ---- click ------------------------------------------------------------------------------------
    /** Không ép giữ tối thiểu: hold = 0 thì DOWN rồi UP liền nhau, không chờ. */
    private static final int MIN_HOLD_MS = 0;
    /** Không ép khoảng nhả giữa 2 click: click sau chạm xuống ngay khi click trước vừa nhả. */
    private static final int MIN_RELEASE_GAP_MS = 0;
    /** Số kịch bản chạy đồng thời tối đa (mỗi nút main một kịch bản). */
    private static final int MAX_RUNS = 64;
    /** Số điểm tối đa của một cụm click cùng lúc (chừa chỗ cho ngón thật; hệ thống nhận tối đa [MAX_POINTERS]). */
    private static final int MAX_GROUP = 10;
    /** Hàng đợi bơm dài hơn mức này thì tạm dừng click mới (chống dồn sự kiện vô hạn khi chạy tốc độ tối đa). */
    private static final int MAX_INJ_BACKLOG = 24;

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
    private static final java.util.concurrent.BlockingQueue<String> OUTQ = new java.util.concurrent.ArrayBlockingQueue<>(512);
    private static volatile boolean outThreadOn = false;
    private static volatile boolean urgentRelease = false;
    /** Hệ thống vừa từ chối/lỡ một sự kiện DOWN/UP -> luồng chạm bên hệ thống đã lệch khỏi [shown]. Chỉ đụng dưới LOCK. */
    private static boolean desync = false;
    private static long lastRebuildAt = 0;
    /** Đang bỏ các sự kiện sau SYN_DROPPED cho tới SYN_REPORT kế tiếp. Chỉ đụng dưới LOCK. */
    private static boolean dropping = false;
    /**
     * Sau SYN_DROPPED không biết kernel đang đứng ở slot nào (lệnh ABS_MT_SLOT có thể nằm trong gói bị bỏ, mà kernel chỉ
     * phát lại ABS_MT_SLOT khi slot ĐỔI) -> toạ độ đến sau bị ghi nhầm sang ngón khác. Cờ này bật tới khi gặp ABS_MT_SLOT kế tiếp.
     */
    private static boolean slotUnsure = false;
    private static boolean pickedInPacket = false;
    /**
     * Trong gói SYN hiện tại: slot nào vừa nhả / BTN_TOUCH vừa về 0. Ngón vừa nhả không được "nhận nuôi" lại
     * từ toạ độ đi kèm trong cùng gói.
     */
    private static final boolean[] upInPacket = new boolean[16];
    private static boolean btnUpInPacket = false;
    /** Slot bị BTN_TOUCH=0 xoá: toạ độ cũ còn dùng được nếu hoá ra ngón đó vẫn đang đè. */
    private static final boolean[] keepXY = new boolean[16];

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
    /**
     * Ngón thật đặt xuống TRONG vùng nút main (nút chỉ đè lên điểm nhấn, không bật chạm xuyên): vẫn báo F cho app
     * nhưng KHÔNG phát xuống hệ thống, để ngón không lọt xuống app bên dưới. Click bơm thì vẫn đi xuống dưới nút.
     */
    private static final boolean[] swallowed = new boolean[REAL_SLOTS];
    /** Các vùng tròn {x, y, r} (pixel màn hình hiện tại), phẳng. Chỉ đọc/ghi dưới LOCK. */
    private static float[] swallowZones = new float[0];
    /**
     * Trợ vuốt tâm: các vùng tròn (nút main bật trợ vuốt), mỗi vùng 9 số:
     * x, y, r, mode (0 kéo tâm / 1 ghìm tâm / 2 buff lực), tốc độ (px/s), giới hạn lệch (px), trễ (ms), buff (%), ngưỡng vuốt (px).
     * Chỉ ngón thật CHẠM XUỐNG trong vùng mới được can thiệp (chốt một lần lúc chạm xuống); ngón khác không bị đụng tới.
     */
    private static float[] assistZones = new float[0];
    private static final int[] aMode = new int[REAL_SLOTS];     // -1 = không can thiệp
    private static final float[] aSpeed = new float[REAL_SLOTS];
    private static final float[] aMax = new float[REAL_SLOTS];
    private static final float[] aDelay = new float[REAL_SLOTS];
    private static final float[] aGain = new float[REAL_SLOTS];
    private static final float[] aSlop = new float[REAL_SLOTS];
    private static final long[] aMoveT = new long[REAL_SLOTS];  // lúc ngón bắt đầu vuốt ra khỏi ngưỡng; 0 = chưa
    static { java.util.Arrays.fill(aMode, -1); }
    private static final float[] downX = new float[REAL_SLOTS];
    private static final float[] downY = new float[REAL_SLOTS];
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

    /** Một click của app (ngón ảo). */
    private static final class Tap {
        final P p;
        final int hold;
        final int gap;
        long releaseAt = 0;       // uptime lúc phải nhả
        boolean down = false;     // đã được phép chạm xuống
        boolean finished = false; // đã tới giờ nhả (hoặc không còn chỗ để chạm)
        Tap(P p, int hold, int gap) { this.p = p; this.hold = hold; this.gap = gap; }
    }

    /**
     * Một ngón đang hiện bên hệ thống: thật (theo slot) hoặc ảo (theo click). Mã pointer [pid] do helper tự cấp
     * (nhỏ nhất đang trống) lúc chạm xuống và giữ nguyên tới khi nhấc.
     */
    private static final class Finger {
        final int slot;     // slot ngón thật; -1 = ngón ảo
        final Tap tap;      // click tương ứng; null = ngón thật
        final int pid;
        P sent;             // toạ độ đã gửi cho hệ thống
        Finger(int slot, Tap tap, int pid, P sent) { this.slot = slot; this.tap = tap; this.pid = pid; this.sent = sent; }
        boolean real() { return tap == null; }
    }

    /**
     * Hệ thống đang thấy. Xếp theo THỨ TỰ CHẠM XUỐNG: ngón nào chạm trước đứng trước, chạm sau đứng sau,
     * không phân biệt thật hay ảo. Chỉ đụng dưới LOCK.
     */
    private static final ArrayList<Finger> shown = new ArrayList<>();
    private static int pidMask = 0;             // bit i = pointer id i đang dùng
    private static long downTime = 0;
    private static long lastRealUpAt = 0;       // uptime lúc ngón thật gần nhất vừa nhả (chỉ đụng dưới LOCK)
    private static int deviceId = 0;

    /**
     * Mã thiết bị dùng cho sự kiện BƠM vào hệ thống (khác [deviceId] của tấm cảm ứng thật).
     * Android 14+ kiểm tra tính nhất quán luồng chạm của mọi sự kiện bơm không kèm targetUid (InputVerifier, khoá theo deviceId):
     * DOWN khi deviceId đó đang "chạm dở" -> bị từ chối (FAILED), CANCEL thiếu FLAG_CANCELED -> bị từ chối.
     * Dùng thẳng deviceId thật thì chỉ cần MỘT lần luồng bị lệch (CANCEL/UP bị từ chối, helper chết giữa chừng...) là trạng thái
     * "đang chạm" kẹt lại trong system_server và MỌI DOWN sau đó đều bị từ chối cho tới khi khởi động lại máy.
     * Nên dùng mã ảo riêng, đổi mới mỗi lần helper chạy và mỗi lần phải dựng lại luồng -> luôn bắt đầu từ trạng thái sạch.
     */
    private static int injDev = 0;
    private static int injGen = 0;
    private static final int INJ_DEV_BASE = 0x5A000000;
    private static final int FLAG_CANCELED = 0x20; // MotionEvent.FLAG_CANCELED (@hide) — bắt buộc với ACTION_CANCEL

    private static int nextInjDev() {
        int pid = android.os.Process.myPid() & 0xFFFF;
        return INJ_DEV_BASE + (pid << 6) + ((injGen++) & 0x3F);
    }

    /** Một bước của kịch bản: cụm click (1 hoặc nhiều điểm CÙNG LÚC) hoặc chờ. */
    private static final class Op {
        final float[] xy;     // cặp x,y; null = bước chờ
        final long waitMs;
        Op(float[] xy, long waitMs) { this.xy = xy; this.waitMs = waitMs; }
        boolean isWait() { return xy == null; }
    }

    /** Một kịch bản đang chạy trong helper (của một nút main). */
    private static final class Run {
        final int id;
        final boolean repeat;
        final int hold;
        final int gap;
        final Op[] ops;
        int idx = 0;
        long notBefore = 0;   // đang chờ (bước W) tới lúc này
        boolean done = false;
        Run(int id, boolean repeat, int hold, int gap, Op[] ops) {
            this.id = id; this.repeat = repeat; this.hold = hold; this.gap = gap; this.ops = ops;
        }
    }

    /** Kịch bản đang chạy + cụm click đang giữ (cả cụm chạm xuống cùng lúc, nhả cùng lúc). Chỉ đụng dưới LOCK. */
    private static final ArrayList<Run> runs = new ArrayList<>();
    private static final ArrayList<Tap> curTaps = new ArrayList<>();
    private static long curReleaseAt = 0;
    private static int rrIndex = 0;
    private static long lastTapUpAt = 0;
    private static long lastErrAt = 0;
    private static int errSkipped = 0;

    private static FileInputStream devIn;
    private static boolean injecting = false;
    private static boolean grabbed = false;
    private static volatile boolean stopping = false;
    /** Chỉ bật ngay trước khi đóng fd: trong lúc chờ nhả grab, luồng đọc vẫn phải tiếp tục phát ngón thật. */
    private static volatile boolean closed = false;
    private static volatile long lastPing = SystemClock.uptimeMillis();

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
        injDev = nextInjDev();

        // Chuẩn bị bộ phát; nếu không có thì không grab (grab mà không phát lại được = mất cảm ứng).
        Injector inj = null;
        try { inj = new Injector(); } catch (Throwable t) { log("inject: " + t); }
        if (inj != null) {
            injector = inj;
            grabbed = grab(devIn.getFD());
            injecting = grabbed;
        }
        OUT.println("READY grab=" + (grabbed ? 1 : 0) + " inj=" + (injecting ? 1 : 0) + " dev=" + deviceId);
        log("bơm bằng mã thiết bị ảo " + injDev + " (thật: " + deviceId + ")");

        startOutThread();
        if (injecting) startInjectThread();
        Thread reader = new Thread(InputProxy::readLoop, "zyn-reader");
        reader.setDaemon(true);
        reader.start();
        Thread pump = new Thread(InputProxy::pump, "zyn-pump");
        pump.setDaemon(true);
        pump.start();
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
                        runs.clear(); // toạ độ kịch bản thuộc chiều cũ: dừng hết
                        sync(); // ngón đang đè phải đổi sang toạ độ của chiều mới ngay, không chờ sự kiện cảm ứng kế
                    }
                    break;
                case "ZONE": {
                    // ZONE x y r [x y r ...]  (không tham số = xoá hết vùng)
                    int n = (t.length - 1) / 3;
                    float[] z = new float[n * 3];
                    for (int i = 0; i < z.length; i++) z[i] = Float.parseFloat(t[1 + i]);
                    synchronized (LOCK) { swallowZones = z; }
                    break;
                }
                case "ASSIST": {
                    // ASSIST x y r mode speedPxS maxPx delayMs gainPct slopPx [...lặp]  (không tham số = xoá hết)
                    int n = (t.length - 1) / 9;
                    float[] z = new float[n * 9];
                    for (int i = 0; i < z.length; i++) z[i] = Float.parseFloat(t[1 + i]);
                    synchronized (LOCK) { assistZones = z; }
                    break;
                }
                case "RUN": {
                    // RUN id repeat(0|1) holdMs gapMs op...  — không có phản hồi
                    final int id = Integer.parseInt(t[1]);
                    final boolean repeat = t[2].equals("1");
                    final int hold = Math.max(MIN_HOLD_MS, Math.min(2000, Integer.parseInt(t[3])));
                    final int gap = Math.max(0, Math.min(500, Integer.parseInt(t[4])));
                    startRun(id, repeat, hold, gap, parseOps(t, 5));
                    break;
                }
                case "STOP":
                    stopRun(Integer.parseInt(t[1]));
                    break;
                case "STOPALL":
                    synchronized (LOCK) { runs.clear(); }
                    break;
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
            // Chạy tới khi fd thật sự bị đóng (closed), KHÔNG dừng ngay khi stopping: lúc shutdown() còn chờ nhả grab,
            // nếu ngừng đọc thì ngón thật không được phát nữa trong khi grab vẫn giữ = đơ cảm ứng tới 1.5s.
            while (!closed) {
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
            if (!closed) log("read: " + e);
        }
        if (!closed) urgentRelease = true; // tấm cảm ứng không còn đọc được: chờ ngón nhấc cũng vô nghĩa
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

    /** Tấm cảm ứng đẩy sự kiện thô vào đây (gọi từ luồng đọc). */
    static void onEvent(int type, int code, int value) {
        synchronized (LOCK) {
            if (dropping) {
                // Sau SYN_DROPPED gói đang dở đã hỏng: bỏ mọi sự kiện tới hết SYN_REPORT kế tiếp rồi đọc tiếp bình thường.
                if (type == EV_SYN && code == SYN_REPORT) dropping = false;
                return;
            }
            if (type == EV_ABS) {
                switch (code) {
                    case ABS_MT_SLOT:
                        curSlot = Math.max(0, Math.min(REAL_SLOTS, value));
                        slotUnsure = false;
                        break;
                    case ABS_MT_TRACKING_ID:
                        if (curSlot >= REAL_SLOTS) break;
                        if (value == -1) {
                            rawActive[curSlot] = false;
                            tid[curSlot] = -1;
                            upInPacket[curSlot] = true;
                        } else if (!rawActive[curSlot]) {
                            startRaw(curSlot, value);
                        } else if (tid[curSlot] != value) {
                            // Slot đổi sang ngón khác mà không thấy báo nhả (gói bị rơi): nhả ngón cũ ngay rồi chạm lại,
                            // nếu không ngón cũ thành ngón "ma" dính hệ thống.
                            rawActive[curSlot] = false;
                            tid[curSlot] = -1;
                            sync();
                            startRaw(curSlot, value);
                        }
                        break;
                    case ABS_MT_POSITION_X:
                        if (slotUnsure && !pickedInPacket) { pickSlotNearest(value, true); pickedInPacket = true; }
                        if (curSlot < REAL_SLOTS) { adoptIfOrphan(curSlot); rawX[curSlot] = value; hasX[curSlot] = true; }
                        break;
                    case ABS_MT_POSITION_Y:
                        if (slotUnsure && !pickedInPacket) { pickSlotNearest(value, false); pickedInPacket = true; }
                        if (curSlot < REAL_SLOTS) { adoptIfOrphan(curSlot); rawY[curSlot] = value; hasY[curSlot] = true; }
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
            } else if (type == EV_KEY) {
                // BTN_TOUCH = 0: không còn ngón nào chạm. Dọn hết ngón còn kẹt (vd. lệnh nhả bị rơi trong gói SYN_DROPPED).
                if (code == BTN_TOUCH && value == 0) {
                    btnUpInPacket = true;
                    for (int s = 0; s < REAL_SLOTS; s++) {
                        // keepXY: nếu ngón này thật ra vẫn đè (BTN_TOUCH báo nhầm) thì còn toạ độ cũ để nhận nuôi lại ngay
                        if (rawActive[s]) { rawActive[s] = false; tid[s] = -1; keepXY[s] = true; }
                    }
                }
            } else if (type == EV_SYN) {
                if (code == SYN_DROPPED) {
                    // Bộ đệm cảm ứng bị tràn (hệ thống/binder chậm). Giữ nguyên trạng thái các ngón, chỉ bỏ gói dở dang;
                    // ngón nào nhấc trong lúc tràn sẽ được dọn ở BTN_TOUCH=0 / lần đổi tracking id kế tiếp.
                    // Lệnh đổi slot có thể đã nằm trong gói bị bỏ -> chưa biết slot hiện tại cho tới khi gặp ABS_MT_SLOT kế tiếp.
                    dropping = true;
                    slotUnsure = true;
                    log("SYN_DROPPED: bộ đệm cảm ứng tràn -> giữ nguyên các ngón đang chạm");
                } else if (code == SYN_REPORT) {
                    sync();
                    java.util.Arrays.fill(upInPacket, false);
                    btnUpInPacket = false;
                    pickedInPacket = false;
                }
            }
        }
    }

    /**
     * Gọi dưới LOCK khi có toạ độ tới cho một slot mà helper tưởng là KHÔNG có ngón. Kernel chỉ gửi ABS_MT_TRACKING_ID lúc ngón
     * chạm xuống, nên nếu helper lỡ mất nó thì ngón đó chết hẳn (không vuốt được) cho tới khi nhấc tay lên chạm lại. Các trường hợp
     * lỡ: helper khởi động / grab lúc ngón ĐANG đè, SYN_DROPPED làm rơi lệnh chạm, BTN_TOUCH=0 báo nhầm. Giờ có toạ độ tới là coi
     * như ngón đó đang chạm: nhận nuôi nó (như vừa chạm xuống tại chỗ đó). Không nhận nuôi khi ngón VỪA nhả trong cùng gói.
     */
    private static void adoptIfOrphan(int s) {
        if (rawActive[s] || upInPacket[s] || btnUpInPacket) return;
        boolean kx = keepXY[s] && hasX[s], ky = keepXY[s] && hasY[s];
        startRaw(s, -2);
        hasX[s] = kx;
        hasY[s] = ky;
    }

    /** Gọi dưới LOCK. Sau SYN_DROPPED: toạ độ [value] thuộc ngón đang chạm có toạ độ gần nhất trên trục đó. */
    private static void pickSlotNearest(int value, boolean isX) {
        int best = -1;
        long bd = Long.MAX_VALUE;
        for (int s = 0; s < REAL_SLOTS; s++) {
            if (!rawActive[s]) continue;
            long d = Math.abs((long) value - (isX ? rawX[s] : rawY[s]));
            if (d < bd) { bd = d; best = s; }
        }
        if (best >= 0) curSlot = best;
    }

    private static void startRaw(int s, int trackingId) {
        keepXY[s] = false;
        rawActive[s] = true;
        tid[s] = trackingId;
        hasX[s] = false;
        hasY[s] = false;
        rawP[s] = 0;
        rawMaj[s] = 0;
        rawMin[s] = 0;
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

    /** Gọi dưới LOCK. */
    private static boolean inSwallowZone(float x, float y) {
        float[] z = swallowZones;
        for (int i = 0; i + 2 < z.length; i += 3) {
            float dx = x - z[i], dy = y - z[i + 1];
            if (dx * dx + dy * dy <= z[i + 2] * z[i + 2]) return true;
        }
        return false;
    }

    /** Gọi dưới LOCK lúc ngón thật vừa chạm xuống: nằm trong vùng trợ vuốt nào thì chốt cách can thiệp. */
    private static void captureAssist(int s, float x, float y) {
        aMode[s] = -1;
        aMoveT[s] = 0;
        float[] z = assistZones;
        for (int i = 0; i + 8 < z.length; i += 9) {
            float dx = x - z[i], dy = y - z[i + 1];
            if (dx * dx + dy * dy <= z[i + 2] * z[i + 2]) {
                aMode[s] = (int) z[i + 3];
                aSpeed[s] = z[i + 4];
                aMax[s] = z[i + 5];
                aDelay[s] = z[i + 6];
                aGain[s] = Math.max(1f, z[i + 7] / 100f);
                aSlop[s] = Math.max(1f, z[i + 8]);
                return;
            }
        }
    }

    /**
     * Gọi dưới LOCK. Ngón đã chốt can thiệp -> toạ độ gửi cho hệ thống:
     *  - buff lực (2): quãng vuốt tính từ chỗ chạm xuống nhân hệ số, đúng hướng ngón vuốt (360°);
     *  - kéo tâm (0) / ghìm tâm (1): cộng thêm độ lệch lên / xuống tăng dần theo thời gian vuốt (tới giới hạn).
     * Chưa vuốt quá ngưỡng thì giữ nguyên ngón thật.
     */
    private static P assistPointer(int s, P p) {
        int m = aMode[s];
        if (m < 0) return p;
        float dx = p.x - downX[s], dy = p.y - downY[s];
        float d = (float) Math.sqrt(dx * dx + dy * dy);
        if (aMoveT[s] == 0) {
            if (d < aSlop[s]) return p;
            aMoveT[s] = SystemClock.uptimeMillis();
        }
        P bp = p.copy();
        if (m == 2) {
            if (d <= aSlop[s]) return p;
            float k = (aSlop[s] + (d - aSlop[s]) * aGain[s]) / d;
            bp.x = clampX(downX[s] + dx * k);
            bp.y = clampY(downY[s] + dy * k);
        } else {
            float el = SystemClock.uptimeMillis() - aMoveT[s] - aDelay[s];
            float off = el > 0 ? Math.min(aMax[s], aSpeed[s] * el / 1000f) : 0f;
            bp.y = clampY(p.y + (m == 1 ? off : -off));
        }
        return bp;
    }

    /** Có ngón đang bị kéo/ghìm theo thời gian (cần nhịp cập nhật dù ngón đứng yên). Gọi dưới LOCK. */
    private static boolean driftActive() {
        for (int s = 0; s < REAL_SLOTS; s++) {
            if (rawActive[s] && aMode[s] >= 0 && aMode[s] != 2 && aMoveT[s] != 0) return true;
        }
        return false;
    }

    private static float clampX(float v) { return Math.max(0f, Math.min(scrW - 1f, v)); }
    private static float clampY(float v) { return Math.max(0f, Math.min(scrH - 1f, v)); }

    // ---- chỗ DUY NHẤT quyết định hệ thống thấy gì -----------------------------------------------
    private static int indexOfSlot(int slot) {
        for (int i = 0; i < shown.size(); i++) if (shown.get(i).slot == slot) return i;
        return -1;
    }

    private static int indexOfTap(Tap t) {
        for (int i = 0; i < shown.size(); i++) if (shown.get(i).tap == t) return i;
        return -1;
    }

    /**
     * Click (ngón ảo) chỉ được cấp mã pointer từ mức này trở lên (nếu còn trống). Ngón THẬT luôn lấy mã nhỏ nhất đang trống
     * từ 0, y hệt khi không có click. Nhờ vậy ngón vuốt cam của bạn luôn là ngón đầu (mã 0) bất kể nó chạm xuống đúng lúc
     * click đang giữ hay đang nhả. Trước đây click chiếm mã 0 khoảng 75% thời gian (giữ 30ms trong chu kỳ 40ms) nên ngón thật
     * lúc thì là ngón đầu, lúc thì là ngón thứ hai tuỳ khoảnh khắc chạm -> game xử lý lúc được lúc không.
     * Đặt 0 để quay về kiểu cũ (cấp theo thứ tự chạm xuống).
     */
    private static final int TAP_PID_FLOOR = 2;

    /** Mã pointer đang trống: ngón thật từ 0, click từ [TAP_PID_FLOOR] (hết thì lùi xuống dưới). -1 nếu hết. */
    private static int allocPid(boolean virtual) {
        int from = virtual ? TAP_PID_FLOOR : 0;
        for (int i = from; i < 32; i++) {
            if ((pidMask & (1 << i)) == 0) { pidMask |= 1 << i; return i; }
        }
        for (int i = 0; i < from; i++) {
            if ((pidMask & (1 << i)) == 0) { pidMask |= 1 << i; return i; }
        }
        return -1;
    }

    /**
     * Gọi dưới LOCK. Cho một ngón (thật hoặc ảo) chạm xuống. Danh sách [shown] luôn xếp theo mã pointer TĂNG DẦN, đúng như
     * InputReader của Android gốc (chỉ số trong MotionEvent = thứ tự theo mã), nên ngón chạm sau có thể chen lên trước nếu mã nhỏ hơn.
     * false nếu hết chỗ.
     */
    private static boolean addFinger(int slot, Tap tap, P p) {
        if (shown.size() >= MAX_POINTERS) return false;
        int pid = allocPid(tap != null);
        if (pid < 0) return false;
        if (shown.isEmpty()) downTime = SystemClock.uptimeMillis();
        int at = 0;
        while (at < shown.size() && shown.get(at).pid < pid) at++;
        shown.add(at, new Finger(slot, tap, pid, p.copy()));
        emit(shown.size() == 1 ? MotionEvent.ACTION_DOWN : MotionEvent.ACTION_POINTER_DOWN, at);
        return true;
    }

    /**
     * Gọi dưới LOCK sau mỗi SYN_REPORT, mỗi khi click bắt đầu/kết thúc và mỗi nhịp trợ vuốt.
     * Ngón thật và ngón ảo cùng đi qua đây: cùng một danh sách [shown], cùng một MotionEvent.
     */
    private static void sync() {
        P[] want = new P[REAL_SLOTS];
        for (int s = 0; s < REAL_SLOTS; s++) {
            if (rawActive[s] && hasX[s] && hasY[s]) {
                P p = realPointer(s);
                // báo cho phần "chạm xuyên" của app (toạ độ pixel)
                if (!reported[s]) {
                    reported[s] = true;
                    swallowed[s] = inSwallowZone(p.x, p.y); // quyết định MỘT lần lúc ngón chạm xuống
                    downX[s] = p.x; downY[s] = p.y;
                    captureAssist(s, p.x, p.y);
                    lastRepX[s] = (int) p.x; lastRepY[s] = (int) p.y;
                    out("F " + s + " 1 " + lastRepX[s] + " " + lastRepY[s]);
                } else {
                    lastRepX[s] = (int) p.x; lastRepY[s] = (int) p.y;
                }
                if (!swallowed[s]) want[s] = assistPointer(s, p);
            } else if (reported[s] && !rawActive[s]) {
                reported[s] = false;
                swallowed[s] = false;
                aMode[s] = -1;
                aMoveT[s] = 0;
                lastRealUpAt = SystemClock.uptimeMillis();
                out("F " + s + " 0 " + lastRepX[s] + " " + lastRepY[s]);
            }
        }
        if (!injecting) return; // chế độ thụ động: hệ thống vẫn tự nhận ngón thật

        // 1) ngón biến mất -> UP (thứ tự các ngón còn lại giữ nguyên)
        for (int i = 0; i < shown.size(); ) {
            Finger f = shown.get(i);
            boolean keep = f.real() ? want[f.slot] != null : (f.tap.down && !f.tap.finished);
            if (keep) { i++; continue; }
            emit(shown.size() == 1 ? MotionEvent.ACTION_UP : MotionEvent.ACTION_POINTER_UP, i);
            shown.remove(i);
            pidMask &= ~(1 << f.pid);
        }
        // 2) ngón còn lại di chuyển (đúng thứ tự của InputReader: UP -> MOVE -> DOWN)
        boolean moved = false, realMoved = false;
        for (Finger f : shown) {
            P w = f.real() ? want[f.slot] : f.tap.p;
            if (w != null && !w.same(f.sent)) { f.sent = w.copy(); moved = true; if (f.real()) realMoved = true; }
        }
        if (moved) emit(MotionEvent.ACTION_MOVE, -1);
        // 3) ngón mới -> DOWN, nối vào cuối: thật hay ảo cũng xếp theo lúc chạm xuống
        for (int s = 0; s < REAL_SLOTS; s++) {
            if (want[s] != null && indexOfSlot(s) < 0) {
                if (!addFinger(s, null, want[s])) { out("LOG hết chỗ pointer cho ngón thật slot " + s); }
            }
        }
        for (int i = 0; i < curTaps.size(); i++) {
            Tap ct = curTaps.get(i);
            if (ct.down && !ct.finished && indexOfTap(ct) < 0) {
                if (!addFinger(-1, ct, ct.p)) ct.finished = true; // hết chỗ: bỏ click này
            }
        }

        // 4) Hệ thống vừa từ chối một DOWN/UP: luồng bên đó đã lệch so với [shown] (ngón thật bị đơ tới khi nhấc tay).
        if (desync) rebuildStream();
    }

    /**
     * Gọi dưới LOCK. Huỷ luồng chạm đang dở bên hệ thống rồi chạm lại đúng các ngón đang hiện (cùng chỗ, cùng mã
     * pointer), để ngón thật và click tiếp tục như bình thường thay vì bị hệ thống bỏ qua cho tới khi nhấc tay.
     */
    private static void rebuildStream() {
        long now = SystemClock.uptimeMillis();
        if (now - lastRebuildAt < 30) return;   // chống lặp liên tục; lần sync sau thử lại
        desync = false;
        lastRebuildAt = now;
        if (!injecting || shown.isEmpty()) return;
        ArrayList<Finger> keep = new ArrayList<>(shown);
        log("inject lỗi -> dựng lại luồng chạm (" + keep.size() + " ngón) để ngón thật không bị đơ");
        dropPendingInject();                        // sự kiện còn xếp hàng thuộc luồng cũ: sẽ bị từ chối hết, bỏ đi
        emit(MotionEvent.ACTION_CANCEL, -1, true); // luồng cũ: huỷ cho gọn, hệ thống có từ chối cũng kệ
        shown.clear();
        injDev = nextInjDev();                     // luồng mới trên mã thiết bị mới = trạng thái kiểm tra sạch
        downTime = SystemClock.uptimeMillis();
        for (Finger f : keep) {
            shown.add(f);
            emit(shown.size() == 1 ? MotionEvent.ACTION_DOWN : MotionEvent.ACTION_POINTER_DOWN, shown.size() - 1);
        }
    }

    /** Gọi dưới LOCK. [actionIndex] = chỉ số trong [shown] của ngón có DOWN/UP (bỏ qua với MOVE/CANCEL). */
    private static void emit(int baseAction, int actionIndex) { emit(baseAction, actionIndex, false); }

    private static void emit(int baseAction, int actionIndex, boolean soft) {
        int n = shown.size();
        if (n == 0) return;
        MotionEvent.PointerProperties[] props = new MotionEvent.PointerProperties[n];
        MotionEvent.PointerCoords[] coords = new MotionEvent.PointerCoords[n];
        for (int i = 0; i < n; i++) {
            Finger f = shown.get(i);
            MotionEvent.PointerProperties pp = new MotionEvent.PointerProperties();
            pp.id = f.pid;
            pp.toolType = MotionEvent.TOOL_TYPE_FINGER;
            MotionEvent.PointerCoords pc = new MotionEvent.PointerCoords();
            P p = f.sent;
            pc.x = p.x; pc.y = p.y;
            pc.pressure = p.pressure;
            pc.size = p.size;
            pc.touchMajor = p.major; pc.touchMinor = p.minor;
            pc.toolMajor = p.toolMajor; pc.toolMinor = p.minor;
            props[i] = pp; coords[i] = pc;
        }
        int action = baseAction;
        if (baseAction == MotionEvent.ACTION_POINTER_DOWN || baseAction == MotionEvent.ACTION_POINTER_UP) {
            action = baseAction | (actionIndex << MotionEvent.ACTION_POINTER_INDEX_SHIFT);
        }
        long now = SystemClock.uptimeMillis();
        int flags = baseAction == MotionEvent.ACTION_CANCEL ? FLAG_CANCELED : 0;
        MotionEvent ev = MotionEvent.obtain(downTime, now, action, n, props, coords,
                0, 0, 1f, 1f, injDev, 0, InputDevice.SOURCE_TOUCHSCREEN, flags);
        enqueueInject(ev, baseAction == MotionEvent.ACTION_MOVE, soft);
    }

    // ---- phát sự kiện: hàng đợi + luồng riêng ------------------------------------------------------
    /**
     * Gọi hệ thống để phát một sự kiện là một cuộc gọi binder đồng bộ, có thể chậm cả chục ms khi game đang nặng.
     * Trước đây nó chạy NGAY trong lúc giữ LOCK -> luồng đọc cảm ứng bị chặn theo, kernel tràn bộ đệm (SYN_DROPPED),
     * ngón thật bị đơ/giật đúng lúc click đang chạy. Giờ sự kiện được dựng dưới LOCK (nhanh) rồi xếp hàng, một luồng
     * riêng phát lần lượt theo đúng thứ tự. MOVE liên tiếp được gộp lại (MOVE nào cũng mang toạ độ mới nhất của mọi ngón).
     */
    private static final class Pending {
        final MotionEvent ev;
        final boolean move;
        final boolean soft; // sự kiện dọn dẹp: bị từ chối thì không tính là lỗi, không kích hoạt dựng lại luồng
        Pending(MotionEvent ev, boolean move, boolean soft) { this.ev = ev; this.move = move; this.soft = soft; }
    }

    private static final Object INJ_LOCK = new Object();
    private static final ArrayDeque<Pending> INJQ = new ArrayDeque<>();
    private static boolean injBusy = false;     // chỉ đụng dưới INJ_LOCK
    private static boolean injStop = false;     // chỉ đụng dưới INJ_LOCK
    private static int injectFails = 0;         // chỉ đụng ở luồng phát
    private static long lastLagLogAt = 0;       // chỉ đụng ở luồng phát

    // ---- thống kê chẩn đoán: cứ ~3s ghi 1 dòng khi có ngón thật đang đè (lúc vuốt cam) ----------------------------
    // Phân biệt được "helper ngừng phát" với "helper vẫn phát đều mà game/hệ thống bỏ qua": nếu vuốt-thật > 0 mà phát-MOVE = 0
    // thì lỗi ở helper; nếu cả hai đều chạy mà cam vẫn đơ thì lỗi nằm phía hệ thống/game.
    private static final Object STAT = new Object();
    private static int stRawMove = 0, stInjMove = 0, stInjOther = 0, stTapDown = 0;
    private static long stMaxCall = 0, stMaxAge = 0;
    private static long lastStatAt = 0;
    private static final long STAT_MS = 3000;

    private static void enqueueInject(MotionEvent ev, boolean move, boolean soft) {
        synchronized (INJ_LOCK) {
            if (move) {
                Pending tail = INJQ.peekLast();
                if (tail != null && tail.move) { INJQ.pollLast(); tail.ev.recycle(); }
            }
            INJQ.addLast(new Pending(ev, move, soft));
            INJ_LOCK.notifyAll();
        }
    }

    private static void dropPendingInject() {
        synchronized (INJ_LOCK) {
            for (Pending q : INJQ) q.ev.recycle();
            INJQ.clear();
        }
    }

    private static void startInjectThread() {
        Thread t = new Thread(InputProxy::injectLoop, "zyn-inject");
        t.setDaemon(true);
        t.start();
    }

    private static void injectLoop() {
        while (true) {
            Pending p;
            synchronized (INJ_LOCK) {
                while (INJQ.isEmpty()) {
                    if (injStop) return;
                    try { INJ_LOCK.wait(); } catch (InterruptedException e) { return; }
                }
                p = INJQ.pollFirst();
                injBusy = true;
            }
            boolean ok = false;
            long t0 = SystemClock.uptimeMillis();
            long age = t0 - p.ev.getEventTime(); // sự kiện nằm chờ trong hàng đợi bao lâu
            try {
                injector.inject(p.ev);
                ok = true;
            } catch (Throwable t) {
                Throwable root = t;
                while (root.getCause() != null && root.getCause() != root) root = root.getCause();
                log("inject fail: " + t + (root != t ? " <- " + root : "")
                    + " [action=" + (p.ev instanceof MotionEvent ? ((MotionEvent) p.ev).getActionMasked() : -1)
                    + " dev=" + (p.ev.getDeviceId()) + " sdk=" + Build.VERSION.SDK_INT + "]");
            } finally {
                p.ev.recycle();
            }
            long took = SystemClock.uptimeMillis() - t0;
            if ((age > 100 || took > 50) && t0 - lastLagLogAt > 1500) {
                lastLagLogAt = t0;
                int qn; synchronized (INJ_LOCK) { qn = INJQ.size(); }
                log("phát chạm chậm: chờ " + age + "ms, gọi hệ thống " + took + "ms, hàng đợi " + qn);
            }
            if (ok) injectFails = 0; else if (!p.soft) onInjectFailure(p.move);
            synchronized (INJ_LOCK) {
                injBusy = false;
                INJ_LOCK.notifyAll();
            }
        }
    }

    private static void onInjectFailure(boolean wasMove) {
        synchronized (LOCK) {
            if (!wasMove) desync = true;
            if (++injectFails >= MAX_INJECT_FAILS && injecting) {
                // Bộ phát hỏng mà grab vẫn còn = cảm ứng chết cả máy. Nhả grab ngay (đóng fd), huỷ ngón kẹt.
                injecting = false;
                urgentRelease = true;
                log("inject hỏng liên tiếp -> nhả cảm ứng ngay");
                new Thread(InputProxy::shutdown, "zyn-bail").start();
            } else if (desync) {
                rebuildStream();
            }
        }
    }

    /** Chờ hàng đợi phát cạn (tối đa [ms]) — gọi KHÔNG giữ LOCK. */
    private static void drainInject(long ms) {
        long end = SystemClock.uptimeMillis() + ms;
        synchronized (INJ_LOCK) {
            while ((!INJQ.isEmpty() || injBusy) && SystemClock.uptimeMillis() < end) {
                try { INJ_LOCK.wait(20); } catch (InterruptedException e) { break; }
            }
        }
    }

    // ---- click của app --------------------------------------------------------------------------
    /** Báo lỗi click về app, giãn nhịp (vòng click 60 lần/giây mà lỗi liên tục không được làm ngập luồng báo). */
    private static void tapErr(String why) {
        long now = SystemClock.uptimeMillis();
        if (now - lastErrAt < 1000) { errSkipped++; return; }
        lastErrAt = now;
        out("ERR " + why + (errSkipped > 0 ? " (+" + errSkipped + " lần trước đó)" : ""));
        errSkipped = 0;
    }

    private static P tapPointer(float x, float y) {
        P p = new P();
        p.x = clampX(x);
        p.y = clampY(y);
        p.pressure = maxPressure > 0 ? 0.5f : 1f;
        p.size = maxMajor > 0 ? 0.1f : 0f;
        return p;
    }

    /** Đọc các op "T:x,y" | "M:x,y;x,y" | "W:ms" (token đã tách theo khoảng trắng, bắt đầu từ [from]). */
    private static Op[] parseOps(String[] t, int from) {
        ArrayList<Op> ops = new ArrayList<>();
        for (int i = from; i < t.length; i++) {
            String s = t[i];
            if (s.length() < 3 || s.charAt(1) != ':') continue;
            char k = s.charAt(0);
            String body = s.substring(2);
            if (k == 'W') {
                ops.add(new Op(null, Math.max(0L, Math.min(3_600_000L, Long.parseLong(body)))));
            } else if (k == 'T' || k == 'M') {
                String[] pts = body.split(";");
                int n = Math.min(pts.length, k == 'T' ? 1 : MAX_GROUP);
                float[] xy = new float[n * 2];
                for (int j = 0; j < n; j++) {
                    String[] c = pts[j].split(",");
                    xy[2 * j] = Float.parseFloat(c[0]);
                    xy[2 * j + 1] = Float.parseFloat(c[1]);
                }
                ops.add(new Op(xy, 0));
            }
        }
        return ops.toArray(new Op[0]);
    }

    /** Bắt đầu một kịch bản. Đang chạy cùng id thì bỏ qua (một lần bấm không dồn việc). Lỗi thì chỉ bỏ. */
    private static void startRun(int id, boolean repeat, int hold, int gap, Op[] ops) {
        boolean anyClick = false;
        for (Op o : ops) if (!o.isWait()) { anyClick = true; break; }
        if (!anyClick) return;
        synchronized (LOCK) {
            if (!injecting) { tapErr("click bị bỏ: helper không bơm được (thụ động)"); return; }
            for (Run r : runs) if (r.id == id) return;
            if (runs.size() >= MAX_RUNS) { tapErr("click bị bỏ: quá nhiều kịch bản đang chạy"); return; }
            runs.add(new Run(id, repeat, hold, gap, ops));
            LOCK.notifyAll();
        }
    }

    private static void stopRun(int id) {
        synchronized (LOCK) {
            for (int i = 0; i < runs.size(); i++) {
                if (runs.get(i).id == id) { runs.remove(i); break; }
            }
        }
    }

    private static int injBacklog() {
        synchronized (INJ_LOCK) { return INJQ.size(); }
    }

    /** Gọi dưới LOCK. Lúc sớm nhất cụm click của kịch bản [r] được chạm xuống. */
    private static long tapReadyAt(Run r) {
        long ready = lastTapUpAt + MIN_RELEASE_GAP_MS;
        // Chạm xuyên + chế độ Thả: game đang bận xử lý ngón thật vừa nhả, click quá sát bị nuốt. Chờ cho đủ khoảng cách.
        if (r.gap > 0 && lastRealUpAt > 0) {
            boolean realDown = false;
            for (int i = 0; i < REAL_SLOTS; i++) realDown |= rawActive[i];
            if (!realDown) ready = Math.max(ready, lastRealUpAt + r.gap);
        }
        return ready;
    }

    private static void advance(Run r) {
        r.idx++;
        if (r.idx >= r.ops.length) {
            if (r.repeat) r.idx = 0; else r.done = true;
        }
    }

    /**
     * Gọi dưới LOCK khi không có cụm nào đang giữ. Chạy tiếp một kịch bản tới cụm click kế tiếp.
     * @return 0 nếu vừa chạm xuống một cụm (gọi lại ngay); ngược lại số ms (>= 1) tới lúc có việc kế.
     */
    private static long startNextGroup(long now) {
        if (injBacklog() > MAX_INJ_BACKLOG) return 1;
        long soonest = 1000;
        int n = runs.size();
        long result = -1;
        for (int k = 0; k < n && result < 0; k++) {
            Run r = runs.get((rrIndex + k) % n);
            if (r.done) continue;
            if (r.notBefore > now) { soonest = Math.min(soonest, r.notBefore - now); continue; }
            for (int guard = 0; guard <= r.ops.length && !r.done; guard++) {
                Op op = r.ops[r.idx];
                if (op.isWait()) {
                    r.notBefore = now + op.waitMs;   // chỉ chờ khi người dùng tự đặt Delay
                    advance(r);
                    if (op.waitMs > 0) { soonest = Math.min(soonest, op.waitMs); break; }
                    continue;
                }
                long ready = tapReadyAt(r);
                if (ready > now) { soonest = Math.min(soonest, ready - now); break; }
                launchGroup(r, op);
                advance(r);
                rrIndex = (rrIndex + k + 1) % Math.max(1, n);
                result = 0;
                break;
            }
        }
        for (int i = runs.size() - 1; i >= 0; i--) if (runs.get(i).done) runs.remove(i);
        return result == 0 ? 0 : Math.max(1, soonest);
    }

    /** Gọi dưới LOCK. Cả cụm chạm xuống liên tiếp không chờ nhau (mỗi ngón một POINTER_DOWN ngay trong một lượt). */
    private static void launchGroup(Run r, Op op) {
        for (int i = 0; i < op.xy.length; i += 2) {
            Tap t = new Tap(tapPointer(op.xy[i], op.xy[i + 1]), r.hold, r.gap);
            t.down = true;
            curTaps.add(t);
        }
        curReleaseAt = SystemClock.uptimeMillis() + r.hold;
        sync();   // DOWN (chung luồng với ngón thật đang có)
        for (int i = curTaps.size() - 1; i >= 0; i--) if (curTaps.get(i).finished) curTaps.remove(i);
        if (curTaps.isEmpty()) {
            lastTapUpAt = SystemClock.uptimeMillis();
            tapErr("click bị bỏ: hết chỗ pointer");
        } else {
            curReleaseAt = SystemClock.uptimeMillis() + r.hold; // giữ tính từ lúc thật sự chạm xuống
        }
    }

    /**
     * MỘT luồng lo mọi việc theo thời gian: bắt đầu / nhả click và nhịp trợ vuốt. Mọi thay đổi đều đi qua [sync]
     * dưới cùng LOCK với ngón thật nên click ảo chạy song song với ngón thật trong cùng một luồng chạm.
     */
    /** Gọi dưới LOCK. Ghi 1 dòng thống kê của ~3s vừa qua, chỉ khi đang có ngón thật đè (bỏ qua lúc rảnh để khỏi tràn log). */
    private static void logStats() {
        boolean realDown = false;
        for (Finger f : shown) if (f.real()) { realDown = true; break; }
        int rm, im, io, td;
        long mc, ma;
        synchronized (STAT) {
            rm = stRawMove; im = stInjMove; io = stInjOther; td = stTapDown; mc = stMaxCall; ma = stMaxAge;
            stRawMove = 0; stInjMove = 0; stInjOther = 0; stTapDown = 0; stMaxCall = 0; stMaxAge = 0;
        }
        if (!realDown) return;
        int qn;
        synchronized (INJ_LOCK) { qn = INJQ.size(); }
        StringBuilder sb = new StringBuilder("stat 3s: vuốt-thật=" + rm + " phát-MOVE=" + im + " phát-DOWN/UP=" + io + " click=" + td
                + " gọi-tối-đa=" + mc + "ms chờ-tối-đa=" + ma + "ms hàng-đợi=" + qn + " ngón=[");
        for (Finger f : shown) sb.append(f.real() ? "T" : "C").append(f.pid).append('@').append((int) f.sent.x).append(',').append((int) f.sent.y).append(' ');
        log(sb.append(']').toString());
    }

    private static void pump() {
        synchronized (LOCK) {
            while (!stopping) {
                long now = SystemClock.uptimeMillis();
                long wait = 1000;
                if (!curTaps.isEmpty() && now >= curReleaseAt) {
                    for (int i = 0; i < curTaps.size(); i++) curTaps.get(i).finished = true;
                    sync();                       // UP cả cụm
                    curTaps.clear();
                    lastTapUpAt = SystemClock.uptimeMillis();
                    now = lastTapUpAt;
                }
                if (curTaps.isEmpty() && !runs.isEmpty()) {
                    wait = Math.min(wait, startNextGroup(now));
                }
                if (!curTaps.isEmpty()) wait = Math.min(wait, curReleaseAt - SystemClock.uptimeMillis());
                if (driftActive()) { sync(); wait = Math.min(wait, 8); }
                if (wait <= 0) continue;          // tới hạn ngay (hold = 0 / click kế tiếp): KHÔNG nghỉ
                try {
                    LOCK.wait(wait);
                } catch (InterruptedException e) {
                    return;
                }
            }
        }
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
                runs.clear();
                curTaps.clear();
                if (injecting && !shown.isEmpty()) {
                    emit(MotionEvent.ACTION_CANCEL, -1, true);
                    shown.clear();
                    pidMask = 0;
                }
                LOCK.notifyAll();
            }
            // Phải phát nốt CANCEL/UP còn nằm trong hàng đợi trước khi chết, nếu không hệ thống kẹt ngón ma.
            drainInject(500);
            synchronized (INJ_LOCK) { injStop = true; INJ_LOCK.notifyAll(); }
        } catch (Throwable ignored) {
        }
        closed = true;
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
    /** Đặt được từ bên ngoài để kiểm thử logic mà không cần hệ thống thật. */
    interface Sink { void inject(InputEvent ev) throws Exception; }

    private static Sink injector;

    /** Tìm hàm injectInputEvent trên InputManagerGlobal (14+), InputManager (≤13) hoặc IInputManager. */
    private static final class Injector implements Sink {
        private final Object target;
        private final Method method;
        private final boolean withUid;
        private Method setDisplay;
        private volatile boolean altDisplay = false;

        private Object call(InputEvent ev) throws Exception {
            return withUid ? method.invoke(target, ev, 0, -1) : method.invoke(target, ev, 0);
        }

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

        @Override
        public void inject(InputEvent ev) throws Exception {
            if (setDisplay != null) {
                try { setDisplay.invoke(ev, altDisplay ? -1 : 0); } catch (Throwable ignored) { }
            }
            // mode 0 = ASYNC, uid -1 = INVALID_UID
            Object r = call(ev);
            if (r instanceof Boolean && !((Boolean) r)) {
                // Bị từ chối với display 0: thử lại để hệ thống tự chọn màn hình mặc định (display chưa gán).
                if (setDisplay != null && !altDisplay) {
                    try { setDisplay.invoke(ev, -1); } catch (Throwable ignored) { }
                    Object r2 = call(ev);
                    if (r2 instanceof Boolean && ((Boolean) r2)) {
                        altDisplay = true;
                        log("inject: display 0 bị từ chối, dùng display mặc định thì được -> giữ cách này");
                        return;
                    }
                }
                throw new IllegalStateException("hệ thống từ chối sự kiện");
            }
        }
    }
}
