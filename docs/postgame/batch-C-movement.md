# Batch C: Movement

Read `00-conventions.md` first. Needs no other batch, but the Wireless Power Beacon from Batch A recharges the jetpack and grapple if both exist (a hook is described below).

## Goal

Let a player get across a 176-block campus and a sprawling base without walking or elytra. Hydrogen, power and Quantum hardware are the currencies.

## Features

### C1. Quantum Teleport Pads (the owner loves this)
Paired pads, one-to-one, instant.
- Blocks: `teleport_pad` (a machine block). A pad has a **name** and an **id**; two pads pair by sharing a **Linked Shard**: crafting a Linked Shard produces two items (`linked_shard`, with a UUID in NBT); use one on each pad (right-click) to pair. A pad can pair with exactly one other. Breaking a pad un-pairs it (the shard it returned is blank).
- Using: stand on the pad (step-on, with a short 2-second charge-up ring and sound), and you arrive on the partner pad. Entities can ride too (pets, minecarts excluded). Teleporting carries items as normal.
- Requirements: each pad needs power (a chosen charge: 1 MW-seconds per 100 blocks, configurable `teleport.mwsPer100Blocks`, drawn from the pad's power network when you step on; if the network can't supply it, the pad shows "insufficient power" and doesn't fire, no partial teleport) and a **Quantum Annealer** in a slot, kept cold by a Cryostat touching the pad (reuse `world/Cryostats.java`: it already handles "a cold Cryostat touching it" for racks with Annealers; extend to pads, since the Annealer in the pad is what entangles the pair). Both pads need their requirements. Each jump consumes Hydrogen from the Cryostat's canisters at a cost (config), the same canister stream racks use.
- Range: any distance in the same dimension. **Cross-dimension** pairing costs 20x power; requires a **Dimensional Shard** (made from a Linked Shard plus a Nether Star and an Eye of Ender; very expensive).
- Chunk loading: the destination pad's chunk must be loaded or the teleport loads it briefly (use a ticket, `ServerWorld.getChunkManager().addTicket`/`ChunkPos` ticket for the duration); never leave tickets behind.
- Safety: if the destination is obstructed (blocks above the pad), it clears the space as a pad-owned airspace is 2 blocks high; if obstructed, the jump aborts with a message. Players are never teleported into blocks.
- GUI: a small screen on the pad listing its name, partner name and distance, its charge and Cryostat state. Reuse `client/screen/MachineStatusScreen.java` conventions and `MachineScreenHandler`.
- Gate: research **Quantum Entanglement** (large: ~30M RC + 300M general; requires Quantum Annealing). Pads are Assembly Line made (Quantum Annealer, Superconducting Wire, a Cryo Coil, Photonic Interconnect, steel blocks). Not on the Exchange. Linked Shard: Ender Eye x2, a gold-bin chiplet, Graphene Sheet; yields two.
- Self-test: pair two pads via Linked Shard; step on with sufficient power and cold Cryostat: moves; insufficient power: doesn't; breaking a pad unpairs; a jump consumes the configured hydrogen; chunk tickets are released.

### C2. Mag-Lev
Powered rail and cars that move players at 40+ blocks/second between stations.
- Blocks: `maglev_rail` (a directional, snap-connecting rail block, straight/curved/slope variants; do not implement all vanilla rail shape logic: support straight, 90-degree turns and 1-block slopes) and `maglev_station` (a platform block with a destination menu). A **Maglev Car** is an entity (`entity/MaglevCarEntity.java`, register in `RcEntities.java`): 4 seats, runs on a continuous rail line, stops at stations. Reuse conveyor-belt-style entity carrying code in `block/ConveyorBeltBlock.java` for how the mod already moves players, plus `entity/RocketEntity.java` for entity registration patterns.
- Simplest viable design: stations are named nodes in a graph (a `MaglevNetwork` per world, saved as `PersistentState`, reset by the self-test). Right-click a station: choose a destination in a list; a car spawns, carries you along the rail to the destination at constant speed and despawns/returns. If a rail is missing or cut, the route is blocked and the menu says "line broken at x,y,z".
- Power: each moving car is a power load on the rail's power network (a rail joins the power network like a cable: register `POWER` with `NetworkManager`). A running car draws e.g. 800 kW. If the supply is insufficient the car slows, and below 10% it stops mid-line until power returns (comedic chat: "The train has stopped. This is fine."). No damage to anyone.
- Client: a simple model and renderer like `client/render/DroneRenderer.java`. Smoothness on the client is hard to check without a client; keep motion simple (server-set velocity) and flag it unverified.
- Gate: research **Magnetic Levitation** (~8M RC + 100M general; requires Superconducting Wire / Advanced Materials). Rails: steel, Superconducting Wire (1 per 4 rails), power cable.
- Self-test: a rail line between two stations; a car completes the trip; breaking a rail blocks the route; low power stalls the car.

### C3. Grapple
- `grapple_hook` item: use to fire a hook up to 40 blocks at a block; it pulls the player towards the anchor with a smooth acceleration, cancels fall damage at the end (up to a limit), or lets you hang (sneak). Draws from a Battery Cell per use (the same `battery_cell` as Batch A; if Batch A isn't built, create `battery_cell` here and note it for A).
- A simple rope line renderer (client) from the hand to the anchor. Server authoritative: the server applies the velocity; the client only draws.
- Gate: research none; a craft from steel, a motor, a battery cell, copper wire.
- Self-test: a use at an anchor block moves the player's velocity towards it and spends one cell.

### C4. Hydrogen Jetpack (with hover)
- `hydrogen_jetpack`, chestplate-slot armor item. Hold jump to thrust upwards, **sneak while airborne to hover** (hold altitude, with a small horizontal drift control), sprint to boost. Burns hydrogen: a tank of 8 Hydrogen Canisters (loaded by right-clicking the item with canisters, or refilled from a Wireless Terminal/Hydrogen Tank in range with a use of the action key); about 30 seconds of thrust per canister, hover costs half.
- Shows fuel as a bar on the item and a small HUD gauge (client; reuse the pattern of `client/CoinHud.java`).
- Landing: damage rules are vanilla: cutting power in mid-air means you fall. Add a 3-second low-fuel warning (sound and HUD flash) and a **safe-descent** mode at 0 fuel: the pack auto-vents the last of the gas to slow your fall (no damage once, with a long cooldown), so running dry is a scare, not a death. Explain in the tooltip.
- Elytra conflicts: can't be worn with an elytra (chestplate slot), as in vanilla.
- Tier 2 `fuel_cell_jetpack` (research **Personal Propulsion II**): a Fuel Cell Pack runs the thrust off electricity from Battery Cells instead (quieter, no hydrogen, shorter). Optional; skip if time is short and note it.
- Wireless Power Beacon hook (Batch A): standing within a beacon's range recharges battery cells held in equipped gear. If Batch A isn't built, leave the no-op hook `WirelessPower.powerPlayerGear`.
- Gate: research **Personal Propulsion** (~5M RC + 60M general; requires Cryogenic Hydrogen Storage). Assembly Line made: Hydrogen Tank (small), Electric Motors x4, Graphene Sheets x2, steel, circuit boards.
- Server authority: all movement is applied server-side via velocity and the client simply predicts; guard against `ServerPlayerEntity` flying-kick (set `fallDistance` and the `floatingTicks` field so vanilla's fly-kick check doesn't kick a hovering player on servers: `ServerPlayNetworkHandler` tracks floating; research how to avoid it or report it).
- Self-test: jetpack with 1 canister burns the configured amount per second of thrust; hover costs half; at 0 fuel the safe-descent triggers once.

## Research (new)
**Quantum Entanglement**, **Magnetic Levitation**, **Personal Propulsion** (and **Personal Propulsion II** if built).

## Docs and assets
README: new "Getting Around" section; Field Manual chapter "Movement". Textures: procedural; the Maglev Car and the jetpack model are the biggest visual tasks and can't be previewed, so keep them blocky and use the existing renderer helpers.

## Acceptance
Self-test passes; test and build pass. Mag-Lev motion, jetpack feel (thrust/hover tuning) and the grapple's rope are **unverified in-client**: expect the owner to tune the numbers; expose them in config (`jetpack.thrust`, `jetpack.hoverCostFactor`, `maglev.speed`).
