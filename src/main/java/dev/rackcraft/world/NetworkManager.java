package dev.rackcraft.world;

import dev.rackcraft.sim.NetGraph;
import dev.rackcraft.sim.NetKind;
import dev.rackcraft.block.CableBlock;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
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
	/** Which component each position is in, per kind; rebuilt with the graphs. */
	private final EnumMap<NetKind, Map<BlockPos, Set<BlockPos>>> componentOf = new EnumMap<>(NetKind.class);
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
		Map<BlockPos, Set<BlockPos>> index = componentOf.get(kind);
		return index == null ? Set.of() : index.getOrDefault(pos, Set.of());
	}

	public Set<BlockPos> endpoints(NetKind kind) {
		return nodes.get(kind).stream().filter(this::isConnected).collect(java.util.stream.Collectors.toUnmodifiableSet());
	}

	/** Cut cables of this kind: they are registered but carry nothing until repaired. */
	public Set<BlockPos> cutCables(NetKind kind) {
		return nodes.get(kind).stream().filter(this::isCut).collect(java.util.stream.Collectors.toUnmodifiableSet());
	}

	public java.util.List<Set<BlockPos>> components(NetKind kind) {
		rebuildIfDirty();
		return graphs.get(kind).components();
	}

	private void rebuildIfDirty() {
		if (!dirty) return;
		List<BlockPos[]> joins = PatchPanels.get(world).joins();
		List<PylonLinks.Span> spans = PylonLinks.get(world).spans();
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
			// Patch Panels join the blocks on faces sharing a port; Pylon spans join power across the gap.
			for (BlockPos[] pair : joins) {
				if (positions.contains(pair[0]) && positions.contains(pair[1])) graph.connect(pair[0], pair[1]);
			}
			if (kind == NetKind.POWER) {
				for (PylonLinks.Span span : spans) {
					if (positions.contains(span.a()) && positions.contains(span.b())) graph.connect(span.a(), span.b());
				}
			}
			graphs.put(kind, graph);
			Map<BlockPos, Set<BlockPos>> index = new HashMap<>();
			for (Set<BlockPos> component : graph.components()) for (BlockPos member : component) index.put(member, component);
			componentOf.put(kind, index);
		}
		dirty = false;
	}

	private boolean isCut(BlockPos pos) {
		var state = world.getBlockState(pos);
		return state.getBlock() instanceof CableBlock && state.get(CableBlock.CUT);
	}

	private boolean isConnected(BlockPos pos) {
		var state = world.getBlockState(pos);
		if (!(state.getBlock() instanceof CableBlock cable)) return true;
		if (state.get(CableBlock.CUT)) return false;
		// A trunk is inert until its research is done.
		return cable.gate() == null || dev.rackcraft.compute.ResearchLab.get(world).done(cable.gate());
	}

	public ServerWorld world() { return world; }
}