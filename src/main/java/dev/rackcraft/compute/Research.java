package dev.rackcraft.compute;

import java.util.List;

/**
 * The R&D programme: projects that turn a big cluster's spare compute and RackCoin into permanent upgrades.
 * Each project costs RackCoin up front and then a pile of compute-seconds, which idle racks on Auto clusters
 * work through (they stop mining while they do).
 *
 * <ul>
 *   <li><b>Projects</b> are one-off upgrades, in tiers: later ones need earlier ones.</li>
 *   <li><b>Repeatables</b> never end: each level costs 2.5 times the RackCoin and twice the compute of the last.</li>
 *   <li><b>Frontier runs</b> train ever bigger models on one cluster, which must keep at least
 *       {@link Project#minCluster} AI compute running the whole time. A run that loses its compute (an outage,
 *       a trip, an overheated hall) rolls back to its last checkpoint. The last one is an AGI.</li>
 * </ul>
 *
 * Ids are saved; never rename them.
 */
public final class Research {
	public enum Kind { GENERAL, AI }

	public enum Type { PROJECT, REPEATABLE, FRONTIER }

	/**
	 * @param credits    RackCoin paid when it starts (for a repeatable, level 0's price)
	 * @param work       compute-seconds of {@code kind} to finish it (for a repeatable, level 0's)
	 * @param requires   projects that must be finished first
	 * @param minCluster frontier runs only: AI compute one cluster must sustain
	 * @param effect     what it does, for the terminal
	 */
	public record Project(String id, String name, Type type, Kind kind, long credits, double work, List<String> requires,
			double minCluster, String effect, String blurb) {
		public boolean repeatable() { return type == Type.REPEATABLE; }
		public boolean frontier() { return type == Type.FRONTIER; }

		/** RackCoin for this level: repeatables cost 2.5 times as much each level. */
		public long credits(int level) {
			return repeatable() ? Math.round(credits * Math.pow(2.5, level)) : credits;
		}

		/** Compute-seconds for this level: repeatables take twice as long each level. */
		public double work(int level) {
			return repeatable() ? work * Math.pow(2, level) : work;
		}
	}

	/** Frontier runs save a checkpoint every this share of the run. */
	public static final double CHECKPOINT = 0.05;

	public static final String AGI = "frontier_5";

