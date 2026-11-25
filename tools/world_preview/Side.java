package net.faultlinesmp.world;
import org.bukkit.Material;
import org.bukkit.generator.WorldInfo;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.lang.reflect.Proxy;
import java.util.*;
/** Side view (looking north) of a strip of world: Side <seed> <x> <z> <halfWidth> <depth> <yMin> <yMax> <out.png> [scale] */
public class Side {
    public static void main(String[] a) throws Exception {
        long seed = Long.parseLong(a[0]); int cx = Integer.parseInt(a[1]), cz = Integer.parseInt(a[2]), hw = Integer.parseInt(a[3]), depth = Integer.parseInt(a[4]), y0 = Integer.parseInt(a[5]), y1 = Integer.parseInt(a[6]);
        int sc = a.length > 8 ? Integer.parseInt(a[8]) : 5;
        org.mockbukkit.mockbukkit.MockBukkit.mock(new org.mockbukkit.mockbukkit.ServerMock() {
            @Override public org.bukkit.block.data.BlockData createBlockData(String data) {
                try { return super.createBlockData(data); }
                catch (Throwable e) { Material m = Material.matchMaterial(data.contains("[") ? data.substring(0, data.indexOf('[')) : data); return super.createBlockData(m == null ? Material.STONE : m); }
            } });
        WorldInfo wi = (WorldInfo) Proxy.newProxyInstance(Side.class.getClassLoader(), new Class<?>[]{WorldInfo.class}, (o, m, g) -> switch (m.getName()) {
            case "getSeed" -> seed; case "getMinHeight" -> -64; case "getMaxHeight" -> 528; case "getName" -> "world"; case "hashCode" -> 1; default -> null; });
        Sites.load(java.util.logging.Logger.getLogger("s"));
        Scan.g = new WorldGen(); Scan.wi = wi;
        for (int x = (cx - hw) >> 4; x <= (cx + hw) >> 4; x++) for (int z = (cz - depth) >> 4; z <= (cz + depth) >> 4; z++) Scan.chunks.put(((long) x << 32) ^ (z & 0xffffffffL), WorldPreview.chunk(Scan.g, wi, x, z));
        int W = hw * 2 + 1, H = y1 - y0 + 1;
        BufferedImage im = new BufferedImage(W * sc, H * sc, BufferedImage.TYPE_INT_RGB);
        for (int px = 0; px < W; px++) for (int py = 0; py < H; py++) {
            int x = cx - hw + px, y = y1 - py; Color c = new Color(150, 160, 175);
            for (int d = 0; d <= depth * 2; d++) { int z = cz + depth - d; Material m = Scan.at(x, y, z); if (m == null || m.isAir()) continue;
                Color b = col(m); float f = 1f - 0.55f * d / (depth * 2f); c = new Color((int) (b.getRed() * f), (int) (b.getGreen() * f), (int) (b.getBlue() * f)); break; }
            for (int i = 0; i < sc; i++) for (int j = 0; j < sc; j++) im.setRGB(px * sc + i, py * sc + j, c.getRGB());
        }
        javax.imageio.ImageIO.write(im, "png", new java.io.File(a[7]));
        Runtime.getRuntime().halt(0);
    }
    static Color col(Material m) {
        String n = m.name();
        if (m == Material.COBWEB) return new Color(245, 245, 250);
        if (n.contains("WOOL") || n.contains("WHITE_CARPET")) return new Color(225, 225, 215);
        if (n.contains("PALE_OAK_L") && n.contains("LEAVES")) return new Color(150, 160, 150);
        if (n.contains("PALE_OAK")) return new Color(200, 190, 185);
        if (n.contains("PALE_MOSS") || n.contains("HANGING_MOSS")) return new Color(120, 130, 120);
        if (n.contains("SKULL") || n.contains("BONE")) return new Color(235, 230, 200);
        if (m == Material.SPAWNER) return new Color(60, 90, 160);
        if (m == Material.CHEST) return new Color(190, 130, 40);
        Color c = WorldPreview.col(m); return c == null ? new Color(110, 110, 110) : c;
    }
}
