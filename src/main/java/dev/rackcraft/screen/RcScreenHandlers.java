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
	public static final ScreenHandlerType<MachineScreenHandler> CREATIVE = register("creative", MachineScreenHandler.Mode.CREATIVE);
	public static final ScreenHandlerType<MachineScreenHandler> STORAGE_ARRAY = register("storage_array", MachineScreenHandler.Mode.STORAGE_ARRAY);
	public static final ScreenHandlerType<MachineScreenHandler> TAPE_LIBRARY = register("tape_library", MachineScreenHandler.Mode.TAPE_LIBRARY);
	public static final ScreenHandlerType<MachineScreenHandler> TRANSMITTER = register("transmitter", MachineScreenHandler.Mode.TRANSMITTER);
	public static final ScreenHandlerType<MachineScreenHandler> WORKSTATION = register("workstation", MachineScreenHandler.Mode.WORKSTATION);
	public static final ScreenHandlerType<MachineScreenHandler> REACTOR = register("reactor", MachineScreenHandler.Mode.REACTOR);
	public static final ScreenHandlerType<MachineScreenHandler> PROCESSOR = register("processor", MachineScreenHandler.Mode.PROCESSOR);
	public static final ScreenHandlerType<MachineScreenHandler> WORKCELL = register("workcell", MachineScreenHandler.Mode.WORKCELL);
	public static final ScreenHandlerType<MachineScreenHandler> LAUNCH = register("launch", MachineScreenHandler.Mode.LAUNCH);
	public static final ScreenHandlerType<MachineScreenHandler> SITE = register("site", MachineScreenHandler.Mode.SITE);
	public static final ScreenHandlerType<dev.rackcraft.compute.OpsScreenHandler> OPERATIONS = Registry.register(
			Registries.SCREEN_HANDLER, Rackcraft.id("operations"), new ExtendedScreenHandlerType<>((syncId, inventory, buf) ->
					new dev.rackcraft.compute.OpsScreenHandler(syncId, inventory, buf.readBlockPos())));
	public static final ScreenHandlerType<dev.rackcraft.darknet.DarknetScreenHandler> DARKNET = Registry.register(
			Registries.SCREEN_HANDLER, Rackcraft.id("darknet"), new ExtendedScreenHandlerType<>((syncId, inventory, buf) ->
					new dev.rackcraft.darknet.DarknetScreenHandler(syncId, inventory, buf.readBlockPos())));
	public static final ScreenHandlerType<dev.rackcraft.storage.TerminalScreenHandler> TERMINAL = Registry.register(
			Registries.SCREEN_HANDLER, Rackcraft.id("terminal"), new ExtendedScreenHandlerType<>((syncId, inventory, buf) ->
					new dev.rackcraft.storage.TerminalScreenHandler(syncId, inventory, dev.rackcraft.storage.StorageService.Access.read(buf))));

	private RcScreenHandlers() {}

	/**
	 * Registers every screen type, by loading this class during mod initialisation. Without it a dedicated server
	 * (no client code to load it early) registered them on first use, after the registries froze, and crashed.
	 */
	public static void register() {}

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
			case CREATIVE -> CREATIVE;
			case STORAGE_ARRAY -> STORAGE_ARRAY;
			case TAPE_LIBRARY -> TAPE_LIBRARY;
			case TRANSMITTER -> TRANSMITTER;
			case WORKSTATION -> WORKSTATION;
			case REACTOR -> REACTOR;
			case PROCESSOR -> PROCESSOR;
			case WORKCELL -> WORKCELL;
			case LAUNCH -> LAUNCH;
			case SITE -> SITE;
		};
	}
}