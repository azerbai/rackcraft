package dev.rackcraft.item;

import dev.rackcraft.block.CableBlock;
import dev.rackcraft.block.MachineBlock;
import dev.rackcraft.block.MachineBlockEntity;
import dev.rackcraft.sim.NetKind;
import dev.rackcraft.sim.ServerModel;
import dev.rackcraft.world.NetworkManager;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import net.minecraft.block.BlockState;
import net.minecraft.client.item.TooltipContext;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.ItemUsageContext;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

/** Right-click: live readout of a machine or cable network. Sneak-right-click a machine: rotate it. */
public final class MultimeterItem extends Item {
	public MultimeterItem(Item.Settings settings) {
		super(settings);
	}

	@Override
	public ActionResult useOnBlock(ItemUsageContext context) {
		World world = context.getWorld();
		BlockPos pos = context.getBlockPos();
		BlockState state = world.getBlockState(pos);
		PlayerEntity player = context.getPlayer();
		boolean machine = state.getBlock() instanceof MachineBlock;
		if (!machine && !(state.getBlock() instanceof CableBlock)) return ActionResult.PASS;
		if (world.isClient || player == null) return ActionResult.SUCCESS;
		ServerWorld serverWorld = (ServerWorld) world;

		if (machine && player.isSneaking()) {
			world.setBlockState(pos, state.with(MachineBlock.FACING, state.get(MachineBlock.FACING).rotateYClockwise()));
			world.playSound(null, pos, SoundEvents.BLOCK_IRON_TRAPDOOR_CLOSE, SoundCategory.BLOCKS, 0.6f, 1.4f);
			return ActionResult.SUCCESS;
		}
		List<Text> lines = state.getBlock() instanceof CableBlock cable
				? cableReadout(serverWorld, pos, state, cable)
				: world.getBlockEntity(pos) instanceof MachineBlockEntity entity ? machineReadout(serverWorld, entity) : List.of();
		for (Text line : lines) player.sendMessage(line, false);
		world.playSound(null, pos, SoundEvents.UI_BUTTON_CLICK.value(), SoundCategory.PLAYERS, 0.3f, 1.8f);
		return ActionResult.SUCCESS;
	}

