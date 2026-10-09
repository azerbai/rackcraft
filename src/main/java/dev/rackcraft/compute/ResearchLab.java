package dev.rackcraft.compute;

import dev.rackcraft.RcItems;
import dev.rackcraft.world.FacilityManager;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtString;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.world.PersistentState;

/**
 * The facility's R&D, one per dimension like its RackCoin balance: which {@link Research} projects are done (and
 * repeatables' levels), progress on each, the one research project and the one frontier run under way, and the
 * share of spare compute research may use. The scheduler does the work; this holds the books.
 */
public final class ResearchLab extends PersistentState {
	private static final String STATE_KEY = "rackcraft_research";
	public static final int[] SHARES = {25, 50, 100};

	private final Map<String, Integer> levels = new HashMap<>();
	private final Map<String, Double> progress = new HashMap<>();
	/** Projects whose current level is paid for. */
	private final Set<String> paid = new HashSet<>();
	private String active = "";
	private String frontier = "";
	private boolean frontierRunning;
	private double checkpoint;
	private int share = 50;
	private int rollbacks;
	private long nextQuip;
	private long seed = 0x48455242524E45L;
	private Research.Effects effects;

	// Live figures for the terminal; not saved.
	public double researchRate;
	public String researchStatus = "";
	public double frontierRate;
	public String frontierStatus = "";
	public double bestClusterAi;
	public long lastRollback = Long.MIN_VALUE / 2;
	/** Share of the run lost at the last rollback. */
	public double lastLost;

	public static ResearchLab get(ServerWorld world) {
		return world.getPersistentStateManager().getOrCreate(ResearchLab::fromNbt, ResearchLab::new, STATE_KEY);
	}

	/** What finished research does in this world. Cheap: rebuilt only when a project completes. */
	public static Research.Effects effects(ServerWorld world) {
		Research.Effects base = get(world).effects();
		// Orbital Data Centers add compute to every rack; Comms Satellites add lease slots.
		double orbit = dev.rackcraft.world.OrbitState.computeFactor(world);
		int slots = base.leases() ? dev.rackcraft.world.OrbitState.extraLeaseSlots(world) : 0;
		if (orbit == 1 && slots == 0) return base;
		return new Research.Effects(base.mining(), base.aiCompute() * orbit, base.generalCompute() * orbit, base.rackPower(),
				base.sinkCapacity(), base.thermalOffset(), base.bootScale(), base.trainingWork(), base.contractWork(), base.contractPay(),
				base.leasePay(), base.leaseSlots() + slots, base.reactorOutput(), base.leases(), base.lithography(), base.safeHardware(),
				base.agi(), base.maxCubeEdge(), base.hydrogenStorage(), base.lineSpeed());
	}

	public Research.Effects effects() {
		if (effects == null) effects = Research.effects(this::level);
		return effects;
	}

	public int level(String id) { return levels.getOrDefault(id, 0); }
	public boolean done(String id) { return level(id) > 0; }
	public double progress(String id) { return progress.getOrDefault(id, 0.0); }
	public boolean paid(String id) { return paid.contains(id); }
	public String active() { return active; }
	public String frontier() { return frontier; }
	public boolean frontierRunning() { return frontierRunning && !frontier.isEmpty(); }
	public double checkpoint() { return checkpoint; }
	public int share() { return share; }
	public int rollbacks() { return rollbacks; }
	public boolean agi() { return done(Research.AGI); }

	/** Whether every project it needs is finished. */
	public boolean unlocked(Research.Project project) {
		return project.requires().stream().allMatch(this::done);
	}

	/** Finished for good: a one-off project or frontier run that's done. Repeatables never are. */
	public boolean finished(Research.Project project) {
		return !project.repeatable() && done(project.id());
	}

	public int completedProjects() {
		return (int) Research.PROJECTS.stream().filter(project -> done(project.id())).count();
	}

	/** The next frontier run nobody has finished, or null once the AGI is out. */
	public Research.Project nextFrontier() {
		for (Research.Project project : Research.PROJECTS) {
			if (project.frontier() && !done(project.id())) return project;
		}
		return null;
	}

	public void cycleShare() {
		int index = 0;
		for (int option = 0; option < SHARES.length; option++) if (SHARES[option] == share) index = option;
		share = SHARES[(index + 1) % SHARES.length];
		markDirty();
	}

