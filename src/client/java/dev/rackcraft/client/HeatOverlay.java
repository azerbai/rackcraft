package dev.rackcraft.client;

import dev.rackcraft.RcItems;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.MinecraftClient;
import net.minecraft.particle.DustParticleEffect;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import org.joml.Vector3f;

public final class HeatOverlay {
	private static final Identifier HEAT_CELLS = new Identifier("rackcraft", "heat_cells");
	private static final List<HeatCell> CELLS = new ArrayList<>();
	private static int ticks;

	private HeatOverlay() {}

	public static void register() {
		ClientPlayNetworking.registerGlobalReceiver(HEAT_CELLS, (client, handler, buf, responseSender) -> {
			int count = Math.max(0, Math.min(2000, buf.readVarInt()));
			List<HeatCell> received = new ArrayList<>(count);
			for (int index = 0; index < count; index++) {
				received.add(new HeatCell(BlockPos.fromLong(buf.readLong()), buf.readShort() / 10.0 - 273.15));
			}
			client.execute(() -> {
				CELLS.clear();
				CELLS.addAll(received);
			});
		});
		ClientTickEvents.END_CLIENT_TICK.register(HeatOverlay::tick);
	}

	private static void tick(MinecraftClient client) {
		if (client.player == null || client.world == null
				|| (!client.player.getMainHandStack().isOf(RcItems.ITEMS.get("thermal_scanner"))
				&& !client.player.getOffHandStack().isOf(RcItems.ITEMS.get("thermal_scanner")))) {
			CELLS.clear();
			return;
		}
		if (++ticks % 4 != 0) return;
		for (HeatCell cell : CELLS) {
			float[] rgb = colorFor(cell.celsius());
			client.world.addParticle(new DustParticleEffect(new Vector3f(rgb[0], rgb[1], rgb[2]), 0.8f),
					cell.pos().getX() + 0.5, cell.pos().getY() + 0.5, cell.pos().getZ() + 0.5, 0, 0.005, 0);
		}
	}

	private static float[] colorFor(double celsius) {
		if (celsius <= 27) return blend(0.15f, 0.45f, 1, 0.2f, 0.85f, 0.35f, (float) ((celsius - 24) / 3));
		if (celsius <= 32) return blend(0.2f, 0.85f, 0.35f, 1, 0.85f, 0.15f, (float) ((celsius - 27) / 5));
		if (celsius <= 40) return blend(1, 0.85f, 0.15f, 1, 0.25f, 0.05f, (float) ((celsius - 32) / 8));
		return blend(1, 0.25f, 0.05f, 1, 0.02f, 0.01f, (float) Math.min(1, (celsius - 40) / 15));
	}

	private static float[] blend(float r1, float g1, float b1, float r2, float g2, float b2, float amount) {
		float t = Math.max(0, Math.min(1, amount));
		return new float[] {r1 + (r2 - r1) * t, g1 + (g2 - g1) * t, b1 + (b2 - b1) * t};
	}

	private record HeatCell(BlockPos pos, double celsius) {}
}