package dev.rackcraft.world.structure;

import dev.rackcraft.RcBlocks;
import dev.rackcraft.RcItems;
import dev.rackcraft.block.CableBlock;
import dev.rackcraft.block.MachineBlock;
import dev.rackcraft.block.MachineBlockEntity;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.ChestBlock;
import net.minecraft.block.entity.LootableContainerBlockEntity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.SpawnReason;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.state.property.Properties;
import net.minecraft.util.BlockRotation;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockBox;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.random.Random;
import net.minecraft.world.StructureWorldAccess;

/**
 * Builds a data-center layout in local coordinates: x runs east across the site's width, z south across
 * its depth, y up from the floor (y = 0). The site may be rotated, and generation happens one chunk at a
 * time, so every write is clipped to the chunk being generated, and all "randomness" is a hash of the
 * position, so neighbouring chunks agree about the same building.
 */
public final class Site {
	private final StructureWorldAccess world;
	private final BlockPos origin;
	private final BlockRotation rotation;
	private final int width;
	private final int depth;
	private final long seed;
	private final BlockBox clip;
	private final List<BlockPos> cables = new ArrayList<>();

	public Site(StructureWorldAccess world, BlockPos origin, BlockRotation rotation, int width, int depth, long seed, BlockBox clip) {
		this.world = world;
		this.origin = origin;
		this.rotation = rotation;
		this.width = width;
		this.depth = depth;
		this.seed = seed;
		this.clip = clip;
	}

	public int width() { return width; }
	public int depth() { return depth; }
	public StructureWorldAccess world() { return world; }

	// ---------------------------------------------------------------- coordinates

	public BlockPos pos(int x, int y, int z) {
		return switch (rotation) {
			case NONE -> origin.add(x, y, z);
			case CLOCKWISE_90 -> origin.add(depth - 1 - z, y, x);
			case CLOCKWISE_180 -> origin.add(width - 1 - x, y, depth - 1 - z);
			case COUNTERCLOCKWISE_90 -> origin.add(z, y, width - 1 - x);
		};
	}

	public Direction dir(Direction local) {
		return local.getAxis().isHorizontal() ? rotation.rotate(local) : local;
	}

	private boolean inside(BlockPos pos) {
		return clip.contains(pos);
	}

	// ---------------------------------------------------------------- randomness

	/** A stable pseudo-random number for this position and purpose. */
	public int hash(int x, int y, int z, int salt) {
		long h = seed ^ (x * 0x9E3779B97F4A7C15L) ^ (y * 0xC2B2AE3D27D4EB4FL) ^ (z * 0x165667B19E3779F9L) ^ (salt * 0x27D4EB2F165667C5L);
		h ^= h >>> 33;
		h *= 0xFF51AFD7ED558CCDL;
		h ^= h >>> 33;
		return (int) (h & 0x7FFFFFFF);
	}

	public boolean chance(int x, int y, int z, int salt, int oneIn) {
		return hash(x, y, z, salt) % oneIn == 0;
	}

	public <T> T pick(int x, int y, int z, int salt, List<T> values) {
		return values.get(hash(x, y, z, salt) % values.size());
	}

	// ---------------------------------------------------------------- blocks

	public void set(int x, int y, int z, BlockState state) {
		BlockPos pos = pos(x, y, z);
		if (!inside(pos)) return;
		world.setBlockState(pos, rotate(state), Block.NOTIFY_LISTENERS);
	}

	private BlockState rotate(BlockState state) {
		if (state.getBlock() instanceof MachineBlock) return state.with(MachineBlock.FACING, rotation.rotate(state.get(MachineBlock.FACING)));
		return state.rotate(rotation);
	}

	public BlockState get(int x, int y, int z) {
		BlockPos pos = pos(x, y, z);
		return inside(pos) ? world.getBlockState(pos) : Blocks.AIR.getDefaultState();
	}

	public interface Material {
		BlockState at(int x, int y, int z);
	}

	public void fill(int x0, int y0, int z0, int x1, int y1, int z1, BlockState state) {
		fill(x0, y0, z0, x1, y1, z1, (x, y, z) -> state);
	}

