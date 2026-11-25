package net.faultlinesmp.world;

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

/**
 * Generates real chunks with WorldGen (in MockBukkit) and draws top views + a cross-section:
 * WorldPreview <outDir> <name> <x> <z> <radiusBlocks> [sectionAxis x|z]
 */
public class WorldPreview {
    static final int MINY = -64, MAXY = 320;

    static Material[] chunk(WorldGen g, WorldInfo wi, int cx, int cz) {
        Material[] a = new Material[16 * 16 * (MAXY - MINY)];
        Arrays.fill(a, Material.AIR);
        ChunkData cd = (ChunkData) Proxy.newProxyInstance(WorldPreview.class.getClassLoader(), new Class<?>[]{ChunkData.class}, (o, m, args) -> {
            switch (m.getName()) {
                case "setBlock": {
                    int x = (int) args[0], y = (int) args[1], z = (int) args[2];
                    if (y < MINY || y >= MAXY || x < 0 || x > 15 || z < 0 || z > 15) return null;
                    Material mat = args[3] instanceof Material mm ? mm : ((BlockData) args[3]).getMaterial();
                    a[((y - MINY) * 16 + z) * 16 + x] = mat; return null;
                }
                case "setRegion": {
                    int x0 = (int) args[0], y0 = (int) args[1], z0 = (int) args[2], x1 = (int) args[3], y1 = (int) args[4], z1 = (int) args[5];
                    Material mat = args[6] instanceof Material mm ? mm : ((BlockData) args[6]).getMaterial();
                    for (int x = Math.max(0, x0); x < Math.min(16, x1); x++) for (int z = Math.max(0, z0); z < Math.min(16, z1); z++)
                        for (int y = Math.max(MINY, y0); y < Math.min(MAXY, y1); y++) a[((y - MINY) * 16 + z) * 16 + x] = mat;
                    return null;
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
        g.generateSurface(wi, new Random(1), cx, cz, cd);
        return a;
    }

    static Color col(Material m) {
        String n = m.name();
        if (m == Material.WATER) return new Color(40, 80, 190);
        if (m.isAir()) return null;
        if (n.contains("LEAVES")) return n.contains("PALE") ? new Color(190, 195, 180) : n.contains("AZALEA") ? new Color(110, 140, 90) : n.contains("MANGROVE") ? new Color(70, 120, 40) : new Color(40, 90, 40);
        if (n.contains("NYLIUM") || n.contains("CRIMSON_ROOTS")) return new Color(170, 20, 40);
        if (n.contains("GRASS") || n.contains("FERN")) return new Color(90, 150, 60);
        if (n.contains("LOG") || n.contains("WOOD") || n.contains("STEM")) return n.contains("BIRCH") || n.contains("MUSHROOM") ? new Color(220, 215, 200) : new Color(80, 55, 35);
        if (n.contains("SAND")) return new Color(225, 205, 140);
        if (n.contains("SNOW") || n.contains("ICE")) return new Color(220, 235, 250);
        if (n.contains("BEDROCK") || n.contains("COMMAND") || n.contains("JIGSAW") || n.contains("STRUCTURE")) return new Color(200, 60, 200);
        if (n.contains("TERRACOTTA")) return n.contains("YELLOW") ? new Color(200, 170, 40) : n.contains("GREEN") || n.contains("LIME") ? new Color(110, 140, 40) : new Color(160, 90, 60);
        if (n.contains("MUD")) return new Color(70, 60, 55);
        if (n.contains("MOSS")) return new Color(80, 120, 50);
        if (n.contains("DIAMOND")) return Color.CYAN;
        if (n.contains("ORE")) return new Color(200, 150, 100);
        if (n.contains("COBWEB")) return Color.WHITE;
        if (n.contains("FROGLIGHT") || n.contains("LANTERN") || n.contains("GLOW")) return new Color(250, 240, 160);
        if (n.contains("BLACKSTONE") || n.contains("BASALT") || n.contains("DEEPSLATE")) return new Color(45, 45, 50);
        if (n.contains("PODZOL") || n.contains("COARSE") || n.contains("DIRT")) return new Color(110, 80, 50);
        if (n.contains("BRICK") || n.contains("STONE") || n.contains("TUFF") || n.contains("ANDESITE")) return new Color(130, 130, 130);
        return new Color(160, 150, 140);
    }

    public static void main(String[] a) throws Exception {
        org.mockbukkit.mockbukkit.MockBukkit.mock(new org.mockbukkit.mockbukkit.ServerMock() {
            @Override public BlockData createBlockData(String data) {
                try { return super.createBlockData(data); }
                catch (Throwable e) { Material m = Material.matchMaterial(data.contains("[") ? data.substring(0, data.indexOf('[')) : data); return super.createBlockData(m == null ? Material.STONE : m); }
            }
        });
        Sites.load(java.util.logging.Logger.getLogger("prev"));
        WorldGen g = new WorldGen();
        WorldInfo wi = (WorldInfo) Proxy.newProxyInstance(WorldPreview.class.getClassLoader(), new Class<?>[]{WorldInfo.class}, (o, m, args) -> switch (m.getName()) {
            case "getSeed" -> 12345L; case "getMinHeight" -> MINY; case "getMaxHeight" -> MAXY; case "getName" -> "world"; case "hashCode" -> 1; default -> null;
        });
        String out = a[0], name = a[1];
        int cxw = Integer.parseInt(a[2]), czw = Integer.parseInt(a[3]), R = Integer.parseInt(a[4]);
        String axis = a.length > 5 ? a[5] : "x";
        int c0 = (cxw - R) >> 4, c1 = (cxw + R) >> 4, d0 = (czw - R) >> 4, d1 = (czw + R) >> 4;
        int W = (c1 - c0 + 1) * 16, H = (d1 - d0 + 1) * 16;
        BufferedImage top = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        BufferedImage sec = new BufferedImage(axis.equals("x") ? W : H, MAXY - MINY, BufferedImage.TYPE_INT_RGB);
        long t0 = System.nanoTime(); int n = 0;
        Map<Material, Integer> counts = new HashMap<>();
        for (int cx = c0; cx <= c1; cx++) for (int cz = d0; cz <= d1; cz++) {
            Material[] ch = chunk(g, wi, cx, cz); n++;
            for (Material m : ch) counts.merge(m, 1, Integer::sum);
            for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) {
                int px = (cx - c0) * 16 + x, pz = (cz - d0) * 16 + z;
                for (int y = MAXY - 1; y >= MINY; y--) {
                    Color c = col(ch[((y - MINY) * 16 + z) * 16 + x]);
                    if (c == null) continue;
                    double s = 0.55 + 0.45 * Math.max(0, Math.min(1, (y + 64) / 384.0 * 1.6 - 0.3));
                    top.setRGB(px, pz, new Color((int) (c.getRed() * s), (int) (c.getGreen() * s), (int) (c.getBlue() * s)).getRGB());
                    break;
                }
                boolean onLine = axis.equals("x") ? cz * 16 + z == czw : cx * 16 + x == cxw;
                if (onLine) for (int y = MINY; y < MAXY; y++) {
                    Color c = col(ch[((y - MINY) * 16 + z) * 16 + x]);
                    int sx = axis.equals("x") ? px : pz;
                    sec.setRGB(sx, MAXY - 1 - y, c == null ? (y < 200 && y > -60 ? 0x101018 : 0x8fb8ff) : c.getRGB());
                }
            }
        }
        System.out.printf("%s: %d chunks, %.1f ms/chunk%n", name, n, (System.nanoTime() - t0) / 1e6 / n);
        ImageIO.write(top, "png", new File(out, name + "_top.png"));
        ImageIO.write(sec, "png", new File(out, name + "_section.png"));
        if (a.length > 6) counts.entrySet().stream().sorted((x, y) -> y.getValue() - x.getValue()).limit(25).forEach(e -> System.out.println("  " + e.getKey() + " " + e.getValue()));
    }
}
