package dev.rackcraft;

import dev.rackcraft.block.MachineBlockEntity;
import dev.rackcraft.block.CableBlock;
import dev.rackcraft.block.MachineBlock;
import dev.rackcraft.generated.ContentIds;
import net.minecraft.registry.Registries;
import net.minecraft.block.Blocks;
import java.util.List;
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
		// Racks boot instantly here, so the other checks don't wait; checkBoot turns it back on.
		RackcraftConfig.values.sim.rackBootScale = 0;
		// Random events would break modules and reboot racks mid-check; checkResearch fires them on purpose.
		RackcraftConfig.values.events.enabled = false;
		dev.rackcraft.compute.ResearchLab.get(server.getOverworld()).reset();
		dev.rackcraft.world.OrbitState.get(server.getOverworld()).reset();
		check("S0.a", RcBlocks.BLOCKS.size() == 92 && RcItems.ITEMS.size() == 91,
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
		// Some things only come out of machines: no crafting recipe may make them (a stale one once made Fuel Cells
		// from glowstone and amethyst).
		java.util.Set<net.minecraft.item.Item> madeOnly = new java.util.HashSet<>(java.util.List.of(RcItems.ITEMS.get("fuel_cell"),
				RcItems.ITEMS.get("hydrogen_canister"), RcItems.ITEMS.get("wafer_scale_engine"), RcItems.ITEMS.get("enriched_uranium"),
				RcItems.ITEMS.get("graphene_sheet"), RcItems.ITEMS.get("gallium_nitride")));
		dev.rackcraft.world.AssemblyLine.recipes().forEach(recipe -> madeOnly.add(recipe.product()));
		// Crafting a chiplet down a bin is allowed: it only ever costs a better chiplet.
		java.util.Set<String> downbins = java.util.Set.of("rackcraft:chiplet_bronze_from_silver", "rackcraft:chiplet_silver_from_gold");
		List<String> shortcuts = server.getRecipeManager().values().stream()
				.filter(recipe -> madeOnly.contains(recipe.getOutput(server.getRegistryManager()).getItem())
						&& !downbins.contains(recipe.getId().toString()))
				.map(recipe -> recipe.getId().toString()).toList();
		check("S5.d", shortcuts.isEmpty(), "recipesForMachineOnlyItems=" + shortcuts, failures);
		// The rocket's own sounds: every event registered, and every file sounds.json names shipped in the jar.
		java.util.List<String> missingSounds = new java.util.ArrayList<>();
		try (var stream = RackcraftSelfTest.class.getResourceAsStream("/assets/rackcraft/sounds.json")) {
			var json = com.google.gson.JsonParser.parseString(new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
			for (String event : json.keySet()) {
				if (!Registries.SOUND_EVENT.containsId(Rackcraft.id(event))) missingSounds.add("event " + event);
				for (var sound : json.getAsJsonObject(event).getAsJsonArray("sounds")) {
					String name = sound.getAsJsonObject().get("name").getAsString().replace("rackcraft:", "");
					if (RackcraftSelfTest.class.getResource("/assets/rackcraft/sounds/" + name + ".ogg") == null) missingSounds.add(name);
				}
			}
		} catch (Exception exception) {
			missingSounds.add("sounds.json: " + exception);
		}
		check("S5.e", missingSounds.isEmpty() && RcSounds.ROCKET_LIFTOFF != null, "missing=" + missingSounds, failures);
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
		MachineBlockEntity router = machine(world, routerPos);
		check("S6.f", router.dataBandwidth() == 100 && router.dataRacks() >= 1 && router.dataDemand() >= 16,
				"router: bandwidth=" + router.dataBandwidth() + " racks=" + router.dataRacks() + " demand=" + router.dataDemand(), failures);
		check("S6.d", ExchangeOffers.all().values().stream().allMatch(offer -> offer.item() != null
						&& offer.item() != Items.AIR) && ExchangeOffers.all().get("diamond").price() == 2000,
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
		checkCompute(world, failures);
		checkCooling(world, failures);
		checkReactorArray(world, failures);
		checkItemPipes(world, failures);
		checkNuclear(world, failures);
		checkBoot(world, failures);
		checkSolar(world, failures);
		checkTerminalGrid(world, failures);
		checkPrices(failures);
		checkResearch(world, failures);
		checkDarknet(world, failures);
		checkUtilities(world, failures);
		checkIndustry(world, failures);
		checkLaunch(world, failures);
		checkMegastructures(world, failures);
		checkRenewables(world, failures);
		checkStorageLogistics(world, failures);
		checkSiteConstruction(world, failures);
		checkSiteQuote(world, failures);
		checkLineSpeedAndFeeding(world, failures);
		checkRackFill(world, failures);
		checkAdvancedHardware(world, failures);
		checkBuildingTools(world, failures);
		checkOverclocking(world, failures);
		checkComputePods(world, failures);
		checkBlueprints(world, failures);
		checkRetrofit(world, failures);
		checkPerformance(world, failures);
		checkStructures(world, failures);
		check("S0.b", SimTicker.failedSteps() == 0, "simulation steps that threw=" + SimTicker.failedSteps(), failures);
		Rackcraft.LOGGER.info("RACKCRAFT_SELFTEST DONE failures={}", failures[0]);
		server.stop(false);
	}

	/** The abandoned data center is a tutorial: it must be broken in exactly the advertised way, and fixable. */
	private static void checkDataCenter(ServerWorld world, int[] failures) {
		BlockPos base = new BlockPos(64, 120, 64);
		world.getChunk(base);
		world.getChunk(base.add(8, 0, 6));
		dev.rackcraft.world.structure.DataCenterPiece.buildNow(world, "site_7", base.down(), net.minecraft.util.BlockRotation.NONE, 7);
		BlockPos power = base.add(2, 0, 3);
		BlockPos fiber = base.add(5, 0, 3);
		MachineBlockEntity rackA = machine(world, base.add(3, 0, 3));
		MachineBlockEntity rackB = machine(world, base.add(4, 0, 3));
		for (int step = 0; step < 4; step++) SimTicker.stepNow(world);
		check("S7.a", world.getBlockState(power).get(CableBlock.CUT) && world.getBlockState(fiber).get(CableBlock.CUT)
						&& !rackA.rackStatus().mining() && rackA.modules().size() == 2 && rackB.modules().size() == 1
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
						&& rackA.miningRate() + rackB.miningRate() > 1.4,
				"after repair: " + rackA.rackStatus() + "/" + rackB.rackStatus() + " rate="
						+ (rackA.miningRate() + rackB.miningRate()) + " inlet=" + rackA.inletCelsius(), failures);
		check("S7.d", ExchangeCatalog.prices().size() > 900 && ExchangeCatalog.price(Items.DIAMOND) == 2000
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
						&& dev.rackcraft.storage.TransmitterUpgrades.drawKw(5) == 16 && dev.rackcraft.storage.TransmitterUpgrades.drawKw(7) == 1000
						&& dev.rackcraft.storage.TransmitterUpgrades.next(6).rackCoin() >= 10_000_000,
				"transmitter=" + link, failures);
		source.setCreativeValue(CreativeSettings.OUTPUT_KW, 0);
		for (int step = 0; step < 3; step++) SimTicker.stepNow(world);
		check("S9.f", !arrayEntity.storageOnline() && dev.rackcraft.storage.StorageService.networkAt(world, terminal).isEmpty(),
				"onlineWithoutPower=" + arrayEntity.storageOnline(), failures);
		source.setCreativeValue(CreativeSettings.OUTPUT_KW, 1000);
	}

	/**
	 * The compute market end to end: autocrafting borrows a cluster on another fiber network, water-cooled
	 * modules stop without a pump, a contract is generated and paid, a model trains, kids draw, a shackled
	 * librarian writes, and exhaust fans make smog.
	 */
	private static void checkCompute(ServerWorld world, int[] failures) {
		BlockPos origin = new BlockPos(-256, 150, 256);
		world.getChunk(origin);
		// Clear the test area from earlier runs.
		for (BlockPos pos : BlockPos.iterate(origin.add(-2, -2, -2), origin.add(24, 6, 20))) {
			if (!world.getBlockState(pos).isAir()) world.setBlockState(pos, Blocks.AIR.getDefaultState());
		}
		world.getEntitiesByClass(net.minecraft.entity.passive.VillagerEntity.class,
				new net.minecraft.util.math.Box(origin).expand(40), entity -> true).forEach(net.minecraft.entity.Entity::discard);

		// A storage network (array + terminal) with no racks, and a separate online cluster: power, rack, router.
		BlockPos storagePower = origin;
		BlockPos array = origin.east();
		BlockPos terminal = array.east();
		world.setBlockState(storagePower, RcBlocks.get("creative_power").getDefaultState());
		world.setBlockState(array, RcBlocks.get("storage_array").getDefaultState());
		world.setBlockState(terminal, RcBlocks.get("storage_terminal").getDefaultState());
		BlockPos clusterPower = origin.south(6);
		BlockPos rackPos = clusterPower.east();
		BlockPos router = rackPos.east();
		world.setBlockState(clusterPower, RcBlocks.get("creative_power").getDefaultState());
		world.setBlockState(rackPos, RcBlocks.get("server_rack").getDefaultState());
		world.setBlockState(router, RcBlocks.get("uplink_router").getDefaultState());
		MachineBlockEntity rack = machine(world, rackPos);
		for (int slot = 0; slot < 8; slot++) rack.setStack(slot, new ItemStack(RcItems.ITEMS.get("server_1u")));
		machine(world, array).setStack(0, new ItemStack(RcItems.ITEMS.get("drive_1k")));
		for (int step = 0; step < 4; step++) SimTicker.stepNow(world);
		var network = dev.rackcraft.storage.StorageService.networkAt(world, terminal);
		network.insert(dev.rackcraft.storage.ItemKey.of(Items.OAK_LOG), 2, false);
		network.insert(dev.rackcraft.storage.ItemKey.of(pattern(List.of(Items.OAK_LOG), new ItemStack(Items.OAK_PLANKS, 4))), 1, false);
		var access = new dev.rackcraft.storage.StorageService.Access(world.getRegistryKey(), terminal, false);
		var plan = dev.rackcraft.storage.Autocrafter.start(world.getServer(), java.util.UUID.randomUUID(), access,
				dev.rackcraft.storage.ItemKey.of(Items.OAK_PLANKS), 8);
		SimTicker.stepNow(world);
		boolean borrowed = rack.rackStatus() == RackStatus.CRAFTING;
		for (int step = 0; step < 10; step++) SimTicker.stepNow(world);
		long planks = network.count(dev.rackcraft.storage.ItemKey.of(Items.OAK_PLANKS), true);
		check("C1.a", plan.ok() && borrowed && planks == 8, "borrowed=" + borrowed + " planks=" + planks
				+ " rack=" + rack.rackStatus(), failures);

		// Liquid cooling: a GPU blade stops until its rack is on a coolant loop with a heat sink.
		rack.setStack(7, ItemStack.EMPTY);
		rack.setStack(6, new ItemStack(RcItems.ITEMS.get("gpu_blade")));
		for (int step = 0; step < 3; step++) SimTicker.stepNow(world);
		boolean dry = rack.rackStatus() == RackStatus.NEEDS_WATER;
		BlockPos cooler = rackPos.up();
		world.setBlockState(cooler, RcBlocks.get("dry_cooler").getDefaultState());
		for (int step = 0; step < 3; step++) SimTicker.stepNow(world);
		boolean wet = rack.rackStatus() != RackStatus.NEEDS_WATER && rack.heatToLoopKw() > 0;
		check("C2.a", dry && wet, "dry=" + dry + " rackNow=" + rack.rackStatus() + " toLoop=" + rack.heatToLoopKw()
				+ " cooler=" + machine(world, cooler).coolingKw(), failures);
		world.setBlockState(cooler, Blocks.AIR.getDefaultState());
		rack.setStack(6, new ItemStack(RcItems.ITEMS.get("server_1u")));
		checkFreshWater(world, failures);

		// A contract on the cluster: generate, deliver from the outbox, get paid.
		var market = dev.rackcraft.compute.ComputeMarket.get(world);
		for (var model : market.models(dev.rackcraft.compute.Contract.Kind.IMAGE)) model.trained = Math.max(model.trained, 60);
		var contract = market.postOffer(world.getTime());
		contract.kind = dev.rackcraft.compute.Contract.Kind.IMAGE;
		contract.docType = "image";
		contract.prompt = "a self-test in crayon";
		contract.quantity = 1;
		contract.quality = 25;
		long creditsBefore = FacilityManager.get(world).credits();
		String accepted = market.accept(contract.id, world.getTime());
		market.setGenerating(contract.id, true, dev.rackcraft.compute.Contract.ANY_CLUSTER);
		boolean generating = false;
		for (int step = 0; step < 300 && contract.state == dev.rackcraft.compute.Contract.State.ACCEPTED; step++) {
			SimTicker.stepNow(world);
			generating |= rack.rackStatus() == RackStatus.GENERATING;
		}
		check("C3.a", accepted == null && generating && contract.state == dev.rackcraft.compute.Contract.State.DONE
						&& contract.earned > 0 && FacilityManager.get(world).credits() >= creditsBefore + contract.earned,
				"accepted=" + accepted + " generating=" + generating + " state=" + contract.state + " earned=" + contract.earned
						+ " status=" + contract.status, failures);

		// Training: one queued item almost done finishes on the next step, and only that model learns.
		var flash = market.model("gemerald_flash");
		var pro = market.model("gemerald_pro");
		int trained = flash.trained;
		int proTrained = pro.trained;
		flash.queued = 1;
		flash.progress = flash.spec.trainWork() - 0.1;
		flash.training = true;
		SimTicker.stepNow(world);
		check("C4.a", flash.trained == trained + 1 && flash.queued == 0 && pro.trained == proTrained,
				"trained=" + flash.trained + " queued=" + flash.queued + " pro=" + pro.trained, failures);

		// Models: Auto picks a fast model for easy work and the Pro for work only it can reach; caps differ.
		var lite = market.model("gemerald_flash_lite");
		int[] saved = {lite.trained, flash.trained, pro.trained};
		lite.trained = 40;
		flash.trained = 40;
		pro.trained = 120;
		var easy = new dev.rackcraft.compute.Contract();
		easy.kind = dev.rackcraft.compute.Contract.Kind.TEXT;
		easy.docType = "homework";
		easy.quality = 40;
		var hard = new dev.rackcraft.compute.Contract();
		hard.kind = dev.rackcraft.compute.Contract.Kind.TEXT;
		hard.docType = "legal";
		hard.quality = 90;
		var easyModel = market.modelFor(easy);
		var hardModel = market.modelFor(hard);
		check("C7.a", easyModel.spec.cost() < 1 && hardModel == pro && lite.cap() < flash.cap() && flash.cap() < pro.cap()
						&& market.models().size() == 6,
				"easy=" + easyModel.versionName() + " hard=" + hardModel.versionName() + " caps=" + lite.cap() + "/" + flash.cap()
						+ "/" + pro.cap(), failures);
		lite.trained = saved[0];
		flash.trained = saved[1];
		pro.trained = saved[2];

		// Eight bays, one module each: eight GPU Blades fit; a ninth slot never takes a module.
		ItemStack gpu = new ItemStack(RcItems.ITEMS.get("gpu_blade"));
		boolean fits = true;
		for (int slot = 0; slot < 8; slot++) {
			fits &= rack.isValid(slot, gpu);
			rack.setStack(slot, gpu.copy());
		}
		boolean ninth = rack.isValid(8, gpu);
		int modules = rack.modules().size();
		double demand = dev.rackcraft.sim.ServerModel.calculate(rack.modules(), 100, 1, 20, true, true).demandKw();
		check("C8.a", fits && !ninth && modules == 8 && demand > 20, "fits=" + fits + " ninth=" + ninth + " modules=" + modules
				+ " demandKw=" + demand, failures);
		for (int slot = 0; slot < 8; slot++) rack.setStack(slot, new ItemStack(RcItems.ITEMS.get("server_1u")));

		// A kid draws at a stocked art table; a shackled librarian writes at a desk.
		BlockPos table = origin.add(4, 0, 14);
		BlockPos desk = origin.add(8, 0, 14);
		world.setBlockState(table.down(), Blocks.STONE.getDefaultState());
		world.setBlockState(desk.down(), Blocks.STONE.getDefaultState());
		for (BlockPos pos : BlockPos.iterate(origin.add(2, -1, 15), origin.add(10, -1, 17))) world.setBlockState(pos, Blocks.STONE.getDefaultState());
		world.setBlockState(table, RcBlocks.get("art_table").getDefaultState().with(MachineBlock.FACING, Direction.SOUTH));
		world.setBlockState(desk, RcBlocks.get("writing_desk").getDefaultState().with(MachineBlock.FACING, Direction.SOUTH));
		MachineBlockEntity tableEntity = machine(world, table);
		MachineBlockEntity deskEntity = machine(world, desk);
		tableEntity.setStack(0, new ItemStack(Items.PAPER, 64));
		tableEntity.setStack(1, new ItemStack(RcItems.ITEMS.get("crayons")));
		deskEntity.setStack(0, new ItemStack(Items.PAPER, 64));
		deskEntity.setStack(1, new ItemStack(Items.INK_SAC, 4));
		var kid = net.minecraft.entity.EntityType.VILLAGER.create(world);
		kid.setBaby(true);
		kid.refreshPositionAndAngles(table.getX() + 0.5, table.getY(), table.getZ() + 1.5, 0, 0);
		world.spawnEntity(kid);
		var librarian = net.minecraft.entity.EntityType.VILLAGER.create(world);
		librarian.setVillagerData(librarian.getVillagerData().withProfession(net.minecraft.village.VillagerProfession.LIBRARIAN));
		librarian.refreshPositionAndAngles(desk.getX() + 3.5, desk.getY(), desk.getZ() + 2.5, 0, 0);
		world.spawnEntity(librarian);
		String shackled = dev.rackcraft.compute.TrainingStations.shackle(world, librarian, SimTicker.machines(world));
		for (int step = 0; step < 90; step++) SimTicker.stepNow(world);
		boolean drew = tableEntity.itemsMade() > 0 || !tableEntity.getStack(2).isEmpty();
		boolean kidYoung = kid.isBaby() && kid.getBreedingAge() <= -2400;
		check("C5.a", drew && kidYoung && tableEntity.getStack(0).getCount() < 64,
				"drawings=" + tableEntity.itemsMade() + " out=" + tableEntity.getStack(2) + " kidAge=" + kid.getBreedingAge(), failures);
		boolean tethered = librarian.squaredDistanceTo(net.minecraft.util.math.Vec3d.ofBottomCenter(desk.south())) <= 2.5;
		check("C5.b", shackled == null && deskEntity.getStack(2).isOf(RcItems.ITEMS.get("text_corpus")) && tethered
						&& librarian.getCommandTags().contains(dev.rackcraft.compute.TrainingStations.SHACKLED_TAG),
				"shackle=" + shackled + " out=" + deskEntity.getStack(2) + " tethered=" + tethered, failures);
		kid.discard();
		librarian.discard();

		// Exhaust fans idle (and stay clean) in cool air, and pollute heavily once they have heat to move.
		var air = dev.rackcraft.world.AirQuality.get(world);
		BlockPos fan = origin.add(20, 0, 0);
		air.set(fan, 0);
		world.setBlockState(fan, RcBlocks.get("exhaust_fan").getDefaultState());
		world.setBlockState(fan.east(), RcBlocks.get("creative_power").getDefaultState());
		for (int step = 0; step < 20; step++) SimTicker.stepNow(world);
		float idleSmog = air.smogAt(fan);
		boolean idleLit = world.getBlockState(fan).get(MachineBlock.LIT);
		for (int step = 0; step < 20; step++) {
			SimTicker.setHeat(world, fan.north(), 60);
			SimTicker.stepNow(world);
		}
		float smog = air.smogAt(fan);
		check("C6.a", idleSmog == 0 && !idleLit && smog > 2 && machine(world, fan).coolingKw() > 1,
				"idleSmog=" + idleSmog + " idleLit=" + idleLit + " smog=" + smog + " moved=" + machine(world, fan).coolingKw(), failures);
		air.set(fan, 0);
		world.setBlockState(fan, Blocks.AIR.getDefaultState());

		// A scrubber cleans its chunk; villagers in choking smog cough and are poisoned.
		BlockPos scrubber = fan;
		world.setBlockState(scrubber, RcBlocks.get("smog_scrubber").getDefaultState());
		air.set(scrubber, 80);
		var victim = net.minecraft.entity.EntityType.VILLAGER.create(world);
		victim.refreshPositionAndAngles(scrubber.getX() + 0.5, scrubber.getY(), scrubber.getZ() + 2.5, 0, 0);
		world.spawnEntity(victim);
		SimTicker.stepNow(world);
		air.set(scrubber, 80);
		air.applyEffectsNextStep();
		SimTicker.stepNow(world);
		MachineBlockEntity scrubberEntity = machine(world, scrubber);
		boolean coughing = victim.hasStatusEffect(RcEffects.COUGHING);
		boolean poisoned = victim.hasStatusEffect(net.minecraft.entity.effect.StatusEffects.POISON);
		check("C9.a", scrubberEntity.scrubRate() > 1 && coughing && poisoned, "scrubRate=" + scrubberEntity.scrubRate()
				+ " coughing=" + coughing + " poisoned=" + poisoned, failures);
		victim.discard();
		air.set(scrubber, 0);
		world.setBlockState(scrubber, Blocks.AIR.getDefaultState());
		world.setBlockState(scrubber.east(), Blocks.AIR.getDefaultState());
	}

	/** Clears a box of the test world and returns its corner; the dev world keeps earlier runs. */
	private static BlockPos clearArea(ServerWorld world, BlockPos origin, int dx, int dy, int dz) {
		world.getChunk(origin);
		for (BlockPos pos : BlockPos.iterate(origin.add(-3, -2, -3), origin.add(dx, dy, dz))) {
			if (!world.getBlockState(pos).isAir()) world.setBlockState(pos, Blocks.AIR.getDefaultState());
		}
		return origin;
	}

	private static MachineBlockEntity place(ServerWorld world, BlockPos pos, String id, Direction facing) {
		world.setBlockState(pos, RcBlocks.get(id).getDefaultState().with(MachineBlock.FACING, facing));
		return machine(world, pos);
	}

	private static MachineBlockEntity rack(ServerWorld world, BlockPos pos, String module) {
		MachineBlockEntity rack = place(world, pos, "server_rack", Direction.NORTH);
		for (int slot = 0; slot < 8; slot++) rack.setStack(slot, new ItemStack(RcItems.ITEMS.get(module)));
		return rack;
	}

	/**
	 * Heat: an unpowered rack makes none; a running rack's heat spreads through the air; a rack on a coolant loop
	 * with a Rear-Door Cooler and a Chiller puts all of it into the loop; an overloaded loop spills into the air.
	 */
	private static void checkCooling(ServerWorld world, int[] failures) {
		BlockPos origin = clearArea(world, new BlockPos(-768, 150, -768), 30, 8, 20);
		double ambient = RackcraftConfig.values.thermal.ambientC;

		MachineBlockEntity dark = rack(world, origin, "gpu_blade");
		for (int step = 0; step < 20; step++) SimTicker.stepNow(world);
		check("H1.a", dark.inletCelsius() < ambient + 0.5 && dark.exhaustCelsius() < ambient + 0.5 && dark.heatToAirKw() == 0,
				"unpowered rack: inlet=" + dark.inletCelsius() + " exhaust=" + dark.exhaustCelsius() + " toAir=" + dark.heatToAirKw(), failures);
		// It shows red on its front, and the fault finder lists it.
		var health = world.getBlockState(origin).get(dev.rackcraft.block.RackBlock.HEALTH);
		boolean listed = dev.rackcraft.world.FaultFinder.faults(world, SimTicker.machines(world)).stream()
				.anyMatch(fault -> fault.pos().equals(origin) && fault.severity() == 2);
		check("F1.a", health == dev.rackcraft.block.RackBlock.Health.FAULT && listed, "health=" + health + " listedByFaultFinder=" + listed, failures);

		BlockPos airRackPos = origin.east(6);
		world.setBlockState(airRackPos.west(), RcBlocks.get("creative_power").getDefaultState());
		MachineBlockEntity airRack = rack(world, airRackPos, "server_1u");
		for (int step = 0; step < 30; step++) SimTicker.stepNow(world);
		double twoBack = SimTicker.temperatureAt(world, airRackPos.south(2));
		check("H1.b", airRack.exhaustCelsius() > ambient + 0.5 && twoBack > ambient + 0.1 && airRack.inletCelsius() < 27
						&& airRack.rackStatus() == RackStatus.NO_NETWORK,
				"air-cooled rack: inlet=" + airRack.inletCelsius() + " exhaust=" + airRack.exhaustCelsius()
						+ " twoBack=" + twoBack + " status=" + airRack.rackStatus(), failures);

		BlockPos loopRackPos = origin.east(12);
		world.setBlockState(loopRackPos.west(), RcBlocks.get("creative_power").getDefaultState());
		MachineBlockEntity loopRack = rack(world, loopRackPos, "gpu_blade");
		MachineBlockEntity door = place(world, loopRackPos.south(), "rear_door_cooler", Direction.NORTH);
		MachineBlockEntity chiller = place(world, loopRackPos.up(), "chiller", Direction.NORTH);
		for (int step = 0; step < 10; step++) SimTicker.stepNow(world);
		check("H2.a", loopRack.heatToLoopKw() > 23 && loopRack.heatToAirKw() < 0.1 && door.coolingKw() > 3
						&& chiller.coolingKw() > 23 && chiller.loopCapacityKw() >= 250,
				"loop rack: toLoop=" + loopRack.heatToLoopKw() + " toAir=" + loopRack.heatToAirKw() + " door=" + door.coolingKw()
						+ " chiller=" + chiller.coolingKw() + " loop=" + chiller.loopHeatKw() + "/" + chiller.loopCapacityKw(), failures);

		// Four GPU racks on one Dry Cooler (40 kW, 60 at most): the loop is overloaded and the rest stays in the air.
		world.setBlockState(loopRackPos.up(), Blocks.AIR.getDefaultState());
		world.setBlockState(loopRackPos.south(), Blocks.AIR.getDefaultState());
		MachineBlockEntity second = rack(world, loopRackPos.east(), "gpu_blade");
		MachineBlockEntity third = rack(world, loopRackPos.east(2), "gpu_blade");
		rack(world, loopRackPos.east(3), "gpu_blade");
		MachineBlockEntity dryCooler = place(world, loopRackPos.east().up(), "dry_cooler", Direction.NORTH);
		for (int step = 0; step < 6; step++) SimTicker.stepNow(world);
		check("H2.b", dryCooler.loopHeatKw() > dryCooler.loopCapacityKw() && dryCooler.loopCapacityKw() >= 24
						&& loopRack.heatToAirKw() > 5 && second.heatToLoopKw() < 20,
				"overloaded: loop=" + dryCooler.loopHeatKw() + "/" + dryCooler.loopCapacityKw() + " rackToAir=" + loopRack.heatToAirKw()
						+ " secondToLoop=" + second.heatToLoopKw() + " third=" + third.rackStatus(), failures);
		clearArea(world, origin, 30, 8, 20);
	}

	/** Eight reactors in a 2x2x2 cube run as one array on one shared fuel supply; break the cube and they run alone. */
	private static void checkReactorArray(ServerWorld world, int[] failures) {
		BlockPos origin = clearArea(world, new BlockPos(-768, 150, -640), 10, 6, 10);
		List<MachineBlockEntity> cores = new java.util.ArrayList<>();
		for (BlockPos pos : BlockPos.iterate(origin, origin.add(1, 1, 1))) cores.add(place(world, pos.toImmutable(), "modular_reactor", Direction.NORTH));
		cores.forEach(core -> core.setFuelBurnTicks(0));
		cores.get(5).setStack(0, new ItemStack(RcItems.ITEMS.get("fuel_cell"), 3));
		MachineBlockEntity load = place(world, origin.west(), "creative_rack", Direction.NORTH);
		load.setCreativeValue(CreativeSettings.DRAW_KW, 3000);
		load.setCreativeValue(CreativeSettings.MINING_RATE, 0);
		for (int step = 0; step < 4; step++) SimTicker.stepNow(world);
		boolean formed = cores.stream().allMatch(core -> core.reactorArraySize() == 2 && core.reactorCapacityKw() == 4000);
		// Pooling spreads the spare cells over the cores, so count the whole cube.
		int cells = cores.stream().mapToInt(core -> core.getStack(0).getCount()).sum();
		int ticks = cores.get(0).arrayFuelTicks();
		check("R1.a", formed && load.powerSatisfaction() > 0.99 && cells == 2 && ticks > 0
						&& ticks < dev.rackcraft.world.ReactorArrays.FUEL_CELL_TICKS && cores.get(3).powerKw() > 2999,
				"formed=" + formed + " supplied=" + load.powerSatisfaction() + " cellsLeft=" + cells + " fuelTicks=" + ticks
						+ " output=" + cores.get(3).powerKw() + " efficiency=" + dev.rackcraft.world.ReactorArrays.efficiency(2), failures);
		boolean formedState = cores.stream().allMatch(core -> world.getBlockState(core.getPos()).get(dev.rackcraft.block.ArrayMachineBlock.FORMED));
		int most = cores.stream().mapToInt(core -> core.getStack(0).getCount()).max().orElse(0);
		check("R2.a", formedState && most <= 1 && cores.stream().mapToInt(core -> core.getStack(0).getCount()).sum() == 2,
				"formed=" + formedState + " fuel per core=" + cores.stream().map(core -> core.getStack(0).getCount()).toList(), failures);
		// Burn the current cell out: it comes back as Spent Fuel. Fill every waste slot and the next one can't go anywhere.
		cores.get(0).setFuelBurnTicks(1);
		SimTicker.stepNow(world);
		int spent = dev.rackcraft.world.ReactorArrays.count(cores, 1, RcItems.ITEMS.get("spent_fuel"));
		for (MachineBlockEntity core : cores) core.setStack(1, new ItemStack(RcItems.ITEMS.get("spent_fuel"), 16));
		cores.get(0).setFuelBurnTicks(1);
		for (int step = 0; step < 3; step++) SimTicker.stepNow(world);
		boolean stopped = cores.get(0).pendingWaste() == 1 && cores.get(3).powerKw() == 0
				&& cores.get(3).processStatus() == dev.rackcraft.world.ReactorArrays.ReactorStatus.WASTE_FULL.ordinal();
		check("R2.b", spent == 1 && stopped, "spentAfterOneCell=" + spent + " pending=" + cores.get(0).pendingWaste()
				+ " output=" + cores.get(3).powerKw() + " status=" + cores.get(3).processStatus(), failures);
		for (MachineBlockEntity core : cores) core.setStack(1, ItemStack.EMPTY);
		SimTicker.stepNow(world);
		check("R2.c", cores.get(0).pendingWaste() == 0 && cores.get(3).powerKw() > 0,
				"after emptying the waste: pending=" + cores.get(0).pendingWaste() + " output=" + cores.get(3).powerKw(), failures);
		world.setBlockState(origin.add(1, 1, 1), Blocks.AIR.getDefaultState());
		SimTicker.stepNow(world);
		check("R1.b", cores.get(0).reactorArraySize() == 1 && cores.get(0).reactorCapacityKw() == 500,
				"after breaking the cube: size=" + cores.get(0).reactorArraySize() + " capacity=" + cores.get(0).reactorCapacityKw(), failures);
		clearArea(world, origin, 10, 6, 10);
	}

	/** Each nuclear processing machine as a powered 2x2x2: inputs in one core are shared, and it makes its product. */
	private static void checkNuclear(ServerWorld world, int[] failures) {
		String[][] runs = {
				{"uranium_mill", "raw_uranium", "16", "", "0", "yellowcake"},
				{"gas_centrifuge", "yellowcake", "16", "", "0", "enriched_uranium"},
				{"fuel_fabricator", "enriched_uranium", "4", "steel_ingot", "4", "fuel_cell"},
				{"cask_sealer", "spent_fuel", "8", "depleted_uranium", "8", "waste_cask"},
				{"silicon_foundry", "quartz", "8", "sand", "16", "silicon"},
				{"ewaste_recycler", "failed_module", "1", "", "0", "silicon"}};
		for (int index = 0; index < runs.length; index++) {
			String[] run = runs[index];
			BlockPos origin = clearArea(world, new BlockPos(-768 + index * 12, 150, -384), 6, 6, 6);
			world.setBlockState(origin.west(), RcBlocks.get("creative_power").getDefaultState());
			List<MachineBlockEntity> cores = new java.util.ArrayList<>();
			for (BlockPos pos : BlockPos.iterate(origin, origin.add(1, 1, 1))) cores.add(place(world, pos.toImmutable(), run[0], Direction.NORTH));
			cores.get(6).setStack(0, new ItemStack(item(run[1]), Integer.parseInt(run[2])));
			if (!run[3].isEmpty()) cores.get(2).setStack(1, new ItemStack(item(run[3]), Integer.parseInt(run[4])));
			for (int step = 0; step < 40; step++) SimTicker.stepNow(world);
			net.minecraft.item.Item product = item(run[5]);
			int made = dev.rackcraft.world.ReactorArrays.count(cores, 2, product);
			boolean formed = world.getBlockState(origin).get(dev.rackcraft.block.ArrayMachineBlock.FORMED);
			check("N1." + run[0], made > 0 && formed && cores.get(0).reactorArraySize() == 2,
					"made " + made + " " + run[5] + ", status=" + cores.get(0).processStatus() + " formed=" + formed
							+ " power=" + cores.get(0).powerSatisfaction() + " inputsLeft=" + cores.stream().map(core -> core.getStack(0).getCount()).toList(), failures);
			// Everything the cube made gathers in its port, the bottom north-west corner, and one click empties the cube.
			MachineBlockEntity port = machine(world, origin);
			boolean marked = world.getBlockState(origin).get(dev.rackcraft.block.ArrayMachineBlock.PORT)
					&& cores.stream().filter(core -> core != port).noneMatch(core -> world.getBlockState(core.getPos())
							.get(dev.rackcraft.block.ArrayMachineBlock.PORT));
			int inPort = port.getStack(2).getCount();
			var collector = net.fabricmc.fabric.api.entity.FakePlayer.get(world);
			collector.getInventory().clear();
			int collected = dev.rackcraft.world.ReactorArrays.collect(dev.rackcraft.world.ReactorArrays.arrayOf(world, port), collector);
			check("P3." + run[0], marked && inPort == made && port.cubePort() && collected >= made
							&& collector.getInventory().count(product) >= made && dev.rackcraft.world.ReactorArrays.count(cores, 2, product) == 0,
					"marked=" + marked + " inPort=" + inPort + " made=" + made + " collected=" + collected, failures);
			collector.getInventory().clear();
			clearArea(world, origin, 6, 6, 6);
		}
		BlockPos lone = clearArea(world, new BlockPos(-768, 150, -360), 4, 4, 4);
		world.setBlockState(lone.west(), RcBlocks.get("creative_power").getDefaultState());
		MachineBlockEntity single = place(world, lone, "uranium_mill", Direction.NORTH);
		single.setStack(0, new ItemStack(item("raw_uranium"), 4));
		for (int step = 0; step < 6; step++) SimTicker.stepNow(world);
		check("N1.single", single.getStack(2).isEmpty() && single.processStatus() == dev.rackcraft.world.NuclearProcessing.Status.NOT_FORMED.ordinal(),
				"a lone mill does nothing: out=" + single.getStack(2) + " status=" + single.processStatus(), failures);
		clearArea(world, lone, 4, 4, 4);
		// Battery Banks in a 2x2x2 charge as one Grid-Scale Battery, shared evenly, at the 2-cube's 92% efficiency.
		BlockPos bank = clearArea(world, new BlockPos(-720, 150, -360), 4, 4, 4);
		world.setBlockState(bank.west(), RcBlocks.get("creative_power").getDefaultState());
		List<MachineBlockEntity> banks = new java.util.ArrayList<>();
		for (BlockPos pos : BlockPos.iterate(bank, bank.add(1, 1, 1))) banks.add(place(world, pos.toImmutable(), "battery_bank", Direction.NORTH));
		banks.forEach(member -> member.setChargeKws(0));
		SimTicker.stepNow(world);
		double first = banks.stream().mapToDouble(MachineBlockEntity::chargeKws).sum();
		for (int step = 0; step < 4; step++) SimTicker.stepNow(world);
		double total = banks.stream().mapToDouble(MachineBlockEntity::chargeKws).sum();
		double expected = 4 * 0.5 * 8 * 15 * SimTicker.batteryEfficiency(2);
		boolean even = banks.stream().allMatch(member -> Math.abs(member.chargeKws() - total / 8) < 1e-6);
		boolean formedBattery = world.getBlockState(bank).get(dev.rackcraft.block.ArrayMachineBlock.FORMED);
		check("B1.a", formedBattery && even && Math.abs(total - first - expected) < 0.5 && Math.abs(banks.get(0).reactorCapacityKw() - 8 * 3300) < 0.01,
				"formed=" + formedBattery + " even=" + even + " charged=" + (total - first) + " expected=" + expected
						+ " capacity=" + banks.get(0).reactorCapacityKw(), failures);
		clearArea(world, bank, 4, 4, 4);
		var placed = world.getRegistryManager().get(net.minecraft.registry.RegistryKeys.PLACED_FEATURE);
		check("N2.a", placed.containsId(Rackcraft.id("uranium_ore")) && RcBlocks.BLOCKS.containsKey("uranium_ore"),
				"uranium ore feature registered=" + placed.containsId(Rackcraft.id("uranium_ore")), failures);
	}

	/**
	 * R&D: a research project borrows racks and pays off; a frontier run needs one big cluster and rolls back to its
	 * checkpoint when the cluster loses power; a lease is served and paid; the Wafer Fab waits for its research; and
	 * events now do what they say. Research is reset afterwards, since the dev world keeps it.
	 */
	private static void checkResearch(ServerWorld world, int[] failures) {
		var lab = dev.rackcraft.compute.ResearchLab.get(world);
		var market = dev.rackcraft.compute.ComputeMarket.get(world);
		FacilityManager facility = FacilityManager.get(world);
		lab.reset();
		BlockPos origin = clearArea(world, new BlockPos(-1280, 150, -1280), 16, 8, 16);

		// A research project runs on idle racks and its effect applies when it's done.
		BlockPos generalPower = origin;
		world.setBlockState(generalPower, RcBlocks.get("creative_power").getDefaultState());
		MachineBlockEntity crafter = rack(world, generalPower.east(), "crafting_coprocessor");
		facility.addCredits(1_000_000);
		long before = facility.credits();
		String problem = lab.start(world, "firmware");
		long paid = before - facility.credits();
		for (int step = 0; step < 4; step++) SimTicker.stepNow(world);
		double progress = lab.progress("firmware");
		check("RD1.a", problem == null && paid == 50_000 && progress > 0 && crafter.rackStatus() == RackStatus.RESEARCHING,
				"problem=" + problem + " paid=" + paid + " progress=" + progress + " rack=" + crafter.rackStatus(), failures);
		lab.addWork(world, dev.rackcraft.compute.Research.get("firmware"), 1e9);
		check("RD1.b", lab.done("firmware") && lab.effects().bootScale() == 0.5 && lab.effects().mining() > 1.04 && lab.active().isEmpty(),
				"done=" + lab.done("firmware") + " effects=" + lab.effects(), failures);
		String locked = lab.start(world, "thermal_envelope");
		check("RD1.c", locked != null && locked.contains("Coolant Chemistry"), "locked=" + locked, failures);

		// A frontier run on one Wafer-Scale Engine rack (1,200 AI), cooled by a chiller on top. Machines touching each
		// other share a power network, so the one creative source powers both, and removing it stops both.
		BlockPos aiPower = origin.south(6);
		world.setBlockState(aiPower, RcBlocks.get("creative_power").getDefaultState());
		BlockPos aiRackPos = aiPower.east();
		MachineBlockEntity aiRack = rack(world, aiRackPos, "wafer_scale_engine");
		world.setBlockState(aiRackPos.up(), RcBlocks.get("chiller").getDefaultState());
		world.setBlockState(aiRackPos.down(), RcBlocks.get("uplink_router").getDefaultState());
		for (String need : List.of("synthetic_data", "distillation", "enterprise_sales")) {
			lab.complete(world, dev.rackcraft.compute.Research.get(need));
		}
		var run = dev.rackcraft.compute.Research.get("frontier_1");
		facility.addCredits(run.credits());
		String started = lab.start(world, "frontier_1");
		for (int step = 0; step < 3; step++) SimTicker.stepNow(world);
		boolean training = aiRack.rackStatus() == RackStatus.RESEARCHING && lab.frontierRate >= run.minCluster();
		lab.addWork(world, run, run.work(0) * 0.07);
		SimTicker.stepNow(world);
		double beforeCut = lab.progress("frontier_1");
		double checkpoint = lab.checkpoint();
		world.setBlockState(aiPower, Blocks.AIR.getDefaultState());
		SimTicker.stepNow(world);
		SimTicker.stepNow(world);
		double afterCut = lab.progress("frontier_1");
		check("RD2.a", started == null && training && Math.abs(checkpoint - run.work(0) * 0.05) < 1 && beforeCut > checkpoint
						&& Math.abs(afterCut - checkpoint) < 1 && lab.rollbacks() == 1,
				"started=" + started + " training=" + training + " rate=" + lab.frontierRate + " checkpoint=" + checkpoint
						+ " before=" + beforeCut + " after=" + afterCut + " rollbacks=" + lab.rollbacks() + " status=" + lab.frontierStatus, failures);
		lab.pause("frontier_1");
		world.setBlockState(aiPower, RcBlocks.get("creative_power").getDefaultState());
		for (int step = 0; step < 3; step++) SimTicker.stepNow(world);
		check("RD2.b", Math.abs(lab.progress("frontier_1") - afterCut) < 1 && aiRack.rackStatus() != RackStatus.RESEARCHING,
				"paused run moved: " + lab.progress("frontier_1") + " rack=" + aiRack.rackStatus(), failures);

		// A Compute Lease on the same rack: served in full, then settled and paid.
		var lease = market.postLease(world.getTime(), 400);
		String accepted = market.acceptLease(lease.id, lab.effects().leaseSlots());
		for (int step = 0; step < 3; step++) SimTicker.stepNow(world);
		boolean served = aiRack.rackStatus() == RackStatus.LEASED && lease.uptime() > 0.999;
		lease.elapsed = lease.durationTicks - 5;
		lease.delivered = lease.elapsed;
		long beforePay = facility.credits();
		SimTicker.stepNow(world);
		check("RD3.a", accepted == null && served && lease.state == dev.rackcraft.compute.Lease.State.DONE
						&& lease.earned == lease.pay && facility.credits() >= beforePay + lease.pay,
				"accepted=" + accepted + " served=" + served + " rack=" + aiRack.rackStatus() + " uptime=" + lease.uptime()
						+ " state=" + lease.state + " earned=" + lease.earned + " pay=" + lease.pay, failures);
		check("RD3.b", dev.rackcraft.compute.Lease.payFraction(0.999, 0.99) == 1 && Math.abs(dev.rackcraft.compute.Lease.payFraction(0.98, 0.99) - 0.5) < 1e-9
						&& dev.rackcraft.compute.Lease.payFraction(0.95, 0.99) == 0,
				"pay at 98% of a 99% guarantee=" + dev.rackcraft.compute.Lease.payFraction(0.98, 0.99), failures);

		// The Wafer Fab does nothing until Extreme UV Lithography is researched.
		BlockPos fab = clearArea(world, origin.east(10), 4, 4, 4);
		world.setBlockState(fab.west(), RcBlocks.get("creative_power").getDefaultState());
		machine(world, fab.west()).setCreativeValue(CreativeSettings.OUTPUT_KW, 10_000);
		List<MachineBlockEntity> cores = new java.util.ArrayList<>();
		for (BlockPos pos : BlockPos.iterate(fab, fab.add(1, 1, 1))) cores.add(place(world, pos.toImmutable(), "wafer_fab", Direction.NORTH));
		cores.get(0).setStack(0, new ItemStack(item("silicon"), 32));
		cores.get(1).setStack(1, new ItemStack(item("gpu_chip"), 8));
		for (int step = 0; step < 3; step++) SimTicker.stepNow(world);
		boolean waited = cores.get(0).processStatus() == dev.rackcraft.world.NuclearProcessing.Status.LOCKED.ordinal();
		lab.complete(world, dev.rackcraft.compute.Research.get("lithography"));
		for (int step = 0; step < 80; step++) SimTicker.stepNow(world);
		int engines = dev.rackcraft.world.ReactorArrays.count(cores, 2, item("wafer_scale_engine"));
		check("W1.a", waited && engines > 0, "waited=" + waited + " engines=" + engines + " status=" + cores.get(0).processStatus()
				+ " power=" + cores.get(0).powerSatisfaction(), failures);
		// On a third of the power it needs, a fab runs slowly instead of reporting no power.
		machine(world, fab.west()).setCreativeValue(CreativeSettings.OUTPUT_KW, 1_000);
		cores.get(0).setStack(0, new ItemStack(item("silicon"), 32));
		cores.get(1).setStack(1, new ItemStack(item("gpu_chip"), 8));
		for (int step = 0; step < 3; step++) SimTicker.stepNow(world);
		double slowBefore = cores.get(0).workProgress();
		SimTicker.stepNow(world);
		double slowAfter = cores.get(0).workProgress();
		check("W1.b", cores.get(0).processStatus() == dev.rackcraft.world.NuclearProcessing.Status.LOW_POWER.ordinal() && slowAfter > slowBefore
						&& cores.get(0).cubeDemandKw() > 3_000,
				"status=" + cores.get(0).processStatus() + " progress " + slowBefore + " -> " + slowAfter + " power=" + cores.get(0).powerSatisfaction()
						+ " needs=" + cores.get(0).cubeDemandKw(), failures);

		// Events: a hardware failure burns out a module (unless Predictive Maintenance catches it); a surge reboots
		// racks unless a battery sits on their power network.
		MachineBlockEntity victim = rack(world, origin.south(12).east(), "gpu_blade");
		world.setBlockState(origin.south(12), RcBlocks.get("creative_power").getDefaultState());
		SimTicker.stepNow(world);
		long failedBefore = failedModules(world);
		String failure = SimTicker.startEvent(world, "hardware_failure");
		long failedAfter = failedModules(world);
		lab.complete(world, dev.rackcraft.compute.Research.get("predictive_maintenance"));
		String caught = SimTicker.startEvent(world, "hardware_failure");
		check("EV1.a", failedAfter == failedBefore + 1 && failedModules(world) == failedAfter && caught.contains("caught"),
				"before=" + failedBefore + " after=" + failedAfter + " failure=" + failure + " caught=" + caught, failures);
		BlockPos protectedPos = origin.south(15);
		world.setBlockState(protectedPos, RcBlocks.get("creative_power").getDefaultState());
		MachineBlockEntity buffered = rack(world, protectedPos.east(), "server_1u");
		world.setBlockState(protectedPos.west(), RcBlocks.get("battery_bank").getDefaultState());
		SimTicker.stepNow(world);
		boolean booted = victim.bootProgress() >= 1 && buffered.bootProgress() >= 1;
		SimTicker.startEvent(world, "surge");
		check("EV1.b", booted && victim.bootProgress() == 0 && buffered.bootProgress() >= 1,
				"booted=" + booted + " unprotected=" + victim.bootProgress() + " battery-backed=" + buffered.bootProgress(), failures);
		facility.triggerEvent("none", 0);

		// A cable cut cuts a cable that feeds a rack, and says where; a stale 17-hour event from an old save is capped.
		BlockPos linePower = origin.south(18);
		world.setBlockState(linePower, RcBlocks.get("creative_power").getDefaultState());
		CableBlock line = (CableBlock) RcBlocks.get("power_cable");
		for (int index = 1; index <= 3; index++) {
			BlockPos pos = linePower.east(index);
			world.setBlockState(pos, line.withConnections(line.getDefaultState(), world, pos));
		}
		MachineBlockEntity fed = rack(world, linePower.east(4), "server_1u");
		SimTicker.stepNow(world);
		boolean poweredBefore = fed.powerSatisfaction() > 0.99;
		var networks = dev.rackcraft.world.NetworkManager.get(world);
		int cutBefore = networks.cutCables(dev.rackcraft.sim.NetKind.POWER).size() + networks.cutCables(dev.rackcraft.sim.NetKind.DATA).size();
		String cut = SimTicker.startEvent(world, "cable_cut");
		int cutAfter = networks.cutCables(dev.rackcraft.sim.NetKind.POWER).size() + networks.cutCables(dev.rackcraft.sim.NetKind.DATA).size();
		check("EV1.c", poweredBefore && cutAfter == cutBefore + 1 && cut.startsWith("cable cut at ")
						&& facility.eventDetail().equals(cut) && facility.activeEvent().equals("cable_cut"),
				"poweredBefore=" + poweredBefore + " cut " + cutBefore + " -> " + cutAfter + " detail=" + cut, failures);
		for (dev.rackcraft.sim.NetKind kind : List.of(dev.rackcraft.sim.NetKind.POWER, dev.rackcraft.sim.NetKind.DATA)) {
			for (BlockPos pos : List.copyOf(networks.cutCables(kind))) {
				if (pos.getManhattanDistance(linePower) <= 4 || cut.endsWith(pos.getX() + " " + pos.getY() + " " + pos.getZ())) {
					CableBlock.setCut(world, pos, false);
				}
			}
		}
		facility.triggerEvent("cable_cut", 1_200_000);
		facility.advanceEventClock(10);
		check("EV1.d", facility.activeEventRemainingTicks() <= 600, "remaining=" + facility.activeEventRemainingTicks(), failures);
		facility.triggerEvent("none", 0);

		// The last frontier run is an AGI, and it leaves its weights in the outbox.
		int outbox = market.outbox().size();
		lab.complete(world, dev.rackcraft.compute.Research.get(dev.rackcraft.compute.Research.AGI));
		boolean weights = market.outbox().size() == outbox + 1 && market.outbox().get(outbox).isOf(item("agi_weights"));
		check("RD4.a", weights && lab.agi() && lab.effects().mining() > 1.5, "weights=" + weights + " effects=" + lab.effects(), failures);
		if (weights) market.outbox().remove(outbox);

		market.leases().removeIf(entry -> entry.state == dev.rackcraft.compute.Lease.State.RUNNING
				|| entry.state == dev.rackcraft.compute.Lease.State.OFFERED);
		lab.reset();
		clearArea(world, origin, 16, 8, 16);
		clearArea(world, fab, 4, 4, 4);
	}

	/**
	 * The darknet: lots are never mod items or creative-only ones; a bid is paid up front and starts the one-minute
	 * countdown; a rival outbids and the bid is refunded; the winner's parcel ships, arrives and can be collected;
	 * and listing slots cost RackCoin.
	 */
	private static void checkDarknet(ServerWorld world, int[] failures) {
		var market = dev.rackcraft.darknet.DarknetMarket.get(world);
		FacilityManager facility = FacilityManager.get(world);
		market.reset(world);
		java.util.Random random = new java.util.Random(7);
		java.util.Set<String> categories = new java.util.HashSet<>();
		List<String> bad = new java.util.ArrayList<>();
		for (int roll = 0; roll < 600; roll++) {
			var lot = dev.rackcraft.darknet.DarknetGoods.roll(random, world.getEnabledFeatures());
			categories.add(lot.category());
			var id = Registries.ITEM.getId(lot.stack().getItem());
			if (!id.getNamespace().equals("minecraft") || id.getPath().contains("command_block") || id.getPath().equals("debug_stick")
					|| id.getPath().equals("obsidian") || id.getPath().equals("bedrock") || id.getPath().equals("barrier")
					|| id.getPath().equals("wither_spawn_egg") || lot.value() <= 0 || lot.stack().isEmpty()) bad.add(id + "=" + lot.value());
		}
		check("DN1.a", bad.isEmpty() && categories.containsAll(List.of("book", "egg", "spawner", "rare", "anything")),
				"categories=" + categories + " bad=" + bad, failures);

		long now = world.getTime();
		market.tick(world, now);
		check("DN1.b", market.listings().size() == 4 && market.listings().stream().allMatch(listing -> listing.endsAt == now
						+ dev.rackcraft.darknet.DarknetMarket.NO_BID_TICKS && listing.opening > 0 && listing.opening < listing.value),
				"listings=" + market.listings().size(), failures);

		var player = net.fabricmc.fabric.api.entity.FakePlayer.get(world);
		var listing = market.listings().get(0);
		listing.rivalMax = listing.opening * 3;
		facility.addCredits(listing.rivalMax * 4);
		long before = facility.credits();
		String low = market.bid(world, player, listing.id, listing.opening - 1, now);
		String placed = market.bid(world, player, listing.id, listing.opening, now);
		check("DN2.a", low != null && placed == null && facility.credits() == before - listing.opening && listing.playerLeading()
						&& listing.endsAt == now + dev.rackcraft.darknet.DarknetMarket.AFTER_BID_TICKS && listing.rivalAt > now,
				"low=" + low + " placed=" + placed + " paid=" + (before - facility.credits()) + " endsAt=" + (listing.endsAt - now)
						+ " rivalAt=" + (listing.rivalAt - now), failures);
		market.tick(world, listing.rivalAt);
		check("DN2.b", !listing.playerLeading() && listing.bid > listing.opening && listing.bid <= listing.rivalMax
						&& facility.credits() == before,
				"rival=" + listing.bidder + " bid=" + listing.bid + " refunded=" + (facility.credits() == before), failures);

		// Outbid the rival past their ceiling: they give up and the player wins when the minute runs out.
		long winning = listing.rivalMax + 1_000;
		String raise = market.bid(world, player, listing.id, winning, now);
		boolean noAnswer = listing.rivalAt == 0;
		long endsAt = listing.endsAt;
		market.tick(world, endsAt);
		var parcel = market.parcels().isEmpty() ? null : market.parcels().get(0);
		check("DN2.c", raise == null && noAnswer && market.find(listing.id) == null && parcel != null
						&& parcel.arrivesAt >= endsAt + 20 * 120 && parcel.arrivesAt <= endsAt + 20 * 360 && facility.credits() == before - winning,
				"raise=" + raise + " noAnswer=" + noAnswer + " parcel=" + (parcel == null ? null : parcel.stack + " in " + (parcel.arrivesAt - endsAt))
						+ " spent=" + (before - facility.credits()), failures);
		int early = market.collect(player, endsAt);
		market.tick(world, parcel.arrivesAt);
		int count = player.getInventory().count(parcel.stack.getItem());
		int collected = market.collect(player, parcel.arrivesAt);
		check("DN2.d", early == 0 && collected == 1 && player.getInventory().count(parcel.stack.getItem()) >= count + parcel.stack.getCount()
						&& market.parcels().isEmpty() && market.listings().size() == 4,
				"early=" + early + " collected=" + collected + " listings=" + market.listings().size(), failures);
		player.getInventory().clear();

		// Untouched auctions run fifteen minutes; more slots cost RackCoin.
		var idle = market.listings().get(1);
		market.tick(world, idle.endsAt);
		boolean idleClosed = market.find(idle.id) == null;
		facility.addCredits(dev.rackcraft.darknet.DarknetMarket.SLOT_PRICES[0]);
		long beforeSlot = facility.credits();
		String slot = market.buySlot(world);
		check("DN3.a", idleClosed && slot == null && market.slots() == 5
						&& facility.credits() == beforeSlot - dev.rackcraft.darknet.DarknetMarket.SLOT_PRICES[0]
						&& market.nextSlotPrice() == dev.rackcraft.darknet.DarknetMarket.SLOT_PRICES[1],
				"idleClosed=" + idleClosed + " slot=" + slot + " slots=" + market.slots(), failures);
		market.reset(world);
	}

	/**
	 * The utility plants and the CDU-behind-a-rack fix: a Desalination Plant waters a tower from sea-or-any water, a
	 * Substation sells a reactor's spare power without touching a battery (and sells nothing from a creative or
	 * utility source), a Heat Recovery Plant sells a loop's heat to villagers, and a CDU on a rack's back catches its
	 * exhaust.
	 */
	private static void checkUtilities(ServerWorld world, int[] failures) {
		FacilityManager facility = FacilityManager.get(world);
		BlockPos origin = clearArea(world, new BlockPos(-1536, 150, -1536), 40, 8, 12);

		// Desalination: a 2x2x2 touching water supplies 16 units; a tower on the same loop gets its full 4.
		world.setBlockState(origin.west(), Blocks.WATER.getDefaultState());
		world.setBlockState(origin.west().south(), Blocks.WATER.getDefaultState());
		List<MachineBlockEntity> desal = new java.util.ArrayList<>();
		for (BlockPos pos : BlockPos.iterate(origin, origin.add(1, 1, 1))) desal.add(place(world, pos.toImmutable(), "desalination_plant", Direction.NORTH));
		world.setBlockState(origin.add(2, 0, 0), RcBlocks.get("creative_power").getDefaultState());
		MachineBlockEntity tower = place(world, origin.add(0, 0, 2), "cooling_tower", Direction.NORTH);
		for (int step = 0; step < 4; step++) SimTicker.stepNow(world);
		MachineBlockEntity controller = machine(world, origin);
		check("U1.a", controller.pumpUnits() == 16 && controller.pumpStatus() == dev.rackcraft.world.FreshwaterCooling.PumpStatus.PUMPING.ordinal()
						&& tower.coolingDetail() == 4 && world.getFluidState(origin.west()).isStill(),
				"units=" + controller.pumpUnits() + " status=" + controller.pumpStatus() + " towerUnits=" + tower.coolingDetail()
						+ " draw=" + controller.powerKw(), failures);

		// Export: a fuelled 2x2x2 reactor array's spare output sells through a 2x2x2 substation; a battery on the
		// network keeps its charge.
		BlockPos grid = origin.east(8);
		List<MachineBlockEntity> reactor = new java.util.ArrayList<>();
		for (BlockPos pos : BlockPos.iterate(grid, grid.add(1, 1, 1))) reactor.add(place(world, pos.toImmutable(), "modular_reactor", Direction.NORTH));
		reactor.forEach(core -> core.setFuelBurnTicks(0));
		reactor.get(0).setStack(0, new ItemStack(RcItems.ITEMS.get("fuel_cell"), 4));
		for (BlockPos pos : BlockPos.iterate(grid.add(2, 0, 0), grid.add(3, 1, 1))) place(world, pos.toImmutable(), "grid_substation", Direction.NORTH);
		MachineBlockEntity battery = place(world, grid.add(0, 0, 2), "battery_bank", Direction.NORTH);
		battery.setChargeKws(1000);
		long before = facility.credits();
		for (int step = 0; step < 4; step++) SimTicker.stepNow(world);
		MachineBlockEntity substation = machine(world, grid.add(2, 0, 0));
		check("U2.a", substation.powerKw() > 3900 && substation.income() > 0 && facility.credits() > before
						&& battery.chargeKws() >= 1000 && reactor.get(0).arrayFuelTicks() > 0,
				"exported=" + substation.powerKw() + " income=" + substation.income() + " battery=" + battery.chargeKws()
						+ " reactorOutput=" + reactor.get(0).powerKw(), failures);
		BlockPos freeGrid = origin.east(16);
		world.setBlockState(freeGrid, RcBlocks.get("creative_power").getDefaultState());
		for (BlockPos pos : BlockPos.iterate(freeGrid.east(), freeGrid.add(2, 1, 1))) place(world, pos.toImmutable(), "grid_substation", Direction.NORTH);
		for (int step = 0; step < 2; step++) SimTicker.stepNow(world);
		check("U2.b", machine(world, freeGrid.east()).powerKw() == 0, "creative power exported " + machine(world, freeGrid.east()).powerKw(), failures);

		// Heat recovery: four villagers take a GPU rack's loop heat, and pay for it.
		BlockPos plant = origin.east(24);
		for (BlockPos pos : BlockPos.iterate(plant, plant.add(1, 1, 1))) place(world, pos.toImmutable(), "heat_recovery_plant", Direction.NORTH);
		// The rack faces away from the plant, so its intake breathes open air.
		MachineBlockEntity hot = rack(world, plant.add(0, 0, -1), "gpu_blade");
		world.setBlockState(plant.add(-1, 0, -1), RcBlocks.get("creative_power").getDefaultState());
		List<net.minecraft.entity.passive.VillagerEntity> village = new java.util.ArrayList<>();
		for (int index = 0; index < 4; index++) {
			var villager = net.minecraft.entity.EntityType.VILLAGER.create(world);
			villager.refreshPositionAndAngles(plant.getX() + 0.5 + (index % 2), plant.getY() + 3, plant.getZ() + 0.5 + index / 2, 0, 0);
			villager.setAiDisabled(true);
			villager.setNoGravity(true);
			world.spawnEntity(villager);
			village.add(villager);
		}
		long beforeHeat = facility.credits();
		for (int step = 0; step < 6; step++) SimTicker.stepNow(world);
		MachineBlockEntity recovery = machine(world, plant);
		check("U3.a", recovery.coolingDetail() >= 4 && recovery.coolingKw() > 15 && recovery.income() > 0
						&& hot.rackStatus() != RackStatus.NEEDS_WATER && facility.credits() > beforeHeat,
				"villagers=" + recovery.coolingDetail() + " moved=" + recovery.coolingKw() + " income=" + recovery.income()
						+ " rack=" + hot.rackStatus() + " capacity=" + recovery.reactorCapacityKw() + " rackKw=" + hot.powerKw()
						+ " toLoop=" + hot.heatToLoopKw() + " toAir=" + hot.heatToAirKw() + " load=" + hot.load() + " inlet=" + hot.inletCelsius()
						+ " loop=" + recovery.loopHeatKw() + "/" + recovery.loopCapacityKw(), failures);
		village.forEach(net.minecraft.entity.Entity::discard);

		// A CDU on a rack's back catches its exhaust into the loop, like a Rear-Door Cooler.
		BlockPos hall = origin.east(32);
		MachineBlockEntity backed = rack(world, hall, "gpu_blade");
		world.setBlockState(hall.west(), RcBlocks.get("creative_power").getDefaultState());
		MachineBlockEntity cdu = place(world, hall.south(), "cdu", Direction.NORTH);
		place(world, hall.south().east(), "chiller", Direction.NORTH);
		for (int step = 0; step < 4; step++) SimTicker.stepNow(world);
		check("T2.a", backed.heatToAirKw() < 0.01 && cdu.coolingKw() > 0 && backed.heatToLoopKw() > 20,
				"toAir=" + backed.heatToAirKw() + " toLoop=" + backed.heatToLoopKw() + " cduCaught=" + cdu.coolingKw(), failures);
		clearArea(world, origin, 40, 8, 12);
	}

	/**
	 * Industry: an Electrolyser cube turns power and aluminium into hydrogen (and stops without water); an Assembly
	 * Line of belts and robots turns a Drone Frame into a Maintenance Drone; a Drone Dock's drones swap a failed module,
	 * splice a cut cable and reset a tripped breaker, then come home.
	 */
	private static void checkIndustry(ServerWorld world, int[] failures) {
		BlockPos origin = clearArea(world, new BlockPos(-1700, 150, -1536), 44, 8, 20);

		// Electrolyser: a 2x2x2 touching water makes canisters at about 30 MW; a dry one reports it needs water.
		world.setBlockState(origin.west(), Blocks.WATER.getDefaultState());
		for (BlockPos pos : BlockPos.iterate(origin, origin.add(1, 1, 1))) place(world, pos.toImmutable(), "electrolyser", Direction.NORTH);
		world.setBlockState(origin.add(2, 0, 0), RcBlocks.get("creative_power").getDefaultState());
		machine(world, origin.add(2, 0, 0)).setCreativeValue(CreativeSettings.OUTPUT_KW, 100_000);
		MachineBlockEntity cell = machine(world, origin);
		cell.setStack(0, new ItemStack(RcItems.ITEMS.get("aluminum_ingot"), 16));
		for (int step = 0; step < 24; step++) SimTicker.stepNow(world);
		int hydrogen = dev.rackcraft.world.ReactorArrays.count(dev.rackcraft.world.ReactorArrays.arrayOf(world, cell).members(),
				dev.rackcraft.world.NuclearProcessing.OUTPUT_SLOT, RcItems.ITEMS.get("hydrogen_canister"));
		check("I1.a", hydrogen >= 2 && cell.powerKw() > 25_000,
				"canisters=" + hydrogen + " draw=" + cell.powerKw() + " status=" + cell.processStatus(), failures);
		BlockPos dry = origin.add(5, 0, 0);
		for (BlockPos pos : BlockPos.iterate(dry, dry.add(1, 1, 1))) place(world, pos.toImmutable(), "electrolyser", Direction.NORTH);
		world.setBlockState(dry.add(2, 0, 0), RcBlocks.get("creative_power").getDefaultState());
		machine(world, dry).setStack(0, new ItemStack(RcItems.ITEMS.get("aluminum_ingot"), 4));
		for (int step = 0; step < 3; step++) SimTicker.stepNow(world);
		check("I1.b", machine(world, dry).processStatus() == dev.rackcraft.world.NuclearProcessing.Status.NO_WATER.ordinal()
						&& machine(world, dry).powerKw() < 10,
				"status=" + machine(world, dry).processStatus() + " draw=" + machine(world, dry).powerKw(), failures);

		// A 2x2x2 Hydrogen Tank touching the wet Electrolyser: gas goes into the tank and no more ingots are used, and
		// storage hands the tank's hydrogen out as canisters.
		for (BlockPos pos : BlockPos.iterate(origin.add(0, 0, 2), origin.add(1, 1, 3))) place(world, pos.toImmutable(), "hydrogen_tank", Direction.NORTH);
		var cellMembers = dev.rackcraft.world.ReactorArrays.arrayOf(world, cell).members();
		// Before Cryogenic Hydrogen Storage the tank is inert: the Electrolyser goes on making canisters, and the Exchange
		// doesn't offer the Tanker Drone.
		boolean wasLocked = !dev.rackcraft.world.HydrogenTanks.unlocked(world);
		cell.setStack(0, new ItemStack(RcItems.ITEMS.get("aluminum_ingot"), 4));
		for (int step = 0; step < 6; step++) SimTicker.stepNow(world);
		boolean lockedCanisters = dev.rackcraft.world.NuclearProcessing.tankNetwork(world, cell) == null
				&& machine(world, origin.add(0, 0, 2)).siteReading(dev.rackcraft.world.HydrogenTanks.R_LOCKED) == 1;
		boolean tankerHidden = !ExchangeCatalog.listed(RcItems.ITEMS.get("tanker_drone"), world);
		dev.rackcraft.compute.ResearchLab.get(world).complete(world, dev.rackcraft.compute.Research.get("cryo_hydrogen"));
		check("I1.d", wasLocked && lockedCanisters && tankerHidden && ExchangeCatalog.listed(RcItems.ITEMS.get("tanker_drone"), world)
						&& ExchangeCatalog.price(RcItems.ITEMS.get("tanker_drone")) == RackcraftConfig.values.exchange.tankerDronePrice,
				"wasLocked=" + wasLocked + " lockedMakesCanisters=" + lockedCanisters + " hiddenBefore=" + tankerHidden
						+ " price=" + ExchangeCatalog.price(RcItems.ITEMS.get("tanker_drone")), failures);
		for (MachineBlockEntity member : cellMembers) member.setStack(dev.rackcraft.world.NuclearProcessing.OUTPUT_SLOT, ItemStack.EMPTY);
		for (MachineBlockEntity member : cellMembers) member.setStack(0, ItemStack.EMPTY);
		cell.setStack(0, new ItemStack(RcItems.ITEMS.get("aluminum_ingot"), 5));
		for (int step = 0; step < 24; step++) SimTicker.stepNow(world);
		MachineBlockEntity tank = machine(world, origin.add(0, 0, 2));
		var gasNetwork = dev.rackcraft.storage.StorageService.networkOf(world,
				dev.rackcraft.world.NetworkManager.get(world).component(tank.getPos(), dev.rackcraft.sim.NetKind.ITEM));
		long gas = gasNetwork.hydrogenStored();
		int ingots = dev.rackcraft.world.ReactorArrays.count(cellMembers, 0, RcItems.ITEMS.get("aluminum_ingot"));
		int canisters = dev.rackcraft.world.ReactorArrays.count(cellMembers, dev.rackcraft.world.NuclearProcessing.OUTPUT_SLOT,
				RcItems.ITEMS.get("hydrogen_canister"));
		var canisterKey = dev.rackcraft.storage.ItemKey.of(RcItems.ITEMS.get("hydrogen_canister"));
		long counted = gasNetwork.count(canisterKey, true);
		long taken = gasNetwork.extract(canisterKey, 2, true, false);
		check("I1.c", gas >= 2 && ingots == 5 && canisters == 0 && counted == gas && taken == 2 && gasNetwork.hydrogenStored() == gas - 2
						&& tank.reactorArraySize() == 2,
				"gas=" + gas + " ingots=" + ingots + " canisters=" + canisters + " counted=" + counted + " taken=" + taken
						+ " edge=" + tank.reactorArraySize(), failures);

		// A Tanker Drone in the tank flies a stack to a Drone Dock 40 blocks off that no pipe reaches, and comes home.
		for (MachineBlockEntity member : dev.rackcraft.world.ReactorArrays.arrayOf(world, tank).members()) member.setHydrogen(25);
		tank.setStack(0, new ItemStack(RcItems.ITEMS.get("tanker_drone")));
		MachineBlockEntity farDock = place(world, origin.add(40, 0, 0), "drone_dock", Direction.NORTH);
		SimTicker.stepNow(world);
		dev.rackcraft.world.HydrogenTanks.scanNow(world);
		boolean tankerLaunched = tank.getStack(0).isEmpty();
		net.minecraft.util.math.Box sky = new net.minecraft.util.math.Box(origin).expand(300);
		for (int tick = 0; tick < 1200; tick++) {
			List<dev.rackcraft.entity.TankerDroneEntity> tankers = world.getEntitiesByClass(dev.rackcraft.entity.TankerDroneEntity.class, sky,
					net.minecraft.entity.Entity::isAlive);
			if (tankers.isEmpty()) break;
			tankers.forEach(dev.rackcraft.entity.TankerDroneEntity::serverTick);
		}
		long left = dev.rackcraft.world.ReactorArrays.arrayOf(world, tank).members().stream().mapToLong(MachineBlockEntity::hydrogen).sum();
		check("HT1.a", tankerLaunched && farDock.getStack(1).isOf(RcItems.ITEMS.get("hydrogen_canister")) && farDock.getStack(1).getCount() == 16
						&& tank.getStack(0).isOf(RcItems.ITEMS.get("tanker_drone")) && left == 8 * 25 - 16,
				"launched=" + tankerLaunched + " dockFuel=" + farDock.getStack(1) + " tankerHome=" + tank.getStack(0) + " tankLeft=" + left, failures);

		// Assembly Line: seven belts running east into a chest, four robots on the north side facing the belts.
		BlockPos line = origin.add(0, 0, 8);
		List<dev.rackcraft.block.BeltBlockEntity> belts = new java.util.ArrayList<>();
		for (int index = 0; index < 7; index++) {
			world.setBlockState(line.east(index), RcBlocks.get("conveyor_belt").getDefaultState()
					.with(dev.rackcraft.block.ConveyorBeltBlock.FACING, Direction.EAST));
			belts.add((dev.rackcraft.block.BeltBlockEntity) world.getBlockEntity(line.east(index)));
		}
		world.setBlockState(line.east(7), Blocks.CHEST.getDefaultState());
		String[] robots = {"assembly_arm", "welding_arm", "assembly_arm", "riveting_arm"};
		List<MachineBlockEntity> arms = new java.util.ArrayList<>();
		for (int index = 0; index < robots.length; index++) {
			BlockPos at = line.east(1 + index).north();
			arms.add(place(world, at, robots[index], Direction.SOUTH));
			world.setBlockState(at.north(), RcBlocks.get("creative_power").getDefaultState());
			machine(world, at.north()).setCreativeValue(CreativeSettings.OUTPUT_KW, 5_000);
		}
		arms.get(0).setStack(0, new ItemStack(RcItems.ITEMS.get("electric_motor"), 4));
		arms.get(2).setStack(0, new ItemStack(RcItems.ITEMS.get("circuit_board"), 2));
		arms.get(2).setStack(1, new ItemStack(RcItems.ITEMS.get("hydrogen_canister"), 1));
		for (int step = 0; step < 2; step++) SimTicker.stepNow(world);
		belts.get(0).accept(new ItemStack(RcItems.ITEMS.get("drone_frame")), 0);
		double weldPeak = 0;
		for (int tick = 0; tick < 2000; tick++) {
			for (var belt : belts) belt.serverTick(world);
			if (tick % 10 == 9) {
				SimTicker.stepNow(world);
				weldPeak = Math.max(weldPeak, arms.get(1).powerKw());
			}
		}
		int drones = 0;
		if (world.getBlockEntity(line.east(7)) instanceof net.minecraft.block.entity.ChestBlockEntity chest) {
			for (int slot = 0; slot < chest.size(); slot++) {
				if (chest.getStack(slot).isOf(RcItems.ITEMS.get("maintenance_drone"))) drones += chest.getStack(slot).getCount();
			}
		}
		check("I2.a", drones == 1 && arms.get(0).getStack(0).isEmpty() && arms.get(2).getStack(0).isEmpty()
						&& arms.get(2).getStack(1).isEmpty() && weldPeak > 1400 && arms.get(3).itemsMade() == 1,
				"drones=" + drones + " motorsLeft=" + arms.get(0).getStack(0).getCount() + " boardsLeft=" + arms.get(2).getStack(0).getCount()
						+ " weldPeak=" + weldPeak + " steps=" + arms.stream().map(arm -> arm.itemsMade() + "").toList()
						+ " belts=" + belts.stream().map(belt -> belt.stack().getName().getString() + "@" + belt.progress()).toList(), failures);
		ItemStack half = new ItemStack(RcItems.ITEMS.get("drone_frame"));
		half.getOrCreateNbt().putInt(dev.rackcraft.world.AssemblyLine.STEPS_KEY, 2);
		List<String> lines = dev.rackcraft.world.AssemblyLine.describe(half);
		check("I2.b", lines.size() == 2 && lines.get(1).contains("Circuit Board") && lines.get(1).contains("Assembly Robot"),
				"tooltip=" + lines, failures);
		check("I2.c", ExchangeCatalog.price(RcItems.ITEMS.get("hydrogen_canister")) == null
						&& ExchangeCatalog.price(RcItems.ITEMS.get("maintenance_drone")) == null
						&& ExchangeCatalog.price(RcItems.ITEMS.get("electric_motor")) != null
						&& ExchangeCatalog.price(RcItems.ITEMS.get("comms_satellite")) == null
						&& ExchangeCatalog.price(RcItems.ITEMS.get("rocket_stage")) == null
						&& ExchangeCatalog.price(RcItems.ITEMS.get("dyson_mirror")) == null
						&& ExchangeCatalog.price(RcItems.ITEMS.get("satellite_bus")) != null,
				"hydrogen=" + ExchangeCatalog.price(RcItems.ITEMS.get("hydrogen_canister")) + " drone="
						+ ExchangeCatalog.price(RcItems.ITEMS.get("maintenance_drone")), failures);

		// Drone Dock: a failed module, a cut cable and a tripped breaker, with two drones home for three jobs.
		BlockPos dockPos = origin.add(20, 0, 4);
		MachineBlockEntity dock = place(world, dockPos, "drone_dock", Direction.NORTH);
		world.setBlockState(dockPos.west(), RcBlocks.get("creative_power").getDefaultState());
		dock.setStack(0, new ItemStack(RcItems.ITEMS.get("maintenance_drone"), 2));
		dock.setStack(1, new ItemStack(RcItems.ITEMS.get("hydrogen_canister"), 1));
		dock.setStack(2, new ItemStack(RcItems.ITEMS.get("server_1u")));
		dock.setStack(3, new ItemStack(RcItems.ITEMS.get("repair_kit")));
		MachineBlockEntity broken = rack(world, dockPos.add(6, 0, 0), "pi_node");
		broken.setStack(3, new ItemStack(RcItems.ITEMS.get("failed_module")));
		BlockPos cable = dockPos.add(0, 0, 6);
		CableBlock cableBlock = (CableBlock) RcBlocks.get("power_cable");
		world.setBlockState(cable, cableBlock.withConnections(cableBlock.getDefaultState(), world, cable));
		CableBlock.setCut(world, cable, true);
		MachineBlockEntity pdu = place(world, dockPos.add(-6, 0, 0), "pdu", Direction.NORTH);
		pdu.setTripped(true);
		for (int step = 0; step < 2; step++) SimTicker.stepNow(world);
		dev.rackcraft.world.DroneDocks.scanNow(world);
		int launched = 2 - dock.getStack(0).getCount();
		int waiting = dock.processStatus();
		flyDrones(world, dockPos);
		dev.rackcraft.world.DroneDocks.scanNow(world);
		flyDrones(world, dockPos);
		boolean spareIn = broken.getStack(3).isOf(RcItems.ITEMS.get("server_1u"));
		boolean deadBack = false;
		for (int slot = 2; slot < 9; slot++) deadBack |= dock.getStack(slot).isOf(RcItems.ITEMS.get("failed_module"));
		check("I3.a", launched == 2 && waiting == dev.rackcraft.world.DroneDocks.Status.NO_DRONES.ordinal() && spareIn && deadBack
						&& !world.getBlockState(cable).get(CableBlock.CUT) && !pdu.isTripped(),
				"launched=" + launched + " status=" + waiting + " spareIn=" + spareIn + " deadBack=" + deadBack
						+ " cableCut=" + world.getBlockState(cable).get(CableBlock.CUT) + " pduTripped=" + pdu.isTripped(), failures);
		check("I3.b", dock.getStack(0).getCount() == 2 && dock.itemsMade() == 3 && dock.toolUses() == 5
						&& dock.getStack(1).isEmpty() && dock.getStack(3).getDamage() == 1,
				"dronesHome=" + dock.getStack(0).getCount() + " fixed=" + dock.itemsMade() + " tripsLeft=" + dock.toolUses()
						+ " canisters=" + dock.getStack(1).getCount() + " kitDamage=" + dock.getStack(3).getDamage(), failures);
		clearArea(world, origin, 44, 8, 20);
	}

	/**
	 * The launch programme: a Satellite Bus with Solar Panels fitted becomes a Dyson Mirror on the line; a Launch Control
	 * beside a 3x3 pad puts a Comms Satellite, an Orbital Data Center and that mirror into orbit, each with its effect;
	 * a failed launch loses the stages and fuel but returns the payload.
	 */
	private static void checkLaunch(ServerWorld world, int[] failures) {
		BlockPos origin = clearArea(world, new BlockPos(-1860, 180, -1536), 30, 12, 20);
		dev.rackcraft.world.OrbitState orbit = dev.rackcraft.world.OrbitState.get(world);
		orbit.reset();
		double baseAi = dev.rackcraft.compute.ResearchLab.effects(world).aiCompute();

		// The bus branches on its first part: an Assembly Robot holding only Solar Panels makes it a mirror.
		BlockPos line = origin.add(0, 0, 14);
		List<dev.rackcraft.block.BeltBlockEntity> belts = new java.util.ArrayList<>();
		for (int index = 0; index < 5; index++) {
			world.setBlockState(line.east(index), RcBlocks.get("conveyor_belt").getDefaultState()
					.with(dev.rackcraft.block.ConveyorBeltBlock.FACING, Direction.EAST));
			belts.add((dev.rackcraft.block.BeltBlockEntity) world.getBlockEntity(line.east(index)));
		}
		world.setBlockState(line.east(5), Blocks.CHEST.getDefaultState());
		String[] robots = {"assembly_arm", "welding_arm", "riveting_arm"};
		List<MachineBlockEntity> arms = new java.util.ArrayList<>();
		for (int index = 0; index < robots.length; index++) {
			BlockPos at = line.east(1 + index).north();
			arms.add(place(world, at, robots[index], Direction.SOUTH));
			world.setBlockState(at.north(), RcBlocks.get("creative_power").getDefaultState());
			machine(world, at.north()).setCreativeValue(CreativeSettings.OUTPUT_KW, 5_000);
		}
		arms.get(0).setStack(0, new ItemStack(RcBlocks.get("solar_panel"), 16));
		for (int step = 0; step < 2; step++) SimTicker.stepNow(world);
		belts.get(0).accept(new ItemStack(RcItems.ITEMS.get("satellite_bus")), 0);
		for (int tick = 0; tick < 1200; tick++) {
			for (var belt : belts) belt.serverTick(world);
			if (tick % 10 == 9) SimTicker.stepNow(world);
		}
		boolean mirror = false;
		if (world.getBlockEntity(line.east(5)) instanceof net.minecraft.block.entity.ChestBlockEntity chest) {
			for (int slot = 0; slot < chest.size(); slot++) mirror |= chest.getStack(slot).isOf(RcItems.ITEMS.get("dyson_mirror"));
		}
		check("L1.a", mirror && arms.get(0).getStack(0).isEmpty(),
				"mirror=" + mirror + " panelsLeft=" + arms.get(0).getStack(0).getCount() + " steps="
						+ arms.stream().map(arm -> arm.itemsMade() + "").toList(), failures);

		// The spaceport: a 3x3 pad, Launch Control on its west side, power, and a Rectenna to catch the mirror's beam.
		BlockPos pad = origin.add(4, 0, 4);
		for (BlockPos pos : BlockPos.iterate(pad.add(-1, 0, -1), pad.add(1, 0, 1))) world.setBlockState(pos, RcBlocks.get("launch_pad").getDefaultState());
		MachineBlockEntity control = place(world, pad.west(2), "launch_control", Direction.WEST);
		world.setBlockState(pad.west(3), RcBlocks.get("creative_power").getDefaultState());
		MachineBlockEntity rectenna = place(world, pad.west(2).north(), "rectenna", Direction.NORTH);
		control.setStack(0, new ItemStack(RcItems.ITEMS.get("rocket_stage"), 16));
		control.setStack(4, new ItemStack(RcItems.ITEMS.get("hydrogen_canister"), 16));
		control.setStack(3, new ItemStack(RcItems.ITEMS.get("comms_satellite")));
		for (int step = 0; step < 2; step++) SimTicker.stepNow(world);
		int drained = control.launchTank();
		int noFuel = control.processStatus();
		control.setLaunchTank(4000);
		dev.rackcraft.world.LaunchPads.failureChance = 0;
		SimTicker.stepNow(world);
		String reply = dev.rackcraft.world.LaunchPads.launch(world, control);
		boolean rocket = !world.getEntitiesByClass(dev.rackcraft.entity.RocketEntity.class, new net.minecraft.util.math.Box(pad).expand(4),
				net.minecraft.entity.Entity::isAlive).isEmpty();
		flyMission(world, control);
		check("L2.a", drained == 16 && noFuel == dev.rackcraft.world.LaunchPads.Status.NO_FUEL.ordinal() && rocket
						&& orbit.comms() == 1 && control.launchTank() == 4000 - 128 && control.getStack(0).getCount() == 15
						&& Math.abs(dev.rackcraft.world.OrbitState.deliveryFactor(world) - 0.9) < 1e-9,
				"drained=" + drained + " noFuelStatus=" + noFuel + " reply=" + reply + " rocket=" + rocket + " comms=" + orbit.comms()
						+ " tank=" + control.launchTank() + " stages=" + control.getStack(0).getCount(), failures);

		control.setStack(3, new ItemStack(RcItems.ITEMS.get("orbital_datacenter")));
		dev.rackcraft.world.LaunchPads.launch(world, control);
		flyMission(world, control);
		control.setStack(3, new ItemStack(RcItems.ITEMS.get("dyson_mirror")));
		dev.rackcraft.world.LaunchPads.launch(world, control);
		flyMission(world, control);
		for (int step = 0; step < 2; step++) SimTicker.stepNow(world);
		double ai = dev.rackcraft.compute.ResearchLab.effects(world).aiCompute();
		check("L2.b", orbit.datacenters() == 1 && orbit.mirrors() == 1 && Math.abs(ai / baseAi - 1.03) < 1e-9
						&& rectenna.networkCapacityKw() >= 2000 && control.getStack(0).getCount() == 10,
				"datacenters=" + orbit.datacenters() + " mirrors=" + orbit.mirrors() + " ai=" + ai + "/" + baseAi
						+ " rectennaCapacity=" + rectenna.networkCapacityKw() + " stages=" + control.getStack(0).getCount(), failures);

		// A failure: the stage and fuel are spent, the payload comes back, and nothing reaches orbit.
		dev.rackcraft.world.LaunchPads.failureChance = 1;
		control.setStack(3, new ItemStack(RcItems.ITEMS.get("comms_satellite")));
		int tankBefore = control.launchTank();
		dev.rackcraft.world.LaunchPads.launch(world, control);
		flyMission(world, control);
		dev.rackcraft.world.LaunchPads.failureChance = 1.0 / dev.rackcraft.world.LaunchPads.FAILURE_ODDS;
		check("L3.a", orbit.comms() == 1 && orbit.failures() == 1 && control.getStack(3).isOf(RcItems.ITEMS.get("comms_satellite"))
						&& control.launchTank() == tankBefore - 128,
				"comms=" + orbit.comms() + " failures=" + orbit.failures() + " payload=" + control.getStack(3) + " tank=" + control.launchTank(),
				failures);

		// A Survey Satellite brings back a map of something nobody has found.
		dev.rackcraft.world.LaunchPads.failureChance = 0;
		control.setStack(3, new ItemStack(RcItems.ITEMS.get("survey_satellite")));
		dev.rackcraft.world.LaunchPads.launch(world, control);
		flyMission(world, control);
		dev.rackcraft.world.LaunchPads.failureChance = 1.0 / dev.rackcraft.world.LaunchPads.FAILURE_ODDS;
		check("L4.a", orbit.surveys() == 1 && control.getStack(3).isOf(Items.FILLED_MAP),
				"surveys=" + orbit.surveys() + " slot=" + control.getStack(3) + " name=" + control.getStack(3).getName().getString(), failures);
		world.getEntitiesByClass(dev.rackcraft.entity.RocketEntity.class, new net.minecraft.util.math.Box(pad).expand(400),
				net.minecraft.entity.Entity::isAlive).forEach(net.minecraft.entity.Entity::discard);
		orbit.reset();
		clearArea(world, origin, 30, 12, 20);
	}

	/**
	 * Bigger cubes: a 6x6x6 of Battery Banks stays lone blocks (saying what research it needs) until Structural
	 * Engineering is done, then forms with the large casing; a 10x10x10 forms after Arcology, with its own casing.
	 */
	private static void checkMegastructures(ServerWorld world, int[] failures) {
		BlockPos origin = clearArea(world, new BlockPos(-2000, 150, -1536), 12, 12, 12);
		dev.rackcraft.compute.ResearchLab lab = dev.rackcraft.compute.ResearchLab.get(world);
		lab.reset();
		for (BlockPos pos : BlockPos.iterate(origin, origin.add(5, 5, 5))) world.setBlockState(pos, RcBlocks.get("battery_bank").getDefaultState());
		for (int step = 0; step < 2; step++) SimTicker.stepNow(world);
		MachineBlockEntity corner = machine(world, origin);
		boolean lockedFormed = world.getBlockState(origin).get(dev.rackcraft.block.ArrayMachineBlock.FORMED);
		check("G1.a", corner.lockedCube() == 6 && corner.reactorArraySize() == 1 && !lockedFormed
						&& dev.rackcraft.world.FaultFinder.faults(world, SimTicker.machines(world)).stream()
								.anyMatch(fault -> fault.label().contains("Structural Engineering")),
				"locked=" + corner.lockedCube() + " edge=" + corner.reactorArraySize() + " formed=" + lockedFormed, failures);
		lab.complete(world, dev.rackcraft.compute.Research.get("structural_engineering"));
		for (int step = 0; step < 2; step++) SimTicker.stepNow(world);
		var state = world.getBlockState(origin);
		check("G1.b", corner.reactorArraySize() == 6 && corner.lockedCube() == 0 && state.get(dev.rackcraft.block.ArrayMachineBlock.FORMED)
						&& state.get(dev.rackcraft.block.ArrayMachineBlock.SCALE) == 1
						&& SimTicker.batteryCapacityPerBank(6) > SimTicker.batteryCapacityPerBank(5) && SimTicker.batteryEfficiency(10) < 1
						&& dev.rackcraft.world.ReactorArrays.efficiency(10) < dev.rackcraft.world.ReactorArrays.efficiency(5),
				"edge=" + corner.reactorArraySize() + " scale=" + state.get(dev.rackcraft.block.ArrayMachineBlock.SCALE)
						+ " efficiency10=" + SimTicker.batteryEfficiency(10), failures);

		for (BlockPos pos : BlockPos.iterate(origin, origin.add(9, 9, 9))) world.setBlockState(pos, RcBlocks.get("battery_bank").getDefaultState());
		lab.complete(world, dev.rackcraft.compute.Research.get("space_frames"));
		lab.complete(world, dev.rackcraft.compute.Research.get("arcology"));
		for (int step = 0; step < 2; step++) SimTicker.stepNow(world);
		corner = machine(world, origin);
		state = world.getBlockState(origin);
		check("G1.c", corner.reactorArraySize() == 10 && state.get(dev.rackcraft.block.ArrayMachineBlock.SCALE) == 2
						&& dev.rackcraft.world.ReactorArrays.maxEdge(world) == 10,
				"edge=" + corner.reactorArraySize() + " scale=" + state.get(dev.rackcraft.block.ArrayMachineBlock.SCALE), failures);
		lab.reset();
		clearArea(world, origin, 12, 12, 12);
	}

	/**
	 * Wind and solar: a turbine makes its new 30 kW-class output; two Solar Arrays side by side share one network with
	 * no cable and both feed a load; breaking one part takes the whole array and drops one item; a Wind Tower runs on
	 * enough sections and stops when too short or blocked; and a Drone Dock services a worn tower.
	 */
	private static void checkRenewables(ServerWorld world, int[] failures) {
		BlockPos origin = clearArea(world, new BlockPos(-2200, 150, -1536), 30, 40, 20);
		long savedTime = world.getTimeOfDay();
		world.setTimeOfDay(6000);
		world.calculateAmbientDarkness();

		BlockPos turbinePos = origin.add(26, 40, 0);
		world.setBlockState(turbinePos, RcBlocks.get("wind_turbine").getDefaultState());
		world.setBlockState(turbinePos.down(), RcBlocks.get("creative_rack").getDefaultState());
		machine(world, turbinePos.down()).setCreativeValue(CreativeSettings.DRAW_KW, 100);
		for (int step = 0; step < 2; step++) SimTicker.stepNow(world);
		double expected = dev.rackcraft.world.Renewables.turbineKw(world, turbinePos);
		check("V1.a", Math.abs(machine(world, turbinePos).powerKw() - expected) < 0.01 && expected > 22,
				"turbine=" + machine(world, turbinePos).powerKw() + " expected=" + expected, failures);

		// Two arrays facing north, the second three blocks east of the first so they touch; a 60 kW load on the first.
		BlockPos arrayA = origin.add(2, 30, 6);
		BlockPos arrayB = arrayA.east(3);
		for (BlockPos start : List.of(arrayA, arrayB)) {
			for (int part = 0; part < 6; part++) {
				world.setBlockState(start.add(dev.rackcraft.block.SolarArrayBlock.offset(Direction.NORTH, part)), RcBlocks.get("solar_array")
						.getDefaultState().with(MachineBlock.FACING, Direction.NORTH).with(dev.rackcraft.block.SolarArrayBlock.PART, part));
			}
		}
		world.setBlockState(arrayA.south(), RcBlocks.get("creative_rack").getDefaultState());
		machine(world, arrayA.south()).setCreativeValue(CreativeSettings.DRAW_KW, 60);
		for (int step = 0; step < 3; step++) SimTicker.stepNow(world);
		double outA = machine(world, arrayA).powerKw();
		double outB = machine(world, arrayB).powerKw();
		check("V2.a", outA > 10 && outB > 10 && outA + outB > 59 && machine(world, arrayA).wear() > 0,
				"arrayA=" + outA + " arrayB=" + outB + " wear=" + machine(world, arrayA).wear(), failures);
		BlockPos broken = arrayA.add(dev.rackcraft.block.SolarArrayBlock.offset(Direction.NORTH, 4));
		world.breakBlock(broken, true);
		boolean gone = true;
		for (int part = 0; part < 6; part++) gone &= world.getBlockState(arrayA.add(dev.rackcraft.block.SolarArrayBlock.offset(Direction.NORTH, part))).isAir();
		int dropped = world.getEntitiesByClass(net.minecraft.entity.ItemEntity.class, new net.minecraft.util.math.Box(arrayA).expand(4),
				item -> item.getStack().isOf(RcBlocks.get("solar_array").asItem())).stream().mapToInt(item -> item.getStack().getCount()).sum();
		check("V2.b", gone && dropped == 1 && world.getBlockState(arrayB).isOf(RcBlocks.get("solar_array")),
				"allGone=" + gone + " dropped=" + dropped, failures);

		// A tower of 12 sections with a load at its foot; a 3-section stub beside it.
		BlockPos foot = origin.add(10, 0, 14);
		for (int y = 0; y < 12; y++) place(world, foot.up(y), "tower_section", Direction.NORTH);
		MachineBlockEntity nacelle = place(world, foot.up(12), "wind_nacelle", Direction.NORTH);
		world.setBlockState(foot.east(), RcBlocks.get("creative_rack").getDefaultState());
		machine(world, foot.east()).setCreativeValue(CreativeSettings.DRAW_KW, 200);
		BlockPos stub = foot.west(6);
		for (int y = 0; y < 3; y++) place(world, stub.up(y), "tower_section", Direction.NORTH);
		MachineBlockEntity shortTop = place(world, stub.up(3), "wind_nacelle", Direction.NORTH);
		for (int step = 0; step < 3; step++) SimTicker.stepNow(world);
		double towerOut = nacelle.powerKw();
		check("V3.a", towerOut > 60 && machine(world, foot.east()).powerSatisfaction() > 0.3 && nacelle.workers() == 12
						&& shortTop.processStatus() == dev.rackcraft.world.Renewables.TowerStatus.TOO_SHORT.ordinal() && shortTop.powerKw() == 0,
				"tower=" + towerOut + " sections=" + nacelle.workers() + " shortStatus=" + shortTop.processStatus(), failures);
		BlockPos inTheWay = foot.up(12).north().east(2);
		world.setBlockState(inTheWay, Blocks.STONE.getDefaultState());
		SimTicker.stepNow(world);
		int blocked = nacelle.processStatus();
		world.setBlockState(inTheWay, Blocks.AIR.getDefaultState());

		// Worn down, then serviced by a drone.
		nacelle.setWear(0.8);
		BlockPos dockPos = foot.add(-3, 0, -6);
		MachineBlockEntity dock = place(world, dockPos, "drone_dock", Direction.NORTH);
		world.setBlockState(dockPos.west(), RcBlocks.get("creative_power").getDefaultState());
		dock.setStack(0, new ItemStack(RcItems.ITEMS.get("maintenance_drone")));
		dock.setStack(1, new ItemStack(RcItems.ITEMS.get("hydrogen_canister")));
		for (int step = 0; step < 2; step++) SimTicker.stepNow(world);
		dev.rackcraft.world.DroneDocks.scanNow(world);
		flyDrones(world, dockPos);
		check("V3.b", blocked == dev.rackcraft.world.Renewables.TowerStatus.BLOCKED.ordinal() && nacelle.wear() < 0.01
						&& dock.getStack(0).getCount() == 1,
				"blockedStatus=" + blocked + " wear=" + nacelle.wear() + " dronesHome=" + dock.getStack(0).getCount(), failures);
		world.getEntitiesByClass(net.minecraft.entity.ItemEntity.class, new net.minecraft.util.math.Box(origin).expand(60), item -> true)
				.forEach(net.minecraft.entity.Entity::discard);
		world.setTimeOfDay(savedTime);
		clearArea(world, origin, 30, 40, 20);
	}

	/**
	 * Filling racks: an Exascale Cabinet on the same fiber as a storage array fills from it (the best module first, and
	 * only modules its tier accepts), then from a player's inventory when told which kind; Empty files them all back.
	 * A Data Hall blueprint's Exascale / Photonic cores size their Chillers to the heat.
	 */
	private static void checkRackFill(ServerWorld world, int[] failures) {
		BlockPos origin = clearArea(world, new BlockPos(-2400, 150, -1600), 12, 6, 6);
		world.setBlockState(origin, RcBlocks.get("creative_power").getDefaultState());
		MachineBlockEntity array = place(world, origin.east(), "storage_array", Direction.NORTH);
		array.setStack(0, new ItemStack(RcItems.ITEMS.get("drive_4k")));
		CableBlock fiber = (CableBlock) RcBlocks.get("fiber_cable");
		for (int dx = 2; dx <= 4; dx++) {
			BlockPos pos = origin.east(dx);
			world.setBlockState(pos, fiber.withConnections(fiber.getDefaultState(), world, pos));
		}
		MachineBlockEntity cabinet = place(world, origin.east(5), "exascale_cabinet", Direction.NORTH);
		SimTicker.stepNow(world);
		var storage = dev.rackcraft.storage.StorageService.networkAt(world, cabinet.getPos());
		storage.insert(dev.rackcraft.storage.ItemKey.of(RcItems.ITEMS.get("pi_node")), 5, false);
		storage.insert(dev.rackcraft.storage.ItemKey.of(RcItems.ITEMS.get("gpu_blade")), 20, false);
		storage.insert(dev.rackcraft.storage.ItemKey.of(RcItems.ITEMS.get("quantum_core")), 6, false);
		int best = dev.rackcraft.block.Racks.fill(cabinet, storage, null, 0);
		boolean quantum = true;
		for (int bay = 0; bay < 6; bay++) quantum &= cabinet.getStack(bay).isOf(RcItems.ITEMS.get("quantum_core"));
		check("RF1.a", best == 6 && quantum && storage.count(dev.rackcraft.storage.ItemKey.of(RcItems.ITEMS.get("quantum_core")), true) == 0,
				"filled=" + best + " quantum=" + quantum, failures);
		int gpuChoice = dev.rackcraft.block.Racks.fillChoices(dev.rackcraft.sim.ServerModel.Tier.EXASCALE).indexOf("gpu_blade") + 1;
		int rest = dev.rackcraft.block.Racks.fill(cabinet, storage, null, gpuChoice);
		boolean noStarter = true;
		for (int bay = 0; bay < 24; bay++) noStarter &= !cabinet.getStack(bay).isOf(RcItems.ITEMS.get("pi_node"));
		check("RF1.b", rest == 18 && noStarter && storage.count(dev.rackcraft.storage.ItemKey.of(RcItems.ITEMS.get("gpu_blade")), true) == 2
				&& storage.count(dev.rackcraft.storage.ItemKey.of(RcItems.ITEMS.get("pi_node")), true) == 5,
				"filled=" + rest + " starterIn=" + !noStarter, failures);
		int out = dev.rackcraft.block.Racks.empty(cabinet, storage, null);
		check("RF1.c", out == 24 && cabinet.getStack(0).isEmpty() && storage.count(dev.rackcraft.storage.ItemKey.of(RcItems.ITEMS.get("gpu_blade")), true) == 20,
				"emptied=" + out, failures);
		// Data Hall blueprints: the default is the old hall; a hotter blueprint gets more Chillers.
		MachineBlockEntity planner = place(world, origin.add(0, 2, 3), "site_planner", Direction.NORTH);
		var hall = dev.rackcraft.world.SitePlanner.hall(planner);
		dev.rackcraft.world.SitePlanner.cycleHallRack(planner);
		dev.rackcraft.world.SitePlanner.cycleHallRack(planner);
		dev.rackcraft.world.SitePlanner.cycleHallRack(planner);
		var cabinets = dev.rackcraft.world.SitePlanner.hall(planner);
		check("RF2.a", hall.tier() == dev.rackcraft.sim.ServerModel.Tier.SERVER && hall.module().equals("quantum_core") && hall.chillers() == 2
				&& cabinets.tier() == dev.rackcraft.sim.ServerModel.Tier.EXASCALE && cabinets.chillers() > 2,
				"default=" + hall + " chillers=" + hall.chillers() + " exascale=" + cabinets + " chillers=" + cabinets.chillers(), failures);
	}

	/**
	 * Storage logistics: a Belt Loader takes items from storage onto a belt and a Belt Unloader files them back, with
	 * nothing lost; a Drone Dock on the same storage replaces a failed GPU Blade with a GPU Blade from storage (not the
	 * Pi Node sitting in the dock), and files the dead module away.
	 */
	private static void checkStorageLogistics(ServerWorld world, int[] failures) {
		BlockPos origin = clearArea(world, new BlockPos(-2400, 150, -1536), 20, 6, 12);
		world.setBlockState(origin, RcBlocks.get("creative_power").getDefaultState());
		MachineBlockEntity array = place(world, origin.east(), "storage_array", Direction.NORTH);
		array.setStack(0, new ItemStack(RcItems.ITEMS.get("drive_4k")));
		CableBlock pipe = (CableBlock) RcBlocks.get("item_pipe");
		for (int dx = 2; dx <= 12; dx++) {
			BlockPos pos = origin.east(dx);
			world.setBlockState(pos, pipe.withConnections(pipe.getDefaultState(), world, pos));
		}
		MachineBlockEntity loader = place(world, origin.add(3, 1, 0), "belt_loader", Direction.SOUTH);
		loader.setStack(0, new ItemStack(RcItems.ITEMS.get("copper_wire")));
		List<dev.rackcraft.block.BeltBlockEntity> belts = new java.util.ArrayList<>();
		for (int x = 3; x <= 6; x++) {
			BlockPos pos = origin.add(x, 1, 1);
			world.setBlockState(pos, RcBlocks.get("conveyor_belt").getDefaultState()
					.with(dev.rackcraft.block.ConveyorBeltBlock.FACING, x < 6 ? Direction.EAST : Direction.NORTH));
			belts.add((dev.rackcraft.block.BeltBlockEntity) world.getBlockEntity(pos));
		}
		MachineBlockEntity unloader = place(world, origin.add(6, 1, 0), "belt_unloader", Direction.NORTH);
		SimTicker.stepNow(world);
		var storage = dev.rackcraft.storage.StorageService.networkAt(world, array.getPos());
		var wire = dev.rackcraft.storage.ItemKey.of(RcItems.ITEMS.get("copper_wire"));
		storage.insert(wire, 3, false);
		for (int tick = 0; tick < 400; tick++) {
			for (var belt : belts) belt.serverTick(world);
			if (tick % 20 == 0) dev.rackcraft.world.ItemPipes.step(world, SimTicker.machines(world));
		}
		long stored = storage.count(wire, true);
		long riding = belts.stream().filter(belt -> belt.stack().isOf(RcItems.ITEMS.get("copper_wire"))).count();
		long buffered = 0;
		for (int slot = 0; slot < unloader.size(); slot++) buffered += unloader.getStack(slot).getCount();
		check("SL1.a", loader.itemsMade() >= 4 && stored + riding + buffered == 3 && loader.getStack(0).getCount() == 1,
				"loaded=" + loader.itemsMade() + " stored=" + stored + " riding=" + riding + " buffered=" + buffered, failures);

		// The dock: a GPU Blade in storage, a Pi Node in the dock, and a rack whose GPU Blade failed.
		loader.setStack(0, ItemStack.EMPTY);
		MachineBlockEntity dock = place(world, origin.add(10, 1, 0), "drone_dock", Direction.NORTH);
		world.setBlockState(origin.add(10, 2, 0), RcBlocks.get("creative_power").getDefaultState());
		dock.setStack(0, new ItemStack(RcItems.ITEMS.get("maintenance_drone")));
		dock.setStack(1, new ItemStack(RcItems.ITEMS.get("hydrogen_canister")));
		dock.setStack(2, new ItemStack(RcItems.ITEMS.get("pi_node")));
		storage.insert(dev.rackcraft.storage.ItemKey.of(RcItems.ITEMS.get("gpu_blade")), 1, false);
		MachineBlockEntity rack = rack(world, origin.add(10, 1, 8), "pi_node");
		ItemStack dead = new ItemStack(RcItems.ITEMS.get("failed_module"));
		dead.getOrCreateNbt().putString(dev.rackcraft.world.DroneDocks.FAILED_KEY, "rackcraft:gpu_blade");
		rack.setStack(5, dead.copy());
		for (int step = 0; step < 2; step++) SimTicker.stepNow(world);
		dev.rackcraft.world.DroneDocks.scanNow(world);
		flyDrones(world, dock.getPos());
		dev.rackcraft.world.ItemPipes.step(world, SimTicker.machines(world));
		long deadStored = storage.count(dev.rackcraft.storage.ItemKey.of(dead), true);
		check("SL2.a", rack.getStack(5).isOf(RcItems.ITEMS.get("gpu_blade")) && dock.getStack(2).isOf(RcItems.ITEMS.get("pi_node"))
						&& storage.count(dev.rackcraft.storage.ItemKey.of(RcItems.ITEMS.get("gpu_blade")), true) == 0 && deadStored == 1
						&& dock.getStack(0).getCount() == 1,
				"slot5=" + rack.getStack(5) + " dockSpare=" + dock.getStack(2) + " deadStored=" + deadStored
						+ " drones=" + dock.getStack(0).getCount(), failures);

		// A Storage Exporter on the pipe stocks a Site Planner that isn't: a stack of cable and the drones.
		MachineBlockEntity exporter = place(world, origin.add(12, 1, 0), "storage_exporter", Direction.EAST);
		MachineBlockEntity planner = place(world, origin.add(13, 1, 0), "site_planner", Direction.NORTH);
		exporter.setStack(0, new ItemStack(RcBlocks.get("power_cable")));
		exporter.setStack(1, new ItemStack(RcItems.ITEMS.get("construction_drone")));
		var cable = dev.rackcraft.storage.ItemKey.of(RcBlocks.get("power_cable").asItem());
		storage.insert(cable, 100, false);
		storage.insert(dev.rackcraft.storage.ItemKey.of(RcItems.ITEMS.get("construction_drone")), 3, false);
		SimTicker.stepNow(world);
		dev.rackcraft.world.ItemPipes.step(world, SimTicker.machines(world));
		dev.rackcraft.world.ItemPipes.step(world, SimTicker.machines(world));
		int plannerCable = 0;
		for (int slot = 3; slot < 9; slot++) if (planner.getStack(slot).isOf(RcBlocks.get("power_cable").asItem())) plannerCable += planner.getStack(slot).getCount();
		check("SL3.a", plannerCable == 64 && planner.getStack(0).getCount() == 3 && storage.count(cable, true) == 36
						&& exporter.getStack(0).getCount() == 1,
				"plannerCable=" + plannerCable + " drones=" + planner.getStack(0) + " stored=" + storage.count(cable, true), failures);
		storage.extract(cable, 36, true, false);

		// Storage Links: a second, unconnected array across the room joins the first through a pair of links, and a lone
		// link with nothing but power reaches both.
		MachineBlockEntity farArray = place(world, origin.add(16, 1, 8), "storage_array", Direction.NORTH);
		farArray.setStack(0, new ItemStack(RcItems.ITEMS.get("drive_4k")));
		world.setBlockState(origin.add(16, 1, 9), RcBlocks.get("storage_link").getDefaultState());
		world.setBlockState(origin.add(16, 1, 10), RcBlocks.get("creative_power").getDefaultState());
		world.setBlockState(origin.add(1, 1, 0), RcBlocks.get("storage_link").getDefaultState());
		world.setBlockState(origin.add(2, 1, 0), RcBlocks.get("creative_power").getDefaultState());
		BlockPos lonePos = origin.add(18, 1, 2);
		world.setBlockState(lonePos, RcBlocks.get("storage_link").getDefaultState());
		world.setBlockState(lonePos.east(), RcBlocks.get("creative_power").getDefaultState());
		SimTicker.stepNow(world);
		SimTicker.stepNow(world);
		var farStorage = dev.rackcraft.storage.StorageService.networkAt(world, farArray.getPos());
		var redstone = dev.rackcraft.storage.ItemKey.of(Items.REDSTONE);
		farStorage.insert(redstone, 50, false);
		long nearSees = dev.rackcraft.storage.StorageService.networkAt(world, array.getPos()).count(redstone, true);
		long loneSees = dev.rackcraft.storage.StorageService.networkOf(world,
				dev.rackcraft.world.NetworkManager.get(world).component(lonePos, dev.rackcraft.sim.NetKind.ITEM)).count(redstone, true);
		check("SL4.a", nearSees == 50 && loneSees == 50 && machine(world, lonePos).siteReading(0) == 3,
				"nearSees=" + nearSees + " loneSees=" + loneSees + " links=" + machine(world, lonePos).siteReading(0), failures);
		farStorage.extract(redstone, 50, true, false);
		farArray.setStack(0, ItemStack.EMPTY);

		// An Exchange Auto-Buyer on the storage: a Diesel Generator on the pipe with no fuel anywhere gets some bought.
		world.setBlockState(origin.add(0, 1, 0), RcBlocks.get("auto_buyer").getDefaultState());
		MachineBlockEntity generator = place(world, origin.add(8, 1, 0), "diesel_generator", Direction.NORTH);
		FacilityManager.get(world).addCredits(1_000_000);
		SimTicker.stepNow(world);
		dev.rackcraft.world.ItemPipes.step(world, SimTicker.machines(world));
		MachineBlockEntity buyer = machine(world, origin.add(0, 1, 0));
		check("SL5.a", generator.getStack(0).isOf(RcItems.ITEMS.get("biodiesel_canister")) && buyer.site().getLong("Bought") == generator.getStack(0).getCount()
						&& buyer.site().getLong("Spent") == generator.getStack(0).getCount() * ExchangeCatalog.price(RcItems.ITEMS.get("biodiesel_canister")),
				"fuel=" + generator.getStack(0) + " bought=" + buyer.site().getLong("Bought") + " spent=" + buyer.site().getLong("Spent"), failures);
		array.setStack(0, ItemStack.EMPTY);
		clearArea(world, origin, 20, 6, 12);
	}

	/**
	 * Site construction, end to end. A 9 x 6 solar site with a tuft of grass, a flower, a little tree, a two-block hump
	 * and a hole: the planner clears it, levels it (cut earth fills the hole and the rest comes home), places nine
	 * arrays and lays the one cable to itself, in that order. Then a wind farm of two towers, which waits for a missing
	 * nacelle and then builds both to Y 130 with a cable between them.
	 */
	private static void checkSiteConstruction(ServerWorld world, int[] failures) {
		BlockPos origin = clearArea(world, new BlockPos(-2600, 100, -1536), 16, 40, 12);
		for (int cx = (origin.getX() - 4) >> 4; cx <= (origin.getX() + 16) >> 4; cx++) {
			for (int cz = (origin.getZ() - 4) >> 4; cz <= (origin.getZ() + 12) >> 4; cz++) world.setChunkForced(cx, cz, true);
		}
		for (BlockPos pos : BlockPos.iterate(origin.add(-2, -1, -2), origin.add(14, 0, 10))) world.setBlockState(pos, Blocks.DIRT.getDefaultState());
		world.setBlockState(origin.add(4, 1, 4), Blocks.DIRT.getDefaultState());
		world.setBlockState(origin.add(5, 1, 4), Blocks.DIRT.getDefaultState());
		world.setBlockState(origin.add(5, 2, 4), Blocks.DIRT.getDefaultState());
		world.setBlockState(origin.add(8, 0, 6), Blocks.AIR.getDefaultState());
		world.setBlockState(origin.add(3, 1, 3), Blocks.GRASS.getDefaultState());
		world.setBlockState(origin.add(6, 1, 6), Blocks.DANDELION.getDefaultState());
		world.setBlockState(origin.add(9, 1, 3), Blocks.OAK_LOG.getDefaultState());
		world.setBlockState(origin.add(9, 2, 3), Blocks.OAK_LOG.getDefaultState());
		for (BlockPos leaf : List.of(origin.add(9, 3, 3), origin.add(8, 2, 3), origin.add(10, 2, 3))) {
			world.setBlockState(leaf, Blocks.OAK_LEAVES.getDefaultState().with(net.minecraft.block.LeavesBlock.PERSISTENT, true));
		}
		BlockPos plannerPos = origin.add(0, 1, 4);
		MachineBlockEntity planner = place(world, plannerPos, "site_planner", Direction.NORTH);
		world.setBlockState(plannerPos.north(), RcBlocks.get("creative_power").getDefaultState());
		String inside = dev.rackcraft.world.SitePlanner.setArea(planner, plannerPos.add(-1, 0, -1), plannerPos.add(4, 0, 2));
		String tooBig = dev.rackcraft.world.SitePlanner.setArea(planner, origin, origin.add(60, 0, 3));
		dev.rackcraft.world.SitePlanner.setArea(planner, origin.add(2, 0, 2), origin.add(10, 0, 7));
		check("SC0.a", inside.contains("inside") && tooBig.startsWith("Too big") && dev.rackcraft.world.SitePlanner.site(planner) != null
						&& dev.rackcraft.world.SitePlanner.site(planner).width() == 9,
				"inside=" + inside + " tooBig=" + tooBig, failures);
		planner.setStack(0, new ItemStack(RcItems.ITEMS.get("construction_drone"), 4));
		planner.setStack(1, new ItemStack(RcItems.ITEMS.get("terraforming_drone"), 2));
		planner.setStack(2, new ItemStack(RcItems.ITEMS.get("hydrogen_canister"), 4));
		planner.setStack(3, new ItemStack(RcBlocks.get("solar_array"), 9));
		// No cable in the planner or its storage: with buying on, it comes from the Crypto Exchange.
		dev.rackcraft.world.SitePlanner.toggleBuying(planner);
		FacilityManager.get(world).addCredits(1_000_000);
		dev.rackcraft.world.SitePlanner.toggleRunning(planner);
		List<Integer> phases = runSite(world, planner);
		boolean arrays = true;
		for (int x = 2; x <= 8; x += 3) {
			for (int z = 7; z >= 3; z -= 2) {
				for (int part = 0; part < 6; part++) {
					var state = world.getBlockState(origin.add(x, 1, z).add(dev.rackcraft.block.SolarArrayBlock.offset(Direction.NORTH, part)));
					arrays &= state.isOf(RcBlocks.get("solar_array")) && state.get(dev.rackcraft.block.SolarArrayBlock.PART) == part;
				}
			}
		}
		boolean cleared = world.getBlockState(origin.add(9, 2, 3)).isAir() && world.getBlockState(origin.add(9, 3, 3)).isAir()
				&& world.getBlockState(origin.add(5, 2, 4)).isAir();
		boolean levelled = world.getBlockState(origin.add(8, 0, 6)).isOpaqueFullCube(world, origin.add(8, 0, 6));
		check("SC1.a", phases.indexOf(1) >= 0 && phases.indexOf(1) < phases.indexOf(2) && phases.indexOf(2) < phases.indexOf(3)
						&& phases.indexOf(3) < phases.indexOf(4) && phases.get(phases.size() - 1) == dev.rackcraft.world.SitePlanner.Phase.DONE.ordinal(),
				"phases=" + phases, failures);
		check("SC1.b", arrays && cleared && levelled && planner.getStack(3).isEmpty(),
				"arrays=" + arrays + " cleared=" + cleared + " levelled=" + levelled + " arraysLeft=" + planner.getStack(3), failures);
		var grid = dev.rackcraft.world.NetworkManager.get(world).component(plannerPos, dev.rackcraft.sim.NetKind.POWER);
		check("SC1.c", world.getBlockState(plannerPos.east()).isOf(RcBlocks.get("power_cable")) && grid.contains(origin.add(8, 1, 3))
						&& planner.getStack(0).getCount() == 4 && planner.getStack(1).getCount() == 2
						&& planner.site().getLong("Spent") == ExchangeCatalog.price(RcBlocks.get("power_cable").asItem()),
				"cable=" + world.getBlockState(plannerPos.east()) + " gridHasFarArray=" + grid.contains(origin.add(8, 1, 3))
						+ " drones=" + planner.getStack(0).getCount() + "/" + planner.getStack(1).getCount() + " spent=" + planner.site().getLong("Spent")
						+ " cablePrice=" + ExchangeCatalog.price(RcBlocks.get("power_cable").asItem()), failures);
		dev.rackcraft.world.SitePlanner.toggleBuying(planner);

		// A wind farm on the same ground, now level: two towers six apart, one nacelle short at first.
		for (BlockPos pos : BlockPos.iterate(origin.add(-2, 1, -2), origin.add(14, 4, 10))) {
			if (!world.getBlockState(pos).isAir() && !pos.equals(plannerPos) && !pos.equals(plannerPos.north())) world.setBlockState(pos, Blocks.AIR.getDefaultState());
		}
		world.setBlockState(origin.add(8, 0, 6), Blocks.DIRT.getDefaultState());
		for (int slot = 3; slot < 9; slot++) planner.setStack(slot, ItemStack.EMPTY);
		dev.rackcraft.world.SitePlanner.toggleRunning(planner);
		dev.rackcraft.world.SitePlanner.cycleLayout(planner);
		dev.rackcraft.world.SitePlanner.cycleLayout(planner);
		dev.rackcraft.world.SitePlanner.setArea(planner, origin.add(2, 0, 2), origin.add(12, 0, 6));
		int sections = dev.rackcraft.world.SitePlanner.nacelleY(origin.getY()) - origin.getY() - 1;
		planner.setStack(3, new ItemStack(RcBlocks.get("tower_section"), 64));
		planner.setStack(4, new ItemStack(RcBlocks.get("tower_section"), Math.max(1, sections * 2 - 64)));
		planner.setStack(5, new ItemStack(RcBlocks.get("wind_nacelle"), 1));
		planner.setStack(6, new ItemStack(RcBlocks.get("power_cable"), 16));
		planner.setStack(7, new ItemStack(RcItems.ITEMS.get("maintenance_drone"), 8));
		planner.setStack(8, new ItemStack(RcItems.ITEMS.get("hydrogen_canister"), 16));
		FacilityManager.get(world).addCredits(10_000_000);
		dev.rackcraft.world.SitePlanner.toggleDocks(planner);
		dev.rackcraft.world.SitePlanner.toggleRunning(planner);
		runSite(world, planner);
		int waiting = planner.processStatus();
		int needItem = planner.siteReading(dev.rackcraft.world.SitePlanner.R_NEED_ITEM) - 1;
		planner.setStack(5, new ItemStack(RcBlocks.get("wind_nacelle"), 1));
		runSite(world, planner);
		for (int step = 0; step < 2; step++) SimTicker.stepNow(world);
		int top = dev.rackcraft.world.SitePlanner.nacelleY(origin.getY());
		boolean towers = true;
		for (int x : new int[] {4, 10}) {
			BlockPos nacelle = new BlockPos(origin.getX() + x, top, origin.getZ() + 4);
			towers &= world.getBlockEntity(nacelle) instanceof MachineBlockEntity machine && machine.blockId().equals("wind_nacelle")
					&& dev.rackcraft.world.Renewables.sections(world, nacelle) == top - origin.getY() - 1
					&& dev.rackcraft.world.Renewables.towerStatus(world, machine) == dev.rackcraft.world.Renewables.TowerStatus.RUNNING;
		}
		boolean linked = true;
		for (int x = 5; x <= 9; x++) linked &= world.getBlockState(origin.add(x, 1, 4)).isOf(RcBlocks.get("power_cable"));
		check("SC2.a", waiting == dev.rackcraft.world.SitePlanner.Status.NEEDS_MATERIALS.ordinal()
						&& needItem == net.minecraft.registry.Registries.ITEM.getRawId(RcBlocks.get("wind_nacelle").asItem()),
				"status=" + waiting + " need=" + needItem, failures);
		// Docking: one dock covers both towers (the nacelles 30 blocks up included), stocked from the slots; drained, it is topped up.
		long[] spots = planner.site().getLongArray("DockAt");
		MachineBlockEntity siteDock = spots.length == 1 && world.getBlockEntity(BlockPos.fromLong(spots[0])) instanceof MachineBlockEntity found
				&& found.blockId().equals("drone_dock") ? found : null;
		boolean stocked = siteDock != null && siteDock.getStack(0).getCount() == 8 && siteDock.getStack(1).getCount() == 16
				&& siteDock.powerSatisfaction() > 0.99;
		if (siteDock != null) siteDock.setStack(1, new ItemStack(RcItems.ITEMS.get("hydrogen_canister"), 2));
		planner.setStack(8, new ItemStack(RcItems.ITEMS.get("hydrogen_canister"), 16));
		runSite(world, planner);
		check("SC3.a", stocked && siteDock.getStack(1).getCount() == 16 && planner.getStack(8).getCount() == 2,
				"spots=" + spots.length + " dock=" + (siteDock == null ? "none" : siteDock.getPos() + " drones=" + siteDock.getStack(0)
						+ " fuel=" + siteDock.getStack(1) + " power=" + siteDock.powerSatisfaction()) + " plannerFuelLeft=" + planner.getStack(8), failures);
		check("SC2.b", towers && linked && top == 130 && planner.processStatus() == dev.rackcraft.world.SitePlanner.Status.DONE.ordinal(),
				"towers=" + towers + " linked=" + linked + " top=" + top + " status=" + planner.processStatus(), failures);

		// A Data Hall, bought on the company card: one row pair, four columns wide, sixteen racks of Quantum Cores.
		for (BlockPos pos : BlockPos.iterate(origin.add(-2, 1, -2), origin.add(14, 34, 10))) {
			if (!world.getBlockState(pos).isAir() && !pos.equals(plannerPos) && !pos.equals(plannerPos.north())) world.setBlockState(pos, Blocks.AIR.getDefaultState());
		}
		// Nothing in the slots but what can't be bought: the hall buys its cable as well.
		for (int slot = 3; slot < 9; slot++) planner.setStack(slot, ItemStack.EMPTY);
		machine(world, plannerPos.north()).setCreativeValue(CreativeSettings.OUTPUT_KW, 5_000);
		dev.rackcraft.world.SitePlanner.toggleRunning(planner);
		dev.rackcraft.world.SitePlanner.toggleDocks(planner);
		dev.rackcraft.world.SitePlanner.cycleLayout(planner);
		dev.rackcraft.world.SitePlanner.setArea(planner, origin.add(2, 0, 2), origin.add(5, 0, 6));
		FacilityManager.get(world).addCredits(200_000_000);
		long before = planner.site().getLong("Spent");
		dev.rackcraft.world.SitePlanner.toggleRunning(planner);
		runSite(world, planner);
		for (int step = 0; step < 6; step++) SimTicker.stepNow(world);
		int racks = 0, mining = 0;
		double worstInlet = 0, toAir = 0, rate = 0;
		for (BlockPos pos : BlockPos.iterate(origin.add(2, 1, 3), origin.add(5, 2, 5))) {
			if (!(world.getBlockEntity(pos) instanceof MachineBlockEntity hallRack) || !hallRack.blockId().equals("server_rack")) continue;
			racks++;
			boolean full = true;
			for (int bay = 0; bay < 8; bay++) full &= hallRack.getStack(bay).isOf(RcItems.ITEMS.get("quantum_core"));
			if (full && hallRack.rackStatus() == RackStatus.MINING) mining++;
			worstInlet = Math.max(worstInlet, hallRack.inletCelsius());
			toAir += hallRack.heatToAirKw();
			rate += hallRack.miningRate();
		}
		check("SC4.a", racks == 16 && mining == 16 && worstInlet < 27 && toAir < 1 && rate >= 16 * 470
						&& planner.processStatus() == dev.rackcraft.world.SitePlanner.Status.DONE.ordinal()
						&& planner.site().getLong("Spent") > before + 16 * 8 * 100_000L,
				"racks=" + racks + " mining=" + mining + " worstInlet=" + worstInlet + " heatToAir=" + toAir + " rate=" + rate
						+ " status=" + planner.processStatus() + " spent=" + (planner.site().getLong("Spent") - before), failures);

		// The same hall again from a blueprint: Exascale Cabinets of GPU Blades, with the cabinets brought in by hand
		// (the Exchange doesn't sell them). The cooling has to grow to match.
		for (BlockPos pos : BlockPos.iterate(origin.add(-2, 1, -2), origin.add(14, 34, 10))) {
			if (!world.getBlockState(pos).isAir() && !pos.equals(plannerPos) && !pos.equals(plannerPos.north())) world.setBlockState(pos, Blocks.AIR.getDefaultState());
		}
		world.getEntitiesByClass(net.minecraft.entity.ItemEntity.class, new net.minecraft.util.math.Box(origin).expand(60), item -> true)
				.forEach(net.minecraft.entity.Entity::discard);
		dev.rackcraft.world.SitePlanner.toggleRunning(planner);
		planner.site().putString("HallRack", "exascale_cabinet");
		planner.site().putString("HallModule", "gpu_blade");
		planner.setStack(3, new ItemStack(RcBlocks.get("exascale_cabinet").asItem(), 16));
		dev.rackcraft.world.SitePlanner.setArea(planner, origin.add(2, 0, 2), origin.add(5, 0, 6));
		FacilityManager.get(world).addCredits(200_000_000);
		dev.rackcraft.world.SitePlanner.toggleRunning(planner);
		runSite(world, planner);
		for (int step = 0; step < 6; step++) SimTicker.stepNow(world);
		int cabinets = 0, cabinetsMining = 0;
		double hallInlet = 0;
		for (BlockPos pos : BlockPos.iterate(origin.add(2, 1, 3), origin.add(5, 2, 5))) {
			if (!(world.getBlockEntity(pos) instanceof MachineBlockEntity hallRack) || !hallRack.blockId().equals("exascale_cabinet")) continue;
			cabinets++;
			boolean full = true;
			for (int bay = 0; bay < 24; bay++) full &= hallRack.getStack(bay).isOf(RcItems.ITEMS.get("gpu_blade"));
			if (full && hallRack.rackStatus() == RackStatus.MINING) cabinetsMining++;
			hallInlet = Math.max(hallInlet, hallRack.inletCelsius());
		}
		check("SC4.b", cabinets == 16 && cabinetsMining == 16 && hallInlet < 27
						&& planner.processStatus() == dev.rackcraft.world.SitePlanner.Status.DONE.ordinal(),
				"cabinets=" + cabinets + " mining=" + cabinetsMining + " inlet=" + hallInlet + " status=" + planner.processStatus(), failures);

		world.getEntitiesByClass(net.minecraft.entity.ItemEntity.class, new net.minecraft.util.math.Box(origin).expand(60), item -> true)
				.forEach(net.minecraft.entity.Entity::discard);
		for (BlockPos pos : BlockPos.iterate(origin.add(-2, -1, -2), origin.add(14, 0, 10))) world.setBlockState(pos, Blocks.AIR.getDefaultState());
		clearArea(world, origin, 16, 40, 12);
		for (int cx = (origin.getX() - 4) >> 4; cx <= (origin.getX() + 16) >> 4; cx++) {
			for (int cz = (origin.getZ() - 4) >> 4; cz <= (origin.getZ() + 12) >> 4; cz++) world.setChunkForced(cx, cz, false);
		}
	}

	/**
	 * The Reactor Cube layout, the price quote and the ledger. Two 3-cubes of Modular Reactors on bought parts: the first Start
	 * only quotes (nothing is bought, no drone leaves), the quote is shown once, Approve buys and builds, and the ledger adds up
	 * to what was spent; a job with everything in hand is never quoted. Then a cube size is held to the research done, and a full
	 * 10 x 10 x 10 cube of a thousand reactors is built and forms. The Procurement Wall sees all of it.
	 */
	private static void checkSiteQuote(ServerWorld world, int[] failures) {
		dev.rackcraft.compute.ResearchLab lab = dev.rackcraft.compute.ResearchLab.get(world);
		lab.reset();
		BlockPos origin = clearArea(world, new BlockPos(-2800, 100, -1536), 22, 14, 22);
		for (int cx = (origin.getX() - 4) >> 4; cx <= (origin.getX() + 22) >> 4; cx++) {
			for (int cz = (origin.getZ() - 4) >> 4; cz <= (origin.getZ() + 22) >> 4; cz++) world.setChunkForced(cx, cz, true);
		}
		for (BlockPos pos : BlockPos.iterate(origin.add(-2, -1, -2), origin.add(20, 0, 20))) world.setBlockState(pos, Blocks.DIRT.getDefaultState());
		BlockPos plannerPos = origin.add(0, 1, 4);
		MachineBlockEntity planner = place(world, plannerPos, "site_planner", Direction.NORTH);
		world.setBlockState(plannerPos.north(), RcBlocks.get("creative_power").getDefaultState());
		MachineBlockEntity wall = place(world, plannerPos.south(), "procurement_wall", Direction.NORTH);
		planner.setStack(0, new ItemStack(RcItems.ITEMS.get("construction_drone"), 4));
		planner.setStack(2, new ItemStack(RcItems.ITEMS.get("hydrogen_canister"), 16));
		for (int layout = 0; layout < 4; layout++) dev.rackcraft.world.SitePlanner.cycleLayout(planner);
		check("SQ0.a", dev.rackcraft.world.SitePlanner.layout(planner) == dev.rackcraft.world.SitePlanner.Layout.REACTOR,
				"layout=" + dev.rackcraft.world.SitePlanner.layout(planner), failures);
		dev.rackcraft.world.SitePlanner.setArea(planner, origin.add(2, 0, 2), origin.add(8, 0, 4));
		long price = ExchangeCatalog.price(RcBlocks.get("modular_reactor").asItem());
		FacilityManager.get(world).addCredits(100_000_000_000L);
		long balance = FacilityManager.get(world).credits();
		int shownBefore = dev.rackcraft.world.SitePlanner.quotesShown();
		dev.rackcraft.world.SitePlanner.toggleRunning(planner);
		for (int scan = 0; scan < 4; scan++) {
			SimTicker.stepNow(world);
			dev.rackcraft.world.SitePlanner.scanNow(world);
		}
		var lines = dev.rackcraft.world.SitePlanner.quoteLines(planner);
		var reactorLine = lines.stream().filter(line -> line.item() == RcBlocks.get("modular_reactor").asItem()).findFirst().orElse(null);
		check("SQ1.a", dev.rackcraft.world.SitePlanner.awaiting(planner) && planner.processStatus() == dev.rackcraft.world.SitePlanner.Status.AWAITING_APPROVAL.ordinal()
						&& !dev.rackcraft.world.SitePlanner.running(planner) && reactorLine != null && reactorLine.count() == 54 && reactorLine.price() == price
						&& dev.rackcraft.world.SitePlanner.quoteTotal(planner) >= 54 * price,
				"awaiting=" + dev.rackcraft.world.SitePlanner.awaiting(planner) + " status=" + planner.processStatus() + " line=" + reactorLine
						+ " total=" + dev.rackcraft.world.SitePlanner.quoteTotal(planner), failures);
		check("SQ1.b", FacilityManager.get(world).credits() >= balance && planner.site().getLong("Spent") == 0
						&& dev.rackcraft.world.SitePlanner.drones(world, plannerPos).isEmpty()
						&& dev.rackcraft.world.SitePlanner.quotesShown() == shownBefore + 1,
				"balanceChange=" + (FacilityManager.get(world).credits() - balance) + " spent=" + planner.site().getLong("Spent")
						+ " quotes=" + (dev.rackcraft.world.SitePlanner.quotesShown() - shownBefore), failures);

		// The wall sees the waiting quote.
		for (int step = 0; step < 2; step++) SimTicker.stepNow(world);
		var seen = dev.rackcraft.world.ProcurementWall.snapshot(world, wall.getPos());
		var wire = net.fabricmc.fabric.api.networking.v1.PacketByteBufs.create();
		seen.write(wire);
		var decoded = dev.rackcraft.world.ProcurementWall.Snapshot.read(wire);
		var hud = dev.rackcraft.world.ProcurementWall.hud(world, SimTicker.machines(world), wall.getPos());
		check("SQ2.a", seen.powered() && seen.planners().size() == 1 && seen.planners().get(0).awaiting() && decoded.planners().size() == 1
						&& decoded.planners().get(0).quoteTotal() == dev.rackcraft.world.SitePlanner.quoteTotal(planner)
						&& decoded.planners().get(0).quote().stream().anyMatch(line -> line.item().equals("rackcraft:modular_reactor") && line.count() == 54)
						&& hud.awaiting() == 1 && hud.pending() == dev.rackcraft.world.SitePlanner.quoteTotal(planner),
				"powered=" + seen.powered() + " planners=" + seen.planners().size() + " hud=" + hud, failures);

		// Approve: the drones buy and build, a second quote never appears, and the ledger adds up.
		dev.rackcraft.world.SitePlanner.toggleRunning(planner);
		runSite(world, planner);
		for (int step = 0; step < 3; step++) SimTicker.stepNow(world);
		var cornerCube = dev.rackcraft.world.ReactorArrays.arrayOf(world, machine(world, origin.add(2, 1, 2)));
		var otherCube = dev.rackcraft.world.ReactorArrays.arrayOf(world, machine(world, origin.add(6, 1, 2)));
		long ledgerReactors = 0;
		long ledgerCost = 0;
		for (var purchase : dev.rackcraft.world.SitePlanner.ledger(planner)) {
			ledgerCost += purchase.cost();
			if (purchase.item().equals("rackcraft:modular_reactor")) ledgerReactors += purchase.count();
		}
		check("SQ3.a", cornerCube != null && cornerCube.edge() == 3 && cornerCube.cores() == 27 && otherCube != null && otherCube.edge() == 3
						&& otherCube != cornerCube && world.getBlockState(origin.add(5, 1, 2)).isAir()
						&& planner.processStatus() == dev.rackcraft.world.SitePlanner.Status.DONE.ordinal(),
				"cube=" + (cornerCube == null ? "none" : cornerCube.edge() + "/" + cornerCube.cores()) + " other="
						+ (otherCube == null ? "none" : otherCube.edge() + "/" + otherCube.cores()) + " status=" + planner.processStatus(), failures);
		check("SQ3.b", ledgerReactors == 54 && ledgerCost == planner.site().getLong("Spent") && ledgerCost >= 54 * price
						&& dev.rackcraft.world.SitePlanner.quotesShown() == shownBefore + 1
						&& !dev.rackcraft.world.SitePlanner.approved(planner) && planner.site().getBoolean("JobDone"),
				"reactors=" + ledgerReactors + " ledgerCost=" + ledgerCost + " spent=" + planner.site().getLong("Spent") + " quotes=" + (dev.rackcraft.world.SitePlanner.quotesShown() - shownBefore), failures);
		var afterBuy = dev.rackcraft.world.ProcurementWall.snapshot(world, wall.getPos());
		check("SQ2.b", afterBuy.rows().size() == dev.rackcraft.world.SitePlanner.ledger(planner).size() && !afterBuy.rows().isEmpty()
						&& afterBuy.planners().get(0).spent() == ledgerCost && !afterBuy.planners().get(0).awaiting(),
				"rows=" + afterBuy.rows().size() + " ledger=" + dev.rackcraft.world.SitePlanner.ledger(planner).size(), failures);

		// A job with everything already in hand has nothing to quote: no preview, no purchases.
		for (BlockPos pos : BlockPos.iterate(origin.add(1, 1, 0), origin.add(20, 12, 20))) {
			if (!world.getBlockState(pos).isAir() && !pos.equals(plannerPos) && !pos.equals(plannerPos.north()) && !pos.equals(plannerPos.south())) {
				world.setBlockState(pos, Blocks.AIR.getDefaultState());
			}
		}
		world.getEntitiesByClass(net.minecraft.entity.ItemEntity.class, new net.minecraft.util.math.Box(origin).expand(60), item -> true)
				.forEach(net.minecraft.entity.Entity::discard);
		dev.rackcraft.world.SitePlanner.toggleRunning(planner);
		dev.rackcraft.world.SitePlanner.setArea(planner, origin.add(2, 0, 2), origin.add(8, 0, 4));
		for (int slot = 3; slot < 9; slot++) planner.setStack(slot, ItemStack.EMPTY);
		planner.setStack(3, new ItemStack(RcBlocks.get("modular_reactor"), 54));
		planner.setStack(4, new ItemStack(RcBlocks.get("power_cable"), 64));
		long spentBefore = planner.site().getLong("Spent");
		int shownMid = dev.rackcraft.world.SitePlanner.quotesShown();
		dev.rackcraft.world.SitePlanner.toggleRunning(planner);
		runSite(world, planner);
		check("SQ4.a", dev.rackcraft.world.SitePlanner.quotesShown() == shownMid && planner.site().getLong("Spent") == spentBefore
						&& planner.processStatus() == dev.rackcraft.world.SitePlanner.Status.DONE.ordinal()
						&& world.getBlockState(origin.add(2, 3, 2)).isOf(RcBlocks.get("modular_reactor")),
				"quotes=" + (dev.rackcraft.world.SitePlanner.quotesShown() - shownMid) + " spentDelta=" + (planner.site().getLong("Spent") - spentBefore)
						+ " status=" + planner.processStatus(), failures);

		// Cube sizes are held to the research: 5 without it, 10 with Arcology; the button walks 2 up to the limit and round.
		dev.rackcraft.world.SitePlanner.toggleRunning(planner);
		dev.rackcraft.world.SitePlanner.setArea(planner, origin.add(2, 0, 2), origin.add(13, 0, 13));
		planner.site().putInt("ReactorEdge", 10);
		int plain = dev.rackcraft.world.SitePlanner.effectiveEdge(world, planner, dev.rackcraft.world.SitePlanner.site(planner));
		planner.site().putInt("ReactorEdge", 2);
		List<Integer> walked = new java.util.ArrayList<>();
		for (int press = 0; press < 5; press++) {
			dev.rackcraft.world.SitePlanner.cycleReactorEdge(planner);
			walked.add(dev.rackcraft.world.SitePlanner.reactorEdge(planner));
		}
		lab.complete(world, dev.rackcraft.compute.Research.get("arcology"));
		planner.site().putInt("ReactorEdge", 10);
		int researched = dev.rackcraft.world.SitePlanner.effectiveEdge(world, planner, dev.rackcraft.world.SitePlanner.site(planner));
		dev.rackcraft.world.SitePlanner.setArea(planner, origin.add(2, 0, 2), origin.add(6, 0, 4));
		int small = dev.rackcraft.world.SitePlanner.effectiveEdge(world, planner, dev.rackcraft.world.SitePlanner.site(planner));
		check("SQ5.a", plain == 5 && walked.equals(List.of(3, 4, 5, 2, 3)) && researched == 10 && small == 3,
				"plain=" + plain + " walked=" + walked + " researched=" + researched + " smallSite=" + small, failures);

		// The full 10 x 10 x 10: a thousand reactors in ten layers, built by four drones, and it forms one cube.
		for (BlockPos pos : BlockPos.iterate(origin.add(1, 1, 0), origin.add(20, 12, 20))) {
			if (!world.getBlockState(pos).isAir() && !pos.equals(plannerPos) && !pos.equals(plannerPos.north()) && !pos.equals(plannerPos.south())) {
				world.setBlockState(pos, Blocks.AIR.getDefaultState());
			}
		}
		world.getEntitiesByClass(net.minecraft.entity.ItemEntity.class, new net.minecraft.util.math.Box(origin).expand(60), item -> true)
				.forEach(net.minecraft.entity.Entity::discard);
		for (int slot = 3; slot < 9; slot++) planner.setStack(slot, ItemStack.EMPTY);
		dev.rackcraft.world.SitePlanner.setArea(planner, origin.add(2, 0, 2), origin.add(11, 0, 11));
		planner.site().putInt("ReactorEdge", 10);
		planner.setStack(0, new ItemStack(RcItems.ITEMS.get("construction_drone"), 4));
		planner.setStack(2, new ItemStack(RcItems.ITEMS.get("hydrogen_canister"), 64));
		long before10 = FacilityManager.get(world).credits();
		dev.rackcraft.world.SitePlanner.toggleRunning(planner);
		dev.rackcraft.world.SitePlanner.scanNow(world);
		var line10 = dev.rackcraft.world.SitePlanner.quoteLines(planner).stream().filter(line -> line.item() == RcBlocks.get("modular_reactor").asItem())
				.findFirst().orElse(null);
		boolean quoted10 = dev.rackcraft.world.SitePlanner.awaiting(planner) && line10 != null && line10.count() == 1000
				&& planner.siteReading(dev.rackcraft.world.SitePlanner.R_EDGE) == 10
				&& FacilityManager.get(world).credits() >= before10;
		runSite(world, planner);
		for (int step = 0; step < 3; step++) SimTicker.stepNow(world);
		var big = dev.rackcraft.world.ReactorArrays.arrayOf(world, machine(world, origin.add(2, 1, 2)));
		check("SQ6.a", quoted10 && big != null && big.edge() == 10 && big.cores() == 1000
						&& world.getBlockState(origin.add(2, 1, 2)).get(dev.rackcraft.block.ArrayMachineBlock.FORMED)
						&& world.getBlockState(origin.add(2, 1, 2)).get(dev.rackcraft.block.ArrayMachineBlock.SCALE) == 2
						&& planner.processStatus() == dev.rackcraft.world.SitePlanner.Status.DONE.ordinal(),
				"quoted=" + quoted10 + " line=" + line10 + " cube=" + (big == null ? "none" : big.edge() + "/" + big.cores())
						+ " status=" + planner.processStatus(), failures);

		lab.reset();
		world.getEntitiesByClass(net.minecraft.entity.ItemEntity.class, new net.minecraft.util.math.Box(origin).expand(60), item -> true)
				.forEach(net.minecraft.entity.Entity::discard);
		for (BlockPos pos : BlockPos.iterate(origin.add(-2, -1, -2), origin.add(20, 0, 20))) world.setBlockState(pos, Blocks.AIR.getDefaultState());
		clearArea(world, origin, 22, 14, 22);
		for (int cx = (origin.getX() - 4) >> 4; cx <= (origin.getX() + 22) >> 4; cx++) {
			for (int cz = (origin.getZ() - 4) >> 4; cz <= (origin.getZ() + 22) >> 4; cz++) world.setChunkForced(cx, cz, false);
		}
	}

	/**
	 * Assembly Robots fetch the part a workpiece on (or coming down) their belt needs, from storage or by Auto-Buyer; and
	 * the three Belts projects double the speed of belts and robots each time (2x, 4x, 8x).
	 */
	private static void checkLineSpeedAndFeeding(ServerWorld world, int[] failures) {
		dev.rackcraft.compute.ResearchLab lab = dev.rackcraft.compute.ResearchLab.get(world);
		lab.reset();
		BlockPos origin = clearArea(world, new BlockPos(-3000, 150, -1536), 20, 6, 12);
		world.setBlockState(origin, RcBlocks.get("creative_power").getDefaultState());
		MachineBlockEntity array = place(world, origin.east(), "storage_array", Direction.NORTH);
		array.setStack(0, new ItemStack(RcItems.ITEMS.get("drive_4k")));
		CableBlock pipe = (CableBlock) RcBlocks.get("item_pipe");
		for (int dx = 2; dx <= 8; dx++) {
			BlockPos pos = origin.east(dx);
			world.setBlockState(pos, pipe.withConnections(pipe.getDefaultState(), world, pos));
		}
		MachineBlockEntity robot = place(world, origin.add(6, 1, 0), "assembly_arm", Direction.SOUTH);
		world.setBlockState(origin.add(6, 2, 0), RcBlocks.get("creative_power").getDefaultState());
		List<dev.rackcraft.block.BeltBlockEntity> belts = new java.util.ArrayList<>();
		for (int x = 3; x <= 6; x++) {
			BlockPos pos = origin.add(x, 1, 1);
			world.setBlockState(pos, RcBlocks.get("conveyor_belt").getDefaultState().with(dev.rackcraft.block.ConveyorBeltBlock.FACING, Direction.EAST));
			belts.add((dev.rackcraft.block.BeltBlockEntity) world.getBlockEntity(pos));
		}
		SimTicker.stepNow(world);
		var storage = dev.rackcraft.storage.StorageService.networkAt(world, array.getPos());
		var motor = dev.rackcraft.storage.ItemKey.of(RcItems.ITEMS.get("electric_motor"));
		storage.insert(motor, 40, false);
		// Nothing on the belts: the robot fetches nothing.
		dev.rackcraft.world.ItemPipes.step(world, SimTicker.machines(world));
		boolean idle = robot.isEmpty();
		// A Drone Frame two belts upstream needs four motors installed first.
		belts.get(1).accept(new ItemStack(RcItems.ITEMS.get("drone_frame")), 0);
		dev.rackcraft.world.ItemPipes.step(world, SimTicker.machines(world));
		int fetched = dev.rackcraft.world.AssemblyLine.parts(robot, RcItems.ITEMS.get("electric_motor"));
		check("LF1.a", idle && fetched == 16 && storage.count(motor, true) == 24,
				"idle=" + idle + " fetched=" + fetched + " storageLeft=" + storage.count(motor, true), failures);

		// With none in storage, an Auto-Buyer on the network buys them instead.
		robot.setStack(0, ItemStack.EMPTY);
		storage.extract(motor, 24, true, false);
		world.setBlockState(origin.add(1, 1, 0), RcBlocks.get("auto_buyer").getDefaultState());
		world.setBlockState(origin.add(1, 2, 0), RcBlocks.get("creative_power").getDefaultState());
		FacilityManager.get(world).addCredits(10_000_000);
		SimTicker.stepNow(world);
		dev.rackcraft.world.ItemPipes.step(world, SimTicker.machines(world));
		MachineBlockEntity buyer = machine(world, origin.add(1, 1, 0));
		int bought = dev.rackcraft.world.AssemblyLine.parts(robot, RcItems.ITEMS.get("electric_motor"));
		check("LF1.b", bought == 16 && buyer.site().getLong("Bought") == 16,
				"robotMotors=" + bought + " bought=" + buyer.site().getLong("Bought"), failures);
		robot.setStack(0, ItemStack.EMPTY);
		belts.get(1).take();

		// Speed: a lone welder on a belt, then the same with the three Belts projects done.
		MachineBlockEntity welder = place(world, origin.add(10, 1, 0), "welding_arm", Direction.SOUTH);
		world.setBlockState(origin.add(10, 2, 0), RcBlocks.get("creative_power").getDefaultState());
		machine(world, origin.add(10, 2, 0)).setCreativeValue(CreativeSettings.OUTPUT_KW, 5_000);
		BlockPos weldBelt = origin.add(10, 1, 1);
		world.setBlockState(weldBelt, RcBlocks.get("conveyor_belt").getDefaultState().with(dev.rackcraft.block.ConveyorBeltBlock.FACING, Direction.EAST));
		var onWeld = (dev.rackcraft.block.BeltBlockEntity) world.getBlockEntity(weldBelt);
		int[] steps = new int[2];
		double[] speeds = new double[2];
		for (int run = 0; run < 2; run++) {
			if (run == 1) {
				for (String id : List.of("line_speed_1", "line_speed_2", "line_speed_3")) lab.complete(world, dev.rackcraft.compute.Research.get(id));
			}
			SimTicker.stepNow(world);
			onWeld.take();
			welder.setItemsMade(0);
			onWeld.accept(new ItemStack(RcItems.ITEMS.get("tower_frame")), 0);
			for (int tick = 0; tick < 400 && welder.itemsMade() == 0; tick++) {
				onWeld.serverTick(world);
				if (tick % 5 == 4) {
					SimTicker.stepNow(world);
					steps[run]++;
				}
			}
			speeds[run] = onWeld.speed();
		}
		check("LF2.a", dev.rackcraft.world.AssemblyLine.lineSpeed(world) == 8 && Math.abs(speeds[0] - 0.05) < 1e-9 && Math.abs(speeds[1] - 0.4) < 1e-9
						&& steps[1] > 0 && steps[1] * 6 <= steps[0],
				"lineSpeed=" + dev.rackcraft.world.AssemblyLine.lineSpeed(world) + " beltSpeeds=" + speeds[0] + "/" + speeds[1]
						+ " weldSteps=" + steps[0] + "/" + steps[1], failures);

		lab.reset();
		clearArea(world, origin, 20, 6, 12);
	}

	/**
	 * Runs a Site Planner until it is done or stuck: plan, fly every drone it sent home, repeat. Returns the phases it
	 * went through, in order.
	 */
	private static List<Integer> runSite(ServerWorld world, MachineBlockEntity planner) {
		List<Integer> phases = new java.util.ArrayList<>();
		for (int round = 0; round < 60; round++) {
			SimTicker.stepNow(world);
			dev.rackcraft.world.SitePlanner.scanNow(world);
			// A quote is waiting on the player's yes: the helper says it for them.
			if (dev.rackcraft.world.SitePlanner.awaiting(planner)) {
				dev.rackcraft.world.SitePlanner.toggleRunning(planner);
				dev.rackcraft.world.SitePlanner.scanNow(world);
			}
			int phase = planner.siteReading(dev.rackcraft.world.SitePlanner.R_PHASE);
			if (phases.isEmpty() || phases.get(phases.size() - 1) != phase) phases.add(phase);
			List<dev.rackcraft.entity.ConstructionDroneEntity> flying = dev.rackcraft.world.SitePlanner.drones(world, planner.getPos());
			if (flying.isEmpty() && (phase == dev.rackcraft.world.SitePlanner.Phase.DONE.ordinal()
					|| planner.processStatus() != dev.rackcraft.world.SitePlanner.Status.WORKING.ordinal())) break;
			for (int tick = 0; tick < 4000 && !flying.isEmpty(); tick++) {
				flying.forEach(dev.rackcraft.entity.ConstructionDroneEntity::serverTick);
				flying = flying.stream().filter(net.minecraft.entity.Entity::isAlive).toList();
			}
		}
		return phases;
	}

	/** Steps the simulation through a launch's countdown and flight. */
	/**
	 * Advanced hardware: rack tiers take their bays and modules and deal with heat their own way; Quantum Annealers need a
	 * cold Cryostat that boils off hydrogen; NPUs only serve inference; FPGAs switch modes; the new cubes and the Assembly
	 * Line wait for research; a diced wafer comes off the line as eight chiplets of one bin; and none of it is for sale.
	 */
	private static void checkAdvancedHardware(ServerWorld world, int[] failures) {
		dev.rackcraft.compute.ResearchLab lab = dev.rackcraft.compute.ResearchLab.get(world);
		lab.reset();
		BlockPos origin = clearArea(world, new BlockPos(-1900, 150, -1536), 40, 8, 24);

		// Bays and what goes in them.
		MachineBlockEntity server = place(world, origin, "server_rack", Direction.NORTH);
		MachineBlockEntity dense = place(world, origin.east(4), "high_density_rack", Direction.NORTH);
		MachineBlockEntity bath = place(world, origin.east(8), "immersion_rack", Direction.NORTH);
		MachineBlockEntity exa = place(world, origin.east(12), "exascale_cabinet", Direction.NORTH);
		ItemStack pi = new ItemStack(RcItems.ITEMS.get("pi_node"));
		ItemStack gpu = new ItemStack(RcItems.ITEMS.get("gpu_blade"));
		check("AH1.a", server.size() == 9 && !server.isValid(8, pi) && dense.isValid(11, pi) && !dense.isValid(12, pi)
						&& bath.isValid(15, pi) && exa.size() == 24 && exa.isValid(23, gpu) && !exa.isValid(0, pi)
						&& exa.isValid(0, new ItemStack(RcItems.ITEMS.get("quantum_annealer"))),
				"sizes=" + server.size() + "/" + dense.size() + "/" + bath.size() + "/" + exa.size(), failures);
		ItemStack fpga = new ItemStack(RcItems.ITEMS.get("fpga_module"));
		var mining = dev.rackcraft.block.Racks.module(fpga);
		fpga.getOrCreateNbt().putInt(dev.rackcraft.block.Racks.FPGA_MODE, 2);
		check("AH1.b", mining == dev.rackcraft.sim.ServerModel.Module.FPGA_MINING
						&& dev.rackcraft.block.Racks.module(fpga) == dev.rackcraft.sim.ServerModel.Module.FPGA_CRAFTING
						&& dev.rackcraft.sim.ServerModel.Module.FPGA_CRAFTING.compute() == 20,
				"default=" + mining + " reflashed=" + dev.rackcraft.block.Racks.module(fpga), failures);
		for (MachineBlockEntity rack : List.of(server, dense, bath, exa)) world.setBlockState(rack.getPos(), Blocks.AIR.getDefaultState());

		// A High-Density Rack of air-cooled 1U servers: off a loop its heat goes to the air; on one, its built-in door
		// catches it all. A Liquid-Immersion Rack won't run off a loop at all, and on one sends nothing to the air.
		dense = place(world, origin, "high_density_rack", Direction.NORTH);
		world.setBlockState(origin.west(), RcBlocks.get("creative_power").getDefaultState());
		for (int slot = 0; slot < 12; slot++) dense.setStack(slot, new ItemStack(RcItems.ITEMS.get("server_1u")));
		bath = place(world, origin.east(6), "immersion_rack", Direction.NORTH);
		world.setBlockState(origin.east(5), RcBlocks.get("creative_power").getDefaultState());
		for (int slot = 0; slot < 16; slot++) bath.setStack(slot, new ItemStack(RcItems.ITEMS.get("server_1u")));
		for (int step = 0; step < 6; step++) SimTicker.stepNow(world);
		double denseAirOff = dense.heatToAirKw();
		RackStatus dryBath = bath.rackStatus();
		MachineBlockEntity denseChiller = place(world, origin.up(), "chiller", Direction.NORTH);
		MachineBlockEntity bathChiller = place(world, origin.east(6).up(), "chiller", Direction.NORTH);
		for (int step = 0; step < 10; step++) SimTicker.stepNow(world);
		check("AH2.a", denseAirOff > 6 && dense.heatToLoopKw() > 6 && dense.heatToAirKw() < 0.1 && denseChiller.coolingKw() > 6,
				"dense: offLoopToAir=" + denseAirOff + " toLoop=" + dense.heatToLoopKw() + " toAir=" + dense.heatToAirKw(), failures);
		check("AH2.b", dryBath == RackStatus.NEEDS_WATER && bath.heatToLoopKw() > 8 && bath.heatToAirKw() < 0.01 && bathChiller.coolingKw() > 8,
				"immersion: offLoop=" + dryBath + " toLoop=" + bath.heatToLoopKw() + " toAir=" + bath.heatToAirKw(), failures);
		clearArea(world, origin, 40, 8, 24);

		// An Exascale Cabinet of GPU Blades mines 20% more than the same blades would, on half the bandwidth, and its
		// switches draw 30 kW on top.
		exa = place(world, origin, "exascale_cabinet", Direction.NORTH);
		world.setBlockState(origin.west(), RcBlocks.get("creative_power").getDefaultState());
		machine(world, origin.west()).setCreativeValue(CreativeSettings.OUTPUT_KW, 1_000);
		place(world, origin.up(), "chiller", Direction.NORTH);
		place(world, origin.up(2), "chiller", Direction.NORTH);
		MachineBlockEntity core = place(world, origin.east(), "core_router", Direction.NORTH);
		for (int slot = 0; slot < 24; slot++) exa.setStack(slot, new ItemStack(RcItems.ITEMS.get("gpu_blade")));
		for (int step = 0; step < 10; step++) SimTicker.stepNow(world);
		check("AH3.a", exa.rackStatus() == RackStatus.MINING && Math.abs(exa.miningRate() - 24 * 12 * 1.2) < 1
						&& exa.powerKw() > 24 * 3 + 29 && Math.abs(core.dataDemand() - 24 * 12 * 1.2 * 0.5) < 1 && exa.heatToAirKw() < 0.1,
				"status=" + exa.rackStatus() + " rate=" + exa.miningRate() + " draw=" + exa.powerKw() + " bandwidthNeed=" + core.dataDemand()
						+ " toAir=" + exa.heatToAirKw(), failures);
		clearArea(world, origin, 40, 8, 24);

		// A Quantum Annealer waits for a cold Cryostat, mines 120 RC/s beside one, and stops when its hydrogen runs out.
		MachineBlockEntity annealerRack = place(world, origin, "server_rack", Direction.NORTH);
		world.setBlockState(origin.west(), RcBlocks.get("creative_power").getDefaultState());
		place(world, origin.up(), "chiller", Direction.NORTH);
		place(world, origin.down(), "core_router", Direction.NORTH);
		annealerRack.setStack(0, new ItemStack(RcItems.ITEMS.get("quantum_annealer")));
		for (int step = 0; step < 4; step++) SimTicker.stepNow(world);
		RackStatus warm = annealerRack.rackStatus();
		MachineBlockEntity cryostat = place(world, origin.east(), "cryostat", Direction.NORTH);
		world.setBlockState(origin.east(2), RcBlocks.get("creative_power").getDefaultState());
		cryostat.setStack(0, new ItemStack(RcItems.ITEMS.get("hydrogen_canister"), 1));
		for (int step = 0; step < 6; step++) SimTicker.stepNow(world);
		RackStatus cold = annealerRack.rackStatus();
		double coldRate = annealerRack.miningRate();
		int cooling = cryostat.workers();
		cryostat.setWorkProgress(0.999);
		for (int step = 0; step < 6; step++) SimTicker.stepNow(world);
		check("AH4.a", warm == RackStatus.NEEDS_CRYOSTAT && cold == RackStatus.MINING && Math.abs(coldRate - 120) < 1 && cooling == 1
						&& cryostat.getStack(0).isEmpty() && cryostat.itemsMade() == 1 && annealerRack.rackStatus() == RackStatus.NEEDS_CRYOSTAT,
				"warm=" + warm + " cold=" + cold + " rate=" + coldRate + " cooling=" + cooling + " left=" + cryostat.getStack(0)
						+ " after=" + annealerRack.rackStatus(), failures);
		clearArea(world, origin, 40, 8, 24);

		// NPUs lend AI compute to inference (contracts, leases) and nothing to training or R&D.
		MachineBlockEntity npuRack = place(world, origin, "server_rack", Direction.NORTH);
		world.setBlockState(origin.west(), RcBlocks.get("creative_power").getDefaultState());
		for (int slot = 0; slot < 8; slot++) npuRack.setStack(slot, new ItemStack(RcItems.ITEMS.get("npu_card")));
		for (int step = 0; step < 4; step++) SimTicker.stepNow(world);
		double inference = dev.rackcraft.compute.Cluster.compute(npuRack, dev.rackcraft.compute.Cluster.Kind.INFERENCE);
		double training = dev.rackcraft.compute.Cluster.compute(npuRack, dev.rackcraft.compute.Cluster.Kind.AI);
		check("AH5.a", Math.abs(inference - 8 * 32) < 0.5 && training == 0,
				"inference=" + inference + " ai=" + training, failures);
		// With nothing for it to do, a rack that can't mine says so instead of "Mining 0 RC/s".
		place(world, origin.up(), "core_router", Direction.NORTH);
		for (int step = 0; step < 3; step++) SimTicker.stepNow(world);
		check("AH5.b", npuRack.rackStatus() == RackStatus.NO_MINERS && npuRack.miningRate() == 0,
				"status=" + npuRack.rackStatus() + " rate=" + npuRack.miningRate(), failures);
		clearArea(world, origin, 40, 8, 24);

		// The CVD Furnace waits for Advanced Materials, then grows graphene from coke and hydrogen.
		for (BlockPos pos : BlockPos.iterate(origin, origin.add(1, 1, 1))) place(world, pos.toImmutable(), "cvd_furnace", Direction.NORTH);
		world.setBlockState(origin.west(), RcBlocks.get("creative_power").getDefaultState());
		machine(world, origin.west()).setCreativeValue(CreativeSettings.OUTPUT_KW, 5_000);
		MachineBlockEntity furnace = machine(world, origin);
		furnace.setStack(0, new ItemStack(RcItems.ITEMS.get("coke"), 32));
		furnace.setStack(1, new ItemStack(RcItems.ITEMS.get("hydrogen_canister"), 8));
		for (int step = 0; step < 4; step++) SimTicker.stepNow(world);
		int lockedStatus = furnace.processStatus();
		lab.complete(world, dev.rackcraft.compute.Research.get("advanced_materials"));
		for (int step = 0; step < 130; step++) SimTicker.stepNow(world);
		var furnaceMembers = dev.rackcraft.world.ReactorArrays.arrayOf(world, furnace).members();
		int graphene = dev.rackcraft.world.ReactorArrays.count(furnaceMembers, dev.rackcraft.world.NuclearProcessing.OUTPUT_SLOT,
				RcItems.ITEMS.get("graphene_sheet"));
		check("AH6.a", lockedStatus == dev.rackcraft.world.NuclearProcessing.Status.LOCKED.ordinal() && graphene >= 16,
				"lockedStatus=" + lockedStatus + " graphene=" + graphene + " status=" + furnace.processStatus(), failures);
		clearArea(world, origin, 40, 8, 24);

		// Dicing a wafer: before Chiplets and Advanced Packaging the welder won't touch it and it rides through whole;
		// after, eight chiplets of one bin come off the end together.
		BlockPos line = origin.add(0, 0, 8);
		List<dev.rackcraft.block.BeltBlockEntity> belts = new java.util.ArrayList<>();
		for (int index = 0; index < 6; index++) {
			world.setBlockState(line.east(index), RcBlocks.get("conveyor_belt").getDefaultState()
					.with(dev.rackcraft.block.ConveyorBeltBlock.FACING, Direction.EAST));
			belts.add((dev.rackcraft.block.BeltBlockEntity) world.getBlockEntity(line.east(index)));
		}
		world.setBlockState(line.east(6), Blocks.CHEST.getDefaultState());
		String[] robots = {"welding_arm", "assembly_arm", "riveting_arm"};
		List<MachineBlockEntity> arms = new java.util.ArrayList<>();
		for (int index = 0; index < robots.length; index++) {
			BlockPos at = line.east(1 + index).north();
			arms.add(place(world, at, robots[index], Direction.SOUTH));
			world.setBlockState(at.north(), RcBlocks.get("creative_power").getDefaultState());
			machine(world, at.north()).setCreativeValue(CreativeSettings.OUTPUT_KW, 5_000);
		}
		arms.get(1).setStack(0, new ItemStack(RcItems.ITEMS.get("graphene_sheet"), 2));
		for (int step = 0; step < 2; step++) SimTicker.stepNow(world);
		belts.get(0).accept(new ItemStack(RcItems.ITEMS.get("wafer_scale_engine")), 0);
		int weldLocked = 0;
		for (int tick = 0; tick < 400; tick++) {
			for (var belt : belts) belt.serverTick(world);
			if (tick % 10 == 9) {
				SimTicker.stepNow(world);
				if (arms.get(0).processStatus() == dev.rackcraft.world.AssemblyLine.Status.LOCKED.ordinal()) weldLocked++;
			}
		}
		java.util.function.Function<net.minecraft.item.Item, Integer> inChest = item -> {
			int count = 0;
			if (world.getBlockEntity(line.east(6)) instanceof net.minecraft.block.entity.ChestBlockEntity chest) {
				for (int slot = 0; slot < chest.size(); slot++) if (chest.getStack(slot).isOf(item)) count += chest.getStack(slot).getCount();
			}
			return count;
		};
		int wafersThrough = inChest.apply(RcItems.ITEMS.get("wafer_scale_engine"));
		lab.complete(world, dev.rackcraft.compute.Research.get("advanced_packaging"));
		belts.get(0).accept(new ItemStack(RcItems.ITEMS.get("wafer_scale_engine")), 0);
		for (int tick = 0; tick < 2000; tick++) {
			for (var belt : belts) belt.serverTick(world);
			if (tick % 10 == 9) SimTicker.stepNow(world);
		}
		int bronze = inChest.apply(RcItems.ITEMS.get("chiplet_bronze"));
		int silver = inChest.apply(RcItems.ITEMS.get("chiplet_silver"));
		int gold = inChest.apply(RcItems.ITEMS.get("chiplet_gold"));
		check("AH7.a", wafersThrough == 1 && weldLocked > 0 && bronze + silver + gold == 8
						&& (bronze == 8 || silver == 8 || gold == 8) && arms.get(1).getStack(0).isEmpty(),
				"lockedWaferThrough=" + wafersThrough + " weldLocked=" + weldLocked + " bins=" + bronze + "/" + silver + "/" + gold
						+ " belts=" + belts.stream().map(belt -> belt.stack().getCount() + " " + belt.stack().getName().getString()).toList(), failures);
		clearArea(world, origin, 40, 8, 24);

		// None of it is sold, except the cubes themselves (which wait for research anyway).
		List<String> forSale = java.util.stream.Stream.of("graphene_sheet", "gallium_nitride", "superconducting_wire", "chiplet_bronze",
						"chiplet_silver", "chiplet_gold", "hbm_stack", "photonic_chip", "fpga_module", "npu_card", "neuromorphic_core",
						"photonic_tensor_core", "quantum_annealer")
				.filter(id -> ExchangeCatalog.price(RcItems.ITEMS.get(id)) != null).toList();
		List<String> racksForSale = java.util.stream.Stream.of("high_density_rack", "immersion_rack", "exascale_cabinet", "cryostat")
				.filter(id -> ExchangeCatalog.price(item(id)) != null).toList();
		check("AH8.a", forSale.isEmpty() && racksForSale.isEmpty() && ExchangeCatalog.price(item("cvd_furnace")) != null,
				"forSale=" + forSale + " racksForSale=" + racksForSale + " cvd=" + ExchangeCatalog.price(item("cvd_furnace")), failures);
		lab.reset();
	}

	/**
	 * Big facilities stay cheap to simulate: 400 racks on one fiber network step in a few milliseconds (each rack once
	 * re-added up its whole network, so a 1,000-rack hall took half a second a step). And a Site Planner's cable goes
	 * round a wall of machinery in its way rather than giving up.
	 */
	/**
	 * Batch A, building and wiring: Trunk Bundles, Patch Panels, Pylons, Power Beacons, the Cable Planner, the
	 * Constructor's Gauntlet and the Terraformer Cannon.
	 */
	private static void checkBuildingTools(ServerWorld world, int[] failures) {
		dev.rackcraft.compute.ResearchLab lab = dev.rackcraft.compute.ResearchLab.get(world);
		lab.reset();
		dev.rackcraft.world.PatchPanels.get(world).reset();
		dev.rackcraft.world.PylonLinks.get(world).reset();
		dev.rackcraft.world.WirelessPower.reset(world);
		dev.rackcraft.world.NetworkManager networks = dev.rackcraft.world.NetworkManager.get(world);
		var player = net.fabricmc.fabric.api.entity.FakePlayer.get(world);
		player.getInventory().clear();
		player.setPosition(-2290, 150, -1530);
		BlockPos o = clearArea(world, new BlockPos(-2300, 150, -1536), 60, 12, 80);
		CableBlock powerCable = (CableBlock) RcBlocks.get("power_cable");
		CableBlock coolantPipe = (CableBlock) RcBlocks.get("coolant_pipe");
		CableBlock fiberCable = (CableBlock) RcBlocks.get("fiber_cable");
		net.minecraft.util.math.Vec3d centre;

		// ---- A1: a Trunk Bundle carries three networks in one block, and is inert until Structured Cabling.
		BlockPos trunkA = o.east(2);
		BlockPos trunkB = o.east(3);
		BlockPos cable = o.east(4);
		BlockPos pipe = trunkB.south();
		BlockPos fiber = trunkB.north();
		world.setBlockState(o, RcBlocks.get("creative_power").getDefaultState());
		world.setBlockState(o.east(), RcBlocks.get("fiber_cable").getDefaultState());
		world.setBlockState(trunkA, RcBlocks.get("trunk_bundle").getDefaultState());
		world.setBlockState(trunkB, RcBlocks.get("trunk_bundle").getDefaultState());
		world.setBlockState(cable, powerCable.withConnections(powerCable.getDefaultState(), world, cable));
		world.setBlockState(pipe, coolantPipe.withConnections(coolantPipe.getDefaultState(), world, pipe));
		world.setBlockState(fiber, fiberCable.withConnections(fiberCable.getDefaultState(), world, fiber));
		world.setBlockState(o.east(), powerCable.withConnections(powerCable.getDefaultState(), world, o.east()));
		networks.markDirty();
		boolean inert = !networks.component(cable, dev.rackcraft.sim.NetKind.POWER).contains(o);
		lab.complete(world, dev.rackcraft.compute.Research.get("structured_cabling"));
		boolean power = networks.component(cable, dev.rackcraft.sim.NetKind.POWER).contains(o);
		boolean coolant = networks.component(pipe, dev.rackcraft.sim.NetKind.COOLANT).contains(trunkA);
		boolean data = networks.component(fiber, dev.rackcraft.sim.NetKind.DATA).contains(trunkA);
		// The lanes stay apart: a power cable doesn't join the fiber lane just because they share a trunk.
		boolean lanesApart = !networks.component(cable, dev.rackcraft.sim.NetKind.DATA).contains(fiber);
		check("BT1.a", inert && power && coolant && data && lanesApart,
				"inertBeforeResearch=" + inert + " power=" + power + " coolant=" + coolant + " fiber=" + data + " lanesApart=" + lanesApart, failures);
		CableBlock.setCut(world, trunkB, true);
		boolean allCut = !networks.component(cable, dev.rackcraft.sim.NetKind.POWER).contains(o)
				&& !networks.component(pipe, dev.rackcraft.sim.NetKind.COOLANT).contains(trunkA)
				&& !networks.component(fiber, dev.rackcraft.sim.NetKind.DATA).contains(trunkA);
		player.setStackInHand(net.minecraft.util.Hand.MAIN_HAND, new ItemStack(RcItems.ITEMS.get("repair_kit")));
		centre = net.minecraft.util.math.Vec3d.ofCenter(trunkB);
		world.getBlockState(trunkB).onUse(world, player, net.minecraft.util.Hand.MAIN_HAND,
				new net.minecraft.util.hit.BlockHitResult(centre, Direction.UP, trunkB, false));
		boolean mended = !world.getBlockState(trunkB).get(CableBlock.CUT)
				&& networks.component(cable, dev.rackcraft.sim.NetKind.POWER).contains(o)
				&& networks.component(pipe, dev.rackcraft.sim.NetKind.COOLANT).contains(trunkA);
		check("BT1.b", allCut && mended, "oneCutCutsAllThree=" + allCut + " oneSpliceMendsAll=" + mended, failures);
		check("BT1.c", world.getBlockState(trunkA).get(ConnectingBlock.FACING_PROPERTIES.get(Direction.EAST))
						&& world.getBlockState(cable).get(ConnectingBlock.FACING_PROPERTIES.get(Direction.WEST))
						&& world.getBlockState(pipe).get(ConnectingBlock.FACING_PROPERTIES.get(Direction.NORTH)),
				"trunk=" + world.getBlockState(trunkA), failures);

		// ---- A3: a Patch Panel joins the faces on one port, and nothing else.
		BlockPos panelRow = o.south(6);
		BlockPos panel = panelRow.east(2);
		world.setBlockState(panelRow, RcBlocks.get("creative_power").getDefaultState());
		world.setBlockState(panelRow.east(), powerCable.withConnections(powerCable.getDefaultState(), world, panelRow.east()));
		world.setBlockState(panel, RcBlocks.get("patch_panel").getDefaultState());
		world.setBlockState(panelRow.east(3), powerCable.withConnections(powerCable.getDefaultState(), world, panelRow.east(3)));
		world.setBlockState(panelRow.east(3).south(), fiberCable.withConnections(fiberCable.getDefaultState(), world, panelRow.east(3).south()));
		world.setBlockState(panelRow.east(3).south(), powerCable.withConnections(powerCable.getDefaultState(), world, panelRow.east(3).south()));
		networks.markDirty();
		player.setStackInHand(net.minecraft.util.Hand.MAIN_HAND, ItemStack.EMPTY);
		centre = net.minecraft.util.math.Vec3d.ofCenter(panel);
		lab.reset();
		world.getBlockState(panel).onUse(world, player, net.minecraft.util.Hand.MAIN_HAND,
				new net.minecraft.util.hit.BlockHitResult(centre, Direction.EAST, panel, false));
		boolean panelInert = dev.rackcraft.world.PatchPanels.get(world).port(panel, Direction.EAST) == 0;
		lab.complete(world, dev.rackcraft.compute.Research.get("structured_cabling"));
		boolean apart = !networks.component(panelRow.east(3), dev.rackcraft.sim.NetKind.POWER).contains(panelRow);
		world.getBlockState(panel).onUse(world, player, net.minecraft.util.Hand.MAIN_HAND,
				new net.minecraft.util.hit.BlockHitResult(centre, Direction.EAST, panel, false));
		world.getBlockState(panel).onUse(world, player, net.minecraft.util.Hand.MAIN_HAND,
				new net.minecraft.util.hit.BlockHitResult(centre, Direction.WEST, panel, false));
		boolean joined = networks.component(panelRow.east(3), dev.rackcraft.sim.NetKind.POWER).contains(panelRow);
		// A third face on a different port, and a face moved off the shared port, break the join again.
		dev.rackcraft.world.PatchPanels panels = dev.rackcraft.world.PatchPanels.get(world);
		panels.cycle(panel, Direction.EAST);
		networks.markDirty();
		boolean split = !networks.component(panelRow.east(3), dev.rackcraft.sim.NetKind.POWER).contains(panelRow);
		// Faces cycle round to 0 after the last port, and the panel forgets itself when broken.
		for (int turn = 0; turn <= dev.rackcraft.world.PatchPanels.MAX_PORT; turn++) panels.cycle(panel, Direction.UP);
		boolean wraps = panels.port(panel, Direction.UP) == 0;
		world.setBlockState(panel, Blocks.AIR.getDefaultState());
		boolean forgotten = panels.joins().stream().noneMatch(pair -> pair[0].equals(panel.west()));
		check("BT2.a", panelInert && apart && joined && split && wraps && forgotten,
				"inertBeforeResearch=" + panelInert + " isolatedByDefault=" + apart + " joinedOnSharedPort=" + joined
						+ " splitWhenMoved=" + split + " wraps=" + wraps + " forgottenWhenBroken=" + forgotten, failures);

		// ---- A4: Pylons carry power across a gap, waste a share by distance, and cut cleanly.
		BlockPos span = o.south(12);
		BlockPos far = span.east(100);
		world.getChunk(far);
		clearArea(world, far, 6, 4, 4);
		world.setBlockState(span, RcBlocks.get("creative_power").getDefaultState());
		world.setBlockState(span.east(), RcBlocks.get("pylon").getDefaultState());
		world.setBlockState(far, RcBlocks.get("pylon").getDefaultState());
		world.setBlockState(far.east(), RcBlocks.get("creative_rack").getDefaultState());
		MachineBlockEntity pylonSource = machine(world, span);
		MachineBlockEntity pylonLoad = machine(world, far.east());
		pylonSource.setCreativeValue(CreativeSettings.OUTPUT_KW, 5000);
		pylonLoad.setCreativeValue(CreativeSettings.DRAW_KW, 1000);
		for (int step = 0; step < 4; step++) SimTicker.stepNow(world);
		boolean dark = pylonLoad.powerSatisfaction() < 0.01;
		ItemStack linker = new ItemStack(RcItems.ITEMS.get("pylon_linker"));
		String first = dev.rackcraft.item.PylonLinkerItem.link(world, linker, span.east());
		String second = dev.rackcraft.item.PylonLinkerItem.link(world, linker, far);
		for (int step = 0; step < 4; step++) SimTicker.stepNow(world);
		double expected = 1000 * (1 + dev.rackcraft.world.PylonLinks.lossFraction(100, false));
		boolean lit = pylonLoad.powerSatisfaction() > 0.99 && Math.abs(pylonLoad.networkDemandKw() - expected) < 1;
		check("BT3.a", dark && lit && second.startsWith("Span strung"),
				"darkBefore=" + dark + " lit=" + lit + " demand=" + pylonLoad.networkDemandKw() + " expected=" + expected + " msg=" + second, failures);
		// Superconducting pylons waste nothing and reach twice as far; an ordinary one can't reach 200 blocks.
		dev.rackcraft.world.PylonLinks.get(world).unlink(span.east());
		world.setBlockState(span.east(), RcBlocks.get("superconducting_pylon").getDefaultState());
		world.setBlockState(far, RcBlocks.get("superconducting_pylon").getDefaultState());
		dev.rackcraft.item.PylonLinkerItem.link(world, linker, span.east());
		String superSpan = dev.rackcraft.item.PylonLinkerItem.link(world, linker, far);
		for (int step = 0; step < 4; step++) SimTicker.stepNow(world);
		boolean lossless = Math.abs(pylonLoad.networkDemandKw() - 1000) < 0.5 && pylonLoad.powerSatisfaction() > 0.99;
		BlockPos tooFar = span.east(200);
		world.getChunk(tooFar);
		clearArea(world, tooFar, 3, 3, 3);
		world.setBlockState(tooFar, RcBlocks.get("pylon").getDefaultState());
		world.setBlockState(span.east(2), RcBlocks.get("pylon").getDefaultState());
		dev.rackcraft.item.PylonLinkerItem.link(world, linker, span.east(2));
		String refused = dev.rackcraft.item.PylonLinkerItem.link(world, linker, tooFar);
		check("BT3.b", lossless && superSpan.contains("no loss") && refused.startsWith("Too far"),
				"lossless=" + lossless + " msg=" + superSpan + " ordinary200=" + refused, failures);
		world.setBlockState(far, Blocks.AIR.getDefaultState());
		boolean unlinked = dev.rackcraft.world.PylonLinks.get(world).count(span.east()) == 0;
		for (int step = 0; step < 3; step++) SimTicker.stepNow(world);
		check("BT3.c", unlinked && pylonLoad.powerSatisfaction() < 0.01,
				"spansLeft=" + dev.rackcraft.world.PylonLinks.get(world).count(span.east()) + " satisfaction=" + pylonLoad.powerSatisfaction(), failures);
		dev.rackcraft.world.PylonLinks.get(world).reset();

		// ---- A5: a Power Beacon feeds a Beacon Receiver in range, and not one out of range.
		lab.reset();
		BlockPos beam = o.south(20);
		world.setBlockState(beam, RcBlocks.get("creative_power").getDefaultState());
		world.setBlockState(beam.east(), RcBlocks.get("power_beacon").getDefaultState());
		BlockPos near = beam.east(9);
		BlockPos away = beam.east(30);
		clearArea(world, away, 4, 3, 3);
		world.setBlockState(near, RcBlocks.get("beacon_receiver").getDefaultState());
		world.setBlockState(near.east(), RcBlocks.get("creative_rack").getDefaultState());
		world.setBlockState(away, RcBlocks.get("beacon_receiver").getDefaultState());
		world.setBlockState(away.east(), RcBlocks.get("creative_rack").getDefaultState());
		machine(world, beam).setCreativeValue(CreativeSettings.OUTPUT_KW, 5000);
		MachineBlockEntity nearLoad = machine(world, near.east());
		MachineBlockEntity awayLoad = machine(world, away.east());
		nearLoad.setCreativeValue(CreativeSettings.DRAW_KW, 500);
		awayLoad.setCreativeValue(CreativeSettings.DRAW_KW, 500);
		for (int step = 0; step < 6; step++) SimTicker.stepNow(world);
		boolean locked = nearLoad.powerSatisfaction() < 0.01;
		lab.complete(world, dev.rackcraft.compute.Research.get("wireless_power"));
		for (int step = 0; step < 8; step++) SimTicker.stepNow(world);
		MachineBlockEntity beacon = machine(world, beam.east());
		boolean fed = nearLoad.powerSatisfaction() > 0.99;
		boolean outOfRange = awayLoad.powerSatisfaction() < 0.01;
		double asked = beacon.networkDemandKw();
		check("BT4.a", locked && fed && outOfRange,
				"inertBeforeResearch=" + locked + " inRangeFed=" + fed + " outOfRangeDark=" + outOfRange + " beaconNetworkDemand=" + asked, failures);
		// What arrives is 85% of what leaves: the source's network sees the load plus the loss.
		double wanted = 500 / (1 - RackcraftConfig.values.building.beaconLoss);
		check("BT4.b", Math.abs(machine(world, beam).powerKw() - wanted) < 2,
				"sourceOutput=" + machine(world, beam).powerKw() + " expected=" + wanted, failures);
		lab.reset();
		clearArea(world, beam, 40, 4, 4);

		// ---- A2: the Cable Planner routes round a wall, ghosts it, and lays only what it can afford.
		BlockPos lane = o.south(28);
		BlockPos from = lane;
		BlockPos to = lane.east(10);
		for (int y = 0; y < 3; y++) for (int z = -2; z <= 2; z++) world.setBlockState(lane.east(5).add(0, y, z), Blocks.STONE.getDefaultState());
		var straight = dev.rackcraft.world.CablePlanner.route(world, from, to, 0, powerCable);
		var shortest = dev.rackcraft.world.CablePlanner.route(world, from, to, 2, powerCable);
		var hugging = dev.rackcraft.world.CablePlanner.route(world, from, to, 3, powerCable);
		boolean straightBlocked = straight.stream().anyMatch(cell -> cell.flag() == dev.rackcraft.world.CablePlanner.BLOCKED);
		boolean clear = !shortest.isEmpty() && shortest.stream().noneMatch(cell -> cell.flag() == dev.rackcraft.world.CablePlanner.BLOCKED)
				&& shortest.get(0).pos().equals(from) && shortest.get(shortest.size() - 1).pos().equals(to);
		boolean contiguous = true;
		for (int index = 1; index < shortest.size(); index++) {
			if (shortest.get(index).pos().getManhattanDistance(shortest.get(index - 1).pos()) != 1) contiguous = false;
		}
		boolean huggingClear = !hugging.isEmpty() && hugging.stream().noneMatch(cell -> cell.flag() == dev.rackcraft.world.CablePlanner.BLOCKED);
		check("BT5.a", straightBlocked && clear && contiguous && huggingClear && shortest.size() > straight.size(),
				"straightBlocked=" + straightBlocked + " shortestClear=" + clear + " contiguous=" + contiguous + " hugClear=" + huggingClear
						+ " lengths=" + straight.size() + "/" + shortest.size() + "/" + hugging.size(), failures);
		// Laying: three uses on faces, taking cables from the inventory and stopping when they run out.
		ItemStack planner = new ItemStack(RcItems.ITEMS.get("cable_planner"));
		planner.getOrCreateNbt().putInt("Style", 2);
		player.getInventory().clear();
		player.getInventory().setStack(5, new ItemStack(powerCable.asItem(), shortest.size() - 3));
		player.setStackInHand(net.minecraft.util.Hand.MAIN_HAND, planner);
		BlockPos floorA = from.down();
		BlockPos floorB = to.down();
		world.setBlockState(floorA, Blocks.STONE.getDefaultState());
		world.setBlockState(floorB, Blocks.STONE.getDefaultState());
		for (BlockPos floor : List.of(floorA, floorB, floorA)) {
			var hit = new net.minecraft.util.hit.BlockHitResult(net.minecraft.util.math.Vec3d.ofCenter(floor), Direction.UP, floor, false);
			planner.getItem().useOnBlock(new net.minecraft.item.ItemUsageContext(world, player, net.minecraft.util.Hand.MAIN_HAND, planner, hit));
		}
		// The route is worked out again as it is laid, and the floor stones changed which way round the wall is shortest.
		long laidBlocks = BlockPos.stream(lane.add(-1, -3, -4), lane.add(11, 5, 4))
				.filter(pos -> world.getBlockState(pos).getBlock() instanceof CableBlock).count();
		check("BT5.b", laidBlocks == shortest.size() - 3 && player.getInventory().count(powerCable.asItem()) == 0
						&& dev.rackcraft.item.CablePlannerItem.start(planner) == null,
				"laid=" + laidBlocks + " of " + shortest.size() + " carried=" + (shortest.size() - 3), failures);
		check("BT5.c", dev.rackcraft.world.CablePlanner.route(world, from, from.east(600), 0, powerCable).isEmpty(),
				"tooLongRefused", failures);

		// ---- A6: the Constructor's Gauntlet builds and removes in bulk, and leaves machines alone.
		lab.reset();
		BlockPos site = o.south(36);
		clearArea(world, site, 12, 6, 12);
		player.getInventory().clear();
		player.getInventory().setStack(5, new ItemStack(Blocks.STONE.asItem(), 64));
		player.getInventory().setStack(6, new ItemStack(RcItems.ITEMS.get("battery_cell"), 2));
		ItemStack gauntlet = new ItemStack(RcItems.ITEMS.get("constructor_gauntlet"));
		gauntlet.getOrCreateNbt().putInt("Mode", 2);
		player.setStackInHand(net.minecraft.util.Hand.MAIN_HAND, gauntlet);
		player.setStackInHand(net.minecraft.util.Hand.OFF_HAND, new ItemStack(Blocks.STONE.asItem()));
		player.setPosition(site.getX(), site.getY(), site.getZ());
		for (BlockPos mark : List.of(site, site.add(2, 2, 2))) {
			// Aim at the face of the block under the cell, so the click lands on the open cell itself.
			var hit = new net.minecraft.util.hit.BlockHitResult(net.minecraft.util.math.Vec3d.ofCenter(mark), Direction.UP, mark.down(), false);
			gauntlet.getItem().useOnBlock(new net.minecraft.item.ItemUsageContext(world, player, net.minecraft.util.Hand.MAIN_HAND, gauntlet, hit));
		}
		long stones = BlockPos.stream(site, site.add(2, 2, 2)).filter(pos -> world.getBlockState(pos).isOf(Blocks.STONE)).count();
		// The off-hand block counts as stock too: 64 in the pack and one in the hand.
		check("BT6.a", stones == 27 && player.getInventory().count(Blocks.STONE.asItem()) == 65 - 27
						&& player.getInventory().count(RcItems.ITEMS.get("battery_cell")) == 1,
				"stoneBlocks=" + stones + " stoneLeft=" + player.getInventory().count(Blocks.STONE.asItem())
						+ " cellsLeft=" + player.getInventory().count(RcItems.ITEMS.get("battery_cell")), failures);
		// Swap stone for glass, then delete the lot: a machine in the box is skipped, the rest comes home.
		BlockPos machineAt = site.add(1, 1, 1);
		world.setBlockState(machineAt, RcBlocks.get("server_rack").getDefaultState());
		player.getInventory().setStack(7, new ItemStack(Blocks.GLASS.asItem(), 64));
		var swap = dev.rackcraft.world.Earthworks.gauntlet(world, player, dev.rackcraft.world.BuildStock.of(player),
				dev.rackcraft.world.Earthworks.Mode.SWAP, site.add(2, 0, 0), site.add(2, 2, 2), Blocks.GLASS);
		long glass = BlockPos.stream(site, site.add(2, 2, 2)).filter(pos -> world.getBlockState(pos).isOf(Blocks.GLASS)).count();
		var delete = dev.rackcraft.world.Earthworks.gauntlet(world, player, dev.rackcraft.world.BuildStock.of(player),
				dev.rackcraft.world.Earthworks.Mode.DELETE, site, site.add(2, 2, 2), null);
		boolean rackSurvived = world.getBlockState(machineAt).isOf(RcBlocks.get("server_rack"));
		long left = BlockPos.stream(site, site.add(2, 2, 2)).filter(pos -> !world.getBlockState(pos).isAir()).count();
		check("BT6.b", swap.changed() == 9 && glass == 9 && delete.changed() > 0 && rackSurvived && left == 1,
				"swapped=" + swap.changed() + " glass=" + glass + " deleted=" + delete.changed() + " rackSurvived=" + rackSurvived + " left=" + left, failures);
		var huge = dev.rackcraft.world.Earthworks.gauntlet(world, player, dev.rackcraft.world.BuildStock.of(player),
				dev.rackcraft.world.Earthworks.Mode.BOX, site, site.add(60, 60, 60), Blocks.STONE);
		boolean shortReach = dev.rackcraft.item.ConstructorGauntletItem.reach(world) == RackcraftConfig.values.building.gauntletReach;
		lab.complete(world, dev.rackcraft.compute.Research.get("long_reach"));
		boolean longReach = dev.rackcraft.item.ConstructorGauntletItem.reach(world) == RackcraftConfig.values.building.gauntletLongReach;
		lab.reset();
		check("BT6.d", shortReach && longReach, "reach32=" + shortReach + " longReach128=" + longReach, failures);
		check("BT6.c", huge.changed() == 0 && huge.note().startsWith("Too big"), "note=" + huge.note(), failures);

		// ---- A7: the Terraformer Cannon flattens a hill, fills a pit, and spends hydrogen for what it moves.
		BlockPos ground = o.south(54);
		clearArea(world, ground.add(-12, 0, -12), 24, 8, 24);
		for (BlockPos pos : BlockPos.iterate(ground.add(-12, -1, -12), ground.add(12, -1, 12))) world.setBlockState(pos, Blocks.DIRT.getDefaultState());
		for (BlockPos pos : BlockPos.iterate(ground.add(-2, 0, -2), ground.add(2, 2, 2))) world.setBlockState(pos, Blocks.STONE.getDefaultState());
		for (BlockPos pos : BlockPos.iterate(ground.add(4, -1, 0), ground.add(5, -1, 1))) world.setBlockState(pos, Blocks.AIR.getDefaultState());
		BlockPos protectedColumn = ground.add(-1, 0, 3);
		world.setBlockState(protectedColumn, RcBlocks.get("server_rack").getDefaultState());
		BlockPos target = ground.add(2, -1, -4);
		var flatten = dev.rackcraft.world.Earthworks.terraform(world, dev.rackcraft.world.Earthworks.Terrain.FLATTEN, target, 8);
		boolean planned = flatten != null && flatten.moved() >= 45 && dev.rackcraft.world.Earthworks.canisters(flatten) >= 1;
		player.getInventory().clear();
		ItemStack cannon = new ItemStack(RcItems.ITEMS.get("terraformer_cannon"));
		cannon.getOrCreateNbt().putInt("Radius", 8);
		player.setStackInHand(net.minecraft.util.Hand.MAIN_HAND, cannon);
		player.setStackInHand(net.minecraft.util.Hand.OFF_HAND, ItemStack.EMPTY);
		var dry = new net.minecraft.util.hit.BlockHitResult(net.minecraft.util.math.Vec3d.ofCenter(target), Direction.UP, target, false);
		cannon.getItem().useOnBlock(new net.minecraft.item.ItemUsageContext(world, player, net.minecraft.util.Hand.MAIN_HAND, cannon, dry));
		boolean refusedDry = world.getBlockState(ground.add(0, 2, 0)).isOf(Blocks.STONE);
		player.getInventory().setStack(5, new ItemStack(RcItems.ITEMS.get("hydrogen_canister"), 4));
		cannon.getItem().useOnBlock(new net.minecraft.item.ItemUsageContext(world, player, net.minecraft.util.Hand.MAIN_HAND, cannon, dry));
		boolean levelled = BlockPos.stream(ground.add(-2, 0, -2), ground.add(2, 2, 2)).noneMatch(pos -> world.getBlockState(pos).isOf(Blocks.STONE));
		int spent = 4 - player.getInventory().count(RcItems.ITEMS.get("hydrogen_canister"));
		boolean rackKept = world.getBlockState(protectedColumn).isOf(RcBlocks.get("server_rack"));
		check("BT7.a", planned && refusedDry && levelled && spent >= 1 && rackKept,
				"planned=" + planned + " refusedWithoutHydrogen=" + refusedDry + " levelled=" + levelled + " canistersSpent=" + spent
						+ " rackKept=" + rackKept, failures);
		var fill = dev.rackcraft.world.Earthworks.terraform(world, dev.rackcraft.world.Earthworks.Terrain.FILL, target, 8);
		for (BlockPos pos : BlockPos.iterate(ground.add(-1, 3, -9), ground.add(1, 5, -7))) world.setBlockState(pos, Blocks.STONE.getDefaultState());
		var hollow = dev.rackcraft.world.Earthworks.terraform(world, dev.rackcraft.world.Earthworks.Terrain.HOLLOW, ground.add(0, 4, -8), 3);
		var smooth = dev.rackcraft.world.Earthworks.terraform(world, dev.rackcraft.world.Earthworks.Terrain.SMOOTH, target, 8);
		if (hollow != null) dev.rackcraft.world.Earthworks.apply(world, hollow);
		boolean hollowed = hollow != null && hollow.moved() == 27 && BlockPos.stream(ground.add(-1, 3, -9), ground.add(1, 5, -7)).allMatch(pos -> world.getBlockState(pos).isAir());
		check("BT7.b", fill != null && hollowed && smooth != null,
				"fill=" + (fill == null ? "null" : fill.moved()) + " hollow=" + (hollow == null ? "null" : hollow.moved())
						+ " smooth=" + (smooth == null ? "null" : smooth.moved()), failures);
		// A held Multimeter overrides a machine's screen: the block passes the click on to the item, which reads it out.
		BlockPos meterAt = o.south(60);
		world.setBlockState(meterAt, RcBlocks.get("diesel_generator").getDefaultState());
		player.setStackInHand(net.minecraft.util.Hand.MAIN_HAND, new ItemStack(RcItems.ITEMS.get("multimeter")));
		var meterHit = new net.minecraft.util.hit.BlockHitResult(net.minecraft.util.math.Vec3d.ofCenter(meterAt), Direction.UP, meterAt, false);
		var blockResult = world.getBlockState(meterAt).onUse(world, player, net.minecraft.util.Hand.MAIN_HAND, meterHit);
		player.setStackInHand(net.minecraft.util.Hand.MAIN_HAND, ItemStack.EMPTY);
		var emptyResult = world.getBlockState(meterAt).onUse(world, player, net.minecraft.util.Hand.MAIN_HAND, meterHit);
		ItemStack meter = new ItemStack(RcItems.ITEMS.get("multimeter"));
		var itemResult = meter.getItem().useOnBlock(new net.minecraft.item.ItemUsageContext(world, player, net.minecraft.util.Hand.MAIN_HAND, meter, meterHit));
		player.closeHandledScreen();
		check("BT9.a", blockResult == net.minecraft.util.ActionResult.PASS && emptyResult != net.minecraft.util.ActionResult.PASS
						&& itemResult.isAccepted(),
				"multimeterHeld=" + blockResult + " emptyHand=" + emptyResult + " itemReadout=" + itemResult, failures);
		world.setBlockState(meterAt, Blocks.AIR.getDefaultState());
		// The hardware-built tools are made, not bought; the cheap conveniences are priced like any other parts.
		List<String> notForSale = List.of("power_beacon", "beacon_receiver", "constructor_gauntlet", "terraformer_cannon", "superconducting_pylon");
		List<String> sold = notForSale.stream().filter(id -> ExchangeCatalog.price(Registries.ITEM.get(Rackcraft.id(id))) != null).toList();
		check("BT8.a", sold.isEmpty() && ExchangeCatalog.price(RcBlocks.get("trunk_bundle").asItem()) != null,
				"soldButShouldNotBe=" + sold, failures);
		// Every new thing has a recipe, and the three projects that unlock them are in the R&D chain.
		List<String> unmade = List.of("trunk_bundle", "patch_panel", "pylon", "superconducting_pylon", "pylon_linker", "power_beacon",
				"beacon_receiver", "cable_planner", "battery_cell", "constructor_gauntlet", "terraformer_cannon").stream()
				.filter(id -> world.getServer().getRecipeManager().get(Rackcraft.id(id)).isEmpty()).toList();
		boolean projects = dev.rackcraft.compute.Research.get("structured_cabling") != null
				&& dev.rackcraft.compute.Research.get("long_reach").requires().contains("structured_cabling")
				&& dev.rackcraft.compute.Research.get("wireless_power").requires().contains("silicon_photonics");
		check("BT8.b", unmade.isEmpty() && projects, "missingRecipes=" + unmade + " projects=" + projects, failures);
		player.getInventory().clear();
		player.setStackInHand(net.minecraft.util.Hand.MAIN_HAND, ItemStack.EMPTY);
		player.setStackInHand(net.minecraft.util.Hand.OFF_HAND, ItemStack.EMPTY);
		lab.reset();
		dev.rackcraft.world.PatchPanels.get(world).reset();
		dev.rackcraft.world.PylonLinks.get(world).reset();
		dev.rackcraft.world.WirelessPower.reset(world);
	}

	/** Batch B: overclocking. Past 100% a rack does more work for the square of the power, and modules can burn out. */
	private static void checkOverclocking(ServerWorld world, int[] failures) {
		dev.rackcraft.compute.ResearchLab lab = dev.rackcraft.compute.ResearchLab.get(world);
		lab.reset();
		// The model: 125% is a quarter more work for 56% more power; 150% half as much again for 125% more.
		var gpus = List.of(dev.rackcraft.sim.ServerModel.Module.GPU_BLADE, dev.rackcraft.sim.ServerModel.Module.GPU_BLADE);
		var rated = dev.rackcraft.sim.ServerModel.calculate(dev.rackcraft.sim.ServerModel.Tier.HIGH_DENSITY, gpus, 100, 1, 20, true, true, true, 1);
		var pushed = dev.rackcraft.sim.ServerModel.calculate(dev.rackcraft.sim.ServerModel.Tier.HIGH_DENSITY, gpus, 125, 1, 20, true, true, true, 1);
		var over = dev.rackcraft.sim.ServerModel.calculate(dev.rackcraft.sim.ServerModel.Tier.HIGH_DENSITY, gpus, 150, 1, 20, true, true, true, 1);
		double idle = 2 * dev.rackcraft.sim.ServerModel.Module.GPU_BLADE.idleKw();
		double span = 2 * (dev.rackcraft.sim.ServerModel.Module.GPU_BLADE.maxKw() - dev.rackcraft.sim.ServerModel.Module.GPU_BLADE.idleKw());
		check("OC1.a", Math.abs(pushed.creditsPerSecond() / rated.creditsPerSecond() - 1.25) < 1e-9
						&& Math.abs(over.creditsPerSecond() / rated.creditsPerSecond() - 1.5) < 1e-9
						&& Math.abs(pushed.demandKw() - (idle + span * 1.5625 + dev.rackcraft.sim.ServerModel.Tier.HIGH_DENSITY.overheadKw())) < 1e-6
						&& Math.abs(over.demandKw() - (idle + span * 2.25 + dev.rackcraft.sim.ServerModel.Tier.HIGH_DENSITY.overheadKw())) < 1e-6
						&& dev.rackcraft.sim.ServerModel.liquidHeatKw(gpus, 1.25) > dev.rackcraft.sim.ServerModel.liquidHeatKw(gpus, 1.0) * 1.35,
				"work=" + pushed.creditsPerSecond() / rated.creditsPerSecond() + " power=" + pushed.demandKw() + "/" + rated.demandKw() + " at150=" + over.demandKw(), failures);
		check("OC1.b", dev.rackcraft.world.Overclocking.perMinute(100, false, false) == 0
						&& dev.rackcraft.world.Overclocking.perMinute(125, false, false) == 0.01
						&& dev.rackcraft.world.Overclocking.perMinute(150, false, false) == 0.03
						&& dev.rackcraft.world.Overclocking.perMinute(150, true, false) == 0.015
						&& dev.rackcraft.world.Overclocking.perMinute(150, true, true) == 0.0075,
				"burn chances", failures);

		// In the world: the limit is held to the research, and a rack at 125% works a quarter harder.
		BlockPos o = clearArea(world, new BlockPos(-3000, 120, -1536), 12, 6, 8);
		world.setBlockState(o, RcBlocks.get("creative_power").getDefaultState());
		MachineBlockEntity rack = place(world, o.east(), "server_rack", Direction.NORTH);
		for (int slot = 0; slot < 8; slot++) rack.setStack(slot, new ItemStack(RcItems.ITEMS.get("server_1u")));
		rack.setLoadLimitPercent(125);
		for (int step = 0; step < 6; step++) SimTicker.stepNow(world);
		double lockedLoad = rack.load();
		double lockedKw = rack.powerKw();
		lab.complete(world, dev.rackcraft.compute.Research.get("overclocking"));
		for (int step = 0; step < 6; step++) SimTicker.stepNow(world);
		double pushedLoad = rack.load();
		double pushedKw = rack.powerKw();
		rack.setLoadLimitPercent(150);
		for (int step = 0; step < 6; step++) SimTicker.stepNow(world);
		double cappedLoad = rack.load();
		check("OC2.a", Math.abs(lockedLoad - 1) < 0.01 && Math.abs(pushedLoad - 1.25) < 0.01 && pushedKw > lockedKw * 1.3 && Math.abs(cappedLoad - 1.25) < 0.01,
				"before=" + lockedLoad + " after=" + pushedLoad + " kw=" + lockedKw + "->" + pushedKw + " at150WithoutCooling=" + cappedLoad, failures);
		lab.complete(world, dev.rackcraft.compute.Research.get("liquid_hydrogen_cooling"));
		for (int step = 0; step < 6; step++) SimTicker.stepNow(world);
		double fullLoad = rack.load();

		// Burn-out: a tier 1 or 2 module can go, a tier 3 never does, and below 100% nothing does.
		rack.setStack(0, new ItemStack(RcItems.ITEMS.get("server_1u")));
		rack.setStack(1, new ItemStack(RcItems.ITEMS.get("neuromorphic_core")));
		dev.rackcraft.world.Overclocking.roll = () -> 0;
		rack.setLoadLimitPercent(150);
		SimTicker.stepNow(world);
		boolean burned = rack.getStack(0).isOf(RcItems.ITEMS.get("failed_module")) && rack.getStack(1).isOf(RcItems.ITEMS.get("neuromorphic_core"));
		boolean remembers = burned && rack.getStack(0).getNbt().getString(dev.rackcraft.world.DroneDocks.FAILED_KEY).equals("rackcraft:server_1u");
		rack.setStack(2, new ItemStack(RcItems.ITEMS.get("server_1u")));
		rack.setLoadLimitPercent(100);
		SimTicker.stepNow(world);
		boolean safeAtRated = rack.getStack(2).isOf(RcItems.ITEMS.get("server_1u"));
		dev.rackcraft.world.Overclocking.roll = () -> 1;
		rack.setLoadLimitPercent(150);
		SimTicker.stepNow(world);
		boolean luck = rack.getStack(2).isOf(RcItems.ITEMS.get("server_1u"));
		dev.rackcraft.world.Overclocking.roll = null;
		check("OC3.a", Math.abs(fullLoad - 1.5) < 0.01 && burned && remembers && safeAtRated && luck,
				"load150=" + fullLoad + " burned=" + burned + " remembers=" + remembers + " safeAtRated=" + safeAtRated + " luck=" + luck, failures);
		rack.setLoadLimitPercent(100);
		lab.reset();
		clearArea(world, o, 12, 6, 8);
	}

	/** Batch B: Compute Pods. A solid block of High-Density Racks and a powered Pod Port; the fabric, the shared breaker, fill and empty. */
	private static void checkComputePods(ServerWorld world, int[] failures) {
		dev.rackcraft.compute.ResearchLab lab = dev.rackcraft.compute.ResearchLab.get(world);
		lab.reset();
		BlockPos o = clearArea(world, new BlockPos(-3100, 120, -1536), 14, 8, 14);
		world.setBlockState(o, RcBlocks.get("creative_power").getDefaultState());
		List<MachineBlockEntity> racks = new java.util.ArrayList<>();
		for (int x = 0; x < 2; x++) for (int z = 0; z < 2; z++) {
			MachineBlockEntity rack = place(world, o.add(1 + x, 0, z), "high_density_rack", Direction.NORTH);
			for (int slot = 0; slot < 4; slot++) rack.setStack(slot, new ItemStack(RcItems.ITEMS.get("server_1u")));
			racks.add(rack);
		}
		MachineBlockEntity port = place(world, o.add(3, 0, 0), "pod_port", Direction.NORTH);
		for (int step = 0; step < 4; step++) SimTicker.stepNow(world);
		double base = dev.rackcraft.compute.Cluster.compute(racks.get(0), dev.rackcraft.compute.Cluster.Kind.AI);
		check("PD1.a", port.siteReading(dev.rackcraft.world.ComputePods.R_STATE) == dev.rackcraft.world.ComputePods.State.LOCKED.ordinal()
						&& racks.get(0).podBonus() == 1 && base > 0,
				"locked state=" + port.siteReading(dev.rackcraft.world.ComputePods.R_STATE) + " base=" + base, failures);
		lab.complete(world, dev.rackcraft.compute.Research.get("compute_pods"));
		for (int step = 0; step < 4; step++) SimTicker.stepNow(world);
		boolean fused = port.siteReading(dev.rackcraft.world.ComputePods.R_STATE) == dev.rackcraft.world.ComputePods.State.FABRIC_OFF.ordinal()
				&& port.siteReading(dev.rackcraft.world.ComputePods.R_RACKS) == 4 && racks.get(0).podBonus() == 1;
		port.setStack(0, new ItemStack(RcItems.ITEMS.get("photonic_chip"), 1));
		for (int step = 0; step < 4; step++) SimTicker.stepNow(world);
		double boosted = dev.rackcraft.compute.Cluster.compute(racks.get(0), dev.rackcraft.compute.Cluster.Kind.AI);
		double general = dev.rackcraft.compute.Cluster.compute(racks.get(0), dev.rackcraft.compute.Cluster.Kind.GENERAL);
		check("PD1.b", fused && port.siteReading(dev.rackcraft.world.ComputePods.R_STATE) == dev.rackcraft.world.ComputePods.State.ACTIVE.ordinal()
						&& Math.abs(boosted / base - 1.1) < 1e-6 && port.siteReading(dev.rackcraft.world.ComputePods.R_BONUS) == 10
						&& Math.abs(general - dev.rackcraft.compute.Cluster.compute(racks.get(1), dev.rackcraft.compute.Cluster.Kind.GENERAL)) < 1e-9,
				"fusedWithoutChips=" + fused + " boost=" + boosted / base + " state=" + port.siteReading(dev.rackcraft.world.ComputePods.R_STATE), failures);
		check("PD1.c", dev.rackcraft.world.ComputePods.bonusFor(3) == 1 && dev.rackcraft.world.ComputePods.bonusFor(4) == 1.1
						&& dev.rackcraft.world.ComputePods.bonusFor(8) == 1.2 && dev.rackcraft.world.ComputePods.bonusFor(16) == 1.3
						&& dev.rackcraft.world.ComputePods.interconnectsFor(4) == 1 && dev.rackcraft.world.ComputePods.interconnectsFor(5) == 2
						&& dev.rackcraft.world.ComputePods.interconnectsFor(16) == 4,
				"bonus table", failures);

		// Eight racks need two interconnects for the next bonus; one is not enough.
		for (int x = 0; x < 2; x++) for (int z = 0; z < 2; z++) {
			MachineBlockEntity rack = place(world, o.add(1 + x, 1, z), "high_density_rack", Direction.NORTH);
			for (int slot = 0; slot < 4; slot++) rack.setStack(slot, new ItemStack(RcItems.ITEMS.get("server_1u")));
			racks.add(rack);
		}
		for (int step = 0; step < 4; step++) SimTicker.stepNow(world);
		boolean starved = port.siteReading(dev.rackcraft.world.ComputePods.R_STATE) == dev.rackcraft.world.ComputePods.State.FABRIC_OFF.ordinal()
				&& port.siteReading(dev.rackcraft.world.ComputePods.R_NEED) == 2 && racks.get(5).podBonus() == 1;
		port.setStack(0, new ItemStack(RcItems.ITEMS.get("photonic_chip"), 2));
		for (int step = 0; step < 4; step++) SimTicker.stepNow(world);
		boolean eight = port.siteReading(dev.rackcraft.world.ComputePods.R_RACKS) == 8 && port.siteReading(dev.rackcraft.world.ComputePods.R_BONUS) == 20
				&& Math.abs(racks.get(5).podBonus() - 1.2) < 1e-9;
		check("PD2.a", starved && eight, "starved=" + starved + " eight=" + eight, failures);

		// One breaker: a rack that overheats takes the whole pod down, and it comes back when they have all cooled.
		world.setBlockState(o.add(2, 0, -2), RcBlocks.get("creative_cooler").getDefaultState().with(MachineBlock.FACING, Direction.SOUTH));
		MachineBlockEntity cooler = machine(world, o.add(2, 0, -2));
		cooler.setCreativeValue(CreativeSettings.TARGET_C, 60);
		for (int step = 0; step < 6; step++) SimTicker.stepNow(world);
		boolean tripped = port.isTripped() && racks.stream().allMatch(MachineBlockEntity::isTripped)
				&& racks.stream().allMatch(rack -> dev.rackcraft.compute.Cluster.compute(rack, dev.rackcraft.compute.Cluster.Kind.AI) == 0)
				&& port.siteReading(dev.rackcraft.world.ComputePods.R_STATE) == dev.rackcraft.world.ComputePods.State.TRIPPED.ordinal();
		cooler.setCreativeValue(CreativeSettings.TARGET_C, 24);
		for (int step = 0; step < 10; step++) SimTicker.stepNow(world);
		boolean recovered = !port.isTripped() && racks.stream().noneMatch(MachineBlockEntity::isTripped)
				&& dev.rackcraft.compute.Cluster.compute(racks.get(0), dev.rackcraft.compute.Cluster.Kind.AI) > 0;
		check("PD3.a", tripped && recovered, "tripped=" + tripped + " recovered=" + recovered + " portTripped=" + port.isTripped(), failures);
		world.setBlockState(o.add(2, 0, -2), Blocks.AIR.getDefaultState());

		// Fill and empty reach every rack of the pod.
		var player = net.fabricmc.fabric.api.entity.FakePlayer.get(world);
		player.getInventory().clear();
		for (int slot = 0; slot < 30; slot++) player.getInventory().setStack(slot % 36, new ItemStack(RcItems.ITEMS.get("pi_node")));
		for (MachineBlockEntity rack : racks) for (int slot = 0; slot < 12; slot++) rack.setStack(slot, ItemStack.EMPTY);
		var pod = dev.rackcraft.world.ComputePods.podOf(world, port);
		int filled = pod == null ? -1 : dev.rackcraft.world.ComputePods.fillAll(world, pod, player, false);
		int inRacks = racks.stream().mapToInt(rack -> rack.modules().size()).sum();
		int emptied = pod == null ? -1 : dev.rackcraft.world.ComputePods.fillAll(world, pod, player, true);
		int left = racks.stream().mapToInt(rack -> rack.modules().size()).sum();
		check("PD4.a", pod != null && filled == 30 && inRacks == 30 && emptied == 30 && left == 0,
				"filled=" + filled + " inRacks=" + inRacks + " emptied=" + emptied + " left=" + left, failures);
		player.getInventory().clear();

		// Not a pod: a rack knocked out of the cuboid, a pod too small, a low tier rack in it, or no port.
		world.setBlockState(o.add(1, 1, 1), Blocks.AIR.getDefaultState());
		for (int step = 0; step < 3; step++) SimTicker.stepNow(world);
		boolean notSolid = port.siteReading(dev.rackcraft.world.ComputePods.R_STATE) == dev.rackcraft.world.ComputePods.State.NO_POD.ordinal()
				&& racks.get(0).podBonus() == 1;
		world.setBlockState(o.add(1, 1, 1), RcBlocks.get("server_rack").getDefaultState());
		for (int step = 0; step < 3; step++) SimTicker.stepNow(world);
		boolean lowTier = port.siteReading(dev.rackcraft.world.ComputePods.R_STATE) == dev.rackcraft.world.ComputePods.State.NO_POD.ordinal();
		for (BlockPos pos : BlockPos.iterate(o.add(1, 1, 0), o.add(2, 1, 1))) world.setBlockState(pos, Blocks.AIR.getDefaultState());
		world.setBlockState(o.add(1, 0, 1), Blocks.AIR.getDefaultState());
		for (int step = 0; step < 3; step++) SimTicker.stepNow(world);
		boolean tooSmall = port.siteReading(dev.rackcraft.world.ComputePods.R_STATE) == dev.rackcraft.world.ComputePods.State.NO_POD.ordinal();
		check("PD5.a", notSolid && lowTier && tooSmall, "notSolid=" + notSolid + " lowTier=" + lowTier + " tooSmall=" + tooSmall, failures);
		lab.reset();
		clearArea(world, o, 14, 8, 14);
	}

	/** A flat dirt site, a powered Site Planner on it with drones and hydrogen, and its chunks forced; returns the planner. */
	private static MachineBlockEntity siteHarness(ServerWorld world, BlockPos origin) {
		clearArea(world, origin, 22, 14, 22);
		for (int cx = (origin.getX() - 4) >> 4; cx <= (origin.getX() + 22) >> 4; cx++) {
			for (int cz = (origin.getZ() - 4) >> 4; cz <= (origin.getZ() + 22) >> 4; cz++) world.setChunkForced(cx, cz, true);
		}
		for (BlockPos pos : BlockPos.iterate(origin.add(-2, -1, -2), origin.add(20, 0, 20))) world.setBlockState(pos, Blocks.DIRT.getDefaultState());
		BlockPos plannerPos = origin.add(0, 1, 4);
		MachineBlockEntity planner = place(world, plannerPos, "site_planner", Direction.NORTH);
		world.setBlockState(plannerPos.north(), RcBlocks.get("creative_power").getDefaultState());
		planner.setStack(0, new ItemStack(RcItems.ITEMS.get("construction_drone"), 4));
		planner.setStack(2, new ItemStack(RcItems.ITEMS.get("hydrogen_canister"), 16));
		return planner;
	}

	private static void siteCleanup(ServerWorld world, BlockPos origin) {
		world.getEntitiesByClass(net.minecraft.entity.ItemEntity.class, new net.minecraft.util.math.Box(origin).expand(60), item -> true)
				.forEach(net.minecraft.entity.Entity::discard);
		for (BlockPos pos : BlockPos.iterate(origin.add(-2, -1, -2), origin.add(20, 12, 20))) world.setBlockState(pos, Blocks.AIR.getDefaultState());
		for (int cx = (origin.getX() - 4) >> 4; cx <= (origin.getX() + 22) >> 4; cx++) {
			for (int cz = (origin.getZ() - 4) >> 4; cz <= (origin.getZ() + 22) >> 4; cz++) world.setChunkForced(cx, cz, false);
		}
	}

	/** Batch B: Blueprints. Scanning, reading (and refusing damaged ones), the scanner item, and printing through a Site Planner. */
	private static void checkBlueprints(ServerWorld world, int[] failures) {
		dev.rackcraft.compute.ResearchLab lab = dev.rackcraft.compute.ResearchLab.get(world);
		lab.reset();
		BlockPos src = clearArea(world, new BlockPos(-3200, 120, -1536), 12, 8, 12);
		MachineBlockEntity rack = place(world, src.add(1, 0, 1), "server_rack", Direction.EAST);
		for (int slot = 0; slot < 3; slot++) rack.setStack(slot, new ItemStack(RcItems.ITEMS.get("server_1u")));
		CableBlock cable = (CableBlock) RcBlocks.get("power_cable");
		world.setBlockState(src.add(2, 0, 1), cable.withConnections(cable.getDefaultState(), world, src.add(2, 0, 1)));
		place(world, src.add(3, 0, 1), "diesel_generator", Direction.SOUTH);
		world.setBlockState(src.add(1, 1, 1), Blocks.STONE.getDefaultState());
		ItemStack blank = new ItemStack(RcItems.ITEMS.get("blueprint"));
		boolean blankReads = dev.rackcraft.world.Blueprints.read(blank) == null && !dev.rackcraft.world.Blueprints.written(blank);
		int blocks = dev.rackcraft.world.Blueprints.scan(world, src, src.add(4, 2, 2), blank);
		var blueprint = dev.rackcraft.world.Blueprints.read(blank);
		var rackCell = blueprint == null ? null : blueprint.cells().stream().filter(cell -> cell.block().equals("server_rack")).findFirst().orElse(null);
		var generatorCell = blueprint == null ? null : blueprint.cells().stream().filter(cell -> cell.block().equals("diesel_generator")).findFirst().orElse(null);
		check("BP1.a", blankReads && blocks == 3 && blueprint != null && blueprint.blocks() == 3 && blueprint.skipped() == 1
						&& blueprint.width() == 5 && blueprint.height() == 3 && blueprint.depth() == 3
						&& rackCell != null && rackCell.x() == 1 && rackCell.y() == 0 && rackCell.z() == 1 && rackCell.facing() == Direction.EAST
						&& "server_1u".equals(rackCell.module()) && generatorCell != null && generatorCell.facing() == Direction.SOUTH && generatorCell.module() == null,
				"blank=" + blankReads + " blocks=" + blocks + " blueprint=" + (blueprint == null ? "null" : blueprint.blocks() + "/" + blueprint.skipped())
						+ " rack=" + rackCell, failures);
		var parts = blueprint == null ? java.util.Map.<net.minecraft.item.Item, Long>of() : dev.rackcraft.world.Blueprints.parts(blueprint);
		check("BP1.b", parts.get(RcBlocks.get("server_rack").asItem()) == 1L && parts.get(RcItems.ITEMS.get("server_1u")) == 8L
						&& parts.get(RcBlocks.get("power_cable").asItem()) == 1L && parts.get(RcBlocks.get("diesel_generator").asItem()) == 1L && parts.size() == 4,
				"parts=" + parts, failures);

		// A damaged or hostile blueprint is refused, never trusted.
		java.util.function.Function<java.util.function.Consumer<net.minecraft.nbt.NbtCompound>, Boolean> refused = tamper -> {
			ItemStack copy = blank.copy();
			tamper.accept(copy.getSubNbt(dev.rackcraft.world.Blueprints.KEY));
			return dev.rackcraft.world.Blueprints.read(copy) == null;
		};
		boolean hugeSize = refused.apply(nbt -> nbt.putIntArray("Size", new int[] {100, 1, 1}));
		boolean zeroSize = refused.apply(nbt -> nbt.putIntArray("Size", new int[] {0, 1, 1}));
		boolean badRuns = refused.apply(nbt -> nbt.putIntArray("Runs", new int[] {0, 5}));
		boolean negative = refused.apply(nbt -> nbt.putIntArray("Runs", new int[] {0, -3, 0, 48}));
		boolean oddRuns = refused.apply(nbt -> nbt.putIntArray("Runs", new int[] {0}));
		boolean unknownBlock = refused.apply(nbt -> {
			var palette = nbt.getList("Palette", net.minecraft.nbt.NbtElement.STRING_TYPE);
			palette.set(1, net.minecraft.nbt.NbtString.of("no_such_block|0|"));
		});
		boolean creativeBlock = refused.apply(nbt -> {
			var palette = nbt.getList("Palette", net.minecraft.nbt.NbtElement.STRING_TYPE);
			palette.set(1, net.minecraft.nbt.NbtString.of("creative_power|0|"));
		});
		boolean moduleOnGenerator = refused.apply(nbt -> {
			var palette = nbt.getList("Palette", net.minecraft.nbt.NbtElement.STRING_TYPE);
			palette.set(1, net.minecraft.nbt.NbtString.of("diesel_generator|0|server_1u"));
		});
		boolean badFacing = refused.apply(nbt -> {
			var palette = nbt.getList("Palette", net.minecraft.nbt.NbtElement.STRING_TYPE);
			palette.set(1, net.minecraft.nbt.NbtString.of("diesel_generator|9|"));
		});
		boolean notAPalette = refused.apply(nbt -> nbt.put("Palette", new net.minecraft.nbt.NbtList()));
		boolean tooBig = dev.rackcraft.world.Blueprints.scan(world, src, src.add(70, 1, 1), new ItemStack(RcItems.ITEMS.get("blueprint"))) == -1
				&& dev.rackcraft.world.Blueprints.scan(world, src, src.add(1, 1, 1), new ItemStack(Items.PAPER)) == -2;
		check("BP2.a", hugeSize && zeroSize && badRuns && negative && oddRuns && unknownBlock && creativeBlock && moduleOnGenerator && badFacing && notAPalette && tooBig,
				"size=" + hugeSize + "/" + zeroSize + " runs=" + badRuns + "/" + negative + "/" + oddRuns + " palette=" + unknownBlock + "/" + creativeBlock
						+ "/" + moduleOnGenerator + "/" + badFacing + "/" + notAPalette + " tooBig=" + tooBig, failures);

		// The scanner item: it marks the box, needs Digital Twin, and writes into a blank Blueprint from the inventory.
		var player = net.fabricmc.fabric.api.entity.FakePlayer.get(world);
		player.getInventory().clear();
		ItemStack scanner = new ItemStack(RcItems.ITEMS.get("blueprint_scanner"));
		for (BlockPos mark : List.of(src, src.add(4, 2, 2))) {
			var hit = new net.minecraft.util.hit.BlockHitResult(net.minecraft.util.math.Vec3d.ofCenter(mark), Direction.UP, mark, false);
			scanner.getItem().useOnBlock(new net.minecraft.item.ItemUsageContext(world, player, net.minecraft.util.Hand.MAIN_HAND, scanner, hit));
		}
		player.setStackInHand(net.minecraft.util.Hand.MAIN_HAND, scanner);
		ItemStack inPack = new ItemStack(RcItems.ITEMS.get("blueprint"));
		player.getInventory().setStack(5, inPack);
		scanner.getItem().use(world, player, net.minecraft.util.Hand.MAIN_HAND);
		boolean locked = !dev.rackcraft.world.Blueprints.written(inPack);
		lab.complete(world, dev.rackcraft.compute.Research.get("digital_twin"));
		scanner.getItem().use(world, player, net.minecraft.util.Hand.MAIN_HAND);
		boolean written = dev.rackcraft.world.Blueprints.written(inPack) && dev.rackcraft.world.Blueprints.read(inPack) != null
				&& dev.rackcraft.world.Blueprints.read(inPack).blocks() == 3;
		player.getInventory().setStack(5, ItemStack.EMPTY);
		ItemStack scanned = inPack.copy();
		scanner.getItem().use(world, player, net.minecraft.util.Hand.MAIN_HAND);
		boolean needsBlank = player.getInventory().count(RcItems.ITEMS.get("blueprint")) == 0;
		check("BP3.a", locked && written && needsBlank, "lockedBeforeResearch=" + locked + " written=" + written + " needsBlank=" + needsBlank, failures);
		player.getInventory().clear();
		player.setStackInHand(net.minecraft.util.Hand.MAIN_HAND, ItemStack.EMPTY);

		// Printing: the Site Planner's Blueprint layout. It says why it can't, then prints what was scanned, on bought parts.
		lab.reset();
		BlockPos origin = new BlockPos(-3300, 100, -1536);
		MachineBlockEntity planner = siteHarness(world, origin);
		for (int press = 0; press < 5; press++) dev.rackcraft.world.SitePlanner.cycleLayout(planner);
		check("BP4.a", dev.rackcraft.world.SitePlanner.layout(planner) == dev.rackcraft.world.SitePlanner.Layout.BLUEPRINT, "layout", failures);
		dev.rackcraft.world.SitePlanner.setArea(planner, origin.add(2, 0, 2), origin.add(8, 0, 6));
		FacilityManager.get(world).addCredits(10_000_000_000L);
		planner.setStack(3, scanned.copy());
		dev.rackcraft.world.SitePlanner.toggleRunning(planner);
		dev.rackcraft.world.SitePlanner.scanNow(world);
		int lockedWhy = planner.siteReading(dev.rackcraft.world.SitePlanner.R_BP_STATE);
		boolean lockedStatus = planner.processStatus() == dev.rackcraft.world.SitePlanner.Status.NOTHING_HERE.ordinal();
		lab.complete(world, dev.rackcraft.compute.Research.get("digital_twin"));
		planner.setStack(3, ItemStack.EMPTY);
		dev.rackcraft.world.SitePlanner.scanNow(world);
		int noneWhy = planner.siteReading(dev.rackcraft.world.SitePlanner.R_BP_STATE);
		planner.setStack(3, scanned.copy());
		dev.rackcraft.world.SitePlanner.toggleRunning(planner);
		dev.rackcraft.world.SitePlanner.setArea(planner, origin.add(2, 0, 2), origin.add(4, 0, 3));
		dev.rackcraft.world.SitePlanner.toggleRunning(planner);
		dev.rackcraft.world.SitePlanner.scanNow(world);
		int bigWhy = planner.siteReading(dev.rackcraft.world.SitePlanner.R_BP_STATE);
		check("BP4.b", lockedWhy == 1 && lockedStatus && noneWhy == 2 && bigWhy == 3
						&& planner.siteReading(dev.rackcraft.world.SitePlanner.R_BP_WIDTH) == 5 && planner.siteReading(dev.rackcraft.world.SitePlanner.R_BP_DEPTH) == 3,
				"locked=" + lockedWhy + " none=" + noneWhy + " tooBig=" + bigWhy, failures);
		dev.rackcraft.world.SitePlanner.toggleRunning(planner);
		dev.rackcraft.world.SitePlanner.setArea(planner, origin.add(2, 0, 2), origin.add(8, 0, 6));
		int shown = dev.rackcraft.world.SitePlanner.quotesShown();
		dev.rackcraft.world.SitePlanner.toggleRunning(planner);
		runSite(world, planner);
		BlockPos printedRack = origin.add(2 + 1, 1, 2 + 1);
		MachineBlockEntity copy = world.getBlockEntity(printedRack) instanceof MachineBlockEntity entity ? entity : null;
		boolean rackOk = copy != null && copy.blockId().equals("server_rack") && copy.getCachedState().get(MachineBlock.FACING) == Direction.EAST
				&& copy.modules().size() == 8 && copy.modules().stream().allMatch(module -> module == dev.rackcraft.sim.ServerModel.Module.SERVER_1U);
		boolean cableOk = world.getBlockState(origin.add(2 + 2, 1, 2 + 1)).isOf(RcBlocks.get("power_cable"));
		boolean generatorOk = world.getBlockState(origin.add(2 + 3, 1, 2 + 1)).isOf(RcBlocks.get("diesel_generator"))
				&& world.getBlockState(origin.add(2 + 3, 1, 2 + 1)).get(MachineBlock.FACING) == Direction.SOUTH;
		boolean noVanilla = world.getBlockState(origin.add(2 + 1, 2, 2 + 1)).isAir();
		check("BP5.a", rackOk && cableOk && generatorOk && noVanilla && planner.processStatus() == dev.rackcraft.world.SitePlanner.Status.DONE.ordinal(),
				"rack=" + rackOk + " cable=" + cableOk + " generator=" + generatorOk + " noVanilla=" + noVanilla + " status=" + planner.processStatus(), failures);
		check("BP6.a", ExchangeCatalog.price(RcBlocks.get("pod_port").asItem()) == null
						&& ExchangeCatalog.price(RcItems.ITEMS.get("blueprint_scanner")) == null && ExchangeCatalog.price(RcItems.ITEMS.get("blueprint")) != null,
				"podPortSold=" + ExchangeCatalog.price(RcBlocks.get("pod_port").asItem()), failures);
		check("BP5.b", planner.site().getLong("Spent") > 0 && dev.rackcraft.world.SitePlanner.quotesShown() == shown + 1
						&& dev.rackcraft.world.SitePlanner.ledger(planner).stream().anyMatch(purchase -> purchase.item().equals("rackcraft:server_rack")),
				"spent=" + planner.site().getLong("Spent") + " quotes=" + (dev.rackcraft.world.SitePlanner.quotesShown() - shown), failures);
		lab.reset();
		siteCleanup(world, origin);
		clearArea(world, src, 12, 8, 12);
	}

	/** Batch B: the Retrofit layout upgrades racks in place and keeps what is in them. */
	private static void checkRetrofit(ServerWorld world, int[] failures) {
		dev.rackcraft.compute.ResearchLab lab = dev.rackcraft.compute.ResearchLab.get(world);
		lab.reset();
		BlockPos origin = new BlockPos(-3400, 100, -1536);
		MachineBlockEntity planner = siteHarness(world, origin);
		for (int press = 0; press < 6; press++) dev.rackcraft.world.SitePlanner.cycleLayout(planner);
		check("RT0.a", dev.rackcraft.world.SitePlanner.layout(planner) == dev.rackcraft.world.SitePlanner.Layout.RETROFIT, "layout", failures);
		MachineBlockEntity rackA = place(world, origin.add(3, 1, 3), "server_rack", Direction.WEST);
		for (int slot = 0; slot < 3; slot++) rackA.setStack(slot, new ItemStack(RcItems.ITEMS.get("server_1u")));
		rackA.setLoadLimitPercent(75);
		MachineBlockEntity rackB = place(world, origin.add(5, 1, 3), "server_rack", Direction.NORTH);
		for (int slot = 0; slot < 8; slot++) rackB.setStack(slot, new ItemStack(RcItems.ITEMS.get("pi_node")));
		MachineBlockEntity rackC = place(world, origin.add(7, 1, 3), "high_density_rack", Direction.NORTH);
		dev.rackcraft.world.SitePlanner.setArea(planner, origin.add(2, 0, 2), origin.add(8, 0, 5));
		FacilityManager.get(world).addCredits(10_000_000_000L);

		// Held back to the research: with none, the target is a Server Rack and there is nothing to do.
		dev.rackcraft.world.SitePlanner.cycleHallRack(planner);
		dev.rackcraft.world.SitePlanner.toggleRunning(planner);
		dev.rackcraft.world.SitePlanner.scanNow(world);
		boolean held = dev.rackcraft.world.Retrofits.target(world, planner) == dev.rackcraft.sim.ServerModel.Tier.SERVER
				&& planner.siteReading(dev.rackcraft.world.SitePlanner.R_BP_STATE) == 1
				&& planner.processStatus() == dev.rackcraft.world.SitePlanner.Status.DONE.ordinal();
		dev.rackcraft.world.SitePlanner.toggleRunning(planner);
		lab.complete(world, dev.rackcraft.compute.Research.get("dense_racks"));
		check("RT1.a", held && dev.rackcraft.world.Retrofits.target(world, planner) == dev.rackcraft.sim.ServerModel.Tier.HIGH_DENSITY
						&& dev.rackcraft.world.Retrofits.pending(world, dev.rackcraft.world.SitePlanner.site(planner), dev.rackcraft.sim.ServerModel.Tier.HIGH_DENSITY).size() == 2,
				"held=" + held + " pending=" + dev.rackcraft.world.Retrofits.pending(world, dev.rackcraft.world.SitePlanner.site(planner), dev.rackcraft.sim.ServerModel.Tier.HIGH_DENSITY).size(), failures);

		// Approve and run: two racks upgraded in place, modules, limit and facing kept, the one already there untouched.
		dev.rackcraft.world.SitePlanner.toggleRunning(planner);
		runSite(world, planner);
		MachineBlockEntity nowA = world.getBlockEntity(origin.add(3, 1, 3)) instanceof MachineBlockEntity entity ? entity : null;
		MachineBlockEntity nowB = world.getBlockEntity(origin.add(5, 1, 3)) instanceof MachineBlockEntity entity ? entity : null;
		boolean upgradedA = nowA != null && nowA.blockId().equals("high_density_rack") && nowA.size() >= 12
				&& nowA.modules().size() == 3 && nowA.loadLimitPercent() == 75 && nowA.getCachedState().get(MachineBlock.FACING) == Direction.WEST;
		boolean upgradedB = nowB != null && nowB.blockId().equals("high_density_rack") && nowB.modules().size() == 8
				&& nowB.getCachedState().get(MachineBlock.FACING) == Direction.NORTH;
		boolean untouched = world.getBlockEntity(origin.add(7, 1, 3)) == rackC;
		check("RT2.a", upgradedA && upgradedB && untouched && planner.processStatus() == dev.rackcraft.world.SitePlanner.Status.DONE.ordinal(),
				"A=" + upgradedA + " B=" + upgradedB + " untouched=" + untouched + " status=" + planner.processStatus(), failures);
		long coils = 0;
		long doors = 0;
		for (var purchase : dev.rackcraft.world.SitePlanner.ledger(planner)) {
			if (purchase.item().equals("rackcraft:cryo_coil")) coils += purchase.count();
			if (purchase.item().equals("rackcraft:rear_door_cooler")) doors += purchase.count();
		}
		boolean noDrops = world.getEntitiesByClass(net.minecraft.entity.ItemEntity.class, new net.minecraft.util.math.Box(origin).expand(30),
				item -> Racks_isModule(item.getStack())).isEmpty();
		check("RT2.b", coils == 4 && doors == 2 && planner.site().getLong("Spent") > 0 && noDrops,
				"coils=" + coils + " doors=" + doors + " spent=" + planner.site().getLong("Spent") + " noDroppedModules=" + noDrops, failures);

		// Several tiers at once, with the parts of every tier in between; and a rack that wouldn't fit is left alone.
		var exa = dev.rackcraft.sim.ServerModel.Tier.EXASCALE;
		var parts = dev.rackcraft.world.Retrofits.parts(dev.rackcraft.sim.ServerModel.Tier.SERVER, exa);
		var partIds = parts.entrySet().stream().collect(java.util.stream.Collectors.toMap(
				entry -> Registries.ITEM.getId(entry.getKey()).getPath(), java.util.Map.Entry::getValue));
		check("RT3.a", partIds.get("cryo_coil") == 2 && partIds.get("cdu") == 3 && partIds.get("rear_door_cooler") == 1 && partIds.get("coolant_pipe") == 8
						&& partIds.get("graphene_sheet") == 4 && partIds.get("photonic_chip") == 8 && partIds.get("superconducting_wire") == 4
						&& partIds.get("core_router") == 1 && partIds.get("hbm_stack") == 4 && partIds.size() == 9,
				"parts=" + partIds, failures);
		BlockPos far = origin.add(10, 1, 3);
		MachineBlockEntity cheap = place(world, far, "server_rack", Direction.SOUTH);
		cheap.setStack(0, new ItemStack(RcItems.ITEMS.get("neuromorphic_core")));
		cheap.setStack(1, new ItemStack(RcItems.ITEMS.get("neuromorphic_core")));
		cheap.setLoadLimitPercent(50);
		net.minecraft.inventory.SimpleInventory hold = new net.minecraft.inventory.SimpleInventory(27);
		parts.forEach((item, count) -> hold.addStack(new ItemStack(item, count)));
		ItemStack cryo = hold.getStack(0);
		cryo.decrement(1);
		boolean short_ = !dev.rackcraft.world.Retrofits.upgrade(world, far, exa, hold) && world.getBlockState(far).isOf(RcBlocks.get("server_rack"));
		cryo.increment(1);
		boolean jumped = dev.rackcraft.world.Retrofits.upgrade(world, far, exa, hold);
		MachineBlockEntity cabinet = world.getBlockEntity(far) instanceof MachineBlockEntity entity ? entity : null;
		boolean keptAll = cabinet != null && cabinet.blockId().equals("exascale_cabinet") && cabinet.size() >= 24 && cabinet.modules().size() == 2
				&& cabinet.loadLimitPercent() == 50 && cabinet.getCachedState().get(MachineBlock.FACING) == Direction.SOUTH && hold.isEmpty();
		BlockPos pi = origin.add(12, 1, 3);
		MachineBlockEntity starter = place(world, pi, "server_rack", Direction.NORTH);
		starter.setStack(0, new ItemStack(RcItems.ITEMS.get("pi_node")));
		net.minecraft.inventory.SimpleInventory again = new net.minecraft.inventory.SimpleInventory(27);
		parts.forEach((item, count) -> again.addStack(new ItemStack(item, count)));
		boolean wontFit = !dev.rackcraft.world.Retrofits.fits(starter, exa) && !dev.rackcraft.world.Retrofits.upgrade(world, pi, exa, again)
				&& world.getBlockState(pi).isOf(RcBlocks.get("server_rack")) && !again.isEmpty();
		boolean downhill = !dev.rackcraft.world.Retrofits.upgrade(world, far, dev.rackcraft.sim.ServerModel.Tier.HIGH_DENSITY, again);
		check("RT3.b", short_ && jumped && keptAll && wontFit && downhill,
				"shortOfAPart=" + short_ + " jumped=" + jumped + " kept=" + keptAll + " wontFit=" + wontFit + " downhill=" + downhill, failures);

		// An empty site has nothing to retrofit.
		dev.rackcraft.world.SitePlanner.toggleRunning(planner);
		dev.rackcraft.world.SitePlanner.setArea(planner, origin.add(14, 0, 10), origin.add(18, 0, 14));
		dev.rackcraft.world.SitePlanner.toggleRunning(planner);
		dev.rackcraft.world.SitePlanner.scanNow(world);
		check("RT4.a", planner.processStatus() == dev.rackcraft.world.SitePlanner.Status.NOTHING_HERE.ordinal(), "status=" + planner.processStatus(), failures);
		lab.reset();
		siteCleanup(world, origin);
	}

	private static boolean Racks_isModule(ItemStack stack) {
		return dev.rackcraft.block.Racks.module(stack) != null;
	}

	private static void checkPerformance(ServerWorld world, int[] failures) {
		BlockPos origin = clearArea(world, new BlockPos(-2100, 150, -1536), 44, 4, 24);
		world.setBlockState(origin.west(), RcBlocks.get("creative_power").getDefaultState());
		machine(world, origin.west()).setCreativeValue(CreativeSettings.OUTPUT_KW, 100_000);
		for (int x = 0; x < 40; x++) {
			for (int z = 0; z < 10; z++) {
				MachineBlockEntity rack = place(world, origin.add(x, 0, z), "server_rack", Direction.NORTH);
				rack.setStack(0, new ItemStack(RcItems.ITEMS.get("server_1u")));
			}
		}
		place(world, origin.up(), "core_router", Direction.NORTH);
		for (int step = 0; step < 3; step++) SimTicker.stepNow(world);
		long start = System.nanoTime();
		for (int step = 0; step < 5; step++) SimTicker.stepNow(world);
		double perStep = (System.nanoTime() - start) / 5e6;
		check("PF1.a", perStep < 30, String.format(java.util.Locale.ROOT, "400 racks on one network: %.1f ms a step", perStep), failures);
		clearArea(world, origin, 44, 4, 24);

		// A wall of steel blocks (machinery: never dug through) across the straight line from the planner to its target.
		BlockPos floor = origin.down();
		for (BlockPos pos : BlockPos.iterate(floor.add(-2, 0, -12), floor.add(24, 0, 12))) world.setBlockState(pos, Blocks.STONE.getDefaultState());
		for (int z = -4; z <= 4; z++) world.setBlockState(origin.add(8, 0, z), RcBlocks.get("steel_block").getDefaultState());
		List<BlockPos> route = dev.rackcraft.world.SitePlanner.route(world, origin, origin.east(16), null);
		boolean clearOfWall = route.stream().noneMatch(pos -> world.getBlockState(pos).isOf(RcBlocks.get("steel_block")));
		boolean joined = !route.isEmpty() && route.get(0).getManhattanDistance(origin) == 1
				&& route.get(route.size() - 1).getManhattanDistance(origin.east(16)) == 1;
		check("PF2.a", clearOfWall && joined && route.size() > 15,
				"cells=" + route.size() + " clearOfWall=" + clearOfWall + " joined=" + joined, failures);
		clearArea(world, origin, 44, 4, 24);
		for (BlockPos pos : BlockPos.iterate(floor.add(-2, 0, -12), floor.add(24, 0, 12))) world.setBlockState(pos, Blocks.AIR.getDefaultState());
	}

	private static void flyMission(ServerWorld world, MachineBlockEntity control) {
		for (int step = 0; step < 200 && (control.launchCountdown() > 0 || control.launchFlight() > 0); step++) SimTicker.stepNow(world);
	}

	/** Flies every drone near this dock until all are home (or 30 seconds pass). */
	private static void flyDrones(ServerWorld world, BlockPos dock) {
		net.minecraft.util.math.Box area = new net.minecraft.util.math.Box(dock).expand(80);
		for (int tick = 0; tick < 600; tick++) {
			List<dev.rackcraft.entity.MaintenanceDroneEntity> flying = world.getEntitiesByClass(
					dev.rackcraft.entity.MaintenanceDroneEntity.class, area, net.minecraft.entity.Entity::isAlive);
			if (flying.isEmpty()) return;
			flying.forEach(dev.rackcraft.entity.MaintenanceDroneEntity::serverTick);
		}
	}

	private static long failedModules(ServerWorld world) {
		long count = 0;
		for (MachineBlockEntity machine : SimTicker.machines(world)) {
			if (!machine.blockId().equals("server_rack")) continue;
			for (int slot = 0; slot < 8; slot++) if (machine.getStack(slot).isOf(RcItems.ITEMS.get("failed_module"))) count++;
		}
		return count;
	}

	/** A rack boots over its boot time once powered, mining partly on the way, and goes cold when power drops. */
	private static void checkBoot(ServerWorld world, int[] failures) {
		RackcraftConfig.values.sim.rackBootScale = 1;
		BlockPos origin = clearArea(world, new BlockPos(-640, 150, -360), 6, 4, 4);
		world.setBlockState(origin.west(), RcBlocks.get("creative_power").getDefaultState());
		MachineBlockEntity rack = rack(world, origin, "server_1u");
		world.setBlockState(origin.up(), RcBlocks.get("uplink_router").getDefaultState());
		rack.setBootProgress(0);
		for (int step = 0; step < 8; step++) SimTicker.stepNow(world);
		double midway = rack.bootProgress();
		RackStatus midStatus = rack.rackStatus();
		double midRate = rack.miningRate();
		for (int step = 0; step < 60; step++) SimTicker.stepNow(world);
		double done = rack.bootProgress();
		double fullRate = rack.miningRate();
		world.setBlockState(origin.west(), Blocks.AIR.getDefaultState());
		SimTicker.stepNow(world);
		double cold = rack.bootProgress();
		// 8 x 1U = 32 s to boot; 8 steps of 0.5 s is an eighth of the way.
		check("K1.a", Math.abs(midway - 0.125) < 0.01 && midStatus == RackStatus.BOOTING && midRate > 0 && midRate < fullRate
						&& done == 1 && rack.rackStatus() != RackStatus.BOOTING && cold == 0,
				"after 4 s: boot=" + midway + " status=" + midStatus + " rate=" + midRate + "; after 34 s: boot=" + done
						+ " rate=" + fullRate + "; unplugged: boot=" + cold, failures);
		clearArea(world, origin, 6, 4, 4);
		RackcraftConfig.values.sim.rackBootScale = 0;
	}

	/**
	 * A solar panel under open sky at noon powers a load, and makes nothing at night. (Roofing it over can't be
	 * checked here: sky light is recalculated off-thread, after this tick.)
	 */
	private static void checkSolar(ServerWorld world, int[] failures) {
		long time = world.getTimeOfDay();
		world.setTimeOfDay(6000);
		world.setWeather(6000, 0, false, false);
		world.calculateAmbientDarkness();
		BlockPos origin = clearArea(world, new BlockPos(-640, 250, -300), 4, 4, 4);
		MachineBlockEntity panel = place(world, origin, "solar_panel", Direction.NORTH);
		MachineBlockEntity load = place(world, origin.east(), "creative_rack", Direction.NORTH);
		load.setCreativeValue(CreativeSettings.DRAW_KW, 2);
		load.setCreativeValue(CreativeSettings.MINING_RATE, 0);
		for (int step = 0; step < 2; step++) SimTicker.stepNow(world);
		double noon = panel.powerKw();
		double supplied = load.powerSatisfaction();
		boolean lit = world.getBlockState(origin).get(MachineBlock.LIT);
		world.setTimeOfDay(18000);
		world.calculateAmbientDarkness();
		SimTicker.stepNow(world);
		double night = panel.powerKw();
		world.setTimeOfDay(time);
		world.calculateAmbientDarkness();
		check("P2.a", noon > 1.99 && lit && supplied > 0.99 && night == 0,
				"noon=" + noon + " loadSupplied=" + supplied + " lit=" + lit + " night=" + night, failures);
		clearArea(world, origin, 4, 4, 4);
	}

	/**
	 * The terminal's crafting result always matches its grid: shift-clicking the ingredients out clears it, and a
	 * stale result can't be taken. (2x2 sand used to leave sandstone behind, free for the taking.) Also checks the
	 * two crafting modules.
	 */
	private static void checkTerminalGrid(ServerWorld world, int[] failures) {
		BlockPos origin = clearArea(world, new BlockPos(-640, 150, -240), 4, 4, 4);
		world.setBlockState(origin, RcBlocks.get("creative_power").getDefaultState());
		MachineBlockEntity array = place(world, origin.east(), "storage_array", Direction.NORTH);
		array.setStack(0, new ItemStack(RcItems.ITEMS.get("drive_1k")));
		world.setBlockState(origin.east(2), RcBlocks.get("storage_terminal").getDefaultState());
		SimTicker.stepNow(world);
		var player = net.fabricmc.fabric.api.entity.FakePlayer.get(world);
		player.getInventory().clear();
		var access = new dev.rackcraft.storage.StorageService.Access(world.getRegistryKey(), origin.east(2), false);
		var handler = new dev.rackcraft.storage.TerminalScreenHandler(0, player.getInventory(), access);
		int[] grid = {0, 1, 3, 4};
		for (int slot : grid) handler.slots.get(slot).setStack(new ItemStack(Items.SAND));
		boolean made = handler.slots.get(9).getStack().isOf(Items.SANDSTONE);
		for (int slot : grid) handler.quickMove(player, slot);
		boolean cleared = handler.slots.get(9).getStack().isEmpty() && player.getInventory().count(Items.SAND) == 4;
		// A result left behind by any other route is refused rather than handed out for free.
		for (int slot : grid) handler.slots.get(slot).setStack(new ItemStack(Items.SAND));
		for (int slot : grid) handler.slots.get(slot).getStack().setCount(0);
		boolean refused = !handler.slots.get(9).canTakeItems(player);
		check("T1.a", made && cleared && refused, "sandstoneMade=" + made + " clearedAfterShiftClick=" + cleared
				+ " staleRefused=" + refused, failures);
		player.getInventory().clear();
		array.setStack(0, ItemStack.EMPTY);
		clearArea(world, origin, 4, 4, 4);

		var coprocessor = dev.rackcraft.sim.ServerModel.Module.byItemId("crafting_coprocessor");
		var accelerator = dev.rackcraft.sim.ServerModel.Module.byItemId("crafting_accelerator");
		boolean dryAccelerator = dev.rackcraft.sim.ServerModel.calculate(List.of(accelerator), 100, 1, 24, true, false).waterBlocked();
		boolean dryCoprocessor = dev.rackcraft.sim.ServerModel.calculate(List.of(coprocessor), 100, 1, 24, true, false).waterBlocked();
		check("M1.a", coprocessor.compute() == 8 && accelerator.compute() == 30 && coprocessor.creditsPerSecond() == 0
						&& dryAccelerator && !dryCoprocessor,
				"coprocessor=" + coprocessor.compute() + " accelerator=" + accelerator.compute() + " acceleratorNeedsLoop=" + dryAccelerator
						+ " coprocessorAirCooled=" + !dryCoprocessor, failures);
	}

	/** Uranium is priced by the work behind it, materials pay double, and plain building blocks don't. */
	private static void checkPrices(int[] failures) {
		Long uranium = ExchangeCatalog.price(RcItems.ITEMS.get("raw_uranium"));
		Long cell = ExchangeCatalog.price(RcItems.ITEMS.get("fuel_cell"));
		Long ingot = ExchangeCatalog.price(Items.IRON_INGOT);
		Long block = ExchangeCatalog.price(Items.IRON_BLOCK);
		Long bricks = ExchangeCatalog.price(Items.STONE_BRICKS);
		Long stairs = ExchangeCatalog.price(Items.STONE_BRICK_STAIRS);
		boolean building = ExchangeCatalog.building(Items.STONE_BRICKS, java.util.Set.of()) && !ExchangeCatalog.building(Items.FURNACE, java.util.Set.of());
		check("E1.a", uranium != null && uranium >= 2000 && cell != null && cell >= 50_000
						&& ExchangeCatalog.price(RcItems.ITEMS.get("spent_fuel")) == null && block != null && ingot != null && block >= 9 * ingot
						&& bricks != null && stairs != null && building
						&& ExchangeCatalog.price(Items.NETHERITE_INGOT) > 4 * ExchangeCatalog.price(Items.NETHERITE_SCRAP),
				"rawUranium=" + uranium + " fuelCell=" + cell + " ironIngot=" + ingot + " ironBlock=" + block
						+ " stoneBricks=" + bricks + " stairs=" + stairs + " buildingRule=" + building
						+ " netheriteIngot=" + ExchangeCatalog.price(Items.NETHERITE_INGOT) + " scrap=" + ExchangeCatalog.price(Items.NETHERITE_SCRAP)
						+ " netheriteBlock=" + ExchangeCatalog.price(Items.NETHERITE_BLOCK) + " gold=" + ExchangeCatalog.price(Items.GOLD_INGOT), failures);
		StringBuilder sample = new StringBuilder();
		for (String id : List.of("steel_ingot", "silicon", "copper_wire", "circuit_board", "cpu_chip", "gpu_chip", "electric_motor", "pi_node",
				"server_1u", "gpu_blade", "quantum_core", "drive_64k", "power_cable", "server_rack", "pdu", "crac_unit", "chiller", "solar_panel",
				"wind_turbine", "battery_bank", "core_router", "storage_array", "modular_reactor", "welding_arm", "crypto_exchange", "site_planner")) {
			sample.append(id).append('=').append(ExchangeCatalog.price(item(id))).append(' ');
		}
		Rackcraft.LOGGER.info("RACKCRAFT_SELFTEST prices {}", sample);
		// Hardware pays its premium on top of the material one; plain materials don't.
		check("E1.b", ExchangeCatalog.hardwarePremium(item("server_rack")) == RackcraftConfig.values.exchange.machinePremium
						&& ExchangeCatalog.hardwarePremium(item("gpu_blade")) == RackcraftConfig.values.exchange.componentPremium
						&& ExchangeCatalog.hardwarePremium(item("steel_ingot")) == 1 && ExchangeCatalog.hardwarePremium(Items.FURNACE) == 1
						&& ExchangeOffers.all().values().stream().allMatch(offer -> ExchangeCatalog.price(offer.item()) == null
								|| offer.price() == ExchangeCatalog.price(offer.item()) * offer.count()),
				"rack=" + ExchangeCatalog.price(item("server_rack")) + " gpuBlade=" + ExchangeCatalog.price(item("gpu_blade"))
						+ " gpuOffer=" + ExchangeOffers.all().get("gpu_blade").price(), failures);
	}

	private static net.minecraft.item.Item item(String id) {
		net.minecraft.item.Item item = RcItems.ITEMS.get(id);
		if (item != null) return item;
		if (RcBlocks.BLOCKS.containsKey(id)) return RcBlocks.get(id).asItem();
		return Registries.ITEM.get(new net.minecraft.util.Identifier("minecraft", id));
	}

	/** An Item Pipe from a Storage Array stocks an art table, a desk and a generator, and files the art aggregates. */
	private static void checkItemPipes(ServerWorld world, int[] failures) {
		BlockPos origin = clearArea(world, new BlockPos(-768, 150, -512), 12, 6, 6);
		world.setBlockState(origin, RcBlocks.get("creative_power").getDefaultState());
		MachineBlockEntity array = place(world, origin.east(), "storage_array", Direction.NORTH);
		array.setStack(0, new ItemStack(RcItems.ITEMS.get("drive_4k")));
		CableBlock pipe = (CableBlock) RcBlocks.get("item_pipe");
		for (int dx = 2; dx <= 6; dx++) {
			BlockPos pos = origin.east(dx);
			world.setBlockState(pos, pipe.withConnections(pipe.getDefaultState(), world, pos));
		}
		MachineBlockEntity table = place(world, origin.add(3, 1, 0), "art_table", Direction.SOUTH);
		MachineBlockEntity desk = place(world, origin.add(4, 1, 0), "writing_desk", Direction.SOUTH);
		MachineBlockEntity generator = place(world, origin.add(6, 1, 0), "diesel_generator", Direction.SOUTH);
		MachineBlockEntity reactor = place(world, origin.add(5, 1, 0), "modular_reactor", Direction.SOUTH);
		reactor.setStack(1, new ItemStack(RcItems.ITEMS.get("spent_fuel"), 2));
		table.setStack(2, new ItemStack(RcItems.ITEMS.get("art_aggregate"), 3));
		SimTicker.stepNow(world);
		var storage = dev.rackcraft.storage.StorageService.networkAt(world, array.getPos());
		storage.insert(dev.rackcraft.storage.ItemKey.of(Items.PAPER), 100, false);
		storage.insert(dev.rackcraft.storage.ItemKey.of(Items.INK_SAC), 10, false);
		storage.insert(dev.rackcraft.storage.ItemKey.of(RcItems.ITEMS.get("crayons")), 2, false);
		storage.insert(dev.rackcraft.storage.ItemKey.of(Items.COAL), 40, false);
		storage.insert(dev.rackcraft.storage.ItemKey.of(RcItems.ITEMS.get("fuel_cell")), 6, false);
		dev.rackcraft.world.ItemPipes.step(world, SimTicker.machines(world));
		long aggregates = storage.count(dev.rackcraft.storage.ItemKey.of(RcItems.ITEMS.get("art_aggregate")), true);
		long spentStored = storage.count(dev.rackcraft.storage.ItemKey.of(RcItems.ITEMS.get("spent_fuel")), true);
		check("P1.b", spentStored == 2 && reactor.getStack(1).isEmpty() && reactor.getStack(0).getCount() == 4,
				"reactor on the pipe: spentStored=" + spentStored + " waste=" + reactor.getStack(1) + " fuel=" + reactor.getStack(0), failures);
		check("P1.a", table.getStack(0).getCount() == 32 && table.getStack(1).isOf(RcItems.ITEMS.get("crayons"))
						&& desk.getStack(0).getCount() == 32 && desk.getStack(1).getCount() == 8 && table.getStack(2).isEmpty()
						&& aggregates == 3 && generator.getStack(0).isOf(Items.COAL) && generator.getStack(0).getCount() == 32,
				"tablePaper=" + table.getStack(0) + " crayons=" + table.getStack(1) + " deskPaper=" + desk.getStack(0)
						+ " ink=" + desk.getStack(1) + " aggregatesStored=" + aggregates + " fuel=" + generator.getStack(0), failures);
		array.setStack(0, ItemStack.EMPTY);
		clearArea(world, origin, 12, 6, 6);
	}

	/** A pump on fresh water runs a GPU rack and drains the pool from its edge. Finds a non-ocean spot first. */
	private static void checkFreshWater(ServerWorld world, int[] failures) {
		BlockPos site = null;
		for (int attempt = 0; attempt < 64 && site == null; attempt++) {
			BlockPos candidate = new BlockPos(512 + attempt * 96, 160, -512);
			var biome = world.getBiome(candidate);
			if (!biome.isIn(net.minecraft.registry.tag.BiomeTags.IS_OCEAN) && !biome.isIn(net.minecraft.registry.tag.BiomeTags.IS_DEEP_OCEAN)
					&& !biome.isIn(net.minecraft.registry.tag.BiomeTags.IS_BEACH) && !biome.isIn(net.minecraft.registry.tag.BiomeTags.IS_RIVER)) {
				site = candidate;
			}
		}
		if (site == null) {
			check("C2.b", true, "skipped: no land biome found near the test area", failures);
			return;
		}
		world.getChunk(site);
		for (BlockPos pos : BlockPos.iterate(site.add(-2, -1, -5), site.add(10, 3, 5))) world.setBlockState(pos, Blocks.AIR.getDefaultState());
		BlockPos rackPos = site;
		world.setBlockState(rackPos.west(), RcBlocks.get("creative_power").getDefaultState());
		world.setBlockState(rackPos, RcBlocks.get("server_rack").getDefaultState());
		MachineBlockEntity rack = machine(world, rackPos);
		for (int slot = 0; slot < 8; slot++) rack.setStack(slot, new ItemStack(RcItems.ITEMS.get("gpu_blade")));
		// The pump sits on the rack at the edge of a 7x7 pool, and a cooling tower sits on the pump: one loop.
		BlockPos pump = rackPos.up();
		BlockPos tower = pump.up();
		for (BlockPos pos : BlockPos.iterate(rackPos.add(1, 0, -4), rackPos.add(8, 1, 4))) world.setBlockState(pos, Blocks.STONE.getDefaultState());
		for (BlockPos pos : BlockPos.iterate(rackPos.add(1, 1, -3), rackPos.add(7, 1, 3))) world.setBlockState(pos, Blocks.WATER.getDefaultState());
		world.setBlockState(pump, RcBlocks.get("freshwater_pump").getDefaultState());
		world.setBlockState(tower, RcBlocks.get("cooling_tower").getDefaultState());
		MachineBlockEntity pumpEntity = machine(world, pump);
		MachineBlockEntity towerEntity = machine(world, tower);
		pumpEntity.addWaterDrawn(-1e9);
		for (int step = 0; step < 4; step++) SimTicker.stepNow(world);
		boolean running = rack.rackStatus() != RackStatus.NEEDS_WATER && pumpEntity.pumpUnits() >= 4
				&& towerEntity.coolingDetail() == 4 && towerEntity.coolingKw() > 15;
		int before = pumpEntity.pumpSources();
		// Fast-forward the draw: the next step takes one source block off the shore.
		pumpEntity.addWaterDrawn(dev.rackcraft.world.FreshwaterCooling.UNIT_SECONDS_PER_BLOCK);
		for (int step = 0; step < 25; step++) SimTicker.stepNow(world);
		int after = pumpEntity.pumpSources();
		check("C2.b", running && after < before, "biome=" + world.getBiome(site).getKey().map(key -> key.getValue().toString()).orElse("?")
				+ " status=" + rack.rackStatus() + " pump=" + pumpEntity.pumpStatus() + " units=" + pumpEntity.pumpUnits()
				+ " towerWater=" + towerEntity.coolingDetail() + " towerKw=" + towerEntity.coolingKw()
				+ " sources " + before + " -> " + after, failures);
		for (BlockPos pos : BlockPos.iterate(site.add(-2, -1, -5), site.add(10, 3, 5))) world.setBlockState(pos, Blocks.AIR.getDefaultState());
	}

	/**
	 * Every data center: registered as a structure, in #rackcraft:data_centers, builds without errors (the
	 * campus included, rotated), contains Rackcraft machines, and the nearest one can be located.
	 */
	private static void checkStructures(ServerWorld world, int[] failures) {
		var registry = world.getRegistryManager().get(net.minecraft.registry.RegistryKeys.STRUCTURE);
		var tagged = registry.getEntryList(Worldgen.DATA_CENTERS).map(list -> list.size()).orElse(0);
		var layouts = dev.rackcraft.world.structure.DataCenterLayouts.all();
		boolean registered = layouts.keySet().stream().allMatch(id -> registry.containsId(Rackcraft.id(id)));
		check("D1.a", layouts.size() >= 12 && registered && tagged == layouts.size(),
				"layouts=" + layouts.size() + " registered=" + registered + " tagged=" + tagged, failures);
		int index = 0;
		BlockPos campusOrigin = null;
		for (var layout : layouts.values()) {
			BlockPos origin = new BlockPos(4096 + index * 192, 120, 4096);
			if (layout.id().equals("hyperscale_campus")) campusOrigin = origin;
			index++;
			var rotation = net.minecraft.util.BlockRotation.values()[index % 4];
			String error = null;
			try {
				dev.rackcraft.world.structure.DataCenterPiece.buildNow(world, layout.id(), origin, rotation, index);
			} catch (RuntimeException exception) {
				error = exception.toString();
				Rackcraft.LOGGER.error("Building {} failed", layout.id(), exception);
			}
			boolean turned = rotation == net.minecraft.util.BlockRotation.CLOCKWISE_90
					|| rotation == net.minecraft.util.BlockRotation.COUNTERCLOCKWISE_90;
			int spanX = turned ? layout.depth() : layout.width();
			int spanZ = turned ? layout.width() : layout.depth();
			int machines = 0;
			int racks = 0;
			for (BlockPos pos : BlockPos.iterate(origin, origin.add(spanX - 1, layout.height() - 1, spanZ - 1))) {
				if (world.getBlockEntity(pos) instanceof MachineBlockEntity machine) {
					machines++;
					if (machine.blockId().equals("server_rack")) racks++;
				}
			}
			String details = layout.id() + " " + rotation + " machines=" + machines + " racks=" + racks
					+ (layout.id().equals("hyperscale_campus") ? " footprint=" + layout.width() + "x" + layout.depth() : "")
					+ (error == null ? "" : " error=" + error);
			check("D2." + layout.id(), error == null && machines >= 2 && (racks > 0 || layout.id().equals("tape_archive")), details, failures);
		}
		var scribes = world.getEntitiesByClass(net.minecraft.entity.passive.VillagerEntity.class,
				new net.minecraft.util.math.Box(new BlockPos(4096, 120, 4096)).expand(2700, 64, 240),
				villager -> villager.getCommandTags().contains(dev.rackcraft.compute.TrainingStations.SHACKLED_TAG));
		var kids = world.getEntitiesByClass(net.minecraft.entity.passive.VillagerEntity.class,
				new net.minecraft.util.math.Box(new BlockPos(4096, 120, 4096)).expand(2700, 64, 240),
				net.minecraft.entity.passive.VillagerEntity::isBaby);
		check("D3.a", scribes.size() >= 11 && kids.size() >= 10, "shackled librarians in the AI lab, content mill and campus="
				+ scribes.size() + ", kids=" + kids.size(), failures);
		world.getEntitiesByClass(net.minecraft.entity.passive.VillagerEntity.class,
				new net.minecraft.util.math.Box(new BlockPos(4096, 120, 4096)).expand(2700, 64, 240), entity -> true)
				.forEach(net.minecraft.entity.Entity::discard);
		if (campusOrigin != null) checkCampusRuns(world, campusOrigin, failures);
		checkWorldgen(world, failures);
		BlockPos found = world.locateStructure(Worldgen.DATA_CENTERS, BlockPos.ORIGIN, 100, false);
		check("D4.a", found != null, "nearest data center to 0,0: " + found, failures);
		if (found == null) return;
		// Let worldgen build it for real, chunk by chunk, and count what it left behind.
		int machines = 0;
		int chunks = 0;
		java.util.List<String> ids = new java.util.ArrayList<>();
		java.util.List<String> starts = new java.util.ArrayList<>();
		net.minecraft.util.math.ChunkPos centre = new net.minecraft.util.math.ChunkPos(found);
		for (int dx = -4; dx <= 4; dx++) {
			for (int dz = -4; dz <= 4; dz++) {
				var chunk = world.getChunk(centre.x + dx, centre.z + dz);
				chunks++;
				for (var entity : chunk.getBlockEntities().values()) {
					if (entity instanceof MachineBlockEntity machine) {
						machines++;
						ids.add(machine.blockId() + "@" + machine.getPos().toShortString());
					}
				}
				chunk.getStructureStarts().forEach((structure, structureStart) -> starts.add(
						registry.getId(structure) + " " + structureStart.getBoundingBox()));
			}
		}
		check("D4.b", machines > 0, "generated " + chunks + " chunks around " + found + ": machines=" + machines
				+ " starts=" + starts + " machines=" + ids, failures);
	}

	/**
	 * Every variant through real worldgen, not just {@code buildNow}: block entities in a generating chunk have no
	 * world yet, so anything that touches it (a sign's setText did) crashes chunk generation. Locates the nearest
	 * of each variant and generates every chunk its piece covers.
	 */
	private static void checkWorldgen(ServerWorld world, int[] failures) {
		var registry = world.getRegistryManager().get(net.minecraft.registry.RegistryKeys.STRUCTURE);
		for (var layout : dev.rackcraft.world.structure.DataCenterLayouts.all().values()) {
			var entry = registry.getEntry(net.minecraft.registry.RegistryKey.of(net.minecraft.registry.RegistryKeys.STRUCTURE,
					Rackcraft.id(layout.id())));
			if (entry.isEmpty()) continue;
			boolean campus = layout.id().equals("hyperscale_campus");
			if (campus) {
				// Never within 5000 blocks of spawn: nothing within 312 chunks of the origin.
				var near = world.getChunkManager().getChunkGenerator().locateStructure(world,
						net.minecraft.registry.entry.RegistryEntryList.of(entry.get()), BlockPos.ORIGIN, 312, false);
				// locate can answer past its radius, so check the distance itself.
				boolean far = near == null || Math.hypot(near.getFirst().getX(), near.getFirst().getZ()) >= 5000;
				check("D7.a", far, "nearest campus to spawn: " + (near == null ? "none within range" : near.getFirst()), failures);
			}
			// The campus is far out by design: look for it from 20,000 blocks away.
			BlockPos from = campus ? new BlockPos(20000, 0, 20000) : BlockPos.ORIGIN;
			var found = world.getChunkManager().getChunkGenerator().locateStructure(world,
					net.minecraft.registry.entry.RegistryEntryList.of(entry.get()), from, campus ? 1000 : 100, false);
			if (found == null) {
				check("D6." + layout.id(), true, "skipped: none within range of 0,0 in this seed", failures);
				continue;
			}
			String error = null;
			int machines = 0;
			int racks = 0;
			int chunks = 0;
			int missingEntities = 0;
			String where = "";
			try {
				var start = world.getChunk(found.getFirst().getX() >> 4, found.getFirst().getZ() >> 4,
						net.minecraft.world.chunk.ChunkStatus.STRUCTURE_STARTS).getStructureStart(entry.get().value());
				net.minecraft.util.math.BlockBox box = start != null && start.hasChildren() ? start.getBoundingBox()
						: new net.minecraft.util.math.BlockBox(found.getFirst());
				where = " box=" + box + " pieces=" + (start == null ? "none" : start.getChildren().size());
				for (int cx = box.getMinX() >> 4; cx <= box.getMaxX() >> 4; cx++) {
					for (int cz = box.getMinZ() >> 4; cz <= box.getMaxZ() >> 4; cz++) {
						world.getChunk(cx, cz);
						chunks++;
					}
				}
				// Count machine blocks, and make sure every one got its block entity (and with it, its contents).
				for (BlockPos pos : BlockPos.iterate(box.getMinX(), box.getMinY(), box.getMinZ(), box.getMaxX(), box.getMaxY(), box.getMaxZ())) {
					if (!(world.getBlockState(pos).getBlock() instanceof MachineBlock)) continue;
					machines++;
					if (!(world.getBlockEntity(pos) instanceof MachineBlockEntity machine)) missingEntities++;
					else if (machine.blockId().equals("server_rack") && !machine.modules().isEmpty()) racks++;
				}
			} catch (RuntimeException exception) {
				error = exception.toString();
				Rackcraft.LOGGER.error("Generating {} failed", layout.id(), exception);
			}
			check("D6." + layout.id(), error == null && machines >= 2 && missingEntities == 0 && (!campus || racks >= 200),
					"generated " + chunks + " chunks at " + found.getFirst().toShortString() + ": machines=" + machines + " loadedRacks=" + racks + " missingEntities=" + missingEntities + where
							+ (error == null ? "" : " error=" + error), failures);
		}
	}

	/**
	 * The campus must work out of the box once its cut cables are spliced: exactly five cuts, and afterwards every
	 * rack powered, networked at full bandwidth, cooled, and with a cool intake.
	 */
	private static void checkCampusRuns(ServerWorld world, BlockPos origin, int[] failures) {
		int size = dev.rackcraft.world.structure.DataCenterLayouts.CAMPUS_SIZE;
		List<BlockPos> cuts = new java.util.ArrayList<>();
		for (BlockPos pos : BlockPos.iterate(origin, origin.add(size - 1, 23, size - 1))) {
			var state = world.getBlockState(pos);
			if (state.getBlock() instanceof CableBlock && state.get(CableBlock.CUT)) cuts.add(pos.toImmutable());
		}
		for (int step = 0; step < 3; step++) SimTicker.stepNow(world);
		List<MachineBlockEntity> racks = SimTicker.machines(world).stream().filter(machine -> machine.blockId().equals("server_rack")
				&& machine.getPos().getX() >= origin.getX() && machine.getPos().getX() < origin.getX() + size
				&& machine.getPos().getZ() >= origin.getZ() && machine.getPos().getZ() < origin.getZ() + size).toList();
		long darkBefore = racks.stream().filter(rack -> !rack.rackStatus().mining()).count();
		for (BlockPos cut : cuts) CableBlock.setCut(world, cut, false);
		for (int step = 0; step < 40; step++) SimTicker.stepNow(world);
		java.util.Map<RackStatus, Long> statuses = new java.util.TreeMap<>();
		for (MachineBlockEntity rack : racks) statuses.merge(rack.rackStatus(), 1L, Long::sum);
		java.util.Set<RackStatus> fine = java.util.EnumSet.of(RackStatus.MINING, RackStatus.CRAFTING, RackStatus.GENERATING,
				RackStatus.TRAINING);
		long bad = racks.stream().filter(rack -> !fine.contains(rack.rackStatus())).count();
		double hottest = racks.stream().mapToDouble(MachineBlockEntity::inletCelsius).max().orElse(0);
		double toAir = racks.stream().mapToDouble(MachineBlockEntity::heatToAirKw).max().orElse(0);
		MachineBlockEntity reactor = SimTicker.machines(world).stream().filter(machine -> machine.blockId().equals("modular_reactor")
				&& machine.getPos().isWithinDistance(origin, size * 1.5)).findFirst().orElse(null);
		String reactorInfo = reactor == null ? "none" : reactor.reactorArraySize() + "-cube, " + Math.round(reactor.powerKw()) + " / "
				+ Math.round(reactor.reactorCapacityKw()) + " kW, loop " + Math.round(reactor.loopHeatKw()) + "/"
				+ Math.round(reactor.loopCapacityKw());
		reactorInfo += " pumps=" + SimTicker.machines(world).stream().filter(machine -> machine.blockId().equals("freshwater_pump")
				&& machine.getPos().isWithinDistance(origin, size * 1.5)).map(pump -> pump.pumpStatus() + "/" + pump.pumpUnits()
				+ "@" + world.getBiome(pump.getPos()).getKey().map(key -> key.getValue().getPath()).orElse("?")).toList();
		check("D5.a", cuts.size() == 5 && racks.size() == 200 && darkBefore > 150 && bad == 0 && hottest < 27 && toAir < 1,
				"cuts=" + cuts.size() + " racks=" + racks.size() + " darkBeforeRepair=" + darkBefore + " after=" + statuses
						+ " hottestInlet=" + String.format(java.util.Locale.ROOT, "%.1f", hottest) + " maxToAir=" + toAir
						+ " reactor=" + reactorInfo, failures);
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