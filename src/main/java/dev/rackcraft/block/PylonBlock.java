package dev.rackcraft.block;

import dev.rackcraft.world.NetworkManager;
import dev.rackcraft.world.PylonLinks;
import net.minecraft.block.AbstractBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.ShapeContext;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.registry.Registries;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.world.BlockView;
import net.minecraft.world.World;

/** A Pylon: a slim mast that one end of a power span is strung from. Link two with a Pylon Linker. */
public final class PylonBlock extends MachineBlock {
	private static final VoxelShape SHAPE = Block.createCuboidShape(3, 0, 3, 13, 16, 13);

	public PylonBlock(AbstractBlock.Settings settings) {
		super(settings);
	}

	@Override
	public VoxelShape getOutlineShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
		return SHAPE;
	}

	public static boolean isPylon(BlockState state) {
		return state.getBlock() instanceof PylonBlock;
	}

	public static boolean superconducting(BlockState state) {
		return PylonLinks.superconducting(Registries.BLOCK.getId(state.getBlock()).getPath());
	}

	/** A pylon has no screen: it says what it is strung to. */
	@Override
	public ActionResult onUse(BlockState state, World world, BlockPos pos, PlayerEntity player, Hand hand, BlockHitResult hit) {
		if (player.getStackInHand(hand).isOf(dev.rackcraft.RcItems.ITEMS.get("pylon_linker"))) return ActionResult.PASS;
		if (world instanceof ServerWorld serverWorld && !world.isClient) {
			int spans = PylonLinks.get(serverWorld).count(pos);
			player.sendMessage(Text.literal(spans == 0 ? "Not strung to anything. Use a Pylon Linker" : "Strung to " + spans + " other pylon" + (spans == 1 ? "" : "s"))
					.formatted(Formatting.GRAY), true);
		}
		return ActionResult.success(world.isClient);
	}

	@Override
	public void onStateReplaced(BlockState state, World world, BlockPos pos, BlockState newState, boolean moved) {
		if (!state.isOf(newState.getBlock()) && world instanceof ServerWorld serverWorld) {
			PylonLinks.get(serverWorld).unlink(pos);
			NetworkManager.get(serverWorld).markDirty();
		}
		super.onStateReplaced(state, world, pos, newState, moved);
	}
}
