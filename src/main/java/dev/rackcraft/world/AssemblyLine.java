package dev.rackcraft.world;

import dev.rackcraft.RcItems;
import dev.rackcraft.block.BeltBlockEntity;
import dev.rackcraft.block.MachineBlock;
import dev.rackcraft.block.MachineBlockEntity;
import java.util.List;
import java.util.Map;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

/**
 * The Assembly Line: Conveyor Belts carry workpieces past robot arms, and each arm does the steps of a recipe it can
 * do while the workpiece waits in the middle of the belt in front of it. A workpiece remembers how many steps are
 * done in its NBT, so one that falls off the line half-built can go round again.
 *
 * <ul>
 *   <li>Welding Robot: welds, {@link Kind#WELD}.</li>
 *   <li>Riveting Robot: rivets, {@link Kind#RIVET}.</li>
 *   <li>Assembly Robot: installs parts from its own nine slots, {@link Kind#INSTALL}.</li>
 * </ul>
 * Arms draw their working power only while they work, and slow down with the share of it the grid can give.
 */
public final class AssemblyLine {
	public static final String STEPS_KEY = "RcAssemblySteps";
	/** Which recipe a workpiece is being built to, once its first step settles it (a Satellite Bus can become four things). */
	public static final String RECIPE_KEY = "RcAssemblyRecipe";
	/** Idle draw of an arm with nothing to do. */
	public static final double IDLE_KW = 1;
	/** Below this share of its power an arm stops. */
	public static final double MIN_POWER = 0.1;

	public enum Kind {
		WELD("welding_arm", 1500, 6, "Weld", "Welding Robot"),
		RIVET("riveting_arm", 600, 4, "Rivet", "Riveting Robot"),
		INSTALL("assembly_arm", 250, 3, "Install", "Assembly Robot");

		public final String blockId;
		public final double kw;
		public final double seconds;
		public final String verb;
		public final String armName;

		Kind(String blockId, double kw, double seconds, String verb, String armName) {
			this.blockId = blockId;
			this.kw = kw;
			this.seconds = seconds;
			this.verb = verb;
			this.armName = armName;
		}
	}

	/** What an arm is doing, for its screen and the fault finder. */
	public enum Status { WORKING, WAITING, NO_PARTS, NO_POWER, NO_BELT, LOW_POWER }

	public record Step(Kind kind, Item part, int count) {
		public String describe() {
			return part == null ? kind.verb : kind.verb + " " + count + " " + new ItemStack(part).getName().getString();
		}
	}

	public record Recipe(String id, Item base, List<Step> steps, Item product) {}

	private AssemblyLine() {}

	public static List<Recipe> recipes() {
		return List.of(
				new Recipe("maintenance_drone", item("drone_frame"), List.of(
						new Step(Kind.INSTALL, item("electric_motor"), 4),
						new Step(Kind.WELD, null, 0),
						new Step(Kind.INSTALL, item("circuit_board"), 2),
						new Step(Kind.INSTALL, item("hydrogen_canister"), 1),
						new Step(Kind.RIVET, null, 0)), item("maintenance_drone")),
				new Recipe("rocket_stage", item("stage_frame"), List.of(
						new Step(Kind.WELD, null, 0),
						new Step(Kind.INSTALL, item("electric_motor"), 4),
						new Step(Kind.INSTALL, item("circuit_board"), 2),
						new Step(Kind.WELD, null, 0),
						new Step(Kind.RIVET, null, 0)), item("rocket_stage")),
				// Payloads share the Satellite Bus; the first part fitted decides which one it becomes.
				new Recipe("comms_satellite", item("satellite_bus"), List.of(
						new Step(Kind.INSTALL, item("copper_wire"), 16),
						new Step(Kind.INSTALL, item("circuit_board"), 4),
						new Step(Kind.WELD, null, 0),
						new Step(Kind.RIVET, null, 0)), item("comms_satellite")),
				new Recipe("survey_satellite", item("satellite_bus"), List.of(
						new Step(Kind.INSTALL, item("thermal_scanner"), 1),
						new Step(Kind.INSTALL, item("circuit_board"), 2),
						new Step(Kind.RIVET, null, 0)), item("survey_satellite")),
				new Recipe("orbital_datacenter", item("satellite_bus"), List.of(
						new Step(Kind.INSTALL, item("wafer_scale_engine"), 4),
						new Step(Kind.INSTALL, item("cryo_coil"), 4),
						new Step(Kind.WELD, null, 0),
						new Step(Kind.RIVET, null, 0)), item("orbital_datacenter")),
				new Recipe("dyson_mirror", item("satellite_bus"), List.of(
						new Step(Kind.INSTALL, dev.rackcraft.RcBlocks.get("solar_panel").asItem(), 16),
						new Step(Kind.WELD, null, 0),
						new Step(Kind.RIVET, null, 0)), item("dyson_mirror")));
	}

