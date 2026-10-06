package dev.rackcraft;

import net.fabricmc.api.ModInitializer;
import net.minecraft.item.ItemGroup;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.util.Identifier;
import net.minecraft.text.Text;
import dev.rackcraft.world.SimTicker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class Rackcraft implements ModInitializer {
	public static final String MOD_ID = "rackcraft";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		RackcraftConfig.load();
		RcBlocks.register();
		RcItems.register();
		ItemGroup group = net.fabricmc.fabric.api.itemgroup.v1.FabricItemGroup.builder()
				.displayName(Text.literal("Rackcraft"))
				.icon(() -> new ItemStack(RcBlocks.get("server_rack")))
				.entries((context, entries) -> {
					RcBlocks.BLOCKS.values().forEach(block -> entries.add(block.asItem()));
					RcItems.ITEMS.values().forEach(entries::add);
				})
				.build();
		Registry.register(Registries.ITEM_GROUP, id("main"), group);
		Worldgen.register();
		SimTicker.register();
		dev.rackcraft.world.CableUpgrader.register();
		RackcraftCommands.register();
		RackcraftNetworking.registerServer();
		ExchangeCatalog.register();
		RackcraftSelfTest.register();
		LOGGER.info("Rackcraft initialised");
	}

	public static Identifier id(String path) {
		return new Identifier(MOD_ID, path);
	}
}