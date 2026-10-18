package net.faultlinesmp.deep;

import org.bukkit.Bukkit;
import org.bukkit.Difficulty;
import org.bukkit.ChatColor;
import org.bukkit.Color;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.Block;
import org.bukkit.entity.AreaEffectCloud;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.Zombie;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityTargetEvent;
import org.bukkit.event.entity.EntityTransformEvent;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/**
 * THE DARK OCEAN'S CREATURES: 20 kinds, by zone (the Sunlit Sea, the crust's caves, the abyss) and temper (passive,
 * neutral: only fights back, aggressive). Inspired by Subnautica / Below Zero, the Calamity mod's seas and Sea of Solitude,
 * with names and looks of our own.
 *
 * Each one is a vanilla mob that does the moving and the hitting (a fish, a squid, a drowned, a spider, a frog...), made
 * invisible, wearing its model: an ItemDisplay riding it (paper with item model faultline:deep/<id>, tools/deep_assets.py)
 * for Java, and an armor stand only Bedrock players see (the same item on its head, drawn by FaultlineDeep.mcpack) that
 * follows it; hitting the stand hits the creature. Everything is non-persistent: unloaded = gone, the spawner refills.
 * Kept in `live` (never a world scan); ticked every 2 ticks.
 */
final class DeepMobs implements Listener {

    static final String TAG = "faultline_deep_mob", PART = "faultline_deep_part", STAND = "faultline_deep_stand";

    enum Temper { PASSIVE, NEUTRAL, AGGRESSIVE }

    /** One kind of creature. damage 0 = no melee. */
    record Kind(String id, String name, ChatColor color, Temper temper, DeepGenerator.Zone zone, EntityType base,
                double scale, double health, double damage, double speed, int weight, float modelScale, String about) {}

    static final Map<String, Kind> KINDS = new LinkedHashMap<>();
    private static void k(String id, String name, ChatColor c, Temper t, DeepGenerator.Zone z, EntityType base, double scale,
                          double hp, double dmg, double speed, int weight, float ms, String about) {
        KINDS.put(id, new Kind(id, name, c, t, z, base, scale, hp, dmg, speed, weight, ms, about));
    }

    static {
        var S = DeepGenerator.Zone.SUNLIT; var C = DeepGenerator.Zone.CAVES; var A = DeepGenerator.Zone.ABYSS;
        var P = Temper.PASSIVE; var N = Temper.NEUTRAL; var G = Temper.AGGRESSIVE;
        // ---- the Sunlit Sea
        k("gawker", "Gawker", ChatColor.YELLOW, P, S, EntityType.COD, 1.0, 4, 0, 0, 30, 0.9f, "A round little fish that's mostly eye. It stares, then darts off in a school.");
        k("bloatfin", "Bloatfin", ChatColor.AQUA, P, S, EntityType.SALMON, 1.0, 6, 0, 0, 20, 1.0f, "A fish like a bubble with fins. Pop one and its air sac bursts: everyone close gets their air back and breathes easy for 15 seconds.");
        k("jellywing", "Jellywing", ChatColor.LIGHT_PURPLE, P, S, EntityType.SQUID, 1.3, 14, 0, 0, 10, 1.6f, "A glowing ray as clear as jelly, gliding over the reefs. Sirens comb their hair with its spines.");
        k("driftback", "Driftback", ChatColor.GREEN, P, S, EntityType.DOLPHIN, 3.0, 160, 0, 0, 1, 5.0f, "A gentle giant with a whole reef growing on its back. It hums as it drifts by.");
        k("clam", "Clam", ChatColor.WHITE, N, S, EntityType.SHULKER, 1.0, 20, 5, 0, 12, 1.0f, "Sits shut on the sea floor. Hit it and it snaps back. Some hold a pearl, and a very few a Shield of the Ocean.");
        k("lumenbell", "Lumen Bell", ChatColor.BLUE, N, S, EntityType.SQUID, 1.1, 10, 2, 0, 10, 1.3f, "A ghostly bell of a jellyfish, pulsing with light. Touching its trailing threads stings; hurting it sets off a shock.");
        k("gasbloom", "Gasbloom", ChatColor.DARK_GREEN, N, S, EntityType.SQUID, 1.5, 24, 0, 0, 6, 1.8f, "A slow, swollen floater. Crowd it or hit it and it lets out pods of poison gas.");
        k("fangstalker", "Fangstalker", ChatColor.GRAY, N, S, EntityType.DROWNED, 1.15, 30, 6, 0.26, 7, 1.6f, "A lean shark with a jaw full of hooked teeth. It leaves you alone... unless you hit it, or swim by holding metal it wants.");
        k("blastfin", "Blastfin", ChatColor.GOLD, G, S, EntityType.DROWNED, 0.6, 6, 0, 0.3, 8, 0.9f, "A spiky little fish full of volatile gas. It swims straight at you and bursts.");
        k("burrowshark", "Burrow Shark", ChatColor.DARK_RED, G, S, EntityType.DROWNED, 1.2, 30, 7, 0.27, 5, 1.7f, "Lies buried in the sand with only its fin showing. Swim too close and it erupts.");
        // ---- the crust's caves
        k("cavecrawler", "Cavecrawler", ChatColor.RED, G, C, EntityType.SPIDER, 0.55, 10, 3, 0.3, 30, 1.0f, "A spindly crab that scuttles over cave walls and leaps at anything that moves.");
        k("glowgrub", "Glowgrub", ChatColor.YELLOW, P, C, EntityType.FROG, 0.6, 4, 0, 0, 25, 0.8f, "A soft grub that glows. Stay near one and its light shows you the way.");
        k("shellsnake", "Shellsnake", ChatColor.DARK_AQUA, G, C, EntityType.DROWNED, 1.4, 40, 8, 0.25, 12, 2.0f, "Coils inside cracks in the rock, waiting. Then it strikes, all teeth and armour plates.");
        // ---- the abyss
        k("lampsquid", "Lampsquid", ChatColor.AQUA, N, A, EntityType.SQUID, 1.7, 40, 0, 0, 10, 2.0f, "A squid with lanterns for eyes. Anger it and it sends out a pulse that kills every light: your night vision, your conduit, everything.");
        k("volteel", "Volt Eel", ChatColor.YELLOW, G, A, EntityType.DROWNED, 1.3, 36, 5, 0.25, 12, 2.0f, "A long eel crackling with power. Get close and it discharges through the water.");
        k("needlejaw", "Needlejaw", ChatColor.DARK_PURPLE, G, A, EntityType.DROWNED, 0.9, 20, 6, 0.32, 18, 1.4f, "A black fish with a lure of light and teeth like needles. Fast.");
        k("devilfish", "Masked Devilfish", ChatColor.DARK_RED, G, A, EntityType.DROWNED, 1.15, 50, 8, 0.26, 8, 1.7f, "Wears a mask of bone that turns most hits aside. Crack the mask first.");
        k("riftwarper", "Rift Warper", ChatColor.LIGHT_PURPLE, G, A, EntityType.DROWNED, 1.2, 40, 6, 0.27, 5, 1.8f, "It blinks through the water, and its bolts throw you somewhere else.");
        k("hollowkin", "Hollow Kin", ChatColor.DARK_GRAY, N, A, EntityType.DROWNED, 0.95, 18, 4, 0.24, 14, 1.4f, "Shadows of children, drifting in the dark. They shy away from light. Hurt one and they all come for you.");
        k("sharkquid", "Sharkquid", ChatColor.BLUE, G, A, EntityType.DROWNED, 1.3, 34, 7, 0.3, 12, 1.9f, "Half shark, half squid, all hunger. It charges, and inks you when it's hurt.");
        // ---- the Sulphur Sea (1.4.0): acid creatures, in its water, on its islands and in the air over them
        var U = DeepGenerator.Zone.SULPHUR;
        k("acideel", "Acid Eel", ChatColor.GREEN, G, U, EntityType.DROWNED, 1.2, 28, 5, 0.27, 14, 2.0f, "A slick olive eel with a glowing stripe. Its bite burns, and it leaves a trail of acid in the water.");
        k("trasher", "Trasher", ChatColor.DARK_GREEN, G, U, EntityType.DROWNED, 1.4, 60, 9, 0.24, 6, 2.4f, "A crocodile of the acid shallows. It lunges out of the water at anyone on the shore or in a boat.");
        k("radiator", "Radiator", ChatColor.GREEN, P, U, EntityType.COD, 1.0, 6, 0, 0, 18, 1.0f, "A little fish glowing green from the inside. Harmless... except that swimming close to one burns.");
        k("flakcrab", "Flak Crab", ChatColor.GOLD, G, U, EntityType.SPIDER, 0.7, 40, 5, 0.25, 8, 1.6f, "A crab in a rusted shell bristling with tubes. It lobs acid flak, and hits to its front shell barely scratch it.");
        k("nucleartoad", "Nuclear Toad", ChatColor.GREEN, N, U, EntityType.FROG, 1.0, 12, 0, 0, 12, 1.2f, "A bloated toad with glowing warts. Hurt it and it blows up in a burst of acid a second later.");
        k("gammaslime", "Gamma Slime", ChatColor.GREEN, G, U, EntityType.SLIME, 1.0, 24, 4, 0, 9, 1.4f, "A cube of glowing jelly round a dark, irradiated core. It leaves puddles of poison where it bounces.");
        k("sulphurskater", "Sulphur Skater", ChatColor.YELLOW, N, U, EntityType.FROG, 1.0, 10, 3, 0, 10, 1.2f, "A long-legged strider that skates the acid surface. Bother it and it bites, fast.");
        k("acidjelly", "Acid Jelly", ChatColor.GREEN, N, U, EntityType.SQUID, 1.2, 14, 3, 0, 10, 1.4f, "A green bell trailing stinging threads. Hurt it and it bursts a cloud of acid.");
        k("orthocera", "Orthocera", ChatColor.GOLD, N, U, EntityType.SQUID, 1.4, 30, 0, 0, 7, 2.0f, "A cephalopod in a long cone shell. Anger it and it jets away spraying blinding acid ink.");
        k("trilobite", "Trilobite", ChatColor.GRAY, P, U, EntityType.SILVERFISH, 1.3, 14, 0, 0, 12, 1.0f, "An armoured bug of the sea floor. Strike it and it curls into a ball that hardly takes damage.");
        k("skyfin", "Skyfin", ChatColor.DARK_GREEN, G, U, EntityType.PHANTOM, 0.7, 16, 4, 0, 8, 1.4f, "A flying fish with wing-fins. It leaps from the sea and dives on anyone standing above the water.");
        k("leech", "Sulphur Leech", ChatColor.DARK_RED, G, U, EntityType.DROWNED, 0.6, 12, 2, 0.3, 12, 1.0f, "A ringed leech with a mouth of hooks. It latches on and drinks until you knock it off.");
        k("boglurker", "Bog Lurker", ChatColor.DARK_GREEN, G, U, EntityType.ZOMBIE, 1.2, 50, 8, 0.24, 5, 2.0f, "It looks like a dead acidwood stump, until the stump opens its eyes. Its grip roots you in place.");
        k("corrosionshark", "Corrosion Shark", ChatColor.DARK_GREEN, G, U, EntityType.DROWNED, 1.5, 70, 10, 0.3, 4, 2.6f, "A huge shark with bile skin. Its teeth eat through armour.");
        k("urchin", "Sulphur Urchin", ChatColor.YELLOW, N, U, EntityType.SHULKER, 0.8, 18, 4, 0, 10, 1.0f, "A spiny ball on the sea floor. Touch it and it stings; hurt it and it fires its spines everywhere.");
        k("crabling", "Crabling", ChatColor.RED, G, U, EntityType.SPIDER, 0.35, 6, 2, 0.32, 10, 0.7f, "A crab no bigger than your fist, the colour of rust. They never come alone.");
        k("bloatgut", "Bloatgut", ChatColor.YELLOW, N, U, EntityType.PUFFERFISH, 1.3, 10, 0, 0, 10, 1.2f, "A pufferfish swollen with acid gas. Get close and it lets it out.");
        k("sulphurmoth", "Sulphur Moth", ChatColor.YELLOW, P, U, EntityType.BAT, 1.0, 4, 0, 0, 12, 1.0f, "A fat, furry moth with glowing sulphur wings, drawn to the fumaroles.");
        k("hazardturtle", "Hazard Turtle", ChatColor.GOLD, N, U, EntityType.TURTLE, 1.3, 60, 6, 0.2, 5, 1.8f, "A turtle grown spikes of sulphur crystal. Hitting it hurts you back; anger it and it rams you.");
        k("sludgesnail", "Sludge Snail", ChatColor.GREEN, P, U, EntityType.FROG, 0.8, 20, 0, 0, 10, 1.2f, "A slow snail in a rusted shell, leaving a trail of acid slime. Arrows bounce off it.");
        // ---- the ACID RAIN (1.5.0): only during the event (AcidRain.java), never from the normal spawner (weight 0)
        k("shambler", "Sludge Shambler", ChatColor.GREEN, G, U, EntityType.DROWNED, 1.1, 40, 6, 0.24, 0, 2.1f, "A drowned sailor soaked through with acid, dripping and falling apart. It hurls globs of sludge. Only seen in the Acid Rain.");
        k("rustbones", "Rustbones", ChatColor.GOLD, G, U, EntityType.SKELETON, 1.0, 30, 4, 0.25, 0, 1.9f, "A skeleton in corroded armour, risen from the wrecks when the acid rain falls. Its arrows are dipped in sulphur.");
        k("acidmite", "Acid Mite", ChatColor.GREEN, G, U, EntityType.ENDERMITE, 1.5, 8, 3, 0.32, 0, 0.9f, "A glowing tick the size of a cat. They pour out of the islands in the Acid Rain, in packs.");
        k("stormwraith", "Storm Wraith", ChatColor.DARK_GREEN, G, U, EntityType.VEX, 1.6, 30, 5, 0, 0, 1.7f, "A shape of green storm cloud with two burning eyes. It drifts through anything and calls down acid on you.");
        k("mauler", "Bile Mauler", ChatColor.DARK_GREEN, G, U, EntityType.DROWNED, 1.9, 110, 12, 0.26, 0, 3.6f, "A hulking, blind shark-thing that comes up with the Acid Rain. It charges through the water and its bite rots armour.");
        k("goblin", "Grubnash the Acid Goblin", ChatColor.GOLD, G, U, EntityType.VINDICATOR, 1.0, 500, 9, 0.32, 0, 1.9f, "A MINI BOSS (500 health) who rides in on the Acid Rain: a scrap-armoured goblin with a pack of bubbling flasks. He throws acid, lobs scrap bombs, leaps on you and calls his mites. Below half health he goes into a frenzy.");
    }

    /** 1.6.0: ten more for the Sunlit Sea and ten for the Abyss. */
    static {
        var S = DeepGenerator.Zone.SUNLIT; var A = DeepGenerator.Zone.ABYSS;
        var P = Temper.PASSIVE; var N = Temper.NEUTRAL; var G = Temper.AGGRESSIVE;
        // ---- the Sunlit Sea
        k("sailfin", "Sailfin Marlin", ChatColor.BLUE, N, S, EntityType.DROWNED, 1.3, 40, 8, 0.3, 6, 2.2f, "A marlin with a sail for a back and a spear for a nose. Leave it be: hit it and it runs you through.");
        k("mantaglider", "Manta Glider", ChatColor.DARK_GRAY, P, S, EntityType.DOLPHIN, 2.6, 120, 0, 0, 2, 4.2f, "A giant ray gliding through the Sunlit Sea. Swim in its wake and it carries you along.");
        k("leafydragon", "Leafy Seadragon", ChatColor.GOLD, P, S, EntityType.COD, 1.0, 6, 0, 0, 14, 1.3f, "A slender seahorse cousin dressed in leaves of kelp. Startle it and it vanishes in a swirl of leaves.");
        k("pearlhorse", "Pearl Seahorse", ChatColor.LIGHT_PURPLE, P, S, EntityType.COD, 0.9, 8, 0, 0, 14, 1.1f, "A pearly seahorse that carries a pearl against its chest. Very rarely, it's a Heart of the Sea.");
        k("hammerjaw", "Hammerjaw", ChatColor.GRAY, G, S, EntityType.DROWNED, 1.3, 45, 9, 0.28, 4, 2.5f, "A hammerhead shark. It rams you with that flat head and leaves you reeling.");
        k("razorcuda", "Razorcuda", ChatColor.WHITE, G, S, EntityType.DROWNED, 0.9, 16, 5, 0.33, 6, 1.6f, "A barred silver barracuda with an underbite full of fangs. They hunt in threes, and their bites bleed.");
        k("stingray", "Stingray", ChatColor.GOLD, N, S, EntityType.DROWNED, 0.8, 20, 4, 0.22, 8, 1.8f, "Lies half-buried in the sand. Swim right over one and its barb finds you.");
        k("mirrorfin", "Mirrorfin", ChatColor.WHITE, N, S, EntityType.SALMON, 1.0, 10, 0, 0, 12, 1.2f, "A tall, flat fish as bright as a mirror. Hurt it and its flash blinds everyone close.");
        k("kelpstrangler", "Kelp Strangler", ChatColor.DARK_GREEN, G, S, EntityType.DROWNED, 1.2, 35, 5, 0, 5, 2.0f, "A tall stalk of kelp with eyes. It never moves; its fronds wrap round you and pull you in.");
        k("siren", "Sea Siren", ChatColor.DARK_AQUA, G, S, EntityType.DROWNED, 1.0, 34, 6, 0.26, 4, 1.7f, "Half woman, half fish, all hunger. Her song dizzies you and draws you to her. Hurting her breaks the song.");
        // ---- the Abyss
        k("gulper", "Gulper Eel", ChatColor.DARK_PURPLE, G, A, EntityType.DROWNED, 1.2, 40, 9, 0.24, 7, 2.4f, "A whip of a body behind a mouth that can swallow something its own size. Its tail tip glows.");
        k("isopod", "Giant Isopod", ChatColor.GRAY, N, A, EntityType.SILVERFISH, 1.4, 50, 5, 0.22, 8, 1.7f, "A woodlouse the size of a dog, plated in grey armour. Strike it and it rolls up tight.");
        k("ghostshark", "Ghost Shark", ChatColor.WHITE, G, A, EntityType.DROWNED, 1.2, 30, 7, 0.28, 6, 2.0f, "A pale chimaera with glassy eyes and a rat's tail. You only see it up close, and its spine numbs you.");
        k("vampsquid", "Vampire Squid", ChatColor.DARK_RED, N, A, EntityType.SQUID, 1.2, 26, 0, 0, 8, 1.7f, "Wrapped in a cloak of red webbing. Anger it and it flips the cloak inside out and drinks your life.");
        k("dumbo", "Dumbo Octopus", ChatColor.LIGHT_PURPLE, P, A, EntityType.SQUID, 0.9, 10, 0, 0, 10, 1.3f, "A soft, glowing octopus that flaps its ear fins to swim. Stay close: its light keeps the dark away.");
        k("swallower", "Black Swallower", ChatColor.DARK_GRAY, G, A, EntityType.DROWNED, 0.8, 18, 4, 0.3, 8, 1.4f, "A little black fish with a stomach that stretches round anything. Its bite steals your food.");
        k("chainlight", "Chainlight", ChatColor.AQUA, N, A, EntityType.SQUID, 1.4, 30, 0, 0, 6, 2.8f, "A colony grown into one long chain of glowing bells. Its trailing threads sting; hurt it and it shocks.");
        k("viperfang", "Viperfang", ChatColor.BLUE, G, A, EntityType.DROWNED, 1.0, 22, 7, 0.32, 8, 1.6f, "A viperfish: fangs too long for its mouth and rows of lights down its belly. Its bites fester.");
        k("hagfish", "Slime Hag", ChatColor.LIGHT_PURPLE, N, A, EntityType.DROWNED, 0.9, 24, 3, 0.22, 8, 1.5f, "A pink-grey eel with barbels round a sucking mouth. Grab it and it smothers you in slime.");
        k("watcher", "Abyss Watcher", ChatColor.RED, G, A, EntityType.SQUID, 1.5, 60, 0, 0, 3, 2.6f, "A great eye drifting in the dark on a crown of tendrils. When it looks at you, you feel it.");
    }

    /** 1.7.0: the Mermaid Den's people (Den.java keeps them; the normal spawner never does: weight 0). */
    static {
        var S = DeepGenerator.Zone.SUNLIT; var N = Temper.NEUTRAL;
        k("mermaid", "Mermaid", ChatColor.LIGHT_PURPLE, N, S, EntityType.DROWNED, 1.0, 40, 5, 0.27, 0, 1.7f, "One of the ten mermaids of the Mermaid Den. She sings to herself and leaves you be. Hurt one and the whole Den turns on you; kill all ten and Coralia rises to avenge them.");
        k("merguard", "Mermaid Guard", ChatColor.DARK_AQUA, N, S, EntityType.DROWNED, 1.15, 70, 8, 0.28, 0, 1.9f, "A merman in prismarine armour who keeps the Den's gates and its palace. He throws his trident, and his net slows you. Leave the mermaids alone and so will he.");
    }

