# Batch E: The Strike and the Bomb

Read `00-conventions.md` first. Uses Batch D's explosion/radiation-safe rules only loosely; it does not require D, but if D exists the Military Base vault can hold the Warhead Blueprint (see D1). Uses the Launch Programme (`world/LaunchPads.java`, `entity/RocketEntity.java`, `world/OrbitState.java`) and the Darknet (`darknet/`).

## Goal

Big-consequence tools for people who finished everything: an orbital strike you build and launch as its own module, a Tactical Warhead delivered by a Darknet fixer's plane, space debris to give the launch programme risk, and destructive launch failures so a spaceport must be built away from home.

## Features

### E1. Destructive launch failures
Today a failed launch (`FAILURE_ODDS = 50`, `failureChance`) is particles and sound only. Make it real, within the "never destroy builds silently" rule:
- A failure now causes an explosion at the failure point, with a **blast radius scaling with the rocket** (1 stage ~ 6 blocks, 2 ~ 10, 3 ~ 16; config `launch.failureBlastScale`) that damages entities and breaks plain blocks, and sets fire. It **spares** Rackcraft machines, cables, racks and storage (use an explosion that ignores specified block types, or a custom `Explosion` subclass / `ExplosionBehavior` returning no damage for those; study how `RocketEntity.FAILS_AFTER` triggers the existing failure and add the explosion there). The Launch Pad and Control themselves take damage: the **pad's blocks are destroyed** (it's a flat 3x3 of Launch Pad; if the pad is destroyed the Control survives). 
- Fire and the fuel: the failure scatters the rocket's remaining Hydrogen as a short-lived fire/blast; a pad with a full 4,096 tank makes a bigger explosion (up to +50%).
- Setting `launch.destructiveFailures=false` in config restores the old cosmetic failure. Default on.
- Warn the player in the Launch Control screen and the Field Manual: "Build the spaceport away from home."
- Self-test: with `LaunchPads.failureChance = 1`, a failure at a pad next to a rack and a stone wall breaks the wall's near blocks, destroys the pad, leaves the rack intact and damages a nearby test entity. With the config off, nothing is broken.

