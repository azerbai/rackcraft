package dev.rackcraft.sim;

import java.util.List;

public final class ServerModel {
	private ServerModel() {}

	/** Module bays in a Server Rack (bigger racks have more, see {@link Tier}). Every module takes one bay, whatever its size. */
	public static final int BAYS = 8;

	/**
	 * Rack modules. Besides mining, each lends general compute (autocrafting) and AI compute (contract
	 * generation and model training). AI compute is priced so general hardware earns a little more on
	 * contracts than mining, and the Tensor Accelerator, which cannot mine, earns about a third more per bay than a mining GPU.
	 */
	public enum Module {
		PI_NODE("pi_node", 0.05, 0.15, 0.5, 1, 0.4, false, 1),
		SERVER_1U("server_1u", 0.2, 0.6, 2, 2, 1.6, false, 1),
		ASIC_MINER("asic_miner", 0.4, 1.2, 5, 0, 0, true, 1),
		GPU_BLADE("gpu_blade", 1, 3, 12, 6, 10, true, 2),
		QUANTUM_CORE("quantum_core", 3, 9, 60, 20, 50, true, 3),
		TENSOR_ACCELERATOR("tensor_accelerator", 0.8, 2.6, 0, 3, 12, true, 2),
		/** Autocrafting specialists: lots of general compute, no mining, little AI. */
		CRAFTING_COPROCESSOR("crafting_coprocessor", 0.15, 0.9, 0, 8, 0.5, false, 1),
		CRAFTING_ACCELERATOR("crafting_accelerator", 0.8, 3.5, 0, 30, 2, true, 2),
		/**
		 * The endgame AI module, only made by a Wafer Fab: a whole silicon wafer as one chip. Three Quantum Cores' AI
		 * compute in one bay for less power each, but 16 kW of heat per bay. It can't mine.
		 */
		WAFER_SCALE_ENGINE("wafer_scale_engine", 2, 16, 0, 40, 150, true, 3),
		/**
		 * An FPGA, in whichever of its three modes its stack is set to (see {@link #of}): it mines, serves AI or
		 * autocrafts at about two thirds of what the specialist module does. Air-cooled.
		 */
		FPGA_MINING("fpga_module", 0.4, 2.5, 8, 1, 1, false, 2),
		FPGA_AI("fpga_module", 0.4, 2.5, 0, 2, 8, false, 2),
		FPGA_CRAFTING("fpga_module", 0.4, 2.5, 0, 20, 1, false, 2),
		/** Inference only: its AI compute serves contracts and leases, never training or R&D. Cool and frugal. */
		NPU_CARD("npu_card", 0.3, 1.8, 0, 2, 32, false, 3),
		/** Spiking silicon: a Crafting Accelerator's work several times over, on almost no power. */
		NEUROMORPHIC_CORE("neuromorphic_core", 0.2, 1.2, 0, 80, 4, false, 3),
		/** Matrix maths done in light: twice a Wafer-Scale Engine's AI compute in one bay, for less heat. */
		PHOTONIC_TENSOR_CORE("photonic_tensor_core", 3, 12, 0, 60, 300, true, 4),
		/** Mines twice what a Quantum Core does, but its qubits need a Cryostat beside the rack instead of a CDU. */
		QUANTUM_ANNEALER("quantum_annealer", 4, 14, 120, 10, 30, true, 4);

		private final String itemId;
		private final double idleKw;
		private final double maxKw;
		private final double creditsPerSecond;
		private final double compute;
		private final double aiCompute;
		private final boolean liquidCooled;
		private final int tier;

