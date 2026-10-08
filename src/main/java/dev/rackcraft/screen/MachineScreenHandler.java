package dev.rackcraft.screen;

import dev.rackcraft.block.MachineBlockEntity;
import dev.rackcraft.world.FacilityManager;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.inventory.Inventory;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.screen.ArrayPropertyDelegate;
import net.minecraft.screen.PropertyDelegate;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.screen.slot.Slot;
import net.minecraft.util.math.BlockPos;

public final class MachineScreenHandler extends ScreenHandler {
	private final Mode mode;
	private final MachineBlockEntity machine;
	private final Inventory machineInventory;
	private final BlockPos pos;
	private final PropertyDelegate properties;
	private final String activeContract;
	private final String activeEvent;

	public MachineScreenHandler(int syncId, PlayerInventory playerInventory, PacketByteBuf buf, Mode mode) {
		this(syncId, playerInventory, null, buf.readBlockPos(), mode,
				mode == Mode.CONTROLLER ? buf.readString(64) : "none",
				mode == Mode.CONTROLLER ? buf.readString(64) : "none");
		if (mode == Mode.EXCHANGE) catalog = dev.rackcraft.ExchangeCatalog.read(buf);
		if (mode == Mode.CREATIVE) {
			creativeEditable = buf.readBoolean();
			creativeValues = new double[buf.readVarInt()];
			for (int index = 0; index < creativeValues.length; index++) creativeValues[index] = buf.readDouble();
		}
	}

	/** Client only: whether this player may edit, and the values when the screen opened. */
	private boolean creativeEditable;
	private double[] creativeValues = new double[0];

	public boolean creativeEditable() { return creativeEditable; }
	public double[] creativeValues() { return creativeValues; }

	/** Client only: every item the Exchange sells, with its price. */
	private java.util.List<dev.rackcraft.ExchangeCatalog.Entry> catalog = java.util.List.of();

	public java.util.List<dev.rackcraft.ExchangeCatalog.Entry> catalog() { return catalog; }

	public MachineScreenHandler(int syncId, PlayerInventory playerInventory, MachineBlockEntity machine, Mode mode) {
		this(syncId, playerInventory, machine, machine.getPos(), mode,
				machine.getWorld() instanceof ServerWorld world && mode == Mode.CONTROLLER
						? FacilityManager.get(world).activeContract() : "none",
				machine.getWorld() instanceof ServerWorld world && mode == Mode.CONTROLLER
						? FacilityManager.get(world).activeEvent() : "none");
	}

