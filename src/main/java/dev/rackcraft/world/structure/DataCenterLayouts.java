package dev.rackcraft.world.structure;

import dev.rackcraft.Rackcraft;
import dev.rackcraft.RcItems;
import dev.rackcraft.block.MachineBlockEntity;
import dev.rackcraft.compute.TrainingStations;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.DoorBlock;
import net.minecraft.block.LanternBlock;
import net.minecraft.block.LeavesBlock;
import net.minecraft.block.LadderBlock;
import net.minecraft.block.StairsBlock;
import net.minecraft.block.enums.DoubleBlockHalf;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.state.property.Properties;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Direction;
import net.minecraft.village.VillagerProfession;

/**
 * Every abandoned data center, as code. Each layout has a footprint (width along x, depth along z), a
 * height, and how it sits in the terrain. Coordinates are local to {@link Site}: y = 0 is the floor.
 *
 * <ul>
 *   <li>{@code site_7}: the original tutorial site. Two cut cables and it mines.</li>
 *   <li>{@code server_closet}: one rack in a brick shed, run off a rooftop solar panel.</li>
 *   <li>{@code container_farm}: shipping containers of ASIC miners beside a pond the pump drank dry.</li>
 *   <li>{@code crypto_garage}: a suburban GPU rig with RGB lighting and a diesel generator indoors.</li>
 *   <li>{@code bunker}: an underground hall with generators and batteries, reached by a ladder shaft.</li>
 *   <li>{@code flooded_hall}: a swamp data hall half full of water.</li>
 *   <li>{@code overgrown_colo}: a jungle colocation facility with a tree growing through it.</li>
 *   <li>{@code arctic_vault}: a snowbound site with a Quantum Core locked in a vault.</li>
 *   <li>{@code ai_lab}: a research lab: a server room, an ops room, three librarians chained to their desks
 *       and a fenced crayon corner of kids.</li>
 *   <li>{@code content_mill}: a timber "data labelling" mill: six shackled librarians, a playroom of kids
 *       at art tables, and a middle manager.</li>
 *   <li>{@code solar_farm}: rows of panels and a control hut.</li>
 *   <li>{@code tape_archive}: a cobwebbed vault of tape libraries.</li>
 *   <li>{@code hyperscale_campus}: 176 x 176 blocks and very rare: a fully working campus of four data halls,
 *       a quantum vault, a 3x3x3 reactor array, an operations centre and a reservoir. Five cut cables keep it dark.</li>
 * </ul>
 */
public final class DataCenterLayouts {
	public enum Placement { SURFACE, BURIED }

	public record Layout(String id, int width, int height, int depth, Placement placement, int buryDepth, int maxSlope,
			boolean allowWater, boolean levelToAverage, Consumer<Site> build) {}

	public static final Identifier SITE_7_LOOT = Rackcraft.id("chests/abandoned_data_center");
	public static final Identifier COMMON_LOOT = Rackcraft.id("chests/data_center/common");
	public static final Identifier GARAGE_LOOT = Rackcraft.id("chests/data_center/garage");
	public static final Identifier VAULT_LOOT = Rackcraft.id("chests/data_center/vault");
	public static final Identifier AI_LAB_LOOT = Rackcraft.id("chests/data_center/ai_lab");
	public static final Identifier ARCHIVE_LOOT = Rackcraft.id("chests/data_center/archive");
	public static final Identifier OFFICE_LOOT = Rackcraft.id("chests/data_center/office");
	public static final Identifier MILL_LOOT = Rackcraft.id("chests/data_center/content_mill");

	/** Campus edge length. Centred on its start chunk, so it stays within the 8-chunk reach of structure references. */
	public static final int CAMPUS_SIZE = 176;

	private static final Map<String, Layout> LAYOUTS = new LinkedHashMap<>();

	static {
		add(new Layout("site_7", 9, 8, 7, Placement.SURFACE, 0, 3, false, false, DataCenterLayouts::site7));
		add(new Layout("server_closet", 7, 6, 7, Placement.SURFACE, 0, 3, false, false, DataCenterLayouts::serverCloset));
		add(new Layout("container_farm", 21, 7, 12, Placement.SURFACE, 0, 4, false, false, DataCenterLayouts::containerFarm));
		add(new Layout("crypto_garage", 11, 6, 10, Placement.SURFACE, 0, 3, false, false, DataCenterLayouts::cryptoGarage));
		add(new Layout("bunker", 13, 13, 11, Placement.BURIED, 8, 6, false, false, DataCenterLayouts::bunker));
		add(new Layout("flooded_hall", 15, 7, 11, Placement.SURFACE, 0, 4, true, false, DataCenterLayouts::floodedHall));
		add(new Layout("overgrown_colo", 17, 11, 13, Placement.SURFACE, 0, 5, false, false, DataCenterLayouts::overgrownColo));
		add(new Layout("arctic_vault", 13, 7, 11, Placement.SURFACE, 0, 4, false, false, DataCenterLayouts::arcticVault));
		add(new Layout("ai_lab", 25, 8, 18, Placement.SURFACE, 0, 4, false, false, DataCenterLayouts::aiLab));
		add(new Layout("content_mill", 27, 9, 19, Placement.SURFACE, 0, 4, false, false, DataCenterLayouts::contentMill));
		add(new Layout("solar_farm", 23, 5, 15, Placement.SURFACE, 0, 4, false, false, DataCenterLayouts::solarFarm));
		add(new Layout("tape_archive", 13, 7, 11, Placement.SURFACE, 0, 4, false, false, DataCenterLayouts::tapeArchive));
		add(new Layout("hyperscale_campus", CAMPUS_SIZE, 24, CAMPUS_SIZE, Placement.SURFACE, 0, 16, false, true, DataCenterLayouts::campus));
	}

	private DataCenterLayouts() {}

	private static void add(Layout layout) {
		LAYOUTS.put(layout.id(), layout);
	}

	public static Layout get(String id) { return LAYOUTS.get(id); }

	public static Map<String, Layout> all() { return LAYOUTS; }

	// ---------------------------------------------------------------- shorthand

	private static BlockState b(Block block) { return block.getDefaultState(); }

	private static final BlockState AIR = b(Blocks.AIR);
	private static final Direction N = Direction.NORTH;
	private static final Direction S = Direction.SOUTH;
	private static final Direction E = Direction.EAST;
	private static final Direction W = Direction.WEST;

	private static BlockState hangingLantern() {
		return b(Blocks.LANTERN).with(LanternBlock.HANGING, true);
	}

	private static BlockState stairs(Block block, Direction facing) {
		return block.getDefaultState().with(StairsBlock.FACING, facing);
	}

	private static BlockState leaves(Block block) {
		return block.getDefaultState().with(LeavesBlock.PERSISTENT, true);
	}

	private static void door(Site s, int x, int y, int z, Block block, Direction facing) {
		s.set(x, y, z, block.getDefaultState().with(DoorBlock.FACING, facing).with(DoorBlock.HALF, DoubleBlockHalf.LOWER));
		s.set(x, y + 1, z, block.getDefaultState().with(DoorBlock.FACING, facing).with(DoorBlock.HALF, DoubleBlockHalf.UPPER));
	}

	/** Fence or bars that join along a straight run, so they don't generate as lonely posts. */
	private static BlockState run(Block block, boolean alongX) {
		BlockState state = block.getDefaultState();
		return alongX ? state.with(Properties.EAST, true).with(Properties.WEST, true)
				: state.with(Properties.NORTH, true).with(Properties.SOUTH, true);
	}

	// ---------------------------------------------------------------- site 7, the tutorial

