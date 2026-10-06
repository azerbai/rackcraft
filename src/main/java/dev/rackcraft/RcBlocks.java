package dev.rackcraft;

import dev.rackcraft.block.MachineBlock;
import dev.rackcraft.block.MachineBlockEntity;
import dev.rackcraft.block.CableBlock;
import dev.rackcraft.block.CableBlockEntity;
import dev.rackcraft.generated.ContentIds;
import dev.rackcraft.sim.NetKind;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.block.AbstractBlock;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.block.entity.BlockEntityType;

public final class RcBlocks {
	public static final Map<String, Block> BLOCKS = new LinkedHashMap<>();
	public static BlockEntityType<MachineBlockEntity> MACHINE_ENTITY;
	public static BlockEntityType<CableBlockEntity> CABLE_ENTITY;

	private RcBlocks() {}

	public static void register() {
		for (String id : ContentIds.BLOCK_IDS) {
			AbstractBlock.Settings settings = AbstractBlock.Settings.copy(Blocks.IRON_BLOCK)
					.strength(3.0f, 6.0f).requiresTool();
			Block block = switch (id) {
				case "power_cable" -> new CableBlock(settings, NetKind.POWER);
				case "coolant_pipe" -> new CableBlock(settings, NetKind.COOLANT);
				case "fiber_cable" -> new CableBlock(settings, NetKind.DATA);
				default -> ContentIds.MACHINE_IDS.contains(id) ? new MachineBlock(settings) : new Block(settings);
			};
			BLOCKS.put(id, Registry.register(Registries.BLOCK, Rackcraft.id(id), block));
		}
		Block[] machineBlocks = ContentIds.MACHINE_IDS.stream().map(BLOCKS::get).toArray(Block[]::new);
		MACHINE_ENTITY = Registry.register(Registries.BLOCK_ENTITY_TYPE, Rackcraft.id("machine"),
				BlockEntityType.Builder.create(MachineBlockEntity::new, machineBlocks).build(null));
		Block[] cableBlocks = BLOCKS.values().stream().filter(CableBlock.class::isInstance).toArray(Block[]::new);
		CABLE_ENTITY = Registry.register(Registries.BLOCK_ENTITY_TYPE, Rackcraft.id("cable"),
				BlockEntityType.Builder.create(CableBlockEntity::new, cableBlocks).build(null));
	}

	public static Block get(String id) {
		Block block = BLOCKS.get(id);
		if (block == null) throw new IllegalArgumentException("Unknown Rackcraft block: " + id);
		return block;
	}
}