	public static final List<Project> PROJECTS = List.of(
			// Tier 1
			project("firmware", "Custom Firmware", Kind.GENERAL, 50_000, 2e6, List.of(),
					"Racks boot twice as fast and mine 5% more",
					"Someone finally read the BIOS settings. Turns out the power-saving mode was on the whole time."),
			project("psu_titanium", "80 PLUS Titanium PSUs", Kind.GENERAL, 100_000, 3e6, List.of(),
					"Racks draw 10% less power, and make 10% less heat",
					"Better power supplies. The old ones were rated 80 PLUS Cardboard."),
			project("coolant_chemistry", "Coolant Chemistry", Kind.GENERAL, 100_000, 3e6, List.of(),
					"Every heat sink takes 20% more heat out of its loop",
					"Glycol, corrosion inhibitors and a strongly worded memo about not drinking it."),
			project("synthetic_data", "Synthetic Data", Kind.AI, 150_000, 4e6, List.of(),
					"Training a model takes 40% less compute per item",
					"Train the models on the models' own output. What could go wrong."),
			project("enterprise_sales", "Enterprise Sales Team", Kind.AI, 250_000, 2e6, List.of(),
					"Unlocks Compute Leases: big clients rent your AI compute by the hour, with an uptime guarantee",
					"Hire people in quarter-zip fleeces to say \"synergy\" at bigger companies."),
			// Tier 2
			project("thermal_envelope", "ASHRAE A2 Envelope", Kind.GENERAL, 400_000, 1e7, List.of("coolant_chemistry"),
					"Racks throttle from 30 C instead of 27, and trip at 43 C instead of 40",
					"The hardware was always fine at 30 C. The warranty department has been informed."),
			project("predictive_maintenance", "Predictive Maintenance", Kind.GENERAL, 300_000, 8e6, List.of("firmware"),
					"Hardware failure events no longer destroy modules",
					"A model that watches the fans and says \"that one\" a week before it dies."),
			project("distillation", "Distillation", Kind.AI, 500_000, 1.2e7, List.of("synthetic_data"),
					"Every model makes contract work with 20% less compute",
					"Teach a small model to imitate a big one. The small one is smug about it."),
			project("reactor_uprate", "Reactor Uprate", Kind.GENERAL, 1_000_000, 1.5e7, List.of("psu_titanium"),
					"Modular Reactors make 20% more power from the same fuel",
					"Recalculated the safety margins. They were very generous margins."),
			project("lithography", "Extreme UV Lithography", Kind.GENERAL, 2_000_000, 2.5e7, List.of("psu_titanium", "coolant_chemistry"),
					"Unlocks the Wafer Fab, which makes Wafer-Scale Engines",
					"Tin droplets, lasers and a machine the size of a bus. Now you can make your own chips."),
			// Megastructures: bigger cube multiblocks, so a new world can't build a 10-cube on day one.
			project("structural_engineering", "Structural Engineering", Kind.GENERAL, 5_000_000, 1e8, List.of("reactor_uprate"),
					"Cube multiblocks can be built up to 7x7x7",
					"Someone with a hard hat ran the numbers. The cube can be bigger if you stop leaning on it."),
			project("space_frames", "Space Frame Design", Kind.GENERAL, 25_000_000, 4e8, List.of("structural_engineering"),
					"Cube multiblocks can be built up to 9x9x9",
					"Triangles. It was triangles all along."),
			project("arcology", "Arcology", Kind.GENERAL, 100_000_000, 1.5e9, List.of("space_frames"),
					"Cube multiblocks can be built up to 10x10x10",
					"A machine so large it has its own weather, its own postcode and a cafe nobody can find."),
			// Frontier runs
			frontier("frontier_1", "Gemerald Ultra", 1_000_000, 2.5e7, 1_000, List.of("distillation", "enterprise_sales"),
					"AI contracts pay 25% more",
					"A frontier model. Clients pay extra for anything with \"Ultra\" in the name."),
			frontier("frontier_2", "Gemerald Ultra Max", 5_000_000, 1e8, 4_000, List.of("frontier_1", "lithography"),
					"Three Compute Leases at once, paying 25% more",
					"Same as Ultra, but more. The launch video has a string quartet."),
			frontier("frontier_3", "Gemerald Ultra Max Pro", 25_000_000, 4e8, 12_000, List.of("frontier_2"),
					"Every rack lends 20% more AI compute: the model writes its own kernels",
					"Writes better GPU kernels than the people who wrote the GPU."),
			frontier("frontier_4", "Gemerald Infinity (Preview)", 100_000_000, 1.5e9, 30_000, List.of("frontier_3"),
					"Every rack lends 50% more general compute and mines 25% more",
					"Available to a limited preview audience of you."),
			frontier(AGI, "HEROBRINE-1", 500_000_000, 6e9, 80_000, List.of("frontier_4"),
					"Artificial general intelligence. Mining +50%, all compute +25%. It will also have opinions",
					"It was always there, in the weights. Removed in a later version, they said."),
			// Repeatables
			repeatable("rep_mining", "Hash Kernel Tuning", Kind.GENERAL, 200_000, 4e6, List.of("firmware"),
					"+5% mining per level"),
			repeatable("rep_cooling", "Cooling Science", Kind.GENERAL, 200_000, 5e6, List.of("coolant_chemistry"),
					"+5% heat sink capacity per level"),
			repeatable("rep_power", "Power Electronics", Kind.GENERAL, 200_000, 5e6, List.of("psu_titanium"),
					"3% less rack power per level"),
			repeatable("rep_ai", "Inference Optimisation", Kind.AI, 300_000, 1e7, List.of("distillation"),
					"+5% AI compute per level"));

	private Research() {}

	public static Project get(String id) {
		for (Project project : PROJECTS) if (project.id().equals(id)) return project;
		return null;
	}

