# Faultline SMP

Custom Minecraft survival server (formerly **Limbo SMP**, renamed to **Faultline SMP**).
Owner: ndjdjs (YouTube: vo0ise). Talks casually and directly, pushes back when something
is wrong, prefers concrete/data-backed answers over generic advice.

> This file was rebuilt from claude.ai saved memory (the original claude.ai chats were not
> in the data export). If something here is wrong or outdated, fix it here.

## Server setup
- **Paper** server, with **Geyser** (Bedrock players) and **ViaVersion**.
- The server runs **Paper 26.2** (new Minecraft version numbering). The poms still compile against
  `paper-api 1.21.11-R0.1-SNAPSHOT` (`api-version: '1.21'`), and the plugins also compile cleanly against 26.2.
  Resource packs use `pack_format` 75 (FaultlineSMP.zip supports 69–75); if 26.2 clients call the pack
  incompatible, raise `max_format` in its pack.mcmeta.
- Hosting: moved from Apex Hosting to a **self-managed OVHcloud VPS**.
  (Apex/Bisect are competitors — Faultline can't appear in BisectHosting sponsor content.)
- Third-party plugin: **DropHeads** by EvModder.

## Gameplay design
- **Currency is player heads.** The **Black Market** building trades player heads for
  shulker boxes based on a staff-assigned tier.
- **Tiers:** numeric 1–8 (1 worst, 8 best). 6–8 are reserved for MC Comp-level players.
- **Scrapped:** permadeath and the ban/"Otherside" system. (Older plugin code had
  permadeath phases — don't build on that unless asked.)
- **Sidebar scoreboard** titled "Faultline SMP": kills, deaths, ping.
- **/leaderboard**: kills only.
- **Faultline Index**: in-game item & creature codex. Crafted from Leather + Book, and given
  on first join.

## Custom plugins: source in `plugins/`
All are Maven projects: Java 21, `paper-api 1.21.11-R0.1-SNAPSHOT`. Build with
`cd plugins/<Name> && mvn package`; the jar lands in `target/`.

| Plugin | Package | What it does | Commands |
|---|---|---|---|
| **LimboBlackMarket** | `net.limbosmp.blackmarket` | Head-tier economy, kill/death stats, kill streaks, anti-farm, quests + Quest Book, friendly-fire logging, global reload | `/tier`, `/markethelp`, `/stats`, `/leaderboard`, `/tierleaderboard`, `/faultlinereload` (`/freload`), `/givequestbook` |
| **FaultlineItems** | `net.faultlinesmp.items` | All custom items + accessories (3-slot `/accessories` GUI), staffs, spawn eggs, diving gear, Kraken & Diamond Jacob gear, Skeleton Wanderer cave trader, **Meteor strikes** (`Meteor.java`), **the Caravan** (`Caravan.java`), **weather events** (`Weather.java`), **Mimics + Skeleton Knights** (`Creatures.java`), **new gear, music discs, museum pedestals** (`Gear.java`), admin `/itemsmenu` | many `/give*` commands, `/itemsmenu` (`/fitems`), `/itemsreload`, `/cavetrader`, `/batform`, `/meteor [now\|here\|target\|at\|check\|stop\|egg]`, `/caravan [now\|here\|stop\|reroll]`, `/queenspider spawn`, `/fweather [list\|<event> [player]\|stop\|status]` (was `/fevent`: renamed, FaultlineEvents owns that name; bare `/fweather` lists the 5 events with click-to-start buttons; also in /itemsmenu), `/fcreature <mimic\|knights>`, `/fgear`, `/giveancientgear`, `/givedisc <id> [player]`, `/fenchant [list\|give <id> [lvl] [amount] [player]]`, `/sit`, `/vanish [player\|list]`, `/camera [speed <1-10>]`, `/ping [player\|all]`, `/coords [share [player\|kingdom\|all]]`, `/lastdeath`, `/crops`, `/giveballoon`, `/fishing [top\|records]` (admin `start [min]\|stop [award]`), `/race [join\|leave\|list\|top]` (admin `create <name> [elytra\|boat\|horse\|any]\|mode\|edit\|cp add [r]\|cp undo\|setstart\|delete\|host <course> [lend]\|start\|stop`) |
| **FaultlineRaids** | `net.faultlinesmp.raids` | Zombie Raids (Zombie Omens, Captains, Rotbeard), Skeleton Raids, **Piglin Raids** (`Piglins.java`: War Horn, the Bulwark, the Great Hog), Elites + 28 mutants (`Elites.java`, `Mutants.java`) | `/zraid <start\|stop\|captain\|menu\|egg\|omen\|horn\|elite\|mutant>` |
| **FaultlineBosses** | `net.faultlinesmp.bosses` | Bosses in one ~585 KB file: Demon Eye, Frostbeard, Dune Devourer/Frostmaw, Don Lorenzo, Kraken, **Diamond Jacob**; admin boss form. **Rocco Vendetta** (+ Werner, the Vendetta Fist) lives in its own `Vendetta.java`. **The way down to the Lost Explorer** (Swarm, his staircase, the void world) is `Below.java`; **the Lost Explorer's fight** is `Explorer.java` (+ `ExplorerAnims.java`). **Grimtusk, the Piglin Warlord** (Nether boss) is `Warlord.java`; **the Boss Rush** is `BossRush.java`. **The wild bosses** (Leviathan, Sandworm King, Lich, Frost Wyrm, Stone Golem) are `Wild.java` + one class each | `/demoneye`, `/frostbeard`, `/dune`, `/don`, `/kraken <summon\|sea\|...>`, `/jacob <summon\|kill\|phase\|item>`, `/rocco <summon\|kill\|phase\|tattoos\|werner\|item>`, `/bossmorph <boss\|off\|release>`, `/below <swarm\|void\|leave\|close\|slayer\|info>`, `/explorer <start\|stop\|skip\|phase\|stun\|reset\|arena>`, `/grimtusk <summon\|kill\|phase\|item>`, `/bossrush [start [hardcore]\|top [hardcore]]` (admin `stop\|skip\|setarena\|arenas\|clearcooldown`), `/leviathan`, `/sandworm`, `/lich`, `/frostwyrm`, `/stonegolem` |
| **FaultlineIndex** | `net.faultlinesmp.index` | The Faultline Index codex + **Achievements** (`Achievements.java`). **Museum** (`Museum.java`: trophies, discs, Hall of Firsts), **Discord bridge** (`Discord.java`), `/serverstats` (`ServerStats.java`). Entries and achievements live in `src/main/resources/index.yml`, font glyphs in `glyphs.yml`, settings in `config.yml` | `/index [achievements\|give\|reset] [player]`, `/index stat <player> <name> [n]` (other plugins), `/index discover <player> <entry>`, `/museum [bosses\|items]`, `/serverstats`, admin `/discord [status\|test [msg]\|daily]` (public + logs webhooks, daily stats post), `/report <player> <reason>`, staff `/reports [n]`, `/suggest <idea>`, staff `/suggestions [n]`, `/guide [chapter]`, staff `/watch [<player>\|log\|xray\|alts\|check\|alerts]` (x-ray / cheat / dupe / grief / alt logs, `Watch.java`) |
| **FaultlineCosmetics** | `net.faultlinesmp.cosmetics` | Permanent cosmetic unlocks worn in 4 slots (Hat, Neck, Back, Body) over armor, for Java and Bedrock. **Seasons** (`Seasons.java`: Halloween/Winter currency + Seasonal Shop). Cosmetics are defined in its `config.yml`, unlocks saved in `players.yml` | `/cosmetics [shop]`, admin `/cosmetic <unlock\|lock\|list\|reload\|season\|tokens\|shop>` |
| **FaultlineKingdoms** | `net.faultlinesmp.kingdoms` | Kingdom 1/2/3 per player, assigned from DISCORD ROLES (players link once: the bot DMs a code), and GRACE PERIODS server-wide or per kingdom (protected kingdom can't be hurt, and can't hurt others unless `grace.protected-can-attack`); `pvp.friendly-fire`; kingdom in the tab list. Data in `data.yml` | `/kingdom [link <discord name>\|verify <code>\|unlink\|list <1\|2\|3>\|ff <on\|off\|clear>]` (`/k`), admin `/kingdoms grace <all\|1\|2\|3> <on\|off> [30m\|2h\|1d\|1h30m]`, `/kingdoms ff <1\|2\|3> <on\|off\|vote>`, Discord `/stats <player>`, `/kingdoms set <player> <1\|2\|3\|none\|auto>`, `/kingdoms sync [player]`, `/kingdoms reload` |
| **FaultlineShips** | `net.faultlinesmp.ships` | Ships: Dinghy, Sloop, Brigantine, Galleon, Pirate Ship. Blueprint → lay out on water → place every block → sail. Health, part damage, sinking, Shipwright's Hammer repair minigame, cannons, banners, names. **Skeleton ships** and the **Pirate Invasion** (`Pirates.java`). Ships saved in `ships.yml` | `/ship [list\|info\|name\|crew\|anchor\|stop\|banner\|scrap]`, admin `/ship <give\|repair\|wreck\|remove\|tp> [name\|#n]`, `/ship spawn <ship> [owner]`, `/ship give upgrade_<hull\|sails\|loaders\|ram>`, `/ship pirates <ship\|invasion\|horn\|spawn\|egg\|ghost [player]\|bell [n] [player]\|map [player]\|stop>` |

Notes:
- `FaultlineItems.java` (~350 KB) and `FaultlineBosses.java` (~585 KB) are huge. Search them instead of reading them whole.
- **Diamond Jacob** is being built one phase at a time, gated by `jacob.max-phase` in the Bosses config.
  All 4 phases are live (`max-phase: 4`); lowering it makes him withdraw (no loot) at the end of that phase.
  Music is on: `music1.ogg` (5:15, phases 1-3) and `music2.ogg` (4:46, cutscene 3 + phase 4). His 5 counter charms live in
  `FaultlineItems/JacobGear.java` (Falconer's Quiver, Ender Anchor, Banner of Defiance, Ember Ward, Diamond Heart);
  Bosses reads which ones a player wears from the `faultlineitems:jacob_counters` tag.
- **Boss form** (`/bossmorph`): an admin plays a boss. `survival()` returns false for anyone in `MORPHED`, so no
  boss targets, hurts, or films them. Each boss's tick checks `pilot(this)`: between moves it follows the admin
  (`pilotIdle`/`pilotWalk`/`pilotFollow`), and its move picker takes `takeMove(this)` instead of choosing randomly;
  moves aim at `pilotTarget(...)`. The hotbar list per boss/phase is `morphMoves()`. A NEW BOSS must get the same hooks
  (and an entry in `morphMoves()`, `MORPH_KINDS`, and the spawn switch in `startMorph`).
- **Rocco Vendetta** (spec: `docs/bosses/ricardo.md`): `Vendetta.java` uses package-private helpers from FaultlineBosses
  (`spawnDisplay`, `renderRig`, `hurt`, `setupHitbox`, `proxy`, ...) and is ticked/cleaned up/hooked into boss form from there.
  His rig and Werner's are skins painted by `tools/vendetta_models.py`; both wear the cosmetic chains model (Rocco also the book).
  Music: `sounds/rocco/music.ogg` (3:10, `rocco.music.length-seconds: 190`), looped for the whole fight.
  Tuned for 7 players (health scales per fighter). His and Werner's animations are in `VendettaAnims.java`; render them to
  PNGs with `tools/anim/preview.sh <unpacked-pack> <out>` (runs the real Java pose code through `tools/anim/render_anims.py`).
- **The Lost Explorer** (final boss, spec + build notes: `docs/bosses/lost_explorer.md`). `Explorer.java`: he stands on a
  12-tall pillar in his arena digging with a pickaxe, his tiger sitting below; SOLO (one fighter, others are put back on the path); walking in → cutscene 1 ("Who are you?", snap)
  → his BLACK TIGER (2000 hp, swords work, 4 hits to down you) → cutscene 2 ("Useless." "You shall die.", his pillar sinks,
  4 pillars rise) → him, 4 phases: can't be hurt, Roaring Knight moves (slash lines, dashes, cross cuts, sword rings,
  starbursts, sword rain, big red/white warnings), 4 hits to down you; after every 15 moves he charges: into a pillar → pickaxe it
  3 times → it falls on him. Downed = kneeling, frozen, keeps everything, never dies. All downed → 30 s speech about the
  kingdom Below the Bedrock (Diamond Jacob was its general; hints he's its lost king, never says it; skippable) → "Do you want
  to do it again..?" (chat buttons, `/lostexplorer skip|yes|no`: Yes restarts from the tiger) → No: "Get out of my sight",
  kicked home, hole sealed (`below.yml` `paid`: no second Fist). No boss form. Music: `sounds/explorer/music.ogg`
  (Black Knife, 2:02, `explorer.music.length-seconds: 122`). Drops 32 mythic bags, 3000 XP, 5 netherite blocks and the
  **Mirror..??** accessory (FaultlineItems `MirrorGear.java`, `/givelostmirror`: reflects one big hit, 45 s cooldown).
  His and the tiger's poses are in `ExplorerAnims.java` (preview with `tools/anim/preview.sh`; the tiger is 8 pieces, 12-number poses). `renderRig` has an overload
  with sword pitch/scale: custom blade-up weapon models use pitch +90, vanilla swords -90.
- **The Kraken at Sea** (the `Kraken` class with `atSea`/`vessel`; Bosses ↔ Ships through `ShipLink` → `ShipsApi` by
  reflection, so neither needs the other): drop a Bait Worm while on a FaultlineShips ship or in a vanilla boat (3 s), or
  1% every 2 min for anyone sailing (`kraken.sea.*`), or `/kraken sea`. Needs 8+ blocks of water beside the vessel. Same
  model, phases, music and loot; 1,600 hp; his head sits out of the water (`keepAtSurface`) and he keeps station beside the
  ship as it sails. 7 moves (pool 1,2,3,5,6,7 + 10 in phase 3): Tentacle Slam, Crushing Grip (drags you off the deck),
  Ink Cloud, Hull Bite (lunges to reach the hull), Barnacle Barrage, Tidal Surge (reaches the deck), Eight-Arm Barrage.
  Hits near the hull call `seaHit` = a fraction of the ship's max hp (`sea.ship-damage.*`, breaks the parts there; a boat
  just breaks). Ship wrecked / boat gone = `lostAtSea()`: he leaves, no hoard. No air bubbles, eels, out-of-water smash,
  Whirlpool, Spawn of the Deep or depths moves at sea; the pink bubble bobs on the surface. Hitbox tag
  `faultline_kraken_sea` (Index: The Kraken at Sea). Tested with both plugins loaded (`SeaKrakenTest`).
- **Diamond Jacob fixes**: his phase 3 riders (`army`, vindicators on horses) ride back to his side when no fighter is
  within 18 blocks of them (`armyFollow`, every second; one left over 64 blocks behind is teleported back). `rehide()`
  (every 2 s) re-applies invisibility to his hitbox slimes and hides them from Bedrock players (they hit his stand-ins).
  Spear Wall: warning lines and spears follow the ground (`floorY`), each flat spear faces across its line with a little
  tilt, and they burst up out of the ground. Tested in the bosses harness (`JacobTest`).
  `Below.java`: Swarm turns up underground (2%/min, y -1..-50), wants a Jacob kill (recorded in `below.yml`, or Jacob
  unlocked in the Index) and the Vendetta Fist, digs a staircase to the bedrock, breaks through with a black pickaxe; the
  hole leads to the void world `faultline_void` (white path in the dark, his statue at the end). Everything Swarm digs is
  put back after 10 minutes, on shutdown, and after a crash. Creative players skip his checks. Tested with MockBukkit.
- **Piglin Raids** (`FaultlineRaids/Piglins.java`, config `piglin-raid:`): Piglin Brutes killed by a player drop a
  **Piglin War Horn** 5% (always III-V). Blowing it gives a Piglin Omen (same omen system as Zombie/Skeleton Omens:
  near a village in the Overworld → 30 s → raid). III = 6 waves, IV = 8, V = 10, 15 piglins a wave: Grunts, Crossbowmen,
  Brutes, Hoglin Riders, Piglin Mages (fireballs, flame burst), Piglin Summoners (portals → Grunts), Balloonists (a piglin
  in a hot air balloon of block displays, dropping fire bombs), Sappers (TNT, 1 s fuse), Shieldbearers (75% less from the
  front), Lobbers (magma → burning ground), Runts (packs of 3), Banner Bearers (Strength + Speed aura), Medics (heal allies).
  Specials are shuffled and cut to fit the 15 each wave. **The Bulwark** (mini boss, wave 5: Shield Bash, Ground
  Slam, Unbreakable = 80% less damage from the front) and **The Great Hog** (boss after the last wave, every level:
  Charge → stunned on a wall, Earthshaker ring, Rally, Fire Breath, enraged below 50%). Every piglin/hoglin is
  `setImmuneToZombification(true)`. Raid piglins (tag `faultline_piglin_unit`) drop no gear: killed by a player, 80% for 3-5 gold nuggets (`nugget-chance/min/max`); the Bulwark and the Great Hog drop their own gold. `/zraid start <lvl> piglin`, `/zraid horn <3-5>`, eggs in `/zraid menu` and `/itemsmenu`.
  Art: `python3 tools/piglin_assets.py <pack> --preview tools/previews` (horn texture + Index icons).
  Tested with MockBukkit (a full level V raid, horn drop rate, and every ability).
- Items: the **Ankh Shield** (held, imbued into a shield) makes you immune to every debuff but NOT knockback
  (`ankh-shield.debuff-immunity: true`, `no-knockback: false`; `old-effects: true` brings back all the old powers).
  Hidden effects (no icon, no particles: cutscene Blindness, the Below's Darkness) still get through. Old imbued shields'
  lore is fixed when held. Tested in `AnkhTest`. The **Harpy Ring** is +5% speed, Elytra durability drains 30% slower (Items 1.2.32; was 7.5% / 50%).
- **Items 1.2.12 (balance)**: the **Abyssal Diving Suit** does nothing in combat (`DivingGear.has()` skips SUIT while
  `inCombat`: 10 s after hitting / being hit by a player or an `Enemy` mob, projectiles count as their shooter;
  `diving.suit.combat-seconds`, `combat-includes-monsters`). **Cleft Horn** 5% -> 1.5% armor penetration, then 0.5% in 1.2.33
  (`NewAccessoryItems.cleftPenetration`: an old config's 0.05 or 0.015 reads as 0.005; lore built from it). **Accessories don't
  stack**: max stack 1 (was 16), and the /accessories menu refuses a second copy of one already worn
  (`AccessoryManager.same`: shared PDC tag other than the generic accessory key); a duplicate saved from before goes back
  to the inventory when the menu closes. `AccessoryStacks` splits old stacks (join, opening any container, pickup) and
  refreshes the Cleft Horn / Suit lore. Tested (`NerfTest`).
- **Deep bug pass (Items 1.2.13, Bosses 1.5.1, Index 1.6.7, LimboBlackMarket 1.0.1)**: `AccessoryStacks` only splits
  stacks in REAL storage (`realStorage`: block containers, double chests, chest/hopper minecarts, chest boats, the ender
  chest): splitting a plugin menu that showed stacked accessories handed the copies to the player (a dupe). The
  **Crafter** block never fires PrepareItemCraftEvent: `CraftGuardListener.onCrafter` applies the same custom-item rules.
  Meteor: a far spot generates its whole 3x3 of chunks in the background, and a generated-land spot needs its 3x3
  generated (the crater/guards used to generate neighbours on the main thread at impact). Sandworm Devour: logging out
  while swallowed puts you on the ground first (`Wild.onQuit`). Black Market: placed-block marks move with pistons
  (place debris, push, mine farmed the "mine Ancient Debris" quests); `StatsManager.save` is synchronized and written
  through `stats.yml.tmp` + atomic move (the async autosave and the shutdown save could interleave). Discord: one webhook
  send at a time. Tested: `NerfTest`, `WildTest`, `EventsTest`, `MarketTest` (scratch harness `mtest`).
- **Mace rules (Items 1.2.14, `MaceRules.java`, config `mace:`)**: maces can't be crafted (the `minecraft:mace` recipe is
  removed at startup, the crafting table result is cleared, the Crafter is cancelled), and a mace only lives in a player's
  own inventory: every non-workstation top inventory refuses it (place, shift-click, number keys, F swap, drag: chests,
  ender chests, shulkers, hoppers, droppers/dispensers, crafters, animal/minecart/boat chests, ship holds...); bundles refuse
  it anywhere (`*_INTO_BUNDLE` actions); hoppers/hopper minecarts can't pick one up or move one (`InventoryPickupItemEvent`,
  `InventoryMoveItemEvent`); mobs can't pick one up; item frames, allays, armor stands, decorated pots, shelves refuse it.
  Workstations (anvil, grindstone, enchanting, smithing...) still work. Maces already in bundles are taken out on join.
  Creative/spectator and `faultline.mace.bypass` (default false, so ops in survival are tested too) skip it. In vanilla the
  only source of a mace is crafting; none of our plugins give a real MACE (the Shipwright's Hammer is a STICK with the mace
  model; the boss-form hotbar icon is admin-only). Tested (`MaceTest`).
- **Maces removed (Items 1.2.15 / Bosses 1.5.2)**: `mace.mode: remove` (default; `restrict` = the 1.2.14 rules above,
  `off`). `MaceRules` deletes every MACE: online players every 2 s + on join/open/held (inventory, armor, offhand, cursor,
  2x2 grid / open workstation / open real storage, ender chest), deep inside bundles and shulker-box items (`strip`,
  `stripInside`), block containers on chunk load (`BlockInventoryHolder`), entities on load (items, frames, equipment,
  entity inventories: allays, villagers, horses, chest minecarts/boats), all loaded worlds once at startup, new item
  spawns, mob/block drops, loot generation, fishing, dispensers, pickups. A swing/use between sweeps is cancelled and the
  mace taken. Creative players lose them too; only `faultline.mace.bypass` keeps one. Every removal is logged ("[Maces]
  removed N"). The /itemsmenu Shipwright's Hammer icon is a STICK with the minecraft:mace model, and the boss-form
  Hammer Slam icon is HEAVY_CORE (a real MACE icon would be swept). Tested (`MaceTest`).
- **Items 1.2.16**: the Mythic Goodie Bag's Elytra is 0.001% (`mythic-goodie-bag.elytra-chance` 0.00001, rolled first;
  an old config's 0.001 reads as the new value); Ender Pearls take the 0.1% back (15%).
- **Bosses 1.5.3 (snow spawns)**: FROSTBEARD spawns in any snowy biome at `mortimer.spawn-chance` 0.0001 (0.01%) per
  player every 30 s (old 0.005 reads as that), no torch needed: `snowSpotAway` = open snowy ground 20-40 blocks away
  (`spawn-distance-min/max`; `require-torch: true` = the old way). `inTheSnow` also matches snowy/frozen/ice/grove/jagged
  biome names. The FROST WYRM's natural spawn is 0.00001 (0.001%) a minute (old 0.015 reads as that) in any snowy biome
  (`Wild.snowy`: PEAKS or temperature < 0.15, not frozen oceans), flying in ~20 blocks away. Tested (`FrostSpawnTest`;
  the bosses harness disables Frostbeard's random spawn unless `-Dfrost.spawns=true`).
- **The 1.2.17 batch (Items 1.2.17, Raids 1.1.0, Ships 1.6.0, Index 1.6.9)**:
  **Spawn protection** (`Items/SpawnProtection.java`, `spawn-protection:`): 30 s after respawning players can't hurt you
  (melee, projectiles, lingering/splash potions, TNT, pets: `playerBehind`); hitting a player ends it (`ends-on-any-attack`
  for any mob), `block-mobs` (off) also stops monsters. **Vampires nerfed**: claws 9 -> 5, 40 -> 30 hp, bleed 0.5/s for 3 s,
  Blood Moon 1 per player every 45 s, max 12 (`VampireManager.NERF` reads old config defaults as the new values).
  **Blood Moon raids** (`FaultlineRaids.bloodMoon()` = FaultlineEvents' `faultlineevents:active_event` BLOOD_MOON on the
  main world; `blood-moon:`): +25% mobs a wave, +30% health + Strength I (`bloodBuff`, tag `faultline_blood_moon`), +1 Mythic
  Bag per defender on a win. **Boss enchant books** (`Items/Enchants.java`, `enchants:`, `/fenchant [list|give <id> [lvl]
  [amount] [player]]`): ENCHANTED_BOOK + PDC `faultlineitems:fenchant_book` ("id=level"); item left + book right in an anvil
  (`combine`: two equal books = next level); on the item PDC `faultlineitems:fenchants` ("lifesteal=2;venom=1") + a "✦ Name
  II" lore line each. Lifesteal, Frostbite, Venom, Thunderstrike (melee: swords/axes/tridents, procs need a charged hit),
  Vitality (chestplate: a player MAX_HEALTH modifier `faultlineitems:vitality`, refreshed every second, removed on quit /
  disable), Featherstep (boots). Drops: the Index Museum's `bossDefeated` dispatches `fenchant bossdrop <player> <entry id>`
  once per defeat, Items rolls `enchants.boss-drops.<entry>` (["id:level:chance"]; code defaults `DEFAULT_DROPS`). In
  /itemsmenu. **Elytra races** (`Items/Race.java`, `race:`, courses + best times + chestplates to give back in `races.yml`):
  admins `/race create <name>` (start = where they stand), `/race cp add [radius]` per ring (the last = finish), `/race host
  <course> [lend]` (clickable [JOIN]), `/race start` (5 s countdown held at the line), `/race stop`; players `/race join|leave|
  list|top`. Rings are per-racer dust; a ring counts when the path between ticks passes within its radius (`segDist`), in
  order. Lent elytra/rockets carry PDC `faultlineitems:race_lent`, can't be dropped/stored, are taken back on finish/out/quit
  and on join (crash). Rewards `race.rewards.first/second/third/finish` (console commands), Index stat `elytra_races`.
  **Ship upgrades** (`FaultlineShips.UPGRADES`, `upgrades:`; PAPER with vanilla item models, glint, PDC
  `faultlineships:upgrade_<id>`; crafted): Hull Plating (3x +15% hp), Swift Sails (3x +10% speed: `Ship.maxSpeed()`), Quick
  Loaders (2x -20% reload), Iron Ram (ramming: half to you, +50% to them). Right-click your own finished ship to fit
  (`fitUpgrade`, owner/admin only); saved under `ships.<id>.upgrades`; scrapping gives them back. `/ship give upgrade_<id>`.
  **Land cannons** (`Ships/LandCannons.java`, `land-cannon:`, `cannons.yml`): a Ship Cannon right-clicked on any block that
  isn't a ship's cannon spot sets a cannon in front of that face: a BARRIER in the cell (solid, clickable) + a non-persistent
  ItemDisplay (`cannonModel`, tags `faultline_land_cannon` + `faultline_no_bedrock_fx`) spawned with its chunk. Holding a
  Cannonball within `reach` (3.5) turns the nearest cannon you may use (owner + /ship crew, `owner-only`) to your view
  (pitch clamped -45..20; pitch is the display's transformation, yaw the entity's) and draws the arc for you; right-click
  fires through `launchTagged("land:<id>", ...)` (same impact as ship cannonballs), 4 s reload. Sneak + punch (owner/admin)
  picks it up; it drops when nothing solid holds it any more (break/explosion); pistons can't move it or its support.
  Bedrock sees a fake dispenser facing the barrel (`bedrockUpkeep`). The BlockPlaceEvent it fires for claim plugins is
  ignored by `onPlace` (`placing`). **Discord, two webhooks** (`Index/Discord.java`): `discord.webhook-url` = PUBLIC (chat,
  joins/leaves, deaths + PvP, `forward` broadcasts), `discord.logs-webhook-url` = STAFF LOGS (everything public with
  `logs.mirror-public`, plus TNT / TNT minecart / end crystal / respawn anchor placers and TNT lighters (`logs.blocks`), PvP
  kills with coordinates, whispers (`logs.whisper-commands`), commands by ops / `faultlineindex.logged` (or everyone with
  `logs.all-commands`), console commands; never `logs.ignore-commands` (login passwords)). Every log line ends with
  `<t:unix:f>` (Discord shows it in each reader's time zone) and the UTC date/time. Each channel batches lines into one
  message every 2.1 s (under the 30/min webhook limit), honours 429 Retry-After, escapes markdown, never pings
  (`allowed_mentions` empty), posts "server online/stopping". `/discord status`, `/discord test [msg]` (both channels).
  THE WEBHOOK URLS ARE SECRETS: only in the server's `plugins/FaultlineIndex/config.yml`, never in this public repo.
  Tested: `BatchTest` (items: protection, books, races, vampire nerf), `BloodTest` (raids), `CannonUpgradeTest` (ships),
  `DiscordTest` (index, a local HTTP server as the webhook).
- **The 1.2.18 batch (Items 1.2.18, Ships 1.6.1, Index 1.6.10)**:
  **Race modes** (`Race.Course.mode`, saved): `elytra` (default), `boat` (everyone gets a `race.boat-type` boat), `horse`
  (everyone gets the same tamed, saddled, invulnerable horse: `race.horse-speed`/`horse-jump`), `any` (bring anything,
  your own ship included: rings check the player's position). `/race create <name> [mode]`, `/race mode <mode>`. Lent
  mounts: tag `faultline_race_mount`, non-persistent, can't be left mid-race (`onDismount`, `releasing` for our own eject),
  broken or opened; removed on finish/out/stop. Index stat `<mode>_races` (`open_races` for any). **Vein Miner** book
  (`Enchants`, Kind PICKAXE): sneak + mine an ore = the connected vein of that ore family (deepslate counts), up to
  `enchants.veinminer.max-blocks` 32, each through `Player#breakBlock` the next tick (events, drops, durability), guard
  set `veining`; drops from the Stone Golem (30%) and the Lost Explorer (50%). **/sit** (`Items/Sit.java`, `sit:`):
  empty-hand right-click on a bottom stair/slab or `/sit`; seat = marker armor stand tag `faultline_seat`; never near a
  `faultline_ship` entity. **/vanish** (`Items/Vanish.java`, `vanish:`, `vanished.yml`): `faultline.vanish`, seen by
  `faultline.vanish.see`; hidePlayer, fake leave/join lines, real join/quit messages hidden, no mob targeting / pickup,
  metadata `vanished` (the Index's Discord skips their joins/leaves: `Discord.vanished`). **Fishing** (`Items/Fishing.java`,
  `fishing:`, `fishing.yml`): every caught fish gets a weight (`SPECIES` ranges, `weigh`); an all-time species record is
  announced and becomes a Trophy Fish (PDC `faultlineitems:fish_weight`); tournaments (`/fishing start [min]|stop [award]`,
  or `auto-every-hours`): best single fish in points = kg / species max x 100, boss bar, `fishing.rewards.*`, Index stat
  `fishing_wins`. **/report** (`Index/Reports.java`, `reports:`, `reports.yml`): to the Discord logs channel as its own
  message (`Discord.report`, pings `discord.logs.report-role`, the only mention allowed), online staff
  (`faultlineindex.reports`), `/reports [n]`. **Ship paint jobs** (`FaultlineShips.paint`): a dye on your ship = all
  `Need.WOOL` sails that colour (1 dye per `paint.sails-per-dye` 32), sneak + planks = every planks/log/fence/stairs/
  trapdoor cell in that wood, state kept (`swap`), anchored real blocks updated; the Pirate Ship's Jolly Roger can't be
  dyed; Bedrock's stand-in model keeps the default woods. **Treasure maps** (`Ships/TreasureMaps.java`,
  `pirates.treasure:`): plunder 35% or `/ship pirates map [player]`; PAPER with the vanilla map model, PDC
  `faultlineships:treasure_map` "world;x;z;id" 250-700 blocks away; held = distance + compass in the action bar, red X
  within 12 (surface height saved into the map within 48, `treasure_y`); breaking a block at the X (top+1 .. top-5,
  ocean floor height) = a chest of `treasure.loot` + `treasure.commands`, map used up, Index stat `treasure_maps`.
  Tested: `Batch2Test` (items), `PaintTreasureTest` (ships), `ReportTest` (index).
- **Bug pass (Items 1.2.19, Ships 1.6.2, Index 1.6.11, Raids 1.1.1, Bosses 1.5.4)**: Boss Rush stages no longer roll
  discs or enchant books (the rush tags its team `faultline_boss_rush` = `BossRush.TAG`, removed at the end and on join;
  `Museum.bossDefeated` skips the rolls for tagged players). Blood Moon raid extras copy only ordinary kinds (never a
  second Bulwark / Great Hog / Bone Commander / boss). Ship paint + scrap was a wood/wool converter: painted cells keep
  what was really built in `Ship.origin` (saved as `ships.<id>.origin`), and scrapping refunds that. A land cannon whose
  barrier vanished (WorldEdit...) drops as an item (checked every 3 s). Races: "any" mode never teleports or holds a
  rider's own vehicle (a ship seat would have been pulled off its ship; `Racer.free`); lent mounts are held at the line
  with `TeleportFlag.EntityState.RETAIN_PASSENGERS`; lent rockets/elytra can't go into bundles (`onBundle`). Discord:
  RCON commands are logged too (not command blocks), the online count leaves vanished staff out (`Discord.visible`).
- **Lag pass (Items 1.2.20, Raids 1.1.2, Bosses 1.5.5)**: `MaceRules` only opens bundles and shulker boxes (`holds()`;
  every enchanted/named item used to be unpacked with getItemMeta every 2 s per player) and sweeps each chunk ONCE (chunk
  PDC `faultlineitems:mace_swept` / `mace_swept_entities`; new chunks skipped): it used to rescan every container (and
  every shulker in them) on every chunk load, a spike whenever someone came home to a storage room. Raids' Bone Commander
  ticks (every 1 s / 15 s) use a cached UUID set refreshed by a full scan every 10 s (`commanders()`, `noteCommander`);
  Rocco's friendly Enforcers likewise (`famAllies`, full scan every 15 s); `Wild.sweep` scans every entity every 5 s only
  while a wild boss is out or for 2 min after, else once a minute. RULE: never scan `World#getEntities()` /
  `getLivingEntities()` / `getEntitiesByClass` on a short timer; keep a set of what you spawned.
- **FaultlineKingdoms 1.0.0** (`FaultlineKingdoms.java` = data, grace, PvP rules, commands; `DiscordLink.java` = the bot).
  Discord is pure REST from a bot token (`discord.bot-token`, `guild-id`, `roles.1/2/3`; the bot needs the Server Members
  Intent): `/kingdom link <name>` searches the guild (`/members/search`, exact username / display name / nick, or the only
  hit), DMs a 6-digit code (`/users/@me/channels` + `/channels/{id}/messages`), `/kingdom verify <code>` links (one Discord
  account per player). Roles are read with `GET /guilds/{g}/members/{id}` (404 = left the server) on join, every
  `sync-minutes` (one call per 250 ms) and `/kingdoms sync`: lowest-numbered kingdom role wins; none =
  kingdom 0 (`remove-when-role-gone`). `/kingdoms set` pins a player (`manual`, sync skips them; `auto` un-pins). Grace map
  `all`/`1`/`2`/`3` -> until ms (-1 = until off), checked in `blocked()` for melee, projectiles, TNT, pets
  (`playerBehind`), splash and lingering potions; timed ones end in `tick()` with a broadcast. Other plugins:
  `FaultlineKingdoms.kingdomOf(uuid)`, `protectedNow(player)`. THE BOT TOKEN IS A SECRET (server config only).
  `discord.api-base` (hidden) points the tests at a fake Discord. Tested (`KingdomTest`, scratch harness `ktest`).
- Changing a plugin's default `config.yml` does NOT update the copy already on the server. Tell the owner
  which values to change in `plugins/<Plugin>/config.yml` on the VPS.
- **Ships** (`FaultlineShips`; `ShipType.java` = the 5 layouts, `Ship.java` = one ship, `FaultlineShips.java` = items,
  events, the hammer minigame, cannons, saving). A ship is one display per block riding an invisible root ItemDisplay
  (moving = one teleport; since 1.21.10 teleports keep passengers). Each display's transformation is fixed (the cell in the
  ship's frame, `Q0` = local→display axes) and turning sets every display's own yaw (`sendRotation`), so the ship turns as
  one rigid piece (re-sending transforms made blocks wobble). Local coords: +x bow, +z starboard, y 0 = top water block.
  v2 layouts (1.2.0): Sloop 111 blocks / 150 hp / 3 seats / 2 cannons, Brigantine 296 / 350 / 5 / 4 + crow's nest + 27-slot
  hold, Galleon 666 / 700 / 9 / 8 + crow's nest + stern windows + 54-slot hold. Ladders down both sides (real ladder blocks
  while anchored); bottoms are narrower than the deck (`hull(..., inset)`) and ladders are never checked against the bank,
  so the Galleon fits a 7-wide river. Saves store blocks by position (`format: 2`); format-1 saves are mapped through
  `layouts_v1.json`, and finished ships get the parts an update adds for free. S brakes (press again to reverse), `/ship stop`,
  Jump/`/ship anchor` at speed brakes first. `/ship name`, `/ship banner` (right-click with any banner flies it from the
  tallest mast; Bedrock: an armor stand wearing it). Admin `/ship <wreck|repair|remove|tp|info> <name|#n>` (#n from
  `/ship list all`). **Cannons**: Ship Cannon item (paper, model `faultline:ship_cannon`) fills the cannon cells; Cannonball
  (paper, `faultline:cannonball`; iron + gunpowder = 4) right-clicked on board fires the nearest loaded cannon on the side
  you look at (35 degree arc, 3 s reload, a Snowball carrying the ship id): no block damage, 45 to a ship it lands on
  (`cannon.*` in config). Models from `tools/ship_assets.py` (Java pack + Bedrock). The repair minigame judges a click by where
  the marker was when the player saw it (ping + 1 tick, `seen()`); marker slower, green wider.
  Blueprints (base item GLOBE_BANNER_PATTERN): Sloop = paper around a boat, Brigantine = gold + paper around a Sloop Blueprint,
  Galleon = diamonds + paper around a Brigantine Blueprint. Building: right-click water → glowing outline → right-click
  the glowing blocks with the right kind (any wood; sneak = 16 at once). Each built cell gets a barrier while
  building/anchored, so the deck is walkable; sailing removes them (everyone aboard sits on seats). Helm: W/S/A/D, Sprint,
  Jump = anchor (snaps to the grid). Steering reads `Player#getCurrentInput()`. Water only: the bottom row must be in
  water, everything above in air (checked each tick). Damage: melee (axe 8...), arrows/tridents, explosions, ramming the shore;
  owner + `/ship crew` can't hurt it. 0 hp = wrecked (torn sails, can't sail until full). Shipwright's Hammer (base item
  STICK with the vanilla mace model; can't be crafted with): timing bar in the action bar, 1 Planks per good hit.
  Seats are invisible marker armor stands (Bedrock can ride those; it has no display entities).
  **Bedrock** (Geyser can't draw block displays): a finished ship also gets a stand-in armor stand, hidden from Java players
  and shown to Bedrock ones (`bedrock(p)` = Floodgate UUID, top half 0), wearing paper with item model
  `faultline:ship/<type>[_wreck]`. Geyser maps that to a Bedrock attachable drawing the whole ship (default woods). While
  a ship is being built, Bedrock players get fake block changes (the real block in each built cell, over the barrier) and
  particles on the empty cells. `/ship anchor` drops/raises without the Jump key. Bedrock files:
  `bedrock/FaultlineShips.mcpack` → `plugins/Geyser-Spigot/packs/`, `bedrock/faultline_ships_mappings.json` →
  `plugins/Geyser-Spigot/custom_mappings/`. Made by `python3 tools/ship_assets.py <pack> --preview tools/previews --bedrock bedrock`
  from `tools/ships/layouts.json` (rerun `tools/ship_layouts.sh` after changing ShipType.java). Chests show as barrels
  (a block display can't draw a chest). Barriers go in only where nobody stands (players get lifted onto the deck); after a crash,
  barriers the save didn't know about are reconciled on the ship's first spawn. Nothing can be built on/over an anchored ship.
  Index icons from `python3 tools/ship_assets.py <pack> --preview tools/previews`. Tested with MockBukkit (build, sail, shore,
  anchor, damage, wreck, repair, save/load, scrap, events, Bedrock stand-in, the fixes, and v2: braking, names, wreck by name,
  banners, cannons, rivers, old-save migration). MockBukkit quirks the test copy patches:
  teleporting an entity with passengers, `Block.getLocation()` returning the block's own Location, rayTraceBlocks.
  **v3 (1.3.0)**: Galleon deck raised to y3 with a walkable hold below (hatch + ladder; `layout: 3` in saves, older Galleons
  are shifted on load). **Dinghy** (26 blocks, 60 hp, 2 seats, no cannons, sneak + right-click = 9-slot fish hold; 2 paper + any
  boat) and **Pirate Ship** (882 blocks, 900 hp, 9 seats, 10 cannons, Jolly Roger sails of SAIL_BLACK/SAIL_WHITE wool, hold
  below a HATCH; black dye + paper + Galleon Blueprint + wither skeleton skull). Shaped cells (stairs, fences, trapdoors,
  panes, ladders, lanterns) are REAL blocks while anchored (`need().shaped()`, `Ship.shipBlock`), so you jump on stairs and
  can't walk through fences. **Parts**: a hit breaks the cells nearest the impact (`damage(amount, by, at)` → `breakNear`,
  `parts.*` config): broken cells vanish; crashing (`ram()`) breaks the bow/side that hit and hurts a ship you rammed. The
  hammer only works within `parts.reach` of a hole (it tells you where), incl. below deck. 0 hp = slowly SINKS (`sink`,
  `pitch`, `sink-speed`), can't be boarded; repaired, it refloats.
  **Pirates** (`Pirates.java`, config `pirates:`): every `check-seconds` a player sailing an ocean biome rolls 1% invasion,
  else 5% skeleton ship (`spawnNear`). A skeleton ship is an AI `Ship` (`ship.ai` = `Brain`, `AiInput` replaces the player's
  keys, owner `OWNER`), dark oak + black sails (`dark(Need)`), half health, never saved. `think()`: patrol; approach;
  broadside (cannons with lead/spread, 2 degrees when a Navigator made you glow); ram a ship under 35% / stopped / cannonless;
  open the range when too close; after `shots-before-boarding` (or your hull < 60%) close in, and alongside (`gap()` < 4,
  real hull gap) drop anchor: the crew stands up to fight, the hold (sneak/chest) is plunder for anyone (`plunder` commands
  once per player). Swimmers: it sails away faster. Your ship sinking: 3 crew go over the side after you (`abandon`).
  Wrecked: spills loot, sinks, gone. Mobs (`Kind`, tag `faultline_pirate_<kind>`): Deckhand, Musketeer, Gunner (lit
  cannonballs), Boarder (leaps), Powder Monkey (explodes), Drowned Corsair, Bosun (chain pull) + Ghost Gull, Bone Shark,
  Navigator, Cursed Wraith. **Invasion** (also the Cursed Pirate Horn, GOAT_HORN + PDC `pirate_horn`, only in skeleton ship
  holds 15%/5%): flagship "The Black Gallows" behind 15 escorts; once they're sunk (or it's wrecked) the bosses come one at a
  time on its deck: the Captain's Son (320), the Skeleton Commander (480, Jolly Roger banner), the Skeleton Captain (900, ghost
  crew below 50%); win = `invasion.rewards` per fighter. Music `faultline:pirates.music` (`sounds/pirates/music.ogg`,
  4:51, `invasion.music-seconds: 291`). Eggs: `/ship pirates egg <kind> [amount] [player]` (in /itemsmenu). Bedrock: AI
  ships use `faultline:ship/<type>_dark[_wreck]`. Tested with MockBukkit (`ShipV3Test`, `PirateTest`: spawn, crew, cannons,
  ramming, boarding, plunder, sinking, swimmers, random roll, horn → 16-ship invasion → 3 bosses → victory, all 14 mobs,
  powder monkey). MockBukkit has no projectile flight or pathfinding: cannon hits and swimming aren't really simulated.
  Invasion details (1.3.1): the bosses only come once the flagship is boarded (it anchors alongside: `boarded()` →
  `nextBoss`), or through the water if it's sunk; a boarded flagship stays at anchor until the invasion ends; a boss that
  vanishes (unloaded) is respawned (`bossDueAt`); music starts per fighter the moment they join (`inv.music`). After a win
  the flagship is `beaten`: it stays (its hold is plunder) and is discarded a minute after everyone leaves.
  **Built ships (admin)**: `/ship give built_<type> [amount] [player]` (GLOBE_BANNER_PATTERN, PDC `built_ship` = type name;
  in /itemsmenu) or `/ship spawn <type> [owner]` puts a FINISHED ship (default oak / white wool, `Need.ghost`) on the water
  you look at (`layOut(..., prebuilt, owner)` → `Ship.completeAll()`). `prebuilt` is saved; scrapping one gives the built
  item back, never its blocks. `/ship list all` #numbers (`numbered()`) skip skeleton ships, so they don't shift. Tested in
  `BuiltTest`.
  **Getting off an unanchored ship (1.3.2)**: it has no barriers, so stepping off dropped you through the deck. `onDismount`
  now cancels the sneak while it isn't anchored: the captain (or anyone, when nobody's steering) waits in the seat while it
  brakes and drops anchor (`gettingOff`, `anchorForLeave` → `anchorToLeave()`), then `letOff()` puts them on the solid deck;
  if it can't anchor there (land, 3 tries) they go over the side (`overboard()`: water beside the hull). A passenger while
  someone else sails is kept seated; a second separate sneak within 2 s (`overboardAsk`, holding sneak doesn't count)
  puts them overboard. Tested in `LeaveTest`.
  **1.3.3**: Performance: a ship is one display PER BLOCK, so an invasion (16 ships, ~3500 blocks) spawned in one tick froze
  clients. `spawn()` now only makes the root/seats; `spawnMore(n)` adds blocks, and `FaultlineShips.tick` shares
  `spawn-blocks-per-tick` (120) between ships still filling (nearest first; `spawningBlocks()`). Rotation packets (one per
  block) are throttled: every 3 ticks for skeleton ships, every 15 when no player is within 64 (`viewerNear`).
  Invasion bosses: once the escorts are sunk the flagship heaves to and anchors where it is (`heaveTries`); all three bosses
  stand on its deck (`DECK_SPOTS`: Son bow, Commander amidships, Captain poop deck); the ones whose turn hasn't come are
  `waiting` (no AI, invulnerable, "(waiting)"); players climb its real ladders (both sides, from the water). Flagship sunk
  first: waiting bosses are removed and come through the water instead. The WRAITH kind is now the "Ghost Pirate":
  invisible + glowing outline, slow falling, soul particles, vex sounds (the Captain's ghost crew and some ship crews).
  **1.3.4**: skeleton ships sail on while out past the draw range (`Ship.tick`/`think` run when `unseenLoaded()`), so
  they keep chasing you instead of freezing; a lone ship sees you from `pirates.sight` 200 (was 110), invasion ships hunt
  fighters from `pirates.invasion.sight` 400 (none of the fleet drifts off).
  **1.4.0 walkable deck** (`walkable-deck: true`): player ships keep a solid deck UNDER SAIL. `updateDeck()` lays barriers on
  every built cell above the water at the ship's current pose (deck-level cells cover every block they overlap, so no gaps
  at an angle), never inside anyone, only into air, and takes the old ones out; `carry()` moves everyone standing on it by
  the spot under them (VELOCITY, plus their own walking momentum: a teleport every tick would freeze their movement until
  confirmed). Standing up from a seat under sail = on the deck; raising the anchor no longer forces anyone into a seat.
  Fell off? Hold Jump against the hull for 0.5 s (`climbers()`) or right-click the hull from the water (`climbAboard`).
  The barriers are recorded in each chunk's PDC (`deckMark`); `cleanDeck` removes any no ship owns when a chunk loads
  (crash safety). The ship's own deck blocks don't count as obstacles in `clear()`. No swell bob under walkers.
  Skeleton ships keep seats. Tested in `DeckTest`.
- **Meteor strikes** (`FaultlineItems/Meteor.java`, config `meteor:`): every `every-minutes` (240 = 4 h, real time; 1.2.11) a meteor falls in the
  Overworld `min/max-distance` (1000) from a random survival player (a server config still saying 60 / 250-600 is read as
  240 / 1000: `everyMs()`, `distances()`). The next strike's time is saved in `plugins/FaultlineItems/meteor.yml` (restarts
  used to reset the timer); no spot / nobody in survival = try again in `retry-minutes` (10), not a whole interval. Land
  that far out is often ungenerated: `findFar` generates it in the background (`getChunkAtAsync(.., true)`, 8 tries,
  `generate-land: true`) and runs the same checks. Announced a minute ahead (chat, title, boss bar with
  X/Z). Only generated chunks, only natural ground (`NATURAL` set; never touches `LEGACY_` materials). `base()` (1.1.3) keeps
  random ones off builds and bases within `build-check-radius` (48): any player-made surface block (stripped logs / bark count;
  upright logs, leaves and an old meteor's crater don't), a block entity near the surface (chest, bed, furnace...; not bee
  nests, spawners or deep dungeon chests), a chunk with `base-hours` (3) of inhabited time, or an online player's respawn point.
  Inside a village (`getStructures`, box + 8) its houses/chests don't count. Admins aim one anywhere: `/meteor target`
  (where you look, 200 blocks), `/meteor at <x> <z> [world]` (console too); `/meteor check` says if your spot is avoided. Impact: crater of magma/blackstone/basalt, a rock with 3 Ancient Debris, the CORE (Gilded
  Blackstone) on top, guards: 4 Molten Husks, 4 Meteor Crawlers (endermites), 1 Star Sentinel (blaze) (tags
  `faultline_meteor_*`, `faultline_star_sentinel`). First to break the core gets `meteor.loot` (locked to them) +
  `index stat <p> meteors 1`. Unclaimed after 30 min = obsidian. Tested in the items harness (`EventsTest`).
- **The Caravan** (`FaultlineItems/Caravan.java`, config `caravan:`): every 3 h (first 30 min after start) a Wandering Trader
  "Caravan Merchant" (tag `faultline_caravan`, 2 trader llamas, a camel) camps 40-100 blocks from a random survival player for
  60 min (announced). 8 offers from `caravan.stock` (`id:emeralds:diamonds:netherite:weight:uses`; prices only in diamonds,
  emeralds, netherite). Trades = `index stat <p> caravan_trades 1` (Paper `PlayerTradeEvent`). His chunk has a plugin chunk ticket while he's there
  (non-persistent entities vanished with an unloaded chunk); the meteor's landing chunk too. Tested in `EventsTest`.
- **Grimtusk, the Piglin Warlord** (`FaultlineBosses/Warlord.java`, config `warlord:`): Warlord's Challenge (4 gold blocks,
  4 blaze rods, netherite scrap; PDC `warlord_item=challenge`) raised in a Bastion Remnant (`inBastion`: any of a 5x5-chunk
  `World#getStructures` lookup, `locateNearestStructure` within 96 blocks, or 40+ blackstone-brick/gilded blocks within 8; the
  3x3-chunk-only check failed on the live server). He spawns via `spawnSpot` (3x3x3 open, solid floor, no lava, clear line
  from you, 3-8 blocks ahead; also used by /grimtusk summon and the rush). Ironhide has Fire Resistance. Real mobs
  (Java + Bedrock see the same): a scaled Piglin Brute (tag `faultline_grimtusk`; real hp tracked in `hp`, entity healed every
  tick) riding IRONHIDE (scaled hoglin, `faultline_ironhide`, 600 hp). 3000 hp +20%/extra fighter; phase 1 mounted = 60% less
  damage; Ironhide dead or 70% = phase 2; 35% = phase 3 (enraged). Moves: Tusk Charge, Golden Cleave, Rally (Bastion Guards /
  Crossbowmen), Fireball Volley, Magma Slam, Soul Fire Lines, Warcry, Gold Rain (block displays). Music: vanilla Pigstep.
  Drops Mythic/Goodie bags, gold, debris, netherite and 25% GRIMTUSK'S CLEAVER (netherite axe, `warlord_item=cleaver`,
  right-click Golden Cleave, 12 s). Boss form hooks done (`pilot`, `takeMove`, `morphMoves`, `MORPH_KINDS`, `startMorph`).
  Tested in the bosses harness (`WarlordTest`).
