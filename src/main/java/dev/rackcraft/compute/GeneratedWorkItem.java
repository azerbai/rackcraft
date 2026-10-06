package dev.rackcraft.compute;

import java.util.List;
import net.minecraft.client.item.TooltipContext;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.world.World;

/**
 * A Generated Image or Generated Document: what the facility's AI models produce for contracts. It
 * remembers its prompt and quality, so a copy kept in stock can be delivered to a later contract that asks
 * for the same thing.
 */
public final class GeneratedWorkItem extends Item {
	public GeneratedWorkItem(Item.Settings settings) {
		super(settings.maxCount(16));
	}

	public static ItemStack create(Item item, String kind, String prompt, int quality, String model) {
		ItemStack stack = new ItemStack(item);
		NbtCompound tag = stack.getOrCreateNbt();
		tag.putString("Kind", kind);
		tag.putString("Prompt", prompt);
		tag.putInt("Quality", quality);
		tag.putString("Model", model);
		return stack;
	}

	@Override
	public Text getName(ItemStack stack) {
		NbtCompound tag = stack.getNbt();
		if (tag == null || !tag.contains("Prompt")) return super.getName(stack);
		String kind = tag.getString("Kind");
		String label = kind.equals("image") ? "Image" : ContractTemplates.docType(kind).name();
		return Text.literal(label + ": " + tag.getString("Prompt"));
	}

	@Override
	public void appendTooltip(ItemStack stack, World world, List<Text> tooltip, TooltipContext context) {
		NbtCompound tag = stack.getNbt();
		if (tag == null || !tag.contains("Prompt")) {
			tooltip.add(Text.literal("Blank. Made by AI contract work.").formatted(Formatting.GRAY));
			return;
		}
		int quality = tag.getInt("Quality");
		tooltip.add(Text.literal("Quality " + quality + "%").formatted(quality >= 60 ? Formatting.GREEN : Formatting.YELLOW));
		String kind = tag.getString("Kind");
		String note = kind.equals("image") ? ContractTemplates.imageVerdict(quality)
				: "\"" + ContractTemplates.excerpt(ContractTemplates.docType(kind), tag.getString("Prompt"), quality) + "\"";
		tooltip.add(Text.literal(note).formatted(Formatting.GRAY, Formatting.ITALIC));
		if (tag.contains("Model")) tooltip.add(Text.literal("Made by " + tag.getString("Model")).formatted(Formatting.DARK_GRAY));
	}
}
