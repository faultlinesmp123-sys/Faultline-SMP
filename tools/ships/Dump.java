package net.faultlinesmp.ships;

/** Prints the three ship layouts as JSON (tools/ship_layouts.sh puts it in tools/ships/layouts.json for ship_assets.py). */
public class Dump {
    public static void main(String[] a) {
        StringBuilder sb = new StringBuilder("{\n");
        ShipType[] all = ShipType.values();
        for (int t = 0; t < all.length; t++) {
            ShipType type = all[t];
            sb.append(" \"").append(type.name().toLowerCase()).append("\": {\"title\": \"").append(type.title).append("\", \"cells\": [\n");
            for (int i = 0; i < type.cells.size(); i++) {
                ShipType.Cell c = type.cells.get(i);
                sb.append("  [").append(c.x()).append(", ").append(c.y()).append(", ").append(c.z()).append(", \"").append(c.need()).append("\", \"").append(c.props()).append("\"]")
                        .append(i < type.cells.size() - 1 ? ",\n" : "\n");
            }
            sb.append(" ], \"seats\": [");
            for (int i = 0; i < type.seats.length; i++) sb.append(i > 0 ? ", " : "").append("[").append(type.seats[i][0]).append(", ").append(type.seats[i][1]).append(", ").append(type.seats[i][2]).append("]");
            sb.append("]}").append(t < all.length - 1 ? ",\n" : "\n");
        }
        System.out.print(sb.append("}\n"));
    }
}
