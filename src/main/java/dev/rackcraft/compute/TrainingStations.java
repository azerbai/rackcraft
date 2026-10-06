package dev.rackcraft.compute;

import dev.rackcraft.RcItems;
import dev.rackcraft.block.MachineBlock;
import dev.rackcraft.block.MachineBlockEntity;
import java.util.List;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ai.brain.MemoryModuleType;
import net.minecraft.entity.ai.brain.WalkTarget;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.village.VillagerProfession;

/**
 * Where training data comes from. Both machines hold paper in slot 0, their tool in slot 1 and their
 * output in slot 2.
 *
 * <p>Kids' Art Table: baby villagers nearby are lured over to draw in crayon. Each kid draws once every
 * {@link #SECONDS_PER_DRAWING} seconds; four drawings make an Art Aggregate. Kids at a stocked table don't
 * grow up. A box of Crayons lasts 64 drawings.
 *
 * <p>Scriptorium Desk: a librarian shackled to it (use Shackles on one nearby) writes a Text Corpus every
 * {@link #SECONDS_PER_CORPUS} seconds, from two paper; an ink sac lasts four corpora. The librarian can't
 * wander off and is not happy about it.
 */
public final class TrainingStations {
	public static final String SHACKLED_TAG = "rackcraft_shackled";
	public static final double SECONDS_PER_DRAWING = 20;
	public static final int DRAWINGS_PER_AGGREGATE = 4;
	public static final double SECONDS_PER_CORPUS = 30;
	public static final int CORPORA_PER_INK = 4;
	private static final int MAX_KIDS = 4;

	private TrainingStations() {}

	public static void step(ServerWorld world, List<MachineBlockEntity> machines, double dt) {
		for (MachineBlockEntity machine : machines) {
			switch (machine.blockId()) {
				case "art_table" -> artTable(world, machine, dt);
				case "writing_desk" -> desk(world, machine, dt);
				default -> {}
			}
		}
	}

	public static boolean active(MachineBlockEntity machine) {
		return machine.workers() > 0 && !machine.getStack(0).isEmpty() && !machine.getStack(1).isEmpty();
	}

	private static void artTable(ServerWorld world, MachineBlockEntity table, double dt) {
		BlockPos pos = table.getPos();
		boolean stocked = table.getStack(0).isOf(Items.PAPER) && table.getStack(1).isOf(RcItems.ITEMS.get("crayons"))
				&& hasRoom(table, RcItems.ITEMS.get("art_aggregate"));
		List<VillagerEntity> kids = world.getEntitiesByClass(VillagerEntity.class, new Box(pos).expand(16, 4, 16),
				villager -> villager.isAlive() && villager.isBaby());
		int drawing = 0;
		for (VillagerEntity kid : kids) {
			boolean close = kid.squaredDistanceTo(Vec3d.ofCenter(pos)) <= 9;
			if (!stocked) continue;
			if (!close) {
				// "Free crayons!" Kids come over by themselves.
				kid.getBrain().remember(MemoryModuleType.WALK_TARGET, new WalkTarget(pos, 0.6f, 1));
				continue;
			}
			if (drawing >= MAX_KIDS) continue;
			drawing++;
			// Kids busy drawing don't grow up.
			if (kid.getBreedingAge() > -2400) kid.setBreedingAge(-2400);
			kid.getLookControl().lookAt(pos.getX() + 0.5, pos.getY() + 1, pos.getZ() + 0.5);
			if (world.random.nextInt(8) == 0) {
				world.spawnParticles(ParticleTypes.HAPPY_VILLAGER, kid.getX(), kid.getY() + 1.0, kid.getZ(), 2, 0.2, 0.2, 0.2, 0);
			}
		}
		table.setWorkers(drawing);
		if (drawing == 0 || !stocked) return;
		double progress = table.workProgress() + dt * drawing / SECONDS_PER_DRAWING;
		while (progress >= 1 && table.getStack(0).isOf(Items.PAPER) && table.getStack(1).isOf(RcItems.ITEMS.get("crayons"))) {
			progress -= 1;
			table.getStack(0).decrement(1);
			ItemStack crayons = table.getStack(1);
			crayons.setDamage(crayons.getDamage() + 1);
			if (crayons.getDamage() >= crayons.getMaxDamage()) {
				table.setStack(1, ItemStack.EMPTY);
				world.playSound(null, pos, SoundEvents.ENTITY_ITEM_BREAK, SoundCategory.BLOCKS, 0.6f, 1.4f);
			}
			table.setItemsMade(table.itemsMade() + 1);
			world.playSound(null, pos, SoundEvents.UI_CARTOGRAPHY_TABLE_TAKE_RESULT, SoundCategory.BLOCKS, 0.5f, 1.3f);
			if (table.itemsMade() >= DRAWINGS_PER_AGGREGATE) {
				table.setItemsMade(table.itemsMade() - DRAWINGS_PER_AGGREGATE);
				output(table, RcItems.ITEMS.get("art_aggregate"));
			}
		}
		table.setWorkProgress(progress);
		table.markDirty();
	}

