package io.github.ivanbanny;

import io.github.ivanbanny.world.Cell;
import io.github.ivanbanny.world.Edit;
import io.github.ivanbanny.world.History;
import io.github.ivanbanny.world.Region;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

class WorldTest {
    @TempDir Path directory;

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private static final class World implements Edit.World {
        final Map<BlockPos, Cell> cells = new HashMap<>();
        BlockPos unloaded, fail;
        int writes;

        public boolean loaded(BlockPos pos) {
            return !pos.equals(unloaded);
        }

        public Cell read(BlockPos pos) {
            return cells.getOrDefault(pos, new Cell(Blocks.AIR.defaultBlockState(), null));
        }

        public void write(BlockPos pos, BlockState state) {
            writes++;
            if (pos.equals(fail)) {
                fail = null;
                return;
            }
            cells.put(pos, new Cell(state, null));
        }

        public void restore(BlockPos pos, Cell cell) {
            writes++;
            cells.put(pos, cell);
        }
    }

    @Test
    void shellBoundariesAndReversedCorners() {
        for (int axis = 1; axis <= 58; axis++) {
            Region region = Region.between(new BlockPos(axis - 1, 0, 0), BlockPos.ZERO);
            Assertions.assertEquals(axis <= 48 ? 8 : axis <= 54 ? 5 : 3, region.padding());
            Assertions.assertEquals(axis, region.volume());
        }
        Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> Region.between(BlockPos.ZERO, new BlockPos(58, 0, 0)).padding());
        Assertions.assertThrows(
                IllegalArgumentException.class,
                () ->
                        Region.between(
                                        new BlockPos(Integer.MIN_VALUE, 0, 0),
                                        new BlockPos(Integer.MAX_VALUE, 0, 0))
                                .padding());
    }

    @Test
    void exactNativeDataUndoAndUnownedCells() {
        var world = new World();
        var tag = new CompoundTag();
        tag.putString("custom", "retained");
        var original = new Cell(Blocks.CHEST.defaultBlockState(), tag);
        world.cells.put(BlockPos.ZERO, original);
        var edit =
                new Edit(
                        Map.of(
                                BlockPos.ZERO,
                                Blocks.STONE.defaultBlockState(),
                                BlockPos.ZERO.east(),
                                Blocks.AIR.defaultBlockState()));
        Map<BlockPos, Cell> before = edit.apply(world, cells -> {});
        Assertions.assertEquals(1, before.size());
        world.cells.put(BlockPos.ZERO, new Cell(Blocks.DIRT.defaultBlockState(), null));
        world.cells.put(BlockPos.ZERO.east(), new Cell(Blocks.GLASS.defaultBlockState(), null));
        Edit.restore(
                world,
                before,
                inverse ->
                        Assertions.assertEquals(
                                Blocks.DIRT.defaultBlockState(),
                                inverse.get(BlockPos.ZERO).state()));
        Assertions.assertEquals(original, world.read(BlockPos.ZERO));
        Assertions.assertEquals(
                Blocks.GLASS.defaultBlockState(), world.read(BlockPos.ZERO.east()).state());
        tag.putString("custom", "mutated");
        Assertions.assertEquals("retained", original.data().getString("custom").orElseThrow());
    }

    @Test
    void writeAndJournalFailuresRollbackAllWrittenCells() {
        var world = new World();
        var states = new LinkedHashMap<BlockPos, BlockState>();
        states.put(BlockPos.ZERO, Blocks.STONE.defaultBlockState());
        states.put(BlockPos.ZERO.east(), Blocks.GLASS.defaultBlockState());
        var edit = new Edit(states);
        world.fail = BlockPos.ZERO.east();
        Assertions.assertThrows(
                IllegalStateException.class,
                () ->
                        edit.apply(
                                world,
                                cells -> Assertions.fail("Journal called after failed write")));
        Assertions.assertTrue(world.read(BlockPos.ZERO).state().isAir());
        Assertions.assertTrue(world.read(BlockPos.ZERO.east()).state().isAir());
        Assertions.assertThrows(
                IllegalStateException.class,
                () ->
                        edit.apply(
                                world,
                                cells -> {
                                    throw new IllegalStateException("disk full");
                                }));
        Assertions.assertTrue(world.read(BlockPos.ZERO).state().isAir());
        world.unloaded = BlockPos.ZERO.east();
        world.writes = 0;
        Assertions.assertThrows(IllegalStateException.class, () -> edit.apply(world, cells -> {}));
        Assertions.assertEquals(0, world.writes);
    }

    @Test
    void persistentUndoRedoOverlapsAndBranching() {
        var history = history();
        var world = new World();
        UUID player = UUID.randomUUID();
        for (int i = 0; i < 100; i++) {
            BlockState target = (i % 2 == 0 ? Blocks.STONE : Blocks.GLASS).defaultBlockState();
            new Edit(Map.of(BlockPos.ZERO, target))
                    .apply(world, before -> history.push(player, Level.OVERWORLD, before));
        }
        for (int i = 99; i >= 0; i--) {
            History reopened = history();
            var entry = reopened.peek(player, false);
            Edit.restore(world, entry.cells(), inverse -> reopened.transfer(entry, false, inverse));
        }
        Assertions.assertTrue(world.read(BlockPos.ZERO).state().isAir());
        Assertions.assertNull(history().peek(player, false));
        for (int i = 0; i < 100; i++) {
            History reopened = history();
            var entry = reopened.peek(player, true);
            Edit.restore(world, entry.cells(), inverse -> reopened.transfer(entry, true, inverse));
        }
        Assertions.assertEquals(
                Blocks.GLASS.defaultBlockState(), world.read(BlockPos.ZERO).state());
        var entry = history.peek(player, false);
        Edit.restore(world, entry.cells(), inverse -> history.transfer(entry, false, inverse));
        history.clearRedo(player);
        Assertions.assertNull(history().peek(player, true));
        Assertions.assertNotNull(history().peek(player, false));
    }

    @Test
    void unloadedOrFailedRestoreRetainsHistory() {
        History history = history();
        UUID player = UUID.randomUUID();
        var world = new World();
        new Edit(Map.of(BlockPos.ZERO, Blocks.STONE.defaultBlockState()))
                .apply(world, cells -> history.push(player, Level.OVERWORLD, cells));
        var entry = history.peek(player, false);
        world.unloaded = BlockPos.ZERO;
        world.writes = 0;
        Assertions.assertThrows(
                IllegalStateException.class,
                () ->
                        Edit.restore(
                                world,
                                entry.cells(),
                                inverse -> history.transfer(entry, false, inverse)));
        Assertions.assertEquals(0, world.writes);
        Assertions.assertEquals(entry.name(), history().peek(player, false).name());
        world.unloaded = null;
        Assertions.assertThrows(
                IllegalStateException.class,
                () ->
                        Edit.restore(
                                world,
                                entry.cells(),
                                inverse -> {
                                    throw new IllegalStateException("disk full");
                                }));
        Assertions.assertEquals(
                Blocks.STONE.defaultBlockState(), world.read(BlockPos.ZERO).state());
    }

    @Test
    void corruptHistoryCannotFallBackOrEscapeItsDirectory() throws Exception {
        UUID player = UUID.randomUUID();
        History history = history();
        history.push(
                player,
                Level.OVERWORLD,
                Map.of(BlockPos.ZERO, new Cell(Blocks.AIR.defaultBlockState(), null)));
        var entry = history.peek(player, false);
        Files.writeString(directory.resolve(player.toString()).resolve(entry.name()), "bad");
        Assertions.assertThrows(RuntimeException.class, () -> history.peek(player, false));
        Files.writeString(
                directory.resolve(player.toString()).resolve("history.json"),
                "{\"format\":1,\"undo\":[\"../../escape.nbt\"],\"redo\":[]}");
        Assertions.assertThrows(IllegalStateException.class, () -> history.peek(player, false));
    }

    @Test
    void everyRegistryStateParsesAndNbtIsRejected() {
        int count = 0;
        for (var block : BuiltInRegistries.BLOCK)
            for (BlockState state : block.getStateDefinition().getPossibleStates()) {
                String text =
                        net.minecraft.commands.arguments.blocks.BlockStateParser.serialize(state);
                Assertions.assertSame(state, Edit.parse(BuiltInRegistries.BLOCK, text));
                count++;
            }
        Assertions.assertEquals(29671, count);
        for (String text :
                java.util.List.of(
                        "minecraft:stone trailing",
                        "minecraft:chest{Items:[]}",
                        "minecraft:missing_block",
                        "minecraft:oak_log[axis=wrong]")) {
            Assertions.assertThrows(
                    IllegalArgumentException.class,
                    () -> Edit.parse(BuiltInRegistries.BLOCK, text));
        }
    }

    @Test
    void rollbackAttemptsEveryCellAndReportsFailures() {
        var memory = new World();
        Edit.World world =
                new Edit.World() {
                    public boolean loaded(BlockPos pos) {
                        return memory.loaded(pos);
                    }

                    public Cell read(BlockPos pos) {
                        return memory.read(pos);
                    }

                    public void write(BlockPos pos, BlockState state) {
                        memory.write(pos, state);
                    }

                    public void restore(BlockPos pos, Cell cell) {
                        if (pos.equals(BlockPos.ZERO))
                            throw new IllegalStateException("restore rejected");
                        memory.restore(pos, cell);
                    }
                };
        var states = new LinkedHashMap<BlockPos, BlockState>();
        states.put(BlockPos.ZERO, Blocks.STONE.defaultBlockState());
        states.put(BlockPos.ZERO.east(), Blocks.STONE.defaultBlockState());
        var error =
                Assertions.assertThrows(
                        IllegalStateException.class,
                        () ->
                                new Edit(states)
                                        .apply(
                                                world,
                                                cells -> {
                                                    throw new IllegalStateException(
                                                            "journal failed");
                                                }));
        Assertions.assertTrue(error.getMessage().startsWith("Rollback failed"));
        Assertions.assertEquals(1, error.getCause().getSuppressed().length);
        Assertions.assertTrue(memory.read(BlockPos.ZERO.east()).state().isAir());
    }

    @Test
    void numberedHistoryImportsWithoutLosingNativeData() throws Exception {
        UUID player = UUID.randomUUID();
        var tag = new CompoundTag();
        tag.putInt("format", 1);
        tag.putString("minecraft_version", SharedConstants.getCurrentVersion().name());
        tag.putString("dimension", "minecraft:overworld");
        var cells = new net.minecraft.nbt.ListTag();
        var cell = new CompoundTag();
        cell.putLong("pos", BlockPos.ZERO.asLong());
        cell.putString("state", "minecraft:chest[facing=north,type=single,waterlogged=false]");
        var data = new CompoundTag();
        data.putString("custom", "retained");
        cell.put("data", data);
        cells.add(cell);
        tag.put("cells", cells);
        Path journal = directory.resolve(player.toString()).resolve("00000000000000000001.nbt");
        Files.createDirectories(journal.getParent());
        net.minecraft.nbt.NbtIo.writeCompressed(tag, journal);
        var history = history();
        var entry = history.peek(player, false);
        Assertions.assertEquals(data, entry.cells().get(BlockPos.ZERO).data());
        history.transfer(
                entry,
                false,
                Map.of(BlockPos.ZERO, new Cell(Blocks.STONE.defaultBlockState(), null)));
        Assertions.assertNull(history().peek(player, false));
        Assertions.assertEquals(
                Blocks.STONE.defaultBlockState(),
                history().peek(player, true).cells().get(BlockPos.ZERO).state());
    }

    private History history() {
        return new History(directory, BuiltInRegistries.BLOCK);
    }
}
