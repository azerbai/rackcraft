package dev.rackcraft.darknet;

import dev.rackcraft.ExchangeCatalog;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import net.minecraft.item.EnchantedBookItem;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.enchantment.EnchantmentLevelEntry;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.item.SpawnEggItem;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtString;
import net.minecraft.registry.Registries;
import net.minecraft.resource.featuretoggle.FeatureSet;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;

/**
 * What turns up on the darknet: enchanted books, spawn eggs, empty spawners, rare loot nobody can craft, and now
 * and then anything else in the game. Never the mod's own items (they have to be built), and never pure
 * creative or operator items: command blocks, the debug stick, structure blocks, barriers, bedrock and so on.
 * Each lot has a value in RackCoin that its auction's opening bid and rival bidders are based on.
 */
public final class DarknetGoods {
	public record Lot(ItemStack stack, long value, String category) {}

	/** Never listed, whatever the roll. Obsidian is left out on request; the rest only exist in creative. */
	private static final Set<String> BANNED = Set.of("air", "bedrock", "obsidian", "crying_obsidian", "barrier", "light",
			"structure_block", "structure_void", "jigsaw", "debug_stick", "knowledge_book", "command_block", "chain_command_block",
			"repeating_command_block", "command_block_minecart", "end_portal_frame", "petrified_oak_slab", "written_book",
			"filled_map", "player_head", "wither_spawn_egg", "ender_dragon_spawn_egg", "farmland", "dirt_path", "frogspawn");

	/** Spawn eggs worth more than the usual 6,000 RC. */
	private static final Map<String, Long> EGG_VALUES = Map.ofEntries(
			Map.entry("villager", 10_000L), Map.entry("iron_golem", 25_000L), Map.entry("blaze", 15_000L),
			Map.entry("wither_skeleton", 20_000L), Map.entry("shulker", 30_000L), Map.entry("warden", 60_000L),
			Map.entry("elder_guardian", 50_000L), Map.entry("evoker", 25_000L), Map.entry("sniffer", 20_000L),
			Map.entry("allay", 12_000L), Map.entry("enderman", 12_000L), Map.entry("ghast", 12_000L), Map.entry("guardian", 12_000L),
			Map.entry("piglin", 8_000L), Map.entry("mooshroom", 9_000L), Map.entry("axolotl", 7_000L));

	/** Rare goods and what they're worth when the Exchange doesn't put a price on them. */
	private static final Map<Item, Long> RARE = Map.ofEntries(
			Map.entry(Items.ELYTRA, 80_000L), Map.entry(Items.TOTEM_OF_UNDYING, 30_000L),
			Map.entry(Items.ENCHANTED_GOLDEN_APPLE, 40_000L), Map.entry(Items.HEART_OF_THE_SEA, 25_000L),
			Map.entry(Items.TRIDENT, 35_000L), Map.entry(Items.DRAGON_HEAD, 50_000L), Map.entry(Items.WITHER_SKELETON_SKULL, 15_000L),
			Map.entry(Items.SNIFFER_EGG, 15_000L), Map.entry(Items.BUDDING_AMETHYST, 20_000L), Map.entry(Items.DRAGON_EGG, 250_000L),
			Map.entry(Items.REINFORCED_DEEPSLATE, 10_000L), Map.entry(Items.NETHER_STAR, 60_000L), Map.entry(Items.BEACON, 70_000L),
			Map.entry(Items.CONDUIT, 40_000L), Map.entry(Items.SHULKER_SHELL, 6_000L), Map.entry(Items.ECHO_SHARD, 5_000L),
			Map.entry(Items.NETHERITE_UPGRADE_SMITHING_TEMPLATE, 20_000L), Map.entry(Items.SILENCE_ARMOR_TRIM_SMITHING_TEMPLATE, 30_000L),
			Map.entry(Items.WARD_ARMOR_TRIM_SMITHING_TEMPLATE, 15_000L), Map.entry(Items.EYE_ARMOR_TRIM_SMITHING_TEMPLATE, 15_000L),
			Map.entry(Items.RECOVERY_COMPASS, 12_000L), Map.entry(Items.MUSIC_DISC_PIGSTEP, 8_000L),
			Map.entry(Items.MUSIC_DISC_OTHERSIDE, 8_000L), Map.entry(Items.MUSIC_DISC_5, 10_000L), Map.entry(Items.DRAGON_BREATH, 2_000L),
			Map.entry(Items.END_CRYSTAL, 8_000L), Map.entry(Items.NETHERITE_INGOT, 12_000L));

	private static final long SPAWNER_VALUE = 60_000;
	private static final long EGG_VALUE = 6_000;

	private DarknetGoods() {}

