package dev.rackcraft;

import dev.rackcraft.block.MachineBlockEntity;
import dev.rackcraft.block.CableBlock;
import dev.rackcraft.block.MachineBlock;
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
		check("S0.a", RcBlocks.BLOCKS.size() == 26 && RcItems.ITEMS.size() == 25,
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
						&& ExchangeCatalog.price(Items.COMMAND_BLOCK) == null && ExchangeCatalog.price(Items.BEDROCK) == null,
				"catalog=" + ExchangeCatalog.prices().size() + " diamondSword=" + ExchangeCatalog.price(Items.DIAMOND_SWORD)
						+ " netheriteSword=" + ExchangeCatalog.price(Items.NETHERITE_SWORD)
						+ " oakPlanks=" + ExchangeCatalog.price(Items.OAK_PLANKS) + " beacon=" + ExchangeCatalog.price(Items.BEACON),
				failures);
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