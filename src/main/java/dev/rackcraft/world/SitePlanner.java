package dev.rackcraft.world;

import dev.rackcraft.RackcraftConfig;
import dev.rackcraft.RcBlocks;
import dev.rackcraft.RcItems;
import dev.rackcraft.block.CableBlock;
import dev.rackcraft.block.MachineBlock;
import dev.rackcraft.block.MachineBlockEntity;
import dev.rackcraft.block.SolarArrayBlock;
import dev.rackcraft.entity.ConstructionDroneEntity;
import dev.rackcraft.sim.NetKind;
import dev.rackcraft.storage.ItemKey;
import dev.rackcraft.storage.StorageNetwork;
import dev.rackcraft.storage.StorageService;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.FluidBlock;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtCompound;
import dev.rackcraft.block.Racks;
import dev.rackcraft.sim.ServerModel;
import net.minecraft.registry.Registries;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.ItemScatterer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.world.EmptyBlockView;
import net.minecraft.world.Heightmap;

/**
 * The Site Planner: it turns a marked-out area into a working solar field or wind farm, using drones and what is in
 * its slots and its storage. Mark two corners with a Survey Stake and use the stake on the planner, pick a layout, and
 * press Start. The work goes in four phases, always in this order, and the planner works out which one it is in from
 * the world itself every couple of seconds:
 *
 * <ol>
 *   <li><b>Clear</b>: Construction Drones take away everything soft on the site (grass, flowers, snow, leaves, whole
 *       trees) before any other work starts; what they take goes into storage.</li>
 *   <li><b>Level</b>: Terraforming Drones even the ground out to its median height, digging high spots and filling low
 *       ones, {@code terraformBlocks} blocks a trip. Spare earth goes into storage, and when cuts run out they fill from
 *       it. Once the ground is even they come home and stay docked.</li>
 *   <li><b>Build</b>: Construction Drones place the layout: Solar Arrays packed edge to edge (so they share power with
 *       no cables), or Wind Towers on a grid with room for the blades, tall enough to put the nacelles at Y 130.</li>
 *   <li><b>Wire</b>: they lay Power Cable between the towers and from the site back to the planner, which is on the
 *       grid, so the site joins the planner's network.</li>
 * </ol>
 * Slots: 0 Construction Drones, 1 Terraforming Drones, 2 Hydrogen Canisters, 3 to 8 materials (arrays, tower parts,
 * cable, fill). Everything also comes from storage if the planner is on an Item Pipe that reaches some.
 */
public final class SitePlanner {
	public static final double PLANNER_KW = 5;
	public static final int DRONE_SLOT = 0;
	public static final int TERRAFORMER_SLOT = 1;
	public static final int FUEL_SLOT = 2;
	public static final int FIRST_MATERIAL = 3;
	public static final int CLEAR_PER_TRIP = 16;
	public static final int CABLE_PER_TRIP = 24;
	private static final int SCAN_TICKS = 40;
	/** How far apart Wind Towers stand, and how far in from the edge: the blades sweep two blocks either side. */
	public static final int TOWER_SPACING = 6;
	public static final int TOWER_MARGIN = 2;

	public enum Layout {
		SOLAR("Solar Field", "solar_array"),
		TRACKING("Tracking Solar Field", "solar_array_tracking"),
		WIND("Wind Farm", "wind_nacelle"),
		HALL("Data Hall", "server_rack");

		public final String label;
		public final String block;

		Layout(String label, String block) {
			this.label = label;
			this.block = block;
		}
	}

	public enum Phase { NONE, CLEAR, LEVEL, BUILD, WIRE, DOCK, DONE }

	/** What the planner is doing, or why it isn't. */
	public enum Status { NO_AREA, NOT_LOADED, PAUSED, NO_POWER, WORKING, NO_DRONES, NO_TERRAFORMERS, NO_FUEL, NEEDS_MATERIALS, BLOCKED, DONE }

	// Readings for the planner's screen (MachineBlockEntity.siteReading).
	public static final int R_PHASE = 0;
	public static final int R_WIDTH = 1;
	public static final int R_DEPTH = 2;
	public static final int R_LAYOUT = 3;
	public static final int R_LEFT = 4;
	public static final int R_TOTAL = 5;
	public static final int R_BUILT = 6;
	public static final int R_NEED_ITEM = 7;
	public static final int R_NEED_COUNT = 8;
	public static final int R_BLOCKED_X = 9;
	public static final int R_BLOCKED_Y = 10;
	public static final int R_BLOCKED_Z = 11;
	public static final int R_LEVEL = 12;
	public static final int R_TERRAFORMERS_OUT = 13;
	public static final int R_RUNNING = 14;
	public static final int R_HAS_BLOCKED = 15;
	public static final int R_BUYING = 16;
	public static final int R_SPENT = 17;
	public static final int R_DOCKING = 18;
	public static final int R_DOCKS = 19;
	public static final int R_DOCKS_PLACED = 20;
	public static final int R_DRAW_KW = 21;
	public static final int R_HALL_RACK = 22;
	public static final int R_HALL_MODULE = 23;
	public static final int READINGS = 24;
	/** What the planner stocks each of its docks to, and when it tops them up. */
	public static final int DOCK_DRONES = 8;
	public static final int DOCK_FUEL = 16;
	public static final int DOCK_FUEL_LOW = 8;
	/** At most this many docks for one site; two cover the biggest. */
	private static final int MAX_DOCKS = 6;

	/** What a drone does at one stop. */
	public enum Action { CLEAR, DIG, FILL, PLACE, ARRAY, CABLE, SUPPLY }

	/** One stop: where, what to do, and for a placement which block and which way it faces. */
	public record Step(BlockPos pos, Action action, String block, Direction facing) {
		public Step(BlockPos pos, Action action, String block) {
			this(pos, action, block, Direction.NORTH);
		}

		public NbtCompound write() {
			NbtCompound nbt = new NbtCompound();
			nbt.putLong("Pos", pos.asLong());
			nbt.putInt("Action", action.ordinal());
			if (block != null) nbt.putString("Block", block);
			nbt.putInt("Facing", facing.getHorizontal());
			return nbt;
		}

		public static Step read(NbtCompound nbt) {
			Action[] actions = Action.values();
			return new Step(BlockPos.fromLong(nbt.getLong("Pos")), actions[Math.max(0, Math.min(actions.length - 1, nbt.getInt("Action")))],
					nbt.contains("Block") ? nbt.getString("Block") : null,
					nbt.contains("Facing") ? Direction.fromHorizontal(nbt.getInt("Facing")) : Direction.NORTH);
		}
	}

	/** The marked area, corners inclusive. */
	public record Site(int x0, int z0, int x1, int z1) {
		public int width() { return x1 - x0 + 1; }
		public int depth() { return z1 - z0 + 1; }
		public boolean contains(int x, int z) { return x >= x0 && x <= x1 && z >= z0 && z <= z1; }
	}

	/** One thing to build: its pieces, bottom up. An array is one piece (its part 0) that places all six parts. */
	private record Structure(List<Piece> pieces) {
		BlockPos base() { return pieces.get(0).pos(); }
	}

	/** One block to place, the way it faces, and for a rack the module to fill all eight bays with (else null). */
	private record Piece(BlockPos pos, String block, Direction facing, String module) {
		Piece(BlockPos pos, String block) {
			this(pos, block, Direction.NORTH, null);
		}
	}

	/** What fills a Data Hall's racks unless the planner is set otherwise: the best module for mining per kilowatt. */
	public static final String HALL_MODULE = "quantum_core";
	/** What one Chiller moves, less a margin: a Data Hall column gets a Chiller for every this many kW its racks can make. */
	private static final double HALL_KW_PER_CHILLER = 220;
	/** Racks per Data Hall column, and what one column draws when full, roughly: four racks plus its chillers. */
	public static final int HALL_RACKS_PER_COLUMN = 4;

	/** One column of the site: where its ground is, and the soft blocks above it. */
	private record Survey(Site site, int[][] ground, List<BlockPos> soft) {
		int groundAt(int x, int z) { return ground[x - site.x0()][z - site.z0()]; }
	}

	private SitePlanner() {}

	private static RackcraftConfig.Construction config() {
		return RackcraftConfig.values.construction;
	}

	public static boolean accepts(int slot, ItemStack stack) {
		if (slot == DRONE_SLOT) return stack.isOf(RcItems.ITEMS.get("construction_drone"));
		if (slot == TERRAFORMER_SLOT) return stack.isOf(RcItems.ITEMS.get("terraforming_drone"));
		if (slot == FUEL_SLOT) return stack.isOf(RcItems.ITEMS.get("hydrogen_canister"));
		return slot < 9;
	}

	// ---------------------------------------------------------------- the site and the player's choices

	public static Site site(MachineBlockEntity planner) {
		NbtCompound data = planner.site();
		if (!data.getBoolean("HasArea")) return null;
		return new Site(data.getInt("X0"), data.getInt("Z0"), data.getInt("X1"), data.getInt("Z1"));
	}

	public static Layout layout(MachineBlockEntity planner) {
		Layout[] layouts = Layout.values();
		return layouts[Math.max(0, Math.min(layouts.length - 1, planner.site().getInt("Layout")))];
	}

