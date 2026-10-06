package dev.rackcraft.compute;

import dev.rackcraft.RcItems;
import dev.rackcraft.world.SimTicker;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Formatting;

/**
 * Shackling librarians. This runs before the villager's own interaction, so holding Shackles never opens
 * the trading screen. Sneak-right-click a shackled villager with an empty hand to set them free.
 */
public final class ComputeEvents {
	private ComputeEvents() {}

	public static void register() {
		UseEntityCallback.EVENT.register((player, world, hand, entity, hit) -> {
			if (!(entity instanceof VillagerEntity villager)) return ActionResult.PASS;
			ItemStack held = player.getStackInHand(hand);
			boolean shackles = held.isOf(RcItems.ITEMS.get("shackles"));
			boolean release = player.isSneaking() && held.isEmpty() && villager.getCommandTags().contains(TrainingStations.SHACKLED_TAG);
			if (!shackles && !release) return ActionResult.PASS;
			if (!(world instanceof ServerWorld serverWorld)) return ActionResult.SUCCESS;
			var machines = SimTicker.machines(serverWorld);
			if (release) {
				if (TrainingStations.release(serverWorld, villager, machines)) {
					player.giveItemStack(new ItemStack(RcItems.ITEMS.get("shackles")));
					player.sendMessage(Text.literal("Set free. They immediately go back to complaining about the price of paper.")
							.formatted(Formatting.GREEN), true);
				}
				return ActionResult.SUCCESS;
			}
			String problem = TrainingStations.shackle(serverWorld, villager, machines);
			if (problem != null) {
				player.sendMessage(Text.literal(problem).formatted(Formatting.RED), true);
			} else {
				if (!player.isCreative()) held.decrement(1);
				player.sendMessage(Text.literal("Shackled to the Scriptorium Desk. The librarian sighs and picks up a quill.")
						.formatted(Formatting.GOLD), true);
			}
			return ActionResult.SUCCESS;
		});
	}
}
