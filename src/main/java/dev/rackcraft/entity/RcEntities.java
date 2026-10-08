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

	private RcEntities() {}

	public static void register() {
		MAINTENANCE_DRONE = Registry.register(Registries.ENTITY_TYPE, Rackcraft.id("maintenance_drone"),
				FabricEntityTypeBuilder.<MaintenanceDroneEntity>create(SpawnGroup.MISC, MaintenanceDroneEntity::new)
						.dimensions(EntityDimensions.fixed(0.75f, 0.35f))
						.trackRangeBlocks(96).trackedUpdateRate(2).forceTrackedVelocityUpdates(true)
						.build());
	}
}