	private static Item item(String id) {
		Item item = RcItems.ITEMS.get(id);
		return item != null ? item : Items.AIR;
	}

	public static Kind kind(String blockId) {
		for (Kind kind : Kind.values()) if (kind.blockId.equals(blockId)) return kind;
		return null;
	}

	/** Every recipe this item could be built to: one for most bases, several for a Satellite Bus. */
	public static List<Recipe> candidates(ItemStack stack) {
		if (stack.isEmpty()) return List.of();
		String chosen = stack.hasNbt() ? stack.getNbt().getString(RECIPE_KEY) : "";
		return recipes().stream().filter(recipe -> stack.isOf(recipe.base()) && (chosen.isEmpty() || chosen.equals(recipe.id()))).toList();
	}

	/** The recipe this workpiece is being built to, or null if it isn't a workpiece or its branch isn't settled yet. */
	public static Recipe recipe(ItemStack stack) {
		List<Recipe> candidates = candidates(stack);
		return candidates.size() == 1 ? candidates.get(0) : null;
	}

	/** The recipe this arm would build the workpiece to: its settled one, or the first branch the arm can start. */
	private static Recipe recipeFor(MachineBlockEntity arm, ItemStack stack) {
		Kind kind = kind(arm.blockId());
		int done = stepsDone(stack);
		for (Recipe recipe : candidates(stack)) {
			if (done >= recipe.steps().size()) continue;
			Step step = recipe.steps().get(done);
			if (step.kind() == kind && (step.part() == null || parts(arm, step.part()) >= step.count())) return recipe;
		}
		return null;
	}

	public static int stepsDone(ItemStack stack) {
		return stack.hasNbt() ? stack.getNbt().getInt(STEPS_KEY) : 0;
	}

	/** The step this workpiece needs next, or null if it isn't one (or is somehow finished). */
	public static Step nextStep(ItemStack stack) {
		Recipe recipe = recipe(stack);
		if (recipe == null) return null;
		int done = stepsDone(stack);
		return done < recipe.steps().size() ? recipe.steps().get(done) : null;
	}

	/** Whether this arm could do the next step of this workpiece right now: its kind, its parts and its power. */
	public static boolean canWork(MachineBlockEntity arm, ItemStack stack) {
		if (kind(arm.blockId()) == null || arm.powerSatisfaction() < MIN_POWER) return false;
		return recipeFor(arm, stack) != null;
	}

	/**
	 * The arm that should hold the item on this belt: the one already holding it if it still can, otherwise any arm
	 * beside the belt and facing it that can do the next step. Null if none.
	 */
	public static BlockPos claimant(ServerWorld world, BlockPos beltPos, ItemStack stack, BlockPos current) {
		if (current != null && world.getBlockEntity(current) instanceof MachineBlockEntity arm && facesBelt(arm, beltPos)
				&& canWork(arm, stack)) return current;
		for (Direction side : Direction.Type.HORIZONTAL) {
			BlockPos pos = beltPos.offset(side);
			if (world.getBlockEntity(pos) instanceof MachineBlockEntity arm && facesBelt(arm, beltPos) && canWork(arm, stack)) return pos;
		}
		return null;
	}

	private static boolean facesBelt(MachineBlockEntity arm, BlockPos beltPos) {
		return kind(arm.blockId()) != null && arm.getPos().offset(arm.getCachedState().get(MachineBlock.FACING)).equals(beltPos);
	}

	public static int parts(MachineBlockEntity arm, Item part) {
		int count = 0;
		for (int slot = 0; slot < arm.size(); slot++) if (arm.getStack(slot).isOf(part)) count += arm.getStack(slot).getCount();
		return count;
	}

	private static void useParts(MachineBlockEntity arm, Item part, int count) {
		for (int slot = 0; slot < arm.size() && count > 0; slot++) {
			ItemStack stack = arm.getStack(slot);
			if (!stack.isOf(part)) continue;
			int used = Math.min(count, stack.getCount());
			stack.decrement(used);
			count -= used;
		}
		arm.markDirty();
	}

	/** What an arm draws: its working power while it worked last step, a trickle otherwise. */
	public static double demandKw(MachineBlockEntity arm) {
		Kind kind = kind(arm.blockId());
		if (kind == null) return 0;
		return arm.processActive() ? kind.kw : IDLE_KW;
	}