- **Boss Rush** (`FaultlineBosses/BossRush.java`, config `boss-rush:`, data `bossrush.yml`): started by right-clicking the
  **Boss Rush Sigil** (NETHER_STAR, model `faultline:boss_rush_sigil`, PDC `rush_sigil`; 8 netherite blocks around a nether star;
  never used up, never a crafting ingredient; `/bossrush sigil [amount] [player]` for admins, in /itemsmenu, Index entry);
  `/bossrush start` needs a Sigil in your inventory (admins don't). Leader + everyone
  in survival within 10 blocks; refused while any boss is out. Stages (`boss-rush.bosses`, 14): rotbeard (beach), bulwark
  (badlands), great_hog (crimson forest) = the raid bosses, spawned through `zraid spawnboss <egg> <world> <x> <y> <z>`, and
  queen_spider (dark forest / pale garden, FaultlineItems `queenspider spawn <world> <x> <y> <z>`); these "external" bosses
  (`BossRush.RAID_TAGS`) are followed by their tag (`raidBoss`; their death = stage won, drops cleared at MONITOR; skipped
  without their plugin), then
  demoneye, frostbeard, dune, frostmaw, don, kraken, jacob, rocco, grimtusk, and the finale explorer: the team goes to the
  void path (`below.returns` set, cleared at the end), the leader is put in his arena and fights alone; his defeat goes through
  `rewards()` (hooked like the others); losing (all downed) in a rush skips the speech/Yes-No and fails the rush. A server
  config still holding 1.3.0's or 1.3.1's list (`OLD_DEFAULT`, `OLD_DEFAULT_2`) gets the new full list. Each: teleport to that boss's biome (found once with `locateNearestBiome` /
  `locateNearestStructure`, saved; `/bossrush setarena <boss>`), 5 s countdown, `rushSummon`. The search (1.3.4) runs from where
  the team stands and from the world spawn, at least 6400 blocks / 150 chunks out (`Math.max` over the config, since server
  configs still say 4000), then `FALLBACK` biomes; a spot in water walks out to dry land (`dryNear`, 64 blocks); nothing found
  at all = the team fights that boss where it stands (not cached). Before this Rotbeard (beach) and the Bulwark (badlands)
  were skipped on the live server. Every
  boss's reward method starts with `rushDefeated(kind)` + `if (rushSuppressLoot(kind)) return;` (a NEW BOSS needs both, plus
  cases in `rushSummon`/`rushAlive`/`rushKill`/`bossOutName`). Death = out with keep-inventory; all out or the boss leaves =
  fail. Win: rewards, `index stat <p> boss_rush 1`, best time per player in `bossrush.yml` (`/bossrush top`), sent home. Tested in `WarlordTest` and `RushTest`
  (Raids + Items loaded alongside: Bulwark, Queen Spider, Rotbeard, then the Explorer, win and loss).
