package dev.rackcraft;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import dev.rackcraft.generated.ContentIds;
import dev.rackcraft.item.FieldManualItem;
import dev.rackcraft.item.MultimeterItem;
import net.fabricmc.fabric.api.registry.FuelRegistry;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;

public final class RcItems {
	private static final Set<String> SINGLE_STACK = Set.of(
			"pi_node", "server_1u", "asic_miner", "gpu_blade", "quantum_core", "failed_module", "thermal_scanner", "field_manual");
	public static final Map<String, Item> ITEMS = new LinkedHashMap<>();

	private RcItems() {}

	public static void register() {
		for (Map.Entry<String, net.minecraft.block.Block> entry : RcBlocks.BLOCKS.entrySet()) {
			Registry.register(Registries.ITEM, Rackcraft.id(entry.getKey()),
					new BlockItem(entry.getValue(), new Item.Settings()));
		}
		for (String id : ContentIds.ITEM_IDS) {
			Item.Settings settings = new Item.Settings();
			if (SINGLE_STACK.contains(id)) settings.maxCount(1);
			if (id.equals("repair_kit")) settings.maxCount(1).maxDamage(8);
			Item item = switch (id) {
				case "field_manual" -> new FieldManualItem(settings);
				case "multimeter" -> new MultimeterItem(settings.maxCount(1));
				case "recipe_pattern" -> new dev.rackcraft.storage.PatternItem(settings);
				case "wireless_terminal" -> new dev.rackcraft.storage.WirelessTerminalItem(settings);
				case "drive_1k", "drive_4k", "drive_16k", "drive_64k", "tape_cartridge" -> new dev.rackcraft.storage.DriveItem(settings,
						ContentIds.DRIVE_CAPACITY.get(id), id.equals("tape_cartridge"));
				default -> new Item(settings);
			};
			ITEMS.put(id, Registry.register(Registries.ITEM, Rackcraft.id(id), item));
		}
		ContentIds.FUEL_TICKS.forEach((id, ticks) ->
				FuelRegistry.INSTANCE.add(Registries.ITEM.get(Rackcraft.id(id)), ticks));
	}
}