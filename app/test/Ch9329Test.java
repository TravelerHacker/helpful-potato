import com.phonehands.hid.Ch9329;
import java.util.Arrays;

public class Ch9329Test {
    static int fails = 0, n = 0;
    static byte[] b(int... v) { byte[] r = new byte[v.length]; for (int i = 0; i < v.length; i++) r[i] = (byte) v[i]; return r; }
    static void eq(String name, byte[] got, byte[] want) {
        n++;
        if (!Arrays.equals(got, want)) { fails++; System.out.println("FAIL " + name + "\n  got  " + Ch9329.hex(got) + "\n  want " + Ch9329.hex(want)); }
        else System.out.println("ok   " + name + ": " + Ch9329.hex(got));
    }
    static void t(String name, boolean c) { n++; if (!c) { fails++; System.out.println("FAIL " + name); } else System.out.println("ok   " + name); }
    public static void main(String[] a) {
        // Known-good vectors from the CH9329 protocol doc / common implementations
        eq("get info", Ch9329.getInfo(0), b(0x57, 0xAB, 0x00, 0x01, 0x00, 0x03));
        eq("key 'a' down", Ch9329.keyboard(0, b(0, 0, 0x04, 0, 0, 0, 0, 0)),
            b(0x57, 0xAB, 0x00, 0x02, 0x08, 0x00, 0x00, 0x04, 0, 0, 0, 0, 0, 0x10));
        eq("all keys up", Ch9329.keyboard(0, new byte[8]),
            b(0x57, 0xAB, 0x00, 0x02, 0x08, 0, 0, 0, 0, 0, 0, 0, 0, 0x0C));
        eq("ctrl+alt+del", Ch9329.keyboard(0, b(0x05, 0, 0x4C, 0, 0, 0, 0, 0)),
            b(0x57, 0xAB, 0x00, 0x02, 0x08, 0x05, 0x00, 0x4C, 0, 0, 0, 0, 0, 0x5D));
        eq("reserved byte forced 0", Ch9329.keyboard(0, b(0, 0x99, 0x04, 0, 0, 0, 0, 0)),
            b(0x57, 0xAB, 0x00, 0x02, 0x08, 0x00, 0x00, 0x04, 0, 0, 0, 0, 0, 0x10));
        eq("rel mouse +10,-10", Ch9329.mouseRel(0, 0, 10, -10, 0),
            b(0x57, 0xAB, 0x00, 0x05, 0x05, 0x01, 0x00, 0x0A, 0xF6, 0x00, 0x0D));
        eq("rel mouse left click", Ch9329.mouseRel(0, 1, 0, 0, 0),
            b(0x57, 0xAB, 0x00, 0x05, 0x05, 0x01, 0x01, 0x00, 0x00, 0x00, 0x0E));
        eq("rel mouse clamp 300,-300 wheel -1", Ch9329.mouseRel(0, 0, 300, -300, -1),
            b(0x57, 0xAB, 0x00, 0x05, 0x05, 0x01, 0x00, 0x7F, 0x81, 0xFF, 0x0C));
        eq("abs mouse center 2048,2048", Ch9329.mouseAbs(0, 0, 2048, 2048, 0),
            b(0x57, 0xAB, 0x00, 0x04, 0x07, 0x02, 0x00, 0x00, 0x08, 0x00, 0x08, 0x00, 0x1F));
        eq("abs mouse clamp 5000,-1", Ch9329.mouseAbs(0, 2, 5000, -1, 0),
            b(0x57, 0xAB, 0x00, 0x04, 0x07, 0x02, 0x02, 0xFF, 0x0F, 0x00, 0x00, 0x00, 0x1F));
        eq("addr 0x01", Ch9329.getInfo(1), b(0x57, 0xAB, 0x01, 0x01, 0x00, 0x04));
        // checksum property on random frames
        java.util.Random r = new java.util.Random(1);
        boolean good = true;
        for (int i = 0; i < 1000; i++) {
            byte[] f = Ch9329.mouseRel(0, r.nextInt(8), r.nextInt(400) - 200, r.nextInt(400) - 200, r.nextInt(255) - 127);
            int s = 0; for (int j = 0; j < f.length - 1; j++) s += f[j] & 0xFF;
            good &= (byte) s == f[f.length - 1];
        }
        t("checksum property x1000", good);
        // reply parsing: noise + get-info reply (ver 0x30, enumerated, numlock on)
        byte[] info = Ch9329.frame(0, 0x81, b(0x30, 0x01, 0x01, 0, 0, 0, 0, 0));
        byte[] buf = new byte[info.length + 3]; buf[0] = 0x11; buf[1] = 0x57; buf[2] = 0x00;
        System.arraycopy(info, 0, buf, 3, info.length);
        int[] p = Ch9329.findReply(buf, buf.length);
        t("findReply locates frame after noise", p != null && p[0] == 3 && p[1] == info.length);
        t("findReply returns null on truncated", Ch9329.findReply(buf, buf.length - 1) == null);
        byte[] bad = info.clone(); bad[bad.length - 1]++;
        t("findReply rejects bad checksum", Ch9329.findReply(bad, bad.length) == null);
        System.out.println(fails == 0 ? "ALL " + n + " TESTS PASSED" : fails + "/" + n + " FAILED");
        System.exit(fails == 0 ? 0 : 1);
    }
}
