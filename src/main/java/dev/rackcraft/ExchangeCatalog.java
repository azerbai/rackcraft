package dev.rackcraft;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.block.OperatorBlock;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.recipe.AbstractCookingRecipe;
import net.minecraft.recipe.CraftingRecipe;
import net.minecraft.recipe.Ingredient;
import net.minecraft.recipe.Recipe;
import net.minecraft.recipe.SpecialCraftingRecipe;
import net.minecraft.recipe.StonecuttingRecipe;
import net.minecraft.registry.Registries;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.resource.featuretoggle.FeatureSet;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.Identifier;
import net.minecraft.util.Rarity;

/**
 * "Buy almost anything": every survival-obtainable item, priced in RackCoin. Raw materials come from
 * {@link #baseValues()}; anything craftable, smeltable or stonecuttable is priced from its cheapest recipe
 * plus a 10% markup; whatever is left falls back to a price by rarity. Then everything but plain building blocks
 * costs {@link #MATERIAL_PREMIUM} times that. Rebuilt on server start and /reload.
 */
public final class ExchangeCatalog {
	private static final double MARKUP = 1.1;
	/**
	 * Everything except plain building blocks costs this many times its value: materials, tools, parts and machines
	 * are what RackCoin is really for, while decorating stays cheap.
	 */
	public static final int MATERIAL_PREMIUM = 2;
	/** Blocks that are tools or machinery even without a block entity. */
	private static final List<String> FUNCTIONAL = List.of("crafting_table", "anvil", "grindstone", "stonecutter", "loom",
			"cartography_table", "fletching_table", "smithing_table", "composter", "cauldron", "respawn_anchor", "lodestone",
			"scaffolding", "rail", "tnt", "redstone", "repeater", "lever", "button", "pressure_plate", "target", "observer",
			"piston", "note_block", "tripwire_hook", "slime_block", "honey_block", "beacon", "conduit", "end_crystal");
	private static final int PASSES = 12;
	/** Rackcraft items that are hardware rather than materials: they pay the component premium. */
	private static final Set<String> COMPONENTS = Set.of("circuit_board", "cpu_chip", "ram_module", "gpu_chip", "cryo_coil",
			"pi_node", "server_1u", "gpu_blade", "asic_miner", "quantum_core", "tensor_accelerator", "crafting_coprocessor",
			"crafting_accelerator", "thermal_scanner", "multimeter", "repair_kit", "drive_1k", "drive_4k", "drive_16k", "drive_64k",
			"tape_cartridge", "wireless_terminal", "electric_motor", "drone_frame", "stage_frame", "satellite_bus", "solar_array_frame",
			"nacelle_frame", "tower_frame", "heavy_drone_frame", "blade_chassis", "cable_planner", "pylon_linker", "battery_cell");
	private static final Set<String> EXCLUDED = Set.of(
			"air", "bedrock", "spawner", "end_portal_frame", "budding_amethyst", "reinforced_deepslate",
			"petrified_oak_slab", "farmland", "dirt_path", "frogspawn", "light", "barrier", "structure_void",
			"debug_stick", "knowledge_book", "written_book", "filled_map", "enchanted_book", "potion",
			"splash_potion", "lingering_potion", "tipped_arrow", "suspicious_stew", "goat_horn", "dragon_egg",
			"suspicious_sand", "suspicious_gravel", "player_head", "chorus_plant", "bundle", "recipe_pattern",
			// Training data and AI work have to be earned, and nuclear waste has to be made (and dealt with).
			"art_aggregate", "text_corpus", "generated_image", "generated_document", "spent_fuel", "waste_cask",
			"wafer_scale_engine", "agi_weights",
			// Hydrogen and drones are what spare power is for: buying them with RackCoin would skip the point.
			"hydrogen_canister", "maintenance_drone",
			// Advanced hardware is made, not bought: its materials come out of research-locked cubes, its chips off the
			// Assembly Line (which nothing here sells anyway), and the Cryostat is built from Superconducting Wire.
			"graphene_sheet", "gallium_nitride", "chiplet_bronze", "chiplet_silver", "chiplet_gold", "cryostat",
			// The postgame tools are built from that hardware and are not for sale either.
			"power_beacon", "beacon_receiver", "constructor_gauntlet", "terraformer_cannon", "superconducting_pylon",
			// Compute Pods and Blueprints are built from that hardware too.
			"pod_port", "blueprint_scanner");

