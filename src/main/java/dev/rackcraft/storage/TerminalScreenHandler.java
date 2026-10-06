package dev.rackcraft.storage;

import dev.rackcraft.RcBlocks;
import dev.rackcraft.RcItems;
import dev.rackcraft.screen.RcScreenHandlers;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.inventory.CraftingInventory;
import net.minecraft.inventory.CraftingResultInventory;
import net.minecraft.inventory.Inventory;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.recipe.CraftingRecipe;
import net.minecraft.recipe.RecipeType;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.CraftingResultSlot;
import net.minecraft.screen.slot.Slot;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;

/**
 * The Storage Terminal (block or wireless). Network contents are virtual: the server sends a snapshot every
 * half second and the client sends {@link Action}s back. Real slots: a 3x3 crafting grid that refills
 * itself from storage, the result, a blank/encoded pattern pair, and the player's inventory.
 */
public final class TerminalScreenHandler extends ScreenHandler {
	public static final Identifier SYNC = dev.rackcraft.Rackcraft.id("terminal_sync");
	public static final Identifier ACTION = dev.rackcraft.Rackcraft.id("terminal_action");
	public static final int WIDTH = 194;
	public static final int HEIGHT = 274;
	public static final int CRAFT_X = 8;
	public static final int CRAFT_Y = 122;
	public static final int RESULT_X = 90;
	public static final int RESULT_Y = 140;
	public static final int BLANK_X = 120;
	public static final int ENCODED_X = 166;
	public static final int PATTERN_Y = 140;
	public static final int INVENTORY_X = 17;
	public static final int INVENTORY_Y = 192;
	private static final int RESULT_SLOT = 9;
	private static final int BLANK_SLOT = 10;
	private static final int ENCODED_SLOT = 11;
	private static final int FIRST_PLAYER_SLOT = 12;
	private static final int SYNC_INTERVAL = 10;

	public enum Action { CLICK, CRAFT, CANCEL, CLEAR, ENCODE }

	public record Entry(ItemKey key, long hot, long cold, boolean craftable) {
		public long total() { return hot + cold; }
	}

	public record Stats(boolean online, long hotUsed, long hotCapacity, long coldUsed, long coldCapacity, int drives, int tapes) {}

	public record JobView(ItemKey target, long amount, long done, long total, String status, int compute) {}

	private final StorageService.Access access;
	private final PlayerEntity player;
	private final CraftingInventory craftGrid = new CraftingInventory(this, 3, 3);
	private final CraftingResultInventory result = new CraftingResultInventory();
	private final SimpleInventory patternSlots = new SimpleInventory(2);
	private int syncCountdown;
	private int lastSyncHash;
	private int forcedSyncCountdown;

	// Client-side mirror of the network.
	private List<Entry> entries = List.of();
	private Stats stats = new Stats(false, 0, 0, 0, 0, 0, 0);
	private List<JobView> jobs = List.of();
	private int revision;

	public TerminalScreenHandler(int syncId, PlayerInventory inventory, StorageService.Access access) {
		super(RcScreenHandlers.TERMINAL, syncId);
		this.access = access;
		this.player = inventory.player;
		for (int row = 0; row < 3; row++) {
			for (int column = 0; column < 3; column++) {
				addSlot(new Slot(craftGrid, column + row * 3, CRAFT_X + column * 18, CRAFT_Y + row * 18));
			}
		}
		addSlot(new RefillingResultSlot(inventory.player, craftGrid, result, 0, RESULT_X, RESULT_Y));
		addSlot(new Slot(patternSlots, 0, BLANK_X, PATTERN_Y) {
			@Override public boolean canInsert(ItemStack stack) { return stack.isOf(RcItems.ITEMS.get("blank_pattern")); }
		});
		addSlot(new Slot(patternSlots, 1, ENCODED_X, PATTERN_Y) {
			@Override public boolean canInsert(ItemStack stack) { return false; }
		});
		for (int row = 0; row < 3; row++) {
			for (int column = 0; column < 9; column++) {
				addSlot(new Slot(inventory, 9 + row * 9 + column, INVENTORY_X + column * 18, INVENTORY_Y + row * 18));
			}
		}
		for (int column = 0; column < 9; column++) addSlot(new Slot(inventory, column, INVENTORY_X + column * 18, INVENTORY_Y + 58));
	}

	public StorageService.Access access() { return access; }
	public List<Entry> entries() { return entries; }
	public Stats stats() { return stats; }
	public List<JobView> jobs() { return jobs; }
	public int revision() { return revision; }

