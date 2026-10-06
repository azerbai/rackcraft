package dev.rackcraft.compute;

import java.util.List;

/**
 * The words that make up contracts: who is asking, what they want drawn or written, and what the result
 * says about itself. Everything here is flavour; the numbers live in {@link Contract}.
 */
public final class ContractTemplates {
	private ContractTemplates() {}

	public static final List<String> CLIENTS = List.of(
			"Plains Village Council", "Pillager Legal LLP", "Wandering Trader Holdings", "Iron Golem Union, Local 12",
			"Witch Hut Wellness Co.", "Desert Temple Tourism Board", "Piglin Bartering Exchange", "Ender Dragon Fan Club",
			"Bee Collective (Hive 4)", "Strider Lava Tours", "Ocean Monument HOA", "Steve (personal account)",
			"Alex's Startup (pre-seed)", "Nether Fortress Facilities", "Taiga Cat Cafe", "Woodland Mansion Estates",
			"Ancient City Noise Complaints", "Allay Same-Day Delivery", "Mooshroom Island Tourism", "Village Primary School");

	public static final List<String> IMAGE_SUBJECTS = List.of(
			"a creeper at a job interview", "an enderman holding a lawsuit", "a pig in a business suit",
			"Steve's LinkedIn headshot", "a villager getting a haircut", "a skeleton doing yoga",
			"a cow that is also a house", "a ghast crying at a wedding", "a wolf filing its taxes",
			"the Ender Dragon on a budget airline", "a chicken riding a minecart into the sunset",
			"a llama spitting at modern art", "a zombie learning to cook", "an axolotl CEO",
			"a sniffer at a perfume counter", "a slime in an elevator", "a witch launching a potion brand",
			"an iron golem holding a puppy", "a bee union on strike", "a panda's holiday photos",
			"a piglin admiring gold", "a phantom with insomnia", "a warden in a library", "a frog in a tiny hat",
			"a goat who knows what it did", "a squid with a mortgage", "a parrot doing stand-up",
			"a turtle winning a race", "a blaze at a barbecue", "an allay delivering pizza",
			"a cat asleep on a server rack", "a creeper family portrait", "a villager discovering crypto",
			"a strider in a hot tub", "an armor stand fashion show", "a snow golem at the beach",
			"a fox stealing a GPU", "a camel in rush-hour traffic", "a horse that is clearly two donkeys",
			"the moon, but made of cheese blocks");

	public static final List<String> IMAGE_STYLES = List.of(
			"in crayon", "as an oil painting", "in pixel art", "as a stock photo", "in watercolour",
			"as a renaissance fresco", "on a cereal box", "as a blurry phone photo", "in anime style",
			"as a motivational poster");

	/** A kind of writing: its name, how much work one takes, what it is about, and how it opens. */
	public record DocType(String id, String name, double scale, List<String> topics, String opening) {}

	public static final List<DocType> DOC_TYPES = List.of(
			new DocType("homework", "Homework", 1200, List.of(
					"why the sky is square", "the water cycle (Nether edition)", "long division with emeralds",
					"a book report on a book nobody read", "the history of the furnace", "photosynthesis, but for mushrooms"),
					"In conclusion, %s is very important because it is."),
			new DocType("essay", "Essay", 2400, List.of(
					"is the Ender Dragon a metaphor?", "bread versus cake: a moral inquiry", "emerald inflation",
					"why villagers deserve weekends", "the ethics of punching trees", "five hundred words about gravel"),
					"Since the dawn of time, scholars have argued about %s."),
			new DocType("legal", "Legal Document", 4800, List.of(
					"Pillager v. Village (appeal)", "a cease-and-desist to a creeper", "a lease for a dirt hut",
					"a non-compete for a wandering trader", "custody of a stolen cat", "an NDA for a Nether portal"),
					"WHEREAS the party of the first part, regarding %s, hereby and heretofore..."),
			new DocType("cover_letter", "Cover Letter", 1500, List.of(
					"a creeper applying to demolitions", "a skeleton applying to coach archery",
					"a villager applying for any job but librarian", "a zombie applying for the night shift",
					"Steve applying to be the main character again"),
					"To whom it may concern: I am writing to express my passion for %s."),
			new DocType("wedding_speech", "Wedding Speech", 1800, List.of(
					"two iron golems", "a cow and a mooshroom", "a piglin marrying gold", "a best man who is a parrot"),
					"For those who don't know me, I've known %s for exactly one crafting session."),
			new DocType("product_review", "Product Review", 1000, List.of(
					"a bed that exploded in the Nether", "a slightly used minecart", "a shield that blocked everything",
					"a cake with no candles", "the End (would not visit again)"),
					"One star. I bought %s and it was not what the villager promised."),
			new DocType("apology", "Apology Letter", 900, List.of(
					"blowing up the house", "trading away the last emerald", "the incident with the bees",
					"leaving the furnace on", "the cow in the elevator"),
					"I am deeply sorry for %s. Mistakes were made, mostly by me."),
			new DocType("tos", "Terms of Service", 3600, List.of(
					"a dirt hut", "a cobblestone generator", "Steve's minecart rentals", "a sheep's wool subscription"),
					"By breathing near %s you agree to the following 4,000 clauses."),
			new DocType("fanfic", "Fan Fiction", 3000, List.of(
					"the enderman who just wanted to hold a flower", "a furnace and a crafting table in love",
					"a creeper finding inner peace", "Herobrine, who is actually just shy"),
					"Chapter 1. The rain fell softly on %s, who did not explode, not today."),
			new DocType("patch_notes", "Patch Notes", 2000, List.of(
					"the village (fixed doors)", "a bee hive", "reality", "the Nether (now 4% less on fire)"),
					"v1.0.1 for %s: fixed a bug where things happened. Known issues: everything."));

	/**
	 * Requests clients make over and over ("kind|prompt"). Finished copies of these turn up in abandoned
	 * data centers, so a find can be sold on the board. Keep in sync with tools/gen_assets.py.
	 */
	public static final List<String> CLASSICS = List.of(
			"image|a creeper at a job interview in crayon", "image|a pig in a business suit as a stock photo",
			"image|Steve's LinkedIn headshot as a blurry phone photo", "image|a cat asleep on a server rack in pixel art",
			"image|a fox stealing a GPU as a motivational poster", "homework|the water cycle (Nether edition)",
			"essay|five hundred words about gravel", "legal|a lease for a dirt hut",
			"cover_letter|a villager applying for any job but librarian", "tos|Steve's minecart rentals", "patch_notes|reality");

	public static DocType docType(String id) {
		for (DocType type : DOC_TYPES) if (type.id().equals(id)) return type;
		return DOC_TYPES.get(0);
	}

	/** What a generated image's tooltip admits about it. */
	public static String imageVerdict(int quality) {
		if (quality < 30) return "Has too many fingers. Possibly too many legs.";
		if (quality < 55) return "Mostly the right number of legs.";
		if (quality < 80) return "Pretty convincing, from a distance.";
		return "Indistinguishable from real crayon art by a real child.";
	}

	/** A generated document's first line: the better the model, the less it repeats itself. */
	public static String excerpt(DocType type, String topic, int quality) {
		if (quality < 30) return "The " + topic + " is a " + topic + " of " + topic + ". " + topic + ".";
		String line = String.format(java.util.Locale.ROOT, type.opening(), topic);
		return quality < 60 ? line + " As an AI language model, I cannot." : line;
	}
}
