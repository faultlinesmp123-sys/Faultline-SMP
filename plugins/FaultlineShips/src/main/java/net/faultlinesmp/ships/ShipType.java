package net.faultlinesmp.ships;

import org.bukkit.Material;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The three ships and their block layouts.
 *
 * Local coordinates: +x is the bow (front), -x the stern, +z starboard (right), y = 0 is the top water block
 * (the bottom of the hull sits in it). Block data facings are local too: "east" points at the bow, "south" to
 * starboard. Every cell is one block the players place while building.
 */
enum ShipType {
    SLOOP("Sloop", "small", 150, 0.36, 3.6, 0, false),
    BRIGANTINE("Brigantine", "medium", 350, 0.30, 2.4, 27, true),
    GALLEON("Galleon", "big", 700, 0.25, 1.6, 54, true);

    /** What a cell needs: any block of a kind (any wood works, the ship keeps the one you used). */
    enum Need {
        PLANKS("Planks", Material.OAK_PLANKS), LOG("Logs", Material.OAK_LOG), FENCE("Wooden Fences", Material.OAK_FENCE),
        STAIRS("Wooden Stairs", Material.OAK_STAIRS), WOOL("Wool (sails)", Material.WHITE_WOOL),
        CHEST("Chests", Material.CHEST), LANTERN("Lanterns", Material.LANTERN);

        final String label;
        final Material ghost;
        Need(String label, Material ghost) { this.label = label; this.ghost = ghost; }

        private static final String[] WOODS = {"OAK", "SPRUCE", "BIRCH", "JUNGLE", "ACACIA", "DARK_OAK", "MANGROVE",
                "CHERRY", "PALE_OAK", "BAMBOO", "CRIMSON", "WARPED"};

        /** By name, so it works on every version (and in tests, where block tags are empty). */
        boolean accepts(Material m) {
            if (m == null || !m.isBlock()) return false;
            String n = m.name();
            return switch (this) {
                case PLANKS -> n.endsWith("_PLANKS");
                case LOG -> !n.contains("POTTED") && (n.endsWith("_LOG") || n.endsWith("_WOOD") || n.endsWith("_STEM")
                        || n.endsWith("_HYPHAE") || n.equals("BAMBOO_BLOCK") || n.equals("STRIPPED_BAMBOO_BLOCK"));
                case FENCE -> n.endsWith("_FENCE") && wood(n);
                case STAIRS -> n.endsWith("_STAIRS") && wood(n);
                case WOOL -> n.endsWith("_WOOL");
                case CHEST -> m == Material.CHEST || m == Material.TRAPPED_CHEST || m == Material.BARREL;
                case LANTERN -> m == Material.LANTERN || m == Material.SOUL_LANTERN;
            };
        }

        private static boolean wood(String n) {
            for (String w : WOODS) if (n.startsWith(w + "_")) return true;
            return false;
        }
    }

    /** One block of the ship. props = block state in local facings, e.g. "facing=west,half=bottom". */
    record Cell(int x, int y, int z, Need need, String props) {}

    final String title, size;
    final double maxHp, speed, turn; // speed in blocks/tick at full sail, turn in degrees/tick
    final int cargo;                 // cargo slots (0 = no hold)
    final boolean deep;              // needs water under the keel too
    List<Cell> cells;
    double[][] seats;                // local x, y (feet), z; seat 0 is the helm
    int deckY, minX, maxX, halfWidth, height;
    List<Integer> probes;            // cells checked against the world while sailing (hull sides, rails, keel)
    Map<Long, Integer> index;        // local x,y,z -> cell number

    ShipType(String title, String size, double maxHp, double speed, double turn, int cargo, boolean deep) {
        this.title = title; this.size = size; this.maxHp = maxHp; this.speed = speed; this.turn = turn;
        this.cargo = cargo; this.deep = deep;
    }

    static {
        for (ShipType t : values()) t.build();
    }

