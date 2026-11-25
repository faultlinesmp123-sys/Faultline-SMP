# Faultline SMP 2.0 setup (the new world)

2.0 replaces the overworld. Do this ONCE, with the server stopped.

## 0. Before anything: back up
Copy somewhere safe (off the VPS if you can):
- `plugins/FaultlineIndex/config.yml` (the staff logs webhook URL)
- any other plugin config you changed by hand
- the whole old server folder if you ever want to look at the old world again

## 1. Update the server to Paper 26.3
Swap the Paper jar for the 26.3 one. Java 25 (same as 26.2).

## 2. Wipe
You said you're wiping everything. Delete:
- the world folders: `world`, `world_nether`, `world_the_end`, `faultline_deep` (and `faultline_void` if it's still there)
- player data goes with the world folder
- plugin data you want reset (`plugins/LimboBlackMarket/stats.yml`, `tiers`, `plugins/FaultlineIndex/progress.yml`,
  `plugins/FaultlineKingdoms/data.yml`, `plugins/FaultlineItems/accessories.yml` ...). Keep the config.yml files.

## 3. Put the jar in
- Delete the old `plugins/FAULTLINESMPJAR.jar`.
- Put `FAULTLINESMPJAR-2.0.jar` in `plugins/` (only ONE Faultline jar in there).

## 4. Start the server: it stops by itself once
On the first start FaultlineWorld installs its datapack into `world/datapacks/` and adds this to `bukkit.yml`:

    worlds:
      world:
        generator: FaultlineWorld

then stops the server BEFORE any world is made. That's on purpose (the console says so in a big banner).

## 5. Start it again
Now the Faultline overworld is generated: spawn at 250 / 250 / -1250, border 7500 x 7500.
Check with `/fworld where` (it names the biome you stand in). If the console says
"the main world isn't using the Faultline generator", the world folder was not deleted: stop, delete it, start again.

## 6. Pregenerate (strongly recommended)
The generator is heavier than vanilla (7-18 ms a chunk). Install Chunky and run:

    chunky world world
    chunky center 0 0
    chunky radius 3750
    chunky start

Let it finish before opening the server (it takes a while: 7500 x 7500 is ~220,000 chunks).

## 7. Config to set by hand
- Kingdoms: the Discord bot is gone. Staff put players in kingdoms with `/kingdoms set <player> <1-5>`.
- Staff logs: put your staff channel's webhook in `plugins/FaultlineIndex/config.yml` as `discord.logs-webhook-url: "https://..."`
  (keep the quotes). That file only, never in chat or GitHub. `/discord status` and `/discord test` check it.
- `plugins/FaultlineRaids/config.yml`: nothing to do (an old `elites.chance: 0.01` is read as 0). Optional:
  `elites.farlands-chance: 0.35`, `elites.fibers-chance: 0.04` to tune the Farlands / Fibers.
- Resource pack: FaultlineSMP.zip on `main` must be the new one (it has the Umbrella).

## Testing first
Plugins don't run in a single player world. To test on your own PC: download the Paper 26.3 jar, put it in an empty
folder with `plugins/FAULTLINESMPJAR-2.0.jar`, run `java -jar paper.jar` twice (see step 4), then join `localhost`.

## Handy admin commands
- `/fworld tp <name>`: teleport to a faction build (castle, angel, tower, death, mega, hall, ghost, himeji, starry,
  ship, ballroom, greek, factory, sightless, cathedral, arena), `pyramid1`..`pyramid3`,
  a faction's shrine: `mountaincore`, `junglecore`, `icecore`, `fiberscore`, `skycore`
- `/fworld giant`: a Zombie Giant where you stand
- `/kingdoms set <player> <1-5>`
- `/missions`: the Mission Board
