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

public final class MachineBlockEntity extends BlockEntity implements Inventory, ExtendedScreenHandlerFactory {
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
				"facility_controller", "cdu", "modular_reactor").contains(id)) kinds.add(NetKind.POWER);
		if (List.of("cooling_tower", "crac_unit", "cdu").contains(id)) kinds.add(NetKind.COOLANT);
		if (List.of("server_rack", "uplink_router", "core_router", "facility_controller",
				"monitoring_wall").contains(id)) kinds.add(NetKind.DATA);
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
			if (!List.of("pi_node", "server_1u", "gpu_blade", "quantum_core").contains(itemId)) return false;
			int moduleUnits = moduleUnits(itemId);
			int installedUnits = 0;
			for (int index = 0; index < inventory.size(); index++) {
				if (index == slot || inventory.get(index).isEmpty()) continue;
				installedUnits += moduleUnits(Registries.ITEM.getId(inventory.get(index).getItem()).getPath());
			}
			return installedUnits + moduleUnits <= 8;
		}
		if (blockId.equals("diesel_generator") || blockId.equals("modular_reactor")) return slot == 0;
		if (blockId.equals("fire_suppression_tank")) return slot == 0;
		return false;
	}

	private int moduleUnits(String itemId) {
		return switch (itemId) {
			case "gpu_blade" -> 2;
			case "quantum_core" -> 4;
			default -> 1;
		};
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
			String itemId = Registries.ITEM.getId(stack.getItem()).getPath();
			ServerModel.Module module = switch (itemId) {
				case "pi_node" -> ServerModel.Module.PI_NODE;
				case "server_1u" -> ServerModel.Module.SERVER_1U;
				case "gpu_blade" -> ServerModel.Module.GPU_BLADE;
				case "quantum_core" -> ServerModel.Module.QUANTUM_CORE;
				default -> null;
			};
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
			case "facility_controller" -> MachineScreenHandler.Mode.CONTROLLER;
			case "monitoring_wall" -> MachineScreenHandler.Mode.MONITOR_WALL;
			default -> MachineScreenHandler.Mode.MACHINE_STATUS;
		};
		return new MachineScreenHandler(syncId, playerInventory, this, mode);
	}

	@Override
	public void writeScreenOpeningData(ServerPlayerEntity player, PacketByteBuf buf) {
		buf.writeBlockPos(pos);
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
		inletCelsius = nbt.contains("InletCelsius") ? nbt.getDouble("InletCelsius") : 24;
		exhaustCelsius = nbt.contains("ExhaustCelsius") ? nbt.getDouble("ExhaustCelsius") : 24;
		powerKw = nbt.getDouble("PowerKw");
		powerSatisfaction = Math.max(0, Math.min(1, nbt.getDouble("PowerSatisfaction")));
		load = nbt.getDouble("Load");
		thermalFactor = nbt.contains("ThermalFactor") ? nbt.getDouble("ThermalFactor") : 1;
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
		nbt.putDouble("InletCelsius", inletCelsius);
		nbt.putDouble("ExhaustCelsius", exhaustCelsius);
		nbt.putDouble("PowerSatisfaction", powerSatisfaction);
		nbt.putDouble("PowerKw", powerKw);
		nbt.putDouble("Load", load);
		nbt.putDouble("ThermalFactor", thermalFactor);
	}
}