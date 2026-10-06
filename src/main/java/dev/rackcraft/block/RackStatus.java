package dev.rackcraft.block;

/** Why a rack is or is not mining, in priority order. Shown on the rack screen with a fix-it hint. */
public enum RackStatus {
	MINING(true),
	THROTTLED(true),
	NETWORK_LIMITED(true),
	EMPTY(false),
	TRIPPED(false),
	NO_POWER(false),
	NEEDS_CDU(false),
	OVERHEATED(false),
	NO_NETWORK(false);

	private final boolean mining;

	RackStatus(boolean mining) {
		this.mining = mining;
	}

	public boolean mining() { return mining; }

	public String translationKey() { return "rack_status.rackcraft." + name().toLowerCase(java.util.Locale.ROOT); }

	public String hintKey() { return translationKey() + ".hint"; }

	public static RackStatus byOrdinal(int ordinal) {
		RackStatus[] values = values();
		return ordinal >= 0 && ordinal < values.length ? values[ordinal] : EMPTY;
	}
}
