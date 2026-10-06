package dev.rackcraft.world;

import com.mojang.serialization.Codec;
import dev.rackcraft.Rackcraft;
import dev.rackcraft.RcBlocks;
import dev.rackcraft.RcItems;
import dev.rackcraft.block.CableBlock;
import dev.rackcraft.block.MachineBlock;
import dev.rackcraft.block.MachineBlockEntity;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.ChestBlock;
import net.minecraft.block.LanternBlock;
import net.minecraft.block.entity.LootableContainerBlockEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.random.Random;
import net.minecraft.world.Heightmap;
import net.minecraft.world.StructureWorldAccess;
import net.minecraft.world.gen.feature.DefaultFeatureConfig;
import net.minecraft.world.gen.feature.Feature;
import net.minecraft.world.gen.feature.util.FeatureContext;

/**
 * A small, abandoned data center: two loaded racks, a fuelled generator, a router and a Crypto Exchange.
 * It works except for one cut power cable and one cut fiber cable, which the chest's Repair Kit fixes,
 * so it teaches power, networking and mining in one place.
 *
 * Layout (looking down, north at the top; the door is in the north wall):
 * <pre>
 *   z0  W W W W . W W W W      W wall, . door
 *   z1  W C . . . . . E W      C chest, E Crypto Exchange
 *   z2  W . . . . . . . W      cold aisle (rack intakes face north)
 *   z3  W G p R R f U . W      G generator, p power cable (cut), R racks, f fiber (cut), U uplink router
 *   z4  W . . . . . . . W      hot aisle (a power cable runs overhead to the fan)
 *   z5  W . . . F . . . W      F exhaust fan, facing north into the hot aisle
 *   z6  W W W W W W W W W
 *        x0 ...           x8
 * </pre>
 */
public final class AbandonedDataCenterFeature extends Feature<DefaultFeatureConfig> {
	public static final Identifier LOOT_TABLE = Rackcraft.id("chests/abandoned_data_center");
	private static final int WIDTH = 9;
	private static final int DEPTH = 7;
	private static final int MAX_SLOPE = 3;

	public AbandonedDataCenterFeature(Codec<DefaultFeatureConfig> codec) {
		super(codec);
	}

	@Override
	public boolean generate(FeatureContext<DefaultFeatureConfig> context) {
		return place(context.getWorld(), context.getOrigin(), context.getRandom(), false);
	}

