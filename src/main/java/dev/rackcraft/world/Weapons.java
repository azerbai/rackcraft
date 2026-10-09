package dev.rackcraft.world;

import dev.rackcraft.RackcraftConfig;
import dev.rackcraft.RcItems;
import dev.rackcraft.block.MachineBlockEntity;
import dev.rackcraft.compute.Research;
import dev.rackcraft.compute.ResearchLab;
import dev.rackcraft.entity.GuardEntity;
import dev.rackcraft.sim.NetKind;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import net.minecraft.block.AbstractFireBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.passive.TameableEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.particle.ParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.registry.Registries;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;

/**
 * The energy and hydrogen weapons. Each has a heat meter (0 to 100, in the item's NBT, cooling on its own and much faster
 * with a Coolant Pack in the pack) and a store of ammo units it tops up from the owner's inventory or a Wireless Terminal.
 * At 100 heat it locks out for a few seconds with a hiss. Fire methods take the time explicitly so the self-test can
 * drive them without the world clock moving.
 */
public final class Weapons {
	public enum Kind {
		FLAMETHROWER("hydrogen_flamethrower", "directed_energy"),
		ARC_COIL("arc_coil", "directed_energy"),
		RAILGUN("railgun", "directed_energy"),
		PLASMA_RIFLE("plasma_rifle", "plasma_weapons"),
		LANCE_LASER("lance_laser", "plasma_weapons");

		public final String id;
		public final String gate;

		Kind(String id, String gate) {
			this.id = id;
			this.gate = gate;
		}

		public static Kind of(String id) {
			for (Kind kind : values()) if (kind.id.equals(id)) return kind;
			return null;
		}
	}

	private static final String HEAT = "Heat";
	private static final String HEAT_AT = "HeatAt";
	private static final String LOCK = "LockUntil";
	public static final String AMMO = "Ammo";
	public static final String POWER = "Power";

	private Weapons() {}

	private static RackcraftConfig.Weapons cfg() { return RackcraftConfig.values.weapons; }

	// ---------------------------------------------------------------- heat

	/** Current heat after cooling up to {@code now}, written back. */
	public static double settle(ItemStack stack, long now, boolean packed) {
		NbtCompound nbt = stack.getOrCreateNbt();
		double heat = nbt.getDouble(HEAT);
		long at = nbt.contains(HEAT_AT) ? nbt.getLong(HEAT_AT) : now;
		double seconds = Math.max(0, now - at) / 20.0;
		heat = Math.max(0, heat - seconds * (packed ? cfg().coolPackPerSecond : cfg().coolPerSecond));
		nbt.putDouble(HEAT, heat);
		nbt.putLong(HEAT_AT, now);
		return heat;
	}

	/** Heat for display, without writing anything. */
	public static int heatPercent(ItemStack stack, long now) {
		NbtCompound nbt = stack.getNbt();
		if (nbt == null) return 0;
		double seconds = Math.max(0, now - (nbt.contains(HEAT_AT) ? nbt.getLong(HEAT_AT) : now)) / 20.0;
		return (int) Math.round(Math.max(0, nbt.getDouble(HEAT) - seconds * cfg().coolPerSecond));
	}

	public static boolean locked(ItemStack stack, long now) {
		NbtCompound nbt = stack.getNbt();
		return nbt != null && nbt.contains(LOCK) && now < nbt.getLong(LOCK);
	}

	/** Adds heat; returns true if that tipped it over into a lockout. */
	private static boolean addHeat(ItemStack stack, long now, double amount, ServerPlayerEntity player) {
		NbtCompound nbt = stack.getOrCreateNbt();
		double heat = nbt.getDouble(HEAT) + amount;
		if (heat < 100) {
			nbt.putDouble(HEAT, heat);
			return false;
		}
		// Lockout: vent it, wait, and come back at 60 so the next shot is not an instant relapse.
		nbt.putLong(LOCK, now + cfg().lockoutTicks);
		nbt.putDouble(HEAT, 60);
		nbt.putLong(HEAT_AT, now + cfg().lockoutTicks);
		ServerWorld world = player.getServerWorld();
		world.playSound(null, player.getBlockPos(), SoundEvents.BLOCK_FIRE_EXTINGUISH, SoundCategory.PLAYERS, 0.9f, 0.7f);
		world.spawnParticles(ParticleTypes.LARGE_SMOKE, player.getX(), player.getEyeY() - 0.3, player.getZ(), 10, 0.2, 0.1, 0.2, 0.02);
		player.sendMessage(Text.literal("Overheated. It hisses at you.").formatted(Formatting.RED), true);
		return true;
	}

