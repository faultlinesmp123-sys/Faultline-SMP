# The Lost Explorer (final boss)

Inspired by the Roaring Knight (Deltarune). The last, hardest boss. **Status: model done (`tools/explorer_models.py`), the way
there is built (`Below.java`, see below); the fight itself isn't coded yet.** At the end of the path he only stands there
(head following you); right-clicking him says nothing yet. That click is where the fight will start.

## Look
Completely black plate armor, an imposing knight, detailed like Elden Ring's Godfrey but all in black. You can't see his
face at all: inside the helm there's only darkness. Tall pointed helm with a circlet of points, a spire and swept-back
horns, a wild black mane down his back, a fur mantle, a high collar, engraved scrollwork on every plate, a tiger
medallion on the chest (and on the belt, knees and mantle clasps), a cloth tabard, huge pauldrons with fur ruffs and
raking spikes, clawed gauntlets, and a long torn cape.
- Rig: `explorer_{head,body,arm_r,arm_l,leg_r,leg_l}` (same joints as Jacob/Don, so `renderRig` draws it), texture `explorer_armor`.
- Weapons: `explorer_axe` (a great axe with double crescent heads and white edges) and `explorer_blade` (greatsword).
- **His black tiger** (comes in phase 2, sits behind him): `explorer_tiger_{body,head,jaw,tail}`, texture `explorer_tiger`,
  joints in `TIGER_JOINT` in the script (head/jaw/tail can move separately for roars and tail swishes).
- Previews: `tools/previews/explorer*.png` (`explorer_scene.png` is him with the axe and the tiger behind him).
  All of these are in FaultlineSMP.zip (the statue at the end of the path uses the rig and the axe).

## The fight (from the owner)
- 3 phases. He can three-shot you.
- Not a sword fight: hit him with a sword and he slashes you down to half a heart. You have to avoid his attacks.
- To fight him: right-click him (talk) first.

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

## Open questions (ask the owner before coding)
- How do players damage him if not with swords? (Bows, reflecting his attacks, surviving timed phases, something else?)
- Solo or group? Does everyone need the Jacob kill and a Fist, or only whoever talks to Swarm?
- Drops and music. What happens when you die in the void world (right now: normal death, you respawn at home).
- Swarm's look was picked without the owner's input (hooded underground wanderer); change it if they describe him.
