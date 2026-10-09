package dev.rackcraft.world;

import dev.rackcraft.RcBlocks;
import dev.rackcraft.block.MachineBlock;
import dev.rackcraft.block.MachineBlockEntity;
import dev.rackcraft.block.Racks;
import dev.rackcraft.compute.ResearchLab;
import dev.rackcraft.sim.ServerModel;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.block.BlockState;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

/**
 * The Site Planner's Retrofit layout: it upgrades the racks already standing on the site to a higher tier, in place. A
 * rack keeps its modules, its load limit and the way it faces; the drone fits the parts the next tier is built from
 * (the same ones the Assembly Line fits to make the rack), so a retrofit costs the parts and not another rack. A rack
 * can jump several tiers at once, fitting each tier's parts in turn. A rack is left alone if its modules wouldn't fit
 * the new tier (an Exascale Cabinet takes only tier 2 hardware and better), or if the research for that tier isn't done.
 */
public final class Retrofits {
	public record Job(MachineBlockEntity rack, ServerModel.Tier from) {}

	private Retrofits() {}

	/** The best rack tier this world has the research to build. */
	public static ServerModel.Tier unlocked(ServerWorld world) {
		ResearchLab lab = ResearchLab.get(world);
		if (lab.done("exascale")) return ServerModel.Tier.EXASCALE;
		if (lab.done("immersion_cooling")) return ServerModel.Tier.IMMERSION;
		if (lab.done("dense_racks")) return ServerModel.Tier.HIGH_DENSITY;
		return ServerModel.Tier.SERVER;
	}

	/** The tier a retrofit goes to: what the planner is set to, held back to what the research allows. */
	public static ServerModel.Tier target(ServerWorld world, MachineBlockEntity planner) {
		ServerModel.Tier chosen = SitePlanner.hall(planner).tier();
		ServerModel.Tier best = unlocked(world);
		return chosen.ordinal() <= best.ordinal() ? chosen : best;
	}

	/** Every loaded rack standing inside the site. */
	public static List<MachineBlockEntity> racksIn(ServerWorld world, SitePlanner.Site site) {
		List<MachineBlockEntity> racks = new ArrayList<>();
		for (MachineBlockEntity machine : SimTicker.machines(world)) {
			if (!Racks.isRack(machine) || machine.isRemoved()) continue;
			if (site.contains(machine.getPos().getX(), machine.getPos().getZ())) racks.add(machine);
		}
		racks.sort(java.util.Comparator.comparingLong(rack -> rack.getPos().asLong()));
		return racks;
	}

	/** Whether every module in the rack would be accepted by the new tier. */
	public static boolean fits(MachineBlockEntity rack, ServerModel.Tier target) {
		for (ServerModel.Module module : rack.modules()) if (!target.accepts(module)) return false;
		// An Immersion Rack won't run off a coolant loop, so it can't take modules that an air rack would; none are excluded here.
		return true;
	}

	/** The racks on the site below the target tier that can be upgraded. */
	public static List<Job> pending(ServerWorld world, SitePlanner.Site site, ServerModel.Tier target) {
		List<Job> jobs = new ArrayList<>();
		for (MachineBlockEntity rack : racksIn(world, site)) {
			ServerModel.Tier tier = Racks.tier(rack);
			if (tier.ordinal() < target.ordinal() && fits(rack, target)) jobs.add(new Job(rack, tier));
		}
		return jobs;
	}

	/** Racks on the site that are below the target but whose modules won't fit it. */
	public static int skipped(ServerWorld world, SitePlanner.Site site, ServerModel.Tier target) {
		int skipped = 0;
		for (MachineBlockEntity rack : racksIn(world, site)) {
			if (Racks.tier(rack).ordinal() < target.ordinal() && !fits(rack, target)) skipped++;
		}
		return skipped;
	}

	/** The parts that take a rack from one tier to a higher one: each tier's Assembly Line install steps, added up. */
	public static Map<Item, Integer> parts(ServerModel.Tier from, ServerModel.Tier to) {
		Map<Item, Integer> parts = new LinkedHashMap<>();
		for (ServerModel.Tier tier : ServerModel.Tier.values()) {
			if (tier.ordinal() <= from.ordinal() || tier.ordinal() > to.ordinal()) continue;
			Item product = RcBlocks.get(tier.blockId()).asItem();
			for (AssemblyLine.Recipe recipe : AssemblyLine.recipes()) {
				if (recipe.product() != product) continue;
				for (AssemblyLine.Step step : recipe.steps()) {
					if (step.kind() == AssemblyLine.Kind.INSTALL && step.part() != null) parts.merge(step.part(), step.count(), Integer::sum);
				}
			}
		}
		return parts;
	}

	/**
	 * Upgrades the rack at a position to the target tier using parts from the drone's hold. Returns false (changing
	 * nothing) if it isn't a lower-tier rack, the modules won't fit or the hold lacks a part.
	 */
	public static boolean upgrade(ServerWorld world, BlockPos pos, ServerModel.Tier target, Inventory hold) {
		BlockState state = world.getBlockState(pos);
		ServerModel.Tier from = ServerModel.Tier.of(net.minecraft.registry.Registries.BLOCK.getId(state.getBlock()).getPath());
		if (from == null || from.ordinal() >= target.ordinal() || !(world.getBlockEntity(pos) instanceof MachineBlockEntity old) || !fits(old, target)) return false;
		Map<Item, Integer> need = parts(from, target);
		for (Map.Entry<Item, Integer> entry : need.entrySet()) {
			int held = 0;
			for (int slot = 0; slot < hold.size(); slot++) if (hold.getStack(slot).isOf(entry.getKey())) held += hold.getStack(slot).getCount();
			if (held < entry.getValue()) return false;
		}
		for (Map.Entry<Item, Integer> entry : need.entrySet()) {
			int left = entry.getValue();
			for (int slot = 0; slot < hold.size() && left > 0; slot++) {
				ItemStack stack = hold.getStack(slot);
				if (!stack.isOf(entry.getKey())) continue;
				int take = Math.min(left, stack.getCount());
				stack.decrement(take);
				left -= take;
			}
		}
		List<ItemStack> contents = new ArrayList<>();
		for (int slot = 0; slot < Racks.bays(old) && slot < old.size(); slot++) contents.add(old.getStack(slot).copy());
		int limit = old.loadLimitPercent();
		Direction facing = state.contains(MachineBlock.FACING) ? state.get(MachineBlock.FACING) : Direction.NORTH;
		// The modules are in hand; empty the old rack so replacing it doesn't scatter them on the floor.
		old.clear();
		world.setBlockState(pos, RcBlocks.get(target.blockId()).getDefaultState().with(MachineBlock.FACING, facing), net.minecraft.block.Block.NOTIFY_ALL);
		if (world.getBlockEntity(pos) instanceof MachineBlockEntity next) {
			for (int slot = 0; slot < contents.size() && slot < Racks.bays(next); slot++) next.setStack(slot, contents.get(slot));
			next.setLoadLimitPercent(limit);
		}
		return true;
	}
}
