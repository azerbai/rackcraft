package dev.rackcraft;

import dev.rackcraft.world.structure.DataCenterPiece;
import dev.rackcraft.world.structure.DataCenterStructure;
import net.fabricmc.fabric.api.biome.v1.BiomeModifications;
import net.fabricmc.fabric.api.biome.v1.BiomeSelectors;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.tag.TagKey;
import net.minecraft.structure.StructurePieceType;
import net.minecraft.world.gen.GenerationStep;
import net.minecraft.world.gen.feature.PlacedFeature;
import net.minecraft.world.gen.structure.Structure;
import net.minecraft.world.gen.structure.StructureType;

public final class Worldgen {
	private static final RegistryKey<PlacedFeature> BAUXITE_ORE = RegistryKey.of(
			RegistryKeys.PLACED_FEATURE, Rackcraft.id("bauxite_ore"));
	private static final RegistryKey<PlacedFeature> URANIUM_ORE = RegistryKey.of(
			RegistryKeys.PLACED_FEATURE, Rackcraft.id("uranium_ore"));
	/** Every abandoned data center variant; also usable as /locate structure #rackcraft:data_centers. */
	public static final TagKey<Structure> DATA_CENTERS = TagKey.of(RegistryKeys.STRUCTURE, Rackcraft.id("data_centers"));
	public static final StructureType<DataCenterStructure> DATA_CENTER_STRUCTURE = () -> DataCenterStructure.CODEC;
	public static final StructurePieceType DATA_CENTER_PIECE = DataCenterPiece::new;

	private Worldgen() {}

	public static void register() {
		Registry.register(Registries.STRUCTURE_TYPE, Rackcraft.id("data_center"), DATA_CENTER_STRUCTURE);
		Registry.register(Registries.STRUCTURE_PIECE, Rackcraft.id("data_center"), DATA_CENTER_PIECE);
		BiomeModifications.addFeature(BiomeSelectors.foundInOverworld(), GenerationStep.Feature.UNDERGROUND_ORES, BAUXITE_ORE);
		BiomeModifications.addFeature(BiomeSelectors.foundInOverworld(), GenerationStep.Feature.UNDERGROUND_ORES, URANIUM_ORE);
	}
}