    static long key3(int x, int y, int z) { return ((long) (x & 0x1fffff) << 42) | ((long) (y & 0x1fffff) << 21) | (z & 0x1fffff); }

    int cellAt(int x, int y, int z) {
        Integer i = index.get(key3(x, y, z));
        return i == null ? -1 : i;
    }

    static ShipType of(String s) {
        if (s == null) return null;
        for (ShipType t : values()) if (t.name().equalsIgnoreCase(s) || t.size.equalsIgnoreCase(s) || t.title.equalsIgnoreCase(s)) return t;
        return null;
    }

    Map<Need, Integer> bill() {
        Map<Need, Integer> m = new LinkedHashMap<>();
        for (Need n : Need.values()) m.put(n, 0);
        for (Cell c : cells) m.merge(c.need(), 1, Integer::sum);
        m.values().removeIf(v -> v == 0);
        return m;
    }

    // =====================================================================================================
    //  layouts
    // =====================================================================================================
    private void build() {
        Layout l = new Layout();
        switch (this) {
            case SLOOP -> {
                l.hull(-4, new int[]{3, 5, 5, 5, 5, 5, 5, 3, 1}, 1);
                l.mast(0, 2, 9);
                l.sail(0, 3, new int[]{2, 2, 2, 1, 1});
                l.bowsprit(5, 2, 2);
                l.helm(-2, 2);
                l.lantern(-4, 3, -1); l.lantern(-4, 3, 1);
                l.seat(1, 2, -1); l.seat(1, 2, 1);
            }
            case BRIGANTINE -> {
                l.hull(-7, new int[]{5, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 5, 3, 1}, 1);
                l.castle(-7, -4, 2, -1, 1, true);
                l.mast(-1, 2, 12);
                l.sail(-1, 4, new int[]{3, 3, 3, 2, 2, 2});
                l.mast(4, 2, 10);
                l.sail(4, 4, new int[]{3, 3, 2, 2});
                l.bowsprit(8, 2, 3);
                l.helm(-5, 3);  // on the castle, clear of the stairs
                l.chest(2, 2, -2, "south");
                l.lantern(-7, 4, -2); l.lantern(-7, 4, 2);
                l.seat(1, 2, -2); l.seat(1, 2, 2); l.seat(-3, 2, -2); l.seat(-3, 2, 2);
            }
            case GALLEON -> {
                int[] w = new int[23];
                for (int i = 0; i < 23; i++) w[i] = 9;
                w[0] = 7; w[19] = 7; w[20] = 5; w[21] = 3; w[22] = 1;
                l.hull(-11, w, 2);
                l.castle(-11, -6, 3, -1, 1, true);  // quarterdeck
                l.castle(-11, -8, 4, 0, 0, true);   // poop deck, on top of it
                l.castle(7, 11, 3, -1, 1, false);   // forecastle
                l.mast(-3, 3, 16);
                l.sail(-3, 6, new int[]{3, 3, 3, 2, 2, 2});
                l.mast(2, 3, 19);
                l.sail(2, 6, new int[]{4, 4, 4, 3, 3, 2, 2});
                l.mast(7, 4, 15);
                l.sail(7, 7, new int[]{3, 3, 3, 2, 2});
                l.bowsprit(12, 4, 4);
                l.helm(-9, 5);  // on the poop deck
                l.chest(-1, 3, -3, "south"); l.chest(-1, 3, 3, "north");
                l.lantern(-11, 6, -3); l.lantern(-11, 6, 3);
                l.seat(0, 3, -3); l.seat(0, 3, 3); l.seat(4, 3, -3); l.seat(4, 3, 3);
                l.seat(-4, 3, -3); l.seat(-4, 3, 3); l.seat(-7, 4, -3); l.seat(-7, 4, 3);
            }
        }
        l.finish(this);
    }