	private static List<Text> machineReadout(ServerWorld world, MachineBlockEntity machine) {
		List<Text> lines = new ArrayList<>();
		lines.add(header(Text.translatable("block.rackcraft." + machine.blockId())));
		switch (machine.blockId()) {
			case "server_rack" -> {
				lines.add(row("Status", Text.translatable(machine.rackStatus().translationKey(), format(machine.miningRate())),
						machine.rackStatus().mining() ? Formatting.GREEN : Formatting.RED));
				lines.add(row("Intake / exhaust", Text.literal(format(machine.inletCelsius()) + " C / "
						+ format(machine.exhaustCelsius()) + " C"), machine.inletCelsius() > 27 ? Formatting.GOLD : Formatting.WHITE));
				lines.add(row("Draw", Text.literal(format(machine.powerKw()) + " kW"), Formatting.WHITE));
				lines.add(row("Heat", Text.literal(format(machine.heatToLoopKw()) + " kW to the loop, "
						+ format(machine.heatToAirKw()) + " kW to the air"), machine.heatToAirKw() > 5 ? Formatting.GOLD : Formatting.WHITE));
			}
			case "modular_reactor" -> {
				int edge = machine.reactorArraySize();
				lines.add(row("Array", Text.literal(edge > 1 ? edge + "x" + edge + "x" + edge + " (" + edge * edge * edge + " cores, "
						+ Math.round(dev.rackcraft.world.ReactorArrays.efficiency(edge) * 100) + "% fuel per core)"
						: "single core"), edge > 1 ? Formatting.GREEN : Formatting.WHITE));
				lines.add(row("Output", Text.literal(format(machine.powerKw()) + " / " + format(machine.reactorCapacityKw()) + " kW"),
						Formatting.WHITE));
				lines.add(row("Fuel", Text.literal(machine.arrayFuelTicks() / 20 + " s on this cell at full output, "
						+ machine.arrayFuelCells() + " spare"), machine.arrayFuelTicks() > 0 || machine.arrayFuelCells() > 0
						? Formatting.WHITE : Formatting.RED));
			}
			case "exhaust_fan", "crac_unit", "rear_door_cooler", "cooling_tower", "dry_cooler", "chiller", "water_heat_exchanger" ->
					lines.add(row("Moving", Text.literal(format(machine.coolingKw()) + " kW of heat"),
							machine.coolingKw() > 0 ? Formatting.GREEN : Formatting.GRAY));
			case "diesel_generator" -> {
				lines.add(row("Output", Text.literal(format(machine.powerKw()) + " / 40 kW"), Formatting.WHITE));
				lines.add(row("Fuel left", Text.literal(machine.fuelBurnTicks() / 20 + " s"),
						machine.fuelBurnTicks() > 0 ? Formatting.WHITE : Formatting.RED));
			}
			case "battery_bank" -> {
				int edge = machine.reactorArraySize();
				double perBank = dev.rackcraft.world.SimTicker.batteryCapacityPerBank(edge);
				lines.add(row("Charge", Text.literal(Math.round(machine.chargeKws() / perBank * 100) + "% (" + format(machine.powerKw())
						+ " kW)"), Formatting.WHITE));
				if (edge > 1) lines.add(row("Grid-Scale Battery", Text.literal(edge + "x" + edge + "x" + edge + ", "
						+ format(machine.reactorCapacityKw() / 1000) + " MJ, " + Math.round(dev.rackcraft.world.SimTicker.batteryEfficiency(edge) * 100)
						+ "% efficient each way"), Formatting.GREEN));
			}
			case "creative_rack" -> {
				lines.add(row("Mining", Text.literal(format(machine.miningRate()) + " RC/s"), Formatting.LIGHT_PURPLE));
				lines.add(row("Test load", Text.literal(format(machine.powerKw()) + " of "
						+ format(machine.creativeValue(dev.rackcraft.CreativeSettings.DRAW_KW)) + " kW"), Formatting.WHITE));
			}
			case "creative_cooler" -> lines.add(row("Holding air at",
					Text.literal(format(machine.creativeValue(dev.rackcraft.CreativeSettings.TARGET_C)) + " C"), Formatting.AQUA));
			case "solar_panel", "wind_turbine", "utility_intake", "creative_power" ->
					lines.add(row("Output", Text.literal(format(machine.powerKw()) + " kW"), Formatting.WHITE));
			default -> {
				if (MachineBlockEntity.networkKinds(machine.blockId()).contains(NetKind.POWER)) {
					lines.add(row("Draw", Text.literal(format(machine.powerKw()) + " kW ("
							+ Math.round(machine.powerSatisfaction() * 100) + "% supplied)"), Formatting.WHITE));
				}
			}
		}
		Set<NetKind> kinds = MachineBlockEntity.networkKinds(machine.blockId());
		if (kinds.contains(NetKind.COOLANT)) lines.add(loopLine(machine));
		if (kinds.contains(NetKind.POWER)) lines.add(powerLine(machine));
		if (kinds.contains(NetKind.DATA)) lines.add(dataLine(world, machine.getPos()));
		return lines;
	}

