package net.faultlinesmp.deep;

import org.bukkit.Material;
import org.bukkit.block.data.BlockData;
import org.bukkit.generator.ChunkGenerator.ChunkData;
import org.bukkit.generator.WorldInfo;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.File;
import java.lang.reflect.Proxy;
import java.util.*;

public class ReachPreview {
    static final int MINY = -64, MAXY = 320;
    static final long SEED = 1234567L;

    static Material[] chunk(DeepGenerator g, WorldInfo wi, int cx, int cz) {
        Material[] a = new Material[16 * 16 * (MAXY - MINY)];
        Arrays.fill(a, Material.AIR);
        ChunkData cd = (ChunkData) Proxy.newProxyInstance(ReachPreview.class.getClassLoader(), new Class<?>[]{ChunkData.class}, (o, m, args) -> {
            switch (m.getName()) {
                case "setBlock": {
                    int x = (int) args[0], y = (int) args[1], z = (int) args[2];
                    if (y < MINY || y >= MAXY || x < 0 || x > 15 || z < 0 || z > 15) return null;
                    Material mat = args[3] instanceof Material mm ? mm : ((BlockData) args[3]).getMaterial();
                    a[((y - MINY) * 16 + z) * 16 + x] = mat; return null;
                }
                case "getType": {
                    int x = (int) args[0], y = (int) args[1], z = (int) args[2];
                    if (y < MINY || y >= MAXY || x < 0 || x > 15 || z < 0 || z > 15) return Material.AIR;
                    return a[((y - MINY) * 16 + z) * 16 + x];
                }
                case "getMinHeight": return MINY;
                case "getMaxHeight": return MAXY;
                case "hashCode": return 1;
                default: return null;
            }
        });
        g.generateNoise(wi, new Random(1), cx, cz, cd);
        return a;
    }

    static Color col(Material m) {
        String n = m.name();
        if (m == Material.WATER || n.contains("BUBBLE_COLUMN")) return null;
        if (m.isAir()) return null;
        if (n.contains("KELP") || n.contains("SEAGRASS")) return new Color(60, 140, 50);
        if (n.contains("SEA_LANTERN") || n.contains("FROGLIGHT") || n.contains("SHROOMLIGHT")) return new Color(240, 250, 210);
        if (n.contains("CYAN_STAINED") || n.contains("LIGHT_BLUE_STAINED")) return new Color(90, 200, 230);
        if (n.contains("PURPLE_STAINED") || n.contains("MAGENTA_STAINED")) return new Color(170, 70, 200);
        if (n.contains("DARK_OAK") || n.contains("MANGROVE")) return new Color(60, 40, 30);
        if (n.contains("SAND") && !n.contains("RED")) return new Color(220, 205, 150);
        if (n.contains("RED_SAND") || n.contains("RED_TERRACOTTA") || n.contains("FIRE_CORAL")) return new Color(170, 60, 40);
        if (n.contains("NYLIUM")) return new Color(150, 30, 40);
        if (n.contains("BROWN_TERRACOTTA") || n.contains("GRANITE") || n.contains("DRIPSTONE")) return new Color(130, 80, 60);
        if (n.contains("PURPLE") || n.contains("MAGENTA") || n.contains("AMETHYST") || n.contains("PURPUR")) return new Color(150, 110, 190);
        if (n.contains("BLUE_TERRACOTTA") || n.contains("PRISMARINE") || n.contains("TUBE")) return new Color(60, 110, 170);
        if (n.contains("MOSS")) return new Color(80, 120, 50);
        if (n.contains("MUSHROOM_STEM")) return new Color(225, 220, 200);
        if (n.contains("YELLOW") || n.contains("LIME")) return new Color(190, 190, 50);
        if (n.contains("GRAVEL") || n.contains("STONE") || n.contains("ANDESITE") || n.contains("TUFF")) return new Color(120, 120, 120);
        if (n.contains("DEEPSLATE") || n.contains("BLACK") || n.contains("BASALT")) return new Color(45, 45, 50);
        if (n.contains("MUD") || n.contains("CLAY") || n.contains("DIRT")) return new Color(100, 85, 70);
        return new Color(150, 140, 130);
    }