	private static volatile Map<Item, Long> prices = Map.of();

	private ExchangeCatalog() {}

	public static void register() {
		ServerLifecycleEvents.SERVER_STARTED.register(ExchangeCatalog::rebuild);
		ServerLifecycleEvents.END_DATA_PACK_RELOAD.register((server, resources, success) -> rebuild(server));
	}

	public static final String TANKER = "tanker_drone";

	public static Long price(Item item) {
		return prices.get(item);
	}

	/** Whether the Exchange shows and sells this item in this world: the Tanker Drone needs its research first. */
	public static boolean listed(Item item, net.minecraft.server.world.ServerWorld world) {
		if (item == RcItems.ITEMS.get(TANKER)) return dev.rackcraft.compute.ResearchLab.effects(world).hydrogenStorage();
		return prices.containsKey(item);
	}

	public static Map<Item, Long> prices() {
		return prices;
	}

	public static boolean sellable(Item item, FeatureSet features) {
		Identifier id = Registries.ITEM.getId(item);
		String path = id.getPath();
		if (EXCLUDED.contains(path) || path.endsWith("_spawn_egg") || path.startsWith("infested_")
				|| path.endsWith("command_block") || path.equals("command_block_minecart")) return false;
		if (item instanceof BlockItem blockItem && blockItem.getBlock() instanceof OperatorBlock) return false;
		if (id.getNamespace().equals(Rackcraft.MOD_ID) && dev.rackcraft.generated.ContentIds.CREATIVE_IDS.contains(path)) return false;
		// Whatever the Assembly Line makes (drones, rocket stages, payloads) has to be built: no recipe price would
		// otherwise leave it on the cheap fallback.
		if (dev.rackcraft.world.AssemblyLine.recipes().stream().anyMatch(recipe -> recipe.product() == item)) return false;
		return item.isEnabled(features);
	}

	public static void rebuild(MinecraftServer server) {
		FeatureSet features = server.getSaveProperties().getEnabledFeatures();
		var registries = server.getRegistryManager();
		Map<Item, Double> values = new HashMap<>(baseValues());
		Set<Item> fixed = Set.copyOf(values.keySet());
		List<Recipe<?>> recipes = server.getRecipeManager().values().stream()
				.filter(recipe -> recipe instanceof CraftingRecipe && !(recipe instanceof SpecialCraftingRecipe)
						|| recipe instanceof AbstractCookingRecipe || recipe instanceof StonecuttingRecipe)
				.toList();

		for (int pass = 0; pass < PASSES; pass++) {
			boolean changed = false;
			for (Recipe<?> recipe : recipes) {
				ItemStack output = recipe.getOutput(registries);
				if (output.isEmpty() || fixed.contains(output.getItem())) continue;
				Double cost = recipeCost(recipe, values);
				if (cost == null) continue;
				double value = cost / output.getCount();
				Double current = values.get(output.getItem());
				if (current == null || value < current - 1e-9) {
					values.put(output.getItem(), value);
					changed = true;
				}
			}
			changed |= priceVariants(values);
			// Smithing upgrades have no ingredient list: price netherite gear as its diamond version plus an ingot.
			for (Item item : Registries.ITEM) {
				String path = Registries.ITEM.getId(item).getPath();
				// Gear only: the block of netherite has a real recipe, and pricing it as a diamond block plus one ingot
				// made netherite ingots (nine to a block) cheaper than their own scrap.
				if (!path.startsWith("netherite_") || item instanceof BlockItem || values.containsKey(item)) continue;
				Item diamond = Registries.ITEM.get(new Identifier("minecraft", "diamond_" + path.substring("netherite_".length())));
				if (diamond != Items.AIR && values.containsKey(diamond) && values.containsKey(Items.NETHERITE_INGOT)) {
					values.put(item, values.get(diamond) + values.get(Items.NETHERITE_INGOT));
					changed = true;
				}
			}
			if (!changed) break;
		}

		Set<Item> useful = usefulBlocks(server, recipes);
		Map<Item, Long> built = new LinkedHashMap<>();
		for (Item item : Registries.ITEM) {
			if (!sellable(item, features)) continue;
			Double value = values.get(item);
			long price = value == null ? fallback(item)
					: fixed.contains(item) ? Math.round(value) : (long) Math.ceil(value * MARKUP);
			if (!building(item, useful)) price *= MATERIAL_PREMIUM;
			built.put(item, Math.max(1, Math.round(price * hardwarePremium(item))));
		}
		// The Tanker Drone has no recipe: it's sold, at a fixed and painful price, and only once researched (see listed()).
		Item tanker = RcItems.ITEMS.get(TANKER);
		if (tanker != null) built.put(tanker, Math.max(1, RackcraftConfig.values.exchange.tankerDronePrice));
		prices = Collections.unmodifiableMap(built);
		Rackcraft.LOGGER.info("[Rackcraft] Exchange catalog priced {} items", built.size());
	}