	private static void desk(ServerWorld world, MachineBlockEntity desk, double dt) {
		VillagerEntity scribe = boundLibrarian(world, desk);
		desk.setWorkers(scribe == null ? 0 : 1);
		if (scribe == null) return;
		// Tethered: the shackles reach as far as the front of the desk.
		Direction facing = desk.getCachedState().get(MachineBlock.FACING);
		Vec3d anchor = Vec3d.ofBottomCenter(desk.getPos().offset(facing));
		if (scribe.squaredDistanceTo(anchor) > 2.25) {
			scribe.getNavigation().stop();
			scribe.requestTeleport(anchor.x, anchor.y, anchor.z);
		}
		scribe.getLookControl().lookAt(desk.getPos().getX() + 0.5, desk.getPos().getY() + 1, desk.getPos().getZ() + 0.5);
		boolean stocked = desk.getStack(0).isOf(Items.PAPER) && desk.getStack(0).getCount() >= 2
				&& desk.getStack(1).isOf(Items.INK_SAC) && hasRoom(desk, RcItems.ITEMS.get("text_corpus"));
		if (!stocked) return;
		BlockPos pos = desk.getPos();
		world.spawnParticles(ParticleTypes.ENCHANT, pos.getX() + 0.5, pos.getY() + 1.4, pos.getZ() + 0.5, 3, 0.3, 0.3, 0.3, 0.5);
		if (world.random.nextInt(40) == 0) {
			world.playSound(null, scribe.getBlockPos(), world.random.nextBoolean() ? SoundEvents.ENTITY_VILLAGER_NO
					: SoundEvents.ENTITY_VILLAGER_WORK_LIBRARIAN, SoundCategory.NEUTRAL, 0.7f, 1.0f);
		}
		double progress = desk.workProgress() + dt / SECONDS_PER_CORPUS;
		while (progress >= 1 && desk.getStack(0).getCount() >= 2 && desk.getStack(1).isOf(Items.INK_SAC)) {
			progress -= 1;
			desk.getStack(0).decrement(2);
			desk.setToolUses(desk.toolUses() + 1);
			if (desk.toolUses() >= CORPORA_PER_INK) {
				desk.setToolUses(0);
				desk.getStack(1).decrement(1);
			}
			world.playSound(null, pos, SoundEvents.ITEM_BOOK_PAGE_TURN, SoundCategory.BLOCKS, 0.7f, 1.0f);
			output(desk, RcItems.ITEMS.get("text_corpus"));
		}
		desk.setWorkProgress(progress);
		desk.markDirty();
	}

	/** The desk's shackled librarian, if it is still alive and nearby; otherwise the desk lets go. */
	private static VillagerEntity boundLibrarian(ServerWorld world, MachineBlockEntity desk) {
		if (desk.boundVillager() == null) return null;
		Entity entity = world.getEntity(desk.boundVillager());
		if (entity == null) return null; // not loaded right now
		if (!(entity instanceof VillagerEntity villager) || !villager.isAlive()
				|| villager.squaredDistanceTo(Vec3d.ofCenter(desk.getPos())) > 64 * 64) {
			desk.setBoundVillager(null);
			if (entity != null) entity.removeScoreboardTag(SHACKLED_TAG);
			return null;
		}
		return villager.getVillagerData().getProfession() == VillagerProfession.LIBRARIAN ? villager : null;
	}

	private static boolean hasRoom(MachineBlockEntity machine, Item output) {
		ItemStack slot = machine.getStack(2);
		return slot.isEmpty() || slot.isOf(output) && slot.getCount() < slot.getMaxCount();
	}

	private static void output(MachineBlockEntity machine, Item item) {
		ItemStack slot = machine.getStack(2);
		if (slot.isEmpty()) machine.setStack(2, new ItemStack(item));
		else if (slot.isOf(item) && slot.getCount() < slot.getMaxCount()) slot.increment(1);
	}

	/**
	 * Shackles a librarian to the nearest free Scriptorium Desk within six blocks. Returns a message for the
	 * player, or null if it worked.
	 */
	public static String shackle(ServerWorld world, VillagerEntity villager, List<MachineBlockEntity> machines) {
		if (villager.isBaby()) return "That's a child. Children go to the Kids' Art Table.";
		if (villager.getCommandTags().contains(SHACKLED_TAG)) return "This librarian is already shackled.";
		if (villager.getVillagerData().getProfession() != VillagerProfession.LIBRARIAN) {
			return "Only librarians can write training text. This one just looks confused.";
		}
		MachineBlockEntity desk = machines.stream()
				.filter(machine -> machine.blockId().equals("writing_desk") && machine.boundVillager() == null
						&& machine.getPos().getSquaredDistance(villager.getPos()) <= 36)
				.min(java.util.Comparator.comparingDouble(machine -> machine.getPos().getSquaredDistance(villager.getPos())))
				.orElse(null);
		if (desk == null) return "Place a free Scriptorium Desk within six blocks first.";
		desk.setBoundVillager(villager.getUuid());
		villager.addCommandTag(SHACKLED_TAG);
		// A librarian with experience keeps the profession even away from a lectern.
		if (villager.getExperience() <= 0) villager.setExperience(1);
		villager.setPersistent();
		world.playSound(null, villager.getBlockPos(), SoundEvents.BLOCK_CHAIN_PLACE, SoundCategory.NEUTRAL, 1f, 0.8f);
		world.playSound(null, villager.getBlockPos(), SoundEvents.ENTITY_VILLAGER_NO, SoundCategory.NEUTRAL, 1f, 0.9f);
		return null;
	}

	/** Frees a shackled villager. Returns true if it was shackled. */
	public static boolean release(ServerWorld world, VillagerEntity villager, List<MachineBlockEntity> machines) {
		if (!villager.getCommandTags().contains(SHACKLED_TAG)) return false;
		villager.removeScoreboardTag(SHACKLED_TAG);
		for (MachineBlockEntity machine : machines) {
			if (villager.getUuid().equals(machine.boundVillager())) machine.setBoundVillager(null);
		}
		world.playSound(null, villager.getBlockPos(), SoundEvents.BLOCK_CHAIN_BREAK, SoundCategory.NEUTRAL, 1f, 1f);
		world.playSound(null, villager.getBlockPos(), SoundEvents.ENTITY_VILLAGER_CELEBRATE, SoundCategory.NEUTRAL, 1f, 1f);
		return true;
	}
}
