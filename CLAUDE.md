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
| **FaultlineItems** | `net.faultlinesmp.items` | All custom items + accessories (3-slot `/accessories` GUI), staffs, spawn eggs, diving gear, Kraken & Diamond Jacob gear, Skeleton Wanderer cave trader, admin `/itemsmenu` | many `/give*` commands, `/itemsmenu` (`/fitems`), `/itemsreload`, `/cavetrader`, `/batform` |
| **FaultlineRaids** | `net.faultlinesmp.raids` | Zombie Raids (Zombie Omens, Captains, Rotbeard), Skeleton Raids, and **Piglin Raids** (`Piglins.java`: War Horn, the Bulwark, the Great Hog) | `/zraid <start\|stop\|captain\|menu\|egg\|omen\|horn>` |
| **FaultlineBosses** | `net.faultlinesmp.bosses` | Bosses in one ~585 KB file: Demon Eye, Frostbeard, Dune Devourer/Frostmaw, Don Lorenzo, Kraken, **Diamond Jacob**; admin boss form. **Rocco Vendetta** (+ Werner, the Vendetta Fist) lives in its own `Vendetta.java`. **The way down to the Lost Explorer** (Swarm, his staircase, the void world) is `Below.java`; **the Lost Explorer's fight** is `Explorer.java` (+ `ExplorerAnims.java`) | `/demoneye`, `/frostbeard`, `/dune`, `/don`, `/kraken`, `/jacob <summon\|kill\|phase\|item>`, `/rocco <summon\|kill\|phase\|tattoos\|werner\|item>`, `/bossmorph <boss\|off\|release>`, `/below <swarm\|void\|leave\|close\|slayer\|info>`, `/explorer <start\|stop\|skip\|phase\|stun\|reset\|arena>` |
| **FaultlineIndex** | `net.faultlinesmp.index` | The Faultline Index codex. Entries live in `src/main/resources/index.yml`, font glyphs in `glyphs.yml` | `/index [give\|reset] [player]` |
| **FaultlineCosmetics** | `net.faultlinesmp.cosmetics` | Permanent cosmetic unlocks worn in 4 slots (Hat, Neck, Back, Body) over armor, for Java and Bedrock. Cosmetics are defined in its `config.yml`, unlocks saved in `players.yml` | `/cosmetics`, admin `/cosmetic <unlock\|lock\|list\|reload>` |
| **FaultlineShips** | `net.faultlinesmp.ships` | Ships: Sloop, Brigantine, Galleon. Blueprint → lay out on water → place every block → sail. Health, wrecks, Shipwright's Hammer repair minigame. Ships saved in `ships.yml` | `/ship [list\|info\|crew\|scrap]`, admin `/ship <give\|repair\|wreck\|remove>` |

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
- Items nerfs: the **Ankh Shield** only stops knockback (`ankh-shield.old-effects: true` brings the rest back); the
  **Harpy Ring** is +7.5% speed.
- Changing a plugin's default `config.yml` does NOT update the copy already on the server. Tell the owner
  which values to change in `plugins/<Plugin>/config.yml` on the VPS.
- **Ships** (`FaultlineShips`; `ShipType.java` = the 3 layouts, `Ship.java` = one ship, `FaultlineShips.java` = items,
  events, the hammer minigame, saving). A ship is one block display per block riding an invisible root ItemDisplay
  (moving = one teleport; since 1.21.10 teleports keep passengers), each display's transformation places/rotates its block
  (re-sent, interpolated, while turning). Local coords: +x bow, +z starboard, y 0 = top water block. Sloop 107 blocks / 150 hp /
  3 seats, Brigantine 280 / 350 / 5 + 27-slot hold, Galleon 648 / 700 / 9 + 54-slot hold (medium and big need water 2 deep).
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
  anchor, damage, wreck, repair, save/load, scrap, events, Bedrock stand-in, the fixes). MockBukkit quirks the test copy patches:
  teleporting an entity with passengers, `Block.getLocation()` returning the block's own Location, rayTraceBlocks.
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
| `bedrock/` | Bedrock (Geyser) packs + custom item mappings: cosmetics (`tools/cosmetics_assets.py`) and ships (`tools/ship_assets.py --bedrock`) |

Resource packs are served from raw GitHub links on `main`, e.g.
`https://raw.githubusercontent.com/faultlinesmp123-sys/Faultline-SMP/main/FaultlineBossesPack.zip`
(same pattern for `FaultlineItemTextures.zip`, `FaultlineBloodMoon.zip`, `FaultlineSMP.zip`).
The server sends **FaultlineSMP.zip** (set in FaultlineItems `config.yml` → `item-textures`).
Changing a zip changes its hash: recompute with `sha1sum <file>.zip` and update the plugin config.
Players download the pack from `main`, so a new hash only works once the new zip is merged to `main`.
Keep `FaultlineSMP.zip` **under 25 MB**: the owner uploads it through GitHub's web page, which refuses bigger files.
Almost all of its size is boss music: encode new tracks as Ogg Vorbis at `ffmpeg -c:a libvorbis -q:a 1 -ar 44100` (~80 kbps).

## Cosmetics (FaultlineCosmetics)
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
