package dev.rackcraft.world;

import dev.rackcraft.RcBlocks;
import dev.rackcraft.RcItems;
import dev.rackcraft.block.MachineBlockEntity;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import net.minecraft.item.Item;
import net.minecraft.item.Items;

/**
 * The nuclear fuel cycle's processing machines. Each only works as a cube multiblock (see {@link ReactorArrays}):
 * every block in the cube is a core that finishes one batch per recipe time, so a 3x3x3 runs 27 batches at once.
 * Slots: 0 and 1 are inputs, 2 the product, 3 the by-product. Inputs are pooled evenly across the cube.
 *
 * <ul>
 *   <li>Uranium Mill: two Raw Uranium to a Yellowcake (30 s, 20 kW per core).</li>
 *   <li>Gas Centrifuge: six Yellowcake to one Enriched Uranium and four Depleted Uranium (120 s, 60 kW).</li>
 *   <li>Fuel Fabricator: Enriched Uranium and two Steel Ingots to a Fuel Cell, the only way to make one (60 s, 30 kW).</li>
 *   <li>Cask Sealer: four Spent Fuel and four Depleted Uranium to a Sealed Waste Cask (60 s, 15 kW).</li>
 *   <li>Wafer Fab: sixteen Silicon and four GPU Chips to a Wafer-Scale Engine (5 min, 400 kW). It only runs once
 *       Extreme UV Lithography is researched; until then it reports {@link Status#LOCKED}.</li>
 * </ul>
 */
public final class NuclearProcessing {
	public static final int OUTPUT_SLOT = 2;
	public static final int BYPRODUCT_SLOT = 3;
	/** Idle draw of a formed core that has nothing to do. */
	private static final double IDLE_KW = 0.1;

	public enum Status { RUNNING, NOT_FORMED, NO_INPUT, OUTPUT_FULL, NO_POWER, LOCKED }

	public record Recipe(Item inputA, int countA, Item inputB, int countB, Item output, int outputCount, Item byproduct,
			int byproductCount, double seconds, double kwPerCore) {}

	private NuclearProcessing() {}

	public static Recipe recipe(String machine) {
		return switch (machine) {
			case "uranium_mill" -> new Recipe(item("raw_uranium"), 2, null, 0, item("yellowcake"), 1, null, 0, 30, 20);
			case "gas_centrifuge" -> new Recipe(item("yellowcake"), 6, null, 0, item("enriched_uranium"), 1,
					item("depleted_uranium"), 4, 120, 60);
			case "fuel_fabricator" -> new Recipe(item("enriched_uranium"), 1, item("steel_ingot"), 2, item("fuel_cell"), 1, null, 0, 60, 30);
			case "cask_sealer" -> new Recipe(item("spent_fuel"), 4, item("depleted_uranium"), 4,
					RcBlocks.get("waste_cask").asItem(), 1, null, 0, 60, 15);
			case "wafer_fab" -> new Recipe(item("silicon"), 16, item("gpu_chip"), 4, item("wafer_scale_engine"), 1, null, 0, 300, 400);
			default -> null;
		};
	}

	private static Item item(String id) {
		Item item = RcItems.ITEMS.get(id);
		return item != null ? item : Items.AIR;
	}

	/** What a core draws: its share of a working cube's power, or a trickle while idle. */
	public static double demandKw(MachineBlockEntity machine) {
		Recipe recipe = recipe(machine.blockId());
		if (recipe == null) return 0;
		int edge = machine.reactorArraySize();
		if (edge < 2) return 0;
		return machine.processActive() ? recipe.kwPerCore() * ReactorArrays.efficiency(edge) : IDLE_KW;
	}

	/** One step for every processing cube: pool inputs, check power, inputs and room, and make what it can. */
	public static void step(Map<MachineBlockEntity, ReactorArrays.Array> arrays, Map<MachineBlockEntity, Double> satisfaction, double dt,
			dev.rackcraft.compute.Research.Effects research) {
		for (ReactorArrays.Array array : new HashSet<>(arrays.values())) {
			Recipe recipe = recipe(array.controller().blockId());
			if (recipe == null) continue;
			List<MachineBlockEntity> members = array.members();
			MachineBlockEntity controller = array.controller();
			ReactorArrays.pool(members, 0);
			ReactorArrays.pool(members, 1);
			Status status;
			boolean active = false;
			if (array.edge() < 2) {
				status = Status.NOT_FORMED;
			} else {
				double power = members.stream().mapToDouble(member -> satisfaction.getOrDefault(member, 0.0)).average().orElse(0);
				double progress = controller.workProgress();
				boolean ready = ready(members, recipe);
				active = ready;
				if (array.controller().blockId().equals("wafer_fab") && !research.lithography()) {
					status = Status.LOCKED;
					active = false;
				} else if (!ready) {
					status = hasInputs(members, recipe) ? Status.OUTPUT_FULL : Status.NO_INPUT;
				} else if (power < 0.5) {
					status = Status.NO_POWER;
				} else {
					status = Status.RUNNING;
					progress += dt * array.cores() / recipe.seconds() * Math.min(1, power);
					while (progress >= 1 && ready(members, recipe)) {
						progress -= 1;
						ReactorArrays.take(members, 0, recipe.inputA(), recipe.countA());
						if (recipe.inputB() != null) ReactorArrays.take(members, 1, recipe.inputB(), recipe.countB());
						ReactorArrays.put(members, OUTPUT_SLOT, recipe.output(), recipe.outputCount(), 64);
						if (recipe.byproduct() != null) ReactorArrays.put(members, BYPRODUCT_SLOT, recipe.byproduct(), recipe.byproductCount(), 64);
					}
					// A stalled cube doesn't bank batches it couldn't finish.
					progress = Math.min(progress, ready(members, recipe) ? progress : 0.99);
				}
				controller.setWorkProgress(progress);
			}
			double draw = 0;
			for (MachineBlockEntity member : members) {
				member.setProcess(status.ordinal(), active);
				member.setReactorArray(array.edge(), 0, 0, 0, 0);
				member.setPowerKw(demandKw(member) * satisfaction.getOrDefault(member, 0.0));
				draw += member.powerKw();
				if (member != controller) member.setWorkProgress(controller.workProgress());
			}
			// Every core reports the whole cube's draw, the way reactor cores report the array's output.
			for (MachineBlockEntity member : members) member.setPowerKw(draw);
		}
	}

	private static boolean hasInputs(List<MachineBlockEntity> members, Recipe recipe) {
		return ReactorArrays.count(members, 0, recipe.inputA()) >= recipe.countA()
				&& (recipe.inputB() == null || ReactorArrays.count(members, 1, recipe.inputB()) >= recipe.countB());
	}

	private static boolean ready(List<MachineBlockEntity> members, Recipe recipe) {
		return hasInputs(members, recipe)
				&& ReactorArrays.room(members, OUTPUT_SLOT, recipe.output(), 64) >= recipe.outputCount()
				&& (recipe.byproduct() == null || ReactorArrays.room(members, BYPRODUCT_SLOT, recipe.byproduct(), 64) >= recipe.byproductCount());
	}
}
