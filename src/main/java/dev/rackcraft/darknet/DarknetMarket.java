package dev.rackcraft.darknet;

import dev.rackcraft.world.FacilityManager;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.UUID;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.world.PersistentState;

/**
 * The darknet auction house behind the Darknet Terminal, one per dimension like the RackCoin balance it spends.
 *
 * <ul>
 *   <li>Four auctions are open at a time (up to eight, bought with RackCoin), each for one lot from
 *       {@link DarknetGoods}. A lot nobody bids on closes after fifteen minutes; the first player bid starts a
 *       one-minute countdown, after which the highest bidder wins.</li>
 *   <li>Rival bidders want the lot too. Each auction has a hidden ceiling, around the lot's value: below it, a
 *       rival answers every player bid within a few seconds, and they nudge the price up now and then before
 *       anyone has bid.</li>
 *   <li>A player bid is paid when it's placed and refunded in full if someone outbids it.</li>
 *   <li>A won lot ships, arriving two to six minutes later in the terminal's dead drop.</li>
 * </ul>
 */
public final class DarknetMarket extends PersistentState {
	private static final String STATE_KEY = "rackcraft_darknet";
	public static final int BASE_SLOTS = 4;
	/** RackCoin for the 5th, 6th, 7th and 8th listing slot. */
	public static final long[] SLOT_PRICES = {2_500_000, 10_000_000, 40_000_000, 150_000_000};
	public static final long NO_BID_TICKS = 20 * 60 * 15;
	public static final long AFTER_BID_TICKS = 20 * 60;
	private static final int HISTORY = 8;

	private static final List<String> RIVALS = List.of("xX_Herobrine_Xx", "anon_villager", "PiglinBrute99", "definitely_not_a_creeper",
			"EndermanWithAHat", "raid_captain_ltd", "ghast_writer", "Steve(real)", "Alex_from_accounting", "NitwitInvestor",
			"wandering_trader_alt", "BastionBoss", "the_ender_dragon_fan", "TotemHoarder", "mooshroom_mafia");
	private static final List<String> SELLERS = List.of("trusted_seller_100%", "WanderingTrader", "no_questions_asked",
			"ancient_city_looter", "fell_off_a_minecart", "bastion_clearance", "stronghold_surplus", "end_city_estate_sale",
			"illager_fence", "ShadyLibrarian", "legit_mob_farm", "dungeon_crawler_42");
	private static final List<String> COURIERS = List.of("a Wandering Trader", "a very tired llama", "an Allay", "a minecart with no brakes",
			"a pigeon (chicken)", "a Phantom, at night");

	public static final class Listing {
		public int id;
		public ItemStack stack = ItemStack.EMPTY;
		public String seller = "";
		public long value;
		public long opening;
		/** Highest bid so far, 0 before anyone bids. */
		public long bid;
		public String bidder = "";
		/** The high bidder's UUID if it's a player, otherwise empty. */
		public String bidderId = "";
		public int bids;
		public long endsAt;
		public boolean playerBid;
		/** The most a rival will pay. Hidden. */
		public long rivalMax;
		/** When a rival answers the leading player bid, or 0. */
		public long rivalAt;

		public boolean playerLeading() { return !bidderId.isEmpty(); }

		/** The least the next bid can be: the opening bid, or 5% over the current one (at least 10 RC more). */
		public long minimumBid() {
			if (bid <= 0) return opening;
			return nice(bid + Math.max(10, Math.round(bid * 0.05)));
		}

		NbtCompound toNbt() {
			NbtCompound tag = new NbtCompound();
			tag.putInt("Id", id);
			tag.put("Item", stack.writeNbt(new NbtCompound()));
			tag.putString("Seller", seller);
			tag.putLong("Value", value);
			tag.putLong("Opening", opening);
			tag.putLong("Bid", bid);
			tag.putString("Bidder", bidder);
			tag.putString("BidderId", bidderId);
			tag.putInt("Bids", bids);
			tag.putLong("EndsAt", endsAt);
			tag.putBoolean("PlayerBid", playerBid);
			tag.putLong("RivalMax", rivalMax);
			tag.putLong("RivalAt", rivalAt);
			return tag;
		}