	public static boolean running(MachineBlockEntity planner) {
		return planner.site().getBoolean("Running");
	}

	/** Takes a Survey Stake's corners as the site. Returns what to tell the player. */
	public static String setArea(MachineBlockEntity planner, BlockPos a, BlockPos b) {
		Site site = new Site(Math.min(a.getX(), b.getX()), Math.min(a.getZ(), b.getZ()), Math.max(a.getX(), b.getX()), Math.max(a.getZ(), b.getZ()));
		int max = config().maxSide;
		if (site.width() > max || site.depth() > max) return "Too big: a site can be up to " + max + " x " + max;
		if (site.width() < 3 || site.depth() < 2) return "Too small: a site needs to be at least 3 x 2";
		BlockPos pos = planner.getPos();
		if (site.contains(pos.getX(), pos.getZ())) return "The planner can't stand inside its own site";
		int dx = Math.max(0, Math.max(site.x0() - pos.getX(), pos.getX() - site.x1()));
		int dz = Math.max(0, Math.max(site.z0() - pos.getZ(), pos.getZ() - site.z1()));
		if (dx + dz > config().maxDistance) return "Too far: the site must be within " + config().maxDistance + " blocks of the planner";
		NbtCompound data = planner.site();
		data.putBoolean("HasArea", true);
		data.putInt("X0", site.x0());
		data.putInt("Z0", site.z0());
		data.putInt("X1", site.x1());
		data.putInt("Z1", site.z1());
		data.putBoolean("Running", false);
		data.remove("Level");
		data.remove("DockAt");
		planner.markDirty();
		return "Site set: " + site.width() + " x " + site.depth() + ". Pick a layout and press Start.";
	}

	public static String cycleLayout(MachineBlockEntity planner) {
		if (running(planner)) return "Pause the planner before changing the layout";
		Layout next = Layout.values()[(layout(planner).ordinal() + 1) % Layout.values().length];
		planner.site().putInt("Layout", next.ordinal());
		planner.site().remove("DockAt");
		planner.markDirty();
		return "Layout: " + next.label;
	}

	/** A Data Hall blueprint: which rack tier the hall is built of, and which module fills every bay. */
	public record Hall(ServerModel.Tier tier, String module) {
		/** What one rack draws flat out, in kW. */
		public double rackKw() {
			ServerModel.Module kind = ServerModel.Module.valueOf(moduleName());
			return tier.bays() * kind.maxKw() + tier.overheadKw();
		}

		private String moduleName() {
			for (ServerModel.Module kind : ServerModel.Module.values()) if (kind.itemId().equals(module)) return kind.name();
			return ServerModel.Module.QUANTUM_CORE.name();
		}

		/** Chillers over each column's CDUs: at least two, more as the racks get hotter. */
		public int chillers() {
			return Math.max(2, (int) Math.ceil(HALL_RACKS_PER_COLUMN * rackKw() / HALL_KW_PER_CHILLER));
		}
	}

	/** The planner's Data Hall blueprint; the Server Rack of Quantum Cores unless changed (or if what it holds no longer fits). */
	public static Hall hall(MachineBlockEntity planner) {
		ServerModel.Tier tier = ServerModel.Tier.of(planner.site().getString("HallRack"));
		if (tier == null) tier = ServerModel.Tier.SERVER;
		String module = planner.site().getString("HallModule");
		if (!Racks.hallChoices(tier).contains(module)) module = Racks.hallChoices(tier).contains(HALL_MODULE) ? HALL_MODULE : Racks.hallChoices(tier).get(0);
		return new Hall(tier, module);
	}

	public static String cycleHallRack(MachineBlockEntity planner) {
		if (running(planner)) return "Pause the planner before changing the blueprint";
		ServerModel.Tier[] tiers = ServerModel.Tier.values();
		ServerModel.Tier next = tiers[(hall(planner).tier().ordinal() + 1) % tiers.length];
		planner.site().putString("HallRack", next.blockId());
		planner.markDirty();
		Hall hall = hall(planner);
		return "Data Hall racks: " + new ItemStack(RcBlocks.get(next.blockId()).asItem()).getName().getString() + " of "
				+ new ItemStack(RcItems.ITEMS.get(hall.module())).getName().getString() + ", " + hall.chillers() + " Chillers a column";
	}

	public static String cycleHallModule(MachineBlockEntity planner) {
		if (running(planner)) return "Pause the planner before changing the blueprint";
		Hall hall = hall(planner);
		List<String> choices = Racks.hallChoices(hall.tier());
		String next = choices.get((choices.indexOf(hall.module()) + 1) % choices.size());
		planner.site().putString("HallModule", next);
		planner.markDirty();
		return "Data Hall modules: " + new ItemStack(RcItems.ITEMS.get(next)).getName().getString() + ", " + hall(planner).chillers() + " Chillers a column";
	}

	public static boolean buying(MachineBlockEntity planner) {
		return planner.site().getBoolean("Buy");
	}

	/** Turns buying on or off: whatever the slots and storage can't supply, the planner buys at Exchange prices. */
	public static String toggleBuying(MachineBlockEntity planner) {
		boolean buy = !buying(planner);
		planner.site().putBoolean("Buy", buy);
		planner.markDirty();
		return buy ? "Buying from the Crypto Exchange: anything it sells that the site needs is paid for in RackCoin"
				: "No longer buying from the Exchange";
	}

	public static boolean docking(MachineBlockEntity planner) {
		return planner.site().getBoolean("Docks");
	}

	/**
	 * Turns docking on or off: once the site is built and wired, the planner places Drone Docks so every array or tower
	 * is in a dock's reach (buying the docks at the Exchange if it has none), stocks them with Maintenance Drones and
	 * hydrogen from storage, and keeps them topped up.
	 */
	public static String toggleDocks(MachineBlockEntity planner) {
		boolean docks = !docking(planner);
		planner.site().putBoolean("Docks", docks);
		planner.markDirty();
		return docks ? "Docks: the planner will place Drone Docks to cover the site and keep them stocked"
				: "Docks: the planner won't place or stock docks";
	}

	public static String toggleRunning(MachineBlockEntity planner) {
		if (site(planner) == null) return "Mark a site first: two corners with a Survey Stake, then use the stake on the planner";
		boolean running = !running(planner);
		planner.site().putBoolean("Running", running);
		planner.markDirty();
		return running ? "Site work started" : "Paused: drones out will finish their trips";
	}

	// ---------------------------------------------------------------- the scan

	public static void step(ServerWorld world, List<MachineBlockEntity> machines, Map<MachineBlockEntity, Double> satisfaction) {
		if (world.getTime() % SCAN_TICKS >= Math.max(1, RackcraftConfig.values.sim.stepTicks)) return;
		for (MachineBlockEntity machine : machines) {
			if (machine.blockId().equals("site_planner")) plan(world, machine, satisfaction.getOrDefault(machine, 0.0));
		}
	}

	/** Plans and dispatches for every planner now, whatever the time; the self-test calls it directly. */
	public static void scanNow(ServerWorld world) {
		for (MachineBlockEntity machine : SimTicker.machines(world)) {
			if (machine.blockId().equals("site_planner")) plan(world, machine, machine.powerSatisfaction());
		}
	}

	/** Drones in flight that belong to this planner. */
	public static List<ConstructionDroneEntity> drones(ServerWorld world, BlockPos planner) {
		int reach = config().maxDistance + config().maxSide + 16;
		return world.getEntitiesByClass(ConstructionDroneEntity.class, new Box(planner).expand(reach, 320, reach),
				drone -> drone.isAlive() && drone.home().equals(planner));
	}

