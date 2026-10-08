#!/usr/bin/env python3
"""Generate deterministic Rackcraft assets from content.json."""

import json
from pathlib import Path

import textures

ROOT = Path(__file__).resolve().parents[1]
RESOURCES = ROOT / "src/main/resources"
CONTENT = json.loads((ROOT / "tools/content.json").read_text(encoding="utf-8"))
MACHINE_IDS = {entry["id"] for entry in CONTENT["blocks"] if entry.get("machine")}
AIRFLOW_BLOCKING = {entry["id"] for entry in CONTENT["blocks"] if entry.get("blocksAirflow")}
CABLE_IDS = {"power_cable", "coolant_pipe", "fiber_cable", "item_pipe"}
# Cubes that make no items, so they have no port core.
NO_PORT = ("battery_bank", "desalination_plant", "grid_substation", "heat_recovery_plant")
ANIMATION_FRAMETIME = 4


def write_texture(path, frames):
    path.parent.mkdir(parents=True, exist_ok=True)
    frames = frames if isinstance(frames, list) else [frames]
    path.write_bytes(textures.png_bytes(frames))
    meta = path.with_name(path.name + ".mcmeta")
    if len(frames) > 1:
        write_json(meta, {"animation": {"frametime": ANIMATION_FRAMETIME}})
    elif meta.exists():
        meta.unlink()


def ingredient(value):
    return {"tag": value[1:]} if value.startswith("#") else {"item": value}


def write_json(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, indent=2, sort_keys=True) + "\n", encoding="utf-8")


# Must match CableBlock#halfWidth.
CABLE_HALF_WIDTH = {"power_cable": 2, "coolant_pipe": 3, "fiber_cable": 1, "item_pipe": 2.5}
ARM_ROTATIONS = {"north": {}, "east": {"y": 90}, "south": {"y": 180}, "west": {"y": 270}, "up": {"x": 270}, "down": {"x": 90}}


def cable_textures(identifier, suffix):
    side = f"rackcraft:block/{identifier}{suffix}"
    return {"particle": side, "side": side, "end": f"rackcraft:block/{identifier}_end{suffix}",
            "joint": f"rackcraft:block/{identifier}_joint{suffix}"}


def cable_core_model(identifier, suffix=""):
    # The sleeve is half a pixel proud of the cable so the joint reads as a separate collar.
    lo, hi = 7.5 - CABLE_HALF_WIDTH[identifier], 8.5 + CABLE_HALF_WIDTH[identifier]
    faces = {face: {"texture": "#joint", "uv": [0, 0, 16, 16]} for face in ("down", "up", "north", "south", "west", "east")}
    return {"parent": "minecraft:block/block", "textures": cable_textures(identifier, suffix),
            "elements": [{"from": [lo, lo, lo], "to": [hi, hi, hi], "faces": faces}]}


def cable_run(lo, hi, z0, z1, cap_faces):
    """A cable segment along the z axis. Side UVs take the radial profile from rows around v=8."""
    length = z1 - z0
    side = {"texture": "#side", "uv": [0, lo, length, hi]}
    faces = {"east": dict(side), "west": dict(side),
             "up": dict(side, rotation=90), "down": dict(side, rotation=90)}
    for face, cull in cap_faces.items():
        faces[face] = {"texture": "#end", "uv": [lo, lo, hi, hi]}
        if cull:
            faces[face]["cullface"] = face
    return {"from": [lo, lo, z0], "to": [hi, hi, z1], "faces": faces}


def cable_arm_model(identifier, suffix=""):
    lo, hi = 8 - CABLE_HALF_WIDTH[identifier], 8 + CABLE_HALF_WIDTH[identifier]
    return {"parent": "minecraft:block/block", "textures": cable_textures(identifier, suffix),
            "elements": [cable_run(lo, hi, 0, lo, {"north": True})]}


def cable_item_model(identifier):
    lo, hi = 8 - CABLE_HALF_WIDTH[identifier], 8 + CABLE_HALF_WIDTH[identifier]
    return {"parent": "minecraft:block/block", "textures": cable_textures(identifier, ""),
            "elements": [cable_run(lo, hi, 0, 16, {"north": False, "south": False})],
            "display": {"gui": {"rotation": [30, 45, 0], "scale": [0.8, 0.8, 0.8]}}}


def cable_blockstate(identifier):
    parts = []
    for cut, suffix in (("false", ""), ("true", "_cut")):
        parts.append({"when": {"cut": cut}, "apply": {"model": f"rackcraft:block/{identifier}_core{suffix}"}})
        for direction, rotation in ARM_ROTATIONS.items():
            parts.append({"when": {direction: "true", "cut": cut},
                          "apply": {"model": f"rackcraft:block/{identifier}_arm{suffix}", **rotation}})
    return {"multipart": parts}


def machine_model(identifier, front):
    """Full cube with distinct front (north), back (south), sides, top and bottom; blockstates rotate it."""
    texture = lambda suffix: f"rackcraft:block/{identifier}_{suffix}"
    faces = {"north": "#front", "south": "#back", "east": "#side", "west": "#side", "up": "#top", "down": "#bottom"}
    return {"parent": "minecraft:block/block", "textures": {
        "front": texture(front), "back": texture("back"), "side": texture("side"),
        "top": texture("top"), "bottom": texture("bottom"), "particle": texture("side"),
    }, "elements": [{"from": [0, 0, 0], "to": [16, 16, 16], "faces": {
        face: {"texture": ref, "cullface": face} for face, ref in faces.items()}}]}


def belt_model(identifier):
    """A low belt between two rails, facing north (items run toward the north edge); blockstates rotate it."""
    texture = lambda suffix: f"rackcraft:block/{identifier}_{suffix}"
    side = {"texture": "#side", "uv": [0, 11, 16, 15]}
    rail = {face: {"texture": "#side", "uv": [0, 10, 16, 11]} for face in ("north", "south", "east", "west", "up")}
    return {"parent": "minecraft:block/block", "textures": {
        "top": texture("top"), "side": texture("side"), "bottom": texture("bottom"), "particle": texture("side")},
        "elements": [
            {"from": [0, 0, 0], "to": [16, 4, 16], "faces": {
                "up": {"texture": "#top", "uv": [0, 0, 16, 16]},
                "down": {"texture": "#bottom", "cullface": "down"},
                "north": dict(side, cullface="north"), "south": dict(side, cullface="south"),
                "east": dict(side, cullface="east"), "west": dict(side, cullface="west")}},
            {"from": [0, 4, 0], "to": [1, 5, 16], "faces": rail},
            {"from": [15, 4, 0], "to": [16, 5, 16], "faces": rail},
        ],
        "display": {"gui": {"rotation": [30, 225, 0], "scale": [0.625, 0.625, 0.625]}}}


