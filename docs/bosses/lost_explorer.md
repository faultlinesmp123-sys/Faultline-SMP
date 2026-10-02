# The Lost Explorer (final boss)

Inspired by the Roaring Knight (Deltarune). The last, hardest boss. **Status: model done (`tools/explorer_models.py`),
nothing else coded yet.**

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
  Not merged into FaultlineSMP.zip yet (that happens with the boss code).

## The fight (from the owner)
- 3 phases. He can three-shot you.
- Not a sword fight: hit him with a sword and he slashes you down to half a heart. You have to avoid his attacks.
- To fight him: right-click him (talk) first.

## Getting there
1. Explore between **y = -1 and y = -50**. Every minute there's a **2% chance** to meet **Swarm**, a citizen of the city
   **Below the Bedrock**.
2. Talk to Swarm. To be let in you must have **killed Diamond Jacob**, and **give him a Vendetta Fist** as an offering.
3. Swarm (advanced animations) turns around and **digs a staircase** down for you. It's covered up again after 10 minutes.
4. At bedrock he pulls out a new pickaxe: "This is a pickaxe you guys don't know..." A fully black pickaxe with strange
   enchanting. He digs through the bedrock into the void.
5. Jump in: you land in a new world. Everything is black except a white path. Walk it, and you find the Lost Explorer.

## Open questions (ask the owner before coding)
- How do players damage him if not with swords? (Bows, reflecting his attacks, surviving timed phases, something else?)
- Solo or group? Does everyone need the Jacob kill and a Fist, or only whoever talks to Swarm?
- Drops, music, what happens when you die in the void world, and how you get back out.
- Swarm's look (a picture would help).
