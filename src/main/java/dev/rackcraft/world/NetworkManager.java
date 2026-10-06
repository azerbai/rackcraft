package dev.rackcraft.world;

import dev.rackcraft.sim.NetGraph;
import dev.rackcraft.sim.NetKind;
import dev.rackcraft.block.CableBlock;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

public final class NetworkManager {
	private static final Map<ServerWorld, NetworkManager> WORLDS = new WeakHashMap<>();
	private final ServerWorld world;
	private final EnumMap<NetKind, Set<BlockPos>> nodes = new EnumMap<>(NetKind.class);
	private final EnumMap<NetKind, NetGraph<BlockPos>> graphs = new EnumMap<>(NetKind.class);
	private final Map<BlockPos, Set<NetKind>> kindsByPosition = new HashMap<>();
	private boolean dirty = true;

	private NetworkManager(ServerWorld world) {
		this.world = world;
		for (NetKind kind : NetKind.values()) nodes.put(kind, new HashSet<>());
	}

	public static NetworkManager get(ServerWorld world) {
		return WORLDS.computeIfAbsent(world, NetworkManager::new);
	}

	public void register(BlockPos pos, Set<NetKind> kinds) {
		unregister(pos);
		BlockPos immutablePos = pos.toImmutable();
		Set<NetKind> copy = Set.copyOf(kinds);
		kindsByPosition.put(immutablePos, copy);
		for (NetKind kind : copy) nodes.get(kind).add(immutablePos);
		dirty = true;
	}

	public void unregister(BlockPos pos) {
		BlockPos immutablePos = pos.toImmutable();
		Set<NetKind> oldKinds = kindsByPosition.remove(immutablePos);
		if (oldKinds == null) return;
		for (NetKind kind : oldKinds) nodes.get(kind).remove(immutablePos);
		dirty = true;
	}

	public void markDirty() { dirty = true; }

	public Set<BlockPos> component(BlockPos pos, NetKind kind) {
		rebuildIfDirty();
		NetGraph<BlockPos> graph = graphs.get(kind);
		if (graph == null) return Set.of();
		return graph.components().stream().filter(component -> component.contains(pos)).findFirst().orElse(Set.of());
	}

	public Set<BlockPos> endpoints(NetKind kind) {
		return nodes.get(kind).stream().filter(this::isConnected).collect(java.util.stream.Collectors.toUnmodifiableSet());
	}

	public java.util.List<Set<BlockPos>> components(NetKind kind) {
		rebuildIfDirty();
		return graphs.get(kind).components();
	}

	private void rebuildIfDirty() {
		if (!dirty) return;
		for (NetKind kind : NetKind.values()) {
			NetGraph<BlockPos> graph = new NetGraph<>();
			Set<BlockPos> positions = new HashSet<>();
			for (BlockPos candidate : nodes.get(kind)) {
				if (!isConnected(candidate)) continue;
				positions.add(candidate);
				graph.addNode(candidate);
			}
			for (BlockPos pos : positions) {
				for (net.minecraft.util.math.Direction direction : net.minecraft.util.math.Direction.values()) {
					BlockPos neighbour = pos.offset(direction);
					if (positions.contains(neighbour)) graph.connect(pos, neighbour);
				}
			}
			graphs.put(kind, graph);
		}
		dirty = false;
	}

	private boolean isConnected(BlockPos pos) {
		var state = world.getBlockState(pos);
		return !(state.getBlock() instanceof CableBlock && state.get(CableBlock.CUT));
	}

	public ServerWorld world() { return world; }
}