def arm_model(identifier, lit, item=False):
    """A robot arm's pedestal, facing north: a hazard-striped floor plate, the turret column and a status beacon.
    The arm itself moves, so the client draws it; the item model adds a folded arm so it reads as a robot."""
    texture = lambda suffix: f"rackcraft:block/{identifier}_{suffix}"
    def box(lo, hi, top, sides):
        faces = {face: {"texture": sides} for face in ("north", "south", "east", "west")}
        faces["up"] = {"texture": top}
        faces["down"] = {"texture": sides}
        return {"from": lo, "to": hi, "faces": faces}
    elements = [
        box([2, 0, 2], [14, 3, 14], "#base", "#column"),
        box([4, 3, 4], [12, 10, 12], "#column", "#column"),
        box([11, 3, 11], [13, 6, 13], "#light", "#light"),
    ]
    if item:
        elements += [box([6.5, 10, 6.5], [9.5, 18, 9.5], "#column", "#column"),
                     box([6.5, 16, 3], [9.5, 19, 9.5], "#column", "#column"),
                     box([7, 13, 3], [9, 16, 5], "#light", "#light")]
    model = {"parent": "minecraft:block/block", "textures": {
        "base": texture("base"), "column": texture("column"), "light": texture("light_on" if lit else "light"),
        "particle": texture("column")}, "elements": elements}
    if item:
        model["display"] = {"gui": {"rotation": [30, 225, 0], "scale": [0.55, 0.55, 0.55], "translation": [0, -1.5, 0]}}
    return model


def solar_array_model(identifier, tracking):
    """One part of a 3x2 array: four posts under a panel. A tracking array stands on a central pivot with its panel
    tilted toward the sun, so a field of them reads differently from fixed ones."""
    texture = lambda suffix: f"rackcraft:block/{identifier}_{suffix}"
    def faces(top):
        result = {face: {"texture": "#frame"} for face in ("north", "south", "east", "west", "down")}
        result["up"] = {"texture": top}
        return result
    panel = {"from": [0, 6, 0], "to": [16, 8, 16], "faces": faces("#cells")}
    if tracking:
        panel["rotation"] = {"origin": [8, 7, 8], "axis": "x", "angle": 22.5}
        elements = [{"from": [7, 0, 7], "to": [9, 6, 9], "faces": faces("#frame")},
                    {"from": [5, 4, 5], "to": [11, 5.5, 11], "faces": faces("#frame")}, panel]
    else:
        elements = [{"from": [x, 0, z], "to": [x + 1, 6, z + 1], "faces": faces("#frame")} for x in (1, 14) for z in (1, 14)] + [panel]
    return {"parent": "minecraft:block/block", "textures": {"cells": texture("cells"), "frame": texture("frame"),
            "particle": texture("frame")}, "elements": elements,
            "display": {"gui": {"rotation": [30, 225, 0], "scale": [0.625, 0.625, 0.625]}}}


def pole_model(identifier):
    """A Tower Section: a round-ish white column ten pixels across."""
    texture = lambda suffix: f"rackcraft:block/{identifier}_{suffix}"
    faces = {face: {"texture": "#side"} for face in ("north", "south", "east", "west")}
    faces["up"] = {"texture": "#top", "cullface": "up"}
    faces["down"] = {"texture": "#top", "cullface": "down"}
    return {"parent": "minecraft:block/block", "textures": {"side": texture("side"), "top": texture("top"), "particle": texture("side")},
            "elements": [{"from": [3, 0, 3], "to": [13, 16, 13], "faces": faces}]}


def validate_guide(blocks, items):
    ids = [entry["id"] for entry in blocks + items]
    listed = [entry for chapter in CONTENT["guide"] for entry in chapter["entries"]]
    missing = sorted(set(ids) - set(listed))
    unknown = sorted(set(listed) - set(ids))
    duplicated = sorted({entry for entry in listed if listed.count(entry) > 1})
    if missing or unknown or duplicated:
        raise ValueError(f"guide chapters out of sync: missing={missing} unknown={unknown} duplicated={duplicated}")
    for entry in blocks + items:
        if not entry.get("desc"):
            raise ValueError(f"missing guide description for {entry['id']}")


def java_list(values):
    return "List.of(" + ", ".join(json.dumps(value) for value in values) + ")"


def write_java(blocks, items):
    fuels = [entry for entry in blocks + items if entry.get("burn")]
    source = [
        "package dev.rackcraft.generated;",
        "",
        "import java.util.List;",
        "import java.util.Map;",
        "",
        "public final class ContentIds {",
        f"\tpublic static final List<String> BLOCK_IDS = {java_list([entry['id'] for entry in blocks])};",
        f"\tpublic static final List<String> ITEM_IDS = {java_list([entry['id'] for entry in items])};",
        f"\tpublic static final List<String> MACHINE_IDS = {java_list([entry['id'] for entry in blocks if entry.get('machine')])};",
        "\t/** Machines that form cube multiblocks (2x2x2 to 5x5x5) and have a FORMED block state. */",
        f"\tpublic static final List<String> ARRAY_IDS = {java_list([entry['id'] for entry in blocks if entry.get('array')])};",
        "\t/** Storage capacity in items for drives and tapes. */",
        "\tpublic static final Map<String, Long> DRIVE_CAPACITY = Map.ofEntries("
        + ", ".join(f"Map.entry({json.dumps(entry['id'])}, {entry['capacity']}L)" for entry in items if entry.get("capacity")) + ");",
        "\t/** Creative-only: no recipe, never sold at the Exchange, shown in the Rackcraft Creative tab. */",
        f"\tpublic static final List<String> CREATIVE_IDS = {java_list([entry['id'] for entry in blocks + items if entry.get('creative')])};",
        "\t/** Furnace burn time in ticks for Rackcraft fuels; registered with Fabric's FuelRegistry. */",
        "\tpublic static final Map<String, Integer> FUEL_TICKS = Map.ofEntries("
        + ", ".join(f"Map.entry({json.dumps(entry['id'])}, {entry['burn']})" for entry in fuels) + ");",
        "",
        "\tpublic record GuideChapter(String id, String icon, boolean fuelPage, List<String> entries) {}",
        "",
        "\tpublic static final List<GuideChapter> GUIDE_CHAPTERS = List.of(",
        ",\n".join(f"\t\t\tnew GuideChapter({json.dumps(chapter['id'])}, {json.dumps(chapter['icon'])}, "
                   f"{str(chapter.get('fuelPage', False)).lower()}, {java_list(chapter['entries'])})"
                   for chapter in CONTENT["guide"]) + ");",
        "",
        "\tprivate ContentIds() {}",
        "}",
        "",
    ]
    generated_java = ROOT / "src/main/java/dev/rackcraft/generated/ContentIds.java"
    generated_java.parent.mkdir(parents=True, exist_ok=True)
    generated_java.write_text("\n".join(source), encoding="utf-8")


