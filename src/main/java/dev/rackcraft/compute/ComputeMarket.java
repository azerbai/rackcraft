package dev.rackcraft.compute;

import dev.rackcraft.RcItems;
import dev.rackcraft.world.FacilityManager;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.world.PersistentState;

/**
 * The facility's AI business, one per dimension like its RackCoin balance: the contract board, the model
 * lineup and each model's training queue, the outbox of finished work, cluster policies and earnings.
 */
public final class ComputeMarket extends PersistentState {
	private static final String STATE_KEY = "rackcraft_market";
	public static final int MAX_OFFERS = 6;
	public static final int MAX_ACTIVE = 5;
	private static final int HISTORY = 24;
	private static final long HOUR_TICKS = 20 * 60 * 60;

	private final List<Contract> contracts = new ArrayList<>();
	private final List<String> history = new ArrayList<>();
	private final Map<Long, Cluster.Policy> policies = new HashMap<>();
	private final List<ItemStack> outbox = new ArrayList<>();
	private final Deque<long[]> earnings = new ArrayDeque<>();
	private final Map<String, AiModel> models = new java.util.LinkedHashMap<>();
	private int nextId = 1;
	private long nextOfferTick;
	private long totalEarned;
	private long seed = 0x41494D4B54L;

	public ComputeMarket() {
		for (AiModel.Spec spec : AiModel.SPECS) models.put(spec.id(), new AiModel(spec));
	}

	public static ComputeMarket get(ServerWorld world) {
		return world.getPersistentStateManager().getOrCreate(ComputeMarket::fromNbt, ComputeMarket::new, STATE_KEY);
	}

	public List<Contract> contracts() { return contracts; }
	public List<ItemStack> outbox() { return outbox; }
	public long totalEarned() { return totalEarned; }

	/** Every model, in lineup order. */
	public List<AiModel> models() { return List.copyOf(models.values()); }

	public AiModel model(String id) { return models.get(id); }

	public List<AiModel> models(Contract.Kind kind) {
		return models.values().stream().filter(model -> model.kind() == kind).toList();
	}

	/**
	 * The model that generates this contract: the one chosen for it, or on Auto the one that makes an item
	 * with the least compute. If no model can reach the quality yet, Auto returns the best-trained one, so
	 * the terminal can say how far short it falls.
	 */
	public AiModel modelFor(Contract contract) {
		AiModel chosen = models.get(contract.model);
		if (chosen != null && chosen.kind() == contract.kind) return chosen;
		AiModel fastest = null;
		AiModel best = null;
		for (AiModel model : models(contract.kind)) {
			if (best == null || model.cap() > best.cap()) best = model;
			double work = model.workFor(contract.scale(), contract.quality);
			if (Double.isFinite(work) && (fastest == null || work < fastest.workFor(contract.scale(), contract.quality))) fastest = model;
		}
		return fastest != null ? fastest : best;
	}

	/** Steps a contract's model choice: Auto, then each model of its kind, then back to Auto. */
	public void cycleModel(int id) {
		Contract contract = find(id);
		if (contract == null) return;
		List<String> options = new ArrayList<>();
		options.add(Contract.AUTO_MODEL);
		models(contract.kind).forEach(model -> options.add(model.id()));
		int index = options.indexOf(contract.model);
		contract.model = options.get((index + 1) % options.size());
		markDirty();
	}

	public long earnedSince(long tick) {
		return earnings.stream().filter(entry -> entry[0] >= tick).mapToLong(entry -> entry[1]).sum();
	}

	private Random random() {
		seed = seed * 6364136223846793005L + 1442695040888963407L;
		return new Random(seed);
	}

	// ---------------------------------------------------------------- clusters

	public Cluster.Policy policyFor(long id, List<dev.rackcraft.block.MachineBlockEntity> racks) {
		Cluster.Policy policy = policies.get(id);
		if (policy != null) return policy;
		for (var rack : racks) {
			policy = policies.remove(rack.getPos().asLong());
			if (policy != null) {
				// The anchor rack changed: move the setting to the cluster's new id.
				policies.put(id, policy);
				markDirty();
				return policy;
			}
		}
		return Cluster.Policy.AUTO;
	}

