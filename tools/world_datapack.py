#!/usr/bin/env python3
"""Builds the Faultline 2.0 world datapack (Minecraft 26.3, data pack format 121) into
plugins/FaultlineWorld/src/main/resources/faultline_world.zip (FaultlineWorld installs it into world/datapacks/).

  python3 tools/world_datapack.py [--out <zip>] [--dir <unzipped copy>]

Everything is built from the REAL vanilla 26.3 files in tools/world_datapack/vanilla_26.3 (copied from misode/mcmeta's
26.3-data branch), so the JSON shapes match the game exactly:
  * data/faultline/worldgen/biome/*.json   14 custom biomes (colours, particles, music, spawns, vanilla features minus trees:
                                            the plugin draws their trees / rocks / buildings itself)
  * data/minecraft/worldgen/noise_settings/overworld.json   sea level 200 (everything else vanilla)
  * data/minecraft/dimension_type/overworld.json            clouds at y 318 (the land is at 200-310)
  * data/minecraft/worldgen/placed_feature/ore_diamond*.json diamonds only between y 0 and 35
  * biome tags: Woodland Mansions in the Deep Forest, shipwrecks / ocean ruins in the Great Sea, forests, overworld.
"""
import argparse
import copy
import io
import json
import os
import zipfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
VAN = os.path.join(ROOT, "tools", "world_datapack", "vanilla_26.3")
FORMAT = 121


def load(rel):
    with open(os.path.join(VAN, rel)) as f:
        return json.load(f)


TREE_WORDS = ("trees_", "tree", "dark_forest_vegetation", "forest_rock", "rooted_azalea")


def strip_trees(features):
    return [[f for f in step if not any(w in f for w in TREE_WORDS)] for step in features]


def spawns(monster=None, creature=None, extra=None, water=None, ambient=None):
    by = {}
    if monster is not None:
        by["monster"] = [{"type": "minecraft:" + t, "count": c, "weight": w} for t, c, w in monster]
    if creature is not None:
        by["creature"] = [{"type": "minecraft:" + t, "count": c, "weight": w} for t, c, w in creature]
    if water is not None:
        by["water_creature"] = [{"type": "minecraft:" + t, "count": c, "weight": w} for t, c, w in water]
    if ambient is not None:
        by["ambient"] = [{"type": "minecraft:" + t, "count": c, "weight": w} for t, c, w in ambient]
    if extra:
        by.update(extra)
    return {"argument": {"spawn_costs": {}, "spawns_by_category": by}, "modifier": "overlay"}


def biome(template, *, temp=None, downfall=None, precip=None, water=None, grass=None, foliage=None, sky=None, fog=None,
          particles=None, music=None, spawn=None, strip=True, grass_mod=None, drop_features=(), music_delay=(12000, 24000), ambient=None):
    b = copy.deepcopy(load("biome/" + template + ".json"))
    a = b.setdefault("attributes", {})
    eff = b.setdefault("effects", {})
    if temp is not None: b["temperature"] = temp
    if downfall is not None: b["downfall"] = downfall
    if precip is not None: b["has_precipitation"] = precip
    if water: eff["water_color"] = water
    if grass: eff["grass_color"] = grass
    if foliage: eff["foliage_color"] = foliage
    if grass_mod: eff["grass_color_modifier"] = grass_mod
    elif grass and eff.get("grass_color_modifier") == "swamp": del eff["grass_color_modifier"]   # our own colour, not swamp noise
    if sky: a["minecraft:visual/sky_color"] = sky
    if fog: a["minecraft:visual/fog_color"] = fog
    if particles:
        a["minecraft:visual/ambient_particles"] = {"argument": [{"particle": {"type": p}, "probability": pr} for p, pr in particles], "modifier": "append"}
    if music:
        a["minecraft:audio/background_music"] = {"default": {"max_delay": music_delay[1], "min_delay": music_delay[0], "sound": music}}
    if ambient:
        a["minecraft:audio/ambient_sounds"] = ambient
    if spawn is not None:
        a["minecraft:gameplay/natural_mob_spawns"] = spawn
    if strip:
        b["features"] = strip_trees(b["features"])
    if drop_features:
        b["features"] = [[f for f in step if not any(w in f for w in drop_features)] for step in b["features"]]
    return b


