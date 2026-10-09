package dev.rackcraft.world.structure;

import dev.rackcraft.Rackcraft;
import dev.rackcraft.RcBlocks;
import dev.rackcraft.entity.GuardEntity;
import dev.rackcraft.entity.RcEntities;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.block.BedBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.DoorBlock;
import net.minecraft.block.LanternBlock;
import net.minecraft.block.enums.BedPart;
import net.minecraft.block.enums.DoubleBlockHalf;
import net.minecraft.entity.EntityType;
import net.minecraft.state.property.Properties;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Direction;

/**
 * The Military Base (Forward Operating Base Gigabyte) and the hostile data centres. The base is 90 x 90: a fence with a
 * gate, six turrets on a power ring that runs back to a generator shed outside the south wall, a barracks, a motor pool,
 * an armoury and a command bunker with a vault in it. The gate and the vault are Blast Doors: held shut by a live cable,
 * opened by cutting it or by an EMP. Hostile data centres are ordinary ruins with scavengers in them.
 */
public final class MilitaryBase {
	public static final int SIZE = 90;
	public static final Identifier ARMOURY_LOOT = Rackcraft.id("chests/military/armoury");
	public static final Identifier VAULT_LOOT = Rackcraft.id("chests/military/vault");
	public static final Identifier COMMAND_LOOT = Rackcraft.id("chests/military/command");
	public static final Identifier STASH_LOOT = Rackcraft.id("chests/data_center/scavenger_stash");
	/** Elite names, picked by position: each is a bounty target if the player has found it. */
	public static final List<String> COMMANDERS = List.of("Colonel Dongle", "Major Cache-Miss", "General Fanout", "Brigadier Bottleneck");
	public static final List<String> BOSSES = List.of("Big Pipe Pete", "Gutter Gary", "Hoodie Hank", "Rack Raider Ron");

	private static final Direction N = Direction.NORTH;
	private static final Direction S = Direction.SOUTH;
	private static final Direction E = Direction.EAST;
	private static final Direction W = Direction.WEST;

	private MilitaryBase() {}

	private static BlockState b(Block block) { return block.getDefaultState(); }

	private static BlockState door(Block block, Direction facing, DoubleBlockHalf half) {
		return block.getDefaultState().with(DoorBlock.FACING, facing).with(DoorBlock.HALF, half);
	}

	private static void ironDoor(Site s, int x, int z, Direction facing) {
		s.set(x, 1, z, door(Blocks.IRON_DOOR, facing, DoubleBlockHalf.LOWER));
		s.set(x, 2, z, door(Blocks.IRON_DOOR, facing, DoubleBlockHalf.UPPER));
	}

	private static void bed(Site s, int x, int z, Direction head) {
		s.set(x, 1, z, b(Blocks.GRAY_BED).with(BedBlock.FACING, head).with(BedBlock.PART, BedPart.FOOT));
		s.set(x + head.getOffsetX(), 1, z + head.getOffsetZ(), b(Blocks.GRAY_BED).with(BedBlock.FACING, head).with(BedBlock.PART, BedPart.HEAD));
	}

	private static BlockState lantern() { return b(Blocks.LANTERN).with(LanternBlock.HANGING, true); }

	/** A hollow building: concrete floor, walls, roof. Interior stays air (the pad already cleared it). */
	private static void hall(Site s, int x0, int z0, int x1, int z1, int h, BlockState wall, BlockState roof) {
		s.fill(x0, 0, z0, x1, 0, z1, b(Blocks.SMOOTH_STONE));
		s.walls(x0, 1, z0, x1, h, z1, wall);
		s.fill(x0, h + 1, z0, x1, h + 1, z1, roof);
	}

	private static GuardEntity soldier(Site s, int x, int z) { return s.guard(x, 1, z, RcEntities.SOLDIER, null); }

	private static GuardEntity robot(Site s, int x, int z) { return s.guard(x, 1, z, RcEntities.SECURITY_ROBOT, null); }

