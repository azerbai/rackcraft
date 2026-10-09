# Postgame batches: shared conventions

Read this first, then the batch file you were given (`batch-A-build-and-wire.md` ... `batch-F-the-ending.md`). The batches are independent in code, except where a batch says "needs Batch X". The design background is in `/POSTGAME_IDEAS.md` at the repo root (its "Decisions" section wins over the older brainstorm below it).

## What Rackcraft is

A Fabric mod for Minecraft 1.20.1 (Java 17 source, Yarn mappings) about data centers: power, cooling and compute simulated server-side, RackCoin (RC) mined by racks, an Exchange, a Darknet auction house, AI contracts, research, nuclear/hydrogen industry, drones, an Assembly Line and a Launch Programme. `README.md` documents every system; read the sections relevant to your batch. The tone of all mod content is chill and comedic (shackled librarians, crayon-drawing kids). Item tooltips and Field Manual text should be funny but accurate.

## Layout

- `src/main/java/dev/rackcraft/...` common/server code. `block/` blocks and block entities, `world/` the systems (NetworkManager, Cryostats, SitePlanner, AssemblyLine, LaunchPads...), `sim/` the pure simulation (NetGraph, PowerSolver, ThermalGrid), `compute/` contracts, research (`Research.java`, `ResearchLab.java`), `darknet/`, `entity/`, `item/`, `screen/` handlers, `storage/`.
- `src/client/java/dev/rackcraft/client/...` client-only: HUD overlays, renderers (`render/`), screens (`screen/`), `ClientNet.java` for packets.
- `tools/content.json` is the content source: blocks, items, recipes, Field Manual chapters, lang strings. `python3 tools/gen_assets.py` regenerates models, blockstates, textures, lang and `src/main/java/dev/rackcraft/generated/ContentIds.java`. **Do not hand-edit generated files.** Keep `content.json` in its compact one-entry-per-line format. `python3 tools/check_assets.py` validates. `tools/textures.py` draws textures procedurally; there is no image editor, so new textures are code. `tools/sounds.py` synthesises sounds (needs numpy and soundfile in a scratch venv).
- Items and blocks are registered by id in `RcItems.java` / `RcBlocks.java` (a `switch` on the id picks the class; unknown ids become plain items/blocks). Machines with a block entity are listed as `"machine": true` in content.json (see `MachineBlock`, `MachineBlockEntity`). Cables are `CableBlock` per `NetKind` (POWER, COOLANT, DATA, ITEM).
- `NetworkManager.register(pos, kinds)` is how a block joins power/coolant/data/item graphs; `component(pos, kind)` gives its connected set. A new transport block must integrate with this, not create a parallel graph.
- Networking: `RackcraftNetworking.java` (server receivers, `Identifier`s like `Rackcraft.id("buy_item")`) and `client/ClientNet.java`.
- Config: `RackcraftConfig.java` (`config/rackcraft.json`). New tunable numbers go there.
- Recipes: the content.json `recipes` list (shaped/shapeless) and the Assembly Line recipes in `world/AssemblyLine.java`. Research projects: `compute/Research.java` (id, name, kind, RC credits, work, requires, effect text).
- The Field Manual is generated from content.json chapters + recipes; every new block/item needs a `desc`, and a chapter `entries` mention.
- Exchange: `ExchangeCatalog.java`/`ExchangeOffers.java`. Late-game hardware is **excluded** from the Exchange by design; check how "advanced hardware" items are excluded and do the same for anything marked scarce.

## Hard rules from the owner

1. **Scarce and expensive at the top end.** Late-game items are Assembly-Line-made behind research, never sold on the Exchange (unless a batch says so), and cost real resources (Hydrogen Canisters, Superconducting Wire, Wafer-Scale chiplets, batteries). If unsure, make it more expensive and say so in the final report.
2. **No server-load regressions.** A previous batch had a "big-facility slowdown" fix. Anything that scans per tick must be throttled, cached or event-driven; consult `SimTicker.java` before adding a per-tick scan.
3. **Persistent per-world state** (anything saved) must be reset by the self-test, because the dev world persists between runs (`ResearchLab.reset()`, `DarknetMarket.reset()` are the examples). World time does not advance during the self-test, so time-gated logic needs a direct hook the test can call.
4. **Structure code must work in real worldgen**, where block entities have no world yet (a sign's `setText` crashed once). Test by actually generating the structure, as the D6 self-test checks do.
5. **The player mostly plays Peaceful.** Nothing may force combat on them outside the dedicated structures (Batch D). Guard NPCs follow zombified-piglin-style rules by difficulty (see Batch D).
6. **Never destroy the player's builds** or RackCoin balance as a gameplay effect. Failure modes trip, stall or shut down; they do not delete.
7. **Fail safe:** the owner leaves the game open. Nothing may cascade into a base-wide failure while they are away.
8. Keep new text, tooltips and chat messages in the mod's chill, comedic tone.

## Build and verify (important)

- Gradle/Loom needs **JDK 25** to run; the code compiles to Java 17 via a toolchain. On the owner's machine: `export JAVA_HOME="$HOME/.jdks/temurin25/Contents/Home"; export PATH="$JAVA_HOME/bin:$PATH"`. See `DEVIATIONS.md`.
- Regenerate assets: `python3 tools/gen_assets.py && python3 tools/check_assets.py`.
- **The real check is `./gradlew runServer`**: it runs `RackcraftSelfTest` (lines tagged `RACKCRAFT_SELFTEST` in the log; 197 checks as of the last batch, 0 failures) and stops itself. Add checks to `RackcraftSelfTest.java` for every new system, then run it. Also grep the log for `Simulation step failed` (SimTicker swallows exceptions and logs one per 1200 ticks; check `S0.b`, `SimTicker.failedSteps() == 0`).
- Also run `./gradlew test` (unit tests in `src/test`, which enforce that `sim/` doesn't import Minecraft classes) and finally **`./gradlew build`** so `build/libs/rackcraft-1.0.0.jar` is fresh. The owner plays from that jar.
- `./gradlew runClient` does **not** work in the agent environment (no network to Mojang's resources), and screenshots aren't permitted. Client code, screens, renderers and HUDs **cannot be seen**. Write them defensively, keep them simple, reuse existing rendering helpers (`client/render/Boxes.java`, `SurveyOutline`, `FaultOverlay`), and clearly say in the final report which parts are unverified visually so the owner can check in-game.
- To eyeball generated textures, decode the PNGs in a scratch script and read the upscaled result (no PIL available).
- Use the session's scratchpad directory for temporary files, not `/tmp`.

## Process

- Work on the current branch (`1.20.1`). **Commit only when asked**; "commit" means commit, "push" is separate. Commit messages end with the attribution line the harness provides.
- Update `README.md` (a section per new system, in the established voice), the Field Manual content, and `FUTURE_IDEAS.md` / `POSTGAME_IDEAS.md` (mark what is built). Add a line to the auto-memory note `future-ideas.md` if you have memory access.
- Report at the end: what was built, self-test count (X checks, 0 failures), what could not be verified (client visuals), and balance numbers you invented so the owner can tune them.
- When something is under-specified, choose the conservative option, document it in the batch's README section, and list it in the report. Don't stop to ask unless a decision would change what is built.
