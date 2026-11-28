package net.faultlinesmp.bedrockvc;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * A QR code encoder (byte mode, versions 1-40, automatic mask), so the join link can be drawn on an in-game map and
 * scanned with a phone camera off any screen: a TV running a console, a Switch in handheld, a PC monitor.
 * The algorithm and tables follow Project Nayuki's "QR Code generator library" (MIT License,
 * https://www.nayuki.io/page/qr-code-generator-library), rewritten in a compact form for this plugin.
 */
final class QrCode {
    enum Ecc { L(1), M(0), Q(3), H(2); final int bits; Ecc(int b) { bits = b; } }

    final int size;
    private final boolean[][] mods;
    private final boolean[][] fn;

    boolean get(int x, int y) { return x >= 0 && y >= 0 && x < size && y < size && mods[y][x]; }

    private static final byte[][] ECC_PER_BLOCK = {
        {-1, 7, 10, 15, 20, 26, 18, 20, 24, 30, 18, 20, 24, 26, 30, 22, 24, 28, 30, 28, 28, 28, 28, 30, 30, 26, 28, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30},
        {-1, 10, 16, 26, 18, 24, 16, 18, 22, 22, 26, 30, 22, 22, 24, 24, 28, 28, 26, 26, 26, 26, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28},
        {-1, 13, 22, 18, 26, 18, 24, 18, 22, 20, 24, 28, 26, 24, 20, 30, 24, 28, 28, 26, 30, 28, 30, 30, 30, 30, 28, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30},
        {-1, 17, 28, 22, 16, 22, 28, 26, 26, 24, 28, 24, 28, 22, 24, 24, 30, 28, 28, 26, 28, 30, 24, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30},
    };
    private static final byte[][] NUM_BLOCKS = {
        {-1, 1, 1, 1, 1, 1, 2, 2, 2, 2, 4, 4, 4, 4, 4, 6, 6, 6, 6, 7, 8, 8, 9, 9, 10, 12, 12, 12, 13, 14, 15, 16, 17, 18, 19, 19, 20, 21, 22, 24, 25},
        {-1, 1, 1, 1, 2, 2, 4, 4, 4, 5, 5, 5, 8, 9, 9, 10, 10, 11, 13, 14, 16, 17, 17, 18, 20, 21, 23, 25, 26, 28, 29, 31, 33, 35, 37, 38, 40, 43, 45, 47, 49},
        {-1, 1, 1, 2, 2, 4, 4, 6, 6, 8, 8, 8, 10, 12, 16, 12, 17, 16, 18, 21, 20, 23, 23, 25, 27, 29, 34, 34, 35, 38, 40, 43, 45, 48, 51, 53, 56, 59, 62, 65, 68},
        {-1, 1, 1, 2, 4, 4, 4, 5, 6, 8, 8, 11, 11, 16, 16, 18, 16, 19, 21, 25, 25, 25, 34, 30, 32, 35, 37, 40, 42, 45, 48, 51, 54, 57, 60, 63, 66, 70, 74, 77, 81},
    };

    /** The smallest code that holds this text at this error correction (byte mode, UTF-8). */
    static QrCode encode(String text, Ecc ecc) {
        byte[] data = text.getBytes(StandardCharsets.UTF_8);
        for (int ver = 1; ver <= 40; ver++) {
            int cap = dataCodewords(ver, ecc) * 8;
            int ccBits = ver <= 9 ? 8 : 16;
            int used = 4 + ccBits + data.length * 8;
            if (used > cap) continue;
            // a bigger error correction level when it still fits in this version
            for (Ecc e : new Ecc[]{Ecc.M, Ecc.Q, Ecc.H})
                if (rank(e) > rank(ecc) && used <= dataCodewords(ver, e) * 8) ecc = e;
            cap = dataCodewords(ver, ecc) * 8;
            Bits bb = new Bits();
            bb.add(4, 4);
            bb.add(data.length, ccBits);
            for (byte b : data) bb.add(b & 0xFF, 8);
            bb.add(0, Math.min(4, cap - bb.len));
            bb.add(0, (8 - bb.len % 8) % 8);
            for (int pad = 0xEC; bb.len < cap; pad ^= 0xEC ^ 0x11) bb.add(pad, 8);
            byte[] cw = new byte[bb.len / 8];
            for (int i = 0; i < bb.len; i++) if (bb.get(i)) cw[i >>> 3] |= (byte) (1 << (7 - (i & 7)));
            return new QrCode(ver, ecc, cw);
        }
        throw new IllegalArgumentException("Text too long for a QR code");
    }

    private static int rank(Ecc e) { return switch (e) { case L -> 0; case M -> 1; case Q -> 2; case H -> 3; }; }

    private static final class Bits {
        byte[] b = new byte[64]; int len;
        void add(int val, int n) {
            for (int i = n - 1; i >= 0; i--) {
                if (len >= b.length * 8) b = Arrays.copyOf(b, b.length * 2);
                if (((val >>> i) & 1) != 0) b[len >>> 3] |= (byte) (1 << (7 - (len & 7)));
                len++;
            }
        }
        boolean get(int i) { return ((b[i >>> 3] >>> (7 - (i & 7))) & 1) != 0; }
    }

