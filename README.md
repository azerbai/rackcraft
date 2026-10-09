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

The machine screens open by right-clicking. Rack bays accept `pi_node`, `server_1u`, `asic_miner`, `gpu_blade`, `tensor_accelerator`, `quantum_core`, the crafting modules and `wafer_scale_engine`, one per bay; a quantum core only contributes load when a CDU is adjacent. Power, coolant, and fiber cables form independent networks.

## Mining RackCoin and the Crypto Exchange

Racks mine RackCoin (RC) into one balance shared by the whole world. A rack mines when it has modules, at least 50% power, a fiber link to a router, and an intake below 40 C. Racks **boot** once powered: 2 seconds per Pi Node, 4 per 1U Server, 5 per ASIC, 8 per GPU Blade or Tensor Accelerator and 15 per Quantum Core, so a full rack of Quantum Cores takes two minutes. Mining, power draw and heat ramp up as they boot, and a rack that loses power starts from cold again. `sim.rackBootScale` in the config scales boot times. Open a rack to see its live RC/s, or the reason it has stopped and how to fix it. Spend RackCoin at a **Crypto Exchange**: its recipe is glass panes, two CPU chips, a circuit board, steel and an emerald. Its curated tabs sell bundles of resources, rare items and Rackcraft parts (`ExchangeOffers.java`). The **All Items** tab sells almost every survival item, searchable, priced from base values plus recipe costs (`ExchangeCatalog.java`); creative-only items are excluded. Click buys one, Shift-click ten, Ctrl-click a stack. Prices work like an exchange of value for value: raw materials have a base price, everything made from them costs what went in plus 10%, and then **everything except plain building blocks costs double**. Materials, ores, storage blocks, tools, parts and machines are what RackCoin is for, while stone bricks and stairs stay cheap. Rackcraft's own hardware costs more again, so a big balance can't simply buy a whole facility: components (chips, boards, modules, drives, motors, frames, tools) cost four times as much, and machines six times (`exchange.componentPremium` and `exchange.machinePremium` in the config). A Server Rack is about 1,700 RC, a GPU Blade about 23,000, a Chiller about 22,000 and a Modular Reactor about 540,000. Materials, fuels and vanilla items are unaffected. Uranium is priced by the work behind it (2,000 RC for Raw Uranium, 50,000 for a Fuel Cell), and Spent Fuel, Waste Casks, Wafer-Scale Engines and the AGI's weights aren't for sale.

## The Darknet Terminal

For things the Exchange won't sell, there's the darknet: an auction house in a block (tinted glass, CPU chips, an eye of ender, a circuit board and steel; no power needed). It lists enchanted books (any enchantment, Mending included), spawn eggs, empty spawners (right-click one with a spawn egg to set its mob), rare loot such as elytras, totems, nether stars, beacons, trims and the occasional dragon egg, and now and then anything else the Exchange prices at 200 RC or more. It never lists the mod's own items or creative and operator items: command blocks, the debug stick, structure blocks, barriers, light blocks, bedrock and the like, and obsidian.

- **Four auctions at a time.** More listing slots cost 2,500,000, 10,000,000, 40,000,000 and 150,000,000 RC, up to eight.
- **Bidding:** each auction opens at 15% to 40% of the lot's value. The next bid must be at least 5% higher. A bid is paid from the RackCoin balance when you place it and refunded in full if you're outbid; raising your own bid costs only the difference.
- **Rivals** (`xX_Herobrine_Xx`, `definitely_not_a_creeper` and friends) bid too. Each auction has a hidden ceiling, usually near the lot's value but sometimes well under (a bargain) or over (a bidding war). Below it they answer your bids within 3 to 12 seconds; before anyone bids they nudge the price up now and then.
- **Closing:** an auction ends one minute after the first player bid, or after fifteen minutes if no player bids. The highest bidder wins.
- **Delivery:** a won lot ships by Wandering Trader, llama, Allay or worse and arrives two to six minutes later in the terminal's **dead drop**. Chat tells you when it lands; collect it at any Darknet Terminal in that dimension.

## Abandoned Data Centers

Thirteen kinds of ruined data center generate as real structures (so `/locate structure #rackcraft:data_centers` works too). They are ruins, not free farms: their racks hold mostly Failed Modules, empty bays and the odd Pi Node or 1U Server, with only a small chance of anything better, and their loot is parts rather than hardware. The Field Manual is how you learn to build the real thing. Most have a cut cable or two and a loot chest:

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
| `hyperscale_campus` | Plains, savannas, deserts, snowy plains, meadows (**extremely rare**: never within 5,000 blocks of spawn, at most one per 320 x 320 chunks and only a third of those) | **176 x 176 blocks, fully working**: four data halls (192 racks of ASICs, GPUs, 1U servers and Tensor Accelerators, all with Rear-Door Coolers and chiller banks), a quantum vault (8 Quantum Core racks cooled by towers on the reservoir), a 3x3x3 reactor array with spare Fuel Cells, an operations centre with an AI wing, and backup utility, diesel and batteries. Only five cut cables keep it dark; repair kits are in the guard hut by the gate |

