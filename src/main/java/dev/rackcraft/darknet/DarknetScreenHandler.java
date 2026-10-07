package dev.rackcraft.darknet;

import dev.rackcraft.Rackcraft;
import dev.rackcraft.RcBlocks;
import dev.rackcraft.screen.RcScreenHandlers;
import dev.rackcraft.world.FacilityManager;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;

/**
 * The Darknet Terminal's screen. Like the Operations Terminal it has no real slots: the server sends a
 * {@link Snapshot} twice a second and the client sends {@link Action}s back.
 */
public final class DarknetScreenHandler extends ScreenHandler {
	public static final Identifier SYNC = Rackcraft.id("darknet_sync");
	public static final Identifier ACTION = Rackcraft.id("darknet_action");
	private static final int SYNC_INTERVAL = 10;

	/** BID takes a listing id and an amount; BUY_SLOT and COLLECT take nothing. */
	public enum Action { BID, BUY_SLOT, COLLECT }

	public record ListingView(int id, ItemStack stack, String seller, long bid, long minimumBid, String bidder, boolean youLead,
			boolean playerLeading, long ticksLeft, int bids, boolean playerBid) {}

	/** {@code ticksLeft} 0 or less means it has arrived. */
	public record ParcelView(ItemStack stack, long ticksLeft, String courier) {}

	public record Snapshot(long credits, int slots, long nextSlotPrice, List<ListingView> listings, List<ParcelView> parcels,
			List<String> history) {
		public static final Snapshot EMPTY = new Snapshot(0, DarknetMarket.BASE_SLOTS, DarknetMarket.SLOT_PRICES[0], List.of(),
				List.of(), List.of());

		public void write(PacketByteBuf buf) {
			buf.writeVarLong(credits);
			buf.writeVarInt(slots);
			buf.writeVarLong(nextSlotPrice);
			buf.writeVarInt(listings.size());
			for (ListingView listing : listings) {
				buf.writeVarInt(listing.id());
				buf.writeItemStack(listing.stack());
				buf.writeString(listing.seller());
				buf.writeVarLong(listing.bid());
				buf.writeVarLong(listing.minimumBid());
				buf.writeString(listing.bidder());
				buf.writeBoolean(listing.youLead());
				buf.writeBoolean(listing.playerLeading());
				buf.writeVarLong(Math.max(0, listing.ticksLeft()));
				buf.writeVarInt(listing.bids());
				buf.writeBoolean(listing.playerBid());
			}
			buf.writeVarInt(parcels.size());
			for (ParcelView parcel : parcels) {
				buf.writeItemStack(parcel.stack());
				buf.writeLong(parcel.ticksLeft());
				buf.writeString(parcel.courier());
			}
			buf.writeVarInt(history.size());
			history.forEach(buf::writeString);
		}

		public static Snapshot read(PacketByteBuf buf) {
			long credits = buf.readVarLong();
			int slots = buf.readVarInt();
			long nextSlotPrice = buf.readVarLong();
			List<ListingView> listings = new ArrayList<>();
			for (int count = buf.readVarInt(); count > 0; count--) {
				listings.add(new ListingView(buf.readVarInt(), buf.readItemStack(), buf.readString(), buf.readVarLong(), buf.readVarLong(),
						buf.readString(), buf.readBoolean(), buf.readBoolean(), buf.readVarLong(), buf.readVarInt(), buf.readBoolean()));
			}
			List<ParcelView> parcels = new ArrayList<>();
			for (int count = buf.readVarInt(); count > 0; count--) parcels.add(new ParcelView(buf.readItemStack(), buf.readLong(), buf.readString()));
			List<String> history = new ArrayList<>();
			for (int count = buf.readVarInt(); count > 0; count--) history.add(buf.readString());
			return new Snapshot(credits, slots, nextSlotPrice, listings, parcels, history);
		}
	}

	private final BlockPos pos;
	private final PlayerEntity player;
	private int syncCountdown;
	private Snapshot snapshot = Snapshot.EMPTY;
	private int revision;

	public DarknetScreenHandler(int syncId, PlayerInventory inventory, BlockPos pos) {
		super(RcScreenHandlers.DARKNET, syncId);
		this.pos = pos;
		this.player = inventory.player;
	}

	public Snapshot snapshot() { return snapshot; }
	public int revision() { return revision; }

	@Override
	public ItemStack quickMove(PlayerEntity player, int slot) { return ItemStack.EMPTY; }

	@Override
	public boolean canUse(PlayerEntity player) {
		return player.getWorld().getBlockState(pos).isOf(RcBlocks.get("darknet_terminal"))
				&& player.squaredDistanceTo(pos.toCenterPos()) <= 64;
	}

	@Override
	public void sendContentUpdates() {
		super.sendContentUpdates();
		if (!(player instanceof ServerPlayerEntity serverPlayer) || --syncCountdown > 0) return;
		syncCountdown = SYNC_INTERVAL;
		PacketByteBuf buf = PacketByteBufs.create();
		buf.writeVarInt(syncId);
		build(serverPlayer.getServerWorld(), serverPlayer).write(buf);
		ServerPlayNetworking.send(serverPlayer, SYNC, buf);
	}

	public void applySync(PacketByteBuf buf) {
		snapshot = Snapshot.read(buf);
		revision++;
	}

	public static Snapshot build(ServerWorld world, PlayerEntity viewer) {
		DarknetMarket market = DarknetMarket.get(world);
		long now = world.getTime();
		String you = viewer.getUuidAsString();
		List<ListingView> listings = new ArrayList<>();
		for (DarknetMarket.Listing listing : market.listings()) {
			listings.add(new ListingView(listing.id, listing.stack, listing.seller, listing.bid, listing.minimumBid(), listing.bidder,
					you.equals(listing.bidderId), listing.playerLeading(), listing.endsAt - now, listing.bids, listing.playerBid));
		}
		List<ParcelView> parcels = new ArrayList<>();
		for (DarknetMarket.Parcel parcel : market.parcels()) parcels.add(new ParcelView(parcel.stack, parcel.arrivesAt - now, parcel.courier));
		return new Snapshot(FacilityManager.get(world).credits(), market.slots(), market.nextSlotPrice(), listings, parcels,
				List.copyOf(market.history()));
	}

	public void handle(ServerPlayerEntity player, Action action, int id, long amount) {
		ServerWorld world = player.getServerWorld();
		DarknetMarket market = DarknetMarket.get(world);
		long now = world.getTime();
		String problem = switch (action) {
			case BID -> market.bid(world, player, id, amount, now);
			case BUY_SLOT -> market.buySlot(world);
			case COLLECT -> market.collect(player, now) > 0 ? null : "Nothing has arrived yet.";
		};
		if (problem != null) player.sendMessage(Text.literal(problem).formatted(Formatting.RED), true);
		syncCountdown = 0;
		sendContentUpdates();
	}

	public static void registerServer() {
		ServerPlayNetworking.registerGlobalReceiver(ACTION, (server, player, handler, buf, responseSender) -> {
			int syncId = buf.readVarInt();
			Action action = Action.values()[Math.max(0, Math.min(Action.values().length - 1, buf.readVarInt()))];
			int id = buf.readVarInt();
			long amount = buf.readVarLong();
			server.execute(() -> {
				if (player.currentScreenHandler instanceof DarknetScreenHandler darknet && darknet.syncId == syncId
						&& darknet.canUse(player)) {
					darknet.handle(player, action, id, amount);
				}
			});
		});
	}
}