	/**
	 * One simulation step for every arm: work on the workpiece its belt holds for it, finish steps, and report.
	 * ITEMS_MADE counts the steps an arm has finished.
	 */
	public static void step(ServerWorld world, List<MachineBlockEntity> machines, Map<MachineBlockEntity, Double> satisfaction, double dt) {
		for (MachineBlockEntity arm : machines) {
			Kind kind = kind(arm.blockId());
			if (kind == null) continue;
			BlockPos beltPos = arm.getPos().offset(arm.getCachedState().get(MachineBlock.FACING));
			double power = satisfaction.getOrDefault(arm, 0.0);
			Status status;
			boolean active = false;
			if (!(world.getBlockEntity(beltPos) instanceof BeltBlockEntity belt)) {
				status = Status.NO_BELT;
				arm.setWorkProgress(0);
			} else if (belt.heldBy() == null || !belt.heldBy().equals(arm.getPos())) {
				// Not ours. An Assembly Robot only counts as out of parts when its slots are empty: on a long line most
				// robots hold one part and let the others' workpieces pass, which is fine.
				Step next = nextStep(belt.stack());
				boolean ours = next != null && next.kind() == kind;
				status = kind == Kind.INSTALL && arm.isEmpty() ? Status.NO_PARTS
						: ours && power < MIN_POWER ? Status.NO_POWER : Status.WAITING;
				arm.setWorkProgress(0);
			} else if (power < MIN_POWER) {
				status = Status.NO_POWER;
			} else {
				status = power < 0.995 ? Status.LOW_POWER : Status.WORKING;
				active = true;
				double progress = arm.workProgress() + dt / kind.seconds * Math.min(1, power);
				if (progress >= 1) {
					finish(world, arm, belt);
					progress = 0;
				}
				arm.setWorkProgress(progress);
				if (world.getTime() % 20 < Math.max(1, dev.rackcraft.RackcraftConfig.values.sim.stepTicks)) sound(world, arm, kind);
			}
			arm.setProcess(status.ordinal(), active);
		}
	}

	private static void finish(ServerWorld world, MachineBlockEntity arm, BeltBlockEntity belt) {
		ItemStack stack = belt.stack().copy();
		Recipe recipe = recipeFor(arm, stack);
		if (recipe == null) return;
		Step step = recipe.steps().get(stepsDone(stack));
		if (step.part() != null) useParts(arm, step.part(), step.count());
		int done = stepsDone(stack) + 1;
		if (done >= recipe.steps().size()) {
			stack = new ItemStack(recipe.product());
			world.playSound(null, belt.getPos(), SoundEvents.BLOCK_ANVIL_USE, SoundCategory.BLOCKS, 0.4f, 1.6f);
		} else {
			stack.getOrCreateNbt().putInt(STEPS_KEY, done);
			stack.getOrCreateNbt().putString(RECIPE_KEY, recipe.id());
		}
		belt.replace(stack);
		arm.setItemsMade(arm.itemsMade() + 1);
	}

	private static void sound(ServerWorld world, MachineBlockEntity arm, Kind kind) {
		BlockPos pos = arm.getPos();
		switch (kind) {
			case WELD -> world.playSound(null, pos, SoundEvents.BLOCK_FIRE_AMBIENT, SoundCategory.BLOCKS, 0.6f, 1.6f);
			case RIVET -> world.playSound(null, pos, SoundEvents.BLOCK_CHAIN_PLACE, SoundCategory.BLOCKS, 0.7f, 0.6f);
			case INSTALL -> world.playSound(null, pos, SoundEvents.BLOCK_PISTON_EXTEND, SoundCategory.BLOCKS, 0.25f, 1.4f);
		}
	}

	/** Tooltip lines for a half-built workpiece: how far it got and what it needs next. */
	/** What happens to this item next on a line, for screens: the next step, or the branches it could take. */
	public static String nextDescription(ItemStack stack) {
		Step next = nextStep(stack);
		if (next != null) return "Next: " + next.describe() + " (" + next.kind().armName + ")";
		List<Recipe> candidates = candidates(stack);
		if (candidates.size() > 1) {
			return "Becomes whatever its first part makes it: " + String.join(", ", candidates.stream()
					.map(recipe -> new ItemStack(recipe.product()).getName().getString()).toList());
		}
		return "Nothing to do to it";
	}

	public static List<String> describe(ItemStack stack) {
		Recipe recipe = recipe(stack);
		if (recipe == null || stepsDone(stack) == 0) return List.of();
		Step next = nextStep(stack);
		return List.of("Assembly: " + stepsDone(stack) + " of " + recipe.steps().size() + " steps done",
				next == null ? "Finished" : "Next: " + next.describe() + " (" + next.kind().armName + ")");
	}
}
