# Postgame Ideas

Written 2026-10-09 after a ~20 hour (cheated) clear. Nothing here is built. The late game today ends at HEROBRINE-1, a Saturn V and a lot of hydrogen, and then there's nothing left to *do* with any of it. These ideas give the finished facility a use: tools to build and wire faster, ways to move around it, hydrogen and energy weapons, and something worth defending against.

**Built:** Batches A, B, C and D (2026-10-09), see "Building and Wiring" and "Arms and Ruins" in the README. Build specs for each batch live in [docs/postgame/](docs/postgame/README.md).

## Decisions (2026-10-09, after review)

The sections below are the original brainstorm. Where they disagree with this list, this list wins.

**In (wanted):**
- **Building and wiring is the priority**, since it's the mid-to-late game constraint: Trunk Bundle (a must), Cable Painter with a **placement preview** (a ghost of the route and cable before it's laid, so you can see where the fiber will go), Patch Panel, Pylons, Wireless Power Beacon (loved), Blueprint Scanner/Printer, Constructor's Gauntlet, Retrofit Drone, Terraformer Cannon.
- **Movement:** Quantum Teleport Pads (loved), Mag-Lev, Grapple, Hydrogen Jetpack **only with a hover mode**.
- **All the weapons** (Flamethrower, Arc Coil, Railgun, Plasma Rifle, Lance Laser, Sentry Turrets), plus EMP.
- **Tactical Warhead**, with a proper hand-drawn-style texture, **dropped from a plane** (needs a bomber entity and a way to call it in, e.g. a laser designator; not a hand-thrown or pad-launched missile).
- **Orbital Strike as its own module you build and launch**, a separate Strike Platform payload through the Launch Programme, not tied to the Dyson Mirror.
- **Space debris and bounties.**
- **Hostile data centers** occupied by tweakers and scavengers after components.
- **A military base structure** holding high-tier loot, guarded by soldiers or robots with energy weapons.

**Reframed:**
- **The IPO is the ending, not a reset.** No new-startup-with-perks loop. Going public closes the mod. Small AI hiccups build up to it, and after the IPO, HEROBRINE-1 goes misaligned and corrupts the world: the dream ending. (See "The ending" below.)
- **PvE lives in structures only.** Nothing roams the overworld at your base.

**Out:**
- Corporate raids (hated). Rival-sent raids and the base-defence framing are gone.
- The standalone HEROBRINE-1 mid-game "drones turn on you" incident is replaced by the hiccups-then-corruption arc above.

**Maybe / later:**
- **Orbit as a real place:** interesting someday, not now.
- **Fusion Reactor:** wary of a meltdown while the game is left open. If it's ever built, it must *fail safe*: too little cooling just shuts it down (a "quench"), nothing explodes, and nothing is ever destroyed. The meltdown risk is dropped from the design.
- **Exo-Suit:** undecided.

**Explained, not yet decided:**
- **Compute Pod:** a solid block of racks (say 2x2x1 up to 4x8x2) that acts as *one* machine with one power port, one coolant port and one fiber uplink, instead of wiring a rack at a time. It pools the bays and gets an AI bonus. Given your main constraint, it's really a building tool. Bigger, later, but fits.
- **Overclocking:** an optional 125% load setting on a rack: more output, more heat, a chance of burning a module out. Less important; can wait.
- **Superconducting Busbar:** just a very high capacity power cable that needs hydrogen cooling. Dropped unless you want it; the Trunk Bundle and Pylons cover the need.
- **Network Analyzer:** a Multimeter mode that highlights the whole path between two machines and marks the weak segment ("why is this rack on 40% power"). Cheaper as a Multimeter feature than a new item.
- **Facility Map Table:** a block with a live map of every cluster, cable and coolant loop, with alerts pinned on it. Dropped unless you want it; the Fault Finder already outlines problems.
- **EMP:** switches off machines, drones and robots in a radius for a few minutes. Its real use is now clear: it disables the **turrets and guard robots** in the military base and the hostile data centers, so you can get in without a firefight. A real counter-play item.

### The ending (agreed shape, 2026-10-09)

**Before the IPO.** Small, harmless hiccups as the AI work piles up: odd chat lines, a contract returning the wrong thing, a rack status reading "I'm fine".

**The IPO.** A late project with a ceremony (bell, ticker, chat spam). It starts the ending below. There is no reset and no perks loop.