	/** The original site, block for block. See the Maintenance Log in its chest. */
	private static void site7(Site s) {
		int width = 9;
		int depth = 7;
		s.pad(0, 0, width - 1, depth - 1, 7, 6, b(Blocks.COBBLESTONE));
		s.fill(0, 0, 0, width - 1, 0, depth - 1, (x, y, z) -> {
			boolean edge = x == 0 || z == 0 || x == width - 1 || z == depth - 1;
			return edge ? b(Blocks.STONE_BRICKS) : s.chance(x, y, z, 2, 5) ? b(Blocks.CRACKED_STONE_BRICKS)
					: b(dev.rackcraft.RcBlocks.get("raised_floor_tile"));
		});
		s.walls(0, 1, 0, width - 1, 3, depth - 1, s::weathered);
		s.fill(0, 4, 0, width - 1, 4, depth - 1, (x, y, z) -> {
			boolean edge = x == 0 || z == 0 || x == width - 1 || z == depth - 1;
			if (!edge && s.chance(x, y, z, 3, 9)) return null;
			return s.chance(x, y, z, 4, 4) ? b(Blocks.MOSSY_COBBLESTONE) : b(Blocks.POLISHED_ANDESITE);
		});
		s.set(4, 1, 0, AIR);
		s.set(4, 2, 0, AIR);
		s.set(2, 2, 0, b(Blocks.IRON_BARS));
		s.set(6, 2, 0, b(Blocks.IRON_BARS));
		s.machine(1, 1, 3, "diesel_generator", N, new ItemStack(Items.COAL, 12));
		s.machine(3, 1, 3, "server_rack", N, Site.item("server_1u", 1), Site.item("server_1u", 1),
				Site.item("pi_node", 1), Site.item("pi_node", 1));
		s.machine(4, 1, 3, "server_rack", N, Site.item("pi_node", 1), Site.item("pi_node", 1),
				Site.item("pi_node", 1), Site.item("pi_node", 1));
		s.machine(6, 1, 3, "uplink_router", N);
		s.machine(4, 1, 5, "exhaust_fan", N);
		s.machine(7, 1, 1, "crypto_exchange", W);
		s.chest(1, 1, 1, S, SITE_7_LOOT);
		s.set(2, 3, 2, hangingLantern());
		s.set(6, 3, 4, hangingLantern());
		s.set(7, 3, 5, b(Blocks.COBWEB));
		s.set(1, 3, 1, b(Blocks.COBWEB));
		for (int index = 0; index < 4; index++) {
			int x = 1 + s.hash(index, 0, 0, 6) % (width - 2);
			int z = 1 + s.hash(index, 0, 0, 7) % 2;
			if (s.get(x, 1, z).isAir()) s.set(x, 1, z, b(Blocks.MOSS_CARPET));
		}
		s.cable(2, 1, 3, "power_cable", true);
		s.cable(5, 1, 3, "fiber_cable", true);
		s.cable(4, 2, 3, "power_cable", false);
		s.cable(4, 2, 4, "power_cable", false);
		s.cable(4, 2, 5, "power_cable", false);
	}

	// ---------------------------------------------------------------- small sites

	private static void serverCloset(Site s) {
		s.pad(0, 0, 6, 6, 6, 6, b(Blocks.COBBLESTONE));
		s.fill(0, 0, 0, 6, 0, 6, b(Blocks.SMOOTH_STONE));
		s.walls(0, 1, 0, 6, 3, 6, (x, y, z) -> s.chance(x, y, z, 1, 6) ? b(Blocks.MOSSY_COBBLESTONE) : b(Blocks.BRICKS));
		s.fill(0, 4, 0, 6, 4, 6, (x, y, z) -> s.chance(x, y, z, 2, 7) && x > 0 && x < 6 && z > 0 && z < 6 ? null : b(Blocks.DARK_OAK_PLANKS));
		s.set(3, 1, 0, AIR);
		s.set(3, 2, 0, AIR);
		s.set(1, 2, 0, b(Blocks.GLASS_PANE));
		s.set(5, 2, 0, b(Blocks.GLASS_PANE));
		s.rack(3, 1, 4, N, "pi_node:3,server_1u:2,empty:1");
		s.machine(4, 1, 4, "uplink_router", N);
		s.machine(3, 4, 4, "solar_panel", N);
		s.cable(3, 2, 4, "power_cable", false);
		s.cable(3, 3, 4, "power_cable", true);
		s.machine(3, 1, 6, "exhaust_fan", N);
		s.chest(1, 1, 5, E, COMMON_LOOT);
		s.set(5, 3, 5, b(Blocks.COBWEB));
		s.set(1, 3, 1, b(Blocks.COBWEB));
		s.set(5, 1, 1, b(Blocks.CRAFTING_TABLE));
	}

	private static void containerFarm(Site s) {
		s.pad(0, 0, 20, 11, 7, 5, b(Blocks.DIRT));
		s.fill(0, 0, 0, 20, 0, 11, (x, y, z) -> s.chance(x, y, z, 1, 3) ? b(Blocks.GRAVEL) : b(Blocks.COARSE_DIRT));
		container(s, 1, 1, Blocks.ORANGE_TERRACOTTA, true);
		container(s, 11, 1, Blocks.BLUE_TERRACOTTA, true);
		container(s, 1, 7, Blocks.RED_TERRACOTTA, false);
		container(s, 11, 7, Blocks.GREEN_TERRACOTTA, false);
		// The pond the pump drank dry.
		s.fill(16, 0, 5, 19, 0, 6, b(Blocks.MUD));
		s.set(17, 1, 5, b(Blocks.DEAD_BUSH));
		s.set(19, 1, 6, b(Blocks.DEAD_BUSH));
		s.machine(20, 1, 5, "freshwater_pump", W);
		s.cableRun(16, 1, 6, 20, 1, 6, "coolant_pipe");
		s.chest(8, 1, 8, W, COMMON_LOOT);
	}

	/** A 9 x 4 shipping container with two ASIC racks inside and its doors facing the middle aisle. */
	private static void container(Site s, int x0, int z0, Block colour, boolean doorsSouth) {
		int x1 = x0 + 8;
		int z1 = z0 + 3;
		s.fill(x0, 0, z0, x1, 4, z1, (x, y, z) -> s.chance(x, y, z, 3, 9) ? b(Blocks.BROWN_TERRACOTTA) : b(colour));
		s.fill(x0 + 1, 1, z0 + 1, x1 - 1, 3, z1 - 1, AIR);
		int doorZ = doorsSouth ? z1 : z0;
		s.fill(x0 + 3, 1, doorZ, x0 + 5, 2, doorZ, AIR);
		s.set(x0 + 3, 3, doorZ, b(Blocks.IRON_BARS));
		Direction facing = doorsSouth ? S : N;
		int rackZ = doorsSouth ? z0 + 1 : z1 - 1;
		s.rack(x0 + 2, 1, rackZ, facing, "asic_miner:5,failed_module:2,empty:1");
		s.rack(x0 + 6, 1, rackZ, facing, "asic_miner:5,failed_module:2,empty:1");
		if (doorsSouth) {
			for (int x = x0 + 1; x <= x1 - 1; x += 2) s.machine(x, 5, z0 + 1, "solar_panel", S);
		}
	}

	private static void cryptoGarage(Site s) {
		s.pad(0, 0, 10, 9, 6, 5, b(Blocks.COBBLESTONE));
		s.fill(0, 0, 0, 10, 0, 9, (x, y, z) -> s.chance(x, y, z, 1, 7) ? b(Blocks.BLACK_CONCRETE) : b(Blocks.SMOOTH_STONE));
		s.walls(0, 1, 0, 10, 1, 9, b(Blocks.BRICKS));
		s.walls(0, 2, 0, 10, 3, 9, b(Blocks.WHITE_CONCRETE));
		s.fill(0, 4, 0, 10, 4, 9, b(Blocks.SPRUCE_PLANKS));
		s.fill(2, 1, 0, 8, 2, 0, AIR);
		s.fill(2, 3, 0, 8, 3, 0, b(Blocks.IRON_TRAPDOOR));
		s.set(0, 2, 3, b(Blocks.MAGENTA_STAINED_GLASS));
		s.set(0, 2, 6, b(Blocks.CYAN_STAINED_GLASS));
		s.set(10, 2, 3, b(Blocks.CYAN_STAINED_GLASS));
		s.set(10, 2, 6, b(Blocks.MAGENTA_STAINED_GLASS));
		s.set(5, 4, 5, b(Blocks.SEA_LANTERN));
		s.set(2, 4, 7, b(Blocks.GLOWSTONE));
		// The rig: generator, a cut cable, a GPU rack, two monitors and a router.
		s.machine(1, 1, 8, "diesel_generator", E, new ItemStack(RcItems.ITEMS.get("coke"), 6));
		s.cable(2, 1, 8, "power_cable", false);
		s.cable(3, 1, 8, "power_cable", true);
		s.rack(4, 1, 8, N, "gpu_blade:4,server_1u:1");
		s.machine(5, 1, 8, "monitoring_wall", N);
		s.machine(6, 1, 8, "monitoring_wall", N);
		s.machine(7, 1, 8, "uplink_router", N);
		s.set(5, 1, 6, stairs(Blocks.RED_NETHER_BRICK_STAIRS, S));
		s.set(5, 1, 5, b(Blocks.RED_CARPET));
		s.machine(10, 2, 7, "exhaust_fan", W);
		s.set(1, 1, 1, b(Blocks.CHIPPED_ANVIL));
		s.set(2, 1, 1, b(Blocks.CAULDRON));
		s.chest(9, 1, 1, W, GARAGE_LOOT);
	}