	public void setPolicy(long clusterId, Cluster.Policy policy) {
		if (policy == Cluster.Policy.AUTO) policies.remove(clusterId);
		else policies.put(clusterId, policy);
		markDirty();
	}

	// ---------------------------------------------------------------- the board

	/** New offers arrive every one to three minutes; unaccepted ones lapse after ten. */
	void refreshOffers(long now) {
		boolean changed = contracts.removeIf(contract -> contract.state == Contract.State.OFFERED && contract.offerExpires <= now);
		long offered = contracts.stream().filter(contract -> contract.state == Contract.State.OFFERED).count();
		if (now >= nextOfferTick && offered < MAX_OFFERS) {
			Random random = random();
			contracts.add(Contract.roll(nextId++, random, now, history));
			nextOfferTick = now + 20 * (60 + random.nextInt(121));
			changed = true;
		}
		// Keep only the last few finished contracts, for the history list.
		List<Contract> finished = contracts.stream()
				.filter(contract -> contract.state == Contract.State.DONE || contract.state == Contract.State.FAILED).toList();
		for (int index = 0; index < finished.size() - 8; index++) {
			contracts.remove(finished.get(index));
			changed = true;
		}
		if (changed) markDirty();
	}

	/** Adds an offer immediately; used by the self-test and the /rackcraft contracts command. */
	public Contract postOffer(long now) {
		Contract contract = Contract.roll(nextId++, random(), now, history);
		contracts.add(contract);
		markDirty();
		return contract;
	}

	public Contract find(int id) {
		for (Contract contract : contracts) if (contract.id == id) return contract;
		return null;
	}

	public String accept(int id, long now) {
		Contract contract = find(id);
		if (contract == null || contract.state != Contract.State.OFFERED) return "That offer is gone.";
		if (active().size() >= MAX_ACTIVE) return "You already have " + MAX_ACTIVE + " contracts on the go.";
		contract.state = Contract.State.ACCEPTED;
		contract.deadline = now + contract.durationTicks;
		markDirty();
		return null;
	}

	public void decline(int id) {
		Contract contract = find(id);
		if (contract != null && contract.state == Contract.State.OFFERED) contracts.remove(contract);
		markDirty();
	}

	/** Gives up an accepted contract. Work already delivered stays paid. */
	public void abandon(int id) {
		Contract contract = find(id);
		if (contract == null) return;
		if (contract.state == Contract.State.STOCK) contracts.remove(contract);
		else if (contract.state == Contract.State.ACCEPTED) contract.state = Contract.State.FAILED;
		contract.generating = false;
		markDirty();
	}

	public void setGenerating(int id, boolean generating, long cluster) {
		Contract contract = find(id);
		if (contract == null || contract.state != Contract.State.ACCEPTED && contract.state != Contract.State.STOCK) return;
		contract.generating = generating;
		contract.cluster = cluster;
		markDirty();
	}

	/** Queues one more copy of a finished contract's work, for stock. */
	public void printCopy(int id) {
		Contract source = find(id);
		if (source == null || source.state != Contract.State.DONE) return;
		Contract copy = new Contract();
		copy.id = nextId++;
		copy.kind = source.kind;
		copy.docType = source.docType;
		copy.prompt = source.prompt;
		copy.client = "Your stock";
		copy.quantity = 1;
		copy.quality = source.quality;
		copy.state = Contract.State.STOCK;
		copy.generating = true;
		contracts.add(copy);
		markDirty();
	}

	public List<Contract> active() {
		return contracts.stream().filter(contract -> contract.state == Contract.State.ACCEPTED || contract.state == Contract.State.STOCK)
				.sorted(Comparator.comparingLong(contract -> contract.state == Contract.State.STOCK ? Long.MAX_VALUE : contract.deadline))
				.toList();
	}

	// ---------------------------------------------------------------- delivery