Operators can find one with `/rackcraft locate datacenter [variant] [radius]` (radius in chunks, default 100, or 1,500 for the campus) and build one in front of them with `/rackcraft structure place <variant>` (`/rackcraft structure datacenter` still builds Site 7).

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
| Crafting Coprocessor | 0 | 8 | 0.5 | air |
| Crafting Accelerator | 0 | 30 | 2 | liquid |
| Wafer-Scale Engine | 0 | 40 | 150 | liquid (2 to 16 kW; only a Wafer Fab makes it) |
| FPGA Module | 8, or | 20, or | 8 (one mode at a time) | air (0.4 to 2.5 kW) |
| NPU Inference Card | 0 | 2 | 32, contracts and leases only | air (0.3 to 1.8 kW) |
| Neuromorphic Core | 0 | 80 | 4 | air (0.2 to 1.2 kW) |
| Photonic Tensor Core | 0 | 60 | 300 | liquid (3 to 12 kW) |
| Quantum Annealer | 120 | 10 | 30 | liquid (4 to 14 kW, and a Cryostat beside the rack) |

A Server Rack has eight bays and every module takes one, so it holds eight GPU Blades (24 kW) or eight Quantum Cores (72 kW, and a CDU beside it). The last five modules and the bigger racks are advanced hardware: see below.

## Advanced Hardware

Past the Wafer-Scale Engine, hardware is manufactured on the Assembly Line, each step behind research, and **none of it is sold on the Crypto Exchange** (nor are its materials).

- **Materials.** Advanced Materials unlocks two processing cubes: the **CVD Furnace** (4 Coke + a Hydrogen Canister to 2 **Graphene Sheets**, 60 s, 150 kW per core) and the **Epitaxy Reactor** (8 Raw Bauxite + a Hydrogen Canister to **Gallium Nitride**, 90 s, 250 kW per core). On a pipe to a Hydrogen Tank they draw hydrogen from it. **Superconducting Wire** is a Cryo Coil wound with 8 Copper Wire and cooled with 2 Hydrogen Canisters on the line.
- **Chiplets and the silicon lottery.** After Chiplets and Advanced Packaging, a line dices a **Wafer-Scale Engine** (weld, 2 Graphene, rivet) into 8 chiplets that all come off as one bin: 60% **bronze**, 30% **silver**, 10% **gold**. Better modules need better bins, and a chiplet can be crafted down a bin, never up.
- **Parts.** An **HBM Stack** is a RAM Module plus 3 more, a bronze chiplet and a Graphene Sheet. A **Photonic Interconnect** (Silicon Photonics) is Gallium Nitride, a silver chiplet and 4 Fiber Cable.
- **Modules** all start as a crafted **Blade Chassis**, and the first part fitted decides what it becomes: GPU Chips an **FPGA** (right-click to reflash it for mining, AI or autocrafting), 4 bronze chiplets an **NPU**, 2 silver a **Neuromorphic Core**, 2 gold a **Photonic Tensor Core**, Superconducting Wire a **Quantum Annealer**. Tier 3 and 4 hardware never burns out in a hardware failure.
- **Cryostat.** A rack with Quantum Annealers only runs with a cold Cryostat touching it: 15 kW and Hydrogen Canisters in its slot. Each running Annealer boils off a canister every 5 minutes; an Item Pipe keeps 16 in it from storage or a tank. It's crafted from Superconducting Wire, Cryo Coils, steel blocks and a Hydrogen Tank.

| Rack | Bays | Built from | Research | Notes |
| --- | --- | --- | --- | --- |
| Server Rack | 8 | crafted | | |
| High-Density Rack | 12 | a Server Rack, 2 Cryo Coils, a CDU and a Rear-Door Cooler | High-Density Racks | Built-in rear door: up to 40 kW of its air heat goes to its loop |
| Liquid-Immersion Rack | 16 | a High-Density Rack, 2 CDUs, 8 Coolant Pipe, 4 Graphene | Immersion Cooling | All its heat goes to the loop; won't run off one |
| Exascale Cabinet | 24 | an Immersion Rack, 8 Photonic Interconnects, 4 Superconducting Wire, a Core Router, 4 HBM Stacks | Exascale Architecture | +20% mining and compute, half the bandwidth, tier 2+ modules only, 30 kW overhead, all heat to the loop |

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
- **Contracts:** Compute Leases at the top, then offers, accepted work with progress bars and deadlines, and history.
- **Clusters:** each cluster's racks, compute, online state, what its racks are doing, mining rate and policy.
- **Models:** every model's cap and ceiling, speed, training queue, and its own upload and pause buttons.
- **R&D:** research projects, repeatables and the frontier run (see *R&D and the Frontier*).
- **Alerts:** every rack that is unpowered, tripped, overheated, offline or needs water; offline storage and drives over 90% full; pumps that are dry or on salt water; cut cables; generators out of fuel; art tables and desks out of supplies; late contracts; smog.

## R&D and the Frontier

The late game: once the cluster is big, the Operations Terminal's **R&D** tab turns its spare compute and RackCoin into permanent upgrades, and gives the facility something to grow toward.