	private MachineScreenHandler(int syncId, PlayerInventory playerInventory, MachineBlockEntity machine,
			BlockPos pos, Mode mode, String activeContract, String activeEvent) {
		super(RcScreenHandlers.type(mode), syncId);
		this.mode = mode;
		this.machine = machine;
		this.machineInventory = machine == null ? new SimpleInventory(9) : machine;
		this.pos = pos;
		this.activeContract = activeContract;
		this.activeEvent = activeEvent;
		// Vanilla syncs screen properties as 16-bit shorts, so each 32-bit stat uses two properties.
		this.properties = machine == null ? new ArrayPropertyDelegate(Stat.COUNT * 2) : new PropertyDelegate() {
			@Override public int size() { return Stat.COUNT * 2; }
			@Override public int get(int index) {
				int value = statValue(machine, mode, index / 2);
				return index % 2 == 0 ? value & 0xFFFF : value >>> 16;
			}
			@Override public void set(int index, int value) {}
		};
		if (mode == Mode.RACK) {
			// Two columns of four bays, below the header.
			for (int index = 0; index < 8; index++) addSlot(new MachineSlot(machineInventory, index, 13 + (index % 2) * 20, 46 + (index / 2) * 20));
			addPlayerInventory(playerInventory, 13, 190);
		} else if (mode == Mode.STORAGE_ARRAY) {
			for (int index = 0; index < 8; index++) addSlot(new MachineSlot(machineInventory, index, 8 + (index % 4) * 22, 30 + (index / 4) * 22));
			addPlayerInventory(playerInventory, 8, 102);
		} else if (mode == Mode.TAPE_LIBRARY) {
			for (int index = 0; index < 4; index++) addSlot(new MachineSlot(machineInventory, index, 8 + index * 22, 30));
			addPlayerInventory(playerInventory, 8, 102);
		} else if (mode == Mode.WORKSTATION) {
			addSlot(new MachineSlot(machineInventory, 0, 26, 40));
			addSlot(new MachineSlot(machineInventory, 1, 50, 40));
			addSlot(new MachineSlot(machineInventory, 2, 134, 40));
			addPlayerInventory(playerInventory, 8, 102);
		} else if (mode == Mode.SINGLE_SLOT) {
			addSlot(new MachineSlot(machineInventory, 0, 80, 46));
			addPlayerInventory(playerInventory, 8, 132);
		} else if (mode == Mode.REACTOR) {
			addSlot(new MachineSlot(machineInventory, 0, 17, 56));
			addSlot(new MachineSlot(machineInventory, 1, 45, 56));
			addPlayerInventory(playerInventory, 8, 160);
		} else if (mode == Mode.PROCESSOR) {
			addSlot(new MachineSlot(machineInventory, 0, 17, 62));
			addSlot(new MachineSlot(machineInventory, 1, 39, 62));
			addSlot(new MachineSlot(machineInventory, 2, 117, 62));
			addSlot(new MachineSlot(machineInventory, 3, 139, 62));
			addPlayerInventory(playerInventory, 8, 160);
		} else if (mode == Mode.WORKCELL) {
			// Assembly Robots and Drone Docks keep their parts in a row of nine; welding and riveting robots have none.
			String id = net.minecraft.registry.Registries.BLOCK.getId(playerInventory.player.getWorld().getBlockState(pos).getBlock()).getPath();
			if (id.equals("assembly_arm") || id.equals("drone_dock") || id.equals("belt_unloader")
					|| id.equals("storage_exporter")) {
				for (int index = 0; index < 9; index++) addSlot(new MachineSlot(machineInventory, index, 8 + index * 18, 116));
			} else if (id.equals("belt_loader")) {
				addSlot(new MachineSlot(machineInventory, 0, 80, 116));
			}
			addPlayerInventory(playerInventory, 8, 152);
		} else if (mode == Mode.SITE) {
			// Drones, terraformers and hydrogen, then six material slots, in one row under the readouts.
			for (int index = 0; index < 9; index++) addSlot(new MachineSlot(machineInventory, index, 8 + index * 18, 146));
			addPlayerInventory(playerInventory, 8, 182);
		} else if (mode == Mode.LAUNCH) {
			// Three stage slots, the payload, and hydrogen in.
			// A wider panel than most (230), so the readouts fit; the player's inventory sits in the middle of it.
			for (int index = 0; index < 3; index++) addSlot(new MachineSlot(machineInventory, index, 12 + index * 18, 42));
			addSlot(new MachineSlot(machineInventory, 3, 107, 42));
			addSlot(new MachineSlot(machineInventory, 4, 202, 42));
			addPlayerInventory(playerInventory, 34, 158);
		}
		addProperties(properties);
	}

	private void addPlayerInventory(PlayerInventory inventory, int x, int y) {
		for (int row = 0; row < 3; row++) {
			for (int column = 0; column < 9; column++) {
				addSlot(new Slot(inventory, 9 + row * 9 + column, x + column * 18, y + row * 18));
			}
		}
		for (int column = 0; column < 9; column++) addSlot(new Slot(inventory, column, x + column * 18, y + 58));
	}