	/** Puts finished work in the outbox; accepted contracts that want it pull it from there. */
	void produce(Contract contract, int quality) {
		Item item = RcItems.ITEMS.get(contract.kind == Contract.Kind.IMAGE ? "generated_image" : "generated_document");
		outbox.add(GeneratedWorkItem.create(item, contract.docType, contract.prompt, quality,
				modelFor(contract).versionName()));
		if (contract.state == Contract.State.STOCK) {
			contracts.remove(contract);
		}
		markDirty();
	}

	/** Delivers matching items from the outbox to every contract that wants them. */
	void deliverFromOutbox(ServerWorld world, long now) {
		for (Contract contract : active()) {
			if (contract.state != Contract.State.ACCEPTED) continue;
			Iterator<ItemStack> iterator = outbox.iterator();
			while (iterator.hasNext() && contract.delivered < contract.quantity) {
				ItemStack stack = iterator.next();
				if (!contract.matches(stack)) continue;
				iterator.remove();
				pay(world, contract, stack, now);
			}
		}
	}

	/** Delivers matching items from a player's inventory. Returns how many were accepted. */
	public int deliverFromInventory(ServerWorld world, PlayerEntity player, int id, long now) {
		Contract contract = find(id);
		if (contract == null || contract.state != Contract.State.ACCEPTED) return 0;
		int count = 0;
		var inventory = player.getInventory();
		for (int slot = 0; slot < inventory.size() && contract.delivered < contract.quantity; slot++) {
			ItemStack stack = inventory.getStack(slot);
			while (!stack.isEmpty() && contract.matches(stack) && contract.delivered < contract.quantity) {
				pay(world, contract, stack.split(1), now);
				count++;
			}
		}
		return count;
	}

	/** Pays for one item: late work earns half; quality above the requirement earns up to 20% more. */
	private void pay(ServerWorld world, Contract contract, ItemStack stack, long now) {
		int quality = stack.getNbt() == null ? contract.quality : stack.getNbt().getInt("Quality");
		double bonus = Math.min(0.2, Math.max(0, quality - contract.quality) * 0.005);
		long amount = Math.round(contract.payPerItem() * (1 + bonus) * (now > contract.deadline ? 0.5 : 1));
		contract.delivered++;
		contract.earned += amount;
		FacilityManager.get(world).addCredits(amount);
		earnings.addLast(new long[] {now, amount});
		totalEarned += amount;
		if (contract.delivered >= contract.quantity) {
			contract.state = Contract.State.DONE;
			contract.generating = false;
			String key = contract.docType + "|" + contract.prompt;
			history.remove(key);
			history.add(key);
			while (history.size() > HISTORY) history.remove(0);
		}
		markDirty();
	}

	/** Contracts more than one full duration overdue are failed. */
	void expire(long now) {
		for (Contract contract : contracts) {
			if (contract.state == Contract.State.ACCEPTED && now > contract.deadline + contract.durationTicks) {
				contract.state = Contract.State.FAILED;
				contract.generating = false;
				markDirty();
			}
		}
		while (!earnings.isEmpty() && earnings.peekFirst()[0] < now - HOUR_TICKS) earnings.removeFirst();
	}

	public void collectOutbox(PlayerEntity player) {
		for (ItemStack stack : outbox) {
			if (!player.getInventory().insertStack(stack)) player.dropItem(stack, false);
		}
		outbox.clear();
		markDirty();
	}

	/** The training data a model learns from: Art Aggregates for image models, Text Corpora for language models. */
	public static Item dataFor(Contract.Kind kind) {
		return RcItems.ITEMS.get(kind == Contract.Kind.IMAGE ? "art_aggregate" : "text_corpus");
	}

	/** Moves every item of a model's training data from a player's inventory into its queue. Returns how many. */
	public int uploadTrainingData(PlayerEntity player, String modelId) {
		AiModel model = models.get(modelId);
		if (model == null) return 0;
		Item data = dataFor(model.kind());
		int uploaded = 0;
		var inventory = player.getInventory();
		for (int slot = 0; slot < inventory.size(); slot++) {
			ItemStack stack = inventory.getStack(slot);
			if (!stack.isOf(data)) continue;
			model.queued += stack.getCount();
			uploaded += stack.getCount();
			inventory.setStack(slot, ItemStack.EMPTY);
		}
		if (uploaded > 0) markDirty();
		return uploaded;
	}

