# The Lost Explorer (final boss)

Inspired by the Roaring Knight (Deltarune). The last, hardest boss. **Status: built.** Model `tools/explorer_models.py`,
animations `ExplorerAnims.java`, the fight `Explorer.java` (config `explorer:`, admin `/explorer`), the way there `Below.java`.
At the end of the path he stands like a statue (head following you); right-click him to start the fight.

## Look
Completely black plate armor, an imposing knight, detailed like Elden Ring's Godfrey but all in black. You can't see his
face at all: inside the helm there's only darkness. Great helm with a low crested dome inside a tall circlet of blade-like points (tallest at the front) and swept-back
horns, a wild black mane down his back, a fur mantle, a high collar, engraved scrollwork on every plate, clean scrollwork (double borders, open curls, a lozenge; no blobs), his emblem
(three claw slashes) on a medallion on the chest (and on the belt, knees and mantle clasps), a cloth tabard, huge pauldrons with fur ruffs and
tapered spikes raked outward, clawed gauntlets, and a long torn cape.
- Rig: `explorer_{head,body,arm_r,arm_l,leg_r,leg_l}` (same joints as Jacob/Don, so `renderRig` draws it), texture `explorer_armor`.
- Weapons: `explorer_axe` (a great axe with double crescent heads and white edges) and `explorer_blade` (greatsword).
- **His black tiger** (comes in phase 2, sits behind him): `explorer_tiger_{body,head,jaw,tail}`, texture `explorer_tiger`,
  joints in `TIGER_JOINT` in the script (head/jaw/tail can move separately for roars and tail swishes).
- Previews: `tools/previews/explorer*.png` (`explorer_scene.png` is him with the axe and the tiger behind him).
  All of these are in FaultlineSMP.zip (the statue at the end of the path uses the rig and the axe).

## The fight (BUILT: `Explorer.java` + `ExplorerAnims.java`)
From the owner: every attack takes 2-3 hits to put you down; nobody dies, they kneel; if everyone's down he says "You
cannot defeat me... Get out of my sight", kicks them out and the void closes (find Swarm again). Talking to him raises 4
pillars (and barriers around the arena so nobody falls into the void). He has to crash into a pillar; while he's stunned you
pickaxe the pillar so it falls on him. 4 phases. Hitting him with a weapon slashes you to half a heart.

How it's built:
- **Start**: right-click the statue (`Below.Statue.talk` → `explorer.begin`). Everyone in the arena (radius 19) fights;
  anyone who walks in later joins. Intro lines; at 2 s a ring of **barriers** (radius ~19, 5 high, closing the path behind you too) goes up and the
  **4 pillars** (3x3, 9 tall, polished blackstone bricks with a chiseled cap, at ±7/±7 from the center) rise over ~2 s.
  Every block placed is remembered and put back after the fight (also on shutdown; leftovers after a crash are cleaned
  next start). The statue is hidden during the fight and for `respawn-minutes` (30) after he's beaten.
- **He can't be hurt.** Melee hits are cancelled and answered with a backhand (health set to half a heart, 1.5 s cooldown
  per player); arrows bounce off. While stunned/pinned, weapon hits just tell you to use a pickaxe.
- **Damage**: every hit is `damage.heavy` (55% of max health) or `damage.light` (40%) as magic damage, so 2 heavy or 3
  light hits. Any hit that would kill a fighter in the void world instead **downs** them: kneeling (fixed crouch pose),
  can't move or jump (transient -100% speed/jump modifiers), can't be hurt, keeps everything. Totems aren't used up.
- **The pillar trick**: he charges every `charge-every-seconds` (12, faster in later phases), or sooner if you keep your
  distance: a roar, a red line on the floor locking on, then a straight bull rush (`charge-speed` 0.95 blocks/tick). Hit
  a pillar and he's **stunned** for `stun-seconds` (8, 1 less each phase). Hit that pillar **3 times with any pickaxe**
  (left-click; `pickaxe-hits`) and it topples onto him (block displays pivoting over its base, 0.9 s), pinning him. Don't
  stand where it falls. Too slow and he gets back up. Pillars can't be mined or broken any other way.
- **Phases** (one per pillar dropped on him): 1: Cleave (cone), Sweep (spin, ring), Charge. 2 ("Hm. Clever."): faster,
  + Leap & Slam (lands on you, then a shockwave ring you jump over) and **his black tiger** pads in and sits behind him
  (roars, crouches, pounces at the nearest player every `tiger-pounce-seconds`, 12, then leaps back). 3 ("Enough."):
  the axe becomes the **greatsword**, + Throw (it spins out and boomerangs back) and Vanish/Ambush (gone in smoke,
  reappears behind someone). 4: + Blade Rain (marked circles, then blades drop). The 4th pillar ends it.
- **Losing** (every fighter downed): he walks to the fallen, "You cannot defeat me...", "Get out of my sight.", a kick
  that throws everyone back, and everyone in the void is sent home (`sendBack`) and **the hole is sealed at once**
  (Swarm fills it in). Their next trip: Swarm still wants the Jacob kill but **won't ask for another Fist** (`below.yml`
  → `paid`).
- **Winning**: "...", "So. You found the way after all.", "Go on. It's yours now.", he crumbles into ash. Each fighter:
  `rewards.diamonds` (32, dropped locked to them), `rewards.mythic-bags` (5, `givemythicbag`), `rewards.xp` (3000), the
  Index entry. Server broadcast. These rewards are placeholders: tell me what he should really drop.
- No music yet, and no boss form (`/bossmorph`): the fight is tied to its arena.
- Bedrock players see a big wither skeleton (and a ravager for the tiger) through `proxy`.
- Admin: `/explorer start` (in the void), `stop`, `phase <1-4>`, `stun` (crash into the nearest pillar), `reset` (statue
  back now), `arena [player]` (straight to the arena edge; the `/itemsmenu` entry uses it).
- Tested with MockBukkit: intro → walls/pillars, crash → 3 pickaxe hits → pinned → phases 2-4 (tiger, blade) → defeat,
  rewards and cleanup; two heavy hits → kneeling with the inventory intact → loss → kicked home, `paid`, arena cleared;
  a player hiding behind a pillar gets charged into it.

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
- Real drops (the rewards are placeholders) and music.
- Solo or group? Right now only whoever talks to Swarm needs the Jacob kill and the Fist; anyone can follow them down.
- Swarm's look was picked without the owner's input (hooded underground wanderer); change it if they describe him.
