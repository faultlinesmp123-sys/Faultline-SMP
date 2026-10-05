# The Lost Explorer (final boss)

Inspired by the Roaring Knight (Deltarune). The last, hardest boss. **Status: built.** Model `tools/explorer_models.py`,
animations `ExplorerAnims.java`, the fight `Explorer.java` (config `explorer:`, admin `/explorer`), the way there `Below.java`.
At the end of the path he stands on top of a huge black pillar, digging into it, his tiger sitting below; walk into the
arena (within 15 blocks) or right-click the tiger to start.

## Look
Completely black plate armor, an imposing knight, detailed like Elden Ring's Godfrey but all in black. You can't see his
face at all: inside the helm there's only darkness. Great helm with a low crested dome inside a tall circlet of blade-like points (tallest at the front) and swept-back
horns, a wild black mane down his back, a fur mantle, a high collar, engraved scrollwork on every plate, clean scrollwork (double borders, open curls, a lozenge; no blobs), his emblem
(three claw slashes) on a medallion on the chest (and on the belt, knees and mantle clasps), a cloth tabard, huge pauldrons with fur ruffs and
tapered spikes raked outward, clawed gauntlets, and a long torn cape.
- Rig: `explorer_{head,body,arm_r,arm_l,leg_r,leg_l}` (same joints as Jacob/Don, so `renderRig` draws it), texture `explorer_armor`.
- Weapons: `explorer_axe` (a great axe with double crescent heads and white edges) and `explorer_blade` (greatsword).
- **His black tiger** (fight 1): standing, in 8 pieces `explorer_tiger_{body,head,jaw,tail,leg_fr,leg_fl,leg_br,leg_bl}`,
  texture `explorer_tiger`, joints in `TIGER_JOINT` (script) = `T_*_J` in Explorer.java = `render_anims.py`. Its poses are
  12-number arrays in `ExplorerAnims` (`tigerIdle/Sit/Walk/Roar/Crouch/Leap/Land/Swipe/Flinch/Die`); preview them with
  `tools/anim/preview.sh <pack> <out> tiger_walk tiger_die ...`.
- Previews: `tools/previews/explorer*.png` (`explorer_scene.png` is him with the axe and the tiger behind him).
  All of these are in FaultlineSMP.zip (the statue at the end of the path uses the rig and the axe).

## The fight (BUILT: `Explorer.java` + `ExplorerAnims.java`)
From the owner: the tiger first (2,000 health, swords work, 4 hits to down you), then him. He's fast and fights exactly like
the Roaring Knight; his hits are randomly a one-shot or a two-shot; he charges after every 15 moves (into a pillar = the
pickaxe trick); 4 phases. Nobody dies, they kneel. If everyone's down he talks for 30 s about the kingdom Below the Bedrock
(it was beautiful, it fell; hint that its lost king ruined it, never say it; Diamond Jacob was its general), then "Get out of
my sight", kicks them out and the void closes (find Swarm again). Hitting him with a weapon slashes you to half a heart.

How it's built:
- **Solo** (from the owner): only the player who starts it (walks in first / `begin(w, player)`) is a fighter. Every 5 ticks
  any other survival/adventure player within 20.5 blocks of the center is teleported back onto the path (z = ARENA_Z-24.5)
  with "Someone is already facing him". Non-fighters can't hurt the tiger, don't get the boss bar, the Skip / Yes / No
  buttons, the kick or the `paid` mark. A retry ("Yes") keeps the same fighter.
- **His pillar** (`BIG_H` 12 tall, 3x3 deepslate bricks, tiles top and bottom, at the arena center): always there except while
  he fights on the floor; `ensureBigPillar` puts it back (statue spawn, fight start, after the fight). The statue (`Below.Statue`,
  ticked every tick) is him on top, back to the path, digging with the black pickaxe (`ExplorerAnims.dig`), and the tiger
  sitting at its foot (`tigerSeat`). A survival/adventure player within 15 blocks of the center starts the fight.
- **Cutscene 1** (`INTRO`, ~11 s): walls (barriers on every void column touching the arena floor, from floor level up 6, plus across the path just outside the circle: no gaps; players where they go up are moved in) at 0.5 s (they hug the floor edge block by block: every void column touching the arena floor, floor level up, and across the path just outside the circle); he
  stops digging, straightens, turns to you; "...", "Who are you?", "You're not from this world."; the finger snap (left hand,
  click + spell sound) wakes the tiger: it rises, roars, bounds off the dais. Music (Black Knife) starts.