    /** 1.8.0: the Trench's own creatures (zone TRENCH), and the Great Whale of the Leviathan Migration (Migration.java only). */
    static {
        var T = DeepGenerator.Zone.TRENCH; var S = DeepGenerator.Zone.SUNLIT;
        var P = Temper.PASSIVE; var N = Temper.NEUTRAL; var G = Temper.AGGRESSIVE;
        k("hadalsnail", "Hadal Snailfish", ChatColor.LIGHT_PURPLE, P, T, EntityType.COD, 1.0, 8, 0, 0, 20, 1.2f, "A pale pink tadpole of a fish, soft as jelly. It lives where nothing else can: the very bottom of the Trench.");
        k("anglerking", "Anglerking", ChatColor.DARK_AQUA, G, T, EntityType.DROWNED, 1.5, 70, 10, 0.24, 8, 2.6f, "A great black anglerfish. In the Trench its lure is the only light, and it knows you'll swim toward it.");
        k("bonecrawler", "Bone Crawler", ChatColor.WHITE, G, T, EntityType.SPIDER, 0.7, 34, 6, 0.28, 12, 1.4f, "A crab that wears a skull for a shell and walks on legs of bone. Its claws pin you where you stand.");
        k("palestalker", "Pale Stalker", ChatColor.WHITE, G, T, EntityType.DROWNED, 1.2, 46, 9, 0.3, 9, 2.2f, "A long, eyeless, milk-white eel. You never see it until it's already coming, and its bite leaves you blind.");
        k("crushjelly", "Crush Jelly", ChatColor.DARK_PURPLE, N, T, EntityType.SQUID, 1.4, 40, 0, 0, 8, 2.0f, "A heavy violet bell ringed with light. Its pulse is the weight of the whole ocean: it slams you down and squeezes.");
        k("tubeworm", "Tubeworm Colony", ChatColor.RED, N, T, EntityType.DROWNED, 1.0, 30, 4, 0, 10, 1.6f, "White tubes on a hot vent, each with a blood-red plume. Get close and they sting; hurt them and the vent scalds.");
        k("greatwhale", "Great Whale", ChatColor.BLUE, P, S, EntityType.DOLPHIN, 3.5, 400, 0, 0, 0, 9.0f, "One of the great whales of the Leviathan Migration, crossing the Sunlit Sea in a pod. Gentle giants. Hunting them brings something from below.");
    }

    /** 1.9.0: the outer ring's creatures (zone SUNLIT; each lives only in its region: REGION), the volcanoes' (weight 0:
     *  Volcano.java spawns them), the Frozen Shelf's mini boss the Rimeback and the Drowned City's people (PLAIN: a vanilla
     *  drowned you see as it is, dressed by OuterMobs, no model) with their king. */
    static {
        var S = DeepGenerator.Zone.SUNLIT;
        var P = Temper.PASSIVE; var N = Temper.NEUTRAL; var G = Temper.AGGRESSIVE;
        // the Bone Fields
        k("marroweel", "Marrow Eel", ChatColor.WHITE, G, S, EntityType.DROWNED, 1.0, 30, 7, 0.3, 10, 2.0f, "A pale eel that nests in the leviathans' bones, its body ribbed like a spine. It bites and won't let go: you bleed until you shake it off.");
        k("scavcrab", "Scavenger Crab", ChatColor.GRAY, N, S, EntityType.SPIDER, 0.7, 26, 5, 0.26, 12, 1.5f, "A grey crab with a shell grown over with bone shards, picking the skeletons clean. Get between it and its meal and it pinches.");
        k("ossuaryray", "Ossuary Ray", ChatColor.WHITE, N, S, EntityType.DOLPHIN, 2.0, 60, 0, 0, 5, 3.6f, "A great ray rotted down to a lattice of bones, still gliding over the Bone Fields. Hurt it and it rattles: a chill that withers.");
        // the Glass Reef
        k("glassjelly", "Glass Jelly", ChatColor.AQUA, N, S, EntityType.SQUID, 1.2, 20, 0, 0, 12, 1.8f, "A bell of living crystal that rings as it pulses. Its threads sting, and when it dies it shatters into flying glass.");
        k("prismfish", "Prism Fish", ChatColor.LIGHT_PURPLE, P, S, EntityType.COD, 0.8, 6, 0, 0, 16, 1.0f, "A little fish of cut crystal. Schools of them throw rainbows across the reef.");
        k("crystalcrab", "Crystal Crab", ChatColor.DARK_PURPLE, N, S, EntityType.SPIDER, 0.8, 40, 6, 0.24, 8, 1.6f, "An amethyst geode that walks. Its shell turns most blows aside: strike it from behind.");
        k("shardback", "Shardback", ChatColor.BLUE, G, S, EntityType.DROWNED, 1.0, 32, 6, 0.3, 7, 1.8f, "A long fish armoured in blades of glass. It flares its crest and fires the spare ones at you.");
        // the Frozen Shelf
        k("narwhal", "Narwhal", ChatColor.GRAY, N, S, EntityType.DOLPHIN, 2.0, 60, 9, 0, 6, 3.0f, "A mottled grey whale with one long spiralled tusk. Gentle, until you hurt it: then it charges tusk first.");
        k("iceseal", "Ice Seal", ChatColor.WHITE, P, S, EntityType.DOLPHIN, 1.2, 20, 0, 0, 10, 1.8f, "A fat silver seal with big black eyes. It plays round the breathing holes, and where the seals are there's air.");
        k("frostorca", "Frost Orca", ChatColor.DARK_GRAY, G, S, EntityType.DROWNED, 1.6, 70, 9, 0.32, 5, 3.6f, "A black-and-white hunter of the Frozen Shelf. They hunt in pairs, and their bite freezes you stiff.");
        k("rimejelly", "Rime Jelly", ChatColor.AQUA, N, S, EntityType.SQUID, 1.2, 22, 0, 0, 9, 1.8f, "A frosted bell trailing icy threads. Its sting freezes you where you swim.");
        k("rimeback", "The Rimeback", ChatColor.AQUA, G, S, EntityType.DROWNED, 2.6, 1200, 14, 0.3, 0, 7.5f, "A MINI BOSS (1200 health): a vast orca sheathed in ice, lord of the Frozen Shelf. It sleeps in its Hollow under the ice. Frost Breath, Ice Ram, Glacier Fall, the Pod, and when it's hurt it freezes the water round it.");
        // the undersea volcanoes
        k("magmaeel", "Magma Eel", ChatColor.GOLD, G, S, EntityType.DROWNED, 1.0, 34, 7, 0.3, 0, 2.0f, "A black eel cracked with glowing seams, born in a volcano's vents. Its bite sears.");
        k("cindercrab", "Cinder Crab", ChatColor.RED, N, S, EntityType.SPIDER, 0.8, 30, 5, 0.24, 0, 1.6f, "A crab with a shell of cooling lava. Hurt it and it bursts into a shower of embers.");
        // the Drowned City (PLAIN: a vanilla drowned, dressed)
        k("drowncitizen", "Drowned Citizen", ChatColor.DARK_AQUA, G, S, EntityType.DROWNED, 1.0, 26, 5, 0.24, 12, 1.0f, "One of the Drowned City's people, still walking its streets in the rags of their old clothes.");
        k("drownguard", "Drowned Guard", ChatColor.DARK_GREEN, G, S, EntityType.DROWNED, 1.05, 44, 7, 0.26, 6, 1.0f, "A guard of the Drowned City in rusted mail, still on patrol. Guards carry tridents, and the keys to the vaults.");
        k("drownpriest", "Drowned Priest", ChatColor.DARK_PURPLE, G, S, EntityType.DROWNED, 1.0, 30, 4, 0.22, 3, 1.0f, "A priest of the drowned cathedral. It mends the dead around it and curses the living: slowing, lifting, weakening.");
        k("drownking", "The Drowned King", ChatColor.GOLD, G, S, EntityType.DROWNED, 2.2, 900, 12, 0.28, 0, 1.0f, "A MINI BOSS (900 health): the Drowned City's king, still on his throne in the palace. Trident Volley, Tidal Slam, the Royal Guard, the King's Decree and the Whirl. He carries three Drowned Vault Keys.");
    }

    /** Which outer region each of those lives in (the spawner keeps them there, and keeps the open sea's out). */
    static final Map<String, String> REGION = Map.ofEntries(
            Map.entry("marroweel", "bones"), Map.entry("scavcrab", "bones"), Map.entry("ossuaryray", "bones"),
            Map.entry("glassjelly", "glass"), Map.entry("prismfish", "glass"), Map.entry("crystalcrab", "glass"), Map.entry("shardback", "glass"),
            Map.entry("narwhal", "frozen"), Map.entry("iceseal", "frozen"), Map.entry("frostorca", "frozen"), Map.entry("rimejelly", "frozen"), Map.entry("rimeback", "frozen"),
            Map.entry("magmaeel", "volcano"), Map.entry("cindercrab", "volcano"),
            Map.entry("drowncitizen", "city"), Map.entry("drownguard", "city"), Map.entry("drownpriest", "city"), Map.entry("drownking", "city"));

    /** Seen as their own vanilla body (no model, no Bedrock stand helmet). */
    static final Set<String> PLAIN = Set.of("drowncitizen", "drownguard", "drownpriest", "drownking");

    /** The Trench's creatures (the Titan's Maw is theirs too). */
    static final Set<String> TRENCH = Set.of("hadalsnail", "anglerking", "bonecrawler", "palestalker", "crushjelly", "tubeworm");

    /** The Acid Rain's own creatures (AcidRain.java spawns them; the normal spawner never does). */
    static final Set<String> RAIN = Set.of("shambler", "rustbones", "acidmite", "stormwraith", "mauler", "goblin");

    /** Where in the Sulphur Sea a kind lives: on the islands (LAND), in the air over them (AIR), on the water's surface (SURFACE). Everything else swims. */
    static final Set<String> LAND = Set.of("flakcrab", "nucleartoad", "gammaslime", "boglurker", "crabling", "hazardturtle", "sludgesnail", "rustbones", "acidmite", "goblin");
    static final Set<String> AIR = Set.of("skyfin", "sulphurmoth", "stormwraith");
    static final Set<String> FLOOR = Set.of("scavcrab", "crystalcrab", "cindercrab", "clam", "burrowshark", "cavecrawler", "glowgrub", "shellsnake", "urchin", "trilobite", "stingray", "kelpstrangler", "isopod", "bonecrawler", "tubeworm");
    static final String ACID_TAG = "faultline_deep_acid";
    /** Creatures that give off their own light: their models are drawn fully lit, so they shine in the Abyss's dark. */
    static final Set<String> GLOW = new java.util.HashSet<>(Set.of("jellywing", "lumenbell", "glowgrub", "lampsquid", "volteel", "needlejaw",
            "riftwarper", "radiator", "gammaslime", "acidjelly", "sulphurmoth", "acidmite", "dumbo", "chainlight", "watcher", "viperfang", "gulper", "vampsquid", "crushjelly", "anglerking",
            "glassjelly", "prismfish", "shardback", "rimejelly", "magmaeel", "cindercrab")); // (1.9.0: the Glass Reef's light, the volcanoes' embers)
    /** Pale, half-seen ones: drawn a little lit (4), so they're faint shapes in the Abyss's dark instead of nothing. */
    static final Set<String> DIM = Set.of("ghostshark", "isopod", "hagfish", "swallower", "hadalsnail", "palestalker", "bonecrawler", "tubeworm");

    final FaultlineDeep pl;
    final Random random = new Random();
    final NamespacedKey eggKey;
    final Map<UUID, Beast> live = new HashMap<>();
    OuterMobs outer;
    /** The guardians the spawner put in the Abyss (capped near each player; vanilla doesn't count them). */
    final Set<UUID> guardians = new HashSet<>();
    private int ticks;
    private boolean spawning;

    /** A live creature. */
    final class Beast {
        final Kind k;
        final LivingEntity body;
        ItemDisplay model;                                    // the body
        final List<ItemDisplay> parts = new ArrayList<>();     // one per moving bone (DeepParts), same order as its bones
        List<DeepParts.Bone> bones = List.of();
        float yaw = Float.NaN, pitch;                          // the smoothed heading the model shows
        int lastPose = -99;
        double clock = Math.random() * 10;                     // its own animation time (runs faster while it swims)
        int clockAt = -1;
        ArmorStand stand;
        UUID angryAt;
        long angryUntil;
        int t, cd, maskHits = 3;
        float sentYaw = 9999, sentPitch = 9999;
        boolean buried, inked, fused;
        int rolled, latched;                                   // trilobite curled up / leech holding on (ticks left)
        Player lookAt;                                          // the Watcher's eye follows them
        int song;                                               // the Siren's song (ticks left)
        boolean mutated;                                        // 1.9.0: a mutated one (OuterMobs.mutate)
        org.bukkit.boss.BossBar bar;                            // the mini bosses' bars (the Rimeback, the Drowned King)
        UUID latchedTo;
        Beast(Kind k, LivingEntity body) { this.k = k; this.body = body; }
        boolean angry() { return angryAt != null && ticks < angryUntil; }
        Player angryPlayer() { Player p = angryAt == null ? null : Bukkit.getPlayer(angryAt); return p != null && p.getWorld().equals(body.getWorld()) ? p : null; }
    }

    DeepMobs(FaultlineDeep pl) {
        this.pl = pl;
        eggKey = new NamespacedKey(pl, "deep_egg");
        outer = new OuterMobs(pl, this);
        Bukkit.getScheduler().runTaskTimer(pl, this::tick, 2L, 2L);
    }

    double cfg(String path, double def) { return pl.getConfig().getDouble("mobs." + path, def); }

    static boolean ours(Entity e) {
        Set<String> t = e.getScoreboardTags();
        return t.contains(TAG) || t.contains(PART) || t.contains(STAND);
    }

    // =========================================================================================== spawning one
    static ItemStack modelItem(String id) {
        ItemStack it = new ItemStack(Material.PAPER);
        ItemMeta m = it.getItemMeta();
        m.setItemModel(new NamespacedKey("faultline", "deep/" + id));
        it.setItemMeta(m);
        return it;
    }

    Beast spawn(Kind k, Location at) {
        World w = at.getWorld();
        Entity e;
        spawning = true; // (our own spawn: the Dark Ocean's spawn filter lets it through, even a phantom)
        try { e = w.spawnEntity(at, k.base(), false); } // by entity type (Paper 26.2 can't spawn some old classes by class)
        finally { spawning = false; }
        if (!(e instanceof LivingEntity body)) { e.remove(); return null; }
        body.addScoreboardTag(TAG);
        body.addScoreboardTag("faultline_deep_" + k.id());
        body.setPersistent(false);
        body.setRemoveWhenFarAway(true);
        body.setInvisible(!PLAIN.contains(k.id()));
        body.setSilent(!PLAIN.contains(k.id()));
        body.setCanPickupItems(false);
        body.setCustomName(k.color() + k.name());
        body.setCustomNameVisible(false);
        if (body instanceof org.bukkit.entity.Slime sl) sl.setSize(2);   // (setSize resets health: first)
        if (body instanceof org.bukkit.entity.Phantom ph) { ph.setSize(0); try { ph.setShouldBurnInDay(false); } catch (Throwable ignored) { } }
        attr(body, Attribute.MAX_HEALTH, k.health());
        body.setHealth(k.health());
        attr(body, Attribute.SCALE, k.scale());
        if (k.damage() > 0) attr(body, Attribute.ATTACK_DAMAGE, k.damage());
        if (k.speed() > 0) attr(body, Attribute.MOVEMENT_SPEED, k.speed());
        if (body.getEquipment() != null) {
            body.getEquipment().clear();
            for (EquipmentSlot s : EquipmentSlot.values()) try { body.getEquipment().setDropChance(s, 0); } catch (RuntimeException ignored) { }
        }
        if (body instanceof Zombie z) { z.setAdult(); try { z.setShouldBurnInDay(false); } catch (Throwable ignored) { } }
        if ((k.id().equals("clam") || k.id().equals("urchin")) && body instanceof Mob m2) m2.setAI(false);
        if (body instanceof org.bukkit.entity.AbstractSkeleton sk) {
            try { sk.setShouldBurnInDay(false); } catch (Throwable ignored) { }
            if (body.getEquipment() != null) body.getEquipment().setItemInMainHand(new ItemStack(Material.BOW)); // Rustbones' bow (it shows: a rusted thing in its hand)
        }
        if (body instanceof org.bukkit.entity.Raider rd) { rd.setCanJoinRaid(false); rd.setPatrolLeader(false); }
        if (body instanceof Zombie z2 && k.id().equals("shambler")) try { z2.setShouldBurnInDay(false); } catch (Throwable ignored) { }
        Beast mob = new Beast(k, body);
        // 1.7.2: the displays are spawned with NO rotation of their own. A display keeps the yaw/pitch it was spawned with
        // (riding doesn't change it) and draws its transformation on top of that, so a creature from an egg or /deep spawn
        // (your look direction) was tilted by your pitch for its whole life
        Location flat = at.clone(); flat.setYaw(0); flat.setPitch(0);
        boolean plain = PLAIN.contains(k.id());
        mob.model = w.spawn(flat, ItemDisplay.class, d -> {
            d.setItemStack(plain ? new ItemStack(Material.AIR) : modelItem(k.id()));
            d.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
            d.setPersistent(false);
            d.addScoreboardTag(PART);
            d.addScoreboardTag("faultline_no_bedrock_fx");
            d.setTeleportDuration(2);
            d.setInterpolationDuration(2); // = the update interval (3 overlapped the next pose: tails stuttered)
            d.setViewRange(1.2f);
            d.setShadowRadius(0);
            if (GLOW.contains(k.id())) d.setBrightness(new org.bukkit.entity.Display.Brightness(15, 15));
            else if (DIM.contains(k.id())) d.setBrightness(new org.bukkit.entity.Display.Brightness(4, 4));
            if (k.id().equals("ghostshark")) d.setViewRange(0.16f); // ~10 blocks: you only see it up close
        });
        body.addPassenger(mob.model);
        mob.bones = DeepParts.BONES.getOrDefault(k.id(), List.of());
        for (DeepParts.Bone b : mob.bones) {
            ItemDisplay d = w.spawn(flat, ItemDisplay.class, e2 -> {
                e2.setItemStack(modelItem(k.id() + "__" + b.name()));
                e2.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
                e2.setPersistent(false);
                e2.addScoreboardTag(PART);
                e2.addScoreboardTag("faultline_no_bedrock_fx");
                e2.setTeleportDuration(2);
                e2.setInterpolationDuration(2);
                e2.setViewRange(1.2f);
                e2.setShadowRadius(0);
                if (GLOW.contains(k.id())) e2.setBrightness(new org.bukkit.entity.Display.Brightness(15, 15));
                else if (DIM.contains(k.id())) e2.setBrightness(new org.bukkit.entity.Display.Brightness(4, 4));
                if (k.id().equals("ghostshark")) e2.setViewRange(0.16f);
            });
            body.addPassenger(d);
            mob.parts.add(d);
        }
        mob.stand = w.spawn(at, ArmorStand.class, a -> {
            a.setVisibleByDefault(false);
            a.setPersistent(false);
            a.addScoreboardTag(STAND);
            a.setInvisible(true);
            a.setGravity(false);
            a.setSilent(true);
            a.setBasePlate(false);
            a.setMarker(false); // Geyser draws the helmet only on a real armor stand
            if (!plain) a.getEquipment().setHelmet(modelItem(k.id()));
        });
        attr(mob.stand, Attribute.SCALE, Math.max(0.0625, Math.min(16, k.modelScale())));
        for (Player p : w.getPlayers()) if (Edition.bedrock(p)) p.showEntity(pl, mob.stand);
        if (k.id().equals("burrowshark") && body instanceof Mob m3) { m3.setAI(false); mob.buried = true; }
        if (k.id().equals("shellsnake") && body instanceof Mob m4) { m4.setAI(false); mob.buried = true; }
        if (k.id().equals("boglurker") && body instanceof Mob m5) { m5.setAI(false); mob.buried = true; } // a stump, until you come close
        if (k.id().equals("stingray") && body instanceof Mob m6) { m6.setAI(false); mob.buried = true; }  // half-buried in the sand
        if (k.id().equals("kelpstrangler") && body instanceof Mob m7) m7.setAI(false);                    // rooted: it never moves
        if (k.id().equals("tubeworm") && body instanceof Mob m8) m8.setAI(false);                         // rooted on its vent
        live.put(body.getUniqueId(), mob);
        if (outer != null) outer.spawned(mob); // 1.9.0: the Drowned City's clothes, mutations
        pose(mob, true);
        return mob;
    }