    private QrCode(int ver, Ecc ecc, byte[] dataCodewords) {
        size = ver * 4 + 17;
        mods = new boolean[size][size];
        fn = new boolean[size][size];
        drawFunctionPatterns(ver, ecc);
        byte[] all = addEccAndInterleave(ver, ecc, dataCodewords);
        drawCodewords(all);
        // pick the mask with the lowest penalty
        int best = 0; long bestPen = Long.MAX_VALUE;
        for (int m = 0; m < 8; m++) {
            applyMask(m);
            drawFormatBits(ecc, m);
            long pen = penalty();
            if (pen < bestPen) { best = m; bestPen = pen; }
            applyMask(m); // undo (XOR)
        }
        applyMask(best);
        drawFormatBits(ecc, best);
    }

    private void set(int x, int y, boolean dark) { mods[y][x] = dark; fn[y][x] = true; }

    private void drawFunctionPatterns(int ver, Ecc ecc) {
        for (int i = 0; i < size; i++) { set(6, i, i % 2 == 0); set(i, 6, i % 2 == 0); }
        finder(3, 3); finder(size - 4, 3); finder(3, size - 4);
        int[] al = alignmentPositions(ver);
        int n = al.length;
        for (int i = 0; i < n; i++) for (int j = 0; j < n; j++) {
            if ((i == 0 && j == 0) || (i == 0 && j == n - 1) || (i == n - 1 && j == 0)) continue;
            for (int dy = -2; dy <= 2; dy++) for (int dx = -2; dx <= 2; dx++)
                set(al[i] + dx, al[j] + dy, Math.max(Math.abs(dx), Math.abs(dy)) != 1);
        }
        drawFormatBits(ecc, 0); // reserves the area
        if (ver >= 7) {
            int rem = ver;
            for (int i = 0; i < 12; i++) rem = (rem << 1) ^ ((rem >>> 11) * 0x1F25);
            long bits = ((long) ver) << 12 | rem;
            for (int i = 0; i < 18; i++) {
                boolean bit = ((bits >>> i) & 1) != 0;
                int a = size - 11 + i % 3, b = i / 3;
                set(a, b, bit); set(b, a, bit);
            }
        }
    }

    private void finder(int cx, int cy) {
        for (int dy = -4; dy <= 4; dy++) for (int dx = -4; dx <= 4; dx++) {
            int d = Math.max(Math.abs(dx), Math.abs(dy)), x = cx + dx, y = cy + dy;
            if (x >= 0 && x < size && y >= 0 && y < size) set(x, y, d != 2 && d != 4);
        }
    }

    private static int[] alignmentPositions(int ver) {
        if (ver == 1) return new int[0];
        int num = ver / 7 + 2;
        int step = (ver == 32) ? 26 : (ver * 4 + num * 2 + 1) / (num * 2 - 2) * 2;
        int[] r = new int[num];
        r[0] = 6;
        for (int i = r.length - 1, pos = ver * 4 + 10; i >= 1; i--, pos -= step) r[i] = pos;
        return r;
    }

    private void drawFormatBits(Ecc ecc, int mask) {
        int data = ecc.bits << 3 | mask;
        int rem = data;
        for (int i = 0; i < 10; i++) rem = (rem << 1) ^ ((rem >>> 9) * 0x537);
        int bits = (data << 10 | rem) ^ 0x5412;
        for (int i = 0; i <= 5; i++) set(8, i, bit(bits, i));
        set(8, 7, bit(bits, 6)); set(8, 8, bit(bits, 7)); set(7, 8, bit(bits, 8));
        for (int i = 9; i < 15; i++) set(14 - i, 8, bit(bits, i));
        for (int i = 0; i < 8; i++) set(size - 1 - i, 8, bit(bits, i));
        for (int i = 8; i < 15; i++) set(8, size - 15 + i, bit(bits, i));
        set(8, size - 8, true);
    }

    private static boolean bit(int x, int i) { return ((x >>> i) & 1) != 0; }

    static int rawModules(int ver) {
        int r = (16 * ver + 128) * ver + 64;
        if (ver >= 2) { int n = ver / 7 + 2; r -= (25 * n - 10) * n - 55; if (ver >= 7) r -= 36; }
        return r;
    }

    static int dataCodewords(int ver, Ecc ecc) {
        int e = rank(ecc);
        return rawModules(ver) / 8 - ECC_PER_BLOCK[e][ver] * NUM_BLOCKS[e][ver];
    }

