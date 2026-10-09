package dev.rackcraft.world;

import dev.rackcraft.RackcraftConfig;
import dev.rackcraft.RcItems;
import dev.rackcraft.block.MachineBlockEntity;
import dev.rackcraft.compute.Research;
import dev.rackcraft.compute.ResearchLab;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.PersistentState;
import net.minecraft.world.World;

/**
 * Quantum Teleport Pads. Two pads entangled with a Linked Shard (a Dimensional Shard for a pair in different
 * dimensions) send whoever stands on one to the other after a two second charge. Each pad needs a Quantum Annealer in its
 * slot and a Cryostat touching it, and a jump burns a Hydrogen Canister from the source's Cryostat and a burst of power
 * from the source's network: {@code teleport.mwsPer100Blocks} megawatt-seconds per 100 blocks, twenty times that
 * between dimensions. The destination only needs its Annealer and a stocked Cryostat. Pairs are saved with the world.
 */
public final class TeleportPads extends PersistentState {
	public static final String GATE = "quantum_entanglement";
	private static final String STATE_KEY = "rackcraft_teleport_pads";
	public static final int CHARGE_TICKS = 40;
	public static final int CROSS_DIMENSION_FACTOR = 20;
	/** Port readings (MachineBlockEntity.siteReading) for a pad's screen. */
	public static final int R_STATE = 0;
	public static final int R_DISTANCE = 1;
	public static final int R_KW = 2;
	public static final int R_CROSS = 3;
	public static final int R_PARTNER_X = 4;
	public static final int R_PARTNER_Y = 5;
	public static final int R_PARTNER_Z = 6;

	/** What a pad is doing. */
	public enum State { LOCKED, UNLINKED, NO_ANNEALER, NO_CRYOSTAT, NO_POWER, PARTNER_GONE, READY }

	private final Map<String, String> partners = new HashMap<>();
	private final Map<String, Boolean> dimensional = new HashMap<>();

	private record Charge(String pad, long lastTouch, int ticks) {}

	private static final Map<UUID, Charge> CHARGES = new HashMap<>();
	/** The pad a player just arrived on, so they don't bounce back the moment they land. */
	private static final Map<UUID, String> ARRIVED = new HashMap<>();
	private static final Map<String, Long> DEMAND_UNTIL = new HashMap<>();
	private static final Map<String, Double> DEMAND_KW = new HashMap<>();

	public static TeleportPads get(MinecraftServer server) {
		return server.getOverworld().getPersistentStateManager().getOrCreate(TeleportPads::fromNbt, TeleportPads::new, STATE_KEY);
	}

	public static String key(World world, BlockPos pos) {
		return world.getRegistryKey().getValue() + "|" + pos.asLong();
	}

	public static BlockPos posOf(String key) { return BlockPos.fromLong(Long.parseLong(key.substring(key.indexOf('|') + 1))); }

	public static Identifier dimensionOf(String key) { return new Identifier(key.substring(0, key.indexOf('|'))); }

