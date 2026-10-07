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
		check("S0.a", RcBlocks.BLOCKS.size() == 50 && RcItems.ITEMS.size() == 47,
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
		checkPrices(failures);
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
				{"cask_sealer", "spent_fuel", "8", "depleted_uranium", "8", "waste_cask"}};
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
	}

	private static net.minecraft.item.Item item(String id) {
		net.minecraft.item.Item item = RcItems.ITEMS.get(id);
		return item != null ? item : RcBlocks.get(id).asItem();
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