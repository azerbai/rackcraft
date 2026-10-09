# Batch B: Blueprints and Pods

Read `00-conventions.md` first. Best built after Batch A (it reuses A's ghost/preview renderer and the Wireless Terminal storage hookup), but does not strictly need it: if the A renderer doesn't exist, write a minimal shared `client/render/GhostRenderer.java` and note it.

## Goal

Copy and upgrade whole facilities, and replace "wire 24 racks one by one" with a rack block that acts as one machine.

## Features

### B1. Blueprint Scanner and Blueprint Printer
- **Blueprint** item: stores a region of Rackcraft-relevant blocks as NBT: block states, facings, block-entity configuration (rack module loadouts, filter slots, Patch Panel assignments if Batch A exists, pipe/cable links come free from block states), relative to an origin and rotation. Size cap 64x64x64 (config `blueprint.maxVolume`). It must be compact: store a palette plus a run-length list, not one NBT compound per block.
- **Blueprint Scanner** (item): two corner points set with right-click on blocks (reuse the pairing pattern of `item/SurveyStakeItem.java` and `client/SurveyOutline.java`), then sneak-right-click in air to scan into a blank Blueprint item from inventory. Scans only: Rackcraft blocks, cables, plain structural blocks, and vanilla blocks listed in a tag `#rackcraft:blueprint_blocks` (stone variants, glass, concrete, iron, etc). Everything else is skipped and reported ("skipped 14 blocks the printer cannot print").
- **Blueprint Printer** (block, machine): takes a Blueprint in its slot, a **position and facing** set with the Survey Stakes or the block's own position, and prints. Use the **Site Planner** for the actual build: look at `world/SitePlanner.java`: it already plans, quotes, buys missing parts through an Exchange Auto-Buyer, shows a price quote and ledger, and builds with Construction Drones. Do not write a second builder. Add a `Blueprint` source to the Site Planner (a plan type that takes a Blueprint instead of a layout like the Data Hall) and have the Printer be a thin UI that feeds the planner a Blueprint plus an origin. Also let a Site Planner accept a Blueprint item directly in a slot, since that is the least work.
- Quote: the planner's existing quote UI shows the part list, RC cost for items it must buy, and drone time. Blueprint printing must go through that same approval step (the owner's rule: no surprise spending).
- Module loadouts: a rack in a Blueprint prints with its modules only if those modules are available from storage (never conjured); missing ones are listed in the quote and left empty.
- Security: a Blueprint is just data; reading one must validate everything (bounds, ids) and ignore unknown block ids safely, so a corrupted item can't crash the server.
- Gate: research **Digital Twin** (AI work: ~25M RC + 300M AI; requires Advanced Packaging). Printer is Assembly Line made. Scanner: circuit boards, ender eye, 2 survey stakes, 4 GPU Chips.
- Darknet hook (small, optional): rivals occasionally list a pre-made Blueprint of a Data Hall as a lot (`darknet/DarknetGoods.java`). If this is awkward, skip and note it.
- Self-test: scan a small 5x5x5 made of cable, a rack with modules and a generator; print it elsewhere through the Site Planner with drones and storage (use the self-test's `runSite` helper that presses Approve on a quote); the copy matches (blocks, facings, modules). A truncated/invalid Blueprint is rejected without exception.

### B2. Retrofit Drone
Upgrades existing machines in place with the modules/contents kept.
- A **Retrofit Order** is given to a Site Planner (extend its plan types) or a Drone Dock: select a region, choose "upgrade X to Y": Server Rack -> High-Density Rack -> Immersion Rack -> Exascale Cabinet (when the requirements for each exist: reuse the crafting-cost items of `rack` tiers; read `RackBlock` and the rack tier recipes in `AssemblyLine.java`/content.json), cable tiers (reuse `world/CableUpgrader.java`), Diesel Generator and battery upgrades if tiers exist.
- Contents must carry over: a rack's installed modules, its settings (load limit, boot state not required), its cable connections (they're block states; keep them).
- Costs: the upgrade's materials come from storage via the existing planner's procurement. Show it in the quote.
- Do **not** add a new drone entity if the existing `ConstructionDroneEntity` can do the job; "Retrofit Drone" can be a *job type* plus an item name for flavour. Keep the entity count low.
- Self-test: a rack with 3 modules upgraded to High-Density keeps the modules and gets 12 bays; cables connected before are connected after.