    /** Builds a layout: hull, raised decks, masts, sails, then rails around every deck and fence connections. */
    private static final class Layout {
        final Map<Long, Cell> cells = new LinkedHashMap<>();
        final List<Set<Long>> floors = new ArrayList<>();
        final List<Integer> floorY = new ArrayList<>();
        final List<Set<Long>> openings = new ArrayList<>();
        final List<double[]> seats = new ArrayList<>();
        final List<Cell> late = new ArrayList<>(); // placed after the rails (bowsprit, lanterns)
        int deckY, minX, maxX, half;
        double[] helmSeat;

        static long key(int x, int z) { return ((long) x << 32) ^ (z & 0xffffffffL); }
        static long key3(int x, int y, int z) { return ShipType.key3(x, y, z); }

        void put(int x, int y, int z, Need n, String props) { cells.put(key3(x, y, z), new Cell(x, y, z, n, props)); }
        boolean has(int x, int y, int z) { return cells.containsKey(key3(x, y, z)); }

        void hull(int stern, int[] widths, int deckY) {
            this.deckY = deckY;
            minX = stern; maxX = stern + widths.length - 1;
            Set<Long> deck = new HashSet<>();
            for (int i = 0; i < widths.length; i++) {
                int x = stern + i, h = (widths[i] - 1) / 2;
                half = Math.max(half, h);
                for (int z = -h; z <= h; z++) deck.add(key(x, z));
            }
            for (int i = 0; i < widths.length; i++) {
                int x = stern + i, h = (widths[i] - 1) / 2, hb = Math.max(0, h - 1);
                for (int z = -h; z <= h; z++) {
                    put(x, deckY, z, Need.PLANKS, "");
                    // the bottom is a narrower ring with a keel down the middle: the deck overhangs it like a real hull
                    if (Math.abs(z) <= hb && (Math.abs(z) == hb || z == 0 || i == 0 || i == widths.length - 1)) put(x, 0, z, Need.PLANKS, "");
                    // a tall hull (deck at y 2) gets walls between the bottom and the deck
                    for (int y = 1; y < deckY; y++) if (edge(deck, x, z)) put(x, y, z, Need.PLANKS, "");
                }
            }
            floors.add(deck); floorY.add(deckY); openings.add(new HashSet<>());
        }

        /** A raised deck from x0 to x1 (inclusive) whose floor is at y, with stairs up to it on its open side. */
        void castle(int x0, int x1, int y, int stairZ0, int stairZ1, boolean stern) {
            Set<Long> main = floors.get(0), f = new HashSet<>(), open = new HashSet<>();
            for (long k : main) {
                int x = (int) (k >> 32), z = (int) k;
                if (x >= x0 && x <= x1) { f.add(k); put(x, y, z, Need.PLANKS, ""); }
            }
            int sx = stern ? x1 + 1 : x0 - 1, edgeX = stern ? x1 : x0;
            for (int z = stairZ0; z <= stairZ1; z++) {
                put(sx, y, z, Need.STAIRS, "facing=" + (stern ? "west" : "east") + ",half=bottom,shape=straight");
                open.add(key(edgeX, z));
            }
            floors.add(f); floorY.add(y); openings.add(open);
        }

        void mast(int x, int y0, int y1) { for (int y = y0; y <= y1; y++) put(x, y, 0, Need.LOG, "axis=y"); }

        /** Square sail across the ship at the mast, rows from the bottom up (half widths); a yard on top. */
        void sail(int x, int y0, int[] halves) {
            for (int r = 0; r < halves.length; r++) for (int z = -halves[r]; z <= halves[r]; z++) {
                if (z != 0) put(x, y0 + r, z, Need.WOOL, "");
            }
            int top = halves[halves.length - 1];
            for (int z = -top; z <= top; z++) if (z != 0) put(x, y0 + halves.length, z, Need.FENCE, "");
        }

        void bowsprit(int x0, int y, int len) { for (int i = 0; i < len; i++) late.add(new Cell(x0 + i, y, 0, Need.FENCE, "")); }
        void lantern(int x, int y, int z) { late.add(new Cell(x, y, z, Need.LANTERN, "hanging=false")); }
        void chest(int x, int y, int z, String facing) { put(x, y, z, Need.CHEST, "facing=" + facing); }
        void seat(int x, int y, int z) { seats.add(new double[]{x, y, z}); }

