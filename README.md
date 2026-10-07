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

Mine bauxite ore and smelt it into aluminum. Blast iron into steel, then craft copper wire, silicon components, and server modules. Place a server rack, fill its eight bays (one module per bay, any size), and connect its power face through a PDU and power cable to a generator or solar source. Diesel generators accept vanilla furnace fuel and the Rackcraft fuels below. See *Cooling* for keeping racks below 27 C.

The machine screens open by right-clicking. Rack bays accept `pi_node`, `server_1u`, `asic_miner`, `gpu_blade`, `tensor_accelerator` and `quantum_core`, one per bay; a quantum core only contributes load when a CDU is adjacent. Power, coolant, and fiber cables form independent networks.

## Mining RackCoin and the Crypto Exchange

Racks mine RackCoin (RC) into one balance shared by the whole world. A rack mines when it has modules, at least 50% power, a fiber link to a router, and an intake below 40 C. Open a rack to see its live RC/s, or the reason it has stopped and how to fix it. Spend RackCoin at a **Crypto Exchange**: its recipe is glass panes, two CPU chips, a circuit board, steel and an emerald. Its curated tabs sell bundles of resources, rare items and Rackcraft parts (`ExchangeOffers.java`). The **All Items** tab sells almost every survival item, searchable, priced from base values plus recipe costs (`ExchangeCatalog.java`); creative-only items are excluded. Click buys one, Shift-click ten, Ctrl-click a stack.

## Abandoned Data Centers

Thirteen kinds of ruined data center generate as real structures (so `/locate structure #rackcraft:data_centers` works too). Most have racks, a cut cable or two, and a loot chest:

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
| `ai_lab` | Temperate | 25 x 18: a server room of AI racks, an ops room, three librarians shackled to their desks and a fenced crayon corner of kids |
| `content_mill` | Temperate | A timber "data labelling" mill: six shackled librarians, a playroom of kids at three art tables, and a Middle Manager |
| `solar_farm` | Deserts, savannas, plains | Rows of panels and a control hut |
| `tape_archive` | Temperate | Tape libraries and storage arrays behind the cobwebs |
| `hyperscale_campus` | Plains, savannas, deserts, snowy plains, meadows (**very rare**: at most one per 128 x 128 chunks) | **176 x 176 blocks, fully working**: four data halls (192 racks of ASICs, GPUs, 1U servers and Tensor Accelerators, all with Rear-Door Coolers and chiller banks), a quantum vault (8 Quantum Core racks cooled by towers on the reservoir), a 3x3x3 reactor array with spare Fuel Cells, an operations centre with an AI wing, and backup utility, diesel and batteries. Only five cut cables keep it dark; repair kits are in the guard hut by the gate |

Operators can find one with `/rackcraft locate datacenter [variant] [radius]` (radius in chunks, default 100, or 400 for the campus) and build one in front of them with `/rackcraft structure place <variant>` (`/rackcraft structure datacenter` still builds Site 7).

## Compute: Mining, Autocrafting and AI Work

Racks on one fiber network form a **cluster**. Each step the facility hands out whole racks, in this order: autocrafting jobs, then AI contracts that are generating (earliest deadline first), then model training. Racks nothing needs keep mining. A rack lent out doesn't mine, but it draws the same power.

- **Autocrafting** borrows racks from the storage's own fiber network first, then from any **online** cluster (one with an uplink or core router). Before, it only used racks wired straight to the storage, so a storage network with no racks of its own reported "No compute" forever.
- **Cluster policy** (set at the Operations Terminal): *Auto* lends racks to any work; *Mining only* never lends.
- Every module lends **general compute** to autocrafting and **AI compute** to contracts and training:

| Module | Mines (RC/s) | General | AI | Cooling |
| --- | --- | --- | --- | --- |
| Pi Node | 0.5 | 1 | 0.4 | air |
| 1U Server | 2 | 2 | 1.6 | air |
| ASIC Miner | 5 | 0 | 0 | liquid |
| GPU Blade | 12 | 6 | 10 | liquid |
| Tensor Accelerator | 0 | 3 | 12 | liquid |
| Quantum Core | 60 | 20 | 50 | liquid |