	private static void bunker(Site s) {
		// The hall, nine blocks below the surface.
		s.fill(0, 0, 0, 12, 5, 10, (x, y, z) -> s.chance(x, y, z, 1, 8) ? b(Blocks.CRACKED_DEEPSLATE_BRICKS) : b(Blocks.GRAY_CONCRETE));
		s.fill(1, 1, 1, 11, 4, 9, AIR);
		s.fill(1, 0, 1, 11, 0, 9, b(Blocks.POLISHED_ANDESITE));
		// Ladder shaft up to a little hut at the surface.
		s.fill(1, 5, 0, 3, 8, 2, b(Blocks.GRAY_CONCRETE));
		s.fill(1, 9, 0, 3, 11, 2, b(Blocks.GRAY_CONCRETE));
		s.fill(1, 12, 0, 3, 12, 2, b(Blocks.SMOOTH_STONE_SLAB));
		s.fill(2, 1, 1, 2, 10, 1, AIR);
		for (int y = 1; y <= 10; y++) s.set(2, y, 1, b(Blocks.LADDER).with(LadderBlock.FACING, S));
		s.fill(2, 9, 2, 2, 10, 2, AIR);
		door(s, 2, 9, 2, Blocks.IRON_DOOR, S);
		s.set(3, 10, 2, b(Blocks.STONE_BUTTON));
		// Power: generators, batteries, a PDU, a cut cable to the rack row.
		s.machine(10, 1, 2, "diesel_generator", W, new ItemStack(RcItems.ITEMS.get("coke"), 8));
		s.machine(10, 1, 3, "diesel_generator", W, new ItemStack(Items.COAL, 16));
		s.machine(10, 1, 4, "battery_bank", W);
		s.machine(10, 1, 5, "battery_bank", W);
		s.machine(10, 1, 6, "pdu", W);
		s.cable(9, 1, 6, "power_cable", false);
		s.cable(9, 1, 7, "power_cable", true);
		s.cable(9, 1, 8, "power_cable", false);
		for (int x = 4; x <= 8; x++) s.rack(x, 1, 8, N, "server_1u:3,pi_node:2,failed_module:1");
		s.machine(3, 1, 8, "uplink_router", N);
		s.machine(1, 1, 5, "fire_suppression_tank", E, Site.item("suppression_canister", 1));
		s.set(6, 4, 4, hangingLantern());
		s.set(4, 4, 6, hangingLantern());
		s.chest(1, 1, 9, E, VAULT_LOOT);
		s.chest(5, 1, 1, S, COMMON_LOOT);
	}

	private static void floodedHall(Site s) {
		s.pad(0, 0, 14, 10, 7, 6, b(Blocks.STONE));
		s.fill(0, 0, 0, 14, 0, 10, (x, y, z) -> s.chance(x, y, z, 1, 3) ? b(Blocks.MOSSY_COBBLESTONE)
				: b(dev.rackcraft.RcBlocks.get("raised_floor_tile")));
		s.walls(0, 1, 0, 14, 4, 10, s::weathered);
		s.fill(0, 5, 0, 14, 5, 10, (x, y, z) -> s.chance(x, y, z, 2, 4) ? null
				: s.chance(x, y, z, 3, 3) ? b(Blocks.MOSS_BLOCK) : b(Blocks.STONE_BRICKS));
		s.fill(1, 1, 1, 13, 1, 9, b(Blocks.WATER));
		s.fill(1, 2, 1, 13, 4, 9, AIR);
		s.set(7, 1, 0, b(Blocks.WATER));
		s.set(7, 2, 0, AIR);
		for (int x = 2; x <= 12; x++) {
			if (!s.chance(x, 1, 3, 4, 4)) s.rack(x, 1, 3, N, "server_1u:3,pi_node:2,failed_module:2");
			if (!s.chance(x, 1, 7, 4, 4)) s.rack(x, 1, 7, S, "server_1u:3,pi_node:2,failed_module:2");
		}
		s.machine(13, 1, 5, "crac_unit", W);
		s.machine(1, 1, 5, "crac_unit", E);
		s.fill(1, 2, 1, 13, 2, 9, (x, y, z) -> s.get(x, 1, z).isOf(Blocks.WATER) && s.chance(x, y, z, 5, 6)
				? b(Blocks.LILY_PAD) : null);
		s.chest(12, 1, 9, W, COMMON_LOOT, true);
	}

	private static void overgrownColo(Site s) {
		s.pad(0, 0, 16, 12, 10, 6, b(Blocks.MOSSY_COBBLESTONE));
		s.fill(0, 0, 0, 16, 0, 12, (x, y, z) -> s.chance(x, y, z, 1, 3) ? b(Blocks.MOSS_BLOCK) : s.weathered(x, y, z));
		s.walls(0, 1, 0, 16, 5, 12, (x, y, z) -> y > 1 && s.chance(x, y, z, 2, 7) ? AIR : s.weathered(x, y, z));
		s.fill(0, 6, 0, 16, 6, 12, (x, y, z) -> s.chance(x, y, z, 3, 3) ? null
				: s.chance(x, y, z, 4, 4) ? leaves(Blocks.JUNGLE_LEAVES) : b(Blocks.MOSSY_STONE_BRICKS));
		s.fill(8, 1, 0, 8, 2, 0, AIR);
		// Colocation cages, three customers' worth.
		for (int cage = 0; cage < 3; cage++) {
			int x0 = 2 + cage * 4;
			int cageX = x0;
			s.walls(x0, 1, 3, x0 + 3, 3, 8, (x, y, z) -> run(Blocks.IRON_BARS, x != cageX && x != cageX + 3));
			s.fill(x0 + 1, 1, 3, x0 + 2, 2, 3, AIR);
			s.rack(x0 + 1, 1, 6, N, "server_1u:3,gpu_blade:1,failed_module:2,empty:1");
			s.rack(x0 + 2, 1, 6, N, "server_1u:3,gpu_blade:1,failed_module:2,empty:1");
		}
		// The tree that won.
		s.fill(14, 1, 10, 14, 9, 10, b(Blocks.JUNGLE_LOG));
		s.fill(12, 8, 8, 16, 10, 12, (x, y, z) -> {
			int dx = x - 14, dy = y - 9, dz = z - 10;
			return dx * dx + dy * dy * 2 + dz * dz <= 6 && !(dx == 0 && dz == 0 && y < 10) ? leaves(Blocks.JUNGLE_LEAVES) : null;
		});
		s.fill(1, 1, 1, 15, 1, 11, (x, y, z) -> s.get(x, y, z).isAir() && s.chance(x, y, z, 5, 5) ? b(Blocks.MOSS_CARPET) : null);
		s.chest(15, 1, 2, W, COMMON_LOOT);
	}

