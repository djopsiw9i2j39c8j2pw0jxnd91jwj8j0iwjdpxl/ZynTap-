package com.zyntap.app.shell;

import java.io.*;
import java.nio.channels.FileLock;
import java.util.Arrays;
import java.util.zip.CRC32;

/** Process-crash journal, not a power-loss guarantee. One writer owns the lock for its lifetime.
 * Store both sides BEFORE a pointer-set transition: death may happen before OR after Binder accepted it.
 * MOVE needs no disk I/O because it does not change the verifier's pointer set.
 */
final class TouchJournal implements Closeable {
    private static final int MAGIC = 0x5a594e34;
    private final File file;
    private final String boot;
    private final RandomAccessFile lockFile;
    private final FileLock lock;
    private boolean cached;
    private State cachedBefore, cachedAfter;
    private static boolean same(State a, State b) { return a == null ? b == null : a.samePointers(b); }

    static final class State {
        final long downTime;
        final int[] ids;
        final float[] x, y;
        State(long downTime, int[] ids, float[] x, float[] y) {
            this.downTime = downTime; this.ids = ids.clone(); this.x = x.clone(); this.y = y.clone();
            if (ids.length < 1 || ids.length > 16 || x.length != ids.length || y.length != ids.length || downTime < 0)
                throw new IllegalArgumentException("invalid touch snapshot");
            long mask = 0;
            for (int i = 0; i < ids.length; i++) {
                if (ids[i] < 0 || ids[i] > 31 || (mask & (1L << ids[i])) != 0 || !Float.isFinite(x[i]) || !Float.isFinite(y[i]))
                    throw new IllegalArgumentException("invalid pointer");
                mask |= 1L << ids[i];
            }
        }
        boolean samePointers(State other) {
            if (other == null || ids.length != other.ids.length) return false;
            int[] a = ids.clone(), b = other.ids.clone(); Arrays.sort(a); Arrays.sort(b);
            return Arrays.equals(a, b);
        }
    }

    TouchJournal(File directory, String boot) throws IOException {
        this.boot = boot;
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("journal directory unavailable");
        directory.setReadable(false, false); directory.setWritable(false, false); directory.setExecutable(false, false);
        directory.setReadable(true, true); directory.setWritable(true, true); directory.setExecutable(true, true);
        file = new File(directory, "touch.bin");
        lockFile = new RandomAccessFile(new File(directory, "owner.lock"), "rw");
        FileLock acquired;
        try { acquired = lockFile.getChannel().tryLock(); }
        catch (RuntimeException | IOException e) { lockFile.close(); throw new IOException("helper already owns journal", e); }
        if (acquired == null) { lockFile.close(); throw new IOException("helper already owns journal"); }
        lock = acquired;
    }

    void prepare(State before, State after) throws IOException {
        // CANCEL matching depends on the pointer set, not gesture age/coordinates.
        // Repeated single-pointer DOWN/UP uses the same {empty,0} record: no disk write per click.
        if (cached && ((same(before, cachedBefore) && same(after, cachedAfter)) ||
                (same(before, cachedAfter) && same(after, cachedBefore)))) return;
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeInt(MAGIC); out.writeUTF(boot); writeState(out, before); writeState(out, after);
        }
        byte[] payload = bytes.toByteArray();
        CRC32 crc = new CRC32(); crc.update(payload);
        File tmp = new File(file.getParentFile(), "touch.tmp");
        try (DataOutputStream out = new DataOutputStream(new FileOutputStream(tmp))) {
            out.writeInt(payload.length); out.write(payload); out.writeLong(crc.getValue());
        }
        // Atomic visibility to the next process. No fsync per tap (avoid flash latency on the input writer).
        if (!tmp.renameTo(file)) throw new IOException("cannot publish touch journal");
        cachedBefore = before; cachedAfter = after; cached = true;
    }

    State[] readCandidates() throws IOException {
        if (!file.exists()) return new State[0];
        if (file.length() > 4096) throw new IOException("journal too large");
        try (DataInputStream in = new DataInputStream(new FileInputStream(file))) {
            int length = in.readInt();
            if (length < 4 || length > 4000) throw new IOException("invalid journal length");
            byte[] payload = new byte[length]; in.readFully(payload);
            long expected = in.readLong(); CRC32 crc = new CRC32(); crc.update(payload);
            if (crc.getValue() != expected || in.read() != -1) throw new IOException("journal checksum mismatch");
            try (DataInputStream data = new DataInputStream(new ByteArrayInputStream(payload))) {
                if (data.readInt() != MAGIC) throw new IOException("journal format mismatch");
                if (!boot.equals(data.readUTF())) { clear(); return new State[0]; }
                State before = readState(data), after = readState(data);
                if (data.read() != -1) throw new IOException("trailing snapshot data");
                if (after == null) return before == null ? new State[0] : new State[]{before};
                if (before == null || before.samePointers(after)) return new State[]{after};
                return new State[]{after, before};
            }
        } catch (IllegalArgumentException e) { throw new IOException("invalid snapshot", e); }
    }

    void clear() throws IOException {
        cached = false; cachedBefore = null; cachedAfter = null;
        if (file.exists() && !file.delete()) throw new IOException("cannot clear touch journal");
    }
    private static void writeState(DataOutputStream out, State s) throws IOException {
        out.writeInt(s == null ? 0 : s.ids.length);
        if (s == null) return;
        out.writeLong(s.downTime);
        for (int i = 0; i < s.ids.length; i++) { out.writeInt(s.ids[i]); out.writeFloat(s.x[i]); out.writeFloat(s.y[i]); }
    }
    private static State readState(DataInputStream in) throws IOException {
        int n = in.readInt(); if (n == 0) return null;
        if (n < 1 || n > 16) throw new IOException("invalid pointer count");
        long time = in.readLong(); int[] ids = new int[n]; float[] x = new float[n], y = new float[n];
        for (int i = 0; i < n; i++) { ids[i] = in.readInt(); x[i] = in.readFloat(); y[i] = in.readFloat(); }
        return new State(time, ids, x, y);
    }
    @Override public void close() throws IOException { try { lock.release(); } finally { lockFile.close(); } }
}
