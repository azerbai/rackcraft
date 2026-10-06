package dev.rackcraft.world;

import dev.rackcraft.Worldgen;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.WeakHashMap;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.random.ChunkRandom;
import net.minecraft.util.math.random.Xoroshiro128PlusPlusRandom;
import net.minecraft.world.Heightmap;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.biome.source.BiomeAccess;
import net.minecraft.world.gen.GenerationStep;
import net.minecraft.world.gen.chunk.ChunkGenerator;
import net.minecraft.world.gen.feature.PlacedFeature;
import net.minecraft.world.gen.feature.util.PlacedFeatureIndexer;
import net.minecraft.world.gen.noise.NoiseConfig;

/**
 * Finds abandoned data centers without generating chunks. The site is a feature, not a structure, so
 * vanilla {@code /locate} cannot see it. Instead this replays {@code ChunkGenerator.generateFeatures}' seeding
 * and the placed feature's modifiers (rarity_filter 1/360, in_square, heightmap, biome) for each chunk, then
 * runs the same terrain check as {@link AbandonedDataCenterFeature#place} against the noise terrain.
 *
 * Heights come from the noise generator, so a site whose ground was later reshaped by an earlier feature (a lake,
 * say) may have been skipped in the real world. Predictions are exact for ordinary terrain.
 */
public final class DataCenterLocator {
	private static final int RARITY = 360;
	private static final int STEP = GenerationStep.Feature.SURFACE_STRUCTURES.ordinal();
	private static final Map<ChunkGenerator, Integer> FEATURE_INDEX = new WeakHashMap<>();

	private DataCenterLocator() {}

	/** The nearest site's floor position (the north-west corner, one block above the floor), searching {@code radius} chunks out. */
	public static Optional<BlockPos> nearest(ServerWorld world, BlockPos from, int radius) {
		PlacedFeature feature = world.getRegistryManager().get(RegistryKeys.PLACED_FEATURE)
				.get(Worldgen.ABANDONED_DATA_CENTER);
		if (feature == null) return Optional.empty();
		ChunkGenerator generator = world.getChunkManager().getChunkGenerator();
		int index = featureIndex(generator, feature);
		if (index < 0) return Optional.empty();

		NoiseConfig noise = world.getChunkManager().getNoiseConfig();
		BiomeAccess biomes = new BiomeAccess((x, y, z) -> generator.getBiomeSource()
				.getBiome(x, y, z, noise.getMultiNoiseSampler()), BiomeAccess.hashSeed(world.getSeed()));
		AbandonedDataCenterFeature.Terrain terrain = new AbandonedDataCenterFeature.Terrain() {
			@Override
			public int topY(int x, int z) {
				return generator.getHeight(x, z, Heightmap.Type.WORLD_SURFACE_WG, world, noise);
			}

			@Override
			public boolean isWet(BlockPos pos) {
				return !generator.getColumnSample(pos.getX(), pos.getZ(), world, noise)
						.getState(pos.getY()).getFluidState().isEmpty();
			}
		};

		ChunkPos center = new ChunkPos(from);
		ChunkRandom random = new ChunkRandom(new Xoroshiro128PlusPlusRandom(0));
		BlockPos best = null;
		double bestDistance = Double.MAX_VALUE;
		int stopRing = radius;
		// Square rings outward; once a hit is found, a closer one can still sit up to sqrt(2) rings further out.
		for (int ring = 0; ring <= stopRing; ring++) {
			for (int dx = -ring; dx <= ring; dx++) {
				for (int dz = -ring; dz <= ring; dz++) {
					if (Math.abs(dx) != ring && Math.abs(dz) != ring) continue;
					BlockPos site = siteIn(world, generator, feature, index, biomes, terrain, random,
							center.x + dx, center.z + dz);
					if (site == null) continue;
					double distance = site.getSquaredDistance(from.getX(), site.getY(), from.getZ());
					if (distance < bestDistance) {
						if (best == null) stopRing = Math.min(radius, (int) Math.ceil(ring * Math.sqrt(2)) + 1);
						best = site;
						bestDistance = distance;
					}
				}
			}
		}
		return Optional.ofNullable(best);
	}

	private static BlockPos siteIn(ServerWorld world, ChunkGenerator generator, PlacedFeature feature, int index,
			BiomeAccess biomes, AbandonedDataCenterFeature.Terrain terrain, ChunkRandom random, int chunkX, int chunkZ) {
		long population = random.setPopulationSeed(world.getSeed(), chunkX << 4, chunkZ << 4);
		random.setDecoratorSeed(population, index, STEP);
		if (random.nextFloat() >= 1.0F / RARITY) return null;
		int x = (chunkX << 4) + random.nextInt(16);
		int z = (chunkZ << 4) + random.nextInt(16);
		int y = terrain.topY(x, z);
		if (y <= world.getBottomY()) return null;
		BlockPos origin = new BlockPos(x, y, z);
		RegistryEntry<Biome> biome = biomes.getBiome(origin);
		if (!generator.getGenerationSettings(biome).isFeatureAllowed(feature)) return null;
		OptionalInt floor = AbandonedDataCenterFeature.floorY(terrain, origin);
		return floor.isPresent() ? new BlockPos(x, floor.getAsInt() + 1, z) : null;
	}

	/** The feature's decoration index in its step, as {@code ChunkGenerator} assigns it; -1 when it never generates. */
	private static int featureIndex(ChunkGenerator generator, PlacedFeature feature) {
		synchronized (FEATURE_INDEX) {
			return FEATURE_INDEX.computeIfAbsent(generator, key -> {
				List<PlacedFeatureIndexer.IndexedFeatures> steps = PlacedFeatureIndexer.collectIndexedFeatures(
						List.copyOf(key.getBiomeSource().getBiomes()),
						biome -> key.getGenerationSettings(biome).getFeatures(), true);
				if (STEP >= steps.size() || !steps.get(STEP).features().contains(feature)) return -1;
				return steps.get(STEP).indexMapping().applyAsInt(feature);
			});
		}
	}
}