	/** Fills a local box, visiting only the part inside the chunk being generated. */
	public void fill(int x0, int y0, int z0, int x1, int y1, int z1, Material material) {
		int minX = Math.min(x0, x1), maxX = Math.max(x0, x1);
		int minY = Math.min(y0, y1), maxY = Math.max(y0, y1);
		int minZ = Math.min(z0, z1), maxZ = Math.max(z0, z1);
		BlockPos a = pos(minX, minY, minZ);
		BlockPos b = pos(maxX, maxY, maxZ);
		int wx0 = Math.max(Math.min(a.getX(), b.getX()), clip.getMinX());
		int wx1 = Math.min(Math.max(a.getX(), b.getX()), clip.getMaxX());
		int wz0 = Math.max(Math.min(a.getZ(), b.getZ()), clip.getMinZ());
		int wz1 = Math.min(Math.max(a.getZ(), b.getZ()), clip.getMaxZ());
		int wy0 = Math.max(minY + origin.getY(), clip.getMinY());
		int wy1 = Math.min(maxY + origin.getY(), clip.getMaxY());
		if (wx0 > wx1 || wz0 > wz1 || wy0 > wy1) return;
		BlockPos.Mutable cursor = new BlockPos.Mutable();
		for (int wx = wx0; wx <= wx1; wx++) {
			for (int wz = wz0; wz <= wz1; wz++) {
				int[] local = local(wx, wz);
				for (int wy = wy0; wy <= wy1; wy++) {
					BlockState state = material.at(local[0], wy - origin.getY(), local[1]);
					if (state == null) continue;
					cursor.set(wx, wy, wz);
					world.setBlockState(cursor, rotate(state), Block.NOTIFY_LISTENERS);
				}
			}
		}
	}

	private int[] local(int wx, int wz) {
		int dx = wx - origin.getX();
		int dz = wz - origin.getZ();
		return switch (rotation) {
			case NONE -> new int[] {dx, dz};
			case CLOCKWISE_90 -> new int[] {dz, depth - 1 - dx};
			case CLOCKWISE_180 -> new int[] {width - 1 - dx, depth - 1 - dz};
			case COUNTERCLOCKWISE_90 -> new int[] {width - 1 - dz, dx};
		};
	}

	public void walls(int x0, int y0, int z0, int x1, int y1, int z1, BlockState state) {
		walls(x0, y0, z0, x1, y1, z1, (x, y, z) -> state);
	}

	/** The walls of a local box (its four sides, all heights). */
	public void walls(int x0, int y0, int z0, int x1, int y1, int z1, Material material) {
		fill(x0, y0, z0, x1, y1, z0, material);
		fill(x0, y0, z1, x1, y1, z1, material);
		fill(x0, y0, z0, x0, y1, z1, material);
		fill(x1, y0, z0, x1, y1, z1, material);
	}

	/**
	 * Levels the ground: solid foundation under the floor down to existing ground (at most {@code maxDepth}
	 * blocks), and air above it up to {@code clearHeight}.
	 */
	public void pad(int x0, int z0, int x1, int z1, int clearHeight, int maxDepth, BlockState foundation) {
		fill(x0, 1, z0, x1, clearHeight, z1, Blocks.AIR.getDefaultState());
		for (int x = x0; x <= x1; x++) {
			for (int z = z0; z <= z1; z++) {
				BlockPos top = pos(x, -1, z);
				if (!inside(top)) continue;
				for (int y = -1; y >= -maxDepth; y--) {
					BlockPos below = pos(x, y, z);
					BlockState state = world.getBlockState(below);
					if (state.isSolidBlock(world, below) && state.getFluidState().isEmpty()) break;
					world.setBlockState(below, foundation, Block.NOTIFY_LISTENERS);
				}
			}
		}
	}

	// ---------------------------------------------------------------- Rackcraft pieces

