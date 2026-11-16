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

public final class InputProxy {

    private static final int EV_SYN = 0x00, EV_KEY = 0x01, EV_ABS = 0x03;
    private static final int SYN_REPORT = 0x00, SYN_DROPPED = 0x03;
    private static final int BTN_TOUCH = 0x14a;
    private static final int ABS_MT_SLOT = 0x2f, ABS_MT_TOUCH_MAJOR = 0x30, ABS_MT_TOUCH_MINOR = 0x31;
    private static final int ABS_MT_POSITION_X = 0x35, ABS_MT_POSITION_Y = 0x36;
    private static final int ABS_MT_TRACKING_ID = 0x39, ABS_MT_PRESSURE = 0x3a;
     
    private static final int EVIOCGRAB = 0x40044590;

    private static final int REAL_SLOTS = 16;          
     
    private static final int MAX_POINTERS = 16;
    private static final long WATCHDOG_MS = 3000;
     
    private static final int MAX_INJECT_FAILS = 4;

    private static final int MIN_HOLD_MS = 0;
     
    private static final int MIN_RELEASE_GAP_MS = 0;
     
    private static final int MAX_RUNS = 64;
     
    private static final int MAX_GROUP = 10;
     
    private static final int MAX_INJ_BACKLOG = 24;
    private static final int MAX_INJ_QUEUE = 256; 

    private static String devPath = "";
    private static String devName = "";
    private static int maxX = 1, maxY = 1, maxPressure = 0, maxMajor = 0;
    private static volatile int rot = 0, scrW = 1, scrH = 1;

    private static final Object LOCK = new Object();
    private static final PrintStream OUT = new PrintStream(System.out, true);

    private static final java.util.concurrent.BlockingQueue<String> OUTQ = new java.util.concurrent.ArrayBlockingQueue<>(512);
    private static volatile boolean outThreadOn = false;
    private static volatile boolean urgentRelease = false;
     
    private static boolean dropping = false;

    private static boolean slotUnsure = false;
    private static boolean pickedInPacket = false;

    private static final boolean[] upInPacket = new boolean[16];
    private static boolean btnUpInPacket = false;
     
    private static final boolean[] keepXY = new boolean[16];