		Module(String itemId, double idleKw, double maxKw, double creditsPerSecond, double compute, double aiCompute,
				boolean liquidCooled, int tier) {
			this.itemId = itemId;
			this.idleKw = idleKw;
			this.maxKw = maxKw;
			this.creditsPerSecond = creditsPerSecond;
			this.compute = compute;
			this.aiCompute = aiCompute;
			this.liquidCooled = liquidCooled;
			this.tier = tier;
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
		/** 1 for starter hardware up to 4 for the newest; an Exascale Cabinet only takes tier 2 and up. */
		public int tier() { return tier; }
		/** NPUs: AI compute only for contracts and leases. */
		public boolean inferenceOnly() { return this == NPU_CARD; }
		/** Too valuable to be lost to a hardware failure event. */
		public boolean hardened() { return tier >= 3; }

		/** The module this item is; an FPGA in its default (mining) mode. */
		public static Module byItemId(String itemId) {
			for (Module module : values()) if (module.itemId.equals(itemId)) return module;
			return null;
		}

		/** The module this item is, with an FPGA in {@code fpgaMode} (0 mining, 1 AI, 2 autocrafting). */
		public static Module of(String itemId, int fpgaMode) {
			if (itemId.equals("fpga_module")) return switch (fpgaMode) {
				case 1 -> FPGA_AI;
				case 2 -> FPGA_CRAFTING;
				default -> FPGA_MINING;
			};
			return byItemId(itemId);
		}
	}

	/** The most bays any rack has (an Exascale Cabinet). */
	public static final int MAX_BAYS = 24;