	/**
	 * The extra a Rackcraft machine or component costs over its value, so a big balance can't simply buy a whole
	 * facility: see {@link RackcraftConfig.Exchange}. 1 for everything else.
	 */
	public static double hardwarePremium(Item item) {
		Identifier id = Registries.ITEM.getId(item);
		if (!id.getNamespace().equals(Rackcraft.MOD_ID)) return 1;
		var exchange = RackcraftConfig.values.exchange;
		if (dev.rackcraft.generated.ContentIds.MACHINE_IDS.contains(id.getPath())) return Math.max(1, exchange.machinePremium);
		if (COMPONENTS.contains(id.getPath())) return Math.max(1, exchange.componentPremium);
		return 1;
	}

	/**
	 * Items made in the world rather than by a recipe inherit the value of what they come from, so a dyed
	 * shulker box or a block of concrete can never undercut its source.
	 */
	private static boolean priceVariants(Map<Item, Double> values) {
		boolean changed = false;
		for (Item item : Registries.ITEM) {
			if (values.containsKey(item)) continue;
			String path = Registries.ITEM.getId(item).getPath();
			String source = null;
			double extra = 0;
			if (path.endsWith("_shulker_box")) source = "shulker_box";
			else if (path.equals("chipped_anvil") || path.equals("damaged_anvil")) source = "anvil";
			else if (path.endsWith("_concrete")) source = path + "_powder";
			else if (path.equals("carved_pumpkin")) source = "pumpkin";
			else if (path.contains("copper") && path.matches(".*(exposed|weathered|oxidized).*")) {
				source = path.replaceFirst("(exposed|weathered|oxidized)_", "");
				if (source.startsWith("waxed_")) {
					source = source.substring("waxed_".length());
					extra = values.getOrDefault(Items.HONEYCOMB, 8.0);
				}
				if (source.equals("copper")) source = "copper_block";
			}
			if (source == null) continue;
			Double sourceValue = values.get(Registries.ITEM.get(new Identifier("minecraft", source)));
			if (sourceValue == null) continue;
			values.put(item, sourceValue + extra);
			changed = true;
		}
		return changed;
	}

	private static Double recipeCost(Recipe<?> recipe, Map<Item, Double> values) {
		double cost = recipe instanceof AbstractCookingRecipe ? 1 : 0;
		boolean any = false;
		for (Ingredient ingredient : recipe.getIngredients()) {
			if (ingredient.isEmpty()) continue;
			Double best = null;
			for (ItemStack option : ingredient.getMatchingStacks()) {
				Double value = values.get(option.getItem());
				if (value != null && (best == null || value < best)) best = value;
			}
			if (best == null) return null;
			cost += best;
			any = true;
		}
		return any ? cost : null;
	}

	/**
	 * A plain building block: a block with no block entity that isn't machinery, and that isn't a store of something
	 * useful (a block of iron, an ore, a log) that a recipe turns back into items. Everything else pays the premium.
	 */
	public static boolean building(Item item, Set<Item> useful) {
		if (!(item instanceof BlockItem blockItem)) return false;
		if (blockItem.getBlock() instanceof net.minecraft.block.BlockEntityProvider) return false;
		String path = Registries.ITEM.getId(item).getPath();
		if (FUNCTIONAL.stream().anyMatch(path::contains)) return false;
		// Weathered and waxed copper blocks scrape back to a block of copper with an axe, so they're copper stock too.
		if (path.matches("(waxed_)?((exposed|weathered|oxidized)_)?copper(_block)?")) return false;
		return !useful.contains(item);
	}

