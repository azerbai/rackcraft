package dev.rackcraft.block;

import net.minecraft.block.AbstractBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.BooleanProperty;

/**
 * A machine that forms cube multiblocks (Modular Reactors and the nuclear processing machines). While it is part
 * of a whole 2x2x2 to 5x5x5 cube, FORMED switches every face to the array's casing.
 */
public class ArrayMachineBlock extends MachineBlock {
	public static final BooleanProperty FORMED = BooleanProperty.of("formed");

	public ArrayMachineBlock(AbstractBlock.Settings settings) {
		super(settings);
		setDefaultState(getDefaultState().with(FORMED, false));
	}

	@Override
	protected void appendProperties(StateManager.Builder<Block, BlockState> builder) {
		super.appendProperties(builder);
		builder.add(FORMED);
	}
}
