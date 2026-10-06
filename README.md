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

Twelve kinds of ruined data center generate as real structures (so `/locate structure #rackcraft:data_centers` works too). Most have racks, a cut cable or two, and a loot chest:

| Variant | Where | What's there |
| --- | --- | --- |
| `site_7` | Temperate biomes | The tutorial site: two cut cables from mining, a Crypto Exchange, and a Maintenance Log |
| `server_closet` | Temperate | One rack in a brick shed, run from a rooftop solar panel |
| `container_farm` | Deserts, savannas, badlands, plains | Shipping containers of ASIC miners beside a pond the pump drank dry |
| `crypto_garage` | Temperate | A GPU rig with RGB lighting and a diesel generator indoors |
| `bunker` | Temperate and cold | Buried hall with generators and batteries, reached by a ladder shaft |
| `flooded_hall` | Swamps | A data hall half under water |
| `overgrown_colo` | Jungles, dark forests | Colocation cages with a tree growing through the roof |
| `arctic_vault` | Snowy biomes | A Quantum Core locked in a vault, cooling towers in the snow |
| `ai_lab` | Temperate | Tensor racks, an Operations Terminal, and a librarian still shackled to a desk |
| `solar_farm` | Deserts, savannas, plains | Rows of panels and a control hut |
| `tape_archive` | Temperate | Tape libraries and storage arrays behind the cobwebs |
| `hyperscale_campus` | Plains, savannas, deserts, snowy plains, meadows (rare) | **112 x 112 blocks**: four data halls (160 racks), an operations centre with an AI wing, a reservoir with pumps, cooling towers, a substation, a generator yard and a car park |

Operators can find one with `/rackcraft locate datacenter [variant] [radius]` (radius in chunks, default 100) and build one in front of them with `/rackcraft structure place <variant>` (`/rackcraft structure datacenter` still builds Site 7).

## Compute: Mining, Autocrafting and AI Work

Racks on one fiber network form a **cluster**. Each step the facility hands out whole racks, in this order: autocrafting jobs, then AI contracts that are generating (earliest deadline first), then model training. Racks nothing needs keep mining. A rack lent out doesn't mine, but it draws the same power.

- **Autocrafting** borrows racks from the storage's own fiber network first, then from any **online** cluster (one with an uplink or core router). Before, it only used racks wired straight to the storage, so a storage network with no racks of its own reported "No compute" forever.
- **Cluster policy** (set at the Operations Terminal): *Auto* lends racks to any work; *Mining only* never lends.
- Every module lends **general compute** to autocrafting and **AI compute** to contracts and training:

| Module | Mines (RC/s) | General | AI | Freshwater cooling |
| --- | --- | --- | --- | --- |
| Pi Node | 0.5 | 1 | 0.4 | no |
| 1U Server | 2 | 2 | 1.6 | no |
| ASIC Miner | 5 | 0 | 0 | 1 unit |
| GPU Blade (2 U) | 12 | 6 | 10 | 2 units |
| Tensor Accelerator (2 U) | 0 | 3 | 12 | 2 units |
| Quantum Core (4 U) | 60 | 20 | 50 | 4 units |

Contracts pay 1.35 RC per AI-compute-second against a well-trained reference model, so AI work earns about 10% more than mining on GPUs and Quantum Cores, and about a third more with Tensor Accelerators (which can't mine). That holds even on free solar power.

## AI Contracts

Clients post work to the **Operations Terminal** every one to three minutes: "Image of a pig in a business suit as a stock photo", or an Essay, Legal Document, Homework, Cover Letter, Wedding Speech, Product Review, Apology Letter, Terms of Service, Fan Fiction or Patch Notes. Each offer shows the client, quantity, the quality required, the pay and how long you have once accepted. Offers lapse after ten minutes.

1. **Accept** it (up to five at once).
2. **Generate**: pick a cluster (or *any* online Auto cluster) and press Generate. Its racks work until each item reaches the required quality, then finished work goes to the outbox and is delivered and paid automatically. Or **Deliver** a finished Generated Image or Document from your inventory if you already have one with the same prompt and enough quality.
3. Late work earns half; quality above the requirement earns up to 20% more. Contracts more than one full duration overdue fail.

Clients often order the same thing again, and some prompts are perennial classics. **Make a spare** on a finished contract generates another copy for stock, and abandoned data centers sometimes hold finished work.

### Models and training data

Quality depends on the model. The image model (*SketchDiffusion*) and the language model (*Large Librarian Model*) start with a 12% quality cap, reaching about 66% after 24 items of training data and 94% after 72. Upload data at the terminal; idle racks on Auto clusters train on it at 1,500 AI-compute-seconds per item.

- **Kids' Art Table** (image data): stock it with paper and **Crayons** and baby villagers come over to draw. Each kid draws every 20 seconds (up to four kids), and four drawings make a **Crayon Art Aggregate**. Kids at a stocked table never seem to grow up.
- **Scriptorium Desk** (text data): use **Shackles** on a librarian within six blocks to chain them to the desk, then stock it with paper and ink sacs. They write a **Librarian Text Corpus** every 30 seconds, can't leave, and complain constantly. Sneak-right-click them with an empty hand to set them free; breaking the desk frees them too.

### The Operations Terminal

One screen for the whole dimension, needing no power or cables. Its tabs:

- **Overview:** income, clusters, model caps, air quality and the top problems.
- **Contracts:** offers, accepted work with progress bars and deadlines, and history.
- **Clusters:** each cluster's racks, compute, online state, what its racks are doing, mining rate and policy.
- **Models:** quality caps, training queues and the upload button.
- **Alerts:** every rack that is unpowered, tripped, overheated, offline or needs water; offline storage and drives over 90% full; pumps that are dry or on salt water; cut cables; generators out of fuel; art tables and desks out of supplies; late contracts; smog.

## Freshwater Cooling

Tier 3 and up hardware (ASIC Miners, GPU Blades, Tensor Accelerators and Quantum Cores) is water-cooled, and a rack holding any of it stops with *Needs freshwater cooling* until it is supplied. Storage, Pi Nodes and 1U Servers don't need water. Racks join the **coolant pipe** network: place a **Freshwater Pump** touching a lake or river (ocean and beach biomes are salt water and don't count) and pipe it to the racks. A pump draws 1.5 kW and supplies one unit per three water source blocks within six blocks, up to 16 units. It drinks the lake: every 120 unit-seconds of cooling drains one source block, from the shoreline first. A four-GPU rack empties a block about every 15 seconds, so big farms need big lakes, and eventually a new one.

