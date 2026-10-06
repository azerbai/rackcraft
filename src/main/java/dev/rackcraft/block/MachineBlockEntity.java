package dev.rackcraft.block;

import dev.rackcraft.RcBlocks;
import dev.rackcraft.sim.NetKind;
import dev.rackcraft.sim.ServerModel;
import dev.rackcraft.world.NetworkManager;
import dev.rackcraft.world.SimTicker;
import dev.rackcraft.world.FacilityManager;
import dev.rackcraft.screen.MachineScreenHandler;
import dev.rackcraft.screen.RcScreenHandlers;
import net.fabricmc.fabric.api.screenhandler.v1.ExtendedScreenHandlerFactory;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.server.network.ServerPlayerEntity;
import java.util.List;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.inventory.Inventories;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.Registries;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.NamedScreenHandlerFactory;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.ScreenHandlerType;
import net.minecraft.text.Text;
import net.minecraft.util.collection.DefaultedList;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.server.world.ServerWorld;

public final class MachineBlockEntity extends BlockEntity implements net.minecraft.inventory.SidedInventory, ExtendedScreenHandlerFactory {
	private final DefaultedList<ItemStack> inventory = DefaultedList.ofSize(9, ItemStack.EMPTY);
	private int loadLimitPercent = 100;
	private double chargeKws;
	private boolean tripped;
	private String feedLabel = "A";
	private double inletCelsius = 24;
	private double exhaustCelsius = 24;
	private double powerSatisfaction;
	private double powerKw;
	private double load;
	private double thermalFactor = 1;
	private double creditRemainder;
	private int dieselSpinupSteps;
	private int fuelBurnTicks;
	private int fuelBurnTotal;
	private RackStatus rackStatus = RackStatus.EMPTY;
	private double miningRate;
	private double networkSupplyKw;
	private double networkDemandKw;
	private double networkCapacityKw;
	private final java.util.Map<String, Double> creativeValues = new java.util.HashMap<>();
	private boolean storageOnline;
	private int transmitterLevel;
	// Freshwater pumps: readings refreshed by FreshwaterCooling; only the drawn water is saved.
	private int pumpSources;
	private int pumpUnits;
	private int pumpStatus;
	private double pumpUsed;
	private double waterDrawn;
	// Art tables and scriptorium desks: work toward the next item, and consumables used.
	private double workProgress;
	private int itemsMade;
	private int toolUses;
	private int workers;
	private java.util.UUID boundVillager;

	public MachineBlockEntity(BlockPos pos, BlockState state) {
		super(RcBlocks.MACHINE_ENTITY, pos, state);
	}

	@Override
	public void setWorld(net.minecraft.world.World world) {
		super.setWorld(world);
		if (world instanceof ServerWorld serverWorld) {
			String id = Registries.BLOCK.getId(getCachedState().getBlock()).getPath();
			NetworkManager.get(serverWorld).register(pos, networkKinds(id));
			SimTicker.registerBlockEntity(serverWorld, this);
		}
	}

	@Override
	public void markRemoved() {
		if (world instanceof ServerWorld serverWorld) {
			NetworkManager.get(serverWorld).unregister(pos);
			SimTicker.unregisterBlockEntity(serverWorld, this);
		}
		super.markRemoved();
	}

	public static java.util.Set<NetKind> networkKinds(String id) {
		java.util.EnumSet<NetKind> kinds = java.util.EnumSet.noneOf(NetKind.class);
		if (List.of("diesel_generator", "solar_panel", "wind_turbine", "pdu", "server_rack",
				"exhaust_fan", "cooling_tower", "crac_unit", "battery_bank", "utility_intake",
				"facility_controller", "cdu", "modular_reactor", "freshwater_pump").contains(id)) kinds.add(NetKind.POWER);
		// Racks join the coolant network for freshwater cooling from pumps.
		if (List.of("cooling_tower", "crac_unit", "cdu", "freshwater_pump", "server_rack").contains(id)) kinds.add(NetKind.COOLANT);
		if (List.of("server_rack", "uplink_router", "core_router", "facility_controller",
				"monitoring_wall", "creative_router").contains(id)) kinds.add(NetKind.DATA);
		if (List.of("creative_power", "creative_rack").contains(id)) kinds.add(NetKind.POWER);
		if (List.of("storage_array", "tape_library", "wireless_transmitter").contains(id)) kinds.add(NetKind.POWER);
		if (List.of("storage_array", "tape_library", "storage_terminal", "wireless_transmitter").contains(id)) kinds.add(NetKind.DATA);
		return kinds;
	}