	private static void plan(ServerWorld world, MachineBlockEntity planner, double power) {
		for (int index = 0; index < READINGS; index++) planner.setSiteReading(index, 0);
		Layout layout = layout(planner);
		boolean running = running(planner);
		planner.setSiteReading(R_LAYOUT, layout.ordinal());
		planner.setSiteReading(R_RUNNING, running ? 1 : 0);
		planner.setSiteReading(R_BUYING, buying(planner) ? 1 : 0);
		planner.setSiteReading(R_DOCKING, docking(planner) ? 1 : 0);
		planner.setSiteReading(R_SPENT, (int) Math.min(Integer.MAX_VALUE, planner.site().getLong("Spent")));
		List<ConstructionDroneEntity> out = drones(world, planner.getPos());
		int builders = (int) out.stream().filter(drone -> !drone.terraformer()).count();
		int terraformers = out.size() - builders;
		planner.setWorkers(builders);
		planner.setSiteReading(R_TERRAFORMERS_OUT, terraformers);
		Site site = site(planner);
		if (site == null) {
			report(planner, Phase.NONE, Status.NO_AREA, !out.isEmpty());
			return;
		}
		planner.setSiteReading(R_WIDTH, site.width());
		planner.setSiteReading(R_DEPTH, site.depth());
		if (!loaded(world, site)) {
			report(planner, Phase.NONE, Status.NOT_LOADED, !out.isEmpty());
			return;
		}
		Set<BlockPos> claimed = new HashSet<>();
		Set<Long> columns = new HashSet<>();
		for (ConstructionDroneEntity drone : out) {
			for (Step step : drone.steps()) {
				claimed.add(step.pos());
				columns.add(column(step.pos()));
			}
		}
		Survey survey = survey(world, site);
		boolean act = running && power >= 0.5;
		StorageNetwork storage = storage(world, planner);
		Phase phase;
		Status status;
		if (!survey.soft().isEmpty()) {
			phase = Phase.CLEAR;
			planner.setSiteReading(R_LEFT, survey.soft().size());
			status = act ? dispatch(world, planner, storage, false, builders,
					() -> clearTrip(survey, planner.getPos(), claimed)) : Status.WORKING;
		} else {
			int level = level(planner, survey);
			planner.setSiteReading(R_LEVEL, level);
			Levelling levelling = levelling(world, survey, level);
			if (levelling.blocked() != null) blocked(planner, levelling.blocked());
			Hall hall = hall(planner);
			planner.setSiteReading(R_HALL_RACK, hall.tier().ordinal());
			planner.setSiteReading(R_HALL_MODULE, Registries.ITEM.getRawId(RcItems.ITEMS.get(hall.module())));
			List<Structure> structures = structures(world, site, layout, level, hall);
			planner.setSiteReading(R_TOTAL, structures.size());
			planner.setSiteReading(R_DRAW_KW, (int) Math.min(Integer.MAX_VALUE, Math.round(fullDrawKw(structures, hall))));
			if (levelling.left() > 0 || terraformers > 0) {
				phase = Phase.LEVEL;
				planner.setSiteReading(R_LEFT, levelling.left());
				status = act ? dispatch(world, planner, storage, true, terraformers,
						() -> levelTrip(world, planner, storage, survey, levelling, level, columns)) : Status.WORKING;
			} else {
				List<Structure> unbuilt = structures.stream().filter(structure -> !built(world, structure)).toList();
				planner.setSiteReading(R_BUILT, structures.size() - unbuilt.size());
				if (!unbuilt.isEmpty()) {
					phase = Phase.BUILD;
					planner.setSiteReading(R_LEFT, unbuilt.size());
					status = act ? dispatch(world, planner, storage, false, builders,
							() -> buildTrip(world, planner, storage, unbuilt, claimed, layout == Layout.HALL)) : Status.WORKING;
				} else {
					List<BlockPos> cables = cables(world, planner.getPos(), site, layout, structures, level);
					List<BlockPos> missing = cables.stream().filter(pos -> !world.getBlockState(pos).isOf(RcBlocks.get("power_cable"))).toList();
					if (!missing.isEmpty()) {
						phase = Phase.WIRE;
						planner.setSiteReading(R_LEFT, missing.size());
						status = act ? dispatch(world, planner, storage, false, builders,
								() -> wireTrip(world, planner, storage, missing, claimed, layout == Layout.HALL)) : Status.WORKING;
					} else {
						List<BlockPos> docks = docking(planner) ? dockSpots(world, planner, site, layout, structures, cables, level) : List.of();
						List<BlockPos> unplaced = docks.stream().filter(pos -> !world.getBlockState(pos).isOf(RcBlocks.get("drone_dock"))).toList();
						planner.setSiteReading(R_DOCKS, docks.size());
						planner.setSiteReading(R_DOCKS_PLACED, docks.size() - unplaced.size());
						if (!unplaced.isEmpty()) {
							phase = Phase.DOCK;
							planner.setSiteReading(R_LEFT, unplaced.size());
							status = act ? dispatch(world, planner, storage, false, builders,
									() -> dockTrip(world, planner, storage, unplaced, claimed)) : Status.WORKING;
						} else {
							phase = Phase.DONE;
							status = Status.DONE;
							// Keep the site's docks in drones and hydrogen.
							if (act && !docks.isEmpty()) dispatch(world, planner, storage, false, builders, () -> supplyTrip(world, planner, storage, docks, claimed));
						}
					}
				}
			}
		}
		if (phase != Phase.DONE) {
			if (!running) status = Status.PAUSED;
			else if (power < 0.5) status = Status.NO_POWER;
		}
		report(planner, phase, status, !out.isEmpty());
	}

	private static void report(MachineBlockEntity planner, Phase phase, Status status, boolean dronesOut) {
		planner.setSiteReading(R_PHASE, phase.ordinal());
		planner.setProcess(status.ordinal(), dronesOut);
	}

	private static void blocked(MachineBlockEntity planner, BlockPos pos) {
		if (planner.siteReading(R_HAS_BLOCKED) != 0) return;
		planner.setSiteReading(R_HAS_BLOCKED, 1);
		planner.setSiteReading(R_BLOCKED_X, pos.getX());
		planner.setSiteReading(R_BLOCKED_Y, pos.getY());
		planner.setSiteReading(R_BLOCKED_Z, pos.getZ());
	}

	private static void needs(MachineBlockEntity planner, Item item, long count) {
		planner.setSiteReading(R_NEED_ITEM, Registries.ITEM.getRawId(item) + 1);
		planner.setSiteReading(R_NEED_COUNT, (int) Math.min(Integer.MAX_VALUE, Math.max(1, count)));
	}

	private static boolean loaded(ServerWorld world, Site site) {
		for (int cx = site.x0() >> 4; cx <= site.x1() >> 4; cx++) {
			for (int cz = site.z0() >> 4; cz <= site.z1() >> 4; cz++) if (!world.getChunkManager().isChunkLoaded(cx, cz)) return false;
		}
		return true;
	}

	private static long column(BlockPos pos) {
		return BlockPos.asLong(pos.getX(), 0, pos.getZ());
	}

	// ---------------------------------------------------------------- what's on the site

	/** What a block is to the planner: room, something soft to clear, our own machinery, or ground. */
	private enum Cell { ROOM, SOFT, OURS, GROUND }

	private static Cell classify(ServerWorld world, BlockPos pos, BlockState state) {
		if (state.isAir() || state.getBlock() instanceof FluidBlock) return Cell.ROOM;
		if (Registries.BLOCK.getId(state.getBlock()).getNamespace().equals(dev.rackcraft.Rackcraft.MOD_ID)) return Cell.OURS;
		return soft(world, pos, state) ? Cell.SOFT : Cell.GROUND;
	}

	/** Grass, flowers, snow, leaves and trees: what drones clear before work starts. */
	public static boolean soft(ServerWorld world, BlockPos pos, BlockState state) {
		// Bee nests come down with their trees. The bees take it personally.
		if (state.isOf(Blocks.BEE_NEST)) return true;
		if (state.hasBlockEntity()) return false;
		if (state.isIn(BlockTags.LEAVES) || state.isIn(BlockTags.LOGS) || state.isIn(BlockTags.FLOWERS) || state.isIn(BlockTags.SAPLINGS)
				|| state.isIn(BlockTags.REPLACEABLE_BY_TREES)) return true;
		if (state.isOf(Blocks.SNOW) || state.isOf(Blocks.CACTUS) || state.isOf(Blocks.SUGAR_CANE) || state.isOf(Blocks.BAMBOO)
				|| state.isOf(Blocks.PUMPKIN) || state.isOf(Blocks.MELON) || state.isOf(Blocks.VINE) || state.isOf(Blocks.COCOA)
				|| state.isOf(Blocks.BROWN_MUSHROOM_BLOCK) || state.isOf(Blocks.RED_MUSHROOM_BLOCK) || state.isOf(Blocks.MUSHROOM_STEM)
				|| state.isOf(Blocks.SWEET_BERRY_BUSH) || state.isOf(Blocks.MOSS_CARPET)) return true;
		float hardness = state.getHardness(world, pos);
		return hardness >= 0 && hardness < 0.5f && state.getCollisionShape(world, pos).isEmpty();
	}

	/** Ground a Terraforming Drone may move: no block entities, nothing unbreakable or obsidian-hard. */
	private static boolean diggable(ServerWorld world, BlockPos pos, BlockState state) {
		if (state.hasBlockEntity()) return false;
		float hardness = state.getHardness(world, pos);
		return hardness >= 0 && hardness < 50;
	}

	/** Earth and rock nobody built, which a cable run outside the site may dig through. */
	private static boolean natural(BlockState state) {
		return state.isIn(BlockTags.DIRT) || state.isIn(BlockTags.BASE_STONE_OVERWORLD) || state.isIn(BlockTags.BASE_STONE_NETHER)
				|| state.isIn(BlockTags.SAND) || state.isIn(BlockTags.TERRACOTTA) || state.isOf(Blocks.GRAVEL) || state.isOf(Blocks.CLAY)
				|| state.isOf(Blocks.SNOW_BLOCK) || state.isOf(Blocks.GRASS_BLOCK) || state.isOf(Blocks.SANDSTONE);
	}

	/** The top of the ground in a column, looking through air, water, plants and our own machinery. */
	private static int ground(ServerWorld world, int x, int z, List<BlockPos> soft) {
		BlockPos.Mutable pos = new BlockPos.Mutable(x, world.getTopY(Heightmap.Type.WORLD_SURFACE, x, z) - 1, z);
		for (; pos.getY() >= world.getBottomY(); pos.move(Direction.DOWN)) {
			BlockState state = world.getBlockState(pos);
			Cell cell = classify(world, pos, state);
			if (cell == Cell.GROUND) return pos.getY();
			if (cell == Cell.SOFT && soft != null) soft.add(pos.toImmutable());
		}
		return world.getBottomY() - 1;
	}

