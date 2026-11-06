package io.github.ivanbanny.world;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/** Native state and block-entity data for one owned cell. */
public record Cell(BlockState state, CompoundTag data) {
    static final int PASTE_FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_SKIP_ALL_SIDEEFFECTS;

    public Cell {
        if (data != null) data = data.copy();
    }

    @Override
    public CompoundTag data() {
        return data == null ? null : data.copy();
    }

    public static Cell capture(ServerLevel level, BlockPos pos) {
        BlockEntity entity = level.getBlockEntity(pos);
        return new Cell(
                level.getBlockState(pos),
                entity == null ? null : entity.saveWithFullMetadata(level.registryAccess()));
    }

    public void restore(ServerLevel level, BlockPos pos) {
        level.setBlock(pos, state, PASTE_FLAGS);
        level.removeBlockEntity(pos);
        if (data != null) {
            BlockEntity entity =
                    BlockEntity.loadStatic(pos, state, data.copy(), level.registryAccess());
            if (entity == null)
                throw new IllegalStateException("Cannot restore block data at " + pos);
            level.setBlockEntity(entity);
            entity.setChanged();
        }
        level.sendBlockUpdated(pos, state, state, Block.UPDATE_CLIENTS);
    }
}
