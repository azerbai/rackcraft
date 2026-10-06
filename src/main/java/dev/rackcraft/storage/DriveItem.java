package dev.rackcraft.storage;

import java.util.List;
import java.util.Locale;
import java.util.UUID;
import net.minecraft.client.item.TooltipContext;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.world.World;

/**
 * A storage drive (hot: instant access, goes in a Storage Array) or a tape cartridge (cold: huge, slow to
 * read, goes in a Tape Library). The item only carries an id and a usage summary; contents live in StorageState.
 */
public final class DriveItem extends Item {
	private final long capacity;
	private final boolean cold;

	public DriveItem(Item.Settings settings, long capacity, boolean cold) {
		super(settings.maxCount(1));
		this.capacity = capacity;
		this.cold = cold;
	}

	public long capacity() { return capacity; }
	public boolean cold() { return cold; }

	public static UUID idFor(ItemStack stack) {
		NbtCompound tag = stack.getOrCreateNbt();
		if (!tag.containsUuid("DriveId")) tag.putUuid("DriveId", UUID.randomUUID());
		return tag.getUuid("DriveId");
	}

	/** Cached on the item so tooltips work on the client and when the drive is out of an array. */
	public static void writeSummary(ItemStack stack, DriveData data) {
		NbtCompound tag = stack.getOrCreateNbt();
		if (tag.getLong("Used") != data.used() || tag.getInt("Types") != data.types()) {
			tag.putLong("Used", data.used());
			tag.putInt("Types", data.types());
		}
	}

	public static double fill(ItemStack stack) {
		if (!(stack.getItem() instanceof DriveItem drive) || !stack.hasNbt()) return 0;
		return stack.getNbt().getLong("Used") / (double) drive.capacity;
	}

	@Override
	public void appendTooltip(ItemStack stack, World world, List<Text> tooltip, TooltipContext context) {
		long used = stack.hasNbt() ? stack.getNbt().getLong("Used") : 0;
		int types = stack.hasNbt() ? stack.getNbt().getInt("Types") : 0;
		tooltip.add(Text.literal(String.format(Locale.ROOT, "%,d / %,d items, %d types", used, capacity, types))
				.formatted(Formatting.GRAY));
		tooltip.add(Text.translatable(cold ? "tooltip.rackcraft.tape" : "tooltip.rackcraft.drive")
				.formatted(cold ? Formatting.AQUA : Formatting.GOLD));
	}

	@Override
	public boolean isItemBarVisible(ItemStack stack) {
		return stack.hasNbt() && stack.getNbt().getLong("Used") > 0;
	}

	@Override
	public int getItemBarStep(ItemStack stack) {
		return (int) Math.round(13 * Math.min(1, fill(stack)));
	}

	@Override
	public int getItemBarColor(ItemStack stack) {
		double fill = fill(stack);
		return fill > 0.9 ? 0xE0645A : fill > 0.7 ? 0xE7A45D : cold ? 0x5BA7E0 : 0x62C5A0;
	}
}
