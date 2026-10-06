package dev.rackcraft.compute;

import net.minecraft.nbt.NbtCompound;

/**
 * One of the facility's two AI models. Training data (Art Aggregates for the image model, Text Corpora
 * for the language model) is uploaded into a queue; idle racks train on it, and every item trained raises
 * the model's quality cap: 12% untrained, about 66% after 24 items and 94% after 72.
 */
public final class AiModel {
	/** AI-compute-seconds to train on one item of data. */
	public static final double WORK_PER_ITEM = 1500;

	public final String name;
	public int trained;
	public int queued;
	public double progress;
	public boolean training = true;

	// Live figure for the operations terminal; not saved.
	public double computeRate;

	AiModel(String name) {
		this.name = name;
	}

	public double cap() {
		return 0.12 + 0.86 * (1 - Math.exp(-trained / 24.0));
	}

	public String versionName() {
		return name + " v" + (1 + trained / 10) + "." + trained % 10;
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
