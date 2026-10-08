package dev.rackcraft.world;

import java.util.ArrayDeque;
import java.util.Deque;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtList;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.world.PersistentState;

public final class FacilityManager extends PersistentState {
	private static final int DATA_VERSION = 1;
	private static final String STATE_KEY = "rackcraft_facilities";
	private long credits;
	private String activeContract = "none";
	private long randomSeed = 0x5241434B43524146L;
	private long eventTicks;
	private String activeEvent = "none";
	/** What the active event did, for the terminal: "cable cut at 12 64 -30". */
	private String eventDetail = "";
	private long activeEventRemainingTicks;
	private long lastEventTick;
	private final Deque<Double> availability = new ArrayDeque<>();

	public static FacilityManager get(ServerWorld world) {
		return world.getPersistentStateManager().getOrCreate(FacilityManager::fromNbt,
				FacilityManager::new, STATE_KEY);
	}

	public long credits() { return credits; }

	// Live mining figures; recomputed every simulation step, so they are not saved.
	private double miningRate;
	private int miningRacks;
	private int totalRacks;

	public double miningRate() { return miningRate; }
	public int miningRacks() { return miningRacks; }
	public int totalRacks() { return totalRacks; }
	public void setMiningStats(double rate, int mining, int total, int lent) {
		miningRate = rate;
		miningRacks = mining;
		totalRacks = total;
		lentRacks = lent;
	}
	/** Racks lent to autocrafting, AI work or R&D last step, so not mining. Not saved. */
	public int lentRacks() { return lentRacks; }
	private int lentRacks;
	public String activeContract() { return activeContract; }
	public long randomSeed() { return randomSeed; }
	public long eventTicks() { return eventTicks; }
	public String activeEvent() { return activeEvent; }
	public String eventDetail() { return activeEvent.equals("none") ? "" : eventDetail; }
	public void setEventDetail(String detail) {
		eventDetail = detail == null ? "" : detail;
		markDirty();
	}

	/**
	 * The longest each event can last. Saves from before one-off events were given 30 seconds kept them "active" for
	 * 1,200,000 ticks (about 17 hours), which left a stale event on the terminal and blocked new ones; the clock
	 * caps them.
	 */
	public static long maxDuration(String event) {
		return switch (event) {
			case "utility_outage" -> 3600;
			case "cooling_failure" -> 2400;
			case "heat_wave" -> 6000;
			default -> 600;
		};
	}
	public long activeEventRemainingTicks() { return activeEventRemainingTicks; }
	public long lastEventTick() { return lastEventTick; }
	public Deque<Double> availability() { return availability; }

	public void setContract(String contract) {
		activeContract = contract;
		markDirty();
	}

	public void addCredits(long amount) {
		credits = Math.max(0, credits + amount);
		markDirty();
	}

	public boolean spendCredits(long amount) {
		if (amount < 0 || credits < amount) return false;
		credits -= amount;
		markDirty();
		return true;
	}

	public void addAvailabilitySample(double sample) {
		availability.addLast(Math.max(0, Math.min(1, sample)));
		while (availability.size() > 2400) availability.removeFirst();
		markDirty();
	}

	public void advanceEventClock(long ticks) {
		eventTicks += ticks;
		if (!activeEvent.equals("none")) {
			activeEventRemainingTicks = Math.max(0, Math.min(maxDuration(activeEvent), activeEventRemainingTicks) - ticks);
			if (activeEventRemainingTicks == 0) activeEvent = "none";
		}
		markDirty();
	}

	public void triggerEvent(String id, long durationTicks) {
		activeEvent = id;
		activeEventRemainingTicks = Math.max(0, durationTicks);
		lastEventTick = eventTicks;
		markDirty();
	}

	public int nextRandomInt(int bound) {
		if (bound <= 0) throw new IllegalArgumentException("bound must be positive");
		randomSeed = randomSeed * 6364136223846793005L + 1442695040888963407L;
		markDirty();
		return (int) Long.remainderUnsigned(randomSeed >>> 1, bound);
	}

	@Override
	public NbtCompound writeNbt(NbtCompound nbt) {
		nbt.putInt("DataVersion", DATA_VERSION);
		nbt.putLong("Credits", credits);
		nbt.putString("ActiveContract", activeContract);
		nbt.putLong("RandomSeed", randomSeed);
		nbt.putLong("EventTicks", eventTicks);
		nbt.putString("ActiveEvent", activeEvent);
		nbt.putString("EventDetail", eventDetail);
		nbt.putLong("ActiveEventRemainingTicks", activeEventRemainingTicks);
		nbt.putLong("LastEventTick", lastEventTick);
		NbtList samples = new NbtList();
		availability.forEach(sample -> {
			NbtCompound entry = new NbtCompound();
			entry.putDouble("Value", sample);
			samples.add(entry);
		});
		nbt.put("Availability", samples);
		return nbt;
	}

	private static FacilityManager fromNbt(NbtCompound nbt) {
		FacilityManager state = new FacilityManager();
		state.credits = Math.max(0, nbt.getLong("Credits"));
		state.activeContract = nbt.getString("ActiveContract");
		if (state.activeContract.isBlank()) state.activeContract = "none";
		state.randomSeed = nbt.getLong("RandomSeed");
		state.eventTicks = Math.max(0, nbt.getLong("EventTicks"));
		state.activeEvent = nbt.getString("ActiveEvent");
		if (state.activeEvent.isBlank()) state.activeEvent = "none";
		state.eventDetail = nbt.getString("EventDetail");
		state.activeEventRemainingTicks = Math.max(0, nbt.getLong("ActiveEventRemainingTicks"));
		state.lastEventTick = Math.max(0, nbt.getLong("LastEventTick"));
		NbtList samples = nbt.getList("Availability", 10);
		for (int index = 0; index < samples.size(); index++) {
			state.availability.addLast(samples.getCompound(index).getDouble("Value"));
		}
		return state;
	}
}