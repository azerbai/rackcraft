package dev.rackcraft.world;

import dev.rackcraft.block.MachineBlockEntity;
import dev.rackcraft.sim.ServerModel;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

/**
 * Cryostats keep Quantum Annealers' qubits a few thousandths of a degree above absolute zero. A rack with an Annealer in
 * it only runs with a cold Cryostat right beside it (any face): one with power and Hydrogen Canisters in its slot. The
 * liquid hydrogen boils off as it works: each running Annealer uses a canister every {@link #SECONDS_PER_CANISTER}
 * seconds, split between the Cryostats beside its rack. An Item Pipe to storage (or a Hydrogen Tank) keeps it topped up.
 */
public final class Cryostats {
	public static final double KW = 15;
	public static final double SECONDS_PER_CANISTER = 300;
	/** Canisters an Item Pipe keeps in it. */
	public static final int STOCK = 16;

	/** What a Cryostat is doing, for its screen. */
	public enum Status { COOLING, STANDING_BY, NO_HYDROGEN, NO_POWER }

	private Cryostats() {}

	public static boolean cold(MachineBlockEntity cryostat, Map<MachineBlockEntity, Double> satisfaction) {
		return satisfaction.getOrDefault(cryostat, 0.0) >= 0.5 && !cryostat.getStack(0).isEmpty();
	}

	/** The cold Cryostats touching this block. */
	public static List<MachineBlockEntity> beside(BlockPos pos, Map<BlockPos, MachineBlockEntity> byPos,
			Map<MachineBlockEntity, Double> satisfaction) {
		List<MachineBlockEntity> found = new ArrayList<>();
		for (Direction side : Direction.values()) {
			MachineBlockEntity machine = byPos.get(pos.offset(side));
			if (machine != null && machine.blockId().equals("cryostat") && cold(machine, satisfaction)) found.add(machine);
		}
		return found;
	}

	/** After the racks have run: boil off hydrogen for the Annealers that ran, and report. */
	public static void step(List<MachineBlockEntity> machines, Map<MachineBlockEntity, ServerModel.RackStep> rackSteps,
			Map<BlockPos, MachineBlockEntity> byPos, Map<MachineBlockEntity, Double> satisfaction, double dt) {
		Map<MachineBlockEntity, Double> annealers = new java.util.HashMap<>();
		rackSteps.forEach((rack, step) -> {
			if (step.load() <= 0) return;
			long count = rack.modules().stream().filter(module -> module == ServerModel.Module.QUANTUM_ANNEALER).count();
			if (count == 0) return;
			List<MachineBlockEntity> cold = beside(rack.getPos(), byPos, satisfaction);
			for (MachineBlockEntity cryostat : cold) annealers.merge(cryostat, count * step.load() / cold.size(), Double::sum);
		});
		for (MachineBlockEntity cryostat : machines) {
			if (!cryostat.blockId().equals("cryostat")) continue;
			double load = annealers.getOrDefault(cryostat, 0.0);
			Status status = satisfaction.getOrDefault(cryostat, 0.0) < 0.5 ? Status.NO_POWER
					: cryostat.getStack(0).isEmpty() ? Status.NO_HYDROGEN : load > 0 ? Status.COOLING : Status.STANDING_BY;
			if (load > 0) {
				double progress = cryostat.workProgress() + load * dt / SECONDS_PER_CANISTER;
				while (progress >= 1 && !cryostat.getStack(0).isEmpty()) {
					cryostat.getStack(0).decrement(1);
					progress -= 1;
					cryostat.setItemsMade(cryostat.itemsMade() + 1);
				}
				cryostat.setWorkProgress(Math.min(progress, 0.999));
			}
			// How many Annealers it is holding cold, rounded up, for its screen.
			cryostat.setWorkers((int) Math.ceil(load - 1e-9));
			cryostat.setProcess(status.ordinal(), status == Status.COOLING);
		}
	}
}
