package dev.rackcraft.storage;

import dev.rackcraft.block.MachineBlockEntity;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.item.ItemStack;
import net.minecraft.server.MinecraftServer;

/**
 * A view over every online drive and tape on one fiber network. Hot drives are filled first; tapes take
 * the overflow and archived items. Reads from tape are slow for players (see {@link StorageService}).
 */
public final class StorageNetwork {
	/** A drive in a host machine; the host is null when the network is reached through a cached transmitter. */
	public record Member(DriveData data, boolean cold, ItemStack stack, MachineBlockEntity host) {}

	public record Totals(long hot, long cold) {
		public long total() { return hot + cold; }
	}

	private static final Map<MinecraftServer, Map<ItemKey, Long>> LAST_ACCESS = new WeakHashMap<>();

	private final MinecraftServer server;
	private final List<Member> hot = new ArrayList<>();
	private final List<Member> cold = new ArrayList<>();
	/** Hydrogen Tank blocks on the network: their hydrogen counts as Hydrogen Canisters. */
	private final List<MachineBlockEntity> tanks = new ArrayList<>();
	/** The Exchange Auto-Buyers on the network that are online, and the world whose balance they spend. */
	private final List<MachineBlockEntity> buyers = new ArrayList<>();
	private net.minecraft.server.world.ServerWorld world;

	StorageNetwork(MinecraftServer server, List<Member> members) {
		this.server = server;
		for (Member member : members) (member.cold() ? cold : hot).add(member);
	}

	StorageNetwork withTanksAndBuyers(net.minecraft.server.world.ServerWorld world, List<MachineBlockEntity> tanks, List<MachineBlockEntity> buyers) {
		this.world = world;
		this.tanks.addAll(tanks);
		this.buyers.addAll(buyers);
		return this;
	}

	public boolean isEmpty() { return hot.isEmpty() && cold.isEmpty() && tanks.isEmpty() && buyers.isEmpty(); }

	// ---------------------------------------------------------------- hydrogen tanks

	private static boolean hydrogen(ItemKey key) {
		return key.equals(ItemKey.of(dev.rackcraft.RcItems.ITEMS.get("hydrogen_canister")));
	}

	public boolean hasTanks() { return !tanks.isEmpty(); }

	public long hydrogenStored() {
		return tanks.stream().mapToLong(MachineBlockEntity::hydrogen).sum();
	}

	/** Canisters' worth of room left in the tanks. */
	public long hydrogenRoom() {
		long room = 0;
		for (MachineBlockEntity tank : tanks) room += Math.max(0, dev.rackcraft.world.HydrogenTanks.capacityPerBlock(tank.reactorArraySize()) - tank.hydrogen());
		return room;
	}

	private long tankInsert(long amount, boolean simulate) {
		long remaining = amount;
		for (MachineBlockEntity tank : tanks) {
			if (remaining <= 0) break;
			long moved = Math.min(remaining, Math.max(0, dev.rackcraft.world.HydrogenTanks.capacityPerBlock(tank.reactorArraySize()) - tank.hydrogen()));
			if (!simulate && moved > 0) tank.setHydrogen(tank.hydrogen() + moved);
			remaining -= moved;
		}
		return amount - remaining;
	}

	private long tankExtract(long amount, boolean simulate) {
		long remaining = amount;
		for (MachineBlockEntity tank : tanks) {
			if (remaining <= 0) break;
			long moved = Math.min(remaining, tank.hydrogen());
			if (!simulate && moved > 0) tank.setHydrogen(tank.hydrogen() - moved);
			remaining -= moved;
		}
		return amount - remaining;
	}

	// ---------------------------------------------------------------- the auto-buyer

	/** Whether an online Exchange Auto-Buyer is on this network. */
	public boolean buying() { return !buyers.isEmpty() && world != null; }

	/**
	 * For machines restocking themselves: takes what storage has, and if an Exchange Auto-Buyer is on the network,
	 * buys the rest at Exchange prices out of the RackCoin balance (anything the Exchange sells). Returns how many.
	 */
	public long extractOrBuy(ItemKey key, long amount) {
		long got = extract(key, amount, true, false);
		long short_ = amount - got;
		if (short_ <= 0 || !buying() || key.isEmpty() || !key.matches(new ItemStack(key.item()))) return got;
		Long price = dev.rackcraft.ExchangeCatalog.price(key.item());
		if (price == null || price <= 0) return got;
		var facility = dev.rackcraft.world.FacilityManager.get(world);
		long bought = Math.min(short_, facility.credits() / price);
		if (bought <= 0 || !facility.spendCredits(bought * price)) return got;
		MachineBlockEntity buyer = buyers.get(0);
		buyer.site().putLong("Spent", buyer.site().getLong("Spent") + bought * price);
		buyer.site().putLong("Bought", buyer.site().getLong("Bought") + bought);
		buyer.markDirty();
		return got + bought;
	}

	/** Whether this item could be bought here if storage runs out (an Auto-Buyer is on, and the Exchange sells it). */
	public boolean canBuy(net.minecraft.item.Item item) {
		Long price = dev.rackcraft.ExchangeCatalog.price(item);
		return buying() && price != null && price > 0 && dev.rackcraft.world.FacilityManager.get(world).credits() >= price;
	}
	public boolean hasTape() { return !cold.isEmpty(); }
	public int hotDrives() { return hot.size(); }
	public int tapes() { return cold.size(); }
	public long hotCapacity() { return hot.stream().mapToLong(member -> member.data().capacity()).sum(); }
	public long hotUsed() { return hot.stream().mapToLong(member -> member.data().used()).sum(); }
	public long coldCapacity() { return cold.stream().mapToLong(member -> member.data().capacity()).sum(); }
	public long coldUsed() { return cold.stream().mapToLong(member -> member.data().used()).sum(); }

