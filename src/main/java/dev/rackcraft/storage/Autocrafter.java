package dev.rackcraft.storage;

import dev.rackcraft.block.MachineBlockEntity;
import dev.rackcraft.block.RackStatus;
import dev.rackcraft.compute.Cluster;
import dev.rackcraft.sim.NetKind;
import dev.rackcraft.world.NetworkManager;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.inventory.CraftingInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.recipe.CraftingRecipe;
import net.minecraft.recipe.RecipeType;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;

/**
 * Autocrafting, run on your server racks. Racks lend general compute (Pi Node 1, 1U Server 2, GPU Blade 6,
 * Tensor Accelerator 3, Quantum Core 20; ASIC Miners cannot craft), scaled by their load. A job crafts
 * {@link #CRAFTS_PER_COMPUTE_SECOND} times per second per point of compute, and racks lending compute
 * stop mining while the job runs. See {@link dev.rackcraft.compute.ComputeScheduler}.
 */
public final class Autocrafter {
	public static final double CRAFTS_PER_COMPUTE_SECOND = 0.25;
	private static final int MAX_CRAFTS_PER_STEP = 64;
	private static final int MAX_DEPTH = 24;

	public record Step(PatternItem.Pattern pattern, long crafts) {}

	public record Plan(List<Step> steps, Map<ItemKey, Long> missing) {
		public boolean ok() { return missing.isEmpty() && !steps.isEmpty(); }
		public long totalCrafts() { return steps.stream().mapToLong(Step::crafts).sum(); }
	}

	public static final class Job {
		final UUID owner;
		final StorageService.Access access;
		final ItemKey target;
		final long amount;
		final List<Step> steps;
		final long totalCrafts;
		int stepIndex;
		long doneInStep;
		long craftsDone;
		double budget;
		String status = "queued";
		int compute;

		Job(UUID owner, StorageService.Access access, ItemKey target, long amount, Plan plan) {
			this.owner = owner;
			this.access = access;
			this.target = target;
			this.amount = amount;
			this.steps = plan.steps();
			this.totalCrafts = plan.totalCrafts();
		}

		public ItemKey target() { return target; }
		public long amount() { return amount; }
		public long craftsDone() { return craftsDone; }
		public long totalCrafts() { return totalCrafts; }
		public String status() { return status; }
		public int compute() { return compute; }
	}

	private static final Map<MinecraftServer, List<Job>> JOBS = new WeakHashMap<>();
	private static final ScreenHandler NO_HANDLER = new ScreenHandler(null, -1) {
		@Override public ItemStack quickMove(PlayerEntity player, int slot) { return ItemStack.EMPTY; }
		@Override public boolean canUse(PlayerEntity player) { return true; }
	};

	private Autocrafter() {}

	/** Patterns stored anywhere on the network, by the item they make. */
	public static Map<ItemKey, PatternItem.Pattern> patterns(StorageNetwork network) {
		Map<ItemKey, PatternItem.Pattern> patterns = new LinkedHashMap<>();
		for (ItemKey key : network.totals().keySet()) {
			if (!(key.item() instanceof PatternItem)) continue;
			PatternItem.Pattern pattern = PatternItem.decode(key.toStack(1));
			if (pattern != null) patterns.putIfAbsent(pattern.output(), pattern);
		}
		return patterns;
	}

	/** Works out every craft needed, using stock first. Missing raw materials are reported, not guessed. */
	public static Plan plan(StorageNetwork network, ItemKey target, long amount) {
		Map<ItemKey, Long> stock = new HashMap<>();
		network.totals().forEach((key, totals) -> stock.put(key, totals.total()));
		Map<ItemKey, PatternItem.Pattern> patterns = patterns(network);
		List<Step> steps = new ArrayList<>();
		Map<ItemKey, Long> missing = new LinkedHashMap<>();
		PatternItem.Pattern root = patterns.get(target);
		if (root == null) {
			missing.put(target, amount);
			return new Plan(steps, missing);
		}
		long crafts = ceilDiv(amount, root.outputCount());
		root.inputs().forEach((key, count) -> need(key, count * crafts, stock, patterns, steps, missing, new HashSet<>(Set.of(target)), 1));
		steps.add(new Step(root, crafts));
		return new Plan(merge(steps), missing);
	}