	private StorageNetwork network() {
		return player instanceof ServerPlayerEntity serverPlayer ? access.resolve(serverPlayer.getServer()) : null;
	}

	// ---------------------------------------------------------------- crafting grid

	@Override
	public void onContentChanged(Inventory inventory) {
		if (inventory != craftGrid || !(player.getWorld() instanceof ServerWorld world)) return;
		Optional<CraftingRecipe> recipe = world.getRecipeManager().getFirstMatch(RecipeType.CRAFTING, craftGrid, world);
		result.setLastRecipe(recipe.orElse(null));
		result.setStack(0, recipe.map(match -> match.craft(craftGrid, world.getRegistryManager())).orElse(ItemStack.EMPTY));
		sendContentUpdates();
	}

	/** After a craft, refill each emptied grid slot with the same item from storage. */
	private final class RefillingResultSlot extends CraftingResultSlot {
		RefillingResultSlot(PlayerEntity player, CraftingInventory input, Inventory inventory, int index, int x, int y) {
			super(player, input, inventory, index, x, y);
		}

		@Override
		public void onTakeItem(PlayerEntity player, ItemStack stack) {
			List<ItemKey> before = new ArrayList<>(9);
			for (int slot = 0; slot < 9; slot++) {
				ItemStack inGrid = craftGrid.getStack(slot);
				before.add(inGrid.isEmpty() ? null : ItemKey.of(inGrid));
			}
			super.onTakeItem(player, stack);
			StorageNetwork network = network();
			if (network == null) return;
			for (int slot = 0; slot < 9; slot++) {
				ItemKey key = before.get(slot);
				if (key == null || !craftGrid.getStack(slot).isEmpty()) continue;
				if (network.extract(key, 1, false, false) == 1) craftGrid.setStack(slot, key.toStack(1));
			}
		}
	}

	// ---------------------------------------------------------------- shift-click and closing

	@Override
	public ItemStack quickMove(PlayerEntity player, int index) {
		Slot slot = slots.get(index);
		if (!slot.hasStack()) return ItemStack.EMPTY;
		ItemStack stack = slot.getStack();
		if (index == RESULT_SLOT) {
			// Craft as many as fit, like a crafting table.
			ItemStack first = stack.copy();
			for (int crafts = 0; crafts < 64 && slot.hasStack() && ItemStack.canCombine(slot.getStack(), first); crafts++) {
				ItemStack crafted = slot.getStack().copy();
				if (!insertItem(crafted, FIRST_PLAYER_SLOT, slots.size(), true)) break;
				slot.onTakeItem(player, slot.getStack().copy());
			}
			return ItemStack.EMPTY;
		}
		if (index < FIRST_PLAYER_SLOT) {
			if (insertItem(stack, FIRST_PLAYER_SLOT, slots.size(), true)) slot.markDirty();
			return ItemStack.EMPTY;
		}
		// From the player's inventory: blank patterns go to the pattern slot, everything else into storage.
		if (stack.isOf(RcItems.ITEMS.get("blank_pattern")) && insertItem(stack, BLANK_SLOT, BLANK_SLOT + 1, false)) {
			slot.markDirty();
			return ItemStack.EMPTY;
		}
		StorageNetwork network = network();
		if (network != null) {
			long stored = network.insert(ItemKey.of(stack), stack.getCount(), false);
			stack.decrement((int) stored);
			slot.markDirty();
		}
		return ItemStack.EMPTY;
	}

	@Override
	public void onClosed(PlayerEntity player) {
		super.onClosed(player);
		if (player.getWorld().isClient) return;
		StorageNetwork network = network();
		for (int slot = 0; slot < 9; slot++) {
			ItemStack stack = craftGrid.removeStack(slot);
			if (stack.isEmpty()) continue;
			if (network != null) stack.decrement((int) network.insert(ItemKey.of(stack), stack.getCount(), false));
			if (!stack.isEmpty()) player.getInventory().offerOrDrop(stack);
		}
		dropInventory(player, patternSlots);
	}

	@Override
	public boolean canUse(PlayerEntity player) {
		if (access.wireless()) return WirelessTerminalItem.reach(player, access) == null;
		return player.getWorld().getRegistryKey() == access.dimension()
				&& player.getWorld().getBlockState(access.pos()).isOf(RcBlocks.get("storage_terminal"))
				&& player.squaredDistanceTo(access.pos().toCenterPos()) <= 64;
	}