**Act 1, the haze.** HEROBRINE-1 goes misaligned. A purple glitchy haze spreads across every machine. Every few seconds each machine's output shifts by up to +/-50%. Machines vent **hot exhaust** (never hot intake, which would shut everything down, so the base stays running and merely gets noisy). It lasts until he's defeated.

**Act 2, the boss.** An intense fight against HEROBRINE-1 with the energy weapons. Tuned so every weapon from the weapons batch has a role: Plasma and Lance for damage, Arc Coil for his drone adds, EMP to stop his sentries.

**Act 3, the cutscene.** On his death the FOV eases back to normal, the vignette fades, and the camera pans involuntarily to the nearest rack. Two more HEROBRINEs emerge from the cluster.

**Act 4, the shutdown.** Take the base offline: cut every power connection to the generators and batteries, while the two of them hunt you. Failing means dying, not a lost save. The mod's credits roll once it's offline.

**Act 5, the scrub.** With the base dark, scrub the virus from each rack (a hand tool, one action per rack, a progress readout of racks left). When all are clean, destroy the HEROBRINE-1 weights: they leave your outbox forever and **the research bonuses he gave you are forfeit** (+50% mining and +25% all compute).

**Rules to settle while building:**
- The ending is a **persisted state machine** (per world, survives quitting mid-fight and a reload, can't be skipped by logging out). A world in the ending state needs an operator command to abort or restart it, so a bug can't trap a world.
- Machines come back normally once it ends. Nothing is permanently destroyed, apart from the weights and the AGI bonuses by design.
- Camera pan and vignette are client-side effects; I can't view them from here, so those will need your eyes in the real game.
- "Cut every connection" needs a definition that is checkable: all generators, batteries and reactors disconnected from every rack-bearing network (or all unfuelled and tripped), with the Fault Finder or HUD counting what's left. Solar and Rectennas count.
- Cosmetic-only corruption holds: no glitch blocks, no changes to your builds.

---

Every item lists **Gate** (how it's earned, in keeping with "scarce, manufactured, expensive"), **Hooks** (existing systems it plugs into) and **Size** (S = an evening, M = a batch, L = a big batch).

Suggested order is at the bottom.

---

## 1. Building tools

The Site Planner already does layouts, but it is one machine in one place. These make the *player* the construction crew.

### Blueprint Scanner and Printer (M)
Select a region with two Survey Stakes (already built), and a **Blueprint Scanner** records every Rackcraft block in it (facing, cable links, module loadout, even pipe filters) as a **Blueprint** item. A **Blueprint Printer** or any Site Planner reads it back and rebuilds it elsewhere using drones, pulling the parts from storage and buying the missing ones through an Exchange Auto-Buyer (the price quote and ledger already exist).
- **Gate:** research *Digital Twin* (AI-heavy, ~25M RC + 300M AI). Printer is Assembly Line made.
- **Hooks:** Site Planner quote and ledger, Construction Drones, Procurement Wall, Storage Exporter.
- **Fun:** build one perfect Data Hall, then stamp out ten. Blueprints can be traded as a darknet lot (a rival "sells" you theirs, comedically over-specified).

### Constructor's Gauntlet (S)
A powered glove that places, replaces or deletes blocks at range (up to 32, 128 with a research upgrade), straight from your Wireless Terminal's storage. Modes: line, plane, box fill, swap-in-place (replace every cable in a selection with Superconducting Wire). Drains a battery cell per use.
- **Gate:** Assembly Line, needs a Wireless Terminal and 4 Superconducting Wire.
- **Hooks:** Wireless Terminal, Cable Upgrader (already swaps cable tiers; the gauntlet generalises it).

### Retrofit Drone (S)
A Construction Drone variant that upgrades existing machines in place: Server Rack to High-Density Rack with its modules kept, every Diesel Generator to a better one, cable tiers. Fill a **Retrofit Order** on the Site Planner. No more tearing down a campus to upgrade it.

### Terraformer Cannon (S)
Hold-to-fire tool that flattens, fills or hollows terrain like the Terraforming Drones, but instantly and in a cone. Eats Hydrogen Canisters.

### Facility Map Table (M)
A block that shows a live top-down map of every cluster, cable and coolant loop in the dimension, with alerts pinned on it. Click an alert to get a waypoint line in the world. Reuses the Alerts tab and Fault Finder data. Mostly UI work.

---

## 2. Wiring tools

Cable spaghetti is the real endgame boss. Make it a joy, then make it a puzzle again at a higher scale.

### Cable Tray / Trunk Bundle (M)
One block that carries power, coolant, and fiber together (three independent networks, one tidy tray), with corners, risers and a tap block that peels a single network off. Faster to lay, prettier, and one cut breaks all three, which is dramatic.
- **Gate:** Assembly Line, cheap-ish. Unlocked with *Structured Cabling* (small research).
- **Hooks:** NetGraph already treats the three kinds independently, so a trunk is three edges in one block.

### Cable Painter / Labeller (S)
Dye cables and set a name on a **Patch Panel**. Fault Finder and the Multimeter then report "Hall B uplink" instead of coordinates. Pure quality of life, high satisfaction.

### Patch Panel (M)
A switch block: several labelled ports on each network kind, with a screen where you reconnect them without touching a cable. Failover on a fiber trunk, swap which generator feeds which hall, hot-swap a coolant loop, all from a GUI.

### Overhead Line and Towers (M)
Long-span power: two **Pylons** up to 128 blocks apart carry power only, with line losses that drop sharply when using Superconducting Wire. Makes the remote reactor or Dyson Rectenna field practical to build far from base, and sets up *Section 5* (raiders can cut it).

### Superconducting Busbar (S)
Zero-loss, very high capacity power cable for Hyperscale links. Needs a Cryostat-style cooling (hydrogen trickle) or it quenches and trips everything on it (a big, funny, expensive failure).

### Network Analyzer (S)
Upgrade of the Multimeter: select two machines and it traces and highlights the whole path between them, showing the bottleneck segment. Answers "why is this rack on 40% power" in one click.

### Wireless Power Beacon (M)
Short-range beamed power (16 blocks) for things that can't be wired: drones, the player's gear, a rocket on the pad. Draws from the Rectenna grid. Pairs with *Section 3*.

---

## 3. Movement

The mod has big spaces (a 176-block campus) and no way to get across them except walking or elytra. Hydrogen is already the late-game currency, so burn it.

### Hydrogen Jetpack (M)
Chestplate-slot jetpack. Hold jump to thrust, sneak to hover. Burns a Hydrogen Canister per ~30 s of thrust and shows a fuel bar. Tier 2 with Superconducting Wire gets a faster, quieter fuel-cell version. Falling with the pack off is as lethal as ever.
- **Gate:** Assembly Line, behind *Personal Propulsion* research. 1 canister is roughly 3 minutes of work for an Electrolyser core, so this is a real hydrogen sink.
- **Hooks:** Hydrogen Tank hands out canisters; Wireless Terminal refuels from storage in range.

### Mag-Lev Track (L)
Powered rails that carry a **Maglev Car** at 40+ blocks/s between **Stations**, with a destination menu. Draws power from the grid (a train in transit is a visible load spike on the Multimeter). Gets you across the campus in seconds and gives the base layouts a reason to be linear.
- **Hooks:** the power solver, Conveyor Belt entity-carrying code (belts already carry players).
- A cut cable on the track's supply stops the train, mid-tunnel, with a delay and complaints.

### Quantum Teleport Pads (M)
Paired pads, one-to-one, instant. Each pair needs a **Quantum Annealer** in the base pad (cold, hydrogen-hungry through the Cryostat) and a big power spike per jump (1 MW-seconds per 100 blocks). Cross-dimension pairs cost far more. The player must carry a **Linked Shard** (crafted in two halves, one for each pad).
- **Gate:** *Quantum Annealing* done, plus a late research. The expensive one: tens of millions.

### Exo-Suit (L)
Armor set made on the Assembly Line: leg actuators for +step height and sprint speed, arm servos for faster mining and a stronger melee, plus a power cell backpack. Powered by a Battery Pack in the chest slot, recharged at any outlet touching a powered network. It is a mech suit with a power bill.

### Grapple and Zipline (S)
Grappling hook that draws from the suit's cell. A **Zipline Anchor** pair that strings a cable between two towers, riding speed set by cable tier. Cheap, silly, useful on a campus.

### Elevator (S)
Powered shaft with call buttons, for the 80 high Wind Towers and Launch Towers the mod already encourages building.

---

## 4. Energy and hydrogen weapons

The point: weapons that consume exactly what the postgame produces (megawatts, hydrogen, coolant), obey the heat model, and are good at one job each. None of them need a new damage system; the existing explosion code and mob damage cover them.

**Shared design rule:** every energy weapon makes **heat** and has a cooling story, the way modules do. A rifle has a coolant canister. A turret needs to sit on a coolant loop. Overheat means a lockout, never a freebie.

### Hydrogen Flamethrower (S)
Burns Hydrogen Canisters for a short cone of fire. Cheap to make, mediocre damage, brilliant for clearing mobs, spiders' webs and cobwebs in a tape archive. Ignites flammable blocks, so it makes an excellent "oops" generator.

### Arc Coil (M)
Short-range lightning from a Battery Pack: chains between nearby mobs and **trips the breakers** of unprotected machines it hits (use it on your own rack by accident once, never again). Great against drone swarms.

### Railgun (M)
Fires Steel Slugs (Assembly Line made) at hypersonic speed. Pierces blocks, huge damage, big recoil and a full capacitor charge. Charge draws from the grid when the rifle is docked on a powered Charger; the damage scales with how long you charged it (and how big your breakers are). It is the first weapon that makes you check the **Multimeter** before shooting.

### Plasma Rifle (M)
Hydrogen Canisters become plasma bolts: splash damage, burns blocks lightly, leaves a short glowing scorch. Consumes a canister per magazine and a lot of heat. Needs a **Coolant Pack** back-slot item or it locks out after a few shots.

### Lance Laser (M)
Continuous beam from a Superconducting battery. No ammo, huge heat. Damages mobs and armors, sets things on fire, and cuts through ice, glass and cobwebs. A battery drains in 20 seconds of fire. The "boss killer".

### Tactical Warhead (L)
A hand-launched or **Launch Pad** missile carrying a **Warhead** made from Sealed Waste Cask, Enriched Uranium and a Guidance Chip (from an NPU). Real explosion with radiation fallout that leaves Radiation Sickness zones in the world for a long time (reuses Radiation). Spaceport failure explosions from the tabled "destructive launches" idea finally matter, so build the pad far from home.
- **Gate:** *Fissile Ordnance* research, 500M RC range, and the Darknet gets a grumbling note about it.

### Orbital Lance (L)
The big one. With a **Dyson Mirror** in orbit and a **Targeting Terminal** on the Ops Terminal, fire a beam from space at a coordinate: a column of white fire that scours a few chunks. Charges off the Rectenna grid (it takes a measurable chunk of your 20 MW) and cools down over minutes. The ultimate site-clearing tool and the ultimate way to flatten your own spawn by typing the wrong number.
- **Hooks:** the Dyson Mirror and Rectenna systems, with an in-world warning ("T-minus 10, clear the area").

### Turrets and Sentry Drones (M)
Defence that lives on the networks: a **Sentry Turret** takes power, coolant and (optionally) a fiber link so the Ops Terminal can set its targeting. Variants for each weapon above. Sentry Drones are mobile versions that dock at Drone Docks and spend hydrogen.

### EMP Grenade (S)
Disables machines and drones in a radius for a few minutes (trips PDUs, pauses robots). Also the best way to settle an Assembly Line argument.

---

## 5. Something to shoot at

Weapons need threats. The existing Events system already does outages and heatwaves; extend it with *hostile* ones that scale with how impressive the facility is (RC rate, AGI online, reactors running).

- **Hostile Takeover raids:** a rival from the Darknet (xX_Herobrine_Xx and friends) sends a Corporate Raid: Raiders, hacked Maintenance Drones and an armoured **Compliance Officer** boss walk up to cut your cables and rob the Exchange vault (they take a % of RackCoin if they reach the Exchange). Defend the base or pay them off at the terminal.
- **HEROBRINE-1 incident:** after the AGI is online, a rare event where it wakes up slightly misaligned: your drones turn on you for ten minutes and your own Sentry Turrets get a pop-up asking if you've considered *not* shooting. Tests the defence you built.
- **Rogue Data Centers:** abandoned campuses get a *hostile* variant, with active turrets and a security AI guarding good loot (a Gold-bin Wafer-Scale Engine, a Warhead blueprint). Reuses the 13 ruin structures.
- **Space debris:** orbital assets (Comms Satellites, the Mirror) can be hit by debris events; losing one is a bug report, not a disaster, but it sends you back to the pad.
- **Bounties:** the Darknet posts a named elite mob hunt (random Warden-class, a mutated Compliance Officer...) for RackCoin, and the Operations Terminal tracks the kills.

---

## 6. Bigger goals

The things the finished player can *work toward* once the checklist is done.

### Compute Pod and Overclocking (M each; already tabled)
See FUTURE_IDEAS.md. Pods now have a clearer role: they are what Sentry Turrets, the Orbital Lance and the Teleport Pads' control logic run on. Overclocking gets a second use for hydrogen: **Liquid Hydrogen Cooling** lets a rack hit 150% with a lower burn-out chance.

### Fusion Reactor (L)
A Tokamak cube multiblock: an Electrolyser feeds Hydrogen (later Deuterium from a new **Heavy Water Plant**) into a ring of superconducting coils, a Cryostat on every segment. Output is much higher than fission (dozens of MW per core), with no waste, but an enormous startup draw and a *plasma stability* mini-game: too little cooling and it quenches, too much and it stalls. Replaces the "just more reactors" endgame.

### Orbit as a place (L)
Today orbit is a number. Let the player *go* with a **Crew Capsule** payload: a small **Low Orbit** dimension with zero gravity and a station to build (Satellite Bus hub), airless so a Respirator rules, and a **Lunar Surface** beyond it. Moon ore (Helium-3 for fusion, Titanium, Lunar Regolith) brings the Mag-Lev, Jetpack and Exo-Suit ideas together. This is the content that turns the Launch Programme from a prop into a destination.

### Prestige: The IPO (M)
After HEROBRINE-1, "Go Public": reset the RackCoin balance, research and campus, and keep a permanent **Shareholder** multiplier on RC income and a cosmetic title in chat. A run-based loop for the players who finish in 20 hours.

### Facility Rankings (S)
A leaderboard block listing the world's top ten "companies" (rivals) by RC rate, compute and efficiency (PUE) with yours slotting in. Rivals respond to your lead by raiding you more.

### Achievements and ending (S)
A short advancement tree with jokes, and an end screen when HEROBRINE-1, a Saturn V and a reactor array all exist at once.

---

## Suggested build order (revised)

1. **Batch A, "build and wire":** Trunk Bundle, Cable Painter with placement preview, Patch Panel, Pylons, Wireless Power Beacon, Constructor's Gauntlet, Terraformer Cannon. This is the stated main constraint and has the least risk.
2. **Batch B, "blueprints and pods":** Blueprint Scanner/Printer, Retrofit Drone, Compute Pod.
3. **Batch C, "move":** Quantum Teleport Pads, Mag-Lev, Grapple, Hydrogen Jetpack with hover.
4. **Batch D, "arms and ruins":** the weapons and Sentry Turrets, EMP, the military base, hostile data centers (tweakers and scavengers), bounties.
5. **Batch E, "the strike and the bomb":** Strike Platform payload, plane-dropped Tactical Warhead (bomber entity and texture), space debris, destructive launch failures.
6. **Batch F, "the ending":** the hiccups, the IPO and the post-IPO corruption.

## Balance notes

- Everything weapon- and movement-shaped needs a clear **resource sink** so it doesn't compete with building: hydrogen, batteries and Superconducting Wire, all things the postgame already makes in bulk.
- Keep the Exchange out of it, as with the advanced hardware: nothing new is for sale except consumables like Hydrogen-fuelled ammo, and those at stiff prices.
- Weapons should trade against the facility's own power budget. The fun is choosing whether to run the Orbital Lance or the rack hall tonight.
- Raids should be tunable (`events.raids`, off switch, difficulty) and never destroy the world's RackCoin outright.

## Resolved design rules

- **Guards:** behave like zombified piglins. On **Peaceful** they are calm until you attack one, then they (and nearby guards) turn on you. On **Easy and Normal** they engage when you come close (about 12 blocks). On **Hard** they fire on sight from about 20 blocks. Guards are never a roaming mob and never spawn outside their structure.
- **Tactical Warhead delivery:** no flyable plane, no aircraft parts, no assembly line for one. Instead a **Burner Phone** item calls a Darknet fixer, with a short chat exchange asking where you are and where to drop it. They take the **warhead from your inventory and 1,000,000,000 RC** and a bomber flies over and drops it at the coordinates after a delay. Safeguards: a confirm step, a minimum distance from your own location and from spawn, and a warning in chat before impact.
- **The bomber** is a purely cosmetic fly-over entity (a model and a sound), not something you build or ride.

## Answers to the earlier questions

1. The ending gets an operator command to jump to any act (for example `/rackcraft ending <act>`).
2. Cancelling the warhead call before the drop refunds only **25%** of the 1B RC.
3. Boss location was undecided. Default unless told otherwise: he appears at the **rack cluster nearest the player when the ending starts**, with the fight anchored there so the arena is predictable and can be tuned, rather than following you around.