# One site per this many chunks in eligible biomes, before the flat-ground check rejects some.
# Abandoned data centers: (variant, biome group, terrain adaptation, weight). The campus has its own, rarer set.
# Layouts live in DataCenterLayouts.java; the self-test checks the two lists agree.
DATA_CENTERS = [
    ("site_7", "temperate", "beard_thin", 6),
    ("server_closet", "temperate", "beard_thin", 5),
    ("container_farm", "dry", "beard_thin", 4),
    ("crypto_garage", "temperate", "beard_thin", 4),
    ("bunker", "bunker", "none", 3),
    ("flooded_hall", "wet", "beard_thin", 5),
    ("overgrown_colo", "jungle", "beard_thin", 5),
    ("arctic_vault", "cold", "beard_thin", 5),
    ("ai_lab", "temperate", "beard_thin", 4),
    ("content_mill", "temperate", "beard_thin", 4),
    ("solar_farm", "dry", "beard_thin", 4),
    ("tape_archive", "temperate", "beard_thin", 3),
]
CAMPUS = ("hyperscale_campus", "flat", "beard_box")
DATA_CENTER_BIOMES = {
    "temperate": ["plains", "sunflower_plains", "meadow", "forest", "flower_forest", "birch_forest", "old_growth_birch_forest",
                  "taiga", "savanna", "desert", "snowy_plains", "cherry_grove"],
    "dry": ["desert", "savanna", "savanna_plateau", "badlands", "plains", "sunflower_plains"],
    "bunker": ["plains", "forest", "birch_forest", "dark_forest", "taiga", "snowy_plains", "snowy_taiga", "savanna", "desert"],
    "wet": ["swamp", "mangrove_swamp"],
    "jungle": ["jungle", "sparse_jungle", "bamboo_jungle", "dark_forest"],
    "cold": ["snowy_plains", "snowy_taiga", "ice_spikes", "grove"],
    "flat": ["plains", "sunflower_plains", "savanna", "desert", "snowy_plains", "meadow"],
}
# Must match ContractTemplates.CLASSICS: clients keep asking for these, so copies found in ruins can be sold.
CLASSICS = [
    ("image", "a creeper at a job interview in crayon"), ("image", "a pig in a business suit as a stock photo"),
    ("image", "Steve's LinkedIn headshot as a blurry phone photo"), ("image", "a cat asleep on a server rack in pixel art"),
    ("image", "a fox stealing a GPU as a motivational poster"), ("homework", "the water cycle (Nether edition)"),
    ("essay", "five hundred words about gravel"), ("legal", "a lease for a dirt hut"),
    ("cover_letter", "a villager applying for any job but librarian"), ("tos", "Steve's minecart rentals"),
    ("patch_notes", "reality"),
]
MAINTENANCE_LOG = [
    "MAINTENANCE LOG\nSite 7\n\nA storm cut two lines and the site went dark. The racks are still loaded and the generator still has fuel.\n\nThe Repair Kit in this chest can splice them.",
    "1) Find the sparking cables. One is the red POWER cable beside the generator, the other the purple FIBER cable beside the router.\n\n2) Right-click each one with the Repair Kit.",
    "3) Open a rack. It should say Mining. RackCoin flows into one shared balance.\n\n4) Spend it at the Crypto Exchange by the door: diamonds, tools, almost anything.\n\nThe Field Manual explains the rest.",
]


def data_center_loot():
    # Each page is a JSON text component inside a single-quoted SNBT string, so escape twice:
    # backslashes for SNBT (it only understands \\ and quote escapes), then the quote itself.
    pages = ",".join("'" + json.dumps({"text": page}).replace("\\", "\\\\").replace("'", "\\'") + "'"
                     for page in MAINTENANCE_LOG)
    book_nbt = '{title:"Maintenance Log",author:"Site Engineer",pages:[' + pages + ']}'

    def item(name, low=1, high=1, weight=1):
        entry = {"type": "minecraft:item", "name": name, "weight": weight}
        if high > 1:
            entry["functions"] = [{"function": "minecraft:set_count", "count": {"type": "minecraft:uniform", "min": low, "max": high}}]
        return entry

    guaranteed = [item("rackcraft:repair_kit"), item("rackcraft:field_manual"), item("rackcraft:multimeter"),
                  {"type": "minecraft:item", "name": "minecraft:written_book",
                   "functions": [{"function": "minecraft:set_nbt", "tag": book_nbt}]}]
    return {"type": "minecraft:chest", "pools": [
        *({"rolls": 1, "entries": [entry]} for entry in guaranteed),
        {"rolls": {"type": "minecraft:uniform", "min": 3, "max": 5}, "entries": [
            item("minecraft:coal", 4, 10, 4), item("rackcraft:coke", 1, 4, 2), item("rackcraft:copper_wire", 2, 6, 3),
            item("rackcraft:silicon", 2, 6, 3), item("rackcraft:circuit_board", 1, 2, 2), item("rackcraft:steel_ingot", 2, 5, 2),
            item("rackcraft:pi_node", 1, 1, 1), item("minecraft:emerald", 1, 2, 1)]},
    ]}


def loot_item(name, low=1, high=1, weight=1, nbt=None):
    entry = {"type": "minecraft:item", "name": name, "weight": weight}
    functions = []
    if high > 1:
        functions.append({"function": "minecraft:set_count", "count": {"type": "minecraft:uniform", "min": low, "max": high}})
    if nbt:
        functions.append({"function": "minecraft:set_nbt", "tag": nbt})
    if functions:
        entry["functions"] = functions
    return entry


def generated_work(kind, prompt, quality, weight):
    """A finished image or document, as a chest might hold it. Quotes in prompts are escaped for SNBT."""
    item = "rackcraft:generated_image" if kind == "image" else "rackcraft:generated_document"
    model = "Nano Melon 2.4" if kind == "image" else "Gemerald 3.1 Pro"
    escaped = prompt.replace("\\", "\\\\").replace('"', '\\"')
    return loot_item(item, weight=weight, nbt=f'{{Kind:"{kind}",Prompt:"{escaped}",Quality:{quality},Model:"{model}"}}')


def chest_loot(rolls, entries, guaranteed=()):
    pools = [{"rolls": 1, "entries": [entry]} for entry in guaranteed]
    pools.append({"rolls": {"type": "minecraft:uniform", "min": rolls[0], "max": rolls[1]}, "entries": entries})
    return {"type": "minecraft:chest", "pools": pools}