	/** Shift-click moves items between the machine's slots and the player's inventory. */
	@Override
	public ItemStack quickMove(PlayerEntity player, int index) {
		int machineSlots = (int) slots.stream().filter(slot -> slot instanceof MachineSlot).count();
		Slot slot = slots.get(index);
		if (machineSlots == 0 || !slot.hasStack()) return ItemStack.EMPTY;
		ItemStack stack = slot.getStack();
		ItemStack original = stack.copy();
		boolean moved = index < machineSlots
				? insertItem(stack, machineSlots, slots.size(), true)
				: insertItem(stack, 0, machineSlots, false);
		if (!moved) return ItemStack.EMPTY;
		if (stack.isEmpty()) slot.setStack(ItemStack.EMPTY);
		else slot.markDirty();
		return original;
	}

	/** A machine slot that only accepts what the machine accepts (drives, rack modules, fuel). */
	private static final class MachineSlot extends Slot {
		MachineSlot(Inventory inventory, int index, int x, int y) {
			super(inventory, index, x, y);
		}

		@Override
		public boolean canInsert(ItemStack stack) {
			return inventory.isValid(getIndex(), stack);
		}
	}
	@Override
	public boolean canUse(PlayerEntity player) { return machine == null || machine.canPlayerUse(player); }
	public Mode mode() { return mode; }
	public BlockPos pos() { return pos; }
	/** Button 0 on a cube's port: hand the player every product the whole cube holds. */
	public static final int COLLECT_BUTTON = 0;
	/** Button 1 on a Launch Control: launch. */
	public static final int LAUNCH_BUTTON = 1;
	/** Buttons 2 and 3 on a Site Planner: the next layout, and start or pause. */
	public static final int LAYOUT_BUTTON = 2;
	public static final int START_BUTTON = 3;
	/** Button 4 on a Site Planner: buy what's missing from the Crypto Exchange, or stop. */
	public static final int BUY_BUTTON = 4;

	@Override
	public boolean onButtonClick(PlayerEntity player, int id) {
		if (id == LAUNCH_BUTTON && mode == Mode.LAUNCH && machine != null && machine.getWorld() instanceof ServerWorld launchWorld) {
			player.sendMessage(net.minecraft.text.Text.literal(dev.rackcraft.world.LaunchPads.launch(launchWorld, machine)), true);
			return true;
		}
		if ((id == LAYOUT_BUTTON || id == START_BUTTON || id == BUY_BUTTON) && mode == Mode.SITE && machine != null
				&& machine.getWorld() instanceof ServerWorld siteWorld) {
			player.sendMessage(net.minecraft.text.Text.literal(id == LAYOUT_BUTTON ? dev.rackcraft.world.SitePlanner.cycleLayout(machine)
					: id == BUY_BUTTON ? dev.rackcraft.world.SitePlanner.toggleBuying(machine)
					: dev.rackcraft.world.SitePlanner.toggleRunning(machine)), true);
			dev.rackcraft.world.SitePlanner.scanNow(siteWorld);
			return true;
		}
		if (id != COLLECT_BUTTON || machine == null || !(machine.getWorld() instanceof ServerWorld world)
				|| !(mode == Mode.REACTOR || mode == Mode.PROCESSOR)) return false;
		var array = dev.rackcraft.world.ReactorArrays.arrayOf(world, machine);
		if (array == null || array.edge() < 2 || array.controller() != machine) return false;
		int collected = dev.rackcraft.world.ReactorArrays.collect(array, player);
		player.sendMessage(net.minecraft.text.Text.literal(collected > 0 ? "Collected " + collected + " items from the whole cube"
				: "Nothing to collect yet"), true);
		return true;
	}

	/** The RackCoin balance, which outgrows one int stat in the late game. */
	public long balance() {
		return ((long) stat(Stat.BALANCE_HIGH) << 31) | (stat(Stat.BALANCE) & 0x7FFFFFFFL);
	}

	public int stat(int stat) {
		return (properties.get(stat * 2) & 0xFFFF) | (properties.get(stat * 2 + 1) << 16);
	}

