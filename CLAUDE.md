# Faultline SMP

Custom Minecraft survival server (formerly **Limbo SMP**, renamed to **Faultline SMP**).
Owner: ndjdjs (YouTube: vo0ise). Talks casually and directly, pushes back when something
is wrong, prefers concrete/data-backed answers over generic advice.

> This file was rebuilt from claude.ai saved memory (the original claude.ai chats were not
> in the data export). If something here is wrong or outdated, fix it here.

## Server setup
- **Paper** server, with **Geyser** (Bedrock players) and **ViaVersion**.
- Paper API target: `1.21.11-R0.1-SNAPSHOT`, `api-version: '1.21'`. Resource packs use
  `pack_format` 75 (FaultlineSMP.zip supports 69–75).
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

## Custom plugins (Java, Maven, package `net.faultlinesmp.*`)
- **LimboBlackMarket**: `/tier set|get` for staff-assigned tiers. Tags dropped player
  heads with the victim's tier via lore. Does NOT auto-generate shulker boxes; staff hand those out.
- Four custom plugins were produced in earlier chats, with features including accessory items +
  accessories GUI, custom items, breach events/raids, Kingdom End teleporters,
  custom brewing chains, per-phase resource packs, and saved progression. Known names:
  **FaultlineItems**, **FaultlineRaids**, **FaultlineBosses**.
- **FaultlineBosses** (source in `FaultlineBosses.zip`): summonable bosses, starting with
  **The Demon Eye** (1000 HP, 4 phases, servants, tooth/blood projectiles, summoned with
  a Suspicious Eye). Command: `/demoneye <summon|kill|give> [amount] [player]`
  (perm `bosses.admin`). `softdepend: [FaultlineItems, FaultlineRaids]`. Tunables in `config.yml`.
- Past bugs already fixed: resource-pack race conditions, gateway teleport cross-world
  exceptions, pom.xml API version bumps.

## Standing rules when updating plugins
1. **Every new item, boss, or enemy must be added to the Faultline Index.**
2. **Every new boss must be added to `/itemsmenu`** (a spawn egg or summon item).
3. If textures/models change, update the resource pack zip **and** its SHA-1 hash in config.

## Repo contents
| File | What it is |
|---|---|
| `FaultlineSMP.zip` | Main combined resource pack (items, bosses, Index, sounds, cinematic font) |
| `FaultlineItemTextures.zip` | Custom item textures pack (accessories, potions, staffs, etc.) |
| `FaultlineBossesPack.zip` | Boss models/sounds pack (Demon Eye, Dune, Frostmaw, mf_* models) |
| `FaultlineBloodMoon.zip` | Blood Moon pack: replaces moon phase textures |
| `FaultlineBosses.zip` | **Source code** for the FaultlineBosses plugin (Maven project) |

Resource packs are served from raw GitHub links on `main`, e.g.
`https://raw.githubusercontent.com/faultlinesmp123-sys/Faultline-SMP/main/FaultlineBossesPack.zip`
(same pattern for `FaultlineItemTextures.zip`, `FaultlineBloodMoon.zip`, `FaultlineSMP.zip`).
Changing a zip changes its hash: recompute with `sha1sum <file>.zip` and update the plugin config.

## Missing
Source for LimboBlackMarket, FaultlineItems, FaultlineRaids, and the other plugins is
**not in this repo yet**. Upload those projects (or their jars) so they can be edited here.