	private static Survey survey(ServerWorld world, Site site) {
		int[][] ground = new int[site.width()][site.depth()];
		List<BlockPos> soft = new ArrayList<>();
		for (int x = site.x0(); x <= site.x1(); x++) {
			for (int z = site.z0(); z <= site.z1(); z++) ground[x - site.x0()][z - site.z0()] = ground(world, x, z, soft);
		}
		return new Survey(site, ground, soft);
	}

	/** The height the site is levelled to: the median of its ground, fixed when levelling starts. */
	private static int level(MachineBlockEntity planner, Survey survey) {
		NbtCompound data = planner.site();
		if (data.contains("Level")) return data.getInt("Level");
		List<Integer> heights = new ArrayList<>();
		for (int[] row : survey.ground()) for (int height : row) heights.add(height);
		heights.sort(Integer::compare);
		int level = heights.get(heights.size() / 2);
		data.putInt("Level", level);
		planner.markDirty();
		return level;
	}

	// ---------------------------------------------------------------- dispatching

	/** A trip to send, or why there isn't one. */
	private record Trip(List<Step> steps, List<ItemStack> cargo, Status status) {
		static Trip none(Status status) { return new Trip(null, null, status); }
	}

	/**
	 * Sends a drone for every trip there is while it has drones home and hydrogen. Returns the planner's status: working,
	 * or what is holding it up.
	 */
	private static Status dispatch(ServerWorld world, MachineBlockEntity planner, StorageNetwork storage, boolean terraformer, int out,
			java.util.function.Supplier<Trip> next) {
		int sent = 0;
		while (true) {
			if (!hasDrone(planner, storage, terraformer)) {
				return sent + out > 0 ? Status.WORKING : terraformer ? Status.NO_TERRAFORMERS : Status.NO_DRONES;
			}
			if (!fuel(planner, storage)) return Status.NO_FUEL;
			Trip trip = next.get();
			if (trip.steps() == null) return trip.status();
			takeDrone(planner, storage, terraformer);
			planner.setToolUses(planner.toolUses() - 1);
			planner.markDirty();
			ConstructionDroneEntity.launch(world, planner.getPos(), terraformer, trip.steps(), trip.cargo());
			sent++;
		}
	}

	private static Item droneItem(boolean terraformer) {
		return RcItems.ITEMS.get(terraformer ? "terraforming_drone" : "construction_drone");
	}

	private static boolean hasDrone(MachineBlockEntity planner, StorageNetwork storage, boolean terraformer) {
		if (!planner.getStack(terraformer ? TERRAFORMER_SLOT : DRONE_SLOT).isEmpty()) return true;
		return storage != null && storage.count(ItemKey.of(droneItem(terraformer)), true) > 0;
	}

	private static void takeDrone(MachineBlockEntity planner, StorageNetwork storage, boolean terraformer) {
		ItemStack slot = planner.getStack(terraformer ? TERRAFORMER_SLOT : DRONE_SLOT);
		if (!slot.isEmpty()) slot.decrement(1);
		else if (storage != null) storage.extract(ItemKey.of(droneItem(terraformer)), 1, true, false);
	}

	/** Makes sure there is a trip's worth of hydrogen, burning a canister from the slot or storage if needed. */
	private static boolean fuel(MachineBlockEntity planner, StorageNetwork storage) {
		if (planner.toolUses() > 0) return true;
		Item canister = RcItems.ITEMS.get("hydrogen_canister");
		ItemStack slot = planner.getStack(FUEL_SLOT);
		if (slot.isOf(canister)) slot.decrement(1);
		else if (storage == null || storage.extract(ItemKey.of(canister), 1, true, false) < 1) return false;
		planner.setToolUses(Math.max(1, config().tripsPerCanister));
		return true;
	}

	private static StorageNetwork storage(ServerWorld world, MachineBlockEntity planner) {
		var pipes = NetworkManager.get(world).component(planner.getPos(), NetKind.ITEM);
		if (pipes.size() <= 1) return null;
		StorageNetwork network = StorageService.networkOf(world, pipes);
		return network.isEmpty() ? null : network;
	}

	private static long available(MachineBlockEntity planner, StorageNetwork storage, Item item) {
		return available(planner, storage, item, false);
	}

	/**
	 * How many of this item the planner can get: its material slots, its storage, and what the Exchange can sell it
	 * (when buying is on, or {@code buy} says to buy anyway).
	 */
	private static long available(MachineBlockEntity planner, StorageNetwork storage, Item item, boolean buy) {
		long count = 0;
		for (int slot = FIRST_MATERIAL; slot < planner.size(); slot++) if (planner.getStack(slot).isOf(item)) count += planner.getStack(slot).getCount();
		if (storage != null) count += storage.count(ItemKey.of(item), true);
		return count + affordable(planner, item, buy);
	}

	/** How many of this item the RackCoin balance buys at the Exchange, if the planner is buying and the Exchange sells it. */
	private static long affordable(MachineBlockEntity planner, Item item, boolean buy) {
		if (!(buying(planner) || buy) || !(planner.getWorld() instanceof ServerWorld world)) return 0;
		Long price = dev.rackcraft.ExchangeCatalog.price(item);
		if (price == null || price <= 0) return 0;
		return FacilityManager.get(world).credits() / price;
	}

	private static List<ItemStack> take(MachineBlockEntity planner, StorageNetwork storage, Item item, int count) {
		return take(planner, storage, item, count, false);
	}

	/** Takes up to this many of an item, slots first, then storage, then the Exchange; returns them as stacks. */
	private static List<ItemStack> take(MachineBlockEntity planner, StorageNetwork storage, Item item, int count, boolean buy) {
		List<ItemStack> taken = new ArrayList<>();
		int left = count;
		for (int slot = FIRST_MATERIAL; slot < planner.size() && left > 0; slot++) {
			ItemStack stack = planner.getStack(slot);
			if (!stack.isOf(item)) continue;
			int moved = Math.min(left, stack.getCount());
			taken.add(stack.split(moved));
			left -= moved;
		}
		if (left > 0 && storage != null) {
			long got = storage.extractOrBuy(ItemKey.of(item), left);
			left -= (int) got;
			addStacks(taken, item, got);
		}
		// Whatever is still short comes straight from the Crypto Exchange, at its prices.
		long bought = Math.min(left, affordable(planner, item, buy));
		if (bought > 0 && planner.getWorld() instanceof ServerWorld world) {
			long cost = bought * dev.rackcraft.ExchangeCatalog.price(item);
			if (FacilityManager.get(world).spendCredits(cost)) {
				planner.site().putLong("Spent", planner.site().getLong("Spent") + cost);
				addStacks(taken, item, bought);
			}
		}
		planner.markDirty();
		return taken;
	}

	private static void addStacks(List<ItemStack> taken, Item item, long count) {
		while (count > 0) {
			int stack = (int) Math.min(count, item.getMaxCount());
			taken.add(new ItemStack(item, stack));
			count -= stack;
		}
	}

	// ---------------------------------------------------------------- clearing

	private static Trip clearTrip(Survey survey, BlockPos planner, Set<BlockPos> claimed) {
		List<BlockPos> open = survey.soft().stream().filter(pos -> !claimed.contains(pos))
				.sorted(Comparator.<BlockPos>comparingInt(pos -> -pos.getY()).thenComparingDouble(pos -> pos.getSquaredDistance(planner))).toList();
		if (open.isEmpty()) return Trip.none(Status.WORKING);
		BlockPos seed = open.get(0);
		// Top down, so a tree comes apart from its crown and its leaves never get left to decay.
		List<Step> steps = new ArrayList<>();
		for (BlockPos pos : open) {
			if (steps.size() >= CLEAR_PER_TRIP) break;
			if (Math.abs(pos.getX() - seed.getX()) > 5 || Math.abs(pos.getZ() - seed.getZ()) > 5) continue;
			steps.add(new Step(pos, Action.CLEAR, null));
			claimed.add(pos);
		}
		return new Trip(steps, List.of(), Status.WORKING);
	}

	// ---------------------------------------------------------------- levelling

	/** The site's cuts (ground above the level) and fills (below it), and the earth still to move. */
	private record Levelling(List<BlockPos> cuts, List<BlockPos> fills, int left, BlockPos blocked) {}

	/** Columns to cut hold their top block; columns to fill hold the first empty block above their ground. */
	private static Levelling levelling(ServerWorld world, Survey survey, int level) {
		Site site = survey.site();
		List<BlockPos> cuts = new ArrayList<>();
		List<BlockPos> fills = new ArrayList<>();
		int left = 0;
		BlockPos blocked = null;
		for (int x = site.x0(); x <= site.x1(); x++) {
			for (int z = site.z0(); z <= site.z1(); z++) {
				int ground = survey.groundAt(x, z);
				if (ground > level) {
					BlockPos top = new BlockPos(x, ground, z);
					if (!diggable(world, top, world.getBlockState(top))) {
						if (blocked == null) blocked = top;
						continue;
					}
					cuts.add(top);
					left += ground - level;
				} else if (ground < level) {
					BlockPos hole = new BlockPos(x, ground + 1, z);
					if (classify(world, hole, world.getBlockState(hole)) != Cell.ROOM) {
						if (blocked == null) blocked = hole;
						continue;
					}
					fills.add(hole);
					left += level - ground;
				}
			}
		}
		return new Levelling(cuts, fills, left, blocked);
	}

