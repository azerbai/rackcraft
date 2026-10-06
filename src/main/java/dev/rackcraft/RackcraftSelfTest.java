package dev.rackcraft;

import dev.rackcraft.block.MachineBlockEntity;
import dev.rackcraft.block.CableBlock;
import dev.rackcraft.block.MachineBlock;
import dev.rackcraft.generated.ContentIds;
import net.minecraft.registry.Registries;
import net.minecraft.block.Blocks;
import java.util.List;
import dev.rackcraft.world.AbandonedDataCenterFeature;
import dev.rackcraft.block.CableBlockEntity;
import dev.rackcraft.block.RackStatus;
import net.minecraft.block.ConnectingBlock;
import net.minecraft.util.math.Direction;
import net.fabricmc.fabric.api.registry.FuelRegistry;
import dev.rackcraft.world.FacilityManager;
import dev.rackcraft.world.SimTicker;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

public final class RackcraftSelfTest {
	private RackcraftSelfTest() {}

	public static void register() {
		ServerLifecycleEvents.SERVER_STARTED.register(server -> {
			if (Boolean.getBoolean("rackcraft.selftest")) run(server);
		});
	}

	private static void run(MinecraftServer server) {
		int[] failures = {0};
		check("S0.a", RcBlocks.BLOCKS.size() == 34 && RcItems.ITEMS.size() == 33,
				"blocks=" + RcBlocks.BLOCKS.size() + " items=" + RcItems.ITEMS.size(), failures);
		ServerWorld world = server.getOverworld();
		BlockPos generatorPos = new BlockPos(0, 80, 0);
		BlockPos cablePos = generatorPos.east();
		BlockPos rackPos = cablePos.east();
		BlockPos routerPos = rackPos.up();
		world.getChunk(generatorPos);
		world.setBlockState(generatorPos, RcBlocks.get("diesel_generator").getDefaultState());
		// Place the cable the way a player would, so it picks up its connections.
		CableBlock cableBlock = (CableBlock) RcBlocks.get("power_cable");
		world.setBlockState(cablePos, cableBlock.withConnections(cableBlock.getDefaultState(), world, cablePos));
		world.setBlockState(rackPos, RcBlocks.get("server_rack").getDefaultState());
		world.setBlockState(routerPos, RcBlocks.get("uplink_router").getDefaultState());
		MachineBlockEntity generator = machine(world, generatorPos);
		MachineBlockEntity rack = machine(world, rackPos);
		generator.setStack(0, new ItemStack(Items.COAL));
		for (int slot = 0; slot < 8; slot++) rack.setStack(slot, new ItemStack(RcItems.ITEMS.get("server_1u")));
		SimTicker.stepNow(world);
		for (int step = 1; step < 40; step++) SimTicker.stepNow(world);
		check("S1.a", rack.powerSatisfaction() > 0.99 && FacilityManager.get(world).credits() > 0,
				"power=" + rack.powerSatisfaction() + " credits=" + FacilityManager.get(world).credits(), failures);
		check("S1.b", generator.getCachedState().get(MachineBlock.LIT) && rack.getCachedState().get(MachineBlock.LIT),
				"generatorLit=" + generator.getCachedState().get(MachineBlock.LIT)
						+ " rackLit=" + rack.getCachedState().get(MachineBlock.LIT), failures);
		check("S5.a", FuelRegistry.INSTANCE.get(RcItems.ITEMS.get("coke")) == 3200
						&& FuelRegistry.INSTANCE.get(RcBlocks.get("coke_block").asItem()) == 32000
						&& FuelRegistry.INSTANCE.get(RcItems.ITEMS.get("biodiesel_canister")) == 9600,
				"coke=" + FuelRegistry.INSTANCE.get(RcItems.ITEMS.get("coke")), failures);
		check("S5.b", server.getRecipeManager().get(Rackcraft.id("biomass_pellet")).isPresent()
						&& server.getRecipeManager().get(Rackcraft.id("biodiesel_canister")).isPresent()
						&& server.getRecipeManager().get(Rackcraft.id("coke")).isPresent()
						&& server.getRecipeManager().get(Rackcraft.id("field_manual")).isPresent(),
				"rackcraft recipes=" + server.getRecipeManager().values().stream()
						.filter(recipe -> recipe.getId().getNamespace().equals(Rackcraft.MOD_ID)).count(), failures);
		var cableState = world.getBlockState(cablePos);
		check("S6.a", cableState.get(ConnectingBlock.FACING_PROPERTIES.get(Direction.WEST))
						&& cableState.get(ConnectingBlock.FACING_PROPERTIES.get(Direction.EAST))
						&& !cableState.get(ConnectingBlock.FACING_PROPERTIES.get(Direction.UP))
						&& world.getBlockEntity(cablePos) instanceof CableBlockEntity,
				"cable=" + cableState, failures);
		check("S6.b", rack.rackStatus() == RackStatus.MINING && rack.miningRate() > 15.9
						&& FacilityManager.get(world).miningRacks() >= 1, // the dev world keeps earlier runs' racks
				"status=" + rack.rackStatus() + " rate=" + rack.miningRate(), failures);
		check("S6.c", generator.powerKw() > 0 && generator.fuelBurnTotal() >= generator.fuelBurnTicks()
						&& generator.networkDemandKw() > 0 && generator.networkCapacityKw() >= 40,
				"output=" + generator.powerKw() + " demand=" + generator.networkDemandKw()
						+ " capacity=" + generator.networkCapacityKw(), failures);
		check("S6.d", ExchangeOffers.all().values().stream().allMatch(offer -> offer.item() != null
						&& offer.item() != Items.AIR) && ExchangeOffers.all().get("diamond").price() == 1000,
				"offers=" + ExchangeOffers.all().size(), failures);
		BlockPos lavaGeneratorPos = generatorPos.north(4);
		world.setBlockState(lavaGeneratorPos, RcBlocks.get("diesel_generator").getDefaultState());
		MachineBlockEntity lavaGenerator = machine(world, lavaGeneratorPos);
		lavaGenerator.setFuelBurnTicks(0); // the dev world persists between runs
		lavaGenerator.setStack(0, new ItemStack(Items.LAVA_BUCKET));
		SimTicker.stepNow(world);
		check("S5.c", lavaGenerator.getStack(0).isOf(Items.BUCKET) && lavaGenerator.fuelBurnTicks() > 0,
				"slot=" + lavaGenerator.getStack(0) + " burn=" + lavaGenerator.fuelBurnTicks(), failures);
		CableBlock.setCut(world, cablePos, true);
		SimTicker.stepNow(world);
		check("S3.e", rack.powerSatisfaction() < 0.01 && world.getBlockState(cablePos).get(CableBlock.CUT),
				"power=" + rack.powerSatisfaction(), failures);
		check("S6.e", rack.rackStatus() != RackStatus.MINING && rack.miningRate() == 0,
				"statusAfterCut=" + rack.rackStatus(), failures);
		checkDataCenter(world, failures);
		checkCreative(world, failures);
		checkStorage(world, failures);
		Rackcraft.LOGGER.info("RACKCRAFT_SELFTEST DONE failures={}", failures[0]);
		server.stop(false);
	}

