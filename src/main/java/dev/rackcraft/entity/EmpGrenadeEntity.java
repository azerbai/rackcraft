package dev.rackcraft.entity;

import dev.rackcraft.RcItems;
import dev.rackcraft.world.Emp;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.projectile.thrown.ThrownItemEntity;
import net.minecraft.item.Item;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.hit.HitResult;
import net.minecraft.world.World;

/** A thrown EMP Grenade: goes off where it lands. */
public final class EmpGrenadeEntity extends ThrownItemEntity {
	public static EntityType<EmpGrenadeEntity> TYPE;

	public EmpGrenadeEntity(EntityType<? extends EmpGrenadeEntity> type, World world) {
		super(type, world);
	}

	public EmpGrenadeEntity(World world, LivingEntity owner) {
		super(TYPE, owner, world);
	}

	@Override
	protected Item getDefaultItem() { return RcItems.ITEMS.get("emp_grenade"); }

	@Override
	protected void onCollision(HitResult hit) {
		super.onCollision(hit);
		if (!(getWorld() instanceof ServerWorld world)) return;
		Emp.zap(world, getPos());
		discard();
	}
}