- **Achievements** (`FaultlineIndex/Achievements.java`, `achievements:` in index.yml): goals by `defeat` (+ `need`
  all/any/n), `chapter`, `found(-items/-creatures)`, or `stat` + `count`. Checked on every unlock/kill/stat and 10 s after
  join (retroactive). Done = server-wide message, title, reward items/xp, cosmetic via `cosmetic unlock`. Saved in
  progress.yml (`achievements.<uuid>.done/stats`). The cover's "Achievements" line opens the book. Tested (`AchievementTest`).
- **Seasons** (`FaultlineCosmetics/Seasons.java`, config `seasons:` with code defaults): Halloween 10-01..11-02 (Candy),
  Winter 12-01..01-06 (Presents). In season, hostile mobs (`Enemy`) killed by a player drop 1-2 tokens 8% (not from spawners / trial spawners / spawn eggs: no farms) (PAPER, model
  `faultline:season_candy/present`, PDC `faultlinecosmetics:candy/present`); the Seasonal Shop (button in /cosmetics,
  `/cosmetics shop`) sells cosmetics with `season:` + `price:`. `/cosmetic season <id|off|auto>` forces one. New cosmetics in
  the JAR's config load even when the server's config.yml doesn't list them (read from the defaults; Bukkit's
  `getConfigurationSection` would create an empty section, so only ids the server's file really has are read from it).
  Hats are head-pixel models (front -z, display.head scale 1.6), Bedrock helmet attachables. Tested (`SeasonTest`).