    /** The body's height (a test server can't measure every mob: then about a block). */
    static double height(Beast m) {
        try { return m.body.getHeight(); } catch (RuntimeException e) { return Math.max(0.3, m.k.scale()); }
    }

    static void attr(LivingEntity e, Attribute a, double v) {
        AttributeInstance ai = e.getAttribute(a);
        if (ai != null) ai.setBaseValue(v);
    }

    /** Creatures that stay level (bells, floaters, crawlers, the clam, the ghosts): never pitched up or down. */
    static final Set<String> UPRIGHT = Set.of("lumenbell", "gasbloom", "bloatfin", "clam", "hollowkin", "blastfin", "cavecrawler", "glowgrub",
            "acidjelly", "gammaslime", "nucleartoad", "flakcrab", "crabling", "urchin", "bloatgut", "hazardturtle", "sludgesnail", "boglurker", "sulphurskater", "trilobite",
            "shambler", "rustbones", "acidmite", "stormwraith", "goblin",
            "pearlhorse", "kelpstrangler", "siren", "dumbo", "watcher", "stingray", "isopod", "mermaid", "merguard", "crushjelly", "tubeworm", "bonecrawler");

    /** Ones whose "buried" means standing disguised on the ground, not sunk into the sand. */
    static final Set<String> DISGUISED = Set.of("boglurker");

    /**
     * The model's pose. Heading: where it's really going (its velocity), eased, so a squid's odd body rotation or a
     * jittery yaw never shows (the old model followed the body's own pitch and lay on its side). Then every bone is posed
     * from DeepParts: the same looping animations the Bedrock pack plays, faster while it swims.
     */
    void pose(Beast m, boolean force) {
        if (m.model == null || !m.model.isValid()) return;
        Location l = m.body.getLocation();
        Vector v = m.body.getVelocity();
        double hs = Math.hypot(v.getX(), v.getZ());
        float wantYaw = hs > 0.03 ? (float) Math.toDegrees(Math.atan2(-v.getX(), v.getZ())) : Float.isNaN(m.yaw) ? l.getYaw() : m.yaw;
        // 1.7.2: creatures swim LEVEL. The old pitch came from any vertical drift: a mob idling while it slowly sank or
        // bobbed (no forward speed) pointed its nose 35 degrees down (atan of the drift over ~0). Now it only tips while
        // really swimming forward (0.06+ blocks a tick), at most 20 degrees, and eases back to level otherwise.
        float wantPitch = 0;
        if (!UPRIGHT.contains(m.k.id()) && !m.buried && hs > 0.06)
            wantPitch = (float) Math.max(-20, Math.min(20, Math.toDegrees(Math.atan2(-v.getY(), hs)) * 0.6));
        if (m.k.id().equals("clam")) wantYaw = Float.isNaN(m.yaw) ? l.getYaw() : m.yaw;
        if (m.lookAt != null && m.lookAt.isValid() && m.lookAt.getWorld().equals(l.getWorld())) { // the Watcher turns its eye on you
            Vector d = m.lookAt.getLocation().toVector().subtract(l.toVector());
            if (Math.hypot(d.getX(), d.getZ()) > 0.1) wantYaw = (float) Math.toDegrees(Math.atan2(-d.getX(), d.getZ()));
        }
        if (Float.isNaN(m.yaw)) { m.yaw = wantYaw; m.pitch = wantPitch; }
        m.yaw += angle(wantYaw - m.yaw) * 0.25f;
        m.pitch += (wantPitch - m.pitch) * 0.2f;
        // 1.5.0: tails BEAT FASTER while it swims (its own clock runs up to 1.8x), they don't swing wider. Before, the swing
        // grew to 2.2x (a 60-degree tail bent right off the body) and was sampled too seldom: the "tail glitch"
        int dtT = m.clockAt < 0 ? 2 : Math.max(0, Math.min(20, m.t - m.clockAt));
        m.clockAt = m.t;
        m.clock += dtT / 20.0 * Math.min(1.8, 0.85 + hs * 7);
        // 1.5.2 LOD: every bone's pose is a packet to everyone tracking the creature, so how often depends on the nearest
        // player: under 24 blocks every 2 ticks, under 48 every 4, under 80 every 10, else every 40 (the glide between
        // poses always lasts exactly the gap, so it still moves smoothly, just less finely far away)
        double nd = nearestViewer(l);
        int every = nd < 24 ? 2 : nd < 48 ? 4 : nd < 80 ? 10 : 40;
        if (!force && m.t - m.lastPose < every) return;
        int gap = m.t - m.lastPose;
        m.lastPose = m.t;
        int interp = gap >= 2 && gap <= 20 ? gap : 2; // glide over exactly the time until the next pose
        if (m.model.getLocation().getPitch() != 0 || m.model.getLocation().getYaw() != 0) { // (the frame is the whole rotation)
            m.model.setRotation(0, 0);
            for (ItemDisplay d : m.parts) if (d != null && d.isValid()) d.setRotation(0, 0);
        }
        if (m.model.getInterpolationDuration() != interp) { m.model.setInterpolationDuration(interp); for (ItemDisplay d : m.parts) if (d != null && d.isValid()) d.setInterpolationDuration(interp); }
        double h = height(m);
        float s = m.k.modelScale() * (m.fused ? 1.5f : 1f);
        float down = (float) (-h * 0.5 - (m.buried && !DISGUISED.contains(m.k.id()) ? h * 0.7 : 0));
        double time = m.clock;
        float speed = (float) Math.min(1.25, 1 + hs * 2);                    // a little wider at full speed, never more
        // the creature's frame (+x front, +y up, +z right) in the world, and the half turn item displays draw with
        Quaternionf frame = new Quaternionf().rotationY((float) Math.toRadians(-m.yaw)).rotateX((float) Math.toRadians(m.pitch))
                .rotateY((float) Math.toRadians(-90));
        Quaternionf flip = new Quaternionf().rotationY((float) Math.PI);
        List<DeepParts.Anim> anims = DeepParts.ANIMS.getOrDefault(m.k.id(), List.of());
        Quaternionf bodyRot = new Quaternionf(); Vector3f bodyPos = new Vector3f();
        animate(anims, "body", time, 1f, bodyRot, bodyPos);
        float[] bp = DeepParts.BODY_PIVOT.getOrDefault(m.k.id(), new float[]{0, 0, 0});
        Vector3f pivot = new Vector3f(bp[0], bp[1], bp[2]);
        // the body: turned about its body pivot
        Vector3f rootOff = new Vector3f(pivot).negate();
        bodyRot.transform(rootOff).add(pivot).add(bodyPos);
        place(m.model, frame, rootOff, new Quaternionf(bodyRot), s, down, flip);
        for (int i = 0; i < m.parts.size() && i < m.bones.size(); i++) {
            ItemDisplay d = m.parts.get(i);
            if (d == null || !d.isValid()) continue;
            DeepParts.Bone b = m.bones.get(i);
            Quaternionf r = new Quaternionf(); Vector3f o = new Vector3f();
            boolean jaw = b.name().equals("jaw") || b.name().equals("lid");
            float amp = jaw ? (m.angry() || m.fused ? 1.6f : 1f) : speed;
            animate(anims, b.name(), time, amp, r, o);
            Vector3f at = new Vector3f(b.x(), b.y(), b.z()).add(o).sub(pivot);
            bodyRot.transform(at).add(pivot).add(bodyPos);
            place(d, frame, at, new Quaternionf(bodyRot).mul(r), s, down, flip);
        }
    }

    /** Puts a display's model origin at `local` (creature frame, blocks) turned by `rot` (creature frame). */
    static void place(ItemDisplay d, Quaternionf frame, Vector3f local, Quaternionf rot, float s, float down, Quaternionf flip) {
        Vector3f t = new Vector3f(local).mul(s);
        frame.transform(t);
        t.add(0, down, 0);
        Quaternionf q = new Quaternionf(frame).mul(rot).mul(flip);
        if (!Float.isFinite(t.x + t.y + t.z + q.x + q.y + q.z + q.w)) return;
        d.setInterpolationDelay(0);
        d.setTransformation(new Transformation(t, q, new Vector3f(s, s, s), new Quaternionf()));
    }

    /**
     * Adds up a bone's animations at `time`: Bedrock's axes and units (x = -right, y = up, z = -front; degrees and
     * pixels) turned into the creature's frame. half = 0..1 (a jaw that opens and shuts).
     */
    static void animate(List<DeepParts.Anim> anims, String bone, double time, float amp, Quaternionf rot, Vector3f pos) {
        for (DeepParts.Anim a : anims) {
            if (!a.bone().equals(bone)) continue;
            // a beat faster than 0.8 s can't be drawn from poses 2 ticks apart (4 samples a beat strobed): slowed to that
            double w = Math.sin(2 * Math.PI * (time / Math.max(0.8, a.secs()) + a.phase()));
            if (a.half()) w = (w + 1) / 2;
            float k = (float) w * (a.rot() ? amp : 1f);
            if (a.rot()) rot.rotateZ((float) Math.toRadians(-a.x() * k)).rotateY((float) Math.toRadians(a.y() * k)).rotateX((float) Math.toRadians(-a.z() * k));
            else pos.add(-a.z() * k / 16f, a.y() * k / 16f, -a.x() * k / 16f);
        }
    }

    /** How far the nearest player is (blocks; huge if nobody's in the world). */
    double nearestViewer(Location l) {
        double best = Double.MAX_VALUE;
        for (Player p : l.getWorld().getPlayers()) best = Math.min(best, p.getLocation().distanceSquared(l));
        return Math.sqrt(best);
    }

    /** Anyone close enough to see it move (the animation costs packets: far away it only updates once a second). */
    boolean viewerNear(Location l, double r) {
        double r2 = r * r;
        for (Player p : l.getWorld().getPlayers()) if (p.getLocation().distanceSquared(l) < r2) return true;
        return false;
    }

    static float angle(float d) { return ((d % 360) + 540) % 360 - 180; }

    // =========================================================================================== every 2 ticks
    void tick() {
        ticks += 2;
        if (live.isEmpty()) { if (ticks % 30 == 0) spawner(); return; }
        // (a copy: a creature's turn can spawn more (crabling swarms, splits, frenzies, the Den's guards) or kill one, and
        // changing `live` mid-loop threw ConcurrentModificationException every time, which skipped the rest of the tick
        // and spammed a stack trace into the console 10 times a second: 1.10.1)
        for (Beast m : new ArrayList<>(live.values())) {
            if (live.get(m.body.getUniqueId()) != m) continue;          // removed by an earlier creature's turn
            if (!m.body.isValid() || m.body.isDead()) { cleanup(m); live.remove(m.body.getUniqueId()); continue; }
            try { tickOne(m); } catch (RuntimeException ex) { pl.getLogger().warning("[Deep] " + m.k.id() + ": " + ex); }
        }
        if (ticks % 30 == 0) spawner();
        if (ticks % 100 == 0) farAway();
        if (ticks % 40 == 0) bedrockUpkeep();
    }

    void cleanup(Beast m) {
        if (m.bar != null) { m.bar.removeAll(); m.bar = null; }
        if (m.model != null && m.model.isValid()) m.model.remove();
        for (ItemDisplay d : m.parts) if (d != null && d.isValid()) d.remove();
        m.parts.clear();
        if (m.stand != null && m.stand.isValid()) m.stand.remove();
    }

    void remove(Beast m) { cleanup(m); live.remove(m.body.getUniqueId()); if (m.body.isValid()) m.body.remove(); }