	private static void arcticVault(Site s) {
		s.pad(0, 0, 12, 10, 7, 6, b(Blocks.STONE));
		s.fill(0, 0, 0, 12, 0, 10, (x, y, z) -> x >= 9 ? b(Blocks.SNOW_BLOCK) : b(Blocks.SMOOTH_QUARTZ));
		s.walls(0, 1, 0, 8, 4, 10, (x, y, z) -> s.chance(x, y, z, 1, 4) ? b(Blocks.PACKED_ICE) : b(Blocks.WHITE_CONCRETE));
		s.fill(0, 5, 0, 8, 5, 10, b(Blocks.SNOW_BLOCK));
		s.fill(0, 6, 0, 8, 6, 10, b(Blocks.SNOW));
		s.fill(6, 1, 0, 6, 2, 0, AIR);
		s.set(6, 0, 1, b(Blocks.POWDER_SNOW));
		for (int x = 4; x <= 7; x++) s.rack(x, 1, 3, S, "server_1u:2,gpu_blade:2,asic_miner:1,failed_module:1");
		// The vault: iron walls, an iron door, a Quantum Core with its CDU.
		s.walls(0, 1, 5, 4, 3, 10, b(Blocks.IRON_BLOCK));
		s.fill(1, 1, 6, 3, 3, 9, AIR);
		door(s, 2, 1, 5, Blocks.IRON_DOOR, S);
		s.set(1, 2, 4, b(Blocks.STONE_BUTTON));
		s.rack(2, 1, 8, N, "quantum_core:1");
		s.machine(3, 1, 8, "cdu", N);
		s.chest(1, 1, 9, E, VAULT_LOOT);
		// The yard: cooling towers in the snow.
		s.fill(9, 1, 0, 12, 1, 10, (x, y, z) -> s.chance(x, y, z, 2, 2) ? b(Blocks.SNOW) : null);
		s.machine(11, 1, 2, "cooling_tower", W);
		s.machine(11, 1, 6, "cooling_tower", W);
		s.cableRun(10, 1, 2, 10, 1, 6, "coolant_pipe");
		s.chest(7, 1, 9, N, COMMON_LOOT);
	}

	/**
	 * 25 x 18, twice the old lab: a glass-fronted research lab with a server room (NW), an ops room (NE), a
	 * training wing of three chained librarians (SE) and a fenced crayon corner of kids (SW). "AGI" is
	 * painted on the west wall, in the hand of someone who believed it.
	 */
	private static void aiLab(Site s) {
		int x1 = 24;
		int z1 = 17;
		s.pad(0, 0, x1, z1, 7, 6, b(Blocks.STONE));
		s.fill(0, 0, 0, x1, 0, z1, (x, y, z) -> s.chance(x, y, z, 1, 6) ? b(Blocks.POLISHED_DIORITE) : b(Blocks.SMOOTH_QUARTZ));
		s.walls(0, 1, 0, x1, 5, z1, (x, y, z) -> (z == 0 || z == z1) && (y == 2 || y == 3) && x % 3 == 1
				? b(Blocks.GLASS_PANE).with(Properties.EAST, true).with(Properties.WEST, true) : b(Blocks.WHITE_CONCRETE));
		s.fill(0, 6, 0, x1, 6, z1, (x, y, z) -> x % 5 == 2 && z % 4 == 2 ? b(Blocks.GLASS) : b(Blocks.LIGHT_GRAY_CONCRETE));
		s.fill(12, 1, 0, 13, 2, 0, AIR);
		String[][] letters = {
				{" # ", "# #", "###", "# #"}, {"###", "#  ", "# #", "###"}, {"###", " # ", " # ", "###"}};
		for (int letter = 0; letter < letters.length; letter++) {
			for (int row = 0; row < 4; row++) {
				for (int column = 0; column < 3; column++) {
					if (letters[letter][row].charAt(column) == '#') s.set(0, 5 - row, 16 - letter * 4 - column, b(Blocks.BLACK_CONCRETE));
				}
			}
		}
		Site.Material partition = (x, y, z) -> y == 2 || y == 3
				? b(Blocks.GLASS_PANE).with(Properties.NORTH, true).with(Properties.SOUTH, true).with(Properties.EAST, true)
						.with(Properties.WEST, true) : b(Blocks.WHITE_CONCRETE);

		// Server room: two rows of AI racks facing a shared cold aisle, a CRAC unit blowing down it.
		s.fill(1, 0, 1, 8, 0, 7, b(dev.rackcraft.RcBlocks.get("raised_floor_tile")));
		s.fill(9, 1, 1, 9, 5, 8, partition);
		s.fill(1, 1, 8, 9, 5, 8, partition);
		s.fill(5, 1, 8, 5, 2, 8, AIR);
		for (int x = 2; x <= 6; x++) {
			s.rack(x, 1, 2, S, "tensor_accelerator:3,gpu_blade:2,failed_module:1");
			s.rack(x, 1, 6, N, "tensor_accelerator:3,gpu_blade:2,failed_module:1");
		}
		s.machine(7, 1, 2, "uplink_router", S);
		s.machine(7, 1, 6, "uplink_router", N);
		s.machine(8, 1, 4, "crac_unit", W);
		s.cableRun(2, 4, 4, 7, 4, 4, "coolant_pipe");
		s.set(1, 1, 7, b(Blocks.COBWEB));

		// Ops room: the terminal under a wall of monitors, and a chair nobody got up from.
		s.fill(15, 1, 1, 15, 5, 7, partition);
		s.fill(15, 1, 7, x1 - 1, 5, 7, partition);
		s.fill(19, 1, 7, 19, 2, 7, AIR);
		s.machine(19, 1, 1, "operations_terminal", S);
		for (int x = 17; x <= 21; x++) if (x != 19) s.machine(x, 2, 1, "monitoring_wall", S);
		s.machine(19, 2, 1, "monitoring_wall", S);
		s.machine(17, 1, 1, "facility_controller", S);
		s.set(19, 1, 3, stairs(Blocks.QUARTZ_STAIRS, S));
		s.chest(23, 1, 6, W, AI_LAB_LOOT);

		// Reception by the door, and the lab benches in the middle: coffee, cake, unfinished experiments.
		s.fill(11, 1, 3, 14, 1, 3, b(Blocks.QUARTZ_SLAB));
		s.set(11, 2, 3, b(Blocks.POTTED_FERN));
		for (int x = 11; x <= 13; x++) s.set(x, 1, 11, b(Blocks.CRAFTING_TABLE));
		s.set(11, 2, 11, b(Blocks.BREWING_STAND));
		s.set(13, 2, 11, b(Blocks.CAKE));
		s.set(12, 1, 12, stairs(Blocks.QUARTZ_STAIRS, N));

		// Training wing: three Scriptorium Desks, a chained librarian at each.
		for (int x : new int[] {16, 19, 22}) {
			s.machine(x, 1, 11, "writing_desk", S, new ItemStack(Items.PAPER, 24), new ItemStack(Items.INK_SAC, 3));
			shackledLibrarian(s, x, 1, 12, x, 1, 11);
		}
		s.fill(15, 1, 14, 23, 3, 16, (x, y, z) -> z == 16 && y <= 2 && x % 2 == 0 ? b(Blocks.BOOKSHELF) : null);

		// The crayon corner: a fenced playroom of kids at two art tables.
		s.fill(1, 0, 11, 8, 0, 16, (x, y, z) -> (x + z) % 2 == 0 ? b(Blocks.YELLOW_WOOL) : b(Blocks.LIGHT_BLUE_WOOL));
		s.fill(9, 1, 10, 9, 1, 16, run(Blocks.OAK_FENCE, false));
		s.fill(1, 1, 10, 8, 1, 10, run(Blocks.OAK_FENCE, true));
		s.set(9, 1, 10, b(Blocks.OAK_FENCE).with(Properties.WEST, true).with(Properties.SOUTH, true));
		s.set(5, 1, 10, b(Blocks.OAK_FENCE_GATE));
		s.machine(3, 1, 12, "art_table", S, new ItemStack(Items.PAPER, 16), Site.item("crayons", 1));
		s.machine(6, 1, 12, "art_table", S, new ItemStack(Items.PAPER, 16), Site.item("crayons", 1));
		for (int[] kid : new int[][] {{2, 14}, {4, 15}, {6, 14}, {7, 16}}) kid(s, kid[0], 1, kid[1]);
		s.chest(1, 1, 16, E, AI_LAB_LOOT);
		s.sign(6, 2, 16, N, "CRAYON CORNER", "", "Training data", "in progress");

		for (int[] lamp : new int[][] {{4, 4}, {12, 6}, {12, 14}, {19, 4}, {19, 13}, {4, 13}}) {
			s.set(lamp[0], 5, lamp[1], hangingLantern());
		}
	}