	private static boolean hasPack(ServerPlayerEntity player) {
		Item pack = RcItems.ITEMS.get("coolant_pack");
		Inventory inventory = player.getInventory();
		for (int slot = 0; slot < inventory.size(); slot++) if (inventory.getStack(slot).isOf(pack)) return true;
		return false;
	}

	private static void wearPack(ServerPlayerEntity player) {
		Item pack = RcItems.ITEMS.get("coolant_pack");
		Inventory inventory = player.getInventory();
		for (int slot = 0; slot < inventory.size(); slot++) {
			ItemStack stack = inventory.getStack(slot);
			if (!stack.isOf(pack)) continue;
			if (!player.isCreative()) stack.damage(1, player, entity -> {});
			return;
		}
	}

	// ---------------------------------------------------------------- ammo

	private static boolean available(ServerPlayerEntity player, NbtCompound nbt, String key, String item) {
		return nbt.getInt(key) > 0 || BuildStock.of(player).count(RcItems.ITEMS.get(item)) > 0;
	}

	private static void spend(ServerPlayerEntity player, NbtCompound nbt, String key, String item, int unitsPerItem) {
		if (nbt.getInt(key) <= 0 && BuildStock.of(player).take(RcItems.ITEMS.get(item), 1) >= 1) nbt.putInt(key, unitsPerItem);
		nbt.putInt(key, Math.max(0, nbt.getInt(key) - 1));
	}

	/** Units of ammo and of power left in the weapon, for its tooltip. */
	public static int stored(ItemStack stack, String key) {
		return stack.getNbt() == null ? 0 : stack.getNbt().getInt(key);
	}

	// ---------------------------------------------------------------- firing

	/**
	 * One use of a weapon: a shot, a bolt, a burst of flame or a tick of beam. Returns null if it fired, or the reason
	 * it did not ("Out of Battery Cells", "Overheated"...). {@code charge} (0 to 1) only matters to the Railgun.
	 */
	public static String fire(Kind kind, ServerPlayerEntity player, ItemStack stack, long now, double charge) {
		ServerWorld world = player.getServerWorld();
		if (!ResearchLab.get(world).done(kind.gate)) return "Inert until " + Research.get(kind.gate).name() + " is researched";
		NbtCompound nbt = stack.getOrCreateNbt();
		if (locked(stack, now)) return "Overheated";
		boolean packed = hasPack(player);
		double heat = settle(stack, now, packed);
		RackcraftConfig.Weapons c = cfg();
		double add;
		switch (kind) {
			case FLAMETHROWER -> {
				if (!available(player, nbt, AMMO, "hydrogen_canister")) return "Out of Hydrogen Canisters";
				spend(player, nbt, AMMO, "hydrogen_canister", c.flameSecondsPerCanister * 10);
				flame(world, player);
				add = c.flameHeatPerStep;
			}
			case ARC_COIL -> {
				if (!available(player, nbt, POWER, "battery_cell")) return "Out of Battery Cells";
				spend(player, nbt, POWER, "battery_cell", c.arcShotsPerCell);
				arc(world, player);
				add = c.arcHeat;
			}
			case RAILGUN -> {
				if (BuildStock.of(player).count(RcItems.ITEMS.get("steel_slug")) < 1) return "Out of Steel Slugs";
				if (!available(player, nbt, POWER, "battery_cell")) return "Out of Battery Cells";
				BuildStock.of(player).take(RcItems.ITEMS.get("steel_slug"), 1);
				spend(player, nbt, POWER, "battery_cell", c.railShotsPerCell);
				rail(world, player, Math.max(0.25, Math.min(1, charge)));
				add = c.railHeat * (0.5 + 0.5 * Math.max(0.25, Math.min(1, charge)));
			}
			case PLASMA_RIFLE -> {
				if (!available(player, nbt, AMMO, "hydrogen_canister")) return "Out of Hydrogen Canisters";
				if (!available(player, nbt, POWER, "battery_cell")) return "Out of Battery Cells";
				spend(player, nbt, AMMO, "hydrogen_canister", c.plasmaMagazine);
				spend(player, nbt, POWER, "battery_cell", c.plasmaShotsPerCell);
				plasma(world, player);
				add = c.plasmaHeat;
			}
			case LANCE_LASER -> {
				if (!available(player, nbt, POWER, "battery_cell")) return "Out of Battery Cells";
				spend(player, nbt, POWER, "battery_cell", c.lanceSecondsPerCell * 10);
				lance(world, player);
				add = c.lanceHeatPerStep;
			}
			default -> add = 0;
		}
		if (packed && heat > 20) wearPack(player);
		addHeat(stack, now, add, player);
		return null;
	}

