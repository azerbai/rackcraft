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
	/** Nine slots, or one per bay for racks with more than that. */
	private final DefaultedList<ItemStack> inventory;
	private final int[] allSlots;
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
	// Smog scrubbers: smog removed per second in the last step; not saved.
	private double scrubRate;
	// Cooling, refreshed every step and not saved. coolingKw: heat this machine moved (a fan or CRAC out of the
	// air, a rear-door cooler off its rack, a sink out of its loop). The loop figures are for its whole coolant
	// network. Racks split their heat between the loop and the air. coolingDetail depends on the machine: water
	// units for a cooling tower, the climate in percent for a dry cooler, water blocks for a heat exchanger.
	private double coolingKw;
	private double loopHeatKw;
	private double loopCapacityKw;
	private double heatToLoopKw;
	private double heatToAirKw;
	private int coolingDetail;
	// Modular reactors: the array this one belongs to (1 on its own), refreshed every step and not saved.
	private int reactorArraySize = 1;
	private double reactorCapacityKw;
	private int arrayFuelTicks;
	private int arrayFuelTotal;
	private int arrayFuelCells;
	// Array machines: what the cube is doing (a ReactorArrays.ReactorStatus or NuclearProcessing.Status ordinal) and
	// whether it was working last step (for its power draw); not saved. A spent cell waiting for waste room is.
	private int processStatus;
	private boolean processActive;
	// Item-making cubes: whether this core is the cube's port (where products gather), what the whole cube holds
	// in its product and by-product slots, and what it draws when working; not saved.
	private boolean cubePort;
	private int cubeOutput;
	private int cubeByproduct;
	private double cubeDemandKw;
	// Utility plants: RackCoin per second earned (exported power, sold heat); not saved.
	private double income;
	// Solar Arrays and Wind Towers: how worn they are, 0 to 1 (a drone services them back to 0). Saved on the array's
	// part 0 and on the nacelle; other parts carry a copy for their screens.
	private double wear;
	// Hydrogen Tanks: canisters' worth of hydrogen in this block (a cube shares it evenly). Saved.
	private long hydrogen;
	// Cube machines: the size of the whole cube this block is part of, when that size isn't researched yet; not saved.
	private int lockedCube;
	// Drone Docks: jobs within range right now; not saved.
	private int dockJobs;
	// Site Planners: the site (corners, layout, running, level), saved; and readings for the screen, not saved.
	private NbtCompound site = new NbtCompound();
	private final int[] siteReadings = new int[dev.rackcraft.world.SitePlanner.READINGS];
	// Launch Controls: hydrogen in the tank, the countdown and flight in progress, and what is on the rocket. Saved, so a
	// launch survives a reload.
	private int launchTank;
	private int launchCountdown;
	private int launchFlight;
	private String launchPayload = "";
	private int launchStages;
	private boolean launchFailed;
	private int pendingWaste;
	// Routers: their fiber network's bandwidth, what its racks need, and how many racks; refreshed every step.
	private double dataBandwidth;
	private double dataDemand;
	private int dataRacks;
	// Racks: how far through booting, 0 to 1. Saved, so a reload doesn't cold-start the hall.
	private double bootProgress;
	/** Compute Pod fabric bonus on this rack's AI compute (1 when it is in no active pod). Not saved: rebuilt every step. */
	private double podBonus = 1;

	public MachineBlockEntity(BlockPos pos, BlockState state) {
		super(RcBlocks.MACHINE_ENTITY, pos, state);
		ServerModel.Tier tier = ServerModel.Tier.of(Registries.BLOCK.getId(state.getBlock()).getPath());
		int size = tier == null ? 9 : Math.max(9, tier.bays());
		inventory = DefaultedList.ofSize(size, ItemStack.EMPTY);
		allSlots = java.util.stream.IntStream.range(0, size).toArray();
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

	public static final java.util.Set<String> COOLANT_MACHINES = java.util.Set.of("cooling_tower", "crac_unit", "cdu",
			"freshwater_pump", "server_rack", "high_density_rack", "immersion_rack", "exascale_cabinet", "modular_reactor", "rear_door_cooler", "dry_cooler", "chiller", "water_heat_exchanger",
			"desalination_plant", "heat_recovery_plant");
	/** Machines an Item Pipe feeds from storage (and empties into it): storage itself, the training stations and generators. */
	public static final java.util.Set<String> ITEM_MACHINES = java.util.Set.of("storage_array", "tape_library", "art_table",
			"writing_desk", "diesel_generator", "modular_reactor", "uranium_mill", "gas_centrifuge", "fuel_fabricator", "cask_sealer", "wafer_fab", "silicon_foundry", "ewaste_recycler",
			"electrolyser", "assembly_arm", "drone_dock", "launch_control", "belt_loader", "belt_unloader", "site_planner", "storage_exporter", "storage_link",
			"hydrogen_tank", "auto_buyer", "cryostat", "cvd_furnace", "epitaxy_reactor");

	public static java.util.Set<NetKind> networkKinds(String id) {
		java.util.EnumSet<NetKind> kinds = java.util.EnumSet.noneOf(NetKind.class);
		if (List.of("diesel_generator", "solar_panel", "wind_turbine", "pdu", "server_rack",
				"exhaust_fan", "cooling_tower", "crac_unit", "battery_bank", "utility_intake",
				"facility_controller", "cdu", "modular_reactor", "freshwater_pump", "smog_scrubber",
				"rear_door_cooler", "dry_cooler", "chiller", "water_heat_exchanger", "desalination_plant", "grid_substation",
				"welding_arm", "riveting_arm", "assembly_arm", "drone_dock", "launch_control", "rectenna",
				"solar_array", "solar_array_tracking", "wind_nacelle", "tower_section", "site_planner", "cryostat", "procurement_wall",
				"pylon", "superconducting_pylon", "power_beacon", "beacon_receiver", "pod_port", "teleport_pad", "maglev_station")
				.contains(id) || ServerModel.Tier.isRack(id)) kinds.add(NetKind.POWER);
		// The coolant loop: racks (liquid-cooled modules) and reactors put heat in; towers, coolers and chillers take it out.
		if (COOLANT_MACHINES.contains(id)) kinds.add(NetKind.COOLANT);
		if (ITEM_MACHINES.contains(id)) kinds.add(NetKind.ITEM);
		if (List.of("uplink_router", "core_router", "facility_controller",
				"monitoring_wall", "creative_router").contains(id) || ServerModel.Tier.isRack(id)) kinds.add(NetKind.DATA);
		if (List.of("creative_power", "creative_rack").contains(id)) kinds.add(NetKind.POWER);
		if (List.of("storage_array", "tape_library", "wireless_transmitter", "storage_link", "auto_buyer").contains(id)) kinds.add(NetKind.POWER);
		if (dev.rackcraft.world.NuclearProcessing.recipe(id) != null) kinds.add(NetKind.POWER);
		if (List.of("storage_array", "tape_library", "storage_terminal", "wireless_transmitter", "storage_link", "hydrogen_tank", "auto_buyer")
				.contains(id)) kinds.add(NetKind.DATA);
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
	public int[] getAvailableSlots(net.minecraft.util.math.Direction side) { return allSlots; }

	@Override
	public boolean canInsert(int slot, ItemStack stack, net.minecraft.util.math.Direction side) { return isValid(slot, stack); }

	/** Hoppers take only finished work out of art tables and desks, never their paper or tools. */
	@Override
	public boolean canExtract(int slot, ItemStack stack, net.minecraft.util.math.Direction side) {
		String id = blockId();
		if (id.equals("modular_reactor")) return slot == dev.rackcraft.world.ReactorArrays.WASTE_SLOT;
		if (dev.rackcraft.world.NuclearProcessing.recipe(id) != null) return slot == 2 || slot == 3;
		// Hoppers under a Drone Dock take away the dead modules its drones bring home, nothing else.
		if (id.equals("drone_dock")) return stack.isOf(dev.rackcraft.RcItems.ITEMS.get("failed_module"));
		if (id.equals("assembly_arm") || id.equals("belt_loader") || id.equals("storage_exporter") || id.equals("hydrogen_tank")) return false;
		// Hoppers can empty a Site Planner's material slots, never its drones or hydrogen.
		if (id.equals("site_planner")) return slot >= dev.rackcraft.world.SitePlanner.FIRST_MATERIAL;
		if (id.equals("belt_unloader")) return true;
		// A Survey Satellite's map comes out of the payload slot; nothing else does.
		if (id.equals("launch_control")) return slot == dev.rackcraft.world.LaunchPads.PAYLOAD_SLOT && stack.isOf(net.minecraft.item.Items.FILLED_MAP);
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
		ServerModel.Tier tier = ServerModel.Tier.of(blockId);
		if (tier != null) {
			// One module a bay: GPU Blades and Quantum Cores fill a bay like anything else. An Exascale Cabinet turns
			// away starter hardware.
			return slot < tier.bays() && tier.accepts(Racks.module(stack));
		}
		if (blockId.equals("teleport_pad")) return slot == 0 && stack.isOf(dev.rackcraft.RcItems.ITEMS.get("quantum_annealer"));
		if (blockId.equals("pod_port")) return slot == 0 && stack.isOf(dev.rackcraft.RcItems.ITEMS.get("photonic_chip"));
		if (blockId.equals("cryostat")) return slot == 0 && stack.isOf(dev.rackcraft.RcItems.ITEMS.get("hydrogen_canister"));
		if (blockId.equals("diesel_generator")) return slot == 0;
		if (blockId.equals("modular_reactor")) return slot == 0 && stack.isOf(dev.rackcraft.RcItems.ITEMS.get("fuel_cell"));
		dev.rackcraft.world.NuclearProcessing.Recipe recipe = dev.rackcraft.world.NuclearProcessing.recipe(blockId);
		if (recipe != null) return slot == 0 ? stack.isOf(recipe.inputA()) : slot == 1 && recipe.inputB() != null && stack.isOf(recipe.inputB());
		if (blockId.equals("storage_array")) {
			return slot < 8 && stack.getItem() instanceof dev.rackcraft.storage.DriveItem drive && !drive.cold();
		}
		if (blockId.equals("tape_library")) {
			return slot < 4 && stack.getItem() instanceof dev.rackcraft.storage.DriveItem drive && drive.cold();
		}
		if (blockId.equals("fire_suppression_tank")) return slot == 0;
		if (blockId.equals("assembly_arm") || blockId.equals("belt_unloader") || blockId.equals("storage_exporter")) return true;
		// A Belt Loader's one slot holds a sample of what to load; nothing else goes in it.
		if (blockId.equals("belt_loader")) return slot == 0;
		if (blockId.equals("drone_dock")) return dev.rackcraft.world.DroneDocks.accepts(slot, stack);
		if (blockId.equals("launch_control")) return dev.rackcraft.world.LaunchPads.accepts(slot, stack);
		if (blockId.equals("site_planner")) return dev.rackcraft.world.SitePlanner.accepts(slot, stack);
		if (blockId.equals("hydrogen_tank")) return slot == dev.rackcraft.world.HydrogenTanks.TANKER_SLOT && stack.isOf(dev.rackcraft.RcItems.ITEMS.get("tanker_drone"));
		if (blockId.equals("art_table") || blockId.equals("writing_desk")) {
			if (slot == 0) return stack.isOf(net.minecraft.item.Items.PAPER);
			if (slot == 1) return blockId.equals("art_table") ? stack.isOf(dev.rackcraft.RcItems.ITEMS.get("crayons"))
					: stack.isOf(net.minecraft.item.Items.INK_SAC);
		}
		return false;
	}

	public int loadLimitPercent() { return loadLimitPercent; }
	public void setLoadLimitPercent(int value) {
		if (value == 25 || value == 50 || value == 75 || value == 100 || value == 125 || value == 150) {
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
	/** The block's id. A block entity belongs to one block for its whole life, so it is looked up once. */
	public String blockId() {
		if (blockId == null) blockId = Registries.BLOCK.getId(getCachedState().getBlock()).getPath();
		return blockId;
	}
	private String blockId;

	/** The modules in this rack's bays, rebuilt only when its slots change (see {@link #markDirty}). */
	public List<ServerModel.Module> modules() {
		if (modules != null) return modules;
		java.util.ArrayList<ServerModel.Module> found = new java.util.ArrayList<>();
		for (ItemStack stack : inventory) {
			ServerModel.Module module = Racks.module(stack);
			if (module != null) found.add(module);
		}
		modules = List.copyOf(found);
		return modules;
	}
	private List<ServerModel.Module> modules;

	@Override
	public void markDirty() {
		modules = null;
		super.markDirty();
	}
	public double inletCelsius() { return inletCelsius; }
	public double exhaustCelsius() { return exhaustCelsius; }
	public double powerSatisfaction() { return powerSatisfaction; }
	public double powerKw() { return powerKw; }
	public double load() { return load; }
	public double podBonus() { return podBonus; }
	public void setPodBonus(double value) { podBonus = value; }
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
	public double scrubRate() { return scrubRate; }
	public void setScrubRate(double value) { scrubRate = Math.max(0, value); }
	public double coolingKw() { return coolingKw; }
	public double loopHeatKw() { return loopHeatKw; }
	public double loopCapacityKw() { return loopCapacityKw; }
	public double heatToLoopKw() { return heatToLoopKw; }
	public double heatToAirKw() { return heatToAirKw; }
	public int coolingDetail() { return coolingDetail; }
	public void setCooling(double movedKw, int detail) {
		coolingKw = Math.max(0, movedKw);
		coolingDetail = detail;
	}
	public void setLoop(double heatKw, double capacityKw) {
		loopHeatKw = Math.max(0, heatKw);
		loopCapacityKw = Math.max(0, capacityKw);
	}
	public void setHeatSplit(double toLoopKw, double toAirKw) {
		heatToLoopKw = Math.max(0, toLoopKw);
		heatToAirKw = Math.max(0, toAirKw);
	}
	public int reactorArraySize() { return reactorArraySize; }
	public double reactorCapacityKw() { return reactorCapacityKw; }
	public int arrayFuelTicks() { return arrayFuelTicks; }
	public int arrayFuelTotal() { return arrayFuelTotal; }
	public int arrayFuelCells() { return arrayFuelCells; }
	public void setReactorArray(int size, double capacityKw, int fuelTicks, int fuelTotal, int fuelCells) {
		reactorArraySize = size;
		reactorCapacityKw = capacityKw;
		arrayFuelTicks = fuelTicks;
		arrayFuelTotal = fuelTotal;
		arrayFuelCells = fuelCells;
	}
	public double income() { return income; }
	public long hydrogen() { return hydrogen; }
	public void setHydrogen(long value) { hydrogen = Math.max(0, value); markDirty(); }
	public double wear() { return wear; }
	public void setWear(double value) {
		double clamped = Math.max(0, Math.min(1, value));
		if (clamped != wear) markDirty();
		wear = clamped;
	}
	public void setWearShown(double value) { wear = value; }
	public int lockedCube() { return lockedCube; }
	public void setLockedCube(int edge) { lockedCube = edge; }
	public int dockJobs() { return dockJobs; }
	public int launchTank() { return launchTank; }
	public void setLaunchTank(int canisters) { launchTank = Math.max(0, canisters); markDirty(); }
	public int launchCountdown() { return launchCountdown; }
	public void setLaunchCountdown(int ticks) { launchCountdown = Math.max(0, ticks); markDirty(); }
	public int launchFlight() { return launchFlight; }
	public void setLaunchFlight(int ticks) { launchFlight = Math.max(0, ticks); markDirty(); }
	public String launchPayload() { return launchPayload; }
	public int launchStages() { return launchStages; }
	public boolean launchFailed() { return launchFailed; }
	public void startLaunch(String payload, int stages, boolean fails, int countdown) {
		launchPayload = payload;
		launchStages = stages;
		launchFailed = fails;
		launchCountdown = countdown;
		launchFlight = 0;
		markDirty();
	}
	public void finishLaunch() {
		launchPayload = "";
		launchStages = 0;
		launchFailed = false;
		launchFlight = 0;
		markDirty();
	}
	public void setDockJobs(int value) { dockJobs = value; }
	/** A Site Planner's saved site; change it through {@link dev.rackcraft.world.SitePlanner}, then markDirty. */
	public NbtCompound site() { return site; }
	public int siteReading(int index) { return index >= 0 && index < siteReadings.length ? siteReadings[index] : 0; }
	public void setSiteReading(int index, int value) { if (index >= 0 && index < siteReadings.length) siteReadings[index] = value; }
	public void setIncome(double rcPerSecond) { income = rcPerSecond; }
	public boolean cubePort() { return cubePort; }
	public int cubeOutput() { return cubeOutput; }
	public int cubeByproduct() { return cubeByproduct; }
	public double cubeDemandKw() { return cubeDemandKw; }
	public void setCube(boolean port, int output, int byproduct, double demandKw) {
		cubePort = port;
		cubeOutput = output;
		cubeByproduct = byproduct;
		cubeDemandKw = demandKw;
	}
	public int processStatus() { return processStatus; }
	public boolean processActive() { return processActive; }
	public void setProcess(int status, boolean active) {
		processStatus = status;
		processActive = active;
	}
	public double dataBandwidth() { return dataBandwidth; }
	public double dataDemand() { return dataDemand; }
	public int dataRacks() { return dataRacks; }
	public void setDataNetwork(double bandwidth, double demand, int racks) {
		dataBandwidth = bandwidth;
		dataDemand = demand;
		dataRacks = racks;
	}
	public double bootProgress() { return bootProgress; }
	public void setBootProgress(double value) {
		double clamped = Math.max(0, Math.min(1, value));
		if (clamped != bootProgress) markDirty();
		bootProgress = clamped;
	}
	public int pendingWaste() { return pendingWaste; }
	public void setPendingWaste(int value) { pendingWaste = Math.max(0, value); markDirty(); }
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
			case "server_rack", "high_density_rack", "immersion_rack", "exascale_cabinet" -> MachineScreenHandler.Mode.RACK;
			case "diesel_generator", "fire_suppression_tank" -> MachineScreenHandler.Mode.SINGLE_SLOT;
			case "modular_reactor" -> MachineScreenHandler.Mode.REACTOR;
			case "uranium_mill", "gas_centrifuge", "fuel_fabricator", "cask_sealer", "wafer_fab", "silicon_foundry", "ewaste_recycler", "electrolyser",
					"cvd_furnace", "epitaxy_reactor" -> MachineScreenHandler.Mode.PROCESSOR;
			case "welding_arm", "riveting_arm", "assembly_arm", "drone_dock", "belt_loader", "belt_unloader", "storage_exporter", "hydrogen_tank",
					"cryostat", "pod_port", "teleport_pad" -> MachineScreenHandler.Mode.WORKCELL;
			case "launch_control" -> MachineScreenHandler.Mode.LAUNCH;
			case "site_planner" -> MachineScreenHandler.Mode.SITE;
			case "crypto_exchange" -> MachineScreenHandler.Mode.EXCHANGE;
			case "storage_array" -> MachineScreenHandler.Mode.STORAGE_ARRAY;
			case "tape_library" -> MachineScreenHandler.Mode.TAPE_LIBRARY;
			case "wireless_transmitter" -> MachineScreenHandler.Mode.TRANSMITTER;
			case "storage_terminal" -> null;
			case "creative_power", "creative_rack", "creative_cooler", "creative_router" -> MachineScreenHandler.Mode.CREATIVE;
			case "facility_controller" -> MachineScreenHandler.Mode.CONTROLLER;
			case "monitoring_wall" -> MachineScreenHandler.Mode.MONITOR_WALL;
			case "art_table", "writing_desk" -> MachineScreenHandler.Mode.WORKSTATION;
			case "operations_terminal", "darknet_terminal", "procurement_wall" -> null;
			default -> MachineScreenHandler.Mode.MACHINE_STATUS;
		};
		if (id.equals("operations_terminal")) return new dev.rackcraft.compute.OpsScreenHandler(syncId, playerInventory, pos);
		if (id.equals("darknet_terminal")) return new dev.rackcraft.darknet.DarknetScreenHandler(syncId, playerInventory, pos);
		if (id.equals("procurement_wall")) return new dev.rackcraft.screen.ProcurementScreenHandler(syncId, playerInventory, pos);
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
		if (blockId().equals("crypto_exchange")) dev.rackcraft.ExchangeCatalog.write(buf, (ServerWorld) world);
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
		modules = null;
		loadLimitPercent = MathHelper.clamp(nbt.getInt("LoadLimit"), 25, 150);
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
		pendingWaste = Math.max(0, nbt.getInt("PendingWaste"));
		bootProgress = Math.max(0, Math.min(1, nbt.getDouble("Boot")));
		wear = Math.max(0, Math.min(1, nbt.getDouble("Wear")));
		site = nbt.getCompound("Site").copy();
		hydrogen = Math.max(0, nbt.getLong("Hydrogen"));
		launchTank = Math.max(0, nbt.getInt("LaunchTank"));
		launchCountdown = Math.max(0, nbt.getInt("LaunchCountdown"));
		launchFlight = Math.max(0, nbt.getInt("LaunchFlight"));
		launchPayload = nbt.getString("LaunchPayload");
		launchStages = nbt.getInt("LaunchStages");
		launchFailed = nbt.getBoolean("LaunchFailed");
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
		if (pendingWaste > 0) nbt.putInt("PendingWaste", pendingWaste);
		if (bootProgress > 0) nbt.putDouble("Boot", bootProgress);
		if (wear > 0) nbt.putDouble("Wear", wear);
		if (!site.isEmpty()) nbt.put("Site", site.copy());
		if (hydrogen > 0) nbt.putLong("Hydrogen", hydrogen);
		if (launchTank > 0) nbt.putInt("LaunchTank", launchTank);
		if (!launchPayload.isEmpty()) {
			nbt.putInt("LaunchCountdown", launchCountdown);
			nbt.putInt("LaunchFlight", launchFlight);
			nbt.putString("LaunchPayload", launchPayload);
			nbt.putInt("LaunchStages", launchStages);
			nbt.putBoolean("LaunchFailed", launchFailed);
		}
		if (!creativeValues.isEmpty()) {
			NbtCompound creative = new NbtCompound();
			creativeValues.forEach(creative::putDouble);
			nbt.put("Creative", creative);
		}
	}
}