package net.faultlinesmp.world;
public class Fixed {
    public static void main(String[] a) {
        for (String sd : a) {
            Terrain t = new Terrain(Long.parseLong(sd)); StringBuilder bad = new StringBuilder();
            Terrain.Col sp = t.column(Layout.SPAWN_X, Layout.SPAWN_Z);
            if (sp.sea || sp.surface != Layout.SPAWN_Y) bad.append(" spawn:" + sp.surface + (sp.sea ? "SEA" : ""));
            for (int i = 0; i < Layout.PYRAMIDS.length; i++) { int mn = 999, mx = -999; boolean sea = false;
                for (int dx = -70; dx <= 70; dx += 10) for (int dz = -70; dz <= 70; dz += 10) { Terrain.Col c = t.column(Layout.PYRAMIDS[i][0] + dx, Layout.PYRAMIDS[i][1] + dz); mn = Math.min(mn, c.surface); mx = Math.max(mx, c.surface); sea |= c.sea; }
                Terrain.Col c = t.column(Layout.PYRAMIDS[i][0], Layout.PYRAMIDS[i][1]);
                if (sea || mx - mn > 40 || c.biome != B.DEEP_DESERT) bad.append(" pyr" + i + ":" + mn + ".." + mx + (sea ? "SEA" : "") + c.biome); }
            for (Layout.Site s : Layout.SITES) { Terrain.Col c = t.column(s.x(), s.z());
                switch (s.shape()) {
                    case FLAT, BASIN -> { if (c.sea || Math.abs(c.surface - (s.y() - 1)) > 1) bad.append(" " + s.file() + ":" + c.surface + (c.sea ? "SEA" : "")); }
                    case SEABED -> { if (!c.sea) bad.append(" " + s.file() + ":notsea"); }
                    case CAVERN, CANYON, CRATER -> { if (c.sea) bad.append(" " + s.file() + ":SEA"); if (s.shape() == Layout.Shape.CAVERN && c.surface < s.y() + 60) bad.append(" " + s.file() + ":roof" + c.surface); }
                    case SKY -> { if (c.surface > s.y() - 20) bad.append(" " + s.file() + ":ground" + c.surface); }
                } }
            for (int[] h : Layout.HOLES) { Terrain.Col c = t.column(h[0], h[1]); if (!c.sea || !c.hole) bad.append(" hole" + h[0] + ":" + (c.sea ? "" : "LAND") + (c.hole ? "" : "nohole")); }
            for (Layout.Core k : Layout.CORES) { Terrain.Col c = t.column(k.x(), k.z()); if (k.y() == 0 && c.sea) bad.append(" core" + k.x() + ":SEA"); }
            System.out.println(sd + (bad.length() == 0 ? " ok" : bad));
        }
    }
}
