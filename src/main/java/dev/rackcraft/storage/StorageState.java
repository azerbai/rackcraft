package dev.rackcraft.storage;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.PersistentState;

/** Every drive's contents, saved with the overworld so drives work in any dimension and in any array. */
public final class StorageState extends PersistentState {
	private static final String STATE_KEY = "rackcraft_storage";
	private final Map<UUID, DriveData> drives = new HashMap<>();
	private final Map<String, Transmitter> transmitters = new HashMap<>();

	/** A drive as last seen by a transmitter, so wireless access still works while its chunk is unloaded. */
	public record CachedDrive(UUID id, long capacity, boolean cold) {}

	public record Transmitter(int level, boolean online, java.util.List<CachedDrive> drives) {}

	public static String transmitterKey(net.minecraft.registry.RegistryKey<net.minecraft.world.World> dimension,
			net.minecraft.util.math.BlockPos pos) {
		return dimension.getValue() + "|" + pos.asLong();
	}

	public Transmitter transmitter(String key) { return transmitters.get(key); }

	public void putTransmitter(String key, Transmitter info) {
		if (!info.equals(transmitters.get(key))) {
			transmitters.put(key, info);
			markDirty();
		}
	}

	public void removeTransmitter(String key) {
		if (transmitters.remove(key) != null) markDirty();
	}

	public DriveData existing(UUID id) { return drives.get(id); }

	public static StorageState get(MinecraftServer server) {
		return server.getOverworld().getPersistentStateManager().getOrCreate(StorageState::fromNbt, StorageState::new, STATE_KEY);
	}

	public DriveData drive(UUID id, long capacity) {
		DriveData drive = drives.computeIfAbsent(id, key -> {
			markDirty();
			return new DriveData(key, capacity);
		});
		drive.setCapacity(capacity);
		return drive;
	}

	@Override
	public NbtCompound writeNbt(NbtCompound nbt) {
		NbtList list = new NbtList();
		drives.values().forEach(drive -> list.add(drive.toNbt()));
		nbt.put("Drives", list);
		NbtList links = new NbtList();
		transmitters.forEach((key, info) -> {
			NbtCompound entry = new NbtCompound();
			entry.putString("Key", key);
			entry.putInt("Level", info.level());
			entry.putBoolean("Online", info.online());
			NbtList cached = new NbtList();
			for (CachedDrive drive : info.drives()) {
				NbtCompound driveTag = new NbtCompound();
				driveTag.putUuid("Id", drive.id());
				driveTag.putLong("Capacity", drive.capacity());
				driveTag.putBoolean("Cold", drive.cold());
				cached.add(driveTag);
			}
			entry.put("Drives", cached);
			links.add(entry);
		});
		nbt.put("Transmitters", links);
		return nbt;
	}

	private static StorageState fromNbt(NbtCompound nbt) {
		StorageState state = new StorageState();
		for (NbtElement element : nbt.getList("Drives", NbtElement.COMPOUND_TYPE)) {
			DriveData drive = DriveData.fromNbt((NbtCompound) element);
			state.drives.put(drive.id(), drive);
		}
		for (NbtElement element : nbt.getList("Transmitters", NbtElement.COMPOUND_TYPE)) {
			NbtCompound entry = (NbtCompound) element;
			java.util.List<CachedDrive> cached = new java.util.ArrayList<>();
			for (NbtElement driveElement : entry.getList("Drives", NbtElement.COMPOUND_TYPE)) {
				NbtCompound driveTag = (NbtCompound) driveElement;
				cached.add(new CachedDrive(driveTag.getUuid("Id"), driveTag.getLong("Capacity"), driveTag.getBoolean("Cold")));
			}
			state.transmitters.put(entry.getString("Key"), new Transmitter(entry.getInt("Level"), entry.getBoolean("Online"), cached));
		}
		return state;
	}
}
