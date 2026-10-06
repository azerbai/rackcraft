package dev.rackcraft.storage;

import java.util.Objects;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;

/** An item type as storage sees it: the item plus its exact NBT. Counts are kept separately as longs. */
public final class ItemKey {
	private final Item item;
	private final NbtCompound nbt;
	private final int hash;

	private ItemKey(Item item, NbtCompound nbt) {
		this.item = item;
		this.nbt = nbt == null || nbt.isEmpty() ? null : nbt.copy();
		this.hash = Objects.hash(item, this.nbt);
	}

	public static ItemKey of(ItemStack stack) {
		return new ItemKey(stack.getItem(), stack.getNbt());
	}

	public static ItemKey of(Item item) {
		return new ItemKey(item, null);
	}

	public Item item() { return item; }

	public boolean isEmpty() { return item == Items.AIR; }

	public boolean matches(ItemStack stack) {
		return !stack.isEmpty() && stack.getItem() == item && Objects.equals(nbt, emptyToNull(stack.getNbt()));
	}

	public ItemStack toStack(long count) {
		ItemStack stack = new ItemStack(item, (int) Math.max(0, Math.min(Integer.MAX_VALUE, count)));
		if (nbt != null) stack.setNbt(nbt.copy());
		return stack;
	}

	public int maxStackSize() { return item.getMaxCount(); }

	public NbtCompound toNbt() {
		NbtCompound tag = new NbtCompound();
		tag.putString("id", Registries.ITEM.getId(item).toString());
		if (nbt != null) tag.put("tag", nbt.copy());
		return tag;
	}

	public static ItemKey fromNbt(NbtCompound tag) {
		Item item = Registries.ITEM.get(Identifier.tryParse(tag.getString("id")));
		return new ItemKey(item, tag.contains("tag") ? tag.getCompound("tag") : null);
	}

	public void write(PacketByteBuf buf) {
		buf.writeVarInt(Registries.ITEM.getRawId(item));
		buf.writeNbt(nbt);
	}

	public static ItemKey read(PacketByteBuf buf) {
		return new ItemKey(Registries.ITEM.get(buf.readVarInt()), buf.readNbt());
	}

	private static NbtCompound emptyToNull(NbtCompound nbt) {
		return nbt == null || nbt.isEmpty() ? null : nbt;
	}

	@Override
	public boolean equals(Object other) {
		return other instanceof ItemKey key && key.item == item && Objects.equals(key.nbt, nbt);
	}

	@Override
	public int hashCode() { return hash; }

	@Override
	public String toString() { return Registries.ITEM.getId(item) + (nbt == null ? "" : nbt.toString()); }
}