	/** The abandoned data center is a tutorial: it must be broken in exactly the advertised way, and fixable. */
	private static void checkDataCenter(ServerWorld world, int[] failures) {
		BlockPos base = new BlockPos(64, 120, 64);
		world.getChunk(base);
		world.getChunk(base.add(8, 0, 6));
		AbandonedDataCenterFeature.place(world, base, world.getRandom(), true);
		BlockPos power = base.add(2, 0, 3);
		BlockPos fiber = base.add(5, 0, 3);
		MachineBlockEntity rackA = machine(world, base.add(3, 0, 3));
		MachineBlockEntity rackB = machine(world, base.add(4, 0, 3));
		for (int step = 0; step < 4; step++) SimTicker.stepNow(world);
		check("S7.a", world.getBlockState(power).get(CableBlock.CUT) && world.getBlockState(fiber).get(CableBlock.CUT)
						&& !rackA.rackStatus().mining() && rackA.modules().size() == 4 && rackB.modules().size() == 4
						&& world.getBlockState(base.add(7, 0, 1)).isOf(RcBlocks.get("crypto_exchange")),
				"before repair: " + rackA.rackStatus(), failures);
		boolean kit = false;
		boolean log = false;
		if (world.getBlockEntity(base.add(1, 0, 1)) instanceof net.minecraft.block.entity.ChestBlockEntity chest) {
			for (int slot = 0; slot < chest.size(); slot++) {
				kit |= chest.getStack(slot).isOf(RcItems.ITEMS.get("repair_kit"));
				log |= chest.getStack(slot).isOf(Items.WRITTEN_BOOK) && chest.getStack(slot).hasNbt();
			}
		}
		check("S7.b", kit && log, "repairKit=" + kit + " maintenanceLog=" + log, failures);
		CableBlock.setCut(world, power, false);
		CableBlock.setCut(world, fiber, false);
		for (int step = 0; step < 40; step++) SimTicker.stepNow(world);
		check("S7.c", rackA.rackStatus().mining() && rackB.rackStatus().mining()
						&& rackA.miningRate() + rackB.miningRate() > 6.9,
				"after repair: " + rackA.rackStatus() + "/" + rackB.rackStatus() + " rate="
						+ (rackA.miningRate() + rackB.miningRate()) + " inlet=" + rackA.inletCelsius(), failures);
		check("S7.d", ExchangeCatalog.prices().size() > 900 && ExchangeCatalog.price(Items.DIAMOND) == 1000
						&& ExchangeCatalog.price(Items.DIAMOND_SWORD) != null && ExchangeCatalog.price(Items.DIAMOND_SWORD) > 2000
						&& ExchangeCatalog.price(Items.NETHERITE_SWORD) != null
						&& ExchangeCatalog.price(Items.NETHERITE_SWORD) > ExchangeCatalog.price(Items.DIAMOND_SWORD)
						&& ExchangeCatalog.price(Items.COMMAND_BLOCK) == null && ExchangeCatalog.price(Items.BEDROCK) == null
						&& ExchangeCatalog.price(Items.RED_SHULKER_BOX) >= ExchangeCatalog.price(Items.SHULKER_BOX)
						&& ExchangeCatalog.price(Items.WAXED_OXIDIZED_COPPER) > ExchangeCatalog.price(Items.COPPER_BLOCK),
				"catalog=" + ExchangeCatalog.prices().size() + " diamondSword=" + ExchangeCatalog.price(Items.DIAMOND_SWORD)
						+ " netheriteSword=" + ExchangeCatalog.price(Items.NETHERITE_SWORD)
						+ " oakPlanks=" + ExchangeCatalog.price(Items.OAK_PLANKS) + " beacon=" + ExchangeCatalog.price(Items.BEACON)
						+ " redShulker=" + ExchangeCatalog.price(Items.RED_SHULKER_BOX) + " waxedOxidized=" + ExchangeCatalog.price(Items.WAXED_OXIDIZED_COPPER),
				failures);
	}

