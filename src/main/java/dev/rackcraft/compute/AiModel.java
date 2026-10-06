package dev.rackcraft.compute;

import java.util.List;
import net.minecraft.nbt.NbtCompound;

/**
 * One AI model the facility runs. There is a lineup per kind of work, like a real lab's: small fast models
 * that learn from a handful of examples but top out early, and big slow ones that need far more data and
 * compute and end up much better. Each model trains separately, on its own queue of uploaded data (Art
 * Aggregates for image models, Text Corpora for language models).
 *
 * <p>A model's quality cap starts at 12% and rises toward its {@link Spec#maxCap} as it trains: about two
 * thirds of the way after {@link Spec#learning} items and 95% of the way after three times that.
 */
public final class AiModel {
	/**
	 * A model in the lineup.
	 *
	 * @param cost       work multiplier per item: 0.5 makes the same item in half the AI compute
	 * @param maxCap     the best quality cap it can ever reach
	 * @param learning   items of data for about two thirds of its headroom
	 * @param trainWork  AI-compute-seconds to train on one item of data
	 * @param bonusMin   quality it adds above the requirement, at least...
	 * @param bonusMax   ...and at most (better models overdeliver, which pays up to 20% extra)
	 * @param versionFormat its name, with %s for the version number
	 * @param blurb      what the terminal says about it
	 */
	public record Spec(String id, Contract.Kind kind, String versionFormat, String tier, double cost, double maxCap,
			double learning, double trainWork, int bonusMin, int bonusMax, String blurb) {
		public double speed() { return 1 / cost; }
	}

	public static final double BASE_CAP = 0.12;

	/** The lineup, fastest first within each kind. Ids are saved; never rename them. */
	public static final List<Spec> SPECS = List.of(
			new Spec("sketchdiffusion", Contract.Kind.IMAGE, "SketchDiffusion %s", "Lite", 0.5, 0.62, 6, 600, 0, 1,
					"Open weights, two years old, draws hands like a fork. Fast and cheap; learns from a few drawings."),
			new Spec("nano_melon", Contract.Kind.IMAGE, "Nano Melon %s", "Flash", 0.75, 0.85, 14, 1200, 0, 4,
					"The everyday image model. Quick, decent, and only occasionally gives a pig six legs."),
			new Spec("nano_melon_pro", Contract.Kind.IMAGE, "Nano Melon Pro %s", "Pro", 1.3, 0.99, 30, 2400, 3, 10,
					"Slow, hungry for data and compute, and frighteningly good. Overdelivers on quality."),
			new Spec("gemerald_flash_lite", Contract.Kind.TEXT, "Gemerald %s Flash-Lite", "Lite", 0.5, 0.6, 6, 500, 0, 1,
					"Writes very fast and very confidently. Mostly about the right topic."),
			new Spec("gemerald_flash", Contract.Kind.TEXT, "Gemerald %s Flash", "Flash", 0.75, 0.84, 14, 1100, 0, 4,
					"The workhorse. Good enough for homework, cover letters and anything nobody will read twice."),
			new Spec("gemerald_pro", Contract.Kind.TEXT, "Gemerald %s Pro", "Pro", 1.3, 0.98, 30, 2200, 3, 10,
					"Thinks before it writes, which takes a while. The only one trusted with legal documents."));

	public final Spec spec;
	public int trained;
	public int queued;
	public double progress;
	public boolean training = true;

	// Live figure for the operations terminal; not saved.
	public double computeRate;

	AiModel(Spec spec) {
		this.spec = spec;
	}

	public static Spec spec(String id) {
		for (Spec spec : SPECS) if (spec.id().equals(id)) return spec;
		return null;
	}

	public String id() { return spec.id(); }
	public Contract.Kind kind() { return spec.kind(); }

	public double cap() {
		return BASE_CAP + (spec.maxCap() - BASE_CAP) * (1 - Math.exp(-trained / spec.learning()));
	}

	/** Work for one item of this scale at this quality, or infinity if the model can't reach it. */
	public double workFor(double scale, int quality) {
		return Contract.workFor(scale * spec.cost(), quality, cap());
	}

	/** "Gemerald 1.4 Flash": the version ticks up with every item trained. */
	public String versionName() {
		return String.format(java.util.Locale.ROOT, spec.versionFormat(), (1 + trained / 10) + "." + trained % 10);
	}

	/** Quality of a finished item: the requirement plus whatever this model overdelivers, never above its cap. */
	public int finishedQuality(int required, java.util.Random random) {
		int bonus = spec.bonusMin() + random.nextInt(spec.bonusMax() - spec.bonusMin() + 1);
		return Math.max(required, Math.min((int) Math.floor(cap() * 100), required + bonus));
	}

	NbtCompound toNbt() {
		NbtCompound tag = new NbtCompound();
		tag.putInt("Trained", trained);
		tag.putInt("Queued", queued);
		tag.putDouble("Progress", progress);
		tag.putBoolean("Training", training);
		return tag;
	}

	void readNbt(NbtCompound tag) {
		trained = Math.max(0, tag.getInt("Trained"));
		queued = Math.max(0, tag.getInt("Queued"));
		progress = Math.max(0, tag.getDouble("Progress"));
		training = !tag.contains("Training") || tag.getBoolean("Training");
	}
}