	/** Blocks a Terraforming Drone can fill with when there is nothing left to cut, best first. */
	private static final List<Item> FILL = List.of(Items.DIRT, Items.COBBLESTONE, Items.COBBLED_DEEPSLATE, Items.NETHERRACK, Items.STONE,
			Items.ANDESITE, Items.DIORITE, Items.GRANITE, Items.TUFF, Items.COARSE_DIRT, Items.GRAVEL);

	private static Trip levelTrip(ServerWorld world, MachineBlockEntity planner, StorageNetwork storage, Survey survey, Levelling levelling,
			int level, Set<Long> claimedColumns) {
		int load = Math.max(1, config().terraformBlocks);
		List<BlockPos> cuts = levelling.cuts().stream().filter(pos -> !claimedColumns.contains(column(pos))).toList();
		List<BlockPos> fills = levelling.fills().stream().filter(pos -> !claimedColumns.contains(column(pos))).toList();
		if (cuts.isEmpty() && fills.isEmpty()) return Trip.none(Status.WORKING);
		List<Step> steps = new ArrayList<>();
		List<ItemStack> cargo = new ArrayList<>();
		BlockPos near;
		int carrying;
		if (!cuts.isEmpty()) {
			// Dig the highest spot first, then the cuts nearest it, top down, until the drone is full.
			BlockPos first = cuts.stream().max(Comparator.<BlockPos>comparingInt(BlockPos::getY)
					.thenComparingDouble(pos -> -pos.getSquaredDistance(planner.getPos()))).orElseThrow();
			List<BlockPos> order = cuts.stream().sorted(Comparator.comparingDouble(pos -> horizontal(pos, first))).toList();
			for (BlockPos top : order) {
				for (int y = top.getY(); y > level && steps.size() < load; y--) {
					BlockPos pos = new BlockPos(top.getX(), y, top.getZ());
					if (!diggable(world, pos, world.getBlockState(pos))) break;
					steps.add(new Step(pos, Action.DIG, null));
				}
				claimedColumns.add(column(top));
				if (steps.size() >= load) break;
			}
			near = first;
			carrying = steps.size();
		} else {
			// Nothing left to cut: bring fill from the planner or storage.
			Item fill = null;
			for (Item item : FILL) {
				if (available(planner, storage, item) > 0) {
					fill = item;
					break;
				}
			}
			if (fill == null) {
				needs(planner, Items.DIRT, levelling.left());
				return Trip.none(Status.NEEDS_MATERIALS);
			}
			cargo.addAll(take(planner, storage, fill, load));
			carrying = cargo.stream().mapToInt(ItemStack::getCount).sum();
			near = planner.getPos();
		}
		// Fill the lowest spots nearest the cut, bottom up.
		List<BlockPos> holes = fills.stream().sorted(Comparator.<BlockPos>comparingInt(BlockPos::getY)
				.thenComparingDouble(pos -> horizontal(pos, near))).toList();
		int placing = 0;
		for (BlockPos hole : holes) {
			if (placing >= carrying) break;
			for (int y = hole.getY(); y <= level && placing < carrying; y++) {
				steps.add(new Step(new BlockPos(hole.getX(), y, hole.getZ()), Action.FILL, null));
				placing++;
			}
			claimedColumns.add(column(hole));
		}
		return new Trip(steps, cargo, Status.WORKING);
	}

	private static double horizontal(BlockPos a, BlockPos b) {
		double dx = a.getX() - b.getX();
		double dz = a.getZ() - b.getZ();
		return dx * dx + dz * dz;
	}

	// ---------------------------------------------------------------- building

	/** Everything the layout puts on the levelled site, nearest the planner's corner first. */
	private static List<Structure> structures(ServerWorld world, Site site, Layout layout, int level, Hall hall) {
		List<Structure> structures = new ArrayList<>();
		int base = level + 1;
		if (layout == Layout.WIND) {
			int top = nacelleY(level);
			if (top >= world.getTopY()) return structures;
			for (int z = site.z0() + TOWER_MARGIN; z <= site.z1() - TOWER_MARGIN; z += TOWER_SPACING) {
				for (int x = site.x0() + TOWER_MARGIN; x <= site.x1() - TOWER_MARGIN; x += TOWER_SPACING) {
					List<Piece> pieces = new ArrayList<>();
					for (int y = base; y < top; y++) pieces.add(new Piece(new BlockPos(x, y, z), "tower_section"));
					pieces.add(new Piece(new BlockPos(x, top, z), "wind_nacelle"));
					structures.add(new Structure(pieces));
				}
			}
		} else if (layout == Layout.HALL) {
			// Data Hall: rows run east-west in pairs, back to back with CDUs between them, so every rack's back is on a CDU
			// (which Quantum Cores need beside them, and which catches the exhaust into the coolant loop) and its front
			// faces a cold aisle shared with the next pair. Two tiers of racks; Core Routers over the racks and two
			// Chillers over the CDUs. Everything touches, so power, coolant and fiber need no cables. One column is a
			// structure: z+1 rack facing north, z+2 CDU, z+3 rack facing south, with aisles at z and z+4.
			// The blueprint picks the rack tier and the module; Chillers stack up over the CDUs as high as the heat needs.
			int chillers = hall.chillers();
			if (base + 2 + chillers >= world.getTopY()) return structures;
			for (int z = site.z0(); z + 4 <= site.z1(); z += 4) {
				for (int x = site.x0(); x <= site.x1(); x++) {
					BlockPos front = new BlockPos(x, base, z + 1);
					BlockPos middle = front.south();
					BlockPos back = middle.south();
					List<Piece> pieces = new ArrayList<>();
					for (int tier = 0; tier < 2; tier++) {
						pieces.add(new Piece(front.up(tier), hall.tier().blockId(), Direction.NORTH, hall.module()));
						pieces.add(new Piece(middle.up(tier), "cdu", Direction.NORTH, null));
						pieces.add(new Piece(back.up(tier), hall.tier().blockId(), Direction.SOUTH, hall.module()));
					}
					pieces.add(new Piece(front.up(2), "core_router", Direction.NORTH, null));
					pieces.add(new Piece(middle.up(2), "chiller", Direction.NORTH, null));
					pieces.add(new Piece(back.up(2), "core_router", Direction.SOUTH, null));
					for (int extra = 1; extra < chillers; extra++) pieces.add(new Piece(middle.up(2 + extra), "chiller", Direction.NORTH, null));
					structures.add(new Structure(pieces));
				}
			}
		} else {
			// Arrays face north: each takes three blocks east and two north of its part 0, packed edge to edge.
			for (int z = site.z1(); z - 1 >= site.z0(); z -= SolarArrayBlock.DEPTH) {
				for (int x = site.x0(); x + 2 <= site.x1(); x += SolarArrayBlock.WIDTH) {
					structures.add(new Structure(List.of(new Piece(new BlockPos(x, base, z), layout.block))));
				}
			}
		}
		return structures;
	}

	/** Nacelles go up to Y 130 (configurable), or the fewest sections a tower needs if the ground is already high. */
	public static int nacelleY(int level) {
		return Math.max(level + 1 + RackcraftConfig.values.renewables.windTowerMinSections, config().windFarmNacelleY);
	}

	private static boolean built(ServerWorld world, Structure structure) {
		for (Piece piece : structure.pieces()) if (!placed(world, piece) || emptyBays(world, piece) > 0) return false;
		return true;
	}

	private static boolean placed(ServerWorld world, Piece piece) {
		BlockState state = world.getBlockState(piece.pos());
		if (!state.isOf(RcBlocks.get(piece.block()))) return false;
		if (state.contains(SolarArrayBlock.PART)) return state.get(SolarArrayBlock.PART) == 0 && state.get(MachineBlock.FACING) == Direction.NORTH;
		return !state.contains(MachineBlock.FACING) || state.get(MachineBlock.FACING) == piece.facing();
	}

	/** Bays a rack piece still needs filled (all eight if it isn't placed yet); a Failed Module counts as filled. */
	private static int emptyBays(ServerWorld world, Piece piece) {
		if (piece.module() == null) return 0;
		int bays = ServerModel.Tier.of(piece.block()).bays();
		if (!placed(world, piece) || !(world.getBlockEntity(piece.pos()) instanceof MachineBlockEntity rack)) return bays;
		int empty = 0;
		for (int slot = 0; slot < bays; slot++) if (rack.getStack(slot).isEmpty()) empty++;
		return empty;
	}

	/** What a layout's structures draw when everything is running, in kW: the planner's screen shows it. */
	private static double fullDrawKw(List<Structure> structures, Hall hall) {
		double kw = 0;
		for (Structure structure : structures) {
			for (Piece piece : structure.pieces()) {
				kw += switch (piece.block()) {
					case "server_rack", "high_density_rack", "immersion_rack", "exascale_cabinet" -> hall.rackKw();
					case "chiller" -> CoolingLoops.CHILLER_BASE_KW + CoolingLoops.CHILLER_KW_PER_KW * hall.rackKw() * HALL_RACKS_PER_COLUMN / hall.chillers();
					case "cdu" -> 0.5;
					default -> 0;
				};
			}
		}
		return kw;
	}