	/**
	 * A timber mill turned data-labelling sweatshop, 27 x 19 under a pitched roof. The north hall is six
	 * Scriptorium Desks against the bookshelves, a chained librarian at each; the south is a fenced playroom
	 * of kids at three art tables; the middle manager has the office in the corner, with the racks.
	 */
	private static void contentMill(Site s) {
		int x1 = 26;
		int z1 = 18;
		s.pad(0, 0, x1, z1, 8, 6, b(Blocks.COBBLESTONE));
		s.fill(0, 0, 0, x1, 0, z1, (x, y, z) -> x == 0 || z == 0 || x == x1 || z == z1 ? b(Blocks.COBBLESTONE)
				: s.chance(x, y, z, 1, 5) ? b(Blocks.DARK_OAK_PLANKS) : b(Blocks.SPRUCE_PLANKS));
		s.walls(0, 1, 0, x1, 4, z1, (x, y, z) -> {
			boolean post = (x % 6 == 0 && (z == 0 || z == z1)) || ((x == 0 || x == x1) && z % 6 == 0);
			if (post) return b(Blocks.STRIPPED_SPRUCE_LOG);
			if (y == 1) return b(Blocks.COBBLESTONE);
			if (y == 3 && (z == 0 || z == z1) && x % 6 == 3) return b(Blocks.GLASS_PANE).with(Properties.EAST, true).with(Properties.WEST, true);
			if (y == 3 && (x == 0 || x == x1) && z % 6 == 3) return b(Blocks.GLASS_PANE).with(Properties.NORTH, true).with(Properties.SOUTH, true);
			return b(Blocks.WHITE_TERRACOTTA);
		});
		// A pitched roof along the length, with gable ends.
		for (int z = 0; z <= z1; z++) {
			int ridge = Math.min(z, z1 - z) / 3;
			int h = 5 + ridge;
			BlockState roof = z == z1 / 2 ? b(Blocks.DARK_OAK_PLANKS) : stairs(Blocks.DARK_OAK_STAIRS, z < z1 / 2 ? S : N);
			s.fill(0, h, z, x1, h, z, roof);
			if (h > 5) {
				s.fill(0, 5, z, 0, h - 1, z, b(Blocks.SPRUCE_PLANKS));
				s.fill(x1, 5, z, x1, h - 1, z, b(Blocks.SPRUCE_PLANKS));
			}
		}
		// The way in is a fence gate: players can open it, the kids can't.
		s.set(13, 1, z1, b(Blocks.SPRUCE_FENCE_GATE));
		s.set(13, 2, z1, AIR);
		s.sign(12, 2, z1 - 1, N, "ACME CONTENT", "MILL", "", "Now hiring");

		// The scriptorium hall: bookshelves, and six desks facing each other across the aisle.
		s.fill(1, 1, 1, x1 - 1, 3, 1, b(Blocks.BOOKSHELF));
		for (int x : new int[] {5, 13, 21}) {
			s.machine(x, 1, 2, "writing_desk", S, new ItemStack(Items.PAPER, 32), new ItemStack(Items.INK_SAC, 4));
			shackledLibrarian(s, x, 1, 3, x, 1, 2);
			s.machine(x, 1, 7, "writing_desk", N, new ItemStack(Items.PAPER, 32), new ItemStack(Items.INK_SAC, 4));
			shackledLibrarian(s, x, 1, 6, x, 1, 7);
		}
		for (int x = 3; x <= x1 - 3; x += 4) s.set(x, 4, 1, b(Blocks.LANTERN));
		s.sign(9, 3, 2, S, "DAYS WITHOUT", "A BREAK:", "", "ALL OF THEM");

		// The playroom: fenced off from the hall, kids at three stocked art tables.
		s.fill(1, 1, 9, 20, 1, 9, run(Blocks.SPRUCE_FENCE, true));
		s.set(13, 1, 9, b(Blocks.SPRUCE_FENCE_GATE));
		s.fill(1, 0, 10, 20, 0, 17, (x, y, z) -> (x / 2 + z / 2) % 2 == 0 ? b(Blocks.RED_WOOL) : b(Blocks.WHITE_WOOL));
		for (int x : new int[] {4, 10, 16}) s.machine(x, 1, 13, "art_table", S, new ItemStack(Items.PAPER, 24), Site.item("crayons", 1));
		for (int[] kid : new int[][] {{3, 15}, {5, 16}, {9, 15}, {11, 16}, {15, 15}, {17, 16}}) kid(s, kid[0], 1, kid[1]);
		for (int x = 2; x <= 20; x += 6) s.set(x, 2, 9, b(Blocks.LANTERN));
		s.set(1, 1, 17, b(Blocks.HAY_BLOCK));
		s.set(2, 1, 17, b(Blocks.NOTE_BLOCK));

		// The office, glassed off in the corner: the racks, the terminal and the manager.
		s.fill(21, 1, 9, 21, 4, z1 - 1, partitionGlass());
		s.rack(x1 - 1, 1, 11, W, "tensor_accelerator:3,gpu_blade:1,server_1u:2");
		s.rack(x1 - 1, 1, 12, W, "tensor_accelerator:3,gpu_blade:1,server_1u:2");
		s.machine(x1 - 1, 1, 13, "uplink_router", W);
		s.machine(x1 - 1, 1, 15, "operations_terminal", W);
		s.set(23, 1, 15, stairs(Blocks.SPRUCE_STAIRS, W));
		s.villager(23, 1, 16, villager -> {
			villager.setVillagerData(villager.getVillagerData().withProfession(VillagerProfession.NITWIT));
			villager.setCustomName(net.minecraft.text.Text.literal("Middle Manager"));
			villager.setCustomNameVisible(true);
		});
		s.chest(22, 1, z1 - 1, N, MILL_LOOT);
		s.set(24, 3, 14, b(Blocks.LANTERN));
		s.set(24, 2, 14, b(Blocks.SPRUCE_FENCE));
	}

	private static Site.Material partitionGlass() {
		return (x, y, z) -> y == 1 || y == 4 ? b(Blocks.STRIPPED_SPRUCE_LOG)
				: b(Blocks.GLASS_PANE).with(Properties.NORTH, true).with(Properties.SOUTH, true);
	}

	/** A librarian standing at (x, y, z), shackled to the Scriptorium Desk at (deskX, deskY, deskZ). */
	private static void shackledLibrarian(Site s, int x, int y, int z, int deskX, int deskY, int deskZ) {
		var scribe = s.villager(x, y, z, villager -> {
			villager.setVillagerData(villager.getVillagerData().withProfession(VillagerProfession.LIBRARIAN));
			villager.setExperience(1);
			villager.addCommandTag(TrainingStations.SHACKLED_TAG);
		});
		MachineBlockEntity desk = s.machineAt(deskX, deskY, deskZ);
		if (scribe != null && desk != null) desk.setBoundVillager(scribe.getUuid());
	}

	/** A baby villager; a stocked art table nearby keeps them busy (and young). */
	private static void kid(Site s, int x, int y, int z) {
		s.villager(x, y, z, villager -> villager.setBaby(true));
	}