	private static int statValue(MachineBlockEntity machine, Mode mode, int stat) {
		FacilityManager facility = machine.getWorld() instanceof ServerWorld world ? FacilityManager.get(world) : null;
		boolean facilityScreen = mode == Mode.CONTROLLER || mode == Mode.EXCHANGE || mode == Mode.MONITOR_WALL;
		return switch (stat) {
			case Stat.INLET -> tenths(machine.inletCelsius());
			case Stat.EXHAUST -> tenths(machine.exhaustCelsius());
			case Stat.POWER -> tenths(machine.powerKw());
			case Stat.LOAD -> (int) Math.round(machine.load() * 100);
			case Stat.THERMAL -> (int) Math.round(machine.thermalFactor() * 100);
			case Stat.SATISFACTION -> (int) Math.round(machine.powerSatisfaction() * 100);
			case Stat.BALANCE -> facility != null && facilityScreen ? (int) (facility.credits() & 0x7FFFFFFFL) : 0;
			case Stat.INCOME -> tenths(machine.income());
			case Stat.BALANCE_HIGH -> facility != null && facilityScreen ? (int) (facility.credits() >>> 31) : 0;
			case Stat.AVAILABILITY -> facility != null && facilityScreen ? (int) Math.round(facility.availability().stream()
					.mapToDouble(Double::doubleValue).average().orElse(1) * 100) : 0;
			case Stat.FUEL -> machine.blockId().equals("modular_reactor") ? machine.arrayFuelTicks() : machine.fuelBurnTicks();
			case Stat.RACK_STATUS -> machine.rackStatus().ordinal();
			case Stat.MINING_RATE -> (int) Math.round((facility != null && facilityScreen
					? facility.miningRate() : machine.miningRate()) * 100);
			case Stat.SPINUP -> machine.dieselSpinupSteps();
			case Stat.FUEL_TOTAL -> machine.blockId().equals("modular_reactor") ? machine.arrayFuelTotal() : machine.fuelBurnTotal();
			case Stat.NETWORK_DELIVERED -> tenths(machine.networkSupplyKw());
			case Stat.NETWORK_DEMAND -> tenths(machine.networkDemandKw());
			case Stat.BATTERY_PERMILLE -> (int) Math.round(machine.chargeKws()
					/ dev.rackcraft.world.SimTicker.batteryCapacityPerBank(machine.reactorArraySize()) * 1000);
			case Stat.MINING_RACKS -> facility != null ? facility.miningRacks() : 0;
			case Stat.TOTAL_RACKS -> facility != null ? facility.totalRacks() : 0;
			case Stat.NETWORK_CAPACITY -> tenths(machine.networkCapacityKw());
			case Stat.TRANSMITTER_LEVEL -> machine.transmitterLevel();
			case Stat.WORKERS -> machine.workers();
			case Stat.WORK_PROGRESS -> (int) Math.round(machine.workProgress() * 100);
			case Stat.ITEMS_MADE -> machine.itemsMade();
			case Stat.TOOL_USES -> machine.toolUses();
			case Stat.BOUND -> machine.boundVillager() != null ? 1 : 0;
			case Stat.PUMP_SOURCES -> machine.pumpSources();
			case Stat.PUMP_UNITS -> machine.pumpUnits();
			case Stat.PUMP_STATUS -> machine.pumpStatus();
			case Stat.PUMP_USED -> tenths(machine.pumpUsed());
			case Stat.SMOG -> machine.getWorld() instanceof ServerWorld world
					? tenths(dev.rackcraft.world.AirQuality.get(world).smogAt(machine.getPos())) : 0;
			case Stat.LOAD_LIMIT -> machine.loadLimitPercent();
			case Stat.SCRUB_RATE -> (int) Math.round(machine.scrubRate() * 100);
			case Stat.COOLING_KW -> tenths(machine.coolingKw());
			case Stat.LOOP_HEAT -> tenths(machine.loopHeatKw());
			case Stat.LOOP_CAPACITY -> tenths(machine.loopCapacityKw());
			case Stat.COOLING_DETAIL -> machine.coolingDetail();
			case Stat.HEAT_TO_LOOP -> tenths(machine.heatToLoopKw());
			case Stat.HEAT_TO_AIR -> tenths(machine.heatToAirKw());
			case Stat.ARRAY_EDGE -> machine.reactorArraySize();
			case Stat.SOURCE_CAPACITY -> tenths(machine.reactorCapacityKw());
			case Stat.FUEL_CELLS -> machine.arrayFuelCells();
			case Stat.PROCESS_STATUS -> machine.processStatus();
			case Stat.BOOT -> (int) Math.round(machine.bootProgress() * 100);
			case Stat.DATA_BANDWIDTH -> (int) Math.min(Integer.MAX_VALUE, Math.round(machine.dataBandwidth()));
			case Stat.DATA_DEMAND -> tenths(machine.dataDemand());
			case Stat.DATA_RACKS -> machine.dataRacks();
			case Stat.CUBE_PORT -> machine.cubePort() ? 1 : 0;
			case Stat.CUBE_OUTPUT -> machine.cubeOutput();
			case Stat.CUBE_BYPRODUCT -> machine.cubeByproduct();
			case Stat.CUBE_DEMAND -> tenths(machine.cubeDemandKw());
			case Stat.DOCK_JOBS -> machine.dockJobs();
			case Stat.LOCKED_CUBE -> machine.lockedCube();
			case Stat.WEAR -> (int) Math.round(machine.wear() * 100);
			case Stat.LAUNCH_TANK -> machine.launchTank();
			case Stat.LAUNCH_COUNTDOWN -> machine.launchCountdown();
			case Stat.LAUNCH_FLIGHT -> machine.launchFlight();
			case Stat.ORBIT_COMMS, Stat.ORBIT_DATACENTERS, Stat.ORBIT_MIRRORS, Stat.ORBIT_LAUNCHES -> {
				var orbit = machine.getWorld() instanceof ServerWorld world ? dev.rackcraft.world.OrbitState.existing(world) : null;
				yield orbit == null ? 0 : switch (stat) {
					case Stat.ORBIT_COMMS -> orbit.comms();
					case Stat.ORBIT_DATACENTERS -> orbit.datacenters();
					case Stat.ORBIT_MIRRORS -> orbit.mirrors();
					default -> orbit.launches();
				};
			}
			default -> stat >= Stat.SITE && stat < Stat.SITE + dev.rackcraft.world.SitePlanner.READINGS ? machine.siteReading(stat - Stat.SITE) : 0;
		};
	}

