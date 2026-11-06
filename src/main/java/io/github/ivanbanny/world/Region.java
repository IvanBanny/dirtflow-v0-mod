package io.github.ivanbanny.world;

import net.minecraft.core.BlockPos;

/** Inclusive, ordered block bounds. */
public record Region(BlockPos min, BlockPos max) {
    public static final int MAX_AXIS = 58;

    public Region {
        min = min.immutable();
        max = max.immutable();
        if (min.getX() > max.getX() || min.getY() > max.getY() || min.getZ() > max.getZ()) {
            throw new IllegalArgumentException("Region corners are reversed");
        }
    }

    public static Region between(BlockPos a, BlockPos b) {
        return new Region(
                new BlockPos(
                        Math.min(a.getX(), b.getX()),
                        Math.min(a.getY(), b.getY()),
                        Math.min(a.getZ(), b.getZ())),
                new BlockPos(
                        Math.max(a.getX(), b.getX()),
                        Math.max(a.getY(), b.getY()),
                        Math.max(a.getZ(), b.getZ())));
    }

    public long sizeX() {
        return (long) max.getX() - min.getX() + 1;
    }

    public long sizeY() {
        return (long) max.getY() - min.getY() + 1;
    }

    public long sizeZ() {
        return (long) max.getZ() - min.getZ() + 1;
    }

    public long volume() {
        return Math.multiplyExact(Math.multiplyExact(sizeX(), sizeY()), sizeZ());
    }

    /** Thickest trained shell that fits on the model's 64-cube. */
    public int padding() {
        long axis = Math.max(sizeX(), Math.max(sizeY(), sizeZ()));
        for (int radius : new int[] {8, 5, 3}) {
            if (axis + 2 * radius <= 64) return radius;
        }
        throw new IllegalArgumentException("Selection exceeds 58 blocks per axis");
    }

    public Region expand(int padding) {
        return new Region(
                new BlockPos(
                        Math.subtractExact(min.getX(), padding),
                        Math.subtractExact(min.getY(), padding),
                        Math.subtractExact(min.getZ(), padding)),
                new BlockPos(
                        Math.addExact(max.getX(), padding),
                        Math.addExact(max.getY(), padding),
                        Math.addExact(max.getZ(), padding)));
    }

    public boolean matches(io.github.ivanbanny.protocol.VoxelTensor tensor) {
        return min.equals(tensor.origin())
                && sizeX() == tensor.sizeX()
                && sizeY() == tensor.sizeY()
                && sizeZ() == tensor.sizeZ();
    }

    public String describe() {
        return min.toShortString()
                + " -> "
                + max.toShortString()
                + " ("
                + sizeX()
                + " x "
                + sizeY()
                + " x "
                + sizeZ()
                + ")";
    }
}