	private static void need(ItemKey key, long amount, Map<ItemKey, Long> stock, Map<ItemKey, PatternItem.Pattern> patterns,
			List<Step> steps, Map<ItemKey, Long> missing, Set<ItemKey> path, int depth) {
		long available = stock.getOrDefault(key, 0L);
		long taken = Math.min(available, amount);
		stock.put(key, available - taken);
		long short_ = amount - taken;
		if (short_ <= 0) return;
		PatternItem.Pattern pattern = patterns.get(key);
		if (pattern == null || depth > MAX_DEPTH || path.contains(key)) {
			missing.merge(key, short_, Long::sum);
			return;
		}
		long crafts = ceilDiv(short_, pattern.outputCount());
		path.add(key);
		pattern.inputs().forEach((input, count) -> need(input, count * crafts, stock, patterns, steps, missing, path, depth + 1));
		path.remove(key);
		steps.add(new Step(pattern, crafts));
		stock.merge(key, crafts * pattern.outputCount() - short_, Long::sum);
	}

	/** Adjacent steps for the same pattern become one. */
	private static List<Step> merge(List<Step> steps) {
		List<Step> merged = new ArrayList<>();
		for (Step step : steps) {
			if (!merged.isEmpty() && merged.get(merged.size() - 1).pattern().equals(step.pattern())) {
				Step last = merged.remove(merged.size() - 1);
				merged.add(new Step(step.pattern(), last.crafts() + step.crafts()));
			} else {
				merged.add(step);
			}
		}
		return merged;
	}

	public static Plan start(ServerPlayerEntity player, StorageService.Access access, ItemKey target, long amount) {
		return start(player.getServer(), player.getUuid(), access, target, amount);
	}

	public static Plan start(MinecraftServer server, UUID owner, StorageService.Access access, ItemKey target, long amount) {
		StorageNetwork network = access.resolve(server);
		if (network == null) return new Plan(List.of(), Map.of(target, amount));
		Plan plan = plan(network, target, amount);
		if (plan.ok()) JOBS.computeIfAbsent(server, ignored -> new ArrayList<>()).add(new Job(owner, access, target, amount, plan));
		return plan;
	}

	public static List<Job> jobs(MinecraftServer server, StorageService.Access access) {
		return JOBS.getOrDefault(server, List.of()).stream().filter(job -> sameNetwork(server, job.access, access)).toList();
	}

	public static void cancel(MinecraftServer server, StorageService.Access access) {
		List<Job> jobs = JOBS.get(server);
		if (jobs != null) jobs.removeIf(job -> sameNetwork(server, job.access, access));
	}

	private static boolean sameNetwork(MinecraftServer server, StorageService.Access a, StorageService.Access b) {
		if (a.dimension() != b.dimension()) return false;
		if (a.pos().equals(b.pos())) return true;
		ServerWorld world = server.getWorld(a.dimension());
		return world != null && NetworkManager.get(world).component(a.pos(), NetKind.DATA).contains(b.pos());
	}

