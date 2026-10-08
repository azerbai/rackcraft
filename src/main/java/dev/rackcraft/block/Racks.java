package dev.rackcraft.block;

import dev.rackcraft.sim.ServerModel;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;

/** Racks of every tier, and the modules in them, as the world sees them. */
public final class Racks {
	/** Which of its three modes an FPGA stack is in: 0 mining, 1 AI, 2 autocrafting. */
	public static final String FPGA_MODE = "FpgaMode";

	private Racks() {}

	public static boolean isRack(String blockId) { return ServerModel.Tier.isRack(blockId); }

	public static boolean isRack(MachineBlockEntity machine) { return isRack(machine.blockId()); }

	/** The rack's tier; a Server Rack's for anything else. */
	public static ServerModel.Tier tier(MachineBlockEntity machine) {
		ServerModel.Tier tier = ServerModel.Tier.of(machine.blockId());
		return tier == null ? ServerModel.Tier.SERVER : tier;
	}

	public static int bays(MachineBlockEntity machine) { return tier(machine).bays(); }

	/** The module this stack is, FPGAs in the mode they are set to; null if it isn't one. */
	public static ServerModel.Module module(ItemStack stack) {
		if (stack.isEmpty()) return null;
		return ServerModel.Module.of(Registries.ITEM.getId(stack.getItem()).getPath(),
				stack.hasNbt() ? stack.getNbt().getInt(FPGA_MODE) : 0);
	}

	/** Fiber bandwidth the rack's mining needs: what its modules mine, with its tier's bonus and fabric. */
	public static double bandwidthNeed(MachineBlockEntity rack) {
		ServerModel.Tier tier = tier(rack);
		return rack.modules().stream().mapToDouble(ServerModel.Module::creditsPerSecond).sum() * tier.bonus() * tier.bandwidth();
	}
}
