# Batch A: Build and Wire

Read `00-conventions.md` first. This is the owner's stated main constraint in the mid to late game, so quality and feel matter more than quantity. Needs no other batch.

## Goal

Make laying out and connecting a big facility faster and clearer: bundle cables, preview before placing, reroute networks from a GUI, send power over long spans and wirelessly, and place or reshape lots of blocks at once.

## Features

### A1. Trunk Bundle (the owner's "absolute must")
One cable block family that carries **power, coolant and fiber together** (three independent networks inside one block), instead of three parallel runs.
- Blocks: `trunk_bundle` (straight/connecting like `CableBlock`, six-way connections) and `trunk_tap` (a block that connects the bundle to ordinary single cables: on each face you choose which of the three kinds it hands out to). Simplest model: a trunk is a `CableBlock`-style block whose block entity registers all of `{POWER, COOLANT, DATA}` with `NetworkManager.register(pos, kinds)`; look at how `CableBlock`/`CableBlockEntity` register and connect, and how `NetGraph` joins neighbours per kind. A trunk joins a neighbouring ordinary cable only for that cable's kind. Item pipes are not carried.
- A cut event on a trunk cuts **all three** (the existing cable-cut event, `CUT` property, Repair Kit splice: make one splice restore the whole trunk; check how events pick cables and make sure trunks are eligible).
- Capacity: the trunk's power throughput and fiber bandwidth follow the existing cable tiers: read how `CableUpgrader` and cable-tier limits work, and make the trunk equal to the best tier available to a single cable (not better; it is a convenience, not a cheat). Coolant uses the same pipe rules.
- Visual: a thick square conduit with three coloured stripes (red, blue, green or the colours the three single cables already use). Add a `trunk` model entry type to `tools/gen_assets.py` modelled on `pipe`, and a texture in `tools/textures.py`.
- Gate: research **Structured Cabling** (small, general compute: ~400,000 RC + 8M general; requires Custom Firmware). Crafted on the Assembly Line? No: a simple shaped recipe from 1 power cable + 1 coolant pipe + 1 fiber cable + 2 steel ingots + 1 aluminum ingot yields 4 trunks. Cheap, because it is a convenience.
- Self-test: a power source -> trunk -> tap -> power cable -> load works; coolant and fiber through the same trunk work; cutting a trunk breaks all three; splice restores all three.

### A2. Cable Painter with placement preview ("Cable Planner")
A tool that previews cable routes before they are placed, so you can see where thin fiber will go.
- Item `cable_planner`. Right-click a block to set point A, right-click another for point B (or sneak-click to clear). Keep input simple: right-click in air cycles the **route style** (Straight L, Z, Shortest, Along Walls) and **cable kind** (power, coolant, fiber, item pipe, trunk).
- It shows a **ghost** of the route (client-side, translucent boxes through walls like `SurveyOutline`) with the same colour as the cable, red where a block is in the way, and a line in the HUD with the cost: cables needed and how many you carry. Look at `client/SurveyOutline.java` and `client/render/Boxes.java` for the rendering pattern, `FaultOverlay` for through-wall outlines, and how the Site Planner plans cable paths (`world/SitePlanner.java` has cable pathfinding with detours; reuse its pathfinder rather than writing another).
- Left-click or a confirm key **places** the route from the player's inventory (or from a connected Wireless Terminal's storage) one block per tick or in a single operation, never breaking existing blocks; stops at the first obstruction and tells the player.
- **Painter** half: sneak-use on an existing cable with a dye in the offhand sets a `colour` property (cosmetic, no network effect; implement as a block entity field or blockstate, whichever the existing cable BE supports without bloating saves) so you can colour-code runs. Keep this small.
- The server computes the path and sends it to the client for ghosting (a new packet in `RackcraftNetworking`/`ClientNet`), or the client computes it from the stakes; choose the simplest that cannot desync (server authoritative, client display only).
- Gate: Assembly Line / simple craft: circuit board, ender eye, 4 copper wire, survey stake. No research needed (a quality-of-life tool).