- **The tiger** (`TIGER`): `tiger.health` 2000 (tracked by the plugin; the slime hitbox has tag `faultline_explorer_tiger`; any
  melee/projectile damage from a fighter counts), boss bar. Moves: prowl (circles you up close), Swipe (paw rake, cone 3.8,
  2 in a row below half health), Pounce (crouch + red circle, then a leap: radius 2.6), Roar (throws you back, Slowness II).
  Every hit = `tiger.damage-share` 0.26 of max health (4 hits). He watches from the pillar.
- **Cutscene 2** (`TIGER_DEATH`, ~12 s): the tiger staggers, collapses, rolls onto its side, turns to ash. Music stops.
  "...", "Useless.", "Completely useless.", "You shall die." (roar, music restarts). He drops off the pillar onto the floor,
  the pillar sinks layer by layer, the four 3x3 fighting pillars (±7, ±7) rise.
- **Damage**: his hits take `damage.hit` 0.26 of max health (4 hits; `one-shot-chance` 0, set it above 0 for random
  one-shots); the tiger's 0.26 too. Taken straight off health (absorption first): armor, Protection and Resistance don't change the count. Hits within
  8 ticks of each other count once. Any hit that would kill a fighter in the void world instead **downs** them: kneeling, frozen
  (speed/jump -100%), can't be hurt, can't use items, eat, pearl or chorus out, keeps everything. Totems aren't used up.
- **His moves** (Roaring Knight style; everything runs on a sped-up clock: `speed` 1.1, x1.12/1.25/1.4 in phases 2-4):
  - Phase 1: **Slash** (a rising slash; 3 white lines across the arena, one through each player, turning red, then they cut:
    anyone within 1.1 of a line is hit), **Dash** (2 dashes straight through you, red line first, 1.7 b/tick), Cleave and
    Sweep up close.
  - Phase 2: + **Cross** (an X through you, then a + over it), **Ring** (8 swords appear around you, hang there, then thrust in:
    run out of the ring), 4 slash lines, 3 dashes.
  - Phase 3: the greatsword: + **Starburst** (3 waves of 12 blades fly out from him, rotated each wave: stand in the gaps),
    Throw (boomerang), Shadow Step (behind you), slash lines cut one after another.
  - Phase 4: + **Sword Rain** (blades fall on marked spots around each player), 6 slash lines, 4 dashes, double rings, 16-blade waves.
- **The charge**: after `charge-after-moves` (15) moves ("He's gathering himself..." one move before), a roar, a red line
  locking on, then a bull rush (`charge-speed` 1.1). Into a pillar: stunned for `stun-seconds` (8, 1 less each phase); 3
  pickaxe hits on that pillar topple it onto him (pinned, next phase; the 4th pillar ends it). Missed: he's ready to charge
  again 4 moves later.
