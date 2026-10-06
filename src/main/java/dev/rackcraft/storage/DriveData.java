package dev.rackcraft.storage;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;

/** The contents of one drive or tape. Lives in {@link StorageState}, not on the item, so drives stay small. */
public final class DriveData {
	private final UUID id;
	private long capacity;
	private final Map<ItemKey, Long> items = new LinkedHashMap<>();
	private long used;

	DriveData(UUID id, long capacity) {
		this.id = id;
		this.capacity = capacity;
	}

	public UUID id() { return id; }
	public long capacity() { return capacity; }
	public long used() { return used; }
	public long free() { return Math.max(0, capacity - used); }
	public int types() { return items.size(); }
	public Map<ItemKey, Long> items() { return java.util.Collections.unmodifiableMap(items); }
	public long count(ItemKey key) { return items.getOrDefault(key, 0L); }

	void setCapacity(long capacity) { this.capacity = Math.max(this.capacity, capacity); }

	/** Returns how many were accepted. */
	long insert(ItemKey key, long amount, boolean simulate) {
		long accepted = Math.min(amount, free());
		if (accepted <= 0) return 0;
		if (!simulate) {
			items.merge(key, accepted, Long::sum);
			used += accepted;
		}
		return accepted;
	}

	/** Returns how many were removed. */
	long extract(ItemKey key, long amount, boolean simulate) {
		long available = items.getOrDefault(key, 0L);
		long taken = Math.min(amount, available);
		if (taken <= 0) return 0;
		if (!simulate) {
			if (taken == available) items.remove(key);
			else items.put(key, available - taken);
			used -= taken;
		}
		return taken;
	}

	NbtCompound toNbt() {
		NbtCompound tag = new NbtCompound();
		tag.putUuid("Id", id);
		tag.putLong("Capacity", capacity);
		NbtList list = new NbtList();
		items.forEach((key, count) -> {
			NbtCompound entry = key.toNbt();
			entry.putLong("Count", count);
			list.add(entry);
		});
		tag.put("Items", list);
		return tag;
	}

	static DriveData fromNbt(NbtCompound tag) {
		DriveData drive = new DriveData(tag.getUuid("Id"), tag.getLong("Capacity"));
		for (NbtElement element : tag.getList("Items", NbtElement.COMPOUND_TYPE)) {
			NbtCompound entry = (NbtCompound) element;
			ItemKey key = ItemKey.fromNbt(entry);
			long count = entry.getLong("Count");
			if (key.isEmpty() || count <= 0) continue;
			drive.items.merge(key, count, Long::sum);
			drive.used += count;
		}
		return drive;
	}
}