### A3. Patch Panel
A block with six labelled **ports** that lets you re-route networks from a GUI.
- Concept: a Patch Panel has 8 numbered ports per network kind it supports (power, data, coolant). Each face of the block can be assigned to a port; two faces assigned to the same port are electrically connected, otherwise the faces are isolated (unlike a cable, which connects all six). The screen shows a grid of face -> port assignment per kind and lets you rename each port ("Hall B uplink").
- Implementation: a machine block entity with per-face assignments, registered in `NetworkManager` as separate nodes per port so `NetGraph.connect` joins only faces on the same port. Study how `NetworkManager.rebuildIfDirty` joins neighbours and extend with an "adjacency filter" hook, or model each port as an internal node. Keep changes in `NetworkManager` minimal and covered by self-tests, because every network passes through it.
- Names show in the Multimeter readout and in Fault Finder alerts ("cut cable on Hall B uplink").
- Screen: `client/screen/` (see `ControllerScreen` / `MachineStatusScreen` for the style); a simple grid with dropdown-style cycle buttons is enough. Server handler: `screen/` (see `MachineScreenHandler`).
- Gate: research Structured Cabling; assembly line craft from 8 steel, 4 circuit boards, 2 fiber, 2 power cable, 2 coolant pipe.

### A4. Pylons (long-span power)
Two **Pylon** blocks up to 128 blocks apart (line of sight not required; both loaded) carry **power only** as a single link, with no cable between them.
- Place a Pylon, right-click it with a **Pylon Linker** (or link by using one Pylon item on another, mirroring the Survey Stake pairing) to pair it with another within range. They appear as one node pair in `NetworkManager`. Draw a visible sagging line between them (client renderer, a simple catenary of segments).
- Losses: a link has a small power loss (3% per 64 blocks), zero with Superconducting Wire as a craft ingredient upgrade (a Pylon variant `superconducting_pylon`, 0% loss, range 256).
- Both ends must be chunk-loaded to carry power; document that. If either end is unloaded the link reads as cut (no crash, no state loss).
- Gate: research Structured Cabling; steel, power cable, copper wire; the superconducting variant needs Superconducting Wire and a Cryo Coil. 
- Self-test: link two pylons 100 blocks apart through the NetworkManager; power flows; losses are applied; unlinking and breaking one end disconnect cleanly.

### A5. Wireless Power Beacon (the owner loves this)
Beams power wirelessly to nearby receivers without cables.
- Blocks: `power_beacon` (transmitter, takes power from its network) and `beacon_receiver` (a small block; feeds the network it touches). The beacon sends up to 1 MW within 16 blocks to receivers in range; losses 15%. Also **powers entities**: Construction/Maintenance/Tanker Drones in range recharge without docks (if drones use any charge/hydrogen logic; if not, skip), and the player's powered gear (the Hydrogen Jetpack and Batteries from Batches C and D) recharges when standing in range. If those items do not exist yet, leave a clearly marked hook (`WirelessPower.powerPlayerGear(ServerPlayerEntity, double kw)`) with a no-op.
- Range, throughput and receiver count in config (`beacon.range`, `beacon.maxKw`). A beam is visible: faint particle line from beacon to each receiver (server-sent particles, throttled).
- Receivers pair automatically with the nearest beacon in range, or by right-click with a Multimeter.
- Gate: research **Wireless Power** (large: ~12M RC + 150M general; requires Silicon Photonics because the beam uses a photonic emitter). Ingredients include a Photonic Interconnect and Gallium Nitride. Expensive and not on the Exchange.
- Self-test: a beacon on a powered network powers a receiver 12 blocks away that powers a load; out of range does not; the loss factor is as configured.

### A6. Constructor's Gauntlet
A powered glove that places, replaces or deletes blocks at range, drawing from the Wireless Terminal's storage.
- Modes cycled by sneak-right-click in air: **Line**, **Plane**, **Box fill**, **Swap** (replace every block of the targeted type in the selection with the block in your offhand; includes cable upgrades by reusing `CableUpgrader`), and **Delete**.
- Range: 32 blocks, 128 with research **Long Reach**. Needs a Wireless Terminal in the inventory (look at `storage/WirelessTerminalItem.java` for how a terminal finds its storage network and range) and takes items from that storage, returns deleted blocks' drops to it.
- Each use uses a Battery Cell from inventory (`battery_cell`, a new cheap item: redstone, copper, iron; 10 uses; recharged on a powered network via a Charger Pad block, or by the Wireless Power Beacon). Keep this simple: durability bar as charge.
- Preview: reuse the Cable Planner's client ghost renderer (build A2 first and share the renderer).
- Hard limits: cannot place or delete inside protected regions (Batch D structures, once they exist, may add protection), nothing larger than 4,096 blocks per use (config).
- Self-test: box fill with a storage network supplying blocks; storage is drained correctly; swap works; fails cleanly with no storage.

