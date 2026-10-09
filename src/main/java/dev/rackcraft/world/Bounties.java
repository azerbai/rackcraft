package dev.rackcraft.world;

import dev.rackcraft.RackcraftConfig;
import dev.rackcraft.entity.GuardEntity;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.world.PersistentState;

/**
 * Darknet bounties on the named elites of Military Bases and hostile data centres. A bounty is only ever posted for an
 * elite that a player has actually found (one within sight of them, loaded), so nobody is sent after something that is
 * not there. Killing the elite pays RackCoin into the facility. Saved with the world; open ones show on the Ops Terminal.
 */
public final class Bounties extends PersistentState {
	private static final String STATE_KEY = "rackcraft_bounties";
	private static final int SCAN_TICKS = 200;
	private static final double FIND_RADIUS = 64;

	public record Bounty(UUID target, String name, BlockPos lastSeen, int payout, boolean done) {}

	private final Map<UUID, Bounty> bounties = new LinkedHashMap<>();

	public static Bounties get(ServerWorld world) {
		return world.getPersistentStateManager().getOrCreate(Bounties::fromNbt, Bounties::new, STATE_KEY);
	}

	public static void register() {
		ServerTickEvents.END_WORLD_TICK.register(world -> {
			if (world.getTime() % SCAN_TICKS == 0) scan(world);
		});
	}

	public List<Bounty> open() {
		List<Bounty> result = new ArrayList<>();
		for (Bounty bounty : bounties.values()) if (!bounty.done()) result.add(bounty);
		return result;
	}

	public List<Bounty> all() { return new ArrayList<>(bounties.values()); }

	public void reset() {
		bounties.clear();
		markDirty();
	}

	/** Posts a bounty for every elite within sight of a player that has none. Returns how many it posted. */
	public static int scan(ServerWorld world) {
		return scan(world, world.getPlayers());
	}

	public static int scan(ServerWorld world, Iterable<? extends ServerPlayerEntity> players) {
		Bounties board = get(world);
		int posted = 0;
		for (ServerPlayerEntity player : players) {
			for (GuardEntity guard : world.getEntitiesByClass(GuardEntity.class, new Box(player.getBlockPos()).expand(FIND_RADIUS), GuardEntity::isElite)) {
				if (board.bounties.containsKey(guard.getUuid()) || !guard.isAlive()) continue;
				board.post(world, guard);
				posted++;
				player.sendMessage(Text.literal("The Darknet has a bounty on " + guard.getName().getString() + ": "
						+ RackcraftConfig.values.weapons.bountyPayout + " RC, dead. It is on your Ops Terminal.").formatted(Formatting.GOLD), false);
			}
		}
		return posted;
	}

	public Bounty post(ServerWorld world, GuardEntity guard) {
		Bounty bounty = new Bounty(guard.getUuid(), guard.getName().getString(), guard.getBlockPos(),
				RackcraftConfig.values.weapons.bountyPayout, false);
		bounties.put(guard.getUuid(), bounty);
		markDirty();
		return bounty;
	}

	/** An elite died: pay out if a player did it. Returns the RC paid. */
	public static int complete(ServerWorld world, GuardEntity guard, Entity killer) {
		if (!guard.isElite() || !(killer instanceof PlayerEntity player)) return 0;
		Bounties board = get(world);
		Bounty bounty = board.bounties.get(guard.getUuid());
		if (bounty == null) bounty = board.post(world, guard);
		if (bounty.done()) return 0;
		board.bounties.put(guard.getUuid(), new Bounty(bounty.target(), bounty.name(), bounty.lastSeen(), bounty.payout(), true));
		board.markDirty();
		FacilityManager.get(world).addCredits(bounty.payout());
		player.sendMessage(Text.literal("Bounty collected on " + bounty.name() + ": +" + bounty.payout() + " RC. The Darknet says thanks and asks no questions.")
				.formatted(Formatting.GOLD), false);
		return bounty.payout();
	}

	public static Bounties fromNbt(NbtCompound nbt) {
		Bounties board = new Bounties();
		for (NbtElement element : nbt.getList("Bounties", NbtElement.COMPOUND_TYPE)) {
			NbtCompound entry = (NbtCompound) element;
			UUID target = entry.getUuid("Target");
			board.bounties.put(target, new Bounty(target, entry.getString("Name"), BlockPos.fromLong(entry.getLong("At")),
					entry.getInt("Payout"), entry.getBoolean("Done")));
		}
		return board;
	}

	@Override
	public NbtCompound writeNbt(NbtCompound nbt) {
		NbtList list = new NbtList();
		for (Bounty bounty : bounties.values()) {
			NbtCompound entry = new NbtCompound();
			entry.putUuid("Target", bounty.target());
			entry.putString("Name", bounty.name());
			entry.putLong("At", bounty.lastSeen().asLong());
			entry.putInt("Payout", bounty.payout());
			entry.putBoolean("Done", bounty.done());
			list.add(entry);
		}
		nbt.put("Bounties", list);
		return nbt;
	}
}
