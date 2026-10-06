package dev.rackcraft.client;

import dev.rackcraft.Rackcraft;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;

public final class ClientNet {
	private static final Identifier SET_LOAD_LIMIT = Rackcraft.id("set_load_limit");
	private static final Identifier SET_CONTRACT = Rackcraft.id("set_contract");
	private static final Identifier BUY_ITEM = Rackcraft.id("buy_item");
	private static final Identifier RESET_BREAKER = Rackcraft.id("reset_breaker");
	private static final Identifier SET_CREATIVE = Rackcraft.id("set_creative");

	private ClientNet() {}

	public static void setLoadLimit(BlockPos pos, int percent) {
		PacketByteBuf buf = PacketByteBufs.create();
		buf.writeBlockPos(pos);
		buf.writeInt(percent);
		ClientPlayNetworking.send(SET_LOAD_LIMIT, buf);
	}

	public static void setContract(BlockPos pos, String contract) {
		PacketByteBuf buf = PacketByteBufs.create();
		buf.writeBlockPos(pos);
		buf.writeString(contract, 16);
		ClientPlayNetworking.send(SET_CONTRACT, buf);
	}

	public static void buyItem(BlockPos pos, String itemId) {
		buyItem(pos, itemId, 1);
	}

	public static void buyItem(BlockPos pos, String itemId, int times) {
		PacketByteBuf buf = PacketByteBufs.create();
		buf.writeBlockPos(pos);
		buf.writeString(itemId, 96);
		buf.writeVarInt(times);
		ClientPlayNetworking.send(BUY_ITEM, buf);
	}

	public static void setCreative(BlockPos pos, String key, double value) {
		PacketByteBuf buf = PacketByteBufs.create();
		buf.writeBlockPos(pos);
		buf.writeString(key, 32);
		buf.writeDouble(value);
		ClientPlayNetworking.send(SET_CREATIVE, buf);
	}

	public static void upgradeTransmitter(BlockPos pos, boolean withRackCoin) {
		PacketByteBuf buf = PacketByteBufs.create();
		buf.writeBlockPos(pos);
		buf.writeBoolean(withRackCoin);
		ClientPlayNetworking.send(Rackcraft.id("upgrade_transmitter"), buf);
	}

	private static PacketByteBuf terminal(int syncId, dev.rackcraft.storage.TerminalScreenHandler.Action action) {
		PacketByteBuf buf = PacketByteBufs.create();
		buf.writeVarInt(syncId);
		buf.writeVarInt(action.ordinal());
		return buf;
	}

	public static void terminalAction(int syncId, dev.rackcraft.storage.TerminalScreenHandler.Action action) {
		ClientPlayNetworking.send(dev.rackcraft.storage.TerminalScreenHandler.ACTION, terminal(syncId, action));
	}

	public static void terminalClick(int syncId, dev.rackcraft.storage.ItemKey key, int button, boolean shift) {
		PacketByteBuf buf = terminal(syncId, dev.rackcraft.storage.TerminalScreenHandler.Action.CLICK);
		buf.writeBoolean(key != null);
		if (key != null) key.write(buf);
		buf.writeVarInt(button);
		buf.writeBoolean(shift);
		ClientPlayNetworking.send(dev.rackcraft.storage.TerminalScreenHandler.ACTION, buf);
	}

	public static void terminalCraft(int syncId, dev.rackcraft.storage.ItemKey key, long amount) {
		PacketByteBuf buf = terminal(syncId, dev.rackcraft.storage.TerminalScreenHandler.Action.CRAFT);
		key.write(buf);
		buf.writeVarLong(amount);
		ClientPlayNetworking.send(dev.rackcraft.storage.TerminalScreenHandler.ACTION, buf);
	}

	public static void opsAction(int syncId, dev.rackcraft.compute.OpsScreenHandler.Action action, int id, long cluster) {
		PacketByteBuf buf = PacketByteBufs.create();
		buf.writeVarInt(syncId);
		buf.writeVarInt(action.ordinal());
		buf.writeVarInt(id);
		buf.writeLong(cluster);
		ClientPlayNetworking.send(dev.rackcraft.compute.OpsScreenHandler.ACTION, buf);
	}

	public static void resetBreaker(BlockPos pos) {
		PacketByteBuf buf = PacketByteBufs.create();
		buf.writeBlockPos(pos);
		ClientPlayNetworking.send(RESET_BREAKER, buf);
	}
}