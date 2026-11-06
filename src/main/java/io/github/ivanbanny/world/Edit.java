package io.github.ivanbanny.world;

import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import io.github.ivanbanny.Dirtflow;
import io.github.ivanbanny.protocol.VoxelTensor;
import io.github.ivanbanny.protocol.VoxelTensorCodec;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

/** One decoded proposal, shared by preview and acceptance. */
public record Edit(Map<BlockPos, BlockState> states) {
    public Edit {
        // BlockPos hashes cluster in dense cubes; retain a chained map
        states = Collections.unmodifiableMap(new LinkedHashMap<>(states));
    }

    public static BlockState parse(HolderLookup<Block> blocks, String text) {
        try {
            var reader = new StringReader(text);
            var parsed = BlockStateParser.parseForBlock(blocks, reader, false);
            if (reader.canRead() || parsed.nbt() != null)
                throw new IllegalArgumentException("Unexpected block data: " + text);
            return parsed.blockState();
        } catch (CommandSyntaxException error) {
            throw new IllegalArgumentException("Unknown block state: " + text, error);
        }
    }

    public static Edit decode(
            ServerLevel level, VoxelTensor tensor, Region region, BlockState[] original) {
        VoxelTensorCodec.validate(tensor);
        region.padding();
        if (!(tensor instanceof VoxelTensor.Sparse sparse)
                || !region.matches(tensor)
                || original.length != region.volume())
            throw new IllegalArgumentException("Diff must match the selection");
        var blocks = level.registryAccess().lookupOrThrow(Registries.BLOCK);
        BlockState[] palette =
                tensor.palette().stream()
                        .map(text -> parse(blocks, text))
                        .toArray(BlockState[]::new);
        var states = new LinkedHashMap<BlockPos, BlockState>();
        for (int i = 0; i < sparse.entryCount(); i++) {
            int index = sparse.indices()[i];
            BlockState state = palette[sparse.paletteIds()[i]];
            if (state != original[index]) states.put(sparse.worldPos(index), state);
        }
        return new Edit(states);
    }

    /** World access is also the failure-injection boundary for transaction tests. */
    public interface World {
        boolean loaded(BlockPos pos);

        Cell read(BlockPos pos);

        void write(BlockPos pos, BlockState state);

        void restore(BlockPos pos, Cell cell);
    }

    public static World world(ServerLevel level) {
        return new World() {
            public boolean loaded(BlockPos pos) {
                return !level.isOutsideBuildHeight(pos) && level.hasChunkAt(pos);
            }

            public Cell read(BlockPos pos) {
                return Cell.capture(level, pos);
            }

            public void write(BlockPos pos, BlockState state) {
                level.setBlock(pos, state, Cell.PASTE_FLAGS);
            }

            public void restore(BlockPos pos, Cell cell) {
                cell.restore(level, pos);
            }
        };
    }

    public int apply(ServerLevel level, Consumer<Map<BlockPos, Cell>> save) {
        var before = apply(world(level), save);
        for (var entry : before.entrySet()) {
            BlockPos pos = entry.getKey();
            BlockState state = level.getBlockState(pos);
            try {
                // Native placement runs after every cell and the journal have been written
                if (!state.is(entry.getValue().state().getBlock()))
                    state.onPlace(level, pos, entry.getValue().state(), false);
                var fluid = level.getFluidState(pos);
                if (!fluid.isEmpty())
                    level.scheduleTick(pos, fluid.getType(), fluid.getType().getTickDelay(level));
            } catch (RuntimeException error) {
                Dirtflow.LOGGER.warn("Minecraft placement callback failed at {}", pos, error);
            }
        }
        return before.size();
    }

    public Map<BlockPos, Cell> apply(World world, Consumer<Map<BlockPos, Cell>> save) {
        var before = new LinkedHashMap<BlockPos, Cell>();
        for (var entry : states.entrySet()) {
            requireLoaded(world, entry.getKey());
            Cell cell = world.read(entry.getKey());
            if (cell.state() != entry.getValue()) before.put(entry.getKey(), cell);
        }
        var written = new LinkedHashMap<BlockPos, Cell>();
        try {
            for (var entry : before.entrySet()) {
                written.put(entry.getKey(), entry.getValue());
                world.write(entry.getKey(), states.get(entry.getKey()));
                if (world.read(entry.getKey()).state() != states.get(entry.getKey())) {
                    throw new IllegalStateException(
                            "Minecraft rejected paste at " + entry.getKey());
                }
            }
            save.accept(Collections.unmodifiableMap(before));
        } catch (RuntimeException error) {
            rollback(world, written, error);
            throw error;
        }
        return before;
    }

    /** Restores all owned cells, even if they changed after acceptance. */
    public static void restore(
            World world, Map<BlockPos, Cell> cells, Consumer<Map<BlockPos, Cell>> save) {
        cells.keySet().forEach(pos -> requireLoaded(world, pos));
        var before = new LinkedHashMap<BlockPos, Cell>();
        try {
            for (var entry : cells.entrySet()) {
                Cell current = world.read(entry.getKey());
                before.put(entry.getKey(), current);
                if (!current.equals(entry.getValue()))
                    world.restore(entry.getKey(), entry.getValue());
                if (!world.read(entry.getKey()).equals(entry.getValue()))
                    throw new IllegalStateException(
                            "Minecraft rejected restore at " + entry.getKey());
            }
            save.accept(Collections.unmodifiableMap(before));
        } catch (RuntimeException error) {
            rollback(world, before, error);
            throw error;
        }
    }

    private static void requireLoaded(World world, BlockPos pos) {
        if (!world.loaded(pos))
            throw new IllegalStateException("Edit chunk unloaded or outside build height: " + pos);
    }

    private static void rollback(World world, Map<BlockPos, Cell> before, RuntimeException cause) {
        boolean failed = false;
        for (var entry : before.entrySet()) {
            try {
                world.restore(entry.getKey(), entry.getValue());
                if (!world.read(entry.getKey()).equals(entry.getValue()))
                    throw new IllegalStateException("Rollback failed at " + entry.getKey());
            } catch (RuntimeException error) {
                cause.addSuppressed(error);
                failed = true;
            }
        }
        if (failed) {
            Dirtflow.LOGGER.error("Rollback failed", cause);
            throw new IllegalStateException("Rollback failed; check the server log", cause);
        }
    }
}