    private static byte[] addEccAndInterleave(int ver, Ecc ecc, byte[] data) {
        int e = rank(ecc);
        int numBlocks = NUM_BLOCKS[e][ver], blockEcc = ECC_PER_BLOCK[e][ver];
        int raw = rawModules(ver) / 8;
        int numShort = numBlocks - raw % numBlocks, shortLen = raw / numBlocks;
        byte[][] blocks = new byte[numBlocks][];
        byte[] div = rsDivisor(blockEcc);
        for (int i = 0, k = 0; i < numBlocks; i++) {
            int len = shortLen - blockEcc + (i < numShort ? 0 : 1);
            byte[] dat = Arrays.copyOfRange(data, k, k + len);
            k += len;
            byte[] block = Arrays.copyOf(dat, shortLen + 1);
            byte[] ec = rsRemainder(dat, div);
            System.arraycopy(ec, 0, block, block.length - blockEcc, ec.length);
            blocks[i] = block;
        }
        byte[] out = new byte[raw];
        for (int i = 0, k = 0; i < blocks[0].length; i++) for (int j = 0; j < blocks.length; j++) {
            if (i != shortLen - blockEcc || j >= numShort) out[k++] = blocks[j][i];
        }
        return out;
    }

    private static byte[] rsDivisor(int degree) {
        byte[] r = new byte[degree];
        r[degree - 1] = 1;
        int root = 1;
        for (int i = 0; i < degree; i++) {
            for (int j = 0; j < r.length; j++) {
                r[j] = (byte) mul(r[j] & 0xFF, root);
                if (j + 1 < r.length) r[j] ^= r[j + 1];
            }
            root = mul(root, 0x02);
        }
        return r;
    }

    private static byte[] rsRemainder(byte[] data, byte[] div) {
        byte[] r = new byte[div.length];
        for (byte b : data) {
            int f = (b ^ r[0]) & 0xFF;
            System.arraycopy(r, 1, r, 0, r.length - 1);
            r[r.length - 1] = 0;
            for (int i = 0; i < r.length; i++) r[i] ^= (byte) mul(div[i] & 0xFF, f);
        }
        return r;
    }

    private static int mul(int x, int y) {
        int z = 0;
        for (int i = 7; i >= 0; i--) { z = (z << 1) ^ ((z >>> 7) * 0x11D); z ^= ((y >>> i) & 1) * x; }
        return z;
    }

    private void drawCodewords(byte[] data) {
        int i = 0;
        for (int right = size - 1; right >= 1; right -= 2) {
            if (right == 6) right = 5;
            for (int vert = 0; vert < size; vert++) for (int j = 0; j < 2; j++) {
                int x = right - j;
                boolean up = ((right + 1) & 2) == 0;
                int y = up ? size - 1 - vert : vert;
                if (!fn[y][x] && i < data.length * 8) {
                    mods[y][x] = ((data[i >>> 3] >>> (7 - (i & 7))) & 1) != 0;
                    i++;
                }
            }
        }
    }

    private void applyMask(int m) {
        for (int y = 0; y < size; y++) for (int x = 0; x < size; x++) {
            if (fn[y][x]) continue;
            boolean inv = switch (m) {
                case 0 -> (x + y) % 2 == 0;
                case 1 -> y % 2 == 0;
                case 2 -> x % 3 == 0;
                case 3 -> (x + y) % 3 == 0;
                case 4 -> (x / 3 + y / 2) % 2 == 0;
                case 5 -> x * y % 2 + x * y % 3 == 0;
                case 6 -> (x * y % 2 + x * y % 3) % 2 == 0;
                default -> ((x + y) % 2 + x * y % 3) % 2 == 0;
            };
            if (inv) mods[y][x] = !mods[y][x];
        }
    }

    private long penalty() {
        long r = 0;
        for (int pass = 0; pass < 2; pass++) {
            for (int a = 0; a < size; a++) {
                boolean col = false; int run = 0;
                int[] hist = new int[7];
                for (int b = 0; b < size; b++) {
                    boolean v = pass == 0 ? mods[a][b] : mods[b][a];
                    if (v == col) { run++; if (run == 5) r += 3; else if (run > 5) r++; }
                    else { addHist(run, hist); if (!col) r += finderLike(hist) * 40L; col = v; run = 1; }
                }
                r += finderTerminate(col, run, hist) * 40L;
            }
        }
        for (int y = 0; y < size - 1; y++) for (int x = 0; x < size - 1; x++) {
            boolean c = mods[y][x];
            if (c == mods[y][x + 1] && c == mods[y + 1][x] && c == mods[y + 1][x + 1]) r += 3;
        }
        int dark = 0;
        for (boolean[] row : mods) for (boolean v : row) if (v) dark++;
        int total = size * size;
        int k = (Math.abs(dark * 20 - total * 10) + total - 1) / total - 1;
        r += k * 10L;
        return r;
    }

    private void addHist(int run, int[] h) {
        if (h[0] == 0) run += size;
        System.arraycopy(h, 0, h, 1, h.length - 1);
        h[0] = run;
    }

    private int finderLike(int[] h) {
        int n = h[1];
        boolean core = n > 0 && h[2] == n && h[3] == n * 3 && h[4] == n && h[5] == n;
        return (core && h[0] >= n * 4 && h[6] >= n ? 1 : 0) + (core && h[6] >= n * 4 && h[0] >= n ? 1 : 0);
    }

    private int finderTerminate(boolean col, int run, int[] h) {
        if (col) { addHist(run, h); run = 0; }
        run += size;
        addHist(run, h);
        return finderLike(h);
    }
}
