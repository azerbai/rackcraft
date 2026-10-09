# Batch D: Arms and Ruins

Read `00-conventions.md` first. Needs no other batch, but uses Batch A's `battery_cell` (create it here if A isn't built) and Batch C's wireless-power hook if present.

## Goal

Energy and hydrogen weapons that obey the mod's power/heat/hydrogen systems, plus the only places that fight back: a military base and hostile data centers. **No roaming PvE, no raids, no base-defence events.** The owner mostly plays Peaceful.

## Shared weapon rules

- Weapons are items with a **charge** (`battery_cell` consumption or a hydrogen tank) and a **heat meter** stored in item NBT. Firing adds heat; heat decays over time; at 100% the weapon locks out for a few seconds with a hiss and a clear message. Some use a **Coolant Pack** (a back-slot / inventory item; `coolant_pack`: refilled from a Coolant Pipe network or crafted) to cool faster; keep this to one consumable item, not a new slot system.
- Damage uses vanilla `DamageSource` plus small custom types if needed (`rackcraft.laser`, `rackcraft.plasma`); respect armor and Fire Protection where natural.
- Hit effects (lightning, burning, block scorching) must never destroy Rackcraft machine blocks or chests; they may ignite/scorch only plain terrain (config `weapons.blockDamage`, default on for scorching only, fire spread following vanilla `doFireTick`).
- All numeric values in config (`weapons.<name>.damage` etc.).
- Everything is Assembly Line made behind research, not on the Exchange; Hydrogen Canisters, Superconducting Wire, Graphene and battery cells are the main costs.
- Client: first-person visuals (beam, muzzle, projectile) use particles and simple renderers. Not visually verifiable; keep to vanilla particle types and existing helpers.

## Weapons

| Item | Behaviour | Ammo / power | Heat |
| --- | --- | --- | --- |
| **Hydrogen Flamethrower** | Hold to spray a short cone of fire, 8 blocks. Ignites mobs and flammable blocks, burns cobwebs. | 1 Hydrogen Canister per ~20 s of fire | low; lockout if held 30 s |
| **Arc Coil** | Short-range lightning: hits a mob and chains to up to 3 within 5 blocks. Trips the PDU breaker of any Rackcraft machine it touches (use the existing breaker mechanism; see `RESET_BREAKER` in `RackcraftNetworking` and `world/` PDU code) and damages Rackcraft robots and drones. | 1 Battery Cell per 8 shots | medium |
| **Railgun** | Hold to charge (up to 2 s), release to fire a hitscan that pierces mobs and up to 3 blocks of plain material; damage scales with charge. Big recoil and sound. | 1 Steel Slug per shot (`steel_slug`, Assembly Line: steel ingot, rivet) + 1 Battery Cell per 4 shots | high; long cooldown |
| **Plasma Rifle** | Semi-auto bolts with splash damage and a glowing scorch. | 1 Hydrogen Canister per magazine of 12 + a little power | very high; needs a Coolant Pack to sustain fire |
| **Lance Laser** | Continuous beam, long range, melts ice, glass, cobweb, sets fire. Strong vs armoured mobs. | Battery Cell drains in ~20 s of beam | extreme; short bursts only |
| **EMP Grenade** | Thrown; disables for 3 minutes every Rackcraft machine, drone, turret and guard robot in a 10-block radius (machines show "EMP'd"; PDUs trip; robots freeze). | consumable | none |

