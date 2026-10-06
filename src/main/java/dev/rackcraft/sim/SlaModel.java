package dev.rackcraft.sim;

import java.util.ArrayDeque;
import java.util.Deque;

public final class SlaModel {
	private final Deque<Double> availabilitySamples = new ArrayDeque<>();
	private final int windowSamples;

	public SlaModel(int windowSamples) {
		this.windowSamples = Math.max(1, windowSamples);
	}

	public void addSample(double availability) {
		availabilitySamples.addLast(Math.max(0, Math.min(1, availability)));
		while (availabilitySamples.size() > windowSamples) availabilitySamples.removeFirst();
	}

	public double averageAvailability() {
		return availabilitySamples.stream().mapToDouble(Double::doubleValue).average().orElse(1);
	}

	public boolean windowComplete() { return availabilitySamples.size() >= windowSamples; }
	public int sampleCount() { return availabilitySamples.size(); }
	public void clearWindow() { availabilitySamples.clear(); }

	public static ContractResult evaluate(Contract contract, double average, int rackCount,
			double redundantRackRatio) {
		boolean qualifies = rackCount >= contract.minimumRacks()
				&& (contract != Contract.GOLD || redundantRackRatio >= 0.9);
		boolean met = qualifies && average >= contract.minimumAvailability();
		return new ContractResult(met, met ? contract.bonusRate() : -contract.penaltyRate());
	}

	public enum Contract {
		BRONZE(0.90, 0.10, 0.05, 1),
		SILVER(0.97, 0.30, 0.15, 4),
		GOLD(0.995, 0.80, 0.40, 10);
		private final double minimumAvailability;
		private final double bonusRate;
		private final double penaltyRate;
		private final int minimumRacks;
		Contract(double minimumAvailability, double bonusRate, double penaltyRate, int minimumRacks) {
			this.minimumAvailability = minimumAvailability;
			this.bonusRate = bonusRate;
			this.penaltyRate = penaltyRate;
			this.minimumRacks = minimumRacks;
		}
		public double minimumAvailability() { return minimumAvailability; }
		public double bonusRate() { return bonusRate; }
		public double penaltyRate() { return penaltyRate; }
		public int minimumRacks() { return minimumRacks; }
	}

	public record ContractResult(boolean met, double payoutRate) {}
}