	/** The blocks a piece takes up: six for an array, one otherwise. */
	private static List<BlockPos> footprint(Piece piece) {
		if (!Renewables.isArray(piece.block())) return List.of(piece.pos());
		List<BlockPos> cells = new ArrayList<>();
		for (int part = 0; part < SolarArrayBlock.WIDTH * SolarArrayBlock.DEPTH; part++) cells.add(piece.pos().add(SolarArrayBlock.offset(Direction.NORTH, part)));
		return cells;
	}

	/** The first block in the way of building this (something solid that isn't ours, or no ground under it), or null. */
	private static BlockPos obstruction(ServerWorld world, Structure structure) {
		BlockPos base = structure.base();
		for (Piece piece : structure.pieces()) {
			if (placed(world, piece)) continue;
			for (BlockPos cell : footprint(piece)) {
				Cell kind = classify(world, cell, world.getBlockState(cell));
				if (kind == Cell.GROUND || kind == Cell.OURS) return cell;
				if (cell.getY() == base.getY()) {
					BlockPos under = cell.down();
					if (classify(world, under, world.getBlockState(under)) != Cell.GROUND) return under;
				}
			}
		}
		return null;
	}

	private static Trip buildTrip(ServerWorld world, MachineBlockEntity planner, StorageNetwork storage, List<Structure> unbuilt, Set<BlockPos> claimed,
			boolean buy) {
		BlockPos home = planner.getPos();
		List<Structure> open = new ArrayList<>();
		for (Structure structure : unbuilt) {
			if (structure.pieces().stream().anyMatch(piece -> claimed.contains(piece.pos()))) continue;
			BlockPos in = obstruction(world, structure);
			if (in != null) blocked(planner, in);
			else open.add(structure);
		}
		if (open.isEmpty()) return Trip.none(planner.siteReading(R_HAS_BLOCKED) != 0 && claimedNone(unbuilt, claimed) ? Status.BLOCKED : Status.WORKING);
		open.sort(Comparator.comparingDouble(structure -> structure.base().getSquaredDistance(home)));
		Structure structure = open.get(0);
		// What it needs, piece by piece; say what is short across everything still to build.
		Map<Item, Integer> wanted = new LinkedHashMap<>();
		for (Piece piece : structure.pieces()) {
			if (!placed(world, piece)) wanted.merge(RcBlocks.get(piece.block()).asItem(), 1, Integer::sum);
			int bays = emptyBays(world, piece);
			if (bays > 0) wanted.merge(RcItems.ITEMS.get(piece.module()), bays, Integer::sum);
		}
		for (Map.Entry<Item, Integer> entry : wanted.entrySet()) {
			Item item = entry.getKey();
			if (available(planner, storage, item, buy) < entry.getValue()) {
				long total = 0;
				for (Structure other : unbuilt) {
					for (Piece piece : other.pieces()) {
						if (!placed(world, piece) && RcBlocks.get(piece.block()).asItem() == item) total++;
						if (piece.module() != null && RcItems.ITEMS.get(piece.module()) == item) total += emptyBays(world, piece);
					}
				}
				needs(planner, item, total - available(planner, storage, item, buy));
				return Trip.none(Status.NEEDS_MATERIALS);
			}
		}
		List<ItemStack> cargo = new ArrayList<>();
		wanted.forEach((item, count) -> cargo.addAll(take(planner, storage, item, count, buy)));
		List<Step> steps = new ArrayList<>();
		for (Piece piece : structure.pieces()) {
			boolean place = !placed(world, piece);
			if (place) steps.add(new Step(piece.pos(), Renewables.isArray(piece.block()) ? Action.ARRAY : Action.PLACE, piece.block(), piece.facing()));
			if (emptyBays(world, piece) > 0) steps.add(new Step(piece.pos(), Action.SUPPLY, null));
			claimed.add(piece.pos());
		}
		return new Trip(steps, cargo, Status.WORKING);
	}

	private static boolean claimedNone(List<Structure> structures, Set<BlockPos> claimed) {
		for (Structure structure : structures) for (Piece piece : structure.pieces()) if (claimed.contains(piece.pos())) return false;
		return true;
	}

	// ---------------------------------------------------------------- wiring

	/**
	 * Every Power Cable block the site needs: along each row of towers and down the first column of them (arrays touch,
	 * so need none), then a run from the planner to the nearest thing built. The run follows the ground; where the ground
	 * steps up or down, the cable climbs the face of the step rather than burrowing.
	 */
	private static List<BlockPos> cables(ServerWorld world, BlockPos planner, Site site, Layout layout, List<Structure> structures, int level) {
		Set<BlockPos> cells = new LinkedHashSet<>();
		int base = level + 1;
		if (layout == Layout.WIND) {
			for (int z = site.z0() + TOWER_MARGIN; z <= site.z1() - TOWER_MARGIN; z += TOWER_SPACING) {
				int last = site.x0() + TOWER_MARGIN;
				for (int x = last + TOWER_SPACING; x <= site.x1() - TOWER_MARGIN; x += TOWER_SPACING) {
					for (int between = last + 1; between < x; between++) cells.add(new BlockPos(between, base, z));
					last = x;
				}
				if (z + TOWER_SPACING <= site.z1() - TOWER_MARGIN) {
					for (int between = z + 1; between < z + TOWER_SPACING; between++) cells.add(new BlockPos(site.x0() + TOWER_MARGIN, base, between));
				}
			}
		}
		// The nearest block of anything built at ground level.
		BlockPos target = null;
		int best = Integer.MAX_VALUE;
		for (Structure structure : structures) {
			for (BlockPos cell : structure.pieces().stream().flatMap(piece -> footprint(piece).stream())
					.filter(cell -> cell.getY() == base).toList()) {
				int distance = Math.abs(cell.getX() - planner.getX()) + Math.abs(cell.getZ() - planner.getZ());
				if (distance < best) {
					best = distance;
					target = cell;
				}
			}
		}
		// No run is needed if the planner's power network already reaches the site (cable laid by hand, say).
		boolean connected = target != null
				&& NetworkManager.get(world).component(planner, dev.rackcraft.sim.NetKind.POWER).contains(target);
		if (target != null && !connected) cells.addAll(route(world, planner, target, site));
		return new ArrayList<>(cells);
	}

	/** How far around the straight line a cable run may detour to get past something in its way. */
	private static final int DETOUR = 24;

	/**
	 * The cable from beside the planner to beside the target, following the ground. It takes the shortest way round
	 * anything it can't dig through (another build, a machine); if there is none, the straight walk across then along,
	 * so the planner can say what is in the way.
	 */
	public static List<BlockPos> route(ServerWorld world, BlockPos planner, BlockPos target, Site site) {
		List<int[]> columns = detour(world, planner, target, site);
		if (columns == null) {
			columns = new ArrayList<>();
			int x = planner.getX();
			int z = planner.getZ();
			while (x != target.getX() || z != target.getZ()) {
				if (x != target.getX()) x += Integer.signum(target.getX() - x);
				else z += Integer.signum(target.getZ() - z);
				if (x == target.getX() && z == target.getZ()) break;
				int surface = ground(world, x, z, null) + 1;
				columns.add(new int[] {x, z, surface, surface});
			}
		}
		if (columns.isEmpty()) return List.of();
		// The first block touches the planner and the last the target, at their heights.
		int[] first = columns.get(0);
		first[2] = Math.min(first[2], planner.getY());
		first[3] = Math.max(first[3], planner.getY());
		int[] last = columns.get(columns.size() - 1);
		last[2] = Math.min(last[2], target.getY());
		last[3] = Math.max(last[3], target.getY());
		// Neighbouring runs must share a height: the lower one climbs to meet the higher.
		for (int index = 0; index + 1 < columns.size(); index++) {
			int[] here = columns.get(index);
			int[] next = columns.get(index + 1);
			if (here[3] < next[2]) here[3] = next[2];
			else if (next[3] < here[2]) next[3] = here[2];
		}
		List<BlockPos> cells = new ArrayList<>();
		for (int[] column : columns) for (int y = column[2]; y <= column[3]; y++) cells.add(new BlockPos(column[0], y, column[1]));
		return cells;
	}

