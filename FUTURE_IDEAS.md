# Future Ideas

Ideas tabled for later updates. Nothing here is built yet, and the numbers are starting points for balancing.

## Destructive launch failures

A failed launch is currently only particles and sound. A real explosion that damages the pad and its surroundings would mean a spaceport has to be built well away from the base.

## Rack tiers

| Tier | Rack | Bays | What's different |
| --- | --- | --- | --- |
| 1 | Server Rack | 8 | As it is now |
| 2 | High-Density Rack | 12 | A built-in rear-door cooler (40 kW); built on the Assembly Line from a rack, Cryo Coils and a CDU |
| 3 | Liquid-Immersion Rack | 16 | Every module's heat goes into the coolant loop, none into the air; must be on a loop |
| 4 | Exascale Cabinet | 24 | +20% compute, takes only tier-2+ modules, a huge power draw, locked behind research (e.g. Space Frame Design) |

## Compute Pod multiblock

A solid block of Server Racks (e.g. 2x2x1 up to 4x8x2) that works as one machine:

- One busbar power port, one coolant manifold and one fiber uplink.
- Pooled bays, and an interconnect bonus to AI compute (an NVLink-style fabric), making pods the natural home for frontier runs.
- Concentrated heat that needs a coolant loop, and one breaker for the whole pod: a single failure takes it all down.
- Could require tier-2 racks or better.
- A variant: an immersion tank, with racks sunk in dielectric fluid. No air cooling; all the heat goes to the loop.

## New chips and higher-tier components

- **Chiplets and the silicon lottery:** Wafer Fab wafers cut into dies on the Assembly Line, binned bronze, silver and gold; better bins make better modules.
- **HBM memory stack:** a high-tier part for the best modules.
- **NPU / inference card:** strong AI compute for contracts only, low heat.
- **FPGA module:** switches between mining, AI and autocrafting, at a penalty.
- **Photonic interconnect chip:** cuts a rack's bandwidth need.
- **Overclocking:** optional; more output and heat, with a chance of failure.
- **Photonic Tensor Core:** about 300 AI and 60 general compute per bay, behind a Photonic Interconnect research project.
- **Neuromorphic Core:** about 80 general compute at very low power, for autocrafting and research.
- **Quantum Annealer v2:** mines about 120 RC/s, double a Quantum Core, and needs a new Cryostat heat sink.
- **New materials to gate them:** Graphene Sheets (from coke and an Electrolyser by-product), Superconducting Wire (needs cryogenics), Gallium Nitride (from bauxite waste).

Each tier should need the previous tier's parts plus an Assembly Line step, so the top end stays a manufacturing chain rather than a crafting-table upgrade.
