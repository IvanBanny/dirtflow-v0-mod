package io.github.ivanbanny.protocol;

import net.minecraft.core.BlockPos;

import java.util.List;

/** Palette tensor in x-fastest order: (z * sizeY + y) * sizeX + x. */
public sealed interface VoxelTensor {
    String AIR = "minecraft:air";

    BlockPos origin();

    int sizeX();

    int sizeY();

    int sizeZ();

    List<String> palette();

    default long totalVoxels() {
        return (long) sizeX() * sizeY() * sizeZ();
    }

    default int linearIndex(int x, int y, int z) {
        return (z * sizeY() + y) * sizeX() + x;
    }

    default int localX(int index) {
        return index % sizeX();
    }

    default int localY(int index) {
        return index / sizeX() % sizeY();
    }

    default int localZ(int index) {
        return index / (sizeX() * sizeY());
    }

    default BlockPos worldPos(int index) {
        return origin().offset(localX(index), localY(index), localZ(index));
    }

    /**
     * Omitted entries are air in a snapshot and unchanged cells in a diff. Arrays are owned by the
     * tensor.
     */
    record Sparse(
            BlockPos origin,
            int sizeX,
            int sizeY,
            int sizeZ,
            List<String> palette,
            int[] indices,
            int[] paletteIds)
            implements VoxelTensor {
        public Sparse {
            origin = origin.immutable();
            palette = List.copyOf(palette);
            if (indices.length != paletteIds.length) {
                throw new IllegalArgumentException("Sparse arrays differ in length");
            }
        }

        public int entryCount() {
            return indices.length;
        }
    }

    /** Runs cover the volume exactly. Arrays are owned by the tensor. */
    record Dense(
            BlockPos origin,
            int sizeX,
            int sizeY,
            int sizeZ,
            List<String> palette,
            int[] runPaletteIds,
            int[] runLengths)
            implements VoxelTensor {
        public Dense {
            origin = origin.immutable();
            palette = List.copyOf(palette);
            if (runPaletteIds.length != runLengths.length) {
                throw new IllegalArgumentException("Dense arrays differ in length");
            }
        }

        public int runCount() {
            return runLengths.length;
        }
    }
}
