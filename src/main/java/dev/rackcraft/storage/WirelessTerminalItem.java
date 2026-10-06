package dev.rackcraft.storage;

import dev.rackcraft.RcBlocks;
import java.util.List;
import net.fabricmc.fabric.api.screenhandler.v1.ExtendedScreenHandlerFactory;
import net.minecraft.client.item.TooltipContext;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.ItemUsageContext;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.TypedActionResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

/** A pocket Storage Terminal linked to a Wireless Transmitter. Range and dimensions depend on the transmitter's level. */
public final class WirelessTerminalItem extends Item {
	public WirelessTerminalItem(Item.Settings settings) {
		super(settings.maxCount(1));
	}

	@Override
	public ActionResult useOnBlock(ItemUsageContext context) {
		PlayerEntity player = context.getPlayer();
		if (player == null || !player.isSneaking()
				|| !context.getWorld().getBlockState(context.getBlockPos()).isOf(RcBlocks.get("wireless_transmitter"))) {
			return ActionResult.PASS;
		}
		if (!context.getWorld().isClient) {
			NbtCompound link = context.getStack().getOrCreateSubNbt("Link");
			link.putString("Dimension", context.getWorld().getRegistryKey().getValue().toString());
			link.putLong("Pos", context.getBlockPos().asLong());
			player.sendMessage(Text.translatable("storage.rackcraft.linked", context.getBlockPos().toShortString())
					.formatted(Formatting.GREEN), true);
		}
		return ActionResult.success(context.getWorld().isClient);
	}

	@Override
	public TypedActionResult<ItemStack> use(World world, PlayerEntity player, Hand hand) {
		ItemStack stack = player.getStackInHand(hand);
		if (world.isClient) return TypedActionResult.success(stack);
		StorageService.Access access = link(stack);
		Text problem = access == null ? Text.translatable("storage.rackcraft.not_linked") : reach(player, access);
		if (problem != null) {
			player.sendMessage(problem.copy().formatted(Formatting.RED), true);
			return TypedActionResult.fail(stack);
		}
		player.openHandledScreen(new ExtendedScreenHandlerFactory() {
			@Override
			public void writeScreenOpeningData(ServerPlayerEntity serverPlayer, PacketByteBuf buf) {
				access.write(buf);
			}

			@Override
			public Text getDisplayName() {
				return stack.getName();
			}

			@Override
			public ScreenHandler createMenu(int syncId, PlayerInventory inventory, PlayerEntity opener) {
				return new TerminalScreenHandler(syncId, inventory, access);
			}
		});
		return TypedActionResult.success(stack);
	}

	public static StorageService.Access link(ItemStack stack) {
		NbtCompound link = stack.getSubNbt("Link");
		if (link == null || !link.contains("Pos")) return null;
		Identifier dimension = Identifier.tryParse(link.getString("Dimension"));
		if (dimension == null) return null;
		return new StorageService.Access(RegistryKey.of(RegistryKeys.WORLD, dimension), BlockPos.fromLong(link.getLong("Pos")), true);
	}

	/** Why the player can't reach the transmitter right now, or null if they can. Server side. */
	public static Text reach(PlayerEntity player, StorageService.Access access) {
		if (player.getServer() == null) return null;
		StorageState.Transmitter info = StorageState.get(player.getServer())
				.transmitter(StorageState.transmitterKey(access.dimension(), access.pos()));
		if (info == null || !info.online()) return Text.translatable("storage.rackcraft.transmitter_offline");
		if (player.getWorld().getRegistryKey() != access.dimension()) {
			return info.level() >= StorageService.MULTIDIMENSIONAL_LEVEL ? null : Text.translatable("storage.rackcraft.other_dimension");
		}
		int range = StorageService.RANGES[Math.max(0, Math.min(StorageService.MAX_LEVEL, info.level()))];
		double distance = Math.sqrt(player.squaredDistanceTo(access.pos().toCenterPos()));
		if (range != Integer.MAX_VALUE && distance > range) {
			return Text.translatable("storage.rackcraft.out_of_range", (int) distance, range);
		}
		return null;
	}

	@Override
	public void appendTooltip(ItemStack stack, World world, List<Text> tooltip, TooltipContext context) {
		StorageService.Access access = link(stack);
		tooltip.add((access == null ? Text.translatable("storage.rackcraft.not_linked")
				: Text.literal("Linked: " + access.pos().toShortString() + " in " + access.dimension().getValue()))
				.formatted(Formatting.GRAY));
	}
}