	@Override
	public int size() { return inventory.size(); }
	@Override
	public boolean isEmpty() { return inventory.stream().allMatch(ItemStack::isEmpty); }
	@Override
	public ItemStack getStack(int slot) { return inventory.get(slot); }

	@Override
	public ItemStack removeStack(int slot, int amount) {
		ItemStack result = Inventories.splitStack(inventory, slot, amount);
		if (!result.isEmpty()) markDirty();
		return result;
	}

	@Override
	public ItemStack removeStack(int slot) {
		ItemStack result = Inventories.removeStack(inventory, slot);
		if (!result.isEmpty()) markDirty();
		return result;
	}

	@Override
	public void setStack(int slot, ItemStack stack) {
		inventory.set(slot, stack);
		if (stack.getCount() > getMaxCountPerStack()) stack.setCount(getMaxCountPerStack());
		markDirty();
	}

	@Override
	public void clear() {
		inventory.clear();
		markDirty();
	}

	private static final int[] ALL_SLOTS = {0, 1, 2, 3, 4, 5, 6, 7, 8};

	@Override
	public int[] getAvailableSlots(net.minecraft.util.math.Direction side) { return ALL_SLOTS; }

	@Override
	public boolean canInsert(int slot, ItemStack stack, net.minecraft.util.math.Direction side) { return isValid(slot, stack); }

	/** Hoppers take only finished work out of art tables and desks, never their paper or tools. */
	@Override
	public boolean canExtract(int slot, ItemStack stack, net.minecraft.util.math.Direction side) {
		String id = blockId();
		return !(id.equals("art_table") || id.equals("writing_desk")) || slot == 2;
	}

	@Override
	public boolean canPlayerUse(PlayerEntity player) {
		return world != null && world.getBlockEntity(pos) == this
				&& player.squaredDistanceTo(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5) <= 64;
	}

	@Override
	public boolean isValid(int slot, ItemStack stack) {
		String blockId = Registries.BLOCK.getId(getCachedState().getBlock()).getPath();
		if (blockId.equals("server_rack")) {
			String itemId = Registries.ITEM.getId(stack.getItem()).getPath();
			ServerModel.Module module = ServerModel.Module.byItemId(itemId);
			if (module == null) return false;
			int installedUnits = 0;
			for (int index = 0; index < inventory.size(); index++) {
				if (index == slot || inventory.get(index).isEmpty()) continue;
				ServerModel.Module installed = ServerModel.Module.byItemId(
						Registries.ITEM.getId(inventory.get(index).getItem()).getPath());
				installedUnits += installed == null ? 1 : installed.units();
			}
			return installedUnits + module.units() <= 8;
		}
		if (blockId.equals("diesel_generator") || blockId.equals("modular_reactor")) return slot == 0;
		if (blockId.equals("storage_array")) {
			return slot < 8 && stack.getItem() instanceof dev.rackcraft.storage.DriveItem drive && !drive.cold();
		}
		if (blockId.equals("tape_library")) {
			return slot < 4 && stack.getItem() instanceof dev.rackcraft.storage.DriveItem drive && drive.cold();
		}
		if (blockId.equals("fire_suppression_tank")) return slot == 0;
		if (blockId.equals("art_table") || blockId.equals("writing_desk")) {
			if (slot == 0) return stack.isOf(net.minecraft.item.Items.PAPER);
			if (slot == 1) return blockId.equals("art_table") ? stack.isOf(dev.rackcraft.RcItems.ITEMS.get("crayons"))
					: stack.isOf(net.minecraft.item.Items.INK_SAC);
		}
		return false;
	}