		static Listing fromNbt(NbtCompound tag) {
			Listing listing = new Listing();
			listing.id = tag.getInt("Id");
			listing.stack = ItemStack.fromNbt(tag.getCompound("Item"));
			listing.seller = tag.getString("Seller");
			listing.value = tag.getLong("Value");
			listing.opening = Math.max(1, tag.getLong("Opening"));
			listing.bid = tag.getLong("Bid");
			listing.bidder = tag.getString("Bidder");
			listing.bidderId = tag.getString("BidderId");
			listing.bids = tag.getInt("Bids");
			listing.endsAt = tag.getLong("EndsAt");
			listing.playerBid = tag.getBoolean("PlayerBid");
			listing.rivalMax = tag.getLong("RivalMax");
			listing.rivalAt = tag.getLong("RivalAt");
			return listing;
		}
	}

	/** A won lot on its way, or waiting in the dead drop once {@code arrivesAt} has passed. */
	public static final class Parcel {
		public ItemStack stack = ItemStack.EMPTY;
		public long arrivesAt;
		public String courier = "";
		public boolean announced;

		NbtCompound toNbt() {
			NbtCompound tag = new NbtCompound();
			tag.put("Item", stack.writeNbt(new NbtCompound()));
			tag.putLong("ArrivesAt", arrivesAt);
			tag.putString("Courier", courier);
			tag.putBoolean("Announced", announced);
			return tag;
		}

		static Parcel fromNbt(NbtCompound tag) {
			Parcel parcel = new Parcel();
			parcel.stack = ItemStack.fromNbt(tag.getCompound("Item"));
			parcel.arrivesAt = tag.getLong("ArrivesAt");
			parcel.courier = tag.getString("Courier");
			parcel.announced = tag.getBoolean("Announced");
			return parcel;
		}
	}

	private final List<Listing> listings = new ArrayList<>();
	private final List<Parcel> parcels = new ArrayList<>();
	private final List<String> history = new ArrayList<>();
	private int slots = BASE_SLOTS;
	private int nextId = 1;
	private long nextListing;
	private long seed = 0x4441524B4E4554L;

	public static DarknetMarket get(ServerWorld world) {
		return world.getPersistentStateManager().getOrCreate(DarknetMarket::fromNbt, DarknetMarket::new, STATE_KEY);
	}

	/** The market only if this dimension has ever had one, so dimensions without a terminal don't get a save file. */
	public static DarknetMarket existing(ServerWorld world) {
		return world.getPersistentStateManager().get(DarknetMarket::fromNbt, STATE_KEY);
	}

	public List<Listing> listings() { return listings; }
	public List<Parcel> parcels() { return parcels; }
	public List<String> history() { return history; }
	public int slots() { return slots; }

	/** The price of the next listing slot, or 0 when all eight are bought. */
	public long nextSlotPrice() {
		int bought = slots - BASE_SLOTS;
		return bought < SLOT_PRICES.length ? SLOT_PRICES[bought] : 0;
	}

	public Listing find(int id) {
		for (Listing listing : listings) if (listing.id == id) return listing;
		return null;
	}

	private Random random() {
		seed = seed * 6364136223846793005L + 1442695040888963407L;
		return new Random(seed);
	}

	/** 1,234 as 1,230; 123,456 as 123,000: auction prices look like auction prices. */
	static long nice(long value) {
		if (value < 100) return Math.max(1, value);
		long step = (long) Math.pow(10, Math.floor(Math.log10(value)) - 2);
		return Math.max(step, Math.round(value / (double) step) * step);
	}

	// ---------------------------------------------------------------- the clock

	/** Closes finished auctions, lets rivals bid, fills empty slots and lands parcels. */
	public void tick(ServerWorld world, long now) {
		boolean changed = false;
		Random random = random();
		for (Listing listing : new ArrayList<>(listings)) {
			if (now >= listing.endsAt) {
				close(world, listing, now, random);
				changed = true;
			} else if (listing.playerLeading() && listing.rivalAt > 0 && now >= listing.rivalAt) {
				listing.rivalAt = 0;
				long next = listing.minimumBid();
				if (next <= listing.rivalMax) {
					// A rival outbids by the minimum, or a little more if they're keen.
					long bid = Math.min(listing.rivalMax, nice(next + (random.nextInt(3) == 0 ? next - listing.bid : 0)));
					rivalBid(world, listing, bid, random);
				}
				changed = true;
			} else if (!listing.playerBid && random.nextInt(120) == 0 && listing.minimumBid() <= listing.rivalMax * 0.55) {
				// Before anyone has bid, rivals nudge the price up now and then, but keep their powder dry.
				rivalBid(world, listing, listing.minimumBid(), random);
				changed = true;
			}
		}
		if (listings.size() < slots && (listings.isEmpty() || now >= nextListing)) {
			// An empty market fills at once; after that a new auction opens every 20 to 60 seconds.
			int wanted = listings.isEmpty() ? slots : 1;
			for (int count = 0; count < wanted && listings.size() < slots; count++) list(world, now, random);
			nextListing = now + 20 * (20 + random.nextInt(41));
			changed = true;
		}
		for (Parcel parcel : parcels) {
			if (!parcel.announced && now >= parcel.arrivesAt) {
				parcel.announced = true;
				announce(world, Text.literal("Darknet: your parcel arrived by " + parcel.courier + ": " + describe(parcel.stack)
						+ ". Collect it at a Darknet Terminal.").formatted(Formatting.DARK_GREEN));
				changed = true;
			}
		}
		if (changed) markDirty();
	}

