package net.faultlinesmp.deep;

/**
 * Abandoned ruins scattered over the Dark Ocean's floor. One may stand in each 176-block cell of the Sunlit Sea (half of
 * them have one) and each 200-block cell of the Abyss (45%): collapsed houses, crumbling towers, temple colonnades with
 * fallen columns, arch gates with broken walls, and in the Abyss, shrines of blackstone lit by soul lanterns. Many hold a
 * Dark Loot chest. Everything is waterlogged where it can be.
 */
final class Ruins {
    static final int SUN = 176, ABYSS = 200, REACH = 26;
    static final int HOUSE = 0, TOWER = 1, TEMPLE = 2, ARCH = 3, SHRINE = 4;
    static final String[] NAMES = {"ruined house", "ruined tower", "sunken temple", "broken gate", "abyss shrine"};

    /** A ruin's spot: world x, z, type, rotation, and a seed for its sizes. */
    record Site(int x, int z, int type, int r, long h, boolean abyss) { }

    static Site site(long seed, int gx, int gz, boolean abyss) {
        int cell = abyss ? ABYSS : SUN, salt = abyss ? 300 : 200;
        long h = DeepGenerator.hash(seed, gx, gz, salt);
        if (DeepGenerator.unit(h) > (abyss ? 0.45 : 0.5)) return null;
        int x = gx * cell + 32 + (int) (DeepGenerator.unit(DeepGenerator.hash(seed, gx, gz, salt + 1)) * (cell - 64));
        int z = gz * cell + 32 + (int) (DeepGenerator.unit(DeepGenerator.hash(seed, gx, gz, salt + 2)) * (cell - 64));
        if (Math.abs(x) > 1000 || Math.abs(z) > 1000) return null;                        // inside the world border
        if (Math.hypot(x, z) < 80) return null;                                            // not on the arrival point
        if (!abyss && Sulphur.near(x, z, 10)) return null;                                 // the Sulphur Sea has its own wrecks
        if (!abyss && MermaidDen.near(x, z, 10)) return null;                              // the Mermaid Den is lived in
        if (Math.max(Math.abs(x - DeepGenerator.FLAT_X), Math.abs(z - DeepGenerator.FLAT_Z)) < DeepGenerator.FLAT_HALF + DeepGenerator.FLAT_BLEND + 20) return null;
        if (!abyss) { // not in a blue hole's mouth
            int cx = Math.floorDiv(x, 144), cz = Math.floorDiv(z, 144);
            for (int i = -1; i <= 1; i++) for (int j = -1; j <= 1; j++) {
                int[] c = DeepGenerator.chimney(seed, cx + i, cz + j);
                if (c != null && Math.hypot(x - c[0], z - c[1]) < 34) return null;
            }
        }
        double t = DeepGenerator.unit(DeepGenerator.hash(seed, gx, gz, salt + 3));
        int type = abyss ? (t < 0.5 ? SHRINE : t < 0.75 ? TOWER : HOUSE) : (t < 0.4 ? HOUSE : t < 0.65 ? TOWER : t < 0.85 ? TEMPLE : ARCH);
        int r = (int) (DeepGenerator.unit(DeepGenerator.hash(seed, gx, gz, salt + 4)) * 4);
        return new Site(x, z, type, r, DeepGenerator.hash(seed, gx, gz, salt + 5), abyss);
    }

    static void paint(DeepGenerator g, DeepGenerator.Noise n, Paint p) {
        for (boolean abyss : new boolean[]{false, true}) {
            int cell = abyss ? ABYSS : SUN;
            for (int gx = Math.floorDiv(p.x0 - REACH, cell); gx <= Math.floorDiv(p.x0 + 15 + REACH, cell); gx++)
                for (int gz = Math.floorDiv(p.z0 - REACH, cell); gz <= Math.floorDiv(p.z0 + 15 + REACH, cell); gz++) {
                    Site s = site(n.seed, gx, gz, abyss);
                    if (s == null || !p.touches(s.x - REACH, s.z - REACH, s.x + REACH, s.z + REACH)) continue;
                    draw(g, n, p, s);
                }
        }
    }

    static int base(DeepGenerator g, DeepGenerator.Noise n, Site s) {
        return s.abyss ? g.abyssFloor(n, s.x, s.z) : g.upperFloor(n, s.x, s.z);
    }

