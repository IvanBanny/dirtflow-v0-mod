package io.github.ivanbanny.world;

import it.unimi.dsi.fastutil.shorts.ShortOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;

import java.util.HashMap;
import java.util.Map;

/** Final preview packets use copied sections; server blocks stay unchanged. */
public final class Preview {
    private record Section(LevelChunkSection blocks, ShortOpenHashSet positions) {}

    private Preview() {}

    public static void send(
            ServerPlayer player, ServerLevel level, Map<BlockPos, BlockState> states) {
        for (BlockPos pos : states.keySet()) {
            if (level.isOutsideBuildHeight(pos) || !level.hasChunkAt(pos))
                throw new IllegalStateException("Preview chunk unloaded or outside build height");
        }
        var sections = new HashMap<SectionPos, Section>();
        for (var entry : states.entrySet()) {
            BlockPos pos = entry.getKey();
            Section section =
                    sections.computeIfAbsent(
                            SectionPos.of(pos),
                            key ->
                                    new Section(
                                            level.getChunkAt(pos)
                                                    .getSection(level.getSectionIndex(pos.getY()))
                                                    .copy(),
                                            new ShortOpenHashSet()));
            section.positions().add(SectionPos.sectionRelativePos(pos));
            section.blocks()
                    .setBlockState(
                            pos.getX() & 15,
                            pos.getY() & 15,
                            pos.getZ() & 15,
                            entry.getValue(),
                            false);
        }
        sections.forEach(
                (pos, section) ->
                        player.connection.send(
                                new ClientboundSectionBlocksUpdatePacket(
                                        pos, section.positions(), section.blocks())));
    }

    public static void clear(ServerPlayer player, ServerLevel level, Edit edit) {
        var states = new HashMap<BlockPos, BlockState>();
        for (BlockPos pos : edit.states().keySet()) {
            if (level.hasChunkAt(pos)) states.put(pos, level.getBlockState(pos));
        }
        send(player, level, states);
        for (BlockPos pos : states.keySet()) {
            var entity = level.getBlockEntity(pos);
            var packet = entity == null ? null : entity.getUpdatePacket();
            if (packet != null) player.connection.send(packet);
        }
    }
}
