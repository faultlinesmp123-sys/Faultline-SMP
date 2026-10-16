package net.faultlinesmp.deep;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.ArrayList;
import java.util.List;

/**
 * The Black Market's lift: press the button in the car and everyone in it rides down to the Pit (or back up). The ride
 * takes 2.5 s: the doors clang, chains rattle, the lights go out (Darkness), then you're there.
 */
final class Elevator implements Listener {
    static final int RIDE_TICKS = 50;
    final FaultlineDeep pl;
    boolean moving;
    int readyTick;

    Elevator(FaultlineDeep pl) { this.pl = pl; }

    static boolean at(Block b, int[] c) { return b.getX() == c[0] && b.getY() == c[1] && b.getZ() == c[2]; }

    @EventHandler
    public void onPress(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK || e.getHand() != EquipmentSlot.HAND) return;
        Block b = e.getClickedBlock();
        if (b == null || pl.world == null || !b.getWorld().equals(pl.world)) return;
        if (at(b, Market.UP_BUTTON)) ride(true, e.getPlayer());
        else if (at(b, Market.DOWN_BUTTON)) ride(false, e.getPlayer());
    }

    /** Who is standing in the car at that floor. */
    List<Player> riders(int floor) {
        List<Player> out = new ArrayList<>();
        for (Player p : pl.world.getPlayers()) {
            Location l = p.getLocation();
            if (l.getX() >= Market.CAR_X0 && l.getX() < Market.CAR_X1 + 1 && l.getZ() >= Market.CAR_Z0 && l.getZ() < Market.CAR_Z1 + 1
                    && l.getY() >= floor + 0.5 && l.getY() < floor + 4) out.add(p);
        }
        return out;
    }

    void ride(boolean down, Player who) {
        if (moving || Bukkit.getCurrentTick() < readyTick) { who.sendActionBar(Component.text("The lift is still moving...", NamedTextColor.GRAY)); return; }
        int from = down ? Market.UP_FLOOR : Market.DOWN_FLOOR, to = down ? Market.DOWN_FLOOR : Market.UP_FLOOR;
        List<Player> riders = riders(from);
        if (riders.isEmpty()) return;
        moving = true;
        Location car = new Location(pl.world, Market.CAR_X0 + 1.5, from + 2, Market.CAR_Z0 + 1.5);
        pl.world.playSound(car, Sound.BLOCK_IRON_DOOR_CLOSE, SoundCategory.BLOCKS, 1.2f, 0.6f);
        pl.world.playSound(car, Sound.BLOCK_CHAIN_PLACE, SoundCategory.BLOCKS, 1.2f, 0.5f);
        for (Player p : riders) {
            p.addPotionEffect(new PotionEffect(PotionEffectType.DARKNESS, RIDE_TICKS + 30, 0, true, false, false));
            p.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, RIDE_TICKS, 4, true, false, false));
            p.sendActionBar(Component.text(down ? "The lift groans and drops into the dark..." : "The chains haul you back up...", NamedTextColor.DARK_PURPLE));
        }
        for (int t = 10; t < RIDE_TICKS; t += 12) {
            int tt = t;
            Bukkit.getScheduler().runTaskLater(pl, () -> {
                for (Player p : riders) if (p.isOnline()) p.playSound(p.getLocation(), tt % 24 == 10 ? Sound.BLOCK_CHAIN_STEP : Sound.ENTITY_MINECART_RIDING, SoundCategory.BLOCKS, 0.6f, 0.5f);
            }, t);
        }
        Bukkit.getScheduler().runTaskLater(pl, () -> {
            try {
                for (Player p : riders) {
                    if (!p.isOnline() || !p.getWorld().equals(pl.world)) continue;
                    Location l = p.getLocation().add(0, to - from, 0);
                    p.teleport(l);
                    p.playSound(l, Sound.BLOCK_NOTE_BLOCK_BELL, SoundCategory.BLOCKS, 1f, down ? 0.6f : 1.2f);
                    p.sendActionBar(Component.text(down ? "THE PIT" : "The Black Market", down ? NamedTextColor.RED : NamedTextColor.DARK_PURPLE));
                }
                pl.world.spawnParticle(Particle.SMOKE, new Location(pl.world, Market.CAR_X0 + 1.5, to + 1.2, Market.CAR_Z0 + 1.5), 20, 0.8, 0.3, 0.8, 0.01);
            } finally {
                moving = false;
                readyTick = Bukkit.getCurrentTick() + 20;
            }
        }, RIDE_TICKS);
    }
}