**Projects** cost RackCoin to start and then compute-seconds, which free racks on Auto clusters work through instead of mining (they show *Busy: R&D*). General projects use general compute, AI projects AI compute. The **Research share** button (25%, 50% or 100%) sets how much of the free compute research may take; the rest trains models and mines. Starting a project pays for it; pausing it, or starting another, keeps its progress.

| Project | Needs | Cost | Effect |
| --- | --- | --- | --- |
| Custom Firmware | | 50,000 RC + 2M general | Racks boot twice as fast and mine 5% more |
| 80 PLUS Titanium PSUs | | 100,000 RC + 3M general | Racks draw (and heat) 10% less |
| Coolant Chemistry | | 100,000 RC + 3M general | Every heat sink takes 20% more |
| Synthetic Data | | 150,000 RC + 4M AI | Training takes 40% less compute per item |
| Enterprise Sales Team | | 250,000 RC + 2M AI | Unlocks Compute Leases |
| ASHRAE A2 Envelope | Coolant Chemistry | 400,000 RC + 10M general | Racks throttle from 30 C and trip at 43 C |
| Predictive Maintenance | Custom Firmware | 300,000 RC + 8M general | Hardware failures no longer destroy modules |
| Distillation | Synthetic Data | 500,000 RC + 12M AI | Contract work takes 20% less compute |
| Reactor Uprate | Titanium PSUs | 1,000,000 RC + 15M general | Modular Reactors make 20% more from the same fuel |
| Extreme UV Lithography | Titanium PSUs, Coolant Chemistry | 2,000,000 RC + 25M general | Unlocks the Wafer Fab |
| Cryogenic Hydrogen Storage | Titanium PSUs | 1,500,000 RC + 20M general | Unlocks Hydrogen Tanks, and Tanker Drones at the Exchange |
| High-Density Racks | Coolant Chemistry | 800,000 RC + 15M general | High-Density Racks on the Assembly Line |
| Advanced Materials | Cryogenic Hydrogen Storage | 3,000,000 RC + 40M general | CVD Furnace, Epitaxy Reactor, Superconducting Wire, FPGA Modules |
| Chiplets and Advanced Packaging | Lithography, Advanced Materials | 6,000,000 RC + 80M general | Wafer dicing, HBM Stacks, NPUs, Neuromorphic Cores |
| Immersion Cooling | High-Density Racks, Advanced Materials | 8,000,000 RC + 100M general | Liquid-Immersion Racks |
| Silicon Photonics | Advanced Packaging | 15,000,000 RC + 200M AI | Photonic Interconnects, Photonic Tensor Cores |
| Quantum Annealing | Advanced Packaging | 20,000,000 RC + 250M general | Quantum Annealers |
| Exascale Architecture | Silicon Photonics, Immersion Cooling, Structural Engineering | 60,000,000 RC + 800M AI | Exascale Cabinets |
| Structural Engineering | Reactor Uprate | 5,000,000 RC + 100M general | Cube multiblocks up to 7x7x7 |
| Space Frame Design | Structural Engineering | 25,000,000 RC + 400M general | Cube multiblocks up to 9x9x9 |
| Arcology | Space Frame Design | 100,000,000 RC + 1.5B general | Cube multiblocks up to 10x10x10 |

Every cube multiblock (reactors, batteries, the processing and utility cubes, the Electrolyser) stops at 5x5x5 until the megastructure research is done, so a new world can't build its way straight to the end. A cube bigger than the research allows stays a pile of lone blocks, and the fault finder and its screen say which research it needs. Bigger cubes keep getting better, more gently past 5: fuel and power per core drop 2.5% per step (67.5% at 10x10x10), batteries hold 5% more per bank per step (65% more at 10) and reach 99% efficiency. Cubes from 6x6x6 to 9x9x9 wear a heavy steel casing with hazard-striped corners, and a 10x10x10 its own gold-trimmed one.

**Repeatables** never end: Hash Kernel Tuning (+5% mining a level), Cooling Science (+5% sink capacity), Power Electronics (3% less rack power) and Inference Optimisation (+5% AI compute). Each level costs 2.5 times the RackCoin and twice the compute of the last.