	public static void build(Site s) {
		s.pad(0, 0, SIZE - 1, SIZE - 1, 13, 10, b(Blocks.STONE));
		s.fill(0, 0, 0, SIZE - 1, 0, SIZE - 1, (x, y, z) -> {
			boolean inside = x >= 8 && x <= 81 && z >= 8 && z <= 81;
			int roll = s.hash(x, y, z, 1) % 10;
			if (inside) return roll < 2 ? b(Blocks.CRACKED_STONE_BRICKS) : roll < 6 ? b(Blocks.STONE_BRICKS) : b(Blocks.SMOOTH_STONE);
			return roll < 3 ? b(Blocks.GRAVEL) : roll < 5 ? b(Blocks.COARSE_DIRT) : b(Blocks.PACKED_MUD);
		});

		// ---- the fence: stone bricks with iron bars on top, a gate in the south wall, a turret on each side of it.
		s.walls(8, 1, 8, 81, 3, 81, (x, y, z) -> s.weathered(x, y, z));
		s.walls(8, 4, 8, 81, 4, 81, (x, y, z) -> {
			boolean alongX = z == 8 || z == 81;
			BlockState bars = b(Blocks.IRON_BARS);
			return alongX ? bars.with(Properties.EAST, true).with(Properties.WEST, true) : bars.with(Properties.NORTH, true).with(Properties.SOUTH, true);
		});
		s.fill(42, 1, 81, 47, 4, 81, b(Blocks.IRON_BLOCK));
		s.fill(43, 1, 81, 46, 3, 81, b(RcBlocks.get("blast_door")));
		s.sign(40, 2, 82, S, "RESTRICTED", "PROPERTY OF", "THE MILITARY", "(NOT YOURS)");

		// ---- power: a generator shed outside the south wall, one line in through the wall, a ring just inside the fence.
		hall(s, 59, 85, 65, 89, 3, b(Blocks.BRICKS), b(Blocks.STONE_BRICK_SLAB));
		s.fill(62, 1, 85, 62, 2, 85, b(Blocks.AIR));
		s.machine(62, 1, 88, "utility_intake", N);
		s.sign(61, 2, 84, N, "GENERATOR", "(please do not", "cut the cable)");
		s.cableRun(62, 1, 82, 62, 1, 87, "power_cable");
		s.cable(62, 1, 81, "power_cable", false);
		s.cableRun(9, 1, 80, 80, 1, 80, "power_cable");
		s.cableRun(9, 1, 9, 80, 1, 9, "power_cable");
		s.cableRun(9, 1, 9, 9, 1, 80, "power_cable");
		s.cableRun(80, 1, 9, 80, 1, 80, "power_cable");
		s.cableRun(43, 2, 80, 46, 3, 80, "power_cable");
		// Corner towers: a 3 x 3 stack with a laser turret on top, fed by a cable up the fence side.
		int[][] towers = {{10, 10, 9}, {79, 10, 80}, {10, 79, 9}, {79, 79, 80}};
		for (int[] tower : towers) {
			int tx = tower[0];
			int tz = tower[1];
			int x0 = tx == 10 ? 10 : 77;
			int z0 = tz == 10 ? 10 : 77;
			s.fill(x0, 1, z0, x0 + 2, 5, z0 + 2, b(Blocks.STONE_BRICKS));
			s.fill(x0, 5, z0, x0 + 2, 5, z0 + 2, b(Blocks.POLISHED_ANDESITE));
			Direction face = tx == 10 ? (tz == 10 ? N : S) : (tz == 10 ? N : S);
			s.machine(tx, 6, tz, "laser_sentry", face);
			setSentryBase(s, tx, 6, tz);
			s.cableRun(tower[2], 2, tz, tower[2], 6, tz, "power_cable");
		}
		for (int gateTurret : new int[] {41, 48}) {
			s.machine(gateTurret, 4, 81, "laser_sentry", S);
			setSentryBase(s, gateTurret, 4, 81);
			s.cableRun(gateTurret, 2, 80, gateTurret, 4, 80, "power_cable");
		}

		// ---- barracks
		hall(s, 14, 14, 34, 28, 4, b(Blocks.GRAY_CONCRETE), b(Blocks.STONE_BRICK_SLAB));
		ironDoor(s, 24, 28, S);
		for (int x = 16; x <= 32; x += 4) bed(s, x, 15, S);
		for (int x = 16; x <= 32; x += 4) s.set(x + 1, 1, 15, b(Blocks.BARREL));
		s.set(24, 4, 21, lantern());
		s.set(18, 4, 21, lantern());
		s.set(30, 4, 21, lantern());
		soldier(s, 18, 23);
		soldier(s, 30, 23);
		soldier(s, 22, 19);
		soldier(s, 26, 19);

		// ---- motor pool: an open-sided shed with three jeeps
		s.fill(50, 0, 14, 76, 0, 30, b(Blocks.POLISHED_ANDESITE));
		for (int x = 50; x <= 76; x += 13) for (int z = 14; z <= 30; z += 16) s.fill(x, 1, z, x, 4, z, b(Blocks.IRON_BARS));
		s.fill(50, 5, 14, 76, 5, 30, b(Blocks.SMOOTH_STONE_SLAB));
		for (int jeep = 0; jeep < 3; jeep++) {
			int jx = 54 + jeep * 8;
			s.fill(jx, 1, 20, jx + 2, 1, 23, b(Blocks.IRON_BLOCK));
			s.fill(jx + 1, 2, 20, jx + 1, 2, 21, b(Blocks.GLASS));
			s.fill(jx, 2, 22, jx + 2, 2, 23, b(Blocks.GRAY_CONCRETE));
			s.set(jx, 0, 19, b(Blocks.BLACK_CONCRETE));
		}
		s.chest(52, 1, 28, S, DataCenterLayouts.COMMON_LOOT);
		s.set(63, 5, 24, lantern());
		soldier(s, 58, 27);
		soldier(s, 70, 27);
		robot(s, 64, 27);

		// ---- armoury: three loot chests behind a door, one soldier and one robot who disagree with you
		hall(s, 14, 48, 30, 62, 4, b(Blocks.GRAY_CONCRETE), b(Blocks.STONE_BRICK_SLAB));
		ironDoor(s, 30, 55, E);
		s.chest(15, 1, 50, E, ARMOURY_LOOT);
		s.chest(15, 1, 60, E, ARMOURY_LOOT);
		s.chest(21, 1, 49, S, ARMOURY_LOOT);
		s.set(22, 4, 55, lantern());
		soldier(s, 24, 55);
		robot(s, 20, 57);

		// ---- command bunker: reinforced walls, an operations room and the vault
		hall(s, 36, 36, 54, 54, 6, b(Blocks.POLISHED_BLACKSTONE_BRICKS), b(Blocks.DEEPSLATE_BRICKS));
		ironDoor(s, 45, 54, S);
		s.chest(40, 1, 50, N, COMMAND_LOOT);
		s.chest(41, 1, 50, N, DataCenterLayouts.COMMON_LOOT);
		s.set(45, 6, 46, lantern());
		s.set(40, 6, 46, lantern());
		// The vault: unbreakable walls, one door of two by two Blast Doors held by a cable that runs back to its own intake.
		s.fill(45, 0, 37, 51, 5, 41, b(Blocks.REINFORCED_DEEPSLATE));
		s.fill(46, 1, 38, 50, 4, 40, b(Blocks.AIR));
		s.fill(47, 1, 41, 48, 2, 41, b(RcBlocks.get("blast_door")));
		s.cableRun(47, 1, 42, 48, 2, 42, "power_cable");
		s.cableRun(44, 1, 42, 46, 1, 42, "power_cable");
		s.cableRun(44, 1, 43, 44, 1, 44, "power_cable");
		s.machine(44, 1, 45, "utility_intake", N);
		s.chest(46, 1, 38, S, VAULT_LOOT);
		s.chest(48, 1, 38, S, VAULT_LOOT);
		s.chest(50, 1, 38, S, VAULT_LOOT);
		s.set(48, 4, 39, lantern());
		s.sign(46, 2, 42, S, "VAULT", "Door held by", "the cable behind it");
		s.guard(45, 1, 49, RcEntities.SOLDIER, COMMANDERS.get(Math.floorMod(s.hash(0, 0, 0, 77), COMMANDERS.size())));
		soldier(s, 40, 44);
		soldier(s, 52, 48);
		robot(s, 38, 52);
		robot(s, 51, 52);

		// ---- the yard
		soldier(s, 30, 40);
		soldier(s, 62, 44);
		soldier(s, 62, 62);
		soldier(s, 28, 72);
		robot(s, 40, 76);
		robot(s, 50, 76);
	}