	// ---------------------------------------------------------------- geometry

	private static Vec3d eye(PlayerEntity player) { return player.getEyePos(); }

	private static Vec3d look(PlayerEntity player) { return player.getRotationVec(1.0f); }

	private static boolean targetable(Entity shooter, LivingEntity entity) {
		if (!entity.isAlive() || entity.isSpectator() || entity == shooter) return false;
		return !(entity instanceof TameableEntity pet && pet.isTamed() && shooter != null && pet.isOwner((LivingEntity) shooter));
	}

	/** Living things on the segment, nearest first. */
	public static List<LivingEntity> along(ServerWorld world, Entity shooter, Vec3d from, Vec3d to, double inflate) {
		Box box = new Box(from, to).expand(inflate + 1);
		List<LivingEntity> hits = new ArrayList<>();
		for (Entity entity : world.getOtherEntities(shooter, box, e -> e instanceof LivingEntity living && targetable(shooter, living))) {
			Optional<Vec3d> hit = entity.getBoundingBox().expand(inflate).raycast(from, to);
			if (hit.isPresent()) hits.add((LivingEntity) entity);
		}
		hits.sort(Comparator.comparingDouble(entity -> entity.squaredDistanceTo(from)));
		return hits;
	}

	/** Where a ray first meets a solid block, or its far end. */
	public static Vec3d stop(ServerWorld world, Entity shooter, Vec3d from, Vec3d to) {
		HitResult hit = world.raycast(new RaycastContext(from, to, RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, shooter));
		return hit.getType() == HitResult.Type.MISS ? to : hit.getPos();
	}

	private static void beam(ServerWorld world, Vec3d from, Vec3d to, ParticleEffect particle) {
		double length = from.distanceTo(to);
		Vec3d step = to.subtract(from).normalize().multiply(0.8);
		Vec3d at = from.add(step);
		for (double travelled = 0.8; travelled < length; travelled += 0.8) {
			world.spawnParticles(particle, at.x, at.y, at.z, 1, 0, 0, 0, 0);
			at = at.add(step);
		}
	}

	private static DamageSource by(ServerPlayerEntity player) {
		return player.getServerWorld().getDamageSources().playerAttack(player);
	}

	/** Never harm what the mod built: machines, cables, chests and the like are off the menu for scorching. */
	public static boolean protectedBlock(ServerWorld world, BlockPos pos) {
		for (int dx = -1; dx <= 1; dx++) for (int dy = -1; dy <= 1; dy++) for (int dz = -1; dz <= 1; dz++) {
			BlockState state = world.getBlockState(pos.add(dx, dy, dz));
			Block block = state.getBlock();
			if (Registries.BLOCK.getId(block).getNamespace().equals("rackcraft")) return true;
			if (state.hasBlockEntity()) return true;
		}
		return false;
	}