	private static void solarFarm(Site s) {
		s.pad(0, 0, 22, 14, 5, 4, b(Blocks.DIRT));
		s.fill(0, 0, 0, 22, 0, 14, b(Blocks.GRASS_BLOCK));
		s.fill(0, 1, 0, 22, 1, 0, run(Blocks.OAK_FENCE, true));
		s.fill(0, 1, 14, 22, 1, 14, run(Blocks.OAK_FENCE, true));
		s.fill(0, 1, 1, 0, 1, 13, run(Blocks.OAK_FENCE, false));
		s.fill(22, 1, 1, 22, 1, 13, run(Blocks.OAK_FENCE, false));
		s.set(11, 1, 14, b(Blocks.OAK_FENCE_GATE));
		for (int z : new int[] {2, 5, 8, 11}) {
			s.fill(2, 0, z, 15, 0, z, b(Blocks.GRAVEL));
			for (int x = 2; x <= 15; x++) {
				if (s.chance(x, 1, z, 1, 6)) s.set(x, 1, z, s.chance(x, 1, z, 2, 2) ? b(Blocks.COBWEB) : b(Blocks.SMOOTH_STONE_SLAB));
				else s.machine(x, 1, z, "solar_panel", S);
			}
		}
		s.cableRun(16, 1, 2, 16, 1, 11, "power_cable");
		s.cable(16, 1, 6, "power_cable", true);
		// Control hut.
		s.walls(17, 1, 2, 21, 3, 7, s::weathered);
		s.fill(17, 4, 2, 21, 4, 7, b(Blocks.STONE_BRICK_SLAB));
		s.fill(18, 1, 3, 20, 3, 6, AIR);
		s.fill(19, 1, 7, 19, 2, 7, AIR);
		s.cable(17, 1, 3, "power_cable", false);
		s.machine(18, 1, 3, "battery_bank", S);
		s.machine(19, 1, 3, "battery_bank", S);
		s.machine(20, 1, 3, "pdu", S);
		s.cable(20, 1, 4, "power_cable", false);
		s.rack(20, 1, 5, W, "server_1u:3,pi_node:2");
		s.machine(20, 1, 6, "uplink_router", W);
		s.chest(18, 1, 6, E, COMMON_LOOT);
	}

	private static void tapeArchive(Site s) {
		s.pad(0, 0, 12, 10, 7, 6, b(Blocks.STONE));
		s.fill(0, 0, 0, 12, 0, 10, (x, y, z) -> s.chance(x, y, z, 1, 3) ? b(Blocks.STONE_BRICKS) : b(Blocks.DARK_OAK_PLANKS));
		s.walls(0, 1, 0, 12, 4, 10, s::weathered);
		s.fill(0, 5, 0, 12, 5, 10, (x, y, z) -> s.chance(x, y, z, 2, 10) && x > 0 && x < 12 && z > 0 && z < 10 ? null : b(Blocks.STONE_BRICKS));
		s.fill(1, 1, 1, 1, 3, 9, b(Blocks.BOOKSHELF));
		s.fill(11, 1, 1, 11, 3, 9, b(Blocks.BOOKSHELF));
		s.fill(6, 1, 0, 6, 2, 0, AIR);
		for (int z : new int[] {3, 5, 7}) s.machine(11, 1, z, "tape_library", W, Site.item("tape_cartridge", 1));
		s.machine(1, 1, 4, "storage_array", E, Site.item("drive_1k", 1), Site.item("drive_4k", 1));
		s.machine(1, 1, 6, "storage_array", E, Site.item("drive_1k", 1));
		s.machine(6, 1, 9, "storage_terminal", N);
		s.set(4, 1, 4, b(Blocks.LECTERN));
		s.set(8, 1, 4, b(Blocks.LECTERN));
		s.fill(2, 3, 1, 10, 4, 9, (x, y, z) -> s.get(x, y, z).isAir() && s.chance(x, y, z, 3, 6) ? b(Blocks.COBWEB) : null);
		s.chest(5, 1, 9, N, ARCHIVE_LOOT);
	}

	// ---------------------------------------------------------------- the hyperscale campus

	/**
	 * The hyperscale campus is not a ruin: everything works, and the only thing keeping it dark is five cut
	 * cables (the reactor's output, the feeders to hall B and the quantum vault, and fiber in halls A and D).
	 * Repair kits wait in the guard hut by the gate. A 3x3x3 reactor array runs the lot; each hall's racks have
	 * rear-door coolers and a chiller bank; the quantum vault's loop goes to cooling towers fed by the reservoir.
	 */
	private static void campus(Site s) {
		int size = CAMPUS_SIZE;
		int last = size - 1;
		s.pad(0, 0, last, last, 23, 12, b(Blocks.DIRT));
		s.fill(0, 0, 0, last, 0, last, (x, y, z) -> s.chance(x, y, z, 1, 11) ? b(Blocks.COARSE_DIRT) : b(Blocks.GRASS_BLOCK));
		// Perimeter fence with brick posts and a gate in the south side.
		s.fill(1, 1, 1, last - 1, 2, 1, run(Blocks.IRON_BARS, true));
		s.fill(1, 1, last - 1, last - 1, 2, last - 1, run(Blocks.IRON_BARS, true));
		s.fill(1, 1, 2, 1, 2, last - 2, run(Blocks.IRON_BARS, false));
		s.fill(last - 1, 1, 2, last - 1, 2, last - 2, run(Blocks.IRON_BARS, false));
		for (int i = 1; i <= last - 1; i += 8) {
			s.fill(i, 1, 1, i, 3, 1, b(Blocks.STONE_BRICKS));
			s.fill(i, 1, last - 1, i, 3, last - 1, b(Blocks.STONE_BRICKS));
			s.fill(1, 1, i, 1, 3, i, b(Blocks.STONE_BRICKS));
			s.fill(last - 1, 1, i, last - 1, 3, i, b(Blocks.STONE_BRICKS));
		}
		s.fill(84, 1, last - 1, 91, 3, last - 1, AIR);
		// Roads: a ring, a cross road, a north-south avenue and the drive in from the gate.
		Site.Material asphalt = (x, y, z) -> s.chance(x, y, z, 6, 40) ? b(Blocks.ANDESITE) : b(Blocks.GRAY_CONCRETE);
		s.fill(3, 0, 3, last - 3, 0, 5, asphalt);
		s.fill(3, 0, last - 5, last - 3, 0, last - 3, asphalt);
		s.fill(3, 0, 3, 5, 0, last - 3, asphalt);
		s.fill(last - 5, 0, 3, last - 3, 0, last - 3, asphalt);
		s.fill(3, 0, 47, last - 3, 0, 50, asphalt);
		s.fill(110, 0, 3, 113, 0, last - 3, asphalt);
		s.fill(86, 0, last - 5, 89, 0, last, asphalt);

		List<String> asic = java.util.Collections.nCopies(8, "asic_miner");
		List<String> gpu = java.util.Collections.nCopies(8, "gpu_blade");
		List<String> servers = java.util.Collections.nCopies(8, "server_1u");
		List<String> ai = List.of("tensor_accelerator", "tensor_accelerator", "tensor_accelerator", "gpu_blade",
				"tensor_accelerator", "tensor_accelerator", "tensor_accelerator", "gpu_blade");
		campusHall(s, 10, 12, "A", asic, 3, 2, true);
		campusHall(s, 62, 12, "B", gpu, 6, 5, false);
		campusHall(s, 10, 58, "C", servers, 2, 1, false);
		campusHall(s, 62, 58, "D", ai, 5, 2, true);
		operationsCentre(s, 120, 12);
		carPark(s, 141, 12);
		reservoir(s, 120, 60);
		quantumVault(s, 120, 90);
		powerPlant(s, 10, 110);

		// The power trunk: down the west side from the reactor, a spine between the hall rows, and feeders.
		s.cableRun(8, 1, 15, 8, 1, 115, "power_cable");
		s.cableRun(8, 1, 54, 118, 1, 54, "power_cable");
		s.cableRun(60, 1, 15, 60, 1, 61, "power_cable");
		s.cableRun(118, 1, 54, 118, 1, 96, "power_cable");
		s.cable(60, 1, 30, "power_cable", true);
		s.cable(118, 1, 75, "power_cable", true);
		// Backup on the trunk: the substation's utility feeds, diesel generators with coke, and batteries.
		for (int z = 70; z <= 73; z++) s.machine(7, 1, z, "utility_intake", E);
		for (int z = 80; z <= 83; z++) s.machine(7, 1, z, "diesel_generator", E, new ItemStack(RcItems.ITEMS.get("coke"), 32));
		for (int z = 90; z <= 95; z++) s.machine(7, 1, z, "battery_bank", E);

		guardHut(s, 94, 160);
		// Nature takes the rest back, a little.
		s.fill(0, 1, 0, last, 1, last, (x, y, z) -> s.get(x, 0, z).isOf(Blocks.GRASS_BLOCK) && s.get(x, 1, z).isAir()
				&& s.chance(x, y, z, 8, 7) ? (s.chance(x, y, z, 9, 5) ? b(Blocks.FERN) : b(Blocks.GRASS)) : null);
	}

