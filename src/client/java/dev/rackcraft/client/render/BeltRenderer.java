package dev.rackcraft.client.render;

import dev.rackcraft.block.BeltBlockEntity;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.block.entity.BlockEntityRenderer;
import net.minecraft.client.render.block.entity.BlockEntityRendererFactory;
import net.minecraft.client.render.item.ItemRenderer;
import net.minecraft.client.render.model.json.ModelTransformationMode;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.RotationAxis;

/** Draws the item riding a Conveyor Belt, lying flat (or as a little block) where it is along the belt. */
public final class BeltRenderer implements BlockEntityRenderer<BeltBlockEntity> {
	private final ItemRenderer items;

	public BeltRenderer(BlockEntityRendererFactory.Context context) {
		items = context.getItemRenderer();
	}

	@Override
	public void render(BeltBlockEntity belt, float tickDelta, MatrixStack matrices, VertexConsumerProvider vertexConsumers,
			int light, int overlay) {
		ItemStack stack = belt.stack();
		if (stack.isEmpty()) return;
		Direction facing = belt.facing();
		double along = belt.progress(tickDelta) - 0.5;
		matrices.push();
		matrices.translate(0.5 + facing.getOffsetX() * along, 4.2 / 16, 0.5 + facing.getOffsetZ() * along);
		matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(-facing.asRotation()));
		boolean block = items.getModel(stack, belt.getWorld(), null, 0).hasDepth();
		if (block) {
			matrices.translate(0, 0.12, 0);
			matrices.scale(0.5f, 0.5f, 0.5f);
		} else {
			matrices.translate(0, 0.02, 0);
			matrices.multiply(RotationAxis.POSITIVE_X.rotationDegrees(90));
			matrices.scale(0.55f, 0.55f, 0.55f);
		}
		items.renderItem(stack, ModelTransformationMode.FIXED, light, overlay, matrices, vertexConsumers, belt.getWorld(),
				(int) belt.getPos().asLong());
		matrices.pop();
	}
}
