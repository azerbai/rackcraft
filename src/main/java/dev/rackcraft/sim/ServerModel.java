package dev.rackcraft.sim;

import java.util.List;

public final class ServerModel {
	private ServerModel() {}

	public enum Module {
		PI_NODE(1, 0.05, 0.15, 0.5),
		SERVER_1U(1, 0.2, 0.6, 2),
		GPU_BLADE(2, 1, 3, 12),
		QUANTUM_CORE(4, 3, 9, 60);

		private final int units;
		private final double idleKw;
		private final double maxKw;
		private final double creditsPerSecond;

		Module(int units, double idleKw, double maxKw, double creditsPerSecond) {
			this.units = units;
			this.idleKw = idleKw;
			this.maxKw = maxKw;
			this.creditsPerSecond = creditsPerSecond;
		}

		public int units() { return units; }
		public double idleKw() { return idleKw; }
		public double maxKw() { return maxKw; }
		public double creditsPerSecond() { return creditsPerSecond; }
	}

	public static double thermalFactor(double inletCelsius) {
		if (inletCelsius <= 27) return 1;
		if (inletCelsius < 32) return 1 - (inletCelsius - 27) * 0.4 / 5;
		if (inletCelsius < 40) return 0.6 - (inletCelsius - 32) * 0.35 / 8;
		return 0;
	}

	public static RackStep calculate(List<Module> modules, int loadLimitPercent,
			double powerSatisfaction, double inletCelsius, boolean quantumHasCdu) {
		int usedUnits = modules.stream().mapToInt(Module::units).sum();
		if (usedUnits > 8) throw new IllegalArgumentException("Rack capacity is 8 U");
		boolean quantumBlocked = modules.contains(Module.QUANTUM_CORE) && !quantumHasCdu;
		double thermal = thermalFactor(inletCelsius);
		double power = clamp(powerSatisfaction, 0, 1);
		double load = clamp(loadLimitPercent / 100.0, 0, 1) * power * thermal;
		if (power < 0.5 || thermal == 0 || quantumBlocked) load = 0;
		final double effectiveLoad = load;
		double demandKw = modules.stream().mapToDouble(module -> module.idleKw()
				+ (module.maxKw() - module.idleKw()) * effectiveLoad).sum();
		double creditsPerSecond = modules.stream().mapToDouble(Module::creditsPerSecond).sum() * effectiveLoad;
		return new RackStep(usedUnits, demandKw, creditsPerSecond, load, thermal,
				quantumBlocked, inletCelsius >= 40);
	}

	private static double clamp(double value, double minimum, double maximum) {
		return Math.max(minimum, Math.min(maximum, value));
	}

	public record RackStep(int usedUnits, double demandKw, double creditsPerSecond,
			double load, double thermalFactor, boolean quantumBlocked, boolean tripped) {}
}