package dev.rackcraft.item;

import dev.rackcraft.world.BuildStock;
import dev.rackcraft.world.CablePlanner;
import java.util.List;
import net.minecraft.client.item.TooltipContext;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.ItemUsageContext;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.TypedActionResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

/**
 * Draws a cable run before it is laid. Use on a block face to set the start, then the end (a ghost of the route hangs
 * in the world, red where something is in the way); use a third time to lay it. Use in the air to cycle the route
 * style, sneak-use in the air to cycle the cable, sneak-use on a block to clear.
 */
public final class CablePlannerItem extends Item {
	private static final String A = "From";
	private static final String B = "To";
	private static final String STYLE = "Style";
	private static final String KIND = "Cable";

	public CablePlannerItem(Settings settings) {
		super(settings.maxCount(1));
	}

	public static BlockPos point(ItemStack stack, String key) {
		NbtCompound nbt = stack.getNbt();
		return nbt != null && nbt.contains(key) ? BlockPos.fromLong(nbt.getLong(key)) : null;
	}

	public static BlockPos start(ItemStack stack) { return point(stack, A); }
	public static BlockPos end(ItemStack stack) { return point(stack, B); }
	public static int style(ItemStack stack) { return stack.getNbt() == null ? 0 : Math.floorMod(stack.getNbt().getInt(STYLE), CablePlanner.STYLES.length); }
	public static int kind(ItemStack stack) { return stack.getNbt() == null ? 0 : Math.floorMod(stack.getNbt().getInt(KIND), CablePlanner.KINDS.length); }

	@Override
	public ActionResult useOnBlock(ItemUsageContext context) {
		World world = context.getWorld();
		PlayerEntity player = context.getPlayer();
		if (world.isClient) return ActionResult.SUCCESS;
		if (!(player instanceof ServerPlayerEntity serverPlayer) || !(world instanceof ServerWorld serverWorld)) return ActionResult.PASS;
		ItemStack stack = context.getStack();
		NbtCompound nbt = stack.getOrCreateNbt();
		if (player.isSneaking()) {
			clear(stack);
			CablePlanner.sendGhost(serverPlayer, kind(stack), List.of());
			tell(player, "Route cleared");
			return ActionResult.SUCCESS;
		}
		// The cell the click is on the outside of: that is where a cable would go.
		BlockPos cell = context.getBlockPos().offset(context.getSide());
		if (!nbt.contains(A) || nbt.contains(B) && !nbt.contains("Armed")) {
			nbt.remove(B);
			nbt.putLong(A, cell.asLong());
			tell(player, "Start at " + cell.toShortString() + ": now pick the end");
			CablePlanner.sendGhost(serverPlayer, kind(stack), List.of());
		} else if (!nbt.contains(B)) {
			nbt.putLong(B, cell.asLong());
			showRoute(serverWorld, serverPlayer, stack);
		} else {
			lay(serverWorld, serverPlayer, stack);
		}
		return ActionResult.SUCCESS;
	}

	@Override
	public TypedActionResult<ItemStack> use(World world, PlayerEntity player, Hand hand) {
		ItemStack stack = player.getStackInHand(hand);
		if (world.isClient || !(player instanceof ServerPlayerEntity serverPlayer) || !(world instanceof ServerWorld serverWorld)) {
			return TypedActionResult.success(stack);
		}
		NbtCompound nbt = stack.getOrCreateNbt();
		if (player.isSneaking()) nbt.putInt(KIND, kind(stack) + 1);
		else nbt.putInt(STYLE, style(stack) + 1);
		if (nbt.contains(A) && nbt.contains(B)) showRoute(serverWorld, serverPlayer, stack);
		else tell(player, describe(stack));
		return TypedActionResult.success(stack);
	}

	private static void clear(ItemStack stack) {
		NbtCompound nbt = stack.getOrCreateNbt();
		nbt.remove(A);
		nbt.remove(B);
	}

	private static String describe(ItemStack stack) {
		return CablePlanner.cableBlock(kind(stack)).getName().getString() + ", " + CablePlanner.STYLES[style(stack)];
	}

	/** Works the route out, ghosts it and says what it would take. */
	private static void showRoute(ServerWorld world, ServerPlayerEntity player, ItemStack stack) {
		List<CablePlanner.Cell> cells = CablePlanner.route(world, start(stack), end(stack), style(stack), CablePlanner.cableBlock(kind(stack)));
		CablePlanner.sendGhost(player, kind(stack), cells);
		if (cells.isEmpty()) {
			tell(player, "Too far: routes are limited to " + CablePlanner.MAX_LENGTH + " blocks");
			return;
		}
		int free = 0;
		int blocked = 0;
		for (CablePlanner.Cell cell : cells) {
			if (cell.flag() == CablePlanner.FREE) free++;
			else if (cell.flag() == CablePlanner.BLOCKED) blocked++;
		}
		long carried = BuildStock.of(player).count(CablePlanner.cableBlock(kind(stack)).asItem());
		String carriedText = player.isCreative() ? "creative" : carried + " in reach";
		stack.getOrCreateNbt().putBoolean("Armed", true);
		tell(player, describe(stack) + ": " + free + " to lay (" + carriedText + ")"
				+ (blocked > 0 ? ", " + blocked + " blocked" : "") + ". Use again to lay it");
	}

	private static void lay(ServerWorld world, ServerPlayerEntity player, ItemStack stack) {
		List<CablePlanner.Cell> cells = CablePlanner.route(world, start(stack), end(stack), style(stack), CablePlanner.cableBlock(kind(stack)));
		CablePlanner.Laid laid = CablePlanner.lay(world, cells, CablePlanner.cableBlock(kind(stack)), BuildStock.of(player));
		stack.getOrCreateNbt().remove("Armed");
		clear(stack);
		CablePlanner.sendGhost(player, kind(stack), List.of());
		String result = "Laid " + laid.placed() + " blocks";
		if (laid.blocked()) result += ", stopped at something in the way";
		else if (laid.outOfCables()) result += ", ran out of cables";
		tell(player, result);
	}

	private static void tell(PlayerEntity player, String message) {
		if (player != null) player.sendMessage(Text.literal(message), true);
	}

	@Override
	public void appendTooltip(ItemStack stack, World world, List<Text> tooltip, TooltipContext context) {
		tooltip.add(Text.literal(describe(stack)).formatted(Formatting.GRAY));
		tooltip.add(Text.literal("Use in the air: route style. Sneak-use in the air: cable.").formatted(Formatting.DARK_GRAY));
	}
}
