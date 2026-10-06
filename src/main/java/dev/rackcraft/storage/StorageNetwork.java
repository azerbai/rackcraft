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

	StorageNetwork(MinecraftServer server, List<Member> members) {
		this.server = server;
		for (Member member : members) (member.cold() ? cold : hot).add(member);
	}

	public boolean isEmpty() { return hot.isEmpty() && cold.isEmpty(); }
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
		Map<ItemKey, Totals> totals = new LinkedHashMap<>();
		sums.forEach((key, pair) -> totals.put(key, new Totals(pair[0], pair[1])));
		return totals;
	}

	public long count(ItemKey key, boolean includeCold) {
		long total = hot.stream().mapToLong(member -> member.data().count(key)).sum();
		if (includeCold) total += cold.stream().mapToLong(member -> member.data().count(key)).sum();
		return total;
	}

	/** Inserts into hot drives (those already holding the type first), then tape. Returns the amount accepted. */
	public long insert(ItemKey key, long amount, boolean simulate) {
		if (key.isEmpty() || amount <= 0) return 0;
		long remaining = amount;
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
