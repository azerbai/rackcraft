package dev.rackcraft.item;

import dev.rackcraft.world.Weapons;
import java.util.List;
import net.minecraft.client.item.TooltipContext;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.TypedActionResult;
import net.minecraft.util.UseAction;
import net.minecraft.world.World;

/**
 * An energy or hydrogen weapon. Flamethrower and Lance Laser fire for as long as use is held, the Railgun charges while
 * held and fires on release, and the Arc Coil and Plasma Rifle fire once per click. All the work is in {@link Weapons}.
 */
public final class WeaponItem extends Item {
	private final Weapons.Kind kind;

	public WeaponItem(Settings settings, Weapons.Kind kind) {
		super(settings.maxCount(1));
		this.kind = kind;
	}

	public Weapons.Kind kind() { return kind; }

	private boolean held() { return kind == Weapons.Kind.FLAMETHROWER || kind == Weapons.Kind.LANCE_LASER || kind == Weapons.Kind.RAILGUN; }

	@Override
	public TypedActionResult<ItemStack> use(World world, PlayerEntity user, Hand hand) {
		ItemStack stack = user.getStackInHand(hand);
		if (held()) {
			user.setCurrentHand(hand);
			return TypedActionResult.consume(stack);
		}
		if (!world.isClient && user instanceof ServerPlayerEntity player) {
			String problem = Weapons.fire(kind, player, stack, world.getTime(), 1);
			if (problem != null) player.sendMessage(Text.literal(problem).formatted(Formatting.RED), true);
			else player.getItemCooldownManager().set(this, kind == Weapons.Kind.ARC_COIL ? 8 : 6);
		}
		return TypedActionResult.success(stack, world.isClient);
	}

	@Override
	public void usageTick(World world, LivingEntity user, ItemStack stack, int remainingUseTicks) {
		if (world.isClient || !(user instanceof ServerPlayerEntity player) || kind == Weapons.Kind.RAILGUN) return;
		int held = getMaxUseTime(stack) - remainingUseTicks;
		if (held % 2 != 0) return;
		String problem = Weapons.fire(kind, player, stack, world.getTime(), 1);
		if (problem != null) {
			player.sendMessage(Text.literal(problem).formatted(Formatting.RED), true);
			player.stopUsingItem();
		}
	}

	@Override
	public void onStoppedUsing(ItemStack stack, World world, LivingEntity user, int remainingUseTicks) {
		if (world.isClient || kind != Weapons.Kind.RAILGUN || !(user instanceof ServerPlayerEntity player)) return;
		int charged = getMaxUseTime(stack) - remainingUseTicks;
		if (charged < 6) return;
		double charge = Math.min(1.0, charged / (double) dev.rackcraft.RackcraftConfig.values.weapons.railChargeTicks);
		String problem = Weapons.fire(kind, player, stack, world.getTime(), charge);
		if (problem != null) player.sendMessage(Text.literal(problem).formatted(Formatting.RED), true);
		else player.getItemCooldownManager().set(this, dev.rackcraft.RackcraftConfig.values.weapons.railCooldownTicks);
	}

	@Override
	public int getMaxUseTime(ItemStack stack) { return 72000; }

	@Override
	public UseAction getUseAction(ItemStack stack) { return kind == Weapons.Kind.RAILGUN ? UseAction.BOW : held() ? UseAction.SPEAR : UseAction.NONE; }

	@Override
	public void appendTooltip(ItemStack stack, World world, List<Text> tooltip, net.minecraft.client.item.TooltipContext context) {
		long now = world == null ? 0 : world.getTime();
		int heat = Weapons.heatPercent(stack, now);
		tooltip.add(Text.literal("Heat " + heat + "%" + (Weapons.locked(stack, now) ? " (venting)" : "")).formatted(heat >= 70 ? Formatting.RED : Formatting.GRAY));
		tooltip.add(Text.literal(switch (kind) {
			case FLAMETHROWER -> "Hold to spray a cone of fire. Burns Hydrogen Canisters.";
			case ARC_COIL -> "Click for lightning that chains between mobs, trips breakers and freezes robots.";
			case RAILGUN -> "Hold to charge, release to fire a slug through mobs and a few plain blocks. Needs Steel Slugs and Battery Cells.";
			case PLASMA_RIFLE -> "Click for a splash bolt. Hydrogen and power. Overheats fast without a Coolant Pack in your pack.";
			case LANCE_LASER -> "Hold for a beam that melts cobweb and ice and goes round armour. Hot.";
		}).formatted(Formatting.DARK_GRAY));
	}
}
