#!/usr/bin/env python3
"""Check generated resources against the Rackcraft content catalog."""

import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
RESOURCES = ROOT / "src/main/resources"
CONTENT = json.loads((ROOT / "tools/content.json").read_text(encoding="utf-8"))
LANG = json.loads((RESOURCES / "assets/rackcraft/lang/en_us.json").read_text(encoding="utf-8"))


def require(path):
    if not path.is_file():
        raise SystemExit(f"missing generated resource: {path.relative_to(ROOT)}")


for block in CONTENT["blocks"]:
    identifier = block["id"]
    if not block.get("machine"):
        require(RESOURCES / f"assets/rackcraft/textures/block/{identifier}.png")
    if block.get("model") == "pipe":
        for part in ("core", "arm", "core_cut", "arm_cut"):
            require(RESOURCES / f"assets/rackcraft/models/block/{identifier}_{part}.json")
    else:
        require(RESOURCES / f"assets/rackcraft/models/block/{identifier}.json")
    require(RESOURCES / f"assets/rackcraft/models/item/{identifier}.json")
    require(RESOURCES / f"assets/rackcraft/blockstates/{identifier}.json")
    require(RESOURCES / f"data/rackcraft/loot_tables/blocks/{identifier}.json")
    if f"block.rackcraft.{identifier}" not in LANG:
        raise SystemExit(f"missing language entry for block {identifier}")

for item in CONTENT["items"]:
    identifier = item["id"]
    require(RESOURCES / f"assets/rackcraft/textures/item/{identifier}.png")
    require(RESOURCES / f"assets/rackcraft/models/item/{identifier}.json")
    if f"item.rackcraft.{identifier}" not in LANG:
        raise SystemExit(f"missing language entry for item {identifier}")

for recipe in CONTENT["recipes"]:
    require(RESOURCES / f"data/rackcraft/recipes/{recipe['id']}.json")

require(RESOURCES / "data/rackcraft/worldgen/configured_feature/bauxite_ore.json")
require(RESOURCES / "data/rackcraft/worldgen/placed_feature/bauxite_ore.json")
require(RESOURCES / "data/rackcraft/tags/blocks/airflow_blocking.json")
require(ROOT / "src/main/java/dev/rackcraft/generated/ContentIds.java")

for block in CONTENT["blocks"]:
    if block.get("machine"):
        for suffix in ("side", "back", "top", "bottom", "front", "front_on"):
            require(RESOURCES / f"assets/rackcraft/textures/block/{block['id']}_{suffix}.png")
        require(RESOURCES / f"assets/rackcraft/models/block/{block['id']}_on.json")
    if f"guide.rackcraft.entry.{block['id']}" not in LANG:
        raise SystemExit(f"missing guide entry for block {block['id']}")

for item in CONTENT["items"]:
    if f"guide.rackcraft.entry.{item['id']}" not in LANG:
        raise SystemExit(f"missing guide entry for item {item['id']}")

for tag in CONTENT["itemTags"]:
    require(RESOURCES / f"data/rackcraft/tags/items/{tag}.json")

if len(CONTENT["blocks"]) != 34 or len(CONTENT["items"]) != 33:
    raise SystemExit("content catalog must contain exactly 34 blocks and 33 standalone items")

print(f"Rackcraft assets ok: {len(CONTENT['blocks'])} blocks, {len(CONTENT['items'])} standalone items, {len(CONTENT['recipes'])} recipes")