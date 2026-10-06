package dev.rackcraft;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;

/**
 * What RackCoin buys. Shared by the Crypto Exchange (all offers) and the Facility Controller (its
 * original parts list). Prices are in whole RackCoin; a full rack of 1U servers mines 16 RC/s.
 */
public final class ExchangeOffers {
	public enum Category { RESOURCES, RARE, PARTS }

	public record Offer(String id, Category category, Item item, int count, long price) {
		public ItemStack stack() { return new ItemStack(item, count); }
	}

	private static Map<String, Offer> offers;

	private ExchangeOffers() {}

	/** Built lazily because Rackcraft items are only registered during mod initialisation. */
	public static Map<String, Offer> all() {
		if (offers == null) {
			Map<String, Offer> map = new LinkedHashMap<>();
			add(map, "coal", Category.RESOURCES, Items.COAL, 16, 40);
			add(map, "copper_ingot", Category.RESOURCES, Items.COPPER_INGOT, 16, 60);
			add(map, "iron_ingot", Category.RESOURCES, Items.IRON_INGOT, 8, 120);
			add(map, "gold_ingot", Category.RESOURCES, Items.GOLD_INGOT, 4, 200);
			add(map, "redstone", Category.RESOURCES, Items.REDSTONE, 16, 100);
			add(map, "lapis_lazuli", Category.RESOURCES, Items.LAPIS_LAZULI, 16, 120);
			add(map, "quartz", Category.RESOURCES, Items.QUARTZ, 16, 150);
			add(map, "amethyst_shard", Category.RESOURCES, Items.AMETHYST_SHARD, 8, 120);
			add(map, "emerald", Category.RARE, Items.EMERALD, 1, 250);
			add(map, "diamond", Category.RARE, Items.DIAMOND, 1, 1_000);
			add(map, "ender_pearl", Category.RARE, Items.ENDER_PEARL, 2, 300);
			add(map, "blaze_rod", Category.RARE, Items.BLAZE_ROD, 2, 400);
			add(map, "experience_bottle", Category.RARE, Items.EXPERIENCE_BOTTLE, 8, 500);
			add(map, "netherite_scrap", Category.RARE, Items.NETHERITE_SCRAP, 1, 3_000);
			add(map, "netherite_ingot", Category.RARE, Items.NETHERITE_INGOT, 1, 12_000);
			add(map, "nether_star", Category.RARE, Items.NETHER_STAR, 1, 40_000);
			add(map, "silicon", Category.PARTS, RcItems.ITEMS.get("silicon"), 8, 60);
			add(map, "steel_ingot", Category.PARTS, RcItems.ITEMS.get("steel_ingot"), 8, 80);
			add(map, "coke", Category.PARTS, RcItems.ITEMS.get("coke"), 8, 100);
			add(map, "biodiesel_canister", Category.PARTS, RcItems.ITEMS.get("biodiesel_canister"), 1, 250);
			add(map, "repair_kit", Category.PARTS, RcItems.ITEMS.get("repair_kit"), 1, 120);
			add(map, "suppression_canister", Category.PARTS, RcItems.ITEMS.get("suppression_canister"), 1, 150);
			add(map, "pi_node", Category.PARTS, RcItems.ITEMS.get("pi_node"), 1, 150);
			add(map, "server_1u", Category.PARTS, RcItems.ITEMS.get("server_1u"), 1, 600);
			add(map, "gpu_blade", Category.PARTS, RcItems.ITEMS.get("gpu_blade"), 1, 4_000);
			add(map, "fuel_cell", Category.PARTS, RcItems.ITEMS.get("fuel_cell"), 1, 500);
			offers = map;
		}
		return offers;
	}

	/** The Facility Controller keeps its original procurement buttons. */
	public static final List<String> CONTROLLER_OFFERS = List.of("coal", "copper_ingot", "silicon", "steel_ingot",
			"repair_kit", "suppression_canister", "pi_node", "server_1u", "gpu_blade", "fuel_cell");

	private static void add(Map<String, Offer> map, String id, Category category, Item item, int count, long price) {
		map.put(id, new Offer(id, category, item, count, price));
	}
}
