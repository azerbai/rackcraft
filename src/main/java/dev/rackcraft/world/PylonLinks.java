package dev.rackcraft.world;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.PersistentState;

/**
 * Pylon spans. A span joins two Pylons into one power cable without a cable between them. Spans are saved here, and
 * {@link NetworkManager} adds each as an edge when both ends are loaded. A Pylon holds at most {@link #MAX_SPANS}
 * spans, so a chain of masts can cross country but a Pylon can't become a hub.
 */
public final class PylonLinks extends PersistentState {
	private static final String STATE_KEY = "rackcraft_pylon_links";
	public static final int MAX_SPANS = 2;
	/** Blocks a span can cross, and with Superconducting Pylons at both ends. */
	public static final int RANGE = 128;
	public static final int SUPERCONDUCTING_RANGE = 256;
	/** Share of a network's load wasted per 64 blocks of an ordinary span. */
	public static final double LOSS_PER_64 = 0.03;

	public record Span(BlockPos a, BlockPos b) {
		public double length() { return Math.sqrt(a.getSquaredDistance(b)); }
	}

	private final Set<Long[]> spans = new LinkedHashSet<>();

	public static PylonLinks get(ServerWorld world) {
		return world.getPersistentStateManager().getOrCreate(PylonLinks::fromNbt, PylonLinks::new, STATE_KEY);
	}

	public List<Span> spans() {
		List<Span> result = new ArrayList<>();
		for (Long[] span : spans) result.add(new Span(BlockPos.fromLong(span[0]), BlockPos.fromLong(span[1])));
		return result;
	}

	public int count(BlockPos pos) {
		int count = 0;
		for (Long[] span : spans) if (span[0] == pos.asLong() || span[1] == pos.asLong()) count++;
		return count;
	}

	public boolean linked(BlockPos a, BlockPos b) {
		for (Long[] span : spans) {
			if (span[0] == a.asLong() && span[1] == b.asLong() || span[0] == b.asLong() && span[1] == a.asLong()) return true;
		}
		return false;
	}

	public void add(BlockPos a, BlockPos b) {
		spans.add(new Long[] {a.asLong(), b.asLong()});
		markDirty();
	}

	/** Cuts every span of a Pylon. Returns how many went. */
	public int unlink(BlockPos pos) {
		int before = spans.size();
		spans.removeIf(span -> span[0] == pos.asLong() || span[1] == pos.asLong());
		if (spans.size() != before) markDirty();
		return before - spans.size();
	}

	public void reset() {
		spans.clear();
		markDirty();
	}

	/** Whether a Pylon block id is the superconducting kind. */
	public static boolean superconducting(String blockId) { return blockId.equals("superconducting_pylon"); }

	/** Share of a network's load a span wastes: nothing between two Superconducting Pylons. */
	public static double lossFraction(double length, boolean superconductingSpan) {
		return superconductingSpan ? 0 : LOSS_PER_64 * length / 64.0;
	}

	public static PylonLinks fromNbt(NbtCompound nbt) {
		PylonLinks links = new PylonLinks();
		NbtList list = nbt.getList("Spans", NbtElement.COMPOUND_TYPE);
		for (int index = 0; index < list.size(); index++) {
			NbtCompound entry = list.getCompound(index);
			links.spans.add(new Long[] {entry.getLong("A"), entry.getLong("B")});
		}
		return links;
	}

	@Override
	public NbtCompound writeNbt(NbtCompound nbt) {
		NbtList list = new NbtList();
		for (Long[] span : spans) {
			NbtCompound entry = new NbtCompound();
			entry.putLong("A", span[0]);
			entry.putLong("B", span[1]);
			list.add(entry);
		}
		nbt.put("Spans", list);
		return nbt;
	}
}
