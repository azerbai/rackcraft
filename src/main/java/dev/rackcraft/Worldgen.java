package dev.rackcraft;

import net.fabricmc.fabric.api.biome.v1.BiomeModifications;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.world.gen.GenerationStep;
import net.minecraft.world.gen.feature.PlacedFeature;
import net.fabricmc.fabric.api.biome.v1.BiomeSelectors;

public final class Worldgen {
	private static final RegistryKey<PlacedFeature> BAUXITE_ORE = RegistryKey.of(
			RegistryKeys.PLACED_FEATURE, Rackcraft.id("bauxite_ore"));

	private Worldgen() {}

	public static void register() {
		BiomeModifications.addFeature(BiomeSelectors.foundInOverworld(), GenerationStep.Feature.UNDERGROUND_ORES, BAUXITE_ORE);
	}
}