## Smog

Exhaust fans dump waste heat straight outside and pollute heavily. Each running fan pours smoke out of its back and adds smog to its chunk, more the more heat it moves. Smog drifts into neighbouring chunks and clears over a few minutes. From 35 players get hungry, and from 70 they feel sick and villagers weaken. Smog also dims solar panels by up to 60%. The Operations Terminal shows the air where you stand and lists smoggy chunks.

## Tools and HUD

- **Multimeter:** right-click a machine or cable for a readout (power delivered against demand, fuel, charge, rack status, fiber bandwidth, coolant loop). Sneak-right-click a machine to rotate it.
- **RackCoin HUD:** your balance and mining rate, shown top-left while you are within 32 blocks of a rack or holding the Field Manual, Multimeter or Thermal Scanner. Set `hud.enabled` to `false` in `config/rackcraft.json` to hide it.
- **Cut cables** spark and smoke, and cable-cut alerts include the coordinates.

## Cables

Power cables, coolant pipes and fiber connect only toward cables of the same kind and toward machines on that network, forming straight runs, corners and junctions. Cables placed with older versions are upgraded when their chunk loads.

## Storage and Autocrafting

- **Storage Array:** 8 bays for 1K / 4K / 16K / 64K **Storage Drives**. It works like a rack: it needs power (0.4 kW + 0.15 kW per drive), takes air in the front and exhausts heat out the back, and goes offline (nothing is lost) when unpowered or at 40 C.
- **Tape Library:** 4 bays for **Tape Cartridges** of 1,048,576 items each. This is cold storage: 0.3 kW, but players wait 2 seconds for each read. When drives pass 85% full, the least recently used items are archived to tape automatically.
- **Storage Terminal:** a searchable grid of everything on its fiber network, a 3x3 crafting grid that refills from storage, and pattern encoding. Left-click takes a stack, right-click half, Shift-click to inventory; clicking with a held item deposits it. Items only on tape are tinted blue.
- **Autocrafting runs on your racks.** Encode a **Recipe Pattern** from a **Blank Pattern** and store it on the network; its output shows a `+`. Ctrl-click to request any amount, and the planner chains patterns for multi-step recipes. Racks lend general compute (see *Compute* below) at 0.25 crafts per second per point: first racks on the storage's fiber network, then any online cluster. **Racks lending compute stop mining** while the job runs. The terminal's job row shows the job's status and progress; hover it for details.
- **Wireless Transmitter + Wireless Terminal:** sneak-right-click the transmitter with the terminal to link it. Range levels are 16, 32, 64, 128, 256 and 1,024 blocks, then unlimited in the dimension, then every dimension. Each level is bought with RackCoin or resources in the transmitter's screen, and doubles its power draw (0.5 kW up to 64 kW). While the transmitter's area is unloaded, wireless access uses the drives it last saw.

Drive contents are stored with the world, keyed to each drive, so drives keep their items when moved between arrays.

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

Operators (permission level 2) can use `/rackcraft credits add <n>`, `/rackcraft event <id>`, `/rackcraft heat set <x> <y> <z> <celsius>`, `/rackcraft facility info`, `/rackcraft sim step <n>`, `/rackcraft locate datacenter [variant] [radius]`, `/rackcraft structure place <variant>` (or `structure datacenter` for Site 7), and `/rackcraft contracts offer` (post a contract offer now). Event IDs are `utility_outage`, `cooling_failure`, `hardware_failure`, `cable_cut`, `heat_wave`, and `surge`.

## Heat Management

Rack intake temperature controls thermal throttling: performance begins to fall above 27 C and trips at 40 C. Keep rack intakes in cold-air aisles, route exhaust into a separate hot aisle, and provide powered cooling. Empty server racks are valid and can be configured before modules are installed.

## License

MIT. See [LICENSE](LICENSE).