	public static int index(String id) {
		for (int index = 0; index < PROJECTS.size(); index++) if (PROJECTS.get(index).id().equals(id)) return index;
		return -1;
	}

	private static Project project(String id, String name, Kind kind, long credits, double work, List<String> requires,
			String effect, String blurb) {
		return new Project(id, name, Type.PROJECT, kind, credits, work, requires, 0, effect, blurb);
	}

	private static Project frontier(String id, String name, long credits, double work, double minCluster, List<String> requires,
			String effect, String blurb) {
		return new Project(id, name, Type.FRONTIER, Kind.AI, credits, work, requires, minCluster, effect, blurb);
	}

	private static Project repeatable(String id, String name, Kind kind, long credits, double work, List<String> requires,
			String effect) {
		return new Project(id, name, Type.REPEATABLE, kind, credits, work, requires, 0, effect,
				"Never finished: every level costs 2.5 times the RackCoin and twice the compute of the last.");
	}

	/**
	 * Everything finished research does, multiplied together. One instance per research state, rebuilt whenever a
	 * project completes.
	 */
	public record Effects(double mining, double aiCompute, double generalCompute, double rackPower, double sinkCapacity,
			double thermalOffset, double bootScale, double trainingWork, double contractWork, double contractPay,
			double leasePay, int leaseSlots, double reactorOutput, boolean leases, boolean lithography,
			boolean safeHardware, boolean agi, int maxCubeEdge) {
		public static final Effects NONE = new Effects(1, 1, 1, 1, 1, 0, 1, 1, 1, 1, 1, 0, 1, false, false, false, false,
				dev.rackcraft.world.ReactorArrays.BASE_EDGE);
	}

	/** The combined effect of these finished levels (0 for not done; a repeatable's level otherwise). */
	public static Effects effects(java.util.function.ToIntFunction<String> level) {
		boolean f1 = level.applyAsInt("frontier_1") > 0;
		boolean f2 = level.applyAsInt("frontier_2") > 0;
		boolean f3 = level.applyAsInt("frontier_3") > 0;
		boolean f4 = level.applyAsInt("frontier_4") > 0;
		boolean agi = level.applyAsInt(AGI) > 0;
		double mining = (level.applyAsInt("firmware") > 0 ? 1.05 : 1) * (f4 ? 1.25 : 1) * (agi ? 1.5 : 1)
				* (1 + 0.05 * level.applyAsInt("rep_mining"));
		double ai = (f3 ? 1.2 : 1) * (agi ? 1.25 : 1) * (1 + 0.05 * level.applyAsInt("rep_ai"));
		double general = (f4 ? 1.5 : 1) * (agi ? 1.25 : 1);
		double power = (level.applyAsInt("psu_titanium") > 0 ? 0.9 : 1) * Math.pow(0.97, level.applyAsInt("rep_power"));
		double sinks = (level.applyAsInt("coolant_chemistry") > 0 ? 1.2 : 1) * (1 + 0.05 * level.applyAsInt("rep_cooling"));
		boolean leases = level.applyAsInt("enterprise_sales") > 0;
		return new Effects(mining, ai, general, power, sinks,
				level.applyAsInt("thermal_envelope") > 0 ? 3 : 0,
				level.applyAsInt("firmware") > 0 ? 0.5 : 1,
				level.applyAsInt("synthetic_data") > 0 ? 0.6 : 1,
				level.applyAsInt("distillation") > 0 ? 0.8 : 1,
				f1 ? 1.25 : 1,
				f2 ? 1.25 : 1,
				!leases ? 0 : f2 ? 3 : 2,
				level.applyAsInt("reactor_uprate") > 0 ? 1.2 : 1,
				leases,
				level.applyAsInt("lithography") > 0,
				level.applyAsInt("predictive_maintenance") > 0,
				agi,
				level.applyAsInt("arcology") > 0 ? 10 : level.applyAsInt("space_frames") > 0 ? 9
						: level.applyAsInt("structural_engineering") > 0 ? 7 : dev.rackcraft.world.ReactorArrays.BASE_EDGE);
	}
}
