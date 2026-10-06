package dev.rackcraft.world;

import dev.rackcraft.block.CableBlock;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.WeakHashMap;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.WorldChunk;

/**
 * Brings cables placed by older Rackcraft versions up to date when their chunk loads: it creates the
 * missing block entity (so the cable joins its network) and fills in the connection states.
 * Work is deferred to the end of the tick because chunks are still being set up during the load event.
 */
public final class CableUpgrader {
	private static final Map<ServerWorld, Deque<ChunkPos>> PENDING = new WeakHashMap<>();

	private CableUpgrader() {}

	public static void register() {
		ServerChunkEvents.CHUNK_LOAD.register((world, chunk) -> {
			if (hasCables(chunk)) PENDING.computeIfAbsent(world, ignored -> new ArrayDeque<>()).add(chunk.getPos());
		});
		ServerTickEvents.END_WORLD_TICK.register(world -> {
			Deque<ChunkPos> pending = PENDING.get(world);
			while (pending != null && !pending.isEmpty()) {
				ChunkPos pos = pending.poll();
				if (world.isChunkLoaded(pos.x, pos.z)) upgrade(world, world.getChunk(pos.x, pos.z));
			}
		});
	}

	private static boolean hasCables(WorldChunk chunk) {
		for (ChunkSection section : chunk.getSectionArray()) {
			if (!section.isEmpty() && section.hasAny(state -> state.getBlock() instanceof CableBlock)) return true;
		}
		return false;
	}

	private static void upgrade(ServerWorld world, WorldChunk chunk) {
		ChunkSection[] sections = chunk.getSectionArray();
		BlockPos.Mutable cursor = new BlockPos.Mutable();
		for (int index = 0; index < sections.length; index++) {
			ChunkSection section = sections[index];
			if (section.isEmpty() || !section.hasAny(state -> state.getBlock() instanceof CableBlock)) continue;
			int baseY = (chunk.getBottomSectionCoord() + index) << 4;
			for (int y = 0; y < 16; y++) {
				for (int z = 0; z < 16; z++) {
					for (int x = 0; x < 16; x++) {
						BlockState state = section.getBlockState(x, y, z);
						if (!(state.getBlock() instanceof CableBlock cable)) continue;
						cursor.set(chunk.getPos().getStartX() + x, baseY + y, chunk.getPos().getStartZ() + z);
						world.getBlockEntity(cursor);
						BlockState connected = cable.withConnections(state, world, cursor);
						if (connected != state) world.setBlockState(cursor, connected, Block.NOTIFY_LISTENERS);
					}
				}
			}
		}
	}
}
