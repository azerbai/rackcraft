package dev.rackcraft.client;

import net.fabricmc.api.ClientModInitializer;
import dev.rackcraft.screen.RcScreenHandlers;
import net.fabricmc.fabric.api.client.screenhandler.v1.ScreenRegistry;
import dev.rackcraft.client.screen.RackScreen;
import dev.rackcraft.client.screen.SingleSlotScreen;
import dev.rackcraft.client.screen.MachineStatusScreen;
import dev.rackcraft.client.screen.ControllerScreen;
import dev.rackcraft.client.screen.MonitorWallScreen;
import dev.rackcraft.client.screen.GuideScreen;
import dev.rackcraft.generated.ContentIds;
import dev.rackcraft.item.FieldManualItem;
import net.fabricmc.fabric.api.client.item.v1.ItemTooltipCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.registry.Registries;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

public final class RackcraftClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		HeatOverlay.register();
		CoinHud.register();
		ShackleChains.register();
		DizzyView.register();
		FaultOverlay.register();
		ScreenRegistry.register(RcScreenHandlers.RACK, RackScreen::new);
		ScreenRegistry.register(RcScreenHandlers.SINGLE_SLOT, SingleSlotScreen::new);
		ScreenRegistry.register(RcScreenHandlers.MACHINE_STATUS, MachineStatusScreen::new);
		ScreenRegistry.register(RcScreenHandlers.CONTROLLER, ControllerScreen::new);
		ScreenRegistry.register(RcScreenHandlers.MONITOR_WALL, MonitorWallScreen::new);
		ScreenRegistry.register(RcScreenHandlers.EXCHANGE, dev.rackcraft.client.screen.ExchangeScreen::new);
		ScreenRegistry.register(RcScreenHandlers.CREATIVE, dev.rackcraft.client.screen.CreativeMachineScreen::new);
		ScreenRegistry.register(RcScreenHandlers.STORAGE_ARRAY, dev.rackcraft.client.screen.StorageScreen::new);
		ScreenRegistry.register(RcScreenHandlers.TAPE_LIBRARY, dev.rackcraft.client.screen.StorageScreen::new);
		ScreenRegistry.register(RcScreenHandlers.TRANSMITTER, dev.rackcraft.client.screen.TransmitterScreen::new);
		ScreenRegistry.register(RcScreenHandlers.TERMINAL, dev.rackcraft.client.screen.TerminalScreen::new);
		ScreenRegistry.register(RcScreenHandlers.WORKSTATION, dev.rackcraft.client.screen.WorkstationScreen::new);
		ScreenRegistry.register(RcScreenHandlers.REACTOR, dev.rackcraft.client.screen.ReactorScreen::new);
		ScreenRegistry.register(RcScreenHandlers.PROCESSOR, dev.rackcraft.client.screen.ProcessorScreen::new);
		ScreenRegistry.register(RcScreenHandlers.OPERATIONS, dev.rackcraft.client.screen.OpsScreen::new);
		ScreenRegistry.register(RcScreenHandlers.DARKNET, dev.rackcraft.client.screen.DarknetScreen::new);
		ScreenRegistry.register(RcScreenHandlers.WORKCELL, dev.rackcraft.client.screen.WorkcellScreen::new);
		ScreenRegistry.register(RcScreenHandlers.LAUNCH, dev.rackcraft.client.screen.LaunchScreen::new);
		net.fabricmc.fabric.api.blockrenderlayer.v1.BlockRenderLayerMap.INSTANCE.putBlock(dev.rackcraft.RcBlocks.get("launch_tower"),
				net.minecraft.client.render.RenderLayer.getCutout());
		net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry.register(dev.rackcraft.entity.RcEntities.ROCKET,
				dev.rackcraft.client.render.RocketRenderer::new);
		net.minecraft.client.render.block.entity.BlockEntityRendererFactories.register(dev.rackcraft.RcBlocks.BELT_ENTITY,
				dev.rackcraft.client.render.BeltRenderer::new);
		net.minecraft.client.render.block.entity.BlockEntityRendererFactories.register(dev.rackcraft.RcBlocks.MACHINE_ENTITY,
				dev.rackcraft.client.render.RobotArmRenderer::new);
		net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry.register(dev.rackcraft.entity.RcEntities.MAINTENANCE_DRONE,
				dev.rackcraft.client.render.DroneRenderer::new);
		net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.registerGlobalReceiver(
				dev.rackcraft.darknet.DarknetScreenHandler.SYNC, (client, handler, buf, responseSender) -> {
					int syncId = buf.readVarInt();
					net.minecraft.network.PacketByteBuf copy = net.fabricmc.fabric.api.networking.v1.PacketByteBufs.copy(buf);
					client.execute(() -> {
						if (client.player != null && client.player.currentScreenHandler
								instanceof dev.rackcraft.darknet.DarknetScreenHandler darknet && darknet.syncId == syncId) {
							darknet.applySync(copy);
						}
					});
				});
		net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.registerGlobalReceiver(
				dev.rackcraft.compute.OpsScreenHandler.SYNC, (client, handler, buf, responseSender) -> {
					int syncId = buf.readVarInt();
					net.minecraft.network.PacketByteBuf copy = net.fabricmc.fabric.api.networking.v1.PacketByteBufs.copy(buf);
					client.execute(() -> {
						if (client.player != null && client.player.currentScreenHandler
								instanceof dev.rackcraft.compute.OpsScreenHandler ops && ops.syncId == syncId) {
							ops.applySync(copy);
						}
					});
				});
		net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.registerGlobalReceiver(
				dev.rackcraft.storage.TerminalScreenHandler.SYNC, (client, handler, buf, responseSender) -> {
					int syncId = buf.readVarInt();
					net.minecraft.network.PacketByteBuf copy = net.fabricmc.fabric.api.networking.v1.PacketByteBufs.copy(buf);
					client.execute(() -> {
						if (client.player != null && client.player.currentScreenHandler
								instanceof dev.rackcraft.storage.TerminalScreenHandler terminal && terminal.syncId == syncId) {
							terminal.applySync(copy);
						}
					});
				});
		FieldManualItem.openScreen = () -> MinecraftClient.getInstance().setScreen(new GuideScreen());
		ItemTooltipCallback.EVENT.register((stack, context, lines) -> {
			var id = Registries.ITEM.getId(stack.getItem());
			if (!id.getNamespace().equals("rackcraft")) return;
			// A half-built workpiece says how far along the Assembly Line it got and what it needs next.
			for (String line : dev.rackcraft.world.AssemblyLine.describe(stack)) lines.add(Text.literal(line).formatted(Formatting.AQUA));
			net.minecraft.item.Item was = dev.rackcraft.world.DroneDocks.failedAs(stack);
			if (was != null) lines.add(Text.literal("Was a " + new net.minecraft.item.ItemStack(was).getName().getString()).formatted(Formatting.GRAY));
			Integer burnTicks = ContentIds.FUEL_TICKS.get(id.getPath());
			if (burnTicks != null) {
				lines.add(Text.translatable("tooltip.rackcraft.fuel", burnTicks / 20).formatted(Formatting.GOLD));
			}
		});
	}
}