    /** How tall it may stand: under the surface, or under the abyss's ceiling. */
    static int room(DeepGenerator g, DeepGenerator.Noise n, Site s, int b) {
        if (!s.abyss) return DeepGenerator.SEA - 4 - b;
        int ceil = g.abyssCeiling(n, s.x, s.z), up = g.upperFloor(n, s.x, s.z);
        if (up - ceil < 14) ceil = up - 14;
        return ceil - 4 - b;
    }

    static double u(Site s, int k) { return DeepGenerator.unit(DeepGenerator.hash(s.h, k, 7, 9)); }

    static void draw(DeepGenerator g, DeepGenerator.Noise n, Paint p, Site s) {
        int b = base(g, n, s), room = room(g, n, s, b);
        if (room < 6) return;
        Paint.Frame f = p.frame(s.x, s.z, s.r, true);
        String[] pal = palette(s);
        switch (s.type) {
            case HOUSE -> house(f, s, b, room, pal);
            case TOWER -> tower(f, s, b, room, pal);
            case TEMPLE -> temple(f, s, b, room);
            case ARCH -> arch(f, s, b, room, pal);
            default -> shrine(f, s, b, room);
        }
    }

    /** wall, accent, floor, stairs, slab. */
    static String[] palette(Site s) {
        if (s.abyss) return new String[]{"deepslate_bricks", "polished_deepslate", "deepslate_tiles", "deepslate_brick_stairs", "deepslate_brick_slab"};
        double t = u(s, 1);
        if (t < 0.45) return new String[]{"stone_bricks", "chiseled_stone_bricks", "stone_bricks", "stone_brick_stairs", "stone_brick_slab"};
        if (t < 0.7) return new String[]{"cut_sandstone", "chiseled_sandstone", "smooth_sandstone", "sandstone_stairs", "cut_sandstone_slab"};
        if (t < 0.88) return new String[]{"prismarine_bricks", "dark_prismarine", "prismarine_bricks", "prismarine_brick_stairs", "prismarine_brick_slab"};
        return new String[]{"bricks", "stone_bricks", "cobblestone", "brick_stairs", "brick_slab"};
    }

    static final String[] FACES = {"north", "east", "south", "west"};

    /** A foundation (down into the floor) and a cleared space above it, over a box. */
    static void footing(Paint.Frame f, int x0, int z0, int x1, int z1, int b, int clear, String floor) {
        for (int x = x0; x <= x1; x++) for (int z = z0; z <= z1; z++) {
            for (int y = b - 3; y <= b; y++) f.set(x, y, z, y == b ? floor : "cobblestone");
            for (int y = b + 1; y <= b + clear; y++) f.set(x, y, z, "water");
            clearUp(f, x, b + clear + 1, z);
        }
    }

    /** Kelp whose root was cleared away would float: take the rest of the stalk too. */
    static void clearUp(Paint.Frame f, int x, int y, int z) {
        for (; y < DeepGenerator.SEA; y++) {
            org.bukkit.Material m = f.get(x, y, z);
            if (m != org.bukkit.Material.KELP && m != org.bukkit.Material.KELP_PLANT && m != org.bukkit.Material.TALL_SEAGRASS) return;
            f.set(x, y, z, "water");
        }
    }

    /** Things lying on the floor: fallen stairs and slabs, sea pickles, a skull. */
    static void litter(Paint.Frame f, int x0, int z0, int x1, int z1, int y, String[] pal, double rate) {
        for (int x = x0; x <= x1; x++) for (int z = z0; z <= z1; z++) {
            double r = f.u(x, y, z, 401);
            if (r < rate * 0.4) f.set(x, y, z, pal[3] + "[facing=" + FACES[(int) (r * 1000) % 4] + ",half=bottom,shape=straight]");
            else if (r < rate * 0.7) f.set(x, y, z, pal[4] + "[type=bottom]");
            else if (r < rate * 0.85) f.set(x, y, z, "sea_pickle[pickles=" + (1 + (int) (r * 997) % 4) + "]");
            else if (r < rate * 0.92) f.set(x, y, z, "seagrass");
            else if (r < rate * 0.95) f.set(x, y, z, "skeleton_skull[rotation=" + (int) (r * 1000) % 16 + "]");
        }
    }