	// ---------------------------------------------------------------- the weapons

	private static void flame(ServerWorld world, ServerPlayerEntity player) {
		RackcraftConfig.Weapons c = cfg();
		Vec3d from = eye(player).add(0, -0.2, 0);
		Vec3d dir = look(player);
		Vec3d end = from.add(dir.multiply(c.flameRange));
		Vec3d wall = stop(world, player, from, end);
		double reach = from.distanceTo(wall);
		for (double d = 1; d < reach; d += 1.0) {
			Vec3d p = from.add(dir.multiply(d));
			world.spawnParticles(ParticleTypes.FLAME, p.x, p.y, p.z, 2, 0.15 + d * 0.04, 0.15 + d * 0.04, 0.15 + d * 0.04, 0.01);
		}
		world.playSound(null, player.getBlockPos(), SoundEvents.ENTITY_BLAZE_SHOOT, SoundCategory.PLAYERS, 0.25f, 0.8f);
		Box box = new Box(from, wall).expand(1.2);
		for (Entity entity : world.getOtherEntities(player, box, e -> e instanceof LivingEntity living && targetable(player, living))) {
			Vec3d to = entity.getBoundingBox().getCenter().subtract(from);
			if (to.length() > reach + 0.5 || to.normalize().dotProduct(dir) < 0.8) continue;
			entity.setOnFireFor(5);
			entity.damage(by(player), (float) c.flameDamage);
		}
		if (!c.blockDamage || !world.getGameRules().getBoolean(net.minecraft.world.GameRules.DO_FIRE_TICK)) return;
		// Cobwebs burn away; a flammable block at the end of the jet catches.
		for (double d = 0.5; d <= reach + 0.5; d += 0.5) {
			BlockPos pos = BlockPos.ofFloored(from.add(dir.multiply(d)));
			if (world.getBlockState(pos).isOf(Blocks.COBWEB)) world.setBlockState(pos, Blocks.AIR.getDefaultState());
		}
		BlockPos hit = BlockPos.ofFloored(wall.add(dir.multiply(0.1)));
		BlockPos above = BlockPos.ofFloored(wall.subtract(dir.multiply(0.2)));
		if (world.getBlockState(hit).isBurnable() && world.getBlockState(above).isAir()
				&& !protectedBlock(world, above) && AbstractFireBlock.canPlaceAt(world, above, player.getHorizontalFacing())) {
			world.setBlockState(above, AbstractFireBlock.getState(world, above));
		}
	}

	private static void arc(ServerWorld world, ServerPlayerEntity player) {
		RackcraftConfig.Weapons c = cfg();
		Vec3d from = eye(player).add(0, -0.2, 0);
		Vec3d dir = look(player);
		Vec3d end = from.add(dir.multiply(6));
		Vec3d wall = stop(world, player, from, end);
		List<LivingEntity> first = along(world, player, from, wall, 0.4);
		world.playSound(null, player.getBlockPos(), SoundEvents.ENTITY_LIGHTNING_BOLT_IMPACT, SoundCategory.PLAYERS, 0.5f, 2.0f);
		if (first.isEmpty()) {
			beam(world, from, wall, ParticleTypes.ELECTRIC_SPARK);
			BlockPos block = BlockPos.ofFloored(wall.add(dir.multiply(0.1)));
			if (world.getBlockEntity(block) instanceof MachineBlockEntity machine) tripNetwork(world, machine);
			return;
		}
		Set<LivingEntity> struck = new HashSet<>();
		LivingEntity current = first.get(0);
		Vec3d previous = from;
		for (int link = 0; link <= c.arcChain && current != null; link++) {
			struck.add(current);
			Vec3d target = current.getBoundingBox().getCenter();
			beam(world, previous, target, ParticleTypes.ELECTRIC_SPARK);
			zapOne(world, player, current, c.arcDamage);
			previous = target;
			LivingEntity chosen = null;
			double best = c.arcChainRange * c.arcChainRange;
			for (Entity candidate : world.getOtherEntities(player, current.getBoundingBox().expand(c.arcChainRange))) {
				if (!(candidate instanceof LivingEntity living) || struck.contains(living) || !targetable(player, living)) continue;
				double distance = candidate.squaredDistanceTo(current);
				if (distance < best) {
					best = distance;
					chosen = living;
				}
			}
			current = chosen;
		}
	}