	public static void register() {
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			if (CHARGES.isEmpty() && ARRIVED.isEmpty()) return;
			long now = server.getOverworld().getTime();
			// Whoever stopped standing on a pad has to start over, and may use the one they landed on again.
			CHARGES.values().removeIf(charge -> now - charge.lastTouch() > 2);
			ARRIVED.entrySet().removeIf(entry -> {
				ServerPlayerEntity player = server.getPlayerManager().getPlayer(entry.getKey());
				Charge touching = CHARGES.get(entry.getKey());
				return player == null || touching == null || !touching.pad().equals(entry.getValue()) || now - touching.lastTouch() > 1;
			});
		});
	}

	// ------------------------------------------------------------------ pairs

	public String partner(String key) { return partners.get(key); }

	public boolean linked(String key) { return partners.containsKey(key); }

	public boolean crossCapable(String key) { return dimensional.getOrDefault(key, false); }

	public void link(String a, String b, boolean cross) {
		partners.put(a, b);
		partners.put(b, a);
		dimensional.put(a, cross);
		dimensional.put(b, cross);
		markDirty();
	}

	/** Cuts a pad's pair. Returns the partner's key, or null. */
	public String unlink(String key) {
		String other = partners.remove(key);
		dimensional.remove(key);
		if (other != null) {
			partners.remove(other);
			dimensional.remove(other);
			markDirty();
		}
		return other;
	}

	public void reset() {
		partners.clear();
		dimensional.clear();
		CHARGES.clear();
		ARRIVED.clear();
		DEMAND_UNTIL.clear();
		DEMAND_KW.clear();
		markDirty();
	}

	public static TeleportPads fromNbt(NbtCompound nbt) {
		TeleportPads pads = new TeleportPads();
		NbtList list = nbt.getList("Pairs", NbtElement.COMPOUND_TYPE);
		for (int index = 0; index < list.size(); index++) {
			NbtCompound entry = list.getCompound(index);
			pads.partners.put(entry.getString("A"), entry.getString("B"));
			pads.dimensional.put(entry.getString("A"), entry.getBoolean("Cross"));
		}
		return pads;
	}

	@Override
	public NbtCompound writeNbt(NbtCompound nbt) {
		NbtList list = new NbtList();
		partners.forEach((a, b) -> {
			NbtCompound entry = new NbtCompound();
			entry.putString("A", a);
			entry.putString("B", b);
			entry.putBoolean("Cross", dimensional.getOrDefault(a, false));
			list.add(entry);
		});
		nbt.put("Pairs", list);
		return nbt;
	}

	// ------------------------------------------------------------------ what a jump needs

	/** The power a jump to this distance needs, in kW, spread over the charge-up. */
	public static double jumpKw(double distance, boolean cross) {
		double megawattSeconds = Math.max(distance, 100) / 100.0 * RackcraftConfig.values.building.teleportMwsPer100Blocks * (cross ? CROSS_DIMENSION_FACTOR : 1);
		return megawattSeconds * 1000 / (CHARGE_TICKS / 20.0);
	}

	/** Whether pads work in this dimension: research is per dimension, and a pad in the Nether can't wait for a lab there. */
	public static boolean researched(ServerWorld world) {
		return ResearchLab.get(world).done(GATE) || ResearchLab.get(world.getServer().getOverworld()).done(GATE);
	}

	public static int canisters(boolean cross) {
		return RackcraftConfig.values.building.teleportCanisters * (cross ? 4 : 1);
	}

	/** A Cryostat touching the pad (powered and with enough canisters if {@code needCold}), or null. */
	private static MachineBlockEntity cryostat(ServerWorld world, BlockPos pos, int canisters, boolean powered) {
		for (Direction side : Direction.values()) {
			if (world.getBlockEntity(pos.offset(side)) instanceof MachineBlockEntity machine && machine.blockId().equals("cryostat")
					&& machine.getStack(0).getCount() >= canisters && (!powered || machine.powerSatisfaction() >= 0.5)) return machine;
		}
		return null;
	}

	private static boolean airAbove(ServerWorld world, BlockPos pad) {
		return world.getBlockState(pad.up()).getCollisionShape(world, pad.up()).isEmpty()
				&& world.getBlockState(pad.up(2)).getCollisionShape(world, pad.up(2)).isEmpty();
	}

	public static double distance(MinecraftServer server, String a, String b) {
		BlockPos from = posOf(a);
		BlockPos to = posOf(b);
		return Math.sqrt(from.getSquaredDistance(to));
	}

	/**
	 * The jump from one pad: checks everything and, if it all holds, burns the power's worth of hydrogen and sends the
	 * player. Returns what is wrong, or null after a jump.
	 */
	public static String attempt(ServerWorld world, BlockPos padPos, ServerPlayerEntity player) {
		MinecraftServer server = world.getServer();
		if (!researched(world)) return "Inert until " + Research.get(GATE).name() + " is researched";
		if (!(world.getBlockEntity(padPos) instanceof MachineBlockEntity pad)) return "No pad here";
		TeleportPads pairs = get(server);
		String here = key(world, padPos);
		String there = pairs.partner(here);
		if (there == null) return "Not entangled with another pad: use a Linked Shard on this and another pad";
		if (pad.getStack(0).isEmpty()) return "Needs a Quantum Annealer in the pad's slot";
		boolean cross = !dimensionOf(there).equals(world.getRegistryKey().getValue());
		int canisters = canisters(cross);
		MachineBlockEntity coldSource = cryostat(world, padPos, canisters, true);
		if (coldSource == null) return "Needs a cold Cryostat touching the pad, with " + canisters + " Hydrogen Canister" + (canisters == 1 ? "" : "s");
		ServerWorld destWorld = server.getWorld(RegistryKey.of(RegistryKeys.WORLD, dimensionOf(there)));
		if (destWorld == null) return "The other pad's dimension isn't there";
		BlockPos destPos = posOf(there);
		// Load the destination, then check it is still a pad with its annealer, a stocked Cryostat and room to stand.
		destWorld.getChunk(destPos);
		if (!(destWorld.getBlockEntity(destPos) instanceof MachineBlockEntity destPad) || !destPad.blockId().equals("teleport_pad")) {
			pairs.unlink(here);
			return "The other pad is gone";
		}
		if (destPad.getStack(0).isEmpty()) return "The other pad has no Quantum Annealer";
		if (cryostat(destWorld, destPos, 1, false) == null) return "The other pad's Cryostat is empty or missing";
		if (!airAbove(destWorld, destPos)) return "Something is in the way above the other pad";
		double distance = cross ? Math.max(100, Math.sqrt(padPos.getSquaredDistance(destPos))) : distance(server, here, there);
		double kw = jumpKw(distance, cross);
		if (pad.powerSatisfaction() < 0.9) return String.format(Locale.ROOT, "Not enough power: the jump needs %,.0f kW for two seconds", kw);
		coldSource.getStack(0).decrement(canisters);
		coldSource.markDirty();
		world.spawnParticles(ParticleTypes.PORTAL, player.getX(), player.getY() + 1, player.getZ(), 40, 0.4, 0.8, 0.4, 0.3);
		world.playSound(null, padPos, SoundEvents.ENTITY_ENDERMAN_TELEPORT, SoundCategory.BLOCKS, 0.8f, 1.2f);
		player.teleport(destWorld, destPos.getX() + 0.5, destPos.getY() + 1.0, destPos.getZ() + 0.5, player.getYaw(), player.getPitch());
		player.fallDistance = 0;
		destWorld.spawnParticles(ParticleTypes.PORTAL, destPos.getX() + 0.5, destPos.getY() + 1.5, destPos.getZ() + 0.5, 40, 0.4, 0.8, 0.4, 0.3);
		destWorld.playSound(null, destPos, SoundEvents.ENTITY_ENDERMAN_TELEPORT, SoundCategory.BLOCKS, 0.8f, 0.9f);
		ARRIVED.put(player.getUuid(), there);
		CHARGES.remove(player.getUuid());
		return null;
	}

	/** Called every tick an entity stands on a pad: counts the charge up and sends the player when it is full. */
	public static void stand(ServerWorld world, BlockPos padPos, ServerPlayerEntity player) {
		String here = key(world, padPos);
		if (here.equals(ARRIVED.get(player.getUuid()))) {
			CHARGES.put(player.getUuid(), new Charge(here, world.getTime(), 0));
			return;
		}
		Charge charge = CHARGES.get(player.getUuid());
		int ticks = charge != null && charge.pad().equals(here) ? charge.ticks() + 1 : 1;
		CHARGES.put(player.getUuid(), new Charge(here, world.getTime(), ticks));
		TeleportPads pairs = get(world.getServer());
		String there = pairs.partner(here);
		if (ticks == 1) {
			if (there != null && researched(world)) {
				boolean cross = !dimensionOf(there).equals(world.getRegistryKey().getValue());
				double distance = cross ? Math.max(100, Math.sqrt(padPos.getSquaredDistance(posOf(there)))) : distance(world.getServer(), here, there);
				// The pad asks its network for the jump's power for the length of the charge, so the grid can answer.
				DEMAND_KW.put(here, jumpKw(distance, cross));
				DEMAND_UNTIL.put(here, world.getTime() + CHARGE_TICKS + 20);
			}
			world.playSound(null, padPos, SoundEvents.BLOCK_BEACON_ACTIVATE, SoundCategory.BLOCKS, 0.6f, 1.6f);
		}
		if (ticks % 4 == 0) world.spawnParticles(ParticleTypes.REVERSE_PORTAL, padPos.getX() + 0.5, padPos.getY() + 1.0, padPos.getZ() + 0.5, 6, 0.3, 0.5, 0.3, 0.1);
		if (ticks < CHARGE_TICKS) return;
		String problem = attempt(world, padPos, player);
		CHARGES.put(player.getUuid(), new Charge(here, world.getTime(), 0));
		if (problem != null) player.sendMessage(Text.literal(problem).formatted(Formatting.RED), true);
	}

	// ------------------------------------------------------------------ the sim's side

	/** What a pad draws: a little always, and the jump's power while someone is charging. */
	public static double demandKw(ServerWorld world, MachineBlockEntity pad) {
		String key = key(world, pad.getPos());
		Long until = DEMAND_UNTIL.get(key);
		double charging = until != null && until >= world.getTime() ? DEMAND_KW.getOrDefault(key, 0.0) : 0;
		return 1 + charging;
	}

	/** Keeps every pad's readings current, once a sim step. */
	public static void step(ServerWorld world, List<MachineBlockEntity> machines) {
		boolean researched = researched(world);
		TeleportPads pairs = null;
		for (MachineBlockEntity pad : machines) {
			if (!pad.blockId().equals("teleport_pad")) continue;
			if (pairs == null) pairs = get(world.getServer());
			String here = key(world, pad.getPos());
			String there = pairs.partner(here);
			State state;
			double distance = 0;
			boolean cross = false;
			if (!researched) state = State.LOCKED;
			else if (there == null) state = State.UNLINKED;
			else if (pad.getStack(0).isEmpty()) state = State.NO_ANNEALER;
			else {
				cross = !dimensionOf(there).equals(world.getRegistryKey().getValue());
				distance = cross ? Math.max(100, Math.sqrt(pad.getPos().getSquaredDistance(posOf(there)))) : distance(world.getServer(), here, there);
				state = cryostat(world, pad.getPos(), canisters(cross), true) == null ? State.NO_CRYOSTAT
						: pad.powerSatisfaction() < 0.5 ? State.NO_POWER : State.READY;
			}
			pad.setSiteReading(R_STATE, state.ordinal());
			pad.setSiteReading(R_DISTANCE, (int) Math.min(Integer.MAX_VALUE, Math.round(distance)));
			pad.setSiteReading(R_KW, (int) Math.min(Integer.MAX_VALUE, Math.round(distance > 0 ? jumpKw(distance, cross) : 0)));
			pad.setSiteReading(R_CROSS, cross ? 1 : 0);
			BlockPos partner = there == null ? BlockPos.ORIGIN : posOf(there);
			pad.setSiteReading(R_PARTNER_X, partner.getX());
			pad.setSiteReading(R_PARTNER_Y, partner.getY());
			pad.setSiteReading(R_PARTNER_Z, partner.getZ());
		}
	}

	public static boolean anyCharging() { return !CHARGES.isEmpty(); }

	public static boolean isShard(net.minecraft.item.ItemStack stack) {
		return stack.isOf(RcItems.ITEMS.get("linked_shard")) || stack.isOf(RcItems.ITEMS.get("dimensional_shard"));
	}
}