### A7. Terraformer Cannon
Hold-to-fire tool that flattens, fills or hollows terrain in a cone/radius, like the Terraforming Drones but instant and by hand.
- Look at how the **Terraforming Drone** and Site Planner terraform (`world/SitePlanner.java`, `entity/ConstructionDroneEntity.java`) and reuse the shared terrain routines instead of writing new ones.
- Modes: Flatten to the Y of the block you clicked, Fill to level, Hollow (clear a box), Smooth. Radius 3 to 16 selectable. Each shot uses one Hydrogen Canister per 256 blocks changed, from inventory or storage. Fire is a short charged use; it plays a sound and particle puff.
- Gate: check how Terraforming Drones are unlocked (research or plain craft) and gate the Cannon the same way. Assembly Line craft: 2 steel blocks, Electric Motor x2, Hydrogen Tank (small), circuit board.
- Can't remove bedrock or blocks with block entities that hold items (chests, racks); protect all Rackcraft machine blocks by default so a stray shot can't wipe a rack.
- Self-test: flatten a hill; machines are untouched; hydrogen is spent.

## Research (new)
Add to `Research.java`: **Structured Cabling** (needs Custom Firmware), **Long Reach** (needs Structured Cabling), **Wireless Power** (needs Silicon Photonics and Structured Cabling). Use costs listed above as starting points; list them in the README's R&D table.

## Docs and assets
README sections: extend "Cables" and "Site Construction" and "Tools and HUD"; add Field Manual entries and a "Building and Wiring" chapter. All textures are procedural (`tools/textures.py`); every new block and item needs a `desc`.

## Acceptance
- New self-test checks pass with no failures and no "Simulation step failed".
- `./gradlew test` and `./gradlew build` pass.
- Client ghost rendering, the Patch Panel screen, Pylon lines and beam particles are unverified visually: say so.

## As built (2026-10-09)

Built and passing 220 self-test checks (23 new, `BT*`), `./gradlew test` and `./gradlew build`. Where the build departs from the spec above:

- **No Trunk Tap block.** The network graph joins touching blocks of the same kind, so a trunk already hands each lane to any cable or machine it touches. A tap would add nothing.
- **Trunk, Patch Panel, Pylon and Beacon are gated functionally**, like the Hydrogen Tank: they can be crafted but do nothing until their research (research is per world, a recipe can't see it). Crafted from ordinary crafting recipes made of research-gated parts (Superconducting Wire, Gallium Nitride, Photonic Interconnects), not Assembly Line recipes.
- **Patch Panel has no screen.** Each face holds one port number for all networks at once (not one per network), cycled by right-clicking the face; a Name Tag labels it and the Multimeter reads it. The names don't appear in Fault Finder alerts yet.
- **Pylon loss** applies to the whole load of the network the span is in (a conservative simplification), as a load in the power solver, so it shows in the Multimeter as extra demand. Spans draw as server particles, not a client renderer. No Pylon Linker range check across unloaded chunks is needed: both ends must be loaded to carry anything.
- **Power Beacon** works through the power solver with a one-step lag; receivers are `BEAMED` sources. The player-gear recharge hook is only `WirelessPower.inBeaconRange` for the later jetpack batch; Battery Cells are not rechargeable here (the spec had a Charger Pad or beacon recharging them, which was dropped).
- **Cable Planner** ghost is a client outline renderer (`CableGhost`) fed by a server packet; it is the one piece nobody could see being built, so check it in-game. The Cable Painter dye half was not built.
- **Constructor's Gauntlet and Terraformer Cannon** take materials from the inventory first, then a Wireless Terminal's storage (`BuildStock`).
- **Battery Cell, Cable Planner and Pylon Linker** are priced on the Exchange as parts; Power Beacon, Beacon Receiver, Superconducting Pylon, Gauntlet and Cannon are excluded from it.
