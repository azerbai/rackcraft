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
			SourceKind.SOLAR, SourceKind.WIND, SourceKind.REACTOR, SourceKind.UTILITY,
			SourceKind.BATTERY, SourceKind.DIESEL);

	private PowerSolver() {}

	public static Result solve(Collection<Sink> sinks, Collection<Source> sources, double dtSeconds) {
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

		if (surplusKw > 0) {
			for (Source source : orderedSources) {
				if (source.kind() != SourceKind.BATTERY) continue;
				double chargeKw = source.charge(surplusKw, dtSeconds);
				surplusKw -= chargeKw;
				outputBySource.merge(source.id(), chargeKw, Double::sum);
				supplyByKind.merge(source.kind(), chargeKw, Double::sum);
			}
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

	public enum SourceKind { SOLAR, WIND, REACTOR, UTILITY, BATTERY, DIESEL }

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

	public record Sink(String id, int priority, double demandKw) {
		public Sink {
			if (demandKw < 0) throw new IllegalArgumentException("demandKw cannot be negative");
		}
	}

	public record Result(Map<String, Double> satisfaction, Map<String, Double> sourceOutputKw,
			double suppliedKw, double demandKw) {}
}