MON = [("zombie", 4, 95), ("skeleton", 4, 100), ("spider", 4, 100), ("creeper", 4, 100), ("enderman", 1, 10), ("witch", 1, 5)]


def biomes():
    return {
        "great_sea": biome("deep_ocean", water="#1b3d8c", fog="#2a4f9e", sky="#7aa6ff"),
        "abyss": biome("deep_cold_ocean", water="#050a1e", fog="#03050d", sky="#03050d", music="minecraft:music.under_water",
                       spawn=spawns(monster=[("drowned", 2, 60), ("guardian", 1, 10)], ambient=[],
                                    # AUDIT: glow squids MUST be listed under their own category. Under water_creature the game's cap
                                    # (which counts by the mob's own category) never saw them: hundreds per player, forever.
                                    extra={"underground_water_creature": [{"type": "minecraft:glow_squid", "count": 2, "weight": 10}]})),
        "wasteland": biome("swamp", temp=1.2, water="#9bc31b", grass="#8a8f2a", foliage="#7d8a1e", sky="#b9c46a", fog="#9aa63a",
                           particles=[("minecraft:spore_blossom_air", 0.012), ("minecraft:white_ash", 0.03)],
                           spawn=spawns(monster=[("bogged", 4, 100), ("slime", 4, 80), ("drowned", 2, 40), ("zombie", 4, 60), ("creeper", 3, 60)], creature=[])),
        "deep_desert": biome("desert", sky="#e8b98a", fog="#e4c79a", particles=[("minecraft:white_ash", 0.004)],
                             spawn=spawns(monster=[("husk", 4, 100), ("skeleton", 4, 60), ("spider", 2, 40), ("creeper", 2, 40)], creature=[("camel", 1, 3), ("rabbit", 3, 4)])),
        "deep_forest": biome("old_growth_pine_taiga", fog="#5f7a64", sky="#86a6a0", grass="#4e7a3a", foliage="#3f6a2e",
                             spawn=spawns(monster=MON, creature=[("wolf", 4, 8), ("fox", 2, 8), ("rabbit", 3, 4), ("pig", 4, 8)])),
        "inferno_forest": biome("savanna", temp=2.0, downfall=0.0, precip=False, grass="#4a3b2c", foliage="#3a2a20", water="#5a3c2c",
                                sky="#6b3b2a", fog="#4c3631", particles=[("minecraft:white_ash", 0.12), ("minecraft:ash", 0.04)],
                                music="minecraft:music.nether.basalt_deltas",
                                spawn=spawns(monster=[("zombie", 4, 100), ("skeleton", 4, 100), ("husk", 4, 70), ("blaze", 1, 15), ("magma_cube", 2, 30), ("creeper", 3, 60)], creature=[])),
        "dark_forest": biome("dark_forest", fog="#1f2c27", sky="#53707a", grass="#2f4a2c", foliage="#294a33", grass_mod="dark_forest",
                             particles=[("minecraft:mycelium", 0.012)], drop_features=("forest_flowers",)),
        "wartsfield_valley": biome("plains", grass="#a3121f", foliage="#c9c4b0", sky="#8ab8ff", fog="#d8c0c0",
                                   drop_features=("flower_plains", "patch_tall_grass", "patch_grass")),
        "spider_woods": biome("dark_forest", grass="#4a4a3a", foliage="#3a3a30", fog="#4a4744", sky="#6a6f74",
                              particles=[("minecraft:white_ash", 0.01)],
                              spawn=spawns(monster=[("spider", 4, 200), ("cave_spider", 3, 80), ("zombie", 4, 40), ("skeleton", 4, 40)], creature=[])),
        "spider_caves": biome("dripstone_caves", fog="#2a2a2a",
                              spawn=spawns(monster=[("spider", 4, 150), ("cave_spider", 4, 150), ("skeleton", 2, 30)], ambient=[("bat", 4, 10)])),
        "fibers": biome("lush_caves", fog="#2d3a26", grass="#5a6a2c", particles=[("minecraft:spore_blossom_air", 0.01)], strip=False,
                        spawn=spawns(monster=[("zombie", 4, 100), ("skeleton", 4, 100), ("spider", 2, 60), ("cave_spider", 2, 40), ("creeper", 3, 80)], ambient=[("bat", 4, 10)])),
        "mire": biome("mangrove_swamp", water="#3a3f22", grass="#3f4a24", foliage="#3a4a20", fog="#2c3122",
                      particles=[("minecraft:spore_blossom_air", 0.015)],
                      spawn=spawns(monster=[("zombie", 4, 140), ("skeleton", 4, 110), ("bogged", 4, 80), ("slime", 4, 100), ("spider", 4, 80), ("creeper", 4, 90), ("witch", 1, 15)], creature=[("frog", 2, 10)])),
        # round 10: a dark crimson sky and fog (the light level is untouched: you can still see), crimson-forest ambience,
        # GHASTS drifting over the walls. Its music (faultline:farlands.music) is played by FaultlineItems (Farlands.musicTick),
        # not the biome: biome music only changed when the song playing ended
        "farlands": biome("windswept_hills", sky="#3b1626", fog="#5a2132",
                          particles=[("minecraft:crimson_spore", 0.012), ("minecraft:ash", 0.02), ("minecraft:end_rod", 0.001)],
                          ambient={"additions": {"sound": "minecraft:ambient.crimson_forest.additions", "tick_chance": 0.0111},
                                   "loop": "minecraft:ambient.crimson_forest.loop",
                                   "mood": {"block_search_extent": 8, "offset": 2.0, "sound": "minecraft:ambient.crimson_forest.mood", "tick_delay": 6000}},
                          spawn=spawns(monster=[("zombie", 4, 120), ("skeleton", 4, 120), ("creeper", 4, 110), ("spider", 4, 100), ("husk", 2, 30),
                                                ("stray", 2, 30), ("ghast", 1, 18)], creature=[("sheep", 4, 6)])),
        "sky_isles": biome("meadow", sky="#9fd7ff", fog="#dff2ff", particles=[("minecraft:white_ash", 0.002)]),
    }


