package dev.rackcraft.block;

import net.minecraft.block.AbstractBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.BooleanProperty;

/**
 * A machine that forms cube multiblocks (Modular Reactors, Battery Banks and the processing machines). While it is
 * part of a whole 2x2x2 to 10x10x10 cube, FORMED switches every face to the array's casing (SCALE picks the casing for
 * bigger cubes). In cubes that make items,
 * PORT marks the one core (the bottom north-west corner) where the whole cube's products gather.
 */
public class ArrayMachineBlock extends MachineBlock {
	public static final BooleanProperty FORMED = BooleanProperty.of("formed");
	public static final BooleanProperty PORT = BooleanProperty.of("port");
	/** Which casing a formed cube wears: 0 up to 5x5x5, 1 for 6x6x6 to 9x9x9, 2 for a full 10x10x10. */
	public static final net.minecraft.state.property.IntProperty SCALE = net.minecraft.state.property.IntProperty.of("scale", 0, 2);

	public static int scaleFor(int edge) {
		return edge >= 10 ? 2 : edge >= 6 ? 1 : 0;
	}

	public ArrayMachineBlock(AbstractBlock.Settings settings) {
		super(settings);
		setDefaultState(getDefaultState().with(FORMED, false).with(PORT, false).with(SCALE, 0));
	}

	@Override
	protected void appendProperties(StateManager.Builder<Block, BlockState> builder) {
		super.appendProperties(builder);
		builder.add(FORMED, PORT, SCALE);
	}
}
