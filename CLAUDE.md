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
| **LimboBlackMarket** | `net.limbosmp.blackmarket` | Head-tier economy, kill/death stats, kill streaks, anti-farm, quests + Quest Book, friendly-fire logging, global reload, **head bounties** (`Bounties.java`) | `/tier`, `/markethelp`, `/stats`, `/leaderboard`, `/tierleaderboard`, `/faultlinereload` (`/freload`), `/givequestbook`, `/bounty [place <player> [n]\|list\|info\|cancel\|claim]` |
| **FaultlineItems** | `net.faultlinesmp.items` | All custom items + accessories (3-slot `/accessories` GUI), staffs, spawn eggs, diving gear, Kraken & Diamond Jacob gear, Skeleton Wanderer cave trader, **the Caravan** (`Caravan.java`), **weather events** (`Weather.java`), **Mimics + Skeleton Knights** (`Creatures.java`), **new gear, music discs, museum pedestals** (`Gear.java`), admin `/itemsmenu`, **OldGear** (`OldGear.java`: takes the removed bosses' items and trophies away) | many `/give*` commands, `/itemsmenu` (`/fitems`), `/itemsreload`, `/cavetrader`, `/batform`, `/caravan [now\|here\|stop\|reroll]`, `/queenspider spawn`, `/fweather [list\|<event> [player]\|stop\|status]` (was `/fevent`: renamed, FaultlineEvents owns that name; bare `/fweather` lists the 3 events (sandstorm, blizzard, eclipse) with click-to-start buttons; also in /itemsmenu), `/fcreature <mimic\|knights>`, `/fgear`, `/givedisc <id> [player]`, `/fenchant [list\|give <id> [lvl] [amount] [player]]`, `/sit`, `/vanish [player\|list]`, `/camera [speed <1-10>]`, `/ping [player\|all]`, `/coords [share [player\|kingdom\|all]]`, `/lastdeath`, `/crops`, `/totems [<player>\|set <player> <n>\|reset <player\|all>\|limit <n\|off>\|on\|off]`, `/giveballoon`, `/balloon [list\\|remove [#n\\|all [player]]]`, `/fishing [top\|records]` (admin `start [min]\|stop [award]`), `/race [join\|leave\|list\|top]`, `/backpack`, `/creator`, admin `/offtp <player> [last\|bed\|death]`, admin `/pielimit [player]`, admin `/mimicplayer [random\|<player>] [count] [easy\|normal\|hard\|godlike]`, `/holiday` (admin `create <name> [elytra\|boat\|horse\|any]\|mode\|edit\|cp add [r]\|cp undo\|setstart\|delete\|host <course> [lend]\|start\|stop`) |
| **FaultlineRaids** | `net.faultlinesmp.raids` | Zombie Raids (Zombie Omens, Captains, Rotbeard), Skeleton Raids, **Piglin Raids** (`Piglins.java`: War Horn, the Bulwark, the Great Hog), Elites + 28 mutants (`Elites.java`, `Mutants.java`) | `/zraid <start\|stop\|captain\|menu\|egg\|omen\|horn\|elite\|mutant>` |
| **FaultlineBosses** | `net.faultlinesmp.bosses` | Since 2.0.0: **Diamond Jacob** (the only land boss left; harder, drops the Mirror), the **Kraken** (the normal one in Overworld deep oceans again since 2.0.2, the Abyssal Kraken in the Dark Ocean), admin boss form, and **the Dark Ocean's 7 bosses** (incl. the Abyssal Titan: `Wild.java` + `DeepBosses.java`, shared rig math in `RigMath.java`). The Demon Eye, Frostbeard, Dune Devourer/Frostmaw, Don Lorenzo, Rocco Vendetta, Grimtusk, the 5 wild land bosses, the Night of Terrors and the Boss Rush were REMOVED in 2.0.0 (the notes below keep their history) | `/kraken <summon\|kill\|phase\|bubble\|worm>`, `/jacob <summon\|kill\|phase\|item>`, `/bossmorph <jacob\|scourge\|...\|titan\|off\|release>`, `/scourge`, `/umbral`, `/coralia`, `/inkjaw`, `/grandmaw`, `/lament`, `/titan` |
| **FaultlineIndex** | `net.faultlinesmp.index` | The Faultline Index codex + **Achievements** (`Achievements.java`). **Museum** (`Museum.java`: discs, Hall of Firsts; trophies removed in 1.8.0), **Discord bridge** (`Discord.java`), `/serverstats` (`ServerStats.java`). Entries and achievements live in `src/main/resources/index.yml`, font glyphs in `glyphs.yml`, settings in `config.yml` | `/index [achievements\|give\|reset] [player]`, `/index stat <player> <name> [n]` (other plugins), `/index discover <player> <entry>`, `/museum [bosses\|items]`, `/serverstats`, admin `/discord [status\|test [msg]\|daily]` (public + logs webhooks, daily stats post), `/report <player> <reason>`, staff `/reports [n]`, `/suggest <idea>`, staff `/suggestions [n]`, `/guide [chapter]`, staff `/watch [<player>\|log\|xray\|alts\|check\|alerts]` (x-ray / cheat / dupe / grief / alt logs, `Watch.java`) |
| **FaultlineCosmetics** | `net.faultlinesmp.cosmetics` | Permanent cosmetic unlocks worn in 4 slots (Hat, Neck, Back, Body) over armor, for Java and Bedrock. **Seasons** (`Seasons.java`: Halloween/Winter currency + Seasonal Shop). Cosmetics are defined in its `config.yml`, unlocks saved in `players.yml` | `/cosmetics [shop]`, admin `/cosmetic <unlock\|lock\|list\|reload\|season\|tokens\|shop>` |
| **FaultlineKingdoms** | `net.faultlinesmp.kingdoms` | Kingdom 1/2/3 per player, assigned from DISCORD ROLES (players link once: the bot DMs a code), and GRACE PERIODS server-wide or per kingdom (protected kingdom can't be hurt, and can't hurt others unless `grace.protected-can-attack`); `pvp.friendly-fire`; kingdom in the tab list. Data in `data.yml` | `/kingdom [link <discord name>\|verify <code>\|unlink\|list <1\|2\|3>\|ff <on\|off\|clear>\|ally <1\|2\|3> <yes\|no\|clear>]` (`/k`), `/kc [msg]`, `/ac [msg]`, admin `/kingdoms treaty <a> <b> <on\|off\|vote>`, `/kingdoms spy`, admin `/kingdoms grace <all\|1\|2\|3> <on\|off> [30m\|2h\|1d\|1h30m]`, `/kingdoms ff <1\|2\|3> <on\|off\|vote>`, Discord `/stats <player>`, `/kingdoms set <player> <1\|2\|3\|none\|auto>`, `/kingdoms sync [player]`, `/kingdoms reload` |
| **FaultlineDeep** | `net.faultlinesmp.deep` | **The Dark Ocean**: a water-only world (`DeepGenerator.java`), its biomes **the Reaches** (`Reaches.java`), **the Sulphur Sea** acid region (`Sulphur.java`), **the Bubble** town over the build area (`Town.java`), **the Black Market + the Pit** (`Market.java`, `Elevator.java`), ruins (`Ruins.java`), its 66 creatures (`DeepMobs.java`), the pitch-black Abyss (`Ocean.dark`), the Mirror + the HOURLY WHIRLPOOL down to it (`Journey.java`, `Vortex.java`; Lucki/Swarm removed in 1.16.0), zone music, events and dying down there (`Ocean.java`), **the Trench** (`Trench.java`), Den reputation (`DenRep.java`), the Submersible, the Migration, the Bloom | `/whirlpool [where\|here\|random\|at <x> <z> [world]\|stop\|next <min>]` (`/maelstrom`; players: where / when), `/dome [list\|combine\|yes\|no\|upgrades\|trust\|untrust\|home\|color]` (dome bases in any water, hallways, 20 upgrades), `/chart`, `/glimmer [top]` (the Glimmertide's shop), admin `/deep season [start\|stop\|auto\|shoal]`, `/deep [info\|set kingdom\|whirlpool ...\|tp [player]\|town [player]\|sulphur [player]\|spawn <mob>\|egg <mob> [n] [player]\|event <pressure\|undertow\|stop>\|acidrain\|den [info\|where\|tp\|raid\|stop\|reset\|rise]\|lucki\|trench [maw] [player]\|bloom\|migration\|sub [player]\|eclipse\|redtide\|frenzy\|volcano [where\|start [x z]\|here\|cool\|heal]\|region <frozen\|city\|glass\|bones>\|give <coral\|dome_small\|dome_large\|vault_key\|cache_key\|fossil\|chart\|glimmer>\|domes]`, players `/den [home]` |
| **FaultlineShips** | `net.faultlinesmp.ships` | Ships: Dinghy, Sloop, Brigantine, Galleon, Pirate Ship. Blueprint → lay out on water → place every block → sail. Health, part damage, sinking, Shipwright's Hammer repair minigame, cannons, banners, names. **Skeleton ships** and the **Pirate Invasion** (`Pirates.java`). Ships saved in `ships.yml` | `/ship [list\|info\|name\|crew\|anchor\|stop\|banner\|scrap]`, sneak + right-click a gun with a Cannonball = man it (W/S/A/D aim), admin `/ship <give\|repair\|wreck\|remove\|tp> [name\|#n]`, `/ship spawn <ship> [owner]`, `/ship give upgrade_<hull\|sails\|loaders\|ram>`, `/ship pirates <ship\|invasion\|horn\|spawn\|egg\|ghost [player]\|bell [n] [player]\|map [player]\|stop>` |
| **FaultlineSkills** | `net.faultlinesmp.skills` | Skill trees: Mining, Building, Farming, Redstone (`Tree.java`: 6 perks each), levels 1-50, anti-abuse (placed-block marks, settle time), chest menu + Bedrock form. Data in `players.yml` | `/skills [top <tree>]`, admin `/skills <player>`, `/skills give <player> <tree> <xp>`, `/skills reset <player> [tree\|perks]` |
| **FaultlineEvents** | `net.faultlinesmp.events` | Sky events in the main world (2.0.0 rewrite, source here since then): **Blood Moon** (`BloodMoon.java` + the Crimson Champion), **Fog** (`Fog.java`), **Lantern Night** (`LanternNight.java`), **Windy** (`Windy.java`, `WindPush.java`) | `/fevent [list]` (`/events`), admin `/fevent start <blood-moon\|fog\|lantern-night\|windy>\|stop\|champion [player]\|realm <nether\|end> <day\|night\|event>` (2.1.0: day and night in the Nether and the End, `Realms.java`) |

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
- **The Lost Explorer** (REMOVED in Bosses 1.6.0, kept here as history; final boss, spec + build notes: `docs/bosses/lost_explorer.md`). `Explorer.java`: he stands on a
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
- **Meteor strikes** (REMOVED in Items 1.2.68, kept as history; `FaultlineItems/Meteor.java`, config `meteor:`): every `every-minutes` (240 = 4 h, real time; 1.2.11) a meteor falls in the
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
  FaultlineEvents' source is in this repo since its 2.0.0 rewrite (see the FaultlineEvents note).
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
  the full draw). **Ranger Emblem +1%** since 1.2.35 (`rangerBonus`; old 0.10 or 0.05 reads 0.01; lore refreshed by `AccessoryStacks`).
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
  0.5% since 1.2.33, Ranger Emblem 1% since 1.2.35, Guardian's Oath 1%, Featherfall 5 blocks), boss counters, immunity charms, the
  Ankh / Mirror endgame. The three `applyModifier`s and `Gear.modifier` now replace a modifier whose amount changed
  (player attribute modifiers persist in player data: an old +10% would have stuck). `AccessoryStacks.changedOnes`
  (cached 5 s) refreshes every changed accessory's lore. Tested (`BalanceTest`; Trinket/Ranger/Items tests updated).
- **Bug pass (Raids 1.3.1, Items 1.2.34)**: DUPE: an Elite doubled every plain drop, including what it picked up (zombies
  hold any item) and a Looter's stolen stacks; `Elites.onDeath` now counts only drops not similar to its equipment, before
  the stolen loot is added. Sniper arrows: damage already scales with speed, so base damage is `damage / speed` (it was
  x2.4, not x1.5). Weaver webs: any air (`isAir`: CAVE_AIR underground), chunk PDC `faultlineraids:mutant_webs` cleans
  crash leftovers on chunk load, a web broken early drops nothing (free cobwebs), unloaded chunks are never loaded to clean
  one. `Elites.name` keeps a Stealth Creeper's tag hidden when it turns Elite. Magnetic / Ender / Leaping / Rampaging need
  line of sight (`Mutants.sees`; an Ender Creeper teleported into sealed bases). `Mutants.onTransform`
  (EntityTransformEvent: drowning, freezing into strays, curing): the mutation and Elite buffs are re-applied to the new
  mob when it fits, a Looter's loot moves with it (`Elites.moveLoot`) or drops (cured into a villager). Treasure Sense
  looks for buried chests from the ocean floor (`HeightMap.OCEAN_FLOOR`). Tested (`MutantTest`, `EliteTest`, `TrinketTest`).
- **Balloons 1.2.36 (Items, Index 1.6.28)**: knock one down: `Balloons.onSwing` (PlayerAnimationEvent ARM_SWING, so
  Bedrock swings count; the model is displays + a marker, nothing hittable) traces the look ray against the basket+ropes box
  (±1.1, 0..3.6) and the envelope box (±2.4, 3.5..10.2), nearer than `rayTraceBlocks` (`aimed`, `hit` = slab test);
  `hits-to-break` 4 within 3 s of each other (an axe counts 2), owner / `items.balloon.admin` / `anyone-can-break`, not
  while someone flies it; drops its item (colour + fuel) where it stood (`takeDown(b, by, drop=true)`). `/balloon
  [list|remove [#n|all [player]]]` (`listed()` numbers by id): your own nearby one = `pack` into your inventory; others (admin)
  go back to the owner, now or on their next join (`returns` in balloons.yml, `onJoin`); riders are set down first.
  Tested (`BalloonTest`).
- **PvP limits (Items 1.2.37, `Items/PvpLimits.java`, `pvp-limits:`)**: spear LUNGE once every `lunge-cooldown-seconds`
  (5) per player: Paper's `EntityLungeEvent` exists only in the 26.x API, so it's registered by name
  (`Class.forName` + `PluginManager.registerEvent`, cast to `Cancellable`/`EntityEvent`); on cooldown the lunge is
  cancelled (the jab still lands) + action bar countdown. COBWEBS: one per `cobweb-place-cooldown-ms` (250) per player
  (BlockPlaceEvent). Creative/spectator never limited. Tested (`PvpTest`, with a copy of the 26.2 event class in the test sources).
- **Perk swap (Items 1.2.38, Index 1.6.29)**: the two accessories that gave a flat +2 max hearts now give a perk each.
  **Diamond Heart** (`JacobGear`, `diamond-heart.damage-reduction` 0.08): take 8% less from every damage type
  (`onHeartDamage`); `sync()` strips the old +4 MAX_HEALTH modifier (`heartMod`) from anyone who still has it.
  **Ranger's Oath** set bonus (`RangerGear`, `ranger-gear.oath-arrow-bonus` 0.10): +10% arrow damage on hit + keeps
  +10% speed; `oathTick` always removes the old MAX_HEALTH modifier. Tested (`RangerTest`: Oath arrow bonus, Diamond Heart reduction).
- **Ranger's Oath quest (Items 1.2.39, LimboBlackMarket 1.0.3, Index 1.6.30)**: the Oath set bonus gets +1 heart back
  (`ranger-gear.oath-health` 2 = +2 MAX_HEALTH, an old config's 4 reads as 2, on top of +10% arrow damage and +10% speed). New Quest Book quest
  `rangers-oath` ("The Ranger's Oath", EXTREME, one-time, the hardest on the server): 1000 Skeletons/Strays/Bogged, 100
  Evokers, 75 Breezes, 75 Phantoms, 5 Wardens, 3 Withers, 15 different Tier 4+ players; reward = console
  `giveaccessory oath <player>` (`QuestRegistry.giveRangersOath`; FaultlineItems missing = the player is told to ask staff).
  `QuestMenuHolder.QUEST_SLOTS` row 2 is now 20-24. The 3% Evoker drop stays. Tested (`RangerTest`, `OathQuestTest` in `mtest`).
- **Meteors heal, ships lag pass, Bedrock cannons (Items 1.2.40, Ships 1.6.4)**:
  **Meteor terrain** (`meteor.restore-terrain: true`): every block the crater / rock / core changes goes through
  `Meteor.change()` (first original `BlockData` per position in `Strike.before`, also saved as `scar:` in `meteor.yml`);
  `end()` puts them back (`heal`: only where the block is still a meteor material or air, so a player's build in the crater
  stays): 2 min after the claim, when it cools (no obsidian any more; `false` = the old obsidian), on shutdown, and on the
  next start after a crash (`healLeftovers`). No rim fire any more (it spread into forests): flame particles instead. The
  interval is still `every-minutes: 240` (logged on start: "[Meteor] one every N minutes"). Tested (`EventsTest`).
  **Ship lag**: (1) turning = one rotation packet PER BLOCK DISPLAY: now every 2 ticks / 1 degree for players' ships,
  every 4 ticks / 2 degrees for skeleton ships (their displays' teleport duration 5), every 20 ticks with nobody within 64
  (was every tick / 0.5 degrees). (2) `updateDeck()` only lays barriers within `deck-radius` (3.5) of someone on/beside
  the ship (it relaid the WHOLE deck every tick = hundreds of block changes a second); nobody aboard = no barriers; bodies =
  nearby players + the pirates' crew map (no area scan). (3) `arrows()` checks the projectiles in `flying` (added on
  `ProjectileLaunchEvent` + `launchTagged`) instead of scanning the area around every ship every 2 ticks (a ship's
  displays are entities in that area). (4) The **Pirate Invasion comes in waves**: `pirates.invasion.wave-size` 5 escorts at
  a time (0 = all 15 at once, the old way), the next wave when `next-wave-at` (1) are left (`Pirates.nextWave`, around the
  fighters, from the flagship's side; `Invasion.toSpawn/wave/escortsLeft()`); the flagship waits for `escortsLeft()`.
  **Deck clipping**: `carry()` lifts anyone whose feet sank 0.02-0.75 into a deck block back on top
  (`floorTopUnder`, a teleport, at most every 10 ticks per player); the bottom 0.25 of a body doesn't stop the barrier under
  it being placed; `climbAboard` teleports first, then lays the deck. **Bedrock land cannons**: no more fake dispenser; a
  Bedrock-only armor stand (`LandCannons.STAND_TAG`, not a marker: Geyser draws helmets only on real stands) wears paper
  with item model `faultline:land_cannon` = a 3D cannon attachable in `FaultlineShips.mcpack` (`ship_assets.py`
  `land_cannon_cubes`), turned with the cannon (yaw only); hitting it = punch (sneak = pick up), right-clicking with a
  Cannonball fires. Ship cannons were already part of each ship's Bedrock stand-in model. Tested (`DeckTest`, `PirateTest`,
  `ShipEventTest`, `CannonUpgradeTest`).
- **THE DEEP UPDATE (FaultlineDeep 1.0.0, Bosses 1.6.0, Items 1.2.41, Index 1.6.31)**: the Lost Explorer, Swarm, `Below.java`
  and the void world are GONE (Below the Bedrock gets scripted later). The **Kraken** drops the **Mirror..??** to every fighter
  (`kraken.rewards.mirror`). Right-clicking the Mirror (PDC `faultlineitems:lost_mirror`) needs Rocco Vendetta AND Diamond Jacob
  beaten (`FaultlineIndex.defeated(uuid, entry)` by reflection; `faultlinedeep.bypass`): 3 s channel → the abandoned kingdom
  (`journey.kingdom`, `/deep set kingdom`). **Lucki** (no-AI fisherman villager in an oak boat, `journey.lucki`) talks, rows you out
  (fade), points, opens a WHIRLPOOL at `journey.whirlpool` (default 0, sea level, 0); swim into the middle = `send()` to the
  **Dark Ocean** (`faultline_deep`, 0 100 0). In the Dark Ocean the Mirror (5 s) brings you back up. **The world**
  (`DeepGenerator`, pure functions of seed/x/y/z): sea to y 100, floor ~ -50 of black sand (hills, dunes, terraced seamounts,
  canyons, reef rises with coral + kelp, arches, sea stacks, whale falls), the CRUST below it (caves, glow lichen, dripstone,
  vents, crystal gardens, Dark Loot chests) and the ABYSS under that (ridges, hanging cones, basalt pillars, chimneys, abyss
  skeletons, sculk); a flat 150x150 area at 681, -50, -951 with 50+ blocks of water above (the owner's village), border radius
  1050 (`border-radius`). Zones `DeepGenerator.Zone` SUNLIT/CAVES/ABYSS; previews `tools/previews/deep_*.png`, harness in the
  scratchpad (`dtest`). **Creatures** (`DeepMobs`, 20 `Kind`s, tag `faultline_deep_<id>`, non-persistent, a vanilla body made
  invisible + an ItemDisplay passenger `faultline:deep/<id>` + a Bedrock-only stand at the body's middle; passive / neutral /
  aggressive, own abilities; spawner cap 14 near players; vanilla spawns limited to sea life, NO phantoms; guardians and elder
  guardians in the Abyss). Summon items drop from them (Devilfish → Brine Bait 1%, Rift Warper → Umbral Sonar 1%, Jellywing →
  Siren's Comb 1.5%, Lampsquid → Inkjaw Bait 2%, Clam → Glistening Pearl 2% + Shield of the Ocean 1%, Hollow Kin → Mournful
  Shell 1.5%, Fangstalker → Abyssal Lure 1%), Dark Loot Bag 1% (Sunlit 0.4%, Driftback 15%). **Ocean.java**: music on the
  MUSIC category, `faultline:deep.sunken` above y 35 (Theme of the Sunken Sea, 362 s) / `faultline:deep.abyss` below
  (Hadopelagic Pressure, 286 s), steps aside while `FaultlineBosses.hearingBossMusic(uuid)`; dying down there = your drops
  sink and the fish eat them after `death.eaten-after-seconds` (60), respawn at 0 100 0; events every 45-90 min: PRESSURE
  SURGE (hurts below y 20 unless the Abyssal Diving Suit or Conduit Power) and UNDERTOW (a current in the Sunlit Sea).
  **The music is its own Java pack** `FaultlineDeepMusic.zip` (`item-textures.music-url/music-hash`, second UUID in
  `ItemTextureManager.sendMusic`): FaultlineSMP.zip has to stay under 25 MB. Bedrock: `bedrock/FaultlineDeep.mcpack` (models,
  eggs, music) + `faultline_deep_mappings.json` (`/bedrockcheck` knows them), made by
  `python3 tools/deep_assets.py <pack> bedrock --preview tools/previews --music <dir with sunken.ogg/abyss.ogg>`.
  **The bosses** (`DeepBosses.java`, kinds in `Wild.KINDS`, `Swimmer`/`Serpent` = 3D swimming through open water only):
  BRINE SCOURGE (boss, 2400, Kraken music; Charge, Acid Spit, Tooth Spray, Sulphur Bubbles + NEW Brine Geysers, Corroding
  Coil), UMBRAL LEVIATHAN (boss, 2600; Shadow Charge, Grab, Vanish, Shriek + NEW Eclipse Coil, Abyssal Echo), CORALIA (mini 900;
  Thralls, Petrifying Gaze, Eel Lash, Bubbles + NEW Siren Song, Coral Spires), OLD INKJAW (mini 500; Slam, Ink, Grip, Bite + NEW
  Ink Jet, Tentacle Cage), GRANDMAW CLAM (mini 900, open sea floor only (`DeepBosses.openSea` + `floorNear` 24); Shell Up 85%,
  Slam, Pearl Ring, Pearl Shot + NEW Riptide Snap, Brine Bubble; Shield of the Ocean 50%), THE LAMENT (mini 900; Lunge, Hunt,
  Wail, Whispers + NEW Sorrow Tide, Light Eater). Where: Coralia = open sea, Grandmaw = open sea near the floor, Scourge /
  Umbral / Lament = below y 35, Inkjaw anywhere; the Leviathan also fights in the Dark Ocean. `Wild.deepSpawns()`: every 5 min
  each survival player in the Dark Ocean rolls `wild.deep-spawns.chance` 0.001 → one that fits comes. Rewards: the wild
  bags/XP + `darkloot rare <p> <chance>` (bosses 50%, minis 10%) + Dark Loot Bags (3 / 1). Boss form hooks, Index entries,
  /itemsmenu (summon items + summons), enchant book drops (`Enchants.DEFAULT_DROPS`, the Lost Explorer's moved here). NOT in
  the Boss Rush. Models in `tools/wild_assets.py` (`deep_bosses()`, `render_icon`, `deep_boss_icons`; `tools/previews/deep_bosses.png`).
  **Dark Loot** (Items `DarkLoot.java`, `dark-loot:`): chests in `dark-loot.world` roll once on first open (block PDC
  `dark_loot_done`; ones players place are never rolled: `dark_loot_placed` + `placing`), the Dark Loot Bag (paper, model
  `faultline:dark_loot_bag`, PDC `dark_loot_bag`) = one roll: accessories (never the Mirror / Ankh / Abyssal gear), netherite,
  Protection IV and other top books, 12% a boss enchant book, sea treasure; 0.1% a RARE: Abyssal Diving Suit, Elytra, shulker of
  totems, shulker of invisibility potions, a god set. `/darkloot bag|rare|give|roll`. Index: chapter `deep` (20 creatures),
  6 bosses, 6 summon items, the bag, achievements Into the Dark / Tides of Ruin / Abyss Walker. Tested: `DeepTest` (scratch
  `ftest`: world, 20 creatures, Mirror, Lucki's trip, dying, events), `DeepBossTest` (bosses harness), `DarkLootTest` (items).
- **Deep bug pass (Deep 1.0.1, Bosses 1.6.1, Items 1.2.42)**: MUSIC NEVER OVERLAPS. Every boss's music start calls
  `FaultlineBosses.heardMusic(p, kind)` (`MUSIC_HEARD`); `hearingBossMusic(uuid)` = a wild/deep boss lists them as a
  listener, or that other boss is still out (`rushAlive`) and they're in the world where it started. FaultlineDeep's
  `Ocean.music()` (every 2 s) STOPS its own track while that's true and resumes after; it also stops vanilla's underwater /
  game music keys (`Ocean.VANILLA`, its own track shares the MUSIC category) and has 4 blocks of give round y 35 (no flipping
  between the two songs). Bosses' `quietVanilla` (every 2 s) keeps the MUSIC category off for anyone hearing boss music.
  Wild/deep bosses stop their music for listeners who leave (other world or 160+ blocks: `musicTick`). A NEW BOSS's music
  start must call `heardMusic` (and `rushAlive` must know its kind). Other fixes: the Abyssal Diving Suit never protected from
  Pressure Surge (reflection on the package-private AccessoryManager needed `setAccessible`); Lucki's boat goes home when
  a trip ends early (rider quit/died) and drifts back to his spot; Lucki refuses a whirlpool spot that isn't water; Rift
  Warper bolts/blinks only land in sight (no teleporting into sealed bases or other caves); spawner guardians capped at
  `mobs.vanilla.guardian-cap` 3 near a player; no spawning on Peaceful; a deep boss's grab / grip / bubble lets go of a
  player who changed worlds; the Leviathan only deep-spawns for players near the surface (it swims up there); deep spawns
  respect boss cooldowns and only announce a boss that really came; meteors, the Caravan, weather and Skeleton Knight
  patrols never pick the Dark Ocean (`FaultlineItems.surfaceWorld`). Tested: `MusicTest`, `DeepBossTest`, `DeepTest`.
- **Deep bug pass 2 (Deep 1.0.2, Bosses 1.6.2)**: FaultlineDeep had `load: STARTUP`, but Paper refuses to create a world
  then ("Cannot create additional worlds on STARTUP"): the Dark Ocean would never have loaded on the real server. It now
  enables after the worlds (default POSTWORLD) and creates/loads `faultline_deep` itself. RULE: a plugin that creates
  worlds must not be `load: STARTUP`. The generator's block data was set up lazily with no lock (chunks generate on several
  threads: a first chunk could hit a null); now made once under a lock, the volatile `chest` written last. Death drops in
  the Dark Ocean are held where you died (`holdDrops`: items float up in water and drifted off in the minute). No bucketing
  the Gawker/Bloatfin (cod/salmon underneath: out came an invisible fish), no interacting with any creature, the Clam
  (a shulker) doesn't hop away when hurt. Deep boss bolts: homing on top of a player no longer makes NaN, a bolt leaving the
  loaded area is dropped (it would have loaded chunks), every "towards the player" direction goes through `Swimmer.unit`.
- **The sea boss remodel + Infernum Scourge (Bosses 1.6.3, Items 1.2.43 = pack hash, Index 1.6.32, Ships 1.6.5 = WildParts)**:
  all 6 deep boss models redone in `tools/wild_assets.py` (`deep_bosses()`: new painters `aq_scales`, `bone_curve`, `glow_lines`,
  `skin`, `hair`, `ribbed`, `mantle`, `suckers`, `eye_paint`, `cracks`, `arc_boxes`). The BRINE SCOURGE looks like Calamity's
  Aquatic Scourge: teal diamond scales with navy seams, one curved bone spike per side per segment, a dark tail point, and two
  crescent MANDIBLES as their own Java parts (`scourge_mandible` / `scourge_mandible_r`, hinge (0.55, 0, ∓1.0), opened by
  `aMand`/`wantMand`) + a Bedrock-only `scourge_head_b` (mandibles as bones `mand_l`/`mand_r`; `scourge_head` is java_only).
  Its fight follows Infernum's (https://infernummod.wiki.gg/wiki/Aquatic_Scourge): phase 1 is a fixed cycle (`CYCLE`) PULSE
  (acid spreads each second + green pulse), RUBBLE (5 lunges, acid trail, ramming rock = 1 s stun + rubble fan), BREATH
  (sharp turns spitting acid, rubble falls on green lines), RUBBLE, SPIN (toxic bubbles); phase 2 (`phaseAt` 0.6; Infernum's
  phase 2 isn't documented) = the same, harder; phase 3 at 25%: ASCEND to the surface, a storm (player weather), the water's
  acidity climbs exponentially (`final-acidity` 0.003 × 1.045^s, only the air is safe), FINAL = FRENZY, ACID_RAIN x2, TYPHOON.
  Sulphuric Poisoning per player (action bar; full = 3 unblockable/s, stays full until a fresh blue bubble or air brings it
  down; bubbles rise from below to help). Anyone above the water in phases 1-2 = ENRAGED (no damage taken, +50% dealt;
  cleared when phase 3 starts). Death: the corpse dissolves. `Wild.phaseAt(int)` is a new per-boss hook (default
  phase2-at / phase3-at). `Wild.Boss.tick` runs a copy of `fx` (an effect starting another one threw
  ConcurrentModificationException). Deep boss bolts hit a player at most once every 8 ticks (`Swimmer.boltHit`: a point-blank
  volley landed all at once). Tested (`DeepBossTest`: the new cycle, enrage, poisoning, the final phase).
- **The Bubble + ruins (Deep 1.1.0)**: `Town.java` puts a glass DOME over the build area (centre 681, -951, ground top y -50;
  ellipsoid radius 72 across, 54 high; glass + light blue/cyan glass, dark prismarine ribs every 22.5 degrees, sea-lantern
  bands and crown). Inside there's NO water: a block is air only when all 6 neighbours are inside the ellipsoid, so no air
  ever touches the sea (`TownPreview` checks 0 leaks). Four gatehouses (`GATES`: E/W on the avenue z -943, N/S on x 681)
  have 3 dark oak doors in their outer face (water never flows into a door), a lit path outside. Inside: an abandoned town
  of 61 buildings (`LOTS`, a fixed layout packed along the streets once: houses, townhouses, blacksmiths, bakeries, general
  stores, taverns, libraries, apothecaries, collapsed ruins; 6 palettes; timber frames, windows (some broken / webbed),
  shutters, awnings, gable roofs, chimneys, full interiors), a plaza (fountain, market stalls, clock tower with a bell),
  lamp posts, and THE PLOT: 50 x 50 smooth stone, x 656..705, z -1001..-952, top at y -50, chiseled border, kept empty for
  the Black Market. `/deep town [player]` goes there. Town chests are Dark Loot chests but only 30% of them (the rest are
  empty barrels). In the bubble (`Town.inside`) Pressure Surge doesn't hurt and dying drops your things normally.
  `Ruins.java`: one ruin per 176-block cell of the Sunlit Sea (50%: ruined house, tower, temple colonnade, broken gate) and
  per 200-block cell of the Abyss (45%: blackstone shrine with soul lanterns, deepslate tower/house), waterlogged, many with
  a Dark Loot chest; never near the arrival point, the build area or a blue hole. Both are drawn by `Paint` (one structure
  drawn whole per chunk it touches, clipped; randomness from world position; `Paint.bd` caches block states, turns them
  with `BlockData.rotate`, and falls back to the plain block if a state is invalid, so a bad name can't break generation).
  NOTE 26.x: `chain` is `iron_chain`. Already-generated chunks don't change: delete the `faultline_deep` world folder (server
  stopped) to get the Bubble and the ruins. Previews: `tools/previews/town_*.png`, `ruins_all.png` (scratch harness `dtest`, `TownPreview`).
  Tested (`DeepTest`: bubble rules, layout vs plot/dome).
- **Coralia = Cala Maria (Bosses 1.6.4, Items 1.2.44 = pack hash, Index 1.6.33, Ships 1.6.6 = WildParts)**: remodelled after
  Cuphead's Cala Maria (`coralia()` in `tools/wild_assets.py`, painters `toon`, `curl`, `button`, `shell_paint`, `fish_tail`,
  `snake_paint`, `leaf_paint`): lavender skin, purple bob with a row of octopus-tentacle curls (pink suckers) and two button
  buns, mint eyes under green eyeshadow, lashes, angry brows, pink lips, blue scallop-shell top, scars, a green tail with her
  skin in scallops over it. Phase 3 she becomes her MEDUSA form: `onPhase(3)` swaps torso/head/arms to `coralia_*_medusa`
  (green skin, snake nest with 5 heads + a big rearing one with fangs and a forked tongue, yellow slit eyes, a fanged grin,
  leafy top, claws) through the new `Wild.Part.swap(model)` (changes the Java display and the Bedrock stand's helmet). The
  head is ppb 32 (eyes need the pixels). No outlines on `toon`: `rounded()` overlaps two boxes and an outline would cross
  the face. Tested (`DeepBossTest`: the Medusa swap).
- **The Black Market + the Pit (Deep 1.2.0)**: `Market.java` builds the Black Market on the Bubble's plot (the plot is no
  longer empty): a gothic hall of blackstone x 663..698, z -995..-962 (front faces the town, south), buttresses, 4 turrets
  with spires, purple lancets, a steep deepslate roof (ridge north-south), a crimson double door (x 680-681) under a
  pointed arch, and THE EYE in the front gable (almond, bloodshot white, purple iris, slit pupil, a crying obsidian /
  shroomlight panel behind it). Forecourt: iron fence with a gate, skull posts, soul-lantern posts, two hooded statues.
  Inside (dark): timber trusses every 6 z with soul-lantern chandeliers on chains, a checker floor, a red runner; THE QUEUE
  (x 665..678, z -986..-965) of chain stanchions on red carpet snaking to the service window at x 676-678; THE DESK
  (z -989, x 665..692, gate at 693) with a bell, candles, heads, a lectern and a stool behind; THE TIER WALL (z -994,
  x 672..689): 8 columns for tiers 1-8, two EMPTY shulker boxes each (white, light gray, yellow, lime, cyan, blue, purple,
  black), a candle and banner per tier, a dragon head over it, bookshelves either side; vaults, an ender chest, barrels;
  the east side: 6 glass cases of heads, a tier bar chart, tables. THE LIFT (`Elevator.java`): car x 695..697, z -980..-978,
  buttons `Market.UP_BUTTON` (697, -48, -979) / `DOWN_BUTTON` (697, -59, -979); everyone in the car rides (2.5 s, Darkness,
  chain sounds) to the other floor, same x/z, 11 blocks; an iron cage, rope and pulley above it. THE PIT under the plot:
  air x 661..693, z -996..-961, y -60..-52 (floor top -61), a fenced pit (x 667..687, z -990..-967) with an emblem, gates on
  4 sides, two rows of seats round it, a throne (north), cages, podiums, basalt pillars, banners, shroomlights. Already-made
  chunks: delete `r.1.-2.mca` (+ entities/poi) in `faultline_deep`. Previews `tools/previews/market_*.png`.
  Tested (`DeepTest`: the lift down, up, a press mid-ride, standing outside; `TownPreview`: no water touches the Pit).
- **Deep 1.2.1 (bug pass)**: the Pit and the lift's lower car are under the Bubble's floor, so `Town.inside` missed them:
  Pressure Surge hurt players fighting in the Pit and their drops were "eaten". `Market.dry(x, y, z)` (Bubble + Pit + lower
  car) is what Ocean checks now. Dying somewhere dry respawns you in front of the Black Market (`Town.plotSpawn`), not at
  the surface entry. `MarketGuard`: without `faultlinedeep.market` (op) nobody breaks the dome's shell or a gatehouse (one
  hole floods the whole town), and in the plot / Pit / lift nobody breaks, places, empties buckets or uses anything but
  doors, buttons, gates, the bell, the lectern and the ender chest (the tier wall's shulker boxes are safe); explosions
  and pistons skip all of it; `market.protect` (true). Tested (`DeepTest`).
- **Deep creatures v2 (Deep 1.3.0, Items 1.2.45 = pack hash)**: all 20 creatures remodelled in `tools/deep_assets.py`
  (painters `fish`, `finp`, `spotted`; helpers `eyes`, `pair`, `side_bone`, `staircase`; `shark()` for the three sharks)
  and every one has named moving BONES with looping animations: tails, fins, wings, jaws, the clam's lid, arms,
  tentacles, legs, claws, eye stalks, kelp, a lure, a hem. Bedrock plays them in `FaultlineDeep.mcpack`. Java: each bone
  is its own model `faultline:deep/<id>__<bone>` (exported with its pivot at the origin; the body is `deep/<id>`), one
  ItemDisplay per bone riding the body (`Beast.parts`, `bones`), posed every 2 ticks by `DeepMobs.pose` from
  `DeepParts.java` (WRITTEN by deep_assets.py: bones + pivots + the same anim numbers, Bedrock axes/units; `animate()` maps
  them: rot (x, y, z) -> rotZ(-x) rotY(y) rotX(-z), pos px -> (-z, y, -x)/16; `half` = jaws/lids that only open and shut,
  `wild_assets.bedrock_anim` honours `part.half`; "body" = whole-creature motion about `part.pivots["body"]`, which
  `bedrock_geo` now uses as the root pivot). Tails beat faster while it swims; jaws/lids open wider when angry. FIXED: the
  model used to follow the body's own pitch (squids report odd rotations): jellies and squids lay on their sides. Heading
  now comes from the velocity, eased; `UPRIGHT` kinds (bells, floaters, the clam, ghosts, crawlers) never pitch. Updates
  every 2 ticks only with a player within 48, else once a second. Tested (`DeepTest`: every creature's parts).
- **The Wardrobe + suit/Vitality changes (Items 1.2.46, Cosmetics 1.1.4, Index 1.6.34, Deep 1.3.1)**: `/accessories`
  (alias `/wardrobe`, and `/cosmetics` on Java; `/cosmetics all` = the old list, `open-wardrobe: false` turns that off) is
  `Items/Wardrobe.java`: one 6-row menu with a custom background (`tools/gui_assets.py`: font `faultline:gui`, a -8 space
  U+F801 + one 176x222 glyph per tab U+F810+tab, ascent 13; invisible filler model `faultline:gui/blank` with a hidden
  tooltip; `wardrobe.custom-background`). Col 0 tabs (Accessories, Hat, Neck, Back, Body; row 5 take every cosmetic off),
  col 1 the list (the 3 accessory slots = `AccessoryGuiHolder.SLOTS` {1, 10, 19}, or that slot's cosmetics, owned first),
  col 2 scroll, col 3 your real armour (put on / take off; Curse of Binding respected; `fits()` = equippable component,
  else vanilla's slot), cols 4-7 the black preview (front col 5, back col 7: hat/body/back cosmetics over the armour, your
  head), col 8 eye (hide all cosmetics without taking them off: `Data.hidden`, Renderer uses `shown()`), seasonal shop,
  help. `Wardrobe.Menu extends AccessoryGuiHolder`; only the Accessories tab holds the slots (`holdsAccessories`, `saved`:
  switching tabs / the shop saves them out first, `hasEquipped` reads the menu only while `live()`). Shift-click puts an
  accessory in a free slot or armour on. Cosmetics are reached by reflection through `FaultlineCosmetics.wardrobe*` (public
  static). Bedrock: plain title + glass panes. **Abyssal Diving Suit**: only being hit BY A PLAYER switches it off now
  (`diving.suit.off-when-mobs-hit` / `off-when-attacking`, both false; the old `combat-includes-monsters` key is ignored),
  and it can be worn as a CHESTPLATE (equippable CHEST, added to old suits by `AccessoryStacks.refresh`; `wearsSuit` =
  accessory or chestplate; Deep's Pressure Surge checks the chestplate too). **Vitality I/II = Regeneration I/II** (ambient,
  topped up only when under 1.5 s left so the heal timer runs normally; never cuts a stronger potion; the old MAX_HEALTH
  modifier is always removed). Tested: `WardrobeTest` (items), `WardrobeCosTest` (Items + Cosmetics, scratch `wtest`),
  `NerfTest`, `BatchTest`.
- **THE SULPHUR SEA (Deep 1.4.0, Bosses 1.6.5, Items 1.2.47, Index 1.6.35, Ships 1.6.7 = WildParts)**: `Deep/Sulphur.java`, an
  acid region of the Dark Ocean round `SX, SZ` = -520, 480 (radius 230 + a 70-block eased, wobbling edge; `weight()`),
  after Calamity's Sulphurous Sea. Its noise (`Noise.sulphur`, `sulphurEdge`) is made LAST in the Noise constructor, so
  every other feature keeps its shape (RULE: append new noise at the end). `upperFloor` blends to `Sulphur.floor`:
  basins ~66, shelves, and ISLANDS breaking the surface up to ~114 (13% of the region). Columns there: `Zone.SULPHUR`
  (above the floor), biome SWAMP (murky green water; `DeepBosses.sulphur(l)` reads it), no caves within 14 of the sea
  (water would spill out of island caves), no chimneys / sea stacks / ruins / arches / whale falls / spires near it.
  Blocks: olive terracotta, mud, sulphur crusts (yellow terracotta + ochre froglights), dead coral reef, toxic kelp,
  slime, acid vents (magma + bubble columns), lily pads in the shallows; islands: mud beaches, moss, dead bushes.
  Structures (`Sulphur.paint`, drawn per chunk like Ruins): SULPHUR COLUMNS (banded spires out of the sea, verdant froglight
  veins), RUSTED SHIPWRECKS (oxidised copper hull, mast, a Dark Loot chest), GIANT RIBCAGES (bone ribs + a skull with
  glowing sockets), ACIDWOOD TREES (dead dark oak, mangrove roots, pale hanging moss), FUMAROLES (tuff cones over a
  signal campfire on hay), ACID POOLS (only where the whole rim stands as high as the water), and THE SULPHUR WORKS at
  `WORKS_X/Z` (a stilted rotting platform: shack, rusted tank, crane with a chain and anvil, rails). `put()` makes
  anything at or under y 100 wet and turns "water" above the sea into air (a ribcage skull on an island leaked).
  Previews: `tools/previews/sulphur_*.png` (scratch `dtest` `SulphurPreview`: 0 spills). `/deep sulphur [player]`.
  `Ocean.sulphur()`: its water poisons you (every 2 s) unless FaultlineItems' diving tier >= `sulphur.safe-diving-tier` 3
  (Abyssal Diving Gear / the Suit); a title the first time you enter. Already-generated chunks there don't change.
  **20 acid creatures** (`DeepMobs`, zone SULPHUR; models in `tools/deep_assets.py` `SULPHUR_MOBS`, all with bones and
  animations): Acid Eel (acid trail), Trasher (croc, lunges at the shore/boats), Radiator (burn aura), Flak Crab (acid flak
  snowballs tagged `ACID_TAG`, front shell -60%), Nuclear Toad (hurt = blows up a second later, no blocks), Gamma Slime
  (SLIME size 2 set before attributes; poison puddles; no split: `onSplit`), Sulphur Skater (frog kept on the surface:
  `skate()`), Acid Jelly, Orthocera (jets + acid ink), Trilobite (silverfish: `onBlock` stops it hiding in stone; curls up
  -80%), Skyfin (phantom, `setShouldBurnInDay(false)`), Sulphur Leech (latches on and drinks: `latchTick`), Bog Lurker
  (a disguised stump: `DISGUISED`, wakes within 4.5), Corrosion Shark (wears armour down), Sulphur Urchin (shulker; stings,
  fires spine arrows), Crablings (swarms of 3-5), Bloatgut (acid gas), Sulphur Moth (bat), Hazard Turtle (thorns 30%, rams),
  Sludge Snail (slime trail clouds, arrows bounce). Habitats: `LAND`, `AIR`, `FLOOR` sets; the spawner tries an island spot
  (`landSpot`, `pickLand`) `mobs.sulphur.land-share` 0.4 of the time in the Sulphur Sea. FIXED: `onVanillaSpawn` cancelled
  our own phantoms (any plugin phantom): `spawning` flag around our spawn. Brine Bait now drops from Trashers 3%, Corrosion
  Sharks 4%, Acid Eels 1% (and Devilfish 1%). Index: 20 entries (chapter deep), achievement `acid_bath` (15 of them);
  `into_the_dark` asked for 25 of a 20-entry list (impossible): it lists all 40 now. /itemsmenu has the 40 eggs.
  **The Brine Scourge** lives ONLY in the Sulphur Sea now (`whyNot`: biome SWAMP). Phases 1-2 unchanged. NEW PHASE 3 at 30%
  (`phase3-at` 0.3; the old surface ascent / rising acidity are gone): MOLT (4 s, untouchable: `husk()` leaves dark copies of
  every part that sink and shrink for 15 s; the rig swaps to `scourge_*_molt`: raw red flesh with lime veins, spikes snapped
  to stubs) -> faster (x1.25), takes x1.15 (`molt-damage-taken`), never enraged; sulphur storm (`stormTick`: acid drops
  above the water). Cycle `FINAL`: SPINES (fans of spine bolts; a spine hitting rock = a pylon: `pylon()`, 12 s, burns within
  2.4, pairs within 12 joined by beams: `pylonTick`, max 8), BREACH (dives under you, then free flight with gravity through
  the air, glides over islands, lobs acid, slam shockwave 7, back into water: `openWater`), GEYSERS (rings on the floor
  under everyone + 3 more, then a column to the surface + 6: throws you up), CLOUDS (5 clouds rolling along the surface /
  island tops toward a player; eyes under water = safe), BREACH, MAELSTROM (tightening spiral, pull in + down, acid heart).
  Once below 10% (`coil-at`): COIL (rings the group 6 s, squeeze 3/s; `coil-break-share` 6% of max hp in hits frees them,
  else a 16 crush). Moves `moves.spine/pylon/beam/breach/breach-slam/geyser/cloud/maelstrom/coil-squeeze/coil-crush`.
  `withY()` helper (Bukkit's `Location.setY` returns void). **The Umbral Leviathan** remodel (`umbral()` in wild_assets,
  after Below Zero's Shadow Leviathan): stepped wedge head rising to a crest, 3 red eyes a side, hooked black claws
  (`tube()` = continuous curved limbs of small cubes), a cyan rib-comb under the belly, black leg-spikes raking back on
  every segment (bones `legs_l/r`), red dots down the back, a flat finned tail with a red tip. Tested: `DeepBossTest`
  (summon only in SWAMP, the molt swap, more damage, never enraged, coil break, pylon beams), `WildTest`, `MusicTest`,
  `StuckTest`, `DeepTest` (40 creatures, toad, leech, trilobite, urchin, snail, acid water + diving tier), `TownPreview`.
- **Acid Rain (Deep 1.4.1, Items 1.2.48 = music hash)**: in the Sulphur Sea (`Ocean.inSulphur`) the music is
  `faultline:deep.acid` (Calamity's "Corrosion - Theme of the Acid Rain", 105 s, `music.acid-seconds`) instead of the depth
  tracks; boss music still wins. It's in `FaultlineDeepMusic.zip` (`sounds/deep/acid.ogg`, new `music-hash`) and the Bedrock
  `FaultlineDeep.mcpack` (`deep_assets.py --music` reads sunken/abyss/acid.ogg). It always rains there
  (`sulphur.always-raining`): per-player `setPlayerWeather(DOWNFALL)` re-applied every 2 s, reset on leaving / quitting /
  changing worlds, plus GREEN RAIN particles (lime/yellow concrete-powder falling dust + green dust,
  `sulphur.green-rain-particles` 40, every 4 ticks, not under a roof). Tested (`DeepTest`).
- **The Acid Rain batch (Deep 1.5.0, Bosses 1.6.6, Items 1.2.49 = pack hash + eggs, Index 1.6.36)**:
  **Sea level y 300** (`DeepGenerator.SEA` 100 -> 300; the owner asked for 350, but the world stops at y 319 and Geyser can't
  show anything above it, so 300). Terrain below is unchanged: the Sunlit Sea floor stays ~46, so ~250 blocks of open water;
  you arrive at 0 300 0. The Sulphur Sea is relative to SEA (`Sulphur.floor`: basins SEA-34, islands capped SEA+9, columns
  SEA+6..18, all under y 319): it rises on sheer cliffs from the deep floor to the surface (the region's crust fills up to
  ~266, so its chunks cost ~45 ms). Already-generated chunks don't change: DELETE the `faultline_deep` world folder (server
  stopped) to get the new sea. Zone / music / pressure lines (y 35, y 20) are unchanged.
  **Tail glitch**: deep creatures' bone anims were sampled every 2 ticks with interpolation 3 (overlapping poses) and the
  tail swing grew to 2.2x while swimming (60 degrees, bending off the body). Now each `Beast` has its own `clock` (runs up to
  1.8x faster while swimming: faster beats, never wider; amp max 1.25), interpolation = the real gap between poses, and no
  anim faster than 0.8 s (`DeepMobs.animate`).
  **Acid mobs detailed**: `tools/deep_assets.py` `_detailed()` doubles their texture resolution (ppb 32) and adds gills,
  barnacles, rust, drips, claws, spines, glowing veins (`crust`, `rusty`, `slick`, `into(p, bone)`, `drips`, `barnacles`).
  **The Acid Rain** (`Deep/AcidRain.java`, `acid-rain:`): every `check-minutes` 5, if anyone is in or near the Sulphur Sea
  (`generator.sulphur` > `edge-weight` 0.15), `chance` 0.005 it starts for `minutes` 6: boss bar, thunder, green rain x3
  (`Ocean.greenRain`), out in the open without a helmet = poison (a helmet takes `helmet-wear` instead). Its 5 creatures
  (`DeepMobs.RAIN`, weight 0 so the normal spawner never picks them, spawned by `spawnNear` every `spawn-seconds` up to
  `cap-per-player` 6): Sludge Shambler (DROWNED, sludge globs `GLOB_TAG`), Rustbones (SKELETON with a bow, poison II arrows
  `onShoot`), Acid Mite (ENDERMITE, packs), Storm Wraith (VEX, acid downpour), Bile Mauler (DROWNED x1.9, charges, armour wear
  `wearArmor`). After `goblin-after-seconds` 90: **GRUBNASH THE ACID GOBLIN** (kind `goblin`, VINDICATOR, 500 hp, own bar):
  Acid Flasks (`FLASK_TAG` pools), Scrap Bombs (`BOMB_TAG`, 1.5 s, 10 dmg r 3.6, no blocks), Goblin Leap (slam 9), Call the
  Mites; frenzy below 50% (Speed II, faster). Drops emeralds/gold/TNT, `darkloot bag 2`, 10% `darkloot rare`, 300 XP, Index stat
  `acid_goblins`, achievement `acid_rain_survivor`. The rain ending melts its creatures (a living goblin runs off, no loot).
  `/deep acidrain [start|stop|goblin]`. Index: 5 entries (deep) + Grubnash (bosses); eggs in /itemsmenu (`deep egg <id>`).
  **Lucki is Swarm** (`Swarm.java`): when the rider drops into the whirlpool he laughs, a puff of ink, and Swarm stands in his
  boat (the old `swarm_*` rig, still in the pack, posed like renderRig; Bedrock: a black armor stand with a wither skull),
  points and talks (`Journey.SWARM_LINES`) while the rider is carried round and down the funnel (`reveal`, ~12 s), then sent.
  Lucki is hidden meanwhile and back at the dock after. **The whirlpool** (`Vortex.java`): a real funnel, FAKE air sent with
  `sendMultiBlockChange` to everyone within 96 (the world never changes: nothing floods, nothing to restore after a crash):
  opens over 3-4 s to `journey.whirlpool-radius` 9 / `whirlpool-depth` 16 (or the floor), spray spirals down the walls, foam rim,
  mist, a dark eye; players there slide round and down into the eye (= sent); closes at the end.
  **Sea boss AI** (`DeepBosses.Swimmer`): every move goes through `clear(a, b, r)` (water every half block along the way; before,
  only the end was checked, so fast moves skipped through thin walls), `step()`. `swimTo` looks ahead (`bestDir`: 60 directions,
  the open one leading most toward the goal, sticks with the side it picked), plans an A* path over 2-block cells (`plan`,
  1800 nodes, 48 blocks) when it hasn't got closer in 50 ticks or hasn't moved in 2 s, follows it (skipping to the furthest
  clear waypoint), and only as a last resort `unstick()`s (ink, reappears in open water near the goal). `circle()` never picks an
  orbit spot inside rock or out of sight (`sight()`: a block walk, MockBukkit has no ray tracing). Serpents lay their bodies
  ALONG the head's trail (`Serpent.follow`), so segments never cut through rock at bends. Vanish only reappears in sight
  (`seen`), the ambush / molt / clam slam stop at rock.
  **Scourge bubbles**: every `air-bubble.every-seconds` 20 giant air bubbles (`wild/air_bubble`, translucent, made by
  deep_assets.py `bubble()`) rise near up to 4 fighters for 12 s: inside = air refills + poison drains. Every
  `pink-bubble.every-seconds` 30 a pink one (`wild/pink_bubble` + an invisible slime hitbox tagged `Scourge.PINK_TAG`,
  `Wild.onHurt` -> `pinkHit`): hit it = it bursts and the Scourge takes `pink-bubble.damage` 150 (even enraged/molting).
  Tested: `DeepTest` (ftest: 46 kinds, acid zone at the new height, funnel, Swarm, Acid Rain + goblin), `DeepBossTest` (bubbles,
  no phasing through a wall, A* round it, it gets to the far side), `WildTest`, `MusicTest`, `StuckTest`, `AchievementTest`,
  `SulphurPreview` (0 spills), `TownPreview` (0 leaks).
- **Bug pass (Deep 1.5.1, Bosses 1.6.7)**: the sea bosses' pathing was too costly: A* now tests each 2-block cell and each
  half-way point ONCE (cached; both ends already have room, so the middle is all an edge needs), 1200 nodes, at most every 3 s;
  `bestDir` sorts its 60 directions by score and stops at the first open one (it tested all 60 every blocked tick); the serpent
  trail is walked with an iterator (LinkedList.get(i) made it quadratic every tick). Scourge bubbles no longer leak their
  cleanup / pink-bubble entries over a long fight. The goblin's acid flask cloud lost its Harming effect (a cloud reapplies it
  every second: 3 hearts a second). An egg-spawned goblin dying no longer drops the event goblin's boss bar
  (`AcidRain.goblinDied(uuid)`); turning `acid-rain.enabled` off mid-event stops it. Bedrock sees Swarm's name (on its stand).
  The whirlpool resends its fake funnel only when the shape changes, to new viewers, or every 2 s (it was every 5 ticks).
- **Lag + music pass (Deep 1.5.2, Items 1.2.50, Index 1.6.37)**: **Pack hashes are automatic** (`Items/PackHash.java`,
  `item-textures.auto-hash` true, `auto-hash-minutes` 10): the plugin downloads each pack url off the main thread at startup,
  SHA-1s it and re-checks with an ETag request (304 = nothing downloaded); packs are sent with the REAL hash and a config hash
  that disagrees is ignored with a warning (a stale `music-hash` made games reject the whole music pack = no Dark Ocean
  music). The music pack's status is logged too ("(Dark Ocean music pack)"). **Dark Ocean creature LOD** (`DeepMobs.pose`):
  every bone pose is a packet to everyone tracking it, so the update rate depends on the nearest player: < 24 blocks every 2
  ticks, < 48 every 4, < 80 every 10, else 40 (interpolation = the gap); the Bedrock stand-ins move every 2 ticks only while a
  Bedrock player is in the Dark Ocean (`bedrockHere`, else every 40). `mobs.cap` default 14 -> 10. **Generator**: no caves in
  the raised Sulphur plateau above y 70 (Sulphur chunks 45 -> 30 ms). **Items**: Skeleton Knight dawn check walks the knights it
  spawned (`Creatures.knights`) instead of scanning every entity in every world every 10 s. **Index**: the inventory scan reads
  items' PDC through Paper's read-only view (`ItemStack#getPersistentDataContainer`, no meta copy), every 5 s (was 2).
- **The dark + 20 more creatures (Deep 1.6.0, Bosses 1.6.8, Items 1.2.51, Index 1.6.38)**: **THE ABYSS IS PITCH BLACK**
  (`Ocean.dark()`, every second, `darkness:` in Deep's config, zones ABYSS + CAVES; never the Pit/lift, `Market.dry`): hidden
  Darkness (ambient, no particles: `Ocean.ours`; it also cancels the client's brightness slider; a Lampsquid's / Warden's own
  Darkness is left alone), every Night Vision removed, player metadata `faultline_abyss_dark` (Items' Abyssal Diving Gear and
  Mining Helmet hold off their Night Vision while it's set). Light down there: a Lantern of Souls held (PDC
  `faultlineitems:gear=lantern_of_souls`), a Glowgrub or Dumbo Octopus within 6-7 blocks (`glowLit`, Ocean ticks), glowing
  creatures (`DeepMobs.GLOW`: display brightness 15; `DIM` = 4, faint shapes), light blocks (new abyss chunks: shroomlight on
  1 hanging cone in 12, was 1 in 3). **20 new creatures** (models in `tools/deep_assets.py` `NEW_SUNLIT`/`NEW_ABYSS`, ppb 32, bones
  + anims): Sunlit: Sailfin Marlin (charges spear first when hit), Manta Glider (passive, its wake = Dolphin's Grace + Water
  Breathing, max 2), Leafy Seadragon (vanishes in leaves when hit), Pearl Seahorse (0.5% Heart of the Sea), Hammerjaw (ram:
  slow + nausea), Razorcuda (packs of 3, bites bleed = Wither), Stingray (buried, `FLOOR`; swim over = barb, Poison II), Mirrorfin
  (hurt = blinding flash), Kelp Strangler (rooted, AI off; pulls you in from 7), Sea Siren (song: nausea + drawn in, hit her to
  break it; 3% Siren's Comb). Abyss: Gulper Eel, Giant Isopod (rolls up like the trilobite), Ghost Shark (display view range 0.16:
  only seen within ~10 blocks), Vampire Squid (drains 2/s while angry), Dumbo Octopus, Black Swallower (bite eats one of your
  foods), Chainlight (stinging chain of bells), Viperfang, Slime Hag (slime: Slowness III + blind), Abyss Watcher (gaze: 5 + slow +
  fatigue + Darkness, line of sight `DeepMobs.sees` = block walk; the model turns to face you, `Beast.lookAt`). Index: 20 entries,
  achievements Eye to Eye + Sunless Sea, into_the_dark lists all 65; /itemsmenu eggs. **Acid mobs, detail pass 2**
  (`_m_<id>` in deep_assets.py over the 1.5.0 pass): snouts, teeth rows, joints, segment rings, ridges, glowing veins.
  **Brine Scourge fixes**: the TAIL WAS DETACHED on every serpent (Scourge, Umbral, Lament): the chain has one more point than
  there are segments and the tail sat on that spare point, one whole gap behind the last segment; now it hangs off the last
  segment's joint (`Serpent.pose`), and gaps taper exactly like the segments (`gap * k * (1 - taper * i)`; they tapered half as
  fast, so holes opened down the body). Faster (`scourge.speed-mult` 1.35 on every move, idle `speed` 0.6, an old 0.45 reads as
  0.6), between moves it keeps swimming loops UNDER the surface (`wetTarget`: never aims at an island or the air), and out of the
  water it drops/slithers back in (`regainWater`; a breach that lands on an island ends in open water). Wild bosses vanishing
  mid-fight: (1) any error in a move removed the whole boss (`Wild.tick` catch): now `Boss.tick` ends just that move and logs
  it ("error in move N"), and only 100 errors in a row remove it; (2) a big display is culled by a 0x0 box at its origin, so
  segments blinked out whenever their origin left the screen: every `Wild.Part` display gets `setDisplayWidth/Height(max(4, 7k))`.
  Tested: `DeepTest` (66 creatures, the new abilities, the dark, lantern, Dumbo), `DeepBossTest` (tail joint, speed, a broken
  move, back into the sea), `WildTest`, `StuckTest`, `MusicTest`, `OceanTest`, `AchievementTest`, `MuseumTest`, `NerfTest`.
- **Lifesteal nerf + 9 new boss enchants (Items 1.2.52, Index 1.6.39)**: LIFESTEAL against PLAYERS heals only on every 5th hit
  you land on players (`Enchants.pvpHits`, `enchants.lifesteal.pvp-every-hits` 5, action bar "Lifesteal n/5"; mobs still heal
  every hit; bows count too). The procs are one method now (`Enchants.procs`, melee + arrows). New (`DEFS`, Kinds LEGS +
  HELMET added): EXECUTIONER I-II (weapons + bows: +12%/lvl below 30% health), CLEAVE I-II (melee only, charged: 25%/lvl of
  the hit to everything within 2.5 of the target; skips you, your pets, armor stands and INVISIBLE entities = boss hitboxes;
  `cutting` guard so splashes / bleed / thorns never proc), BLEED I-II (weapons + bows: 12%/lvl, 1 a second for 3+lvl s, from
  the cutter), ARROWGUARD I-II (chestplates: -10%/lvl projectiles), THORNHIDE I-II (leggings: 10%/lvl of melee back),
  SWIFTSTRIDE I-II (boots: MOVEMENT_SPEED modifier `faultlineitems:swiftstride` +5%/lvl, refreshed every second, removed on
  quit/disable), TIDECALLER I (boots: Dolphin's Grace in water), AUTOSMELT I (pickaxes: raw iron/gold/copper drops as ingots),
  CLEARSIGHT I (helmets: cancels Blindness/Darkness, except hidden ambient ones = the Abyss's dark, cutscenes). Their drops are
  `Enchants.NEW_DROPS`, rolled ON TOP of `boss-drops` (a server config keeps its old lists; override with
  `enchants.new-boss-drops.<entry>`). Index's Boss Enchant Book entry lists them all. Tested (`EnchantTest`, `BatchTest`).
- **Boss enchant nerf + the suit is a chestplate (Items 1.2.53, Index 1.6.40)**: EVERY boss enchant nerfed through
  `Enchants.nerf(path, old, new)` (= `Balance.num`: a server config still holding the old default reads as the new one):
  Lifesteal 8% -> 5% a level, max 3 -> 2 a hit (PvP still every 5th hit); Frostbite / Venom 15% -> 10% a level;
  Thunderstrike 8% -> 5%, +4 -> +3, 8 s -> 12 s; Vitality: Regeneration I at both levels (`max-regeneration-level` 2 -> 1),
  and Vitality I only below half health (`vitality.level1-below` 0.5); Featherstep 35% -> 25%; Vein Miner 32 -> 16 blocks;
  Executioner 12% -> 8%; Cleave 25% -> 15%; Bleed 12% -> 8%, 3 -> 2 s base; Arrowguard 10% -> 6%; Thornhide 10% -> 6%;
  Swiftstride 5% -> 3% a level. **The Abyssal Diving Suit is a CHESTPLATE ONLY**: `DivingGear.chestplate()` takes the
  `is_accessory` tag and the "Equip via /accessories" line off, adds 6 ARMOR (`faultlineitems:suit_armor`, `diving.suit.armor`;
  an iron chestplate's) + the chest equippable; `wearsSuit` = the chestplate only; `convert()` rebuilds old suits (called by
  `AccessoryStacks.refresh`/`fix` before the accessory check, and for the worn chestplate); `unslot()` (30 ticks after join)
  takes a suit out of the accessory slots (closing an open Wardrobe FIRST, or its close would save it back = a dupe) and puts it
  on, or in the inventory. Nerfed: 2 plates (`suit.plates`, was 3), 10% each (`plate-absorb`, was 15%), regrow 30 s (was 20).
  Index: the suit moved to the Armor chapter. Tested (`EnchantTest`, `BatchTest`, `NerfTest`, `WardrobeTest`).
- **THE MERMAID DEN (Deep 1.7.0, Bosses 1.6.9, Items 1.2.54 = hashes + eggs, Index 1.6.41)**: the Dark Ocean's border is
  2000 now (`border-radius`; an old 1050 reads as 2000). `Deep/MermaidDen.java` (pure functions, like Sulphur/Town): a reef
  crater round `MX, MZ` = 1200, 700 (`R` 150 lagoon, `RIM` 172 wall, `BLEND` 55 cliff; lagoon floor `FLOOR` = SEA-42 = 258,
  wall crest SEA-10), blended in `DeepGenerator.upperFloor` (biome WARM_OCEAN); four gates on the axes; avenues + two ring
  roads with jelly lamps; the PALACE (plaza at `PLAZA`, mosaic, tiered seats, 16 columns + a floating crown ring, the throne
  `throne()` under a scallop shell, a great pearl); 32 houses (`HOUSES`: conch spirals, pearl domes, coral towers, 4
  palettes, chests = Dark Loot), 18 coral trees, giant clams, kelp belt. No chimneys/stacks/ruins/caves/arches near it.
  Previews: scratch `dtest` `DenPreview` (den_map/palace/houses/gate). Already-generated chunks there don't change (it was
  outside the old border, so it's normally fresh). `Deep/Den.java` (`den:` config, `den.yml`): 10 MERMAIDS (one per
  `home(i)` house) + 6 GUARDS (gates + throne; tridents) = DeepMobs kinds `mermaid`/`merguard` (NEUTRAL, weight 0, models in
  `deep_assets.py` `DEN_MOBS`), spawned while a player is within 90 of their home (DeepMobs' `farAway` still drops them past
  100). Hurting one (`DeepMobs.onHurt` -> `Den.hurt`) angers every resident within 48. A PLAYER-killed mermaid stays dead
  (`dead[]`) until `restock-minutes` 30; all ten dead = `rise()` = console `coralia summon <world> <throne>`. An OMEN IV
  (BAD_OMEN amplifier exactly 3) inside the Den = WAR (`startWar`: omen removed, boss bar, every resident + 2 waves of
  guards (`raid.wave-1` 6, `wave-2` 8 when <= 2 left) attack the raiders; all dead = Coralia); any other omen level = a
  message only. `/deep den [info|tp|raid|stop|reset|rise]`. The normal spawner puts nothing aggressive in the Den.
  **CORALIA REWORK** (`DeepBosses.Coralia`, "Coralia, Queen of the Den"): only the Den calls her (`whyNot` always refuses:
  the Siren's Comb no longer works and no longer drops; no deep spawns). 2400 hp (old config 900 reads as 2400). ONLY SWORD
  MELEE hurts her (`Boss.swordOnly()`, `Wild.swordHit`: player damager, ENTITY_ATTACK/SWEEP, `*_SWORD` in hand). The
  Scourge's giant bubbles moved into `Swimmer` (`giantBubbles`, `giantBubble`, `pinks`, `pinkHit`, `clearBubbles`, hooks
  `breathed`/`breathLine`/`bubbleHint`; tag `Swimmer.PINK_TAG` = the old `faultline_scourge_pink`; `Wild.onHurt` finds the
  owning boss): Coralia gets air bubbles every 15 s and pink ones every 20 s (120 damage). New rig (`wild_assets.py`
  `coralia()`): tail, `coralia_fluke` (own part, turned 40 degrees at joint (-1.75,-2.8,0)), torso, head (coral + pearl
  crown), `coralia_hair` (from the head joint (-0.22,0.72,0)), 2 arms, `coralia_trident` (in the right hand (0,-1.3,0),
  shaft +y, `tilt` flips it 180 = a twirl); phase 3 swaps all 8 to `*_wrath`. Pose = eased targets per move (`want()`),
  a tail->fluke wave, barrel rolls (`barrel`). Moves (gaps 24/18/12 ticks): Tidal Dash, Trident Volley, Pearl Rain, Gaze,
  Coral Spires, Royal Guard; phase 2 Whirlpool Waltz, Siren's Aria, Riptide Spiral; phase 3 Tempest, Eel Lash. Music
  `faultline:coralia.music` (Cryogen theme, 285.86 s) in FaultlineDeepMusic.zip (`sounds/coralia/music.ogg`) + the Bedrock
  FaultlineDeep.mcpack (`deep_assets.py --music` reads coralia.ogg). Preview: `tools/wild_rig_preview.py <out> coralia`.
  Tested: `DeepTest` (68 kinds, the Den: residents, alarm, kills, restock, Omen III vs IV, two waves, fall),
  `DeepBossTest` (sword only, pink + air bubbles, every new move, wrath swap), `WildTest`, `MusicTest`, `StuckTest`, `OceanTest`.
- **Maces are back (Items 1.2.55)**: `mace.mode` default `"off"` (quoted: bare `off` is YAML false; `MaceRules.mode()`
  treats anything but remove/restrict as off): crafted and used freely. A server config still holding 1.2.15's `remove`
  reads as off unless `mace.really-remove: true`. `Items/MaceEnchants.java` (`mace-enchants:`, `allowed: {unbreaking: 3,
  wind_burst: 2, mending: 1}`): a MACE keeps only those (others stripped, levels capped): enchanting table
  (`EnchantItemEvent`: forbidden dropped, capped; nothing left = Unbreaking at the offered level), anvil
  (`PrepareAnvilEvent`: result capped, no result when nothing is gained), and every player's inventory + cursor every 2 s,
  on pickup and when held. Enchanted books of Wind Burst are capped to II too (no Wind Burst III anywhere).
  `faultline.mace.bypass` skips it. 1.2.56: allowed enchants are matched BY NAME (`id()`), never by Enchantment object (on
  the live server Wind Burst II was stripped); every change is logged ("[Maces] mace enchant ..."). Tested (`MaceTest`).
- **Bug pass (Deep 1.7.1, Index 1.6.42)**: the Watch no longer judges speed in or next to water (`Watch.wet`: in water,
  swimming, water / bubble column / waterlogged at the feet, below or above): the Abyssal Diving Suit's Dolphin's Grace
  (+ Depth Strider, currents, the Undertow) was logged as "speed". Den homes: `Den.waterAt` searches rings out to 8 blocks
  for open water (seagrass/kelp count, `Den.swim`) and each home's spot is cached (`homeSpot`); all 16 homes checked on
  generated terrain (scratch `dtest` `DenHomes`). `/deep den where` lists every mermaid/guard: out (where, angry), dead,
  or at home waiting for someone within 90 blocks.
- **Deep 1.7.2 (creatures look straight)**: every Dark Ocean creature was tilted. (1) Its displays were spawned at the spawn
  location, so they kept its yaw/pitch (an egg / `/deep spawn` = your look direction) and drew the pose on top of it: now
  spawned with no rotation, and `pose` resets any display rotation to 0 (the frame is the whole rotation). (2) The pitch came
  from any vertical drift (idle + sinking = nose 35 degrees down): now 0 unless swimming forward (0.06+ blocks a tick), x0.6,
  max 20. Tested (`DeepTest`).
- **THE TRENCH batch (Deep 1.8.0, Bosses 1.7.0, Items 1.2.57, Index 1.6.43, Ships 1.6.9 = WildParts)**:
  **The Trench** (`Deep/Trench.java`, pure functions like Sulphur/MermaidDen): a rift from the sea floor down to the bedrock
  (floor `BOTTOM` -61) along a Catmull-Rom line through `CTRL` (-1280,-100) .. (-1600,-1220) to the MAW at `LX, LZ` = -1460,
  -1640 (`LAIR_R` 44); half-width up to `MAX_HALF` 34, solid walls `WALL` 10 thick (the crust's caves never open into it),
  bone ribs across it every 140, vents, bone spikes, hadal coral, the Maw's tusks. `Trench.near/inside` (64-block grid)
  keep chimneys / stacks / spires / arches / whale falls out; `Zone.TRENCH` (always pitch dark in `Ocean.darkZone`).
  `Trench.bd` falls back to the plain block for a state MockBukkit (or a version) doesn't know. It's beyond the old 1050
  border, so its chunks are fresh. Previews `tools/previews/trench_*.png`. HADAL PRESSURE (`trench.pressure.*`): below y -10
  in the Trench 3 damage every 2 s (Conduit Power doesn't help), halved by the Abyssal Diving Suit, none with the suit + a
  Hadal Dive Helmet (tier >= `helmet-tier` 3, read from `faultlineitems:oxygen_helmet` on the helmet), none in a Submersible
  or in a Boss Rush. 7 creatures (`DeepMobs`, `TRENCH` set): Hadal Snail, Angler King (its lure pulls you in; lights the dark),
  Bone Crawler, Pale Stalker (ambush), Crush Jelly (pressure pulse), Tube Worm (rooted, AI off: sting + scald), plus the
  GREAT WHALE (SUNLIT, weight 0, only the Migration spawns it). Trench Dark Loot Bag 2.5%. `/deep trench [maw] [player]`.
  **The Abyssal Titan** (`DeepBosses.Titan`, kind `titan`, 5000 hp +25%/fighter, music disc 5): a colossal torso in the
  Maw (rig `titan_*` in `wild_assets.py titan()`, appended LAST to ALL_PARTS; hide drawn at `hide-light` 3, eyes and three
  lures always lit). Phase 1 Claw Sweep, Crushing Slam, The Lure, Abyssal Roar, Hadal Spawn; phase 2 (65%) Tentacle
  Eruption, Pressure Wave, The Gaze, and its LURES are hitboxes (`Boss.hitboxHit`, new hook from `Wild.onHurt`): each breaks
  at `lure-break-share` 3% of max hp; all three = stunned `stun-ticks` 160 taking x1.5; phase 3 (30%) Devour, Abyssal
  Collapse. Woken ONLY by FaultlineDeep (`Ocean.maw` → `FaultlineBosses.wakeTitan(Location, Player)` by reflection) for
  someone within `trench.titan.wake-radius` 30 of the Maw who has beaten all six sea bosses (`trench.titan.needs`,
  `FaultlineIndex.defeated`); `/titan summon` for admins. `Wild.whyNot` = within `maw-radius` of `maw-x/z`. Boss form
  hooks, Index `abyssal_titan`, /itemsmenu summon, achievement Titan Slayer (stat `titans`).
  **Deep Boss Rush** (`BossRush.Run.deep`): the Sigil used in the Dark Ocean, or `/bossrush start deep [hardcore]`:
  `DEEP_STAGES` (config `boss-rush.deep-bosses`) inkjaw, grandmaw, lament, umbral, goblin, scourge, coralia, titan. Each
  stage's spot comes from `FaultlineDeep.rushSpot(kind)` (Grandmaw on the open floor, Scourge in the Sulphur Sea, Coralia's
  throne, the Titan's Maw...; `/bossrush setarena` in the deep world saves `deep_<kind>`), the goblin (Grubnash) is an
  external stage (`RAID_TAGS`, spawned through `FaultlineDeep.rushSpawn`); Water Breathing between stages. Own boards:
  `/bossrush top deep [hardcore]`, Index stat `deep_boss_rush`, achievement Deep Rush Champion.
  **Den reputation + trading** (`Deep/DenRep.java`, `den.rep.*`, `den_rep.yml`): HATED (<= -40: guards attack on sight) /
  STRANGER / FRIEND 40 (mermaid trades, 9) / ALLY 120 (guard trades, 6, + the guards fight for you) / HONORED 250 (+ a
  Conduit trade; claim a house: `/den home` (cooldown 300 s), respawning in the Dark Ocean puts you there). Earned by kills
  within `defend-radius` 120 of the Den (+1, +2 inside; 60/day) and gifts right-clicked onto a mermaid (prismarine crystals
  / sea pickles 1 per 4, nautilus shell 3, heart of the sea 25; 40/day); lost by hurting residents -10, killing a mermaid -50,
  a guard -20, an Omen IV war. Right-click a resident = their trades (merchant). Index stat `den_<tier>`, achievement Honored
  by the Den (`den_honored`). `/den`.
  **Dive helmets** (Items `DiveHelmets.java`, `dive-helmets:`): Copper (120 s, 2 armor), Reinforced (300 s), Hadal (600 s,
  +2 toughness), PAPER head-equippable, model `faultline:dive_helmet_<n>` (worn 3D model from `wild_assets.py worn_items`),
  PDC `faultlineitems:oxygen_helmet` = tier; crafted in a chain (copper blocks + glass + nautilus; + iron blocks + crystals;
  + echo shards, hearts of the sea, netherite). Underwater the tank drains (player PDC `oxygen`) and your air stays full;
  breathing refills it in 10 s, bubble columns / Conduit Power fast; action bar meter. Index (Armor), /itemsmenu.
  **The Submersible** (`Deep/Submersible.java`, `submersible:`, item PDC `faultlinedeep:submersible`, recipe IGI/CHC/III):
  a small persistent ArmorStand (tag `faultline_submersible`) carrying ItemDisplays `deep/submersible` + `deep/submersible__prop`
  and a Bedrock-only stand; place on water, right-click to board, W/A/S/D where you look, Jump up, Sneak down, Sprint
  faster (`getCurrentInput`), per-axis collision, teleported with RETAIN_PASSENGERS; a waterlogged LIGHT headlight; inside
  you breathe, the dark and pressure can't reach you, bites hit the hull. Below `safe-y` -40 the hull cracks
  (`crack-per-second`) unless HADAL PLATED (4 Netherite Ingots on it); Iron Ingots patch it; 0 = it implodes. Sneak + punch
  picks it up. Index entry. **The Leviathan Migration** (`Deep/Migration.java`, `migration:`): every 60-120 min a pod of 3-5
  Great Whales (AI off, pushed along a heading) crosses the Sunlit Sea `distance` 600; at the end the eldest sinks into a
  WHALE FALL (bone ribs + a loot chest, `heart-chance`); killing a whale = `umbral-chance` 35% the Umbral Leviathan comes up.
  `/deep migration [start|stop]`. **The Bioluminescent Bloom** (`Deep/Bloom.java`, `bloom:`): every 10 min 3% with someone
  deep: 5 minutes with no darkness anywhere, glowing particles, glowing creatures out; Index stat `blooms`, achievement The
  Bloom. `/deep bloom [start|stop]`. Index: 359 entries, 34 achievements (+ Hadal Zone). Tested: `DeepTest` (ftest: 75
  kinds, Trench zone + pressure, Maw wake, rush spots, rep tiers/trades/home, sub ride/crack/plating, migration + whale
  fall, bloom), `TitanTest` (bosses harness: phases, lures, stun, the Deep Rush), `DeepBossTest`, `WildTest`, `StuckTest`,
  `MusicTest`, `HardcoreRushTest`, `DiveTest` (items).
- **The Blood Moon loading screen (Items 1.2.58, LimboBlackMarket 1.0.4)**: FaultlineEvents (source NOT in this repo) pushed
  `FaultlineBloodMoon.zip` (red moon textures) to everyone when a Blood Moon started; ANY resource pack pushed mid-game makes
  the client reload every pack (FaultlineSMP.zip ~24 MB) = the Mojang loading screen, long enough to die. `Items/BloodMoonSky.java`
  (`blood-moon-sky:`) draws the red moon itself while `faultlineevents:active_event` = BLOOD_MOON on the main world: a private
  billboard ItemDisplay per Java player (model `faultline:blood_moon` = the blood moon pack's full_moon.png as a generated
  item, in FaultlineSMP.zip), placed `distance` 60 out along the real moon's direction (`moonDir`: the game's celestial angle
  formula from the player's time, sun rising +x) and sized like the vanilla moon (40 wide at 100) x `size` 1.08; hidden below
  the horizon. The owner must turn FaultlineEvents' moon pack off in ITS config. `onPack` warns the console + ops once when a
  pack that isn't ours (`OURS` = Items main/music, Bosses) is accepted more than 30 s after join. (Since 1.2.59 the display
  moon is only the fallback when `event-packs.blood-moon.enabled: false`.) **/tier remove <name|uuid>** (`TierManager.findAssigned`: matched against the tier
  list itself by last known name or UUID, so banned / never-cached players can be removed; tab-completes the list).
  Tested (`BloodMoonTest`).
- **Event sky packs + the pack shield (Items 1.2.59)**: the owner WANTS real packs for the sky events, so `Items/EventPacks.java`
  (`event-packs:`) adds a small pack on top of FaultlineSMP.zip (own UUID per event, `EVENT_IDS`, so it never touches the main
  pack) to every Java player in the event's world while it's on, and removes it when it ends: `blood-moon` (FaultlineEvents'
  BLOOD_MOON, `FaultlineBloodMoon.zip`), `eclipse` (Weather's Eclipse, `FaultlineEclipse.zip`: every moon phase = a black sun in a
  burning corona, since the Eclipse sets the player's sky to night) and `aurora` (`FaultlineAurora.zip`: clouds.png = green /
  teal / violet curtains). The last two come from `python3 tools/event_packs.py <repo> --preview tools/previews`; hashes in
  `event-packs.<id>.hash`, also checked by PackHash. A player gets one only after their main pack loaded (`ready`, or 90 s in),
  one load at a time; latecomers / arrivals get it then. With a pack on, the display moon (BloodMoonSky) and the Eclipse's
  display black sun are skipped. FaultlineEvents' own moon pack must stay OFF (two loads). THE PACK SHIELD (`pack-shield:`):
  for ANY pack (the join packs too), from the push / ACCEPTED status until SUCCESSFULLY_LOADED (or a failure) + `grace-seconds`
  3, capped at `max-seconds` 120 (a removal sends no answer: `removal-seconds` 25): no `Enemy` spawns within `radius` 96 of that
  player for natural reasons (`BLOCKED`: NATURAL, SPAWNER, PATROL, REINFORCEMENTS, TRIAL_SPAWNER...; eggs / plugins untouched),
  mobs can't target them, they take no damage. The clock is server ticks (`now`), so lag stretches it. Tested
  (`PackShieldTest`, `BloodMoonTest`, `WildItemsTest`). Hadal fishing shipped OFF here; it's ON since Items 1.2.62.
- **THE OUTER RING batch (Deep 1.9.0, Bosses 1.8.0, Index 1.7.0, Items 1.2.60, Cosmetics 1.1.5, FaultlineSkills 1.0.0)**:
  **Border 3000** (`border-radius`; an old 1050 / 2000 reads as 3000). Four regions past the old border, pure functions like
  Sulphur/MermaidDen (`Regions.java`: `weight/near/nearAny/at/title`; chimneys, stacks, spires, arches, whale falls, Abyss
  pockets keep out): **the Frozen Shelf** (`FrozenShelf.java`, 0, -2600, R 300: an ice cap over the sea with keels, icicles,
  breathing holes, bergs, a lair of blue-ice pillars, a frozen longship), **the Drowned City** (`DrownedCity.java`, 2600, -300,
  R 200: walls + towers + gates, 408 buildings in 6 palettes on a 28-block grid, plaza fountain, cathedral, bank (vault), the
  PALACE (throne `THRONE`, treasury), 10 vault chests `VAULTS`), **the Glass Reef** (`GlassReef.java`, 1700, 2600: rainbow glass
  trees, crystal spires, sealed air terrariums, arches, lit seams, the dry Prism Spire with a viewing room), **the Bone Fields**
  (`BoneFields.java`, -2600, 900: 9 leviathan skeletons (vertebrae, ribs, skulls with dripstone teeth) on dark silt, fossil
  clusters). Biomes FROZEN_OCEAN / DEEP_COLD_OCEAN / WARM_OCEAN / COLD_OCEAN. Previews: scratch `dtest` `RegionPreview`.
  **18 creatures** (`DeepMobs`, `REGION` map limits each to its region; models in `deep_assets.py` `OUTER_MOBS`): Marrow Eel,
  Scavenger Crab, Ossuary Ray (bones), Glass Jelly, Prism Fish, Crystal Crab, Shardback (glass), Narwhal, Ice Seal, Frost Orca,
  Rime Jelly (ice), Magma Eel + Cinder Crab (volcano only, weight 0), and the Drowned City's people `PLAIN` (visible vanilla
  drowned, no model: Citizen, Guard, Priest). Abilities/drops/mini bosses in `OuterMobs.java`. MINI BOSSES: **the Rimeback**
  (1200 hp, Frost Breath / Ice Ram / Glacier Fall / Pod / Freeze Over; rises for someone under the ice within 50 of the Shelf's
  middle) and **the Drowned King** (900 hp, Trident Volley / Tidal Slam / Royal Guard / Decree / Whirl; rises within 30 of his
  throne); both have boss bars, rest `outer.<king|rimeback>.cooldown-minutes` 30 (`Outer.java`). MUTATED creatures
  (`mobs.mutated-chance` 3%): x2 health, x1.5 damage, glowing, double drops/XP, Index stat `mutated_sea`.
  **Outer.java**: region titles; DROWNED VAULT KEYS (PAPER, model `faultline:drowned_vault_key`, PDC `faultlinedeep:vault_key`;
  guards 8%, priests 5%, the King 3) open a vault once (LOWEST interact; marks `faultlineitems:dark_loot_done` so Dark Loot
  never rolls it; shut vaults can't be broken or blown up); ANCIENT FOSSILS (PAPER, `faultline:ancient_fossil`, PDC
  `faultlinedeep:fossil`) from the Bone Fields' own bone (`outer.fossil.mine-chance` 4%; placed bone is in the chunk PDC
  `faultlinedeep:placed_bone`) and its creatures; a mermaid takes one as a gift (`den.rep.fossil` 15 + a sea treasure back).
  **Tides.java** (`tides:`): the ABYSSAL ECLIPSE (5 min, `Ocean.darkZone` true everywhere, Abyss creatures rise in the Sunlit
  Sea), the RED TIDE (10 min, Sunlit water poisons unless the suit / Water Breathing + Fire Resistance, every creature within 16
  turns on you), FEEDING FRENZIES (something of 150+ hp dying: 50% 4-6 sharks, mad, Speed II, 60 s; `DeepMobs.onDeath` →
  `bigDeath`). **Volcano.java** (`volcano:`, `volcano.yml`): rises from the open sea floor 180-350 from someone in the Sunlit
  Sea (never near regions/Den/Sulphur/Trench/Bubble), grows layer by layer, erupts 60 min (plume, lava bombs = falling magma that
  bursts, nothing left behind; boiling water near the cone; Magma Eels / Cinder Crabs), cools to obsidian with 6-10 Ancient
  Debris in the plugged vent and 3 Dark Loot chests on the rim; after `linger-hours` 6 every changed block is put back where
  it's still volcano rock (chests and players' blocks stay); crash = cools on load, heals when due. **Habitat.java**
  (`habitat:`, `domes.yml`): AIR CORAL (PAPER `faultline:air_coral`; a BUBBLE CORAL BLOCK that refills air within 3.5; chunk PDC
  `faultlinedeep:air_corals`) and DOME KITS (Small r6 / Large r10: glass hemisphere, sea-lantern ring, glass floor where open,
  water inside → air; old states saved; shell + floor unbreakable/blast-proof/piston-proof; owner sneak + empty-hand right-click
  dismantles; `Market.dry` counts domes (`Habitat.inDome`); 3 per player). Tested: `DeepTest` (93 kinds), `OuterTest` (scratch
  `ftest`: regions, King, vaults, fossils, eclipse, red tide, frenzy, volcano grow/erupt/cool/heal, dome + no leaks, coral).
  **Night of Terrors** (Bosses `Terrors.java`, `terrors:`, `terrors.yml`): every 10-31 night (America/New_York, once a year) a
  random boss from `terrors.bosses` comes for a survival player every 120 s while none is out (`rushSummon`, normal loot), max 8;
  main world PDC `faultlinebosses:terrors` = FaultlineCosmetics' Candy x5 (`Seasons.onKill`, Halloween's token even out of
  season); dawn ends it; `/terrors [start|stop|status]`. **Boss weaknesses** (Index `Weakness.java`, `weakness.yml`): each boss
  entry's `weakness:` shows only after that boss killed you (killer's tag = `detect-tag`, its name, `ALIASES`; Bosses'
  `DeathMessages` also sends `/index died <player> <boss name>`); Index stat `weaknesses`. Index: 381 entries, 53 achievements
  (outer ring, events, mutants, domes, weaknesses, skills). **FaultlineSkills** (new plugin): see the table; XP: ores 2-20,
  stone 0.2, blocks 1 (paid after `building.settle-seconds` 60 if still there, `max-per-hour` 2500), redstone components 3 +
  crafting 2 (64/day), ripe crops 2, melons/pumpkins 3, breeding 5 (60/hour); nothing in creative; `faultlineskills:placed`
  chunk marks (moved by pistons). Perks: attribute modifiers `faultlineskills:reach` / `quick_builder` (removed on quit/
  disable); `FaultlineSkills.skillLevel(uuid, tree)` for other plugins. Index stat `skill_<tree>` +1 a level. Tested: `SkillTest`
  (scratch `sktest`), `TerrorsTest` (bosses), `WeakTest` (index), `SeasonTest` (cosmetics). Pack: new models/icons from
  `deep_assets.py` (`OUTER_ICONS`, `OUTER_HEADS`), FaultlineSMP.zip 24.25 MB.
- **Bug pass (Items 1.2.61, Deep 1.9.1, Skills 1.0.1)**: a sweep of every plugin (direct `setVelocity`, class spawns of
  slimes, entity scans on timers, async Bukkit calls, menus without drag guards, unguarded config parsing, loot handlers that
  ignore cancelled events, crash leftovers). Fixed: the Magic Mirror menu cancelled clicks but not DRAGS (a dragged stack fell
  into the menu and vanished); the Mirror's anvil repair gave a whole stack back for one mirror (`repaired.setAmount(1)`);
  FaultlineSkills paid Mining XP for blocks a cobblestone / stone / basalt GENERATOR made (`onForm`: BlockFormEvent marks
  them placed); the VOLCANO overwrote anything in its way (an underwater base, a dome, a chest's contents): it only grows
  through sea water and the sea floor's own rock (`Volcano.natural`), never inside a dome, and never rises within 40 blocks
  of one (`goodSpot`); a DOME KIT's shell overwrote other players' blocks (chests lost their items): a block entity anywhere in
  the dome refuses it, and any non-water block in the shell is kept as part of the wall. Checked and fine: LimboBlackMarket
  (stats, quests, anti-farm, placed-ore marks), Raids menus/omens/waves, Ships' hold vs scrap, Cosmetics' shop, every
  plugin's GUI drag guards. Tested (`SkillTest`: generator stone, `OuterTest`).
- **The detail pass (Deep 1.10.0)**: FIX: the Drowned City palace's corner towers and front colonnade overhung the raised
  platform and floated 3 blocks over the street (the owner's screenshot); the platform now runs flat to `hd + 5` (colonnade on
  it, a portico roof, steps beyond), the towers stand on a footing from the ground with floors, slits and a parapet, and the
  cathedral's bell tower has a floor over the nave. NEW `Deep/Details.java` (drawn after the regions, only into water/soft
  plants: `put`/`soft`): THE SUNLIT SEA (cell 120, 42%, `plain()` = away from regions, the Den, Sulphur, Trench, Bubble, blue
  holes and ruins): anchor + chain, capsized boat + spilled cargo, fallen lighthouse (lamp lit), giant nautilus shell, stone
  head buried to the chin, cargo field; kelp is cleared round each (`clearing`). THE ABYSS (cell 110, 50%, not on mounds):
  glowcap groves (mushroom stems, warped wart caps spotted with shroomlight = light in the dark), obelisks in standing-stone
  rings, cages hanging from the ceiling, sculk gardens (catalyst + sensors, no shriekers). THE TRENCH: descent rigs every 260
  blocks along it (derrick, winch, boom, chain into the rift, cage platform + soul lantern). SULPHUR: toxic copper drum dumps.
  THE DEN: four statues of past queens (radius PALACE_R + 22) and 8 soul-sand bubble fountains to the surface. Regions:
  FROZEN SHELF igloos, ice spikes, fishing camps at breathing holes, frozen beasts in ice on the floor (`FrozenShelf.details`);
  DROWNED CITY chimneys, townhouse balconies, beds/candles/rugs/skulls, shop counters, street carts, crossing wells and broken
  statues, fallen masonry (`DrownedCity.streets`); GLASS REEF floating glass jellyfish, geodes cracked open in the floor, glass
  anemones, more buds/shards; BONE FIELDS whaler wrecks (ribs, harpoon gun, try-pots), bone shrines, tar seeps, giant teeth;
  open sea: sponges, mossy boulders. Already-generated chunks don't change (new chunks only: delete region files to redo an
  area). Previews: scratch `dtest` `DetailPreview`, `IglooPreview`, `RegionPreview`. Tested (`DeepTest`, `OuterTest`).
- **Hadal fishing is on (Items 1.2.62, Index 1.7.1)**: `Fishing.hadal(zone)` (zone from `FaultlineDeep.fishZone`: SUNLIT /
  ABYSS / SULPHUR / DEN / TRENCH / BUBBLE): `fishing.hadal.chance` 0.7 of catches (always over the Trench) are one of 14 species
  (`Fishing.SPECIES`, COD / SALMON / TROPICAL_FISH + item model `faultline:fish/<id>` + PDC `faultlineitems:fish_species`); the
  Blind King has its own roll `fishing.hadal.blind-king-chance` 0.005 (by weight it was 1 Trench catch in 27). Code default
  `enabled` is true now. Icons from `tools/deep_assets.py` (`FISH`, `fish_icon`; Index glyphs `fish_<id>`, Bedrock mappings on
  the base fish). Index: new chapter `fish` "Dark Ocean Fish" (14 entries, ids `fish_<id>`, detect `fish_species=<id>`; the cover
  drops its blank line at 9 chapters, a page holds 14 lines), achievements Hadal Angler (stat `hadal_fish` 25), Every Fish in
  the Sea (all 13 but the King), The Blind King (stat `blind_king`). Tested (`HadalTest` in itest, `AchievementTest`).
- **Lag hunt (Deep 1.10.1)**: `DeepMobs.tick` iterated `live` directly while a creature's turn could add or remove one
  (the Rimeback's Pod / the Drowned King's Royal Guard summons, crabling swarms, blastfins, the Den): a
  ConcurrentModificationException that skipped the rest of that tick (every creature froze) and printed a stack trace each
  time. Now it loops over a copy (skipping any removed meanwhile). Load-tested every plugin in MockBukkit with 12 moving
  players (scratch `ProfX` in each harness; Deep: `ftest` `ProfDeep`, 8 players / 60 creatures): all under ~1.5 ms/tick.
  Dark Ocean chunk generation 6-18 ms/chunk (scratch `dtest` `GenTime`). For real lag, ask for a spark profile first.
- **The Titan detail pass (Bosses 1.8.1, Items 1.2.63 = pack hash, Ships 1.6.10 = WildParts)**: the Abyssal Titan's model
  (`tools/wild_assets.py` `_titan_body`, `_titan_head`, `titan()`; joints `TI_*` unchanged) has overlapping chest plates
  round a glowing seam, a rift with a red heart, ribs with glowing flesh between them, 3-layer spiked pauldrons, gills,
  barnacles, dead coral, broken chains, back spines, 8 root tendrils into the floor; the head a brow ridge, snout and crest
  plates, nostrils, uneven fangs, two pairs of horns, glowing cheek frills; the jaw a tongue and tusks; arms banded with a
  shackle and elbow spike; claws with FOUR talons and a glowing palm; lures ringed with feelers. Preview:
  `python3 tools/wild_rig_preview.py tools/previews titan` (mirrors `Titan.pose`). FIXES: one big hit from phase 1 to 3
  (`startPhase` jumps) never called `onPhase(2)`, so the lures never grew and the stun was impossible: `onPhase(3)` grows
  them when they never did. Lure hitboxes (size 3 slimes now, matching the bulb) sit on the swaying bulb (`lureAt`,
  `BULB_X/Y`), not ~1 block under it; the Lure move's sparks come from the bulbs in phase 1 too. Tested (`TitanTest`).
- **Domes v2 (Deep 1.11.0, Index 1.7.2)**: Dome Kits (`Habitat.java`) work under water in ANY world (Overworld oceans and
  lakes too; the Dark Ocean's no-go spots still apply there). Each dome gets prismarine ribs, a lantern crown and TWO DARK OAK
  DOORS framed in dark prismarine (one on the side you placed it from, one opposite; water never flows into a door). Pieces:
  `Piece` (id, world, owner, `before`) = `Dome` (`hollow` = its air, `area` = air + shell + floor, `shellCell`) or `Hall`
  (`air` / `skin` cell sets); shell map per world (`shell`, rebuilt by `rebuildShell`). The OWNER may break their own walls
  (no drops); `onFlow` cancels any flow from outside a dome/hallway into one, so a hole is a dry window (water poured inside
  still flows inside). COMBINING: a new dome within `habitat.domes.combine-gap` 12 (glass to glass, floors within 6) of
  another of yours asks "Are you sure you want to combine this dome?" 1.5 s later: a Paper DIALOG (Java 1.21.6+, confirmation
  with Yes/No, `ClickCallback` once / 2 min), a title, chat [YES]/[NO] (`/dome yes|no`) and a Bedrock form (`BedrockForms`,
  copied into Deep). Yes = `combine()`: a hallway 3 wide x 3 tall inside from centre to centre (floor eased between the two
  floors), cut through both walls (a door it cuts loses both halves), glass on every face that touches water (prismarine floor,
  a lantern every 4 in the roof), refused through chests / another dome / another hallway / unloaded ground. `/dome
  [list|combine|yes|no]` (combine = ask again from inside a dome). Dismantling a dome restores its hallways first (the other
  dome's wall/door comes back), saved in `domes.yml` (`world`, `halls.<id>.a/b/air/skin/before`; old domes = the Dark Ocean).
  `Market.dry`/`inDome` count hallways (Dark Ocean only); the volcano only avoids Dark Ocean domes. Tested (`DomeTest` in
  scratch `ftest`: Overworld lake, doors, owner/other breaking, flow, no/yes, walkable hallway, no leaks, save/load, the Dark
  Ocean, 3-dome limit, packing away; `OuterTest`).
- **The Scourge swims like the Dune Devourer (Bosses 1.8.2)**: `Scourge.swimTo` (override; `wild.scourge.dune-swim: false`
  = the old turn-on-the-spot swim) moves with MOMENTUM: `vel` is turned toward the way it wants to go by at most
  `turn * turn-mult` (0.8) degrees a tick (`bend`: a goal behind it = a wide loop, never stopping and backing up; speed eases
  to the move's speed, a bit slower in hard turns) and carries straight on near its goal. The way still comes from the
  shared `Swimmer.heading()` (planned A* path / straight / round rock) and `progress()` (stuck → plan, unstick), split out of
  `Swimmer.swimTo` for this; blocked = slides along the wall, boxed in = the old `swimTo` takes that tick. Between moves
  (`idleDune`) it makes PASSES: to a spot `pass-overshoot` 12 beyond its prey (beside + under, sides swap), speed
  `cruise * spd * pass-speed` 1.1, then loops round. Every `leap-every-seconds` 9 (+-20%), if the surface over its prey is
  open sea within `leap-max-depth` 40, it LEAPS: up through the surface (`passable` = no rock), then `airborne`: gravity
  `dune-gravity` 0.055 arcs it back down (`fly`), a splash, and anyone it bursts through takes `moves.leap` 8 and is thrown.
  Just splashed down (no room for `clear`) it moves `loose` until it's deep again. Spray + bubbles wherever a body point
  crosses the surface (`surfaceSpray`, the Devourer's sand spray). Rubble charges and the breach hand their speed to `vel`
  (it comes out of a lunge still moving). Yaw eases 20/tick and holds when moving straight up/down. Tested (`DeepBossTest`:
  leaps out + falls back in, never in rock, never stops, no snaps, still gets round a wall).
- **The worms stay in the ground (Bosses 1.8.3)**: the Dune Devourer / Frostmaw and the Sandworm King flew up to players
  who stood high (a pillar, a cliff, the air). Dune (`FaultlineBosses.Dune`): `groundY` is now the LOWEST of the column and 4
  spots 2 blocks round it (`columnY`), so a 1-wide pillar isn't ground; `physics()` counts "in the ground" only below that
  (no gravity inside a pillar was a free climb) and its too-deep lift uses it too and never stacks on an upward burst;
  `steer()` can't pull it UP while it's out in the air (steering at a high target cancelled gravity = hovering); Ground
  Slam and Tremor tunnel under you and LEAP out with gravity (`leapFromGround`, `slam-leap` 1.0, `tremor-leap` 0.85; they
  flew up 24 / 9 blocks and hung there); Breath rears up out of the ground (`breathSpot` = ground + 1.5/3, was your height +
  up to 7); the Sand Spit breach stops at `breach-max-height` 6 over the ground and loses its upward speed; the Ambush erupts
  from 2 under the surface at `ambush-burst` 1.0; the Sandstorm / Vultures prowl rings and the intro use the ground under you;
  a safety net turns gravity back on after `max-hover-ticks` 30 held more than 4 over the ground. Sandworm King: `groundAt`
  searches DOWN from the target (Wild.ground kept a floating target's own height) with the same 5-spot minimum, and
  `keepLow()` holds the head at most `head-max-above` 1.8 (rearing `rear-max-above` 3.4) x scale over the ground, except the
  Eruption arc (`erupt-height` 7, was 10) and the Devour burst (`devour-height` 4.5, was 7). Tested (`WormAirTest` in the
  bosses harness: a player on a 20-tall pillar and 25 up in the air; before: up to 38 blocks up and hanging the whole time;
  now at most 12.6 and never over 4 up for more than ~2 s).
- **The bounty batch (LimboBlackMarket 1.1.0, Bosses 1.9.0, Deep 1.12.0, Index 1.7.3, Items 1.2.64 = pack hash)**:
  **Head bounties** (`LimboBlackMarket/Bounties.java`, `bounty:`, `bounties.yml`): `/bounty place <player> [n]` takes the player
  heads in your main hand (only real ones: tagged `limboblackmarket:head_tier` by HeadTagger, `tagged-heads-only`) and holds
  them; whoever kills that player gets every head on them (MONITOR PlayerDeathEvent). No payout (the bounty stays) for a
  kill `AntiFarmManager.isFarmedKill` flags, the same IP (`same-ip-claims`; loopback/LAN skipped = Geyser) or the same
  kingdom (`same-kingdom-claims`); the killer's own stake just comes back. A full inventory = `pending`, `/bounty claim`.
  `cancel-lock-minutes` 30 before you can take yours back; unclaimed after `expire-days` 7 they go back. Saved as whole
  ItemStacks. Index stat `bounties_claimed`, achievements Bounty Hunter / Headhunter. Tested (`BountyTest`, scratch `mtest`).
  **The Abyssal Kraken** (Bosses, `kraken.abyssal.*`): a Bait Worm dropped in the Dark Ocean (`abyssal.world`, any water
  `min-depth` 60 deep, no Deep Ocean biome needed) or `/kraken summon` there wakes the Kraken at `scale` 40 = TEN TIMES his
  size (head ~70 wide, arms ~220 long). In the `Kraken` class: `colossal`, `k` = scale / normal scale (1 for the normal
  Kraken, so nothing changed for him), `hs` = hit areas x2.2 (rings, ink, surge band, bubbles: you can still swim out),
  `ms` = movement x2.2, `far()` = notice/music/bar radii x2.5, `bodyR()` = beak/whirlpool contact x0.6k. Every move's body
  offsets are `* k`; his head hangs `station` 50 off and `station-height` 20 above his prey (the normal one stays put within
  10: the giant would have had you inside his head); the bite aims his beak; bubbles, pink bubbles, eels and Spawn of the
  Deep rise round a random fighter (`nearFighter`). 9,000 hp, `damage-multiplier` 1.5 on top (hitbox tag
  `faultline_kraken_abyssal`, read in `hurt`), rests x0.75, pink bubbles x3. New: swimming into an arm hurts (`armTouch`,
  segment distance < width x0.55, once a second), 12) ABYSSAL CLAP (two arms clap on a ring, 26 + slowness/nausea), 13) INK
  STORM (phase 2+: ink on every fighter at once, burns 3/s). Rewards x2 + 4 Dark Loot Bags + 50% a Dark Loot rare + 2
  netherite; Index `the_abyssal_kraken` (+ disc), achievement Bigger Fish. Big displays get a display width/height (culling)
  and view range 16; Bedrock proxy squid at scale 16. `seabedY` / `surfaceY` scan 400 (the Dark Ocean is ~250 deep: they
  found no floor and pushed him up). Boss form is always the normal one (`krakenNormalOnly`). Tested (`AbyssKrakenTest`).
  **The Abyssal Chart** (`Deep/AbyssMap.java`, `abyss-map:`, `abyss_map.yml`): a FILLED_MAP (PDC `faultlinedeep:abyss_map`;
  4 glow ink + 4 prismarine crystals round a map) of the whole Dark Ocean, 6144 across (128 px x 48 blocks), one shared
  MapView with a per-player renderer: fog everywhere you haven't swum (`reveal-cells` 2 round you every 2 s), else the floor's
  depth, the Sulphur Sea, the Den, the Trench/Maw, the Bubble, the 4 regions, ruins; markers for landmarks and regions once
  found, your domes and an erupting volcano always. Painted once off the main thread from the generator's pure functions.
  `/chart` = % uncovered; Index stat `chart_percent`, achievement Cartographer of the Deep (50%). `/deep give chart`.
  **Dome upgrades** (`Deep/DomeUpgrades.java`, `habitat.upgrades.<id>` = cost "MAT:n,MAT:n", `habitat.upgrades-settings.*`):
  `/dome upgrades` inside your dome (chest menu / Bedrock form), 20 per dome (`Dome.ups`, `tint`, saved in domes.yml) and a
  per-owner trust list (`trust.<owner>`, `/dome trust|untrust`): Airlock (doors shut after 2 s + 20 s Water Breathing),
  Keycard Doors, Locked Vault (containers inside), Oxygen Reserve (leaving = 60 s Water Breathing), Bubble Ring (breathe within
  6 outside), Hydroponics (+50%), Fish Trap (a barrel inside, a fish every 5 min), Thermal Vents (cook x2), Med Bay (Regen I),
  Floodlights (`lit()` in `Ocean.darkZone`, 12), Sonar (hostiles within 40 glow), Defense Pulse (4 dmg + push within 6),
  Mob Ward (`warded()`: CreatureSpawnEvent + the DeepMobs spawner, 24), Home Beacon (`/dome home [n]`, 300 s, not within 10 s
  of being hurt), Respawn Pod, Docking Bay (Submersible hull +2%/5 s within 12), Pressure Hull (`shielded()`: Pressure Surge,
  hadal pressure, acid water within 16), Heat Lamps (no freezing within 8), Intruder Alarm, Stained Shell (`/dome color`,
  `OURS` now holds every stained glass so dismantling still floods it). Index stat `dome_upgrades`, achievement Deep Architect.
  **The Glimmertide** (`Deep/Glimmertide.java`, `glimmertide:`, `glimmertide.yml`): the Dark Ocean's season, `windows`
  ("11-01..11-14", Feb/May/Aug too, `zone`), or `/deep season start|stop|auto`. GLIMMER PEARLS (PAPER, model
  `faultline:glimmer_pearl` from `deep_assets.py OUTER_ICONS`, PDC `faultlinedeep:glimmer_pearl`): creatures killed there
  `drop-chance` 15% 1-2, faultline_* 300+ hp bosses 8-12; GLIMMER SHOALS (every `shoal-seconds` 120, `shoal-chance` 35% per
  swimmer: 4-7 glowing pearl ItemDisplays + END_ROD particles for Bedrock, a minute; swim within 2 = a pearl); the Bloom x3
  (`bloom-multiplier`); a boss bar with days left. THE GLIMMER SHOP `/glimmer` (only in season; `glimmertide.shop` =
  "id;MATERIAL;price;Name;command|command", {player}; defaults `DEFAULT_SHOP`: Dark Loot Bags, Air Coral, Dome Kits, the
  Chart, a Submersible, sea treasure, a Mythic Bag, a Bait Worm, a Dark Loot gamble). `/glimmer top`. Pearls keep between
  seasons. Index stat `glimmer_pearls`, achievements Glimmer Collector / Tidecaller. Tested (`UpgradeTest` in scratch
  `ftest`: all 20 upgrades, the chart, the season, the shop, shoals).
- **THE LAND BOSSES ARE GONE (Bosses 2.0.0, Items 1.2.65, Index 1.8.0, Deep 1.13.0)**: the owner kept only Diamond Jacob on
  land and the Dark Ocean's (aquatic) bosses. DELETED: the Demon Eye, Frostbeard, the Dune Devourer / Frostmaw (+ vultures,
  the Dune Idol), Don Lorenzo, the overworld Kraken + the Kraken at Sea (the Kraken is only the Abyssal Kraken now: a Bait
  Worm only works in the Dark Ocean), Rocco Vendetta (`Vendetta.java`), Grimtusk (`Warlord.java`), the wild land bosses
  (`Leviathan/Sandworm/Lich/FrostWyrm/StoneGolem.java`; their summons/weapons out of `Wild.ITEMS`, recipes removed on
  enable), the Night of Terrors (`Terrors.java`) and the WHOLE Boss Rush incl. the Deep Boss Rush (`BossRush.java`, the
  Sigil, `/bossrush`, Deep's `rushSpot/rushSpawn`, the `faultline_boss_rush` checks). Their commands are gone from
  plugin.yml; `OLD_TAGS` sweeps their leftover entities on startup. Helpers the deep bosses used moved to `RigMath.java`.
  Kept: raid bosses, pirate bosses, Captain Hollow, the Queen Spider, Grubnash, the Rimeback, the Drowned King.
  The global boss damage multiplier is `bosses.damage-multiplier` (falls back to the old `demon-eye.damage-multiplier`).
  **Diamond Jacob is harder** (`JACOB_HARDER` in FaultlineBosses: a config value still equal to its OLD default reads as
  the new one): 6000 health (4 x 1500), 15% defense, x1.3 damage, walk 0.34 / phase-4 0.5, `rest-multiplier` 0.75, orbs
  80 damage every 26 s, spiders every 180 ticks (max 9), army 20 (reinforce below 6, 7 at a time). His rewards add the
  **Mirror..??** (`jacob.rewards.mirror`, every fighter) and the Ankh 50% (`rewards.ankh-piece-chance`); the Abyssal
  Kraken drops the Cross 50% (`kraken.abyssal.cross-chance`) and no longer the Mirror. Deep's Mirror needs only Jacob
  beaten now (`journey.needs.jacob`). Deep creatures that dropped the Abyssal Lure drop Dark Loot Bags / Inkjaw Bait.
  **OldGear** (Items `OldGear.java`, `old-gear.enabled`): the removed bosses' items (faultlinebosses `suspicious_eye`,
  `twin_eye_staff`, `demon_eye_shield`, `unholy_arrow`, eggs, `captains_armband`, `lorenzos_ball`, `talon`, `dune_idol`,
  `vendetta_item`, `warlord_item`, `rush_sigil`, `wild_item` other than the 6 deep summons), the Ancient Gear Part /
  Ancient Core, the Demon Eye / Dune / Don / Rocco / Explorer discs and every boss TROPHY (`faultlineindex:trophy`) are
  taken from players (join, every 30 s, any inventory opened, pickups, ender chest, cursor, inside bundles/shulkers),
  containers (once per chunk, chunk PDC `oldgear_swept`), entities (`oldgear_swept_entities`) and pedestals
  (`Gear.showChunk`). Crafted summons give their ingredients back (`REFUND`: the Sigil = 8 Netherite Blocks + a Nether
  Star). A Demon Eye shield imbued with the Ankh Shield is kept. Logged as "[OldGear]". The Museum gives no trophies.
  **Boss enchants** (`Enchants.DROPS`, one list per remaining boss, `enchants.drops.<entry>` overrides; the old
  `boss-drops` / `new-boss-drops` lists are no longer read): all 16 enchants still drop (Jacob, the Abyssal Kraken and the
  Titan the most). Tuned (`tune()`, any old default reads as new): Thunderstrike 7% / +4 / 10 s, Arrowguard 8%/level,
  Featherstep 30%, Vein Miner 20 blocks. **Accessories**: Diamond Heart 10% (was 8%), Mirror reflect cap 12 (6 hearts,
  was 30) + cooldown 60 s (every Jacob fighter gets one), Miner's Lantern 5 blocks, Green Thumb 20%, Treasure Sense 40,
  Smelter's Glove 30% (`TrinketGear.RETUNE`); lore refreshed through `AccessoryStacks.changedOnes`. Owner-picked values
  (Cleft Horn 0.5%, Ranger Emblem 1%, Guardian's Oath 1%, Featherfall 5) left alone. FaultlineSkills stays.
  Index: 343 entries, the removed bosses/items/minions/discs/trophy gone, achievements Lord of the Deep, Hold the Line,
  Warlord's Bane, Wild Hunter and the three Boss Rush ones removed; First Blood / Slayer of Faultline list the bosses left.
  Tested: scratch `mb/Smoke.java` (MockBukkit 4.116.3 from Maven Central over the built papi: all four plugins enable,
  commands gone/kept, Jacob's config, 343 entries, OldGear sweeps + refunds, every enchant has a source).
- **The Reaches + the Mirror is an item (Deep 1.14.0, Items 1.2.66, Index 1.8.1)**: after the owner's reference map of an
  alien-ocean game (built in that style, NOT a copy of its map, names or art). `Deep/Reaches.java` (pure functions): the
  Sunlit Sea's open floor is a Voronoi map of biomes: sites on a jittered `CELL` 650 grid, borders DOMAIN-WARPED (`warped`,
  `WARP` 190: organic blobs, not polygons), kind by hash from a ring list by distance (`RING1` < 1100: Kelpwood, Ember
  Meadows, Pale Dunes, Lantern Groves, open sea; `RING2` < 2100 adds Needle Fields, Lumen Reef, Hanging Isles; `RING3` adds
  the Drowned Peaks), and the SUNLIT SHALLOWS round the arrival point (`shallows`, r 230 + 70 blend, floor SEA-46). Floors
  blend between sites with exponential weights (`floor`, `SOFT` 16: smooth slopes or cliffs, never steps); materials and
  decoration from the nearest site (`at`). `DeepGenerator.upperFloor` = `Reaches.floor(old open floor)` then the landmarks
  as before. Kinds: SHALLOWS (sand, the old reef's coral, ~45 under the surface), KELP (floor 74, kelp to the surface),
  MEADOW (plateaus on sheer cliffs `plateau` noise, crimson-nylium "red grass" + fire coral, banded terracotta cliffs), BULB
  (lavender floor, LANTERN TREES: dark-oak stalk + a caged sphere of sea lanterns / cyan glass, `bulb`), DUNES (dune ridges +
  `crater`s, arches, whale falls), CRAGS (jagged banded terracotta NEEDLES up to ~85 tall, `needle`), PEAKS (ridged mountains
  to SEA-30), GLOW (deep basin, LUMEN DOMES: purple glass caps with pearlescent froglights on purpur stems, pane strands,
  `dome`), ISLES (deep floor, floating mossy rock islands y 120-240, `isle` = one span per column), OPEN (the old floor:
  sand, prism spires, stacks, arches, whale falls). Feature bases use the floor at their own SITE (`siteFloor`, per-thread
  cache), so they never shear. Exposed rock above y 64 is the kind's own (`under`), not the crust's bands. The SULPHUR SEA is
  a MUSHROOM FOREST (`mushroom`: mushroom-stem trunks, yellow/lime terracotta caps with verdant froglights, ochre-froglight
  gills; on islands too, capped below y 318). Noise `plateau`, `ridges`, `detail` appended LAST in `Noise`. No crust caves
  above y 100 in columns whose floor is over 110 (cost). Ruins and Details only in the quiet kinds (`Reaches.quiet`). Biomes
  per kind (`Reaches.biome`: OCEAN / LUKEWARM_OCEAN added to the provider). Titles on entry (`Outer.reachTitle`,
  `reaches.titles`), chart colours (`Reaches.colour` in `AbyssMap.colour`). Old chunks don't change: delete the
  `faultline_deep` world folder for the new sea. Previews: `tools/previews/reach_*.png` (map + top/side of every kind),
  made by `tools/reach_preview/ReachPreview.java` (MockBukkit + a Proxy ChunkData; ~11 ms/chunk).
  **Mirror..??** (`Items/MirrorGear.java`): a plain ITEM (glass pane, model `faultline:lost_mirror`, PDC
  `faultlineitems:lost_mirror`, no `is_accessory`), no reflect power; old ones are converted on join / any inventory opened
  (`convert`) and pulled out of accessory slots 30 ticks after join (`unslot`, closing an open Wardrobe first); config
  `mirror:` removed. Index: the entry moved to Weapons & Tools. Tested (`mb/Smoke.java`: not an accessory, old one unslotted).
- **The Aurora and the Locust Swarm are gone (Items 1.2.67, Index 1.8.2)**: `Weather.KINDS` = sandstorm, blizzard, eclipse
  (the `Aurora` / `Locusts` classes, the XP / enchant-cost handlers, the farm lookup and `LOCUST_TAG` removed; leftover
  locusts carry `faultline_weather` and are swept on startup). `EventPacks` no longer has an `aurora` pack
  (FaultlineAurora.zip is never sent; the file stays in the repo, unused). /itemsmenu lost both buttons, `weather.aurora` /
  `weather.locusts` / `event-packs.aurora` left the default config (old keys in a server's config are ignored). Index: the
  Locust entry and the Pest Control achievement removed (342 entries), the Discord forward regexes, the guide's weather line
  and `/serverstats` (no "Locust swarms") updated. Tested (`mb/Smoke.java`).
- **Deep 1.14.1 (the sunken anchor)**: `Details.anchor` floated apart in game: the ring sat diagonally off the shank, the
  rotted stock left loose bits beside it, and the chain was a staircase of single links hanging in mid-water. Now the shank
  runs to b+11 with a closed ring on it (b+12..14), the stock is one piece (one side rotted short), and the chain runs out
  through the ring, hangs straight down past the flukes and lies along the real floor (`g.upperFloor` per column, vertical
  links where the floor steps). Already-generated anchors don't change. Preview: scratch `mb/prev` `AnchorPreview`.
- **Caves, orbs, mushrooms; meteors gone (Deep 1.14.2, Items 1.2.68, Index 1.8.3)**:
  **Crust caves** (`DeepGenerator.crustCave`, pure, also used by the plugin): tunnels as before; caverns rarer and smaller
  (c3 > 0.72 on 46x22, was 0.64 on 60x26: halls a hundred blocks long), broken up by ROCK PILLARS (`cavePillar`, grid 9, 45%),
  and at least 7 blocks of rock over every cave (was 3). `decorateCrust` (now with world x/z): gravel/clay and moss patches by
  noise, cobbled rubble, exposed ore (coal/copper/iron/gold/lapis), magma cracks with down-bubbles, stalagmites, rubble
  slabs/stairs, boulders, amethyst, dead coral fans, bone, lichen + pickles; stalactites and lichen overhead.
  **Cave caches** (`DeepGenerator.cacheSpot(seed, cx, cz)`, pure: `CACHE_CHANCE` 2.5% of chunks, a crust cave floor with rock
  overhead, never by a chimney / in the Trench / over the build area; `LOCKED_SHARE` 40%): a chest on a POLISHED DEEPSLATE plinth
  + lantern (open) or a CHISELED DEEPSLATE plinth + soul lantern (locked). The crust's old random Dark Loot chests are gone (the
  abyss mound-tunnel chests stay, 17.5% of chunks). `Deep/CaveCaches.java` (`caves:` config): finds a cache by position; locked =
  needs a DEEP CACHE KEY in hand (PAPER, model `faultline:drowned_vault_key` + glint, PDC `faultlinedeep:cache_key`; used up, block
  PDC `faultlinedeep:cache_unlocked`), can't be broken/blown up while locked; the first open runs Items' new
  `darkloot fill <world> <x> <y> <z> [rare] [player]` with `caves.rare-chance` 0.01 (Dark Loot with a 1% rare instead of 0.1%,
  marks `dark_loot_done`, broadcasts a rare). Keys: Dark Ocean creatures killed by a player in CAVES/ABYSS, `caves.key-drop-chance`
  3%; `/deep give cache_key`. Index entry (consumables). About 1.4% of chunks end up with one (some rolls find no cave).
  **Lantern trees / lumen domes cut in half**: each column drew only its own biome's features, up to its own floor + reach, so a
  tree at a biome edge or over lower ground was sliced (orbs in half, stalks in floating pieces). Now `Reaches.tall` (bulb +
  dome) runs in every Reaches column, each feature only from a site of its own kind (`siteKind`, cached), up to its real top
  (`tallTop`); crag needles still follow the column's floor.
  **Sulphur mushrooms** (`Reaches.shroom/mushroom/mushroomTop`): three caps per site, DOME (spotted, rayed gills), OPEN (wide flat
  disc, rim turned up, glowing gill rays) and BELL (tall round cap, a lacy mangrove-root veil); swollen rooted foot, a ring under
  the cap; pale bone spots, glowing verdant rims; only from sites with Sulphur weight > 0.5, drawn up to their real top (no more
  cut caps). BRACKET MUSHROOMS on the cliffs (`Reaches.bracket`, pass `DeepGenerator.brackets` from a 24x24 heightmap, so they
  match across chunks): half-disc shelves in tiers of 9, yellow tops with bone spots, glowing lime rims, gills underneath.
  **Meteors removed** (Items): no strikes, guards, loot or `/meteor` (command + `items.meteor` permission gone, `meteor:` config
  ignored). `Meteor.java` is now only `cleanUp` (startup: leftover `faultline_meteor` guards removed, a crash's crater in
  meteor.yml healed, file deleted) + the `legacy()` text helper. Index: Molten Husk / Meteor Crawler / Star Sentinel entries and
  the Stargazer / Star Collector achievements removed (340 entries with the new key), `/serverstats` and the guide/Discord
  forwards updated. The Meteor Pendant cosmetic (FaultlineCosmetics) has no source any more.
  Old chunks don't change: delete the `faultline_deep` world folder for the new caves, caches, trees and mushrooms.
  Previews: `tools/previews/fix_*.png` (scratch `mb/prev` `Prev2`, `BrCount`). Tested (`mb/Smoke.java`).
- **Totem limit + pearl cooldown (Items 1.2.69)**: `Items/TotemLimit.java` (`totem-limit:`, data `totems.yml`): a totem popped
  because of ANOTHER PLAYER (`playerBehind`: melee, their projectiles, tamed pets, TNT they lit; read from the victim's
  `getLastDamageCause()`, else a hit by a player in the last 0.6 s) counts 1/4..; at `max` (4) the next one is cancelled in
  `EntityResurrectEvent` (HIGH, ignoreCancelled: it only fires uncancelled when a totem is there) = they die, with "4/4 Totems
  has been popped, this totem wont work". Mob / fall / lava pops never count or get blocked. Holding a totem (either hand) at the
  limit warns once per totem (`warnHolders`, every 10 ticks). `reset-on-death` (true) puts it back to 0. `/totems` (players:
  own count; `items.totems` (op): `<player>`, `set <player> <n>`, `reset <player|all>`, `limit <n|off>`, `on|off`, written to the
  config). ENDER PEARLS (`PvpLimits`, `pvp-limits.ender-pearl-cooldown-seconds` 7): the throw (`ProjectileLaunchEvent`) sets
  `setCooldown(ENDER_PEARL, 140)` a tick later (the game puts its own 1 s on after the event); a right-click with a pearl still
  cooling down is denied (`pearlReady`, kept over relogs and re-applied on join). Survival/adventure only. Tested (`mb/Smoke.java`:
  4 pops work, the 5th doesn't + the warning, a fall pop at 4/4 still works, holding warns, set 2, death reset, limit off,
  140-tick pearl cooldown, early throw refused, works again after 7 s).
- **Ships 1.6.11 (land cannons pointed backwards)**: an ItemDisplay draws its item turned 180 degrees round Y (ship cannons
  already make up for it: the `facing=south` ones get `FLIP`), but `LandCannons.transform` didn't, so on Java the barrel
  pointed behind the cannon while it fired forwards. Now `rotateX(pitch).rotateY(PI)` (checked: muzzle = the cannon's
  facing, aiming up lifts it). The Bedrock stand model was right and is unchanged.
- **Emotes (Items 1.2.70, Index 1.8.4 = guide line)**: `Items/Emotes.java` (`emotes:`, crash data `emotes.yml`, perm
  `faultline.emote` default true). `/emote` (`/emotes`, `/em`) = a chest menu (Bedrock: a form); `/emote <id>|stop|list|camera
  on|off|join <player>`. The body is a MANNEQUIN (`EntityType.MANNEQUIN`, spawned by type; tag `faultline_emote`, non-persistent,
  no gravity, immovable) wearing the player's skin (`ResolvableProfile` of their profile), name and armour COPIES (drop chance 0,
  death drops cleared). Animation = per-tick keyframes (`frame()` writes wants: fwd/side/lift offsets, yaw, head pitch, pose
  STANDING/SNEAKING/FALL_FLYING, a pose item, riptide, scale; `apply()` puts them on, never into a wall: `lastGood`) + swings,
  beat-synced note-block music (`beat()`: 16-step kick/snare/hat/bass), particles, floating TextDisplays (`floatAt`, props).
  Arm poses come from invisible items (`poseItem`: model `faultline:gui/blank`, a CONSUMABLE component with an
  `ItemUseAnimation`, used via `startUsingItem`): `crossbow` (charged crossbow = both arms out), `goggles` (SPYGLASS),
  `handout` (BRUSH), `raise` (TRIDENT), `chest` (BLOCK), `mouth` (TOOT_HORN). The client derives a living entity's BODY yaw
  (no packet): it follows the head only past 75 degrees or on a swing, and turns toward movement. Emotes: griddy, aura (Aura
  Farming), sturdy (Get Sturdy), birdbrain (loop up to `max-seconds` 60), and DUOS dap (72 ticks) / hug (96): the host WAITS
  (hand/arms out, a TextDisplay prompt, nearby Java players get a [JOIN] chat button, Bedrock within `duo-range` a form) for
  `duo-wait-seconds` 20; joining = right-click the mannequin, [JOIN] (`/emote join <host>`), or picking the same duo emote
  within `duo-range` 6. The partner's body goes `stand()` 1.6 from the host toward them (else the host's front/sides), both
  face each other, `leader` plays the shared effects. CAMERA (Java, `emotes.camera` + per-player PDC `faultlineitems:emote_camera`):
  spectator + `setSpectatorTarget` an ItemDisplay (tag `faultline_emote_cam`, teleport duration 3) flying `shot()` per emote
  (orbit, Aura's low push-in, Sturdy's low fast orbit, Birdbrain's high angle, duo two-shots from both sides), pulled in
  before any solid block; sneak (PlayerStopSpectatingEntityEvent) stops. Bedrock or camera off = stays put in their mode,
  hidden (`hidePlayer`), moving / using / dropping stops it. ANTI-GLITCH: any damage to the mannequin is cancelled, ends the
  emote (both, for a duo) and lands on the player (`p.damage(amount, DamageSource)`, `Camera.noteFight` for both sides);
  damage to the player ends it; refused in combat (`Camera.refuse`), in the air / water / riding / gliding / vanished / in
  /camera / racing / without headroom / on cooldown (server ticks); spectator hotbar teleports and spectating anything else
  are blocked; another teleport ends it then still happens (next tick); game mode changed by staff ends it without moving
  them; world change, quit, shutdown, a crash (emotes.yml on join) put them back exactly where they stood in their old
  mode; a move error ends the emote with "[Emotes] <id> broke at tick N: <error> at <method>:<line>". Tested (scratch
  `et/EmoteTest`: all 6, hit forwarding, combat refuse, sneak, tp, quit, armour drops, camera off, dap + hug joins, timeout).
- **Emotes loop (Items 1.2.71)**: every emote loops until you stop it (`emotes.max-seconds` 0 = no limit; a server config
  still saying 1.2.70's 60 reads as 0): Dap Up repeats its 72-tick sequence (`t % 72`), Hug holds and sways the embrace
  (ticks 14..73 looped). One partner stopping (sneak, /emote stop, a hit) ends both. Elbows can't bend: the body is a vanilla
  mannequin (one-piece arms); bendy limbs with the player's skin need a client mod or a resource-pack core shader rig.
- **The bendy emote body (Items 1.2.72)**: `Items/EmoteRig.java` + `FaultlineEmotes.zip` (built by `python3 tools/emote_pack.py <repo>
  --check <dir with 26.2's shaders/include/*.glsl>`; `--check` compiles all 128 define combos of both stages with glslangValidator).
  Technique, part ids and UV tables from Stable Player Display by bradleyq (CC0; credited in the pack's CREDITS.txt), ported from
  1.21.8's to 26.2's `core/entity.vsh/.fsh` (pack format 88; 26.2's vanilla shaders: misode/mcmeta `26.2-assets` on GitHub). Each
  body part is an item display (THIRDPERSON_RIGHTHAND) holding the player's PLAYER_HEAD (their profile) with item model
  `faultline:emote/<part>` (`slim/` variant via custom_model_data string "slim", from `PlayerTextures.SkinModel`); its
  translation is lowered by 1024 x part id (0 head, 1 r arm, 2 l arm, 3 chest, 4 r leg, 5 l leg, 6 r forearm, 7 l forearm,
  8 waist, 9 r shin, 10 l shin) and the shader moves it back and paints that part of the skin (inner + outer layer). Part
  cubes hang DOWN from the display (head sits on it), x/z centred; limbs/torso scaled 0.9375 on Y. Rig frame: x = right,
  y = up, -z = front; `face = rotY(PI - yaw)`. FK in `solve()`: pelvis -> waist -> chest -> head / upper arms (shoulder 2 px
  under the arm top) -> forearms; pelvis -> thighs -> shins. `Pose`: root offset, body yaw, pelvis + per-joint euler
  (`limb()` = forward / outward / twist, mirrored for the left side; `lean()` for upright parts). Updated every tick with
  interpolation 2. `Emotes.rigPose()` builds every emote's pose from the frame's wants (crouch with knees, swings with elbows,
  the arm-pose items) + its own bends: `griddyRig` (hand-keyed: heel kicks, bent knee under the dip, elbows ~85 pumping,
  chest twist, head nods, the two-handed goggles), `auraRig`, `sturdyRig` (knee lift before each stomp), `birdRig` (chicken
  wings), `dapRig` (arm round the back), `hugRig` (forearms curl round). WHO SEES WHAT (`viewers()`, every 10 ticks):
  `rigViewer` = Java + FaultlineEmotes.zip loaded (`packLoaded`, PlayerResourcePackStatusEvent for `Emotes.PACK_ID`) +
  client protocol in `emotes.rig-protocols` [776 = 26.2] (ViaVersion by reflection, none = native) + not `/emote view
  classic` (PDC `faultlineitems:emote_view`): they see the rig (+ a name TextDisplay + an Interaction hitbox, since the
  mannequin is hidden from them); everyone else sees the mannequin. Rig parts are `setVisibleByDefault(false)` AND hidden by
  hand from everyone present at spawn. Hitting the hitbox (PrePlayerAttackEntityEvent) = `hitLands()` with their attack
  damage x swing charge (the mannequin's damage path uses the same method). The pack is sent from `applyTextures` (after the
  music pack) only to rig-capable games: `item-textures.emotes-url` (code default = raw main FaultlineEmotes.zip),
  `emotes-hash` (empty = PackHash works it out). Iris/OptiFine shaders or another version = they just see the mannequin
  (their client still loads the pack; `/emote view classic` if the bodies look broken). UNTESTED IN A REAL CLIENT: the
  shader compiles and links for every variant, but the vertex order / render type of a skull in an item display in 26.2
  is assumed to match 1.21.8's. Previews: `release/previews/emote_*_bendy.mp4` (scratch `vid/render_rig.py` draws the
  dumped transforms). Tested (scratch `et/EmoteTest`: 81 checks incl. 11 parts in their 1024 bands, finite transforms,
  hidden without the pack, shown once it loads, view classic/rig, hitbox hit forwarding, duo = 22 parts, cleanup).
- **The Griddy, 1:1 (Items 1.2.73)**: keyed to the real dance. Both bodies share the timing (`GRIDDY_STEP` 6 ticks a tap = ~100 BPM
  in eighths, `GRIDDY_BAR` 16 steps: 12 traveling heel taps (`griddyTravel`: 0.85 forward), then 4 steps of goggles while it
  skips back to the start so the loop lines up). `griddyRig`: hop-skip (`up` lands on each tap, lifts between), the tapping
  leg kicks out nearly straight on its heel (thigh 42) while the support knee bends (18 / -38), loose slightly bent arms
  swing opposite the legs and cross low in front of the hips, chest counter-twists, torso leans in, head watches the feet;
  GOGGLES: elbows up and out, forearms upright beside the face, hands on the eyes (angles SOLVED: scratch `vid/ik.py` =
  the rig's FK in Python, a grid search for the hand on the eye + forearm-upright penalty: R_ARM limb(120, 60, 50),
  R_FORE limb(50, -30, 20), mirrored). Previews `release/previews/emote_griddy_bendy.mp4` + `emote_griddy_front.mp4`.
- **Emotes walk with you (Items 1.2.74)**: the shader body drew as loose cubes in a real 26.2 game (owner's screenshot) and
  never showed armour, so it's OFF: new key `emotes.bendy-body` (default false; `bendy()`; the old `emotes.rig` key is
  ignored so a server config still saying `rig: true` can't turn it back on) and FaultlineEmotes.zip is no longer sent while
  it's off. Everyone sees the MANNEQUIN (skin + armour copies). The CAMERA IS GONE (`cameraPref` always false, the menu
  slot is a help sign, `/emote camera` says it was removed). SOLO emotes WALK WITH YOU (`walks(r)`, `follow(r)`: each tick
  the base = the player's spot and look yaw, the real player stays hidden, F5 to watch); SNEAK stops (`onSneak`); swimming,
  gliding, riding, flying or water ends it. Duos still stay on their spot (walking off cancels). Bendy limbs with armour on
  vanilla clients are impossible server-side: Essential does it with a CLIENT MOD. Tested (scratch `et/WalkTest`).
- **Gunnery (Ships 1.6.12, `Ships/Gunnery.java`, `gunnery:` + `cannon.traverse/min-elevation/max-elevation`)**: every
  cannon aims up and down now. SHIP GUNS have a laid aim per cell (`Ship.aim` = {traverse +left, elevation +up} degrees;
  `Ship.transform` turns the barrel `rotY(trav) * q0 * rotX(elev)`; `gunDir/gunCenter/gunMuzzle`). Quick fire (right-click a
  Cannonball on board) swings the nearest ready gun on that side to where you look (traverse 35, elevation -10..40) and
  fires (`layGun`, `fireGun`). MANNING: sneak + right-click with a Cannonball within `gunnery.reach` 2.8 of a ship gun
  (owner/crew, not riding) or a land cannon (`mayUse`): you're held in place (MOVEMENT_SPEED + JUMP_STRENGTH -1
  MULTIPLY_SCALAR_1 modifiers `faultlineships:gunner`, removed on leave/quit/death/world change/disable/join), W/S elevation,
  A/D traverse (land: free 360 yaw, elevation -20..45), Sprint = 0.35 degree steps (else 1.4/tick), hotbar scroll (cancelled)
  = powder charge Light/Standard/Heavy (speed x0.7/1/1.3, reload x0.8/1/1.35), Jump edge or right-click = fire, Sneak (after
  letting go of it once) = leave. Only the gunner sees the predicted arc (snowball physics: drag 0.99, gravity 0.03, stops at
  solid / unloaded blocks) + a red landing ring, and an action bar (elevation, traverse/bearing, charge, range, READY). One
  gunner per gun; others get "Someone's manning that gun." Pushed past `leave-distance` 4.5, the gun gone or the ship wrecked =
  let go. `LandCannons.turn(c, yaw, pitch, eps)` for fine steps. Tested (scratch `gt/GunTest`: man, hold sneak, W/D/fine,
  clamp, Jump fire + ball used + upward shot, scroll = heavy x1.3, reload, arc, leave, stranger, distance, quit).
- **The Kraken: smaller, lighter, never in the ground (Bosses 2.0.1)**: STUCK: `keepInWater` only checked the column under his
  middle, so his wide head swam into cliffs, ledges and cave walls and sat there buried. Now `unwall()` (not during the intro)
  tests 72 points round his head (`headR` = 3.5 x scale/4, 12 directions x 2 rings x 3 heights, `solidAt`, unloaded = solid),
  pushes him out and kills the speed going into rock; buried 2 s = moved to the nearest open water his head fits (`openWater`,
  `fits`, logged "[Kraken] was stuck in the ground"); the floor clamp uses the highest floor under the edge of his head too
  (`ringFloor`, every 4 ticks); a summon whose home doesn't fit his head moves it. LAG: the Abyssal Kraken was scale 40 (a
  70-block head, view range 16 = drawn from ~1000 blocks) and every one of his ~77 displays sent a teleport + transformation
  every tick: now `abyssal.scale` 14 (3.5x; `acfg(path, old, new)` reads 1.9.0's defaults as the new ones: hit-scale 2.2->1.5,
  move-scale 2.2->1.4, reach-scale 2.5->1.6, station 50->20, station-height 20->8, arena-radius 140->70), view range 6/5, Bedrock
  squid 8, and display packets every 2nd tick (interpolated over 2) and none with nobody within 128/200 (`send()`; the hitbox
  still moves every tick). Tested (scratch `kt/KrakenWallTest`, MockBukkit gaps stubbed with ByteBuddy `kt/Patch.java`: old jar
  had his head buried 461 of 650 ticks, new 0).
- **Blue Obsidian + End portals (Items 1.2.75, Index 1.8.5, FaultlineSMP.zip)**: `Items/BlueObsidian.java` (`blue-obsidian:`,
  data `blue_portals.yml`). Recipe OOO/OLO/OOO (8 Obsidian + Lapis Lazuli, key `faultlineitems:blue_obsidian`) = Blue Obsidian
  (CRYING_OBSIDIAN item, model `faultline:blue_obsidian`, PDC `faultlineitems:blue_obsidian`; never in vanilla recipes:
  `onCraft`). Placed = a real CRYING_OBSIDIAN (hard, blast-proof, immovable) recorded in the chunk PDC (`faultlineitems:
  blue_obsidian`, int array of packed local positions; `blocks` per world for loaded chunks) + a non-persistent ItemDisplay
  overlay (tag `faultline_blue_obsidian` + `faultline_no_bedrock_fx`, scale 1.004, brightness from the air beside it) for Java;
  Bedrock sees crying obsidian. Broken = drops Blue Obsidian (`onDrop` swaps the crying obsidian drop, tool rules kept). A
  closed frame of it, ANY shape (a ring), lit from the inside with Flint and Steel / a Fire Charge, ONLY in THE_END (`onLight`;
  elsewhere an action bar): flood fill of the air in the X or Z vertical plane (`fill`, 4-connected, every border must be Blue
  Obsidian, `max-portal-cells` 441, `min-portal-cells` 2) -> NETHER_PORTAL blocks with that axis, physics off (`onPhysics`
  cancels neighbour updates on our cells; `upkeep` every 2 s puts knocked-out cells back and closes a portal whose frame lost a
  block). Through it (`PlayerPortalEvent` from one of our cells, cancelled): the Overworld (`world` or the main NORMAL world),
  a random x/z in [-max-xz, max-xz] (500), surface (`surface`: not liquid/lava/magma/cactus/fire/powder snow, 2 air above),
  async chunk load (`tryAt`, 15 tries, else world spawn); NOTHING is built on that side (no way back). Entities can't use
  them (`onEntityPortal`); vanilla never builds into our cells (`onPortalCreate`). `/giveblueobsidian [amount] [player]`
  (`items.giveblueobsidian`), /itemsmenu. Pack: `assets/faultline/{items,models/block,textures/block,textures/index}/
  blue_obsidian*` + font providers \ue443/\ue843 (glyphs.yml `blue_obsidian`). Index entry (Weapons & Tools). Not in the
  Bedrock mappings yet. Tested (scratch `bt/BlueTest`: recipe, ring, overlays, lighting in the End only, plain crying obsidian
  ignored, travel within 500 on solid ground with no portal built, physics, self-heal, outer vs frame block breaks, saving).
- **The blue portal + its summoning (Items 1.2.76, FaultlineSMP.zip)**: the purple NETHER_PORTAL fill is gone. A portal's cells
  are LIGHT blocks (`portal-light` 11, invisible, walk-through) + one non-persistent ItemDisplay per cell (`sheet`, tag
  `faultline_blue_portal`, PAPER with model `faultline:blue_portal`: a 1-px-thick plane with an ANIMATED translucent swirl
  `textures/block/blue_portal.png` 32 frames, tileable; brightness 15; turned 90 degrees for Z-axis portals). PAPER base, not
  the crying obsidian item, so the texture stays translucent. An open portal's frame GLOWS (`lit` per world, `setLit`: overlay
  -> `faultline:blue_obsidian_lit`, pulsing cyan veins, 8 frames, brightness 15; dark again on close). Overlays/sheets are
  shown through `model(id)`. SUMMONING (`summon`, a BukkitRunnable; the cells are claimed at once, `ready` false until the
  burst): the click flares (flint, anchor charge, soul flames), a charge runs round the frame both ways in 26-neighbour layers
  from the clicked block (`frameLayers`, 2 ticks a layer, sparks + amethyst chimes rising in pitch), 12 ticks of light
  pouring from the frame to the middle (END_ROD aimed in, beacon activate), the sheet grows out from the middle (each cell
  scales 0 -> 1 over 7 ticks, LIGHT placed), then the BURST (end portal spawn + beacon + a soft boom, fireworks, reverse
  portal, a shockwave ring in the portal's plane growing 14 ticks: `ring`). `ambient` every 5 ticks with a player within 48
  (motes, frame sparks, beacon hum). TRAVEL is our own now (`travelTick`, every 2 ticks): feet or eyes in a ready cell charges
  `travel-ticks` 30 (creative at once; whoosh + particles), then `send`. Close: the sheets shrink away, LIGHT removed, frame
  dark, beacon deactivate. 1.2.75 portals (nether portal cells) turn into blue ones by `upkeep`. Bedrock sees the particles only
  (no displays). Preview `release/previews/blue_portal_summon.mp4` (scratch `bo/anim.py`). Tested (scratch `bt/BlueTest`, 34
  checks: charge progress mid-summon, no travel mid-summon, ready, LIGHT + sheet per cell, full size/brightness, whole frame
  lit, stand-in travel after 1.5 s, self-heal of light and sheets, shrink + dark on close). MockBukkit quirk met again:
  `Block.getLocation()` is the block's OWN Location (adding to it moved the block): build `new Location(...)` instead.
- **Light blue (Items 1.2.77, FaultlineSMP.zip)**: no purple anywhere. `blue_obsidian.png` is icy steel-blue with pale cyan
  glints (also the Index icon); `blue_obsidian_lit.png` is its own look: a paler ice-blue block with white-hot cracks and a
  bright band of energy flowing diagonally through it (16 frames, frametime 2, tileable); `blue_portal.png` is a sky-blue /
  white swirl. Particles: every REVERSE_PORTAL (purple) is GLOW now, the dust colours `CYAN` (150,228,255) / `DEEP`
  (105,195,255). A frame block that lights swells to 1.02 (eased over 4 ticks) with a puff of light-blue dust and snowflakes;
  open frames shed snowflakes (`ambient`). Made by scratch `bo/make3.py`; preview re-rendered.
- **Kingdom head tags (Kingdoms 1.3.0, `Kingdoms/HeadTags.java`, `display.head-tag` / `outlaw-name` / `outlaw-color` /
  `tab-outlaw` / `head-tag-scale` / `head-tag-view-range`)**: a line over every player's name tag = their kingdom in its
  colour, or "Outlaw" (DARK_RED) with no kingdom. A non-persistent TextDisplay (tag `faultline_kingdom_tag`) that FOLLOWS the
  player (teleported to hitbox top + 0.78 each tick they moved, teleport duration 2), not a passenger. Hidden by default,
  shown per viewer every 10 ticks: never to the player themself, nor anyone who `canSee` says can't see them (vanish, emotes,
  /camera), nor while they're invisible / spectating / dead. The tab list shows "Outlaw name" for no kingdom (`tab-outlaw`).
  Bedrock (Geyser) has no text displays: Bedrock players don't see the tags. Tested (scratch `ht/TagTest`).
- **The suggestions batch (Kingdoms 1.4.0, LimboBlackMarket 1.2.0, Items 1.2.78, Deep 1.15.0, Index 1.9.0, FaultlineSMP.zip)**:
  **Treaties** (Kingdoms, `treaties:`): `/kingdom ally <1|2|3> <yes|no|clear>` (`Member.allyVotes`, saved `ally-votes`); kingdoms a and b
  are allied while BOTH want it (`wants`: >= `min-votes` 3 voted and yes > no) or staff forced it (`/kingdoms treaty <a> <b>
  <on|off|vote>`, `treaty-forced`); `checkTreaties` (votes, kingdom changes, every 10 s) announces; allies can't hurt each other
  (`blocked`); a broken treaty keeps PvP off `break-grace-minutes` 5 (`brokenAt`). `FaultlineKingdoms.allies(uuid, uuid)` for
  other plugins. **Kingdom chat** (`KingdomChat.java`, `kingdom-chat:`): `/kc [msg]` (alone = toggle), `/ac` = kingdom + allies
  (AsyncChatEvent LOWEST cancelled, so it never reaches public chat or the Discord mirror); `kingdoms.spy` sees all (`/kingdoms
  spy`). Each kingdom's own Discord channel `discord.kingdom-chat.channels.1/2/3`: /kc is POSTed there by the bot (batched every
  1.5 s, no pings) and MESSAGE_CREATE in that channel goes to that kingdom only (`DiscordGateway.message` -> `fromDiscord`; the
  gateway asks for the message intents when any kingdom channel is set). Tested (scratch `tr/TrTest`).
  **Kill streak cosmetics** (LimboBlackMarket `StreakFx.java`, `streak-cosmetics:`): fire trail at `trail-at` 5 kills in a row,
  a floating Slayer Crown (`faultline:cosmetic/slayer_crown`, ItemDisplay `faultline_streak_crown`, above the kingdom head tag)
  at `crown-at` 10; per-viewer like the head tags. Tested (scratch `st/StTest`).
  **Items 1.2.78**: BACKPACK (`Backpack.java`, `backpack:`): accessory (`/giveaccessory backpack`, recipe LSL/LCL/LLL, model
  `faultline:backpack`), `/backpack` (`/bp`) or sneak + right-click air = 9 slots stored in the PLAYER's PDC
  `faultlineitems:backpack_items` (serializeItemsAsBytes, saved on every click), drops on death. CREATOR MODE (`CreatorMode.java`,
  `/creator`, `creator-mode:`): Where's bar says "hidden", DeathSpot shows distance only, shared /coords reach them masked,
  chat renderer masks coordinates + IPs per viewer (`COORDS` / `IP` regexes; F3 can't be hidden per player). MIMIC PLAYER
  (`MimicPlayer.java`, admin `/mimicplayer [random|<name>|clear|list] [n]`, `mimic-player:`): invisible zombie brain
  (`faultline_player_mimic`) + a MANNEQUIN with an offline player's skin (`faultline_player_mimic_body`) teleported onto it every
  tick; strafes, sprint-jump crits, shield (axes break it), golden apples, pearls, bow, one totem. HAUNTED VILLAGES
  (`HauntedVillage.java`, `haunted-villages:`, Oct 1 - Nov 2): at night near a village (`locateNearestStructure`) 3 waves of
  Restless Spirits (vex) / Haunted Villagers (zombie villagers in pumpkins), then the Lantern Wraith (wither skeleton, 160 hp)
  = Candy (`cosmetic tokens <p> halloween 6`), a Goodie Bag, stat `haunted_villages`; Cursed Jack o'Lantern (/itemsmenu)
  starts one. KRAMPUS (`Krampus.java`, `krampus:`, Dec 1 - Jan 6): Krampus' Bell (BELL, recipe CIC/IBI/CIC) or 0.3%/5 min at
  night; wither skeleton x1.7 + horn/chain/sack displays, 900 hp; Switch, Chain Lash, Into the Sack, Coal Barrage, Naughty
  List, Imps, Winter's Howl; Presents (`cosmetic tokens <p> winter 12`), Mythic + Goodie Bags, stat `krampus`.
  `/holiday [status|haunt [p|stop]|krampus [p|kill]|bell|lantern]` (`items.holiday`). Tested (scratch `nt/NewTest`).
  **Deep 1.15.0**: DEN FESTIVAL (`DenFestival.java`, `den.festival:`, days 1-2 each month or `/deep den festival start|stop|auto`):
  boss bar, lights + chimes, each resident gives each visitor ONE gift per festival (`DenRep.click` asks `festival.gift` first:
  `gifts` roll + Glimmer Pearls + rep +5 + 15% Dark Loot Bag), stat `den_festivals`. CORAL FARMING (`CoralFarm.java`,
  `habitat.coral.farm:`): planted Air Coral blooms (1/grow-minutes a minute, x1.5 near Hydroponics, max 3; chunk PDC
  `faultlinedeep:coral_blooms`), empty-hand right-click = Air Coral Fragments (model `faultline:air_coral_fragment`), `/glimmer
  sell` all year (4 = 1 pearl; also a button in the shop), 4 round a Tube Coral Block = Air Coral. LIGHTHOUSES (`Lighthouse.java`,
  `lighthouse:`, `lighthouses.yml`): Lighthouse Lamp (model `faultline:lighthouse_lamp`, recipe GGG/SHS/PPP) on a tower = a sea
  lantern lighting radius 10 + 1.5 x tower height (max 48) for good (`Ocean.darkZone`), sweeping beam, 3 each.
  Tested (scratch `dt2/DeepNew`). Index: 11 entries (352) + Silent Night, Ghostbuster, Festival Goer, Coral Farmer; icons
  from scratch `icons/make.py` (also the 3 item textures). Not in the Bedrock mappings yet (Bedrock sees the base items).
- **The real mimic + tags lowered (Items 1.2.79, Kingdoms 1.4.1, LimboBlackMarket 1.2.1)**: the 1.2.78 mimic's brain was an
  invisible ZOMBIE wearing the kit: an invisible mob still draws its armour and held items, so a second armour set, a sword and
  a shield floated on the body (owner's screenshot). Now the brain is an invisible VINDICATOR (not undead: Regeneration /
  Instant Health work) carrying NOTHING, ATTACK_DAMAGE 0 and its vanilla melee cancelled (`striking` flag): every hit is our
  `strike` (real cooldown by weapon, reach 3 eye-to-hitbox `reachTo`, hit-select, crits while falling, sprint knockback, W-tap,
  sweeps, Knockback / Fire Aspect). GEAR = the real player's (`Gear`, `capture`): online = right now, offline = snapshot every
  minute + on quit (`mimic_gear.yml`): armour + enchants (armour points / toughness / KB res as attributes from `armorOf`,
  Protection / Fire / Blast / Projectile Protection / Feather Falling as vanilla EPF in `onProtection`), best sword + axe, mace,
  bow (Power, Flame), off hand, and real counts of gaps, god apples, pearls, splash Healing, totems (each pops), wind charges,
  lava/water buckets, cobwebs, blocks, rockets, rod, flint, elytra; their max health (20); no snapshot = `mimic-player.kit`.
  Skills `easy/normal/hard/godlike` (`SKILLS`: reaction, block, hit-select, jump-crit, bow, spread, W-tap, strafe, speed).
  TECHS: MACE (`maceTech`/`smash`: wind-charge slam with air steering, Wind Burst pogo chains, stun slam = axe breaks your
  shield then the mace, elytra + rocket dive, swap to the mace when falling into reach; damage `maceBonus` = vanilla's
  4/2/1 per block + Density 0.5/block/level, Breach x(1+0.12/level), smash knocks back nearby, no fall damage after a
  smash), SWORD/AXE (crit chains `jumpAt`, sweeps, axe on your shield), UHC (`uhcTech`: lava at your feet 1.5 s, cobwebs 5 s,
  rod hit + sprint in, flint and steel, `clutch` water MLG on its own falls, `pillar` to escape) through `TempBlock`s (only into
  air, put back on expiry / clear / shutdown; flows, ignition, burning, spreading, bucket pickups and drops blocked near them;
  `mimic-player.world-techs`). Heals: splash Healing II at its feet (looks down), gaps (god apple when very low), pearls AWAY
  to get room to eat (`eatAfterPearl`), natural regen out of combat, flees + pearls away with nothing left. Kingdom line over
  its name (`kingdomLine` reads FaultlineKingdoms by reflection). `/mimicplayer [random|<p>] [count] [skill]`.
  Head tags: `display.head-tag-offset` 0.45 over the hitbox (1.3.0's 0.78 floated a line above the name; an old 0.78 reads
  as 0.45); the streak crown `streak-cosmetics.crown-offset` 0.55 (was 1.2 = high in the sky). Tested (scratch `nt/NewTest`:
  bare brain, his Prot IV / sword / off hand / counts, EPF 32%, vanilla melee off, 2 totems, mace damage, temp lava back, a
  30 s fight that really hurts him).
- **The mimic, round 2 (Items 1.2.80)**: `MimicPlayer` brain has NO vanilla goals (`Bukkit.getMobGoals().removeAllGoals`):
  every move is ours. `move()` = player movement (sprint speed `skill.speed` x0.9 + Speed, SPRINT-JUMPS on open ground with the
  +0.2 boost, steps up blocks, keeps momentum in the air); `chase()` runs straight at you (pathfinder only when a 2-high wall
  blocks it 10 ticks); `flee` uses it too. The BODY is moved by velocity (= the gap to the brain), teleported only when > 1.2
  behind, so the client animates its legs (it used to be teleported every tick; `setImmovable` removed). Hits: EVERY hit is a
  CRIT (jumps when charged and in reach, swings on the way down) or a SPRINT hit (lunges in + sprint knockback); NO sweeps
  (block removed). After eating it goes straight back in (`fleeUntil`/`eatAfterPearl` cleared). Range `mimic-player.range` 160
  (sticks to its target out to 1.5x; FOLLOW_RANGE = range; pearls in from 14-60). COBWEBS (`webEscape`): a real WATER BUCKET
  poured on the web (TempBlock water, the web gone, bucket back after 11 ticks), else a pearl out, else the sword cuts it.
  ARMOUR: worn, else the best spare of that slot in the inventory (`armourScore`); none anywhere = the kit's
  (`kit-when-bare`). ELYTRA PvP (`elytra-pvp`, needs an elytra + rockets): takes off after anyone 22+ away / flying / 8+ above,
  ELYTRA SWAP (body chestplate -> elytra, armour points drop while flying, `armourFor`), glides with lead, rockets when slow,
  stays ~6 above then dives, hits as it passes (mace = smash), lands near a grounded target; when losing it rockets away
  (`elytraEscape`). `eye(m)` falls back to the brain's eye. `/mimicplayer clear` also removes its pearls in flight. Style
  counts debuff pots. Tested (scratch `nt/NewTest`, with a tiny gravity/ground sim since MockBukkit has no entity physics).
- **The mimic, human pass (Items 1.2.81)**: `elytra-pvp` is OFF by default now (code + config). Human bits in `MimicPlayer`:
  SAFE MOVEMENT (`safeStep`/`safePath`, `HAZARD` set): never walks, strafes, W-taps, lunges or sprint-jumps into lava / fire /
  cactus / magma / campfires / berry bushes / powder snow or off a drop of 4+; looks further ahead the faster it moves
  (`look`), turns 45/90 aside to go round, else stops; SWIMS in water (holds space). MISSES like a player (`whiff`,
  `missChance` easy 25% / normal 14% / hard 7% / godlike 3%: arm swings, cooldown resets, nothing lands). AIM: at your chest
  with a hand shake by skill (`aimJx/aimJy` from `skill.spread`), head turn speed by skill (`turnRate` 22/35/50/65 degrees a
  tick). ITEM SWAP time (`swapTicks` 4/3/2/1 before eating / drinking). Keeps MOVING while eating / drawing a bow / drinking
  (item-use crawl 0.075, backs off + strafes, strafes sideways while drawing far off). ZIG-ZAGS in when you hold a bow /
  crossbow / trident (+-35 degrees) or from far off (+-15). TURNS ON whoever hits it (60%, half its reaction time). IDLE with
  nobody around: looks around and wanders a few steps (`idle`, `idleYaw`). Tested (scratch `nt/NewTest`: a stone floor under
  the mock world, the lava pool walk-round, turning on Ana, elytra test turns the key on).
- **The mimic, every tech (Items 1.2.82)**: `tier(m)` = easy 1 / normal 2 / hard 3 / godlike 4 gates the techs (easy: sprint/crit
  hits, eating, potting, blocking, bow; normal+: pot runs, debuff pots, snowballs, UHC techs, TNT, fire charges, spear lunge,
  trident throws, crossbow, towering, digging; hard+: crystals, anchors, riptide, spear-mace; godlike: double crystal, surround).
  GEAR now also counts END_CRYSTAL, OBSIDIAN, RESPAWN_ANCHOR, GLOWSTONE, TNT, FIRE_CHARGE, a spear (`*_SPEAR`), trident,
  crossbow, pickaxe, shovel (saved in mimic_gear.yml). EVERY drinkable potion is its buff (fire res, turtle master...),
  splashes with a good effect too. STYLES: spearmace, mace, crystal, nethpot / pot (healing-pot kits), nethop / diamond,
  trident, spear, bow, uhc, axe, sword. POT RUN (`potRun`, request): low with heal pots and close = sprints away 6/8/11 ticks
  (looking away), throws the Healing pot down ahead of its own run (it runs into the splash), then `rushUntil` 30 ticks:
  straight back in, +10% speed, no zig-zag, no buffing, spacing 2.5. BOOM (`boomStart`/`boomTick`, `boomSpot` = a cell
  beside them, 3.6+ from itself; `mimic-player.explosives`): CRYSTAL (obsidian TempBlock unless there's obsidian/bedrock
  already, EnderCrystal tagged `faultline_player_mimic`, punched = removed + `createExplosion(6, no fire, no blocks, source
  brain)`; godlike 50% DOUBLE CRYSTAL), ANCHOR (not in the Nether: anchor TempBlock, glowstone charge, then click = power 5),
  TNT (TempBlock, lit with flint / fire charge = TNTPrimed 30 fuse, tagged: `onOurBoom` clears block damage), FIRE CHARGE at
  their feet. Step gap 1/2/4 ticks by tier. SURROUND (godlike): their crystals / anchor in hand = obsidian on 4 sides of its
  feet. SPEAR: LUNGE (dash 0.75+0.2xLunge, jab when in reach, +speed damage), SPEAR MACE (lunge up, slam = maceTech swaps to the
  mace). TRIDENT: throw with lead (Loyalty back after 34-6xlvl ticks, none = gone), RIPTIDE (in water/rain, `riptideTick`,
  SPIN_ATTACK pose), Impaling vs wet targets, Channeling lightning in storms. CROSSBOW: Quick Charge load, Multishot 3,
  Piercing. DIG (`startDig`/`digTick`/`mine`, `mimic-player.dig`, `dig-restore-seconds` 60): walled off while chasing =
  mines the head then feet block with the right tool (pickaxe / axe / shovel tags) at vanilla speed (ceil(hardness x 30 /
  tool speed)), never block entities or hardness > 10; mined blocks are TempBlocks (placed AIR) put back later. TOWER UP
  (`towerUp`): they're 2.5+ up within 4 = jump-places blocks under itself. Tested (scratch `nt/NewTest`: styles, drinkables,
  fire res, pot run, crystal + punch, easy never crystals, anchor, trident + Loyalty, spear lunge, dig + restore, tower; the
  test's physics stand-in floors on blocks and slows in webs).
- **No crystal / anchor / cart PvP (Items 1.2.83)**: the owner: Faultline NEVER does crystal, respawn anchor or TNT minecart
  PvP. The mimic's crystal, anchor and surround techs and its crystal / anchor / obsidian / glowstone gear counts are GONE (the
  "crystal" style too); `boomStart` only does TNT and fire charges (`explosives`). Don't add any of them back. Movement:
  `move()` tries the way it wants then 45 / 90 / 135 degrees aside; each candidate must pass `clearMove` (where that velocity
  really takes its whole hitbox over the next ticks, cells it already stands on ignored, `cellSafe`); round an obstacle it
  COMMITS to one side (`detourDeg`/`detourUntil`, only that side's angles) until `clearLine` (the straight way at full width)
  is clear; the crit-chain hop and air control check for hazards too. Tested (scratch `nt/NewTest`: godlike carrying
  crystals + anchors never places either; the lava pool walk-round passes 6/6).
- **The mimic reads you (Items 1.2.84)**: `read()` in `think` (before towering / the fight): their ACTIVE ITEM (`isHandRaised`
  + `getActiveItem`) = food / potion -> `rushUntil` 15 (punish the eat, normal+), bow / crossbow -> shield up (hard+, if it has
  one); they're under 35% and it's over 50% -> all in; PRE-GAP (hard+): golden apple at < 75% with no absorption, 10 s unhurt,
  they're 5+ away; PEARL FOLLOW (hard+, `onTargetPearl` = PlayerTeleportEvent ENDER_PEARL of its target -> `followAt` after its
  reaction time, throws a pearl there when 8-60 away); comboed 4+ times under 50% (godlike) -> pearls out. In `melee`:
  COMBO BREAK (normal+, `comboTaken` counts melee hits taken since its last hit, `comboGiven` the other way): 2+ in a row, its
  hit not ready = a hard side cut + jump (`breakUntil`); I-FRAME TIMING (hard+): never swings while their `noDamageTicks` >
  half the max (a wasted hit); BACK TO A HAZARD (hard+): lava / a ledge 2.2 behind it = strafes to the safe side; BAIT
  (godlike): a fake jump in while its hit charges. FLANK (normal+, `flank`): several mimics on one player come in at +-40/80
  degrees. `webEscape` and the test's web physics use the whole hitbox (`underNow`). Tested (scratch `nt/NewTest`: i-frames
  wait + hit after, combo break, pearl follow, pre-gap, flank angle; 8/8 runs).
- **Mimic swings, doors, dodging (Items 1.2.85)**: ARM SWINGS: vanilla only plays the swing animation back for mobs / players
  (`updateSwingTime` lives in Mob/Player `aiStep`), so a MANNEQUIN never shows `swingMainHand()` (the owner: "the M1 animation
  doesn't work at all, same for the pickaxe"). `swing(m)` (every old `swingMainHand` call) also draws it with item-use poses
  on a COPY of what it holds (PDC `faultlineitems:mimic_pose`, a CONSUMABLE component): TRIDENT (arm up) 2 ticks, SPEAR
  (thrust) 2 ticks, then the real item back; skipped while eating / drinking / drawing / loading / shielding (`usingSomething`);
  empty hand = an invisible `faultline:gui/blank` paper; `mimic-player.arm-animations`. DOORS (`mimic-player.doors`):
  `openAhead` (in `move`) opens wooden doors (both halves), trapdoors and fence gates in its way like a right-click (swing +
  sound; iron ones left alone), `tickDoors` shuts them 2.5 s later when nobody's in them; walled off (`chaseStuck` > 3) it
  `findDoor`s within 8 and walks to it before digging; `blocks(b)` = solid and not an open door; `diggable` never mines an
  Openable. DODGING (`dodge`, `incoming` from `onShot` = its target's projectiles, not pearls): closest approach < 1.4 in 14
  ticks = shield up (normal+, with a shield) or a sidestep (hard+). TOTEM SWAP (hard+, `totemSwap`, first thing each tick):
  under 35% with a shield off hand = totem in the off hand (`totemHand`: no shield), back at 65%. A shield anywhere in their
  inventory counts as their off hand when it's empty. CUT-OFF (`intercept`, normal half lead): chases where they're going from
  its own tracked `tgVel` (a player's server velocity is mostly 0). MEMORY (`Profile`, `learn`, `mimic_memory.yml` with the
  gear save): shield / bow / eat rates per player: shields a lot -> axe out first (hard+), bows a lot -> always zig-zags in.
  Godlike circle-strafes longer. Tests (scratch `nt/NewTest`; its physics stand-in now does per-axis wall sliding with a
  0.6-wide body, open doors passable, gravity even at zero speed): dodge, shield an arrow, totem swap, cut-off angle, memory,
  door open/through/shut/never chopped, swing poses; 6/6 runs.
- **Mimic: drain, mending, crits, pots, drops, combat (Items 1.2.86)**: BUCKETS are real (`pour()`: the bucket count drops when
  it pours; `TempBlock.owner`): it gets the bucket back only by scooping its own water / lava when the TempBlock expires;
  a PLAYER scooping it first with an empty bucket (`onBucket`, allowed for owned water / lava) DRAINS it: theirs, never back
  (the clutch and self-extinguish used to be free). CRITS (`canCrit`): falling and NOT in a web (whole hitbox, `inWeb`),
  water, on a ladder / vine (`Tag.CLIMBABLE`), blind, riding, or sprint-hitting (vanilla: a web resets fall distance); no crit
  jumps in webs / water. POTS: only re-pots a buff in its last 3 s or when stronger; bad effects mixed into its own potion
  (Turtle Master's Slowness) never count; lingering potions are never its buffs; re-pots only with SPACE (6+ blocks, or hard+
  right after its hit knocked them back; drinkables need 10+). ARMOUR: `m.wear` = its own copies (the kit-when-bare used to
  write into the shared gear snapshot); `onWear` (MONITOR, causes in `WEARS`) wears every piece damage/4 (min 1, Unbreaking
  chance), breaks at max (sound, attributes recomputed). MENDING (`mend`, every 10 ticks, 5+ from the target): pieces with
  Mending repair 2 a point from the copied player's XP (`Gear.xpPoints` = `getTotalExperience`, `xpPool`) then XP BOTTLES
  (`xpBottles`, thrown at its feet, 3-11 points), random damaged piece like vanilla; none = no mending. DROPS
  (`drop-inventory`, `inventory(m)`): killed = its armour as worn, weapons / tools, and every unused consumable + a player's
  XP drop (it copies a real player's gear: that duplicates items, the owner asked for it). COMBAT (`CombatTag.java`,
  `mimic-player.combat-tag`): the mimic hurting you (`combat(tg)` at every damage call; its shots / TNT via the damage
  event) or you hurting it (`onBrainHurt`) = in combat. With DONUTCOMBAT (the owner's combat plugin, softdepend; its own
  listener only tags player-vs-player) it calls DonutCombat's public `tagPlayer(Player)` by reflection = the real tag;
  without it a built-in 30 s tag (action bar, `blocked-commands` incl. DonutTPA's tpa/tpahere/tpaccept, command / plugin
  teleports over `max-teleport` 16 cancelled, pearls fine, cleared on death). DonutTPA has no combat system. Tested (scratch
  `nt/NewTest`, `-Ddonut=<DonutCombat.jar>` loads the real plugin into MockBukkit: the mimic's hit lands in its `combatLog`;
  the harness turns `pack-shield` off (mock players never finish loading the pack = no damage) and the built-in tag off
  outside its own section; physics stand-in: walls before floor, open doors are thin, no standing on them). 8/8 runs.
- **Mimic: mace techs, vanilla M1, fake join (Items 1.2.87)**: MACE: `Gear.breachMace` (a second mace with Breach; the main
  one is the best SLAM mace, `slamScore` = 2 Density + 2 Wind Burst + Breach; saved `breach-mace`, hotbar "breach").
  `smash()`: DENSITY / BREACH SWAP (normal+: a low slam (< 7) or no Density into 15+ armour points = the Breach mace), Breach
  maths `breachMult` (vanilla armour formula before/after armour x(1-0.15/level)), ATTRIBUTE SWAP (hard+: the swing's charge
  comes from the SWORD's cooldown, the mace comes out on the hit's tick). Slams: BACKSLAM (normal+ vs a shield with no axe,
  hard+ when they face it: air control aims 1.4 behind them), STUN SLAM (axe breaks the shield, then the mace). `windJump`
  (look down, wind charge at the feet, vy 1.4). `pearlCatch(m, vel, slam)`: a pearl (FX, tag `faultline_mimic_fx`) + a wind
  charge 3 ticks later = teleported there riding `vel` (fall reset); hard+: SIDEWAYS catch (12-32 away, angled at their cut-off),
  VERTICAL catch (Density mace, roof clear, vy 1.9), DOUBLE HEIGHT (godlike, 2+ charges: wind jump then the catch 5 ticks into
  the climb, vy 2.6, `catchAt`), MID-AIR catch (`airCatch`, falling 10+ with nothing to slam and no water clutch = pearl back up).
  GROUND BREACH SWAP (`breachSwap`, hard+, 15+ armour: the Breach mace on the hit tick, the sword back 2 ticks later). ELYTRA MACE
  IS GONE (the dive and the glide smash removed; elytra-pvp itself stays off). VANILLA M1 (`strike`): attack delay = 20 / attack
  speed exactly (`cooldown()` double: sword 12.5, mace 33.3, axes 20/22.2/25...), charge = (ticks + 0.5) / delay; a slot change
  resets the meter the NEXT tick (`hold` -> `tickerResetAt`, `ticker()`); crits AND sprint knockback need charge > 0.9, crits
  never while sprint-hitting; the crit x1.5 is on the attack only (Sharpness = (0.5 lvl + 0.5) x charge after it); bonus
  knockback = (Knockback + 1 for a charged sprint hit) x 0.5 along its facing (vanilla `knockback()`: half the old speed + dir,
  y min 0.4 on the ground, x KB resistance), then its own speed x0.6 (the base 0.4 comes from the hit). FAKE JOIN
  (`mimic-player.fake-join` / `tab-list`, both true): mimicking an OFFLINE player broadcasts the yellow translatable
  "multiplayer.player.joined" (`announce`: every player + console) and adds a tab-list line (`MimicTab`: the server's own
  ClientboundPlayerInfoUpdatePacket by reflection (Paper is Mojang-mapped): Entry record filled by type, a random UUID, their
  name + skin textures, ping 25-95; new joiners get it on `PlayerJoinEvent`; the real player logging in removes it); removed =
  "left the game" + ClientboundPlayerInfoRemovePacket; killed = a vanilla death line ("death.attack.player") then it leaves 2 s
  later. Mimicking an online player does none of it. Reflection failing = one warning, no tab line (UNTESTED on the live server:
  MockBukkit has no NMS). Tested (scratch `nt/NewTest`).
- **Mimic: the walker + reach techs (Items 1.2.88)**: `MimicNav.java` = A* over the block grid with a player's moves (a node =
  the cell its feet stand in; cells cached per search, unloaded chunks = walls): WALK (8 ways, no corner cutting), STEP (1 up:
  blocks, stairs, slabs; half-steps under a low ceiling need no head room), DROP (off a ledge as far as `maxDrop`: health - 3
  hearts, min 3, max 23, 30 with a water bucket; water landings any height), DOOR (closed wooden doors / gates: +1, opened on
  the way; iron = wall), DIG (feet + head with the right tool, dirt/sand/leaves by hand; player-built blocks cost x4 + 6 so it
  takes the door; never next to water/lava, never block entities), DIG up a step, DIGDOWN (mines its floor), PILLAR (tier 2+,
  blocks), CLIMB (ladders/vines), SWIM. Budget `mimic-player.path-nodes` 2500, range 96; no full way = the partial path that
  gets closest (`navReach` false). `direct()` = about level (|dy| <= 1.2) and `walkLine` clear (same floor, room for the
  body, no hazard / drop of 2+), or within 3.2 and seen: then the old fight / straight chase; anything else (above / below
  them, a wall, a door, stairs) = `navChase` (no more strafing on a ledge above you). Replans when they move 3 blocks (1.5
  close), every 2 s, when stuck. `followPath`: skips ahead to any of the next 5 steps it's in, string-pulls level WALK steps
  (`walkLine`), jumps STEPs, sprint-jumps long runs (`openAir`), slows for drops / doors, air-strafes, mines `dig` blocks first
  (`navDig`, the old digTick), opens doors, centres for DIGDOWN / PILLAR. STUCK (no closer for 25 ticks): out of a wall if in one,
  that edge penalised 40 for 10 s (`navBad`), a hop, replan; 4 in a row = vanilla's pathfinder for 3 s. IN A WALL anywhere
  (`inWall`: feet/head point inside a block's box) 8 ticks = `unwall` to the nearest standable cell 2 round. `towerUp` only
  when the walker has no full path. REACH TECHS (`reachTech`, no full path): WIND HOP (ledge 1.8-7 up within 6, no roof:
  a wind charge, steers onto the ledge on the way down, `hopUntil`), WATER CLIMB (`waterClimb`: from a cell open overhead and
  right beside the edge of their floor (`edgeBeside`); a water TempBlock in its head cell, swims up held on the column, picks
  it up and re-places it above its head, steps onto their floor at the top, scoops the last one back; under their floor /
  out in the open = first `stageSpot` (nearest such cell 6 round, planned to exactly)), PEARL (sees them, 5-60), WIND CHARGE +
  PEARL (`pearlCatch` over a wall, 4-22). BREEZE RODS: counted in the gear (`rods`, saved), out of wind charges = crafts one
  rod into 4 (1 s), dropped on death. REACH FIX: hits are judged from the brain AND the body players see (`reachOf`, max of
  both, 3.0; spear 3.5), the mace slam lost its +0.5, and the body snaps to the brain past 0.6 (was 1.2: the body trailed,
  so hits looked like 4 blocks). Tested (scratch `nt/NewTest`: house door (walls intact), stairs up 6, drop off a 6 ledge,
  dig down through rock (glass walls kept), out of a wall, breeze rods, wind hop, water climb from a wall and from under the
  floor, pearl; 8/8 runs).
- **Mimic walker 2 (Items 1.2.89)**: `MimicNav` new moves: GAP (parkour: a sprint-jump over 1-3 air cells to a standable
  cell level, 1 up (gap <= 2) or 1 down; needs head room; `followPath` runs to the edge of its cell (0.15 past the centre),
  jumps 0.36 + 0.42, then holds 0.3 b/t at the landing in the air: `gapUntil`/`gapStep`) and BRIDGE (`canBridge` = blocks +
  world techs: a block placed under the next cell over a gap, water or lava, `replaceable`; it creeps to the edge, stops,
  `bridgeBlock` = a TempBlock into air/water/lava, put back after 30 s). DROPS are judged by fall DAMAGE (`maxFallDamage`:
  health - 6, 40 with a water bucket; `fallDamage` = (k - 3) x 0 on slime / powder snow / cobweb / water, 0.2 hay / honey, 0.5
  beds). TERRAIN cost (`terrain`): soul sand / honey +1.5, slime +0.8. Sprint-swimming (0.2). PATH BUDGET: all mimics share
  `mimic-player.path-budget-per-tick` 8000 nodes a tick (`budgetUsed`; over it = keeps its old path a tick). FLEE uses the
  walker (`navFlee`: a standable spot ~18 away, turned up to 90 degrees off straight-away, replanned every 2 s) when the
  straight run isn't a clear `walkLine`. Can't reach them at all and no movement tech = `farShot`: a WIND CHARGE at them (a real
  WindCharge entity; knocks them off a pillar), the ROD (hook, 8 ticks later reel: + 0.1 x the gap), the BOW, snowballs.
  Tested (scratch `nt/NewTest`: 3-wide parkour gap with nothing to bridge, a 6-wide gap bridged (3 blocks, then a jump), wind
  charges + rod at Ben up a 10-high pillar; 8/8 runs).
- **Mimic 1.2.90 (Items): combo techs, pearls, ladders, cover-up, the hunt**: COMBO (`comboTech`, before the normal swing):
  D-TAP (hard+: they're <= 7 hearts holding a totem, spear + mace/sword: spear hit, 2 ticks later the other with their
  i-frames reset), SPEAR STUN SLAM (shield up, in `spearReach()` = `mimic-player.spear-reach` 4.5: shield on cooldown 100, then
  windJump slam / axe), BACKSTAB (shield up, 2.5-7, Lunge spear: lunges to 1.3 behind them), WIND CHARGE CANCEL (they're
  dropping on it / jumping in for a crit: `windAt` their feet), GRAPPLE (rod + mace: reel 0.1 x gap, then windJump slam), SPEAR
  REACH SWAP (3.0-4.5: spear out, hard+ swaps to the sword/axe for the hit). SPEAR DASH (`spearDash`, long clear run 7-30) +
  LUNGE SWAP (`lungeSwap`; `lungeTicks` = the server's `pvp-limits.lunge-cooldown-seconds`, min 10 / 50 below hard). TRIPLE SWAP
  (godlike + spear: slam reach = spear reach). WIND AIR (`windAir`): MOMENTUM BREAK (falling past its safe height, ground
  within 2, no water: charge at the ground, vy 0.55, fall reset) + WIND RESET (godlike, far target: back up toward them).
  WIND CHARGE BLOCK (`dodge`: a charge knocks an incoming projectile back). SWEEP (vanilla, only when someone's within 1 of
  its target: standing, not sprinting, charge > 0.848, sword; 1 + dmg x Sweeping Edge ratio; `sweepTargets`; the old
  "no sweeps" rule is gone). S-TAP (hard+ backs off; W-tap below), S-CRIT (`scritAt`: hit = hops back, crits them coming in),
  UPPERCUT (hits at its jump peak). ELYTRA DIVE BOMB (`mimic-player.elytra-mace` false: `startBomb`/`bombTick`). PEARLS:
  `solvePearl` = vanilla projectile physics (move, x0.99, -0.03) simulated per pitch -45..80 with block hits (`pearlLands`);
  chasing throws only land within 2.5 (`throwPearl(m, to, exact)`); cooldown = `pvp-limits.ender-pearl-cooldown-seconds`
  (`pearlTicks`, was 15 s); PEARL FOLLOW for normal+ (`followPearl`, early in think, waits up to 3 s for its pearl, skips
  walkable ones). LADDERS: full steering on climbables (was weak air control = stuck at the top), CLIMB steps off toward
  the next step, hits while climbing / in water. STUCK / TRAPPED (`escape`): pearl to them (seen) > mine out toward them
  (`navDig`, a staircase if they're above) > pearl out to open ground; trapped (`trapped`: < 8 walkable cells 4 round) and
  hit = `escapeAt`. SWIMMING FIGHT (`swimAt`: 3D at their chest 0.2, space up / dive down, climbs onto the bank). STUCK SWING
  POSE: `follow` restores the real item when a pose item outlives `poseUntil`. SWINGS: the pose item goes in hand, the use
  starts a tick later (the client used the OLD item when both came in one tick) and is held longer (7 ticks). RUNNING
  (`runTricks`): BLOCK TRAIL (blocks behind it, feet then head, never in a body: `occupied`), WIND DASH (hard+). COVER UP
  (`startCover`/`coverTick`/`openCover`: low with food, them within 6, blocks: 4 sides x 2 + roof, their side first; eats
  inside; opens their side when healed / breached). HEAL RUN (`healRun`: food but nothing to make room: runs until 6+ off,
  then eats). TAB + TAG: the tab line has "[Kingdom n] Name" / "Outlaw Name" like FaultlineKingdoms (`tabName`, the
  UPDATE_DISPLAY_NAME action through `PaperAdventure.asVanilla`); the skin arrives later for uncached offline profiles, so the
  entry is re-sent once `update()` completes; the head tag is rebuilt every 2 s if gone and its text refreshed; a failure to
  read FaultlineKingdoms is logged once ("[Mimic] couldn't read FaultlineKingdoms..."). THE HUNT (`/mimicplayer hunt <player>
  [mimic]`, `hunt` alone = where players hang out, `hunt stop`): `MimicHunt.java` (data `mimic_world.yml`) samples every
  minute each survival player's world + 64-block cell (decays 0.999 a minute; `favouriteWorld`, `hotspot`) and records every
  `PlayerPortalEvent` (the portal block + where they came out; `portalTo`, `anyPortalTo`); `findEndPortal` = locateNearestStructure
  STRONGHOLD + async chunk snapshots for an END_PORTAL_FRAME (cached). Hunting: any range/world; within 64 = the normal
  fight; else `travel`: other world = `toWorld` (a recorded portal; the End's stronghold portal: `endPortalInside` fills
  empty frames with its Eyes of Ender (`eyesLeft`, counted from ENDER_EYE) and lights it; the End's exit fountain; walks in,
  60 ticks in the portal with no vanilla trip = teleported to where players came out, the End's platform built if missing);
  same world: elytra (`flyTo`: cruise ~30 over the ground, rockets only above `hunt.keep-rockets` 4) over `hunt.fly-over` 150,
  else 40-block legs on the walker (`navGoTo`, pearls over blockers only above `hunt.keep-pearls` 4); underground = `digDown`
  (centred, mines below, a block over its head each drop, never into lava/water). Plugin chunk tickets 7x7 round it
  (`keepLoaded`, `hunt.load-radius` 3) so it ticks with no one near, released on stop/remove/death. digTick runs while
  travelling too. Tested (scratch `nt/NewTest`: pearl solver flat / pillar / over a wall, normal pearl follow, ladder, boxed-in
  mining out, reach swap vs none, D-tap, wind block, sweep, momentum break, spear dash, dive bomb, cover up + eat, block
  trail, heal run, swimming up, hunt at 300, activity world, recorded portal to another world, dig down to a buried end
  portal + sealing + into the End, elytra travel keeping 4 rockets; 8/8 runs).
- **Mimic hits like a player (Items 1.2.91)**: the game scales damage from MOBS by difficulty before plugins see it (x1.5 on
  Hard, min(d/2 + 1, d) on Easy), so on Hard every mimic hit was 1.5x a player's ("his strength got buffed").
  `onMimicHitsPlayer` (EntityDamageByEntityEvent LOWEST; damager = its brain, a projectile it shot, its TNT) undoes it
  (`undoDifficulty`; `mimic-player.player-damage` true). `/mimicplayer debug` (per admin, `hitLog`): every mimic hit on a
  player in chat: the raw number, the scaling undone, how it was made (`lastHitInfo`: weapon base, Strength, charge, crit,
  Sharpness, sprint hit, reach; mace: fall, Density, Breach) and what they lost after armour. Tested (scratch `nt/NewTest`).
- **Boss enchant caps (Items 1.2.92, Index 1.9.1)**: an item holds at most `enchants.max-per-weapon` 2 different boss
  enchants (swords, axes, tridents, bows, pickaxes) and `enchants.max-per-armor` 1 (helmets, chestplates, leggings, boots);
  0 = no limit. `Enchants.combine` refuses a NEW enchant past the cap (upgrading one it has still works; the anvil shows
  "already has n/n" in the action bar). Gear over the cap (`trim`: keeps the highest levels, stable order) is fixed by
  `sweep` 2 s after join, every 5 s, and when any inventory opens (+ the opened real container / ender chest, bags to the
  opener): one Dark Loot Bag (`DarkLoot.bag()`) per enchant removed (x stack size), a chat line, "[Enchants] trimmed"
  in the log. `enchants.enforce-caps: false` turns the sweep off. Tested (scratch `nt/NewTest`).
- **Mimic: running, spectating, travel, mining (Items 1.2.93)**: RUNNING AWAY (`navFlee`/`fleeSpots`): 16 ways x 14/22 out,
  standable spots scored by distance from them (never toward / past them), out of their sight there (`clearSight`, a block
  walk: +12), openness (`openness`, 7x7 standable columns; < 14 = dead end -25), lower ground, keeping its pick; top 3
  planned, a path passing within 2.5 of them thrown out; replanned every 1.5 s / when cut off. Cornered (wall, nowhere
  open, them within 3.5) = turns and fights. `fleeTricks`: pearl out when they catch up (`pearlOut` = a pearl that really
  lands on the best flee spot 12+ off, else straight away), jukes (hard+), a cobweb behind (normal+). Faces where it runs
  (`runYaw`) with a glance back every 2 s. EATING: only with room (9+ off, or 6+ out of sight, not hit for 1 s); low with
  food and pearls within 9 = pearl out first; HIT WHILE EATING (or them within 2.8, normal+) = stops (keeps the apple) and
  pearls out / runs. TRAVEL faces its way (`runYaw`; it used to look at random `idleYaw`s) and never `idle()`s mid-trip.
  ELYTRA: `hunt.fly-over` 48 (old 150 reads 48), `keep-rockets` 2 (old 4 reads 2), takes off with a wind charge and glides
  when it has no spare rockets (not in the End: needs a rocket), looks 48 ahead for terrain (`solidSurface`, climbs before
  a hill, pulls up at a wall), glides without rockets (no climbing, trades height for speed), lands on a dry spot picked once
  (`landSpot`) within 32; over the VOID (End) never below its destination + 12 and spends a reserve rocket rather than sink;
  in a fight it flies to anyone 32+ off or across a void (`voidBetween`). The Nether: no elytra. Walking: `goLeg`/`legGoal`
  (9 headings 32 out: dry, no lava, under trees (`MOTION_BLOCKING_NO_LEAVES`), climb costs; stuck 8 s = legs turned
  60/-60/120/-120 + a pearl ahead); UNDERGROUND (`underground` = 7+ blocks over it) legs stay at its level working toward the
  goal's height (a tunnel, not a staircase to the surface); `travelLeap` = pearl hops (pearls > keep + 2) and wind leaps
  (charges > 2). PORTALS: `toWorld(m, world, dest)` takes the portal its quarry last used (`MimicHunt.lastUsed`), else
  `bestPortal` (walk to it + walk from its exit to dest); NETHER SHORTCUT: an Overworld trip over `hunt.nether-over` 500
  goes via recorded portals when that's 30%+ shorter (`viaNether`). STRONGHOLDS: `findEndPortal` scans 11x11 chunks
  (`scanAround`), heads to the located stronghold (`strongholdCenter`) while searching and rescans round itself within 80.
  `/mimicplayer hunt <target> <mimic> [skill]` SPAWNS that mimic when none is out (the owner hit "No mimic of X out").
  SPECTATE (`MimicWatch.java`): `/mimicplayer spectate [name|#n|next|stop]` = spectator camera on its body (re-attached /
  teleported when it goes far or to another world; sneak = free cam kept within 64), action bar = name, skill, health,
  `doing(m)`, world/xyz; stop / quit / last mimic gone = back where you were in your old mode (PDC `faultlineitems:mimic_watch`,
  restored on join after a crash); changing game mode yourself ends it in place. MINING: `breakTicks` = vanilla (tool speed +
  Efficiency, Haste, Fatigue, /5 underwater without Aqua Affinity, /5 in the air; /100 rule = no tool for stone = can't),
  recomputed every tick as `digProgress`; reach 4.6 from the eyes (was 3.5 from the feet: it gave up on head blocks); crack
  animation (`sendBlockDamage`), looks at the block, stands still; never mines next to lava / water (all 6 sides, live and in
  the planner); gravity blocks stacked over a cell add their cost; new DIG-DOWN-A-STEP move (stairs down, not pits).
  Tested (scratch `nt/NewTest`).
- **Mimic fights (Items 1.2.94)**: a mimic's opponent is any `LivingEntity` now (`Mimic.target`, every `tg` param): a player or
  another mimic's BRAIN. What it reads off a mimic foe goes through helpers that map the brain to its `Mimic` (`mimicOf`):
  `blocking` (its shield up), `sprinting`, `mainHand`/`offHand` (the body's equipment), `usingNow` (eating / drinking /
  drawing / loading), `breakShield` (an axe knocks a mimic's shield out: `shieldOffUntil`), `attackCharge`, `facing` (the body's
  look), `nameOf`; `combat(LivingEntity)` only tags players; `learn`/`Profile` only remember real players. Teams: `Mimic.team`
  (0 = not fighting) + `fightId`; `foeOrNearest` picks the nearest living mimic of another team in the same fight (players are
  ignored, and a player hitting a fighter doesn't turn it), else ends the fight; `fightDeath` (from `onDeath`) / `endFight`
  announce "[Mimic fight] X wins!" to admins + players within 96 and clear the teams. A mimic's death line names the mimic that
  killed it (`lastHitBy`). Mimic foes' pearls are followed (`onPearl`), their shots dodged (`onShot`), `onTarget` allows mimic
  foes. Commands: `/mimicplayer duel <player> <player> [skill] [skill]` (spawns both 10 apart where you look),
  `fight <mimic> <mimic>`, `fight <a,b> vs <c,d>` (teams), `ffa` / `fight ffa` (every mimic within 64 of you), `fight stop`.
  Tested (scratch `nt/NewTest`: duel to the death, bystander untouched, ffa teams, stop).
- **Mimic flies, swims and swings like a player (Items 1.2.95)**: PLAYER BODY (`MimicBody.java`, `mimic-player.player-body`
  true, `player-body-range` 96): every viewer in range gets a real PLAYER entity (ClientboundAddEntityPacket by reflection,
  Mojang names) that USES THE MANNEQUIN'S ENTITY ID, with its own unlisted player-info entry (`MimicTab.add(..., listed)`,
  `m.bodyId`); the mannequin is `hideEntity`'d for them. Mirrored every tick: position (`ClientboundEntityPositionSyncPacket.of`),
  head, equipment (every 4 ticks when changed), entity data = the mannequin's values limited to accessor ids of Entity /
  LivingEntity / Avatar (a mannequin-only id would break the client) + shared flags / pose from `Look` (sprint, crouch =
  shield, swim, elytra glide, riptide). `swing()` = the real animate packet (the trident/spear pose hack is skipped), hurt
  flash on hits/death. Any reflection failure = one warning and mannequins again (`fail`). Hits: a click on it is a click on
  the mannequin; SAFETY NET `onSwingAtBody` (an ARM_SWING by a viewer with no PrePlayerAttackEntityEvent on a mimic that tick,
  look ray on the body's box within ENTITY_INTERACTION_RANGE, no block in front) = `Player#attack(body)`. UNTESTED ON A LIVE
  SERVER (MockBukkit has no NMS: there it falls back). ELYTRA (`flyTo` rewritten): jump, opens the elytra falling, rocket;
  in flight only pitch / yaw (`flyPitch`/`flyYaw`, eased 5 / 8 degrees a tick, the body looks that way) and vanilla's
  fall-flying maths each tick (`glideStep`: lift cos^2, dive -> speed, pull-up -> height, x0.99/0.98, firework boost
  `look*0.1 + (look*1.5 - v)*0.5` for 22-30 ticks, `elytraRocket`), blocked axes zeroed from the real movement; pilot: cruise
  26 over the ground ahead, zoom-climb on speed, dive when high, rocket when slow (reserve kept), pull up at walls; landing =
  glide slope onto `landSpot`, steep spiral when right over it, FLARE (-25) under 8 blocks when fast. WATER: `swimVel` swims
  toward a depth (`swimToY` from goLeg / the path step) instead of always rising, `needAir` surfaces at a third of its air
  (not with Water Breathing / Conduit Power); followPath's land-step swim goes up OR down; `legGoal` has an UNDER WATER branch
  (the heightmap counts water as the top, so it aimed at the surface over its quarry: now water / floor cells at the depth it
  wants, `wetAt`). LAG: `MimicNav` cells are one shared primitive long->byte table per world for 10 ticks (`Cells`, `CELLS`,
  `dirty(w)` on its own block changes, doors and block place/break/bucket/explosion/piston events; was a boxed HashMap per
  search, every block read again); `getBlockData` read once per cell; pearl solver coarse 5 degrees + 1-degree refine round the
  two best, stops once below both ends - 6, one block read per cell (`pearlLands`), a failed exact throw isn't re-solved for
  10 ticks (`pearlSolveAt`), a failed pearl hop for 30 (`leapTryAt`); goLeg reuses its leg goal for 10 ticks; Krampus /
  Haunted Villages check their timer before the date. Profiled (scratch `nt/ProfMimic`, 6 mimics): pearl solving 50% -> 3%,
  planning 40% -> ~18-35%, total ~1.35-1.74 -> ~0.9 ms/tick in MockBukkit. Tested (scratch `nt/NewTest`: jump takeoff, glide
  physics, pitch range, speed changes, lands near Ben slowly, never in a block, dives in a tank toward a lower target; the
  harness clears the cell cache every tick since setType fires no events; 6/6 runs).
- **Mimic walks like a player (Items 1.2.96)**: LOCOMOTION on vanilla's own physics (`stride`): the server already applies
  friction / soul sand / honey to the mob, so each tick only ADDS a player's input, at most `a` = sp x 0.454 x 0.216 / f^3
  (f = `friction()` under its feet: ice / packed 0.98, blue ice 0.989, slime 0.8, else 0.6), steering the carried speed toward
  `want` (slowing and turning cost the same input): it accelerates over ~5 ticks to `sp`, keeps momentum, slides on ice. (The
  old `cur*0.4 + dir*sp*0.9` snapped to speed and ran ~15% faster than a real sprint.) `jumpVel` = 0.42 (+ Jump Boost) on top of
  its speed, sprint-jump +0.2; SPRINT-JUMPS every landing on an open straight once up to speed, and HEAD-HITS under a 2-high
  ceiling (`headHitter`). AIR (`airStrafe`): only 0.026 a tick of control, no braking; `landsSafe` simulates the fall (drag
  0.91, gravity) and heading for lava / a 6+ drop / the void it steers toward the safe side (knocked off a ledge). AVOID
  (`avoid`, `getNearbyEntities` 1.8): people / mobs ahead push its heading aside (not its target, nor anything within 1.5 of
  the target, nor invisible hitboxes). PATHS: pure pursuit over the next 8 level WALK steps (`aimIdx`), eases off before a
  corner sharper than 60 degrees, brakes instead of carrying off an edge on a WALK step (never on a planned DROP). MimicNav
  EDGE COSTS (`edge`, cached in `Cells.edges`): a cell beside a 2+ drop +0.35, beside lava / fire +1.2 (max 2.4), only within
  20 blocks of the start (so it walks down the middle of a bridge). Melee spacing that would hang a corner over an edge
  steps in / out without the strafe and flips it. ELYTRA LANDING: approach starts earlier the faster it is (`speed * 18`),
  AIR-BRAKE (pitch -28/-12 when coming in fast within 10 above the spot), never sinks below the spot short of it, a rocket
  back up when it overshot / sank over nothing, harder turn when behind. BUG: `landsSafe` read below the world on long falls
  (threw = the mimic's tick died). Profiled: ~12% over 1.2.95 in the all-ledges test world. Tested (scratch `nt/NewTest`:
  accel / top speed / ice / jump numbers, head-hitter, air recovery off a lava ledge, bridge middle 17/17, avoid; scratch
  `nt/Focus` for the ladder / momentum break / void landing 10/10).
- **Mimic: never vanishes, vanilla placing, mace dodge, teams (Items 1.2.97)**: VANISHING: an error in `think` no longer removes the
  mimic (logged, its plan reset; only 100 errors in a row remove it); its chunks keep a 3x3 plugin ticket even when not hunting;
  VOID SAVE (`voidSave`: falling with nothing under it to the bottom = elytra + rocket back to `lastGround`, else a pearl there);
  viewers get the player body re-sent on respawn / world change / join (`resendBody`). M1: the jar's manifest says
  `paperweight-mappings-namespace: mojang` (pom: maven-jar-plugin), so Paper doesn't remap the Mojang names `MimicBody` reflects on;
  `/mimicplayer body` = is the player body running, and if not why (`MimicBody.why()`). PLACING (`supported`): every block it places
  (`place`, `bridgeBlock`) must touch a non-fluid block on one of its 6 faces like a player's (no more floating trail / cover blocks);
  buckets also go on top of their own water column (`pourOver`) and the climb's first pour may be a waterfall from a ledge in
  reach; a cell it mined itself can be built back into (still restored later); at the wall's top it jumps out onto the ledge. POTS
  only from the ground / water (a pot thrown mid-air on a slam fell far below it): buffs, heals, the pot run and the drink-splash
  wait to land. REACH: `reachOf` also measures from the body's eye over the last 3 ticks (`seenEye`: what a viewer still saw).
  ANTI-MACE (`antiMace`, normal+): someone holding / carrying a mace 2.2+ above it in the air = it works out where they'll land
  (drift `tgVel` + fall time from `tgVy`) and sprints out sideways past 5.5; right before the slam: shield up, else a pearl away.
  ESCAPE PEARLS ARE FLAT (`flatPearl`: `pearlOut`/`pearlAway` solve only pitches up to 20 degrees, fallback lift 0.3; a lob hung it
  in the air for free hits). TEAMED (`outnumbered`, normal+, not in mimic fights; `hitBy` = who hit it in the last 5 s within 16):
  focuses the weakest one in reach, webs the second one coming in, runs out sideways when sandwiched (they come from one side),
  pearls out with 3 on it or 2 and under 40%. Staircases: `digTick` no longer digs the floor of a planned stair step. Lag: item PDC
  reads use Paper's read-only view (46 places), MaceEnchants reads enchants before copying the meta, MimicNav caches edge costs.
  Tested (scratch `nt/NewTest` 6/6; `nt/Focus` cliff staircase). Anti-mace and team tactics have no MockBukkit test (no projectile
  flight / player physics there).
- **Mimic: the void, the slam, log boxes (Items 1.2.98)**: the VOID: `voidColumn` (nothing solid / water under a spot to
  the bottom; cached per (world, x, y, z) for 20 ticks in `VOID_CACHE`), `voidDir(l, r)` (which way the void is, 24 samples),
  `voidScan` every 10 ticks (`voidMe` / `voidTg`). No mace launch (wind-charge slam, pearl catches, stun slam, grapple, reach
  tech pearl catch) when either of them is within 4 of the void (`voidRisk`); a slam whose air steering would land in the void
  (`landsAtAll`: fall sim, 200 ticks) steers home to `lastGround` instead. EDGE ESCAPE (`edgeEscape`, every skill, first in
  the fight): void within 3.5 = walks away from it (round them if they're on the safe side); mid-jump there = no new jumps, air
  control inward. VOID GUARD (`voidGuard`, start of `follow`, every tick, whatever moved it): a ground step or a jump that comes
  down in the void is cancelled, also a jump that left the ground this tick; falling over nothing = air control home. The
  straight run-away bends away from the void. Void save: an elytra with no rockets still glides home when within 30.
  `MimicNav.edge0`: a cell beside 32+ blocks of nothing costs +1.6 more (paths keep off the End's edges). SLAM HITS: `reachOf`
  takes the CLOSEST of the body's last 3 eyes (1.2.97 took the furthest: a fast slam was "out of reach" and phased through), and a
  slam that lands on the tick it reaches them still hits (`slamFall`). SIGHT: `sees()` = hasLineOfSight AND a block walk
  (`clearSight` now stops at any solid block = vanilla's collision-shape sight; isOccluding missed logs), so `direct()` (also
  needs reach or a walkable line now) no longer sends it to fight a player through a wall: the walker mines in (log box test:
  first log at tick 17-30). Tested: scratch `nt/VoidTest` (two godlike-kit mace mimics on a 15x15 island over a cleared void,
  run one round per JVM with `-Donly=k -Drounds=k+1`: 18/18 rounds no fall, was 4/4 falling; `-Dbox=true` = the log box),
  `nt/NewTest` 6 runs (known flakes only). The VoidTest physics stand-in checks the hitbox corners and doesn't tunnel through
  thin floors at speed.
- **Offline teleport + the walker, part 3 (Items 1.2.99)**: `/offtp <player> [last|bed|death]` (`/otp`, `/tpoffline`;
  `Items/OfflineTp.java`, perm `items.offlinetp` op): an offline player's LOGOUT spot (our own record from PlayerQuitEvent in
  `offline_spots.yml`: name, world, xyz, look, time; else Paper's `OfflinePlayer#getLocation()` from their player file), their
  respawn point (`getRespawnLocation`) or last death (`getLastDeathLocation`); online = their position. Names: online, then our
  records, then `getOfflinePlayerIfCached` (never a blocking web lookup); tab-completes recorded names; `teleportAsync` (plain
  teleport where that's missing); logged "[OfflineTp]". WALKER (`MimicNav` + `followPath`): rises of 0.6 or less (bottom slabs,
  stairs, slab onto a block: `stepTop` = the floor block's bounding box top) are WALKED up like vanilla step height (no jump;
  STEP onto a `halfStep` costs 0.15, was 0.6); DIAGONALS cut a single corner (one side open feet + head is enough, +0.25; never
  past a hazard on either corner); TERRAIN: ice -0.3 (faster), wading (feet in water, head out) +0.7; PATH CHECK (`pathBlocked`,
  every 5 ticks): the next 5 plain steps (WALK/STEP/DROP/GAP with no dig/door) must still be standable, else it replans at once
  (a block placed in its way, a door shut) instead of waiting to get stuck. MockBukkit has no step height, so the no-jump stairs
  are untested there. Tested (scratch `nt/VoidTest -Dofftp=true`: logout spot, unknown name, tab, non-op; `nt/NewTest` 6 runs,
  void rounds, the log box).
- **Map mods, pie chart mods, the pie chart limit (Items 1.3.0)**: `Items/ModGuard.java`. MAP MODS (`mod-guard:`): on join
  every Java player gets Xaero's switch-off codes as literal-text chat lines (`codes`: `&n&o&m&i&n&i&m&a&p` = no minimap,
  `&f&a&i&r&x&a&e&r&o` = fair play; `&` swapped for the section sign BY HAND: Bukkit's colour translation skips `&i`, which
  broke the code) and in the server-list MOTD (`motd-codes`, PaperServerListPingEvent). Any other map mod the client
  announces (a plugin channel via PlayerRegisterChannelEvent + `getListeningPluginChannels` 2 s / 10 s after join, or
  `getClientBrandName`) matching `map-patterns` (world/journey/voxel/mini map, ftbchunks, mapfrontiers, antique atlas, ANY
  "...map" / "...atlas" name = homemade ones, radar) and not `map-allow` (Xaero's Minimap; keymap / bitmap / lightmap...)
  = `map-action` kick (Xaero's World Map has no known off-code, so it's kicked). PIE CHART MODS: `pie-patterns`
  (pie chart / pie ray / pie menu) = `pie-action` kick. A mod that registers no channel can't be seen. Bypass
  `faultline.modguard.bypass` (false). The Xaero codes are from memory (the docs site was blocked here): edit `codes` if
  wrong. PIE CHART LIMIT (`pie-limit:`): every chunk SENT to a Java player (Paper `PlayerChunkLoadEvent`, its block entities
  via `getTileEntities(predicate, false)`) has its drawn block entities (`DEFAULT_HIDE`: chests, trapped chests, shulkers,
  beds, banners, signs, heads, spawners, enchanting tables, beacons, bells, conduits, campfires,
  lecterns, pots, suspicious sand/gravel, copper golem statues, shelves; NEVER the ender chest, and since 1.3.1 never TRIAL
  SPAWNERS / VAULTS: players need to find trial chambers; removed even from a server config's own `hide` list) replaced client-side
  (`sendMultiBlockChange`, the tick after) by the wall round it (all 6 sides occluding) else air / water; revealed
  (`show`: real block + `sendBlockUpdate` for its data) within `touch-distance` 6, or within `reveal-distance` 24 when a
  block walk from the eyes reaches it (`seesBlock`); faked again past `rehide-distance` 32. Ticked every 5 ticks, at most
  `checks-per-player` 96 sight checks. New blocks placed are hidden from far players. Bedrock / creative / spectator /
  `faultline.piechart.bypass` skip it (creative turns everything real again). Entities (item frames, chest minecarts) still
  show in the pie chart. Tested (scratch `nt/VoidTest -Dmods=true`: codes intact, minimap allowed, JourneyMap / World Map /
  pie / homemade map kicked, ordinary mods kept, buried chest + trial spawner hidden, ender chest + open chest not, reveal
  on approach, rehide; `nt/NewTest` 4/4).
- **The pie chart limit really works (Items 1.3.2)**: on the live server the F3 pie chart still listed chests, spawners and
  signs. Fixed in `ModGuard`: creative / spectator players are limited too (only Bedrock and `faultline.piechart.bypass`
  skip it; an op given `*` by a permissions plugin HAS that bypass); the chunk is faked right in `PlayerChunkLoadEvent` and
  again 3 ticks later (no `isChunkSent` gate any more); `sweep(p)` fakes everything already sent within the player's send view
  distance the first time the limit is on for them (joined before the update, a /reload, losing the bypass; `armed` set,
  cleared by `forget`); a block that was faked and may now be seen is shown again in `hideNow`; one player's error no longer
  stops the 5-tick pass for everyone (`tickOne`, one warning "[PieLimit] tick failed"). `may()` never touches
  `Block.getLocation()` (MockBukkit returns the block's own Location: `add` moved the block). Admin `/pielimit [player]`
  (`items.pielimit`, op): ON + how many are hidden, or why it's off (config, Bedrock, the bypass permission). Tested (scratch
  `nt/VoidTest -Dmods=true`: + creative player swept with no chunk events, /pielimit).
- **The hourly whirlpool, the Kraken's back, Frostbite nerf (Deep 1.16.0, Bosses 2.0.2, Items 1.3.3, Index 1.9.2)**:
  LUCKI AND SWARM ARE GONE (`Swarm.java` deleted; leftover `faultline_lucki*` entities removed on chunk load; `/deep set`
  only takes `kingdom` = where the Mirror brings you up). `Journey.java` (`whirlpool:` config, `whirlpool.yml` = `next-at`):
  every `every-minutes` 60 (first `first-after-minutes` 10 after the very first start) a WHIRLPOOL opens in the surface world
  (`whirlpool.world`, else the first Overworld) `min/max-distance` 150-450 from a random survival player (`tryAt`: 16 tries,
  3x3 chunks loaded/generated async via `chunkAsync`; `openSea`: an ocean biome, water at sea level - 1 with air above,
  `min-depth` 10, water all round the rim r + 3); nowhere = `retry-minutes` 5. `Bukkit.broadcastMessage` "A whirlpool has
  opened ... at X, Z" (the Index's Discord forwards `(?i)whirlpool has opened`) + per player distance/direction (`away`),
  a title, an action-bar arrow within `pointer-distance` 400 (`arrow`), "closes in 1 minute". Open `open-minutes` 10, plugin
  chunk tickets 3x3. Swim into the eye (`Vortex.atEye`, only once the funnel is >= 5 deep) = `send` to the Dark Ocean 0 300 0;
  boats are dragged in and throw their rider out at the funnel; the same player can go again 10 s after (`sent` map).
  `needs-jacob` (false) = the old Diamond Jacob gate. `/whirlpool` (anyone: where / next), admins (`faultlinedeep.whirlpool`):
  `here` (where you stand in the sea), `random`, `at <x> <z> [world]`, `stop`, `next <minutes>`; also `/deep whirlpool ...`.
  Admin ones don't move the hourly timer. One at a time; a tick error is logged once, 100 in a row close it.
  THE MIRROR: on the surface = the whirlpool's coordinates / minutes to the next; in the Dark Ocean (5 s) = up to
  `journey.kingdom`, else the player's bed, else the surface world's spawn. `Vortex.java` (radius 11, depth 18): THE BEAM =
  fake blocks (`beam`: LIGHT_BLUE_STAINED_GLASS in the sea floor under the eye, a BEACON under it, 3x3 IRON_BLOCK under that,
  sent with the funnel to everyone who has the middle chunk (`isChunkSent`) and re-sent every 2 s) so clients draw a real
  light-blue beacon beam into the sky, plus long-distance dust (`force` particles, `beam-height` 160) for Bedrock; DEBRIS
  (`debris` 14 BlockDisplays tag `faultline_whirlpool` + `faultline_no_bedrock_fx`: planks, logs, barrels, chests, kelp,
  coral spiralling down the walls faster near the eye, swallowed and respawned at the rim); THE EYE (black disc, cyan glow
  outline, turning); five teal spiral arms, foam rim, glow spray ring; THE SOUND (layered: whirlpool churn, whirlpool-inside
  near the eye, rain = rushing water, bucket gulps, rim splashes, conduit hum, elder guardian groan; 40-200 blocks away a
  distant roar played from its direction). BOSSES 2.0.2: the NORMAL Kraken again (`kraken.overworld` true, `krakenHere`): a
  Bait Worm in an Overworld Deep Ocean (`deepOcean`, `min-depth` 15) or `/kraken summon` in Overworld water; the Dark Ocean
  still makes the Abyssal one. Index entry "The Kraken" (`faultline_kraken`) back, Bait Worm / Kraken Eel / Mirror / guide
  text updated. ITEMS 1.3.3 FROSTBITE NERF: 6% a level (`frostbite.chance-per-level`; old 0.15 / 0.10 read as 0.06), always
  Slowness I for 1.5 / 2 s (was I / II for 3 / 4 s), once every `frostbite.cooldown-seconds` 5 per victim (`frostReady`).
  Tested (scratch `wp/WhirlTest`: status for players, players can't open one, admin here + broadcast with coords and
  distance, funnel r 11 / d 18, beam = 11 fake blocks, 15 displays, the eye sends you to the Dark Ocean, the Mirror back up and
  its surface status, stop + cleanup, the timed one near a player + next in an hour, `/kraken summon` in the Overworld = the
  normal Kraken, `kraken.overworld: false` refuses). The fake beacon beam and the normal Kraken's full fight can't be checked
  in MockBukkit (no client; the mock can't draw his rig).
- **Dragon egg buff (Items 1.3.4)**: `Items/DragonEggBuff.java` (`dragon-egg:`): the DRAGON_EGG anywhere on a player
  (inventory, hotbar, off hand; `carrying`) = `dragon-egg.effects` ["STRENGTH:2", "SPEED:2"] ("EFFECT:level", `swiftness` =
  speed), topped up every 20 ticks for 40 ticks (ambient, icon only), so it runs out within 2 s of losing the egg (never
  removed by hand: a weaker longer potion the game kept hidden comes back). Never replaces a stronger effect. Spectators
  skipped. (1.3.4 first shipped it on the dragon HEAD by mistake; 1.3.5 = the egg, config key `dragon-egg`.)
- **Bedrock couldn't craft our items (Items 1.3.5)**: since 1.21.2 the server only sends a client the recipes in its recipe
  book, and Geyser's Bedrock crafting grid only shows a result for a recipe it was told about; plugin recipes are never
  unlocked on their own (only the Mirror / Golden Ring / Iron Stick / Necro Staff / Harpy Ring / combos / ships / Index book /
  Quest Book were, by hand). `Items/RecipeUnlock.java`: every recipe whose namespace starts with `faultline` or `limbo` (all
  our plugins) is discovered for every player 2 s and 10 s after join and for everyone online 5 s after enable
  (`recipes.unlock-all`, true). RULE: any new recipe is covered automatically; no per-recipe `discoverRecipe` needed.
  Tested (scratch `dh/DhTest`: egg in inventory / off hand, a head does nothing, gone after, stronger kept, all 31 recipes
  unlocked on join).
- **Boss enchant nerf 3 (Items 1.3.6, Index 1.9.3)**: every boss enchant through `Enchants.tune(path, new, olds...)` (any
  older default in a server config reads as the new value): LIFESTEAL 2% / 4% of the hit (`lifesteal.per-level` 0.02),
  at most 1 health a heal (`max-per-hit`), at most one heal a second per player (`cooldown-ms` 1000, `stealReady`), on players
  every 8th hit (`pvp-every-hits`, was 5); Executioner +5% / +10% below 25% (`executioner.below` 0.25); Cleave 10% / 20%; Bleed
  5% / 10% for 2 / 3 s; Arrowguard 5%; Thornhide 4%; Swiftstride 2%; Frostbite 4% / 8%, once every 8 s per target; Venom 6%;
  Thunderstrike 4% / +3 / 15 s; Vitality I below 35% health, II below 60% (`vitality.level2-below`, was always on); Featherstep
  20%; Vein Miner 12 blocks; Tidecaller off for `tidecaller.pvp-off-seconds` 10 after a player fight (`lastPvp`, `onPvp`);
  Autosmelt `autosmelt.chance` 0.5 per stack; Clearsight blocks Blindness / Darkness `clearsight.chance` 0.5 of the time.
  Index Boss Enchant Book text updated. Tested (scratch `dh/LsTest`: an old config's 0.05 / 2 read as 0.02 / 1, a 40 hit heals 1,
  none within a second, a 10 hit after it heals 0.4).
- **Totem count resets (Items 1.3.7)**: `TotemLimit.expire()` (every 10 s): a player no PLAYER has hurt for
  `totem-limit.reset-after-minutes` 10 (0 = off) goes back to 0/4 with a chat line. The clock is `lastAt` (real time, set by any
  player hit in `onHit` and by each counted pop), saved in totems.yml `last-hit.<uuid>` so logging off / restarts don't skip
  it; counts from before 1.3.7 and `/totems set` start the clock at that moment. Tested (scratch `dh/TotTest`: 9 min = kept,
  10 min = reset + message, a player hit restarts it).
- **Unholy Arrows are back (Items 1.3.8, Index 1.9.4)**: `Items/UnholyArrows.java` (`unholy-arrows:`), same PDC key
  `faultlinebosses:unholy_arrow` + model `faultline:unholy_arrow` as the Demon Eye's (old ones work; OldGear no longer takes
  them). Skeletons / Strays / Bogged killed by a player drop 1-2 at `drop-chance` 0.05 (never spawner / trial spawner mobs or
  mutant minions); shapeless recipe Arrow + Fermented Spider Eye + Glowstone Dust + Redstone = 2. No bonus damage: a hit on a
  living thing (not armor stands / invisible boss hitboxes) from a draw >= `min-draw` 0.9 (crossbows always count) gives the
  SHOOTER Strength II + Swiftness II for `buff-seconds` 3, at most once every `cooldown-seconds` 20 (action bar); never cuts a
  stronger effect. Infinity still uses one up; the arrow picked back up is an Unholy Arrow; purple trail. `/giveunholy [amount]
  [player]` (`items.giveunholy`), /itemsmenu, Index entry (weapons). Tested (scratch `dh/UnTest`).
- **The Upgraded Grindstone (Items 1.3.9, Index 1.9.5, FaultlineSMP.zip)**: `Items/UpgradedGrindstone.java`
  (`upgraded-grindstone:`): GRINDSTONE item, model `faultline:upgraded_grindstone` (vanilla grindstone model retextured icy
  blue, blue obsidian legs, a glowing band on the wheel; scratch `ug/tools/make.py`), PDC `faultlineitems:upgraded_grindstone`;
  recipe BBB/BGB/BBB = 8 Blue Obsidian (ExactChoice) round a Grindstone (`BlueObsidian.onCraft` lets this recipe through).
  Placed = a real grindstone marked in the chunk PDC (int array, like Blue Obsidian) + a non-persistent ItemDisplay (tag
  `faultline_upgraded_grindstone` + `faultline_no_bedrock_fx`, scale 1.01, turned like the blockstate: `turn()` = rotateYXZ(-y,
  -x)); Bedrock sees a plain grindstone. Broken = drops our item (`onDrop`); explosion- and piston-proof. Right-click = a 54-slot
  menu (`Menu`): input slot 13, one button per enchant (rows 4-5: boss `b:<id>`, normal `v:<key>`), 46 every boss enchant,
  48 every normal one, 50 everything, 53 close. Boss enchants come back as their book (`return-boss-books` true); normal ones
  give XP like a grindstone (min modified cost, half to all) or books (`return-vanilla-books` false); curses stay
  (`remove-curses` false); repair cost = 2^remaining - 1; an emptied enchanted book becomes a BOOK; boss books and stacks are
  refused. `Enchants.write` with an empty map now removes the PDC key and the glint override. `/giveupgradedgrindstone`,
  /itemsmenu, Index entry (weapons, glyphs 58456/59480). Tested (scratch `ug/t/GrTest`).
- **FaultlineEvents 2.0.0 (a rewrite; Items 1.3.10, Index 1.9.6)**: the old jar's source was never in the repo, so it's new
  (`plugins/FaultlineEvents`, `net.faultlinesmp.events`). `FaultlineEvents.java` = clock, rolls, boss bar, `/fevent`; `Ev` = one
  event (`id` written to the main world's PDC `faultlineevents:active_event` = BLOOD_MOON / FOG / LANTERN_NIGHT / WINDY, which
  Items (sky pack, vampires, Lantern Night drops x2 + villager discount, Weather never overlaps) and Raids (Blood Moon raids)
  read; `faultlineevents:ends_at` + per-event `last_<event>` day for `schedule.cooldown-days`). Rolled once at dusk
  (`schedule.dusk`: blood-moon 0.10, lantern-night 0.10, fog 0.05) and at dawn (`schedule.dawn`: windy 0.15, fog 0.10), first
  hit wins, one at a time, `min-players` 1 survival; night events end at dawn, timed ones count down in SERVER seconds
  (`secondsLeft`); a restart resumes the running one. Admin start of a night event in the day sets the time to 13000
  (`admin-start-sets-night`). Main world + `extra-worlds`. **BLOOD MOON**: NATURAL `Monster` spawns get MULTIPLY_SCALAR_1
  modifiers `faultlineevents:blood_*` (health x1.3, damage x1.25, speed x1.1; tag `bloodmoon_mob`: no "faultline" prefix so
  Raids' Elites still roll), up to `extra-spawns.per-player` 5 more come for each survival player every 15 s (NATURAL reason,
  `groundSpot`, never where block light >= 8), monster kills x2 XP + 25% one `bonus-loot` roll, no sleeping, red mist +
  heartbeat; at `champion.at-time` 18000 the CRIMSON CHAMPION (Zombie, tag `faultline_crimson_champion`, 150 hp, 9 damage,
  scale 1.25, red leather + Fire Aspect netherite sword, no burning) hunts a random survival player: Blood Leap every 6-8 s,
  heals 35% of damage it deals, 3 `bloodmoon_minion` Bloodbound at half health, own boss bar within 64; killed = `drops` +
  `rewards` (Goodie Bag) for everyone who hurt it + stat `crimson_champions`; crumbles at dawn. Dawn: top 3 hunters broadcast,
  `dawn-rewards` for 10+ kills, stats `blood_moon_kills` / `blood_moons_survived`. **FOG** (12 min): each player's
  `setSendViewDistance(view-distance 4)` = the game's own distance fog (restored with -1 on end / quit / world change), grey
  DUST mist (size 3-4) round everyone every 5 ticks, undead don't burn in sunlight, up to 3 `fog_mob` wanderers each, kills x1.5
  XP (stat `fog_kills`), distant sounds, SHADES: a MANNEQUIN (tag `faultline_fog_shade`, only that player sees it) with a random
  online player's face 18-26 blocks ahead that watches you and vanishes in smoke when you get within 11 or stare at it 1 s (stat
  `fog_shades`). **LANTERN NIGHT**: `calm` 60% of NATURAL monster spawns cancelled, XP x1.5 (PlayerExpChangeEvent), rising sky
  lanterns (BlockDisplay LANTERN, tag `faultline_sky_lantern`, up to 14 per player, SMALL_FLAME trail for Bedrock), LANTERN
  WISPS (glowing BlockDisplay lanterns, tag `faultline_lantern_wisp`, 2 per player, bob near the ground; walk into one =
  `wisps.rewards` "chance;xp:N|item:MAT:a-b|cmd:..." + stat `lantern_wisps`), harmless midnight fireworks (`faultline_lantern_show`,
  their damage cancelled). **WINDY** (15 min by day): a wind angle that veers 3 degrees/s, gusts every 20-45 s for 3 s (x2.5);
  every 10 ticks exposed players (under the sky, not swimming/riding/flying/spectating, not Bedrock) are pushed `push` 0.035
  downwind (sneak x0.3, gliding x3, above y 120 x1.4) by `WindPush`: a ClientboundExplodePacket by reflection (Mojang names,
  manifest `paperweight-mappings-namespace: mojang`) carrying only a knockback that the client ADDS (setVelocity would replace the
  player's speed), the silent `SoundEvents.EMPTY`, a WHITE_ASH / GUST particle; any mismatch = one warning and no push (UNTESTED
  on a live server: MockBukkit has no NMS). Projectiles launched drift `projectile-drift` 0.006/tick for 4 s. Luck I, and x1.2
  (`loot-multiplier`, probabilistic rounding) on stackable mob drops (not what the mob was holding, not faultline-tagged mobs),
  XP, ores / ripe crops / melons / glowstone / amethyst (never a block's drop of itself = silk touch, never seeds) and 20% an
  extra fish (not our custom fish). Items /itemsmenu has a button per event + the Champion. Index: Crimson Champion entry,
  achievements Blood Hunter (100 `blood_moon_kills`), Champion Slayer, Lamplighter (25 wisps), guide line, Discord forwards.
  Snowy Day is gone (Items' Frost Wraith hook just never fires). Tested (scratch `ev/EvTest`: all four events, the Champion,
  calm rate, wisp catch, x1.2 exactly, held items not multiplied, arrow drift, timed end, the dusk roll).
- **Save guard, the Blue Obsidian Bed, Nether / End nights (Index 1.9.7, Items 1.3.11, Events 2.1.0)**:
  **SaveGuard** (`Index/SaveGuard.java`, `save-guard:`, data `saveguard.yml`; Watch kinds `rollback`, `unsaveable`): reports
  of inventories WIPED after a relog and of "put it in a shulker, relog, have it twice" = a player's save going BACK IN TIME
  (no code of ours loads or restores player data; the cause is a save that fails or never happens). On quit (MONITOR) it
  fixes what the game can't write (`repair`: stack > 99, enchant level > 255, anything `ItemStack#serializeAsBytes` throws
  on is taken out and logged with the reason), calls `saveData()` and records what they carry (`print`: material counts of
  inventory + ender chest + cursor, into shulker boxes and bundles; location). On join (LOWEST, before other plugins hand
  things out) the loaded inventory is compared: different = ALERT (LOG for < 32 non-valuable items gained and nothing
  missing) "came back with a DIFFERENT inventory" with what came back / what's missing, minutes away, blocks moved. One
  online player's items are re-checked every 5 s. `clean` flag + `online` list in saveguard.yml: a start after an unclean
  stop (crash / kill) is an ALERT naming who was online (their records are dropped: they can't be trusted). Tested (scratch
  `sg/SgTest`). The real cause on the live server is NOT confirmed: needs latest.log ("Failed to save player data").
  **Blue Obsidian Bed** (`Items/BlueBed.java`, `blue-bed:`; LIGHT_BLUE_BED item with glint, PDC `faultlineitems:blue_bed`;
  recipe WWW/BBB = any wool over Blue Obsidian, key `faultlineitems:blue_obsidian_bed`; `/givebluebed`, /itemsmenu, Index
  entry with the blue_obsidian icon; no pack change): both halves marked in the chunk PDC (`faultlineitems:blue_bed`);
  drops itself (`onDrop`, and `onItem` for the half vanilla breaks without an event); explosion- and piston-proof. In the
  NETHER / END a right-click is cancelled (no explosion), sets the respawn point (player PDC `blue_bed_spawn`
  "world;x;y;z" of the head; `onRespawn` DEATH -> `stand()` beside it; a vanilla bed / anchor set later clears it via
  PlayerSetSpawnEvent; bed gone = cleared) and, at that world's night (`faultlineevents:realm_night`; always without
  FaultlineEvents; `needs-night`), `Player#sleep(loc, true)`; refused / unimplemented = RESTING (held on the bed, Blindness,
  metadata `faultline_blue_bed_rest`, moving gets up). In the Overworld it's a normal bed. Tested (scratch `bb/BbTest`).
  **Realms** (`Events/Realms.java`, `realms:`, `/fevent realm <nether|end> <day|night|ember-storm|soul-tide|void-storm|
  starfall>`): every NETHER / THE_END world has its own clock (world PDC `faultlineevents:realm_clock` seconds,
  `realm_night` 0/1, `realm_event`; runs only with a player there): `day-minutes` 12, then `night-minutes` 8. Night: boss
  bar, extra spawns near survival players (Nether: blaze / magma cube / wither skeleton / skeleton, End: endermen; tag
  `realm_mob`, NATURAL reason, max 3 each), kill XP x1.25, and at nightfall each event rolls 0.3 (first hit; none = a quiet
  night). EMBER STORM: in the open (no solid block within 6 above, no Fire Resistance) 12%/s you catch fire for 3 s; natural
  `Enemy` spawns +25% health / damage (modifiers `faultlineevents:realm_*`), kills x2 XP + 25% a bonus drop, stat
  `ember_storm_kills`. SOUL TIDE: piglin barters doubled, quartz / nether gold ore x1.5, +5% wither skulls, Speed I, LOST
  SOULS (glowing soul-lantern BlockDisplays, tag `faultline_realm_pickup`, walk into one = `soul-tide.souls.rewards`, stat
  `lost_souls`). VOID STORM: every 15 s a calm enderman within 24 targets you (max 2), endermen +25% / +30%, +1-2 pearls
  and x2 XP, stat `void_storm_kills`. STARFALL: fallen stars (sea-lantern displays, `starfall.stars.rewards`, stat
  `star_shards`), XP x1.5, elytra don't wear. Dawn when the night runs out or `sleep-percent` 0.5 of the survival players
  there sleep / rest for `sleep-seconds` 5; staying awake a whole night = stat `realm_nights`. Index achievements Soul
  Catcher, Star Catcher, Night Shift. Tested (scratch `rl/RlTest`).
- Soft dependencies: Bosses → Items, Raids; Raids → Items; Deep → Items, Bosses, Index; Skills → Index; Events → Items, Index; Index → all the others.
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
| `FaultlineBloodMoon.zip` | Blood Moon pack: replaces moon phase textures (sent by FaultlineItems' EventPacks during a Blood Moon) |
| `FaultlineEclipse.zip`, `FaultlineAurora.zip` | Eclipse / Aurora sky packs (`tools/event_packs.py`, sent by EventPacks during those events) |
| `FaultlineBosses.zip` | Very old zip of the FaultlineBosses source (Demon Eye only). Ignore it; the live copy is `plugins/FaultlineBosses/` |
| `plugins/` | Source code for all eleven custom plugins (edit these) |
| `FaultlineEmotes.zip` | Third Java pack: the bendy emote body (26.2 core shader + body part models, `tools/emote_pack.py`; sent to 26.2 games beside FaultlineSMP.zip) |
| `FaultlineDeepMusic.zip` | Second Java pack: the Dark Ocean's three music tracks (sunken, abyss, acid; sent beside FaultlineSMP.zip) |
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