- **Losing** (every fighter down, in either fight): ~30 s (`loss-speech-seconds`) of 9 lines about the kingdom Below the
  Bedrock (`LORE` in Explorer.java). A clickable **[ Skip ]** in chat (`/lostexplorer skip`, no permission needed, any fighter)
  cuts it short. Then (skipped or not) "Do you want to do it again..?" as a title plus **[ Yes ] [ No ]** in chat
  (`/lostexplorer yes|no`, the first fighter to click decides, `again-seconds` 15). **Yes**: the fight restarts at once, everyone
  back up, walls up, straight into the snap and the tiger ("Again, then."). **No** or no answer: "Get out of my sight.", the kick,
  everyone in the void sent home, the hole sealed at once; fighters are marked `paid` (Swarm won't ask for another Fist).
- **Winning**: "...", "So. You found the way after all.", "Go on. It's yours now.", he crumbles into ash. Each fighter
  (from the owner): `rewards.mythic-bags` (32), `rewards.xp` (3000), `rewards.netherite-blocks` (5, dropped locked to
  them) and the **Mirror..??** (`rewards.mirror`, FaultlineItems `/givelostmirror`), plus the Index entry. Server broadcast.
- **Mirror..??** (accessory, `MirrorGear.java` in FaultlineItems, key `faultlineitems:lost_mirror`, model
  `faultline:lost_mirror`): a hit that would take a third of your max health or more (`mirror.min-hit-share`) is cancelled
  and thrown back at whoever dealt it (up to `mirror.max-reflect` 30), then a 45 s cooldown (`mirror.cooldown-seconds`).
  The reflected hit counts as yours (boss counters like Rocco's Payback see it). It doesn't work against the Explorer
  himself (his hits aren't normal damage). Art: `tools/explorer_models.py` (a black hand mirror with his helm in the glass).
- **Music**: Black Knife (Deltarune), `sounds/explorer/music.ogg` (2:02, `explorer.music.length-seconds: 122`), from the
  snap through the tiger fight, then again from "You shall die.", looped for players within 30 blocks; silent when everyone's
  down and when he's beaten.
- No boss form (`/bossmorph`): the fight is tied to its arena.
- Crash leftovers (walls/pillars) are cleaned when the arena's chunks load and when a fight starts. Players standing where
  the wall rises are moved inside.
- Bedrock players see a big wither skeleton (and a ravager for the tiger) through `proxy`.
- Admin: `/explorer start` (in the void), `stop`, `skip` (past the cutscene / kills the tiger), `phase <1-4>` (straight to
  him on the floor), `stun` (crash into the nearest pillar), `reset` (back on his pillar now), `arena [player]` (on the path
  just short of the arena; the `/itemsmenu` entry uses it).
- Tested with MockBukkit: walking in → cutscene 1 lines → tiger (2000 hp, sword damage counts, 0.26 per hit) → cutscene 2
  (Useless / You shall die, pillar sinks, 4 pillars rise) → slashes/dashes → the charge after 15 moves → 4 hits to go down →
  4 pillars → defeat, cleanup, his pillar back; a loss during the tiger fight → the 30 s speech (mentions Diamond Jacob, never
  says he was the king) → kicked home; phase 1 and 4 run a minute each without errors (up to 48 blades in the air).

## Getting there (BUILT: `plugins/FaultlineBosses/.../Below.java`, config `below:`, admin `/below`)
1. Explore between **y = -1 and y = -50**. Every minute there's a **2% chance** to meet **Swarm**, a citizen of the city
   **Below the Bedrock**.
2. Talk to Swarm. To be let in you must have **killed Diamond Jacob**, and **give him a Vendetta Fist** as an offering.
3. Swarm (advanced animations) turns around and **digs a staircase** down for you. It's covered up again after 10 minutes.
4. At bedrock he pulls out a new pickaxe: "This is a pickaxe you guys don't know..." A fully black pickaxe with strange
   enchanting. He digs through the bedrock into the void.
5. Jump in: you land in a new world. Everything is black except a white path. Walk it, and you find the Lost Explorer.

How it's built:
- **Swarm** (`swarm_*` rig, art by `tools/below_models.py`): hooded, ash-grey skin, pale glowing eyes, scarf, bandaged
  arms, ragged teal cloak, a little amber lantern on his belt. Bedrock players see a Wandering Trader stand-in. Spawns
  5-10 blocks away (preferably behind you) for players in survival between `swarm.min-y`/`max-y` with no sky light, one
  Swarm at a time, then not again for that player for 10 minutes. Waits 5 minutes. Can't be hurt. Talks in chat.
- **Requirements**: killed Jacob = recorded when Jacob's rewards are handed out (`below.yml` → `jacob-slayers`), or, for
  kills from before this update, Jacob unlocked in the Faultline Index (`FaultlineIndex/progress.yml`). The Fist must be in
  the main hand; he keeps it. **Creative players skip both checks** (for testing).
- **Staircase**: away from whoever paid, one step per swing (~0.9 s), 1 wide and 3 tall, down to y = min+5 (-59). Lava
  and water next to it are plugged with deepslate, loose gravel/sand above is replaced, missing floors get deepslate, and
  a light block every 4 steps. Chests/spawners/signs (tile entities) are left alone.
- **Bedrock**: a landing to the side, then he draws the black pickaxe (`below_pickaxe`, with the enchant glint) and
  smashes a 2x2 hole through every bedrock layer. Falling below the bottom of the world within 4 blocks of the hole sends
  you to the void world. Anyone can follow (the checks are only for whoever talks to him).
- **Sealing**: after `stairs-open-seconds` (600) he fills the hole bottom-up, then walks back up, filling each step behind
  him. Every changed block goes back exactly as it was, unless someone built there since. Players inside a refilled block
  are moved to the top. Also restored on shutdown/`/below close`, and after a crash (the list is autosaved to `below.yml`).
- **The void world** (`faultline_void`, `PathGenerator`): a 7x7 white platform at (0, 40, 0), a 3-wide winding white path to
  z=158, a white circle (radius 18, black ring at 13) centered at z=174, lit by invisible light blocks, under a black
  concrete ceiling at y=64 (hides the sky; below y=63 the horizon fog is black). Fixed at midnight, no mobs, no weather,
  path can't be broken (except in creative). Fall off: you're put back on the platform. **The way back**: a white rift
  behind the spawn (0.5, 41, -2.5) returns you to the top of the staircase you came down (or your respawn point).
  Changing the layout needs the old `faultline_void` world folder deleted.
- Picture: `tools/previews/void_world.png` (`tools/void_preview.py`), Swarm: `tools/previews/swarm*.png`.

## Open questions
- Solo or group? Right now only whoever talks to Swarm needs the Jacob kill and the Fist; anyone can follow them down.
- Swarm's look was picked without the owner's input (hooded underground wanderer); change it if they describe him.