	/** A breadth-first walk over ground columns from the planner to the target, round what a run can't pass; null if none. */
	private static List<int[]> detour(ServerWorld world, BlockPos planner, BlockPos target, Site site) {
		int minX = Math.min(planner.getX(), target.getX()) - DETOUR;
		int maxX = Math.max(planner.getX(), target.getX()) + DETOUR;
		int minZ = Math.min(planner.getZ(), target.getZ()) - DETOUR;
		int maxZ = Math.max(planner.getZ(), target.getZ()) + DETOUR;
		java.util.Map<Long, Long> from = new java.util.HashMap<>();
		java.util.Map<Long, Integer> surface = new java.util.HashMap<>();
		java.util.ArrayDeque<Long> queue = new java.util.ArrayDeque<>();
		long start = column(planner.getX(), planner.getZ());
		long goal = column(target.getX(), target.getZ());
		from.put(start, start);
		queue.add(start);
		while (!queue.isEmpty()) {
			long here = queue.removeFirst();
			int hx = (int) (here >> 32);
			int hz = (int) here;
			for (Direction side : Direction.Type.HORIZONTAL) {
				int x = hx + side.getOffsetX();
				int z = hz + side.getOffsetZ();
				long next = column(x, z);
				if (x < minX || x > maxX || z < minZ || z > maxZ || from.containsKey(next)) continue;
				if (next == goal) {
					from.put(next, here);
					List<int[]> path = new ArrayList<>();
					for (long step = here; step != start; step = from.get(step)) {
						path.add(0, new int[] {(int) (step >> 32), (int) step, surface.get(step), surface.get(step)});
					}
					return path;
				}
				int y = ground(world, x, z, null) + 1;
				BlockPos pos = new BlockPos(x, y, z);
				BlockState state = world.getBlockState(pos);
				Cell cell = classify(world, pos, state);
				boolean inSite = site != null && site.contains(x, z);
				boolean passable = state.isOf(RcBlocks.get("power_cable")) || cell == Cell.ROOM || cell == Cell.SOFT
						|| cell == Cell.GROUND && diggable(world, pos, state) && (inSite || natural(state));
				if (!passable) continue;
				from.put(next, here);
				surface.put(next, y);
				queue.add(next);
			}
		}
		return null;
	}

	private static long column(int x, int z) {
		return ((long) x << 32) | (z & 0xFFFFFFFFL);
	}

	private static Trip wireTrip(ServerWorld world, MachineBlockEntity planner, StorageNetwork storage, List<BlockPos> missing, Set<BlockPos> claimed,
			boolean buy) {
		Site site = site(planner);
		List<BlockPos> run = new ArrayList<>();
		boolean anyClaimed = false;
		for (BlockPos pos : missing) {
			if (claimed.contains(pos)) {
				anyClaimed = true;
				continue;
			}
			BlockState state = world.getBlockState(pos);
			Cell cell = classify(world, pos, state);
			boolean inSite = site != null && site.contains(pos.getX(), pos.getZ());
			boolean clear = cell == Cell.ROOM || cell == Cell.SOFT
					|| cell == Cell.GROUND && (inSite ? diggable(world, pos, state) : natural(state) && diggable(world, pos, state));
			if (!clear) {
				blocked(planner, pos);
				continue;
			}
			if (run.size() < CABLE_PER_TRIP) run.add(pos);
		}
		if (run.isEmpty()) return Trip.none(planner.siteReading(R_HAS_BLOCKED) != 0 && !anyClaimed ? Status.BLOCKED : Status.WORKING);
		Item cable = RcBlocks.get("power_cable").asItem();
		long have = available(planner, storage, cable, buy);
		if (have <= 0) {
			needs(planner, cable, missing.size());
			return Trip.none(Status.NEEDS_MATERIALS);
		}
		if (have < run.size()) run = run.subList(0, (int) have);
		List<ItemStack> cargo = take(planner, storage, cable, run.size(), buy);
		List<Step> steps = new ArrayList<>();
		for (BlockPos pos : run) {
			steps.add(new Step(pos, Action.CABLE, "power_cable"));
			claimed.add(pos);
		}
		return new Trip(steps, cargo, Status.WORKING);
	}

	// ---------------------------------------------------------------- docks

	/**
	 * Where the site's Drone Docks go, chosen once and remembered: as few as cover every array (its part 0, where the wear
	 * is kept) or nacelle, each on level ground at the site's foot and touching an array, a tower or a site cable, so the
	 * site powers it.
	 */
	private static List<BlockPos> dockSpots(ServerWorld world, MachineBlockEntity planner, Site site, Layout layout, List<Structure> structures,
			List<BlockPos> cables, int level) {
		NbtCompound data = planner.site();
		if (data.contains("DockAt")) {
			List<BlockPos> spots = new ArrayList<>();
			for (long packed : data.getLongArray("DockAt")) spots.add(BlockPos.fromLong(packed));
			return spots;
		}
		int base = level + 1;
		Set<BlockPos> taken = new HashSet<>(cables);
		Set<BlockPos> attach = new HashSet<>();
		List<BlockPos> targets = new ArrayList<>();
		for (Structure structure : structures) {
			for (Piece piece : structure.pieces()) for (BlockPos cell : footprint(piece)) {
				taken.add(cell);
				if (cell.getY() == base) attach.add(cell);
			}
			targets.add(layout == Layout.HALL ? structure.base() : structure.pieces().get(structure.pieces().size() - 1).pos());
		}
		for (BlockPos cable : cables) if (cable.getY() == base) attach.add(cable);
		List<BlockPos> candidates = new ArrayList<>();
		for (int x = site.x0() - 1; x <= site.x1() + 1; x++) {
			for (int z = site.z0() - 1; z <= site.z1() + 1; z++) {
				BlockPos pos = new BlockPos(x, base, z);
				if (taken.contains(pos)) continue;
				// A dock in a Data Hall's aisle would block the racks' air: those go round the outside.
				if (layout == Layout.HALL && site.contains(x, z)) continue;
				boolean touching = false;
				for (Direction side : Direction.Type.HORIZONTAL) touching |= attach.contains(pos.offset(side));
				if (!touching) continue;
				Cell cell = classify(world, pos, world.getBlockState(pos));
				if ((cell == Cell.ROOM || cell == Cell.SOFT) && classify(world, pos.down(), world.getBlockState(pos.down())) == Cell.GROUND) candidates.add(pos);
			}
		}
		// Greedy cover: each dock goes where it reaches the most of what isn't covered yet.
		List<BlockPos> uncovered = new ArrayList<>(targets);
		List<BlockPos> spots = new ArrayList<>();
		while (!uncovered.isEmpty() && spots.size() < MAX_DOCKS) {
			BlockPos best = null;
			int bestCount = 0;
			for (BlockPos candidate : candidates) {
				int count = (int) uncovered.stream().filter(target -> DroneDocks.inRange(candidate, target)).count();
				if (count > bestCount) {
					best = candidate;
					bestCount = count;
				}
			}
			if (best == null) break;
			BlockPos chosen = best;
			spots.add(chosen);
			uncovered.removeIf(target -> DroneDocks.inRange(chosen, target));
			candidates.removeIf(candidate -> candidate.getManhattanDistance(chosen) <= 1);
		}
		if (!uncovered.isEmpty()) blocked(planner, uncovered.get(0));
		if (!spots.isEmpty()) {
			data.putLongArray("DockAt", spots.stream().mapToLong(BlockPos::asLong).toArray());
			planner.markDirty();
		}
		return spots;
	}

	private static Trip dockTrip(ServerWorld world, MachineBlockEntity planner, StorageNetwork storage, List<BlockPos> unplaced, Set<BlockPos> claimed) {
		Item dock = RcBlocks.get("drone_dock").asItem();
		boolean anyClaimed = false;
		for (BlockPos pos : unplaced) {
			if (claimed.contains(pos)) {
				anyClaimed = true;
				continue;
			}
			Cell cell = classify(world, pos, world.getBlockState(pos));
			if (cell != Cell.ROOM && cell != Cell.SOFT) {
				blocked(planner, pos);
				continue;
			}
			// The dock itself comes from the slots or storage, or else the Exchange, whether or not Buy is on.
			if (available(planner, storage, dock, true) < 1) {
				needs(planner, dock, unplaced.size());
				return Trip.none(Status.NEEDS_MATERIALS);
			}
			List<ItemStack> cargo = new ArrayList<>(take(planner, storage, dock, 1, true));
			cargo.addAll(take(planner, storage, RcItems.ITEMS.get("maintenance_drone"),
					(int) Math.min(DOCK_DRONES, available(planner, storage, RcItems.ITEMS.get("maintenance_drone")))));
			cargo.addAll(take(planner, storage, RcItems.ITEMS.get("hydrogen_canister"),
					(int) Math.min(DOCK_FUEL, available(planner, storage, RcItems.ITEMS.get("hydrogen_canister")))));
			claimed.add(pos);
			return new Trip(List.of(new Step(pos, Action.PLACE, "drone_dock"), new Step(pos, Action.SUPPLY, null)), cargo, Status.WORKING);
		}
		return Trip.none(planner.siteReading(R_HAS_BLOCKED) != 0 && !anyClaimed ? Status.BLOCKED : Status.WORKING);
	}

	/** A trip topping up one of the site's docks that is short of drones or low on hydrogen, if any is. */
	private static Trip supplyTrip(ServerWorld world, MachineBlockEntity planner, StorageNetwork storage, List<BlockPos> docks, Set<BlockPos> claimed) {
		Item drone = RcItems.ITEMS.get("maintenance_drone");
		Item fuel = RcItems.ITEMS.get("hydrogen_canister");
		for (BlockPos pos : docks) {
			if (claimed.contains(pos) || !(world.getBlockEntity(pos) instanceof MachineBlockEntity dock) || !dock.blockId().equals("drone_dock")) continue;
			int drones = dock.getStack(DroneDocks.DRONE_SLOT).getCount() + dock.workers();
			int canisters = dock.getStack(DroneDocks.FUEL_SLOT).getCount();
			int wantDrones = (int) Math.min(Math.max(0, DOCK_DRONES - drones), available(planner, storage, drone));
			int wantFuel = canisters >= DOCK_FUEL_LOW ? 0 : (int) Math.min(DOCK_FUEL - canisters, available(planner, storage, fuel));
			if (wantDrones + wantFuel <= 0) continue;
			List<ItemStack> cargo = new ArrayList<>(take(planner, storage, drone, wantDrones));
			cargo.addAll(take(planner, storage, fuel, wantFuel));
			claimed.add(pos);
			return new Trip(List.of(new Step(pos, Action.SUPPLY, null)), cargo, Status.WORKING);
		}
		return Trip.none(Status.DONE);
	}

