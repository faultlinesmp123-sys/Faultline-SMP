package net.faultlinesmp.world;
public class Leak {
    public static void main(String[] a) {
        long seed = Long.parseLong(a[0]);
        Terrain t = new Terrain(seed);
        int total = 0;
        java.util.List<int[]> boxes = new java.util.ArrayList<>();
        for (Layout.Site s : Layout.SITES) { if (s.r() > 0) { int r = (int) (s.r() * 2.1) + 4; boxes.add(new int[]{s.x() - r, s.z() - r, s.x() + r, s.z() + r, 0}); } }
        boxes.add(new int[]{Layout.CANYON_X - Layout.CANYON_HX - 3, Layout.CANYON_Z - Layout.CANYON_HZ - 3, Layout.CANYON_X + Layout.CANYON_HX + 3, Layout.CANYON_Z + Layout.CANYON_HZ + 3, 1});
        int bi = 0;
        for (int[] b : boxes) {
            int w = b[2] - b[0] + 1, h = b[3] - b[1] + 1; int leaks = 0, dryCols = 0, minDry = 999;
            Terrain.Col[][] g = new Terrain.Col[w][h];
            for (int i = 0; i < w; i++) for (int j = 0; j < h; j++) g[i][j] = t.column(b[0] + i, b[1] + j);
            int ex = 0, ez = 0;
            for (int i = 1; i < w - 1; i++) for (int j = 1; j < h - 1; j++) {
                Terrain.Col c = g[i][j];
                boolean airBelowSea = c.surface < Layout.SEA - 1 && !(c.sea || c.river) || c.dry;
                if (c.dry) { dryCols++; minDry = Math.min(minDry, c.surface); }
                if (!airBelowSea) continue;
                for (int[] d : new int[][]{{1,0},{-1,0},{0,1},{0,-1}}) {
                    Terrain.Col n = g[i + d[0]][j + d[1]];
                    if ((n.sea || n.river) && !n.dry) { leaks++; ex = b[0] + i; ez = b[1] + j; }
                }
            }
            System.out.println("box " + bi++ + " (" + b[0] + "," + b[1] + ") dry=" + dryCols + " minY=" + minDry + " LEAKS=" + leaks + (leaks > 0 ? " e.g. " + ex + "," + ez : ""));
            total += leaks;
        }
        System.out.println("TOTAL " + total);
    }
}