def _it(name, w, lo=1, hi=1, ench=None, book=False):
    e = {"type": "minecraft:item", "name": "minecraft:" + name, "weight": w, "functions": []}
    if hi > 1: e["functions"].append({"function": "minecraft:set_count", "count": {"type": "minecraft:uniform", "min": lo, "max": hi}})
    if ench: e["functions"].append({"function": "minecraft:enchant_with_levels", "levels": ench})
    if not e["functions"]: del e["functions"]
    return e


def _pool(lo, hi, entries):
    return {"rolls": {"type": "minecraft:uniform", "min": lo, "max": hi}, "entries": entries}


def loot_tables():
    """Round 10: the Farlands' chests. Hard to reach, so really good. FaultlineItems adds bags, boss books and accessories on top."""
    vault = [_it("diamond", 22, 2, 6), _it("emerald", 16, 4, 12), _it("gold_ingot", 14, 4, 10), _it("iron_ingot", 14, 6, 16),
             _it("netherite_scrap", 8, 1, 3), _it("ancient_debris", 6, 1, 2), _it("golden_apple", 12, 1, 3), _it("enchanted_golden_apple", 1),
             _it("enchanted_book", 10, ench=30), _it("diamond_sword", 3, ench=30), _it("diamond_pickaxe", 3, ench=30),
             _it("diamond_chestplate", 3, ench=30), _it("totem_of_undying", 2), _it("experience_bottle", 12, 8, 16),
             _it("netherite_upgrade_smithing_template", 3), _it("ender_pearl", 8, 4, 8), _it("name_tag", 3)]
    deep = [_it("diamond", 20, 4, 10), _it("emerald", 12, 8, 20), _it("netherite_scrap", 12, 2, 4), _it("netherite_ingot", 5),
            _it("ancient_debris", 8, 2, 4), _it("golden_apple", 12, 2, 4), _it("enchanted_golden_apple", 4),
            _it("enchanted_book", 14, ench=30), _it("diamond_sword", 4, ench=30), _it("diamond_axe", 3, ench=30),
            _it("diamond_helmet", 3, ench=30), _it("diamond_leggings", 3, ench=30), _it("diamond_boots", 3, ench=30),
            _it("totem_of_undying", 5), _it("experience_bottle", 12, 16, 32), _it("netherite_upgrade_smithing_template", 6),
            _it("diamond_block", 4, 1, 2)]
    camp = [_it("bread", 12, 2, 6), _it("cooked_beef", 10, 2, 5), _it("torch", 8, 8, 16), _it("iron_ingot", 12, 3, 8),
            _it("gold_ingot", 8, 2, 6), _it("diamond", 8, 1, 3), _it("emerald", 8, 2, 6), _it("compass", 4), _it("golden_apple", 6, 1, 2),
            _it("arrow", 8, 8, 24), _it("experience_bottle", 8, 4, 10), _it("enchanted_book", 6, ench=20),
            _it("iron_chestplate", 3, ench=20), _it("bow", 3, ench=25), _it("ender_pearl", 5, 2, 4), _it("spyglass", 2)]
    def table(pools):
        return {"type": "minecraft:chest", "pools": pools}
    return {
        "chests/farlands_vault": table([_pool(5, 8, vault)]),
        "chests/farlands_vault_deep": table([_pool(7, 11, deep)]),
        "chests/farlands_camp": table([_pool(4, 7, camp)]),
    }


