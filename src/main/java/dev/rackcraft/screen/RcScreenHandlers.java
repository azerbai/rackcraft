package dev.rackcraft.screen;

import dev.rackcraft.Rackcraft;
import net.fabricmc.fabric.api.screenhandler.v1.ExtendedScreenHandlerType;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.screen.ScreenHandlerType;
import net.minecraft.entity.player.PlayerInventory;

public final class RcScreenHandlers {
	public static final ScreenHandlerType<MachineScreenHandler> RACK = register("rack", MachineScreenHandler.Mode.RACK);
	public static final ScreenHandlerType<MachineScreenHandler> SINGLE_SLOT = register("single_slot", MachineScreenHandler.Mode.SINGLE_SLOT);
	public static final ScreenHandlerType<MachineScreenHandler> MACHINE_STATUS = register("machine_status", MachineScreenHandler.Mode.MACHINE_STATUS);
	public static final ScreenHandlerType<MachineScreenHandler> CONTROLLER = register("controller", MachineScreenHandler.Mode.CONTROLLER);
	public static final ScreenHandlerType<MachineScreenHandler> MONITOR_WALL = register("monitor_wall", MachineScreenHandler.Mode.MONITOR_WALL);
	public static final ScreenHandlerType<MachineScreenHandler> EXCHANGE = register("exchange", MachineScreenHandler.Mode.EXCHANGE);

	private RcScreenHandlers() {}

	private static ScreenHandlerType<MachineScreenHandler> register(String id, MachineScreenHandler.Mode mode) {
		ExtendedScreenHandlerType<MachineScreenHandler> type = new ExtendedScreenHandlerType<>(
				(syncId, inventory, buf) -> new MachineScreenHandler(syncId, inventory, buf, mode));
		return Registry.register(Registries.SCREEN_HANDLER, Rackcraft.id(id), type);
	}

	public static ScreenHandlerType<MachineScreenHandler> type(MachineScreenHandler.Mode mode) {
		return switch (mode) {
			case RACK -> RACK;
			case SINGLE_SLOT -> SINGLE_SLOT;
			case MACHINE_STATUS -> MACHINE_STATUS;
			case CONTROLLER -> CONTROLLER;
			case MONITOR_WALL -> MONITOR_WALL;
			case EXCHANGE -> EXCHANGE;
		};
	}
}