    public static void main(String[] a) throws Exception {
        org.mockbukkit.mockbukkit.MockBukkit.mock(new org.mockbukkit.mockbukkit.ServerMock() {
            @Override public BlockData createBlockData(String data) {
                try { return super.createBlockData(data); }
                catch (RuntimeException e) { return super.createBlockData(Material.matchMaterial(data.contains("[") ? data.substring(0, data.indexOf('[')) : data)); }
            }
        });
        DeepGenerator g = new DeepGenerator();
        WorldInfo wi = (WorldInfo) Proxy.newProxyInstance(ReachPreview.class.getClassLoader(), new Class<?>[]{WorldInfo.class}, (o, m, args) -> switch (m.getName()) {
            case "getSeed" -> SEED; case "getMinHeight" -> MINY; case "getMaxHeight" -> MAXY; case "getName" -> "faultline_deep"; default -> null;
        });
        String out = a[0];
        // 1) the whole map, 1 px = 10 blocks, chart colours
        int N = 600;
        BufferedImage map = new BufferedImage(N, N, BufferedImage.TYPE_INT_RGB);
        Map<Reaches.Kind, Integer> count = new EnumMap<>(Reaches.Kind.class);
        for (int i = 0; i < N; i++) for (int j = 0; j < N; j++) {
            int x = -3000 + i * 10, z = -3000 + j * 10;
            map.setRGB(i, j, AbyssMap.colour(g, SEED, x, z, 3000).getRGB());
            count.merge(Reaches.at(SEED, x, z), 1, Integer::sum);
        }
        ImageIO.write(map, "png", new File(out, "reach_map.png"));
        System.out.println("kinds: " + count);
        // 2) a top-down render + a side slice of each kind: find a site of that kind
        long t0 = System.nanoTime(); int chunks = 0;
        for (Reaches.Kind k0 : java.util.stream.Stream.concat(java.util.Arrays.stream(Reaches.Kind.values()), java.util.stream.Stream.of((Reaches.Kind) null)).toList()) {
            Reaches.Kind k = k0;
            int[] at = null;
            outer:
            for (int r = 0; r < 12 && at == null; r++) for (int gx = -r; gx <= r; gx++) for (int gz = -r; gz <= r; gz++) {
                if (k == null || Reaches.kindOf(SEED, gx, gz) != k) continue;
                int[] s = Reaches.site(SEED, gx, gz);
                if (Reaches.at(SEED, s[0], s[1]) != k || Sulphur.near(s[0], s[1], 60) || MermaidDen.near(s[0], s[1], 60) || Regions.nearAny(s[0], s[1], 60) || Trench.near(s[0], s[1], 60)) continue;
                at = s; break outer;
            }
            if (k == Reaches.Kind.SHALLOWS) at = new int[]{0, 0};
            if (k == null) at = new int[]{Sulphur.SX + 60, Sulphur.SZ - 40};
            if (at == null) { System.out.println("no " + k); continue; }
            int W = 8; // 8x8 chunks = 128 blocks
            int c0x = Math.floorDiv(at[0], 16) - W / 2, c0z = Math.floorDiv(at[1], 16) - W / 2;
            BufferedImage top = new BufferedImage(W * 16 * 3, W * 16 * 3, BufferedImage.TYPE_INT_RGB);
            BufferedImage side = new BufferedImage(W * 16 * 2, (MAXY - MINY), BufferedImage.TYPE_INT_RGB);
            for (int cx = 0; cx < W; cx++) for (int cz = 0; cz < W; cz++) {
                Material[] c = chunk(g, wi, c0x + cx, c0z + cz); chunks++;
                for (int lx = 0; lx < 16; lx++) for (int lz = 0; lz < 16; lz++) {
                    // top-down: highest non-water block, shaded by height
                    Color cc = new Color(10, 30, 70); int hy = MINY;
                    for (int y = MAXY - 1; y >= MINY; y--) { Color q = col(c[((y - MINY) * 16 + lz) * 16 + lx]); if (q != null) { cc = q; hy = y; break; } }
                    double sh = 0.45 + 0.55 * Math.max(0, Math.min(1, (hy + 0.0) / 300.0));
                    Color s2 = new Color((int) (cc.getRed() * sh), (int) (cc.getGreen() * sh), (int) (cc.getBlue() * sh));
                    for (int p = 0; p < 3; p++) for (int q2 = 0; q2 < 3; q2++) top.setRGB((cx * 16 + lx) * 3 + p, (cz * 16 + lz) * 3 + q2, s2.getRGB());
                    // side slice along x through the middle row
                    if (cz == W / 2 && lz == 8) for (int y = MINY; y < MAXY; y++) {
                        Color q = col(c[((y - MINY) * 16 + lz) * 16 + lx]);
                        if (q == null) q = y > DeepGenerator.SEA ? new Color(200, 220, 240) : new Color(20, 50, 110);
                        for (int p = 0; p < 2; p++) side.setRGB((cx * 16 + lx) * 2 + p, MAXY - 1 - y, q.getRGB());
                    }
                }
            }
            ImageIO.write(top, "png", new File(out, "reach_" + (k == null ? "sulphur" : k.name().toLowerCase()) + "_top.png"));
            ImageIO.write(side, "png", new File(out, "reach_" + (k == null ? "sulphur" : k.name().toLowerCase()) + "_side.png"));
        }
        // the sulphur mushroom forest
        System.out.printf("%d chunks, %.1f ms/chunk%n", chunks, (System.nanoTime() - t0) / 1e6 / chunks);
        System.exit(0);
    }
}