	/**
	 * A 40 x 30 data hall, in working order. Two pairs of rack rows, twelve racks each, back to back with a
	 * double row of Rear-Door Coolers between them, so all of the heat goes into the coolant loop and none into
	 * the room. Power comes in on the west column, the coolant column runs down the east end to a chiller bank
	 * along the north wall, fiber runs over every row to the core routers.
	 */
	private static void campusHall(Site s, int x0, int z0, String name, List<String> bays, int chillers, int routers, boolean cutFiber) {
		int x1 = x0 + 39;
		int z1 = z0 + 29;
		int xs = x0 + 10;
		int xe = xs + 11;
		s.fill(x0, 0, z0, x1, 0, z1, (x, y, z) -> x == x0 || x == x1 || z == z0 || z == z1
				? b(Blocks.POLISHED_ANDESITE) : b(dev.rackcraft.RcBlocks.get("raised_floor_tile")));
		s.walls(x0, 1, z0, x1, 7, z1, (x, y, z) -> y == 7 ? b(Blocks.GRAY_CONCRETE) : b(Blocks.LIGHT_GRAY_CONCRETE));
		s.fill(x0, 8, z0, x1, 8, z1, b(Blocks.GRAY_CONCRETE));
		s.fill(x0 + 18, 1, z1, x0 + 21, 3, z1, AIR);
		s.sign(x0 + 17, 3, z1 + 1, S, "DATA HALL " + name, bays.get(0).replace('_', ' ').toUpperCase(java.util.Locale.ROOT));
		int[] rows = {z0 + 8, z0 + 11, z0 + 14, z0 + 17};
		Direction[] faces = {N, S, N, S};
		for (int row = 0; row < rows.length; row++) {
			for (int x = xs; x <= xe; x++) {
				s.fullRack(x, 1, rows[row], faces[row], bays);
				// Rear-Door Coolers fill the hot aisle between each pair of rows.
				s.machine(x, 1, rows[row] + (faces[row] == N ? 1 : -1), "rear_door_cooler", faces[row]);
			}
			s.cableRun(xs, 2, rows[row], xe + 1, 2, rows[row], "fiber_cable");
		}
		for (int i = 0; i < chillers; i++) s.machine(xe - i, 1, z0 + 4, "chiller", S);
		s.cableRun(x0 - 2, 1, z0 + 3, xe, 1, z0 + 3, "power_cable");
		s.cableRun(xs - 1, 1, z0 + 3, xs - 1, 1, rows[3], "power_cable");
		s.cableRun(xe + 1, 1, z0 + 4, xe + 1, 1, rows[3], "coolant_pipe");
		s.cableRun(xe + 1, 2, rows[0], xe + 1, 2, rows[3], "fiber_cable");
		for (int i = 0; i < routers; i++) {
			s.machine(xe + 2, 1, rows[0] + i, "core_router", W);
			s.cable(xe + 2, 2, rows[0] + i, "fiber_cable", false);
		}
		if (cutFiber) s.cable(xe + 1, 2, rows[2] - 1, "fiber_cable", true);
		for (int x = x0 + 4; x <= x1 - 4; x += 6) {
			for (int z = z0 + 4; z <= z1 - 4; z += 6) s.set(x, 7, z, hangingLantern());
		}
		// A desk by the door for whoever was on shift.
		s.set(x0 + 30, 1, z1 - 3, b(Blocks.CRAFTING_TABLE));
		s.machine(x0 + 31, 1, z1 - 3, "monitoring_wall", N);
		s.set(x0 + 30, 1, z1 - 2, stairs(Blocks.OAK_STAIRS, N));
		s.chest(x1 - 2, 1, z1 - 1, W, COMMON_LOOT);
	}

	/**
	 * The quantum vault: one row of eight Quantum Core racks, each beside a CDU, with Rear-Door Coolers behind.
	 * Its loop runs out to six cooling towers, and two pumps on the reservoir keep the towers in water.
	 */
	private static void quantumVault(Site s, int x0, int z0) {
		int x1 = x0 + 21;
		int z1 = z0 + 13;
		s.fill(x0, 0, z0, x1, 0, z1, b(Blocks.POLISHED_DEEPSLATE));
		s.walls(x0, 1, z0, x1, 6, z1, (x, y, z) -> y == 3 && (x + z) % 4 == 0 ? b(Blocks.TINTED_GLASS) : b(Blocks.DEEPSLATE_TILES));
		s.fill(x0, 7, z0, x1, 7, z1, b(Blocks.DEEPSLATE_TILES));
		s.fill(x0 + 10, 1, z0, x0 + 11, 2, z0, AIR);
		s.sign(x0 + 9, 3, z0 - 1, N, "QUANTUM VAULT", "Do not observe", "the qubits");
		int z = z0 + 6;
		int xe = x0 + 15;
		for (int i = 0; i < 12; i++) {
			int x = x0 + 4 + i;
			if (i % 3 == 1) {
				s.machine(x, 1, z, "cdu", N);
				continue;
			}
			s.fullRack(x, 1, z, N, java.util.Collections.nCopies(8, "quantum_core"));
			s.machine(x, 1, z + 1, "rear_door_cooler", N);
		}
		s.cableRun(x0 - 2, 1, z, x0 + 3, 1, z, "power_cable");
		s.cableRun(x0 + 4, 2, z, xe + 1, 2, z, "fiber_cable");
		for (int i = 0; i < 4; i++) s.machine(xe + 2 + i, 2, z, "core_router", N);
		// Coolant out through the east wall to the towers, power alongside.
		s.cableRun(xe + 1, 1, z, x1 + 2, 1, z, "coolant_pipe");
		s.cableRun(xe + 1, 1, z + 1, x1 + 8, 1, z + 1, "power_cable");
		for (int i = 0; i < 6; i++) s.machine(x1 + 3 + i, 1, z, "cooling_tower", N);
		for (int x = x0 + 3; x <= x1 - 3; x += 5) s.set(x, 6, z0 + 3, hangingLantern());
		s.chest(x1 - 2, 1, z1 - 2, W, VAULT_LOOT);
	}

	/** A 3x3x3 Modular Reactor array with spare Fuel Cells, its own chiller bank, and its output cable cut. */
	private static void powerPlant(Site s, int x0, int z0) {
		s.fill(x0, 0, z0, x0 + 30, 0, z0 + 16, b(Blocks.SMOOTH_STONE));
		s.walls(x0, 1, z0, x0 + 30, 2, z0 + 16, (x, y, z) -> run(Blocks.IRON_BARS, z == z0 || z == z0 + 16));
		s.fill(x0 + 14, 1, z0 + 16, x0 + 16, 2, z0 + 16, AIR);
		s.fill(x0 + 13, 1, z0 + 16, x0 + 13, 2, z0 + 16, b(Blocks.STONE_BRICKS));
		s.sign(x0 + 13, 2, z0 + 17, S, "REACTOR ARRAY", "27 cores, 13.5 MW", "Output cable: CUT");
		int rx = x0 + 2;
		int rz = z0 + 4;
		for (int dx = 0; dx < 3; dx++) {
			for (int dy = 0; dy < 3; dy++) {
				for (int dz = 0; dz < 3; dz++) {
					s.machine(rx + dx, 1 + dy, rz + dz, "modular_reactor", S, new ItemStack(RcItems.ITEMS.get("fuel_cell"), 6));
				}
			}
		}
		// Output west to the trunk (cut), coolant east to the chillers, chiller power alongside.
		s.cableRun(x0 - 2, 1, rz + 1, rx - 1, 1, rz + 1, "power_cable");
		s.cable(x0, 1, rz + 1, "power_cable", true);
		s.cable(rx + 3, 1, rz + 1, "coolant_pipe", false);
		for (int i = 0; i < 7; i++) s.machine(rx + 4 + i, 1, rz + 1, "chiller", N);
		s.cableRun(rx + 3, 1, rz + 2, rx + 10, 1, rz + 2, "power_cable");
		s.chest(x0 + 28, 1, z0 + 2, W, COMMON_LOOT);
	}

