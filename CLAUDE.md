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
| **FaultlineItems** | `net.faultlinesmp.items` | All custom items + accessories (3-slot `/accessories` GUI), staffs, spawn eggs, diving gear, Kraken & Diamond Jacob gear, Skeleton Wanderer cave trader, **Meteor strikes** (`Meteor.java`), **the Caravan** (`Caravan.java`), **weather events** (`Weather.java`), **Mimics + Skeleton Knights** (`Creatures.java`), **new gear, music discs, museum pedestals** (`Gear.java`), admin `/itemsmenu` | many `/give*` commands, `/itemsmenu` (`/fitems`), `/itemsreload`, `/cavetrader`, `/batform`, `/meteor [now\|here\|target\|at\|check\|stop\|egg]`, `/caravan [now\|here\|stop\|reroll]`, `/queenspider spawn`, `/fweather [list\|<event> [player]\|stop\|status]` (was `/fevent`: renamed, FaultlineEvents owns that name; bare `/fweather` lists the 5 events with click-to-start buttons; also in /itemsmenu), `/fcreature <mimic\|knights>`, `/fgear`, `/giveancientgear`, `/givedisc <id> [player]` |
| **FaultlineRaids** | `net.faultlinesmp.raids` | Zombie Raids (Zombie Omens, Captains, Rotbeard), Skeleton Raids, and **Piglin Raids** (`Piglins.java`: War Horn, the Bulwark, the Great Hog) | `/zraid <start\|stop\|captain\|menu\|egg\|omen\|horn>` |
| **FaultlineBosses** | `net.faultlinesmp.bosses` | Bosses in one ~585 KB file: Demon Eye, Frostbeard, Dune Devourer/Frostmaw, Don Lorenzo, Kraken, **Diamond Jacob**; admin boss form. **Rocco Vendetta** (+ Werner, the Vendetta Fist) lives in its own `Vendetta.java`. **The way down to the Lost Explorer** (Swarm, his staircase, the void world) is `Below.java`; **the Lost Explorer's fight** is `Explorer.java` (+ `ExplorerAnims.java`). **Grimtusk, the Piglin Warlord** (Nether boss) is `Warlord.java`; **the Boss Rush** is `BossRush.java`. **The wild bosses** (Leviathan, Sandworm King, Lich, Frost Wyrm, Stone Golem) are `Wild.java` + one class each | `/demoneye`, `/frostbeard`, `/dune`, `/don`, `/kraken <summon\|sea\|...>`, `/jacob <summon\|kill\|phase\|item>`, `/rocco <summon\|kill\|phase\|tattoos\|werner\|item>`, `/bossmorph <boss\|off\|release>`, `/below <swarm\|void\|leave\|close\|slayer\|info>`, `/explorer <start\|stop\|skip\|phase\|stun\|reset\|arena>`, `/grimtusk <summon\|kill\|phase\|item>`, `/bossrush [start\|top]` (admin `stop\|skip\|setarena\|arenas\|clearcooldown`), `/leviathan`, `/sandworm`, `/lich`, `/frostwyrm`, `/stonegolem` |
| **FaultlineIndex** | `net.faultlinesmp.index` | The Faultline Index codex + **Achievements** (`Achievements.java`). **Museum** (`Museum.java`: trophies, discs, Hall of Firsts), **Discord bridge** (`Discord.java`), `/serverstats` (`ServerStats.java`). Entries and achievements live in `src/main/resources/index.yml`, font glyphs in `glyphs.yml`, settings in `config.yml` | `/index [achievements\|give\|reset] [player]`, `/index stat <player> <name> [n]` (other plugins), `/index discover <player> <entry>`, `/museum [bosses\|items]`, `/serverstats`, admin `/discord test [msg]` |
| **FaultlineCosmetics** | `net.faultlinesmp.cosmetics` | Permanent cosmetic unlocks worn in 4 slots (Hat, Neck, Back, Body) over armor, for Java and Bedrock. **Seasons** (`Seasons.java`: Halloween/Winter currency + Seasonal Shop). Cosmetics are defined in its `config.yml`, unlocks saved in `players.yml` | `/cosmetics [shop]`, admin `/cosmetic <unlock\|lock\|list\|reload\|season\|tokens\|shop>` |
| **FaultlineShips** | `net.faultlinesmp.ships` | Ships: Dinghy, Sloop, Brigantine, Galleon, Pirate Ship. Blueprint → lay out on water → place every block → sail. Health, part damage, sinking, Shipwright's Hammer repair minigame, cannons, banners, names. **Skeleton ships** and the **Pirate Invasion** (`Pirates.java`). Ships saved in `ships.yml` | `/ship [list\|info\|name\|crew\|anchor\|stop\|banner\|scrap]`, admin `/ship <give\|repair\|wreck\|remove\|tp> [name\|#n]`, `/ship spawn <ship> [owner]`, `/ship pirates <ship\|invasion\|horn\|spawn\|egg\|ghost [player]\|bell [n] [player]\|stop>` |

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
  lore is fixed when held. Tested in `AnkhTest`. The **Harpy Ring** is +7.5% speed.
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
- **Meteor strikes** (`FaultlineItems/Meteor.java`, config `meteor:`): every `every-minutes` (60, real time) a meteor falls in the
  Overworld `min/max-distance` (250-600) from a random survival player, announced a minute ahead (chat, title, boss bar with
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
  only work in their biome unless `wild.require-biome: false`): **Leviathan** (`Leviathan.java`, 2400 hp, Abyssal Lure over
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
| `plugins/` | Source code for all seven custom plugins (edit these) |
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
