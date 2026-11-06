package io.github.ivanbanny.client;

import io.github.ivanbanny.Packets;
import io.github.ivanbanny.protocol.TensorPayload;
import io.github.ivanbanny.protocol.VoxelTensor;
import io.github.ivanbanny.protocol.VoxelTensorCodec;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.locks.LockSupport;

public final class PreviewGameTest implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext context) {
        DirtflowClient mod =
                FabricLoader.getInstance()
                        .getEntrypoints("client", ClientModInitializer.class)
                        .stream()
                        .filter(DirtflowClient.class::isInstance)
                        .map(DirtflowClient.class::cast)
                        .findFirst()
                        .orElseThrow();
        // Slow client frames must not prevent the server from preparing spawn chunks
        ClientTickEvents.START_CLIENT_TICK.register(
                client -> {
                    if (client.getSingleplayerServer() != null && client.level == null)
                        LockSupport.parkNanos(100_000_000L);
                });
        try (var world = context.worldBuilder().create()) {
            world.getServer().runCommand("gamemode spectator @a");
            world.getServer().runCommand("tp @a 6 -56 14 180 20");
            context.waitTicks(10);
            world.getClientWorld().waitForChunksRender();
            var origin = new BlockPos(2, -60, 2);
            var palette =
                    List.of(
                            "minecraft:air",
                            "minecraft:oak_planks",
                            "minecraft:oak_log[axis=y]",
                            "minecraft:oak_slab[type=bottom,waterlogged=false]",
                            "minecraft:oak_stairs[facing=east,half=bottom,shape=straight,waterlogged=false]",
                            "minecraft:cobblestone",
                            "minecraft:glass",
                            "minecraft:oak_fence[east=true,north=false,south=false,waterlogged=false,west=true]",
                            "minecraft:lantern[hanging=false,waterlogged=false]");
            int[] cells = new int[8 * 4 * 3], lengths = new int[8 * 4 * 3];
            Arrays.fill(lengths, 1);
            for (int z = 0; z < 3; z++)
                for (int x = 0; x < 8; x++) {
                    cells[(z * 4 + 1) * 8 + x] = x == 0 ? 4 : x == 7 ? 3 : 1;
                    if ((x == 1 || x == 6) && z != 1) cells[(z * 4) * 8 + x] = 2;
                    if (x > 0 && x < 7 && z != 1) cells[(z * 4 + 2) * 8 + x] = 7;
                }
            cells[51] = 5;
            cells[52] = 6;
            cells[57] = 8;
            byte[] wire =
                    VoxelTensorCodec.encode(
                            new VoxelTensor.Dense(origin, 8, 4, 3, palette, cells, lengths));
            context.runOnClient(
                    client -> {
                        client.options.hideGui = true;
                        mod.selection.first = origin;
                        mod.selection.second = origin.offset(7, 3, 2);
                        mod.preview.begin("visual", "minecraft:overworld");
                        mod.preview.receive(
                                new Packets.Preview(
                                        "visual",
                                        "minecraft:overworld",
                                        1,
                                        "structure",
                                        0,
                                        1,
                                        wire));
                    });
            context.waitTicks(20);
            context.takeScreenshot("shape-preview");
            context.runOnClient(
                    client ->
                            mod.preview.receive(
                                    new Packets.Preview(
                                            "visual",
                                            "minecraft:overworld",
                                            2,
                                            "blockstates",
                                            0,
                                            1,
                                            wire)));
            context.waitTicks(20);
            context.takeScreenshot("material-preview");
            context.runOnClient(
                    client -> {
                        if (mod.preview.displayedSequence() != 2)
                            throw new AssertionError("Preview did not finish building");
                        if (!client.level.getBlockState(origin.above()).isAir())
                            throw new AssertionError("Ghost changed client world");
                        mod.preview.reload();
                    });
            context.waitTicks(20);
            context.runOnClient(
                    client -> {
                        if (mod.preview.displayedSequence() != 2)
                            throw new AssertionError("Resource reload lost frame");
                        mod.preview.clear();
                    });
            int volume = 58 * 58 * 58;
            byte[] large =
                    TensorPayload.pack(
                            VoxelTensorCodec.encode(
                                    new VoxelTensor.Dense(
                                            origin,
                                            58,
                                            58,
                                            58,
                                            List.of("minecraft:air", "minecraft:stone"),
                                            new int[] {1, 1, 1},
                                            new int[] {65535, 65535, volume - 131070})));
            context.runOnClient(
                    client -> {
                        mod.preview.begin("large", "minecraft:overworld");
                        mod.preview.receive(
                                new Packets.Preview(
                                        "large",
                                        "minecraft:overworld",
                                        1,
                                        "blockstates",
                                        0,
                                        1,
                                        large));
                    });
            context.waitTicks(80);
            context.runOnClient(
                    client -> {
                        if (mod.preview.displayedSequence() != 1)
                            throw new AssertionError("Full cube preview did not complete");
                        mod.preview.clear();
                    });
            int[] singleRuns = new int[volume];
            Arrays.fill(singleRuns, 1);
            byte[] chunked =
                    VoxelTensorCodec.encode(
                            new VoxelTensor.Dense(
                                    origin,
                                    58,
                                    58,
                                    58,
                                    List.of("minecraft:air", "minecraft:stone"),
                                    singleRuns,
                                    singleRuns));
            context.runOnClient(client -> mod.preview.begin("chunks", "minecraft:overworld"));
            world.getServer()
                    .runOnServer(
                            server ->
                                    Packets.preview(
                                            server.getPlayerList().getPlayers().getFirst(),
                                            "chunks",
                                            "minecraft:overworld",
                                            2,
                                            "structure",
                                            chunked));
            context.waitFor(client -> mod.preview.displayedSequence() == 2);
            world.getServer()
                    .runOnServer(
                            server ->
                                    Packets.preview(
                                            server.getPlayerList().getPlayers().getFirst(),
                                            "chunks",
                                            "minecraft:overworld",
                                            1,
                                            "structure",
                                            chunked));
            context.waitTicks(5);
            context.runOnClient(
                    client -> {
                        if (mod.preview.displayedSequence() != 2)
                            throw new AssertionError("Stale frame replaced current geometry");
                        mod.preview.clear();
                    });
            var target = new BlockPos(2, -50, 2);
            world.getServer().runCommand("execute as @a at @s run df select 2 -50 2 3 -49 3");
            context.waitFor(client -> target.equals(mod.selection.first));
            world.getServer().runCommand("execute as @a at @s run df client integration");
            context.waitFor(
                    client ->
                            client.level
                                    .getBlockState(target)
                                    .is(net.minecraft.world.level.block.Blocks.STONE));
            world.getServer()
                    .runOnServer(
                            server -> {
                                if (!server.overworld().getBlockState(target).isAir())
                                    throw new AssertionError("Final preview changed server world");
                            });
            world.getServer().runCommand("execute as @a run df reject");
            context.waitFor(client -> client.level.getBlockState(target).isAir());
            world.getServer().runCommand("execute as @a run df retry");
            context.waitFor(
                    client ->
                            client.level
                                    .getBlockState(target)
                                    .is(net.minecraft.world.level.block.Blocks.STONE));
            world.getServer().runCommand("execute as @a run df accept");
            world.getServer()
                    .runOnServer(
                            server -> {
                                if (!server.overworld()
                                        .getBlockState(target)
                                        .is(net.minecraft.world.level.block.Blocks.STONE))
                                    throw new AssertionError("Accept missed server world");
                            });
            world.getServer().runCommand("execute as @a run df undo");
            context.waitFor(client -> client.level.getBlockState(target).isAir());
            world.getServer().runCommand("execute as @a run df redo");
            context.waitFor(
                    client ->
                            client.level
                                    .getBlockState(target)
                                    .is(net.minecraft.world.level.block.Blocks.STONE));
        }
    }
}