    private static void out(String line) {
        if (!outThreadOn) { OUT.println(line); return; }
        OUTQ.offer(line); 
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

    private static final int[] tid = new int[REAL_SLOTS];
    private static final boolean[] rawActive = new boolean[REAL_SLOTS];
    private static final boolean[] hasX = new boolean[REAL_SLOTS];
    private static final boolean[] hasY = new boolean[REAL_SLOTS];
    private static final int[] rawX = new int[REAL_SLOTS];
    private static final int[] rawY = new int[REAL_SLOTS];
    private static final int[] rawP = new int[REAL_SLOTS];
    private static final int[] rawMaj = new int[REAL_SLOTS];
    private static final int[] rawMin = new int[REAL_SLOTS];
    private static final boolean[] reported = new boolean[REAL_SLOTS]; 

    private static final boolean[] swallowed = new boolean[REAL_SLOTS];
     
    private static float[] swallowZones = new float[0];

    private static float[] assistZones = new float[0];
    private static final int[] aMode = new int[REAL_SLOTS];     
    private static final float[] aSpeed = new float[REAL_SLOTS];
    private static final float[] aMax = new float[REAL_SLOTS];
    private static final float[] aDelay = new float[REAL_SLOTS];
    private static final float[] aGain = new float[REAL_SLOTS];
    private static final float[] aSlop = new float[REAL_SLOTS];
    private static final long[] aMoveT = new long[REAL_SLOTS];  
    static { java.util.Arrays.fill(aMode, -1); }
    private static final float[] downX = new float[REAL_SLOTS];
    private static final float[] downY = new float[REAL_SLOTS];
    private static final int[] lastRepX = new int[REAL_SLOTS];
    private static final int[] lastRepY = new int[REAL_SLOTS];
    private static int curSlot = 0;

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

    private static final class Tap {
        final P p;
        final int owner;
        final int hold;
        final int gap;
        long releaseAt = 0;       
        boolean down = false;     
        boolean finished = false; 
        Tap(P p, int hold, int gap, int owner) { this.p = p; this.hold = hold; this.gap = gap; this.owner = owner; }
    }

    private static final class Finger {
        final int slot;     
        final Tap tap;      
        final int pid;
        P sent;             
        Finger(int slot, Tap tap, int pid, P sent) { this.slot = slot; this.tap = tap; this.pid = pid; this.sent = sent; }
        boolean real() { return tap == null; }
    }

    private static final ArrayList<Finger> shown = new ArrayList<>();
    private static int pidMask = 0;             
    private static long downTime = 0;
    private static long lastRealUpAt = 0;       
    private static int deviceId = 0;
    private static String bootToken = "unknown";
    private static TouchJournal journal;
    private static boolean journalHealthy = true;

    private static final int injDev = -1;
    private static final int FLAG_CANCELED = 0x20;
    private static volatile boolean recovering = false;

    private static MotionEvent acceptedTouch;
    private static boolean activeReported = false;

    private static final class Op {
        final float[] xy;     
        final long waitMs;
        Op(float[] xy, long waitMs) { this.xy = xy; this.waitMs = waitMs; }
        boolean isWait() { return xy == null; }
    }

    private static final class Run {
        final int id;
        final boolean repeat;
        final int hold;
        final int gap;
        final Op[] ops;
        int idx = 0;
        long notBefore = 0;   
        boolean done = false;
        Run(int id, boolean repeat, int hold, int gap, Op[] ops) {
            this.id = id; this.repeat = repeat; this.hold = hold; this.gap = gap; this.ops = ops;
        }
    }

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
     
    private static volatile boolean closed = false;
    private static volatile long lastPing = SystemClock.uptimeMillis();

    private static int recSize = 0;

    public static void main(String[] args) {
        try {
            for (String a : args) {
                int eq = a.indexOf('=');
                if (eq <= 0) continue;
                String k = a.substring(0, eq), v = a.substring(eq + 1);
                switch (k) {
                    case "dev": devPath = v; break;
                    case "boot": bootToken = v; break;
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
        
        Runtime.getRuntime().addShutdownHook(new Thread(() -> { urgentRelease = true; shutdown(); }, "zyn-hook"));
        devIn = new FileInputStream(devPath);
        deviceId = findDeviceId(devName);

        Injector inj = null;
        try { inj = new Injector(); } catch (Throwable t) { log("inject: " + t); }
        if (inj != null) {
            injector = inj;

            if (bootToken.equals("unknown") || bootToken.equals("-1")) {
                bootToken = new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get("/proc/sys/kernel/random/boot_id")), java.nio.charset.StandardCharsets.UTF_8).trim();
            }
            journal = new TouchJournal(new java.io.File("/data/local/tmp/com.zyntap.input-v1"), bootToken);
            recoverJournal(); 
            if (!journalHealthy) throw new IOException("journal damaged; no grab, retry on next session");
            grabbed = grab(devIn.getFD());
            injecting = grabbed;
        }
        OUT.println("READY grab=" + (grabbed ? 1 : 0) + " inj=" + (injecting ? 1 : 0) + " dev=" + deviceId);
        log("inject stream dev=" + injDev + " (touch hardware=" + deviceId + ")");

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

    private static volatile boolean debugEnabled = false;
    private static void handle(String line) {
        if (line.isEmpty() || line.equals("PING")) return;
        if (line.equals("DEBUG 0")) { debugEnabled = false; return; }
        if (line.equals("DEBUG 1")) { debugEnabled = true; return; }
        String[] t = line.split(" ");
        try {
            switch (t[0]) {
                case "ROT":
                    synchronized (LOCK) {
                        int nextRot = Integer.parseInt(t[1]) & 3;
                        int nextW = Math.max(1, Integer.parseInt(t[2]));
                        int nextH = Math.max(1, Integer.parseInt(t[3]));
                        // RUN sends its frame atomically first. An unchanged frame must not cancel other runs.
                        if (rot != nextRot || scrW != nextW || scrH != nextH) {
                            stopAllTaps();
                            rot = nextRot;
                            scrW = nextW;
                            scrH = nextH;
                            sync();
                        }
                    }
                    break;
                case "ZONE": {
                    
                    int n = (t.length - 1) / 3;
                    float[] z = new float[n * 3];
                    for (int i = 0; i < z.length; i++) z[i] = Float.parseFloat(t[1 + i]);
                    synchronized (LOCK) { swallowZones = z; }
                    break;
                }
                case "ASSIST": {
                    
                    int n = (t.length - 1) / 9;
                    float[] z = new float[n * 9];
                    for (int i = 0; i < z.length; i++) z[i] = Float.parseFloat(t[1 + i]);
                    synchronized (LOCK) { assistZones = z; }
                    break;
                }
                case "RUN": {
                    
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
                    synchronized (LOCK) { stopAllTaps(); }
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
                urgentRelease = true; 
                shutdown();
                return;
            }
            SystemClock.sleep(500);
        }
    }

    private static void readLoop() {
        byte[] buf = new byte[4800]; 
        byte[] acc = new byte[9600];
        int n = 0;
        try {

            while (!closed) {
                int r = devIn.read(buf);
                if (r < 0) break;
                if (r == 0) continue;
                if (n + r > acc.length) n = 0; 
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
        if (!closed) urgentRelease = true; 
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

    static void onEvent(int type, int code, int value) {
        synchronized (LOCK) {
            if (dropping) {
                
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
                
                if (code == BTN_TOUCH && value == 0) {
                    btnUpInPacket = true;
                    for (int s = 0; s < REAL_SLOTS; s++) {
                        
                        if (rawActive[s]) { rawActive[s] = false; tid[s] = -1; keepXY[s] = true; }
                    }
                }
            } else if (type == EV_SYN) {
                if (code == SYN_DROPPED) {

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

    private static void adoptIfOrphan(int s) {
        if (rawActive[s] || upInPacket[s] || btnUpInPacket) return;
        boolean kx = keepXY[s] && hasX[s], ky = keepXY[s] && hasY[s];
        startRaw(s, -2);
        hasX[s] = kx;
        hasY[s] = ky;
    }

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

    private static boolean inSwallowZone(float x, float y) {
        float[] z = swallowZones;
        for (int i = 0; i + 2 < z.length; i += 3) {
            float dx = x - z[i], dy = y - z[i + 1];
            if (dx * dx + dy * dy <= z[i + 2] * z[i + 2]) return true;
        }
        return false;
    }

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

    private static boolean driftActive() {
        for (int s = 0; s < REAL_SLOTS; s++) {
            if (rawActive[s] && aMode[s] >= 0 && aMode[s] != 2 && aMoveT[s] != 0) return true;
        }
        return false;
    }

    private static float clampX(float v) { return Math.max(0f, Math.min(scrW - 1f, v)); }
    private static float clampY(float v) { return Math.max(0f, Math.min(scrH - 1f, v)); }

    private static int indexOfSlot(int slot) {
        for (int i = 0; i < shown.size(); i++) if (shown.get(i).slot == slot) return i;
        return -1;
    }

    private static int indexOfTap(Tap t) {
        for (int i = 0; i < shown.size(); i++) if (shown.get(i).tap == t) return i;
        return -1;
    }

    private static final int TAP_PID_FLOOR = 2;

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

    private static void sync() {
        P[] want = new P[REAL_SLOTS];
        for (int s = 0; s < REAL_SLOTS; s++) {
            if (rawActive[s] && hasX[s] && hasY[s]) {
                P p = realPointer(s);
                
                if (!reported[s]) {
                    reported[s] = true;
                    swallowed[s] = inSwallowZone(p.x, p.y); 
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
        if (!injecting || recovering || closed) return; 

        for (int i = 0; i < shown.size(); ) {
            Finger f = shown.get(i);
            boolean keep = f.real() ? want[f.slot] != null : (f.tap.down && !f.tap.finished);
            if (keep) { i++; continue; }
            emit(shown.size() == 1 ? MotionEvent.ACTION_UP : MotionEvent.ACTION_POINTER_UP, i);
            shown.remove(i);
            pidMask &= ~(1 << f.pid);
        }
        
        boolean moved = false, realMoved = false;
        for (Finger f : shown) {
            P w = f.real() ? want[f.slot] : f.tap.p;
            if (w != null && !w.same(f.sent)) { f.sent = w.copy(); moved = true; if (f.real()) realMoved = true; }
        }
        if (moved) emit(MotionEvent.ACTION_MOVE, -1);
        
        for (int s = 0; s < REAL_SLOTS; s++) {
            if (want[s] != null && indexOfSlot(s) < 0) {
                if (!addFinger(s, null, want[s])) { out("LOG hết chỗ pointer cho ngón thật slot " + s); }
            }
        }
        for (int i = 0; i < curTaps.size(); i++) {
            Tap ct = curTaps.get(i);
            if (ct.down && !ct.finished && indexOfTap(ct) < 0) {
                if (!addFinger(-1, ct, ct.p)) ct.finished = true; 
            }
        }

    }

    private static void rememberAccepted(MotionEvent ev) {
        int action = ev.getActionMasked();
        if (acceptedTouch != null) { acceptedTouch.recycle(); acceptedTouch = null; }
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) return;
        int skip = action == MotionEvent.ACTION_POINTER_UP ? ev.getActionIndex() : -1;
        int count = ev.getPointerCount() - (skip >= 0 ? 1 : 0);
        MotionEvent.PointerProperties[] pp = new MotionEvent.PointerProperties[count];
        MotionEvent.PointerCoords[] pc = new MotionEvent.PointerCoords[count];
        for (int i = 0, j = 0; i < ev.getPointerCount(); i++) {
            if (i == skip) continue;
            pp[j] = new MotionEvent.PointerProperties(); ev.getPointerProperties(i, pp[j]);
            pc[j] = new MotionEvent.PointerCoords(); ev.getPointerCoords(i, pc[j]); j++;
        }
        acceptedTouch = MotionEvent.obtain(ev.getDownTime(), ev.getEventTime(), MotionEvent.ACTION_MOVE,
                count, pp, pc, 0, 0, 1f, 1f, injDev, 0, InputDevice.SOURCE_TOUCHSCREEN, 0);
    }

    private static TouchJournal.State snapshot(MotionEvent ev, boolean after) {
        if (ev == null) return null;
        int action = ev.getActionMasked();
        if (after && (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL)) return null;
        int skip = after && action == MotionEvent.ACTION_POINTER_UP ? ev.getActionIndex() : -1;
        int count = ev.getPointerCount() - (skip >= 0 ? 1 : 0);
        int[] ids = new int[count]; float[] x = new float[count], y = new float[count];
        for (int i = 0, j = 0; i < ev.getPointerCount(); i++) {
            if (i == skip) continue;
            ids[j] = ev.getPointerId(i); x[j] = ev.getX(i); y[j] = ev.getY(i); j++;
        }
        return new TouchJournal.State(ev.getDownTime(), ids, x, y);
    }

    private static boolean cancelSnapshot(TouchJournal.State state) {
        if (state == null) return false;
        int n = state.ids.length;
        MotionEvent.PointerProperties[] pp = new MotionEvent.PointerProperties[n];
        MotionEvent.PointerCoords[] pc = new MotionEvent.PointerCoords[n];
        for (int i = 0; i < n; i++) {
            pp[i] = new MotionEvent.PointerProperties(); pp[i].id = state.ids[i]; pp[i].toolType = MotionEvent.TOOL_TYPE_FINGER;
            pc[i] = new MotionEvent.PointerCoords(); pc[i].x = state.x[i]; pc[i].y = state.y[i];
        }
        long now = SystemClock.uptimeMillis();
        MotionEvent cancel = MotionEvent.obtain(Math.min(state.downTime, now), now,
                MotionEvent.ACTION_CANCEL, n, pp, pc, 0, 0, 1f, 1f, injDev, 0,
                InputDevice.SOURCE_TOUCHSCREEN, FLAG_CANCELED);
        try {
            injector.inject(cancel);
            rememberAccepted(cancel);
            
            if (journal != null) try { journal.clear(); } catch (IOException e) { log("journal clear: " + e); }
            return true;
        } catch (Throwable error) {
            log("cleanup CANCEL rejected: " + error + " pointers=" + java.util.Arrays.toString(state.ids));
            return false;
        } finally { cancel.recycle(); }
    }

    private static boolean recoverJournal() {
        if (journal == null) return false;
        try {
            TouchJournal.State[] candidates = journal.readCandidates();
            for (TouchJournal.State state : candidates) {
                if (cancelSnapshot(state)) {
                    log("RECOVERED: đã CANCEL pointer lưu từ phiên trước; không cần đổi deviceId");
                    return true;
                }
            }
            if (candidates.length > 0) log("Journal không còn khớp (có thể UP đã hoàn tất); chưa kết luận bộ phát bị kẹt");
        } catch (IOException error) {
            
            log("journal read fail: " + error);
            journalHealthy = false;
            try { journal.clear(); } catch (IOException ignored) { }
            
        }
        return false;
    }

    private static boolean cancelAccepted() {
        if (acceptedTouch != null && cancelSnapshot(snapshot(acceptedTouch, false))) return true;
        return recoverJournal();
    }

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

    private static final class Pending {
        final MotionEvent ev;
        final boolean move;
        final boolean soft; 
        Pending(MotionEvent ev, boolean move, boolean soft) { this.ev = ev; this.move = move; this.soft = soft; }
    }

    private static final Object INJ_LOCK = new Object();
    private static final ArrayDeque<Pending> INJQ = new ArrayDeque<>();
    private static boolean injBusy = false;     
    private static boolean injectFinished = false;
    private static boolean injStop = false;     
    private static int injectFails = 0;
    private static long recoveryWindowAt = 0;
    private static int recoveriesInWindow = 0;         
    private static long lastLagLogAt = 0;       

    private static void enqueueInject(MotionEvent ev, boolean move, boolean soft) {
        synchronized (INJ_LOCK) {
            if (injStop || !injecting || recovering) { ev.recycle(); return; }
            if (INJQ.size() >= MAX_INJ_QUEUE) {
                ev.recycle();
                for (Pending pending : INJQ) pending.ev.recycle();
                INJQ.clear();
                injecting = false; recovering = true; urgentRelease = true;
                log("inject hỏng liên tiếp -> nhả cảm ứng ngay; hàng đợi vượt giới hạn " + MAX_INJ_QUEUE);
                new Thread(InputProxy::shutdown, "zyn-overflow").start();
                return;
            }
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
                while (INJQ.isEmpty() && !injStop) {
                    try { INJ_LOCK.wait(); } catch (InterruptedException e) { return; }
                }
                if (injStop) {
                    
                    break;
                }
                p = INJQ.pollFirst();
                injBusy = true;
            }
            boolean ok = false;
            long t0 = SystemClock.uptimeMillis();
            long age = t0 - p.ev.getEventTime(); 
            try {
                int action = p.ev.getActionMasked();
                if (!journalHealthy) throw new IOException("touch journal unavailable; refuse untracked input");
                if (journal != null && action != MotionEvent.ACTION_MOVE) {
                    try { journal.prepare(snapshot(acceptedTouch, false), snapshot(p.ev, true)); }
                    catch (IOException e) { journalHealthy = false; throw e; }
                }
                injector.inject(p.ev);
                rememberAccepted(p.ev);
                if (!p.soft && !activeReported) { activeReported = true; out("ACTIVE"); }
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
            if (ok && !p.soft) injectFails = 0; else if (!ok && !p.soft) onInjectFailure(p.move);
            synchronized (INJ_LOCK) {
                injBusy = false;
                INJ_LOCK.notifyAll();
            }
        }
        
        cancelAccepted();
        synchronized (INJ_LOCK) { injBusy = false; injectFinished = true; INJ_LOCK.notifyAll(); }
    }

    private static void onInjectFailure(boolean wasMove) {

        synchronized (LOCK) {
            recovering = true;
            runs.clear(); curTaps.clear(); shown.clear(); pidMask = 0;
            dropPendingInject();
        }
        boolean clean = cancelAccepted();
        synchronized (LOCK) {
            injectFails++;
            long now = SystemClock.uptimeMillis();
            if (now - recoveryWindowAt > 10_000) { recoveryWindowAt = now; recoveriesInWindow = 0; }
            recoveriesInWindow++;
            if (clean && journalHealthy && injectFails < MAX_INJECT_FAILS && recoveriesInWindow < MAX_INJECT_FAILS && !stopping) {
                recovering = false;
                log("inject lỗi -> đã CANCEL tập pointer được nhận; bỏ click cũ, phục hồi ngón thật");
                sync();
                LOCK.notifyAll();
            } else {
                injecting = false;
                urgentRelease = true;
                log("inject hỏng liên tiếp -> nhả cảm ứng ngay; cleanup=" + clean);
                new Thread(InputProxy::shutdown, "zyn-bail").start();
            }
        }
    }

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
            for (Tap tap : curTaps) if (tap.owner == id) tap.finished = true;
            sync();
            for (int i = curTaps.size() - 1; i >= 0; i--) if (curTaps.get(i).finished) curTaps.remove(i);
            LOCK.notifyAll();
        }
    }

    private static void stopAllTaps() {
        runs.clear();
        for (Tap tap : curTaps) tap.finished = true;
        sync();
        curTaps.clear();
        LOCK.notifyAll();
    }

    private static int injBacklog() {
        synchronized (INJ_LOCK) { return INJQ.size(); }
    }

    private static long tapReadyAt(Run r) {
        long ready = lastTapUpAt + MIN_RELEASE_GAP_MS;
        
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
                    r.notBefore = now + op.waitMs;   
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

    private static void launchGroup(Run r, Op op) {
        for (int i = 0; i < op.xy.length; i += 2) {
            Tap t = new Tap(tapPointer(op.xy[i], op.xy[i + 1]), r.hold, r.gap, r.id);
            t.down = true;
            curTaps.add(t);
        }
        curReleaseAt = SystemClock.uptimeMillis() + r.hold;
        sync();   
        for (int i = curTaps.size() - 1; i >= 0; i--) if (curTaps.get(i).finished) curTaps.remove(i);
        if (curTaps.isEmpty()) {
            lastTapUpAt = SystemClock.uptimeMillis();
            tapErr("click bị bỏ: hết chỗ pointer");
        } else {
            curReleaseAt = SystemClock.uptimeMillis() + r.hold; 
        }
    }

    private static void pump() {
        synchronized (LOCK) {
            int immediateSteps = 0;
            while (!stopping) {
                long now = SystemClock.uptimeMillis();
                long wait = 1000;
                if (!curTaps.isEmpty() && now >= curReleaseAt) {
                    for (int i = 0; i < curTaps.size(); i++) curTaps.get(i).finished = true;
                    sync();                       
                    curTaps.clear();
                    lastTapUpAt = SystemClock.uptimeMillis();
                    now = lastTapUpAt;
                }
                if (curTaps.isEmpty() && !runs.isEmpty()) {
                    wait = Math.min(wait, startNextGroup(now));
                }
                if (!curTaps.isEmpty()) wait = Math.min(wait, curReleaseAt - SystemClock.uptimeMillis());
                if (driftActive()) { sync(); wait = Math.min(wait, 8); }

                if (wait <= 0 && ++immediateSteps < 8) continue;
                immediateSteps = 0;
                try {
                    LOCK.wait(Math.max(1L, wait));
                } catch (InterruptedException e) {
                    return;
                }
            }
        }
    }

    private static boolean down = false;
    private static final Object SHUTDOWN_LOCK = new Object();

    private static void shutdown() {
        synchronized (SHUTDOWN_LOCK) {
            if (down) return;
            down = true;
        }
        stopping = true;

        Thread killer = new Thread(() -> {
            SystemClock.sleep(3000);
            Runtime.getRuntime().halt(0);
        }, "zyn-kill");
        killer.setDaemon(true);
        killer.start();
        try {
            synchronized (LOCK) { stopAllTaps(); }
            
            long end = SystemClock.uptimeMillis() + 1500;
            while (!urgentRelease && SystemClock.uptimeMillis() < end) {
                boolean any = false;
                synchronized (LOCK) { for (int i = 0; i < REAL_SLOTS; i++) any |= rawActive[i]; }
                if (!any) break;
                SystemClock.sleep(30);
            }
            synchronized (LOCK) {

                recovering = true;
                injecting = false;
                runs.clear(); curTaps.clear(); shown.clear(); pidMask = 0;
                dropPendingInject();
                synchronized (INJ_LOCK) { injStop = true; INJ_LOCK.notifyAll(); }
                LOCK.notifyAll();
            }
            
            if (urgentRelease) {
                closed = true;
                try { if (devIn != null) devIn.close(); } catch (Throwable ignored) { }
            }
            long cleanupEnd = SystemClock.uptimeMillis() + 700;
            synchronized (INJ_LOCK) {
                while (outThreadOn && !injectFinished && SystemClock.uptimeMillis() < cleanupEnd) {
                    try { INJ_LOCK.wait(20); } catch (InterruptedException e) { break; }
                }
            }
        } catch (Throwable ignored) {
        }
        closed = true;
        try { if (devIn != null) devIn.close(); } catch (Throwable ignored) { } 
        out("LOG bye"); 
        Runtime.getRuntime().halt(0);
    }

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

    private static void log(String s) { if (debugEnabled || s.contains("inject hỏng liên tiếp")) out("LOG " + s.replace('\n', ' ')); }

    interface Sink { void inject(InputEvent ev) throws Exception; }

    private static Sink injector;

    private static final class Injector implements Sink {
        private final Object target;
        private final Method method;
        private final boolean withUid;
        private Method setDisplay;

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
                try { setDisplay.invoke(ev, 0); } catch (Throwable ignored) { }
            }
            
            Object r = call(ev);
            if (r instanceof Boolean && !((Boolean) r)) {

                throw new IllegalStateException("hệ thống từ chối sự kiện");
            }
        }
    }
}