	/** Moves whatever of one item the hold has into a dock's slot, up to the item's stack size. */
	private static void unload(Inventory hold, MachineBlockEntity dock, int slot, Item item) {
		for (int index = 0; index < hold.size(); index++) {
			ItemStack stack = hold.getStack(index);
			if (!stack.isOf(item)) continue;
			ItemStack there = dock.getStack(slot);
			if (there.isEmpty()) {
				int moved = Math.min(stack.getCount(), item.getMaxCount());
				dock.setStack(slot, stack.split(moved));
			} else if (there.isOf(item)) {
				int moved = Math.min(stack.getCount(), there.getMaxCount() - there.getCount());
				there.increment(moved);
				stack.decrement(moved);
			}
		}
		dock.markDirty();
	}

	// ---------------------------------------------------------------- what drones do

	/** The tool drones dig with, for working out what a block drops. */
	private static final ItemStack TOOL = new ItemStack(Items.IRON_PICKAXE);

	/** Does one stop of a trip where the drone hovers. Things it digs up go into its hold. */
	public static void doStep(ServerWorld world, Step step, Inventory hold) {
		BlockPos pos = step.pos();
		BlockState state = world.getBlockState(pos);
		switch (step.action()) {
			case SUPPLY -> {
				if (world.getBlockEntity(pos) instanceof MachineBlockEntity rack && Racks.isRack(rack)) {
					// Fill every empty bay with whatever modules the drone carries.
					for (int bay = 0; bay < Racks.bays(rack); bay++) {
						if (!rack.getStack(bay).isEmpty()) continue;
						for (int index = 0; index < hold.size(); index++) {
							ItemStack stack = hold.getStack(index);
							if (stack.isEmpty() || !rack.isValid(bay, stack)) continue;
							rack.setStack(bay, stack.split(1));
							break;
						}
					}
					rack.markDirty();
					return;
				}
				if (!(world.getBlockEntity(pos) instanceof MachineBlockEntity dock) || !dock.blockId().equals("drone_dock")) return;
				unload(hold, dock, DroneDocks.DRONE_SLOT, RcItems.ITEMS.get("maintenance_drone"));
				unload(hold, dock, DroneDocks.FUEL_SLOT, RcItems.ITEMS.get("hydrogen_canister"));
			}
			case CLEAR -> {
				if (soft(world, pos, state)) dig(world, pos, state, hold);
			}
			case DIG -> {
				if (classify(world, pos, state) == Cell.GROUND && diggable(world, pos, state)) dig(world, pos, state, hold);
			}
			case FILL -> {
				if (classify(world, pos, state) != Cell.ROOM) return;
				ItemStack fill = takeFill(hold);
				if (fill.isEmpty()) return;
				world.setBlockState(pos, ((BlockItem) fill.getItem()).getBlock().getDefaultState(), Block.NOTIFY_ALL);
				placeSound(world, pos);
			}
			case PLACE, ARRAY, CABLE -> {
				Block block = RcBlocks.get(step.block());
				Cell cell = classify(world, pos, state);
				if (cell == Cell.OURS || state.isOf(block)) return;
				if (step.action() == Action.ARRAY) {
					for (int part = 1; part < SolarArrayBlock.WIDTH * SolarArrayBlock.DEPTH; part++) {
						BlockPos other = pos.add(SolarArrayBlock.offset(Direction.NORTH, part));
						if (classify(world, other, world.getBlockState(other)) == Cell.GROUND || classify(world, other, world.getBlockState(other)) == Cell.OURS) return;
					}
				}
				if (!takeOne(hold, block.asItem())) return;
				if (cell == Cell.SOFT || cell == Cell.GROUND) dig(world, pos, state, hold);
				switch (step.action()) {
					case ARRAY -> {
						for (int part = 1; part < SolarArrayBlock.WIDTH * SolarArrayBlock.DEPTH; part++) {
							BlockPos other = pos.add(SolarArrayBlock.offset(Direction.NORTH, part));
							BlockState there = world.getBlockState(other);
							if (classify(world, other, there) == Cell.SOFT) dig(world, other, there, hold);
						}
						((SolarArrayBlock) block).placeAll(world, pos, Direction.NORTH);
					}
					case CABLE -> world.setBlockState(pos, ((CableBlock) block).withConnections(block.getDefaultState(), world, pos), Block.NOTIFY_ALL);
					default -> world.setBlockState(pos, block.getDefaultState().with(MachineBlock.FACING, step.facing()), Block.NOTIFY_ALL);
				}
				placeSound(world, pos);
			}
		}
	}

	/** Breaks a block the way a drone does: no drops on the floor, everything into its hold. */
	private static void dig(ServerWorld world, BlockPos pos, BlockState state, Inventory hold) {
		List<ItemStack> drops = Block.getDroppedStacks(state, world, pos, null, null, TOOL);
		world.breakBlock(pos, false);
		for (ItemStack drop : drops) {
			ItemStack left = addToHold(hold, drop);
			if (!left.isEmpty()) ItemScatterer.spawn(world, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, left);
		}
	}

	private static ItemStack addToHold(Inventory hold, ItemStack stack) {
		for (int slot = 0; slot < hold.size() && !stack.isEmpty(); slot++) {
			ItemStack there = hold.getStack(slot);
			if (there.isEmpty()) {
				hold.setStack(slot, stack.copy());
				return ItemStack.EMPTY;
			}
			if (ItemStack.canCombine(there, stack) && there.getCount() < there.getMaxCount()) {
				int moved = Math.min(stack.getCount(), there.getMaxCount() - there.getCount());
				there.increment(moved);
				stack.decrement(moved);
			}
		}
		return stack;
	}

	private static boolean takeOne(Inventory hold, Item item) {
		for (int slot = 0; slot < hold.size(); slot++) {
			if (hold.getStack(slot).isOf(item)) {
				hold.getStack(slot).decrement(1);
				return true;
			}
		}
		return false;
	}

	/** One block from the hold that makes good ground: a plain full block, not a machine or anything that falls. */
	private static ItemStack takeFill(Inventory hold) {
		for (int slot = 0; slot < hold.size(); slot++) {
			ItemStack stack = hold.getStack(slot);
			if (stack.isEmpty() || !(stack.getItem() instanceof BlockItem item)) continue;
			BlockState state = item.getBlock().getDefaultState();
			if (state.hasBlockEntity() || !state.isFullCube(EmptyBlockView.INSTANCE, BlockPos.ORIGIN)
					|| Registries.BLOCK.getId(item.getBlock()).getNamespace().equals(dev.rackcraft.Rackcraft.MOD_ID)) continue;
			return stack.split(1);
		}
		return ItemStack.EMPTY;
	}

	private static void placeSound(ServerWorld world, BlockPos pos) {
		world.playSound(null, pos, SoundEvents.BLOCK_STONE_PLACE, SoundCategory.BLOCKS, 0.6f, 1.1f);
	}

	/**
	 * A drone is home: it goes back in its slot (or storage), and its hold into storage, the material slots, or the
	 * floor, in that order.
	 */
	public static void dockDrone(ServerWorld world, MachineBlockEntity planner, boolean terraformer, Inventory hold) {
		StorageNetwork storage = storage(world, planner);
		ItemStack drone = new ItemStack(droneItem(terraformer));
		int slotIndex = terraformer ? TERRAFORMER_SLOT : DRONE_SLOT;
		ItemStack slot = planner.getStack(slotIndex);
		if (slot.isEmpty()) planner.setStack(slotIndex, drone);
		else if (slot.isOf(drone.getItem()) && slot.getCount() < slot.getMaxCount()) slot.increment(1);
		else if (storage == null || storage.insert(ItemKey.of(drone), 1, false) < 1) drop(world, planner, drone);
		for (int index = 0; index < hold.size(); index++) {
			ItemStack stack = hold.removeStack(index);
			if (stack.isEmpty()) continue;
			if (storage != null) stack.decrement((int) storage.insert(ItemKey.of(stack), stack.getCount(), false));
			for (int material = FIRST_MATERIAL; material < planner.size() && !stack.isEmpty(); material++) {
				ItemStack there = planner.getStack(material);
				if (there.isEmpty()) {
					planner.setStack(material, stack.copy());
					stack.setCount(0);
				} else if (ItemStack.canCombine(there, stack) && there.getCount() < there.getMaxCount()) {
					int moved = Math.min(stack.getCount(), there.getMaxCount() - there.getCount());
					there.increment(moved);
					stack.decrement(moved);
				}
			}
			if (!stack.isEmpty()) drop(world, planner, stack);
		}
		planner.markDirty();
	}

	private static void drop(ServerWorld world, MachineBlockEntity planner, ItemStack stack) {
		BlockPos pos = planner.getPos();
		ItemScatterer.spawn(world, pos.getX() + 0.5, pos.getY() + 1.2, pos.getZ() + 0.5, stack);
	}
}