	private void list(ServerWorld world, long now, Random random) {
		DarknetGoods.Lot lot = DarknetGoods.roll(random, world.getEnabledFeatures());
		Listing listing = new Listing();
		listing.id = nextId++;
		listing.stack = lot.stack();
		listing.value = lot.value();
		listing.seller = SELLERS.get(random.nextInt(SELLERS.size()));
		listing.opening = nice(Math.round(lot.value() * (0.15 + random.nextDouble() * 0.25)));
		// Rivals' ceiling: usually around the lot's value, sometimes well under (a bargain) or over (a bidding war).
		listing.rivalMax = nice(Math.round(lot.value() * Math.exp(random.nextGaussian() * 0.3)));
		listing.endsAt = now + NO_BID_TICKS;
		listings.add(listing);
	}

	private void rivalBid(ServerWorld world, Listing listing, long amount, Random random) {
		refund(world, listing, true);
		listing.bid = amount;
		listing.bidder = RIVALS.get(random.nextInt(RIVALS.size()));
		listing.bidderId = "";
		listing.bids++;
	}

	/** Gives a leading player's bid back, telling them they were outbid. */
	private void refund(ServerWorld world, Listing listing, boolean outbid) {
		if (!listing.playerLeading()) return;
		FacilityManager.get(world).addCredits(listing.bid);
		if (!outbid) return;
		ServerPlayerEntity player = world.getServer().getPlayerManager().getPlayer(UUID.fromString(listing.bidderId));
		if (player != null) {
			player.sendMessage(Text.literal(String.format(Locale.ROOT, "Darknet: outbid on %s. Your %,d RC is back.",
					describe(listing.stack), listing.bid)).formatted(Formatting.RED), false);
		}
	}

	private void close(ServerWorld world, Listing listing, long now, Random random) {
		listings.remove(listing);
		String what = describe(listing.stack);
		String result;
		if (listing.playerLeading()) {
			Parcel parcel = new Parcel();
			parcel.stack = listing.stack.copy();
			// Comms Satellites in orbit route the courier faster.
			parcel.arrivesAt = now + Math.round(20 * (120 + random.nextInt(241)) * dev.rackcraft.world.OrbitState.deliveryFactor(world));
			parcel.courier = COURIERS.get(random.nextInt(COURIERS.size()));
			parcels.add(parcel);
			result = String.format(Locale.ROOT, "%s won %s for %,d RC", listing.bidder, what, listing.bid);
			announce(world, Text.literal(String.format(Locale.ROOT, "Darknet: %s won %s for %,d RC. It ships in a few minutes.",
					listing.bidder, what, listing.bid)).formatted(Formatting.DARK_GREEN));
		} else if (listing.bids > 0) {
			result = String.format(Locale.ROOT, "%s went to %s for %,d RC", what, listing.bidder, listing.bid);
		} else {
			result = what + " went unsold";
		}
		history.add(0, result);
		while (history.size() > HISTORY) history.remove(history.size() - 1);
	}

	// ---------------------------------------------------------------- players

	/**
	 * Bids {@code amount} on a lot, paid now and refunded if someone outbids it. A player already leading can raise
	 * their own bid and pays only the difference. Returns a problem to show, or null.
	 */
	public String bid(ServerWorld world, PlayerEntity player, int id, long amount, long now) {
		Listing listing = find(id);
		if (listing == null || now >= listing.endsAt) return "That auction has closed.";
		if (amount < listing.minimumBid()) {
			return String.format(Locale.ROOT, "The next bid must be at least %,d RC.", listing.minimumBid());
		}
		FacilityManager facility = FacilityManager.get(world);
		String you = player.getUuidAsString();
		long owed = you.equals(listing.bidderId) ? amount - listing.bid : amount;
		if (!facility.spendCredits(owed)) {
			return String.format(Locale.ROOT, "You need %,d RC for that bid; you have %,d.", owed, facility.credits());
		}
		if (!you.equals(listing.bidderId)) refund(world, listing, true);
		listing.bid = amount;
		listing.bidder = player.getName().getString();
		listing.bidderId = you;
		listing.bids++;
		if (!listing.playerBid) {
			listing.playerBid = true;
			listing.endsAt = now + AFTER_BID_TICKS;
		}
		// A rival answers in 3 to 12 seconds, if the lot's still worth it to them.
		listing.rivalAt = listing.bid < listing.rivalMax ? now + 60 + random().nextInt(181) : 0;
		markDirty();
		return null;
	}

