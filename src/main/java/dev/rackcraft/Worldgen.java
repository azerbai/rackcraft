package dev.rackcraft;

import dev.rackcraft.world.AbandonedDataCenterFeature;
import net.fabricmc.fabric.api.biome.v1.BiomeModifications;
import net.fabricmc.fabric.api.biome.v1.BiomeSelectors;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.world.biome.BiomeKeys;
import net.minecraft.world.gen.GenerationStep;
import net.minecraft.world.gen.feature.DefaultFeatureConfig;
import net.minecraft.world.gen.feature.PlacedFeature;

public final class Worldgen {
	private static final RegistryKey<PlacedFeature> BAUXITE_ORE = RegistryKey.of(
			RegistryKeys.PLACED_FEATURE, Rackcraft.id("bauxite_ore"));
	public static final RegistryKey<PlacedFeature> ABANDONED_DATA_CENTER = RegistryKey.of(
			RegistryKeys.PLACED_FEATURE, Rackcraft.id("abandoned_data_center"));
	public static final AbandonedDataCenterFeature DATA_CENTER_FEATURE = new AbandonedDataCenterFeature(DefaultFeatureConfig.CODEC);

	private Worldgen() {}

	public static void register() {
		Registry.register(Registries.FEATURE, Rackcraft.id("abandoned_data_center"), DATA_CENTER_FEATURE);
		BiomeModifications.addFeature(BiomeSelectors.foundInOverworld(), GenerationStep.Feature.UNDERGROUND_ORES, BAUXITE_ORE);
		// Open, fairly flat biomes; rarity lives in the placed feature JSON.
		BiomeModifications.addFeature(BiomeSelectors.includeByKey(BiomeKeys.PLAINS, BiomeKeys.SUNFLOWER_PLAINS,
				BiomeKeys.MEADOW, BiomeKeys.SAVANNA, BiomeKeys.FOREST, BiomeKeys.BIRCH_FOREST, BiomeKeys.TAIGA,
				BiomeKeys.SNOWY_PLAINS, BiomeKeys.DESERT), GenerationStep.Feature.SURFACE_STRUCTURES, ABANDONED_DATA_CENTER);
	}
}