	public void toggleTraining(String modelId) {
		AiModel model = models.get(modelId);
		if (model == null) return;
		model.training = !model.training;
		markDirty();
	}

	// ---------------------------------------------------------------- saving

	@Override
	public NbtCompound writeNbt(NbtCompound nbt) {
		NbtList list = new NbtList();
		contracts.forEach(contract -> list.add(contract.toNbt()));
		nbt.put("Contracts", list);
		NbtList prompts = new NbtList();
		history.forEach(entry -> {
			NbtCompound tag = new NbtCompound();
			tag.putString("Key", entry);
			prompts.add(tag);
		});
		nbt.put("History", prompts);
		NbtCompound policyTag = new NbtCompound();
		policies.forEach((id, policy) -> policyTag.putString(Long.toString(id), policy.name()));
		nbt.put("Policies", policyTag);
		NbtList items = new NbtList();
		outbox.forEach(stack -> items.add(stack.writeNbt(new NbtCompound())));
		nbt.put("Outbox", items);
		NbtList earned = new NbtList();
		earnings.forEach(entry -> {
			NbtCompound tag = new NbtCompound();
			tag.putLong("Tick", entry[0]);
			tag.putLong("Amount", entry[1]);
			earned.add(tag);
		});
		nbt.put("Earnings", earned);
		NbtCompound modelTag = new NbtCompound();
		models.forEach((id, model) -> modelTag.put(id, model.toNbt()));
		nbt.put("Models", modelTag);
		nbt.putInt("NextId", nextId);
		nbt.putLong("NextOffer", nextOfferTick);
		nbt.putLong("TotalEarned", totalEarned);
		nbt.putLong("Seed", seed);
		return nbt;
	}

	private static ComputeMarket fromNbt(NbtCompound nbt) {
		ComputeMarket market = new ComputeMarket();
		NbtList list = nbt.getList("Contracts", NbtElement.COMPOUND_TYPE);
		for (int index = 0; index < list.size(); index++) market.contracts.add(Contract.fromNbt(list.getCompound(index)));
		NbtList prompts = nbt.getList("History", NbtElement.COMPOUND_TYPE);
		for (int index = 0; index < prompts.size(); index++) market.history.add(prompts.getCompound(index).getString("Key"));
		NbtCompound policyTag = nbt.getCompound("Policies");
		for (String key : policyTag.getKeys()) {
			try {
				market.policies.put(Long.parseLong(key), Cluster.Policy.valueOf(policyTag.getString(key)));
			} catch (IllegalArgumentException ignored) {
				// A policy from a newer version: fall back to AUTO.
			}
		}
		NbtList items = nbt.getList("Outbox", NbtElement.COMPOUND_TYPE);
		for (int index = 0; index < items.size(); index++) {
			ItemStack stack = ItemStack.fromNbt(items.getCompound(index));
			if (!stack.isEmpty()) market.outbox.add(stack);
		}
		NbtList earned = nbt.getList("Earnings", NbtElement.COMPOUND_TYPE);
		for (int index = 0; index < earned.size(); index++) {
			NbtCompound tag = earned.getCompound(index);
			market.earnings.addLast(new long[] {tag.getLong("Tick"), tag.getLong("Amount")});
		}
		if (nbt.contains("Models")) {
			NbtCompound modelTag = nbt.getCompound("Models");
			market.models.forEach((id, model) -> {
				if (modelTag.contains(id)) model.readNbt(modelTag.getCompound(id));
			});
		} else {
			// Saves from before the lineup had one model per kind; their curves match the Pro models.
			market.models.get("nano_melon_pro").readNbt(nbt.getCompound("ImageModel"));
			market.models.get("gemerald_pro").readNbt(nbt.getCompound("TextModel"));
		}
		market.nextId = Math.max(1, nbt.getInt("NextId"));
		market.nextOfferTick = nbt.getLong("NextOffer");
		market.totalEarned = nbt.getLong("TotalEarned");
		if (nbt.contains("Seed")) market.seed = nbt.getLong("Seed");
		return market;
	}
}
