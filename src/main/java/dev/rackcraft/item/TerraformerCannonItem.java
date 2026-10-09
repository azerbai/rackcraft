package dev.rackcraft.item;

import dev.rackcraft.world.BuildStock;
import dev.rackcraft.world.Earthworks;
import java.util.List;
import net.minecraft.client.item.TooltipContext;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.ItemUsageContext;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.TypedActionResult;
import net.minecraft.world.World;

/**
 * The Terraformer Cannon. Use on a block to fire at it; use in the air to cycle the radius (3 to 16); sneak-use in the
 * air to cycle the mode. It burns a Hydrogen Canister for every {@code building.cannonBlocksPerCanister} blocks it moves.
 */
public final class TerraformerCannonItem extends Item {
	private static final String MODE = "Mode";
	private static final String RADIUS = "Radius";
	private static final Earthworks.Terrain[] MODES = Earthworks.Terrain.values();

	public TerraformerCannonItem(Settings settings) {
		super(settings.maxCount(1));
	}

	public static Earthworks.Terrain mode(ItemStack stack) {
		return MODES[stack.getNbt() == null ? 0 : Math.floorMod(stack.getNbt().getInt(MODE), MODES.length)];
	}

	public static int radius(ItemStack stack) {
		int radius = stack.getNbt() == null ? 0 : stack.getNbt().getInt(RADIUS);
		return radius < Earthworks.MIN_RADIUS || radius > Earthworks.MAX_RADIUS ? 5 : radius;
	}

	@Override
	public TypedActionResult<ItemStack> use(World world, PlayerEntity player, Hand hand) {
		ItemStack stack = player.getStackInHand(hand);
		if (world.isClient) return TypedActionResult.success(stack);
		NbtCompound nbt = stack.getOrCreateNbt();
		if (player.isSneaking()) {
			nbt.putInt(MODE, Math.floorMod(nbt.getInt(MODE) + 1, MODES.length));
		} else {
			int next = radius(stack) + 1;
			nbt.putInt(RADIUS, next > Earthworks.MAX_RADIUS ? Earthworks.MIN_RADIUS : next);
		}
		player.sendMessage(Text.literal(describe(stack)), true);
		return TypedActionResult.success(stack);
	}

	@Override
	public ActionResult useOnBlock(ItemUsageContext context) {
		World world = context.getWorld();
		if (world.isClient) return ActionResult.SUCCESS;
		if (!(context.getPlayer() instanceof ServerPlayerEntity player) || !(world instanceof ServerWorld serverWorld)) return ActionResult.PASS;
		ItemStack stack = context.getStack();
		Earthworks.Plan plan = Earthworks.terraform(serverWorld, mode(stack), context.getBlockPos(), radius(stack));
		if (plan == null) {
			player.sendMessage(Text.literal("Too much ground for one shot: lower the radius").formatted(Formatting.RED), true);
			return ActionResult.SUCCESS;
		}
		if (plan.moved() == 0) {
			player.sendMessage(Text.literal("Nothing to " + mode(stack).name().toLowerCase(java.util.Locale.ROOT) + " here"), true);
			return ActionResult.SUCCESS;
		}
		int canisters = Earthworks.canisters(plan);
		BuildStock stock = BuildStock.of(player);
		if (stock.take(Earthworks.hydrogen(), canisters) < canisters) {
			player.sendMessage(Text.literal("Needs " + canisters + " Hydrogen Canisters for " + plan.moved() + " blocks").formatted(Formatting.RED), true);
			return ActionResult.SUCCESS;
		}
		Earthworks.apply(serverWorld, plan);
		world.playSound(null, context.getBlockPos(), SoundEvents.ENTITY_GENERIC_EXPLODE, SoundCategory.PLAYERS, 0.5f, 1.4f);
		player.sendMessage(Text.literal(describe(stack) + ": moved " + plan.moved() + " blocks, " + canisters + " canister" + (canisters == 1 ? "" : "s")), true);
		return ActionResult.SUCCESS;
	}

	private static String describe(ItemStack stack) {
		String mode = mode(stack).name().charAt(0) + mode(stack).name().substring(1).toLowerCase(java.util.Locale.ROOT);
		return mode + ", radius " + radius(stack);
	}

	@Override
	public void appendTooltip(ItemStack stack, World world, List<Text> tooltip, TooltipContext context) {
		tooltip.add(Text.literal(describe(stack)).formatted(Formatting.GRAY));
		tooltip.add(Text.literal("Use in the air: radius. Sneak-use in the air: mode.").formatted(Formatting.DARK_GRAY));
	}
}
