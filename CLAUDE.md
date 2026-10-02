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
| **FaultlineItems** | `net.faultlinesmp.items` | All custom items + accessories (2-slot `/accessories` GUI), staffs, spawn eggs, diving gear, Kraken & Diamond Jacob gear, Skeleton Wanderer cave trader, admin `/itemsmenu` | many `/give*` commands, `/itemsmenu` (`/fitems`), `/itemsreload`, `/cavetrader`, `/batform` |
| **FaultlineRaids** | `net.faultlinesmp.raids` | Zombie Raids: Zombie Omens, Zombie Captains, waves of special zombies | `/zraid <start\|stop\|captain\|menu\|egg\|omen>` |
| **FaultlineBosses** | `net.faultlinesmp.bosses` | Bosses in one ~585 KB file: Demon Eye, Frostbeard, Dune Devourer/Frostmaw, Don Lorenzo, Kraken, **Diamond Jacob**; admin boss form. **Rocco Vendetta** (+ Werner, the Vendetta Fist) lives in its own `Vendetta.java` | `/demoneye`, `/frostbeard`, `/dune`, `/don`, `/kraken`, `/jacob <summon\|kill\|phase\|item>`, `/rocco <summon\|kill\|phase\|tattoos\|werner\|item>`, `/bossmorph <boss\|off\|release>` |
| **FaultlineIndex** | `net.faultlinesmp.index` | The Faultline Index codex. Entries live in `src/main/resources/index.yml`, font glyphs in `glyphs.yml` | `/index [give\|reset] [player]` |
| **FaultlineCosmetics** | `net.faultlinesmp.cosmetics` | Permanent cosmetic unlocks worn in 4 slots (Hat, Neck, Back, Body) over armor, for Java and Bedrock. Cosmetics are defined in its `config.yml`, unlocks saved in `players.yml` | `/cosmetics`, admin `/cosmetic <unlock\|lock\|list\|reload>` |

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
- Changing a plugin's default `config.yml` does NOT update the copy already on the server. Tell the owner
  which values to change in `plugins/<Plugin>/config.yml` on the VPS.
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
| `plugins/` | Source code for all six custom plugins (edit these) |
| `bedrock/` | Bedrock (Geyser) cosmetics pack + custom item mappings, made by `tools/cosmetics_assets.py` |

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

Rocco Vendetta and Werner (skins → 6-piece rigs), the Vendetta Fist, the Vendetta Contract and their Index icons come from
`python3 tools/vendetta_models.py <unpacked-pack-dir> --preview tools/previews` (it reuses `jacob_models.py` and `cosmetics_assets.py`).
New Index icons also need a glyph in `FaultlineIndex/glyphs.yml` and two bitmap providers in `assets/faultline/font/index.json`.

## Building in Claude Code cloud sessions
Maven must reach `repo.papermc.io` to download paper-api. If the build fails with
`403 Forbidden` on that host, add `repo.papermc.io` to the environment's allowed network domains.
Without it, run `tools/compile_check.sh` to compile every plugin against the real Paper 26.2 API (`PAPER=1.21.11` for the old one)
(it builds paper-api from PaperMC's GitHub source + Maven Central). Run it before pushing Java changes.