	public void machine(int x, int y, int z, String id, Direction facing, ItemStack... contents) {
		BlockPos pos = pos(x, y, z);
		if (!inside(pos)) return;
		set(x, y, z, RcBlocks.get(id).getDefaultState().with(MachineBlock.FACING, facing));
		if (contents.length > 0 && world.getBlockEntity(pos) instanceof MachineBlockEntity machine) {
			for (int slot = 0; slot < contents.length && slot < machine.size(); slot++) machine.setStack(slot, contents[slot]);
		}
	}

	public MachineBlockEntity machineAt(int x, int y, int z) {
		BlockPos pos = pos(x, y, z);
		return inside(pos) && world.getBlockEntity(pos) instanceof MachineBlockEntity machine ? machine : null;
	}

	/** A rack loaded with modules from a weighted mix like "pi_node:3,server_1u:2,failed_module:1", up to 8 U. */
	public void rack(int x, int y, int z, Direction facing, String mix) {
		BlockPos pos = pos(x, y, z);
		if (!inside(pos)) return;
		List<String> pool = new ArrayList<>();
		for (String part : mix.split(",")) {
			String[] pieces = part.split(":");
			for (int count = 0; count < Integer.parseInt(pieces[1]); count++) pool.add(pieces[0]);
		}
		List<ItemStack> modules = new ArrayList<>();
		int units = 0;
		for (int slot = 0; slot < 8 && units < 8; slot++) {
			String id = pick(x, y + slot, z, 77, pool);
			if (id.equals("empty")) continue;
			int size = id.equals("gpu_blade") || id.equals("tensor_accelerator") ? 2 : id.equals("quantum_core") ? 4 : 1;
			if (units + size > 8) continue;
			units += size;
			modules.add(new ItemStack(RcItems.ITEMS.get(id)));
		}
		machine(x, y, z, "server_rack", facing, modules.toArray(ItemStack[]::new));
	}

	/** A rack with exactly these modules in its bays, in order: a working rack, not a ruin. */
	public void fullRack(int x, int y, int z, Direction facing, List<String> bays) {
		ItemStack[] modules = bays.stream().limit(8).map(id -> new ItemStack(RcItems.ITEMS.get(id))).toArray(ItemStack[]::new);
		machine(x, y, z, "server_rack", facing, modules);
	}

	/** A chest holding exactly these items (no loot table). */
	public void chestWith(int x, int y, int z, Direction facing, ItemStack... contents) {
		BlockPos pos = pos(x, y, z);
		if (!inside(pos)) return;
		set(x, y, z, Blocks.CHEST.getDefaultState().with(ChestBlock.FACING, facing));
		if (world.getBlockEntity(pos) instanceof net.minecraft.block.entity.ChestBlockEntity chest) {
			for (int slot = 0; slot < contents.length && slot < chest.size(); slot++) chest.setStack(slot, contents[slot]);
		}
	}

	public void cable(int x, int y, int z, String id, boolean cut) {
		BlockPos pos = pos(x, y, z);
		if (!inside(pos)) return;
		world.setBlockState(pos, RcBlocks.get(id).getDefaultState().with(CableBlock.CUT, cut), Block.NOTIFY_LISTENERS);
		cables.add(pos);
	}

	public void cableRun(int x0, int y0, int z0, int x1, int y1, int z1, String id) {
		for (int x = Math.min(x0, x1); x <= Math.max(x0, x1); x++) {
			for (int y = Math.min(y0, y1); y <= Math.max(y0, y1); y++) {
				for (int z = Math.min(z0, z1); z <= Math.max(z0, z1); z++) cable(x, y, z, id, false);
			}
		}
	}

	public void chest(int x, int y, int z, Direction facing, Identifier lootTable) {
		chest(x, y, z, facing, lootTable, false);
	}

	public void chest(int x, int y, int z, Direction facing, Identifier lootTable, boolean waterlogged) {
		BlockPos pos = pos(x, y, z);
		if (!inside(pos)) return;
		set(x, y, z, Blocks.CHEST.getDefaultState().with(ChestBlock.FACING, facing).with(ChestBlock.WATERLOGGED, waterlogged));
		LootableContainerBlockEntity.setLootTable(world, Random.create(hash(x, y, z, 5)), pos, lootTable);
	}

