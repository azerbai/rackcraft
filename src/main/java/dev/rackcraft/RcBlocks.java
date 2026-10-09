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
	public static BlockEntityType<dev.rackcraft.block.BeltBlockEntity> BELT_ENTITY;

	private RcBlocks() {}

	public static void register() {
		for (String id : ContentIds.BLOCK_IDS) {
			AbstractBlock.Settings settings = AbstractBlock.Settings.copy(Blocks.IRON_BLOCK)
					.strength(3.0f, 6.0f).requiresTool();
			Block block = switch (id) {
				case "power_cable" -> new CableBlock(settings, NetKind.POWER);
				case "coolant_pipe" -> new CableBlock(settings, NetKind.COOLANT);
				case "fiber_cable" -> new CableBlock(settings, NetKind.DATA);
				case "item_pipe" -> new CableBlock(settings, NetKind.ITEM);
				case "trunk_bundle" -> new CableBlock(settings, java.util.EnumSet.of(NetKind.POWER, NetKind.COOLANT, NetKind.DATA), 3.5, "structured_cabling");
				case "patch_panel" -> new dev.rackcraft.block.PatchPanelBlock(settings);
				case "pylon", "superconducting_pylon" -> new dev.rackcraft.block.PylonBlock(settings.nonOpaque());
				case "server_rack", "high_density_rack", "immersion_rack", "exascale_cabinet" -> new dev.rackcraft.block.RackBlock(settings);
				// See-through lattice: non-opaque so the blocks behind it still draw.
				case "launch_tower" -> new Block(settings.nonOpaque());
				case "solar_array", "solar_array_tracking" -> new dev.rackcraft.block.SolarArrayBlock(settings.nonOpaque());
				case "tower_section" -> new dev.rackcraft.block.TowerSectionBlock(settings.nonOpaque());
				case "belt_loader", "site_planner", "storage_exporter" -> new dev.rackcraft.block.DirectedMachineBlock(settings);
				case "conveyor_belt" -> new dev.rackcraft.block.ConveyorBeltBlock(settings.strength(1.5f, 6.0f).nonOpaque());
				case "welding_arm", "riveting_arm", "assembly_arm" -> new dev.rackcraft.block.RobotArmBlock(settings.nonOpaque());
				default -> ContentIds.ARRAY_IDS.contains(id) ? new dev.rackcraft.block.ArrayMachineBlock(settings)
						: ContentIds.MACHINE_IDS.contains(id) ? new MachineBlock(settings) : new Block(settings);
			};
			BLOCKS.put(id, Registry.register(Registries.BLOCK, Rackcraft.id(id), block));
		}
		Block[] machineBlocks = ContentIds.MACHINE_IDS.stream().map(BLOCKS::get).toArray(Block[]::new);
		MACHINE_ENTITY = Registry.register(Registries.BLOCK_ENTITY_TYPE, Rackcraft.id("machine"),
				BlockEntityType.Builder.create(MachineBlockEntity::new, machineBlocks).build(null));
		BELT_ENTITY = Registry.register(Registries.BLOCK_ENTITY_TYPE, Rackcraft.id("conveyor_belt"),
				BlockEntityType.Builder.create(dev.rackcraft.block.BeltBlockEntity::new, BLOCKS.get("conveyor_belt")).build(null));
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