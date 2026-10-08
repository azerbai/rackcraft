package dev.rackcraft.world;

import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.world.PersistentState;

/**
 * What a dimension has put in orbit, and what it does down here. Like the RackCoin balance it is per dimension.
 *
 * <ul>
 *   <li>Comms Satellites (up to {@link #MAX_COMMS} count): Darknet parcels 10% sooner and a Compute Lease slot each.</li>
 *   <li>Orbital Data Centers: +{@link #COMPUTE_PER_MODULE} AI and general compute on every rack each, no limit.</li>
 *   <li>Dyson Mirrors: {@link #MIRROR_KW} each, beamed down to the Rectennas, no limit.</li>
 * </ul>
 * Survey Satellites do their job once and leave nothing behind but a count.
 */
public final class OrbitState extends PersistentState {
	private static final String STATE_KEY = "rackcraft_orbit";
	public static final int MAX_COMMS = 5;
	public static final double COMPUTE_PER_MODULE = 0.03;
	public static final double MIRROR_KW = 2000;
	public static final double RECTENNA_MAX_KW = 20_000;

	private int comms;
	private int datacenters;
	private int mirrors;
	private int surveys;
	private int launches;
	private int failures;

	public static OrbitState get(ServerWorld world) {
		return world.getPersistentStateManager().getOrCreate(OrbitState::fromNbt, OrbitState::new, STATE_KEY);
	}

	/** The orbit of a world that has one, without creating it (so effects stay cheap for worlds that never launched). */
	public static OrbitState existing(ServerWorld world) {
		return world.getPersistentStateManager().get(OrbitState::fromNbt, STATE_KEY);
	}

	public int comms() { return comms; }
	public int activeComms() { return Math.min(MAX_COMMS, comms); }
	public int datacenters() { return datacenters; }
	public int mirrors() { return mirrors; }
	public int surveys() { return surveys; }
	public int launches() { return launches; }
	public int failures() { return failures; }

	public void add(String payload) {
		switch (payload) {
			case "comms_satellite" -> comms++;
			case "orbital_datacenter" -> datacenters++;
			case "dyson_mirror" -> mirrors++;
			case "survey_satellite" -> surveys++;
			default -> {}
		}
		launches++;
		markDirty();
	}

	public void failed() {
		failures++;
		launches++;
		markDirty();
	}

	public void reset() {
		comms = datacenters = mirrors = surveys = launches = failures = 0;
		markDirty();
	}

	/** Multiplier on every rack's AI and general compute. */
	public static double computeFactor(ServerWorld world) {
		OrbitState orbit = existing(world);
		return orbit == null ? 1 : 1 + COMPUTE_PER_MODULE * orbit.datacenters;
	}

	/** Darknet deliveries take this share of their usual time. */
	public static double deliveryFactor(ServerWorld world) {
		OrbitState orbit = existing(world);
		return orbit == null ? 1 : 1 - 0.1 * orbit.activeComms();
	}

	public static int extraLeaseSlots(ServerWorld world) {
		OrbitState orbit = existing(world);
		return orbit == null ? 0 : orbit.activeComms();
	}

	/** Power beamed down to each Rectenna: the swarm's output shared between them, up to a Rectenna's limit. */
	public static double rectennaKw(ServerWorld world, int rectennas) {
		OrbitState orbit = existing(world);
		if (orbit == null || rectennas <= 0) return 0;
		return Math.min(RECTENNA_MAX_KW, orbit.mirrors * MIRROR_KW / rectennas);
	}

	@Override
	public NbtCompound writeNbt(NbtCompound nbt) {
		nbt.putInt("Comms", comms);
		nbt.putInt("Datacenters", datacenters);
		nbt.putInt("Mirrors", mirrors);
		nbt.putInt("Surveys", surveys);
		nbt.putInt("Launches", launches);
		nbt.putInt("Failures", failures);
		return nbt;
	}

	private static OrbitState fromNbt(NbtCompound nbt) {
		OrbitState orbit = new OrbitState();
		orbit.comms = nbt.getInt("Comms");
		orbit.datacenters = nbt.getInt("Datacenters");
		orbit.mirrors = nbt.getInt("Mirrors");
		orbit.surveys = nbt.getInt("Surveys");
		orbit.launches = nbt.getInt("Launches");
		orbit.failures = nbt.getInt("Failures");
		return orbit;
	}
}
