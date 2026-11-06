package io.github.ivanbanny;

import io.github.ivanbanny.protocol.VoxelTensor;
import io.github.ivanbanny.world.Cell;
import io.github.ivanbanny.world.Edit;
import io.github.ivanbanny.world.Region;
import io.github.ivanbanny.world.Snapshot;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

public final class PasteGameTest {
    private static void set(ServerLevel level, BlockPos pos, BlockState state) {
        level.setBlock(pos, state, Block.UPDATE_CLIENTS | Block.UPDATE_SKIP_ALL_SIDEEFFECTS);
    }

    @GameTest(structure = "dirtflow-tests:volume", maxTicks = 1000)
    public void everyRegistryStateDecodesAndPastes(GameTestHelper helper) {
        var level = helper.getLevel();
        int count = 0;
        for (var block : BuiltInRegistries.BLOCK)
            for (BlockState state : block.getStateDefinition().getPossibleStates()) {
                BlockPos pos =
                        helper.absolutePos(
                                new BlockPos(
                                        10 + 3 * (count % 8),
                                        10 + 3 * ((count / 64) % 8),
                                        10 + 3 * ((count / 8) % 8)));
                for (Direction side : Direction.values())
                    set(level, pos.relative(side), Blocks.STONE.defaultBlockState());
                set(level, pos, Blocks.AIR.defaultBlockState());
                var tensor =
                        new VoxelTensor.Sparse(
                                pos,
                                1,
                                1,
                                1,
                                List.of("minecraft:air", BlockStateParser.serialize(state)),
                                new int[] {0},
                                new int[] {1});
                Edit edit =
                        Edit.decode(
                                level,
                                tensor,
                                Region.between(pos, pos),
                                new BlockState[] {Blocks.AIR.defaultBlockState()});
                if (!state.is(Blocks.AIR))
                    helper.assertTrue(edit.states().get(pos) == state, "Decoder changed " + state);
                var before = new AtomicReference<Map<BlockPos, Cell>>();
                edit.apply(level, before::set);
                Edit.restore(Edit.world(level), before.get(), cells -> {});
                helper.assertTrue(level.getBlockState(pos).isAir(), "Undo failed for " + state);
                count++;
            }
        helper.assertTrue(count == 29671, "Registry state count: " + count);
        helper.succeed();
    }

    @GameTest
    public void nativeInventorySurvivesUndoAndRedo(GameTestHelper helper) {
        var level = helper.getLevel();
        BlockPos pos = helper.absolutePos(new BlockPos(2, 3, 2));
        set(level, pos, Blocks.CHEST.defaultBlockState());
        ((ChestBlockEntity) level.getBlockEntity(pos)).setItem(0, new ItemStack(Items.DIAMOND, 7));
        Cell original = Cell.capture(level, pos);
        var before = new AtomicReference<Map<BlockPos, Cell>>();
        new Edit(Map.of(pos, Blocks.STONE.defaultBlockState())).apply(level, before::set);
        var inverse = new AtomicReference<Map<BlockPos, Cell>>();
        Edit.restore(Edit.world(level), before.get(), inverse::set);
        helper.assertTrue(
                Cell.capture(level, pos).equals(original), "Inventory changed during undo");
        Edit.restore(Edit.world(level), inverse.get(), cells -> {});
        helper.assertTrue(level.getBlockState(pos).is(Blocks.STONE), "Redo did not restore stone");
        helper.assertTrue(
                level.getEntitiesOfClass(ItemEntity.class, new AABB(pos).inflate(2)).isEmpty(),
                "Container replacement dropped duplicate items");
        helper.succeed();
    }

    @GameTest
    public void acceptanceCapturesCurrentCellsAndLeavesUnownedCells(GameTestHelper helper) {
        var level = helper.getLevel();
        BlockPos pos = helper.absolutePos(new BlockPos(2, 3, 2));
        var raw =
                new VoxelTensor.Sparse(
                        pos,
                        2,
                        1,
                        1,
                        List.of("minecraft:air", "minecraft:stone"),
                        new int[] {0},
                        new int[] {1});
        var edit =
                Edit.decode(
                        level,
                        raw,
                        Region.between(pos, pos.east()),
                        new BlockState[] {
                            Blocks.AIR.defaultBlockState(), Blocks.AIR.defaultBlockState()
                        });
        set(level, pos, Blocks.DIRT.defaultBlockState());
        set(level, pos.east(), Blocks.GLASS.defaultBlockState());
        var before = new AtomicReference<Map<BlockPos, Cell>>();
        edit.apply(level, before::set);
        set(level, pos, Blocks.DIAMOND_BLOCK.defaultBlockState());
        Edit.restore(Edit.world(level), before.get(), cells -> {});
        helper.assertTrue(
                level.getBlockState(pos).is(Blocks.DIRT), "Undo missed a changed owned cell");
        helper.assertTrue(
                level.getBlockState(pos.east()).is(Blocks.GLASS), "Undo touched an unowned cell");
        helper.succeed();
    }