	/** Blocks that a one-ingredient recipe (crafting or smelting) turns into a non-block item. */
	private static Set<Item> usefulBlocks(MinecraftServer server, List<Recipe<?>> recipes) {
		Set<Item> useful = new java.util.HashSet<>();
		for (Recipe<?> recipe : recipes) {
			List<Ingredient> ingredients = recipe.getIngredients().stream().filter(ingredient -> !ingredient.isEmpty()).toList();
			if (ingredients.size() != 1 || recipe.getOutput(server.getRegistryManager()).getItem() instanceof BlockItem) continue;
			for (ItemStack option : ingredients.get(0).getMatchingStacks()) {
				if (option.getItem() instanceof BlockItem) useful.add(option.getItem());
			}
		}
		return useful;
	}

	private static long fallback(Item item) {
		Rarity rarity = item.getRarity(new ItemStack(item));
		return switch (rarity) {
			case COMMON -> 20;
			case UNCOMMON -> 300;
			case RARE -> 1_500;
			case EPIC -> 8_000;
		};
	}

	/** Client copy of the price list, sent when an Exchange screen opens. */
	public static void write(PacketByteBuf buf, net.minecraft.server.world.ServerWorld world) {
		Map<Item, Long> snapshot = new LinkedHashMap<>(prices);
		snapshot.keySet().removeIf(item -> !listed(item, world));
		buf.writeVarInt(snapshot.size());
		snapshot.forEach((item, price) -> {
			buf.writeVarInt(Registries.ITEM.getRawId(item));
			buf.writeVarLong(price);
		});
	}

	public static List<Entry> read(PacketByteBuf buf) {
		int size = buf.readVarInt();
		List<Entry> entries = new ArrayList<>(size);
		for (int index = 0; index < size; index++) {
			entries.add(new Entry(Registries.ITEM.get(buf.readVarInt()), buf.readVarLong()));
		}
		return entries;
	}

	public record Entry(Item item, long price) {}

