package dev.rackcraft.world.structure;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.rackcraft.Worldgen;
import java.util.Optional;
import net.minecraft.util.BlockRotation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.Heightmap;
import net.minecraft.world.gen.structure.Structure;
import net.minecraft.world.gen.structure.StructureType;

/**
 * An abandoned data center. One structure type with a {@code variant} picking the layout from
 * {@link DataCenterLayouts}; each variant is its own structure JSON with its own biomes. Surface sites
 * sit on the lowest point of their footprint (the campus, centred on its start chunk, levels to the average instead) and skip steep
 * or flooded ground; the bunker is dug in below the surface.
 */
public final class DataCenterStructure extends Structure {
	public static final Codec<DataCenterStructure> CODEC = RecordCodecBuilder.create(instance -> instance.group(
			configCodecBuilder(instance),
			Codec.STRING.fieldOf("variant").forGetter(structure -> structure.variant)
	).apply(instance, DataCenterStructure::new));

	/** No hyperscale campus within this many blocks of the world's origin, where players spawn. */
	public static final int CAMPUS_MIN_DISTANCE = 5000;

	private final String variant;

	public DataCenterStructure(Structure.Config config, String variant) {
		super(config);
		this.variant = variant;
	}

	@Override
	protected Optional<StructurePosition> getStructurePosition(Context context) {
		DataCenterLayouts.Layout layout = DataCenterLayouts.get(variant);
		if (layout == null) return Optional.empty();
		BlockRotation rotation = BlockRotation.random(context.random());
		boolean turned = rotation == BlockRotation.CLOCKWISE_90 || rotation == BlockRotation.COUNTERCLOCKWISE_90;
		int spanX = turned ? layout.depth() : layout.width();
		int spanZ = turned ? layout.width() : layout.depth();
		int x0 = context.chunkPos().getStartX() + context.random().nextInt(16);
		int z0 = context.chunkPos().getStartZ() + context.random().nextInt(16);
		if (layout.levelToAverage()) {
			// The campus is meant to be a once-a-world find: never near spawn, and only one eligible spot in three.
			int cx = context.chunkPos().getCenterX();
			int cz = context.chunkPos().getCenterZ();
			if ((long) cx * cx + (long) cz * cz < (long) CAMPUS_MIN_DISTANCE * CAMPUS_MIN_DISTANCE) return Optional.empty();
			if (context.random().nextInt(3) != 0) return Optional.empty();
			// It spans eleven chunks: centre it on its start chunk, or chunks past the eighth wouldn't build.
			x0 -= spanX / 2;
			z0 -= spanZ / 2;
		}
		int lowest = Integer.MAX_VALUE;
		int highest = Integer.MIN_VALUE;
		long total = 0;
		int samples = 0;
		for (int i = 0; i <= 2; i++) {
			for (int j = 0; j <= 2; j++) {
				int x = x0 + (spanX - 1) * i / 2;
				int z = z0 + (spanZ - 1) * j / 2;
				int top = context.chunkGenerator().getHeight(x, z, Heightmap.Type.WORLD_SURFACE_WG, context.world(), context.noiseConfig());
				if (!layout.allowWater() && !context.chunkGenerator().getColumnSample(x, z, context.world(), context.noiseConfig())
						.getState(top - 1).getFluidState().isEmpty()) return Optional.empty();
				lowest = Math.min(lowest, top);
				highest = Math.max(highest, top);
				total += top;
				samples++;
			}
		}
		if (highest - lowest > layout.maxSlope()) return Optional.empty();
		int ground = layout.levelToAverage() ? (int) (total / samples) : lowest;
		int floor = ground - 1 - (layout.placement() == DataCenterLayouts.Placement.BURIED ? layout.buryDepth() : 0);
		if (floor <= context.world().getBottomY() + 4) return Optional.empty();
		BlockPos origin = new BlockPos(x0, floor, z0);
		long seed = context.random().nextLong();
		BlockPos centre = new BlockPos(x0 + spanX / 2, ground, z0 + spanZ / 2);
		return Optional.of(new StructurePosition(centre, collector ->
				collector.addPiece(new DataCenterPiece(variant, origin, rotation, seed))));
	}

	@Override
	public StructureType<?> getType() {
		return Worldgen.DATA_CENTER_STRUCTURE;
	}
}
