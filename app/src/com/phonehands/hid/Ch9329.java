package com.phonehands.hid;

/**
 * Pure-Java CH9329 serial protocol encoder (no Android deps, unit-tested on the host JVM).
 * Frame: 0x57 0xAB ADDR CMD LEN DATA... SUM, where SUM = (sum of all preceding bytes) & 0xFF.
 */
public final class Ch9329 {
    public static final int HEAD1 = 0x57, HEAD2 = 0xAB;
    public static final int CMD_GET_INFO = 0x01;
    public static final int CMD_KB_GENERAL = 0x02;
    public static final int CMD_KB_MEDIA = 0x03;
    public static final int CMD_MOUSE_ABS = 0x04;
    public static final int CMD_MOUSE_REL = 0x05;
    public static final int ABS_MAX = 4095;

    private Ch9329() {}

    public static byte[] frame(int addr, int cmd, byte[] data) {
        int n = data == null ? 0 : data.length;
        byte[] f = new byte[6 + n];
        f[0] = (byte) HEAD1; f[1] = (byte) HEAD2; f[2] = (byte) addr; f[3] = (byte) cmd; f[4] = (byte) n;
        if (n > 0) System.arraycopy(data, 0, f, 5, n);
        int sum = 0;
        for (int i = 0; i < 5 + n; i++) sum += f[i] & 0xFF;
        f[5 + n] = (byte) sum;
        return f;
    }

    /** report = 8-byte boot keyboard report: mods, 0, k1..k6. */
    public static byte[] keyboard(int addr, byte[] report) {
        if (report.length != 8) throw new IllegalArgumentException("keyboard report must be 8 bytes");
        byte[] d = report.clone();
        d[1] = 0;
        return frame(addr, CMD_KB_GENERAL, d);
    }

    /** Relative mouse: dx/dy/wheel in -127..127. */
    public static byte[] mouseRel(int addr, int buttons, int dx, int dy, int wheel) {
        return frame(addr, CMD_MOUSE_REL, new byte[]{0x01, (byte) (buttons & 0x07),
            (byte) clamp(dx, -127, 127), (byte) clamp(dy, -127, 127), (byte) clamp(wheel, -127, 127)});
    }

    /** Absolute mouse: x/y in 0..4095 (fraction of screen * 4096). */
    public static byte[] mouseAbs(int addr, int buttons, int x, int y, int wheel) {
        x = clamp(x, 0, ABS_MAX); y = clamp(y, 0, ABS_MAX);
        return frame(addr, CMD_MOUSE_ABS, new byte[]{0x02, (byte) (buttons & 0x07),
            (byte) (x & 0xFF), (byte) (x >> 8), (byte) (y & 0xFF), (byte) (y >> 8), (byte) clamp(wheel, -127, 127)});
    }

    public static byte[] getInfo(int addr) { return frame(addr, CMD_GET_INFO, new byte[0]); }

    static int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }

    /**
     * Find a valid reply frame in buf[0..len). Returns {offset, totalLength} or null.
     * Reply CMD = request CMD | 0x80 (success) or | 0xC0 (error).
     */
    public static int[] findReply(byte[] buf, int len) {
        for (int i = 0; i + 6 <= len; i++) {
            if ((buf[i] & 0xFF) != HEAD1 || (buf[i + 1] & 0xFF) != HEAD2) continue;
            int n = buf[i + 4] & 0xFF;
            if (i + 6 + n > len) return null;
            int sum = 0;
            for (int j = i; j < i + 5 + n; j++) sum += buf[j] & 0xFF;
            if ((byte) sum == buf[i + 5 + n]) return new int[]{i, 6 + n};
        }
        return null;
    }

    public static String hex(byte[] b) {
        StringBuilder s = new StringBuilder();
        for (byte x : b) { if (s.length() > 0) s.append(' '); s.append(String.format("%02X", x & 0xFF)); }
        return s.toString();
    }
}