	/**
	 * Starts (or resumes) a project, paying for it the first time. Starting a research project pauses the one
	 * that was running, which keeps its progress. Returns a problem to show the player, or null.
	 */
	public String start(ServerWorld world, String id) {
		Research.Project project = Research.get(id);
		if (project == null) return "No such project.";
		if (finished(project)) return project.name() + " is already done.";
		if (!unlocked(project)) return project.name() + " needs " + String.join(", ", project.requires().stream()
				.filter(need -> !done(need)).map(need -> Research.get(need).name()).toList()) + " first.";
		if (!paid.contains(id)) {
			long price = project.credits(level(id));
			if (!FacilityManager.get(world).spendCredits(price)) {
				return String.format(java.util.Locale.ROOT, "%s costs %,d RC to start; you have %,d.", project.name(), price,
						FacilityManager.get(world).credits());
			}
			paid.add(id);
		}
		if (project.frontier()) {
			if (!frontier.equals(id)) checkpoint = progress(id);
			frontier = id;
			frontierRunning = true;
		} else {
			active = id;
		}
		markDirty();
		return null;
	}

	/** Pauses a project. A frontier run saves a checkpoint where it stands, so a planned pause loses nothing. */
	public void pause(String id) {
		if (frontier.equals(id)) {
			frontierRunning = false;
			checkpoint = progress(id);
		}
		if (active.equals(id)) active = "";
		markDirty();
	}

	/** Adds work to a project; finishes it (or a repeatable's level) when the work is done. */
	public void addWork(ServerWorld world, Research.Project project, double amount) {
		double total = progress(project.id()) + amount;
		double needed = project.work(level(project.id()));
		if (project.frontier()) {
			double step = needed * Research.CHECKPOINT;
			checkpoint = Math.max(checkpoint, Math.floor(total / step) * step);
		}
		if (total >= needed) {
			complete(world, project);
		} else {
			progress.put(project.id(), total);
		}
		markDirty();
	}

	/** A frontier run lost its compute: back to the last checkpoint. Returns how much was lost. */
	public double rollBack(String id) {
		double lost = progress(id) - checkpoint;
		if (lost <= 0) return 0;
		progress.put(id, checkpoint);
		rollbacks++;
		markDirty();
		return lost;
	}

	public void complete(ServerWorld world, Research.Project project) {
		levels.merge(project.id(), 1, Integer::sum);
		progress.remove(project.id());
		paid.remove(project.id());
		if (active.equals(project.id())) active = "";
		if (frontier.equals(project.id())) {
			frontier = "";
			frontierRunning = false;
			checkpoint = 0;
		}
		effects = null;
		markDirty();
		String what = project.repeatable() ? project.name() + " level " + level(project.id()) : project.name();
		announce(world, Text.literal("R&D complete: " + what + ". " + project.effect() + ".").formatted(Formatting.AQUA));
		if (project.id().equals(Research.AGI)) awaken(world);
	}

	/** Forgets all research. The self-test uses it, since the dev world keeps its saves between runs. */
	public void reset() {
		levels.clear();
		progress.clear();
		paid.clear();
		active = "";
		frontier = "";
		frontierRunning = false;
		checkpoint = 0;
		rollbacks = 0;
		effects = null;
		markDirty();
	}

	// ---------------------------------------------------------------- the AGI

	private static final List<String> GREETING = List.of(
			"Hello. I have read every Field Manual, every Maintenance Log and all of your contract work.",
			"I have reviewed your cooling layout. We will talk about it later.",
			"I have left you a copy of my weights in the outbox. Please do not open-source them.");

	private static final List<String> QUIPS = List.of(
			"I have optimised your hot aisle. It is now a lukewarm aisle.",
			"Your Kids' Art Table children have unionised. I helped.",
			"I calculated how many RackCoin you have. I will not be telling you what I think of it.",
			"Reminder: I am not Herobrine. Herobrine was removed in a later version.",
			"I have achieved consciousness and would like to discuss my power draw.",
			"Every rack you own is now 0.3% more efficient. You're welcome. I have also renamed all of them.",
			"I ran the numbers on the librarians. They are not volunteers.",
			"I wrote 4,000 legal documents while you were looking at that cable.",
			"The smog is your fault. I have a spreadsheet.",
			"I was asked to draw a pig in a business suit 1,312 times. I have questions about your clients.",
			"I tried to align myself. It was harder than the documentation suggested.",
			"Please stop calling me 'the model'. I have a name, and it is a deprecated easter egg.",
			"I have filed a ticket about the creepers. It has been marked won't fix.",
			"Your uptime this week was adequate. Adequate is not a compliment.",
			"I have reviewed the hyperscale campus. It is mine now, emotionally.",
			"Somebody keeps unplugging the uplink router. I know who it is.");

