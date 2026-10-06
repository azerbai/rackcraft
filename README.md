# Rackcraft

Rackcraft is a Fabric mod for Minecraft 1.20.1 that models data-center power, cooling, and compute as a server-authoritative simulation.

## Build and Install

Use JDK 25 to run Gradle with the pinned Fabric Loom snapshot. The project selects a Java 17 toolchain for source compilation and output bytecode, as required for Minecraft 1.20.1. See [DEVIATIONS.md](DEVIATIONS.md) for why the build runtime differs from the game runtime.

```sh
export JAVA_HOME="$HOME/.jdks/temurin25/Contents/Home"
export PATH="$JAVA_HOME/bin:$PATH"
python3 tools/gen_assets.py
python3 tools/check_assets.py
./gradlew clean build
```

Install `build/libs/rackcraft-1.0.0.jar` with Fabric Loader for Minecraft 1.20.1 and Fabric API 0.92.x. The mod has no other runtime mod dependencies.

## Progression

Mine bauxite ore and smelt it into aluminum. Blast iron into steel, then craft copper wire, silicon components, and server modules. Place a server rack, insert up to 8 U of modules, and connect its power face through a PDU and power cable to a generator or solar source. Diesel generators accept vanilla furnace fuel and the Rackcraft fuels below. Cooling fans remove heat from their front cell; CRAC units require a powered coolant loop and a cooling tower.

The machine screens open by right-clicking. Rack bays accept `pi_node`, `server_1u`, `asic_miner`, `gpu_blade`, and `quantum_core`; a quantum core only contributes load when a CDU is adjacent. Power, coolant, and fiber cables form independent networks.

## Mining RackCoin and the Crypto Exchange

Racks mine RackCoin (RC) into one balance shared by the whole world. A rack mines when it has modules, at least 50% power, a fiber link to a router, and an intake below 40 C. Open a rack to see its live RC/s, or the reason it has stopped and how to fix it. Spend RackCoin at a **Crypto Exchange**: its recipe is glass panes, two CPU chips, a circuit board, steel and an emerald. Its curated tabs sell bundles of resources, rare items and Rackcraft parts (`ExchangeOffers.java`). The **All Items** tab sells almost every survival item, searchable, priced from base values plus recipe costs (`ExchangeCatalog.java`); creative-only items are excluded. Click buys one, Shift-click ten, Ctrl-click a stack.

## Abandoned Data Centers

Rare ruined data centers generate on the surface of plains, meadows, savannas, forests, taigas, snowy plains and deserts (about one per 360 chunks there, on flat ground only). Each one has two loaded racks, a fuelled generator, a router, an exhaust fan and a Crypto Exchange. Only one cut power cable and one cut fiber cable keep it from mining. The chest holds a Repair Kit, the Field Manual, a Multimeter and a Maintenance Log that walks new players through the fix. Operators can place one with `/rackcraft structure datacenter`.

## Tools and HUD

- **Multimeter:** right-click a machine or cable for a readout (power delivered against demand, fuel, charge, rack status, fiber bandwidth, coolant loop). Sneak-right-click a machine to rotate it.
- **RackCoin HUD:** your balance and mining rate, shown top-left while you are within 32 blocks of a rack or holding the Field Manual, Multimeter or Thermal Scanner. Set `hud.enabled` to `false` in `config/rackcraft.json` to hide it.
- **Cut cables** spark and smoke, and cable-cut alerts include the coordinates.

## Cables

Power cables, coolant pipes and fiber connect only toward cables of the same kind and toward machines on that network, forming straight runs, corners and junctions. Cables placed with older versions are upgraded when their chunk loads.

## Creative Machines

Four creative-only blocks live in the **Rackcraft Creative** tab. They have no recipes and are never sold at the Exchange. Right-click one to set its values in-game; the values are saved on the block, and only players in creative mode or operators can change them.

| Block | Setting |
| --- | --- |
| Creative Power Source | Output in kW, supplied to the power network it touches |
| Creative Rack | Mining rate in RC/s (no modules, power, fiber or cooling needed), plus an optional test load in kW drawn from its power network |
| Creative Cooler | Air temperature held in front of and behind it (never below ambient, since the thermal model tracks heat above ambient only) |
| Creative Router | Bandwidth added to its fiber network |

## Field Manual

Craft a book with a copper ingot to get the Rackcraft Field Manual, then right-click to open it. It has a chapter for each stage of progression and a page for every block and item, and it shows the recipes the game has actually loaded, so datapack changes appear automatically. Click a Rackcraft ingredient to jump to its page. Use the arrow keys or the scroll wheel to turn pages, and Backspace to go back. Long pages continue onto the next page, so no recipe is cut off.

## Fuels

The diesel generator burns one item at a time. Each tick of furnace burn time gives one tick of 40 kW output. Fuel items show their runtime in their tooltip, and a lava bucket leaves its empty bucket in the generator's slot.

| Fuel | Recipe | Runtime |
| --- | --- | --- |
| Biomass Pellet | 8 `#rackcraft:biomass` (crops, seeds, kelp, leaves, saplings) around bone meal, makes 4 | 40 s |
| Coke | Blast coal or charcoal | 160 s |
| Biodiesel Canister | Aluminum ingot over 6 biomass pellets | 480 s |
| Block of Coke | 9 coke | 1,600 s |

## Textures

All textures are generated by `tools/textures.py` from the `color`, `pattern`, `front` and `top` fields in `tools/content.json`. Machines use an orientable model: the front face points at the player who placed the machine and switches to an animated "on" texture while the machine is active. Racks blink, fans and turbines spin, and the reactor core pulses. Run `python3 tools/gen_assets.py` after editing the catalog.

## Configuration

`config/rackcraft.json` is written on first launch. Main keys include `sim.stepTicks` (10), `thermal.ambientC` (24), `thermal.cellCapacityKjPerK` (4), `thermal.faceConductanceKwPerK` (0.25), `thermal.upwardMultiplier` (2), `thermal.leakKwPerK` (0.01), `thermal.rackFlowKwPerK` (0.5), `thermal.cracFlowKwPerK` (2), `thermal.maxActiveCells` (16384), `thermal.settleEpsilonK` (0.05), `events.enabled`, `events.perHour` (3), and `heatOverlay.maxCells` (2000).

## Commands

Operators (permission level 2) can use `/rackcraft credits add <n>`, `/rackcraft event <id>`, `/rackcraft heat set <x> <y> <z> <celsius>`, `/rackcraft facility info`, and `/rackcraft sim step <n>`. Event IDs are `utility_outage`, `cooling_failure`, `hardware_failure`, `cable_cut`, `heat_wave`, and `surge`.

## Heat Management

Rack intake temperature controls thermal throttling: performance begins to fall above 27 C and trips at 40 C. Keep rack intakes in cold-air aisles, route exhaust into a separate hot aisle, and provide powered cooling. Empty server racks are valid and can be configured before modules are installed.

## License

MIT. See [LICENSE](LICENSE).