	private static int tenths(double value) {
		return (int) Math.round(Math.max(-2e8, Math.min(2e8, value * 10)));
	}

	/** Indices for {@link #stat(int)}. Fixed-point units are noted. */
	public static final class Stat {
		public static final int INLET = 0;              // tenths of C
		public static final int EXHAUST = 1;            // tenths of C
		public static final int POWER = 2;              // tenths of kW
		public static final int LOAD = 3;               // percent
		public static final int THERMAL = 4;            // percent
		public static final int SATISFACTION = 5;       // percent
		public static final int BALANCE = 6;            // RackCoin, low 31 bits (see balance())
		public static final int AVAILABILITY = 7;       // percent
		public static final int FUEL = 8;               // ticks left on the current fuel item
		public static final int RACK_STATUS = 9;        // RackStatus ordinal
		public static final int MINING_RATE = 10;       // hundredths of RC/s
		public static final int SPINUP = 11;            // 0..20 steps
		public static final int FUEL_TOTAL = 12;        // ticks the current fuel item started with
		public static final int NETWORK_DELIVERED = 13; // tenths of kW
		public static final int NETWORK_DEMAND = 14;    // tenths of kW
		public static final int BATTERY_PERMILLE = 15;
		public static final int MINING_RACKS = 16;
		public static final int TOTAL_RACKS = 17;
		public static final int NETWORK_CAPACITY = 18;  // tenths of kW
		public static final int TRANSMITTER_LEVEL = 19;
		public static final int WORKERS = 20;           // villagers at an art table or desk
		public static final int WORK_PROGRESS = 21;     // percent toward the next drawing or corpus
		public static final int ITEMS_MADE = 22;        // drawings toward the next aggregate
		public static final int TOOL_USES = 23;         // corpora written on the current ink sac
		public static final int BOUND = 24;             // 1 if a desk has a shackled librarian
		public static final int PUMP_SOURCES = 25;
		public static final int PUMP_UNITS = 26;
		public static final int PUMP_STATUS = 27;       // FreshwaterCooling.PumpStatus ordinal
		public static final int PUMP_USED = 28;         // tenths of a unit
		public static final int SMOG = 29;              // tenths
		public static final int LOAD_LIMIT = 30;        // percent
		public static final int SCRUB_RATE = 31;        // hundredths of smog per second
		public static final int COOLING_KW = 32;        // tenths of kW: heat this machine moved
		public static final int LOOP_HEAT = 33;         // tenths of kW put into this machine's coolant loop
		public static final int LOOP_CAPACITY = 34;     // tenths of kW the loop's sinks can take
		public static final int COOLING_DETAIL = 35;    // tower water units, dry cooler climate %, exchanger water blocks
		public static final int HEAT_TO_LOOP = 36;      // tenths of kW of a rack's heat carried off by its loop
		public static final int HEAT_TO_AIR = 37;       // tenths of kW of a rack's heat left in the air
		public static final int ARRAY_EDGE = 38;        // reactor array edge length, 1 for a lone reactor
		public static final int SOURCE_CAPACITY = 39;   // tenths of kW a reactor (array) can supply
		public static final int FUEL_CELLS = 40;        // Fuel Cells waiting in a reactor array's slots
		public static final int PROCESS_STATUS = 41;    // ReactorArrays.ReactorStatus or NuclearProcessing.Status ordinal
		public static final int BOOT = 42;              // percent a rack has booted
		public static final int DATA_BANDWIDTH = 43;    // RC/s a router's fiber network can carry
		public static final int DATA_DEMAND = 44;       // tenths of RC/s its racks would mine
		public static final int DATA_RACKS = 45;        // racks on a router's fiber network
		public static final int CUBE_PORT = 46;         // 1 if this core is its cube's port
		public static final int CUBE_OUTPUT = 47;       // products (or a reactor's Spent Fuel) in the whole cube
		public static final int CUBE_BYPRODUCT = 48;    // by-products in the whole cube
		public static final int CUBE_DEMAND = 49;       // tenths of kW the whole cube draws while working
		public static final int BALANCE_HIGH = 50;      // RackCoin above 2^31, so balances past 2.1 billion show
		public static final int INCOME = 51;            // tenths of RC/s a utility plant earns
		public static final int DOCK_JOBS = 52;        // jobs a Drone Dock can see right now
		public static final int LAUNCH_TANK = 53;      // Hydrogen Canisters in a Launch Control's tank
		public static final int LAUNCH_COUNTDOWN = 54; // ticks to liftoff
		public static final int LAUNCH_FLIGHT = 55;    // ticks of flight left
		public static final int ORBIT_COMMS = 56;
		public static final int ORBIT_DATACENTERS = 57;
		public static final int ORBIT_MIRRORS = 58;
		public static final int ORBIT_LAUNCHES = 59;
		public static final int LOCKED_CUBE = 60;     // edge of a whole cube too big for the research done, else 0
		public static final int WEAR = 61;            // percent an array or tower has worn
		public static final int SITE = 62;            // first of a Site Planner's readings (SitePlanner.R_*)
		static final int COUNT = SITE + dev.rackcraft.world.SitePlanner.READINGS;

		private Stat() {}
	}
	public String activeContract() { return activeContract; }
	public String activeEvent() { return activeEvent; }

	public enum Mode { RACK, SINGLE_SLOT, MACHINE_STATUS, CONTROLLER, MONITOR_WALL, EXCHANGE, CREATIVE,
		STORAGE_ARRAY, TAPE_LIBRARY, TRANSMITTER, WORKSTATION, REACTOR, PROCESSOR, WORKCELL, LAUNCH, SITE }
}