	/** Builds the site with its north-west corner at {@code origin}. {@code force} skips the terrain checks. */
	public static boolean place(StructureWorldAccess world, BlockPos origin, Random random, boolean force) {
		int lowest = Integer.MAX_VALUE;
		int highest = Integer.MIN_VALUE;
		for (int dx : new int[] {0, WIDTH / 2, WIDTH - 1}) {
			for (int dz : new int[] {0, DEPTH / 2, DEPTH - 1}) {
				int top = world.getTopY(Heightmap.Type.WORLD_SURFACE_WG, origin.getX() + dx, origin.getZ() + dz);
				lowest = Math.min(lowest, top);
				highest = Math.max(highest, top);
				BlockPos surface = new BlockPos(origin.getX() + dx, top - 1, origin.getZ() + dz);
				if (!force && !world.getFluidState(surface).isEmpty()) return false;
			}
		}
		if (!force && highest - lowest > MAX_SLOPE) return false;
		BlockPos base = force ? origin.down() : new BlockPos(origin.getX(), lowest - 1, origin.getZ());

		for (int x = 0; x < WIDTH; x++) {
			for (int z = 0; z < DEPTH; z++) {
				// Foundation down to solid ground, then a cleared shell above the floor.
				for (int y = -1; y >= -6; y--) {
					BlockPos below = base.add(x, y, z);
					if (world.getBlockState(below).isSolidBlock(world, below)) break;
					set(world, below, Blocks.COBBLESTONE.getDefaultState());
				}
				for (int y = 1; y <= 7; y++) set(world, base.add(x, y, z), Blocks.AIR.getDefaultState());
				boolean edge = x == 0 || z == 0 || x == WIDTH - 1 || z == DEPTH - 1;
				set(world, base.add(x, 0, z), edge ? Blocks.STONE_BRICKS.getDefaultState()
						: random.nextInt(5) == 0 ? Blocks.CRACKED_STONE_BRICKS.getDefaultState()
						: RcBlocks.get("raised_floor_tile").getDefaultState());
				for (int y = 1; y <= 3; y++) {
					if (edge) set(world, base.add(x, y, z), wall(random));
				}
				// A roof with a few collapsed holes.
				if (edge || random.nextInt(9) != 0) set(world, base.add(x, 4, z), roof(random));
			}
		}
		// Door in the north wall, a window either side, and a cleared path outside.
		set(world, base.add(4, 1, 0), Blocks.AIR.getDefaultState());
		set(world, base.add(4, 2, 0), Blocks.AIR.getDefaultState());
		set(world, base.add(2, 2, 0), Blocks.IRON_BARS.getDefaultState());
		set(world, base.add(6, 2, 0), Blocks.IRON_BARS.getDefaultState());
		for (int y = 1; y <= 2; y++) set(world, base.add(4, y, -1), Blocks.AIR.getDefaultState());

		machine(world, base.add(1, 1, 3), "diesel_generator", Direction.NORTH);
		machine(world, base.add(3, 1, 3), "server_rack", Direction.NORTH);
		machine(world, base.add(4, 1, 3), "server_rack", Direction.NORTH);
		machine(world, base.add(6, 1, 3), "uplink_router", Direction.NORTH);
		machine(world, base.add(4, 1, 5), "exhaust_fan", Direction.NORTH);
		machine(world, base.add(7, 1, 1), "crypto_exchange", Direction.WEST);
		fill(world, base.add(1, 1, 3), new ItemStack(Items.COAL, 12));
		fill(world, base.add(3, 1, 3), new ItemStack(RcItems.ITEMS.get("server_1u")),
				new ItemStack(RcItems.ITEMS.get("server_1u")), new ItemStack(RcItems.ITEMS.get("pi_node")),
				new ItemStack(RcItems.ITEMS.get("pi_node")));
		fill(world, base.add(4, 1, 3), new ItemStack(RcItems.ITEMS.get("pi_node")),
				new ItemStack(RcItems.ITEMS.get("pi_node")), new ItemStack(RcItems.ITEMS.get("pi_node")),
				new ItemStack(RcItems.ITEMS.get("pi_node")));

		BlockPos chest = base.add(1, 1, 1);
		set(world, chest, Blocks.CHEST.getDefaultState().with(ChestBlock.FACING, Direction.SOUTH));
		LootableContainerBlockEntity.setLootTable(world, random, chest, LOOT_TABLE);

		set(world, base.add(2, 3, 2), Blocks.LANTERN.getDefaultState().with(LanternBlock.HANGING, true));
		set(world, base.add(6, 3, 4), Blocks.LANTERN.getDefaultState().with(LanternBlock.HANGING, true));
		set(world, base.add(7, 3, 5), Blocks.COBWEB.getDefaultState());
		set(world, base.add(1, 3, 1), Blocks.COBWEB.getDefaultState());
		for (int index = 0; index < 4; index++) {
			BlockPos moss = base.add(1 + random.nextInt(WIDTH - 2), 1, 1 + random.nextInt(2));
			if (world.isAir(moss)) set(world, moss, Blocks.MOSS_CARPET.getDefaultState());
		}

		// Cables last, so their connections see the machines. The two cut ones are the puzzle.
		cable(world, base.add(2, 1, 3), "power_cable", true);
		cable(world, base.add(5, 1, 3), "fiber_cable", true);
		cable(world, base.add(4, 2, 3), "power_cable", false);
		cable(world, base.add(4, 2, 4), "power_cable", false);
		cable(world, base.add(4, 2, 5), "power_cable", false);
		return true;
	}

	private static BlockState wall(Random random) {
		int roll = random.nextInt(10);
		return roll < 2 ? Blocks.MOSSY_STONE_BRICKS.getDefaultState()
				: roll < 4 ? Blocks.CRACKED_STONE_BRICKS.getDefaultState() : Blocks.STONE_BRICKS.getDefaultState();
	}

	private static BlockState roof(Random random) {
		return random.nextInt(4) == 0 ? Blocks.MOSSY_COBBLESTONE.getDefaultState() : Blocks.POLISHED_ANDESITE.getDefaultState();
	}

	private static void set(StructureWorldAccess world, BlockPos pos, BlockState state) {
		world.setBlockState(pos, state, Block.NOTIFY_LISTENERS);
	}

	private static void machine(StructureWorldAccess world, BlockPos pos, String id, Direction facing) {
		set(world, pos, RcBlocks.get(id).getDefaultState().with(MachineBlock.FACING, facing));
	}

	private static void fill(StructureWorldAccess world, BlockPos pos, ItemStack... stacks) {
		if (!(world.getBlockEntity(pos) instanceof MachineBlockEntity machine)) return;
		for (int slot = 0; slot < stacks.length; slot++) machine.setStack(slot, stacks[slot]);
	}

	private static void cable(StructureWorldAccess world, BlockPos pos, String id, boolean cut) {
		CableBlock cable = (CableBlock) RcBlocks.get(id);
		set(world, pos, cable.withConnections(cable.getDefaultState(), world, pos).with(CableBlock.CUT, cut));
	}
}