**Compute Leases** (after Enterprise Sales): clients such as Creeper Insurance Co. rent a block of AI compute (15% to 50% of your facility's, so offers grow with you) for 10 to 60 minutes, with a 99% or 99.9% uptime guarantee (the stricter one pays 30% more). They pay 1.15 RC per AI-compute-second, in full at the end if uptime met the guarantee, and 5% less for every 0.1% short, so 2% short pays nothing. A lease that can't reach a paying uptime any more is breached on the spot. Leases borrow racks from online Auto clusters ahead of contracts, and two run at once (three after Gemerald Ultra Max). Outages, trips and overheating cost uptime, so they reward redundant power and cooling.

**The Wafer Fab** is a cube multiblock like the nuclear machines, built from steel blocks, cryo coils, GPU chips and netherite. It does nothing until Extreme UV Lithography is done; then each core etches 16 Silicon and 4 GPU Chips into a **Wafer-Scale Engine** every 5 minutes at 400 kW (a 3x3x3 draws over 10 MW, so plan on a reactor array). The engine is the endgame AI module: 150 AI and 40 general compute per bay, 16 kW of heat, 20 seconds to boot, no mining, and it can't be crafted or bought.

**Frontier runs** train ever bigger models, one after another. A run trains on your single biggest Auto cluster, which must keep lending at least the run's minimum AI compute. It takes every AI rack there, ahead of everything else. If the cluster falls short mid-run (an outage, a trip, an overheated hall), the run **rolls back to its last checkpoint**, saved every 5%. Pausing a run first saves a checkpoint where it stands, so plan maintenance.

| Run | Needs | Cost | Minimum cluster | Reward |
| --- | --- | --- | --- | --- |
| Gemerald Ultra | Distillation, Enterprise Sales | 1M RC + 25M AI | 1,000 AI | Contracts pay 25% more |
| Gemerald Ultra Max | Ultra, Lithography | 5M RC + 100M AI | 4,000 AI | Three leases at once, paying 25% more |
| Gemerald Ultra Max Pro | Ultra Max | 25M RC + 400M AI | 12,000 AI | Racks lend 20% more AI compute |
| Gemerald Infinity (Preview) | Ultra Max Pro | 100M RC + 1.5B AI | 30,000 AI | +50% general compute, +25% mining |
| HEROBRINE-1 | Infinity | 500M RC + 6B AI | 80,000 AI | AGI: +50% mining, +25% all compute, its weights in your outbox, and opinions |

Once HEROBRINE-1 is online it comments on your facility in chat every eight to fifteen minutes. The repeatables keep going after it.

## Events

Events start about three times an hour once a facility has run for a while (`events.perHour`, `events.enabled`), and they all do something:

| Event | What happens |
| --- | --- |
| Utility outage | Utility Intakes supply nothing for 1.5 to 3 minutes |
| Cooling failure | Every heat sink, CRAC and exhaust fan stops for 2 minutes |
| Heat wave | For 5 minutes intakes breathe 4 C hotter air, and dry coolers and cooling towers lose 30% |
| Hardware failure | One module per 64 racks (one to three) burns out into a Failed Module, and chat says where. Tier 3 and 4 modules (Quantum Cores, Wafer-Scale Engines and up) are spared, and Predictive Maintenance stops it entirely |
| Cable cut | A random power or fiber cable on a network that feeds a server rack is cut, and the terminal shows where. With no such cable, nothing happens |
| Surge | Every rack on a power network without a Battery Bank reboots from cold |

## Cooling

Every kilowatt a rack draws comes back out as heat, and a rack slows down once the air at its front passes 27 C and stops at 40 C. An unpowered rack makes no heat. Heat leaves a rack two ways, and the rack screen shows both (**To loop** and **To air**):

- **Air.** Out of the back into the room. Air heat spreads block by block and leaks away: slowly through walls, quickly outdoors. Keep it off the intakes with hot and cold aisles, blanking panels and raised floor, or catch it:
  - **Rear-Door Cooler**: placed against a rack's back face, it catches up to 40 kW of that rack's exhaust into its coolant loop. A **CDU** against a rack's back does the same, so the CDU a Quantum Core rack needs can sit behind it without trapping the exhaust. Any other solid block behind a rack pushes its exhaust out sideways, often into the next aisle.
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

## Utility Plants

Three more cube multiblocks (2x2x2 to 5x5x5), each doing something no other machine does. Their casings change when formed; they make no items, so they have no port.

| Plant | What it does | Numbers |
| --- | --- | --- |
| **Desalination Plant** | Makes fresh water for Cooling Towers from any water touching the cube, the sea included, and never drains it | 2 units per core (a tower wants 4), 20 kW per core |
| **Grid-Tie Substation** | Sells spare solar, wind and reactor output to the outside grid for RackCoin, once everything else is powered and batteries are charged. Never drains a battery, runs a diesel or resells the utility feed; exported reactor power still burns fuel | Up to 1 MW per core; 0.05 RC per kJ at night, 60% of that before dusk, 2.5 times as much in the evening peak |
| **Heat Recovery Plant** | A coolant-loop heat sink that sells the heat to nearby villages as district heating. Needs no power or water, only customers | 50 kW per villager within 64 blocks (half again in cold biomes, less in hot ones), up to 400 kW per core; 0.4 RC per kJ |

## Industry and Drones

Once RackCoin stops mattering, power is what's left to spend.

- **Electrolyser** (cube, 2x2x2 to 5x5x5, with water against its outside): each core turns an Aluminium Ingot into a Hydrogen Canister every 30 seconds at 4,000 kW. A 3x3x3 draws about 100 MW and a 5x5x5 about 400 MW. Canisters gather in the port. Hydrogen can't be bought at the Exchange.
- **Hydrogen Tank** (cube, 2x2x2 to 5x5x5, or 10x10x10 with research; no power; inert until **Cryogenic Hydrogen Storage** is researched): holds hydrogen as gas, 256 canisters' worth per block and more per block in bigger cubes (40% more in a 5x5x5). An Electrolyser touching a tank, or on the same Item Pipe or Storage Link network, fills it instead of making canisters, with no Aluminium Ingots, and stops only when every tank is full. Storage counts a tank's hydrogen as Hydrogen Canisters and hands it out as them, so Drone Docks, Site Planners, Launch Controls and Assembly Robots draw on the tank through their usual storage connections.
- **Tanker Drone**: the one drone that can't be built. The Crypto Exchange sells it for 50,000,000 RC (`exchange.tankerDronePrice`), and only lists it once Cryogenic Hydrogen Storage is researched. Kept in a Hydrogen Tank's slot (up to 8), each tanker flies a stack of up to 16 canisters at a time, high and fast, to any Drone Dock, Site Planner or Launch Control within 256 blocks whose hydrogen slot isn't full, emptiest first, and comes back for more. Depots on the tank's own Item Pipes are left to storage.
- **Assembly Line**: Conveyor Belts carry one item each, a block a second, onto the next belt, into a container, or off the end; items dropped on a belt ride it, and so do players. Robots stand beside a belt facing it, stop any workpiece they can work on in the middle of the belt, and do their step:

| Robot | Step | Power while working |
| --- | --- | --- |
| **Welding Robot** | Weld, 6 s | 1,500 kW |
| **Riveting Robot** | Rivet, 4 s | 600 kW |
| **Assembly Robot** | Install parts from its nine slots, 3 s per step, several steps in a row if they're all installs | 250 kW |

  Arms idle at 1 kW, slow down below full power and stop under 10%. A workpiece remembers its progress (its tooltip says what it needs next), so one that falls off half-built can go round again. The first recipe: a **Drone Frame** gets 4 Electric Motors, a weld, 2 Circuit Boards, a Hydrogen Canister and rivets, and becomes a **Maintenance Drone**.
- **Drone Dock**: holds up to 8 drones, Hydrogen Canisters and spares (rack modules, Repair Kits). Every two seconds it sends a drone to each job within 32 blocks across and 96 up or down (enough for a Wind Tower's nacelle): swapping a Failed Module out of a rack for a spare (the dead one comes back, and a hopper under the dock can take it away), splicing a cut cable with one of a Repair Kit's repairs, resetting a tripped PDU breaker, or servicing a worn Solar Array or Wind Tower. Each trip burns an eighth of a canister; the dock draws 2 kW. On an Item Pipe that reaches storage, a dock also draws drones, hydrogen, Repair Kits and replacement modules from it as needed, and files dead modules away. A Failed Module remembers what it was (its tooltip says so), so a burned-out GPU Blade is replaced with a GPU Blade, never just any spare. Hit a drone and it drops as an item.

**Belt Loaders** and **Belt Unloaders** connect a line to storage by Item Pipe: a Loader holds a sample item and puts another from storage onto the belt it faces whenever that belt is empty; an Unloader at the end of the line files whatever arrives back into storage. Neither needs power. A **Storage Exporter** does the same for any machine or container: on an Item Pipe, facing a Site Planner, Launch Control or chest, it keeps that block topped up to a stack of each of the up to nine sample items in its slots (also no power).

Each belt carries one item, so only one robot works on it at a time; a second robot beside the same belt helps only if it does a different step. Lengthen the line instead. A Satellite Bus can become any of four payloads: the first part an Assembly Robot fits decides which.

An Assembly Robot on an Item Pipe stocks itself: besides topping up the parts already in its slots, it watches the belt in front of it and the five belts feeding it, and when a workpiece's next step is installing a part it has none of, it takes that part from storage into an empty slot (up to 16), buying it if an Exchange Auto-Buyer is on the network and the Exchange sells it. Three R&D projects, **Brisk Belts**, **Frantic Belts** and **Benny Hill Belts**, each double the speed of every Conveyor Belt and every Welding, Riveting and Assembly Robot (2x, 4x, then 8x), for the same power.

## The Launch Programme

A **Launch Control** touching a flat 3x3 **Launch Pad** (with open sky over all nine blocks) launches payloads. The rocket is sized to the job: one stage is a slim rocket about 14 blocks tall, two stages a Saturn IB about 19, and three stages a full Saturn V three blocks across and about 37 tall. Players near the pad get the T-minus count on screen. **Launch Tower** blocks are see-through red lattice for building a service tower beside the pad; they're decoration only. Load Rocket Stages, a payload and Hydrogen Canisters (its tank holds 4,096; hoppers and Item Pipes can fill it), press Launch, and after a ten-second countdown the rocket lifts off. Every stage burns 128 canisters. About one launch in fifty fails: the stages and fuel are lost, the payload comes back.

| Payload | Built from a Satellite Bus with | Stages | In orbit |
| --- | --- | --- | --- |
| **Comms Satellite** | 16 Copper Wire, 4 Circuit Boards, weld, rivet | 1 | Each (up to 5): Darknet parcels 10% sooner, one more Compute Lease slot |
| **Survey Satellite** | Thermal Scanner, 2 Circuit Boards, rivet | 1 | A map to the nearest undiscovered Hyperscale Campus (or data center), left in the Launch Control |
| **Orbital Data Center** | 4 Wafer-Scale Engines, 4 Cryo Coils, weld, rivet | 2 | +3% AI and general compute on every rack, no limit |
| **Dyson Mirror** | 16 Solar Panels, weld, rivet | 3 | 2 MW beamed to your **Rectennas** (shared between them, up to 20 MW each), no limit |

Launches sound and feel like launches. Rackcraft's own synthesised sounds (no vanilla stand-ins) carry a mission-control countdown, a six-second ignition sequence from T-6 (igniters, turbopumps, building roar), a huge liftoff hit, the crackling roar of full thrust following the rocket up, the bang and ring of stage separation, and, for a failure, a proper explosion. Bigger rockets are louder and deeper: a Saturn V can be heard about 500 blocks away, and players further off (up to 768 blocks) hear a distant rumble. A Saturn V also shakes the camera of anyone within about 190 blocks (following the Distortion Effects slider), flashes the pad, boils the water deluge into steam, throws twice the fire and a far bigger ring of exhaust, leaves a hanging column of smoke, and separates its stages twice on the way up. The sounds are generated by `tools/sounds.py` (needs numpy and soundfile).

Rocket Stages are built on the line too: a Stage Frame gets a weld, 4 Electric Motors, 2 Circuit Boards, another weld and rivets.

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
- **Autocrafting runs on your racks.** Encode a **Recipe Pattern** from a **Blank Pattern** and store it on the network; its output shows a `+`. Middle-click it (or Ctrl/Cmd-click) to request any amount, even when some is already in stock; a plain click works when there's none. The planner chains patterns for multi-step recipes. Racks lend general compute (see *Compute* below) at 0.25 crafts per second per point: first racks on the storage's fiber network, then any online cluster. **Racks lending compute stop mining** while the job runs. The terminal's job row shows the job's status and progress; hover it for details.
- **Storage Link:** joins storage without another cable. Every powered link in a dimension is linked to the others, and the storage on each link's fiber and Item Pipe networks becomes one: terminals, Wireless Terminals, Item Pipes and machines reaching any link see all of it, and a machine touching a link (a Site Planner, Drone Dock or Launch Control) draws on it with no pipe at all. 4 kW; touching any powered machine is enough to power it.
- **Exchange Auto-Buyer:** the company credit card. On a storage network (touching a Storage Array, Storage Link, Item Pipe or a machine that restocks from storage) and powered (2 kW), it buys at Exchange prices whatever a machine restocking from that storage finds missing: generator fuel (the best the Exchange sells, when storage has none), Fuel Cells, processing inputs, paper and ink, Repair Kits and replacement modules for Drone Docks, Assembly Robot parts, Storage Exporter and Belt Loader items, and Site Planner materials. It can't buy what the Exchange doesn't sell (drones, hydrogen, Assembly Line products), and its screen shows what it has bought. With power, a fuel or hydrogen supply, and coin, a facility keeps itself running.
- **Wireless Transmitter + Wireless Terminal:** sneak-right-click the transmitter with the terminal to link it. Range levels are 16, 32, 64, 128, 256 and 1,024 blocks, then unlimited in the dimension, then every dimension. Each level is bought with RackCoin or resources in the transmitter's screen. Up to 1,024 blocks each level doubles its power draw (0.5 to 16 kW). The last two are endgame: unlimited range in the dimension costs 1,000,000 RC (or 4 nether stars, 8 netherite ingots and 32 eyes of ender) and draws 250 kW; every dimension costs 10,000,000 RC (or the dragon egg, 8 nether stars and 4 netherite blocks) and draws 1 MW. While the transmitter's area is unloaded, wireless access uses the drives it last saw.

- **Item Pipe:** connects Storage Arrays and Tape Libraries to the machines that use items. Once a second it tops up Kids' Art Tables (32 paper, a box of crayons) and Scriptorium Desks (32 paper, 8 ink sacs) and sends their Art Aggregates and Text Corpora back to storage; it fuels Diesel Generators (the best fuel on hand first, empty buckets go back) and keeps 4 Fuel Cells in each Modular Reactor. Hoppers still work for chest-fed setups.

Drive contents are stored with the world, keyed to each drive, so drives keep their items when moved between arrays.

## Nuclear

Every nuclear machine is a **cube multiblock**: build a solid cube of the same machine, 2x2x2 up to 5x5x5, and it works as one. Its casing changes to show it, and every kind of cube has its own face: a reactor's trefoil, a centrifuge's rotors, a fab's wafer. Every cube that makes items has a **port**: its bottom north-west corner, framed in cyan with an output hatch. Products, by-products and a reactor's Spent Fuel gather there, and the port's screen has a **Collect all** button that hands you everything the whole cube holds. A processing cube short of power runs at the share it gets (a cube with a third of its power works at a third of the speed) and only stops below 10%; its screen shows what it needs and how much of that the grid covers. Every block is a core, items put into any core are shared evenly across the cube, and bigger cubes are more economical: 5% less fuel or power per core for a 2-cube, up to 20% for a 5-cube. Anything that isn't a whole cube shows an amber "not formed" fault.

| Step | Machine | In | Out | Per batch, per core |
| --- | --- | --- | --- | --- |
| Mine | Uranium Ore (Y -64 to 16, iron pickaxe) | | Raw Uranium | |
| Mill | Uranium Mill | 2 Raw Uranium | Yellowcake | 30 s, 20 kW |
| Enrich | Gas Centrifuge | 6 Yellowcake | Enriched Uranium + 4 Depleted Uranium | 120 s, 60 kW |
| Fabricate | Fuel Fabricator | Enriched Uranium + 2 Steel Ingots | Fuel Cell | 60 s, 30 kW |
| Burn | Modular Reactor | Fuel Cell | 500 kW for 30 min, then Spent Fuel | |
| Seal | Cask Sealer | 4 Spent Fuel + 4 Depleted Uranium | Sealed Waste Cask | 60 s, 15 kW |

Two more processing cubes work the same way: the **Silicon Foundry** (2 Nether Quartz + 4 Sand to 8 Silicon, 20 s, 40 kW per core; steel, blast furnaces, copper wire and a cauldron) and the **E-Waste Recycler** (a Failed Module to 3 Silicon and a Copper Wire, 15 s, 10 kW per core; steel, pistons, grindstones, a circuit board and a hopper). One feeds the Wafer Fab; the other turns hardware failures back into parts.

Fuel Cells can't be crafted or bought: every one comes out of a Fuel Fabricator, twelve Raw Uranium each. The machines themselves are built from steel blocks, diamonds, GPU chips and cryo coils, and a working cube draws hundreds of kilowatts, so nuclear power takes real power to bootstrap.

A **Modular Reactor** runs on its own too: 500 kW, one Fuel Cell per 30 minutes at full output, less at part load. A reactor array adds up every core (4 MW for a 2-cube, 62.5 MW for a 5-cube) and shares fuel and waste across the cores. Every burnt-out cell comes out as **Spent Fuel** in the waste slot (16 per core); once the waste slots are full the reactor stops until you empty them. A reactor's heat, 30% of its output, goes into its coolant loop if it is piped to one, otherwise into the air around it.

**Spent Fuel is radioactive**: carrying any gives Radiation Sickness (level II from 8 rods, III from 32), which hurts every two seconds. It's safe inside machines, chests and storage, and a Sealed Waste Cask is safe anywhere.

**Automation:** connect the cubes and a Storage Array with Item Pipe. Each cube is stocked from storage and sends its products and by-products back, and reactors take Fuel Cells and hand back Spent Fuel, so ore in storage ends up as Fuel Cells, and Spent Fuel ends up as casks, without anyone touching it. Hoppers work on any core too.

## Wind and Solar

| Source | Output | Notes |
| --- | --- | --- |
| **Solar Panel** | 4 kW | Daytime, open sky, dimmed by smog |
| **Wind Turbine** | Up to 30 kW | 25% at Y 70 or below, full at Y 130, +50% in thunderstorms; no fuel and no upkeep |
| **Solar Array** | 36 kW in full sun | A placed 3x2 built on the Assembly Line (6 Solar Panels, 2 Circuit Boards, weld, rivet). Arrays that touch share power with no cables |
| **Tracking Solar Array** | 47 kW | A Solar Array sent back down the line for 2 Electric Motors and a Circuit Board; it also works at 60% through dawn and dusk |
| **Wind Tower** | 120 kW at Y 130, up to 150 kW above Y 150 | A Wind Tower Nacelle (built on the line) on at least ten Tower Sections, with a clear 5x5 in front for its blades. Power runs down the tower |

The wind is one for the whole world: it rises and falls by up to a quarter over a few minutes, for every turbine and tower at once, so a battery bank evens out a wind farm. Solar Arrays and Wind Towers wear: over three days of running they lose up to 30% of their output, until a **Maintenance Drone** from a Drone Dock within 32 blocks services them (the dock sends one at 25% wear). The basic panel and turbine never wear, so an early setup never depends on drones. Every number here is in the `renewables` section of the config.

## Site Construction

A **Site Planner** builds a whole solar field or wind farm with drones. Mark the site with a **Survey Stake** (use it on the ground at two opposite corners; the area is outlined while you hold it), then use the stake on the planner. Sites can be up to 48 x 48 and within 96 blocks of the planner. Choose a layout (**Solar Field**, **Tracking Solar Field**, **Wind Farm**, **Data Hall** or **Reactor Cube**) and press Start. The work always runs in this order:

1. **Clear**: Construction Drones take everything soft off the site (grass, flowers, snow, leaves, whole trees, bee nests), 16 blocks a trip, into storage.
2. **Level**: Terraforming Drones bring the site to its median height, digging up to 5 blocks off the high spots each trip and dropping them into the low ones. Spare earth goes into storage; when there is more hole than hill they fill from storage (dirt, cobblestone and other plain stone). Once the ground is even they dock.
3. **Build**: Construction Drones place arrays packed edge to edge (so they share power with no cables), or Wind Towers six blocks apart with their nacelles at Y 130.
4. **Wire**: they lay Power Cable between the towers and from the site back to the planner, so the site joins whatever grid the planner is on.

The planner holds Construction Drones, Terraforming Drones, Hydrogen Canisters and six slots of materials. On an Item Pipe it also takes all of these from storage. A **Data Hall** is the densest rack layout the simulation allows, and the planner buys everything for it on the company card (RackCoin) when storage doesn't have it. The site is cut into row pairs, four blocks deep, repeating:

| z | Ground tier | Second tier | Third tier | Fourth tier |
| --- | --- | --- | --- | --- |
| 0 | cold aisle (shared with the pair before) | aisle | | |
| 1 | Server Rack facing the aisle | Server Rack | Core Router | |
| 2 | CDU | CDU | Chiller | Chiller |
| 3 | Server Rack facing the next aisle | Server Rack | Core Router | |

Every rack holds eight Quantum Cores (the best mining per kilowatt, and they never burn out). Its back sits on a CDU, which Quantum Cores need beside them and which catches the 15% of heat that isn't liquid-cooled, so nothing reaches the air and the intakes stay at ambient. The racks, CDUs and Chillers touch, so they share power and one coolant loop. Each column of four racks has two Chillers (500 kW against 288 kW of heat) and two Core Routers (2,000 bandwidth against 1,920 RC/s). A column mines 1,920 RC/s and draws about 360 kW, so a full 48 x 48 hall is over 500 columns and around 190 MW: the planner's screen shows the estimate before you start. Docks for a hall go around its outside, never in the aisles.

With **Docks** switched on, a fifth phase follows wiring: the planner works out the fewest Drone Docks that reach every array and nacelle, places them touching the site (so the site powers them), using docks from storage or buying them at the Exchange, stocks each with up to 8 Maintenance Drones and 16 Hydrogen Canisters from storage, and from then on sends a Construction Drone to top up any dock that runs short. With **Buy** switched on, it buys whatever the slots and storage can't supply from the Crypto Exchange, at Exchange prices, out of the RackCoin balance; the screen shows the running total. The Exchange doesn't sell Assembly Line products or hydrogen, so those still have to be built. Each trip burns a sixteenth of a canister, and the planner draws 5 kW. Its screen shows the phase, the progress, what it is short of and anything in the way. Inside the site it moves anything without a block entity, so don't mark out your house. Outside the site, the cable only digs through earth and rock. Both drones are built on the Assembly Line from a **Heavy Drone Frame**: two Sticky Pistons first make a Construction Drone, a Diamond Shovel first makes a Terraforming Drone, then four Electric Motors, two Circuit Boards, a weld and rivets. The numbers are in the `construction` section of the config.

**Reactor Cube** builds solid cubes of Modular Reactors, a layer at a time (one drone trip per layer, up to 100 reactors), with a block of air between cubes so they stay separate cubes. The **Cube** button picks the size, 2 x 2 x 2 up to 10 x 10 x 10 (a thousand reactors, 500 MW), held to the research done (5 without it; Structural Engineering, Space Frame Design and Arcology raise it) and to what fits the site; a site holds up to 16 cubes. The Exchange sells the reactors, at machine prices, with no need for Buy to be on. Cubes make no use of docks.

**The quote.** Before the drones buy anything, the planner works out the whole bill (everything the layout, its cable, its fill and its docks need, less what the slots and storage already hold, for whatever it may buy) and tells the players nearby, item by item, in chat. The job then waits: the Start button becomes **Approve**, and until it is pressed nothing is bought and no drone leaves. A job with nothing to buy is never quoted. Each job is quoted once: changing the site, layout, blueprint, cube size, Buy or Docks starts a new job, and so does a finished site being damaged and rebuilt.

**Procurement Wall.** A Monitoring Wall that keeps the books. Powered from the same grid as one or more Site Planners (it draws 0.5 kW), it lists each planner's status and waiting quote (with an Approve button) and the ledger: every Exchange purchase the planners' drones made, with the count, the price each, the cost, what part of the job it was for (clearing, fill, build, racks, reactors, cable, docks) and how long ago. A planner remembers its last 400 purchases and keeps a running total. Within 48 blocks of a powered wall, a **HUD** in the top-right corner shows the same fleet's spending, any quote waiting for a yes, and the latest six purchases; the wall's screen has a button to turn it off (`hud.procurement` in `rackcraft.json`).

## Grid-Scale Batteries

A Battery Bank stores 3,000 kJ, charges at 15 kW and discharges at 60 kW, losing 10% each way. Build Battery Banks into a solid cube, 2x2x2 up to 5x5x5, and they become one **Grid-Scale Battery**: one store with every bank's charge and rates, 10% more capacity per bank for each step up in size (40% more in a 5-cube), and smaller losses: 8% each way for a 2-cube down to 2% for a 5-cube. The casing changes when it forms, and its screen and the Multimeter show its size, capacity and efficiency.

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

Operators (permission level 2) can use `/rackcraft credits add <n>`, `/rackcraft event <id>`, `/rackcraft heat set <x> <y> <z> <celsius>`, `/rackcraft facility info`, `/rackcraft sim step <n>`, `/rackcraft locate datacenter [variant] [radius]`, `/rackcraft structure place <variant>` (or `structure datacenter` for Site 7), and `/rackcraft contracts offer` (post a contract offer now). Event IDs are `utility_outage`, `cooling_failure`, `hardware_failure`, `cable_cut`, `heat_wave`, and `surge` (see *Events*).

## Heat Management

Rack intake temperature controls thermal throttling: performance begins to fall above 27 C and trips at 40 C. See *Cooling* above. Empty server racks are valid and can be configured before modules are installed.

## License

MIT. See [LICENSE](LICENSE).
