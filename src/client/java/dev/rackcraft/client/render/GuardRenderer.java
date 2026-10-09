package dev.rackcraft.client.render;

import dev.rackcraft.Rackcraft;
import dev.rackcraft.entity.GuardEntity;
import net.minecraft.client.render.entity.BipedEntityRenderer;
import net.minecraft.client.render.entity.EntityRendererFactory;
import net.minecraft.client.render.entity.feature.HeldItemFeatureRenderer;
import net.minecraft.client.render.entity.model.BipedEntityModel;
import net.minecraft.client.render.entity.model.EntityModelLayers;
import net.minecraft.util.Identifier;

/** Soldiers, Security Robots and Scavengers: a plain biped in their own skin, holding what they carry. */
public final class GuardRenderer extends BipedEntityRenderer<GuardEntity, BipedEntityModel<GuardEntity>> {
	private final Identifier texture;

	public GuardRenderer(EntityRendererFactory.Context context, String id) {
		super(context, new BipedEntityModel<>(context.getPart(EntityModelLayers.ZOMBIE)), 0.5f);
		this.texture = Rackcraft.id("textures/entity/" + id + ".png");
	}

	@Override
	public Identifier getTexture(GuardEntity entity) { return texture; }
}