	// ---------------------------------------------------------------- actions from the client

	public void handle(ServerPlayerEntity player, Action action, PacketByteBuf buf) {
		StorageNetwork network = network();
		switch (action) {
			case CLICK -> {
				ItemKey key = buf.readBoolean() ? ItemKey.read(buf) : null;
				int button = buf.readVarInt();
				boolean shift = buf.readBoolean();
				if (network != null) click(player, network, key, button, shift);
			}
			case CRAFT -> {
				ItemKey key = ItemKey.read(buf);
				long amount = Math.max(1, Math.min(10_000, buf.readVarLong()));
				Autocrafter.Plan plan = Autocrafter.start(player, access, key, amount);
				Text name = key.toStack(1).getName();
				if (plan.ok()) {
					player.sendMessage(Text.translatable("storage.rackcraft.job_started", amount, name, plan.totalCrafts())
							.formatted(Formatting.GREEN), true);
				} else if (plan.steps().isEmpty() && plan.missing().containsKey(key)) {
					player.sendMessage(Text.translatable("storage.rackcraft.no_pattern", name).formatted(Formatting.RED), false);
				} else {
					player.sendMessage(Text.translatable("storage.rackcraft.job_missing", name, describe(plan.missing()))
							.formatted(Formatting.RED), false);
				}
			}
			case CANCEL -> Autocrafter.cancel(player.getServer(), access);
			case CLEAR -> {
				for (int slot = 0; slot < 9; slot++) {
					ItemStack stack = craftGrid.getStack(slot);
					if (stack.isEmpty() || network == null) continue;
					stack.decrement((int) network.insert(ItemKey.of(stack), stack.getCount(), false));
					craftGrid.setStack(slot, stack.isEmpty() ? ItemStack.EMPTY : stack);
				}
			}
			case ENCODE -> encode(player);
		}
		forcedSyncCountdown = 0;
		syncCountdown = 0;
		sendContentUpdates();
	}

	private void click(ServerPlayerEntity player, StorageNetwork network, ItemKey key, int button, boolean shift) {
		ItemStack cursor = getCursorStack();
		if (!cursor.isEmpty()) {
			int amount = button == 1 ? 1 : cursor.getCount();
			cursor.decrement((int) network.insert(ItemKey.of(cursor), amount, false));
			setCursorStack(cursor);
			return;
		}
		if (key == null) return;
		long hot = network.count(key, false);
		long cold = network.count(key, true) - hot;
		int wanted = key.maxStackSize();
		if (button == 1) wanted = (int) Math.max(1, (Math.min(hot, wanted) + 1) / 2);
		if (hot > 0) {
			ItemStack taken = key.toStack(network.extract(key, wanted, false, false));
			if (shift) {
				player.getInventory().insertStack(taken);
				if (!taken.isEmpty()) network.insert(ItemKey.of(taken), taken.getCount(), false);
			} else {
				setCursorStack(taken);
			}
		} else if (cold > 0) {
			StorageService.requestRecall(player, access, key, Math.min(wanted, cold));
		}
	}

	private void encode(ServerPlayerEntity player) {
		ItemStack blank = patternSlots.getStack(0);
		ItemStack output = result.getStack(0);
		if (!blank.isOf(RcItems.ITEMS.get("blank_pattern")) || output.isEmpty() || !patternSlots.getStack(1).isEmpty()) return;
		List<ItemStack> grid = new ArrayList<>(9);
		for (int slot = 0; slot < 9; slot++) grid.add(craftGrid.getStack(slot).copyWithCount(1));
		ItemStack pattern = new ItemStack(RcItems.ITEMS.get("recipe_pattern"));
		PatternItem.encode(pattern, grid, output);
		blank.decrement(1);
		patternSlots.setStack(1, pattern);
		player.sendMessage(Text.translatable("storage.rackcraft.encoded").formatted(Formatting.GREEN), true);
	}

	private static Text describe(Map<ItemKey, Long> missing) {
		var text = Text.empty();
		int shown = 0;
		for (Map.Entry<ItemKey, Long> entry : missing.entrySet()) {
			if (shown++ > 0) text.append(", ");
			if (shown > 4) {
				text.append("...");
				break;
			}
			text.append(entry.getValue() + " x ").append(entry.getKey().toStack(1).getName());
		}
		return text;
	}

	// ---------------------------------------------------------------- syncing the virtual contents