	public int loadLimitPercent() { return loadLimitPercent; }
	public void setLoadLimitPercent(int value) {
		if (value == 25 || value == 50 || value == 75 || value == 100) {
			loadLimitPercent = value;
			markDirty();
		}
	}
	public double chargeKws() { return chargeKws; }
	public void setChargeKws(double value) { chargeKws = Math.max(0, value); markDirty(); }
	public boolean isTripped() { return tripped; }
	public void setTripped(boolean value) { tripped = value; markDirty(); }
	public String feedLabel() { return feedLabel; }
	public void toggleFeedLabel() { feedLabel = feedLabel.equals("A") ? "B" : "A"; markDirty(); }
	public String blockId() { return Registries.BLOCK.getId(getCachedState().getBlock()).getPath(); }
	public List<ServerModel.Module> modules() {
		java.util.ArrayList<ServerModel.Module> modules = new java.util.ArrayList<>();
		for (ItemStack stack : inventory) {
			if (stack.isEmpty()) continue;
			ServerModel.Module module = ServerModel.Module.byItemId(Registries.ITEM.getId(stack.getItem()).getPath());
			if (module != null) modules.add(module);
		}
		return List.copyOf(modules);
	}
	public double inletCelsius() { return inletCelsius; }
	public double exhaustCelsius() { return exhaustCelsius; }
	public double powerSatisfaction() { return powerSatisfaction; }
	public double powerKw() { return powerKw; }
	public double load() { return load; }
	public double thermalFactor() { return thermalFactor; }
	public int dieselSpinupSteps() { return dieselSpinupSteps; }
	public void setDieselSpinupSteps(int value) { dieselSpinupSteps = Math.max(0, Math.min(20, value)); }
	public int fuelBurnTicks() { return fuelBurnTicks; }
	public void setFuelBurnTicks(int value) { fuelBurnTicks = Math.max(0, value); }
	public int fuelBurnTotal() { return Math.max(fuelBurnTotal, fuelBurnTicks); }
	public void startFuel(int ticks) { fuelBurnTicks = Math.max(0, ticks); fuelBurnTotal = fuelBurnTicks; markDirty(); }
	public RackStatus rackStatus() { return rackStatus; }
	public double miningRate() { return miningRate; }
	public void setMining(RackStatus status, double creditsPerSecond) {
		rackStatus = status;
		miningRate = Math.max(0, creditsPerSecond);
	}
	public double networkSupplyKw() { return networkSupplyKw; }
	public double networkDemandKw() { return networkDemandKw; }
	public double networkCapacityKw() { return networkCapacityKw; }

	/** Storage arrays and tape libraries: whether their drives are reachable right now. */
	public boolean storageOnline() { return storageOnline; }
	public void setStorageOnline(boolean online) { storageOnline = online; }
	public int transmitterLevel() { return transmitterLevel; }
	public void setTransmitterLevel(int level) {
		transmitterLevel = Math.max(0, Math.min(dev.rackcraft.storage.StorageService.MAX_LEVEL, level));
		markDirty();
	}
	public int driveCount() {
		int drives = 0;
		for (ItemStack stack : inventory) if (stack.getItem() instanceof dev.rackcraft.storage.DriveItem) drives++;
		return drives;
	}

	public int pumpSources() { return pumpSources; }
	public int pumpUnits() { return pumpUnits; }
	public int pumpStatus() { return pumpStatus; }
	public double pumpUsed() { return pumpUsed; }
	public double waterDrawn() { return waterDrawn; }
	public void setPumpReadings(int sources, int units, int status) {
		pumpSources = sources;
		pumpUnits = units;
		pumpStatus = status;
	}
	public void setPumpUsed(double units) { pumpUsed = units; }
	public void addWaterDrawn(double unitSeconds) { waterDrawn = Math.max(0, waterDrawn + unitSeconds); markDirty(); }

	public double workProgress() { return workProgress; }
	public void setWorkProgress(double value) { workProgress = Math.max(0, value); markDirty(); }
	public int itemsMade() { return itemsMade; }
	public void setItemsMade(int value) { itemsMade = Math.max(0, value); markDirty(); }
	public int toolUses() { return toolUses; }
	public void setToolUses(int value) { toolUses = Math.max(0, value); markDirty(); }
	/** Villagers working at this table or desk right now; refreshed every step, not saved. */
	public int workers() { return workers; }
	public void setWorkers(int value) { workers = value; }
	public java.util.UUID boundVillager() { return boundVillager; }
	public void setBoundVillager(java.util.UUID villager) { boundVillager = villager; markDirty(); }

	/** A creative machine's in-game setting, or its default if never changed. */
	public double creativeValue(String key) {
		dev.rackcraft.CreativeSettings.Setting setting = dev.rackcraft.CreativeSettings.find(blockId(), key);
		if (setting == null) return 0;
		return creativeValues.getOrDefault(key, setting.defaultValue());
	}

