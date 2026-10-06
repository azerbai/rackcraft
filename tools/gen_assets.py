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
CABLE_IDS = {"power_cable", "coolant_pipe", "fiber_cable"}
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
CABLE_HALF_WIDTH = {"power_cable": 2, "coolant_pipe": 3, "fiber_cable": 1}
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
DATA_CENTER_RARITY = 360
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
        elif identifier in MACHINE_IDS:
            for suffix, frames in textures.machine_textures(block).items():
                write_texture(block_textures / f"{identifier}_{suffix}.png", frames)
            model = machine_model(identifier, "front")
            write_json(RESOURCES / f"assets/rackcraft/models/block/{identifier}_on.json", machine_model(identifier, "front_on"))
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
        else:
            variants = {"": {"model": f"rackcraft:block/{identifier}"}}
        if identifier in CABLE_IDS:
            write_json(RESOURCES / f"assets/rackcraft/blockstates/{identifier}.json", cable_blockstate(identifier))
            write_json(RESOURCES / f"assets/rackcraft/models/item/{identifier}.json", cable_item_model(identifier))
        else:
            write_json(RESOURCES / f"assets/rackcraft/blockstates/{identifier}.json", {"variants": variants})
            write_json(RESOURCES / f"assets/rackcraft/models/item/{identifier}.json", {
                "parent": f"rackcraft:block/{identifier}"
            })
        drops = "raw_bauxite" if identifier == "bauxite_ore" else identifier
        if identifier == "bauxite_ore":
            loot = {"type": "minecraft:block", "pools": [{
                "rolls": 1,
                "entries": [{"type": "minecraft:alternatives", "children": [
                    {"type": "minecraft:item", "name": "rackcraft:bauxite_ore", "conditions": [{
                        "condition": "minecraft:match_tool",
                        "predicate": {"enchantments": [{
                            "enchantment": "minecraft:silk_touch",
                            "levels": {"min": 1}
                        }]}
                    }]},
                    {"type": "minecraft:item", "name": "rackcraft:raw_bauxite", "functions": [
                        {"function": "minecraft:apply_bonus", "enchantment": "minecraft:fortune", "formula": "minecraft:ore_drops"},
                        {"function": "minecraft:explosion_decay"}
                    ]}
                ]}],
                "conditions": [{"condition": "minecraft:survives_explosion"}]
            }]}
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
        "rack_status.rackcraft.throttled.hint": "The intake is above 27 C, so the rack slows down. Improve cooling to mine at full speed.",
        "rack_status.rackcraft.network_limited": "Mining %s RC/s (bandwidth-limited)",
        "rack_status.rackcraft.network_limited.hint": "The routers on this fiber network can't carry every rack. Add another Uplink Router or a Core Router.",
        "rack_status.rackcraft.empty": "Idle: no modules",
        "rack_status.rackcraft.empty.hint": "Put Pi Nodes, 1U Servers, ASIC Miners, GPU Blades or a Quantum Core into the bays on the left.",
        "rack_status.rackcraft.tripped": "Stopped: breaker tripped",
        "rack_status.rackcraft.tripped.hint": "Power dropped below 50%. The rack restarts once power is back and the intake is under 32 C.",
        "rack_status.rackcraft.no_power": "Stopped: no power",
        "rack_status.rackcraft.no_power.hint": "Connect this rack to a generator, solar panel or utility intake with Power Cable.",
        "rack_status.rackcraft.needs_cdu": "Stopped: Quantum Core needs a CDU",
        "rack_status.rackcraft.needs_cdu.hint": "Place a Coolant Distribution Unit directly beside this rack.",
        "rack_status.rackcraft.overheated": "Stopped: overheated",
        "rack_status.rackcraft.overheated.hint": "The intake air is 40 C or hotter. Separate the hot and cold aisles and add fans or a CRAC unit.",
        "rack_status.rackcraft.no_network": "Not mining: offline",
        "rack_status.rackcraft.no_network.hint": "Run Fiber Cable from this rack to an Uplink Router so it can mine.",
        "generator.rackcraft.running": "Running: supplying the grid",
        "generator.rackcraft.no_fuel": "No fuel: add coal, coke or biodiesel",
        "generator.rackcraft.no_fuel_cell": "No fuel cell loaded",
        "generator.rackcraft.spinning_up": "Spinning up: %s%%",
        "generator.rackcraft.standby": "Standby: other sources cover demand",
        "screen.rackcraft.suppression_hint": "Load a Suppression Canister into the slot above.",
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
    write_json(RESOURCES / "data/rackcraft/worldgen/configured_feature/abandoned_data_center.json", {
        "type": "rackcraft:abandoned_data_center", "config": {}
    })
    write_json(RESOURCES / "data/rackcraft/worldgen/placed_feature/abandoned_data_center.json", {
        "feature": "rackcraft:abandoned_data_center",
        "placement": [
            {"type": "minecraft:rarity_filter", "chance": DATA_CENTER_RARITY},
            {"type": "minecraft:in_square"},
            {"type": "minecraft:heightmap", "heightmap": "WORLD_SURFACE_WG"},
            {"type": "minecraft:biome"}
        ]
    })
    write_json(RESOURCES / "data/rackcraft/loot_tables/chests/abandoned_data_center.json", data_center_loot())
    write_json(RESOURCES / "data/rackcraft/tags/blocks/airflow_blocking.json", {
        "replace": False,
        "values": [f"rackcraft:{identifier}" for identifier in sorted(AIRFLOW_BLOCKING)]
    })
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