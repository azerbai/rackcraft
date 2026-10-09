package dev.rackcraft.screen;

import dev.rackcraft.Rackcraft;
import dev.rackcraft.RcBlocks;
import dev.rackcraft.block.MachineBlockEntity;
import dev.rackcraft.world.ProcurementWall;
import dev.rackcraft.world.SimTicker;
import dev.rackcraft.world.SitePlanner;
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
 * The Procurement Wall's screen: no slots, just a snapshot of its planners' quotes and ledgers that the server sends twice a
 * second, and one action back (approve a planner's quote).
 */
public final class ProcurementScreenHandler extends ScreenHandler {
	public static final Identifier SYNC = Rackcraft.id("procurement_sync");
	public static final Identifier ACTION = Rackcraft.id("procurement_action");
	private static final int SYNC_INTERVAL = 10;

	private final BlockPos pos;
	private final PlayerEntity player;
	private int syncCountdown;
	private ProcurementWall.Snapshot snapshot = ProcurementWall.Snapshot.EMPTY;
	private int revision;

	public ProcurementScreenHandler(int syncId, PlayerInventory inventory, BlockPos pos) {
		super(RcScreenHandlers.PROCUREMENT, syncId);
		this.pos = pos;
		this.player = inventory.player;
	}

	public BlockPos pos() { return pos; }
	public ProcurementWall.Snapshot snapshot() { return snapshot; }
	public int revision() { return revision; }

	@Override
	public ItemStack quickMove(PlayerEntity player, int slot) { return ItemStack.EMPTY; }

	@Override
	public boolean canUse(PlayerEntity player) {
		return player.getWorld().getBlockState(pos).isOf(RcBlocks.get("procurement_wall")) && player.squaredDistanceTo(pos.toCenterPos()) <= 64;
	}

	@Override
	public void sendContentUpdates() {
		super.sendContentUpdates();
		if (!(player instanceof ServerPlayerEntity serverPlayer) || --syncCountdown > 0) return;
		syncCountdown = SYNC_INTERVAL;
		PacketByteBuf buf = PacketByteBufs.create();
		buf.writeVarInt(syncId);
		ProcurementWall.snapshot(serverPlayer.getServerWorld(), pos).write(buf);
		ServerPlayNetworking.send(serverPlayer, SYNC, buf);
	}

	public void applySync(PacketByteBuf buf) {
		snapshot = ProcurementWall.Snapshot.read(buf);
		revision++;
	}

	/** Approves the quote of the planner at {@code planner}, if it is on this wall's grid and waiting. */
	private void approve(ServerPlayerEntity who, BlockPos planner) {
		ServerWorld world = who.getServerWorld();
		for (MachineBlockEntity candidate : ProcurementWall.planners(world, SimTicker.machines(world), pos)) {
			if (!candidate.getPos().equals(planner)) continue;
			String message = SitePlanner.awaiting(candidate) ? SitePlanner.toggleRunning(candidate) : "That planner has no quote waiting";
			who.sendMessage(Text.literal(message).formatted(Formatting.GREEN), true);
			SitePlanner.scanNow(world);
		}
		syncCountdown = 0;
		sendContentUpdates();
	}

	public static void registerServer() {
		ServerPlayNetworking.registerGlobalReceiver(ACTION, (server, player, handler, buf, responseSender) -> {
			int syncId = buf.readVarInt();
			BlockPos planner = buf.readBlockPos();
			server.execute(() -> {
				if (player.currentScreenHandler instanceof ProcurementScreenHandler wall && wall.syncId == syncId && wall.canUse(player)) {
					wall.approve(player, planner);
				}
			});
		});
	}
}
