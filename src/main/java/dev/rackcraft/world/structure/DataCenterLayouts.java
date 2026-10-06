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
 *   <li>{@code ai_lab}: a research lab, still with its librarian chained to the desk.</li>
 *   <li>{@code solar_farm}: rows of panels and a control hut.</li>
 *   <li>{@code tape_archive}: a cobwebbed vault of tape libraries.</li>
 *   <li>{@code hyperscale_campus}: 112 x 112 blocks: four data halls, an operations centre, a reservoir,
 *       cooling towers, a substation, a generator yard and a car park.</li>
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
		add(new Layout("ai_lab", 17, 7, 13, Placement.SURFACE, 0, 4, false, false, DataCenterLayouts::aiLab));
		add(new Layout("solar_farm", 23, 5, 15, Placement.SURFACE, 0, 4, false, false, DataCenterLayouts::solarFarm));
		add(new Layout("tape_archive", 13, 7, 11, Placement.SURFACE, 0, 4, false, false, DataCenterLayouts::tapeArchive));
		add(new Layout("hyperscale_campus", 112, 24, 112, Placement.SURFACE, 0, 14, false, true, DataCenterLayouts::campus));
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

	private static void aiLab(Site s) {
		s.pad(0, 0, 16, 12, 7, 6, b(Blocks.STONE));
		s.fill(0, 0, 0, 16, 0, 12, (x, y, z) -> s.chance(x, y, z, 1, 6) ? b(Blocks.POLISHED_DIORITE) : b(Blocks.SMOOTH_QUARTZ));
		s.walls(0, 1, 0, 16, 4, 12, (x, y, z) -> (z == 0 || z == 12) && (y == 2 || y == 3) && x % 3 == 1
				? b(Blocks.GLASS_PANE).with(Properties.EAST, true).with(Properties.WEST, true) : b(Blocks.WHITE_CONCRETE));
		s.fill(0, 5, 0, 16, 5, 12, (x, y, z) -> x % 5 == 2 && z % 4 == 2 ? b(Blocks.GLASS) : b(Blocks.LIGHT_GRAY_CONCRETE));
		s.fill(8, 1, 0, 8, 2, 0, AIR);
		// "AGI" on the west wall, in the hand of someone who believed it.
		String[][] letters = {
				{" # ", "# #", "###", "# #"}, {"###", "#  ", "# #", "###"}, {"###", " # ", " # ", "###"}};
		for (int letter = 0; letter < letters.length; letter++) {
			for (int row = 0; row < 4; row++) {
				for (int column = 0; column < 3; column++) {
					if (letters[letter][row].charAt(column) != '#') continue;
					s.set(0, 4 - row, 11 - letter * 4 - column, b(Blocks.BLACK_CONCRETE));
				}
			}
		}
		// Server corner.
		for (int x = 2; x <= 4; x++) s.rack(x, 1, 10, N, "tensor_accelerator:3,gpu_blade:2,failed_module:1");
		s.machine(5, 1, 10, "uplink_router", N);
		s.machine(8, 1, 11, "operations_terminal", N);
		// Training corner: the art table is stocked; the librarian is still at the desk.
		s.machine(12, 1, 3, "art_table", S, new ItemStack(Items.PAPER, 12), Site.item("crayons", 1));
		s.machine(15, 1, 7, "writing_desk", W, new ItemStack(Items.PAPER, 20), new ItemStack(Items.INK_SAC, 2),
				Site.item("text_corpus", 1));
		var scribe = s.villager(14, 1, 7, villager -> {
			villager.setVillagerData(villager.getVillagerData().withProfession(VillagerProfession.LIBRARIAN));
			villager.setExperience(1);
			villager.addCommandTag(TrainingStations.SHACKLED_TAG);
		});
		MachineBlockEntity desk = s.machineAt(15, 1, 7);
		if (scribe != null && desk != null) desk.setBoundVillager(scribe.getUuid());
		s.set(10, 1, 6, stairs(Blocks.QUARTZ_STAIRS, E));
		s.set(11, 1, 6, b(Blocks.WHITE_CARPET));
		s.chest(15, 1, 11, W, AI_LAB_LOOT);
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

	private static void campus(Site s) {
		int size = 112;
		s.pad(0, 0, size - 1, size - 1, 23, 12, b(Blocks.DIRT));
		s.fill(0, 0, 0, size - 1, 0, size - 1, (x, y, z) -> s.chance(x, y, z, 1, 11) ? b(Blocks.COARSE_DIRT) : b(Blocks.GRASS_BLOCK));
		// Perimeter fence with brick posts and a gate in the south side.
		s.fill(1, 1, 1, 110, 2, 1, run(Blocks.IRON_BARS, true));
		s.fill(1, 1, 110, 110, 2, 110, run(Blocks.IRON_BARS, true));
		s.fill(1, 1, 2, 1, 2, 109, run(Blocks.IRON_BARS, false));
		s.fill(110, 1, 2, 110, 2, 109, run(Blocks.IRON_BARS, false));
		for (int i = 1; i <= 110; i += 8) {
			s.fill(i, 1, 1, i, 3, 1, b(Blocks.STONE_BRICKS));
			s.fill(i, 1, 110, i, 3, 110, b(Blocks.STONE_BRICKS));
			s.fill(1, 1, i, 1, 3, i, b(Blocks.STONE_BRICKS));
			s.fill(110, 1, i, 110, 3, i, b(Blocks.STONE_BRICKS));
		}
		s.fill(52, 1, 110, 59, 3, 110, AIR);
		// Roads: a ring, a cross road and the drive in from the gate.
		Site.Material asphalt = (x, y, z) -> s.chance(x, y, z, 6, 12) ? b(Blocks.GRAVEL)
				: s.chance(x, y, z, 7, 8) ? b(Blocks.ANDESITE) : b(Blocks.GRAY_CONCRETE);
		s.fill(4, 0, 4, 107, 0, 6, asphalt);
		s.fill(4, 0, 105, 107, 0, 107, asphalt);
		s.fill(4, 0, 4, 6, 0, 107, asphalt);
		s.fill(105, 0, 4, 107, 0, 107, asphalt);
		s.fill(4, 0, 64, 107, 0, 66, asphalt);
		s.fill(54, 0, 66, 57, 0, 110, asphalt);
		hall(s, 9, 9, "asic_miner:4,failed_module:2,empty:2");
		hall(s, 67, 9, "gpu_blade:3,tensor_accelerator:1,failed_module:2,empty:2");
		hall(s, 9, 38, "server_1u:4,pi_node:2,failed_module:2");
		hall(s, 67, 38, "quantum_core:1,gpu_blade:2,failed_module:3,empty:3");
		operationsCentre(s, 47, 9);
		reservoir(s, 47, 38);
		coolingYard(s, 9, 70);
		substation(s, 67, 70);
		generatorYard(s, 90, 70);
		carPark(s, 47, 70);
		// Nature takes the rest back.
		s.fill(0, 1, 0, size - 1, 1, size - 1, (x, y, z) -> s.get(x, 0, z).isOf(Blocks.GRASS_BLOCK) && s.get(x, 1, z).isAir()
				&& s.chance(x, y, z, 8, 7) ? (s.chance(x, y, z, 9, 5) ? b(Blocks.FERN) : b(Blocks.GRASS)) : null);
	}

	/** A 36 x 22 data hall: four rack rows in hot and cold aisles, CRAC units, exhaust fans and a collapsed corner. */
	private static void hall(Site s, int x0, int z0, String mix) {
		int x1 = x0 + 35;
		int z1 = z0 + 21;
		s.fill(x0, 0, z0, x1, 0, z1, (x, y, z) -> x == x0 || x == x1 || z == z0 || z == z1
				? b(Blocks.POLISHED_ANDESITE) : b(dev.rackcraft.RcBlocks.get("raised_floor_tile")));
		s.walls(x0, 1, z0, x1, 7, z1, (x, y, z) -> y >= 3 && s.chance(x, y, z, 1, 25) ? AIR
				: y == 7 ? b(Blocks.GRAY_CONCRETE) : b(Blocks.LIGHT_GRAY_CONCRETE));
		s.fill(x0, 8, z0, x1, 8, z1, (x, y, z) -> {
			boolean collapsed = x >= x0 + 20 && x <= x0 + 25 && z >= z0 + 8 && z <= z0 + 12;
			return collapsed || s.chance(x, y, z, 2, 12) && x != x0 && x != x1 && z != z0 && z != z1 ? null : b(Blocks.GRAY_CONCRETE);
		});
		s.fill(x0 + 16, 1, z0, x0 + 19, 4, z0, AIR);
		s.fill(x0 + 6, 1, z1, x0 + 6, 2, z1, AIR);
		int[] rows = {z0 + 5, z0 + 8, z0 + 13, z0 + 16};
		Direction[] faces = {N, S, N, S};
		for (int row = 0; row < rows.length; row++) {
			int z = rows[row];
			s.machine(x0 + 7, 1, z, "pdu", faces[row]);
			for (int x = x0 + 8; x <= x0 + 17; x++) {
				if (!s.chance(x, 1, z, 3, 5)) s.rack(x, 1, z, faces[row], mix);
			}
			s.machine(x0 + 18, 1, z, "uplink_router", faces[row]);
			for (int x = x0 + 20; x <= x0 + 29; x++) {
				if (!s.chance(x, 1, z, 4, 4)) s.rack(x, 1, z, faces[row], mix);
			}
			s.cableRun(x0 + 3, 1, z, x0 + 6, 1, z, "power_cable");
			if (s.chance(x0, 1, z, 5, 2)) s.cable(x0 + 5, 1, z, "power_cable", true);
		}
		for (int z : new int[] {z0 + 6, z0 + 15}) {
			s.machine(x0 + 1, 1, z, "crac_unit", E);
			s.machine(x1 - 1, 1, z, "crac_unit", W);
		}
		for (int z : new int[] {z0 + 6, z0 + 7, z0 + 14, z0 + 15}) s.machine(x1, 5, z, "exhaust_fan", W);
		for (int x = x0 + 4; x <= x1 - 4; x += 6) {
			for (int z = z0 + 4; z <= z1 - 4; z += 6) if (s.get(x, 8, z).isOf(Blocks.GRAY_CONCRETE)) s.set(x, 7, z, hangingLantern());
		}
		s.fill(x0 + 19, 1, z0 + 7, x0 + 26, 2, z0 + 13, (x, y, z) -> s.get(x, y, z).isAir() && s.chance(x, y, z, 6, 3)
				? (y == 1 ? b(Blocks.GRAY_CONCRETE) : b(Blocks.COBWEB)) : null);
		s.chest(x1 - 2, 1, z1 - 1, W, COMMON_LOOT);
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
		s.machine(x0 + 3, 6, z0 + 4, "art_table", S, new ItemStack(Items.PAPER, 16), Site.item("crayons", 1));
		s.machine(x0 + 6, 6, z0 + 4, "art_table", S, new ItemStack(Items.PAPER, 16));
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
		for (int x = x1 - 5; x <= x1 - 3; x++) s.rack(x, 6, z0 + 8, S, "tensor_accelerator:3,gpu_blade:1,failed_module:1");
		s.machine(x1 - 2, 6, z0 + 8, "uplink_router", S);
		s.chest(x1 - 2, 6, z1 - 2, W, AI_LAB_LOOT);
		// On the roof: panels and an antenna.
		for (int x = x0 + 2; x <= x1 - 2; x += 2) s.machine(x, 11, z0 + 2, "solar_panel", S);
		s.machine(x0 + 9, 11, z0 + 12, "wireless_transmitter", S);
	}

	/** The reservoir the campus cooled itself from, with its pumps still on the edge. */
	private static void reservoir(Site s, int x0, int z0) {
		int x1 = x0 + 17;
		int z1 = z0 + 21;
		s.fill(x0, -3, z0, x1, 0, z1, b(Blocks.STONE_BRICKS));
		s.fill(x0 + 1, -2, z0 + 1, x1 - 1, 0, z1 - 1, b(Blocks.WATER));
		s.machine(x0 + 1, 1, z0 + 6, "freshwater_pump", E);
		s.machine(x0 + 1, 1, z0 + 14, "freshwater_pump", E);
		s.machine(x1 - 1, 1, z0 + 10, "freshwater_pump", W);
		s.cableRun(x0 - 2, 1, z0 + 6, x0, 1, z0 + 6, "coolant_pipe");
		s.cableRun(x0 - 2, 1, z0 + 14, x0, 1, z0 + 14, "coolant_pipe");
		s.cableRun(x1, 1, z0 + 10, x1 + 2, 1, z0 + 10, "coolant_pipe");
		s.fill(x0 + 1, 1, z0 + 1, x1 - 1, 1, z1 - 1, (x, y, z) -> s.get(x, 0, z).isOf(Blocks.WATER) && s.chance(x, y, z, 1, 14)
				? b(Blocks.LILY_PAD) : null);
	}

	private static void coolingYard(Site s, int x0, int z0) {
		s.fill(x0, 0, z0, x0 + 35, 0, z0 + 30, b(Blocks.LIGHT_GRAY_CONCRETE));
		s.cableRun(x0 + 5, 1, z0 + 14, x0 + 35, 1, z0 + 14, "coolant_pipe");
		for (int cx : new int[] {x0 + 5, x0 + 17, x0 + 29}) {
			for (int cz : new int[] {z0 + 7, z0 + 22}) {
				s.fill(cx - 2, 1, cz - 2, cx + 2, 8, cz + 2, (x, y, z) -> {
					int dx = x - cx, dz = z - cz;
					int r2 = dx * dx + dz * dz;
					if (r2 > 5 || r2 < 3) return r2 < 3 ? AIR : null;
					return s.chance(x, y, z, 2, 10) ? b(Blocks.CRACKED_STONE_BRICKS) : b(Blocks.SMOOTH_STONE);
				});
				s.machine(cx, 1, cz, "cooling_tower", N);
				s.cableRun(cx, 1, Math.min(cz + 1, z0 + 14), cx, 1, Math.max(cz - 1, z0 + 14), "coolant_pipe");
			}
		}
	}

	private static void substation(Site s, int x0, int z0) {
		s.fill(x0, 0, z0, x0 + 21, 0, z0 + 30, b(Blocks.GRAVEL));
		s.walls(x0, 1, z0, x0 + 21, 2, z0 + 30, (x, y, z) -> run(Blocks.IRON_BARS, z == z0 || z == z0 + 30));
		s.fill(x0 + 10, 1, z0, x0 + 11, 2, z0, AIR);
		for (int i = 0; i < 4; i++) {
			int z = z0 + 5 + i * 6;
			s.machine(x0 + 3, 1, z, "utility_intake", E);
			s.cableRun(x0 + 4, 1, z, x0 + 7, 1, z, "power_cable");
			if (i == 2) s.cable(x0 + 6, 1, z, "power_cable", true);
			s.fill(x0 + 8, 1, z - 1, x0 + 9, 3, z, b(Blocks.IRON_BLOCK));
			s.set(x0 + 8, 4, z, b(Blocks.LIGHTNING_ROD));
			s.set(x0 + 9, 4, z - 1, b(Blocks.LIGHTNING_ROD));
		}
	}

	private static void generatorYard(Site s, int x0, int z0) {
		s.fill(x0, 0, z0, x0 + 13, 0, z0 + 30, b(Blocks.SMOOTH_STONE));
		for (int z = z0 + 3; z <= z0 + 27; z += 3) {
			ItemStack fuel = s.chance(x0, 1, z, 1, 2) ? new ItemStack(RcItems.ITEMS.get("coke"), 4) : ItemStack.EMPTY;
			s.machine(x0 + 3, 1, z, "diesel_generator", W, fuel);
			s.fill(x0 + 7, 1, z, x0 + 8, 2, z, b(Blocks.WHITE_TERRACOTTA));
		}
		s.chest(x0 + 11, 1, z0 + 2, W, COMMON_LOOT);
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
