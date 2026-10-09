package dev.rackcraft.block;

import net.minecraft.block.AbstractBlock;
import net.minecraft.block.BlockState;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

/** A Sentry Turret. It has no screen: use it to cycle who it shoots. */
public final class SentryBlock extends MachineBlock {
	public static final String[] MODES = {"all hostile mobs", "guards and scavengers only", "off", "the base's rules"};

	public SentryBlock(AbstractBlock.Settings settings) {
		super(settings);
	}

	@Override
	public ActionResult onUse(BlockState state, World world, BlockPos pos, PlayerEntity player, Hand hand, BlockHitResult hit) {
		if (player.getStackInHand(hand).isOf(dev.rackcraft.RcItems.ITEMS.get("multimeter"))) return ActionResult.PASS;
		if (!world.isClient && world.getBlockEntity(pos) instanceof MachineBlockEntity machine) {
			if (machine.sentryMode() == 3) {
				player.sendMessage(Text.literal("Bolted down to the base's rules. It does not take requests.").formatted(Formatting.GRAY), true);
			} else {
				machine.setSentryMode((machine.sentryMode() + 1) % 3);
				player.sendMessage(Text.literal("Sentry targets: " + MODES[machine.sentryMode()]).formatted(Formatting.YELLOW), true);
			}
		}
		return ActionResult.success(world.isClient);
	}
}
