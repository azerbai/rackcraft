package dev.rackcraft.world;

import dev.rackcraft.RcItems;
import dev.rackcraft.block.MachineBlockEntity;
import dev.rackcraft.block.Racks;
import dev.rackcraft.compute.ResearchLab;
import dev.rackcraft.sim.ServerModel;
import java.util.function.DoubleSupplier;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.server.world.ServerWorld;

/**
 * Overclocking. A rack's load limit can go past 100% once Overclocking is researched (125%), and to 150% with Liquid
 * Hydrogen Cooling. The rack does load times as much work and pays the square of it in power and heat
 * ({@link ServerModel#powerLoad}), and every module that isn't tier 3 or better has a small chance each minute of
 * burning out: 1% at 125% and 3% at 150%, halved by Predictive Maintenance and halved again by Liquid Hydrogen Cooling.
 */
public final class Overclocking {
	public static final String RESEARCH = "overclocking";
	public static final String COOLING = "liquid_hydrogen_cooling";
	/** The self-test sets this to make the burn-out roll certain (0) or impossible (1). */
	public static DoubleSupplier roll = null;

	private Overclocking() {}

	/** The highest load limit this world's research allows. */
	public static int cap(ServerWorld world) {
		ResearchLab lab = ResearchLab.get(world);
		return lab.done(COOLING) ? 150 : lab.done(RESEARCH) ? 125 : 100;
	}

	public static int effectiveLimit(ServerWorld world, MachineBlockEntity rack) {
		return Math.min(rack.loadLimitPercent(), cap(world));
	}

	/** Each module's chance per minute of burning out at a load limit. */
	public static double perMinute(int limit, boolean predictiveMaintenance, boolean liquidHydrogen) {
		if (limit <= 100) return 0;
		double chance = limit <= 125 ? 0.01 : 0.03;
		if (predictiveMaintenance) chance /= 2;
		if (liquidHydrogen) chance /= 2;
		return chance;
	}

	/**
	 * Rolls for burn-outs in a rack that has been running {@code dt} seconds at {@code limit}. A module that burns out
	 * becomes a Failed Module that remembers what it was, so a Drone Dock can replace it. Returns how many went.
	 */
	public static int burn(ServerWorld world, MachineBlockEntity rack, int limit, double dt, boolean predictiveMaintenance, boolean liquidHydrogen) {
		double chance = perMinute(limit, predictiveMaintenance, liquidHydrogen) * dt / 60.0;
		if (chance <= 0) return 0;
		int burned = 0;
		for (int slot = 0; slot < Racks.bays(rack) && slot < rack.size(); slot++) {
			ServerModel.Module module = Racks.module(rack.getStack(slot));
			if (module == null || module.hardened()) continue;
			double dice = roll != null ? roll.getAsDouble() : world.random.nextDouble();
			if (dice >= chance) continue;
			ItemStack dead = new ItemStack(RcItems.ITEMS.get("failed_module"));
			dead.getOrCreateNbt().putString(DroneDocks.FAILED_KEY, Registries.ITEM.getId(rack.getStack(slot).getItem()).toString());
			rack.setStack(slot, dead);
			burned++;
		}
		if (burned > 0) rack.markDirty();
		return burned;
	}
}