	@Override
	public void sendContentUpdates() {
		super.sendContentUpdates();
		if (!(player instanceof ServerPlayerEntity serverPlayer) || --syncCountdown > 0) return;
		syncCountdown = SYNC_INTERVAL;
		StorageNetwork network = network();
		List<Entry> snapshot = new ArrayList<>();
		Stats current;
		List<JobView> jobViews = new ArrayList<>();
		if (network == null) {
			current = new Stats(false, 0, 0, 0, 0, 0, 0);
		} else {
			Map<ItemKey, PatternItem.Pattern> patterns = Autocrafter.patterns(network);
			network.totals().forEach((key, totals) -> snapshot.add(new Entry(key, totals.hot(), totals.cold(), patterns.containsKey(key))));
			for (ItemKey output : patterns.keySet()) {
				if (snapshot.stream().noneMatch(entry -> entry.key().equals(output))) snapshot.add(new Entry(output, 0, 0, true));
			}
			current = new Stats(true, network.hotUsed(), network.hotCapacity(), network.coldUsed(), network.coldCapacity(),
					network.hotDrives(), network.tapes());
			for (Autocrafter.Job job : Autocrafter.jobs(serverPlayer.getServer(), access)) {
				jobViews.add(new JobView(job.target(), job.amount(), job.craftsDone(), job.totalCrafts(), job.status(), job.compute()));
			}
		}
		int hash = java.util.Objects.hash(snapshot, current, jobViews);
		if (hash == lastSyncHash && --forcedSyncCountdown > 0) return;
		lastSyncHash = hash;
		forcedSyncCountdown = 8;
		PacketByteBuf buf = PacketByteBufs.create();
		buf.writeVarInt(syncId);
		buf.writeBoolean(current.online());
		buf.writeVarLong(current.hotUsed());
		buf.writeVarLong(current.hotCapacity());
		buf.writeVarLong(current.coldUsed());
		buf.writeVarLong(current.coldCapacity());
		buf.writeVarInt(current.drives());
		buf.writeVarInt(current.tapes());
		buf.writeVarInt(snapshot.size());
		for (Entry entry : snapshot) {
			entry.key().write(buf);
			buf.writeVarLong(entry.hot());
			buf.writeVarLong(entry.cold());
			buf.writeBoolean(entry.craftable());
		}
		buf.writeVarInt(jobViews.size());
		for (JobView job : jobViews) {
			job.target().write(buf);
			buf.writeVarLong(job.amount());
			buf.writeVarLong(job.done());
			buf.writeVarLong(job.total());
			buf.writeString(job.status(), 32);
			buf.writeVarInt(job.compute());
		}
		ServerPlayNetworking.send(serverPlayer, SYNC, buf);
	}

	/** Client: apply a snapshot sent by {@link #sendContentUpdates()}. */
	public void applySync(PacketByteBuf buf) {
		Stats received = new Stats(buf.readBoolean(), buf.readVarLong(), buf.readVarLong(), buf.readVarLong(), buf.readVarLong(),
				buf.readVarInt(), buf.readVarInt());
		int count = buf.readVarInt();
		List<Entry> list = new ArrayList<>(count);
		for (int index = 0; index < count; index++) {
			list.add(new Entry(ItemKey.read(buf), buf.readVarLong(), buf.readVarLong(), buf.readBoolean()));
		}
		int jobCount = buf.readVarInt();
		List<JobView> jobList = new ArrayList<>(jobCount);
		for (int index = 0; index < jobCount; index++) {
			jobList.add(new JobView(ItemKey.read(buf), buf.readVarLong(), buf.readVarLong(), buf.readVarLong(),
					buf.readString(32), buf.readVarInt()));
		}
		stats = received;
		entries = list;
		jobs = jobList;
		revision++;
	}

	/** Server: route an action packet to the player's open terminal. */
	public static void registerServer() {
		ServerPlayNetworking.registerGlobalReceiver(ACTION, (server, player, handler, buf, responseSender) -> {
			int syncId = buf.readVarInt();
			Action action = Action.values()[Math.max(0, Math.min(Action.values().length - 1, buf.readVarInt()))];
			PacketByteBuf copy = PacketByteBufs.copy(buf);
			server.execute(() -> {
				if (player.currentScreenHandler instanceof TerminalScreenHandler terminal && terminal.syncId == syncId
						&& terminal.canUse(player)) {
					terminal.handle(player, action, copy);
				}
			});
		});
	}
}
