package dev.rackcraft.item;

import dev.rackcraft.RcItems;
import dev.rackcraft.compute.Research;
import dev.rackcraft.compute.ResearchLab;
import dev.rackcraft.world.Blueprints;
import java.util.List;
import net.minecraft.client.item.TooltipContext;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.ItemUsageContext;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.TypedActionResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

/**
 * Records a box of Rackcraft blocks into a blank Blueprint. Use on a block for one corner and on another for the
 * opposite; the box is outlined while the scanner is held. Use it in the air to scan into a blank Blueprint from your
 * inventory. Sneak-use on a block clears the corners. Needs Digital Twin researched.
 */
public final class BlueprintScannerItem extends Item {
	public static final String GATE = "digital_twin";

	public BlueprintScannerItem(Settings settings) {
		super(settings.maxCount(1));
	}

	@Override
	public ActionResult useOnBlock(ItemUsageContext context) {
		World world = context.getWorld();
		PlayerEntity player = context.getPlayer();
		if (world.isClient) return ActionResult.SUCCESS;
		ItemStack stack = context.getStack();
		NbtCompound nbt = stack.getOrCreateNbt();
		BlockPos pos = context.getBlockPos();
		if (player != null && player.isSneaking()) {
			nbt.remove(SurveyStakeItem.FIRST);
			nbt.remove(SurveyStakeItem.SECOND);
			tell(player, "Corners cleared");
			return ActionResult.SUCCESS;
		}
		if (!nbt.contains(SurveyStakeItem.FIRST) || nbt.contains(SurveyStakeItem.SECOND)) {
			nbt.putLong(SurveyStakeItem.FIRST, pos.asLong());
			nbt.remove(SurveyStakeItem.SECOND);
			tell(player, "First corner " + pos.toShortString() + ": now the opposite one");
		} else {
			nbt.putLong(SurveyStakeItem.SECOND, pos.asLong());
			BlockPos a = BlockPos.fromLong(nbt.getLong(SurveyStakeItem.FIRST));
			tell(player, "Box " + (Math.abs(a.getX() - pos.getX()) + 1) + " x " + (Math.abs(a.getY() - pos.getY()) + 1) + " x "
					+ (Math.abs(a.getZ() - pos.getZ()) + 1) + ". Use in the air to scan it into a blank Blueprint");
		}
		return ActionResult.SUCCESS;
	}

	@Override
	public TypedActionResult<ItemStack> use(World world, PlayerEntity player, Hand hand) {
		ItemStack stack = player.getStackInHand(hand);
		if (world.isClient || !(world instanceof ServerWorld serverWorld)) return TypedActionResult.success(stack);
		BlockPos a = SurveyStakeItem.corner(stack, SurveyStakeItem.FIRST);
		BlockPos b = SurveyStakeItem.corner(stack, SurveyStakeItem.SECOND);
		if (a == null || b == null) {
			tell(player, "Mark both corners first");
			return TypedActionResult.success(stack);
		}
		if (!ResearchLab.get(serverWorld).done(GATE)) {
			tell(player, "Inert until " + Research.get(GATE).name() + " is researched");
			return TypedActionResult.success(stack);
		}
		ItemStack blank = ItemStack.EMPTY;
		for (ItemStack candidate : player.getInventory().main) {
			if (Blueprints.isBlueprint(candidate) && !Blueprints.written(candidate)) {
				blank = candidate;
				break;
			}
		}
		boolean fresh = false;
		if (blank.isEmpty()) {
			if (!player.isCreative()) {
				tell(player, "Needs a blank Blueprint in your inventory");
				return TypedActionResult.success(stack);
			}
			blank = new ItemStack(RcItems.ITEMS.get("blueprint"));
			fresh = true;
		}
		int blocks = Blueprints.scan(serverWorld, a, b, blank);
		if (blocks == -1) {
			tell(player, "Too big: a blueprint is at most " + Blueprints.MAX_SIDE + " blocks a side");
			return TypedActionResult.success(stack);
		}
		if (fresh && !player.getInventory().insertStack(blank)) player.dropItem(blank, false);
		int skipped = blank.getSubNbt(Blueprints.KEY).getInt("Skipped");
		world.playSound(null, player.getBlockPos(), SoundEvents.BLOCK_AMETHYST_BLOCK_CHIME, SoundCategory.PLAYERS, 0.8f, 1.2f);
		tell(player, "Scanned " + blocks + " blocks" + (skipped > 0 ? ", skipped " + skipped + " the printer can't print" : ""));
		return TypedActionResult.success(stack);
	}

	private static void tell(PlayerEntity player, String message) {
		if (player != null) player.sendMessage(Text.literal(message), true);
	}

	@Override
	public void appendTooltip(ItemStack stack, World world, List<Text> tooltip, TooltipContext context) {
		BlockPos a = SurveyStakeItem.corner(stack, SurveyStakeItem.FIRST);
		BlockPos b = SurveyStakeItem.corner(stack, SurveyStakeItem.SECOND);
		tooltip.add(Text.literal(a == null ? "No box marked" : b == null ? "First corner " + a.toShortString() : "Box marked").formatted(Formatting.GRAY));
	}
}