    // ------------------------------------------------------------------------------------------- the ruins
    static void house(Paint.Frame f, Site s, int b, int room, String[] pal) {
        int w = 7 + (int) (u(s, 2) * 5), d = 7 + (int) (u(s, 3) * 5), hMax = Math.min(room - 1, 4 + (int) (u(s, 4) * 3));
        footing(f, -1, -1, w, d, b, hMax + 3, pal[2]);
        int door = w / 2;
        for (int x = 0; x < w; x++) for (int z = 0; z < d; z++) {
            boolean ex = x == 0 || x == w - 1, ez = z == 0 || z == d - 1;
            if (!ex && !ez) continue;
            boolean corner = ex && ez;
            for (int y = b + 1; y <= b + hMax + (corner ? 1 : 0); y++) {
                int k = y - b;
                if (z == 0 && x == door && k <= 2) continue;                                       // the doorway
                if (k == 2 && !corner && (ez ? x : z) % 3 == 1) continue;                          // window gaps
                double decay = (corner ? 0.0 : 0.06) + Math.max(0, k - 2) * (corner ? 0.12 : 0.2);
                if (f.u(x, y, z, 411) < decay) continue;
                f.set(x, y, z, corner ? pal[1] : pal[0]);
            }
        }
        // a fallen beam/stair line where the roof came down, and the rest of the roof lying inside
        litter(f, 1, 1, w - 2, d - 2, b + 1, pal, 0.3);
        litter(f, -1, -1, w, -1, b + 1, pal, 0.25);
        if (u(s, 5) < 0.4) f.set(1 + (int) (u(s, 6) * (w - 2)), b + 1, d - 2, "chest[facing=north]");
    }

    static void tower(Paint.Frame f, Site s, int b, int room, String[] pal) {
        double R = 3 + u(s, 2) * 1.6;
        int H = Math.min(room - 1, 10 + (int) (u(s, 3) * 9));
        int ri = (int) Math.ceil(R + 3);
        double fallAng = u(s, 4) * Math.PI * 2;
        for (int x = -ri; x <= ri; x++) for (int z = -ri; z <= ri; z++) {
            double d = Math.hypot(x, z);
            if (d <= R + 0.5) {
                for (int y = b - 3; y <= b; y++) f.set(x, y, z, y == b ? pal[2] : "cobblestone");
                for (int y = b + 1; y <= b + H + 2; y++) f.set(x, y, z, "water");
                clearUp(f, x, b + H + 3, z);
            }
            if (d > R + 0.5 || d <= R - 0.5) {
                if (d > R + 0.5 && d < R + 3 && f.u(x, b + 1, z, 421) < 0.16) f.set(x, b + 1, z, f.u(x, 0, z, 422) < 0.5 ? pal[0] : pal[4] + "[type=bottom]");
                continue;
            }
            double ang = Math.atan2(z, x);
            int top = H - (int) (f.u(x, 0, z, 423) * 4) - (int) (Math.max(0, Math.cos(ang - fallAng)) * H * 0.55);
            boolean doorway = z < 0 && Math.abs(x) <= 0;
            for (int y = b + 1; y <= b + top; y++) {
                int k = y - b;
                if (doorway && k <= 2) continue;
                if (k % 5 == 3 && Math.floorMod((int) Math.round(ang * 4 / Math.PI), 2) == 0 && Math.abs(Math.cos(ang * 2)) > 0.9) continue; // window slits
                f.set(x, y, z, k % 6 == 0 ? pal[1] : pal[0]);
            }
        }
        // half of an upper floor still clinging on, a broken stair of slabs up the inside
        int fy = b + Math.min(H - 2, 7);
        for (int x = -ri; x <= ri; x++) for (int z = -ri; z <= ri; z++) {
            double d = Math.hypot(x, z);
            if (d < R - 0.5 && x > 0 && f.u(x, fy, z, 424) > 0.25) f.set(x, fy, z, pal[4] + "[type=top]");
        }
        for (int k = 1; k < fy - b; k++) {
            double a = k * 0.7;
            int x = (int) Math.round(Math.cos(a) * (R - 1.2)), z = (int) Math.round(Math.sin(a) * (R - 1.2));
            if (f.u(x, b + k, z, 425) > 0.2) f.set(x, b + k, z, pal[4] + "[type=top]");
        }
        litter(f, -1, -1, 1, 1, b + 1, pal, 0.3);
        if (u(s, 5) < 0.45) f.set(0, b + 1, 1, "chest[facing=north]");
    }

