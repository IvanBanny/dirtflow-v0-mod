package io.github.ivanbanny.world;

import io.github.ivanbanny.protocol.VoxelTensor;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;

/** Captures a loaded, padded region on the server thread. */
public record Snapshot(VoxelTensor tensor, BlockState[] selected) {
    public static Snapshot capture(ServerLevel level, Region region) {
        int padding = region.padding();
        Region capture = region.expand(padding);
        int sx = (int) capture.sizeX(), sy = (int) capture.sizeY(), sz = (int) capture.sizeZ();
        if (level.isOutsideBuildHeight(capture.min())
                || level.isOutsideBuildHeight(capture.max())) {
            throw new IllegalArgumentException(
                    "Selection and context must fit within build height");
        }
        for (int z = capture.min().getZ() >> 4; z <= capture.max().getZ() >> 4; z++) {
            for (int x = capture.min().getX() >> 4; x <= capture.max().getX() >> 4; x++) {
                if (!level.hasChunk(x, z))
                    throw new IllegalArgumentException(
                            "Selection and context chunks must be loaded");
            }
        }
        var palette = new ArrayList<String>();
        palette.add(VoxelTensor.AIR);
        var ids = new HashMap<BlockState, Integer>();
        int[] cells = new int[sx * sy * sz];
        BlockState[] selected = new BlockState[(int) region.volume()];
        var pos = new BlockPos.MutableBlockPos();
        int cursor = 0, editCursor = 0;
        for (int z = 0; z < sz; z++) {
            for (int y = 0; y < sy; y++) {
                for (int x = 0; x < sx; x++) {
                    pos.set(
                            capture.min().getX() + x,
                            capture.min().getY() + y,
                            capture.min().getZ() + z);
                    BlockState state = level.getBlockState(pos);
                    cells[cursor++] =
                            state.isAir()
                                    ? 0
                                    : ids.computeIfAbsent(
                                            state,
                                            value -> {
                                                palette.add(BlockStateParser.serialize(value));
                                                return palette.size() - 1;
                                            });
                    if (x >= padding
                            && x < sx - padding
                            && y >= padding
                            && y < sy - padding
                            && z >= padding
                            && z < sz - padding) selected[editCursor++] = state;
                }
            }
        }
        int[] runIds = new int[cells.length], lengths = new int[cells.length];
        int runs = 0;
        for (int start = 0; start < cells.length; ) {
            int end = start + 1;
            while (end < cells.length && end - start < 65535 && cells[end] == cells[start]) end++;
            runIds[runs] = cells[start];
            lengths[runs++] = end - start;
            start = end;
        }
        return new Snapshot(
                new VoxelTensor.Dense(
                        capture.min(),
                        sx,
                        sy,
                        sz,
                        palette,
                        Arrays.copyOf(runIds, runs),
                        Arrays.copyOf(lengths, runs)),
                selected);
    }
}
