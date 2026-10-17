package net.faultlinesmp.deep;

/**
 * THE KRAKEN'S GRAVE (Deep 1.9.0, in progress): the Kraken's corpse in the far south of the Dark Ocean (GX, GZ), a dungeon
 * that opens once the server has killed enough Krakens.
 */
final class KrakenGrave {
    static final int GX = 250, GZ = 1650, R = 60;

    final FaultlineDeep pl;

    KrakenGrave(FaultlineDeep pl) { this.pl = pl; }

    /** Is the grave open (enough Krakens killed)? */
    boolean open() { return false; }
}