    static void temple(Paint.Frame f, Site s, int b, int room) {
        double t = u(s, 6);
        String floor, col, cap, stairs, slab;
        if (t < 0.4) { floor = "prismarine_bricks"; col = "dark_prismarine"; cap = "sea_lantern"; stairs = "prismarine_brick_stairs"; slab = "prismarine_brick_slab"; }
        else if (t < 0.75) { floor = "quartz_bricks"; col = "quartz_pillar"; cap = "chiseled_quartz_block"; stairs = "quartz_stairs"; slab = "quartz_slab"; }
        else { floor = "cut_sandstone"; col = "chiseled_sandstone"; cap = "chiseled_sandstone"; stairs = "sandstone_stairs"; slab = "cut_sandstone_slab"; }
        String[] pal = {floor, cap, floor, stairs, slab};
        int H = Math.min(room - 3, 7);
        // a raised platform with steps all round
        for (int x = -6; x <= 6; x++) for (int z = -8; z <= 8; z++) {
            boolean step = x == -6 || x == 6 || z == -8 || z == 8;
            for (int y = b - 3; y <= b; y++) f.set(x, y, z, "cobblestone");
            f.set(x, b, z, floor);
            if (step) { if (f.u(x, b + 1, z, 431) > 0.15) f.set(x, b + 1, z, stairs + "[facing=" + (x == -6 ? "east" : x == 6 ? "west" : z == -8 ? "south" : "north") + ",half=bottom,shape=straight]"); }
            else f.set(x, b + 1, z, f.u(x, b + 1, z, 432) < 0.06 ? "water" : floor);
            for (int y = b + 2; y <= b + H + 3; y++) f.set(x, y, z, "water");
            clearUp(f, x, b + H + 4, z);
        }
        // two rows of columns; some have fallen and lie beside the platform
        boolean[] fell = new boolean[10];
        int i = 0;
        for (int sx : new int[]{-4, 4}) for (int z = -6; z <= 6; z += 3, i++) {
            fell[i] = f.u(sx, b, z, 433) < 0.35;
            if (fell[i]) {
                int stub = 1 + (int) (f.u(sx, b, z, 434) * 2);
                for (int y = b + 2; y < b + 2 + stub; y++) f.set(sx, y, z, col.equals("quartz_pillar") ? col + "[axis=y]" : col);
                int dir = sx < 0 ? -1 : 1;
                for (int k = 2; k <= H; k++) if (f.u(sx, k, z, 435) > 0.15) f.set(sx + dir * k, k < 3 ? b + 2 : b + 1, z, col.equals("quartz_pillar") ? col + "[axis=x]" : col);
                continue;
            }
            for (int y = b + 2; y < b + 2 + H; y++) f.set(sx, y, z, col.equals("quartz_pillar") ? col + "[axis=y]" : col);
            f.set(sx, b + 2 + H, z, cap);
        }
        // the lintels that still rest on standing columns
        i = 0;
        for (int sx : new int[]{-4, 4}) {
            for (int z = -6; z < 6; z += 3, i++) {
                if (fell[i] || fell[i + 1 < 10 ? i + 1 : i]) continue;
                for (int k = 0; k <= 3; k++) f.set(sx, b + 3 + H, z + k, floor);
            }
            i++;
        }
        // the altar and its chest
        f.set(0, b + 2, 0, cap); f.set(-1, b + 2, 0, stairs + "[facing=east,half=bottom,shape=straight]"); f.set(1, b + 2, 0, stairs + "[facing=west,half=bottom,shape=straight]");
        if (u(s, 5) < 0.6) f.set(0, b + 3, 0, "chest[facing=south]");
        f.set(-2, b + 1, -6, "sea_lantern"); f.set(2, b + 1, 6, "sea_lantern");
        litter(f, -3, -7, 3, 7, b + 2, pal, 0.12);
    }

