package dev.rackcraft.entity;

import dev.rackcraft.Rackcraft;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricDefaultAttributeRegistry;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricEntityTypeBuilder;
import net.minecraft.entity.EntityDimensions;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.SpawnGroup;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;

public final class RcEntities {
	public static EntityType<MaintenanceDroneEntity> MAINTENANCE_DRONE;
	public static EntityType<RocketEntity> ROCKET;
	public static EntityType<ConstructionDroneEntity> CONSTRUCTION_DRONE;
	public static EntityType<TankerDroneEntity> TANKER_DRONE;
	public static EntityType<MaglevCarEntity> MAGLEV_CAR;
	public static EntityType<GuardEntity> SOLDIER;
	public static EntityType<GuardEntity> SECURITY_ROBOT;
	public static EntityType<GuardEntity> SCAVENGER;

	private RcEntities() {}

	/** A guard type: MISC so it never counts towards mob caps, and tracked from a distance so you can see the post from afar. */
	private static EntityType<GuardEntity> registerGuard(String id) {
		return Registry.register(Registries.ENTITY_TYPE, Rackcraft.id(id),
				FabricEntityTypeBuilder.<GuardEntity>create(SpawnGroup.MISC, GuardEntity::new)
						.dimensions(EntityDimensions.fixed(0.6f, 1.95f))
						.trackRangeBlocks(80).trackedUpdateRate(3)
						.build());
	}

	public static void register() {
		MAINTENANCE_DRONE = Registry.register(Registries.ENTITY_TYPE, Rackcraft.id("maintenance_drone"),
				FabricEntityTypeBuilder.<MaintenanceDroneEntity>create(SpawnGroup.MISC, MaintenanceDroneEntity::new)
						.dimensions(EntityDimensions.fixed(0.75f, 0.35f))
						.trackRangeBlocks(96).trackedUpdateRate(2).forceTrackedVelocityUpdates(true)
						.build());
		CONSTRUCTION_DRONE = Registry.register(Registries.ENTITY_TYPE, Rackcraft.id("construction_drone"),
				FabricEntityTypeBuilder.<ConstructionDroneEntity>create(SpawnGroup.MISC, ConstructionDroneEntity::new)
						.dimensions(EntityDimensions.fixed(0.8f, 0.4f))
						.trackRangeBlocks(128).trackedUpdateRate(2).forceTrackedVelocityUpdates(true)
						.build());
		TANKER_DRONE = Registry.register(Registries.ENTITY_TYPE, Rackcraft.id("tanker_drone"),
				FabricEntityTypeBuilder.<TankerDroneEntity>create(SpawnGroup.MISC, TankerDroneEntity::new)
						.dimensions(EntityDimensions.fixed(0.9f, 0.45f))
						.trackRangeBlocks(160).trackedUpdateRate(2).forceTrackedVelocityUpdates(true)
						.build());
		MAGLEV_CAR = Registry.register(Registries.ENTITY_TYPE, Rackcraft.id("maglev_car"),
				FabricEntityTypeBuilder.<MaglevCarEntity>create(SpawnGroup.MISC, MaglevCarEntity::new)
						.dimensions(EntityDimensions.fixed(0.9f, 0.5f))
						.trackRangeBlocks(160).trackedUpdateRate(1).forceTrackedVelocityUpdates(true)
						.build());
		SOLDIER = registerGuard("soldier");
		SECURITY_ROBOT = registerGuard("security_robot");
		SCAVENGER = registerGuard("scavenger");
		FabricDefaultAttributeRegistry.register(SOLDIER, GuardEntity.createGuardAttributes());
		FabricDefaultAttributeRegistry.register(SECURITY_ROBOT, GuardEntity.createGuardAttributes());
		FabricDefaultAttributeRegistry.register(SCAVENGER, GuardEntity.createGuardAttributes());
		BoltEntity.TYPE = Registry.register(Registries.ENTITY_TYPE, Rackcraft.id("guard_bolt"),
				FabricEntityTypeBuilder.<BoltEntity>create(SpawnGroup.MISC, BoltEntity::new)
						.dimensions(EntityDimensions.fixed(0.25f, 0.25f))
						.trackRangeBlocks(64).trackedUpdateRate(2).forceTrackedVelocityUpdates(true)
						.build());
		EmpGrenadeEntity.TYPE = Registry.register(Registries.ENTITY_TYPE, Rackcraft.id("emp_grenade"),
				FabricEntityTypeBuilder.<EmpGrenadeEntity>create(SpawnGroup.MISC, EmpGrenadeEntity::new)
						.dimensions(EntityDimensions.fixed(0.25f, 0.25f))
						.trackRangeBlocks(64).trackedUpdateRate(10)
						.build());
		ROCKET = Registry.register(Registries.ENTITY_TYPE, Rackcraft.id("rocket"),
				FabricEntityTypeBuilder.<RocketEntity>create(SpawnGroup.MISC, RocketEntity::new)
						.dimensions(EntityDimensions.fixed(0.9f, 7f))
						.trackRangeBlocks(256).trackedUpdateRate(1).forceTrackedVelocityUpdates(true)
						.build());
	}
}
