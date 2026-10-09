package dev.rackcraft.world;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.PersistentState;

/**
 * What every Patch Panel in a dimension is set to. A panel has six faces, each on a port from 0 to {@link #MAX_PORT}:
 * two faces on the same non-zero port are joined, on every network at once, and a face on port 0 is isolated. The panel
 * itself carries nothing, so {@link NetworkManager} treats each pair of joined faces as an edge between the two blocks
 * the faces touch.
 */
public final class PatchPanels extends PersistentState {
	private static final String STATE_KEY = "rackcraft_patch_panels";
	public static final int MAX_PORT = 8;

	private final Map<Long, int[]> ports = new HashMap<>();
	private final Map<Long, String> names = new HashMap<>();

	public static PatchPanels get(ServerWorld world) {
		return world.getPersistentStateManager().getOrCreate(PatchPanels::fromNbt, PatchPanels::new, STATE_KEY);
	}

	public int port(BlockPos pos, Direction face) {
		int[] faces = ports.get(pos.asLong());
		return faces == null ? 0 : faces[face.getId()];
	}

	public void setPort(BlockPos pos, Direction face, int port) {
		int[] faces = ports.computeIfAbsent(pos.asLong(), key -> new int[6]);
		faces[face.getId()] = Math.floorMod(port, MAX_PORT + 1);
		markDirty();
	}

	/** Moves a face to the next port (wrapping to 0 after the last). Returns the new port. */
	public int cycle(BlockPos pos, Direction face) {
		setPort(pos, face, port(pos, face) + 1);
		return port(pos, face);
	}

	public String name(BlockPos pos) { return names.getOrDefault(pos.asLong(), ""); }

	public void setName(BlockPos pos, String name) {
		if (name.isEmpty()) names.remove(pos.asLong());
		else names.put(pos.asLong(), name);
		markDirty();
	}

	public void remove(BlockPos pos) {
		boolean changed = ports.remove(pos.asLong()) != null;
		changed |= names.remove(pos.asLong()) != null;
		if (changed) markDirty();
	}

	public void reset() {
		ports.clear();
		names.clear();
		markDirty();
	}

	/** The pairs of blocks that the panels currently join: the two blocks touching two faces on the same port. */
	public List<BlockPos[]> joins() {
		List<BlockPos[]> pairs = new ArrayList<>();
		for (Map.Entry<Long, int[]> entry : ports.entrySet()) {
			BlockPos pos = BlockPos.fromLong(entry.getKey());
			int[] faces = entry.getValue();
			for (int first = 0; first < 6; first++) {
				if (faces[first] == 0) continue;
				for (int second = first + 1; second < 6; second++) {
					if (faces[second] == faces[first]) {
						pairs.add(new BlockPos[] {pos.offset(Direction.byId(first)), pos.offset(Direction.byId(second))});
					}
				}
			}
		}
		return pairs;
	}

	public static PatchPanels fromNbt(NbtCompound nbt) {
		PatchPanels panels = new PatchPanels();
		NbtList list = nbt.getList("Panels", NbtElement.COMPOUND_TYPE);
		for (int index = 0; index < list.size(); index++) {
			NbtCompound entry = list.getCompound(index);
			long key = entry.getLong("Pos");
			int[] faces = entry.getIntArray("Ports");
			if (faces.length == 6) panels.ports.put(key, faces);
			if (entry.contains("Name")) panels.names.put(key, entry.getString("Name"));
		}
		return panels;
	}

	@Override
	public NbtCompound writeNbt(NbtCompound nbt) {
		NbtList list = new NbtList();
		for (Map.Entry<Long, int[]> entry : ports.entrySet()) {
			NbtCompound panel = new NbtCompound();
			panel.putLong("Pos", entry.getKey());
			panel.putIntArray("Ports", entry.getValue());
			String name = names.get(entry.getKey());
			if (name != null) panel.putString("Name", name);
			list.add(panel);
		}
		nbt.put("Panels", list);
		return nbt;
	}
}