def data_center_loot_tables():
    classics = [generated_work(kind, prompt, 55 + (index * 7) % 40, 1) for index, (kind, prompt) in enumerate(CLASSICS)]
    images = [entry for entry, (kind, _) in zip(classics, CLASSICS) if kind == "image"]
    documents = [entry for entry, (kind, _) in zip(classics, CLASSICS) if kind != "image"]
    return {
        "common": chest_loot((4, 7), [
            loot_item("minecraft:coal", 4, 12, 4), loot_item("rackcraft:coke", 1, 4, 3), loot_item("rackcraft:copper_wire", 2, 8, 4),
            loot_item("rackcraft:silicon", 2, 6, 3), loot_item("rackcraft:circuit_board", 1, 3, 3), loot_item("rackcraft:steel_ingot", 2, 6, 3),
            loot_item("rackcraft:cpu_chip", 1, 2, 2), loot_item("rackcraft:ram_module", 1, 2, 2), loot_item("rackcraft:pi_node", 1, 1, 1),
            loot_item("rackcraft:failed_module", 1, 2, 3), loot_item("rackcraft:repair_kit", 1, 1, 2),
            loot_item("rackcraft:drive_1k", 1, 1, 1), loot_item("rackcraft:field_manual", 1, 1, 1), loot_item("minecraft:paper", 2, 8, 2),
            loot_item("minecraft:emerald", 1, 3, 1)]),
        "garage": chest_loot((4, 6), [
            loot_item("minecraft:honey_bottle", 2, 5, 4), loot_item("rackcraft:gpu_chip", 1, 2, 3), loot_item("minecraft:gold_nugget", 3, 9, 3),
            loot_item("rackcraft:failed_module", 1, 2, 2), loot_item("rackcraft:coke", 2, 6, 3), loot_item("rackcraft:copper_wire", 2, 6, 2),
            loot_item("minecraft:redstone", 2, 8, 2), *images]),
        "vault": chest_loot((3, 5), [
            loot_item("minecraft:diamond", 1, 3, 3), loot_item("minecraft:emerald", 2, 6, 3), loot_item("minecraft:gold_ingot", 2, 6, 3),
            loot_item("rackcraft:gpu_chip", 1, 1, 2), loot_item("rackcraft:failed_module", 1, 2, 2),
            loot_item("rackcraft:drive_4k", 1, 1, 1), loot_item("rackcraft:cryo_coil", 1, 1, 1), loot_item("rackcraft:freshwater_pump", 1, 1, 1)]),
        "ai_lab": chest_loot((4, 7), [
            loot_item("rackcraft:crayons", 1, 1, 4), loot_item("rackcraft:shackles", 1, 1, 3), loot_item("minecraft:paper", 6, 16, 4),
            loot_item("minecraft:ink_sac", 2, 6, 3), loot_item("rackcraft:cpu_chip", 1, 2, 1), loot_item("rackcraft:art_aggregate", 1, 3, 3),
            loot_item("rackcraft:text_corpus", 1, 3, 3), *classics], guaranteed=[loot_item("rackcraft:shackles")]),
        "content_mill": chest_loot((4, 7), [
            loot_item("minecraft:paper", 8, 24, 5), loot_item("minecraft:ink_sac", 2, 8, 4), loot_item("rackcraft:crayons", 1, 1, 3),
            loot_item("rackcraft:text_corpus", 1, 4, 4), loot_item("rackcraft:art_aggregate", 1, 4, 4), loot_item("rackcraft:shackles", 1, 2, 2),
            loot_item("minecraft:cookie", 2, 8, 3), loot_item("rackcraft:carbon_offset", 1, 3, 2), loot_item("minecraft:emerald", 1, 3, 1),
            *classics], guaranteed=[loot_item("rackcraft:text_corpus", 2, 4)]),
        "archive": chest_loot((4, 6), [
            loot_item("rackcraft:tape_cartridge", 1, 1, 4), loot_item("rackcraft:drive_1k", 1, 1, 3), loot_item("rackcraft:drive_4k", 1, 1, 2),
            loot_item("minecraft:book", 1, 3, 3), loot_item("minecraft:paper", 4, 12, 3),
            loot_item("rackcraft:blank_pattern", 1, 4, 3), *documents]),
        "office": chest_loot((4, 7), [
            loot_item("minecraft:paper", 6, 20, 4), loot_item("minecraft:book", 1, 2, 2), loot_item("minecraft:cookie", 2, 6, 2),
            loot_item("rackcraft:field_manual", 1, 1, 2), loot_item("rackcraft:multimeter", 1, 1, 2), loot_item("rackcraft:repair_kit", 1, 1, 3),
            loot_item("rackcraft:ram_module", 1, 2, 2), loot_item("minecraft:emerald", 1, 4, 2), *documents, *documents]),
    }


def data_center_worldgen():
    """Structures, their sets, the biome tags they spawn in, and #rackcraft:data_centers for /locate."""
    for group, biomes in DATA_CENTER_BIOMES.items():
        write_json(RESOURCES / f"data/rackcraft/tags/worldgen/biome/has_structure/data_center_{group}.json",
                   {"replace": False, "values": [f"minecraft:{biome}" for biome in biomes]})
    for variant, group, adaptation, _ in DATA_CENTERS + [CAMPUS + (0,)]:
        write_json(RESOURCES / f"data/rackcraft/worldgen/structure/{variant}.json", {
            "type": "rackcraft:data_center", "variant": variant,
            "biomes": f"#rackcraft:has_structure/data_center_{group}",
            "step": "surface_structures", "spawn_overrides": {}, "terrain_adaptation": adaptation})
    write_json(RESOURCES / "data/rackcraft/worldgen/structure_set/data_centers.json", {
        "structures": [{"structure": f"rackcraft:{variant}", "weight": weight} for variant, _, _, weight in DATA_CENTERS],
        "placement": {"type": "minecraft:random_spread", "spacing": 40, "separation": 16, "salt": 20761123}})
    write_json(RESOURCES / "data/rackcraft/worldgen/structure_set/hyperscale_campus.json", {
        "structures": [{"structure": f"rackcraft:{CAMPUS[0]}", "weight": 1}],
        "placement": {"type": "minecraft:random_spread", "spacing": 320, "separation": 96, "salt": 20761124}})
    write_json(RESOURCES / "data/rackcraft/tags/worldgen/structure/data_centers.json",
               {"replace": False, "values": [f"rackcraft:{variant}" for variant, *_ in DATA_CENTERS] + [f"rackcraft:{CAMPUS[0]}"]})
    for name, table in data_center_loot_tables().items():
        write_json(RESOURCES / f"data/rackcraft/loot_tables/chests/data_center/{name}.json", table)


def item_result(recipe):
    return {"item": f"rackcraft:{recipe['result']}", "count": recipe.get("count", 1)}


def recipe_json(recipe):
    kind = recipe["kind"]
    result = item_result(recipe)
    if kind in ("smelting", "blasting"):
        value = {
            "type": f"minecraft:{kind}",
            "ingredient": ingredient(recipe["ingredient"]),
            "result": result["item"],
            "experience": recipe.get("experience", 0.0),
            "cookingtime": recipe.get("ticks", 200 if kind == "smelting" else 100),
        }
    elif kind == "shapeless":
        value = {"type": "minecraft:crafting_shapeless", "ingredients": [ingredient(item) for item in recipe["ingredients"]], "result": result}
    else:
        value = {
            "type": "minecraft:crafting_shaped",
            "pattern": recipe["pattern"],
            "key": {key: ingredient(item) for key, item in recipe["key"].items()},
            "result": result,
        }
    return value