### E2. Strike Platform (orbital strike module)
A separate launched payload, **not** tied to the Dyson Mirror.
- Item `strike_platform`: built on the Assembly Line from a Satellite Bus (the first part an Assembly Robot fits decides the payload; see `world/AssemblyLine.java` and the existing `comms_satellite`/`survey_satellite`/`orbital_datacenter`/`dyson_mirror` recipes; add this payload the same way) with a **Photonic Tensor Core** (targeting), 8 Superconducting Wire, 4 Cryo Coils, weld, rivet. Very expensive.
- Launch: add `"strike_platform" -> 3` to `LaunchPads.stagesFor` (three stages) and handle the payload in `LaunchPads` / `OrbitState` like the others: a launched platform becomes a persistent per-world orbit asset (count up to 3; each is an independent cooled-down shooter).
- **Firing:** the **Targeting Terminal** block (a machine; needs power 5 MW while charging and a fiber link): enter X/Z coordinates (and shows the chunk/biome and the distance from every player and from spawn), choose a yield (Narrow / Wide), confirm twice, and a platform fires after a 60-second countdown with a chat warning to everyone in the dimension within 500 blocks. A platform must have ≥ 10 minutes since its last shot (config; the terminal shows each platform's cooldown). The shot is an orbital beam: a column of white fire from the sky, a very loud sound, and a crater/burn of radius 12 (narrow) to 28 (wide) that destroys plain blocks and entities (and spares nothing but bedrock and Rackcraft machines on a **protected** list: add a block tag `#rackcraft:strike_immune` containing nothing by default; operators can add to it).
- Safety rails (these matter; the owner leaves the game open and can mistype): the terminal refuses targets within 128 blocks of any player's bed spawn point or world spawn; refuses unloaded chunks; requires typing the coordinates twice (a confirm field), then a 60 s countdown that can be cancelled for 55 s of it.
- Power: charging the platform's capacitor draws a large power load from the Targeting Terminal's network, so the owner has to decide between the strike and the rack hall (the balance note in `POSTGAME_IDEAS.md`).
- Gate: research **Orbital Defence** (huge: ~80M RC + 800M AI; requires Silicon Photonics and Exascale Architecture). Not on the Exchange.
- Self-test: launch (with `failureChance = 0`) a platform; `OrbitState` records it; the terminal refuses a target near spawn; fires a valid shot at a far target through a direct hook (not waiting real time); the crater matches the radius; a machine tagged immune survives.

### E3. Tactical Warhead and the Burner Phone
- **Warhead** (`tactical_warhead`, a large model/texture item; **it needs a proper bomb texture**, drawn procedurally in `tools/textures.py`; make it look like a fat tapered bomb with a yellow band and a radiation trefoil, not a recoloured existing item): Assembly Line made from a **Sealed Waste Cask**, Enriched Uranium x4, a Guidance Chip (from a gold-bin or silver chiplet; define `guidance_chip` here), steel blocks, weld, rivets. Gate: research **Fissile Ordnance** (~60M RC + 600M general; requires Orbital Defence or Nuclear processing completed; use whichever dependency exists in `Research.java`). Not on the Exchange. Also appears in the Military Base vault, if Batch D exists, as a *Warhead Blueprint* (leave a clear TODO if not).
- **Burner Phone** (`burner_phone`, one-use item): right-click opens a short chat-like screen (a text-message UI: reuse a simple screen, or use chat messages and clickable text if the screen is too much). A Darknet fixer, in character, asks **"Where are you?"** (auto-filled with the player's coordinates) and **"Where do you want it dropped?"** (X, Z entry), then states the price: **1,000,000,000 RC and the warhead**, collected from the player's inventory on confirm. The fixer then takes the bomb and the money. **Cancelling before the drop refunds only 25% of the RC** (the warhead is returned in full; owner's decision). Config: `warhead.priceRc`.
- Delivery: after a delay of 3 minutes, a **cosmetic bomber** (an entity, `entity/BomberEntity.java`: a fly-over model on a straight line at high altitude over the target, with a jet-engine sound, no AI, no collisions, not rideable; reuse `RocketEntity`/`RocketRenderer` patterns for a renderer) crosses the target and drops the warhead, which falls as a `WarheadEntity` for ~10 s and detonates on impact.
- Detonation: a huge explosion (radius ~48, config), a mushroom cloud made from particles, damage to entities, blocks destroyed except Rackcraft machines and bedrock; then **Radiation fallout**: `world/Radiation.java` already gives Radiation Sickness; add a **fallout zone** (a radius of 64, lasting ~1 hour of game time, config) that applies the existing sickness to entities inside and shows on the Fault Finder/HUD as an area. Persist the zone in a `PersistentState` and **expire it reliably**. The self-test must reset it.
- Safeguards (the owner wanted these): confirm step; refuses targets within 256 blocks of any player or the world spawn; refuses unloaded or never-visited chunks; a chat warning to all players at T-60s and T-10s; players in range can still escape by leaving; the owner can disable it all via `warhead.enabled=false`.
- Self-test: the phone flow through its server API (`Burner.confirm(player, x, z)`); the money and the warhead are taken; cancelling refunds 25%; the bomber entity spawns and the warhead detonates via a direct hook; fallout zone applies sickness and expires.

### E4. Space debris (events)
- Add to the Events system (`sim/EventScheduler.java`, `compute/ComputeEvents.java`; read the README's Events table for the shape) an event **Debris Field** that can only fire if the player has launched orbital assets (OrbitState): it damages **one** random asset (a Comms Satellite, Orbital Data Center, Dyson Mirror or Strike Platform), removing it from orbit with a funny chat line ("A defunct weather satellite from 1987 has hit your Comms Satellite. Insurance does not cover this."). The player must relaunch.
- Rate: rare (config `events.debrisPerHour`, ~0.3); a **Debris Tracker** payload (a cheap Survey-Satellite-like payload) in orbit lowers the chance of a hit by 75% per tracker (max 2).
- Never removes more than one asset per event, never during an active Strike countdown.
- Self-test: with OrbitState holding assets, forcing the event removes exactly one; with a Tracker the chance is lower (assert the computed chance).

## Research (new)
**Orbital Defence**, **Fissile Ordnance**. (Possibly **Debris Tracking** as a minor project.) Add to `Research.java` and README.

## Docs and assets
README: extend "The Launch Programme" with failures, Strike Platform, Targeting Terminal, Debris; add a "Tactical Warhead" subsection; new Darknet fixer text. Field Manual: "Weapons of Mass Destruction (don't)". All visual assets (bomb texture, bomber model) are procedural and **unverified in-client**: say so.

## Acceptance
Self-test passes with the new state reset; test and build pass. Everything destructive is config-gated with a safe default noted in the report.