	private static List<Text> cableReadout(ServerWorld world, BlockPos pos, BlockState state, CableBlock cable) {
		List<Text> lines = new ArrayList<>();
		lines.add(header(Text.translatable(state.getBlock().getTranslationKey())));
		if (state.get(CableBlock.CUT)) {
			lines.add(row("Status", Text.literal("CUT: right-click with a Repair Kit"), Formatting.RED));
			return lines;
		}
		Set<BlockPos> component = NetworkManager.get(world).component(pos, cable.kind());
		long machines = component.stream().filter(member -> world.getBlockEntity(member) instanceof MachineBlockEntity).count();
		lines.add(row("Connected machines", Text.literal(Long.toString(machines)), Formatting.WHITE));
		switch (cable.kind()) {
			case POWER -> component.stream().map(world::getBlockEntity).filter(MachineBlockEntity.class::isInstance)
					.map(MachineBlockEntity.class::cast).findFirst().ifPresent(machine -> lines.add(powerLine(machine)));
			case DATA -> lines.add(dataLine(world, pos));
			case COOLANT -> {
				MachineBlockEntity first = component.stream().map(world::getBlockEntity).filter(MachineBlockEntity.class::isInstance)
						.map(MachineBlockEntity.class::cast).findFirst().orElse(null);
				long sinks = component.stream().filter(member -> world.getBlockEntity(member) instanceof MachineBlockEntity entity
						&& dev.rackcraft.world.CoolingLoops.isSink(entity.blockId())).count();
				lines.add(row("Heat sinks", Text.literal(sinks == 0 ? "none: add a Cooling Tower, Dry Cooler, Chiller or Water Heat Exchanger"
						: Long.toString(sinks)), sinks == 0 ? Formatting.RED : Formatting.GREEN));
				if (first != null) lines.add(loopLine(first));
			}
			case ITEM -> {
				long storage = component.stream().filter(member -> world.getBlockEntity(member) instanceof MachineBlockEntity entity
						&& (entity.blockId().equals("storage_array") || entity.blockId().equals("tape_library")) && entity.storageOnline()).count();
				lines.add(row("Storage", Text.literal(storage == 0 ? "none online: connect a powered Storage Array" : storage + " online"),
						storage == 0 ? Formatting.RED : Formatting.GREEN));
			}
		}
		return lines;
	}

	private static Text loopLine(MachineBlockEntity machine) {
		double heat = machine.loopHeatKw();
		double capacity = machine.loopCapacityKw();
		return row("Coolant loop", Text.literal(format(heat) + " kW of heat in, sinks can take " + format(capacity) + " kW"),
				heat > capacity + 0.05 ? Formatting.RED : capacity <= 0 ? Formatting.GRAY : Formatting.GREEN);
	}

	private static Text powerLine(MachineBlockEntity machine) {
		boolean shortfall = machine.networkSupplyKw() + 0.05 < machine.networkDemandKw();
		return row("Power network", Text.literal(format(machine.networkSupplyKw()) + " of " + format(machine.networkDemandKw())
				+ " kW delivered, " + format(machine.networkCapacityKw()) + " kW capacity"),
				shortfall ? Formatting.RED : Formatting.GREEN);
	}

	private static Text dataLine(ServerWorld world, BlockPos pos) {
		double bandwidth = 0;
		double demand = 0;
		for (BlockPos member : NetworkManager.get(world).component(pos, NetKind.DATA)) {
			if (!(world.getBlockEntity(member) instanceof MachineBlockEntity entity)) continue;
			if (entity.blockId().equals("uplink_router")) bandwidth += 100;
			if (entity.blockId().equals("core_router")) bandwidth += 1000;
			if (entity.blockId().equals("creative_router")) bandwidth += entity.creativeValue(dev.rackcraft.CreativeSettings.BANDWIDTH);
			if (entity.blockId().equals("server_rack")) {
				demand += entity.modules().stream().mapToDouble(ServerModel.Module::creditsPerSecond).sum();
			}
		}
		return row("Fiber bandwidth", Text.literal(format(bandwidth) + " available, " + format(demand) + " needed"),
				bandwidth <= 0 ? Formatting.RED : bandwidth < demand ? Formatting.GOLD : Formatting.GREEN);
	}

	private static Text header(Text name) {
		return Text.literal("-- ").formatted(Formatting.DARK_GRAY).append(name.copy().formatted(Formatting.AQUA))
				.append(Text.literal(" --").formatted(Formatting.DARK_GRAY));
	}

	private static Text row(String label, Text value, Formatting color) {
		MutableText text = Text.literal(label + ": ").formatted(Formatting.GRAY);
		return text.append(value.copy().formatted(color));
	}

	private static String format(double value) {
		return String.format(Locale.ROOT, "%.1f", value);
	}

	@Override
	public void appendTooltip(ItemStack stack, World world, List<Text> tooltip, TooltipContext context) {
		tooltip.add(Text.translatable("tooltip.rackcraft.multimeter").formatted(Formatting.GRAY));
	}
}
