package dev.rackcraft.sim;

import java.util.List;
import java.util.Random;

public final class EventScheduler {
	private final Random random;
	private final double eventsPerHour;
	private final int minimumGapSeconds;
	private long secondsSinceLastEvent;
	private long elapsedSeconds;

	public EventScheduler(long seed, double eventsPerHour, int minimumGapSeconds) {
		this.random = new Random(seed);
		this.eventsPerHour = Math.max(0, eventsPerHour);
		this.minimumGapSeconds = Math.max(0, minimumGapSeconds);
		this.secondsSinceLastEvent = minimumGapSeconds;
	}

	public String tickSecond(boolean enabled, boolean hasRacks, boolean facilityMature) {
		elapsedSeconds++;
		secondsSinceLastEvent++;
		if (!enabled || !hasRacks || !facilityMature || secondsSinceLastEvent < minimumGapSeconds) return null;
		if (random.nextDouble() >= eventsPerHour / 3600.0) return null;
		secondsSinceLastEvent = 0;
		return chooseEvent();
	}

	private String chooseEvent() {
		List<String> ids = List.of("utility_outage", "cooling_failure", "hardware_failure",
				"cable_cut", "heat_wave", "surge");
		int[] weights = {3, 3, 4, 2, 1, 1};
		int roll = random.nextInt(14);
		for (int index = 0; index < weights.length; index++) {
			roll -= weights[index];
			if (roll < 0) return ids.get(index);
		}
		return ids.get(0);
	}

	public long elapsedSeconds() { return elapsedSeconds; }
	public long secondsSinceLastEvent() { return secondsSinceLastEvent; }
}