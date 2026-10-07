package dev.rackcraft.compute;

import java.util.List;
import java.util.Random;
import net.minecraft.nbt.NbtCompound;

/**
 * A Compute Lease: a big client rents a block of AI compute for a while, with an uptime guarantee.
 * "Creeper Insurance Co. needs 2,400 AI for 30 minutes at 99.9% uptime: 3,100,000 RC". Unlocked by the
 * Enterprise Sales Team research.
 *
 * <p>While it runs, the scheduler lends it racks from online Auto clusters ahead of contracts. Each step counts
 * the share of the promised compute that was delivered; uptime is the average. At the end it pays in full if
 * uptime met the guarantee, and loses 5% of the pay for every 0.1% it fell short (so 2% short pays nothing).
 * A lease that can no longer reach a paying uptime is breached early. Offers are sized to the facility, so
 * there is always a bigger one.
 */
public final class Lease {
	/** RackCoin per AI-compute-second at a 99% guarantee: below contracts, but there's far more of it. */
	public static final double RC_PER_WORK = 1.15;
	/** Pay lost per unit of uptime short of the guarantee: 50 means 2% short pays nothing. */
	public static final double SHORTFALL_PENALTY = 50;
	/** Smallest AI capacity a facility needs before anyone offers it a lease. */
	public static final double MIN_FACILITY_AI = 200;

	public enum State { OFFERED, RUNNING, DONE, BREACHED, CANCELLED }

	private static final List<String> CLIENTS = List.of(
			"Creeper Insurance Co.", "Wandering Trader LLC", "Piglin Gold Exchange", "Ender Pearl Express", "Strider Rideshare",
			"Allay Logistics", "Nether Portal Holdings", "End City Real Estate", "The Illager Council", "Villager Trading Hall Analytics",
			"Sniffer Seed Capital", "Ghast Tears Pharmaceuticals", "Warden Security Solutions", "Pillager Outpost Consulting");

	private static final List<String> PURPOSES = List.of(
			"a chatbot that sells insurance", "fraud detection on emerald trades", "a recommendation engine for hay bales",
			"a model that predicts raids", "an AI that writes villager trade offers", "a nightly batch of very important spreadsheets",
			"rendering a metaverse nobody visits", "translating Piglin bartering contracts", "an AI assistant for their AI assistant",
			"backtesting a RackCoin trading strategy", "a model that rates other models", "simulating ten thousand customer complaints");

	public int id;
	public String client;
	public String purpose;
	/** AI compute promised, per second. */
	public double compute;
	public long durationTicks;
	/** The uptime guarantee, 0.99 or 0.999. */
	public double sla;
	public long pay;
	public State state = State.OFFERED;
	public long offerExpires;
	/** Ticks it has been running. */
	public long elapsed;
	/** Ticks' worth of the promised compute actually delivered. */
	public double delivered;
	public long earned;

	// Live figures; not saved.
	public double rate;
	public String status = "";

	public double uptime() {
		return elapsed <= 0 ? 1 : delivered / elapsed;
	}

	/** The best uptime it could still finish with, if every remaining tick is delivered in full. */
	public double bestUptime() {
		return (delivered + Math.max(0, durationTicks - elapsed)) / (double) durationTicks;
	}

	/** What it pays at this uptime: in full at the guarantee, less 5% per 0.1% short. */
	public static double payFraction(double uptime, double sla) {
		if (uptime >= sla - 1e-9) return 1;
		return Math.max(0, 1 - (sla - uptime) * SHORTFALL_PENALTY);
	}

	/** Counts one step: {@code fraction} of the promised compute delivered over {@code ticks}. */
	public void record(double fraction, long ticks) {
		elapsed += ticks;
		delivered += Math.max(0, Math.min(1, fraction)) * ticks;
	}

	public boolean finished() {
		return elapsed >= durationTicks;
	}

	/** Can no longer finish with any pay at all. */
	public boolean hopeless() {
		return payFraction(bestUptime(), sla) <= 0;
	}

	/** A new offer sized to the facility: 15% to 50% of its AI compute, for 10 minutes to an hour. */
	static Lease roll(int id, Random random, long now, double facilityAi) {
		Lease lease = new Lease();
		lease.id = id;
		lease.client = CLIENTS.get(random.nextInt(CLIENTS.size()));
		lease.purpose = PURPOSES.get(random.nextInt(PURPOSES.size()));
		double share = 0.15 + random.nextDouble() * 0.35;
		lease.compute = Math.max(100, Math.round(facilityAi * share / 50) * 50);
		int[] minutes = {10, 20, 30, 45, 60};
		lease.durationTicks = 20L * 60 * minutes[random.nextInt(minutes.length)];
		boolean strict = random.nextInt(3) == 0;
		lease.sla = strict ? 0.999 : 0.99;
		lease.pay = Math.round(lease.compute * lease.durationTicks / 20.0 * RC_PER_WORK * (strict ? 1.3 : 1));
		lease.offerExpires = now + 20 * 60 * 10;
		return lease;
	}

	NbtCompound toNbt() {
		NbtCompound tag = new NbtCompound();
		tag.putInt("Id", id);
		tag.putString("Client", client);
		tag.putString("Purpose", purpose);
		tag.putDouble("Compute", compute);
		tag.putLong("Duration", durationTicks);
		tag.putDouble("Sla", sla);
		tag.putLong("Pay", pay);
		tag.putString("State", state.name());
		tag.putLong("OfferExpires", offerExpires);
		tag.putLong("Elapsed", elapsed);
		tag.putDouble("Delivered", delivered);
		tag.putLong("Earned", earned);
		return tag;
	}

	static Lease fromNbt(NbtCompound tag) {
		Lease lease = new Lease();
		lease.id = tag.getInt("Id");
		lease.client = tag.getString("Client");
		lease.purpose = tag.getString("Purpose");
		lease.compute = Math.max(1, tag.getDouble("Compute"));
		lease.durationTicks = Math.max(1, tag.getLong("Duration"));
		lease.sla = tag.getDouble("Sla") > 0 ? tag.getDouble("Sla") : 0.99;
		lease.pay = tag.getLong("Pay");
		try {
			lease.state = State.valueOf(tag.getString("State"));
		} catch (IllegalArgumentException ignored) {
			lease.state = State.CANCELLED;
		}
		lease.offerExpires = tag.getLong("OfferExpires");
		lease.elapsed = tag.getLong("Elapsed");
		lease.delivered = tag.getDouble("Delivered");
		lease.earned = tag.getLong("Earned");
		return lease;
	}
}