Gate: research **Directed Energy** (~6M RC + 80M general; requires Superconducting Wire / Advanced Materials) for Arc Coil, Railgun and Flamethrower; **Plasma Weapons** (~12M RC + 150M general) for Plasma Rifle, Lance Laser. EMP Grenade: with Directed Energy, cheap-ish (it's the key to the structures). Craft costs scarce and in line with Rule 1.

### Sentry Turrets
- Block `sentry_turret` (a machine): takes **power**, optionally **coolant** and **fiber** (fiber makes it appear on the Ops Terminal, where its targeting can be set: *All hostile*, *Only guards/scavengers*, *Off*). Fires one of the weapon types (variant per weapon: `laser_sentry`, `arc_sentry`, `railgun_sentry`). Uses the shared weapon heat model with its coolant loop as the cooler (a turret without coolant overheats quickly, which is the point).
- It only attacks monsters the game already considers hostile (`HostileEntity`) and the Batch D guards if hostile, **never** players, pets, villagers or its own owner's drones. Peaceful difficulty: monsters don't exist anyway.
- Must not scan every tick: use a 10-tick target scan, a 16-block range (config), and no loading of chunks.
- EMP Grenade and the Arc Coil disable it.

## Guards and AI rules (used by the structures)

Create `entity/GuardEntity.java` (a `PathAwareEntity` or `HostileEntity` subclass; check what `entity/` entities extend: the drones are plain `Entity`, so look at vanilla `ZombifiedPiglinEntity` for the angry-at-player logic and copy its structure). Two models, reusing renderers where possible: **Soldier** (humanoid, armoured) and **Security Robot** (blocky; for the base and the data centers).
- **Peaceful difficulty:** guards are calm and never attack unless the player attacks one; then that guard and all guards within 16 blocks turn on the attacker for a few minutes (the zombified piglin rule: `Angerable`).
- **Easy and Normal:** guards stand idle; they engage when the player comes within about **12 blocks** with line of sight.
- **Hard:** they fire on sight from about **20 blocks**.
- **Never roams:** guards have a home position and leash (a 24 to 40 block `setPositionTarget`/`WanderAroundFarGoal` limited to the structure), don't despawn while the structure is loaded, never spawn naturally, and don't count towards mob caps (spawn group `MISC`). Registered like other entities in `RcEntities.java`.
- They carry energy weapons (laser rifle, arc baton): reuse the player weapons' damage code with their own numbers, and they use projectiles/beams the player can see and dodge.
- They are **EMP-able**: frozen for 3 minutes.
- Drops: components and parts rather than top-tier hardware (Rule 1); the high-tier loot is in the structure.
- Leaving combat: guards reset and heal after losing the target for 30 s.

## Structures

Add new structures through the existing structure system: look at `Worldgen.java`, `world/structure/DataCenterStructure.java`, `DataCenterPiece.java`, `DataCenterLayouts.java` and `data/rackcraft/worldgen/structure*/*.json`, and mimic how `hyperscale_campus` is declared (extremely rare). Both new structure types must be located with `/rackcraft locate datacenter <variant>` style commands (see `RackcraftCommands.java`) and placeable with `/rackcraft structure place <variant>`.

**Real worldgen caveat:** block entities have no world during generation. Follow the existing code's handling for signs, chests and racks; the self-test's D6-style checks locate and generate each new variant for real. Test with a fresh seed too (delete `run/world`).

### D1. Military Base (`military_base`)
- Large (around 90 x 90): perimeter fence and gate, barracks, a motor pool, an armoury, a command bunker, and a **vault** holding the best loot in the mod (see Loot). Guarded by Soldiers and Security Robots, with Sentry Turrets (laser) at the corners.
- **Extremely rare**: like the hyperscale campus, never within 5,000 blocks of spawn, at most one per 320 x 320 chunks and only a fraction of those (config). Biomes: plains, desert, savanna, snowy plains.
- Entry options give the structure its puzzle: walk in and fight, **EMP** the gate turrets, or cut the base power (the turrets and the gate are on a visible power network with a few cut points and an easily-found Generator; cutting power disables turrets and doors but not soldiers).
- Loot: the vault holds a chest set with a small number of top-tier items with strongly weighted rarity: **Gold-bin chiplets**, **Wafer-Scale Engines** (the one place to find one outside the fab), a **Warhead Blueprint** for Batch E (if that item exists, else a placeholder), Superconducting Wire stacks, Battery Cells, EMP Grenades and one random energy weapon. Respect the owner's balance rule: scarce. Loot tables in `data/rackcraft/loot_tables`.
- A **Mission Log** book in the command bunker (like the Maintenance Log in Site 7) describing what the base was for, in the mod's tone.

### D2. Hostile Data Centers
- A hostile variant for some existing ruin structures (`hostile_` prefix on at least `container_farm`, `bunker`, `tape_archive`, `ai_lab`, picking from the 13 variants): the same structure, but **occupied by tweakers and scavengers** (a new `ScavengerEntity`, an unarmoured humanoid with a pipe and a hoodie texture) who "are stripping the racks for parts". Their loot: components and Failed Modules; their stash chest holds a little more than the ruin's usual loot (still parts, never hardware), because they've done the dig for you.
- Same difficulty rules as guards (scavengers are jittery, so on Easy/Normal they engage at 8 blocks; hard 16).
- Not every instance is hostile: ~25% of generated ruins (config) get scavengers. Peaceful-friendly by the same rules.
- Rarely, a hostile data centre has a Security Robot and a Sentry Turret guarding a vault door (opened by an EMP or by cutting the cable that powers the door).

### D3. Bounties
- The Darknet (`darknet/`) posts a **bounty** every so often: "Kill the named elite X". The target is a named elite variant of a guard (stronger, a leader at a D1/D2 structure) that exists only in those structures. Completing it pays RC (config-scaled). Track on the Darknet Terminal under a Bounties tab and on the Ops Terminal Alerts tab as an info row. Persist progress per world; reset in the self-test.
- If the structure doesn't exist yet in the world (not generated), the bounty is not offered (don't send the player on an impossible hunt); use `/rackcraft locate` logic to check cheaply, or post the bounty only after the player has discovered one.

## Research (new)
**Directed Energy**, **Plasma Weapons**. List them in the README R&D table.

## Docs and assets
README: new "Arms and Ruins" section (weapons table, turrets, guards, the Military Base and hostile data centers); update "Abandoned Data Centers" table with hostile variants. Field Manual: "Defence" chapter. New textures procedurally (`tools/textures.py`).

## Acceptance
- Self-test: weapon heat and ammo logic (call the fire methods directly, with a test player or a mock); a Sentry only targets hostile mobs; an EMP freezes a machine, a turret and a guard; the guard anger rules per difficulty (set the world's difficulty in the test); military base generates for real without exceptions on a fresh seed; loot tables roll.
- Combat feel, projectile visuals and guard animation are **unverified in-client**: say so and expose numbers in config.