    static void arch(Paint.Frame f, Site s, int b, int room, String[] pal) {
        int R = Math.min(4, room - 6), span = 6 + (int) (u(s, 2) * 5);
        footing(f, -R - span - 2, -1, R + span + 2, 2, b, R + 7, pal[2]);
        for (int x = -R - 1; x <= R + 1; x++) for (int z = 0; z <= 1; z++) {
            boolean pillar = Math.abs(x) >= R;
            for (int y = b + 1; y <= b + R + 6; y++) {
                int k = y - b;
                boolean on = pillar ? k <= R + 2 : Math.abs(Math.hypot(x, k - (R + 2)) - R) < 0.7 && k >= R + 2;
                if (!on && pillar && k == R + 3) on = true;
                if (!on || f.u(x, y, z, 441) < 0.1) continue;
                f.set(x, y, z, pillar && (k == 1 || k == R + 3) ? pal[1] : pal[0]);
            }
        }
        // broken walls running off either side
        for (int dir : new int[]{-1, 1}) for (int k = 1; k <= span; k++) {
            int x = dir * (R + 1 + k);
            int h = Math.max(0, 4 - (int) (k * 0.4) - (int) (f.u(x, 0, 0, 442) * 3));
            for (int y = b + 1; y <= b + h; y++) if (f.u(x, y, 0, 443) > 0.12) f.set(x, y, 0, pal[0]);
            if (h > 0 && f.u(x, 0, 1, 444) < 0.3) f.set(x, b + 1, 1, pal[4] + "[type=bottom]");
        }
        litter(f, -R, -1, R, 2, b + 1, pal, 0.2);
    }

    static void shrine(Paint.Frame f, Site s, int b, int room) {
        int H = Math.min(room - 1, 5 + (int) (u(s, 2) * 2));
        String[] pal = {"polished_blackstone_bricks", "chiseled_polished_blackstone", "polished_blackstone", "polished_blackstone_brick_stairs", "polished_blackstone_brick_slab"};
        for (int x = -7; x <= 7; x++) for (int z = -7; z <= 7; z++) {
            double d = Math.hypot(x, z);
            if (d > 7.3) continue;
            for (int y = b - 3; y <= b; y++) f.set(x, y, z, "blackstone");
            f.set(x, b + 1, z, d > 6.3 ? "polished_blackstone_brick_slab[type=bottom]" : (int) d % 2 == 0 ? "polished_blackstone_bricks" : "polished_blackstone");
            if (d > 5 && f.u(x, b, z, 451) < 0.15) f.set(x, b + 1, z, "sculk");
            for (int y = b + 2; y <= b + H + 3; y++) f.set(x, y, z, "water");
            clearUp(f, x, b + H + 4, z);
        }
        // a ring of pillars, each with a soul lantern on top (the only light down here), some broken
        for (int k = 0; k < 8; k++) {
            double a = k * Math.PI / 4;
            int x = (int) Math.round(Math.cos(a) * 5), z = (int) Math.round(Math.sin(a) * 5);
            boolean broken = f.u(x, b, z, 452) < 0.3;
            int h = broken ? 1 + (int) (f.u(x, b, z, 453) * 2) : H;
            for (int y = b + 2; y < b + 2 + h; y++) f.set(x, y, z, y == b + 1 + h && !broken ? "chiseled_polished_blackstone" : "polished_blackstone");
            if (!broken) f.set(x, b + 2 + h, z, "soul_lantern[hanging=false]");
            else f.set(x + (x > 0 ? 1 : -1), b + 2, z, "soul_lantern[hanging=false]");
        }
        // the altar: crying obsidian round a pedestal, a chest on top
        for (int x = -1; x <= 1; x++) for (int z = -1; z <= 1; z++) f.set(x, b + 1, z, (x == 0 && z == 0) ? "polished_blackstone" : "crying_obsidian");
        f.set(0, b + 2, 0, "chiseled_polished_blackstone");
        if (u(s, 5) < 0.7) f.set(0, b + 3, 0, "chest[facing=south]");
        f.set(0, b + 2, -2, "polished_blackstone_brick_stairs[facing=south,half=bottom,shape=straight]");
        litter(f, -4, -4, 4, 4, b + 2, pal, 0.06);
    }
}