	/** The base's turrets follow the guards' rules (set on the block entity; mode 3). */
	private static void setSentryBase(Site s, int x, int y, int z) {
		var machine = s.machineAt(x, y, z);
		if (machine != null) machine.setSentryMode(3);
	}

	/**
	 * A hostile data centre: the ordinary ruin, with scavengers stripping the racks for parts and a stash they have been
	 * piling up. Candidates are hashed spots, so neighbouring chunks agree; only open standing spots in this chunk spawn.
	 */
	public static Consumer<Site> hostile(Consumer<Site> ruin, int width, int depth, int scavengers) {
		return s -> {
			ruin.accept(s);
			int spawned = 0;
			for (int attempt = 0; attempt < scavengers * 6 && spawned < scavengers; attempt++) {
				int x = 1 + s.hash(attempt, 0, 0, 901) % (width - 2);
				int z = 1 + s.hash(attempt, 0, 1, 902) % (depth - 2);
				if (!s.open(x, 1, z)) continue;
				boolean boss = attempt == 0 && s.hash(0, 0, 0, 903) % 3 == 0;
				s.guard(x, 1, z, RcEntities.SCAVENGER, boss ? BOSSES.get(s.hash(0, 0, 0, 904) % BOSSES.size()) : null);
				spawned++;
			}
			for (int attempt = 0; attempt < 24; attempt++) {
				int x = 1 + s.hash(attempt, 0, 0, 911) % (width - 2);
				int z = 1 + s.hash(attempt, 0, 1, 912) % (depth - 2);
				if (!s.open(x, 1, z)) continue;
				s.chest(x, 1, z, Direction.fromHorizontal(s.hash(x, 0, z, 913) % 4), STASH_LOOT);
				if (attempt >= 2) break;
			}
		};
	}
}
