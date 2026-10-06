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
		check("S0.a", RcBlocks.BLOCKS.size() == 39 && RcItems.ITEMS.size() == 42,
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
		checkCompute(world, failures);
		checkStructures(world, failures);
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

		// Water: a GPU blade stops without a pump; a pump beside a fresh pool runs it.
		rack.setStack(7, ItemStack.EMPTY);
		rack.setStack(6, new ItemStack(RcItems.ITEMS.get("gpu_blade")));
		for (int step = 0; step < 3; step++) SimTicker.stepNow(world);
		boolean dry = rack.rackStatus() == RackStatus.NEEDS_WATER;
		// The pump sits on the rack (sharing its power and coolant networks) at the edge of a 7x7 pool.
		BlockPos pump = rackPos.up();
		for (BlockPos pos : BlockPos.iterate(rackPos.add(1, 0, -4), rackPos.add(8, 1, 4))) {
			if (!pos.equals(router)) world.setBlockState(pos, Blocks.STONE.getDefaultState());
		}
		for (BlockPos pos : BlockPos.iterate(rackPos.add(1, 1, -3), rackPos.add(7, 1, 3))) {
			world.setBlockState(pos, Blocks.WATER.getDefaultState());
		}
		world.setBlockState(pump, RcBlocks.get("freshwater_pump").getDefaultState());
		for (int step = 0; step < 3; step++) SimTicker.stepNow(world);
		MachineBlockEntity pumpEntity = machine(world, pump);
		boolean salt = pumpEntity.pumpStatus() == dev.rackcraft.world.FreshwaterCooling.PumpStatus.SALT_WATER.ordinal();
		boolean wet = rack.rackStatus() != RackStatus.NEEDS_WATER;
		check("C2.a", dry && (salt || wet && pumpEntity.pumpUnits() > 0), "dry=" + dry + " pump=" + pumpEntity.pumpStatus()
				+ " units=" + pumpEntity.pumpUnits() + " sources=" + pumpEntity.pumpSources() + " rackNow=" + rack.rackStatus()
				+ (salt ? " (test site is salt water: the ban works)" : ""), failures);
		world.setBlockState(pump, Blocks.AIR.getDefaultState());
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

		// Exhaust fans pollute heavily.
		var air = dev.rackcraft.world.AirQuality.get(world);
		BlockPos fan = origin.add(20, 0, 0);
		air.set(fan, 0);
		world.setBlockState(fan, RcBlocks.get("exhaust_fan").getDefaultState());
		world.setBlockState(fan.east(), RcBlocks.get("creative_power").getDefaultState());
		for (int step = 0; step < 20; step++) SimTicker.stepNow(world);
		float smog = air.smogAt(fan);
		check("C6.a", smog > 5, "smog=" + smog, failures);
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
		rack.setStack(0, new ItemStack(RcItems.ITEMS.get("gpu_blade")));
		rack.setStack(1, new ItemStack(RcItems.ITEMS.get("gpu_blade")));
		BlockPos pump = rackPos.up();
		for (BlockPos pos : BlockPos.iterate(rackPos.add(1, 0, -4), rackPos.add(8, 1, 4))) world.setBlockState(pos, Blocks.STONE.getDefaultState());
		for (BlockPos pos : BlockPos.iterate(rackPos.add(1, 1, -3), rackPos.add(7, 1, 3))) world.setBlockState(pos, Blocks.WATER.getDefaultState());
		world.setBlockState(pump, RcBlocks.get("freshwater_pump").getDefaultState());
		MachineBlockEntity pumpEntity = machine(world, pump);
		pumpEntity.addWaterDrawn(-1e9);
		for (int step = 0; step < 3; step++) SimTicker.stepNow(world);
		boolean running = rack.rackStatus() != RackStatus.NEEDS_WATER && pumpEntity.pumpUnits() >= 4;
		int before = pumpEntity.pumpSources();
		// Fast-forward the draw: the next step takes one source block off the shore.
		pumpEntity.addWaterDrawn(dev.rackcraft.world.FreshwaterCooling.UNIT_SECONDS_PER_BLOCK);
		for (int step = 0; step < 25; step++) SimTicker.stepNow(world);
		int after = pumpEntity.pumpSources();
		check("C2.b", running && after < before, "biome=" + world.getBiome(site).getKey().map(key -> key.getValue().toString()).orElse("?")
				+ " status=" + rack.rackStatus() + " pump=" + pumpEntity.pumpStatus() + " units=" + pumpEntity.pumpUnits()
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
		for (var layout : layouts.values()) {
			BlockPos origin = new BlockPos(4096 + index * 192, 120, 4096);
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
				new net.minecraft.util.math.Box(new BlockPos(4096, 120, 4096)).expand(2400, 64, 200),
				villager -> villager.getCommandTags().contains(dev.rackcraft.compute.TrainingStations.SHACKLED_TAG));
		var kids = world.getEntitiesByClass(net.minecraft.entity.passive.VillagerEntity.class,
				new net.minecraft.util.math.Box(new BlockPos(4096, 120, 4096)).expand(2400, 64, 200),
				net.minecraft.entity.passive.VillagerEntity::isBaby);
		check("D3.a", scribes.size() >= 11 && kids.size() >= 10, "shackled librarians in the AI lab, content mill and campus="
				+ scribes.size() + ", kids=" + kids.size(), failures);
		world.getEntitiesByClass(net.minecraft.entity.passive.VillagerEntity.class,
				new net.minecraft.util.math.Box(new BlockPos(4096, 120, 4096)).expand(2400, 64, 200), entity -> true)
				.forEach(net.minecraft.entity.Entity::discard);
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