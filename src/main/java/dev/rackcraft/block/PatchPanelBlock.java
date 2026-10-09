package dev.rackcraft.block;

import dev.rackcraft.RcItems;
import dev.rackcraft.compute.Research;
import dev.rackcraft.compute.ResearchLab;
import dev.rackcraft.world.NetworkManager;
import dev.rackcraft.world.PatchPanels;
import net.minecraft.block.AbstractBlock;
import net.minecraft.block.BlockState;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.World;

/**
 * A Patch Panel. Right-click a face with an empty hand to cycle that face's port; faces on the same non-zero port are
 * joined on every network ({@link PatchPanels}). A Name Tag in hand labels the panel; a Multimeter reads its wiring.
 */
public final class PatchPanelBlock extends MachineBlock {
	public static final String GATE = "structured_cabling";

	public PatchPanelBlock(AbstractBlock.Settings settings) {
		super(settings);
	}

	@Override
	public ActionResult onUse(BlockState state, World world, BlockPos pos, PlayerEntity player, Hand hand, BlockHitResult hit) {
		if (world.isClient || !(world instanceof ServerWorld serverWorld)) return ActionResult.SUCCESS;
		ItemStack held = player.getStackInHand(hand);
		PatchPanels panels = PatchPanels.get(serverWorld);
		if (held.isOf(net.minecraft.item.Items.NAME_TAG) && held.hasCustomName()) {
			panels.setName(pos, held.getName().getString());
			player.sendMessage(Text.literal("Labelled \"" + held.getName().getString() + "\"").formatted(Formatting.GRAY), true);
			return ActionResult.SUCCESS;
		}
		if (held.isOf(RcItems.ITEMS.get("multimeter"))) {
			readout(serverWorld, pos, player);
			return ActionResult.SUCCESS;
		}
		if (!held.isEmpty()) return ActionResult.PASS;
		if (!ResearchLab.get(serverWorld).done(GATE)) {
			player.sendMessage(Text.literal("Inert until " + Research.get(GATE).name() + " is researched").formatted(Formatting.RED), true);
			return ActionResult.SUCCESS;
		}
		Direction face = hit.getSide();
		int port = panels.cycle(pos, face);
		NetworkManager.get(serverWorld).markDirty();
		world.playSound(null, pos, SoundEvents.BLOCK_COMPARATOR_CLICK, SoundCategory.BLOCKS, 0.6f, 0.8f + 0.1f * port);
		player.sendMessage(Text.literal(face.getName() + " face: " + (port == 0 ? "isolated" : "port " + port))
				.formatted(port == 0 ? Formatting.GRAY : Formatting.AQUA), true);
		return ActionResult.SUCCESS;
	}

	/** One line per port in use: which faces share it. */
	public static void readout(ServerWorld world, BlockPos pos, PlayerEntity player) {
		PatchPanels panels = PatchPanels.get(world);
		String name = panels.name(pos);
		player.sendMessage(Text.literal("-- Patch Panel" + (name.isEmpty() ? "" : " \"" + name + "\"") + " --").formatted(Formatting.AQUA), false);
		for (int port = 1; port <= PatchPanels.MAX_PORT; port++) {
			StringBuilder faces = new StringBuilder();
			for (Direction face : Direction.values()) {
				if (panels.port(pos, face) == port) faces.append(faces.isEmpty() ? "" : ", ").append(face.getName());
			}
			if (!faces.isEmpty()) player.sendMessage(Text.literal("Port " + port + ": " + faces).formatted(Formatting.GRAY), false);
		}
		player.sendMessage(Text.literal(ResearchLab.get(world).done(GATE) ? "Faces on the same port are joined; port 0 is isolated"
				: "Inert until " + Research.get(GATE).name() + " is researched").formatted(Formatting.DARK_GRAY), false);
	}

	@Override
	public void onStateReplaced(BlockState state, World world, BlockPos pos, BlockState newState, boolean moved) {
		if (!state.isOf(newState.getBlock()) && world instanceof ServerWorld serverWorld) {
			PatchPanels.get(serverWorld).remove(pos);
			NetworkManager.get(serverWorld).markDirty();
		}
		super.onStateReplaced(state, world, pos, newState, moved);
	}
}