	/** Whether this item may ever be listed. */
	public static boolean allowed(Item item, FeatureSet features) {
		Identifier id = Registries.ITEM.getId(item);
		if (!id.getNamespace().equals("minecraft") || BANNED.contains(id.getPath()) || item == Items.AIR) return false;
		if (id.getPath().startsWith("infested_")) return false;
		if (item instanceof net.minecraft.item.BlockItem block && block.getBlock() instanceof net.minecraft.block.OperatorBlock) return false;
		return item.isEnabled(features);
	}

	/**
	 * A random lot: enchanted books 35%, spawn eggs 15%, a spawner 8%, rare goods 25%, and anything else the
	 * Exchange prices at 200 RC or more 17%. Falls back to a book if a roll comes up empty.
	 */
	public static Lot roll(Random random, FeatureSet features) {
		int roll = random.nextInt(100);
		Lot lot = null;
		if (roll < 35) lot = book(random);
		else if (roll < 50) lot = egg(random, features);
		else if (roll < 58) lot = spawner();
		else if (roll < 83) lot = rare(random, features);
		else lot = anything(random, features);
		return lot != null ? lot : book(random);
	}

	static Lot book(Random random) {
		List<Enchantment> enchantments = new ArrayList<>();
		Registries.ENCHANTMENT.forEach(enchantments::add);
		Enchantment enchantment = enchantments.get(random.nextInt(enchantments.size()));
		int max = enchantment.getMaxLevel();
		// Low levels are commoner: the top level turns up about one time in three on a three-level enchantment.
		int level = Math.min(max, 1 + (int) Math.floor(Math.pow(random.nextDouble(), 1.4) * max));
		ItemStack stack = EnchantedBookItem.forEnchantment(new EnchantmentLevelEntry(enchantment, level));
		double rarity = switch (enchantment.getRarity()) {
			case COMMON -> 1;
			case UNCOMMON -> 2;
			case RARE -> 4;
			case VERY_RARE -> 8;
		};
		double value = 1_500 * rarity * Math.pow(level, 1.5) * (enchantment.isTreasure() ? 2.5 : 1) * (enchantment.isCursed() ? 0.2 : 1);
		return new Lot(stack, Math.max(500, Math.round(value)), "book");
	}

	static Lot egg(Random random, FeatureSet features) {
		List<SpawnEggItem> eggs = new ArrayList<>();
		for (SpawnEggItem egg : SpawnEggItem.getAll()) if (allowed(egg, features)) eggs.add(egg);
		if (eggs.isEmpty()) return null;
		SpawnEggItem egg = eggs.get(random.nextInt(eggs.size()));
		String mob = Registries.ENTITY_TYPE.getId(egg.getEntityType(null)).getPath();
		int count = 1 + random.nextInt(3);
		return new Lot(new ItemStack(egg, count), EGG_VALUES.getOrDefault(mob, EGG_VALUE) * count, "egg");
	}

	/** An empty spawner: right-click it with a spawn egg to choose the mob, as in vanilla. */
	static Lot spawner() {
		ItemStack stack = new ItemStack(Items.SPAWNER);
		NbtList lore = new NbtList();
		lore.add(NbtString.of(Text.Serializer.toJson(Text.literal("Empty: right-click it with a spawn egg").formatted(Formatting.GRAY))));
		NbtCompound display = stack.getOrCreateSubNbt("display");
		display.put("Lore", lore);
		return new Lot(stack, SPAWNER_VALUE, "spawner");
	}

	static Lot rare(Random random, FeatureSet features) {
		List<Item> items = RARE.keySet().stream().filter(item -> allowed(item, features))
				.sorted(java.util.Comparator.comparing(item -> Registries.ITEM.getId(item).toString())).toList();
		if (items.isEmpty()) return null;
		Item item = items.get(random.nextInt(items.size()));
		Long priced = ExchangeCatalog.price(item);
		long each = priced != null ? priced : RARE.get(item);
		int count = item.getMaxCount() == 1 ? 1 : Math.max(1, Math.min(item.getMaxCount(), (int) Math.ceil(20_000.0 / each)));
		return new Lot(new ItemStack(item, count), each * count, "rare");
	}

	/** Anything the Exchange values at 200 RC or more, in a stack worth at least about 2,000 RC. */
	static Lot anything(Random random, FeatureSet features) {
		List<Map.Entry<Item, Long>> pool = new ArrayList<>();
		for (Map.Entry<Item, Long> entry : ExchangeCatalog.prices().entrySet()) {
			if (entry.getValue() >= 200 && allowed(entry.getKey(), features)) pool.add(entry);
		}
		if (pool.isEmpty()) return null;
		pool.sort(java.util.Comparator.comparing(entry -> Registries.ITEM.getId(entry.getKey()).toString()));
		Map.Entry<Item, Long> pick = pool.get(random.nextInt(pool.size()));
		Item item = pick.getKey();
		int count = Math.max(1, Math.min(item.getMaxCount(), (int) Math.ceil(2_000.0 / pick.getValue())));
		return new Lot(new ItemStack(item, count), pick.getValue() * count, "anything");
	}
}