	/** Buys the next listing slot. Returns a problem, or null. */
	public String buySlot(ServerWorld world) {
		long price = nextSlotPrice();
		if (price <= 0) return "The market is already showing eight auctions.";
		if (!FacilityManager.get(world).spendCredits(price)) {
			return String.format(Locale.ROOT, "Another listing slot costs %,d RC.", price);
		}
		slots++;
		markDirty();
		return null;
	}

	/** Hands every parcel that has arrived to the player. Returns how many. */
	public int collect(PlayerEntity player, long now) {
		int collected = 0;
		for (Parcel parcel : new ArrayList<>(parcels)) {
			if (now < parcel.arrivesAt) continue;
			parcels.remove(parcel);
			ItemStack stack = parcel.stack.copy();
			if (!player.getInventory().insertStack(stack)) player.dropItem(stack, false);
			collected++;
		}
		if (collected > 0) markDirty();
		return collected;
	}

	/** Clears everything, refunding live player bids. The self-test uses it, since the dev world keeps its saves. */
	public void reset(ServerWorld world) {
		for (Listing listing : listings) refund(world, listing, false);
		listings.clear();
		parcels.clear();
		history.clear();
		slots = BASE_SLOTS;
		nextListing = 0;
		markDirty();
	}

	public static String describe(ItemStack stack) {
		String name = stack.getName().getString();
		var enchantments = net.minecraft.enchantment.EnchantmentHelper.get(stack);
		if (stack.isOf(net.minecraft.item.Items.ENCHANTED_BOOK) && !enchantments.isEmpty()) {
			var entry = enchantments.entrySet().iterator().next();
			name = entry.getKey().getName(entry.getValue()).getString() + " book";
		}
		return stack.getCount() > 1 ? stack.getCount() + " x " + name : name;
	}

	private static void announce(ServerWorld world, Text message) {
		for (ServerPlayerEntity player : world.getPlayers()) player.sendMessage(message, false);
	}

	// ---------------------------------------------------------------- saving

	@Override
	public NbtCompound writeNbt(NbtCompound nbt) {
		NbtList listingTag = new NbtList();
		listings.forEach(listing -> listingTag.add(listing.toNbt()));
		nbt.put("Listings", listingTag);
		NbtList parcelTag = new NbtList();
		parcels.forEach(parcel -> parcelTag.add(parcel.toNbt()));
		nbt.put("Parcels", parcelTag);
		NbtList historyTag = new NbtList();
		history.forEach(line -> historyTag.add(net.minecraft.nbt.NbtString.of(line)));
		nbt.put("History", historyTag);
		nbt.putInt("Slots", slots);
		nbt.putInt("NextId", nextId);
		nbt.putLong("NextListing", nextListing);
		nbt.putLong("Seed", seed);
		return nbt;
	}

	private static DarknetMarket fromNbt(NbtCompound nbt) {
		DarknetMarket market = new DarknetMarket();
		NbtList listingTag = nbt.getList("Listings", NbtElement.COMPOUND_TYPE);
		for (int index = 0; index < listingTag.size(); index++) {
			Listing listing = Listing.fromNbt(listingTag.getCompound(index));
			if (!listing.stack.isEmpty()) market.listings.add(listing);
		}
		NbtList parcelTag = nbt.getList("Parcels", NbtElement.COMPOUND_TYPE);
		for (int index = 0; index < parcelTag.size(); index++) {
			Parcel parcel = Parcel.fromNbt(parcelTag.getCompound(index));
			if (!parcel.stack.isEmpty()) market.parcels.add(parcel);
		}
		NbtList historyTag = nbt.getList("History", NbtElement.STRING_TYPE);
		for (int index = 0; index < historyTag.size(); index++) market.history.add(historyTag.getString(index));
		market.slots = Math.max(BASE_SLOTS, Math.min(BASE_SLOTS + SLOT_PRICES.length, nbt.getInt("Slots")));
		market.nextId = Math.max(1, nbt.getInt("NextId"));
		market.nextListing = nbt.getLong("NextListing");
		if (nbt.contains("Seed")) market.seed = nbt.getLong("Seed");
		return market;
	}
}