    @GameTest
    public void journalFailureRestoresNativeData(GameTestHelper helper) {
        var level = helper.getLevel();
        BlockPos pos = helper.absolutePos(new BlockPos(2, 3, 2));
        set(level, pos, Blocks.CHEST.defaultBlockState());
        ((ChestBlockEntity) level.getBlockEntity(pos)).setItem(0, new ItemStack(Items.DIAMOND, 4));
        Cell original = Cell.capture(level, pos);
        try {
            new Edit(Map.of(pos, Blocks.STONE.defaultBlockState()))
                    .apply(
                            level,
                            cells -> {
                                throw new IllegalStateException("disk full");
                            });
            throw new AssertionError("Journal failure was ignored");
        } catch (IllegalStateException expected) {
            helper.assertTrue(
                    expected.getMessage().equals("disk full"), "Unexpected write failure");
        }
        helper.assertTrue(
                Cell.capture(level, pos).equals(original), "Rollback lost native inventory");
        helper.succeed();
    }

    @GameTest(maxTicks = 80)
    public void nativeFluidAndGravityTicksRunAfterPaste(GameTestHelper helper) {
        var level = helper.getLevel();
        BlockPos pos = helper.absolutePos(new BlockPos(2, 5, 2));
        new Edit(
                        Map.of(
                                pos,
                                Blocks.WATER.defaultBlockState(),
                                pos.east(4),
                                Blocks.SAND.defaultBlockState()))
                .apply(level, cells -> {});
        helper.runAfterDelay(
                20,
                () -> {
                    helper.assertTrue(
                            !level.getFluidState(pos.below()).isEmpty(),
                            "Fluid tick was not scheduled");
                    helper.assertTrue(
                            !level.getBlockState(pos.east(4)).is(Blocks.SAND),
                            "Gravity tick was not scheduled");
                    helper.succeed();
                });
    }

    @GameTest(structure = "dirtflow-tests:volume")
    public void paddedSnapshotUsesWorldCoordinates(GameTestHelper helper) {
        var level = helper.getLevel();
        BlockPos pos = helper.absolutePos(new BlockPos(12, 12, 12));
        set(level, pos.west(8), Blocks.GLASS.defaultBlockState());
        set(level, pos, Blocks.STONE.defaultBlockState());
        Snapshot snapshot = Snapshot.capture(level, Region.between(pos, pos.east()));
        helper.assertTrue(
                snapshot.tensor().origin().equals(pos.offset(-8, -8, -8)),
                "Snapshot origin lost padding");
        helper.assertTrue(
                snapshot.tensor().sizeX() == 18
                        && snapshot.tensor().sizeY() == 17
                        && snapshot.tensor().sizeZ() == 17,
                "Snapshot dimensions");
        helper.assertTrue(snapshot.selected()[0].is(Blocks.STONE), "Selected input order");
        helper.assertTrue(
                snapshot.tensor().palette().contains("minecraft:glass"), "Context was omitted");
        helper.succeed();
    }

    @GameTest
    public void malformedDiffAndUnloadedTargetsFailBeforeWrites(GameTestHelper helper) {
        var level = helper.getLevel();
        BlockPos pos = helper.absolutePos(new BlockPos(2, 3, 2));
        var region = Region.between(pos, pos);
        BlockState[] original = {level.getBlockState(pos)};
        for (var raw :
                List.of(
                        new VoxelTensor.Sparse(
                                pos.east(),
                                1,
                                1,
                                1,
                                List.of("minecraft:air"),
                                new int[] {0},
                                new int[] {0}),
                        new VoxelTensor.Sparse(
                                pos,
                                1,
                                1,
                                1,
                                List.of("minecraft:air", "minecraft:missing"),
                                new int[] {0},
                                new int[] {1}),
                        new VoxelTensor.Sparse(
                                pos,
                                1,
                                1,
                                1,
                                List.of("minecraft:air"),
                                new int[] {0, 0},
                                new int[] {0, 0}))) {
            try {
                Edit.decode(level, raw, region, original);
                throw new AssertionError("Invalid diff accepted");
            } catch (IllegalArgumentException expected) {
            }
        }
        var cells = new LinkedHashMap<BlockPos, BlockState>();
        cells.put(pos, Blocks.STONE.defaultBlockState());
        cells.put(new BlockPos(20000000, pos.getY(), 20000000), Blocks.STONE.defaultBlockState());
        try {
            new Edit(cells).apply(level, before -> {});
            throw new AssertionError("Unloaded target accepted");
        } catch (IllegalStateException expected) {
        }
        helper.assertTrue(level.getBlockState(pos) == original[0], "Invalid edit wrote a cell");
        helper.succeed();
    }
}
