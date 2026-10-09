package dev.rackcraft.item;

import dev.rackcraft.compute.Research;
import dev.rackcraft.compute.ResearchLab;
import dev.rackcraft.entity.EmpGrenadeEntity;
import java.util.List;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.TypedActionResult;
import net.minecraft.world.World;

/** Throw it and every Rackcraft machine, drone, turret and robot nearby goes quiet for three minutes. */
public final class EmpGrenadeItem extends Item {
	public EmpGrenadeItem(Settings settings) {
		super(settings.maxCount(8));
	}

	@Override
	public TypedActionResult<ItemStack> use(World world, PlayerEntity user, Hand hand) {
		ItemStack stack = user.getStackInHand(hand);
		if (world instanceof ServerWorld serverWorld && !ResearchLab.get(serverWorld).done("directed_energy")) {
			user.sendMessage(Text.literal("Inert until " + Research.get("directed_energy").name() + " is researched").formatted(Formatting.RED), true);
			return TypedActionResult.fail(stack);
		}
		world.playSound(null, user.getX(), user.getY(), user.getZ(), SoundEvents.ENTITY_SNOWBALL_THROW, SoundCategory.PLAYERS, 0.6f, 0.5f);
		if (!world.isClient) {
			EmpGrenadeEntity grenade = new EmpGrenadeEntity(world, user);
			grenade.setItem(stack);
			grenade.setVelocity(user, user.getPitch(), user.getYaw(), 0, 1.3f, 1);
			world.spawnEntity(grenade);
		}
		if (!user.getAbilities().creativeMode) stack.decrement(1);
		return TypedActionResult.success(stack, world.isClient);
	}

	@Override
	public void appendTooltip(ItemStack stack, World world, List<Text> tooltip, net.minecraft.client.item.TooltipContext context) {
		tooltip.add(Text.literal("Switches off every Rackcraft machine, drone, turret and robot within 10 blocks for 3 minutes.").formatted(Formatting.GRAY));
		tooltip.add(Text.literal("PDUs trip. Vault doors give up. Phones, sadly, are fine.").formatted(Formatting.DARK_GRAY));
	}
}
