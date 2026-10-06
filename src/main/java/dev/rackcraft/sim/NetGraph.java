package dev.rackcraft.sim;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class NetGraph<N> {
	private final Map<N, Set<N>> edges = new HashMap<>();
	private List<Set<N>> cachedComponents = List.of();
	private boolean dirty = true;

	public void connect(N first, N second) {
		if (first.equals(second)) return;
		boolean changed = edges.computeIfAbsent(first, ignored -> new HashSet<>()).add(second);
		changed |= edges.computeIfAbsent(second, ignored -> new HashSet<>()).add(first);
		if (changed) dirty = true;
	}

	public void addNode(N node) {
		if (edges.putIfAbsent(node, new HashSet<>()) == null) dirty = true;
	}

	public void remove(N node) {
		Set<N> neighbours = edges.remove(node);
		if (neighbours == null) return;
		for (N neighbour : neighbours) {
			Set<N> adjacent = edges.get(neighbour);
			if (adjacent != null) adjacent.remove(node);
		}
		dirty = true;
	}

	public List<Set<N>> components() {
		if (!dirty) return cachedComponents;
		Set<N> visited = new HashSet<>();
		List<Set<N>> rebuilt = new ArrayList<>();
		for (N start : edges.keySet()) {
			if (!visited.add(start)) continue;
			Set<N> component = new HashSet<>();
			ArrayDeque<N> pending = new ArrayDeque<>();
			pending.add(start);
			while (!pending.isEmpty()) {
				N current = pending.removeFirst();
				component.add(current);
				for (N neighbour : edges.getOrDefault(current, Set.of())) {
					if (visited.add(neighbour)) pending.addLast(neighbour);
				}
			}
			rebuilt.add(Collections.unmodifiableSet(component));
		}
		cachedComponents = List.copyOf(rebuilt);
		dirty = false;
		return cachedComponents;
	}

	public boolean isDirty() {
		return dirty;
	}
}