        /** The wheel: a fence post on the deck at floor level y, the captain sits right behind it. */
        void helm(int x, int y) { put(x, y, 0, Need.FENCE, ""); helmSeat = new double[]{x - 1, y, 0}; }

        static boolean edge(Set<Long> f, int x, int z) {
            return !f.contains(key(x + 1, z)) || !f.contains(key(x - 1, z)) || !f.contains(key(x, z + 1)) || !f.contains(key(x, z - 1));
        }

        void finish(ShipType t) {
            // rails around every deck (skip where something already stands, or where stairs come up)
            for (int i = 0; i < floors.size(); i++) {
                Set<Long> f = floors.get(i);
                int y = floorY.get(i) + 1;
                for (long k : f) {
                    int x = (int) (k >> 32), z = (int) k;
                    if (!edge(f, x, z) || openings.get(i).contains(k) || has(x, y, z)) continue;
                    // a raised deck's edge over open main deck needs a rail; its outer edge too
                    put(x, y, z, Need.FENCE, "");
                }
            }
            for (Cell c : late) put(c.x(), c.y(), c.z(), c.need(), c.props());
            // fences connect to fences and solid blocks beside them
            List<Cell> out = new ArrayList<>();
            for (Cell c : cells.values()) {
                if (c.need() != Need.FENCE) { out.add(c); continue; }
                String p = "east=" + joins(c.x() + 1, c.y(), c.z()) + ",west=" + joins(c.x() - 1, c.y(), c.z())
                        + ",south=" + joins(c.x(), c.y(), c.z() + 1) + ",north=" + joins(c.x(), c.y(), c.z() - 1);
                out.add(new Cell(c.x(), c.y(), c.z(), c.need(), p));
            }
            // build order: bottom up, so the ghost fills in like a real build
            out.sort((a, b) -> a.y() != b.y() ? Integer.compare(a.y(), b.y()) : a.x() != b.x() ? Integer.compare(a.x(), b.x()) : Integer.compare(a.z(), b.z()));
            t.cells = Collections.unmodifiableList(out);
            List<double[]> s = new ArrayList<>();
            s.add(helmSeat);
            s.addAll(seats);
            t.seats = s.toArray(new double[0][]);
            t.deckY = deckY; t.minX = minX; t.maxX = maxX; t.halfWidth = half;
            int top = 0;
            for (Cell c : out) top = Math.max(top, c.y());
            t.height = top + 1;
            // probes: the bottom, and the outer cells of everything up to the rails (what would scrape the shore)
            Map<Long, Integer> idx = new HashMap<>();
            for (int i = 0; i < out.size(); i++) idx.put(key3(out.get(i).x(), out.get(i).y(), out.get(i).z()), i);
            List<Integer> probes = new ArrayList<>();
            for (int i = 0; i < out.size(); i++) {
                Cell c = out.get(i);
                boolean outer = !idx.containsKey(key3(c.x() + 1, c.y(), c.z())) || !idx.containsKey(key3(c.x() - 1, c.y(), c.z()))
                        || !idx.containsKey(key3(c.x(), c.y(), c.z() + 1)) || !idx.containsKey(key3(c.x(), c.y(), c.z() - 1));
                if (c.y() == 0 || (outer && c.y() <= deckY + 2) || c.x() > maxX) probes.add(i);
            }
            t.probes = Collections.unmodifiableList(probes);
            t.index = Collections.unmodifiableMap(idx);
        }

        boolean joins(int x, int y, int z) {
            Cell n = cells.get(key3(x, y, z));
            return n != null && (n.need() == Need.FENCE || n.need() == Need.PLANKS || n.need() == Need.LOG || n.need() == Need.STAIRS);
        }
    }
}