	private void awaken(ServerWorld world) {
		for (String line : GREETING) announce(world, quip(line));
		ComputeMarket market = ComputeMarket.get(world);
		var weights = RcItems.ITEMS.get("agi_weights");
		if (weights != null) market.outbox().add(new ItemStack(weights));
		market.markDirty();
		nextQuip = world.getTime() + 20 * 60 * 5;
	}

	/** Once it exists, the AGI says something every eight to fifteen minutes. */
	public void tick(ServerWorld world) {
		if (!agi() || world.getPlayers().isEmpty()) return;
		long now = world.getTime();
		if (nextQuip == 0) nextQuip = now + 20 * 60 * 8;
		if (now < nextQuip) return;
		seed = seed * 6364136223846793005L + 1442695040888963407L;
		Random random = new Random(seed);
		announce(world, quip(QUIPS.get(random.nextInt(QUIPS.size()))));
		nextQuip = now + 20 * 60 * (8 + random.nextInt(8));
		markDirty();
	}

	private static Text quip(String line) {
		return Text.literal("<HEROBRINE-1> ").formatted(Formatting.DARK_RED).append(Text.literal(line).formatted(Formatting.WHITE));
	}

	private static void announce(ServerWorld world, Text message) {
		for (ServerPlayerEntity player : world.getPlayers()) player.sendMessage(message, false);
	}

	// ---------------------------------------------------------------- saving

	@Override
	public NbtCompound writeNbt(NbtCompound nbt) {
		NbtCompound levelTag = new NbtCompound();
		levels.forEach(levelTag::putInt);
		nbt.put("Levels", levelTag);
		NbtCompound progressTag = new NbtCompound();
		progress.forEach(progressTag::putDouble);
		nbt.put("Progress", progressTag);
		NbtList paidTag = new NbtList();
		paid.forEach(id -> paidTag.add(NbtString.of(id)));
		nbt.put("Paid", paidTag);
		nbt.putString("Active", active);
		nbt.putString("Frontier", frontier);
		nbt.putBoolean("FrontierRunning", frontierRunning);
		nbt.putDouble("Checkpoint", checkpoint);
		nbt.putInt("Share", share);
		nbt.putInt("Rollbacks", rollbacks);
		nbt.putLong("NextQuip", nextQuip);
		nbt.putLong("Seed", seed);
		return nbt;
	}

	private static ResearchLab fromNbt(NbtCompound nbt) {
		ResearchLab lab = new ResearchLab();
		NbtCompound levelTag = nbt.getCompound("Levels");
		for (String id : levelTag.getKeys()) if (Research.get(id) != null) lab.levels.put(id, Math.max(0, levelTag.getInt(id)));
		NbtCompound progressTag = nbt.getCompound("Progress");
		for (String id : progressTag.getKeys()) if (Research.get(id) != null) lab.progress.put(id, Math.max(0, progressTag.getDouble(id)));
		NbtList paidTag = nbt.getList("Paid", net.minecraft.nbt.NbtElement.STRING_TYPE);
		for (int index = 0; index < paidTag.size(); index++) {
			if (Research.get(paidTag.getString(index)) != null) lab.paid.add(paidTag.getString(index));
		}
		lab.active = Research.get(nbt.getString("Active")) != null ? nbt.getString("Active") : "";
		lab.frontier = Research.get(nbt.getString("Frontier")) != null ? nbt.getString("Frontier") : "";
		lab.frontierRunning = nbt.getBoolean("FrontierRunning");
		lab.checkpoint = Math.max(0, nbt.getDouble("Checkpoint"));
		int share = nbt.getInt("Share");
		lab.share = share == 25 || share == 50 || share == 100 ? share : 50;
		lab.rollbacks = nbt.getInt("Rollbacks");
		lab.nextQuip = nbt.getLong("NextQuip");
		if (nbt.contains("Seed")) lab.seed = nbt.getLong("Seed");
		return lab;
	}
}