def diamonds():
    out = {}
    for name in ("ore_diamond", "ore_diamond_buried", "ore_diamond_large", "ore_diamond_medium"):
        pf = load(f"placed_feature/{name}.json")
        for p in pf["placement"]:
            if p.get("type") == "minecraft:height_range":
                p["height"] = {"type": "minecraft:uniform", "min_inclusive": {"absolute": 0}, "max_inclusive": {"absolute": 35}}
        out[name] = pf
    return out


def add_tag(path, extra, files):
    t = load("tags/" + path + ".json")
    t = {"replace": False, "values": list(dict.fromkeys(t["values"] + extra))}
    files["data/minecraft/tags/worldgen/biome/" + path + ".json"] = t


def build():
    files = {}
    files["pack.mcmeta"] = {"pack": {"description": "Faultline SMP 2.0: the Faultline overworld's biomes, sea level 200, diamonds at y 0-35",
                                     "pack_format": FORMAT, "min_format": [FORMAT, 0], "max_format": [FORMAT + 20, 0]}}
    for k, v in biomes().items():
        files[f"data/faultline/worldgen/biome/{k}.json"] = v
    ns = load("noise_settings_overworld.json")
    ns["sea_level"] = 200
    files["data/minecraft/worldgen/noise_settings/overworld.json"] = ns
    dt = load("dimension_type_overworld.json")
    # round 7: the world is 640 tall (y -64..575): the sky kingdom floats at 300-470, the cathedral stands on a mountain.
    # (Bedrock clients only ever show y -64..319.)
    dt["height"] = 592          # y -64..527; Geyser shows Bedrock players up to y 511
    dt["logical_height"] = 592
    dt["attributes"]["minecraft:visual/cloud_height"] = 500.0
    ns["noise"]["height"] = 592
    files["data/minecraft/dimension_type/overworld.json"] = dt
    for k, v in loot_tables().items():
        files[f"data/faultline/loot_table/{k}.json"] = v
    for k, v in diamonds().items():
        files[f"data/minecraft/worldgen/placed_feature/{k}.json"] = v
    land = ["faultline:" + b for b in ("wasteland", "deep_desert", "deep_forest", "inferno_forest", "dark_forest", "wartsfield_valley",
                                       "spider_woods", "spider_caves", "fibers", "mire", "farlands", "sky_isles")]
    add_tag("is_overworld", land, files)
    add_tag("has_structure/woodland_mansion", ["faultline:deep_forest"], files)
    add_tag("has_structure/shipwreck", ["faultline:great_sea"], files)
    add_tag("has_structure/ocean_ruin_cold", ["faultline:great_sea"], files)
    add_tag("is_forest", ["faultline:dark_forest", "faultline:deep_forest", "faultline:spider_woods"], files)
    return files


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", default=os.path.join(ROOT, "plugins/FaultlineWorld/src/main/resources/faultline_world.zip"))
    ap.add_argument("--dir")
    a = ap.parse_args()
    files = build()
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w", zipfile.ZIP_DEFLATED) as z:
        for path in sorted(files):
            z.writestr(zipfile.ZipInfo(path, (2026, 1, 1, 0, 0, 0)), json.dumps(files[path], indent=2))
    with open(a.out, "wb") as f:
        f.write(buf.getvalue())
    if a.dir:
        for path, v in files.items():
            p = os.path.join(a.dir, path)
            os.makedirs(os.path.dirname(p), exist_ok=True)
            with open(p, "w") as f:
                json.dump(v, f, indent=2)
    print(f"{a.out}: {len(files)} files, {len(buf.getvalue()) // 1024} KB")


if __name__ == "__main__":
    main()
