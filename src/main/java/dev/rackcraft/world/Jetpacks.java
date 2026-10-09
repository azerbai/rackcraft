package dev.rackcraft.world;

import dev.rackcraft.Rackcraft;
import dev.rackcraft.RcItems;
import dev.rackcraft.compute.Research;
import dev.rackcraft.compute.ResearchLab;
import dev.rackcraft.mixin.ServerPlayNetworkHandlerAccessor;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Vec3d;

/**
 * The Hydrogen Jetpack. The client sends the player's jump, sneak and sprint keys (the server can't see them), and
 * every tick the server pushes the player: jump climbs, sneaking in the air hovers, sprinting adds a forward boost.
 * The tank holds {@link #CANISTER} half-ticks per canister (thirty seconds of thrust), refilled from the Hydrogen
 * Canisters the player carries or a Wireless Terminal's storage; hovering burns half as fast. With no gas left in a
 * long fall the pack vents what's left once every five minutes for a slow fall, so running dry is a scare, not a death.
 */
public final class Jetpacks {
	public static final Identifier INPUT = Rackcraft.id("jetpack_input");
	public static final String GATE = "personal_propulsion";
	public static final int JUMP = 1;
	public static final int SNEAK = 2;
	public static final int SPRINT = 4;
	/** Fuel is counted in half-ticks: a tick of thrust costs 2, a tick of hover 1. */
	public static final int CANISTER = 30 * 20 * 2;
	public static final long VENT_COOLDOWN = 20 * 60 * 5;
	private static final String FUEL = "Fuel";
	private static final String VENTED = "VentedAt";
	private static final Map<UUID, Integer> INPUTS = new HashMap<>();

	private Jetpacks() {}

	public static void register() {
		ServerPlayNetworking.registerGlobalReceiver(INPUT, (server, player, handler, buf, responseSender) -> {
			int flags = buf.readByte();
			server.execute(() -> INPUTS.put(player.getUuid(), flags));
		});
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
				Integer flags = INPUTS.get(player.getUuid());
				if (flags != null || isWearing(player)) tick(player, flags == null ? 0 : flags);
			}
		});
		net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> INPUTS.remove(handler.player.getUuid()));
	}

	public static boolean isWearing(ServerPlayerEntity player) {
		return player.getEquippedStack(EquipmentSlot.CHEST).isOf(RcItems.ITEMS.get("hydrogen_jetpack"));
	}

	public static int fuelSeconds(ItemStack stack) {
		return stack.getNbt() == null ? 0 : stack.getNbt().getInt(FUEL) / 40;
	}

	/** One tick of the jetpack for a player holding these keys. */
	public static void tick(ServerPlayerEntity player, int flags) {
		ItemStack pack = player.getEquippedStack(EquipmentSlot.CHEST);
		if (!pack.isOf(RcItems.ITEMS.get("hydrogen_jetpack"))) {
			INPUTS.remove(player.getUuid());
			return;
		}
		boolean airborne = !player.isOnGround() && !player.isTouchingWater() && !player.hasVehicle() && !player.getAbilities().flying;
		boolean climb = (flags & JUMP) != 0;
		boolean hover = !climb && (flags & SNEAK) != 0 && airborne;
		NbtCompound nbt = pack.getOrCreateNbt();
		int fuel = nbt.getInt(FUEL);
		if (!climb && !hover) {
			if (airborne && fuel <= 0) ventIfFalling(player, pack);
			return;
		}
		if (player.getServerWorld() != null && !ResearchLab.get(player.getServerWorld()).done(GATE)) {
			if (player.age % 40 == 0) player.sendMessage(Text.literal("Inert until " + Research.get(GATE).name() + " is researched").formatted(Formatting.RED), true);
			return;
		}
		int cost = climb ? 2 : 1;
		if (fuel < cost && BuildStock.of(player).take(RcItems.ITEMS.get("hydrogen_canister"), 1) >= 1) fuel += CANISTER;
		if (fuel < cost) {
			nbt.putInt(FUEL, Math.max(0, fuel));
			if (airborne) ventIfFalling(player, pack);
			else if (player.age % 20 == 0) player.sendMessage(Text.literal("Jetpack out of hydrogen").formatted(Formatting.RED), true);
			return;
		}
		fuel -= cost;
		nbt.putInt(FUEL, fuel);
		Vec3d velocity = player.getVelocity();
		boolean boost = (flags & SPRINT) != 0;
		if (climb) {
			double climbSpeed = boost ? 0.75 : 0.55;
			double lift = Math.min(velocity.y + 0.14, climbSpeed);
			Vec3d push = boost ? player.getRotationVector().multiply(1, 0, 1).normalize().multiply(0.05) : Vec3d.ZERO;
			player.setVelocity(velocity.x + push.x, lift, velocity.z + push.z);
		} else {
			// Hover: cancel the fall, and let go of any climb, keeping what drift the player has.
			player.setVelocity(velocity.x * 0.98, velocity.y > 0 ? velocity.y * 0.4 : 0, velocity.z * 0.98);
		}
		player.velocityModified = true;
		player.fallDistance = 0;
		if (player.networkHandler != null) ((ServerPlayNetworkHandlerAccessor) player.networkHandler).rackcraft$setFloatingTicks(0);
		if (player.getServerWorld() != null) {
			var world = player.getServerWorld();
			world.spawnParticles(ParticleTypes.FLAME, player.getX(), player.getY() + 0.8, player.getZ(), 1, 0.1, 0.05, 0.1, 0.02);
			world.spawnParticles(ParticleTypes.SMOKE, player.getX(), player.getY() + 0.6, player.getZ(), 1, 0.1, 0.05, 0.1, 0.01);
			if (player.age % 6 == 0) world.playSound(null, player.getBlockPos(), SoundEvents.ENTITY_BLAZE_SHOOT, SoundCategory.PLAYERS, 0.15f, hover ? 1.6f : 1.2f);
		}
		if (fuel < 200 && player.age % 20 == 0 && BuildStock.of(player).count(RcItems.ITEMS.get("hydrogen_canister")) == 0) {
			player.sendMessage(Text.literal("Jetpack fuel low").formatted(Formatting.GOLD), true);
		}
	}

	/** Out of gas in a long fall: vent what's left for a slow fall, once every five minutes. */
	private static void ventIfFalling(ServerPlayerEntity player, ItemStack pack) {
		if (player.fallDistance < 4 || player.getVelocity().y > -0.3) return;
		NbtCompound nbt = pack.getOrCreateNbt();
		long now = player.getWorld().getTime();
		if (nbt.contains(VENT) && now - nbt.getLong(VENT) < VENT_COOLDOWN) return;
		nbt.putLong(VENT, now);
		player.addStatusEffect(new StatusEffectInstance(StatusEffects.SLOW_FALLING, 200, 0, false, false, true));
		player.fallDistance = 0;
		player.sendMessage(Text.literal("Jetpack vented its last gas to slow your fall").formatted(Formatting.GOLD), true);
	}

	private static final String VENT = VENTED;
}