    private void tickOne(Beast m) {
        m.t += 2;
        if (m.cd > 0) m.cd -= 2;
        pose(m, false);
        if (m.stand != null && m.stand.isValid() && (bedrockHere && m.t % 2 == 0 || m.t % 40 == 0)) { // (only Bedrock players see it: with none here it barely moves)
            Location l = m.body.getLocation();
            // the Bedrock model is centred on the stand's feet, so the stand stands in the body's middle
            Location sl = l.clone().add(0, height(m) * 0.5 - (m.buried && !DISGUISED.contains(m.k.id()) ? height(m) * 0.7 : 0), 0);
            sl.setPitch(0);
            m.stand.teleport(sl);
        }
        if (m.rolled > 0) m.rolled -= 2;
        if (m.latched > 0) latchTick(m);
        if (m.k.id().equals("sulphurskater")) skate(m);
        if (m.t % 10 != 0) return;
        Player target = target(m);
        World w = m.body.getWorld();
        Location c = m.body.getLocation().add(0, height(m) / 2, 0);
        if (outer != null && (REGION.containsKey(m.k.id()) || m.mutated)) outer.tick(m, target, w, c);
        switch (m.k.id()) {
            case "jellywing" -> { if (m.t % 20 == 0) w.spawnParticle(Particle.GLOW, c, 3, 0.6, 0.2, 0.6, 0); }
            case "driftback" -> { if (m.t % 40 == 0) { w.spawnParticle(Particle.BUBBLE, c, 12, 2, 1, 2, 0.05); if (random.nextInt(4) == 0) w.playSound(c, Sound.ENTITY_DOLPHIN_AMBIENT_WATER, SoundCategory.NEUTRAL, 2f, 0.4f); } }
            case "clam" -> {
                Player a = m.angryPlayer();
                if (m.angry() && a != null && a.getLocation().distanceSquared(c) < 3.5 * 3.5 && m.cd <= 0 && fair(a)) {
                    m.cd = 30;
                    a.damage(cfg("clam.bite", 5), m.body);
                    w.playSound(c, Sound.ENTITY_EVOKER_FANGS_ATTACK, SoundCategory.HOSTILE, 1f, 1.4f);
                    snap(m);
                }
            }
            case "lumenbell" -> {
                if (m.t % 40 == 0) w.spawnParticle(Particle.GLOW, c, 4, 0.3, 0.6, 0.3, 0);
                for (Player p : near(c, 1.6)) if (m.cd <= 0 && fair(p)) {
                    m.cd = 20; p.damage(cfg("lumenbell.sting", 2), m.body);
                    p.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 40, 0));
                    w.spawnParticle(Particle.ELECTRIC_SPARK, p.getLocation().add(0, 1, 0), 10, 0.3, 0.4, 0.3, 0.05);
                }
                if (m.angry() && m.t % 60 == 0) shock(m, c, 3, cfg("lumenbell.shock", 4));
            }
            case "gasbloom" -> {
                Player p = target != null ? target : nearest(c, 3);
                if (p != null && m.cd <= 0 && (m.angry() || random.nextInt(4) == 0)) { m.cd = 80; gas(m, c); }
            }
            case "fangstalker" -> {
                if (!m.angry() && m.t % 40 == 0) for (Player p : near(c, 10)) if (fair(p) && metal(p.getInventory().getItemInMainHand()) && random.nextDouble() < 0.12) {
                    anger(m, p, 600);
                    p.sendActionBar(ChatColor.GRAY + "A Fangstalker has seen your " + pretty(p.getInventory().getItemInMainHand().getType()) + "...");
                }
            }
            case "blastfin" -> {
                if (target != null && !m.fused && target.getLocation().distanceSquared(c) < 2.4 * 2.4) {
                    m.fused = true; pose(m, true);
                    w.playSound(c, Sound.ENTITY_PUFFER_FISH_BLOW_UP, SoundCategory.HOSTILE, 1.2f, 0.8f);
                    Bukkit.getScheduler().runTaskLater(pl, () -> {
                        if (!m.body.isValid()) return;
                        Location at = m.body.getLocation();
                        remove(m);
                        at.getWorld().createExplosion(at, (float) cfg("blastfin.power", 2.4), false, false);
                    }, 16L);
                }
            }
            case "burrowshark", "shellsnake" -> {
                if (m.buried) {
                    if (m.t % 40 == 0) w.spawnParticle(Particle.BLOCK, c.clone().add(0, -0.6, 0), 4, 0.4, 0.1, 0.4, 0, m.body.getLocation().add(0, -1, 0).getBlock().getBlockData());
                    Player p = nearest(c, m.k.id().equals("burrowshark") ? 9 : 6);
                    if (p != null && fair(p)) erupt(m, p);
                }
            }
            case "cavecrawler" -> {
                if (target != null && m.cd <= 0 && target.getLocation().distanceSquared(c) < 25 && m.body.isOnGround()) {
                    m.cd = 40;
                    Vector v = target.getLocation().toVector().subtract(m.body.getLocation().toVector());
                    Edition.vel(m.body, v.normalize().multiply(0.8).setY(0.45));
                    w.playSound(c, Sound.ENTITY_SPIDER_STEP, SoundCategory.HOSTILE, 1f, 1.6f);
                }
            }
            case "glowgrub" -> {
                if (m.t % 20 == 0) w.spawnParticle(Particle.GLOW, c, 3, 0.3, 0.2, 0.3, 0);
                // its light keeps the Abyss's dark off you (Ocean.dark), and outside it, it's night vision
                for (Player p : near(c, cfg("glowgrub.light-radius", 7))) {
                    if (pl.ocean != null && pl.ocean.darkened.contains(p.getUniqueId())) pl.ocean.glowLit.put(p.getUniqueId(), pl.ocean.ticks + 50L);
                    else p.addPotionEffect(new PotionEffect(PotionEffectType.NIGHT_VISION, 260, 0, true, false, true));
                }
            }
            case "lampsquid" -> {
                if (m.t % 30 == 0) w.spawnParticle(Particle.GLOW, c, 2, 0.4, 0.4, 0.4, 0);
                Player a = m.angryPlayer();
                if (m.angry() && a != null && fair(a) && a.getLocation().distanceSquared(c) < 64) {
                    if (m.cd <= 0) { m.cd = 120; emp(m, c); }
                    else if (m.t % 30 == 0) dash(m, a, 0.7);
                }
            }
            case "volteel" -> {
                if (m.t % 30 == 0) w.spawnParticle(Particle.ELECTRIC_SPARK, c, 4, 0.5, 0.3, 0.5, 0.02);
                if (target != null && m.cd <= 0 && target.getLocation().distanceSquared(c) < 4.5 * 4.5) { m.cd = 60; shock(m, c, 4.5, cfg("volteel.shock", 4)); }
            }
            case "needlejaw" -> {
                if (m.t % 20 == 0) w.spawnParticle(Particle.END_ROD, c.clone().add(m.body.getLocation().getDirection().multiply(0.8)), 1, 0, 0, 0, 0);
                if (target != null && m.cd <= 0 && target.getLocation().distanceSquared(c) < 49) { m.cd = 50; dash(m, target, 0.9); }
            }
            case "riftwarper" -> {
                if (target != null && m.cd <= 0) {
                    m.cd = 80;
                    blink(m, target);
                    if (target.getLocation().distanceSquared(c) < 144 && m.body.hasLineOfSight(target)) warpBolt(m, target);
                }
            }
            case "hollowkin" -> {
                if (m.t % 30 == 0) w.spawnParticle(Particle.SMOKE, c, 3, 0.3, 0.5, 0.3, 0.01);
                Player lit = null;
                for (Player p : near(c, 8)) if (holdsLight(p)) { lit = p; break; }
                if (lit != null && !m.angry()) { // shy away from the light
                    Vector away = c.toVector().subtract(lit.getLocation().toVector());
                    Edition.vel(m.body, away.lengthSquared() < 0.01 ? new Vector(0, 0.2, 0) : away.normalize().multiply(0.35));
                }
            }
            case "sharkquid" -> {
                if (target != null && m.cd <= 0 && target.getLocation().distanceSquared(c) < 100) { m.cd = 60; dash(m, target, 1.0); w.playSound(c, Sound.ENTITY_GUARDIAN_ATTACK, SoundCategory.HOSTILE, 1f, 1.4f); }
                if (!m.inked && m.body.getHealth() < m.k.health() * 0.5) {
                    m.inked = true;
                    w.spawnParticle(Particle.SQUID_INK, c, 60, 1.5, 1.5, 1.5, 0.05);
                    for (Player p : near(c, 4.5)) if (fair(p)) p.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, 60, 0));
                }
            }
            // ---------------------------------------------------------------- the Sulphur Sea
            case "acideel" -> { if (target != null && m.t % 30 == 0 && m.body.getVelocity().lengthSquared() > 0.004) acidCloud(m, m.body.getLocation(), 1.3f, 50); }
            case "trasher" -> {
                if (target != null && m.cd <= 0 && m.body.isInWater() && !target.isInWater() && target.getLocation().distanceSquared(c) < 64) {
                    m.cd = 80; // it lunges out of the water at you
                    Vector v = target.getLocation().toVector().subtract(m.body.getLocation().toVector());
                    Edition.vel(m.body, unit(v).multiply(1.1).setY(0.65));
                    w.playSound(c, Sound.ENTITY_RAVAGER_ROAR, SoundCategory.HOSTILE, 1f, 1.5f);
                    w.spawnParticle(Particle.SPLASH, c, 40, 0.8, 0.3, 0.8, 0.2);
                }
            }
            case "radiator" -> {
                if (m.t % 20 == 0) {
                    w.spawnParticle(Particle.DUST, c, 4, 0.5, 0.4, 0.5, 0, new Particle.DustOptions(Color.fromRGB(170, 255, 80), 1.2f));
                    for (Player p : near(c, cfg("radiator.radius", 2.6))) if (fair(p)) { p.damage(cfg("radiator.burn", 1.5), m.body); p.sendActionBar(ChatColor.GREEN + "The Radiator's glow burns!"); }
                }
            }
            case "flakcrab" -> {
                if (target != null && m.cd <= 0 && target.getLocation().distanceSquared(c) < 14 * 14 && m.body.hasLineOfSight(target)) { m.cd = 70; flak(m, target); }
            }
            case "gammaslime" -> { if (target != null && m.t % 40 == 0) acidCloud(m, m.body.getLocation(), 1.6f, 100); if (m.t % 20 == 0) w.spawnParticle(Particle.GLOW, c, 2, 0.4, 0.4, 0.4, 0); }
            case "sulphurskater", "hazardturtle" -> {
                Player a = m.angryPlayer();
                if (m.angry() && a != null && fair(a)) {
                    double d2 = a.getLocation().distanceSquared(c);
                    if (m.k.id().equals("hazardturtle") && m.cd <= 0 && d2 < 100) { m.cd = 70; dash(m, a, 0.9); w.playSound(c, Sound.ENTITY_TURTLE_HURT, SoundCategory.HOSTILE, 1.2f, 0.5f); }
                    else if (m.k.id().equals("sulphurskater") && m.cd <= 0 && d2 < 64) { m.cd = 30; dash(m, a, 0.8); }
                    if (d2 < 2.2 * 2.2 && m.t % 20 == 0) { a.damage(m.k.damage(), m.body); if (m.k.id().equals("hazardturtle")) Edition.vel(a, unit(a.getLocation().toVector().subtract(c.toVector())).multiply(0.8).setY(0.35)); }
                }
                if (m.k.id().equals("hazardturtle") && m.t % 30 == 0) w.spawnParticle(Particle.DUST, c.clone().add(0, 0.4, 0), 3, 0.4, 0.2, 0.4, 0, new Particle.DustOptions(Color.fromRGB(240, 212, 60), 1.3f));
            }
            case "acidjelly" -> {
                if (m.t % 40 == 0) w.spawnParticle(Particle.GLOW, c, 3, 0.3, 0.5, 0.3, 0);
                for (Player p : near(c, 1.7)) if (m.cd <= 0 && fair(p)) {
                    m.cd = 20; p.damage(cfg("acidjelly.sting", 3), m.body);
                    p.addPotionEffect(new PotionEffect(PotionEffectType.POISON, 60, 0));
                }
            }
            case "orthocera" -> {
                Player a = m.angryPlayer();
                if (m.angry() && a != null && fair(a) && m.cd <= 0 && a.getLocation().distanceSquared(c) < 100) {
                    m.cd = 60; // jets away, spraying acid ink behind it
                    Vector away = c.toVector().subtract(a.getLocation().toVector());
                    Edition.vel(m.body, unit(away).multiply(1.0));
                    w.spawnParticle(Particle.SQUID_INK, c, 50, 1.2, 1.2, 1.2, 0.05);
                    w.spawnParticle(Particle.DUST, c, 30, 1.2, 1.2, 1.2, 0, new Particle.DustOptions(Color.fromRGB(150, 220, 60), 1.6f));
                    for (Player p : near(c, 4.5)) if (fair(p)) { p.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, 60, 0)); p.addPotionEffect(new PotionEffect(PotionEffectType.POISON, 60, 0)); }
                }
            }
            case "boglurker" -> {
                if (m.buried) {
                    if (m.t % 60 == 0) w.spawnParticle(Particle.FALLING_SPORE_BLOSSOM, c.clone().add(0, 0.6, 0), 2, 0.3, 0.2, 0.3, 0);
                    Player p = nearest(c, cfg("boglurker.wake-range", 4.5));
                    if (p != null && fair(p)) erupt(m, p);
                }
            }
            case "urchin" -> {
                for (Player p : near(c, 1.4)) if (m.cd <= 0 && fair(p)) { m.cd = 20; p.damage(cfg("urchin.sting", 3), m.body); p.addPotionEffect(new PotionEffect(PotionEffectType.POISON, 40, 0)); }
            }
            case "bloatgut" -> {
                Player p = target != null ? target : nearest(c, 3.2);
                if (p != null && m.cd <= 0 && fair(p)) { m.cd = 100; acidCloud(m, c, 2.4f, 100); w.playSound(c, Sound.ENTITY_PUFFER_FISH_BLOW_OUT, SoundCategory.HOSTILE, 1.2f, 0.6f); }
            }
            case "sulphurmoth" -> { if (m.t % 10 == 0) w.spawnParticle(Particle.DUST, c, 2, 0.3, 0.2, 0.3, 0, new Particle.DustOptions(Color.fromRGB(250, 230, 120), 1.0f)); }
            case "sludgesnail" -> { if (m.t % 40 == 0 && nearest(c, 16) != null && m.body.isOnGround()) slime(m, m.body.getLocation()); }
            // ---------------------------------------------------------------- the Acid Rain
            case "shambler" -> {
                if (m.t % 20 == 0) w.spawnParticle(Particle.FALLING_DUST, c, 3, 0.3, 0.5, 0.3, 0, Material.LIME_CONCRETE_POWDER.createBlockData());
                if (target != null && m.cd <= 0 && target.getLocation().distanceSquared(c) < 16 * 16 && m.body.hasLineOfSight(target)) { m.cd = 80; glob(m, target); }
            }
            case "acidmite" -> { if (m.t % 20 == 0) w.spawnParticle(Particle.DUST, c, 2, 0.2, 0.1, 0.2, 0, new Particle.DustOptions(Color.fromRGB(170, 255, 80), 1f)); }
            case "stormwraith" -> {
                if (m.t % 10 == 0) w.spawnParticle(Particle.DUST, c, 6, 0.5, 0.5, 0.5, 0, new Particle.DustOptions(Color.fromRGB(90, 130, 70), 1.8f));
                if (target != null && m.cd <= 0 && target.getLocation().distanceSquared(c) < 20 * 20) { m.cd = 60; acidDownpour(m, target); }
            }
            case "mauler" -> {
                if (target != null && m.cd <= 0 && m.body.isInWater() && target.getLocation().distanceSquared(c) < 14 * 14) {
                    m.cd = 70; dash(m, target, 1.15);
                    w.playSound(c, Sound.ENTITY_RAVAGER_ROAR, SoundCategory.HOSTILE, 1.4f, 0.6f);
                    w.spawnParticle(Particle.BUBBLE, c, 40, 1, 1, 1, 0.1);
                }
            }
            case "goblin" -> goblin(m, target);
            // ---------------------------------------------------------------- 1.6.0: the Sunlit Sea
            case "sailfin" -> { // runs you through, spear first
                Player a = m.angryPlayer();
                if (m.angry() && a != null && fair(a) && m.cd <= 0 && a.getLocation().distanceSquared(c) < 14 * 14) {
                    m.cd = 70; dash(m, a, 1.3);
                    w.playSound(c, Sound.ITEM_TRIDENT_RIPTIDE_1, SoundCategory.HOSTILE, 1.2f, 1.2f);
                    w.spawnParticle(Particle.BUBBLE, c, 30, 0.6, 0.3, 0.6, 0.2);
                    Bukkit.getScheduler().runTaskLater(pl, () -> { // the spear lands if it reached you
                        if (!m.body.isValid() || !a.isValid() || !a.getWorld().equals(m.body.getWorld())) return;
                        if (a.getLocation().distanceSquared(m.body.getLocation()) < 3.2 * 3.2 && fair(a)) {
                            a.damage(cfg("sailfin.spear", 8), m.body);
                            Edition.vel(a, unit(m.body.getVelocity().clone().setY(0)).multiply(1.0).setY(0.3));
                        }
                    }, 8L);
                }
            }
            case "mantaglider" -> { // its wake carries you along
                if (m.t % 20 == 0) {
                    w.spawnParticle(Particle.BUBBLE, c, 10, 2, 0.5, 2, 0.05);
                    for (Player p : near(c, cfg("mantaglider.wake", 6))) if (p.isInWater()) {
                        p.addPotionEffect(new PotionEffect(PotionEffectType.DOLPHINS_GRACE, 60, 0, true, false, true));
                        p.addPotionEffect(new PotionEffect(PotionEffectType.WATER_BREATHING, 100, 0, true, false, true));
                    }
                }
            }
            case "pearlhorse" -> { if (m.t % 40 == 0) w.spawnParticle(Particle.END_ROD, c, 1, 0.1, 0.2, 0.1, 0); }
            case "hammerjaw" -> { // rams you and leaves you reeling
                if (target != null && m.cd <= 0 && target.getLocation().distanceSquared(c) < 12 * 12) {
                    m.cd = 80; dash(m, target, 1.1);
                    w.playSound(c, Sound.ENTITY_RAVAGER_ATTACK, SoundCategory.HOSTILE, 1.2f, 0.8f);
                    Bukkit.getScheduler().runTaskLater(pl, () -> {
                        if (!m.body.isValid() || !target.isValid() || !target.getWorld().equals(m.body.getWorld())) return;
                        if (target.getLocation().distanceSquared(m.body.getLocation()) < 3 * 3 && fair(target)) {
                            target.damage(cfg("hammerjaw.ram", 7), m.body);
                            target.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 40, 2));
                            target.addPotionEffect(new PotionEffect(PotionEffectType.NAUSEA, 80, 0));
                            Edition.vel(target, unit(target.getLocation().toVector().subtract(m.body.getLocation().toVector())).multiply(1.2).setY(0.4));
                        }
                    }, 8L);
                }
            }
            case "razorcuda" -> { if (target != null && m.cd <= 0 && target.getLocation().distanceSquared(c) < 9 * 9) { m.cd = 40; dash(m, target, 1.0); } }
            case "stingray" -> {
                if (m.buried) {
                    if (m.t % 40 == 0) w.spawnParticle(Particle.BLOCK, c.clone().add(0, -0.3, 0), 3, 0.5, 0.05, 0.5, 0, m.body.getLocation().add(0, -1, 0).getBlock().getBlockData());
                    Player p = nearest(c, cfg("stingray.range", 2.6));
                    if (p != null && fair(p)) { sting(m, p); erupt(m, p); anger(m, p, 300); }
                } else {
                    Player a = m.angryPlayer();
                    if (m.angry() && a != null && fair(a) && m.cd <= 0 && a.getLocation().distanceSquared(c) < 2.4 * 2.4) sting(m, a);
                }
            }
            case "kelpstrangler" -> { // rooted: its fronds reach out and pull
                if (m.t % 20 == 0) for (Player p : near(c, cfg("kelpstrangler.reach", 7))) {
                    if (!fair(p) || !sees(m, p)) continue;
                    Vector in = c.toVector().subtract(p.getLocation().toVector());
                    double d = in.length();
                    if (d > 1.8) Edition.vel(p, p.getVelocity().add(unit(in).multiply(0.35).setY(unit(in).getY() * 0.35 - 0.05)));
                    p.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 30, 1));
                    w.spawnParticle(Particle.BLOCK, p.getLocation().add(0, 0.6, 0), 5, 0.3, 0.4, 0.3, 0, Material.KELP_PLANT.createBlockData());
                    if (d < 2.2 && m.cd <= 0) { m.cd = 20; p.damage(m.k.damage(), m.body); p.sendActionBar(ChatColor.DARK_GREEN + "The Kelp Strangler's fronds are crushing you!"); }
                }
            }
            case "siren" -> { // her song: dizzy, and drawn to her
                if (m.song > 0) {
                    m.song -= 10;
                    if (m.t % 20 == 0) {
                        w.spawnParticle(Particle.NOTE, c.clone().add(0, 1, 0), 3, 0.5, 0.4, 0.5, 1);
                        for (Player p : near(c, cfg("siren.song-range", 14))) if (fair(p)) {
                            Vector in = c.toVector().subtract(p.getLocation().toVector());
                            if (in.lengthSquared() > 4) Edition.vel(p, p.getVelocity().add(unit(in).multiply(0.22)));
                            p.addPotionEffect(new PotionEffect(PotionEffectType.NAUSEA, 100, 0));
                        }
                    }
                } else if (target != null && m.cd <= 0 && target.getLocation().distanceSquared(c) < 14 * 14 && sees(m, target)) {
                    m.cd = 200; m.song = 80;
                    w.playSound(c, Sound.ENTITY_ALLAY_AMBIENT_WITHOUT_ITEM, SoundCategory.HOSTILE, 2f, 0.6f);
                    for (Player p : near(c, cfg("siren.song-range", 14))) if (fair(p)) p.sendActionBar(ChatColor.DARK_AQUA + "Somewhere close, someone is singing...");
                }
            }
            // ---------------------------------------------------------------- 1.6.0: the Abyss
            case "gulper" -> { if (m.t % 40 == 0) w.spawnParticle(Particle.DUST, m.body.getLocation().add(m.body.getLocation().getDirection().multiply(-1.6)).add(0, 0.4, 0), 2, 0.1, 0.1, 0.1, 0, new Particle.DustOptions(Color.fromRGB(255, 90, 140), 1.2f)); }
            case "ghostshark" -> { if (target != null && m.cd <= 0 && target.getLocation().distanceSquared(c) < 9 * 9) { m.cd = 60; dash(m, target, 1.1); w.playSound(c, Sound.ENTITY_VEX_CHARGE, SoundCategory.HOSTILE, 1f, 0.5f); } }
            case "vampsquid" -> { // drinks your life while it's angry
                Player a = m.angryPlayer();
                if (m.angry() && a != null && fair(a)) {
                    double d2 = a.getLocation().distanceSquared(c);
                    if (d2 < 30) dash(m, a, 0.5);
                    if (d2 < 4.5 * 4.5 && m.t % 20 == 0) {
                        double dmg = cfg("vampsquid.drain", 2);
                        a.damage(dmg, m.body);
                        m.body.setHealth(Math.min(m.k.health(), m.body.getHealth() + dmg));
                        w.spawnParticle(Particle.DAMAGE_INDICATOR, a.getLocation().add(0, 1, 0), 3, 0.3, 0.3, 0.3, 0);
                        w.spawnParticle(Particle.DUST, c, 6, 0.4, 0.4, 0.4, 0, new Particle.DustOptions(Color.fromRGB(160, 10, 30), 1.4f));
                    }
                }
            }
            case "dumbo" -> { // its light keeps the dark off (Ocean.dark), and outside the dark it's night vision
                if (m.t % 30 == 0) w.spawnParticle(Particle.GLOW, c, 2, 0.4, 0.4, 0.4, 0);
                for (Player p : near(c, cfg("dumbo.light-radius", 6))) {
                    if (pl.ocean != null && pl.ocean.darkened.contains(p.getUniqueId())) pl.ocean.glowLit.put(p.getUniqueId(), pl.ocean.ticks + 50L);
                }
            }
            case "swallower" -> { if (target != null && m.cd <= 0 && target.getLocation().distanceSquared(c) < 8 * 8) { m.cd = 50; dash(m, target, 0.9); } }
            case "chainlight" -> {
                if (m.t % 30 == 0) w.spawnParticle(Particle.GLOW, c, 4, 1.2, 0.3, 1.2, 0);
                for (Player p : near(c, cfg("chainlight.reach", 2.6))) if (m.cd <= 0 && fair(p)) {
                    m.cd = 20; p.damage(cfg("chainlight.sting", 3), m.body);
                    p.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 50, 1));
                    w.spawnParticle(Particle.ELECTRIC_SPARK, p.getLocation().add(0, 1, 0), 10, 0.3, 0.4, 0.3, 0.05);
                }
                if (m.angry() && m.t % 60 == 0) shock(m, c, 4, cfg("chainlight.shock", 4));
            }
            case "viperfang" -> {
                if (m.t % 30 == 0) w.spawnParticle(Particle.END_ROD, c, 1, 0.3, 0.1, 0.3, 0);
                if (target != null && m.cd <= 0 && target.getLocation().distanceSquared(c) < 10 * 10) { m.cd = 50; dash(m, target, 1.05); }
            }
            case "mermaid" -> { // calm: she sings; angry: a bubble song that slows you and mends the guards
                if (!m.angry()) { if (m.t % 60 == 0 && viewerNear(c, 16)) w.spawnParticle(Particle.NOTE, c.clone().add(0, height(m) * 0.6, 0), 1, 0.2, 0.1, 0.2, random.nextDouble()); }
                else if (target != null && m.cd <= 0 && target.getLocation().distanceSquared(c) < 9 * 9) {
                    m.cd = 70;
                    w.spawnParticle(Particle.BUBBLE_POP, c, 50, 2.5, 1.5, 2.5, 0.08);
                    w.playSound(c, Sound.ENTITY_ALLAY_AMBIENT_WITH_ITEM, SoundCategory.HOSTILE, 1.5f, 0.6f);
                    for (Player p : near(c, cfg("mermaid.song-range", 7))) if (fair(p)) {
                        p.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 50, 0));
                        p.addPotionEffect(new PotionEffect(PotionEffectType.NAUSEA, 60, 0));
                    }
                    for (Beast o : live.values()) if (o.k.id().equals("merguard") && o.body.getWorld().equals(w) && o.body.getLocation().distanceSquared(c) < 12 * 12)
                        o.body.setHealth(Math.min(o.k.health(), o.body.getHealth() + cfg("mermaid.heal-guards", 4)));
                }
            }
            case "merguard" -> { if (m.angry() && m.t % 40 == 0) w.spawnParticle(Particle.DUST, c.clone().add(0, height(m) * 0.5, 0), 2, 0.2, 0.2, 0.2, 0, new Particle.DustOptions(Color.fromRGB(80, 200, 220), 1f)); }
            case "watcher" -> { // the gaze
                m.lookAt = target != null && target.getLocation().distanceSquared(c) < 24 * 24 ? target : null;
                if (m.t % 20 == 0) w.spawnParticle(Particle.DUST, c, 3, 0.4, 0.4, 0.4, 0, new Particle.DustOptions(Color.fromRGB(220, 40, 60), 1.1f));
                if (target != null && m.cd <= 0 && target.getLocation().distanceSquared(c) < 16 * 16 && sees(m, target)) { m.cd = 80; gaze(m, target); }
            }
            // ---------------------------------------------------------------- 1.8.0: the Trench
            case "hadalsnail" -> { if (m.t % 60 == 0 && viewerNear(c, 16)) w.spawnParticle(Particle.BUBBLE, c, 2, 0.2, 0.1, 0.2, 0.01); }
            case "anglerking" -> { // its lure: the only light down here, and it draws you in
                Location bulb = c.clone().add(m.body.getLocation().getDirection().setY(0).normalize().multiply(height(m) * 0.9)).add(0, height(m) * 0.5, 0);
                if (m.t % 10 == 0) w.spawnParticle(Particle.GLOW, bulb, 2, 0.1, 0.1, 0.1, 0);
                for (Player p : near(c, cfg("anglerking.light-radius", 7))) if (pl.ocean != null && pl.ocean.darkened.contains(p.getUniqueId())) pl.ocean.glowLit.put(p.getUniqueId(), pl.ocean.ticks + 30L);
                if (target != null && m.t % 20 == 0 && target.getLocation().distanceSquared(c) < 16 * 16 && sees(m, target)) {
                    Vector in = c.toVector().subtract(target.getLocation().toVector());
                    if (in.lengthSquared() > 9) Edition.vel(target, target.getVelocity().add(unit(in).multiply(cfg("anglerking.pull", 0.16))));
                    if (random.nextInt(3) == 0) target.sendActionBar(ChatColor.DARK_AQUA + "A soft light in the dark... you can't look away.");
                }
                if (target != null && m.cd <= 0 && target.getLocation().distanceSquared(c) < 5 * 5) { m.cd = 50; dash(m, target, 0.9); w.playSound(c, Sound.ENTITY_RAVAGER_ATTACK, SoundCategory.HOSTILE, 1.2f, 0.6f); }
            }
            case "bonecrawler" -> { if (target != null && m.cd <= 0 && target.getLocation().distanceSquared(c) < 6 * 6) { m.cd = 40; dash(m, target, 0.8); w.playSound(c, Sound.ENTITY_SKELETON_STEP, SoundCategory.HOSTILE, 1.4f, 0.6f); } }
            case "palestalker" -> { // the ambush: from out of the dark, all at once
                if (target != null && m.cd <= 0 && target.getLocation().distanceSquared(c) < 13 * 13 && sees(m, target)) {
                    m.cd = 90; dash(m, target, 1.45);
                    w.playSound(c, Sound.ENTITY_WARDEN_ATTACK_IMPACT, SoundCategory.HOSTILE, 1.2f, 1.6f);
                }
            }
            case "crushjelly" -> { // the pulse: the weight of the ocean
                if (m.t % 30 == 0) w.spawnParticle(Particle.GLOW, c, 3, 0.5, 0.3, 0.5, 0);
                boolean close = nearest(c, 3.5) != null;
                if ((m.angry() || close) && m.cd <= 0) {
                    m.cd = 60;
                    w.spawnParticle(Particle.SONIC_BOOM, c, 1, 0, 0, 0, 0);
                    w.playSound(c, Sound.BLOCK_CONDUIT_DEACTIVATE, SoundCategory.HOSTILE, 1.6f, 0.5f);
                    for (Player p : near(c, cfg("crushjelly.radius", 6))) if (fair(p)) {
                        Edition.vel(p, new Vector(p.getVelocity().getX() * 0.3, -cfg("crushjelly.slam", 0.9), p.getVelocity().getZ() * 0.3));
                        p.damage(cfg("crushjelly.damage", 4), m.body);
                        p.addPotionEffect(new PotionEffect(PotionEffectType.MINING_FATIGUE, 80, 1));
                        p.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 40, 1));
                        p.sendActionBar(ChatColor.DARK_PURPLE + "The pressure crushes you down!");
                    }
                }
            }
            case "tubeworm" -> { // stings anyone in its plumes
                if (m.t % 40 == 0 && viewerNear(c, 16)) w.spawnParticle(Particle.BUBBLE_COLUMN_UP, c, 4, 0.3, 0.2, 0.3, 0.05);
                for (Player p : near(c, cfg("tubeworm.reach", 2.4))) if (m.cd <= 0 && fair(p)) {
                    m.cd = 30; p.damage(m.k.damage(), m.body);
                    p.addPotionEffect(new PotionEffect(PotionEffectType.POISON, 60, 0));
                    w.spawnParticle(Particle.DUST, p.getLocation().add(0, 1, 0), 8, 0.3, 0.4, 0.3, 0, new Particle.DustOptions(Color.fromRGB(220, 30, 40), 1.2f));
                }
            }
            case "greatwhale" -> {
                if (m.t % 80 == 0 && viewerNear(c, 64)) {
                    w.spawnParticle(Particle.BUBBLE, c, 20, 3, 1.5, 3, 0.05);
                    if (random.nextInt(3) == 0) w.playSound(c, Sound.ENTITY_WARDEN_LISTENING, SoundCategory.NEUTRAL, 3f, 0.3f);
                }
            }
            default -> { }
        }
        // a neutral one whose grudge ran out calms down
        if (m.k.temper() == Temper.NEUTRAL && m.angryAt != null && !m.angry()) {
            m.angryAt = null;
            if (m.body instanceof Mob mb) mb.setTarget(null);
        }
    }

    // ---------- abilities
    /** Nothing solid between the creature's middle and the player's eyes (a block walk). */
    static boolean sees(Beast m, Player p) {
        Location a = m.body.getLocation().add(0, height(m) / 2, 0), b = p.getEyeLocation();
        if (!a.getWorld().equals(b.getWorld())) return false;
        Vector d = b.toVector().subtract(a.toVector());
        int n = (int) Math.ceil(d.length() / 0.5);
        for (int i = 1; i < n; i++) if (a.clone().add(d.clone().multiply(i / (double) n)).getBlock().getType().isOccluding()) return false;
        return true;
    }

    /** The Stingray's barb: venom. */
    void sting(Beast m, Player p) {
        m.cd = 40;
        p.damage(cfg("stingray.barb", 5), m.body);
        p.addPotionEffect(new PotionEffect(PotionEffectType.POISON, 100, 1));
        p.getWorld().playSound(p.getLocation(), Sound.ENTITY_BEE_STING, SoundCategory.HOSTILE, 1.2f, 0.6f);
        p.sendActionBar(ChatColor.GOLD + "A Stingray's barb! Venom burns in your leg.");
    }

    /** The Abyss Watcher's gaze: a red beam, and you feel it. */
    void gaze(Beast m, Player p) {
        World w = m.body.getWorld();
        Location from = m.body.getLocation().add(0, height(m) / 2, 0), to = p.getEyeLocation();
        Vector step = to.toVector().subtract(from.toVector());
        double len = step.length();
        step = unit(step).multiply(0.5);
        Location at = from.clone();
        for (double d = 0; d < len; d += 0.5) { at.add(step); w.spawnParticle(Particle.DUST, at, 1, 0, 0, 0, 0, new Particle.DustOptions(Color.fromRGB(230, 30, 50), 1.3f)); }
        w.playSound(from, Sound.ENTITY_ELDER_GUARDIAN_CURSE, SoundCategory.HOSTILE, 0.8f, 1.6f);
        p.damage(cfg("watcher.gaze", 5), m.body);
        p.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 60, 1));
        p.addPotionEffect(new PotionEffect(PotionEffectType.MINING_FATIGUE, 100, 1));
        p.addPotionEffect(new PotionEffect(PotionEffectType.DARKNESS, 80, 0));
        p.sendActionBar(ChatColor.RED + "The Abyss Watcher is looking at you.");
    }

    /** The Leafy Seadragon vanishes in a swirl of leaves: somewhere close in open water. */
    void vanish(Beast m) {
        Location c = m.body.getLocation();
        World w = c.getWorld();
        w.spawnParticle(Particle.BLOCK, c, 30, 0.5, 0.5, 0.5, 0, Material.KELP_PLANT.createBlockData());
        w.playSound(c, Sound.BLOCK_WET_GRASS_BREAK, SoundCategory.NEUTRAL, 1f, 1.2f);
        for (int i = 0; i < 8; i++) {
            Location to = c.clone().add(random.nextGaussian() * 4, random.nextGaussian() * 2, random.nextGaussian() * 4);
            if (!w.isChunkLoaded(to.getBlockX() >> 4, to.getBlockZ() >> 4) || to.getBlock().getType() != Material.WATER) continue;
            if (!clearPath(c, to)) continue;
            m.body.teleport(to);
            return;
        }
    }

    /** Water all the way between two points (a block walk, so it never lands behind a wall). */
    static boolean clearPath(Location a, Location b) {
        Vector d = b.toVector().subtract(a.toVector());
        int n = (int) Math.ceil(d.length() * 2);
        for (int i = 1; i <= n; i++) {
            Location q = a.clone().add(d.clone().multiply(i / (double) n));
            if (q.getBlock().getType() != Material.WATER) return false;
        }
        return true;
    }
    static Vector unit(Vector v) { return v.lengthSquared() < 1e-6 ? new Vector(0, 1, 0) : v.normalize(); }

    /** A small lingering acid cloud (poison). */
    void acidCloud(Beast m, Location at, float r, int dur) {
        at.getWorld().spawn(at, AreaEffectCloud.class, cl -> {
            cl.setRadius(r); cl.setDuration(dur); cl.setRadiusPerTick(-r / (float) dur * 0.5f);
            cl.setColor(Color.fromRGB(150, 220, 60));
            cl.addCustomEffect(new PotionEffect(PotionEffectType.POISON, 60, 0), true);
            cl.addScoreboardTag(PART);
        });
    }

    /** The Sludge Snail's trail: slows and poisons whoever steps in it. */
    void slime(Beast m, Location at) {
        at.getWorld().spawn(at.clone().add(0, 0.05, 0), AreaEffectCloud.class, cl -> {
            cl.setRadius(1.1f); cl.setDuration(160); cl.setRadiusPerTick(0);
            cl.setColor(Color.fromRGB(170, 230, 90));
            cl.addCustomEffect(new PotionEffect(PotionEffectType.SLOWNESS, 60, 1), true);
            cl.addCustomEffect(new PotionEffect(PotionEffectType.POISON, 40, 0), true);
            cl.addScoreboardTag(PART);
        });
    }

    /** Flak: three globs of acid lobbed at you. */
    void flak(Beast m, Player t) {
        World w = m.body.getWorld();
        w.playSound(m.body.getLocation(), Sound.ENTITY_SNOWBALL_THROW, SoundCategory.HOSTILE, 1.4f, 0.5f);
        for (int i = 0; i < 3; i++) {
            Vector v = t.getEyeLocation().toVector().subtract(m.body.getEyeLocation().toVector());
            double d = v.length();
            v = unit(v).multiply(Math.min(1.6, 0.6 + d * 0.06)).add(new Vector(random.nextGaussian() * 0.06, 0.12 + d * 0.012, random.nextGaussian() * 0.06));
            org.bukkit.entity.Snowball sb = m.body.launchProjectile(org.bukkit.entity.Snowball.class, v);
            sb.setItem(new ItemStack(Material.SLIME_BALL));
            sb.addScoreboardTag(ACID_TAG);
        }
    }

    /** A Sludge Shambler's glob: one big lobbed ball of sludge. */
    void glob(Beast m, Player t) {
        Vector v = t.getEyeLocation().toVector().subtract(m.body.getEyeLocation().toVector());
        double d = v.length();
        v = unit(v).multiply(Math.min(1.5, 0.6 + d * 0.06)).add(new Vector(0, 0.14 + d * 0.012, 0));
        org.bukkit.entity.Snowball sb = m.body.launchProjectile(org.bukkit.entity.Snowball.class, v);
        sb.setItem(new ItemStack(Material.SLIME_BLOCK));
        sb.addScoreboardTag(ACID_TAG);
        sb.addScoreboardTag(GLOB_TAG);
        m.body.getWorld().playSound(m.body.getLocation(), Sound.ENTITY_SLIME_SQUISH, SoundCategory.HOSTILE, 1.4f, 0.5f);
    }

    static final String GLOB_TAG = "faultline_deep_glob", FLASK_TAG = "faultline_goblin_flask", BOMB_TAG = "faultline_goblin_bomb";

    /** A Storm Wraith's downpour: drops of acid rain out of the cloud over you (and round you). */
    void acidDownpour(Beast m, Player t) {
        World w = t.getWorld();
        w.playSound(t.getLocation(), Sound.WEATHER_RAIN_ABOVE, SoundCategory.HOSTILE, 1.5f, 0.6f);
        for (int i = 0; i < 6; i++) {
            Location from = t.getLocation().add(random.nextGaussian() * 2.2, 7 + random.nextDouble() * 3, random.nextGaussian() * 2.2);
            if (from.getBlock().getType().isSolid()) continue;
            org.bukkit.entity.Snowball sb = w.spawn(from, org.bukkit.entity.Snowball.class);
            sb.setItem(new ItemStack(Material.LIME_DYE));
            sb.setShooter(m.body);
            Edition.vel(sb, new Vector(0, -0.8, 0));
            sb.addScoreboardTag(ACID_TAG);
        }
    }

    /** Corroding teeth: every armour piece loses durability. */
    void wearArmor(Player p, int amount) {
        for (ItemStack it : p.getInventory().getArmorContents()) {
            if (it == null || !(it.getItemMeta() instanceof org.bukkit.inventory.meta.Damageable dm) || it.getType().getMaxDurability() <= 0) continue;
            dm.setDamage(Math.min(it.getType().getMaxDurability() - 1, dm.getDamage() + amount));
            it.setItemMeta(dm);
        }
    }

    // ---------------------------------------------------------------- GRUBNASH THE ACID GOBLIN (mini boss, 500 health)
    static final List<String> GOBLIN_LINES = List.of("Shinies! Gimme your shinies!", "Hehehe... it BURNS, don't it?", "Mites! Eat 'em!", "Boom boom BOOM!", "Grubnash never misses! ...mostly.");

    void goblinSay(Beast m, String line) {
        for (Player p : near(m.body.getLocation(), 40)) p.sendMessage(ChatColor.GOLD + "" + ChatColor.BOLD + "Grubnash" + ChatColor.DARK_GRAY + ": " + ChatColor.YELLOW + line);
        m.body.getWorld().playSound(m.body.getLocation(), Sound.ENTITY_PIGLIN_ANGRY, SoundCategory.HOSTILE, 1.5f, 1.6f);
    }

    /** His moves: Acid Flasks (3 splashing flasks: acid pools), a Scrap Bomb (goes off after 1.5 s), a Goblin Leap (slams down on you), Call the Mites. Frenzy below half. */
    void goblin(Beast m, Player t) {
        World w = m.body.getWorld();
        Location c = m.body.getLocation();
        if (m.t % 40 == 0) w.spawnParticle(Particle.DUST, c.clone().add(0, 1, 0), 6, 0.4, 0.6, 0.4, 0, new Particle.DustOptions(Color.fromRGB(170, 255, 80), 1.3f));
        boolean frenzy = m.body.getHealth() < m.k.health() * cfg("goblin.frenzy-at", 0.5);
        if (frenzy && !m.inked) {
            m.inked = true;
            m.body.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 20 * 600, 1, false, false));
            goblinSay(m, "You made Grubnash MAD!");
            w.playSound(c, Sound.ENTITY_RAVAGER_ROAR, SoundCategory.HOSTILE, 2f, 1.8f);
        }
        if (t == null || m.cd > 0) return;
        double d2 = t.getLocation().distanceSquared(c);
        if (d2 > 30 * 30) return;
        int move = random.nextInt(d2 > 49 ? 3 : 4); // far: no mites first, mostly ranged
        if (random.nextInt(5) == 0) goblinSay(m, GOBLIN_LINES.get(random.nextInt(GOBLIN_LINES.size())));
        switch (move) {
            case 0 -> { // acid flasks
                for (int i = 0; i < 3; i++) {
                    Vector v = t.getLocation().add(random.nextGaussian() * 2, 0, random.nextGaussian() * 2).toVector().subtract(m.body.getEyeLocation().toVector());
                    double d = v.length();
                    v = unit(v).multiply(Math.min(1.5, 0.5 + d * 0.06)).add(new Vector(0, 0.2 + d * 0.015, 0));
                    org.bukkit.entity.Snowball sb = m.body.launchProjectile(org.bukkit.entity.Snowball.class, v);
                    sb.setItem(new ItemStack(Material.SPLASH_POTION));
                    sb.addScoreboardTag(ACID_TAG); sb.addScoreboardTag(FLASK_TAG);
                }
                w.playSound(c, Sound.ENTITY_WITCH_THROW, SoundCategory.HOSTILE, 1.5f, 0.8f);
            }
            case 1 -> { // a scrap bomb
                Vector v = t.getLocation().toVector().subtract(m.body.getEyeLocation().toVector());
                double d = v.length();
                v = unit(v).multiply(Math.min(1.4, 0.5 + d * 0.055)).add(new Vector(0, 0.25 + d * 0.015, 0));
                org.bukkit.entity.Snowball sb = m.body.launchProjectile(org.bukkit.entity.Snowball.class, v);
                sb.setItem(new ItemStack(Material.TNT));
                sb.addScoreboardTag(ACID_TAG); sb.addScoreboardTag(BOMB_TAG);
                w.playSound(c, Sound.ENTITY_TNT_PRIMED, SoundCategory.HOSTILE, 1.5f, 1.4f);
            }
            case 2 -> { // the leap
                Vector v = t.getLocation().toVector().subtract(c.toVector()).setY(0);
                double d = v.length();
                Edition.vel(m.body, unit(v).multiply(Math.min(1.6, 0.4 + d * 0.11)).setY(0.75));
                w.playSound(c, Sound.ENTITY_GOAT_LONG_JUMP, SoundCategory.HOSTILE, 1.5f, 1.2f);
                int[] air = {0};
                Bukkit.getScheduler().runTaskTimer(pl, task -> {
                    if (!m.body.isValid() || ++air[0] > 60) { task.cancel(); return; }
                    if (air[0] < 6 || !(m.body.isOnGround() || m.body.isInWater())) return;
                    task.cancel();
                    Location at = m.body.getLocation();
                    w.spawnParticle(Particle.EXPLOSION, at, 3, 1, 0.2, 1, 0);
                    w.spawnParticle(Particle.DUST, at, 50, 2.5, 0.3, 2.5, 0, new Particle.DustOptions(Color.fromRGB(170, 255, 80), 1.8f));
                    w.playSound(at, Sound.ENTITY_GENERIC_EXPLODE, SoundCategory.HOSTILE, 1.5f, 1.3f);
                    for (Player p : near(at, cfg("goblin.slam-radius", 3.5))) if (fair(p)) {
                        p.damage(cfg("goblin.slam", 9), m.body);
                        Edition.vel(p, unit(p.getLocation().toVector().subtract(at.toVector()).setY(0)).multiply(0.8).setY(0.7));
                    }
                }, 2L, 2L);
            }
            default -> { // call the mites
                Kind mite = KINDS.get("acidmite");
                for (int i = 0; i < (frenzy ? 4 : 3); i++) {
                    Beast b = spawn(mite, c.clone().add(random.nextGaussian() * 1.5, 0.2, random.nextGaussian() * 1.5));
                    if (b != null && b.body instanceof Mob mb) mb.setTarget(t);
                }
                w.playSound(c, Sound.ENTITY_SILVERFISH_AMBIENT, SoundCategory.HOSTILE, 2f, 0.6f);
            }
        }
        m.cd = frenzy ? (int) cfg("goblin.frenzy-cooldown", 46) : (int) cfg("goblin.cooldown", 80);
    }

    /** The Sulphur Urchin's spines, out in every direction. */
    void spines(Beast m) {
        Location c = m.body.getLocation().add(0, 0.5, 0);
        for (int i = 0; i < 12; i++) {
            double a = i * Math.PI / 6;
            Vector v = new Vector(Math.cos(a), 0.15 + random.nextDouble() * 0.3, Math.sin(a)).multiply(1.2);
            org.bukkit.entity.Arrow ar = c.getWorld().spawn(c.clone().add(v.clone().multiply(0.6)), org.bukkit.entity.Arrow.class);
            Edition.vel(ar, v);
            ar.setShooter(m.body);
            ar.setDamage(cfg("urchin.spine", 2));
            ar.setPickupStatus(org.bukkit.entity.AbstractArrow.PickupStatus.DISALLOWED);
            try { ar.addCustomEffect(new PotionEffect(PotionEffectType.POISON, 60, 0), true); } catch (RuntimeException ignored) { } // (a test server lacks tipped arrows)
            ar.addScoreboardTag(ACID_TAG);
            Bukkit.getScheduler().runTaskLater(pl, () -> { if (ar.isValid()) ar.remove(); }, 100L);
        }
        c.getWorld().playSound(c, Sound.ENTITY_ARROW_SHOOT, SoundCategory.HOSTILE, 1.2f, 1.6f);
    }

    /** A Nuclear Toad goes off a second after it's hurt. */
    void meltdown(Beast m) {
        if (m.fused) return;
        m.fused = true; pose(m, true);
        Location c = m.body.getLocation();
        c.getWorld().playSound(c, Sound.ENTITY_CREEPER_PRIMED, SoundCategory.HOSTILE, 1.2f, 0.6f);
        Bukkit.getScheduler().runTaskLater(pl, () -> {
            Location at = m.body.isValid() ? m.body.getLocation() : c;
            if (m.body.isValid()) remove(m);
            try { at.getWorld().createExplosion(at, (float) cfg("nucleartoad.power", 2.2), false, false); } catch (RuntimeException ignored) { } // (no blocks broken either way)
            at.getWorld().spawnParticle(Particle.DUST, at, 80, 2, 1, 2, 0, new Particle.DustOptions(Color.fromRGB(170, 255, 80), 2f));
            acidCloud(m, at, 3f, 120);
        }, 20L);
    }

    /** The Sulphur Skater keeps to the surface of the water and skates about. */
    void skate(Beast m) {
        if (!m.body.isInWater() && m.body.getLocation().getBlock().getType() != Material.WATER) return;
        Location l = m.body.getLocation();
        double up = DeepGenerator.SEA + 0.75 - l.getY();
        Vector v = m.body.getVelocity();
        if (m.t % 30 == 0 && !m.angry()) { double a = random.nextDouble() * Math.PI * 2; v.setX(Math.cos(a) * 0.35).setZ(Math.sin(a) * 0.35); }
        v.setY(Math.max(-0.2, Math.min(0.3, up * 0.4)));
        Edition.vel(m.body, v);
    }

    /** A Sulphur Leech holding on: it rides you, drinking, until it's knocked off or lets go. */
    void latchTick(Beast m) {
        m.latched -= 2;
        Player p = m.latchedTo == null ? null : Bukkit.getPlayer(m.latchedTo);
        if (p == null || !fair(p) || !p.getWorld().equals(m.body.getWorld()) || m.latched <= 0) { m.latched = 0; m.latchedTo = null; return; }
        m.body.teleport(p.getLocation().add(p.getLocation().getDirection().setY(0).multiply(-0.4)).add(0, 0.6, 0));
        if (m.t % 20 == 0) {
            p.damage(cfg("leech.drain", 1), m.body);
            m.body.setHealth(Math.min(m.k.health(), m.body.getHealth() + 1));
            p.sendActionBar(ChatColor.DARK_RED + "A Sulphur Leech is drinking from you! " + ChatColor.GRAY + "Hit it to knock it off.");
        }
    }

    void snap(Beast m) {
        if (m.model == null) return;
        m.fused = true; pose(m, true);
        Bukkit.getScheduler().runTaskLater(pl, () -> { m.fused = false; pose(m, true); }, 6L);
    }

    void shock(Beast m, Location c, double r, double dmg) {
        World w = c.getWorld();
        w.spawnParticle(Particle.ELECTRIC_SPARK, c, 70, r * 0.6, r * 0.4, r * 0.6, 0.2);
        w.playSound(c, Sound.ENTITY_LIGHTNING_BOLT_IMPACT, SoundCategory.HOSTILE, 0.6f, 1.8f);
        for (Player p : near(c, r)) if (fair(p) && p.isInWater()) {
            p.damage(dmg, m.body);
            p.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 40, 1));
        }
    }

    void gas(Beast m, Location c) {
        World w = c.getWorld();
        w.playSound(c, Sound.ENTITY_PUFFER_FISH_BLOW_OUT, SoundCategory.HOSTILE, 1.4f, 0.6f);
        for (int i = 0; i < 3; i++) {
            Location at = c.clone().add(random.nextGaussian() * 1.5, random.nextGaussian() * 0.6, random.nextGaussian() * 1.5);
            w.spawn(at, AreaEffectCloud.class, cl -> {
                cl.setRadius(2.2f);
                cl.setDuration(100);
                cl.setRadiusPerTick(-0.01f);
                cl.setColor(Color.fromRGB(110, 170, 40));
                cl.addCustomEffect(new PotionEffect(PotionEffectType.POISON, 80, 1), true);
                cl.addScoreboardTag(PART);
            });
        }
    }

    void erupt(Beast m, Player p) {
        m.buried = false;
        if (m.body instanceof Mob mb) { mb.setAI(true); mb.setTarget(p); }
        Location c = m.body.getLocation();
        World w = c.getWorld();
        w.spawnParticle(Particle.BLOCK, c, 50, 1, 0.5, 1, 0.1, c.clone().add(0, -1, 0).getBlock().getBlockData());
        w.playSound(c, Sound.ENTITY_RAVAGER_ROAR, SoundCategory.HOSTILE, 0.8f, 1.7f);
        Edition.vel(m.body, p.getLocation().toVector().subtract(c.toVector()).normalize().multiply(1.0).setY(0.5));
        pose(m, true);
    }

    void dash(Beast m, Player p, double speed) {
        Vector v = p.getLocation().add(0, 0.8, 0).toVector().subtract(m.body.getLocation().toVector());
        if (v.lengthSquared() < 0.01) return;
        Edition.vel(m.body, v.normalize().multiply(speed));
    }

    void emp(Beast m, Location c) {
        World w = c.getWorld();
        w.spawnParticle(Particle.FLASH, c, 1);
        w.spawnParticle(Particle.SONIC_BOOM, c, 1);
        w.playSound(c, Sound.BLOCK_BEACON_DEACTIVATE, SoundCategory.HOSTILE, 1.5f, 0.6f);
        for (Player p : near(c, 9)) if (fair(p)) {
            p.removePotionEffect(PotionEffectType.NIGHT_VISION);
            p.removePotionEffect(PotionEffectType.CONDUIT_POWER);
            p.addPotionEffect(new PotionEffect(PotionEffectType.DARKNESS, 120, 0));
            p.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 60, 0));
            p.sendActionBar(ChatColor.AQUA + "The Lampsquid's pulse snuffs out every light!");
        }
    }

    void blink(Beast m, Player t) {
        Location to = waterNear(t.getEyeLocation(), 3, 6); // in sight of its target: never through a wall into a base
        if (to == null) return;
        World w = m.body.getWorld();
        w.spawnParticle(Particle.REVERSE_PORTAL, m.body.getLocation(), 30, 0.4, 0.6, 0.4, 0.05);
        m.body.teleport(to);
        w.spawnParticle(Particle.REVERSE_PORTAL, to, 30, 0.4, 0.6, 0.4, 0.05);
        w.playSound(to, Sound.ENTITY_ENDERMAN_TELEPORT, SoundCategory.HOSTILE, 1f, 0.6f);
    }

    void warpBolt(Beast m, Player p) {
        Location to = waterNear(p.getEyeLocation(), 5, 8); // in sight: never through rock into another cave or a sealed room
        if (to == null) return;
        to.setYaw(p.getLocation().getYaw()); to.setPitch(p.getLocation().getPitch());
        p.getWorld().spawnParticle(Particle.PORTAL, p.getLocation().add(0, 1, 0), 40, 0.4, 0.8, 0.4, 0.5);
        p.teleport(to);
        p.addPotionEffect(new PotionEffect(PotionEffectType.NAUSEA, 100, 0));
        p.sendActionBar(ChatColor.LIGHT_PURPLE + "The Rift Warper's bolt throws you through the water!");
    }

    /** A water block (with water above it) between min and max blocks from l, with nothing solid between l and it. */
    Location waterNear(Location l, double min, double max) {
        for (int i = 0; i < 12; i++) {
            double a = random.nextDouble() * Math.PI * 2, d = min + random.nextDouble() * (max - min);
            Location c = l.clone().add(Math.cos(a) * d, random.nextGaussian() * 1.5, Math.sin(a) * d);
            if (c.getBlock().getType() != Material.WATER || c.clone().add(0, 1, 0).getBlock().getType() != Material.WATER) continue;
            Vector dir = c.toVector().subtract(l.toVector());
            double len = dir.length();
            if (len < 0.1) continue;
            var hit = l.getWorld().rayTraceBlocks(l, dir.normalize(), len, org.bukkit.FluidCollisionMode.NEVER, true);
            if (hit != null && hit.getHitBlock() != null) continue;
            return c;
        }
        return null;
    }

    static boolean metal(ItemStack it) {
        if (it == null) return false;
        String n = it.getType().name();
        return (n.startsWith("IRON_") || n.startsWith("GOLDEN_") || n.startsWith("NETHERITE_") || n.startsWith("DIAMOND_"))
                && (n.endsWith("_SWORD") || n.endsWith("_AXE") || n.endsWith("_PICKAXE") || n.endsWith("_SHOVEL") || n.endsWith("_HOE"))
                || it.getType() == Material.TRIDENT;
    }

    static final Set<Material> LIGHTS = EnumSet.of(Material.TORCH, Material.SOUL_TORCH, Material.LANTERN, Material.SOUL_LANTERN,
            Material.GLOWSTONE, Material.SEA_LANTERN, Material.SEA_PICKLE, Material.GLOW_BERRIES, Material.SHROOMLIGHT,
            Material.JACK_O_LANTERN, Material.END_ROD, Material.GLOW_INK_SAC, Material.LIGHT);

    static boolean holdsLight(Player p) {
        return LIGHTS.contains(p.getInventory().getItemInMainHand().getType()) || LIGHTS.contains(p.getInventory().getItemInOffHand().getType());
    }

    static String pretty(Material m) { return m.name().toLowerCase().replace('_', ' '); }

    // ---------- who's around
    static boolean fair(Player p) { return (p.getGameMode() == GameMode.SURVIVAL || p.getGameMode() == GameMode.ADVENTURE) && !p.isDead(); }

    List<Player> near(Location c, double r) {
        List<Player> out = new ArrayList<>();
        for (Player p : c.getWorld().getPlayers()) if (p.getLocation().distanceSquared(c) <= r * r) out.add(p);
        return out;
    }

    Player nearest(Location c, double r) {
        Player best = null; double bd = r * r;
        for (Player p : c.getWorld().getPlayers()) { double d = p.getLocation().distanceSquared(c); if (d <= bd && fair(p)) { bd = d; best = p; } }
        return best;
    }

    /** Who it's after: an aggressive one hunts the nearest fair player in 16; a neutral one only whoever angered it. */
    Player target(Beast m) {
        if (m.k.temper() == Temper.PASSIVE) return null;
        World w = m.body.getWorld(); // (never someone who has gone to another world: distances across worlds throw)
        if (m.k.temper() == Temper.NEUTRAL) { Player a = m.angryPlayer(); return m.angry() && a != null && fair(a) && a.getWorld().equals(w) ? a : null; }
        if (m.body instanceof Mob mb && mb.getTarget() instanceof Player p && fair(p) && p.getWorld().equals(w)) return p;
        Player p = nearest(m.body.getLocation(), 16);
        if (p != null && m.body instanceof Mob mb && !m.buried) mb.setTarget(p);
        return p;
    }

    void anger(Beast m, Player p, int ticksLong) {
        m.angryAt = p.getUniqueId();
        m.angryUntil = ticks + ticksLong;
        if (m.body instanceof Mob mb && !m.k.id().equals("clam")) mb.setTarget(p);
    }

    // =========================================================================================== the spawner
    /** Every 1.5 s: each player in the Dark Ocean with fewer than mobs.cap creatures around gets one more, for where they are. */
    void spawner() {
        World w = pl.world;
        if (w == null || !pl.getConfig().getBoolean("mobs.enabled", true)) return;
        int cap = (int) cfg("cap", 10); // (1.5.2: was 14; each creature is up to 10 entities with its bones)
        if (w.getDifficulty() == Difficulty.PEACEFUL) return; // the server would delete the hostile ones at once, over and over
        guardians.removeIf(id -> { Entity g = Bukkit.getEntity(id); return g == null || !g.isValid(); });
        for (Player p : w.getPlayers()) {
            if (p.getGameMode() == GameMode.SPECTATOR) continue;
            Location l = p.getLocation();
            int around = 0;
            for (Beast m : live.values()) if (m.body.getWorld().equals(w) && m.body.getLocation().distanceSquared(l) < 56 * 56) around++;
            if (around >= cap) continue;
            // the Sulphur Sea's islands and the air over them: a land or flying creature now and then
            if (pl.generator.sulphur(w.getSeed(), l.getBlockX(), l.getBlockZ()) > 0.35 && random.nextDouble() < cfg("sulphur.land-share", 0.4)) {
                Location land = landSpot(l);
                Kind k = land == null ? null : pickLand(land);
                if (k != null) {
                    if (AIR.contains(k.id())) land.add(0, 3 + random.nextInt(4), 0);
                    spawn(k, land);
                    if (k.id().equals("crabling")) for (int i = 0; i < 2 + random.nextInt(3); i++) spawn(k, land.clone().add(random.nextGaussian(), 0, random.nextGaussian())); // a swarm
                    continue;
                }
            }
            Location at = spawnSpot(l);
            if (at == null) continue;
            DeepGenerator.Zone z = pl.zone(at);
            if (z == DeepGenerator.Zone.ABYSS && random.nextDouble() < cfg("vanilla.guardian-chance", 0.06)) {
                long near = guardians.stream().map(Bukkit::getEntity).filter(g -> g != null && g.getWorld().equals(w) && g.getLocation().distanceSquared(l) < 56 * 56).count();
                if (near < cfg("vanilla.guardian-cap", 3)) {
                    Entity g = w.spawnEntity(at, random.nextDouble() < cfg("vanilla.elder-share", 0.08) ? EntityType.ELDER_GUARDIAN : EntityType.GUARDIAN);
                    if (g instanceof LivingEntity le)
                        le.setRemoveWhenFarAway(true);
                    guardians.add(g.getUniqueId());
                }
                continue;
            }
            Kind k = pick(z, at);
            if (k != null && k.temper() == Temper.AGGRESSIVE && MermaidDen.weight(at.getBlockX(), at.getBlockZ()) > 0.3) continue; // the Den is guarded
            if (k != null && k.id().equals("sulphurskater")) at.setY(DeepGenerator.SEA + 0.2); // it lives on the surface
            if (k != null) spawn(k, at);
            if (k != null && k.id().equals("razorcuda")) for (int i = 0; i < 2; i++) { // they hunt in threes
                Location o = at.clone().add(random.nextGaussian(), random.nextGaussian() * 0.5, random.nextGaussian());
                if (o.getBlock().getType() == Material.WATER) spawn(k, o);
            }
        }
    }

    /** A spot 14-40 blocks from l in open water (2 blocks of it), loaded. */
    Location spawnSpot(Location l) {
        World w = l.getWorld();
        for (int i = 0; i < 6; i++) {
            double a = random.nextDouble() * Math.PI * 2, d = 14 + random.nextDouble() * 26;
            Location c = l.clone().add(Math.cos(a) * d, random.nextGaussian() * 8, Math.sin(a) * d);
            c.setY(Math.max(w.getMinHeight() + 4, Math.min(DeepGenerator.SEA - 2, c.getY())));
            if (!w.isChunkLoaded(c.getBlockX() >> 4, c.getBlockZ() >> 4)) continue;
            Block b = c.getBlock();
            if (b.getType() != Material.WATER || b.getRelative(0, 1, 0).getType() != Material.WATER) continue;
            return c;
        }
        return null;
    }

    /** A spot on a Sulphur Sea island 14-40 blocks from l: the top of dry ground, with room above. */
    Location landSpot(Location l) {
        World w = l.getWorld();
        for (int i = 0; i < 6; i++) {
            double a = random.nextDouble() * Math.PI * 2, d = 14 + random.nextDouble() * 26;
            int x = (int) Math.floor(l.getX() + Math.cos(a) * d), z = (int) Math.floor(l.getZ() + Math.sin(a) * d);
            if (!w.isChunkLoaded(x >> 4, z >> 4)) continue;
            int y = w.getHighestBlockYAt(x, z, org.bukkit.HeightMap.MOTION_BLOCKING_NO_LEAVES);
            if (y <= DeepGenerator.SEA) continue;
            Block top = w.getBlockAt(x, y, z);
            if (!top.getType().isSolid() || top.isLiquid() || !top.getRelative(0, 1, 0).isEmpty() || !top.getRelative(0, 2, 0).isEmpty()) continue;
            return new Location(w, x + 0.5, y + 1, z + 0.5);
        }
        return null;
    }

    /** One of the Sulphur Sea's land or flying kinds. */
    Kind pickLand(Location at) {
        List<Kind> opts = new ArrayList<>();
        int total = 0;
        for (Kind k : KINDS.values()) {
            if (k.zone() != DeepGenerator.Zone.SULPHUR || !(LAND.contains(k.id()) || AIR.contains(k.id()))) continue;
            if (!pl.getConfig().getBoolean("mobs." + k.id() + ".enabled", true)) continue;
            int wgt = (int) cfg(k.id() + ".weight", k.weight());
            if (wgt <= 0) continue;
            opts.add(k); total += wgt;
        }
        if (total <= 0) return null;
        int r = random.nextInt(total);
        for (Kind k : opts) { r -= (int) cfg(k.id() + ".weight", k.weight()); if (r < 0) return k; }
        return null;
    }

    Kind pick(DeepGenerator.Zone z, Location at) {
        boolean floor = at.clone().add(0, -1, 0).getBlock().getType().isSolid();
        String region = z == DeepGenerator.Zone.SUNLIT ? Regions.at(at.getBlockX(), at.getBlockZ()) : null; // 1.9.0
        List<Kind> opts = new ArrayList<>();
        int total = 0;
        for (Kind k : KINDS.values()) {
            if (k.zone() != z) continue;
            if (LAND.contains(k.id()) || AIR.contains(k.id())) continue; // (those come from landSpot)
            if (!java.util.Objects.equals(REGION.get(k.id()), region)) continue; // the outer regions have their own, and keep them
            if (!pl.getConfig().getBoolean("mobs." + k.id() + ".enabled", true)) continue;
            boolean needsFloor = FLOOR.contains(k.id());
            if (needsFloor != floor && needsFloor) continue;
            if (k.id().equals("driftback")) { long n = live.values().stream().filter(m -> m.k == k).count(); if (n >= cfg("driftback.max", 2)) continue; }
            int wgt = (int) cfg(k.id() + ".weight", k.weight());
            if (wgt <= 0) continue;
            opts.add(k); total += wgt;
        }
        if (total <= 0) return null;
        int r = random.nextInt(total);
        for (Kind k : opts) { r -= (int) cfg(k.id() + ".weight", k.weight()); if (r < 0) return k; }
        return null;
    }

    /** Ones nobody is near any more go (they're not saved anyway). */
    void farAway() {
        for (Beast m : new ArrayList<>(live.values())) {
            Location l = m.body.getLocation();
            boolean anyone = false;
            for (Player p : l.getWorld().getPlayers()) if (p.getLocation().distanceSquared(l) < 100 * 100) { anyone = true; break; }
            if (!anyone) remove(m);
        }
    }

    /** Is any Bedrock player in the Dark Ocean? (worked out every 2 s) */
    boolean bedrockHere;

    void bedrockUpkeep() {
        bedrockHere = false;
        if (pl.world != null) for (Player p : pl.world.getPlayers()) if (Edition.bedrock(p)) { bedrockHere = true; break; }
        for (Beast m : live.values()) {
            if (m.stand == null || !m.stand.isValid()) continue;
            for (Player p : m.stand.getWorld().getPlayers()) {
                if (Edition.bedrock(p)) { if (!p.canSee(m.stand)) p.showEntity(pl, m.stand); }
                else if (p.canSee(m.stand)) p.hideEntity(pl, m.stand);
            }
        }
    }

    // =========================================================================================== events
    Beast mobOf(Entity e) {
        Beast m = live.get(e.getUniqueId());
        if (m != null) return m;
        if (e.getScoreboardTags().contains(STAND)) for (Beast x : live.values()) if (e.equals(x.stand)) return x;
        return null;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onTarget(EntityTargetEvent e) {
        Beast m = live.get(e.getEntity().getUniqueId());
        if (m == null) return;
        if (e.getTarget() == null) return;
        if (!(e.getTarget() instanceof Player p) || !fair(p)) { e.setCancelled(true); return; }
        if (m.buried) { e.setCancelled(true); return; }
        switch (m.k.temper()) {
            case PASSIVE -> e.setCancelled(true);
            case NEUTRAL -> { if (!m.angry() || !p.getUniqueId().equals(m.angryAt)) e.setCancelled(true); }
            default -> { }
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onHurt(EntityDamageEvent e) {
        Entity ent = e.getEntity();
        if (ent.getScoreboardTags().contains(STAND)) { // a Bedrock player hit the model: hit the creature
            e.setCancelled(true);
            Beast m = mobOf(ent);
            if (m != null && e instanceof EntityDamageByEntityEvent by && by.getDamager() instanceof Player p && fair(p)) m.body.damage(e.getDamage(), p);
            return;
        }
        if (ent.getScoreboardTags().contains(PART)) { e.setCancelled(true); return; }
        Beast m = live.get(ent.getUniqueId());
        if (m == null) return;
        if (e.getCause() == EntityDamageEvent.DamageCause.DROWNING || e.getCause() == EntityDamageEvent.DamageCause.SUFFOCATION
                || e.getCause() == EntityDamageEvent.DamageCause.DRYOUT) { e.setCancelled(true); return; }
        Player by = null;
        if (e instanceof EntityDamageByEntityEvent ee) {
            if (ee.getDamager() instanceof Player p) by = p;
            else if (ee.getDamager() instanceof org.bukkit.entity.Projectile pr && pr.getShooter() instanceof Player s) by = s;
            if (ee.getDamager() != null && ours(ee.getDamager())) { e.setCancelled(true); return; } // they never hurt each other
        }
        if (m.k.id().equals("devilfish") && m.maskHits > 0) { // the bone mask turns most of it aside
            e.setDamage(e.getDamage() * (1 - cfg("devilfish.mask-reduction", 0.7)));
            if (by != null && e.getDamage() >= 1.2) {
                m.maskHits--;
                World w = ent.getWorld();
                w.playSound(ent.getLocation(), m.maskHits == 0 ? Sound.ENTITY_ITEM_BREAK : Sound.BLOCK_BONE_BLOCK_HIT, SoundCategory.HOSTILE, 1.2f, 0.8f);
                if (m.maskHits == 0) { w.spawnParticle(Particle.BLOCK, ent.getLocation().add(0, 1, 0), 30, 0.4, 0.4, 0.4, 0, Material.BONE_BLOCK.createBlockData()); by.sendActionBar(ChatColor.GOLD + "You cracked the Devilfish's mask!"); }
            }
        }
        if (outer != null && REGION.containsKey(m.k.id())) outer.hurt(m, e, by);
        // ---- the Sulphur Sea's defences
        switch (m.k.id()) {
            case "flakcrab" -> { // the front shell
                if (e instanceof EntityDamageByEntityEvent ee) {
                    Vector face = m.body.getLocation().getDirection().setY(0), from = ee.getDamager().getLocation().toVector().subtract(ent.getLocation().toVector()).setY(0);
                    if (face.lengthSquared() > 1e-4 && from.lengthSquared() > 1e-4 && face.normalize().dot(from.normalize()) > 0.35) {
                        e.setDamage(e.getDamage() * (1 - cfg("flakcrab.shell", 0.6)));
                        ent.getWorld().playSound(ent.getLocation(), Sound.ITEM_SHIELD_BLOCK, SoundCategory.HOSTILE, 0.8f, 0.7f);
                    }
                }
            }
            case "trilobite" -> {
                if (m.rolled > 0) e.setDamage(e.getDamage() * 0.2);
                else if (by != null) { m.rolled = 60; ent.getWorld().playSound(ent.getLocation(), Sound.ENTITY_ARMADILLO_ROLL, SoundCategory.NEUTRAL, 1f, 1f); }
            }
            case "sludgesnail" -> { if (e instanceof EntityDamageByEntityEvent ee && ee.getDamager() instanceof org.bukkit.entity.Projectile) { e.setCancelled(true); return; } }
            case "hazardturtle" -> { if (e instanceof EntityDamageByEntityEvent ee && ee.getDamager() instanceof Player p && fair(p)) p.damage(e.getDamage() * cfg("hazardturtle.thorns", 0.3), m.body); }
            case "leech" -> { if (by != null && m.latched > 0 && by.getUniqueId().equals(m.latchedTo)) { m.latched = 0; m.latchedTo = null; Edition.vel(m.body, new Vector(0, 0.4, 0)); } }
            case "nucleartoad" -> { if (by != null) meltdown(m); }
            case "urchin" -> { if (by != null && m.cd <= 0) { m.cd = 40; spines(m); } }
            case "acidjelly" -> { if (by != null && !m.inked) { m.inked = true; acidCloud(m, ent.getLocation(), 2.2f, 80); } }
            case "isopod" -> {
                if (m.rolled > 0) e.setDamage(e.getDamage() * 0.2);
                else if (by != null) { m.rolled = 60; ent.getWorld().playSound(ent.getLocation(), Sound.ENTITY_ARMADILLO_ROLL, SoundCategory.NEUTRAL, 1f, 0.6f); }
            }
            case "leafydragon" -> { if (by != null) Bukkit.getScheduler().runTask(pl, () -> { if (m.body.isValid()) vanish(m); }); }
            case "mirrorfin" -> { // the flash
                if (by != null && m.cd <= 0) {
                    m.cd = 60;
                    World w = ent.getWorld();
                    w.spawnParticle(Particle.FLASH, ent.getLocation(), 1);
                    w.playSound(ent.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_CHIME, SoundCategory.NEUTRAL, 1.5f, 1.8f);
                    for (Player p : near(ent.getLocation(), cfg("mirrorfin.flash-range", 6))) if (fair(p)) p.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, 60, 0));
                    Edition.vel(m.body, unit(ent.getLocation().toVector().subtract(by.getLocation().toVector())).multiply(0.8));
                }
            }
            case "tubeworm" -> { if (by != null && m.cd <= 20) { m.cd = 40; Location v = ent.getLocation(); ent.getWorld().spawnParticle(Particle.BUBBLE_COLUMN_UP, v, 40, 1.2, 0.6, 1.2, 0.2);
                ent.getWorld().playSound(v, Sound.BLOCK_BUBBLE_COLUMN_UPWARDS_INSIDE, SoundCategory.HOSTILE, 2f, 0.6f);
                for (Player p : near(v, cfg("tubeworm.scald-radius", 4))) if (fair(p)) { p.damage(cfg("tubeworm.scald", 5), m.body); p.sendActionBar(ChatColor.RED + "The vent scalds you!"); } } }
            case "siren" -> { if (by != null && m.song > 0) { m.song = 0; ent.getWorld().playSound(ent.getLocation(), Sound.ENTITY_ALLAY_HURT, SoundCategory.HOSTILE, 1.5f, 0.6f); } }
            case "hagfish" -> { // slime!
                if (by != null && m.cd <= 0) {
                    m.cd = 120;
                    World w = ent.getWorld();
                    w.spawnParticle(Particle.ITEM_SLIME, ent.getLocation().add(0, 0.5, 0), 60, 1.2, 1.0, 1.2, 0.05);
                    w.playSound(ent.getLocation(), Sound.BLOCK_SLIME_BLOCK_BREAK, SoundCategory.NEUTRAL, 1.5f, 0.6f);
                    for (Player p : near(ent.getLocation(), cfg("hagfish.slime-range", 3.5))) if (fair(p)) {
                        p.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 80, 2));
                        p.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, 40, 0));
                        p.sendActionBar(ChatColor.LIGHT_PURPLE + "You're smothered in hagfish slime!");
                    }
                }
            }
            case "vampsquid" -> { // flips its cloak inside out: a cloud of glowing mucus
                if (by != null && !m.angry()) {
                    ent.getWorld().spawnParticle(Particle.GLOW, ent.getLocation().add(0, 0.5, 0), 30, 1, 1, 1, 0.05);
                    for (Player p : near(ent.getLocation(), 4)) if (fair(p)) p.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, 30, 0));
                }
            }
            default -> { }
        }
        if (by != null) {
            if (m.buried) erupt(m, by);
            if (m.k.temper() == Temper.NEUTRAL) {
                anger(m, by, 400);
                if (pl.den != null) pl.den.hurt(m, by); // the whole Den turns on you
                if (m.k.id().equals("hollowkin")) for (Beast o : live.values()) // hurt one and they all come
                    if (o.k == m.k && o.body.getLocation().distanceSquared(ent.getLocation()) < 18 * 18) anger(o, by, 400);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBite(EntityDamageByEntityEvent e) {
        Beast m = live.get(e.getDamager().getUniqueId());
        if (m == null || !(e.getEntity() instanceof Player p)) return;
        if (m.k.temper() == Temper.PASSIVE) { e.setCancelled(true); return; }
        if (pl.rep != null) pl.rep.noteHit(p);
        if (outer != null && (REGION.containsKey(m.k.id()) || m.mutated)) outer.bite(m, p, e);
        if (m.k.id().equals("hollowkin")) p.addPotionEffect(new PotionEffect(PotionEffectType.DARKNESS, 60, 0));
        if (m.k.id().equals("needlejaw")) p.addPotionEffect(new PotionEffect(PotionEffectType.POISON, 40, 0));
        switch (m.k.id()) {
            case "acideel", "skyfin", "crabling", "acidmite", "shambler" -> p.addPotionEffect(new PotionEffect(PotionEffectType.POISON, 60, 0));
            case "stormwraith" -> { p.addPotionEffect(new PotionEffect(PotionEffectType.POISON, 60, 0)); p.addPotionEffect(new PotionEffect(PotionEffectType.WEAKNESS, 60, 0)); }
            case "mauler" -> { wearArmor(p, (int) cfg("mauler.armor-wear", 10)); p.addPotionEffect(new PotionEffect(PotionEffectType.POISON, 80, 1)); p.sendActionBar(ChatColor.DARK_GREEN + "The Bile Mauler's bite rots your armour!"); }
            case "trasher" -> p.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 40, 1));
            case "bonecrawler" -> { p.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 50, 3)); p.sendActionBar(ChatColor.WHITE + "The Bone Crawler pins you!"); }
            case "palestalker" -> { p.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, 40, 0)); p.addPotionEffect(new PotionEffect(PotionEffectType.WITHER, 40, 0)); }
            case "anglerking" -> { p.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 40, 1)); p.sendActionBar(ChatColor.DARK_AQUA + "The Anglerking's jaws close round you!"); }
            case "boglurker" -> p.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 60, 3)); // rooted
            case "leech" -> { if (m.latched <= 0) { m.latched = (int) cfg("leech.hold-ticks", 80); m.latchedTo = p.getUniqueId(); } }
            case "razorcuda" -> p.addPotionEffect(new PotionEffect(PotionEffectType.WITHER, 40, 0)); // it bleeds
            case "merguard" -> { p.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 40, 1)); p.sendActionBar(ChatColor.DARK_AQUA + "The guard's net drags at you!"); }
            case "viperfang" -> p.addPotionEffect(new PotionEffect(PotionEffectType.WITHER, 60, 0));
            case "ghostshark" -> { p.addPotionEffect(new PotionEffect(PotionEffectType.POISON, 60, 0)); p.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 40, 1)); }
            case "gulper" -> { p.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 40, 2)); p.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, 25, 0)); p.sendActionBar(ChatColor.DARK_PURPLE + "The Gulper Eel's mouth closes round you!"); }
            case "swallower" -> { // steals a bite of your food
                p.addPotionEffect(new PotionEffect(PotionEffectType.HUNGER, 100, 1));
                for (int i = 0; i < p.getInventory().getSize(); i++) {
                    ItemStack it = p.getInventory().getItem(i);
                    if (it == null || !it.getType().isEdible() || it.hasItemMeta() && it.getItemMeta().hasCustomName()) continue;
                    it.setAmount(it.getAmount() - 1);
                    p.getInventory().setItem(i, it.getAmount() <= 0 ? null : it);
                    p.sendActionBar(ChatColor.DARK_GRAY + "The Black Swallower gulped down your " + pretty(it.getType()) + "!");
                    break;
                }
            }
            case "corrosionshark" -> { // its teeth eat through armour
                for (ItemStack it : p.getInventory().getArmorContents()) {
                    if (it == null || !(it.getItemMeta() instanceof org.bukkit.inventory.meta.Damageable dm) || it.getType().getMaxDurability() <= 0) continue;
                    dm.setDamage(Math.min(it.getType().getMaxDurability() - 1, dm.getDamage() + (int) cfg("corrosionshark.armor-wear", 8)));
                    it.setItemMeta(dm);
                }
                p.addPotionEffect(new PotionEffect(PotionEffectType.WEAKNESS, 80, 0));
                p.sendActionBar(ChatColor.DARK_GREEN + "The Corrosion Shark's teeth eat into your armour!");
            }
            default -> { }
        }
    }

    /** Flak globs and urchin spines landing. */
    @EventHandler(ignoreCancelled = true)
    public void onAcidHit(org.bukkit.event.entity.ProjectileHitEvent e) {
        if (!e.getEntity().getScoreboardTags().contains(ACID_TAG) || !(e.getEntity() instanceof org.bukkit.entity.Snowball)) return;
        Location at = e.getEntity().getLocation();
        at.getWorld().spawnParticle(Particle.DUST, at, 20, 0.4, 0.4, 0.4, 0, new Particle.DustOptions(Color.fromRGB(150, 220, 60), 1.5f));
        Set<String> tags = e.getEntity().getScoreboardTags();
        LivingEntity src = e.getEntity().getShooter() instanceof LivingEntity le ? le : null;
        if (tags.contains(BOMB_TAG)) { // the goblin's scrap bomb: a fizzing second and a half, then BOOM (no blocks)
            at.getWorld().spawnParticle(Particle.SMOKE, at, 20, 0.3, 0.3, 0.3, 0.02);
            at.getWorld().playSound(at, Sound.ENTITY_TNT_PRIMED, SoundCategory.HOSTILE, 1.4f, 1f);
            Bukkit.getScheduler().runTaskLater(pl, () -> {
                at.getWorld().spawnParticle(Particle.EXPLOSION_EMITTER, at, 1);
                at.getWorld().spawnParticle(Particle.BLOCK, at, 40, 1.2, 0.6, 1.2, 0.2, Material.IRON_BLOCK.createBlockData());
                at.getWorld().playSound(at, Sound.ENTITY_GENERIC_EXPLODE, SoundCategory.HOSTILE, 2f, 0.9f);
                for (Player p : near(at, cfg("goblin.bomb-radius", 3.6))) if (fair(p)) {
                    p.damage(cfg("goblin.bomb", 10), src);
                    Edition.vel(p, unit(p.getLocation().toVector().subtract(at.toVector())).multiply(0.9).setY(0.5));
                }
            }, 30L);
            return;
        }
        if (tags.contains(FLASK_TAG)) { // an acid flask shatters: a pool of acid
            at.getWorld().playSound(at, Sound.ENTITY_SPLASH_POTION_BREAK, SoundCategory.HOSTILE, 1.4f, 0.8f);
            at.getWorld().spawn(at, AreaEffectCloud.class, cl -> {
                cl.setRadius(2.6f); cl.setDuration(120); cl.setRadiusPerTick(-0.01f);
                cl.setColor(Color.fromRGB(150, 230, 60));
                cl.addCustomEffect(new PotionEffect(PotionEffectType.POISON, 80, 1), true); // (no Harming: a cloud reapplies it every second, 3 hearts each)
                cl.addScoreboardTag(PART);
            });
            return;
        }
        if (e.getHitEntity() instanceof Player p && fair(p)) {
            p.damage(cfg(tags.contains(GLOB_TAG) ? "shambler.glob" : "flakcrab.flak", tags.contains(GLOB_TAG) ? 5 : 3), src);
            p.addPotionEffect(new PotionEffect(PotionEffectType.POISON, tags.contains(GLOB_TAG) ? 80 : 60, 0));
            if (tags.contains(GLOB_TAG)) p.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 60, 1));
        }
    }

    @EventHandler
    public void onDeath(EntityDeathEvent e) {
        Beast m = live.remove(e.getEntity().getUniqueId());
        if (m == null) return;
        cleanup(m);
        e.getDrops().clear();
        e.setDroppedExp(m.k.id().equals("goblin") ? (int) cfg("goblin.xp", 300) : (int) Math.max(1, m.k.health() / 4));
        if (m.k.id().equals("goblin")) {
            Player kk = e.getEntity().getKiller();
            for (Player p : e.getEntity().getWorld().getPlayers())
                p.sendMessage(ChatColor.GOLD + "" + ChatColor.BOLD + "Grubnash the Acid Goblin " + ChatColor.YELLOW + "has been slain" + (kk != null ? " by " + kk.getName() : "") + "!");
            if (pl.acidRain != null) pl.acidRain.goblinDied(e.getEntity().getUniqueId());
        }
        Location at = e.getEntity().getLocation();
        if (m.k.id().equals("bloatfin")) { // its air sac bursts
            at.getWorld().spawnParticle(Particle.BUBBLE_POP, at, 40, 1.2, 1, 1.2, 0.1);
            for (Player p : near(at, 4.5)) { p.setRemainingAir(p.getMaximumAir()); p.addPotionEffect(new PotionEffect(PotionEffectType.WATER_BREATHING, 300, 0)); }
        }
        if (m.k.id().equals("gammaslime")) acidCloud(m, at, 2.6f, 140);
        Player killer = e.getEntity().getKiller();
        if (pl.den != null) pl.den.died(m, killer);
        if (pl.rep != null) pl.rep.killed(m, killer);
        if (m.k.id().equals("greatwhale") && pl.migration != null) pl.migration.whaleKilled(killer, at);
        if (outer != null) outer.died(m, killer, at, e);
        if (pl.tides != null) pl.tides.bigDeath(at, m.k.health()); // 1.9.0: blood in the water
        drops(m.k, e.getDrops(), killer);
        if (m.mutated) { // 1.9.0: a mutated one drops everything twice and gives double experience
            List<ItemStack> copy = new ArrayList<>();
            for (ItemStack it : e.getDrops()) copy.add(it.clone());
            e.getDrops().addAll(copy);
            e.setDroppedExp(e.getDroppedExp() * 2);
            if (killer != null) pl.console("index stat " + killer.getName() + " mutated_sea 1");
        }
    }

    /** Each creature's loot: a little vanilla stuff, its rare bits (through the other plugins' commands), a Dark Loot Bag now and then. */
    void drops(Kind k, List<ItemStack> drops, Player killer) {
        if (outer != null && outer.drops(k, drops, killer)) return;
        switch (k.id()) {
            case "gawker" -> drops.add(new ItemStack(Material.COD, 1 + random.nextInt(2)));
            case "bloatfin" -> drops.add(new ItemStack(Material.SALMON, 1));
            case "jellywing" -> { drops.add(new ItemStack(Material.GLOW_INK_SAC, 1 + random.nextInt(2))); }
            case "driftback" -> { drops.add(new ItemStack(Material.PRISMARINE_CRYSTALS, 6 + random.nextInt(8))); drops.add(new ItemStack(Material.SEA_LANTERN, 2 + random.nextInt(3))); drops.add(new ItemStack(Material.NAUTILUS_SHELL, 1 + random.nextInt(2))); rare(killer, "driftback.bag", 0.15, "darkloot bag 1 %p"); }
            case "clam" -> {
                drops.add(new ItemStack(Material.PRISMARINE_SHARD, 1 + random.nextInt(3)));
                rare(killer, "clam.shield-of-the-ocean", 0.01, "giveaccessory shield 1 %p");
                rare(killer, "clam.glistening-pearl", 0.02, "grandmaw item glistening_pearl 1 %p");
            }
            case "lumenbell" -> drops.add(new ItemStack(Material.GLOW_INK_SAC, 1));
            case "gasbloom" -> { drops.add(new ItemStack(Material.SLIME_BALL, 1 + random.nextInt(2))); drops.add(new ItemStack(Material.FERMENTED_SPIDER_EYE, 1)); }
            case "fangstalker" -> { drops.add(new ItemStack(Material.BONE, 1 + random.nextInt(3))); rare(killer, "fangstalker.abyssal-lure", 0.01, "leviathan item abyssal_lure 1 %p"); }
            case "blastfin" -> drops.add(new ItemStack(Material.GUNPOWDER, 1 + random.nextInt(2)));
            case "burrowshark" -> { drops.add(new ItemStack(Material.SAND, 2 + random.nextInt(4))); drops.add(new ItemStack(Material.BONE, 1 + random.nextInt(2))); }
            case "cavecrawler" -> drops.add(new ItemStack(Material.STRING, 1 + random.nextInt(2)));
            case "glowgrub" -> drops.add(new ItemStack(Material.GLOW_BERRIES, 1 + random.nextInt(3)));
            case "shellsnake" -> { drops.add(new ItemStack(Material.TURTLE_SCUTE, 1)); drops.add(new ItemStack(Material.NAUTILUS_SHELL, random.nextInt(2))); }
            case "lampsquid" -> { drops.add(new ItemStack(Material.GLOW_INK_SAC, 2 + random.nextInt(3))); rare(killer, "lampsquid.inkjaw-bait", 0.02, "inkjaw item inkjaw_bait 1 %p"); }
            case "volteel" -> { drops.add(new ItemStack(Material.COPPER_INGOT, 1 + random.nextInt(3))); drops.add(new ItemStack(Material.REDSTONE, 2 + random.nextInt(3))); }
            case "needlejaw" -> drops.add(new ItemStack(Material.PRISMARINE_SHARD, 1 + random.nextInt(2)));
            case "devilfish" -> { drops.add(new ItemStack(Material.BONE_BLOCK, 1)); rare(killer, "devilfish.brine-bait", 0.01, "scourge item brine_bait 1 %p"); }
            case "riftwarper" -> { drops.add(new ItemStack(Material.ENDER_PEARL, 1 + random.nextInt(2))); rare(killer, "riftwarper.umbral-sonar", 0.01, "umbral item umbral_sonar 1 %p"); }
            case "hollowkin" -> { if (random.nextDouble() < 0.02) drops.add(new ItemStack(Material.ECHO_SHARD, 1)); rare(killer, "hollowkin.mournful-shell", 0.015, "lament item mournful_shell 1 %p"); }
            case "sharkquid" -> { drops.add(new ItemStack(Material.INK_SAC, 1 + random.nextInt(3))); drops.add(new ItemStack(Material.BONE, 1)); }
            // the Sulphur Sea: the Brine Scourge's bait comes from its own waters now
            case "acideel" -> { drops.add(new ItemStack(Material.SLIME_BALL, 1 + random.nextInt(2))); rare(killer, "acideel.brine-bait", 0.01, "scourge item brine_bait 1 %p"); }
            case "trasher" -> { drops.add(new ItemStack(Material.LEATHER, 2 + random.nextInt(3))); drops.add(new ItemStack(Material.BONE, 1 + random.nextInt(2))); rare(killer, "trasher.brine-bait", 0.03, "scourge item brine_bait 1 %p"); }
            case "radiator" -> drops.add(new ItemStack(Material.GLOWSTONE_DUST, 1 + random.nextInt(3)));
            case "flakcrab" -> { drops.add(new ItemStack(Material.COPPER_INGOT, 1 + random.nextInt(3))); drops.add(new ItemStack(Material.GUNPOWDER, 1)); }
            case "nucleartoad" -> { drops.add(new ItemStack(Material.SLIME_BALL, 1)); drops.add(new ItemStack(Material.FERMENTED_SPIDER_EYE, 1)); }
            case "gammaslime" -> { drops.add(new ItemStack(Material.SLIME_BALL, 2 + random.nextInt(3))); drops.add(new ItemStack(Material.GLOWSTONE_DUST, 1)); }
            case "sulphurskater" -> { drops.add(new ItemStack(Material.STRING, 1 + random.nextInt(2))); drops.add(new ItemStack(Material.YELLOW_DYE, 1)); }
            case "acidjelly" -> { drops.add(new ItemStack(Material.SLIME_BALL, 1)); drops.add(new ItemStack(Material.GLOW_INK_SAC, 1)); }
            case "orthocera" -> { drops.add(new ItemStack(Material.INK_SAC, 2)); if (random.nextDouble() < 0.3) drops.add(new ItemStack(Material.NAUTILUS_SHELL, 1)); }
            case "trilobite" -> { drops.add(new ItemStack(Material.BONE_MEAL, 2)); if (random.nextDouble() < 0.2) drops.add(new ItemStack(Material.TURTLE_SCUTE, 1)); }
            case "skyfin" -> { drops.add(new ItemStack(Material.PHANTOM_MEMBRANE, 1)); drops.add(new ItemStack(Material.COD, 1)); }
            case "leech" -> drops.add(new ItemStack(Material.REDSTONE, 1 + random.nextInt(2)));
            case "boglurker" -> { drops.add(new ItemStack(Material.DARK_OAK_LOG, 2 + random.nextInt(3))); drops.add(new ItemStack(Material.MOSS_BLOCK, 1)); }
            case "corrosionshark" -> { drops.add(new ItemStack(Material.BONE, 2 + random.nextInt(3))); drops.add(new ItemStack(Material.IRON_NUGGET, 3 + random.nextInt(4))); rare(killer, "corrosionshark.brine-bait", 0.04, "scourge item brine_bait 1 %p"); }
            case "urchin" -> { drops.add(new ItemStack(Material.PRISMARINE_SHARD, 2)); drops.add(new ItemStack(Material.YELLOW_DYE, 1)); }
            case "crabling" -> drops.add(new ItemStack(Material.BONE_MEAL, 1));
            case "bloatgut" -> { drops.add(new ItemStack(Material.PUFFERFISH, 1)); drops.add(new ItemStack(Material.YELLOW_DYE, 1)); }
            case "sulphurmoth" -> drops.add(new ItemStack(Material.GLOWSTONE_DUST, 1 + random.nextInt(2)));
            case "hazardturtle" -> { drops.add(new ItemStack(Material.TURTLE_SCUTE, 1 + random.nextInt(2))); drops.add(new ItemStack(Material.GLOWSTONE_DUST, 2 + random.nextInt(3))); }
            case "sludgesnail" -> drops.add(new ItemStack(Material.SLIME_BALL, 2 + random.nextInt(3)));
            case "shambler" -> { drops.add(new ItemStack(Material.ROTTEN_FLESH, 1 + random.nextInt(2))); drops.add(new ItemStack(Material.SLIME_BALL, 1 + random.nextInt(2))); }
            case "rustbones" -> { drops.add(new ItemStack(Material.BONE, 1 + random.nextInt(2))); drops.add(new ItemStack(Material.ARROW, 2 + random.nextInt(4))); drops.add(new ItemStack(Material.COPPER_INGOT, 1)); }
            case "acidmite" -> { if (random.nextInt(3) == 0) drops.add(new ItemStack(Material.GLOWSTONE_DUST, 1)); }
            case "stormwraith" -> { drops.add(new ItemStack(Material.PHANTOM_MEMBRANE, 1)); drops.add(new ItemStack(Material.LIME_DYE, 1 + random.nextInt(2))); }
            case "mauler" -> { drops.add(new ItemStack(Material.BONE, 3 + random.nextInt(3))); drops.add(new ItemStack(Material.PRISMARINE_CRYSTALS, 2 + random.nextInt(3))); rare(killer, "mauler.brine-bait", 0.05, "scourge item brine_bait 1 %p"); }
            case "sailfin" -> { drops.add(new ItemStack(Material.COD, 2 + random.nextInt(2))); drops.add(new ItemStack(Material.PRISMARINE_SHARD, 1 + random.nextInt(2))); rare(killer, "sailfin.abyssal-lure", 0.01, "leviathan item abyssal_lure 1 %p"); }
            case "mantaglider" -> { drops.add(new ItemStack(Material.LEATHER, 3 + random.nextInt(3))); drops.add(new ItemStack(Material.PRISMARINE_CRYSTALS, 4 + random.nextInt(5))); rare(killer, "mantaglider.bag", 0.1, "darkloot bag 1 %p"); }
            case "leafydragon" -> { drops.add(new ItemStack(Material.KELP, 2 + random.nextInt(3))); if (random.nextDouble() < 0.3) drops.add(new ItemStack(Material.SEA_PICKLE, 1)); }
            case "pearlhorse" -> { drops.add(new ItemStack(Material.PRISMARINE_CRYSTALS, 1 + random.nextInt(2))); if (random.nextDouble() < cfg("pearlhorse.heart-chance", 0.005)) drops.add(new ItemStack(Material.HEART_OF_THE_SEA, 1)); }
            case "hammerjaw" -> { drops.add(new ItemStack(Material.BONE, 2 + random.nextInt(2))); drops.add(new ItemStack(Material.LEATHER, 1 + random.nextInt(2))); rare(killer, "hammerjaw.abyssal-lure", 0.015, "leviathan item abyssal_lure 1 %p"); }
            case "razorcuda" -> { drops.add(new ItemStack(Material.COD, 1)); if (random.nextBoolean()) drops.add(new ItemStack(Material.BONE, 1)); }
            case "stingray" -> { drops.add(new ItemStack(Material.LEATHER, 1 + random.nextInt(2))); drops.add(new ItemStack(Material.SPIDER_EYE, 1)); }
            case "mirrorfin" -> { drops.add(new ItemStack(Material.QUARTZ, 1 + random.nextInt(2))); drops.add(new ItemStack(Material.SALMON, 1)); }
            case "kelpstrangler" -> { drops.add(new ItemStack(Material.KELP, 4 + random.nextInt(5))); drops.add(new ItemStack(Material.SLIME_BALL, 1)); }
            case "mermaid" -> { drops.add(new ItemStack(Material.PRISMARINE_CRYSTALS, 2 + random.nextInt(3))); if (random.nextDouble() < 0.25) drops.add(new ItemStack(Material.NAUTILUS_SHELL, 1)); drops.add(new ItemStack(Material.SEA_PICKLE, 1 + random.nextInt(2))); }
            case "merguard" -> { drops.add(new ItemStack(Material.PRISMARINE_SHARD, 2 + random.nextInt(3))); if (random.nextDouble() < cfg("merguard.trident-chance", 0.05)) drops.add(new ItemStack(Material.TRIDENT, 1)); rare(killer, "merguard.bag", 0.01, "darkloot bag 1 %p"); }
            case "siren" -> { drops.add(new ItemStack(Material.PRISMARINE_CRYSTALS, 2 + random.nextInt(3))); if (random.nextDouble() < 0.3) drops.add(new ItemStack(Material.NAUTILUS_SHELL, 1)); }
            case "gulper" -> { drops.add(new ItemStack(Material.INK_SAC, 1 + random.nextInt(2))); drops.add(new ItemStack(Material.BONE, 1)); rare(killer, "gulper.inkjaw-bait", 0.015, "inkjaw item inkjaw_bait 1 %p"); }
            case "isopod" -> { drops.add(new ItemStack(Material.BONE_MEAL, 2 + random.nextInt(2))); drops.add(new ItemStack(Material.IRON_NUGGET, 1 + random.nextInt(3))); if (random.nextDouble() < 0.3) drops.add(new ItemStack(Material.TURTLE_SCUTE, 1)); }
            case "ghostshark" -> { drops.add(new ItemStack(Material.BONE, 1 + random.nextInt(2))); if (random.nextDouble() < 0.1) drops.add(new ItemStack(Material.GHAST_TEAR, 1)); }
            case "vampsquid" -> { drops.add(new ItemStack(Material.INK_SAC, 2)); drops.add(new ItemStack(Material.REDSTONE, 1 + random.nextInt(2))); rare(killer, "vampsquid.mournful-shell", 0.015, "lament item mournful_shell 1 %p"); }
            case "dumbo" -> drops.add(new ItemStack(Material.GLOW_INK_SAC, 1 + random.nextInt(2)));
            case "swallower" -> drops.add(new ItemStack(random.nextBoolean() ? Material.COOKED_COD : Material.COOKED_SALMON, 1 + random.nextInt(2))); // what it swallowed
            case "chainlight" -> { drops.add(new ItemStack(Material.GLOW_INK_SAC, 2)); drops.add(new ItemStack(Material.PRISMARINE_CRYSTALS, 1 + random.nextInt(2))); }
            case "viperfang" -> { drops.add(new ItemStack(Material.PRISMARINE_SHARD, 1 + random.nextInt(2))); rare(killer, "viperfang.umbral-sonar", 0.01, "umbral item umbral_sonar 1 %p"); }
            case "hagfish" -> drops.add(new ItemStack(Material.SLIME_BALL, 2 + random.nextInt(3)));
            case "watcher" -> { drops.add(new ItemStack(Material.ENDER_EYE, 1)); if (random.nextDouble() < 0.05) drops.add(new ItemStack(Material.ECHO_SHARD, 1)); rare(killer, "watcher.umbral-sonar", 0.02, "umbral item umbral_sonar 1 %p"); }
            case "hadalsnail" -> { drops.add(new ItemStack(Material.COD, 1)); drops.add(new ItemStack(Material.BONE_MEAL, 1 + random.nextInt(2))); }
            case "anglerking" -> { drops.add(new ItemStack(Material.GLOW_INK_SAC, 2 + random.nextInt(3))); drops.add(new ItemStack(Material.PRISMARINE_CRYSTALS, 2 + random.nextInt(3))); rare(killer, "anglerking.bag", 0.05, "darkloot bag 1 %p"); }
            case "bonecrawler" -> { drops.add(new ItemStack(Material.BONE, 2 + random.nextInt(3))); if (random.nextDouble() < 0.2) drops.add(new ItemStack(Material.BONE_BLOCK, 1)); }
            case "palestalker" -> { drops.add(new ItemStack(Material.BONE, 1 + random.nextInt(2))); drops.add(new ItemStack(Material.PHANTOM_MEMBRANE, 1)); if (random.nextDouble() < 0.04) drops.add(new ItemStack(Material.ECHO_SHARD, 1)); }
            case "crushjelly" -> { drops.add(new ItemStack(Material.GLOW_INK_SAC, 1 + random.nextInt(2))); drops.add(new ItemStack(Material.AMETHYST_SHARD, 1 + random.nextInt(2))); }
            case "tubeworm" -> { drops.add(new ItemStack(Material.REDSTONE, 1 + random.nextInt(3))); drops.add(new ItemStack(Material.MAGMA_CREAM, 1)); }
            case "greatwhale" -> { drops.add(new ItemStack(Material.LEATHER, 6 + random.nextInt(6))); drops.add(new ItemStack(Material.BONE_BLOCK, 3 + random.nextInt(4))); drops.add(new ItemStack(Material.PRISMARINE_CRYSTALS, 6 + random.nextInt(6))); }
            case "goblin" -> { // the mini boss's hoard
                drops.add(new ItemStack(Material.EMERALD, 6 + random.nextInt(7)));
                drops.add(new ItemStack(Material.GOLD_INGOT, 4 + random.nextInt(5)));
                drops.add(new ItemStack(Material.TNT, 2 + random.nextInt(3)));
                drops.add(new ItemStack(Material.SPLASH_POTION, 1));
                if (killer != null && !killer.getScoreboardTags().contains("faultline_boss_rush")) { // (no loot in a Boss Rush)
                    pl.console("darkloot bag " + (int) cfg("goblin.bags", 2) + " " + killer.getName());
                    rare(killer, "goblin.rare", 0.1, "darkloot rare %p 1");
                    pl.console("index stat " + killer.getName() + " acid_goblins 1");
                }
            }
            default -> { }
        }
        if (killer != null && k.zone() == DeepGenerator.Zone.TRENCH) rare(killer, "dark-loot-bag-trench", 0.025, "darkloot bag 1 %p");
        else if (killer != null && k.zone() == DeepGenerator.Zone.SULPHUR) rare(killer, "dark-loot-bag-sulphur", 0.008, "darkloot bag 1 %p");
        else if (killer != null && k.zone() != DeepGenerator.Zone.SUNLIT) rare(killer, "dark-loot-bag", 0.01, "darkloot bag 1 %p");
        else if (killer != null) rare(killer, "dark-loot-bag-sunlit", 0.004, "darkloot bag 1 %p");
    }

    /** A rare drop: a console command for the killer (the item comes from the plugin that owns it). */
    void rare(Player killer, String path, double def, String cmd) {
        if (killer == null || random.nextDouble() >= cfg("drops." + path, def)) return;
        pl.console(cmd.replace("%p", killer.getName()));
    }

    /** Rustbones' sulphur-dipped arrows. */
    @EventHandler(ignoreCancelled = true)
    public void onShoot(org.bukkit.event.entity.EntityShootBowEvent e) {
        Beast m = live.get(e.getEntity().getUniqueId());
        if (m == null || !m.k.id().equals("rustbones") || !(e.getProjectile() instanceof org.bukkit.entity.Arrow ar)) return;
        try { ar.addCustomEffect(new PotionEffect(PotionEffectType.POISON, 80, 1), true); } catch (RuntimeException ignored) { }
        ar.setColor(Color.fromRGB(170, 255, 80));
        ar.setPickupStatus(org.bukkit.entity.AbstractArrow.PickupStatus.DISALLOWED);
    }

    @EventHandler(ignoreCancelled = true)
    public void onTransform(EntityTransformEvent e) { if (live.containsKey(e.getEntity().getUniqueId())) e.setCancelled(true); }

    @EventHandler(ignoreCancelled = true)
    public void onStandUse(PlayerInteractAtEntityEvent e) { if (ours(e.getRightClicked())) e.setCancelled(true); }

    /** The Clam is a shulker underneath: shulkers hop away when hurt. It stays put on its patch of sand. */
    @EventHandler(ignoreCancelled = true)
    public void onHop(org.bukkit.event.entity.EntityTeleportEvent e) {
        Beast m = live.get(e.getEntity().getUniqueId());
        if (m != null && m.k.base() == EntityType.SHULKER) e.setCancelled(true);
    }

    /** The Trilobite is a silverfish underneath: no hiding in stone, no waking others in the walls. */
    @EventHandler(ignoreCancelled = true)
    public void onBlock(org.bukkit.event.entity.EntityChangeBlockEvent e) { if (live.containsKey(e.getEntity().getUniqueId())) e.setCancelled(true); }

    /** The Gamma Slime doesn't split (its puddle is its death). */
    @EventHandler(ignoreCancelled = true)
    public void onSplit(org.bukkit.event.entity.SlimeSplitEvent e) {
        if (live.containsKey(((org.bukkit.event.entity.EntityEvent) e).getEntity().getUniqueId())) e.setCancelled(true);
    }

    /** The Gawker and the Bloatfin are a cod and a salmon underneath: no scooping them up (out came an invisible fish). */
    @EventHandler(ignoreCancelled = true)
    public void onBucket(org.bukkit.event.player.PlayerBucketEntityEvent e) { if (ours(e.getEntity())) e.setCancelled(true); }

    /** Nor leashing, trading, riding or naming them: they're not what they look like underneath. */
    @EventHandler(ignoreCancelled = true)
    public void onUse(org.bukkit.event.player.PlayerInteractEntityEvent e) {
        Beast m = live.get(e.getRightClicked().getUniqueId());
        if (m == null) return;
        e.setCancelled(true);
        // the Den's people: gifts and trades (1.8.0, DenRep)
        if (e.getHand() == EquipmentSlot.HAND && pl.rep != null && pl.den != null && pl.den.resident(m) && (m.k.id().equals("mermaid") || m.k.id().equals("merguard")))
            pl.rep.click(e.getPlayer(), m);
    }

    @EventHandler(ignoreCancelled = true)
    public void onRob(PlayerArmorStandManipulateEvent e) { if (ours(e.getRightClicked())) e.setCancelled(true); }

    @EventHandler
    public void onUnload(ChunkUnloadEvent e) {
        for (Entity en : e.getChunk().getEntities()) {
            Beast m = live.get(en.getUniqueId());
            if (m != null) remove(m);
            else if (en.getScoreboardTags().contains(PART) || en.getScoreboardTags().contains(STAND)) en.remove();
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        boolean b = Edition.bedrock(p);
        for (Beast m : live.values()) if (m.stand != null && m.stand.isValid()) { if (b) p.showEntity(pl, m.stand); else p.hideEntity(pl, m.stand); }
    }

    /** In the Dark Ocean only sea life spawns on its own (no phantoms, no bats, nothing from the land). */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onVanillaSpawn(CreatureSpawnEvent e) {
        if (pl.world == null || !e.getEntity().getWorld().equals(pl.world)) return;
        if (spawning) return; // one of ours (a Skyfin is a phantom underneath)
        CreatureSpawnEvent.SpawnReason r = e.getSpawnReason();
        if (r == CreatureSpawnEvent.SpawnReason.CUSTOM || r == CreatureSpawnEvent.SpawnReason.COMMAND || r == CreatureSpawnEvent.SpawnReason.SPAWNER_EGG
                || r == CreatureSpawnEvent.SpawnReason.DEFAULT || r == CreatureSpawnEvent.SpawnReason.BUCKET || r == CreatureSpawnEvent.SpawnReason.BREEDING
                || r == CreatureSpawnEvent.SpawnReason.EGG) {
            if (e.getEntityType() != EntityType.PHANTOM || r == CreatureSpawnEvent.SpawnReason.COMMAND || r == CreatureSpawnEvent.SpawnReason.SPAWNER_EGG) return;
        }
        if (!SEA_LIFE.contains(e.getEntityType())) e.setCancelled(true);
    }

    static final Set<EntityType> SEA_LIFE = EnumSet.of(EntityType.COD, EntityType.SALMON, EntityType.TROPICAL_FISH, EntityType.PUFFERFISH,
            EntityType.SQUID, EntityType.GLOW_SQUID, EntityType.DOLPHIN, EntityType.TURTLE, EntityType.AXOLOTL, EntityType.DROWNED,
            EntityType.GUARDIAN, EntityType.ELDER_GUARDIAN, EntityType.TADPOLE);

    // =========================================================================================== eggs (admins, /itemsmenu)
    ItemStack egg(Kind k) {
        ItemStack it = new ItemStack(Material.PAPER);
        ItemMeta m = it.getItemMeta();
        m.setDisplayName(k.color() + "" + ChatColor.BOLD + k.name() + ChatColor.GRAY + " Spawn Egg");
        m.setLore(List.of(ChatColor.GRAY + k.temper().name().substring(0, 1) + k.temper().name().substring(1).toLowerCase() + ", " + zoneName(k.zone()),
                ChatColor.DARK_GRAY + "Right-click in water to release it."));
        m.setItemModel(new NamespacedKey("faultline", "deep/" + k.id() + "_icon"));
        m.getPersistentDataContainer().set(eggKey, PersistentDataType.STRING, k.id());
        it.setItemMeta(m);
        return it;
    }

    static String zoneName(DeepGenerator.Zone z) {
        return switch (z) { case SUNLIT -> "the Sunlit Sea"; case CAVES -> "the caves of the Crust"; case ABYSS -> "the Abyss"; case SULPHUR -> "the Sulphur Sea"; case TRENCH -> "the Trench"; };
    }

    String eggOf(ItemStack it) {
        if (it == null || !it.hasItemMeta()) return null;
        return it.getItemMeta().getPersistentDataContainer().get(eggKey, PersistentDataType.STRING);
    }

    void shutdown() {
        for (Beast m : new ArrayList<>(live.values())) remove(m);
        live.clear();
    }
}
