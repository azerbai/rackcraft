package dev.rackcraft.block;

import net.minecraft.block.AbstractBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.EnumProperty;
import net.minecraft.util.StringIdentifiable;

/** A server rack. Its front shows its health from across a hall: blinking amber when slowed, red when stopped. */
public class RackBlock extends MachineBlock {
	public static final EnumProperty<Health> HEALTH = EnumProperty.of("health", Health.class);

	public enum Health implements StringIdentifiable {
		OK, WARN, FAULT;

		@Override
		public String asString() { return name().toLowerCase(java.util.Locale.ROOT); }

		/** What a rack's status looks like on its front panel. Busy and empty racks are fine. */
		public static Health of(RackStatus status) {
			return switch (status) {
				case TRIPPED, NO_POWER, NEEDS_CDU, NEEDS_WATER, OVERHEATED, NO_NETWORK, NEEDS_CRYOSTAT -> FAULT;
				case THROTTLED, NETWORK_LIMITED -> WARN;
				default -> OK;
			};
		}
	}

	public RackBlock(AbstractBlock.Settings settings) {
		super(settings);
		setDefaultState(getDefaultState().with(HEALTH, Health.OK));
	}

	@Override
	protected void appendProperties(StateManager.Builder<Block, BlockState> builder) {
		super.appendProperties(builder);
		builder.add(HEALTH);
	}
}
