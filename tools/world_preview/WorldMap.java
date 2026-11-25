package net.faultlinesmp.world;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.HashMap;
import java.util.Map;

/** Renders the whole Faultline overworld from Terrain's pure functions: tools/world_preview/WorldMap <out.png> [blocksPerPixel]. */
public class WorldMap {
    static final Map<B, Color> C = new HashMap<>();
    static {
        C.put(B.GREAT_SEA, new Color(20, 40, 110)); C.put(B.OCEAN, new Color(40, 80, 170)); C.put(B.COLD_OCEAN, new Color(40, 70, 150));
        C.put(B.WARM_OCEAN, new Color(40, 140, 190)); C.put(B.LUKEWARM_OCEAN, new Color(40, 110, 180)); C.put(B.FROZEN_OCEAN, new Color(120, 150, 200));
        C.put(B.BEACH, new Color(230, 220, 160)); C.put(B.SNOWY_BEACH, new Color(235, 235, 220)); C.put(B.STONY_SHORE, new Color(140, 140, 140));
        C.put(B.RIVER, new Color(60, 110, 220)); C.put(B.FROZEN_RIVER, new Color(160, 190, 240));
        C.put(B.PLAINS, new Color(130, 180, 80)); C.put(B.SUNFLOWER_PLAINS, new Color(170, 190, 70)); C.put(B.FOREST, new Color(50, 120, 40));
        C.put(B.BIRCH_FOREST, new Color(90, 150, 70)); C.put(B.FLOWER_FOREST, new Color(110, 160, 60)); C.put(B.MEADOW, new Color(150, 200, 110));
        C.put(B.TAIGA, new Color(60, 100, 70)); C.put(B.OLD_GROWTH_SPRUCE, new Color(50, 80, 60)); C.put(B.WINDSWEPT_HILLS, new Color(110, 130, 110));
        C.put(B.JAGGED_PEAKS, new Color(220, 220, 230)); C.put(B.STONY_PEAKS, new Color(150, 150, 150)); C.put(B.CHERRY_GROVE, new Color(240, 170, 200));
        C.put(B.SWAMP, new Color(70, 90, 50));
        C.put(B.DESERT, new Color(220, 200, 120)); C.put(B.SAVANNA, new Color(180, 170, 80)); C.put(B.SAVANNA_PLATEAU, new Color(160, 150, 70));
        C.put(B.BADLANDS, new Color(200, 100, 50)); C.put(B.ERODED_BADLANDS, new Color(210, 80, 40)); C.put(B.WOODED_BADLANDS, new Color(160, 110, 60));
        C.put(B.JUNGLE, new Color(30, 150, 30)); C.put(B.BAMBOO_JUNGLE, new Color(80, 170, 40)); C.put(B.SPARSE_JUNGLE, new Color(70, 150, 60));
        C.put(B.MANGROVE_SWAMP, new Color(60, 110, 70));
        C.put(B.SNOWY_PLAINS, new Color(235, 240, 245)); C.put(B.SNOWY_TAIGA, new Color(190, 210, 210)); C.put(B.ICE_SPIKES, new Color(170, 210, 240));
        C.put(B.GROVE, new Color(200, 220, 210)); C.put(B.SNOWY_SLOPES, new Color(225, 230, 240)); C.put(B.FROZEN_PEAKS, new Color(200, 220, 250));
        C.put(B.WASTELAND, new Color(150, 170, 30)); C.put(B.DEEP_DESERT, new Color(250, 220, 90)); C.put(B.DEEP_FOREST, new Color(10, 70, 30));
        C.put(B.INFERNO_FOREST, new Color(170, 40, 20)); C.put(B.DARK_FOREST, new Color(20, 50, 40)); C.put(B.WARTSFIELD, new Color(210, 30, 50));
        C.put(B.SPIDER, new Color(90, 80, 90)); C.put(B.FARLANDS, new Color(120, 90, 60)); C.put(B.SKY_ISLES, new Color(250, 250, 255));
    }

    public static void main(String[] a) throws Exception {
        int bpp = a.length > 1 ? Integer.parseInt(a[1]) : 10;
        Terrain t = new Terrain(12345L);
        int R = Layout.BORDER, n = 2 * R / bpp;
        BufferedImage img = new BufferedImage(n, n, BufferedImage.TYPE_INT_RGB);
        int abyss = 0, land = 0;
        long t0 = System.nanoTime();
        for (int py = 0; py < n; py++) for (int px = 0; px < n; px++) {
            int x = -R + px * bpp, z = -R + py * bpp;
            Terrain.Col c = t.column(x, z);
            Color col = C.getOrDefault(c.biome, Color.MAGENTA);
            double shade = c.sea ? 0.55 + 0.45 * Math.max(0, Math.min(1, (c.surface + 60) / 260.0)) : 0.75 + 0.25 * Math.min(1, (c.surface - 200) / 110.0);
            if (c.abyssTop != Integer.MIN_VALUE) { abyss++; shade *= 0.8; }
            if (c.hole) col = Color.BLACK;
            if (!c.sea) land++;
            img.setRGB(px, py, new Color((int) Math.min(255, col.getRed() * shade), (int) Math.min(255, col.getGreen() * shade), (int) Math.min(255, col.getBlue() * shade)).getRGB());
        }
        Graphics2D g = img.createGraphics();
        g.setColor(Color.RED);
        for (Layout.Site s : Layout.SITES) { int px = (s.x() + R) / bpp, py = (s.z() + R) / bpp; g.drawOval(px - 4, py - 4, 8, 8); g.drawString(s.file().substring(s.file().indexOf('/') + 1), px + 5, py); }
        g.setColor(Color.ORANGE);
        for (int[] p : Layout.PYRAMIDS) { int px = (p[0] + R) / bpp, py = (p[1] + R) / bpp; g.fillPolygon(new int[]{px - 5, px + 5, px}, new int[]{py + 4, py + 4, py - 6}, 3); }
        g.setColor(Color.WHITE); int sx = (Layout.SPAWN_X + R) / bpp, sy = (Layout.SPAWN_Z + R) / bpp; g.fillRect(sx - 3, sy - 3, 6, 6); g.drawString("SPAWN", sx + 5, sy + 12);
        ImageIO.write(img, "png", new File(a[0]));
        System.out.printf("land %.1f%%, abyss %.1f%%, %.1f us/column%n", 100.0 * land / (n * n), 100.0 * abyss / (n * n), (System.nanoTime() - t0) / 1000.0 / (n * n));
        // heights sample
        for (int[] p : new int[][]{{Layout.SPAWN_X, Layout.SPAWN_Z}, {-800, -1650}, {-760, -1800}, {1480, 1450}, {0, 250}, {3500, 0}}) {
            Terrain.Col c = t.column(p[0], p[1]);
            System.out.println(p[0] + "," + p[1] + " -> y " + c.surface + " " + c.biome + (c.sea ? " sea" : "") + (c.abyssTop != Integer.MIN_VALUE ? " abyss " + c.abyssBottom + ".." + c.abyssTop : ""));
        }
        int max = 0; for (int x = -1200; x < -300; x += 4) for (int z = -2200; z < -1200; z += 4) max = Math.max(max, t.column(x, z).surface);
        System.out.println("tallest in the west range: " + max);
    }
}