A rack has eight bays and every module takes one, so a rack holds eight GPU Blades (24 kW) or eight Quantum Cores (72 kW, and a CDU beside it).

Contracts pay 1.35 RC per AI-compute-second against a well-trained reference model, so AI work earns about 10% more than mining on GPUs and Quantum Cores, and about a third more with Tensor Accelerators (which can't mine). That holds even on free solar power.

## AI Contracts

Clients post work to the **Operations Terminal** every one to three minutes: "Image of a pig in a business suit as a stock photo", or an Essay, Legal Document, Homework, Cover Letter, Wedding Speech, Product Review, Apology Letter, Terms of Service, Fan Fiction or Patch Notes. Each offer shows the client, quantity, the quality required, the pay and how long you have once accepted. Offers lapse after ten minutes.

1. **Accept** it (up to five at once). Long prompts wrap on the card, so the pay, deadline and buttons stay visible.
2. **Model**: leave it on *Auto* (the fastest model that can reach the quality) or pick one.
3. **Generate**: pick a cluster (or *any* online Auto cluster) and press Generate. Its racks work until each item reaches the required quality, then finished work goes to the outbox and is delivered and paid automatically. Or **Deliver** a finished Generated Image or Document from your inventory if you already have one with the same prompt and enough quality.
4. Late work earns half; quality above the requirement earns up to 20% more. Contracts more than one full duration overdue fail.

Clients often order the same thing again, and some prompts are perennial classics. **Make a spare** on a finished contract generates another copy for stock, and abandoned data centers sometimes hold finished work.

### Models and training data

Each kind of work has a lineup, like a real lab's. Every model trains separately, on its own queue: upload Art Aggregates or Text Corpora to a specific model at the terminal, and idle racks on Auto clusters train the queued models, sharing the free compute. A model's quality cap starts at 12% and climbs toward its ceiling as it trains (two thirds of the way after its "learns from" count of items).

| Model | Kind | Speed | Ceiling | Learns from | Training per item | Overdelivers |
| --- | --- | --- | --- | --- | --- | --- |
| SketchDiffusion | image | 2.0x | 62% | 6 items | 600 AI-s | 0-1% |
| Nano Melon | image | 1.33x | 85% | 14 items | 1,200 AI-s | 0-4% |
| Nano Melon Pro | image | 0.77x | 99% | 30 items | 2,400 AI-s | 3-10% |
| Gemerald Flash-Lite | text | 2.0x | 60% | 6 items | 500 AI-s | 0-1% |
| Gemerald Flash | text | 1.33x | 84% | 14 items | 1,100 AI-s | 0-4% |
| Gemerald Pro | text | 0.77x | 98% | 30 items | 2,200 AI-s | 3-10% |

Speed is compute per item against the reference model the pay is set by: a Flash model finishes the same item in three quarters of the compute, a Pro in 1.3 times as much but at a much higher cap. Overdelivered quality earns the quality bonus, so Pro work pays a little extra. Saves from before the lineup carry their two models' training over to the Pro models.

- **Kids' Art Table** (image data): stock it with paper and **Crayons** and baby villagers come over to draw. Each kid draws every 20 seconds (up to four kids), and four drawings make a **Crayon Art Aggregate**. Kids at a stocked table never seem to grow up.
- **Scriptorium Desk** (text data): use **Shackles** on a librarian within six blocks to chain them to the desk, then stock it with paper and ink sacs. They write a **Librarian Text Corpus** every 30 seconds, can't leave, and complain constantly. You can see the chain running from their wrists to the desk. Sneak-right-click them with an empty hand to set them free; breaking the desk frees them too.

### The Operations Terminal

One screen for the whole dimension, needing no power or cables. Its tabs:

- **Overview:** income, clusters, model caps, air quality and the top problems.
- **Contracts:** offers, accepted work with progress bars and deadlines, and history.
- **Clusters:** each cluster's racks, compute, online state, what its racks are doing, mining rate and policy.
- **Models:** every model's cap and ceiling, speed, training queue, and its own upload and pause buttons.
- **Alerts:** every rack that is unpowered, tripped, overheated, offline or needs water; offline storage and drives over 90% full; pumps that are dry or on salt water; cut cables; generators out of fuel; art tables and desks out of supplies; late contracts; smog.

## Cooling

Every kilowatt a rack draws comes back out as heat, and a rack slows down once the air at its front passes 27 C and stops at 40 C. An unpowered rack makes no heat. Heat leaves a rack two ways, and the rack screen shows both (**To loop** and **To air**):

- **Air.** Out of the back into the room. Air heat spreads block by block and leaks away: slowly through walls, quickly outdoors. Keep it off the intakes with hot and cold aisles, blanking panels and raised floor, or catch it:
  - **Rear-Door Cooler**: placed against a rack's back face, it catches up to 40 kW of that rack's exhaust into its coolant loop.
  - **CRAC unit**: pulls up to 40 kW per face out of the room air into its coolant loop (3 kW).
  - **Exhaust Fan**: vents up to 10 kW from the block in front of it outside (0.2 kW). It only runs, and only makes smog, while that air is at least 1 C above ambient.
- **Loop.** Each connected run of **Coolant Pipe** is one coolant loop. Liquid-cooled modules (ASIC and up) send 85% of their heat down the pipe, and a rack holding them stops with *Needs liquid cooling* until it is on a loop with at least one heat sink. Rear-Door Coolers, CRACs and Modular Reactors put heat into the same loops. The pipe can run anywhere, so the heat can be dumped far from the racks. **Heat sinks** take it out:

| Sink | Takes out of the loop | Draws | Notes |
| --- | --- | --- | --- |
| Cooling Tower | 120 kW with water, 20 kW dry | 4 kW | Needs 4 units of fresh water from a Freshwater Pump on the same loop |
| Dry Cooler | 40 kW (60 in snowy biomes, 24 in deserts, savannas and badlands) | 2 kW | No water |
| Chiller | 250 kW anywhere | 5 kW + 20% of the heat it moves | No water |
| Water Heat Exchanger | 3 kW per water block within 4 blocks, up to 120 kW | 0.5 kW | Must touch water; sea water works and is never used up |

If more heat goes into a loop than its sinks can take, every machine feeding it gets the same share of what the loop can take and the rest stays in the air, so racks run hot. The Multimeter shows a loop's budget on any pipe or machine on it: heat in against what its sinks can take. The Operations Terminal flags overloaded loops.

**Freshwater Pumps** supply the towers: touching a lake or river (ocean and beach biomes are salt water and don't count), one unit per three water source blocks within six blocks, up to 16 units, for 1.5 kW. Towers drink the lake: every 240 unit-seconds of water used drains a source block from the shoreline.

## Smog

Exhaust fans dump waste heat straight outside and pollute heavily. Each running fan pours smoke out of its back and adds smog to its chunk, more the more heat it moves. Smog drifts into neighbouring chunks and clears over a few minutes. Breathing it gets worse in steps:

| Smog | Effect |
| --- | --- |
| over 30 | **Smog Dizziness**: a slow, gentle sway of the view and a brown haze (much lighter than Nausea; stronger from 60; follows the Distortion Effects slider) |
| 45 | Spells of blindness, more often the thicker it gets |
| 55 | **Smoker's Cough**: coughing fits that stop you sprinting and cost stamina. Villagers cough too |
| 70 | Poison and hunger. Villagers weaken and are poisoned |
| 88 | Poison II |

A **Respirator** (helmet slot) keeps all of it out until its charcoal filter clogs, about 40 minutes of smog; repair it with charcoal. A **Smog Scrubber** draws 6 kW and removes 1.5 smog per second from its chunk and 0.75 from each neighbour. **Carbon Offset Certificates** remove exactly one point of smog each. Smog also dims solar panels by up to 60%. The Operations Terminal shows the air where you stand and lists smoggy chunks.

## Tools and HUD

- **Multimeter:** right-click a machine or cable for a readout (power delivered against demand, fuel, charge, rack status, fiber bandwidth, coolant loop). Sneak-right-click a machine to rotate it.
- **RackCoin HUD:** your balance and mining rate, shown top-left while you are within 32 blocks of a rack or holding the Field Manual, Multimeter or Thermal Scanner. Set `hud.enabled` to `false` in `config/rackcraft.json` to hide it.
- **Cut cables** spark and smoke, and cable-cut alerts include the coordinates.

## Cables

Power cables, coolant pipes, fiber and item pipes connect only toward cables of the same kind and toward machines on that network, forming straight runs, corners and junctions. Cables placed with older versions are upgraded when their chunk loads.

## Storage and Autocrafting

- **Storage Array:** 8 bays for 1K / 4K / 16K / 64K **Storage Drives**. It works like a rack: it needs power (0.4 kW + 0.15 kW per drive), takes air in the front and exhausts heat out the back, and goes offline (nothing is lost) when unpowered or at 40 C.
- **Tape Library:** 4 bays for **Tape Cartridges** of 1,048,576 items each. This is cold storage: 0.3 kW, but players wait 2 seconds for each read. When drives pass 85% full, the least recently used items are archived to tape automatically.
- **Storage Terminal:** a searchable grid of everything on its fiber network, a 3x3 crafting grid that refills from storage, and pattern encoding. Left-click takes a stack, right-click half, Shift-click to inventory; clicking with a held item deposits it. Items only on tape are tinted blue.
- **Autocrafting runs on your racks.** Encode a **Recipe Pattern** from a **Blank Pattern** and store it on the network; its output shows a `+`. Ctrl-click to request any amount, and the planner chains patterns for multi-step recipes. Racks lend general compute (see *Compute* below) at 0.25 crafts per second per point: first racks on the storage's fiber network, then any online cluster. **Racks lending compute stop mining** while the job runs. The terminal's job row shows the job's status and progress; hover it for details.
- **Wireless Transmitter + Wireless Terminal:** sneak-right-click the transmitter with the terminal to link it. Range levels are 16, 32, 64, 128, 256 and 1,024 blocks, then unlimited in the dimension, then every dimension. Each level is bought with RackCoin or resources in the transmitter's screen, and doubles its power draw (0.5 kW up to 64 kW). While the transmitter's area is unloaded, wireless access uses the drives it last saw.

- **Item Pipe:** connects Storage Arrays and Tape Libraries to the machines that use items. Once a second it tops up Kids' Art Tables (32 paper, a box of crayons) and Scriptorium Desks (32 paper, 8 ink sacs) and sends their Art Aggregates and Text Corpora back to storage; it fuels Diesel Generators (the best fuel on hand first, empty buckets go back) and keeps 4 Fuel Cells in each Modular Reactor. Hoppers still work for chest-fed setups.

Drive contents are stored with the world, keyed to each drive, so drives keep their items when moved between arrays.

## Nuclear

Every nuclear machine is a **cube multiblock**: build a solid cube of the same machine, 2x2x2 up to 5x5x5, and it works as one (the casing changes to show it). Every block is a core, items put into any core are shared evenly across the cube, and bigger cubes are more economical: 5% less fuel or power per core for a 2-cube, up to 20% for a 5-cube. Anything that isn't a whole cube shows an amber "not formed" fault.

| Step | Machine | In | Out | Per batch, per core |
| --- | --- | --- | --- | --- |
| Mine | Uranium Ore (Y -64 to 16, iron pickaxe) | | Raw Uranium | |
| Mill | Uranium Mill | Raw Uranium | Yellowcake | 10 s, 4 kW |
| Enrich | Gas Centrifuge | 4 Yellowcake | Enriched Uranium + 3 Depleted Uranium | 30 s, 10 kW |
| Fabricate | Fuel Fabricator | Enriched Uranium + Steel Ingot | 2 Fuel Cells | 20 s, 6 kW |
| Burn | Modular Reactor | Fuel Cell | 500 kW for 30 min, then Spent Fuel | |
| Seal | Cask Sealer | 4 Spent Fuel + 4 Depleted Uranium | Sealed Waste Cask | 30 s, 4 kW |

A **Modular Reactor** runs on its own too: 500 kW, one Fuel Cell per 30 minutes at full output, less at part load. A reactor array adds up every core (4 MW for a 2-cube, 62.5 MW for a 5-cube) and shares fuel and waste across the cores. Every burnt-out cell comes out as **Spent Fuel** in the waste slot (16 per core); once the waste slots are full the reactor stops until you empty them. A reactor's heat, 30% of its output, goes into its coolant loop if it is piped to one, otherwise into the air around it.

**Spent Fuel is radioactive**: carrying any gives Radiation Sickness (level II from 8 rods, III from 32), which hurts every two seconds. It's safe inside machines, chests and storage, and a Sealed Waste Cask is safe anywhere.

**Automation:** connect the cubes and a Storage Array with Item Pipe. Each cube is stocked from storage and sends its products and by-products back, and reactors take Fuel Cells and hand back Spent Fuel, so ore in storage ends up as Fuel Cells, and Spent Fuel ends up as casks, without anyone touching it. Hoppers work on any core too.

## Finding Problems

On a big farm, three things point you to trouble:

- **Rack fronts** show their health: a blinking amber bar when a rack is slowed (hot, or short of bandwidth), red when it is stopped (no power, tripped, offline, overheated, no liquid cooling, no CDU).
- **The fault finder:** hold a **Multimeter** and every machine with a problem within 128 blocks is outlined through walls, red for stopped and amber for slowed, with a line at the top of the screen counting them and pointing to the nearest ("Nearest: Rack: no power, 23 m north-east"). It covers racks, cut cables, offline storage, dry pumps, overloaded coolant loops, generators and reactors out of fuel, full reactor waste, and nuclear cubes that aren't formed, have no power or are full.
- **The Operations Terminal** groups rack problems by kind and area ("48 racks unpowered around 72, 64, 30") under a one-line summary of how many racks are stopped and slowed.

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

`config/rackcraft.json` is written on first launch. Main keys include `sim.stepTicks` (10), `thermal.ambientC` (24), `thermal.cellCapacityKjPerK` (4), `thermal.faceConductanceKwPerK` (2), `thermal.upwardMultiplier` (2), `thermal.leakKwPerK` (0.005, through walls), `thermal.outdoorLeakKwPerK` (0.4, under open sky), `thermal.maxActiveCells` (65536), `thermal.settleEpsilonK` (0.03), `events.enabled`, `events.perHour` (3), and `heatOverlay.maxCells` (2000). Config files from before the air model was rebuilt (no `version`, or below 2) get the new thermal defaults once.

## Commands

Operators (permission level 2) can use `/rackcraft credits add <n>`, `/rackcraft event <id>`, `/rackcraft heat set <x> <y> <z> <celsius>`, `/rackcraft facility info`, `/rackcraft sim step <n>`, `/rackcraft locate datacenter [variant] [radius]`, `/rackcraft structure place <variant>` (or `structure datacenter` for Site 7), and `/rackcraft contracts offer` (post a contract offer now). Event IDs are `utility_outage`, `cooling_failure`, `hardware_failure`, `cable_cut`, `heat_wave`, and `surge`.

## Heat Management

Rack intake temperature controls thermal throttling: performance begins to fall above 27 C and trips at 40 C. See *Cooling* above. Empty server racks are valid and can be configured before modules are installed.

## License

MIT. See [LICENSE](LICENSE).
