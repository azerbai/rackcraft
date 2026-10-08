package dev.rackcraft.client.render;

import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.util.math.MatrixStack;
import org.joml.Matrix3f;
import org.joml.Matrix4f;

/**
 * Draws textured boxes for the moving models (robot arms, drones). Their textures are strips of 16x16 tiles, and
 * every face of a box gets a whole tile, stretched: simple, and at this size it reads fine.
 */
final class Boxes {
	private Boxes() {}

	static void box(MatrixStack matrices, VertexConsumer buffer, float x0, float y0, float z0, float x1, float y1, float z1,
			int tile, int tiles, int light, int overlay) {
		MatrixStack.Entry entry = matrices.peek();
		Matrix4f position = entry.getPositionMatrix();
		Matrix3f normal = entry.getNormalMatrix();
		float u0 = tile / (float) tiles;
		float u1 = (tile + 1) / (float) tiles;
		Face face = new Face(buffer, position, normal, u0, u1, light, overlay);
		face.quad(x0, y1, z0, x0, y0, z0, x1, y0, z0, x1, y1, z0, 0, 0, -1);
		face.quad(x1, y1, z1, x1, y0, z1, x0, y0, z1, x0, y1, z1, 0, 0, 1);
		face.quad(x0, y1, z1, x0, y0, z1, x0, y0, z0, x0, y1, z0, -1, 0, 0);
		face.quad(x1, y1, z0, x1, y0, z0, x1, y0, z1, x1, y1, z1, 1, 0, 0);
		face.quad(x0, y1, z0, x0, y1, z1, x1, y1, z1, x1, y1, z0, 0, 1, 0);
		face.quad(x0, y0, z1, x0, y0, z0, x1, y0, z0, x1, y0, z1, 0, -1, 0);
	}

	private record Face(VertexConsumer buffer, Matrix4f position, Matrix3f normal, float u0, float u1, int light, int overlay) {
		void quad(float ax, float ay, float az, float bx, float by, float bz, float cx, float cy, float cz, float dx, float dy, float dz,
				float nx, float ny, float nz) {
			vertex(ax, ay, az, u0, 0, nx, ny, nz);
			vertex(bx, by, bz, u0, 1, nx, ny, nz);
			vertex(cx, cy, cz, u1, 1, nx, ny, nz);
			vertex(dx, dy, dz, u1, 0, nx, ny, nz);
		}

		private void vertex(float x, float y, float z, float u, float v, float nx, float ny, float nz) {
			buffer.vertex(position, x, y, z).color(255, 255, 255, 255).texture(u, v).overlay(overlay).light(light)
					.normal(normal, nx, ny, nz).next();
		}
	}
}