	/** By the gate: two Repair Kits, a note, and a chair nobody has sat in for a while. */
	private static void guardHut(Site s, int x0, int z0) {
		s.fill(x0, 0, z0, x0 + 6, 0, z0 + 5, b(Blocks.SMOOTH_STONE));
		s.walls(x0, 1, z0, x0 + 6, 3, z0 + 5, (x, y, z) -> y == 2 && (x == x0 + 3 || z == z0 + 2) ? b(Blocks.GLASS) : b(Blocks.WHITE_CONCRETE));
		s.fill(x0, 4, z0, x0 + 6, 4, z0 + 5, b(Blocks.SMOOTH_STONE));
		door(s, x0 + 3, 1, z0 + 5, Blocks.OAK_DOOR, N);
		s.chestWith(x0 + 1, 1, z0 + 1, S, Site.item("repair_kit", 1), Site.item("repair_kit", 1), Site.item("multimeter", 1),
				Site.item("fuel_cell", 16), new ItemStack(Items.BREAD, 6));
		s.sign(x0 + 2, 2, z0 + 1, S, "SHIFT NOTE:", "5 cables cut in", "the storm. All else", "nominal. Kits here.");
		s.set(x0 + 5, 1, z0 + 1, stairs(Blocks.OAK_STAIRS, W));
	}

	/** Two-storey operations centre: the NOC downstairs, the "AI wing" upstairs. */
	private static void operationsCentre(Site s, int x0, int z0) {
		int x1 = x0 + 17;
		int z1 = z0 + 21;
		s.fill(x0, 0, z0, x1, 0, z1, b(Blocks.POLISHED_DEEPSLATE));
		s.walls(x0, 1, z0, x1, 9, z1, (x, y, z) -> (y == 2 || y == 3 || y == 7 || y == 8) && (x + z) % 3 != 0
				? b(Blocks.LIGHT_BLUE_STAINED_GLASS) : b(Blocks.SMOOTH_STONE));
		s.fill(x0 + 1, 5, z0 + 1, x1 - 1, 5, z1 - 1, b(Blocks.SMOOTH_STONE));
		s.fill(x0, 10, z0, x1, 10, z1, b(Blocks.GRAY_CONCRETE));
		door(s, x0 + 8, 1, z1, Blocks.IRON_DOOR, N);
		door(s, x0 + 9, 1, z1, Blocks.IRON_DOOR, N);
		// The NOC wall, the controller and the operations terminal.
		for (int x = x0 + 2; x <= x1 - 2; x++) {
			s.machine(x, 1, z0 + 1, "monitoring_wall", S);
			s.machine(x, 2, z0 + 1, "monitoring_wall", S);
		}
		s.machine(x0 + 8, 1, z0 + 5, "facility_controller", S);
		s.machine(x0 + 9, 1, z0 + 5, "operations_terminal", S);
		for (int x = x0 + 3; x <= x1 - 3; x += 3) {
			s.set(x, 1, z0 + 9, b(Blocks.CRAFTING_TABLE));
			s.set(x, 1, z0 + 10, stairs(Blocks.OAK_STAIRS, S));
		}
		s.chest(x0 + 1, 1, z1 - 1, E, OFFICE_LOOT);
		s.chest(x1 - 1, 1, z1 - 1, W, OFFICE_LOOT);
		// Ladder to the upper floor.
		s.set(x1 - 1, 5, z0 + 12, AIR);
		for (int y = 1; y <= 5; y++) s.set(x1 - 1, y, z0 + 12, b(Blocks.LADDER).with(LadderBlock.FACING, W));
		// Upstairs: the AI wing. Two art tables, two desks, two librarians who would rather be anywhere else.
		s.machine(x0 + 3, 6, z0 + 4, "art_table", S, new ItemStack(Items.PAPER, 32), Site.item("crayons", 1));
		s.machine(x0 + 6, 6, z0 + 4, "art_table", S, new ItemStack(Items.PAPER, 32), Site.item("crayons", 1));
		for (int desk = 0; desk < 2; desk++) {
			int x = x0 + 3 + desk * 5;
			int z = z1 - 3;
			s.machine(x, 6, z, "writing_desk", N, new ItemStack(Items.PAPER, 24), new ItemStack(Items.INK_SAC, 3));
			var scribe = s.villager(x, 6, z - 1, villager -> {
				villager.setVillagerData(villager.getVillagerData().withProfession(VillagerProfession.LIBRARIAN));
				villager.setExperience(1);
				villager.addCommandTag(TrainingStations.SHACKLED_TAG);
			});
			MachineBlockEntity entity = s.machineAt(x, 6, z);
			if (scribe != null && entity != null) entity.setBoundVillager(scribe.getUuid());
		}
		s.chest(x1 - 2, 6, z1 - 2, W, AI_LAB_LOOT);
		// On the roof: panels and an antenna.
		for (int x = x0 + 2; x <= x1 - 2; x += 2) s.machine(x, 11, z0 + 2, "solar_panel", S);
		s.machine(x0 + 9, 11, z0 + 12, "wireless_transmitter", S);
	}

	/** The reservoir, 22 x 24 and three deep. Two pumps on its east bank keep the quantum vault's cooling towers in water. */
	private static void reservoir(Site s, int x0, int z0) {
		int x1 = x0 + 21;
		int z1 = z0 + 23;
		s.fill(x0, -3, z0, x1, 0, z1, b(Blocks.STONE_BRICKS));
		s.fill(x0 + 1, -2, z0 + 1, x1 - 1, 0, z1 - 1, b(Blocks.WATER));
		for (int z : new int[] {z0 + 10, z0 + 16}) {
			s.machine(x1 - 1, 1, z, "freshwater_pump", E);
			s.cableRun(x1, 1, z, x1 + 2, 1, z, "coolant_pipe");
		}
		// Down the east side to the vault's tower line.
		s.cableRun(x1 + 2, 1, z0 + 10, x1 + 2, 1, z0 + 35, "coolant_pipe");
		// Pump power: over the pumps, then down beside the pipe to the towers' supply.
		s.cableRun(x1 - 1, 2, z0 + 10, x1 - 1, 2, z0 + 16, "power_cable");
		s.cableRun(x1, 2, z0 + 10, x1 + 3, 2, z0 + 10, "power_cable");
		s.cableRun(x1 + 3, 1, z0 + 10, x1 + 3, 1, z0 + 35, "power_cable");
		s.fill(x0 + 1, 1, z0 + 1, x1 - 1, 1, z1 - 1, (x, y, z) -> s.get(x, 0, z).isOf(Blocks.WATER) && s.chance(x, y, z, 1, 14)
				? b(Blocks.LILY_PAD) : null);
	}

	/** Asphalt, faded lines, and a few cars nobody came back for. */
	private static void carPark(Site s, int x0, int z0) {
		s.fill(x0, 0, z0, x0 + 17, 0, z0 + 30, (x, y, z) -> x >= x0 + 7 && x <= x0 + 10 ? null
				: (z - z0) % 5 == 0 && x != x0 + 6 && x != x0 + 11 ? b(Blocks.WHITE_CONCRETE) : b(Blocks.BLACK_CONCRETE));
		List<Block> paint = List.of(Blocks.RED_CONCRETE, Blocks.BLUE_CONCRETE, Blocks.WHITE_CONCRETE, Blocks.YELLOW_CONCRETE,
				Blocks.LIME_CONCRETE, Blocks.CYAN_CONCRETE);
		int car = 0;
		for (int z = z0 + 1; z + 3 < z0 + 30; z += 5) {
			for (int x : new int[] {x0 + 2, x0 + 13}) {
				if (s.chance(x, 0, z, 2, 3)) continue;
				Block body = paint.get(car++ % paint.size());
				s.fill(x, 1, z, x + 1, 1, z + 3, b(body));
				s.fill(x, 2, z + 1, x + 1, 2, z + 2, b(Blocks.TINTED_GLASS));
			}
		}
	}
}
