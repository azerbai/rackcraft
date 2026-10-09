package dev.rackcraft.client;

import dev.rackcraft.RcItems;
import dev.rackcraft.world.Jetpacks;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.network.PacketByteBuf;

/** Tells the server which of jump, sneak and sprint the player is holding while a Hydrogen Jetpack is on their back. */
final class JetpackInput {
	private static int last;

	private JetpackInput() {}

	static void register() {
		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			MinecraftClient game = MinecraftClient.getInstance();
			if (game.player == null || !ClientPlayNetworking.canSend(Jetpacks.INPUT)) return;
			boolean wearing = game.player.getEquippedStack(EquipmentSlot.CHEST).isOf(RcItems.ITEMS.get("hydrogen_jetpack"));
			int flags = 0;
			if (wearing && game.currentScreen == null) {
				if (game.options.jumpKey.isPressed()) flags |= Jetpacks.JUMP;
				if (game.options.sneakKey.isPressed()) flags |= Jetpacks.SNEAK;
				if (game.options.sprintKey.isPressed()) flags |= Jetpacks.SPRINT;
			}
			if (flags == last) return;
			last = flags;
			PacketByteBuf buf = PacketByteBufs.create();
			buf.writeByte(flags);
			ClientPlayNetworking.send(Jetpacks.INPUT, buf);
		});
	}
}