	/** Lightning on one target: machines and robots take it differently from flesh. */
	private static void zapOne(ServerWorld world, ServerPlayerEntity player, LivingEntity target, double damage) {
		boolean mechanical = target instanceof GuardEntity guard && guard.isMechanical();
		target.damage(by(player), (float) (mechanical ? damage * 2 : damage));
		Emp.freeze(world, target, 20 * 20L);
	}

	/** An arc through a machine trips every breaker on its power network. */
	public static int tripNetwork(ServerWorld world, MachineBlockEntity machine) {
		Set<BlockPos> network = NetworkManager.get(world).component(machine.getPos(), NetKind.POWER);
		int tripped = 0;
		for (MachineBlockEntity other : SimTicker.machines(world)) {
			if (other.blockId().equals("pdu") && (network.contains(other.getPos()) || other == machine) && !other.isTripped()) {
				other.setTripped(true);
				tripped++;
			}
		}
		if (machine.blockId().equals("pdu") && !machine.isTripped()) {
			machine.setTripped(true);
			tripped++;
		}
		world.spawnParticles(ParticleTypes.ELECTRIC_SPARK, machine.getPos().getX() + 0.5, machine.getPos().getY() + 0.5, machine.getPos().getZ() + 0.5, 20, 0.4, 0.4, 0.4, 0.2);
		return tripped;
	}

	private static void rail(ServerWorld world, ServerPlayerEntity player, double charge) {
		RackcraftConfig.Weapons c = cfg();
		Vec3d from = eye(player).add(0, -0.1, 0);
		Vec3d dir = look(player);
		double range = 64;
		// Walk the line: plain blocks absorb it for a while, anything built by the mod or unbreakable stops it dead.
		int solids = 0;
		boolean wasSolid = false;
		double reached = range;
		for (double d = 0.5; d <= range; d += 0.5) {
			BlockPos pos = BlockPos.ofFloored(from.add(dir.multiply(d)));
			BlockState state = world.getBlockState(pos);
			boolean solid = !state.isAir() && state.isOpaque() && !state.getCollisionShape(world, pos).isEmpty();
			if (solid) {
				boolean mod = Registries.BLOCK.getId(state.getBlock()).getNamespace().equals("rackcraft");
				if (mod || state.getHardness(world, pos) < 0 || state.getBlock().getBlastResistance() >= 1200 || state.hasBlockEntity()) {
					reached = d;
					break;
				}
				if (!wasSolid) solids++;
				if (solids > c.railPierceBlocks) {
					reached = d;
					break;
				}
			}
			wasSolid = solid;
		}
		Vec3d to = from.add(dir.multiply(reached));
		beam(world, from, to, ParticleTypes.END_ROD);
		for (LivingEntity target : along(world, player, from, to, 0.35)) {
			target.damage(by(player), (float) (c.railDamage * charge));
		}
		world.playSound(null, player.getBlockPos(), SoundEvents.ENTITY_GENERIC_EXPLODE, SoundCategory.PLAYERS, 0.5f, 1.8f);
		world.playSound(null, player.getBlockPos(), SoundEvents.BLOCK_ANVIL_LAND, SoundCategory.PLAYERS, 0.6f, 0.5f);
		// Recoil: the rifle pushes back harder than it should.
		Vec3d push = dir.multiply(-0.9 * charge);
		player.setVelocity(player.getVelocity().add(push.x, 0.15 * charge, push.z));
		player.velocityModified = true;
	}

