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
	}

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
			for (int index = 0; index < 8; index++) addSlot(new Slot(machineInventory, index, 10, 18 + index * 18));
			addPlayerInventory(playerInventory, 10, 170);
		} else if (mode == Mode.SINGLE_SLOT) {
			addSlot(new Slot(machineInventory, 0, 80, 42));
			addPlayerInventory(playerInventory, 8, 92);
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

	@Override
	public ItemStack quickMove(PlayerEntity player, int slot) { return ItemStack.EMPTY; }
	@Override
	public boolean canUse(PlayerEntity player) { return machine == null || machine.canPlayerUse(player); }
	public Mode mode() { return mode; }
	public BlockPos pos() { return pos; }
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
			case Stat.BALANCE -> facility != null && facilityScreen ? (int) Math.min(Integer.MAX_VALUE, facility.credits()) : 0;
			case Stat.AVAILABILITY -> facility != null && facilityScreen ? (int) Math.round(facility.availability().stream()
					.mapToDouble(Double::doubleValue).average().orElse(1) * 100) : 0;
			case Stat.FUEL -> machine.fuelBurnTicks();
			case Stat.RACK_STATUS -> machine.rackStatus().ordinal();
			case Stat.MINING_RATE -> (int) Math.round((facility != null && facilityScreen
					? facility.miningRate() : machine.miningRate()) * 100);
			case Stat.SPINUP -> machine.dieselSpinupSteps();
			case Stat.FUEL_TOTAL -> machine.fuelBurnTotal();
			case Stat.NETWORK_DELIVERED -> tenths(machine.networkSupplyKw());
			case Stat.NETWORK_DEMAND -> tenths(machine.networkDemandKw());
			case Stat.BATTERY_PERMILLE -> (int) Math.round(machine.chargeKws() / 3000 * 1000);
			case Stat.MINING_RACKS -> facility != null ? facility.miningRacks() : 0;
			case Stat.TOTAL_RACKS -> facility != null ? facility.totalRacks() : 0;
			case Stat.NETWORK_CAPACITY -> tenths(machine.networkCapacityKw());
			default -> 0;
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
		public static final int BALANCE = 6;            // RackCoin
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
		static final int COUNT = 19;

		private Stat() {}
	}
	public String activeContract() { return activeContract; }
	public String activeEvent() { return activeEvent; }

	public enum Mode { RACK, SINGLE_SLOT, MACHINE_STATUS, CONTROLLER, MONITOR_WALL, EXCHANGE }
}