	public boolean setCreativeValue(String key, double value) {
		dev.rackcraft.CreativeSettings.Setting setting = dev.rackcraft.CreativeSettings.find(blockId(), key);
		if (setting == null) return false;
		creativeValues.put(key, setting.clamp(value));
		markDirty();
		return true;
	}
	public void setNetworkStats(double deliveredKw, double demandKw) {
		networkSupplyKw = deliveredKw;
		networkDemandKw = demandKw;
		if (deliveredKw == 0 && demandKw == 0) networkCapacityKw = 0;
	}
	public void setNetworkCapacity(double capacityKw) { networkCapacityKw = capacityKw; }
	/** Output for sources, draw for consumers, signed charge rate for batteries. Racks set it via setRackStats. */
	public void setPowerKw(double value) { powerKw = value; }
	public void setRackStats(double inlet, double exhaust, double power, double rackLoad, double thermal) {
		inletCelsius = inlet;
		exhaustCelsius = exhaust;
		powerKw = power;
		load = rackLoad;
		thermalFactor = thermal;
	}
	public void setPowerSatisfaction(double value) {
		powerSatisfaction = Math.max(0, Math.min(1, value));
	}
	public long accrueCredits(double creditsPerSecond, double dtSeconds) {
		creditRemainder += Math.max(0, creditsPerSecond) * dtSeconds;
		long wholeCredits = (long) creditRemainder;
		creditRemainder -= wholeCredits;
		return wholeCredits;
	}

	@Override
	public Text getDisplayName() {
		String blockId = Registries.BLOCK.getId(getCachedState().getBlock()).getPath();
		return Text.translatable("block.rackcraft." + blockId);
	}

	@Override
	public ScreenHandler createMenu(int syncId, PlayerInventory playerInventory, PlayerEntity player) {
		String id = blockId();
		MachineScreenHandler.Mode mode = switch (id) {
			case "server_rack" -> MachineScreenHandler.Mode.RACK;
			case "diesel_generator", "modular_reactor", "fire_suppression_tank" -> MachineScreenHandler.Mode.SINGLE_SLOT;
			case "crypto_exchange" -> MachineScreenHandler.Mode.EXCHANGE;
			case "storage_array" -> MachineScreenHandler.Mode.STORAGE_ARRAY;
			case "tape_library" -> MachineScreenHandler.Mode.TAPE_LIBRARY;
			case "wireless_transmitter" -> MachineScreenHandler.Mode.TRANSMITTER;
			case "storage_terminal" -> null;
			case "creative_power", "creative_rack", "creative_cooler", "creative_router" -> MachineScreenHandler.Mode.CREATIVE;
			case "facility_controller" -> MachineScreenHandler.Mode.CONTROLLER;
			case "monitoring_wall" -> MachineScreenHandler.Mode.MONITOR_WALL;
			case "art_table", "writing_desk" -> MachineScreenHandler.Mode.WORKSTATION;
			case "operations_terminal" -> null;
			default -> MachineScreenHandler.Mode.MACHINE_STATUS;
		};
		if (id.equals("operations_terminal")) return new dev.rackcraft.compute.OpsScreenHandler(syncId, playerInventory, pos);
		if (mode == null) return new dev.rackcraft.storage.TerminalScreenHandler(syncId, playerInventory, terminalAccess());
		return new MachineScreenHandler(syncId, playerInventory, this, mode);
	}

	private dev.rackcraft.storage.StorageService.Access terminalAccess() {
		return new dev.rackcraft.storage.StorageService.Access(world.getRegistryKey(), pos, false);
	}

	@Override
	public void writeScreenOpeningData(ServerPlayerEntity player, PacketByteBuf buf) {
		if (blockId().equals("storage_terminal")) {
			terminalAccess().write(buf);
			return;
		}
		buf.writeBlockPos(pos);
		if (blockId().equals("crypto_exchange")) dev.rackcraft.ExchangeCatalog.write(buf);
		if (dev.rackcraft.generated.ContentIds.CREATIVE_IDS.contains(blockId())) {
			var settings = dev.rackcraft.CreativeSettings.forBlock(blockId());
			buf.writeBoolean(dev.rackcraft.CreativeSettings.canEdit(player));
			buf.writeVarInt(settings.size());
			for (var setting : settings) buf.writeDouble(creativeValue(setting.key()));
		}
		if (blockId().equals("facility_controller") && world instanceof ServerWorld serverWorld) {
			buf.writeString(FacilityManager.get(serverWorld).activeContract(), 64);
			buf.writeString(FacilityManager.get(serverWorld).activeEvent(), 64);
		}
	}

