package dev.rackcraft.world.structure;

import dev.rackcraft.Worldgen;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.structure.StructureContext;
import net.minecraft.structure.StructurePiece;
import net.minecraft.util.BlockRotation;
import net.minecraft.util.math.BlockBox;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.random.Random;
import net.minecraft.world.StructureWorldAccess;
import net.minecraft.world.gen.StructureAccessor;
import net.minecraft.world.gen.chunk.ChunkGenerator;

/** A whole data center as one piece; it builds itself chunk by chunk through a clipped {@link Site}. */
public final class DataCenterPiece extends StructurePiece {
	private final String variant;
	private final BlockPos origin;
	private final BlockRotation rotation;
	private final long seed;

	public DataCenterPiece(String variant, BlockPos origin, BlockRotation rotation, long seed) {
		super(Worldgen.DATA_CENTER_PIECE, 0, box(variant, origin, rotation));
		this.variant = variant;
		this.origin = origin;
		this.rotation = rotation;
		this.seed = seed;
	}

	public DataCenterPiece(StructureContext context, NbtCompound nbt) {
		super(Worldgen.DATA_CENTER_PIECE, nbt);
		this.variant = nbt.getString("Variant");
		this.origin = BlockPos.fromLong(nbt.getLong("Origin"));
		BlockRotation parsed;
		try {
			parsed = BlockRotation.valueOf(nbt.getString("Rotation"));
		} catch (IllegalArgumentException exception) {
			parsed = BlockRotation.NONE;
		}
		this.rotation = parsed;
		this.seed = nbt.getLong("Seed");
	}

	private static BlockBox box(String variant, BlockPos origin, BlockRotation rotation) {
		DataCenterLayouts.Layout layout = DataCenterLayouts.get(variant);
		boolean turned = rotation == BlockRotation.CLOCKWISE_90 || rotation == BlockRotation.COUNTERCLOCKWISE_90;
		int spanX = turned ? layout.depth() : layout.width();
		int spanZ = turned ? layout.width() : layout.depth();
		return new BlockBox(origin.getX(), origin.getY(), origin.getZ(),
				origin.getX() + spanX - 1, origin.getY() + layout.height() - 1, origin.getZ() + spanZ - 1);
	}

	@Override
	protected void writeNbt(StructureContext context, NbtCompound nbt) {
		nbt.putString("Variant", variant);
		nbt.putLong("Origin", origin.asLong());
		nbt.putString("Rotation", rotation.name());
		nbt.putLong("Seed", seed);
	}

	@Override
	public void generate(StructureWorldAccess world, StructureAccessor structureAccessor, ChunkGenerator chunkGenerator,
			Random random, BlockBox chunkBox, ChunkPos chunkPos, BlockPos pivot) {
		build(world, variant, origin, rotation, seed, chunkBox);
	}

	private static void build(StructureWorldAccess world, String variant, BlockPos origin, BlockRotation rotation, long seed, BlockBox clip) {
		DataCenterLayouts.Layout layout = DataCenterLayouts.get(variant);
		if (layout == null) return;
		Site site = new Site(world, origin, rotation, layout.width(), layout.depth(), seed, clip);
		layout.build().accept(site);
		site.finish();
	}

	/**
	 * Builds a whole site right now, with its floor's north-west corner at {@code origin}: for commands and
	 * the self-test. Returns false for an unknown variant.
	 */
	public static boolean buildNow(ServerWorld world, String variant, BlockPos origin, BlockRotation rotation, long seed) {
		DataCenterLayouts.Layout layout = DataCenterLayouts.get(variant);
		if (layout == null) return false;
		BlockBox everything = new BlockBox(origin.getX() - 256, world.getBottomY(), origin.getZ() - 256,
				origin.getX() + 256, world.getTopY() - 1, origin.getZ() + 256);
		build(world, variant, origin, rotation, seed, everything);
		return true;
	}
}