	public static ItemStack item(String id, int count) {
		Item item = RcItems.ITEMS.get(id);
		return new ItemStack(item, count);
	}

	/** Spawns a villager standing at this spot, once (only from the chunk that contains it). */
	public VillagerEntity villager(int x, int y, int z, Consumer<VillagerEntity> setup) {
		BlockPos pos = pos(x, y, z);
		if (!inside(pos)) return null;
		VillagerEntity villager = EntityType.VILLAGER.create(world.toServerWorld());
		if (villager == null) return null;
		villager.refreshPositionAndAngles(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, 0, 0);
		villager.initialize(world, world.getLocalDifficulty(pos), SpawnReason.STRUCTURE, null, null);
		villager.setPersistent();
		setup.accept(villager);
		world.spawnEntityAndPassengers(villager);
		return villager;
	}

	/** True if this local cell is a standing spot: solid floor under it, two cells of air above. */
	public boolean open(int x, int y, int z) {
		return get(x, y, z).isAir() && get(x, y + 1, z).isAir() && !get(x, y - 1, z).isAir();
	}

	/** Spawns a guard standing here, tied to this post, once (from the chunk that contains it). Elites carry a bounty name. */
	public dev.rackcraft.entity.GuardEntity guard(int x, int y, int z, net.minecraft.entity.EntityType<dev.rackcraft.entity.GuardEntity> type, String eliteName) {
		BlockPos pos = pos(x, y, z);
		if (!inside(pos)) return null;
		dev.rackcraft.entity.GuardEntity guard = type.create(world.toServerWorld());
		if (guard == null) return null;
		guard.refreshPositionAndAngles(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, (hash(x, y, z, 31) % 360), 0);
		guard.initialize(world, world.getLocalDifficulty(pos), SpawnReason.STRUCTURE, null, null);
		guard.equipFor(pos, eliteName);
		world.spawnEntityAndPassengers(guard);
		return guard;
	}

	/** A wall sign with up to four lines of text, hanging on the block behind it (facing away from that block). */
	public void sign(int x, int y, int z, Direction facing, String... lines) {
		BlockPos pos = pos(x, y, z);
		if (!inside(pos)) return;
		set(x, y, z, Blocks.OAK_WALL_SIGN.getDefaultState().with(net.minecraft.block.WallSignBlock.FACING, facing));
		if (world.getBlockEntity(pos) instanceof net.minecraft.block.entity.SignBlockEntity sign) {
			net.minecraft.block.entity.SignText text = new net.minecraft.block.entity.SignText();
			for (int line = 0; line < lines.length && line < 4; line++) text = text.withMessage(line, net.minecraft.text.Text.literal(lines[line]));
			// setText notifies the world, and during worldgen the block entity has none yet: load the text as NBT.
			net.minecraft.nbt.NbtCompound nbt = sign.createNbt();
			net.minecraft.block.entity.SignText.CODEC.encodeStart(net.minecraft.nbt.NbtOps.INSTANCE, text)
					.result().ifPresent(encoded -> nbt.put("front_text", encoded));
			sign.readNbt(nbt);
			sign.markDirty();
		}
	}

	/** Joins cables to their neighbours. Cables at a chunk edge are finished when the next chunk loads. */
	public void finish() {
		for (BlockPos pos : cables) {
			BlockState state = world.getBlockState(pos);
			if (state.getBlock() instanceof CableBlock cable) {
				world.setBlockState(pos, cable.withConnections(state, world, pos), Block.NOTIFY_LISTENERS);
			}
		}
		cables.clear();
	}

	// ---------------------------------------------------------------- weathering

	public BlockState weathered(int x, int y, int z) {
		int roll = hash(x, y, z, 1) % 10;
		return roll < 2 ? Blocks.MOSSY_STONE_BRICKS.getDefaultState()
				: roll < 4 ? Blocks.CRACKED_STONE_BRICKS.getDefaultState() : Blocks.STONE_BRICKS.getDefaultState();
	}

	public static boolean waterlogged(BlockState state) {
		return state.contains(Properties.WATERLOGGED) && state.get(Properties.WATERLOGGED);
	}
}