	/** Per-item RackCoin values for things that are found, farmed, mined or dropped rather than crafted. */
	private static Map<Item, Double> baseValues() {
		Map<Item, Double> base = new HashMap<>();
		put(base, 1, Items.COBBLESTONE, Items.COBBLED_DEEPSLATE, Items.DIRT, Items.SAND, Items.RED_SAND, Items.GRAVEL,
				Items.NETHERRACK, Items.BASALT, Items.BLACKSTONE, Items.TUFF, Items.GRANITE, Items.DIORITE, Items.ANDESITE,
				Items.MUD, Items.SNOWBALL, Items.KELP, Items.SEAGRASS, Items.BAMBOO, Items.WHEAT_SEEDS, Items.BEETROOT_SEEDS,
				Items.ROTTEN_FLESH, Items.MELON_SLICE, Items.POISONOUS_POTATO, Items.DEAD_BUSH, Items.GRASS, Items.FERN,
				Items.TALL_GRASS, Items.LARGE_FERN, Items.CRIMSON_ROOTS, Items.WARPED_ROOTS, Items.NETHER_SPROUTS,
				Items.HANGING_ROOTS);
		put(base, 2, Items.END_STONE, Items.SOUL_SAND, Items.SOUL_SOIL, Items.CALCITE, Items.DRIPSTONE_BLOCK,
				Items.POINTED_DRIPSTONE, Items.DEEPSLATE, Items.GRASS_BLOCK, Items.PODZOL, Items.ROOTED_DIRT, Items.CLAY_BALL,
				Items.FLINT, Items.CACTUS, Items.SUGAR_CANE, Items.WHEAT, Items.CARROT, Items.POTATO, Items.BEETROOT,
				Items.SWEET_BERRIES, Items.MELON_SEEDS, Items.PUMPKIN_SEEDS, Items.VINE, Items.TWISTING_VINES,
				Items.WEEPING_VINES, Items.SCULK_VEIN, Items.MANGROVE_ROOTS);
		put(base, 3, Items.ICE, Items.MOSS_BLOCK, Items.MYCELIUM, Items.COAL, Items.EGG, Items.FEATHER, Items.LILY_PAD,
				Items.GLOW_LICHEN, Items.COCOA_BEANS, Items.GLOW_BERRIES, Items.BROWN_MUSHROOM, Items.RED_MUSHROOM,
				Items.CRIMSON_NYLIUM, Items.WARPED_NYLIUM, Items.AZALEA, Items.BIG_DRIPLEAF, Items.SMALL_DRIPLEAF);
		put(base, 4, Items.STRING, Items.BONE, Items.PUMPKIN, Items.MAGMA_BLOCK, Items.SCULK, Items.CRIMSON_FUNGUS,
				Items.WARPED_FUNGUS, Items.RABBIT_HIDE, Items.BEEF, Items.PORKCHOP, Items.MUTTON, Items.CHICKEN, Items.RABBIT,
				Items.COD, Items.SALMON, Items.RAW_COPPER, Items.FLOWERING_AZALEA, Items.WARPED_WART_BLOCK);
		put(base, 5, Items.APPLE, Items.INK_SAC, Items.NETHER_WART, Items.COPPER_INGOT, Items.COAL_ORE,
				Items.DEEPSLATE_COAL_ORE, Items.SEA_PICKLE);
		put(base, 6, Items.REDSTONE, Items.GLOWSTONE_DUST, Items.SPIDER_EYE, Items.TROPICAL_FISH, Items.COPPER_ORE,
				Items.DEEPSLATE_COPPER_ORE);
		put(base, 8, Items.LEATHER, Items.LAPIS_LAZULI, Items.CHORUS_FRUIT, Items.PRISMARINE_SHARD, Items.HONEYCOMB,
				Items.PUFFERFISH);
		put(base, 10, Items.GUNPOWDER, Items.QUARTZ, Items.COBWEB, Items.SHROOMLIGHT, Items.HONEY_BOTTLE);
		put(base, 12, Items.PRISMARINE_CRYSTALS, Items.AMETHYST_SHARD, Items.NETHER_QUARTZ_ORE);
		put(base, 15, Items.SLIME_BALL, Items.RAW_IRON, Items.GLOW_INK_SAC);
		put(base, 16, Items.IRON_INGOT);
		put(base, 18, Items.IRON_ORE, Items.DEEPSLATE_IRON_ORE);
		put(base, 20, Items.NETHER_GOLD_ORE);
		put(base, 30, Items.AMETHYST_CLUSTER, Items.CHORUS_FLOWER, Items.TORCHFLOWER_SEEDS, Items.PITCHER_POD,
				Items.REDSTONE_ORE, Items.DEEPSLATE_REDSTONE_ORE);
		put(base, 40, Items.OBSIDIAN, Items.LAPIS_ORE, Items.DEEPSLATE_LAPIS_ORE);
		put(base, 45, Items.RAW_GOLD);
		put(base, 50, Items.GOLD_INGOT, Items.SPORE_BLOSSOM);
		put(base, 55, Items.GOLD_ORE, Items.DEEPSLATE_GOLD_ORE);
		put(base, 60, Items.CRYING_OBSIDIAN, Items.PHANTOM_MEMBRANE, Items.RABBIT_FOOT, Items.EXPERIENCE_BOTTLE);
		put(base, 80, Items.GILDED_BLACKSTONE);
		put(base, 100, Items.TURTLE_EGG, Items.DISC_FRAGMENT_5, Items.OCHRE_FROGLIGHT, Items.VERDANT_FROGLIGHT,
				Items.PEARLESCENT_FROGLIGHT);
		put(base, 120, Items.ENDER_PEARL);
		put(base, 150, Items.BLAZE_ROD);
		put(base, 200, Items.SCUTE, Items.BEE_NEST);
		put(base, 250, Items.EMERALD);
		put(base, 280, Items.EMERALD_ORE, Items.DEEPSLATE_EMERALD_ORE);
		put(base, 100, Items.CHAINMAIL_HELMET, Items.CHAINMAIL_BOOTS);
		put(base, 150, Items.IRON_HORSE_ARMOR, Items.CHAINMAIL_LEGGINGS, Items.CHAINMAIL_CHESTPLATE);
		put(base, 300, Items.GHAST_TEAR, Items.NAUTILUS_SHELL, Items.NAME_TAG, Items.SCULK_SENSOR,
				Items.GOLDEN_HORSE_ARMOR, Items.BELL, Items.GLOBE_BANNER_PATTERN, Items.PIGLIN_BANNER_PATTERN);
		put(base, 400, Items.SADDLE, Items.SPONGE, Items.WET_SPONGE, Items.SCULK_CATALYST, Items.DRAGON_BREATH);
		put(base, 500, Items.ECHO_SHARD, Items.SKELETON_SKULL, Items.SCULK_SHRIEKER);
		put(base, 800, Items.ZOMBIE_HEAD, Items.CREEPER_HEAD, Items.PIGLIN_HEAD);
		put(base, 1_000, Items.DIAMOND);
		put(base, 1_100, Items.DIAMOND_ORE, Items.DEEPSLATE_DIAMOND_ORE);
		put(base, 1_500, Items.SHULKER_SHELL);
		put(base, 2_000, Items.SNIFFER_EGG);
		put(base, 2_500, Items.HEART_OF_THE_SEA, Items.DIAMOND_HORSE_ARMOR);
		put(base, 3_000, Items.NETHERITE_SCRAP, Items.ANCIENT_DEBRIS, Items.WITHER_SKELETON_SKULL);
		put(base, 4_000, Items.TRIDENT);
		put(base, 5_000, Items.TOTEM_OF_UNDYING, Items.NETHERITE_UPGRADE_SMITHING_TEMPLATE);
		put(base, 10_000, Items.ENCHANTED_GOLDEN_APPLE, Items.DRAGON_HEAD);
		put(base, 30_000, Items.ELYTRA);
		put(base, 40_000, Items.NETHER_STAR);
		put(base, 5, RcItems.ITEMS.get("raw_bauxite"));
		put(base, 10, RcItems.ITEMS.get("failed_module"));
		// The nuclear chain is priced by the work behind it: ore deep underground, then each multiblock step.
		put(base, 1_000, RcItems.ITEMS.get("raw_uranium"));
		put(base, 1_200, RcBlocks.get("uranium_ore").asItem());
		put(base, 2_500, RcItems.ITEMS.get("yellowcake"));
		put(base, 500, RcItems.ITEMS.get("depleted_uranium"));
		put(base, 20_000, RcItems.ITEMS.get("enriched_uranium"));
		put(base, 25_000, RcItems.ITEMS.get("fuel_cell"));
		for (Item item : Registries.ITEM) {
			var entry = item.getRegistryEntry();
			String path = Registries.ITEM.getId(item).getPath();
			if (base.containsKey(item)) continue;
			if (entry.isIn(ItemTags.LOGS)) base.put(item, 4.0);
			else if (entry.isIn(ItemTags.LEAVES)) base.put(item, 1.0);
			else if (entry.isIn(ItemTags.SAPLINGS)) base.put(item, 3.0);
			else if (entry.isIn(ItemTags.SMALL_FLOWERS)) base.put(item, 2.0);
			else if (entry.isIn(ItemTags.TALL_FLOWERS)) base.put(item, 3.0);
			else if (path.startsWith("music_disc_")) base.put(item, 600.0);
			else if (path.endsWith("_pottery_sherd")) base.put(item, 150.0);
			else if (path.endsWith("_armor_trim_smithing_template")) base.put(item, 2_000.0);
			else if (path.endsWith("coral") || path.endsWith("coral_fan")) base.put(item, 3.0);
			else if (path.endsWith("coral_block")) base.put(item, 5.0);
			else if (path.endsWith("_bucket") && item != Items.BUCKET) base.put(item, path.equals("axolotl_bucket") ? 200.0 : 55.0);
		}
		return base;
	}

	private static void put(Map<Item, Double> map, double value, Item... items) {
		for (Item item : items) {
			if (item != null) map.put(item, value);
		}
	}
}
