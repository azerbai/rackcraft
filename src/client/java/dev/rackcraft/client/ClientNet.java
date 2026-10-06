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

	public static void resetBreaker(BlockPos pos) {
		PacketByteBuf buf = PacketByteBufs.create();
		buf.writeBlockPos(pos);
		ClientPlayNetworking.send(RESET_BREAKER, buf);
	}
}