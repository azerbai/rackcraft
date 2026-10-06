package dev.rackcraft;

import java.util.List;
import java.util.Map;
import net.minecraft.entity.player.PlayerEntity;

/**
 * The in-game adjustable values of creative-only machines. Values live on each block entity (saved with
 * the world) and are edited from the machine's screen; there is deliberately no config file for them.
 */
public final class CreativeSettings {
	public record Setting(String key, String unit, double min, double max, double defaultValue) {
		public double clamp(double value) {
			return Double.isFinite(value) ? Math.max(min, Math.min(max, value)) : defaultValue;
		}
	}

	public static final String OUTPUT_KW = "output_kw";
	public static final String MINING_RATE = "mining_rate";
	public static final String DRAW_KW = "draw_kw";
	public static final String TARGET_C = "target_c";
	public static final String BANDWIDTH = "bandwidth";

	private static final Map<String, List<Setting>> BY_BLOCK = Map.of(
			"creative_power", List.of(new Setting(OUTPUT_KW, "kW", 0, 1_000_000, 1_000)),
			"creative_rack", List.of(new Setting(MINING_RATE, "RC/s", 0, 1_000_000, 100),
					new Setting(DRAW_KW, "kW", 0, 1_000_000, 0)),
			// The thermal model tracks heat above ambient only, so air never goes below ambient (24 C by default).
			"creative_cooler", List.of(new Setting(TARGET_C, "C", 0, 200, 20)),
			"creative_router", List.of(new Setting(BANDWIDTH, "", 0, 1_000_000_000, 100_000)));

	private CreativeSettings() {}

	public static List<Setting> forBlock(String blockId) {
		return BY_BLOCK.getOrDefault(blockId, List.of());
	}

	public static Setting find(String blockId, String key) {
		return forBlock(blockId).stream().filter(setting -> setting.key().equals(key)).findFirst().orElse(null);
	}

	/** Anyone may look; only creative-mode players and operators may change values. */
	public static boolean canEdit(PlayerEntity player) {
		return player.isCreative() || player.hasPermissionLevel(2);
	}
}