- **The wild update** (Bosses 1.4.0, Items 1.2.0, Ships 1.5.0, Index 1.6.0). All models come from
  `python3 tools/wild_assets.py <unpacked-pack> bedrock --preview tools/previews` (Java item models `faultline:wild/<part>`,
  flat items, 9 discs `faultline:disc/disc_<id>`, 17 trophies `faultline:trophy/trophy_<icon>`, worn knight_helm/mining_helmet,
  Index icons + glyphs; Bedrock: `bedrock/FaultlineWild.mcpack` + `faultline_wild_mappings.json`). It writes `WildParts.java`
  (each part's display scale/offset). Bedrock sees every model part as the helmet of an armor stand only Floodgate players
  can see (`setVisibleByDefault(false)` + `showEntity`); hitting the stand hits the boss.
  **Wild bosses** (`FaultlineBosses/Wild.java`: rig, shared fight loop, summon items, weapons; config `wild:`; summon items
  only work in their biome unless `wild.require-biome: false`): **Leviathan** (`Leviathan.java`, 900 hp (all five are mini bosses since 1.4.12), Abyssal Lure over
  deep ocean or 0.4% at night sailing; Tidal Ram / Water Spout / Bile / Roar / Coil / Tail Sweep / Drowned; hits near a ship
  break its parts via ShipLink, `ship-damage.*`; drops Tidebreaker trident 25%), **Sandworm King** (`Sandworm.java`, 3600,
  Sandworm Drum on sand; Eruption / Slither / Boulders / Quicksand / Brood (Larvae) / Tremor; Sandworm Fang sword: Burrow Dash),
  **Lich** (`Lich.java`, 2800, Cursed Phylactery in the Deep Dark; Soul Bolts / Raise (Revenants with players' heads) / Death
  Ring / Blink / Darkness / Life Drain; phase 2: 3 end-crystal phylacteries (`Lich.CRYSTAL_TAG`) shield him; Staff of the
  Lich), **Frost Wyrm** (`FrostWyrm.java`, 3200, Frozen Horn on frozen peaks; Breath / Icicles / Dive / Roar / Wyrmguard
  Archers / blizzard; Glacial Fang), **Stone Golem** (`StoneGolem.java`, mini boss 900, wakes 3%/min near explorers in lush
  caves; invisible IronGolem walks it; Pound / Boulder / Crystal Spikes / Overgrowth; drops Ancient Gear Parts). All 5 have
  boss form hooks, Boss Rush stages (19 total, `OLD_DEFAULT_3` upgrades old lists), Index entries, /itemsmenu eggs and
  summons. Music is vanilla discs (`wild.<boss>.music`). Tested in the bosses harness (`WildTest`).
  **Weather** (`FaultlineItems/Weather.java`, `weather:`): Aurora (night, XP x2, enchanting x0.7; Java ItemDisplay
  ribbons `wild/aurora`, Bedrock dust), Sandstorm (deserts: dust, slowness, husks, treasure from dug sand), Blizzard (snowy:
  freezing unless near fire/leather, strays), Eclipse (midday dark 5 min, neutral mobs hostile), Locusts (Vex with
  `wild/locust` model eat a farm's crops; drive off = `index stat locust_swarms`). `/fweather`.
  **Creatures** (`Creatures.java`): Mimics (1% of loot-table chests; Spider + `wild/mimic_base`/`mimic_lid`; chest loot on death;
  `index stat mimics`), Skeleton Knight patrols (night, skeleton horses, Knight's Helm 6%, gone at dawn). `/fcreature`.
  **Gear** (`Gear.java`, PDC `faultlineitems:gear=<id>`): Mining Helmet (night vision + headlamp LIGHT block), Lantern of Souls
  (held: light, reveals invisibles, clears Darkness, makes the Ghost Ship solid), Knight's Helm, Ancient Gear Part → Ancient
  Core accessory, Museum Pedestal (chunk PDC `pedestals` + `pedestal_<local>`; ItemDisplay / Bedrock stand), 9 boss music
  discs (`disc_<id>`, jukebox plays `faultline:<boss>.music`, TileState PDC `jukebox_disc`), grappling hook recipe.
  **Ghost Ship** (`FaultlineShips/GhostShip.java`, `pirates.ghost-ship:`): "The Wailing Mary", a Galleon of pale glass/pale oak
  (`Brain.ghost`), at night near ocean sailors (3%, x2 in rain) or `/ship pirates ghost`. Untouchable/unboardable unless a
  Lantern of Souls is within `lantern-range`: then she anchors and Captain Hollow (invisible wither skeleton + `wild/ghost_captain`,
  400 hp: blink, sweep, wraiths, soul chains) fights; loot + `index stat ghost_ships`. Fades at dawn / after 20 s without the
  lantern. Bedrock model `faultline:ship/galleon_ghost` (`ship_assets.py`). Tested (`GhostTest`).
  **Museum** (`FaultlineIndex/Museum.java`, Index `config.yml` `museum:`): first defeat of a boss = its trophy (PAPER,
  `faultlineindex:trophy`); each defeat rolls `disc-chance` for that boss's disc (`givedisc`; one roll per defeat even when
  the kill credit and `index discover` both fire); server firsts saved in `museum.yml`, announced, shown in `/museum`.
  **Discord** (`Discord.java`, `discord:`): webhook URL; broadcasts matching `discord.forward` regexes are posted (1.5 s apart).
  **/serverstats** (`ServerStats.java`): totals from vanilla stats + Index stats, top 3s, cached 5 min (counted async).
  Tested in the Index harness (`MuseumTest`).
- **1.4.3 / 1.2.3 fixes (wild update)**:
  Bosses: `Wild.ground(world, l)` searches 12 up / 32 down and otherwise keeps the caller's height (it used to jump to the
  SURFACE after 16 blocks, so a Lich called in the Deep Dark appeared 60+ blocks overhead and left unseen); the Lich
  spawns at `Wild.freeSpot` near you. Natural spawns for all four (`Wild.naturalSpawns`, every minute, `wild.<kind>.natural-chance-per-minute`:
  Lich 0.04 in the Deep Dark, Sandworm 0.015 on desert/badlands surface by day, Frost Wyrm 0.015 on peaks/slopes/groves,
  Leviathan 0.02 in a deep ocean swimming/boat/ship; `wild.natural-gap-minutes` 20 between any two; never in `faultline_void`).
  A boss with anyone (even a creative admin) in range never times out (`watched()`). Summon/tick also catch `LinkageError`.
  The Frost Wyrm's icicle is `Bukkit.createBlockData("minecraft:pointed_dripstone[...]")` (26.2 moved
  `PointedDripstone.Thickness` to `Speleothem.Thickness`: the 1.21.11 jar's typed call was the only API difference).
  Items Weather: the ECLIPSE never touches world time or gamerules (1.2.0 set doDaylightCycle false + time 18000 and only
  undid it on a normal end: a crash left the world frozen, which stopped FaultlineEvents' nights). Now `setPlayerTime(18000)`
  per player + a private BLACK SUN (`wild/black_sun`, Java model 50 blocks overhead; Bedrock: dust ring) + the beasts; a world
  found stuck (cycle off, time ~18000) is repaired on start. The AURORA was 5x the model (~120 blocks wide) and everyone's
  ribbons were visible to everyone: now 3 private ribbons per player (`aurora.size` 1.2 = ~29 wide, `aurora.height` 38).
  No Weather event starts while FaultlineEvents has an event active (`faultlineevents:active_event` on the main world,
  `FaultlineItems.faultlineEvent()`) or in `faultline_void`. Tested (`WildSpawnTest`, `WildItemsTest`).
  FaultlineEvents (Blood Moon, Snowy Day, Lantern Night, fog...) is a separate plugin whose source is NOT in this repo.
- **Wild models v2 (Bosses 1.4.4)**: the FROST WYRM is a real dragon: `wyrm_body` (chest, four clawed legs, spine spikes),
  3 `wyrm_neck` segments placed along a quadratic curve from the chest to the head in `FrostWyrm.place()` (low and forward
  for Frost Breath, high for the landed roar), a horned `wyrm_head` (`headBase()` = the head's joint, `mouth()`), bat wings
  with finger bones and scalloped membranes (`_stairs`/`_lerp_pts` in wild_assets.py), and a `wyrm_tail_tip` ice blade on the
  last tail segment; landed = body 2.45*k up (on its legs), wings folded at 62 degrees. New models for the Leviathan (fanged
  jaws, horns, crest, gill frills, barbels, glowing spots, crescent fluke), the Sandworm King (armour rings, four fanged maw
  petals, two tooth rings, a glowing throat, a crown of bone spikes, a stinger) and the Lich (hooded skull, crown, collar,
  pauldrons, ribcage with a soul, raised hand with an orb, skull staff, soul wisps). The STONE GOLEM has a separate
  `golem_head` (tracks its target) and eased poses (`animate()`/`place()`: every joint eases toward the move's target: idle
  breathing, walk sway and bob, Pound wind-up then slam, Boulder bend-heave-throw with one arm, Spikes fists into the
  ground, Overgrowth kneel, a flinch on big hits, and a death where the whole body (not just the torso) sags and topples).
  `python3 tools/wild_rig_preview.py tools/previews` renders the whole bosses assembled with the plugin's pose math
  (`rig_<boss>.png`).
- **1.4.5 / Ships 1.5.3 (stuck bosses, calling the Ghost Ship)**: the Frost Wyrm keeps `4.5*k` clear of the terrain under it,
  where it's going and 5*k ahead (`terrainTop`, a 3x3 of heightmap columns), brakes to 15% speed while a wall ahead makes it
  climb, and is born above the terrain; `fly(..., landing=true)` (the roar) skips that. The Lich only moves where it fits
  (`Wild.fits`: floor within 3 blocks, 3 clear blocks), so it can't drift into cave walls. Creative/spectator players near a
  boss with no fighters get an action bar every 3 s (it ignores creative; `/<boss> kill`). Ghost Ship: the **Phantom Bell**
  (BELL, PDC `faultlineships:phantom_bell`, glint, never placed; ghast tears + phantom membranes around a bell; Index entry;
  /itemsmenu) rung at night at sea calls her (`GhostShip.call`): from a FaultlineShips ship, or the water top under a boat /
  a swimmer in an ocean biome (`waterTop`, `spawnAt`). `/ship pirates ghost [player]` (console too, any time of day: `forced`
  = she doesn't fade at dawn), `/ship pirates bell [n] [player]`. Tested (`TerrainTest`, `BellTest`).
- **1.4.6 / Items 1.2.7 / Index 1.6.2 (bug hunt)**: wild bosses: `Wild.live(kind)` clears a boss whose body is gone or
  that stopped updating for 5 s (it used to hang frozen in the world and block every summon with "already out there");
  `leave()` takes it off the list FIRST and every cleanup step runs on its own (`quietly`); `sweep()` (every 5 s, and
  `/<boss> kill`) removes model pieces/stands/hitboxes/Lich crystals no live boss owns. With nobody in survival nearby a
  wild boss fights creative players for show (`onlookers()`, `wild.fight-creative: true`; no damage either way, never a
  fighter). A summon item that can't spawn its boss isn't used up. Items: a pedestal with someone else's exhibit can't be
  broken by others; explosions skip pedestals, pistons can't move them; a blown-up jukebox ejects its boss disc; a Mimic
  that unloads (or the server stops) turns back into its chest with the loot table; Mimics wake at HIGH priority (after
  claim plugins); sandstorm treasure ignores sand placed during the storm and caps at `treasure-per-player` (3); headlamp
  LIGHT blocks are recorded in the chunk PDC (`headlamps`) and cleaned on chunk load. Index: `/serverstats` reads offline
  players' stats files (`players/stats` on 26.x, else `stats`) off the main thread, cached 5 min.
  Tested: `OceanTest` (bosses), `AuditTest` (items), `MuseumTest` (index).
- **1.4.7**: the Leviathan's body swam ~1 block under the surface, so players only saw its head. `Leviathan.hump(i, p)`
  lifts each segment near the surface (0.2*k plus a wave of humps running down the body, `wild.leviathan.hump-height` 1.5);
  only the drawn pose moves (the chain and hitboxes don't); nothing while `submerged` (Tidal Ram) or dying.
- **Items 1.2.8 (Moon Stone flight glitch)**: Bat Form saved `allowFlight` when it started and put it back when it ended,
  so a flag left on from anywhere (an older bug, a crash, saved in the player file) was carried on forever = creative-style
  flight in survival. Now the end only keeps flight for a running Cloud Potion or `FlightGuard.mayFly` (op,
  `faultline.fly`, `essentials.fly`, `bosses.admin`). `FlightGuard` (every 10 ticks, `flight-guard.enabled`) takes flight
  away from any survival/adventure player without a reason (not a bat, no Cloud Potion, no permission), with Slow Falling;
  a Cloud Potion keeps its double-jump flag but never real flight. `AccessoryManager.hasEquipped` reads the open
  /accessories menu (an accessory taken out kept its powers until the menu closed). Moon Stone lifesteal ignores armor
  stands. Tested (`FlightTest`, `ItemsTest`).
- **Bosses 1.4.8 (bosses that couldn't be summoned again)**: `safely()` counts failed updates per boss (`failStreak`);
  100 in a row (5 s) = `stuck(what)` removes that boss (Demon Eye, Frostbeard, Dune worm, Don, Kraken, Jacob, Rocco,
  Grimtusk, the Lost Explorer's fight via `bossPart(..., "fight", ...)`, the Boss Rush) so it can be summoned again; before,
  an error every tick kept it "already here" until a restart. Rocco's/Grimtusk's `leave()`, Don's `cleanup()` (each step
  in `bossPart`) and the Boss Rush's `finish()` clear their field FIRST. The Diamond Jacob horn, Rocco's contract and
  Grimtusk's challenge are used up only once the boss really appeared (and never in creative for the horn); a Kraken
  Bait worm that sank but brought no Kraken comes back. Tested (`StuckTest`: every boss after its area unloads, and the
  watchdog).
- **Bosses 1.4.9**: `Leviathan.sea(Block)` counts kelp, seagrass, bubble columns and waterlogged plants as sea water
  (only plain WATER counted: over kelp forests `seaSpot` found no 8-deep column and `/leviathan summon` failed in the
  middle of a deep ocean; swimming, kelp at the surface read as the coast). A failed wild summon tells the real reason
  (`Wild.lastFail`: the IllegalStateException's message, or the error) instead of a hard-coded "needs open water / no room",
  and sweeps whatever the half-built boss left behind. Tested (`KelpTest`).
- **Bosses 1.4.10**: `Wild.live()`'s "stopped updating" check counts server ticks (`lastTick = ticks`, stale after 100),
  not real time: a 5 s lag spike made every live wild boss look stale and the next summon deleted it. A failed summon's
  chat message names the error and where in our code it broke (`Wild.where(e)`). Tested every wild boss through its
  command with Items + Raids loaded, survival and creative (`CmdTest`, run with `runall.sh`).
- **Bosses 1.4.11 (THE reason the wild bosses wouldn't summon)**: on the live 26.2 server `world.spawn(at, Slime.class, ...)`
  throws `IllegalArgumentException: Cannot spawn an entity for org.bukkit.entity.AbstractCubeMob` (Paper rewrites an old
  plugin's `Slime` to the new abstract `AbstractCubeMob`). Every wild boss's hitboxes were spawned that way, so the model
  appeared, the constructor threw, and the boss hung frozen / "couldn't appear". MockBukkit doesn't reproduce it.
  RULE: spawn mobs BY ENTITY TYPE: `Wild.spawnAs(world, at, EntityType.X, X.class, setup)` (= `spawnEntity(at, type,
  false)` + setup), like the older bosses' `(Slime) world.spawnEntity(at, EntityType.SLIME, false)`. Never
  `world.spawn(at, Slime.class, ...)`. Wild minions (`minion(at, EntityType, Class, ...)`), the Golem's walker, the Lich's
  crystals, Don's hitbox and the Kraken's drowned use it too.
- **Bosses 1.4.12 / Index 1.6.3: the 5 wild bosses are all MINI BOSSES**: 900 health, +25% per extra fighter (the Golem's).
  `Boss.baseHealth()`/`perFighter()` treat a server config still holding the old defaults (`Wild.OLD_HEALTH` 2400/3600/
  2800/3200, +0.2) as the new one. Damage on the Golem's 10-14 scale (Tidal Ram 15 -> 14, Eruption 16 -> 14). Animations:
  `Boss.flinch`/`flinchAmt()` (set by a hit of 4+, every boss recoils); Lich `animate()` (eased lift/pitch/roll/scale/spin
  per move: lean into the drift, bolt recoil, Raise arms-up then slam, Ring spin, Blink shrink to a speck and back,
  Darkness hunch/throw, Drain high and shuddering; death: rise, crumple, dwindle); Sandworm `place()` draws a sideways
  slither + ring ripple (per-segment scale) + twist on the chain (hitboxes untouched), head shake on Eruption/Tremor, head
  lift for Boulders/Brood, death sinks ring by ring (`sinkFrom`); Leviathan head eases + recoils + snaps at the end of the
  ram, the roll runs down the body with the humps, death rolls belly-up (`deathRoll`); Frost Wyrm eases its neck between
  poses (it SNAPPED), banks into turns (`aBank`), power-stroke wingbeat with the body bobbing, wings swept back in a dive,
  head tracks its target (not during Breath), tail swings, death = limp wings + spiral. `Part.pose` skips non-finite
  positions (real Paper throws on them). Index entries say "A mini boss ..., 900 health".
- **26.2 runtime audit (Bosses 1.4.13, Items 1.2.9, Raids 1.0.2, Ships 1.5.4)**: things MockBukkit never catches.
  (1) Only `Slime`/`MagmaCube` changed hierarchy in 26.2 (new `AbstractCubeMob`; `SulfurCube` is new): no other
  `world.spawn(X.class)` is affected; every class-spawned type is concrete. (2) `DamageCause.HOT_FLOOR`/`CAMPFIRE` are
  deprecated in 26.2 and magma blocks / campfires now hurt as `CONTACT` (block in the `EntityDamageByBlockEvent`): the
  Ember Ward (`JacobGear.hotBlock`) and Grimtusk's magma immunity cover it now. (3) Paper throws on a non-finite velocity
  (a push "away from" a point you stand exactly on = 0/0 = NaN), MockBukkit doesn't: EVERY `x.setVelocity(v)` in Bosses,
  Raids, Ships and Items now goes through each plugin's `Safe.vel(entity, v)` (non-finite = a plain hop). RULE: use
  `Safe.vel`, never `setVelocity` directly. Checked too: config material names all exist in 26.2; `setHealth` calls are
  clamped; methods deprecated "does nothing" in 26.2 (arrow knockback, bite chance, keep-spawn) aren't used. To repeat:
  compile with `-Xlint:deprecation,removal` against both APIs and diff the warnings.
- **Bosses 1.5.0 (new moves, the Lich remodel)**: the LICH is a jointed rig now (`lich_body` origin = waist, `lich_head`
  on the neck, `lich_arm` x2 on the shoulders, `lich_staff` in the right hand, `lich_cape` between the shoulder blades;
  `tools/wild_assets.py lich()`, grave palette `L_*`, painters `cloth`/`bone`/`skull_face`/`ribs`; the old one-piece
  `wild/lich` model is gone). `Lich.target()` gives each move a pose (`Pose`: lift/lean/roll/spin, head turn, arm
  swing/spread, staff tilt, cape), eased in `animate()`, drawn by `draw()` (joints `SH_L/SH_R/NECK/CAPE/HAND` match the
  model; `tools/wild_rig_preview.py` mirrors it). Bolts leave his left hand; in death the staff topples on its own.
  New moves: Lich GRASP (bone-finger hands under everyone, slowness 6) + SPEARS (phase 2, lines of bone spikes);
  Leviathan BREACH (leaps out in an arc, crashes on a ring, `ship-damage.breach`) + WHIRL (phase 2, pulls players/boats
  in, `ship-damage.whirlpool-per-second`); Sandworm SANDBLAST (rears up, sweeping blinding cone) + DEVOUR (phase 2:
  swallowed = held in its mouth `devour-hold-ticks` 50, spat out; `devour-break` 4% of max hp in hits frees you;
  released safely on death/leave); Frost Wyrm SHARDS (ice-shard snowballs, tag `FrostWyrm.SHARD_TAG`, landed in
  `Wild.onBoltHit`) + GUST (phase 2, rears back, three wingbeats push you away). All in boss form and the Index.
  Tested (`WildTest`: every move, the Lich's joints, Grasp, Devour + breaking free).
- **Wild models v3 (Bosses 1.5.6, Items 1.2.21)**: `tools/wild_assets.py` parts can be `java_only` (an ItemDisplay, no
  Bedrock stand) or `bedrock_only` (a stand, no display): `WildParts.JAVA_ONLY` / `BEDROCK_ONLY`, read by `Wild.Part`
  (a stand-only part still follows `pose`). Java: the Leviathan's and the Frost Wyrm's lower jaws are their own parts
  (`lev_jaw` hinge (0.5,-0.55,0), `wyrm_jaw` hinge (0.4,-0.5,0), posed at `Lich.joint(head, frame, hinge)` with
  `Lich.rotZ(-open)`), the Sandworm's maw is 4 `worm_petal`s on the rim (`rotX(90*i)` then `rotZ(open)`, hinge (1,1,0)
  turned): eased, idle breathing/panting, wide on roars / breath / bile / eruptions / Devour, snapping shut on bites, the
  ram, shard volleys and flinches. Bodies are bevelled (`rounded()`). Bedrock (stands only turn left/right): one head
  each (`lev_head_b`, `worm_head_b`, `wyrm_head_b`) with the jaw/petals as BONES, and the pack loops animations
  (`Part.bone`/`anim`/`bone_all`, `bedrock_anim` → `animations/*.animation.json` + the attachable's `scripts.animate`):
  jaws, petals, fins, crest, fluke, bristles, stinger, wing beats, tail blade, the Lich's nod / sleeves / cape / hover.
  Previews: `python3 tools/wild_rig_preview.py tools/previews` (jaws and petals + a Bedrock view).
- **The camera batch (Items 1.2.23, Index 1.6.13, Kingdoms 1.1.0, Cosmetics 1.1.3, Bosses 1.5.8, LimboBlackMarket 1.0.2)**:
  **/camera** (`Items/Camera.java`, `camera:`, `camera.yml`): spectator free cam where you stand, `/camera` again = back
  at the saved spot/game mode/fly speed; `/camera speed 1-10`; tethered `max-distance` (64) in that world (pulled back
  every 10 ticks; SPECTATE teleports outside it cancelled; spectating entities blocked) unless
  `faultline.camera.unlimited`; refused within `combat-seconds` (10) of a player/monster fight, during a race, riding,
  falling. Quit/shutdown restore first; a crash is restored on join from camera.yml. Another plugin/`/gamemode` leaving
  spectator just ends it. **/suggest** (`Index/Suggestions.java`, `suggestions:`, `suggestions.yml`): a numbered embed to
  `discord.suggestions-webhook-url` (else the public channel; `Discord.suggest`), online staff, `/suggestions [n]`.
  **Discord /stats** (`Kingdoms/DiscordGateway.java`, `discord.slash-commands`): the Kingdoms bot keeps a GATEWAY websocket
  (java.net.http, intents 0, heartbeats, zombie detection, reconnect with backoff, 4004 = bad token stops it; `gen` makes
  a close + an error reconnect once), registers a guild command (PUT `/applications/{id}/guilds/{g}/commands`), answers
  type 5 (deferred) then PATCHes `/webhooks/{app}/{token}/messages/@original` with an embed built on the main thread:
  kingdom, status/last seen, play time, LimboBlackMarket kills/deaths/streak/tier (reflection), mob kills,
  `FaultlineIndex.discordStats(uuid)` (reflection). `discord.gateway-url` (hidden) points the test at a fake gateway.
  `/kingdoms` status shows it. **Friendly fire vote** (`FaultlineKingdoms.friendlyFire(k)`): `/kingdom ff <on|off|clear>`
  (`Member.ffVote`, saved as `ff-vote`; cleared when your kingdom changes); majority once `pvp.friendly-fire-vote.min-votes`
  (3) voted, a tie / too few = `pvp.friendly-fire`; admins `/kingdoms ff <k> <on|off|vote>` (`ff-forced` in data.yml);
  flips are announced to that kingdom (`ffChanged`). **Bedrock menus**: `KingdomForms` (link/verify are text boxes:
  `BedrockForms.input` = Cumulus `CustomForm`), `CosmeticForms` (slots, wear/take off, locked = how to get it, the
  Seasonal Shop with Buy? = `FaultlineCosmetics.buy`/`toggle`/`takeOff`, shared with the chest menu); `bedrock-forms` in
  each config. **Boss death messages** (`Bosses/DeathMessages.java`, `death-messages:`): `FaultlineBosses.hurt` notes the
  source's (stripped) custom name; a death within `seconds` (5) gets a line from `LINES` (first fragment that matches the
  name) or "was slain by <name>". **Bug fix**: LimboBlackMarket's `KingdomDataReader` read the OLD kingdoms plugin's
  `kingdoms.yml`, so /stats showed no kingdom and friendly-fire kills were never logged: it asks
  `FaultlineKingdoms.kingdomName(uuid)` by reflection now. Tested: `CameraTest`, `FormsItemsTest` (items), `SuggestTest`
  (index), `GatewayTest` (kingdoms: a tiny websocket server + fake REST), `CosFormsTest` (cosmetics), `DeathMsgTest`
  (bosses), `MarketKingdomTest` (BlackMarket + Kingdoms loaded together).
- **The balloon batch (Items 1.2.24, Index 1.6.14, Bosses 1.5.9)**:
  **Hot air balloons** (`Items/Balloons.java`, `balloon:`, `balloons.yml`): PAPER item, model `faultline:hot_air_balloon`
  (icon from `tools/events_assets.py`, Bedrock via `tools/bedrock/items.tsv` + `bedrock_pack.py`), PDC
  `faultlineitems:balloon_item` = "color;fuel"; recipe WWW/WFW/SBS (any wool, campfire, string, barrel); `/giveballoon`,
  /itemsmenu, Index entry (weapons). Set up = right-click a block (`room()`: 2x2x3 basket + the envelope's air); board =
  right-click while standing in the basket (`basketAt`; nothing to click, so Bedrock works); a marker armor stand seat
  (tag `faultline_balloon`) + 17 block displays (basket, rim, ropes, burner, envelope, band, crown; `shape(color)`)
  teleported together every tick (the seat with `RETAIN_PASSENGERS`). Controls from `Player#getCurrentInput()`
  (`input`, swappable in tests): Jump up, Sneak down, W/A/S/D where you look, Sprint x1.6; eased velocity; per-axis
  collision (`blocked`: basket corners, envelope edge/top); `landed` on touching down. Fuel (seconds; coal/charcoal 60,
  blaze rod 150, coal block 600, `max-fuel-seconds`) burns in the air; none = sinks. Dismount cancelled in the air;
  no fall damage riding. Dye recolours; owner/`items.balloon.admin` sneak + punch packs it (fuel/colour kept). Saved every
  30 s + shutdown; respawned on chunk load (`wake`, put on the ground if it was flying); a rider quitting mid-air is
  set on the ground. Bedrock sees the envelope through FaultlineBosses' BedrockFx block mirrors. MockBukkit doesn't
  carry passengers on a vehicle teleport and reports the clicked chunk unloaded (`spawn(b, true)` skips that check).
  **/ping, /coords** (`Items/Where.java`, `where:`, `coords.yml`): a boss bar with X Y Z, facing, biome, dimension
  (twice a second, toggle remembered; `bedrock-coords-on-join`), `/coords share [player|kingdom|all]`, `/ping
  [player|all]` (Bedrock: a menu). **/guide** (`Index/Guide.java`, `guide.yml`): chapters with "#" headings / "-"
  bullets; Java book (contents page with changePage links, `FaultlineIndex.wrapText`), Bedrock forms; the "bosses"
  chapter appends every Index boss entry's `where`; first join says "/guide". **Hardcore Boss Rush** (`BossRush.Run.hardcore`;
  sneak + right-click the Sigil, `/bossrush start hardcore`): a death (or quitting, `hardcore.quit-fails`) fails it for
  everyone; `hardcore.reward-multiplier` (2); own times under `hardcore.` in bossrush.yml (`/bossrush top hardcore`); Index
  stat `boss_rush_hardcore` + achievement `hardcore_champion`. **Grief logs** (`Index/Grief.java`,
  `discord.logs.grief.*`, the logs channel only): lava, fires (not portals/campfires/candles; throttled per player),
  Withers built, and someone else's container broken or looted (containers record their placer in the block PDC
  `faultlineindex:placer` from now on; open = snapshot, close = what left it). Whispers log as "Alice → Bob: text"
  (`Discord.whisper`; /r names the last partner). Tested: `BalloonTest`, `WhereTest` (items), `GriefTest`, `GuideTest`
  (index), `HardcoreRushTest` (bosses).
- **The Watch (Index 1.6.15, Items 1.2.25)**: `Index/Watch.java` (config `watch:`, data `watch.yml`, daily files
  `plugins/FaultlineIndex/logs/watch-YYYY-MM-DD.log`, `/watch [<player>|log [player] [n]|xray [player]|alts <player>|check
  <player>|alerts]`, perms `faultlineindex.watch` / `faultlineindex.alerts` (op) / `faultlineindex.watch.exempt` (false)).
  Severities: INFO (file + /watch log), LOG (+ Discord logs channel), ALERT (+ staff in game with [TP]/[INFO] buttons +
  `Discord.alert`: its own message pinging `discord.logs.alert-role`, max one ping a minute; one alert per player per kind
  per `alert-cooldown-minutes`). **X-ray**: each diamond/debris/emerald VEIN (a new ore of that family not within 4 blocks
  of one dug in the last 2 min) is BURIED when every non-occluding face is a block that player dug (`Miner.dug`, last
  ~6000 positions) else exposed; stone/netherrack dug counted in per-minute buckets; alert when, in `xray.window-minutes`
  (20), buried veins >= `min-buried` (4; debris 3), buried share >= 0.6 and stone per buried vein < `max-blocks-per-vein`
  (60; debris 45). Every diamond/debris vein is a LOG line (`discord-veins`). Ores players place are in chunk PDC
  `faultlineindex:placed_ores` and skipped. TNT/bed mining exposes ores (explosion air isn't the player's), so it isn't
  flagged. **Cheats** (`judged()`: survival/adventure, not exempt, Bedrock skipped by `skip-bedrock`): reach / killaura /
  multiaura from Paper's `PrePlayerAttackEntityEvent` (real attack packets only, so plugin AoE damage and forwarded boss
  hitboxes never count; faultline-tagged victims skipped), reach = eye to hitbox vs the ENTITY_INTERACTION_RANGE attribute +
  tolerance + (both pings x both speeds); fly = 3 s unsupported (`unsupported()`: 2 blocks down under the hitbox corners,
  climbables/liquids/slabs..., no entity within 1.5) without falling; speed > 16 b/s (+3 per Speed level) 2 s running;
  excused: gliding, riptide, vehicles, allowFlight, no gravity, levitation, 2.5 s after a velocity event (knockback, our
  pushes via Safe.vel, ship decks), damage or teleport. Nuker > 22 hard blocks/s twice (Vein Miner tools skipped),
  fastplace > 12/s twice, scaffold = under your feet while running with pitch < 45. **Items** (every 30 s + any container
  opened + `/watch check`): `illegal.items`, enchant over max, unbreakable, custom attribute modifiers, over-stacked;
  items with any `faultline*`/`limbo*`/`dropheads` PDC key are ours and skipped; `illegal.remove` (false). Valuables
  (`DEFAULT_VALUE`, diamonds' worth, shulker contents counted) jumping by `value.spike` (300) in 30 s = LOG; valuables
  someone else dropped picked up (`Item#getThrower`) summed per pair = LOG ("same IP" noted). **Grief**: chunk PDC
  `faultlineindex:builders` ("uuid=n,..", top 8 placers, cached, written on unload / every 5 min); breaking non-natural
  blocks in a chunk whose main builder (>= `min-built` 20) is someone else (you built < 1/4 of theirs) is summed per
  breaker+owner (LOG at 8, ALERT at 64); TNT (source player) / end crystals (last player to hit it) destroying placed
  blocks; other players' pets, villagers (throttled), name-tagged mobs killed; frames/paintings in others' builds.
  **Accounts**: SHA-256(salt + IP) only (`watch.yml` `salt`, `ips.<hash>`, `players.<uuid>.ips`; loopback/LAN skipped =
  Geyser on the same box), shared IP = LOG once per pair, with a banned account = ALERT (ban evasion); creative inventory
  takes (summed every 30 s) and switches to creative/spectator (not plugin spectator = /camera) = LOG. A BEDROCK block
  broken in survival = ALERT. Grief: `Grief.container()` filters by material before
  `getState()` (it copied a block entity on every block placed/broken). **Camera** (Items): can't open inventories, and
  `camera.no-clip` (false) cancels moves whose feet/eye end inside an occluding block (spectator x-ray); stuck = lifted out
  the top. Tested: `WatchTest` (index: x-rayer vs cave finds vs branch miner, placed ores, reach, killaura, tagged parts,
  fly, illegal, value spike, grief, /watch, log file, no IP stored), `CameraTest` (items).
- **Index 1.6.16 (Watch fixes)**: the speed limit scales with the MOVEMENT_SPEED attribute (Hermes/Spectre boots,
  Harpy Ring); an item whose item model or every attribute modifier key is in our namespaces (`Watch.ours`) is never
  illegal; no value-spike line for 10 min after a death (picking your drops back up), and spikes say "after opening a chest
  / ender chest" when one was opened in the last 35 s; `/suggest` falls back to the LOGS channel when there's no public
  webhook (one-channel setups: only `logs-webhook-url` + `mirror-public: true` = chat in the staff channel). Tested (`WatchTest`).
  1.6.17: `Discord.logsWarning()`: an empty / invalid `logs-webhook-url` while the public one works (TNT, whispers, grief,
  Watch silently going nowhere) is warned in the console on load, to ops on join and in `/discord status`.
  1.6.18: `/discord` with the bridge off says WHY (`Discord.diagnose()`: config.yml has a YAML error + its line, since
  Bukkit then ignores the whole file; or each URL empty / not https://, shown only up to "/webhooks/"); also in the
  console on load. `cleanUrl` forgives spaces, `<...>` and an upper-case scheme. Tested (`DiagTest`).
  1.6.19: `FaultlineIndex.repairConfig()` (on enable and every `/discord`) replaces what copy-pasting from Discord/browsers
  brings into config.yml (non-breaking spaces, tabs, zero-width chars, BOM, CRLF), keeping the original as `config.yml.broken`.
- **The farm & mobs batch (Raids 1.2.0, Items 1.2.26, Kingdoms 1.2.0, Index 1.6.20)**:
  **Elite mobs + mutations** (`Raids/Elites.java`, `elites:`): NATURAL hostile spawns only (decided a tick later, skipped if
  another plugin tagged it `faultline*`; never in `faultline_void`, never `NEVER` types) and raid waves (`raidRoll` in
  `spawnWave`, never bosses/commanders/captains). ELITE (`chance` 1%, `raid-chance` 6%, tag `faultline_elite`): "★ Elite"
  name, MULTIPLY_SCALAR_1 modifiers `faultlineraids:elite_*` (health x2, damage x1.5, speed x1.15, scale +15%); killed by
  a player = plain drops twice (`plain()`: stackable, no name/enchants/PDC), 3x XP, `bonus-loot` rolls, 4% Goodie Bag
  (`givegoodiebag ... at:`), `index stat <p> elites 1`. Mutations (`mutation-chance` 2%, `raid-mutation-chance` 5%):
  LOOTER ZOMBIE (`faultline_looter`, any Zombie but PigZombie: a hit steals a random backpack stack 9-35, `steal-chance`
  0.3, `max-stacks` 3, never race-lent / Index / Quest Book items; stored Base64 `serializeAsBytes` in entity PDC
  `faultlineraids:looter_stolen`, made persistent; dropped on death, and `release()` on raid end before `e.remove()`),
  BLAZING SKELETON (`faultline_blazing`: Fire Resistance, visual fire, arrows `setFireTicks(200)`, 50% 1-2 blaze powder),
  SPLITTING CREEPER (`faultline_splitter`: explode or killed = 2-3 `faultline_creeper_mini`, scale 0.55, 6 hp, fuse 20,
  radius 1, no drops, never split). `/zraid elite <type>`, `/zraid mutant <looter|blazing|splitter> [elite]`. Index
  entries (enemies) + icons `elite_mob`, `looter_zombie`, `blazing_skeleton`, `splitting_creeper` (`tools/events_assets.py`).
  **Crop seasons** (`Items/CropSeasons.java`, `crop-seasons:`, zone `America/New_York`): `months.<crop>` (wheat, carrots,
  potatoes, beetroots, pumpkins (stem+fruit), melons, berries, sugar_cane, cocoa, cactus, bamboo, flowers); BlockGrowEvent in
  season = `in-season-boost` 35% extra age next tick, off season = `off-season-skip` 25% cancelled; BlockDropItemEvent ripe
  in-season +1 (`harvest-bonus` 25%); Nether Wart and bone meal untouched; `/crops` (Bedrock: a menu); `today` swappable.
  **Mount stats** (`Items/MountStats.java`): a plain STICK (no name/PDC: not the Shipwright's Hammer) right-click on any
  AbstractHorse = speed (MOVEMENT_SPEED x 42.16 b/s), jump (JUMP_STRENGTH through the community height fit), health,
  llama strength x3 slots, owner, 5-star ratings vs the best vanilla horse; cancels the mount; Bedrock: a menu.
  **Death coordinates** (`Items/DeathSpot.java`): player PDC `faultlineitems:last_death` "world;x;y;z;ms"; on respawn (10
  ticks later) only that player gets "You died at X Y Z (dim) · N blocks <way> [Copy] [Again]" (Bedrock: a menu);
  `/lastdeath` (`deathpos`, `deathcoords`). **Discord → game chat** (`Kingdoms/DiscordGateway`, `discord.chat-bridge.*`):
  with `channel-id` set the bot identifies with GUILD_MESSAGES + MESSAGE_CONTENT (`intents()`); MESSAGE_CREATE in that
  channel (not `webhook_id`, not bots: our own logs/chat mirror never echoes) = "[Discord] nick: text" to everyone (mentions
  → @name, custom emoji → :name:, § stripped, `max-length`); close 4014 (intent not enabled) = warning + reconnect without
  it (`noContent`; /stats still works); the gateway now also runs for the bridge alone. `/kingdoms` shows `bridgeStatus`.
  **Daily stats post** (`Index/Daily.java`, `daily-stats:`, `daily.yml`): counts PvP kills, deaths, hostile mob kills,
  play minutes (sessions), diamond/debris ore, Index finds (`unlock` → `found`), boss defeats (`Museum.bossDefeated` after
  its dedupe), achievements; every day at `hour` (9) in `zone` posts the last day as an embed (`Discord.embed`: public, else
  logs): top killer, most deaths, longest playtime, mob slayer, rarest find (fewest players have that entry,
  `howManyFound`), bosses, achievements, server totals; then resets. Nobody played = nothing posted. `/discord daily` =
  post now. Tested: `EliteTest` (raids), `FarmTest` (items), `GatewayTest` (kingdoms), `DailyTest` (index).
- **The ranger batch (Items 1.2.27, Bosses 1.5.10, Index 1.6.21)**: `Items/RangerGear.java` (`ranger-gear:`): 8 accessories
  (`Piece`: id/`/giveaccessory` name/base): Hawkeye Monocle (`monocle`, SPYGLASS; craft G/GYG/E: sneak + drawing a bow or
  crossbow = Slowness IV 8 ticks (FOV zoom) + action bar target name and distance, ray 160), Quickdraw Glove (`quickdraw`;
  1% Skeleton/Stray/Bogged: BOW shots under full draw are sped up as if `force / quickdraw-full-at` (0.75), crit at full),
  Fletcher's Pouch (`pouch`, RABBIT_HIDE; craft LSL/RAR/LLL: 30% `setConsumeItem(false)` + that arrow CREATIVE_ONLY pickup),
  Windrunner Band (`windrunner`; 3% Breezes: velocity x1.2), Ricochet Charm (`ricochet`; VERY rare: 1.5% ELITE
  skeleton-types, 1% Bone Commanders: arrow PDC `faultlineitems:ricochet`; ProjectileHitEvent on a block = a new arrow
  (spawned by type) reflected off the face at 75%, damage/crit/pierce/fire/pickup/bow enchants copied, once), Phantom
  Fletching (`phantom`, FEATHER; 2% Phantoms: +1 pierce), Wolfcaller Whistle (`whistle`, FLINT; craft B/BHB/G: your
  non-sitting tamed wolves within 32 target what your arrows hit), Ranger's Oath Pendant (`oath`, ECHO_SHARD; 3% Evokers:
  with 2 other ranger accessories (Ranger Emblem counts) +4 MAX_HEALTH / +20% MOVEMENT_SPEED modifiers
  `faultlineitems:ranger_oath_*`, every second, removed on quit/disable). Bases are inert (no bundle, bone or emerald).
  Drops only from player kills, never spawner mobs. **Boss books on BOWS**: `Enchants.fits(MELEE)` includes BOW (not
  crossbows); on shoot RangerGear copies the bow's `fenchants` into arrow PDC `faultlineitems:arrow_fenchants` (+
  `arrow_full` when force >= 0.9); hits run `Enchants.applyRanged` (Lifesteal/Frostbite/Venom/Thunderstrike, procs need
  the full draw). **Ranger Emblem +5%** (`rangerBonus`; old 0.10 reads 0.05; lore refreshed by `AccessoryStacks`).
  **Unholy Arrows +2.5%** (Bosses `unholyMultiplier`, `unholy-arrow.damage-multiplier` 1.025; old 2.0 reads as it; lore
  refreshed on join, `refreshUnholy`). Index entries + icons (`events_assets.py`, item models too), Bedrock mappings in
  `items.tsv`. Tested: `RangerTest` (items), `UnholyTest` (bosses).
- **Trinkets (Items 1.2.28, Index 1.6.22)**: `Items/TrinketGear.java` (`trinkets:`), 9 accessories (`Piece`: id /
  `/giveaccessory` name / inert base): Bloodthirst Fang (`fang`, FLINT; 8% Ravagers, 1% Vindicators: any kill heals
  `fang-heal` 6), Ironhide Pauldron (`pauldron`, IRON_NUGGET; craft INI/ILI: KNOCKBACK_RESISTANCE +0.15 modifier),
  Gladiator's Crest (`crest`, GOLD_NUGGET; ONLY Diamond Jacob: the Index's `fenchant bossdrop <p> diamond_jacob` hook also
  calls `TrinketGear.bossDrop`, 40% per fighter: ARMOR +1 per other non-spectator player within 10, max 4), Guardian's
  Oath (`guardian`, NAUTILUS_SHELL; craft IGI/GSG/IGI: a player hurt within 8 blocks of a same-kingdom wearer (Kingdoms
  `kingdomOf` by reflection) takes 1% less, no stacking), Miner's Lantern Charm (`lantern`, AMETHYST_SHARD; craft
  GLG/LDL/GLG: mining stone (1.5 s cooldown) = ores within 4 shown 3 s as glowing BlockDisplays only that player sees
  (`setVisibleByDefault(false)` + `showEntity`, tag `faultline_no_bedrock_fx`), Bedrock: WAX_OFF particles; sets player
  metadata `faultline_ore_sense` every second, and the Index Watch turns its x-ray ALERT into a LOG line "(wears a Miner's
  Lantern Charm)"), Green Thumb Ring (`greenthumb`, PRISMARINE_CRYSTALS; craft WBW/BGB/WBW: BlockGrowEvent within 8 of a
  wearer (`thumbs` cached each second) = 15% an extra age next tick), Treasure Sense (`treasure`, COMPASS; 3% Drowned:
  every 2 s the X of any `faultlineships:treasure_map` carried, or a `BURIED_TREASURE` (locateNearestStructure radius 1)
  whose chest is still in the column, within 30 = action bar + chime), Smelter's Glove (`smelter`, LEATHER; craft
  LIL/IBI/LIL: BlockDropItemEvent raw iron/gold/copper stacks 20% -> ingots), Featherfall Charm (`featherfall`, FEATHER;
  craft FFF/FSF/FGF: FALL damage cancelled when fall distance <= 5.5). Modifiers removed on quit/disable. Index entries +
  icons (`events_assets.py`), Bedrock `items.tsv`. Tested (`TrinketTest`).
- **Bug pass (Items 1.2.29, Raids 1.2.1, Ships 1.6.3)**: Ricochet read the BYTE `arrow_full` key as a STRING, so a
  bounce off an enchanted bow threw after spawning its arrow (two arrows, books lost); the bounce now copies
  `arrow_fenchants` (STRING) and `arrow_full` (BYTE) correctly and is spawned by type. Gladiator's Crest only counts
  survival/adventure players who aren't vanished (`TrinketGear.vanished`). Treasure Sense uses `DeathSpot.away` (no "0
  blocks right here"). **Bedrock ping**: Geyser runs on the same box, so `Player#getPing` reads ~0 ms for Bedrock players;
  `Edition.ping(p)` (Items, Ships) asks Geyser's `connectionByUuid(uuid).ping()` by reflection and takes the higher one:
  `/ping` (marks "(Bedrock)") and the Shipwright's Hammer timing use it. Crop seasons' harvest bonus skips the PUMPKIN /
  MELON blocks (placing and re-breaking one was a free extra). Elites' `NEVER` adds Zombified Piglins, Endermen, Guardians
  and Witches (mob farms were farming elite loot). The "Faultline SMP" sidebar's ping is a third-party plugin, not here.
  Tested: `RangerTest`, `TrinketTest`, `FarmTest`, `WhereTest` (items), `EliteTest` (raids), `ShipFixTest`, `PingTest` (fake Geyser).
- **25 more mutants (Raids 1.3.0, Index 1.6.23, Items 1.2.30 = pack hash only)**: `Raids/Mutants.java` (`elites.mutants.*`,
  `elites.disabled-mutants: [ids]`). `Mutants.KINDS` = all 28 (`Kind`: id, tag, name prefix, who fits, sample mob); a mob
  that rolls a mutation (same `mutation-chance` / `raid-mutation-chance`) picks a random enabled one its kind fits
  (`Elites.mutate` → `Mutants.apply`; the 1.2.0 three keep their setup in Elites). Natural spawns: mutations for any
  `Enemy` (phantoms, ghasts, hoglins; never `Slime`/magma cubes) and PATROL spawns; ELITE only for `Monster`. Zombies:
  Plague (poison + hunger, poison cloud on death), Brute (x1.4 scale, KB res, 1.5x hp, launching hits), Bloated (death
  burst, `bloater.radius/damage`, no blocks), Gravecaller (2 Risen babies every 10 s, max 4), Twin (splits once below half:
  copy is a minion, tag `faultline_twin_split`), Troll (Regen II; fire stops it 5 s); Dust Devil (husk: blind), Abyssal
  (drowned: mining fatigue, drags down in water). Skeletons: Sniper (x1.6 arrow speed, x1.5 damage, follow 40), Volley
  (+2 arrows, `launching` guard), Frost (freeze + slow), Hexbolt (+pillagers: levitation + glowing), Phasing (blinks 4-8 when
  a player hits it, 3 s), Cursed (wither skel: Wither II + heals). Creepers: Storm (powered + lightning effect), Magnetic
  (pulls within 7), Stealth (invisible until 5 blocks), Ender (teleports beside its target 7-24 away). Spiders: Weaver
  (5 s cobweb at your feet, only into air, `webs` cleared on disable), Leaping, Broodmother (3-4 cave spiders on death).
  Inferno (blaze/ghast: +2 fireballs), Shrieking (phantom: darkness + weakness, glows), Rampaging (hoglin/zoglin: Speed II,
  charges), Vampiric (melee mobs: heals half the damage). Shot effects ride on the projectile's tags (`SHOT_TAGS`), so
  they work after the shooter dies. Timed ones (`TICKED`) are in a UUID set re-found on `EntitiesLoadEvent` (no scans),
  ticked every 10 ticks. Minions (`faultline_mutant_minion`): no drops, 1 XP, never mutate/Elite. Mutants give no extra
  loot; a player kill = Index stat `mutants` (achievements Mutant Hunter 25, Mutant Slayer 250). `/zraid mutant [kind]
  [mob type] [elite]` (no kind = list). Index: 25 entries (270 total), icons from `tools/mutant_icons.py` (head + mark,
  pulled in by `events_assets.py`). `Mutants.open()` replaces `Block#isPassable` (MockBukkit). Tested (`MutantTest`, `EliteTest`).
- **Accessory balance pass (Items 1.2.31, Index 1.6.24)**: `Items/Balance.java`: `Balance.num(plugin, path, old, new)`
  reads a server config value still equal to its OLD default as the new one (an owner-picked value is kept); `pct()` for
  lore. Golden Ring XP x2 -> x1.5; Discount Card 17.5% -> 10%, drop 10% -> 3%; Life Jelly 2% -> 1% max hp / 2 s
  (`life-jelly.heal-percent`); Shield of the Ocean +10% -> +5% speed; Black Belt loses its -2.5% speed (the Bloody Wolf
  Scarf beat it in every way); Scarf 5% -> 3% damage reduction (+2.5% melee kept); Spelunker's Amulet +2% -> +10% mining;
  Moon Stone 5% -> 2% from Phantoms; Windrunner arrows x1.2 -> x1.08 (arrow damage scales with speed); Ranger's Oath +20%
  -> +10% speed; Bloodthirst Fang 3 -> 2 hearts, only `Enemy` mobs and players, `fang-cooldown-seconds` 3; Ancient Core
  +3 armor/+25% KB/+2 hearts -> +2/+15%/none (`ancient-core.*`). Left alone on purpose: values the owner set (Cleft Horn
  0.5% since 1.2.33, Ranger Emblem 5%, Guardian's Oath 1%, Featherfall 5 blocks), boss counters, immunity charms, the
  Ankh / Mirror endgame. The three `applyModifier`s and `Gear.modifier` now replace a modifier whose amount changed
  (player attribute modifiers persist in player data: an old +10% would have stuck). `AccessoryStacks.changedOnes`
  (cached 5 s) refreshes every changed accessory's lore. Tested (`BalanceTest`; Trinket/Ranger/Items tests updated).
- Soft dependencies: Bosses → Items, Raids; Raids → Items; Index → all the others.
- Past bugs already fixed: resource-pack race conditions, gateway teleport cross-world
  exceptions, pom.xml API version bumps.
- Earlier chats mentioned Kingdom End teleporters, permadeath phases, and per-phase
  resource packs. Check the source before assuming those still exist.

## Standing rules when updating plugins
1. **Every new item, boss, or enemy must be added to the Faultline Index.**
2. **Every new boss must be added to `/itemsmenu`** (a spawn egg or summon item).
3. If textures/models change, update the resource pack zip **and** its SHA-1 hash in config.
4. **Keep `item-textures.enabled: true`** in FaultlineItems' config (and the code default true). The pack
   must always be sent. Note the server's own `plugins/FaultlineItems/config.yml` overrides the jar's default.

## Repo contents
| File | What it is |
|---|---|
| `FaultlineSMP.zip` | Main combined resource pack (items, bosses, Index, sounds, cinematic font) |
| `FaultlineItemTextures.zip` | Custom item textures pack (accessories, potions, staffs, etc.) |
| `FaultlineBossesPack.zip` | Boss models/sounds pack (Demon Eye, Dune, Frostmaw, mf_* models) |
| `FaultlineBloodMoon.zip` | Blood Moon pack: replaces moon phase textures |
| `FaultlineBosses.zip` | Very old zip of the FaultlineBosses source (Demon Eye only). Ignore it; the live copy is `plugins/FaultlineBosses/` |
| `plugins/` | Source code for all eight custom plugins (edit these) |
| `bedrock/` | Bedrock (Geyser) packs + custom item mappings: cosmetics (`tools/cosmetics_assets.py`), ships (`tools/ship_assets.py --bedrock`), wild models (`tools/wild_assets.py`) |

Resource packs are served from raw GitHub links on `main`, e.g.
`https://raw.githubusercontent.com/faultlinesmp123-sys/Faultline-SMP/main/FaultlineBossesPack.zip`
(same pattern for `FaultlineItemTextures.zip`, `FaultlineBloodMoon.zip`, `FaultlineSMP.zip`).
The server sends **FaultlineSMP.zip** (set in FaultlineItems `config.yml` → `item-textures`).
Changing a zip changes its hash: recompute with `sha1sum <file>.zip` and update the plugin config.
Players download the pack from `main`, so a new hash only works once the new zip is merged to `main`.
Keep `FaultlineSMP.zip` **under 25 MB**: the owner uploads it through GitHub's web page, which refuses bigger files.
Almost all of its size is boss music: encode new tracks as Ogg Vorbis at `ffmpeg -c:a libvorbis -q:a 1 -ar 44100` (~80 kbps).

## Bedrock (Geyser) coverage
Bedrock players are detected in every plugin by `bedrock(p)` → each plugin's `Edition.bedrock(p)` (Items, Bosses, Ships,
Cosmetics): a Floodgate UUID (top half 0), OR Floodgate's / Geyser's API (by reflection; softdepend `floodgate`, `Geyser-Spigot`).
Before 1.2.2 only the UUID was checked, so a Bedrock player who LINKED a Java account (Java UUID) got none of the Bedrock
stand-ins, models or effects. A yes is cached; a no is re-asked after 5 s. What each needs:
- **Boss bodies**: a vanilla stand-in mob per boss (`FaultlineBosses.proxy(...)`), shown only to Bedrock players.
- **Boss moves** (`BedrockFx.java`, ticked by FaultlineBosses): Geyser can't draw display entities, so every item/block
  display near a Bedrock player (any plugin: boss moves, piglin balloons, ...) is traced in particles for them only:
  block displays in that block's crack particles, vanilla items in item bits, custom models in dust (glow colour, or a
  colour from the model name). Skipped: boss bodies (model-name regex `BedrockFx.BODY`), held weapons (`faultline_held`
  tag), ships, cosmetics, lanterns. Cap `bedrock.fx-particles-per-player` (160 per 2 ticks). A new boss body model must
  be added to `BODY`; a held weapon display needs `BedrockFx.HELD_TAG`.
- **Cinematic black/white frames**: `FaultlineBosses.cinematic(...)` (Java: the cinematic font glyph; Bedrock: Blindness,
  subtitle kept). Cutscene cameras: Bedrock players are teleported along the shot (Geyser has no spectator camera).
- **Music + custom sounds, Index icons, custom item icons**: `bedrock/FaultlineBedrock.mcpack` (→ `plugins/Geyser-Spigot/packs/`)
  and `bedrock/faultline_items_mappings.json` (→ `plugins/Geyser-Spigot/custom_mappings/`), made by
  `python3 tools/bedrock_pack.py FaultlineSMP.zip bedrock --items tools/bedrock/items.tsv --preview tools/previews`.
  Geyser passes custom sound names through, so `sound_definitions.json` uses the Java names (`faultline:jacob.music1`).
  Index glyphs = `font/glyph_E0/E3/E4/E7.png`. `tools/bedrock/items.tsv` lists every /itemsmenu item (base, model, stack,
  name), dumped by building them in a MockBukkit server (the mock forgets item_model; the test copy records it in the PDC);
  the other plugins' items are found by scanning their source. Rerun after adding items, sounds or Index icons.
- **Ghost Pirates** (invisible + glowing): Bedrock has no glow outline, so Bedrock players get their shape in dust.
- **Pack versions**: Bedrock clients cache packs by UUID + version, so a changed pack with the same version is never
  downloaded again (players keep the old models). Every asset tool now calls `tools/mcpack_version.py` `stamp()`, which sets
  the manifest version to `[1, a, b]` from a hash of the pack's content. Rerun it (`python3 tools/mcpack_version.py bedrock/*.mcpack`)
  after editing an .mcpack by hand.
- **Block mirrors** (`BedrockFx`, Bosses 1.4.1): a chunky block display (largest side 0.3-10, smallest/largest >= 0.45: boulders,
  ice chunks, rock spikes, amethyst; never thin warning lines) near a Bedrock player gets a MIRROR, an armor stand only
  Bedrock players see (`MIRROR_TAG`), wearing that block on its head (`HEAD_Y` 1.72, `HEAD_BLOCK` 0.625 at scale 1), scaled
  and moved with it; invulnerable, unclickable, projectiles pass through. Max 120. Any plugin can tag a display
  `faultline_no_bedrock_fx` (`BedrockFx.SKIP_TAG`) when Bedrock already sees it another way. Tested (`MirrorTest`).
- **Bedrock-only stands must be guarded**: Bedrock players hit and click the stand, not the thing it draws. Items'
  `StandGuard` (tag `faultline_bedrock_stand`): hits are cancelled and passed on to the creature (`StandGuard.forward`:
  Mimics, locusts), a pedestal's stand acts as the pedestal (`Gear.clickPedestal`), robbing is cancelled, and an upkeep
  shows every stand to Bedrock players who arrive from another world. Before 1.2.1 punching a pedestal's stand dropped a
  copy of the exhibit, and punching a Mimic broke its stand. Captain Hollow's stand forwards hits (`GhostShip.onStandHit`);
  ship banner stands and the captain's stand are in `bedrockUpkeep`. The Mimic's lid lifts on Bedrock (a stand can't tilt).
  Tested (`BedrockStandTest`, `GhostTest`).
- **Bedrock forms (Items 1.2.22, Bosses 1.5.7, Index 1.6.12)**: Bedrock can't click chat text or links in a book.
  `BedrockForms` (a copy in Items, Bosses, Index) sends native Bedrock menus by reflection through Floodgate's
  `FloodgateApi.sendForm(UUID, Form)` or Geyser's `GeyserApi.api().sendForm(...)` (Cumulus `SimpleForm` = a list of
  buttons, `ModalForm` = Yes/No); answers come back on the main thread; false = couldn't send, the caller falls back to
  text. Used by: the INDEX (`IndexForms`: cover -> chapter -> entry, achievements; before, Bedrock players could open the
  cover but never get into a chapter, since its links ran /index open; `bedrock-forms: true` in the Index config),
  Don Lorenzo's "Are you sure?", the Lost Explorer's Skip and "Do you want to do it again..?", the race invitation
  (/race host asks every Bedrock player), and `/fweather` (the event list). A NEW click-button in chat needs a form too.
  Tested against the real Cumulus (cloned from GitHub, compiled) with a fake FloodgateApi (`FormsTest`, `FormsItemsTest`).
  Ship upgrades and the Pirate Treasure Map (PAPER with vanilla models) now have Bedrock mappings + icons
  (`ship_assets.py` `EXTRA_ICONS`; before, Bedrock players saw plain paper). Index: the Boss Enchant Book, Pirate
  Treasure Map and Trophy Fish entries had been put under `achievements:` in index.yml (missing from the Index, warnings
  at startup): moved to `entries:` (223 entries).
- **`/bedrockcheck`** (FaultlineItems, op): checks Geyser/Floodgate, `enable-custom-content` / `force-resource-packs` in
  Geyser's config, every pack in `plugins/Geyser-Spigot/packs/` against `bedrock_packs.yml` (written by `mcpack_version.py`:
  missing / outdated / two copies / unzipped folder / wrong extension / left in the wrong folder), the four
  `custom_mappings/*.json`, and who online is detected as Bedrock. Problems are also logged to the console 10 s after start.
  `/bedrockcheck install [branch]` downloads the 4 packs + 4 mappings from `raw.githubusercontent.com/.../<branch>/bedrock/`
  (default `main`, or `bedrock-packs.branch`), moving older copies of the same pack to `packs/old/`; then restart (Geyser reads
  packs and mappings only at startup). Tested (`BedrockCheckTest`, with a fake FloodgateApi for linked accounts).
- Ships, cosmetics and the wild models have their own Bedrock packs (`FaultlineShips`, `FaultlineCosmetics`, `FaultlineWild`).
  `tools/bedrock/items.tsv` also lists the wild bosses' summon items/weapons and the Ancient Core by hand (not in the MENU dump).

## Cosmetics (FaultlineCosmetics)
- `/cosmetics` opens the menu; `/cosmetic` (the admin command) also opens it for non-admins. A menu error is shown to the
  player and logged in full (1.0.1).
- Unlock from console or another plugin: `cosmetic unlock <player> <id> [silent]` (Rocco Vendetta will drop `rocco_chains`, `rocco_book`, `rocco_coat`).
- Drawing: HAT/BODY = a fake helmet/chestplate sent to other players (BODY uses an equipment asset `faultline:<id>`).
  NECK/BACK = an ItemDisplay following the torso for Java viewers; Bedrock viewers get a fake LEGS/FEET item that the
  Bedrock pack draws on the body bone instead. Fake armor is never sent to the wearer (it would land in their own slots),
  so a player can't see their own Hat/Body cosmetic in third person.
- Art comes from `tools/cosmetics_assets.py <java-out> <bedrock-out> --preview tools/previews`. Merge the Java output into
  `FaultlineSMP.zip` (and update the hash). The Bedrock output lives in `bedrock/`: `FaultlineCosmetics.mcpack` goes in
  `plugins/Geyser-Spigot/packs/`, `faultline_cosmetics_mappings.json` in `plugins/Geyser-Spigot/custom_mappings/`
  (Geyser's `config.yml` needs `enable-custom-content: true`).
- Item model names: icon `faultline:cosmetic/<id>_icon`, worn model `faultline:cosmetic/<id>`.

## Models
Diamond Jacob's knight, blaze form, hawk, and war mace (jacob_hammer) are generated by `tools/jacob_models.py` (Pillow + numpy).
Run `python3 tools/jacob_models.py <unpacked-pack-dir> --preview tools/previews`, zip the output into
`FaultlineSMP.zip`, and update the hash. The preview PNGs show the result without launching the game.

The seasonal and achievement cosmetics are in `tools/cosmetics_new.py` (used by `cosmetics_assets.py`). Index icons and the
Grimtusk items for the meteor/Caravan/Grimtusk/achievements come from `python3 tools/events_assets.py <unpacked-pack> --preview tools/previews`
(run it after `cosmetics_assets.py`; it also adds glyphs to `glyphs.yml` and providers to `font/index.json`).

Rocco Vendetta, Werner and the Vendetta Enforcers (`vendetta_goon`; skins → 6-piece rigs), the Vendetta Fist, the Vendetta Contract and their Index icons come from
`python3 tools/vendetta_models.py <unpacked-pack-dir> --preview tools/previews` (it reuses `jacob_models.py` and `cosmetics_assets.py`).
Swarm (`swarm_*` rig), the black pickaxe (`below_pickaxe`) and Swarm's Index icon come from
`python3 tools/below_models.py <unpacked-pack-dir> --preview tools/previews`. `tools/void_preview.py <out> [<pack>]` draws the void world.

New Index icons also need a glyph in `FaultlineIndex/glyphs.yml` and two bitmap providers in `assets/faultline/font/index.json`.

## Building in Claude Code cloud sessions
Maven must reach `repo.papermc.io` to download paper-api. If the build fails with
`403 Forbidden` on that host, add `repo.papermc.io` to the environment's allowed network domains.
Without it, run `tools/compile_check.sh` to compile every plugin against the real Paper 26.2 API (`PAPER=1.21.11` for the old one)
(it builds paper-api from PaperMC's GitHub source + Maven Central). Run it before pushing Java changes.
**Build release jars from the 1.21.11 output** (`PAPER=1.21.11 tools/compile_check.sh <Plugin>` → `$CACHE/1.21.11/build/<Plugin>`
plus `src/main/resources`, `jar cf`), like `mvn package` would. The 26.2 build uses Adventure 5.2, whose method signatures the
server's runtime doesn't have (`TextComponent$Builder.build()` crashed the Index). Some event getters also return different
types between the two APIs (`EntityPotionEffectEvent.getEntity()`, `SlimeSplitEvent.getEntity()`): call them through
`((EntityEvent) event).getEntity()`. To check, diff the `javap -c` method refs of the two builds; they should be identical.
