package dev.rackcraft.world;

import dev.rackcraft.RcEffects;
import dev.rackcraft.RcItems;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;

/**
 * Spent Fuel is radioactive: carrying any gives Radiation Sickness, level II from 8 rods and III from 32. Sealed
 * Waste Casks are safe, and so is Spent Fuel kept in machines, chests or storage.
 */
public final class Radiation {
	private Radiation() {}

	public static void step(ServerWorld world) {
		for (ServerPlayerEntity player : world.getPlayers()) expose(player);
	}

	/** Checks one player's pockets and doses them. Returns the rods they carry. */
	public static int expose(PlayerEntity player) {
		if (player.isCreative() || player.isSpectator()) return 0;
		int rods = 0;
		for (int slot = 0; slot < player.getInventory().size(); slot++) {
			ItemStack stack = player.getInventory().getStack(slot);
			if (stack.isOf(RcItems.ITEMS.get("spent_fuel"))) rods += stack.getCount();
		}
		if (rods > 0) {
			int level = rods >= 32 ? 2 : rods >= 8 ? 1 : 0;
			player.addStatusEffect(new StatusEffectInstance(RcEffects.RADIATION, 100, level, true, true));
		}
		return rods;
	}
}