def main():
    blocks = CONTENT["blocks"]
    items = CONTENT["items"]
    all_ids = [entry["id"] for entry in blocks + items]
    if len(all_ids) != len(set(all_ids)):
        raise ValueError("Block and standalone item ids must be unique")

    validate_guide(blocks, items)
    write_java(blocks, items)

    lang = {}
    block_tag = []
    for block in blocks:
        identifier = block["id"]
        texture_path = f"rackcraft:block/{identifier}"
        lang[f"block.rackcraft.{identifier}"] = block["name"]
        block_tag.append(f"rackcraft:{identifier}")
        block_textures = RESOURCES / "assets/rackcraft/textures/block"
        if block.get("model") == "pipe":
            for suffix, cut in (("", False), ("_cut", True)):
                write_texture(block_textures / f"{identifier}{suffix}.png", textures.cable_side(block, cut))
                write_texture(block_textures / f"{identifier}_end{suffix}.png", textures.cable_end(block, cut))
                write_texture(block_textures / f"{identifier}_joint{suffix}.png", textures.cable_joint(block, cut))
                write_json(RESOURCES / f"assets/rackcraft/models/block/{identifier}_core{suffix}.json", cable_core_model(identifier, suffix))
                write_json(RESOURCES / f"assets/rackcraft/models/block/{identifier}_arm{suffix}.json", cable_arm_model(identifier, suffix))
            model = None
        elif block.get("model") == "belt":
            write_texture(block_textures / f"{identifier}_top.png", textures.belt_top(block))
            # The cleats move a pixel a tick, close to the belt's real speed of a block a second.
            write_json(block_textures / f"{identifier}_top.png.mcmeta", {"animation": {"frametime": 1}})
            write_texture(block_textures / f"{identifier}_side.png", textures.belt_side(block))
            write_texture(block_textures / f"{identifier}_bottom.png", textures.belt_bottom(block))
            model = belt_model(identifier)
        elif block.get("model") == "solar_array":
            tracking = identifier.endswith("tracking")
            write_texture(block_textures / f"{identifier}_cells.png", textures.array_cells(block, tracking))
            write_texture(block_textures / f"{identifier}_frame.png", textures.array_frame_texture(block))
            model = solar_array_model(identifier, tracking)
            write_json(RESOURCES / f"assets/rackcraft/models/block/{identifier}_on.json", model)
        elif block.get("model") == "pole":
            write_texture(block_textures / f"{identifier}_side.png", textures.pole_side(block))
            write_texture(block_textures / f"{identifier}_top.png", textures.pole_top(block))
            model = pole_model(identifier)
            write_json(RESOURCES / f"assets/rackcraft/models/block/{identifier}_on.json", model)
        elif block.get("model") == "arm":
            write_texture(block_textures / f"{identifier}_base.png", textures.arm_base(block))
            write_texture(block_textures / f"{identifier}_column.png", textures.arm_column(block))
            write_texture(block_textures / f"{identifier}_light.png", textures.arm_light(block, False))
            write_texture(block_textures / f"{identifier}_light_on.png", textures.arm_light(block, True))
            entity_texture = RESOURCES / f"assets/rackcraft/textures/entity/{identifier}.png"
            entity_texture.parent.mkdir(parents=True, exist_ok=True)
            entity_texture.write_bytes(textures.arm_parts(block))
            model = arm_model(identifier, False)
            write_json(RESOURCES / f"assets/rackcraft/models/block/{identifier}_on.json", arm_model(identifier, True))
            write_json(RESOURCES / f"assets/rackcraft/models/block/{identifier}_item.json", arm_model(identifier, False, item=True))
        elif identifier in MACHINE_IDS:
            for suffix, frames in textures.machine_textures(block).items():
                write_texture(block_textures / f"{identifier}_{suffix}.png", frames)
            model = machine_model(identifier, "front")
            write_json(RESOURCES / f"assets/rackcraft/models/block/{identifier}_on.json", machine_model(identifier, "front_on"))
            if block.get("front") == "rack":
                for alert in ("warn", "fault"):
                    write_json(RESOURCES / f"assets/rackcraft/models/block/{identifier}_{alert}.json", machine_model(identifier, f"front_{alert}"))
            if block.get("array"):
                kinds = ("formed",) + (("port",) if identifier not in NO_PORT else ())
                for suffix in [f"{kind}{size}{lit}" for kind in kinds for size in ("", "_large", "_mega") for lit in ("", "_on")]:
                    write_json(RESOURCES / f"assets/rackcraft/models/block/{identifier}_{suffix}.json", {
                        "parent": "minecraft:block/cube_all", "textures": {"all": f"rackcraft:block/{identifier}_{suffix}"}})
        else:
            write_texture(block_textures / f"{identifier}.png", textures.block_texture(block))
            model = {"parent": "minecraft:block/cube_all", "textures": {"all": texture_path}}
        if model is not None:
            write_json(RESOURCES / f"assets/rackcraft/models/block/{identifier}.json", model)
        if identifier in MACHINE_IDS:
            rotations = {"north": 0, "east": 90, "south": 180, "west": 270}
            variants = {
                f"facing={facing},lit={str(lit).lower()}": {
                    "model": f"rackcraft:block/{identifier}{'_on' if lit else ''}", "y": rotation}
                for facing, rotation in rotations.items()
                for lit in (False, True)
            }
            if block.get("array"):
                # A formed cube shows its casing on every face; facing doesn't matter then. Its port core (where the
                # products gather) gets the port face; Grid-Scale Batteries make nothing, so they have no port.
                # Bigger cubes wear their own casing: scale 1 for 6x6x6 to 9x9x9, scale 2 for 10x10x10.
                sizes = {0: "", 1: "_large", 2: "_mega"}
                def formed_model(key, port, scale):
                    lit = "_on" if "lit=true" in key else ""
                    kind = "port" if port and identifier not in NO_PORT else "formed"
                    return {"model": f"rackcraft:block/{identifier}_{kind}{sizes[scale]}{lit}"}
                variants = {f"{key},formed={str(formed).lower()},port={str(port).lower()},scale={scale}": (
                    formed_model(key, port, scale) if formed else value)
                    for key, value in variants.items() for formed in (False, True) for port in (False, True)
                    for scale in (0, 1, 2)}
            if block.get("model") == "solar_array":
                # Six parts of one 3x2 array share the model; the blockstate rotates it with the array.
                variants = {f"{key},part={part}": value for key, value in variants.items() for part in range(6)}
            if identifier == "server_rack":
                # Health: amber for a slowed rack, red for a stopped one, whether or not it is lit. Only the Server Rack
                # has the property; the Creative Rack shares its face but not its health.
                variants = {f"{key},health={health}": (value if health == "ok" else
                    {"model": f"rackcraft:block/{identifier}_{health}", "y": value["y"]})
                    for key, value in variants.items() for health in ("ok", "warn", "fault")}
        elif block.get("model") == "belt":
            variants = {f"facing={facing}": {"model": f"rackcraft:block/{identifier}", "y": rotation}
                        for facing, rotation in {"north": 0, "east": 90, "south": 180, "west": 270}.items()}
        else:
            variants = {"": {"model": f"rackcraft:block/{identifier}"}}
        if identifier in CABLE_IDS:
            write_json(RESOURCES / f"assets/rackcraft/blockstates/{identifier}.json", cable_blockstate(identifier))
            write_json(RESOURCES / f"assets/rackcraft/models/item/{identifier}.json", cable_item_model(identifier))
        else:
            write_json(RESOURCES / f"assets/rackcraft/blockstates/{identifier}.json", {"variants": variants})
            write_json(RESOURCES / f"assets/rackcraft/models/item/{identifier}.json", {
                "parent": f"rackcraft:block/{identifier}{'_item' if block.get('model') == 'arm' else ''}"
            })
        drops = block.get("drops", identifier)
        if drops != identifier:
            loot = {"type": "minecraft:block", "pools": [{
                "rolls": 1,
                "entries": [{"type": "minecraft:alternatives", "children": [
                    {"type": "minecraft:item", "name": f"rackcraft:{identifier}", "conditions": [{
                        "condition": "minecraft:match_tool",
                        "predicate": {"enchantments": [{
                            "enchantment": "minecraft:silk_touch",
                            "levels": {"min": 1}
                        }]}
                    }]},
                    {"type": "minecraft:item", "name": f"rackcraft:{drops}", "functions": [
                        {"function": "minecraft:apply_bonus", "enchantment": "minecraft:fortune", "formula": "minecraft:ore_drops"},
                        {"function": "minecraft:explosion_decay"}
                    ]}
                ]}],
                "conditions": [{"condition": "minecraft:survives_explosion"}]
            }]}
        elif block.get("model") == "solar_array":
            # One item for the whole 3x2 array: only its first part drops (breaking any part breaks them all).
            loot = {"type": "minecraft:block", "pools": [{"rolls": 1, "entries": [{"type": "minecraft:item", "name": f"rackcraft:{drops}"}],
                    "conditions": [{"condition": "minecraft:block_state_property", "block": f"rackcraft:{identifier}",
                                    "properties": {"part": "0"}}]}]}
        else:
            loot = {"type": "minecraft:block", "pools": [{"rolls": 1, "entries": [{"type": "minecraft:item", "name": f"rackcraft:{drops}"}]}]}
        write_json(RESOURCES / f"data/rackcraft/loot_tables/blocks/{identifier}.json", loot)

    for item in items:
        identifier = item["id"]
        lang[f"item.rackcraft.{identifier}"] = item["name"]
        write_texture(RESOURCES / f"assets/rackcraft/textures/item/{identifier}.png", textures.item_texture(item))
        write_json(RESOURCES / f"assets/rackcraft/models/item/{identifier}.json", {
            "parent": "minecraft:item/generated",
            "textures": {"layer0": f"rackcraft:item/{identifier}"}
        })

    lang.update({
        "itemGroup.rackcraft.main": "Rackcraft",
        "screen.rackcraft.rack": "Rack telemetry",
        "screen.rackcraft.single_slot": "Machine input",
        "screen.rackcraft.machine_status": "Machine status",
        "screen.rackcraft.controller": "Facility controller",
        "screen.rackcraft.monitor_wall": "Facility monitor",
        "command.rackcraft.event": "Rackcraft event started",
        "event.rackcraft.utility_outage": "Utility outage",
        "event.rackcraft.cooling_failure": "Cooling failure",
        "event.rackcraft.hardware_failure": "Hardware failure",
        "event.rackcraft.cable_cut": "Cable cut",
        "event.rackcraft.heat_wave": "Heat wave",
        "event.rackcraft.surge": "Power surge",
        "guide.rackcraft.title": "Rackcraft Field Manual",
        "guide.rackcraft.contents": "Contents",
        "guide.rackcraft.back": "Back",
        "guide.rackcraft.page": "%s / %s",
        "guide.rackcraft.recipes": "Recipes",
        "guide.rackcraft.recipes_next": "Recipes continue on the next page.",
        "guide.rackcraft.continued": "%s (continued)",
        "guide.rackcraft.no_recipe": "No recipe. Found in the world or produced by machines.",
        "guide.rackcraft.crafting": "Crafting",
        "guide.rackcraft.smelting": "Furnace, %s s",
        "guide.rackcraft.blasting": "Blast Furnace, %s s",
        "guide.rackcraft.burn_time": "Diesel runtime: %s s",
        "guide.rackcraft.fuel_notes": CONTENT["fuelNotes"],
        "guide.rackcraft.click_to_open": "Click to open page",
        "tooltip.rackcraft.fuel": "Fuel: %s s of diesel runtime",
        "tooltip.rackcraft.field_manual": "Right-click to read",
        "tooltip.rackcraft.multimeter": "Right-click a machine or cable to measure; sneak-right-click a machine to rotate",
        "screen.rackcraft.exchange": "Crypto Exchange",
        "exchange.rackcraft.balance": "Balance: %s RC",
        "exchange.rackcraft.mining": "+%s RC/s from %s of %s racks",
        "exchange.rackcraft.not_mining": "No racks are mining. Open a rack to see why.",
        "exchange.rackcraft.price": "Price: %s RC",
        "exchange.rackcraft.eta": "Affordable in about %s at the current rate",
        "exchange.rackcraft.bulk": "Shift-click to buy %s",
        "exchange.rackcraft.footer": "Racks mine RackCoin while powered, cooled and linked to a router by fiber.",
        "exchange.rackcraft.category.resources": "Resources",
        "exchange.rackcraft.category.rare": "Rare",
        "exchange.rackcraft.category.parts": "Parts",
        "exchange.rackcraft.category.all": "All Items",
        "exchange.rackcraft.search": "Search items...",
        "exchange.rackcraft.price_each": "Price: %s RC each",
        "exchange.rackcraft.bulk_all": "Shift-click: buy %s. Ctrl-click: buy a stack",
        "exchange.rackcraft.footer_all": "Almost every survival item is for sale. Scroll to browse; dimmed items are beyond your balance.",
        "rack_status.rackcraft.mining": "Mining %s RC/s",
        "rack_status.rackcraft.mining.hint": "Everything is working. RackCoin goes to the shared balance; spend it at a Crypto Exchange.",
        "rack_status.rackcraft.throttled": "Mining %s RC/s (throttled)",
        "rack_status.rackcraft.throttled.hint": "The intake is above 27 C, so the rack slows down. Check where its heat goes (To loop / To air): put a Rear-Door Cooler or CDU on its back (either catches the exhaust into the loop), add sinks to its coolant loop, or pull hot air away with a CRAC unit or exhaust fan. A solid block behind a rack pushes its exhaust out sideways, often straight into the next aisle.",
        "rack_status.rackcraft.network_limited": "Mining %s RC/s (bandwidth-limited)",
        "rack_status.rackcraft.network_limited.hint": "The routers on this fiber network can't carry every rack. Add another Uplink Router or a Core Router.",
        "rack_status.rackcraft.empty": "Idle: no modules",
        "rack_status.rackcraft.empty.hint": "Put Pi Nodes, 1U Servers, ASIC Miners, GPU Blades, Tensor Accelerators or Quantum Cores into the eight bays on the left. Each module takes one bay.",
        "rack_status.rackcraft.tripped": "Stopped: breaker tripped",
        "rack_status.rackcraft.tripped.hint": "Power dropped below 50%. The rack restarts once power is back and the intake is under 32 C.",
        "rack_status.rackcraft.no_power": "Stopped: no power",
        "rack_status.rackcraft.no_power.hint": "Connect this rack to a generator, solar panel or utility intake with Power Cable.",
        "rack_status.rackcraft.needs_cdu": "Stopped: Quantum Core needs a CDU",
        "rack_status.rackcraft.needs_cdu.hint": "Place a Coolant Distribution Unit directly beside this rack.",
        "rack_status.rackcraft.overheated": "Stopped: overheated",
        "rack_status.rackcraft.overheated.hint": "The intake air is 40 C or hotter. Heat that doesn't go into a coolant loop goes into the room: catch it with a Rear-Door Cooler on the rack's back, take it out of the air with a CRAC unit or exhaust fan, and keep hot exhaust away from intakes. If To loop is less than the rack's heat, its loop is overloaded: add sinks.",
        "rack_status.rackcraft.no_network": "Not mining: offline",
        "rack_status.rackcraft.no_network.hint": "Run Fiber Cable from this rack to an Uplink Router so it can mine.",
        "generator.rackcraft.running": "Running: supplying the grid",
        "generator.rackcraft.no_fuel": "No fuel: add coal, coke or biodiesel",
        "generator.rackcraft.no_fuel_cell": "No fuel cell loaded",
        "generator.rackcraft.spinning_up": "Spinning up: %s%%",
        "generator.rackcraft.standby": "Standby: other sources cover demand",
        "screen.rackcraft.suppression_hint": "Load a Suppression Canister into the slot above.",
        "tooltip.rackcraft.drive": "Hot storage: goes in a Storage Array",
        "tooltip.rackcraft.tape": "Cold storage: goes in a Tape Library, 2 s to read",
        "storage.rackcraft.mounting": "Mounting tape for %s x %s...",
        "storage.rackcraft.retrieved": "Retrieved %s x %s from tape",
        "storage.rackcraft.job_done": "Autocraft finished: %s x %s",
        "storage.rackcraft.job_started": "Autocrafting %s x %s (%s crafts)",
        "storage.rackcraft.job_missing": "Can't craft %s: missing %s",
        "storage.rackcraft.no_pattern": "No Recipe Pattern for %s is stored on this network",
        "storage.rackcraft.offline": "No storage reachable: check power, fiber and that the arrays are not overheated",
        "storage.rackcraft.linked": "Linked to the transmitter at %s",
        "storage.rackcraft.not_linked": "Sneak-right-click a Wireless Transmitter to link this terminal",
        "storage.rackcraft.out_of_range": "Out of range: %s blocks away, transmitter reaches %s",
        "storage.rackcraft.other_dimension": "The transmitter is in another dimension; it needs the multidimensional upgrade",
        "storage.rackcraft.transmitter_offline": "The linked transmitter is unpowered or gone",
        "storage.rackcraft.encoded": "Pattern encoded",
        "storage.rackcraft.status.crafting": "Crafting",
        "storage.rackcraft.status.crafting.hint": "Server racks on this fiber network are lending compute. Each point of compute makes 0.25 crafts per second.",
        "storage.rackcraft.status.waiting": "Waiting",
        "storage.rackcraft.status.waiting.hint": "An ingredient ran out mid-job, probably taken out of storage. Put more in and the job carries on.",
        "storage.rackcraft.status.no_compute": "No compute",
        "storage.rackcraft.status.no_compute.hint": "No rack can lend compute: there are no working racks on this storage's fiber network, and no online cluster (racks with an uplink router) set to Auto is free. ASIC Miners can't craft.",
        "storage.rackcraft.status.bad_pattern": "Bad pattern",
        "storage.rackcraft.status.bad_pattern.hint": "A pattern no longer matches any recipe. Encode it again.",
        "storage.rackcraft.status.offline": "Offline",
        "storage.rackcraft.status.offline.hint": "Storage is offline: check power, fiber and that the arrays are not overheated.",
        "storage.rackcraft.status.asleep": "Unloaded",
        "storage.rackcraft.status.asleep.hint": "The storage network's area isn't loaded, so the job is paused until it is.",
        "storage.rackcraft.status.queued": "Queued",
        "storage.rackcraft.status.queued.hint": "Starts on the next simulation step.",
        "rack_status.rackcraft.crafting": "Busy: autocrafting",
        "rack_status.rackcraft.crafting.hint": "This rack is lending its compute to an autocrafting job, so it is not mining. It resumes when the job finishes.",
        "rack_status.rackcraft.generating": "Busy: AI contract work",
        "rack_status.rackcraft.generating.hint": "This rack is generating work for an AI contract, which pays better than mining. It goes back to mining when the item is finished.",
        "rack_status.rackcraft.training": "Busy: training a model",
        "rack_status.rackcraft.training.hint": "This rack is training an AI model on uploaded data. Set its cluster to Mining at an Operations Terminal to keep it mining instead.",
        "rack_status.rackcraft.booting": "Booting: %s%% (%s RC/s so far)",
        "rack_status.rackcraft.booting.hint": "Racks start slowly once they have power: a couple of seconds per Pi Node, four per 1U Server, five per ASIC, eight per GPU Blade or Tensor Accelerator and fifteen per Quantum Core, so a full rack of Quantum Cores takes two minutes. Power, mining and heat ramp up as it boots. Losing power means booting from cold again.",
        "rack_status.rackcraft.researching": "Busy: R&D",
        "rack_status.rackcraft.researching.hint": "This rack is working on a research project or a frontier training run from the Operations Terminal's R&D tab, so it is not mining. Set its cluster to Mining at the terminal to keep it mining instead.",
        "rack_status.rackcraft.leased": "Busy: leased to a client",
        "rack_status.rackcraft.leased.hint": "This rack is serving a Compute Lease. Keep it powered and cool: the client only pays in full if the lease's uptime guarantee is met.",
        "rack_status.rackcraft.needs_water": "Stopped: needs liquid cooling",
        "rack_status.rackcraft.needs_water.hint": "ASIC Miners, GPU Blades, Tensor Accelerators and Quantum Cores are liquid-cooled. Run Coolant Pipe from this rack to a heat sink: a Cooling Tower, Dry Cooler, Chiller or Water Heat Exchanger. The pipe carries 85% of their heat away.",
        "transmitter.rackcraft.level": "Level %s: %s",
        "transmitter.rackcraft.range_blocks": "%s block range",
        "transmitter.rackcraft.range_infinite": "unlimited range in this dimension",
        "transmitter.rackcraft.range_multidimensional": "unlimited range, every dimension",
        "transmitter.rackcraft.upgrade_rc": "Upgrade: %s RC",
        "transmitter.rackcraft.upgrade_items": "Upgrade with items",
        "transmitter.rackcraft.max": "Fully upgraded",
        "transmitter.rackcraft.needs": "Items: %s",
        "transmitter.rackcraft.link_hint": "Sneak-right-click this block with a Wireless Terminal to link it.",
        "transmitter.rackcraft.cannot_afford": "Not enough RackCoin",
        "transmitter.rackcraft.missing_items": "You don't have the items",
        "creative.rackcraft.output_kw": "Power output",
        "creative.rackcraft.mining_rate": "Mining rate",
        "creative.rackcraft.draw_kw": "Test load drawn from the grid",
        "creative.rackcraft.target_c": "Air temperature",
        "creative.rackcraft.bandwidth": "Bandwidth",
        "creative.rackcraft.apply": "Apply",
        "creative.rackcraft.saved": "Saved.",
        "creative.rackcraft.invalid": "Not a number: %s",
        "creative.rackcraft.locked": "Only players in creative mode or operators can change these values.",
        "creative.rackcraft.live_power": "Supplying %s; network demand %s",
        "creative.rackcraft.live_rack": "Mining %s RC/s; drawing %s",
        "creative.rackcraft.live_cooler": "Holds the air in front of and behind this block at the set temperature. Air can't go below the world's ambient temperature (%s C).",
        "creative.rackcraft.live_router": "Adds this bandwidth to every rack on its fiber network.",
        "effect.rackcraft.dizzy": "Smog Dizziness",
        "effect.rackcraft.coughing": "Smoker's Cough",
        "effect.rackcraft.radiation": "Radiation Sickness",
        "entity.rackcraft.maintenance_drone": "Maintenance Drone",
        "entity.rackcraft.construction_drone": "Construction Drone",
        "entity.rackcraft.rocket": "Rocket",
        "screen.rackcraft.workcell": "Robot",
    })
    for entry in blocks + items:
        lang[f"guide.rackcraft.entry.{entry['id']}"] = entry["desc"]
    for chapter in CONTENT["guide"]:
        lang[f"guide.rackcraft.chapter.{chapter['id']}"] = chapter["title"]
        lang[f"guide.rackcraft.chapter.{chapter['id']}.intro"] = chapter["intro"]
    for name, values in CONTENT["itemTags"].items():
        write_json(RESOURCES / f"data/rackcraft/tags/items/{name}.json", {"replace": False, "values": values})

    for recipe in CONTENT["recipes"]:
        write_json(RESOURCES / f"data/rackcraft/recipes/{recipe['id']}.json", recipe_json(recipe))
    write_json(RESOURCES / "assets/rackcraft/lang/en_us.json", lang)
    write_json(RESOURCES / "data/minecraft/tags/blocks/mineable/pickaxe.json", {"replace": False, "values": block_tag})

    write_json(RESOURCES / "data/rackcraft/worldgen/configured_feature/bauxite_ore.json", {
        "type": "minecraft:ore",
        "config": {"size": 7, "discard_chance_on_air_exposure": 0.0, "targets": [
            {"target": {"predicate_type": "minecraft:tag_match", "tag": "minecraft:stone_ore_replaceables"}, "state": {"Name": "rackcraft:bauxite_ore"}},
            {"target": {"predicate_type": "minecraft:tag_match", "tag": "minecraft:deepslate_ore_replaceables"}, "state": {"Name": "rackcraft:bauxite_ore"}}
        ]}
    })
    write_json(RESOURCES / "data/rackcraft/worldgen/placed_feature/bauxite_ore.json", {
        "feature": "rackcraft:bauxite_ore",
        "placement": [
            {"type": "minecraft:count", "count": 8},
            {"type": "minecraft:in_square"},
            {"type": "minecraft:height_range", "height": {
                "type": "minecraft:uniform",
                "min_inclusive": {"absolute": 0},
                "max_inclusive": {"absolute": 64}
            }},
            {"type": "minecraft:biome"}
        ]
    })
    write_json(RESOURCES / "data/rackcraft/worldgen/configured_feature/uranium_ore.json", {
        "type": "minecraft:ore",
        "config": {"size": 5, "discard_chance_on_air_exposure": 0.5, "targets": [
            {"target": {"predicate_type": "minecraft:tag_match", "tag": "minecraft:deepslate_ore_replaceables"}, "state": {"Name": "rackcraft:uranium_ore"}},
            {"target": {"predicate_type": "minecraft:tag_match", "tag": "minecraft:stone_ore_replaceables"}, "state": {"Name": "rackcraft:uranium_ore"}}
        ]}
    })
    write_json(RESOURCES / "data/rackcraft/worldgen/placed_feature/uranium_ore.json", {
        "feature": "rackcraft:uranium_ore",
        "placement": [
            {"type": "minecraft:count", "count": 6},
            {"type": "minecraft:in_square"},
            {"type": "minecraft:height_range", "height": {
                "type": "minecraft:uniform",
                "min_inclusive": {"absolute": -64},
                "max_inclusive": {"absolute": 16}
            }},
            {"type": "minecraft:biome"}
        ]
    })
    write_json(RESOURCES / "data/minecraft/tags/blocks/needs_iron_tool.json", {"replace": False, "values": ["rackcraft:uranium_ore"]})
    write_json(RESOURCES / "data/rackcraft/loot_tables/chests/abandoned_data_center.json", data_center_loot())
    data_center_worldgen()
    effect_textures = RESOURCES / "assets/rackcraft/textures/mob_effect"
    effect_textures.mkdir(parents=True, exist_ok=True)
    for effect in ("dizzy", "coughing", "radiation"):
        (effect_textures / f"{effect}.png").write_bytes(textures.effect_icon(effect))
    # Armor materials in 1.20.1 can only name textures in the minecraft namespace.
    armor = RESOURCES / "assets/minecraft/textures/models/armor/rackcraft_respirator_layer_1.png"
    armor.parent.mkdir(parents=True, exist_ok=True)
    armor.write_bytes(textures.respirator_armor_layer())
    write_json(RESOURCES / "data/rackcraft/tags/blocks/airflow_blocking.json", {
        "replace": False,
        "values": [f"rackcraft:{identifier}" for identifier in sorted(AIRFLOW_BLOCKING)]
    })
    entity_textures = RESOURCES / "assets/rackcraft/textures/entity"
    entity_textures.mkdir(parents=True, exist_ok=True)
    (entity_textures / "maintenance_drone.png").write_bytes(textures.drone_parts())
    (entity_textures / "rocket.png").write_bytes(textures.rocket_parts())
    (entity_textures / "wind_rotor.png").write_bytes(textures.rotor_parts())
    (entity_textures / "construction_drone.png").write_bytes(textures.site_drone_parts((232, 192, 48), "construction_drone", False))
    (entity_textures / "terraforming_drone.png").write_bytes(textures.site_drone_parts((122, 106, 72), "terraforming_drone", True))
    (entity_textures / "rocket_first_stage.png").write_bytes(textures.rocket_first_stage())
    (entity_textures / "rocket_second_stage.png").write_bytes(textures.rocket_second_stage())
    (entity_textures / "rocket_third_stage.png").write_bytes(textures.rocket_third_stage())
    # The Survey Satellite looks for this one.
    write_json(RESOURCES / "data/rackcraft/tags/worldgen/structure/campus.json",
               {"replace": False, "values": [f"rackcraft:{CAMPUS[0]}"]})
    remove_stale_textures(blocks)
    print(f"Generated assets for {len(blocks)} blocks, {len(items)} items, and {len(CONTENT['recipes'])} recipes.")


def remove_stale_textures(blocks):
    """Drop generated files that older layouts used and nothing references any more."""
    for block in blocks:
        stale = []
        if block["id"] in MACHINE_IDS:
            stale.append(RESOURCES / f"assets/rackcraft/textures/block/{block['id']}.png")
        if block["id"] in CABLE_IDS:
            stale += [RESOURCES / f"assets/rackcraft/models/block/{block['id']}.json",
                      RESOURCES / f"assets/rackcraft/models/block/{block['id']}_cut.json"]
        for path in stale:
            if path.exists():
                path.unlink()


if __name__ == "__main__":
    main()