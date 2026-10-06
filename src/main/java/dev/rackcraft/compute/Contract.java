package dev.rackcraft.compute;

import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;

/**
 * One piece of AI contract work: "3 x image of a pig in a business suit, 60% quality, due in 8 minutes".
 *
 * Work is measured in AI-compute-seconds (a GPU Blade at full load does 10 per second). A model with
 * quality cap {@code c} reaches quality {@code q} after {@code scale * ln(1 / (1 - q / c))} work, so a better
 * trained model needs less compute and a model whose cap is below the requirement never gets there.
 *
 * Pay is set when the offer is made, against a well-trained reference model (cap 90%), at
 * {@link #RC_PER_WORK} RackCoin per AI-compute-second. That beats mining on the same racks by about 10%
 * on GPUs and Quantum Cores and by a third on Tensor Accelerators, before quality bonuses.
 */
public final class Contract {
	public static final double RC_PER_WORK = 1.35;
	public static final double REFERENCE_CAP = 0.9;
	/** AI compute per second the deadline assumes: about one GPU Blade. */
	private static final double REFERENCE_RATE = 10;
	public static final long ANY_CLUSTER = Long.MIN_VALUE;

	public enum Kind { IMAGE, TEXT }

	public enum State { OFFERED, ACCEPTED, STOCK, DONE, FAILED }

	public int id;
	public Kind kind;
	/** "image" for images, otherwise a {@link ContractTemplates.DocType} id. */
	public String docType;
	public String prompt;
	public String client;
	public int quantity;
	public int quality;
	public long payout;
	public long offerExpires;
	public long durationTicks;
	public long deadline;
	public State state;
	public int delivered;
	public long earned;
	public boolean generating;
	public long cluster = ANY_CLUSTER;
	public double work;

	// Live figures for the operations terminal; not saved.
	public double computeRate;
	public String status = "";

	public double scale() {
		return kind == Kind.IMAGE ? 2400 : ContractTemplates.docType(docType).scale();
	}

	public String title() {
		return kind == Kind.IMAGE ? "Image of " + prompt : ContractTemplates.docType(docType).name() + ": " + prompt;
	}

	/** Work for one item at this quality with a model of this cap, or infinity if the model can't reach it. */
	public static double workFor(double scale, int quality, double cap) {
		double target = quality / 100.0;
		if (target >= cap) return Double.POSITIVE_INFINITY;
		return scale * Math.log(1 / (1 - target / cap));
	}

	/** Quality of the item in progress, from the work done so far. */
	public int qualityNow(double cap) {
		return (int) Math.floor(100 * cap * (1 - Math.exp(-work / scale())));
	}

	public long payPerItem() {
		return Math.max(1, payout / Math.max(1, quantity));
	}

	public boolean matches(ItemStack stack) {
		NbtCompound tag = stack.getNbt();
		return stack.getItem() instanceof GeneratedWorkItem && tag != null && tag.getString("Kind").equals(docType)
				&& tag.getString("Prompt").equals(prompt) && tag.getInt("Quality") >= quality;
	}

	/**
	 * A fresh offer. One in three repeats a prompt you've already delivered and some are perennial classics,
	 * so finished work kept in stock (or found in ruins) can be resold.
	 */
	public static Contract roll(int id, java.util.Random random, long now, java.util.List<String> history) {
		Contract contract = new Contract();
		contract.id = id;
		contract.state = State.OFFERED;
		contract.client = pick(ContractTemplates.CLIENTS, random);
		String repeat = !history.isEmpty() && random.nextInt(3) == 0 ? history.get(random.nextInt(history.size()))
				: random.nextInt(6) == 0 ? ContractTemplates.CLASSICS.get(random.nextInt(ContractTemplates.CLASSICS.size())) : null;
		if (repeat != null) {
			String[] parts = repeat.split("\\|", 2);
			contract.docType = parts[0];
			contract.prompt = parts[1];
			contract.kind = parts[0].equals("image") ? Kind.IMAGE : Kind.TEXT;
		} else if (random.nextBoolean()) {
			contract.kind = Kind.IMAGE;
			contract.docType = "image";
			contract.prompt = pick(ContractTemplates.IMAGE_SUBJECTS, random) + " " + pick(ContractTemplates.IMAGE_STYLES, random);
		} else {
			ContractTemplates.DocType type = pick(ContractTemplates.DOC_TYPES, random);
			contract.kind = Kind.TEXT;
			contract.docType = type.id();
			contract.prompt = pick(type.topics(), random);
		}
		contract.quantity = 1 + (random.nextInt(10) < 7 ? 0 : random.nextInt(3));
		contract.quality = 20 + 5 * (int) Math.round(Math.pow(random.nextDouble(), 1.3) * 14);
		double workPerItem = workFor(contract.scale(), contract.quality, REFERENCE_CAP);
		double tip = 1 + random.nextInt(11) / 100.0;
		contract.payout = Math.max(50, Math.round(RC_PER_WORK * workPerItem * contract.quantity * tip / 10) * 10);
		double seconds = Math.max(300, Math.min(3600, workPerItem * contract.quantity / REFERENCE_RATE * 3));
		contract.durationTicks = Math.round(seconds) * 20;
		contract.offerExpires = now + 20 * 60 * 10;
		return contract;
	}

	private static <T> T pick(java.util.List<T> values, java.util.Random random) {
		return values.get(random.nextInt(values.size()));
	}

	public NbtCompound toNbt() {
		NbtCompound tag = new NbtCompound();
		tag.putInt("Id", id);
		tag.putString("Kind", kind.name());
		tag.putString("DocType", docType);
		tag.putString("Prompt", prompt);
		tag.putString("Client", client);
		tag.putInt("Quantity", quantity);
		tag.putInt("Quality", quality);
		tag.putLong("Payout", payout);
		tag.putLong("OfferExpires", offerExpires);
		tag.putLong("Duration", durationTicks);
		tag.putLong("Deadline", deadline);
		tag.putString("State", state.name());
		tag.putInt("Delivered", delivered);
		tag.putLong("Earned", earned);
		tag.putBoolean("Generating", generating);
		tag.putLong("Cluster", cluster);
		tag.putDouble("Work", work);
		return tag;
	}

	public static Contract fromNbt(NbtCompound tag) {
		Contract contract = new Contract();
		contract.id = tag.getInt("Id");
		contract.kind = tag.getString("Kind").equals("TEXT") ? Kind.TEXT : Kind.IMAGE;
		contract.docType = tag.getString("DocType");
		contract.prompt = tag.getString("Prompt");
		contract.client = tag.getString("Client");
		contract.quantity = Math.max(1, tag.getInt("Quantity"));
		contract.quality = tag.getInt("Quality");
		contract.payout = tag.getLong("Payout");
		contract.offerExpires = tag.getLong("OfferExpires");
		contract.durationTicks = tag.getLong("Duration");
		contract.deadline = tag.getLong("Deadline");
		try {
			contract.state = State.valueOf(tag.getString("State"));
		} catch (IllegalArgumentException exception) {
			contract.state = State.FAILED;
		}
		contract.delivered = tag.getInt("Delivered");
		contract.earned = tag.getLong("Earned");
		contract.generating = tag.getBoolean("Generating");
		contract.cluster = tag.getLong("Cluster");
		contract.work = tag.getDouble("Work");
		return contract;
	}
}
