package dev.rackcraft.storage;

import dev.rackcraft.block.MachineBlockEntity;
import dev.rackcraft.sim.NetKind;
import dev.rackcraft.world.NetworkManager;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.RegistryKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

/** Builds network views, tracks wireless transmitters, and runs tape recalls and archiving. */
public final class StorageService {
	/** Players wait this long for an item to come off tape. */
	public static final int TAPE_MOUNT_TICKS = 40;
	/** Range in blocks per transmitter level; level 6 is unlimited in its dimension, level 7 reaches every dimension. */
	public static final int[] RANGES = {16, 32, 64, 128, 256, 1024, Integer.MAX_VALUE, Integer.MAX_VALUE};
	public static final int MAX_LEVEL = RANGES.length - 1;
	public static final int INFINITE_LEVEL = 6;
	public static final int MULTIDIMENSIONAL_LEVEL = 7;

	private record Recall(UUID player, Access access, ItemKey key, long amount, long readyTick) {}

	private static final Map<MinecraftServer, List<Recall>> RECALLS = new WeakHashMap<>();

	private StorageService() {}

	public static void register() {
		net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents.END_SERVER_TICK.register(StorageService::tickRecalls);
	}

	/** How a terminal reaches its network: a block on the network, or a wireless link to a transmitter. */
	public record Access(RegistryKey<World> dimension, BlockPos pos, boolean wireless) {
		public StorageNetwork resolve(MinecraftServer server) {
			ServerWorld world = server.getWorld(dimension);
			boolean loaded = world != null && world.isChunkLoaded(pos);
			if (!wireless) return loaded ? networkAt(world, pos) : null;
			StorageState.Transmitter info = StorageState.get(server).transmitter(StorageState.transmitterKey(dimension, pos));
			if (info == null || !info.online()) return null;
			if (loaded) return networkAt(world, pos);
			// The transmitter's area is unloaded: use the drives it last saw.
			List<StorageNetwork.Member> members = new ArrayList<>();
			for (StorageState.CachedDrive drive : info.drives()) {
				members.add(new StorageNetwork.Member(StorageState.get(server).drive(drive.id(), drive.capacity()), drive.cold(), null, null));
			}
			return new StorageNetwork(server, members);
		}

		public void write(net.minecraft.network.PacketByteBuf buf) {
			buf.writeIdentifier(dimension.getValue());
			buf.writeBlockPos(pos);
			buf.writeBoolean(wireless);
		}

		public static Access read(net.minecraft.network.PacketByteBuf buf) {
			return new Access(RegistryKey.of(net.minecraft.registry.RegistryKeys.WORLD, buf.readIdentifier()),
					buf.readBlockPos(), buf.readBoolean());
		}

		/** Whether the full simulation (power, compute) runs here right now. */
		public boolean live(MinecraftServer server) {
			ServerWorld world = server.getWorld(dimension);
			return world != null && world.isChunkLoaded(pos);
		}
	}

	/** Every online drive and tape on the fiber network containing {@code pos}. */
	public static StorageNetwork networkAt(ServerWorld world, BlockPos pos) {
		List<StorageNetwork.Member> members = new ArrayList<>();
		for (BlockPos member : NetworkManager.get(world).component(pos, NetKind.DATA)) {
			if (!(world.getBlockEntity(member) instanceof MachineBlockEntity machine) || !machine.storageOnline()) continue;
			boolean array = machine.blockId().equals("storage_array");
			if (!array && !machine.blockId().equals("tape_library")) continue;
			for (int slot = 0; slot < machine.size(); slot++) {
				ItemStack stack = machine.getStack(slot);
				if (!(stack.getItem() instanceof DriveItem drive) || drive.cold() == array) continue;
				DriveData data = StorageState.get(world.getServer()).drive(DriveItem.idFor(stack), drive.capacity());
				members.add(new StorageNetwork.Member(data, drive.cold(), stack, machine));
			}
		}
		return new StorageNetwork(world.getServer(), members);
	}

	/** Refreshes a transmitter's cached drive list; called every simulation step while it is loaded. */
	public static void updateTransmitter(ServerWorld world, MachineBlockEntity transmitter) {
		List<StorageState.CachedDrive> drives = new ArrayList<>();
		for (BlockPos member : NetworkManager.get(world).component(transmitter.getPos(), NetKind.DATA)) {
			if (!(world.getBlockEntity(member) instanceof MachineBlockEntity machine) || !machine.storageOnline()) continue;
			for (int slot = 0; slot < machine.size(); slot++) {
				ItemStack stack = machine.getStack(slot);
				if (stack.getItem() instanceof DriveItem drive
						&& (machine.blockId().equals("storage_array") ? !drive.cold() : machine.blockId().equals("tape_library") && drive.cold())) {
					drives.add(new StorageState.CachedDrive(DriveItem.idFor(stack), drive.capacity(), drive.cold()));
				}
			}
		}
		boolean online = transmitter.powerSatisfaction() >= 0.5;
		StorageState.get(world.getServer()).putTransmitter(StorageState.transmitterKey(world.getRegistryKey(), transmitter.getPos()),
				new StorageState.Transmitter(transmitter.transmitterLevel(), online, drives));
	}

	/** Takes items off tape for a player after the mount delay. */
	public static void requestRecall(ServerPlayerEntity player, Access access, ItemKey key, long amount) {
		long now = player.getServer().getOverworld().getTime();
		RECALLS.computeIfAbsent(player.getServer(), ignored -> new ArrayList<>())
				.add(new Recall(player.getUuid(), access, key, amount, now + TAPE_MOUNT_TICKS));
		player.sendMessage(Text.translatable("storage.rackcraft.mounting", amount, key.toStack(1).getName())
				.formatted(Formatting.AQUA), true);
	}

	public static void tickRecalls(MinecraftServer server) {
		List<Recall> recalls = RECALLS.get(server);
		if (recalls == null || recalls.isEmpty()) return;
		long now = server.getOverworld().getTime();
		Iterator<Recall> iterator = recalls.iterator();
		while (iterator.hasNext()) {
			Recall recall = iterator.next();
			if (recall.readyTick() > now) continue;
			iterator.remove();
			ServerPlayerEntity player = server.getPlayerManager().getPlayer(recall.player());
			StorageNetwork network = recall.access().resolve(server);
			if (player == null || network == null) continue;
			long taken = network.extract(recall.key(), recall.amount(), true, false);
			long remaining = taken;
			while (remaining > 0) {
				int batch = (int) Math.min(remaining, recall.key().maxStackSize());
				ItemStack stack = recall.key().toStack(batch);
				if (!player.getInventory().insertStack(stack)) player.dropItem(stack, false);
				remaining -= batch;
			}
			if (taken > 0) {
				player.sendMessage(Text.translatable("storage.rackcraft.retrieved", taken, recall.key().toStack(1).getName())
						.formatted(Formatting.AQUA), true);
			}
		}
	}
}
