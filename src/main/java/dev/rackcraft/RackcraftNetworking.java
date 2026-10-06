package dev.rackcraft;

import dev.rackcraft.block.MachineBlockEntity;
import dev.rackcraft.screen.MachineScreenHandler;
import dev.rackcraft.world.FacilityManager;
import dev.rackcraft.sim.ThermalGrid;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.registry.Registries;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;

public final class RackcraftNetworking {
	private static final Identifier SET_LOAD_LIMIT = Rackcraft.id("set_load_limit");
	private static final Identifier SET_CONTRACT = Rackcraft.id("set_contract");
	private static final Identifier BUY_ITEM = Rackcraft.id("buy_item");
	private static final Identifier RESET_BREAKER = Rackcraft.id("reset_breaker");
	private static final Identifier HEAT_CELLS = Rackcraft.id("heat_cells");
	private RackcraftNetworking() {}

	public static void registerServer() {
		ServerPlayNetworking.registerGlobalReceiver(SET_LOAD_LIMIT, (server, player, handler, buf, responseSender) -> {
			BlockPos pos = buf.readBlockPos();
			int limit = buf.readInt();
			server.execute(() -> {
				MachineBlockEntity machine = validatedMachine(player, pos);
				if (machine != null && machine.blockId().equals("server_rack")) machine.setLoadLimitPercent(limit);
			});
		});
		ServerPlayNetworking.registerGlobalReceiver(SET_CONTRACT, (server, player, handler, buf, responseSender) -> {
			BlockPos pos = buf.readBlockPos();
			String contract = buf.readString(16);
			server.execute(() -> {
				MachineBlockEntity machine = validatedMachine(player, pos);
				if (machine == null || !machine.blockId().equals("facility_controller")) return;
				if (contract.equals("bronze") || contract.equals("silver") || contract.equals("gold")) {
					FacilityManager.get(player.getServerWorld()).setContract(contract);
				}
			});
		});
		ServerPlayNetworking.registerGlobalReceiver(BUY_ITEM, (server, player, handler, buf, responseSender) -> {
			BlockPos pos = buf.readBlockPos();
			String itemId = buf.readString(32);
			int times = Math.max(1, Math.min(16, buf.readVarInt()));
			server.execute(() -> {
				for (int purchase = 0; purchase < times; purchase++) {
					if (!buy(player, pos, itemId)) break;
				}
			});
		});
		ServerPlayNetworking.registerGlobalReceiver(RESET_BREAKER, (server, player, handler, buf, responseSender) -> {
			BlockPos pos = buf.readBlockPos();
			server.execute(() -> {
				MachineBlockEntity machine = validatedMachine(player, pos);
				if (machine != null && machine.blockId().equals("pdu")) machine.setTripped(false);
			});
		});
	}

	public static void sendHeatCells(ServerWorld world, ThermalGrid grid) {
		for (ServerPlayerEntity player : world.getPlayers()) {
			if (!player.getMainHandStack().isOf(RcItems.ITEMS.get("thermal_scanner"))
					&& !player.getOffHandStack().isOf(RcItems.ITEMS.get("thermal_scanner"))) continue;
			BlockPos origin = player.getBlockPos();
			List<Map.Entry<ThermalGrid.CellPos, Double>> visible = new ArrayList<>();
			for (Map.Entry<ThermalGrid.CellPos, Double> entry : grid.snapshot().entrySet()) {
				ThermalGrid.CellPos cell = entry.getKey();
				BlockPos pos = new BlockPos(cell.x(), cell.y(), cell.z());
				if (origin.getSquaredDistance(pos) <= 576
						&& entry.getValue() >= grid.ambientCelsius() + 2) visible.add(entry);
			}
			visible.sort(java.util.Comparator.comparingDouble(
					(Map.Entry<ThermalGrid.CellPos, Double> entry) -> origin.getSquaredDistance(
							new BlockPos(entry.getKey().x(), entry.getKey().y(), entry.getKey().z()))));
			int limit = Math.min(2000, Math.max(0, RackcraftConfig.values.heatOverlay.maxCells));
			int count = Math.min(limit, visible.size());
			net.minecraft.network.PacketByteBuf buf = net.fabricmc.fabric.api.networking.v1.PacketByteBufs.create();
			buf.writeVarInt(count);
			for (int index = 0; index < count; index++) {
				Map.Entry<ThermalGrid.CellPos, Double> entry = visible.get(index);
				ThermalGrid.CellPos cell = entry.getKey();
				buf.writeLong(new BlockPos(cell.x(), cell.y(), cell.z()).asLong());
				buf.writeShort((int) Math.round((entry.getValue() + 273.15) * 10));
			}
			ServerPlayNetworking.send(player, HEAT_CELLS, buf);
		}
	}

	private static boolean buy(ServerPlayerEntity player, BlockPos pos, String offerId) {
		MachineBlockEntity machine = validatedMachine(player, pos);
		ExchangeOffers.Offer offer = ExchangeOffers.all().get(offerId);
		if (machine == null || offer == null) return false;
		boolean sold = machine.blockId().equals("crypto_exchange")
				|| machine.blockId().equals("facility_controller") && ExchangeOffers.CONTROLLER_OFFERS.contains(offerId);
		if (!sold) return false;
		FacilityManager facility = FacilityManager.get(player.getServerWorld());
		if (!facility.spendCredits(offer.price())) return false;
		ItemStack stack = offer.stack();
		if (!player.getInventory().insertStack(stack)) player.dropItem(stack, false);
		return true;
	}

	private static MachineBlockEntity validatedMachine(ServerPlayerEntity player, BlockPos pos) {
		if (player.squaredDistanceTo(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5) > 64) return null;
		return player.getServerWorld().getBlockEntity(pos) instanceof MachineBlockEntity machine ? machine : null;
	}

}