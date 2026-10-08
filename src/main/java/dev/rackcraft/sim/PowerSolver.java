package dev.rackcraft.sim;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class PowerSolver {
	private static final List<SourceKind> DISPATCH_ORDER = List.of(
			SourceKind.BEAMED, SourceKind.SOLAR, SourceKind.WIND, SourceKind.REACTOR, SourceKind.UTILITY,
			SourceKind.BATTERY, SourceKind.DIESEL);

	private PowerSolver() {}

	/** Sources whose spare output a Grid-Tie Substation may sell: not batteries, diesel or the (free) utility feed. */
	private static final List<SourceKind> EXPORTABLE = List.of(SourceKind.SOLAR, SourceKind.WIND, SourceKind.REACTOR);

	/**
	 * Dispatches sources to sinks. Export sinks (Grid-Tie Substations) come last and only take what solar, wind and
	 * reactors have left once every other sink is served and batteries have charged: exporting never drains a
	 * battery or spins up a diesel.
	 */
	public static Result solve(Collection<Sink> allSinks, Collection<Source> sources, double dtSeconds) {
		List<Sink> sinks = allSinks.stream().filter(sink -> !sink.export()).toList();
		List<Sink> exports = allSinks.stream().filter(Sink::export).toList();
		EnumMap<SourceKind, Double> supplyByKind = new EnumMap<>(SourceKind.class);
		Map<String, Double> outputBySource = new LinkedHashMap<>();
		List<Source> orderedSources = new ArrayList<>(sources);
		orderedSources.sort(Comparator.comparingInt(source -> DISPATCH_ORDER.indexOf(source.kind())));
		double totalDemand = sinks.stream().mapToDouble(Sink::demandKw).sum();
		double remainingDemand = totalDemand;
		double firmSupply = orderedSources.stream()
				.filter(source -> source.kind() != SourceKind.BATTERY && source.kind() != SourceKind.DIESEL)
				.mapToDouble(source -> source.availableKw(dtSeconds)).sum();
		if (totalDemand > firmSupply) {
			orderedSources.stream().filter(source -> source.kind() == SourceKind.DIESEL)
					.forEach(Source::advanceSpinup);
		}
		double surplusKw = 0;

		for (Source source : orderedSources) {
			double available = source.availableKw(dtSeconds);
			double dispatched = Math.min(available, remainingDemand);
			outputBySource.put(source.id(), dispatched);
			supplyByKind.merge(source.kind(), dispatched, Double::sum);
			if (source.kind() == SourceKind.BATTERY) source.discharge(dispatched, dtSeconds);
			remainingDemand -= dispatched;
			if (source.kind() != SourceKind.BATTERY) surplusKw += available - dispatched;
		}

		List<Sink> orderedSinks = new ArrayList<>(sinks);
		orderedSinks.sort(Comparator.comparingInt(Sink::priority));
		Map<String, Double> satisfaction = new LinkedHashMap<>();
		double undispatched = outputBySource.values().stream().mapToDouble(Double::doubleValue).sum();
		int currentPriority = Integer.MIN_VALUE;
		List<Sink> priorityGroup = new ArrayList<>();
		for (Sink sink : orderedSinks) {
			if (sink.priority() != currentPriority && !priorityGroup.isEmpty()) {
				undispatched = satisfyGroup(priorityGroup, undispatched, satisfaction);
				priorityGroup.clear();
			}
			currentPriority = sink.priority();
			priorityGroup.add(sink);
		}
		if (!priorityGroup.isEmpty()) satisfyGroup(priorityGroup, undispatched, satisfaction);

		double charged = 0;
		if (surplusKw > 0) {
			for (Source source : orderedSources) {
				if (source.kind() != SourceKind.BATTERY) continue;
				double chargeKw = source.charge(surplusKw, dtSeconds);
				surplusKw -= chargeKw;
				charged += chargeKw;
				outputBySource.merge(source.id(), chargeKw, Double::sum);
				supplyByKind.merge(source.kind(), chargeKw, Double::sum);
			}
		}
		double exportDemand = exports.stream().mapToDouble(Sink::demandKw).sum();
		if (exportDemand > 0) {
			// Batteries charge from the utility and diesel spare first, then from what could have been exported.
			double firmSpare = 0;
			double exportSpare = 0;
			for (Source source : orderedSources) {
				if (source.kind() == SourceKind.BATTERY) continue;
				double spare = Math.max(0, source.availableKw(dtSeconds) - outputBySource.getOrDefault(source.id(), 0.0));
				if (EXPORTABLE.contains(source.kind())) exportSpare += spare;
				else firmSpare += spare;
			}
			double sellable = Math.min(exportDemand, Math.max(0, exportSpare - Math.max(0, charged - firmSpare)));
			double remaining = sellable;
			for (SourceKind kind : EXPORTABLE) {
				for (Source source : orderedSources) {
					if (source.kind() != kind || remaining <= 0) continue;
					double spare = Math.max(0, source.availableKw(dtSeconds) - outputBySource.getOrDefault(source.id(), 0.0));
					double sold = Math.min(spare, remaining);
					if (sold <= 0) continue;
					outputBySource.merge(source.id(), sold, Double::sum);
					supplyByKind.merge(kind, sold, Double::sum);
					remaining -= sold;
				}
			}
			double ratio = (sellable - remaining) / exportDemand;
			for (Sink sink : exports) satisfaction.put(sink.id(), ratio);
		}
		return new Result(Map.copyOf(satisfaction), Map.copyOf(outputBySource),
				supplyByKind.values().stream().mapToDouble(Double::doubleValue).sum(), totalDemand);
	}

	private static double satisfyGroup(List<Sink> sinks, double availableKw, Map<String, Double> satisfaction) {
		double demand = sinks.stream().mapToDouble(Sink::demandKw).sum();
		double ratio = demand <= 0 ? 1 : Math.min(1, availableKw / demand);
		for (Sink sink : sinks) satisfaction.put(sink.id(), ratio);
		return Math.max(0, availableKw - demand * ratio);
	}

	/** BEAMED: power from the Dyson swarm, free and steady, so it is drawn first; like UTILITY it is never exported. */
	public enum SourceKind { SOLAR, WIND, REACTOR, UTILITY, BATTERY, DIESEL, BEAMED }

	public enum StorageMode { NONE, STORAGE }

	public static final class Source {
		private final String id;
		private final SourceKind kind;
		private final double capacityKw;
		private final double capacityKws;
		private final double maxInKw;
		private final double maxOutKw;
		private double chargeKws;
		private int spinupSteps;
		/** Charging stores this share of the power put in, and discharging draws 1/efficiency per kW delivered. */
		private double efficiency = 0.9;

		public Source(String id, SourceKind kind, double capacityKw) {
			this(id, kind, capacityKw, 0, 0, 0, 0, 0);
		}

		public Source(String id, SourceKind kind, double capacityKw, double capacityKws,
				double chargeKws, double maxInKw, double maxOutKw) {
			this(id, kind, capacityKw, capacityKws, chargeKws, maxInKw, maxOutKw, 0);
		}

		public Source(String id, SourceKind kind, double capacityKw, double capacityKws,
				double chargeKws, double maxInKw, double maxOutKw, int spinupSteps) {
			this.id = id;
			this.kind = kind;
			this.capacityKw = Math.max(0, capacityKw);
			this.capacityKws = Math.max(0, capacityKws);
			this.chargeKws = Math.max(0, Math.min(chargeKws, capacityKws));
			this.maxInKw = Math.max(0, maxInKw);
			this.maxOutKw = Math.max(0, maxOutKw);
			this.spinupSteps = Math.max(0, Math.min(20, spinupSteps));
		}

		/** Sets a battery's one-way efficiency (0.9 by default); returns this source. */
		public Source efficiency(double value) {
			efficiency = Math.max(0.5, Math.min(1, value));
			return this;
		}

		public String id() { return id; }
		public SourceKind kind() { return kind; }
		public double capacityKw() { return capacityKw; }
		public double chargeKws() { return chargeKws; }
		public int spinupSteps() { return spinupSteps; }

		private double availableKw(double dtSeconds) {
			if (kind == SourceKind.BATTERY) {
				return dtSeconds <= 0 ? 0 : Math.min(maxOutKw, chargeKws / dtSeconds);
			}
			if (kind == SourceKind.DIESEL && spinupSteps < 20) return 0;
			return capacityKw;
		}

		private void advanceSpinup() {
			spinupSteps = Math.min(20, spinupSteps + 1);
		}

		private double charge(double requestedKw, double dtSeconds) {
			if (kind != SourceKind.BATTERY || dtSeconds <= 0) return 0;
			double inputKw = Math.min(Math.min(requestedKw, maxInKw), (capacityKws - chargeKws) / dtSeconds);
			double storedKw = inputKw * efficiency;
			chargeKws = Math.min(capacityKws, chargeKws + storedKw * dtSeconds);
			return inputKw;
		}

		private void discharge(double deliveredKw, double dtSeconds) {
			if (kind != SourceKind.BATTERY || dtSeconds <= 0) return;
			chargeKws = Math.max(0, chargeKws - deliveredKw * dtSeconds / efficiency);
		}
	}

	/** {@code export}: a Grid-Tie Substation, served last and only from spare solar, wind and reactor output. */
	public record Sink(String id, int priority, double demandKw, boolean export) {
		public Sink {
			if (demandKw < 0) throw new IllegalArgumentException("demandKw cannot be negative");
		}

		public Sink(String id, int priority, double demandKw) {
			this(id, priority, demandKw, false);
		}
	}

	public record Result(Map<String, Double> satisfaction, Map<String, Double> sourceOutputKw,
			double suppliedKw, double demandKw) {}
}