	public Map<ItemKey, Totals> totals() {
		Map<ItemKey, long[]> sums = new LinkedHashMap<>();
		for (Member member : hot) member.data().items().forEach((key, count) -> sums.computeIfAbsent(key, k -> new long[2])[0] += count);
		for (Member member : cold) member.data().items().forEach((key, count) -> sums.computeIfAbsent(key, k -> new long[2])[1] += count);
		long gas = hydrogenStored();
		if (gas > 0) sums.computeIfAbsent(ItemKey.of(dev.rackcraft.RcItems.ITEMS.get("hydrogen_canister")), k -> new long[2])[0] += gas;
		Map<ItemKey, Totals> totals = new LinkedHashMap<>();
		sums.forEach((key, pair) -> totals.put(key, new Totals(pair[0], pair[1])));
		return totals;
	}

	public long count(ItemKey key, boolean includeCold) {
		long total = hot.stream().mapToLong(member -> member.data().count(key)).sum();
		if (includeCold) total += cold.stream().mapToLong(member -> member.data().count(key)).sum();
		if (hydrogen(key)) total += hydrogenStored();
		return total;
	}

	/** Inserts into hot drives (those already holding the type first), then tape. Returns the amount accepted. */
	public long insert(ItemKey key, long amount, boolean simulate) {
		if (key.isEmpty() || amount <= 0) return 0;
		long remaining = amount;
		// Canisters filed into storage go into the tanks first.
		if (hydrogen(key)) remaining -= tankInsert(remaining, simulate);
		List<Member> order = new ArrayList<>(hot);
		order.sort(Comparator.comparing((Member member) -> member.data().count(key) == 0));
		order.addAll(cold);
		for (Member member : order) {
			if (remaining <= 0) break;
			remaining -= member.data().insert(key, remaining, simulate);
		}
		long inserted = amount - remaining;
		if (!simulate && inserted > 0) changed(key);
		return inserted;
	}

	/** Extracts from hot drives, then (if allowed) tape. Returns the amount removed. */
	public long extract(ItemKey key, long amount, boolean allowCold, boolean simulate) {
		long remaining = amount;
		// Canisters come out of the tanks first: a tank fills one for every unit of hydrogen.
		if (hydrogen(key) && amount > 0) remaining -= tankExtract(remaining, simulate);
		for (Member member : hot) {
			if (remaining <= 0) break;
			remaining -= member.data().extract(key, remaining, simulate);
		}
		if (allowCold) {
			for (Member member : cold) {
				if (remaining <= 0) break;
				remaining -= member.data().extract(key, remaining, simulate);
			}
		}
		long extracted = amount - remaining;
		if (!simulate && extracted > 0) changed(key);
		return extracted;
	}

	/** Moves up to {@code amount} of a type from tape onto hot drives. Returns the amount moved. */
	public long recall(ItemKey key, long amount) {
		long room = 0;
		for (Member member : hot) room += member.data().free();
		long moved = 0;
		long wanted = Math.min(amount, room);
		for (Member member : cold) {
			if (moved >= wanted) break;
			moved += member.data().extract(key, wanted - moved, false);
		}
		long back = moved - insertHot(key, moved);
		if (back > 0) cold.get(0).data().insert(key, back, false);
		if (moved > 0) changed(key);
		return moved - back;
	}

	private long insertHot(ItemKey key, long amount) {
		long remaining = amount;
		for (Member member : hot) {
			if (remaining <= 0) break;
			remaining -= member.data().insert(key, remaining, false);
		}
		return amount - remaining;
	}

	/**
	 * Tiering: while the hot drives are over {@code highWater} full, move the least recently used types to
	 * tape until they are under {@code lowWater}. Returns the number of items archived.
	 */
	public long archive(double highWater, double lowWater) {
		long capacity = hotCapacity();
		if (cold.isEmpty() || capacity <= 0 || hotUsed() <= capacity * highWater) return 0;
		Map<ItemKey, Long> lastAccess = LAST_ACCESS.computeIfAbsent(server, ignored -> new HashMap<>());
		Map<ItemKey, Long> hotTypes = new HashMap<>();
		for (Member member : hot) member.data().items().forEach((key, count) -> hotTypes.merge(key, count, Long::sum));
		List<ItemKey> oldestFirst = new ArrayList<>(hotTypes.keySet());
		oldestFirst.sort(Comparator.comparingLong(key -> lastAccess.getOrDefault(key, 0L)));
		long archived = 0;
		for (ItemKey key : oldestFirst) {
			if (hotUsed() <= capacity * lowWater) break;
			long amount = hotTypes.get(key);
			long room = cold.stream().mapToLong(member -> member.data().free()).sum();
			long moving = Math.min(amount, room);
			if (moving <= 0) break;
			long taken = 0;
			for (Member member : hot) taken += member.data().extract(key, moving - taken, false);
			long remaining = taken;
			for (Member member : cold) remaining -= member.data().insert(key, remaining, false);
			archived += taken;
		}
		if (archived > 0) refreshSummaries();
		return archived;
	}

	private void changed(ItemKey key) {
		LAST_ACCESS.computeIfAbsent(server, ignored -> new HashMap<>()).put(key, server.getOverworld().getTime());
		refreshSummaries();
	}

	private void refreshSummaries() {
		StorageState.get(server).markDirty();
		for (List<Member> tier : List.of(hot, cold)) {
			for (Member member : tier) {
				if (member.stack() == null) continue;
				DriveItem.writeSummary(member.stack(), member.data());
				if (member.host() != null) member.host().markDirty();
			}
		}
	}
}