	/**
	 * Seconds a rack takes to boot from cold once it has power: a few per module, more for bigger hardware, so a
	 * full rack of Quantum Cores takes two minutes. Load (and with it mining and power draw) ramps up as it boots.
	 */
	public static double bootSeconds(List<Module> modules) {
		return modules.stream().mapToDouble(module -> switch (module) {
			case PI_NODE -> 2;
			case SERVER_1U, CRAFTING_COPROCESSOR -> 4;
			case ASIC_MINER -> 5;
			case GPU_BLADE, TENSOR_ACCELERATOR, CRAFTING_ACCELERATOR, FPGA_MINING, FPGA_AI, FPGA_CRAFTING, NPU_CARD, NEUROMORPHIC_CORE -> 8;
			case QUANTUM_CORE -> 15;
			case WAFER_SCALE_ENGINE, PHOTONIC_TENSOR_CORE -> 20;
			case QUANTUM_ANNEALER -> 25;
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
		double powered = powerLoad(load);
		return modules.stream().filter(Module::liquidCooled)
				.mapToDouble(module -> module.idleKw() + (module.maxKw() - module.idleKw()) * powered).sum() * LIQUID_SHARE;
	}

	/** The most a rack can be pushed to, as a share of its rated load: 150% with Liquid Hydrogen Cooling. */
	public static final double MAX_OVERCLOCK = 1.5;

	/**
	 * What a load draws (and so heats) as a share of the rated range. Up to 100% it is the load itself; an overclocked
	 * rack makes load times as much work but pays the square of it, so 125% costs 156% and 150% costs 225%.
	 */
	public static double powerLoad(double load) {
		return load > 1 ? load * load : load;
	}

	/**
	 * Rack tiers. Each is a different block; they share everything but their bays and how they deal with heat.
	 *
	 * @param bays           module bays
	 * @param doorKw         a built-in rear-door cooler: this much of the rack's air heat goes into its loop
	 * @param immersed       every module sits in dielectric fluid: all the heat goes to the loop, none to the air, and the
	 *                       rack won't run off a loop at all
	 * @param bonus          compute and mining multiplier
	 * @param minModuleTier  the lowest module tier it takes
	 * @param bandwidth      share of the usual fiber bandwidth its mining needs (a photonic fabric cuts it)
	 * @param overheadKw     what its own switches and pumps draw while it has power
	 */
	public enum Tier {
		SERVER("server_rack", 8, 0, false, 1, 1, 1, 0),
		HIGH_DENSITY("high_density_rack", 12, 40, false, 1, 1, 1, 0.5),
		IMMERSION("immersion_rack", 16, 0, true, 1, 1, 1, 2),
		EXASCALE("exascale_cabinet", 24, 0, true, 1.2, 2, 0.5, 30);

		private final String blockId;
		private final int bays;
		private final double doorKw;
		private final boolean immersed;
		private final double bonus;
		private final int minModuleTier;
		private final double bandwidth;
		private final double overheadKw;

		Tier(String blockId, int bays, double doorKw, boolean immersed, double bonus, int minModuleTier, double bandwidth,
				double overheadKw) {
			this.blockId = blockId;
			this.bays = bays;
			this.doorKw = doorKw;
			this.immersed = immersed;
			this.bonus = bonus;
			this.minModuleTier = minModuleTier;
			this.bandwidth = bandwidth;
			this.overheadKw = overheadKw;
		}

		public String blockId() { return blockId; }
		public int bays() { return bays; }
		public double doorKw() { return doorKw; }
		public boolean immersed() { return immersed; }
		public double bonus() { return bonus; }
		public int minModuleTier() { return minModuleTier; }
		public double bandwidth() { return bandwidth; }
		public double overheadKw() { return overheadKw; }
		public boolean accepts(Module module) { return module != null && module.tier() >= minModuleTier; }

		/** The tier of this block, or null if it isn't a rack (the Creative Rack isn't). */
		public static Tier of(String blockId) {
			for (Tier tier : values()) if (tier.blockId.equals(blockId)) return tier;
			return null;
		}

		public static boolean isRack(String blockId) { return of(blockId) != null; }
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
		return calculate(Tier.SERVER, modules, loadLimitPercent, powerSatisfaction, inletCelsius, quantumHasCdu, true,
				liquidCooling, boot);
	}

	/**
	 * {@code cryostat}: whether a working Cryostat sits beside the rack, which Quantum Annealers need. An immersion rack
	 * needs its loop whatever is in it; its own overhead draw is added while it has power. Mining is scaled by the
	 * tier's bonus.
	 */
	public static RackStep calculate(Tier tier, List<Module> modules, int loadLimitPercent, double powerSatisfaction,
			double inletCelsius, boolean quantumHasCdu, boolean cryostat, boolean liquidCooling, double boot) {
		int usedBays = modules.size();
		if (usedBays > tier.bays()) throw new IllegalArgumentException("This rack has " + tier.bays() + " bays");
		boolean quantumBlocked = modules.contains(Module.QUANTUM_CORE) && !quantumHasCdu;
		boolean cryoBlocked = modules.contains(Module.QUANTUM_ANNEALER) && !cryostat;
		double thermal = thermalFactor(inletCelsius);
		double power = clamp(powerSatisfaction, 0, 1);
		double load = clamp(loadLimitPercent / 100.0, 0, MAX_OVERCLOCK) * power * thermal * clamp(boot, 0, 1);
		boolean waterBlocked = !liquidCooling && !modules.isEmpty() && (tier.immersed() || needsLiquidCooling(modules));
		if (power < 0.5 || thermal == 0 || quantumBlocked || cryoBlocked || waterBlocked) load = 0;
		final double effectiveLoad = load;
		final double poweredLoad = powerLoad(effectiveLoad);
		double demandKw = modules.stream().mapToDouble(module -> module.idleKw()
				+ (module.maxKw() - module.idleKw()) * poweredLoad).sum() + (modules.isEmpty() ? 0 : tier.overheadKw());
		double creditsPerSecond = modules.stream().mapToDouble(Module::creditsPerSecond).sum() * effectiveLoad * tier.bonus();
		return new RackStep(usedBays, demandKw, creditsPerSecond, load, thermal,
				quantumBlocked, inletCelsius >= 40, waterBlocked, cryoBlocked);
	}

	private static double clamp(double value, double minimum, double maximum) {
		return Math.max(minimum, Math.min(maximum, value));
	}

	public record RackStep(int usedBays, double demandKw, double creditsPerSecond,
			double load, double thermalFactor, boolean quantumBlocked, boolean tripped, boolean waterBlocked, boolean cryoBlocked) {
		public RackStep(int usedBays, double demandKw, double creditsPerSecond, double load, double thermalFactor,
				boolean quantumBlocked, boolean tripped, boolean waterBlocked) {
			this(usedBays, demandKw, creditsPerSecond, load, thermalFactor, quantumBlocked, tripped, waterBlocked, false);
		}
	}
}