	/**
	 * Runs this world's jobs for one simulation step. Each job borrows racks from its own storage network's
	 * cluster first; if that has no free compute, from any online cluster set to lend (racks on another fiber
	 * network reach the storage over the uplink). Borrowed racks are marked CRAFTING in {@code busy}.
	 */
	public static void tick(ServerWorld world, List<Cluster> clusters, Map<MachineBlockEntity, RackStatus> busy, double dt) {
		List<Job> jobs = JOBS.get(world.getServer());
		if (jobs == null || jobs.isEmpty()) return;
		Iterator<Job> iterator = jobs.iterator();
		while (iterator.hasNext()) {
			Job job = iterator.next();
			if (job.access.dimension() != world.getRegistryKey()) continue;
			job.compute = 0;
			if (!job.access.live(world.getServer())) {
				job.status = "asleep";
				continue;
			}
			StorageNetwork network = job.access.resolve(world.getServer());
			if (network == null || network.isEmpty()) {
				job.status = "offline";
				continue;
			}
			double remaining = 0;
			for (int index = job.stepIndex; index < job.steps.size(); index++) remaining += job.steps.get(index).crafts();
			remaining -= job.doneInStep;
			double wanted = Math.min(remaining, MAX_CRAFTS_PER_STEP) / (CRAFTS_PER_COMPUTE_SECOND * dt);
			List<MachineBlockEntity> lenders = new ArrayList<>();
			List<Cluster> local = clusters.stream().filter(cluster -> cluster.contains(job.access.pos())
					&& cluster.policy() == Cluster.Policy.AUTO).toList();
			double compute = Cluster.take(local, Cluster.Kind.GENERAL, wanted, busy, RackStatus.CRAFTING, lenders);
			if (compute < wanted) {
				List<Cluster> remote = clusters.stream().filter(cluster -> !cluster.contains(job.access.pos())
						&& cluster.online() && cluster.policy() == Cluster.Policy.AUTO).toList();
				compute += Cluster.take(remote, Cluster.Kind.GENERAL, wanted - compute, busy, RackStatus.CRAFTING, lenders);
			}
			job.compute = (int) Math.round(compute);
			if (compute <= 0) {
				job.status = "no_compute";
				continue;
			}
			job.budget = Math.min(job.budget + compute * CRAFTS_PER_COMPUTE_SECOND * dt, MAX_CRAFTS_PER_STEP);
			boolean progressed = false;
			while (job.budget >= 1 && job.stepIndex < job.steps.size()) {
				Step step = job.steps.get(job.stepIndex);
				String problem = craftOnce(world, network, job, step.pattern());
				if (problem != null) {
					job.status = problem;
					break;
				}
				progressed = true;
				job.budget -= 1;
				job.craftsDone++;
				if (++job.doneInStep >= step.crafts()) {
					job.stepIndex++;
					job.doneInStep = 0;
				}
				job.status = "crafting";
			}
			// A stalled job gives its racks back so they can mine.
			if (!progressed && !"crafting".equals(job.status)) lenders.forEach(busy::remove);
			if (job.stepIndex >= job.steps.size()) {
				iterator.remove();
				ServerPlayerEntity owner = world.getServer().getPlayerManager().getPlayer(job.owner);
				if (owner != null) {
					owner.sendMessage(Text.translatable("storage.rackcraft.job_done", job.amount, job.target.toStack(1).getName())
							.formatted(Formatting.GREEN), true);
				}
			}
		}
	}

	/** Performs one craft of a pattern. Returns null on success or a status key explaining the stall. */
	private static String craftOnce(ServerWorld world, StorageNetwork network, Job job, PatternItem.Pattern pattern) {
		for (Map.Entry<ItemKey, Long> input : pattern.inputs().entrySet()) {
			if (network.extract(input.getKey(), input.getValue(), true, true) < input.getValue()) return "waiting";
		}
		CraftingInventory grid = new CraftingInventory(NO_HANDLER, 3, 3);
		for (int slot = 0; slot < 9; slot++) {
			ItemKey key = pattern.grid().get(slot);
			if (key == null) continue;
			network.extract(key, 1, true, false);
			grid.setStack(slot, key.toStack(1));
		}
		Optional<CraftingRecipe> recipe = world.getRecipeManager().getFirstMatch(RecipeType.CRAFTING, grid, world);
		if (recipe.isEmpty()) {
			for (int slot = 0; slot < 9; slot++) if (!grid.getStack(slot).isEmpty()) store(world, network, job, grid.getStack(slot));
			return "bad_pattern";
		}
		ItemStack output = recipe.get().craft(grid, world.getRegistryManager());
		store(world, network, job, output);
		for (ItemStack remainder : recipe.get().getRemainder(grid)) if (!remainder.isEmpty()) store(world, network, job, remainder);
		return null;
	}

	private static void store(ServerWorld world, StorageNetwork network, Job job, ItemStack stack) {
		long stored = network.insert(ItemKey.of(stack), stack.getCount(), false);
		if (stored < stack.getCount()) {
			// Storage full: drop the rest at the terminal rather than lose it.
			ItemStack overflow = stack.copyWithCount((int) (stack.getCount() - stored));
			BlockPos at = job.access.pos().up();
			world.spawnEntity(new ItemEntity(world, at.getX() + 0.5, at.getY() + 0.5, at.getZ() + 0.5, overflow));
		}
	}

	private static long ceilDiv(long value, long divisor) {
		return (value + divisor - 1) / divisor;
	}
}