	private static void checkCreative(ServerWorld world, int[] failures) {
		BlockPos power = new BlockPos(-64, 120, -64);
		BlockPos rack = power.east();
		BlockPos cooler = power.south(3);
		world.getChunk(power);
		world.setBlockState(power, RcBlocks.get("creative_power").getDefaultState());
		world.setBlockState(rack, RcBlocks.get("creative_rack").getDefaultState());
		world.setBlockState(cooler, RcBlocks.get("creative_cooler").getDefaultState());
		MachineBlockEntity source = machine(world, power);
		MachineBlockEntity creativeRack = machine(world, rack);
		MachineBlockEntity coolerEntity = machine(world, cooler);
		source.setCreativeValue(CreativeSettings.OUTPUT_KW, 50);
		creativeRack.setCreativeValue(CreativeSettings.MINING_RATE, 250);
		creativeRack.setCreativeValue(CreativeSettings.DRAW_KW, 30);
		coolerEntity.setCreativeValue(CreativeSettings.TARGET_C, 35);
		long before = FacilityManager.get(world).credits();
		for (int step = 0; step < 10; step++) SimTicker.stepNow(world);
		long earned = FacilityManager.get(world).credits() - before;
		check("S8.a", Math.abs(source.powerKw() - 30) < 0.01 && creativeRack.powerSatisfaction() > 0.99,
				"output=" + source.powerKw() + " satisfaction=" + creativeRack.powerSatisfaction(), failures);
		check("S8.b", creativeRack.miningRate() == 250 && earned >= 1000,
				"rate=" + creativeRack.miningRate() + " earned=" + earned, failures);
		check("S8.c", creativeRack.setCreativeValue(CreativeSettings.MINING_RATE, -5)
						&& creativeRack.creativeValue(CreativeSettings.MINING_RATE) == 0
						&& !creativeRack.setCreativeValue("not_a_setting", 1),
				"clamped=" + creativeRack.creativeValue(CreativeSettings.MINING_RATE), failures);
		BlockPos front = cooler.offset(coolerEntity.getCachedState().get(MachineBlock.FACING));
		double frontTemperature = SimTicker.temperatureAt(world, front);
		check("S8.d", Math.abs(frontTemperature - 35) < 0.01, "front=" + frontTemperature, failures);
		boolean creativeSold = ContentIds.CREATIVE_IDS.stream()
				.anyMatch(id -> ExchangeCatalog.price(RcBlocks.get(id).asItem()) != null);
		check("S8.e", !creativeSold && !ExchangeOffers.all().values().stream()
						.anyMatch(offer -> ContentIds.CREATIVE_IDS.contains(Registries.ITEM.getId(offer.item()).getPath())),
				"creativeSold=" + creativeSold, failures);
		try {
			java.nio.file.Files.writeString(java.nio.file.Path.of("rackcraft_catalog.txt"), ExchangeCatalog.prices().entrySet().stream()
					.map(entry -> Registries.ITEM.getId(entry.getKey()) + " " + entry.getValue())
					.collect(java.util.stream.Collectors.joining("\n")));
		} catch (java.io.IOException ignored) {
			// The dump is a review aid only.
		}
	}

