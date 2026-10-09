package dev.rackcraft.entity;

import dev.rackcraft.Rackcraft;
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

	private RcEntities() {}

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
		ROCKET = Registry.register(Registries.ENTITY_TYPE, Rackcraft.id("rocket"),
				FabricEntityTypeBuilder.<RocketEntity>create(SpawnGroup.MISC, RocketEntity::new)
						.dimensions(EntityDimensions.fixed(0.9f, 7f))
						.trackRangeBlocks(256).trackedUpdateRate(1).forceTrackedVelocityUpdates(true)
						.build());
	}
}
