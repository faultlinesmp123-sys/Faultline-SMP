package net.faultlinesmp.world;

import org.bukkit.block.data.BlockData;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.*;
import java.util.zip.GZIPInputStream;

/**
 * A Litematica schematic (.litematic: gzipped NBT, block states packed into a long array), kept compactly: per
 * column (x, z) only the non-air blocks (y + palette index), plus each column's lowest / highest solid y.
 */
final class Schematic {
    final String name;
    final int sx, sy, sz;
    final String[] paletteNames;
    BlockData[] palette;                 // resolved on the main thread (Bukkit.createBlockData)
    final int[] colStart;                // sx*sz+1 offsets into ys/idx
    final short[] ys, idx;
    final short[] colMin, colMax;        // -1 = empty column
    int floorY;                          // the ground floor (lowest layer covering 30%+ of the footprint)

    private Schematic(String name, int sx, int sy, int sz, String[] pal, int[] colStart, short[] ys, short[] idx, short[] mn, short[] mx) {
        this.name = name; this.sx = sx; this.sy = sy; this.sz = sz; this.paletteNames = pal;
        this.colStart = colStart; this.ys = ys; this.idx = idx; this.colMin = mn; this.colMax = mx;
    }

    /** The palette index at a local cell, or -1 (air / outside). Each column's ys are in rising order. */
    int find(int x, int y, int z) {
        if (x < 0 || z < 0 || y < 0 || x >= sx || z >= sz || y >= sy) return -1;
        int c = x * sz + z, lo = colStart[c], hi = colStart[c + 1] - 1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1, v = ys[mid];
            if (v == y) return idx[mid];
            if (v < y) lo = mid + 1; else hi = mid - 1;
        }
        return -1;
    }

    /** Per palette index: a water SOURCE (the kind that spreads). */
    boolean[] source;
    /** Air cells (local, +1 on each axis so the ring outside the box fits) beside or under a water source: the build's
     *  water would run out through them. They get a block. */
    final Set<Long> plugs = new HashSet<>();

    void findPlugs() {
        source = new boolean[paletteNames.length];
        boolean any = false;
        for (int i = 0; i < source.length; i++) {
            String n = paletteNames[i];
            source[i] = n.equals("minecraft:water") || n.equals("minecraft:water[level=0]") || n.equals("minecraft:lava") || n.equals("minecraft:lava[level=0]");
            any |= source[i];
        }
        if (!any) return;
        int[][] around = {{1, 0, 0}, {-1, 0, 0}, {0, 0, 1}, {0, 0, -1}, {0, -1, 0}};
        for (int x = 0; x < sx; x++) for (int z = 0; z < sz; z++) {
            int c = x * sz + z;
            for (int i = colStart[c]; i < colStart[c + 1]; i++) {
                if (!source[idx[i]]) continue;
                int y = ys[i];
                for (int[] o : around) if (find(x + o[0], y + o[1], z + o[2]) < 0) plugs.add(Sites.pack(x + o[0] + 1, y + o[1] + 1, z + o[2] + 1));
            }
        }
    }

    /** True when a water / lava source is right next to this cell (so taking the cell's block out would let it run). */
    boolean besideSource(int x, int y, int z) {
        if (source == null) return false;
        int i;
        return (i = find(x + 1, y, z)) >= 0 && source[i] || (i = find(x - 1, y, z)) >= 0 && source[i] || (i = find(x, y, z + 1)) >= 0 && source[i]
                || (i = find(x, y, z - 1)) >= 0 && source[i] || (i = find(x, y + 1, z)) >= 0 && source[i];
    }

    /** Old block names some schematics carry. */
    static final Map<String, String> RENAME = Map.of(
            "minecraft:grass", "minecraft:short_grass",
            "minecraft:grass_path", "minecraft:dirt_path",
            "minecraft:chain", "minecraft:iron_chain");

    static Schematic read(String name, InputStream raw) throws IOException {
        DataInputStream in = new DataInputStream(new GZIPInputStream(raw, 1 << 16));
        int type = in.readUnsignedByte();
        if (type != 10) throw new IOException("not an NBT compound");
        in.readUTF();
        Map<String, Object> root = readCompound(in);
        Map<String, Object> regions = cast(root.get("Regions"));
        Map<String, Object> reg = cast(regions.values().iterator().next());
        Map<String, Object> size = cast(reg.get("Size"));
        int sx = Math.abs((int) size.get("x")), sy = Math.abs((int) size.get("y")), sz = Math.abs((int) size.get("z"));
        List<Object> pal = castList(reg.get("BlockStatePalette"));
        String[] names = new String[pal.size()];
        int airIdx = -1;
        for (int i = 0; i < pal.size(); i++) {
            Map<String, Object> e = cast(pal.get(i));
            String n = (String) e.get("Name");
            n = RENAME.getOrDefault(n, n);
            StringBuilder sb = new StringBuilder(n);
            Map<String, Object> props = cast(e.get("Properties"));
            if (props != null && !props.isEmpty()) {
                sb.append('[');
                boolean first = true;
                for (Map.Entry<String, Object> p : new TreeMap<>(props).entrySet()) { if (!first) sb.append(','); first = false; sb.append(p.getKey()).append('=').append(p.getValue()); }
                sb.append(']');
            }
            names[i] = sb.toString();
            if (n.equals("minecraft:air") || n.equals("minecraft:structure_void") || n.equals("minecraft:void_air")) { if (airIdx < 0 || n.equals("minecraft:air")) airIdx = i; }
        }
        long[] bits = (long[]) reg.get("BlockStates");
        int b = Math.max(2, 32 - Integer.numberOfLeadingZeros(Math.max(1, names.length - 1)));
        boolean[] isAir = new boolean[names.length];
        for (int i = 0; i < names.length; i++) isAir[i] = names[i].startsWith("minecraft:air") || names[i].startsWith("minecraft:structure_void") || names[i].startsWith("minecraft:void_air") || names[i].startsWith("minecraft:cave_air");
        int cols = sx * sz;
        int[] count = new int[cols + 1];
        long mask = (1L << b) - 1;
        long vol = (long) sx * sy * sz;
        // pass 1: count per column
        for (long i = 0; i < vol; i++) {
            int v = get(bits, i, b, mask);
            if (v < isAir.length && !isAir[v]) {
                int x = (int) (i % sx), z = (int) ((i / sx) % sz);
                count[x * sz + z + 1]++;
            }
        }
        for (int i = 0; i < cols; i++) count[i + 1] += count[i];
        int total = count[cols];
        short[] ys = new short[total], idx = new short[total];
        int[] fill = Arrays.copyOf(count, cols);
        short[] mn = new short[cols], mx = new short[cols];
        Arrays.fill(mn, (short) -1); Arrays.fill(mx, (short) -1);
        int[] layer = new int[sy];
        for (long i = 0; i < vol; i++) {
            int v = get(bits, i, b, mask);
            if (v >= isAir.length || isAir[v]) continue;
            int x = (int) (i % sx), z = (int) ((i / sx) % sz), y = (int) (i / ((long) sx * sz));
            int c = x * sz + z, p = fill[c]++;
            ys[p] = (short) y; idx[p] = (short) v;
            if (mn[c] < 0 || y < mn[c]) mn[c] = (short) y;
            if (y > mx[c]) mx[c] = (short) y;
            layer[y]++;
        }
        Schematic s = new Schematic(name, sx, sy, sz, names, count, ys, idx, mn, mx);
        s.floorY = 0;
        for (int y = 0; y < sy; y++) if (layer[y] > cols * 0.3) { s.floorY = y; break; }
        // AUDIT: a floor can be two or three blocks thick (the Fibers base, the ballroom): the ground floor is its TOP layer,
        // or the doorway was "cut" through solid floor and never found the inside.
        while (s.floorY + 1 < sy && layer[s.floorY + 1] > cols * 0.5) s.floorY++;
        return s;
    }

    private static int get(long[] a, long i, int b, long mask) {
        long bit = i * b;
        int w = (int) (bit >>> 6), o = (int) (bit & 63);
        long v = a[w] >>> o;
        if (o + b > 64 && w + 1 < a.length) v |= a[w + 1] << (64 - o);
        return (int) (v & mask);
    }

    @SuppressWarnings("unchecked") private static Map<String, Object> cast(Object o) { return (Map<String, Object>) o; }
    @SuppressWarnings("unchecked") private static List<Object> castList(Object o) { return (List<Object>) o; }

    // ------------------------------------------------------------------ tiny NBT reader

    private static Map<String, Object> readCompound(DataInputStream in) throws IOException {
        Map<String, Object> m = new HashMap<>();
        while (true) {
            int t = in.readUnsignedByte();
            if (t == 0) return m;
            String k = in.readUTF();
            m.put(k, payload(in, t));
        }
    }

    private static Object payload(DataInputStream in, int t) throws IOException {
        switch (t) {
            case 1: return in.readByte();
            case 2: return in.readShort();
            case 3: return in.readInt();
            case 4: return in.readLong();
            case 5: return in.readFloat();
            case 6: return in.readDouble();
            case 7: { int n = in.readInt(); byte[] a = new byte[n]; in.readFully(a); return a; }
            case 8: return in.readUTF();
            case 9: { int et = in.readUnsignedByte(); int n = in.readInt(); List<Object> l = new ArrayList<>(n); for (int i = 0; i < n; i++) l.add(payload(in, et)); return l; }
            case 10: return readCompound(in);
            case 11: { int n = in.readInt(); int[] a = new int[n]; for (int i = 0; i < n; i++) a[i] = in.readInt(); return a; }
            case 12: { int n = in.readInt(); long[] a = new long[n]; for (int i = 0; i < n; i++) a[i] = in.readLong(); return a; }
            default: throw new IOException("bad NBT tag " + t);
        }
    }
}