### B3. Compute Pod (multiblock)
A solid block of racks that works as one machine.
- Build a solid cuboid (2x2x1 up to 4x8x2, config) of **High-Density Racks or better** (config `pod.minTier`). When complete, the racks fuse into a **Compute Pod**: one power port, one coolant port and one fiber port, on a designated **Pod Port** block (a corner; framed like the cube multiblock "ports" in `world/ReactorArrays.java` and `block/ArrayMachineBlock.java`; study how cube multiblocks detect, tint their casing and expose one port: reuse that pattern).
- Pooled bays: all the racks' bays form one pool for modules (a single screen listing every bay, scrollable) instead of one screen per rack. Study `block/Racks.java`, `RackBlock` and `RackScreen` to see how bays, load and boot work today. The pod participates in the cluster and scheduler exactly like a set of racks: **do not rewrite `ComputeScheduler`**; the pod is N racks internally sharing external ports. The minimum viable design: keep the racks as racks, but treat the cuboid as one electrical/thermal/data node (a single `NetworkManager` registration for the pod, internal connectivity implicit) and add the pooled screen and interconnect bonus on top.
- Interconnect bonus: +10% AI compute at 4 racks, +20% at 8, +30% at 16+ (config), only for AI compute (not mining). Pods must have a Photonic Interconnect per 4 racks installed in the port (a slot) or the bonus is zero; this is the endgame sink.
- Heat: the pod's heat is concentrated; all of it goes to the coolant port if one is connected, otherwise to the air as for racks (reuse thermal rules so that a pod without a loop overheats and throttles, not explodes).
- One breaker: a single PDU/breaker trip takes the whole pod down. Booting is gradual as today (`sim.rackBootScale`).
- Failure behaviour: hardware failures only affect one module as today, not the whole pod.
- Gate: research **Compute Pods** (~10M RC + 120M general; requires Exascale Architecture? Pick Immersion Cooling if High-Density racks are the minimum). Pod Port: Assembly Line made from Photonic Interconnects, a Core Router and Superconducting Wire.
- Self-test: a 2x2x1 of High-Density Racks fused into a pod mines and trains the same as four separate racks with the same modules (compare totals), with one connection; breaking one rack unfuses it cleanly and the modules in it return to the player's drop (not deleted).
- Saves: a world saved with separate racks must load unchanged; fusing is a new state.

### B4. Overclocking (small)
- An optional per-rack **Load limit** exists already (`set_load_limit` in `RackcraftNetworking`). Allow up to 125% (150% with Liquid Hydrogen Cooling research) when **Overclocking** is researched (~2M RC + 30M general; requires Custom Firmware).
- Effects: output (mining RC/s and compute) scales with the limit; power draw and heat scale more steeply (limit^2). Burn-out: above 100%, each module has a small chance per minute to fail (becoming a Failed Module) unless it is tier 3+ ("never burns out in a hardware failure", an existing rule; keep it), halved by Predictive Maintenance; with a clear warning in the rack screen when overclocked.
- Never silent: the Ops Terminal Alerts tab lists overclocked racks that are throttling, and the HUD shows OC.
- Self-test: a rack at 125% produces 25% more and draws more; a Failed Module appears eventually with a seeded random (hook the RNG so the test can force it).

## Research (new)
**Digital Twin**, **Compute Pods**, **Overclocking** (and optionally **Liquid Hydrogen Cooling**). Add to `Research.java` and the README R&D table.

## Docs and assets
README: extend "Site Construction", "Compute", and "Advanced Hardware" (pods). Field Manual: a "Copying and Scaling" chapter.

## Acceptance
Self-test passes; `./gradlew test` and `build` pass; client screens (pod bay list, Printer UI, ghost overlay) unverified visually: say so.

## As built (2026-10-09)

Built with 247 self-test checks passing (`OC*`, `PD*`, `BP*`, `RT*`), `./gradlew test` and `build`. Where it departs from the spec above:

- **No Blueprint Printer block.** The Site Planner takes a written Blueprint in a material slot and a new **Blueprint** layout; that was the spec's own "least work" option. The planner's quote, ledger, Buy, drones and Procurement Wall come with it.
- **Rackcraft blocks only.** Drones carry Rackcraft's blocks, so vanilla blocks (glass, concrete) are skipped when scanning. A rack prints full of its dominant module, not with its exact bay loadout. A Blueprint prints facing as scanned (no rotation). The Darknet blueprint lot was not built.
- **Retrofit is a Site Planner layout**, not a new drone: the Rack button picks the target tier, a new `RETROFIT` drone action does the swap, and the parts come from `AssemblyLine.recipes()` so they can't drift from the real recipes.
- **Compute Pod:** racks stay racks (the scheduler and sim are unchanged); touching racks already share networks, so the pod adds the Pod Port, the fabric bonus and the shared breaker. The "pooled bays" screen is the Port's Fill pod / Empty pod buttons, not a single giant bay list. The breaker resets by itself at 30 C instead of needing a manual reset, because the owner leaves the game open.
- **Overclocking:** limit buttons 125 and 150 on the rack screen; the effective limit is min(setting, research cap). Burn-out is rolled per module each sim step.
- The scanner outline, the Pod Port screen buttons and the new rack-screen buttons are client code and unverified visually.
