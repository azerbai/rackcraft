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
		PI_NODE("pi_node", 0.05, 0.15, 0.5, 1, 0.4, false),
		SERVER_1U("server_1u", 0.2, 0.6, 2, 2, 1.6, false),
		ASIC_MINER("asic_miner", 0.4, 1.2, 5, 0, 0, true),
		GPU_BLADE("gpu_blade", 1, 3, 12, 6, 10, true),
		QUANTUM_CORE("quantum_core", 3, 9, 60, 20, 50, true),
		TENSOR_ACCELERATOR("tensor_accelerator", 0.8, 2.6, 0, 3, 12, true);

		private final String itemId;
		private final double idleKw;
		private final double maxKw;
		private final double creditsPerSecond;
		private final double compute;
		private final double aiCompute;
		private final boolean liquidCooled;

		Module(String itemId, double idleKw, double maxKw, double creditsPerSecond, double compute, double aiCompute,
				boolean liquidCooled) {
			this.itemId = itemId;
			this.idleKw = idleKw;
			this.maxKw = maxKw;
			this.creditsPerSecond = creditsPerSecond;
			this.compute = compute;
			this.aiCompute = aiCompute;
			this.liquidCooled = liquidCooled;
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
		 * Tier 3 and up (ASIC Miners, GPU Blades, Tensor Accelerators, Quantum Cores) are liquid-cooled: the rack
		 * must sit on a coolant loop with somewhere to put the heat, and {@link #LIQUID_SHARE} of their heat goes
		 * into the loop instead of the air. Pi Nodes and 1U Servers are air-cooled.
		 */
		public boolean liquidCooled() { return liquidCooled; }

		public static Module byItemId(String itemId) {
			for (Module module : values()) if (module.itemId.equals(itemId)) return module;
			return null;
		}
	}

	/**
	 * Seconds a rack takes to boot from cold once it has power: a few per module, more for bigger hardware, so a
	 * full rack of Quantum Cores takes two minutes. Load (and with it mining and power draw) ramps up as it boots.
	 */
	public static double bootSeconds(List<Module> modules) {
		return modules.stream().mapToDouble(module -> switch (module) {
			case PI_NODE -> 2;
			case SERVER_1U -> 4;
			case ASIC_MINER -> 5;
			case GPU_BLADE, TENSOR_ACCELERATOR -> 8;
			case QUANTUM_CORE -> 15;
		}).sum();
	}

	public static double thermalFactor(double inletCelsius) {
		if (inletCelsius <= 27) return 1;
		if (inletCelsius < 32) return 1 - (inletCelsius - 27) * 0.4 / 5;
		if (inletCelsius < 40) return 0.6 - (inletCelsius - 32) * 0.35 / 8;
		return 0;
	}

	/** Share of a liquid-cooled module's heat its cold plates carry into the coolant loop; the rest leaves as warm air. */
	public static final double LIQUID_SHARE = 0.85;

	public static boolean needsLiquidCooling(List<Module> modules) {
		return modules.stream().anyMatch(Module::liquidCooled);
	}

	/** Of this much rack heat, how much the coolant loop takes when the rack is liquid-cooled. */
	public static double liquidHeatKw(List<Module> modules, double load) {
		return modules.stream().filter(Module::liquidCooled)
				.mapToDouble(module -> module.idleKw() + (module.maxKw() - module.idleKw()) * load).sum() * LIQUID_SHARE;
	}

	public static RackStep calculate(List<Module> modules, int loadLimitPercent,
			double powerSatisfaction, double inletCelsius, boolean quantumHasCdu) {
		return calculate(modules, loadLimitPercent, powerSatisfaction, inletCelsius, quantumHasCdu, true);
	}

	/** {@code liquidCooling}: whether the rack is on a coolant loop with heat sinks; liquid-cooled racks stop without one. */
	public static RackStep calculate(List<Module> modules, int loadLimitPercent,
			double powerSatisfaction, double inletCelsius, boolean quantumHasCdu, boolean liquidCooling) {
		return calculate(modules, loadLimitPercent, powerSatisfaction, inletCelsius, quantumHasCdu, liquidCooling, 1);
	}

	/** {@code boot}: how far the rack has booted, 0 to 1; its load is scaled by it. */
	public static RackStep calculate(List<Module> modules, int loadLimitPercent, double powerSatisfaction,
			double inletCelsius, boolean quantumHasCdu, boolean liquidCooling, double boot) {
		int usedBays = modules.size();
		if (usedBays > BAYS) throw new IllegalArgumentException("A rack has " + BAYS + " bays");
		boolean quantumBlocked = modules.contains(Module.QUANTUM_CORE) && !quantumHasCdu;
		double thermal = thermalFactor(inletCelsius);
		double power = clamp(powerSatisfaction, 0, 1);
		double load = clamp(loadLimitPercent / 100.0, 0, 1) * power * thermal * clamp(boot, 0, 1);
		boolean waterBlocked = !liquidCooling && needsLiquidCooling(modules);
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