	private static void plasma(ServerWorld world, ServerPlayerEntity player) {
		RackcraftConfig.Weapons c = cfg();
		Vec3d from = eye(player).add(0, -0.15, 0);
		Vec3d dir = look(player);
		Vec3d far = from.add(dir.multiply(48));
		Vec3d wall = stop(world, player, from, far);
		List<LivingEntity> direct = along(world, player, from, wall, 0.3);
		Vec3d impact = direct.isEmpty() ? wall : direct.get(0).getBoundingBox().getCenter();
		beam(world, from, impact, ParticleTypes.SOUL_FIRE_FLAME);
		world.spawnParticles(ParticleTypes.SOUL_FIRE_FLAME, impact.x, impact.y, impact.z, 24, 0.5, 0.5, 0.5, 0.05);
		world.spawnParticles(ParticleTypes.FLASH, impact.x, impact.y, impact.z, 1, 0, 0, 0, 0);
		world.playSound(null, player.getBlockPos(), SoundEvents.ENTITY_BLAZE_SHOOT, SoundCategory.PLAYERS, 0.5f, 1.5f);
		Box blast = new Box(impact, impact).expand(c.plasmaSplashRadius);
		for (Entity entity : world.getOtherEntities(player, blast, e -> e instanceof LivingEntity living && targetable(player, living))) {
			double distance = entity.getBoundingBox().getCenter().distanceTo(impact);
			if (distance > c.plasmaSplashRadius) continue;
			double falloff = 1 - 0.6 * distance / c.plasmaSplashRadius;
			entity.damage(by(player), (float) (c.plasmaDamage * falloff));
			entity.setOnFireFor(2);
		}
		if (c.blockDamage && world.getGameRules().getBoolean(net.minecraft.world.GameRules.DO_FIRE_TICK)) {
			BlockPos spot = BlockPos.ofFloored(impact.subtract(dir.multiply(0.2)));
			if (world.getBlockState(spot).isAir() && !protectedBlock(world, spot) && world.getBlockState(spot.down()).isBurnable()) {
				world.setBlockState(spot, AbstractFireBlock.getState(world, spot));
			}
		}
	}

	private static void lance(ServerWorld world, ServerPlayerEntity player) {
		RackcraftConfig.Weapons c = cfg();
		Vec3d from = eye(player).add(0, -0.2, 0);
		Vec3d dir = look(player);
		Vec3d far = from.add(dir.multiply(c.lanceRange));
		Vec3d wall = far;
		// The beam melts the soft stuff in its way and stops at the first thing it can't.
		for (double d = 0.5; d <= c.lanceRange; d += 0.5) {
			Vec3d at = from.add(dir.multiply(d));
			BlockPos pos = BlockPos.ofFloored(at);
			BlockState state = world.getBlockState(pos);
			if (state.isAir() || state.getCollisionShape(world, pos).isEmpty() && !state.isOf(Blocks.COBWEB)) continue;
			if (c.blockDamage && !protectedBlock(world, pos)) {
				if (state.isOf(Blocks.COBWEB) || state.isIn(net.minecraft.registry.tag.BlockTags.ICE)
						|| (c.meltGlass && (state.isOf(Blocks.GLASS) || state.isOf(Blocks.GLASS_PANE)))) {
					world.setBlockState(pos, state.isOf(Blocks.ICE) ? Blocks.WATER.getDefaultState() : Blocks.AIR.getDefaultState());
					continue;
				}
			}
			// Cobweb has no body to stop a beam, so one left standing (beside the mod's blocks) is simply passed through.
			if (state.getCollisionShape(world, pos).isEmpty()) continue;
			wall = at;
			break;
		}
		beam(world, from, wall, ParticleTypes.CRIT);
		beam(world, from, wall, ParticleTypes.END_ROD);
		for (LivingEntity target : along(world, player, from, wall, 0.3)) {
			// Strong against armour: it goes round it.
			target.damage(world.getDamageSources().indirectMagic(player, player), (float) c.lanceDamage);
			target.setOnFireFor(2);
		}
		if (player.age % 6 == 0) world.playSound(null, player.getBlockPos(), SoundEvents.BLOCK_BEACON_AMBIENT, SoundCategory.PLAYERS, 0.6f, 2.0f);
	}
}