	/** Storage end to end: hot then cold, archiving, autocrafting on rack compute, transmitters, power loss. */
	private static void checkStorage(ServerWorld world, int[] failures) {
		BlockPos power = new BlockPos(-128, 120, -128);
		BlockPos array = power.east();
		BlockPos library = array.east();
		BlockPos terminal = library.east();
		BlockPos transmitter = library.up();
		BlockPos rack = array.up();
		world.getChunk(power);
		for (BlockPos pos : List.of(power, array, library, terminal, transmitter, rack)) world.setBlockState(pos, Blocks.AIR.getDefaultState());
		world.setBlockState(power, RcBlocks.get("creative_power").getDefaultState());
		world.setBlockState(array, RcBlocks.get("storage_array").getDefaultState());
		world.setBlockState(library, RcBlocks.get("tape_library").getDefaultState());
		world.setBlockState(terminal, RcBlocks.get("storage_terminal").getDefaultState());
		world.setBlockState(transmitter, RcBlocks.get("wireless_transmitter").getDefaultState());
		world.setBlockState(rack, RcBlocks.get("server_rack").getDefaultState());
		MachineBlockEntity arrayEntity = machine(world, array);
		MachineBlockEntity libraryEntity = machine(world, library);
		MachineBlockEntity rackEntity = machine(world, rack);
		MachineBlockEntity source = machine(world, power);
		ItemStack drive = new ItemStack(RcItems.ITEMS.get("drive_1k"));
		arrayEntity.setStack(0, drive);
		libraryEntity.setStack(0, new ItemStack(RcItems.ITEMS.get("tape_cartridge")));
		for (int slot = 0; slot < 4; slot++) rackEntity.setStack(slot, new ItemStack(RcItems.ITEMS.get("pi_node")));
		rackEntity.setStack(4, new ItemStack(RcItems.ITEMS.get("server_1u")));
		rackEntity.setStack(5, new ItemStack(RcItems.ITEMS.get("server_1u")));
		for (int step = 0; step < 4; step++) SimTicker.stepNow(world);

		var network = dev.rackcraft.storage.StorageService.networkAt(world, terminal);
		var cobble = dev.rackcraft.storage.ItemKey.of(Items.COBBLESTONE);
		long first = network.insert(cobble, 1000, false);
		long second = network.insert(cobble, 500, false);
		check("S9.a", arrayEntity.storageOnline() && libraryEntity.storageOnline() && first == 1000 && second == 500
						&& network.count(cobble, false) == 1024 && network.count(cobble, true) == 1500,
				"online=" + arrayEntity.storageOnline() + "/" + libraryEntity.storageOnline() + " hot="
						+ network.count(cobble, false) + " all=" + network.count(cobble, true), failures);
		check("S9.b", drive.hasNbt() && drive.getNbt().getLong("Used") == 1024, "driveSummary=" + drive.getNbt(), failures);
		long archived = network.archive(0.85, 0.7);
		check("S9.c", archived > 0 && network.hotUsed() <= 1024 * 0.7 && network.count(cobble, true) == 1500,
				"archived=" + archived + " hotUsed=" + network.hotUsed(), failures);

		// Two-step autocraft: logs -> planks -> sticks, run on the rack's compute.
		network.insert(dev.rackcraft.storage.ItemKey.of(Items.OAK_LOG), 4, false);
		network.insert(dev.rackcraft.storage.ItemKey.of(pattern(List.of(Items.OAK_LOG), new ItemStack(Items.OAK_PLANKS, 4))), 1, false);
		network.insert(dev.rackcraft.storage.ItemKey.of(pattern(java.util.Arrays.asList(Items.OAK_PLANKS, null, null, Items.OAK_PLANKS),
				new ItemStack(Items.STICK, 4))), 1, false);
		var access = new dev.rackcraft.storage.StorageService.Access(world.getRegistryKey(), terminal, false);
		var plan = dev.rackcraft.storage.Autocrafter.start(world.getServer(), java.util.UUID.randomUUID(), access,
				dev.rackcraft.storage.ItemKey.of(Items.STICK), 16);
		SimTicker.stepNow(world);
		boolean lent = rackEntity.rackStatus() == dev.rackcraft.block.RackStatus.CRAFTING && rackEntity.miningRate() == 0;
		for (int step = 0; step < 30; step++) SimTicker.stepNow(world);
		long sticks = network.count(dev.rackcraft.storage.ItemKey.of(Items.STICK), true);
		long logs = network.count(dev.rackcraft.storage.ItemKey.of(Items.OAK_LOG), true);
		check("S9.d", plan.ok() && plan.totalCrafts() == 6 && lent && sticks == 16 && logs == 2
						&& dev.rackcraft.storage.Autocrafter.jobs(world.getServer(), access).isEmpty()
						&& rackEntity.rackStatus() != dev.rackcraft.block.RackStatus.CRAFTING,
				"plan=" + plan.totalCrafts() + " missing=" + plan.missing() + " lent=" + lent + " sticks=" + sticks
						+ " logs=" + logs + " rackNow=" + rackEntity.rackStatus(), failures);

		var link = dev.rackcraft.storage.StorageState.get(world.getServer()).transmitter(
				dev.rackcraft.storage.StorageState.transmitterKey(world.getRegistryKey(), transmitter));
		check("S9.e", link != null && link.online() && link.drives().size() == 2
						&& dev.rackcraft.storage.TransmitterUpgrades.drawKw(7) == 64,
				"transmitter=" + link, failures);
		source.setCreativeValue(CreativeSettings.OUTPUT_KW, 0);
		for (int step = 0; step < 3; step++) SimTicker.stepNow(world);
		check("S9.f", !arrayEntity.storageOnline() && dev.rackcraft.storage.StorageService.networkAt(world, terminal).isEmpty(),
				"onlineWithoutPower=" + arrayEntity.storageOnline(), failures);
		source.setCreativeValue(CreativeSettings.OUTPUT_KW, 1000);
	}

	private static ItemStack pattern(List<net.minecraft.item.Item> grid, ItemStack output) {
		List<ItemStack> stacks = new java.util.ArrayList<>();
		for (int slot = 0; slot < 9; slot++) {
			net.minecraft.item.Item item = slot < grid.size() ? grid.get(slot) : null;
			stacks.add(item == null ? ItemStack.EMPTY : new ItemStack(item));
		}
		ItemStack pattern = new ItemStack(RcItems.ITEMS.get("recipe_pattern"));
		dev.rackcraft.storage.PatternItem.encode(pattern, stacks, output);
		return pattern;
	}

	private static MachineBlockEntity machine(ServerWorld world, BlockPos pos) {
		if (world.getBlockEntity(pos) instanceof MachineBlockEntity machine) return machine;
		throw new IllegalStateException("Expected Rackcraft machine at " + pos);
	}

	private static void check(String id, boolean passed, String details, int[] failures) {
		if (!passed) failures[0]++;
		Rackcraft.LOGGER.info("RACKCRAFT_SELFTEST {} {} {}", id, passed ? "PASS" : "FAIL", details);
	}
}