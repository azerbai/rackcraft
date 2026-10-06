package dev.rackcraft.sim;

import java.util.List;

public final class ServerModel {
	private ServerModel() {}

	/** Module bays in a rack. Every module takes one bay, whatever its size. */
	public static final int BAYS = 8;

	/**
	 * Rack modules. Besides mining, each lends general compute (autocrafting) and AI compute (contract
	 * generation and model training). AI compute is priced so general hardware earns a little more on
	 * contracts than mining, and the Tensor Accelerator, which cannot mine, earns about a third more per bay than a mining GPU.
	 */
	public enum Module {
		PI_NODE("pi_node", 0.05, 0.15, 0.5, 1, 0.4, 0),
		SERVER_1U("server_1u", 0.2, 0.6, 2, 2, 1.6, 0),
		ASIC_MINER("asic_miner", 0.4, 1.2, 5, 0, 0, 1),
		GPU_BLADE("gpu_blade", 1, 3, 12, 6, 10, 2),
		QUANTUM_CORE("quantum_core", 3, 9, 60, 20, 50, 4),
		TENSOR_ACCELERATOR("tensor_accelerator", 0.8, 2.6, 0, 3, 12, 2);

		private final String itemId;
		private final double idleKw;
		private final double maxKw;
		private final double creditsPerSecond;
		private final double compute;
		private final double aiCompute;
		private final int waterUnits;

		Module(String itemId, double idleKw, double maxKw, double creditsPerSecond, double compute, double aiCompute,
				int waterUnits) {
			this.itemId = itemId;
			this.idleKw = idleKw;
			this.maxKw = maxKw;
			this.creditsPerSecond = creditsPerSecond;
			this.compute = compute;
			this.aiCompute = aiCompute;
			this.waterUnits = waterUnits;
		}

		public String itemId() { return itemId; }
		public double idleKw() { return idleKw; }
		public double maxKw() { return maxKw; }
		public double creditsPerSecond() { return creditsPerSecond; }
		/** General compute lent to autocrafting jobs, at full load. */
		public double compute() { return compute; }
		/** AI compute lent to contract generation and model training, at full load. */
		public double aiCompute() { return aiCompute; }
		/**
		 * Freshwater cooling this module needs from a Freshwater Pump on the rack's coolant network. Tier 3 and
		 * up (ASIC Miners, GPU Blades, Tensor Accelerators, Quantum Cores) need it; Pi Nodes and 1U Servers don't.
		 */
		public int waterUnits() { return waterUnits; }

		public static Module byItemId(String itemId) {
			for (Module module : values()) if (module.itemId.equals(itemId)) return module;
			return null;
		}
	}

	public static double thermalFactor(double inletCelsius) {
		if (inletCelsius <= 27) return 1;
		if (inletCelsius < 32) return 1 - (inletCelsius - 27) * 0.4 / 5;
		if (inletCelsius < 40) return 0.6 - (inletCelsius - 32) * 0.35 / 8;
		return 0;
	}

	public static int waterUnits(List<Module> modules) {
		return modules.stream().mapToInt(Module::waterUnits).sum();
	}

	public static RackStep calculate(List<Module> modules, int loadLimitPercent,
			double powerSatisfaction, double inletCelsius, boolean quantumHasCdu) {
		return calculate(modules, loadLimitPercent, powerSatisfaction, inletCelsius, quantumHasCdu, true);
	}

	/** {@code waterSupplied}: whether the rack's freshwater cooling is met; water-cooled racks stop without it. */
	public static RackStep calculate(List<Module> modules, int loadLimitPercent,
			double powerSatisfaction, double inletCelsius, boolean quantumHasCdu, boolean waterSupplied) {
		int usedBays = modules.size();
		if (usedBays > BAYS) throw new IllegalArgumentException("A rack has " + BAYS + " bays");
		boolean quantumBlocked = modules.contains(Module.QUANTUM_CORE) && !quantumHasCdu;
		double thermal = thermalFactor(inletCelsius);
		double power = clamp(powerSatisfaction, 0, 1);
		double load = clamp(loadLimitPercent / 100.0, 0, 1) * power * thermal;
		boolean waterBlocked = !waterSupplied && waterUnits(modules) > 0;
		if (power < 0.5 || thermal == 0 || quantumBlocked || waterBlocked) load = 0;
		final double effectiveLoad = load;
		double demandKw = modules.stream().mapToDouble(module -> module.idleKw()
				+ (module.maxKw() - module.idleKw()) * effectiveLoad).sum();
		double creditsPerSecond = modules.stream().mapToDouble(Module::creditsPerSecond).sum() * effectiveLoad;
		return new RackStep(usedBays, demandKw, creditsPerSecond, load, thermal,
				quantumBlocked, inletCelsius >= 40, waterBlocked);
	}

	private static double clamp(double value, double minimum, double maximum) {
		return Math.max(minimum, Math.min(maximum, value));
	}

	public record RackStep(int usedBays, double demandKw, double creditsPerSecond,
			double load, double thermalFactor, boolean quantumBlocked, boolean tripped, boolean waterBlocked) {}
}