	@Override
	public void readNbt(NbtCompound nbt) {
		super.readNbt(nbt);
		Inventories.readNbt(nbt, inventory);
		loadLimitPercent = MathHelper.clamp(nbt.getInt("LoadLimit"), 25, 100);
		chargeKws = Math.max(0, nbt.getDouble("ChargeKws"));
		tripped = nbt.getBoolean("Tripped");
		feedLabel = nbt.getString("FeedLabel").equals("B") ? "B" : "A";
		creditRemainder = Math.max(0, nbt.getDouble("CreditRemainder"));
		dieselSpinupSteps = Math.max(0, Math.min(20, nbt.getInt("DieselSpinupSteps")));
		fuelBurnTicks = Math.max(0, nbt.getInt("FuelBurnTicks"));
		fuelBurnTotal = Math.max(fuelBurnTicks, nbt.getInt("FuelBurnTotal"));
		transmitterLevel = Math.max(0, nbt.getInt("TransmitterLevel"));
		inletCelsius = nbt.contains("InletCelsius") ? nbt.getDouble("InletCelsius") : 24;
		exhaustCelsius = nbt.contains("ExhaustCelsius") ? nbt.getDouble("ExhaustCelsius") : 24;
		powerKw = nbt.getDouble("PowerKw");
		powerSatisfaction = Math.max(0, Math.min(1, nbt.getDouble("PowerSatisfaction")));
		load = nbt.getDouble("Load");
		thermalFactor = nbt.contains("ThermalFactor") ? nbt.getDouble("ThermalFactor") : 1;
		waterDrawn = Math.max(0, nbt.getDouble("WaterDrawn"));
		workProgress = Math.max(0, nbt.getDouble("WorkProgress"));
		itemsMade = Math.max(0, nbt.getInt("ItemsMade"));
		toolUses = Math.max(0, nbt.getInt("ToolUses"));
		boundVillager = nbt.containsUuid("BoundVillager") ? nbt.getUuid("BoundVillager") : null;
		creativeValues.clear();
		NbtCompound creative = nbt.getCompound("Creative");
		for (String key : creative.getKeys()) creativeValues.put(key, creative.getDouble(key));
	}

	@Override
	protected void writeNbt(NbtCompound nbt) {
		super.writeNbt(nbt);
		Inventories.writeNbt(nbt, inventory);
		nbt.putInt("LoadLimit", loadLimitPercent);
		nbt.putDouble("ChargeKws", chargeKws);
		nbt.putBoolean("Tripped", tripped);
		nbt.putString("FeedLabel", feedLabel);
		nbt.putDouble("CreditRemainder", creditRemainder);
		nbt.putInt("DieselSpinupSteps", dieselSpinupSteps);
		nbt.putInt("FuelBurnTicks", fuelBurnTicks);
		nbt.putInt("FuelBurnTotal", fuelBurnTotal);
		if (transmitterLevel > 0) nbt.putInt("TransmitterLevel", transmitterLevel);
		nbt.putDouble("InletCelsius", inletCelsius);
		nbt.putDouble("ExhaustCelsius", exhaustCelsius);
		nbt.putDouble("PowerSatisfaction", powerSatisfaction);
		nbt.putDouble("PowerKw", powerKw);
		nbt.putDouble("Load", load);
		nbt.putDouble("ThermalFactor", thermalFactor);
		if (waterDrawn > 0) nbt.putDouble("WaterDrawn", waterDrawn);
		if (workProgress > 0) nbt.putDouble("WorkProgress", workProgress);
		if (itemsMade > 0) nbt.putInt("ItemsMade", itemsMade);
		if (toolUses > 0) nbt.putInt("ToolUses", toolUses);
		if (boundVillager != null) nbt.putUuid("BoundVillager", boundVillager);
		if (!creativeValues.isEmpty()) {
			NbtCompound creative = new NbtCompound();
			creativeValues.forEach(creative::putDouble);
			nbt.put("Creative", creative);
		}
	}
}