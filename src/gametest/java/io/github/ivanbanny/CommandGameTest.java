package io.github.ivanbanny;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import io.github.ivanbanny.protocol.TensorPayload;
import io.github.ivanbanny.protocol.VoxelTensor;
import io.github.ivanbanny.world.Region;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.gamerules.GameRules;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Command lifecycle with a controlled HTTP peer; Minecraft internals run normally. */
public final class CommandGameTest implements ModInitializer {
    private static final Map<String, Job> JOBS = new ConcurrentHashMap<>();

    private record Job(JsonObject request, CountDownLatch release, AtomicInteger deletes) {}

    @Override
    public void onInitialize() {
        try {
            var engine = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            var executor = Executors.newVirtualThreadPerTaskExecutor();
            engine.setExecutor(executor);
            var next = new AtomicInteger();
            engine.createContext(
                    "/v1/generate",
                    exchange -> {
                        var request =
                                JsonParser.parseString(
                                                new String(
                                                        exchange.getRequestBody().readAllBytes(),
                                                        StandardCharsets.UTF_8))
                                        .getAsJsonObject();
                        new Gson().fromJson(request.get("snapshot"), TensorPayload.class).decode();
                        String id = "test-" + next.incrementAndGet();
                        var job = new Job(request, new CountDownLatch(1), new AtomicInteger());
                        JOBS.put(id, job);
                        if (request.get("prompt").getAsString().startsWith("hold-submit"))
                            await(job.release());
                        byte[] body =
                                ("{\"job_id\":\"" + id + "\"}").getBytes(StandardCharsets.UTF_8);
                        exchange.sendResponseHeaders(202, body.length);
                        try (var out = exchange.getResponseBody()) {
                            out.write(body);
                        }
                    });
            engine.createContext(
                    "/v1/jobs/",
                    exchange -> {
                        String id = exchange.getRequestURI().getPath().split("/")[3];
                        Job job = JOBS.get(id);
                        if (exchange.getRequestMethod().equals("DELETE")) {
                            job.deletes().incrementAndGet();
                            job.release().countDown();
                            exchange.sendResponseHeaders(204, -1);
                            exchange.close();
                            return;
                        }
                        var request = job.request();
                        var min = request.getAsJsonObject("region").getAsJsonArray("min");
                        var max = request.getAsJsonObject("region").getAsJsonArray("max");
                        var origin =
                                new BlockPos(
                                        min.get(0).getAsInt(),
                                        min.get(1).getAsInt(),
                                        min.get(2).getAsInt());
                        int sx = max.get(0).getAsInt() - origin.getX() + 1,
                                sy = max.get(1).getAsInt() - origin.getY() + 1,
                                sz = max.get(2).getAsInt() - origin.getZ() + 1;
                        String prompt = request.get("prompt").getAsString();
                        int[] indices =
                                prompt.equals("full cube")
                                        ? java.util.stream.IntStream.range(0, sx * sy * sz)
                                                .toArray()
                                        : new int[] {0};
                        int[] ids = new int[indices.length];
                        Arrays.fill(ids, 1);
                        String target =
                                request.getAsJsonObject("options")
                                                .get("task_type")
                                                .getAsString()
                                                .equals("repair")
                                        ? "minecraft:glass"
                                        : "minecraft:stone";
                        var tensor =
                                new VoxelTensor.Sparse(
                                        origin,
                                        sx,
                                        sy,
                                        sz,
                                        List.of("minecraft:air", target),
                                        indices,
                                        ids);
                        var result = new JsonObject();
                        result.addProperty("job_id", id);
                        result.add("diff", new Gson().toJsonTree(TensorPayload.of(tensor)));
                        exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
                        exchange.sendResponseHeaders(200, 0);
                        try (var out = exchange.getResponseBody()) {
                            out.write(
                                    ("id: 1\nevent: status\ndata: {\"job_id\":\""
                                                    + id
                                                    + "\",\"phase\":\"QUEUED\",\"queue_position\":0}\n\n")
                                            .getBytes(StandardCharsets.UTF_8));
                            out.flush();
                            if (prompt.startsWith("hold-stream")) await(job.release());
                            out.write(
                                    ("id: 2\nevent: result\ndata: " + result + "\n\n")
                                            .getBytes(StandardCharsets.UTF_8));
                        }
                    });
            engine.start();
            System.setProperty(
                    "dirtflow.server.url", "http://127.0.0.1:" + engine.getAddress().getPort());
            System.setProperty("dirtflow.server.token", "");
            ServerLifecycleEvents.SERVER_STOPPED.register(
                    server -> {
                        JOBS.values().forEach(job -> job.release().countDown());
                        engine.stop(0);
                        executor.shutdownNow();
                    });
        } catch (java.io.IOException error) {
            throw new java.io.UncheckedIOException(error);
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(30, TimeUnit.SECONDS))
                throw new IllegalStateException("Held test request timed out");
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(error);
        }
    }

    private static Job job(String prompt) {
        return JOBS.values().stream()
                .filter(value -> value.request().get("prompt").getAsString().equals(prompt))
                .findFirst()
                .orElse(null);
    }

    private static int execute(ServerPlayer player, String command) {
        try {
            return player.level()
                    .getServer()
                    .getCommands()
                    .getDispatcher()
                    .execute(command, player.createCommandSourceStack());
        } catch (com.mojang.brigadier.exceptions.CommandSyntaxException error) {
            throw new AssertionError(error);
        }
    }

    private static void command(ServerPlayer player, String command) {
        if (execute(player, "df " + command) != 1)
            throw new AssertionError("Command failed: " + command);
    }

    private static ServerPlayer player(GameTestHelper helper, BlockPos pos) {
        var player = helper.makeMockServerPlayerInLevel();
        player.setPos(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
        return player;
    }

    private static ServerSession session(ServerPlayer player) {
        return Dirtflow.session(player.level().getServer());
    }

    private static void preview(GameTestHelper helper, ServerPlayer player) {
        helper.assertTrue(session(player).preview(player.getUUID()) != null, "Waiting for preview");
    }

    private static void remove(ServerPlayer player) {
        session(player).disconnect(player);
        player.level().getServer().getPlayerList().remove(player);
    }

    @GameTest(
            environment = "dirtflow-tests:commands",
            structure = "dirtflow-tests:volume",
            maxTicks = 4000)
    public void commandsReviewRetryAndPersistentHistory(GameTestHelper helper) {
        var level = helper.getLevel();
        BlockPos pos = helper.absolutePos(new BlockPos(12, 12, 12));
        var player = player(helper, pos);
        helper.startSequence()
                .thenExecute(
                        () -> {
                            command(player, "pos1");
                            command(player, "pos2 ~2 ~3 ~2");
                            command(player, "settings seed 123");
                            command(player, "repair literal review prompt");
                            helper.assertTrue(
                                    session(player)
                                            .selection(player.getUUID())
                                            .equals(Region.between(pos, pos.offset(2, 3, 2))),
                                    "Submission cleared selection");
                        })
                .thenWaitUntil(() -> preview(helper, player))
                .thenExecute(
                        () -> {
                            helper.assertTrue(
                                    level.getBlockState(pos).isAir(),
                                    "Preview changed server world");
                            level.setBlock(
                                    pos.east(),
                                    Blocks.DIRT.defaultBlockState(),
                                    Block.UPDATE_CLIENTS | Block.UPDATE_SKIP_ALL_SIDEEFFECTS);
                            command(player, "accept");
                            helper.assertTrue(
                                    level.getBlockState(pos).is(Blocks.GLASS),
                                    "Accept did not paste preview");
                            helper.assertTrue(
                                    session(player).selection(player.getUUID()) == null,
                                    "Accept retained selection");
                            command(player, "undo");
                            command(player, "redo");
                            helper.assertTrue(
                                    level.getBlockState(pos).is(Blocks.GLASS),
                                    "Redo did not restore accepted edit");
                            command(player, "undo");
                            command(player, "retry");
                        })
                .thenWaitUntil(() -> preview(helper, player))
                .thenExecute(
                        () -> {
                            helper.assertTrue(
                                    JOBS.values().stream()
                                            .filter(
                                                    value ->
                                                            value.request()
                                                                    .get("prompt")
                                                                    .getAsString()
                                                                    .equals(
                                                                            "literal review prompt"))
                                            .anyMatch(
                                                    value ->
                                                            !value.request()
                                                                    .getAsJsonObject("options")
                                                                    .has("seed")),
                                    "Retry reused pinned seed");
                            helper.assertTrue(
                                    execute(player, "df redo") == 0, "Submission kept redo branch");
                            command(player, "reject");
                            helper.assertTrue(
                                    session(player).selection(player.getUUID()) == null
                                            && level.getBlockState(pos).isAir(),
                                    "Reject changed world or kept selection");
                            helper.assertTrue(
                                    level.getBlockState(pos.east()).is(Blocks.DIRT),
                                    "Undo touched unowned cell");
                            command(player, "select previous");
                            command(player, "select 1");
                            helper.assertTrue(
                                    session(player).selection(player.getUUID()).volume() == 27,
                                    "Radius selection size");
                            helper.assertTrue(
                                    execute(player, "dirtflow help") == 1, "Long alias failed");
                            remove(player);
                        })
                .thenSucceed();
    }

    @GameTest(
            environment = "dirtflow-tests:commands",
            structure = "dirtflow-tests:volume",
            maxTicks = 4000)
    public void changingSelectionCancelsLateSubmission(GameTestHelper helper) {
        var pos = helper.absolutePos(new BlockPos(12, 12, 12));
        var player = player(helper, pos);
        helper.startSequence()
                .thenExecute(
                        () -> {
                            command(player, "select 1");
                            command(player, "hold-submit selection");
                        })
                .thenWaitUntil(
                        () ->
                                helper.assertTrue(
                                        job("hold-submit selection") != null, "Waiting for POST"))
                .thenExecute(
                        () -> {
                            command(player, "select 2");
                            job("hold-submit selection").release().countDown();
                        })
                .thenWaitUntil(
                        () ->
                                helper.assertTrue(
                                        job("hold-submit selection").deletes().get() == 1,
                                        "Late response was not canceled"))
                .thenExecute(
                        () -> {
                            helper.assertTrue(
                                    session(player).preview(player.getUUID()) == null,
                                    "Late response revived preview");
                            helper.assertTrue(
                                    session(player).selection(player.getUUID()).volume() == 125,
                                    "Selection was replaced by old request");
                            remove(player);
                        })
                .thenSucceed();
    }

    @GameTest(
            environment = "dirtflow-tests:commands",
            structure = "dirtflow-tests:volume",
            maxTicks = 4000)
    public void identicalSelectionKeepsRunningRequestAndDisconnectCancels(GameTestHelper helper) {
        var pos = helper.absolutePos(new BlockPos(12, 12, 12));
        var player = player(helper, pos);
        helper.startSequence()
                .thenExecute(
                        () -> {
                            command(player, "select 1");
                            command(player, "hold-stream disconnect");
                        })
                .thenWaitUntil(
                        () ->
                                helper.assertTrue(
                                        job("hold-stream disconnect") != null, "Waiting for POST"))
                .thenExecute(() -> command(player, "select 1"))
                .thenIdle(10)
                .thenExecute(
                        () -> {
                            helper.assertTrue(
                                    job("hold-stream disconnect").deletes().get() == 0,
                                    "Identical selection canceled generation");
                            remove(player);
                        })
                .thenWaitUntil(
                        () ->
                                helper.assertTrue(
                                        job("hold-stream disconnect").deletes().get() == 1,
                                        "Disconnect did not cancel request"))
                .thenSucceed();
    }

    @GameTest(
            environment = "dirtflow-tests:commands",
            structure = "dirtflow-tests:volume",
            maxTicks = 4000)
    public void fullCanvasTransfersAcceptsAndUndoes(GameTestHelper helper) {
        var level = helper.getLevel();
        var pos = helper.absolutePos(new BlockPos(10, 10, 10));
        var player = player(helper, pos);
        helper.startSequence()
                .thenExecute(
                        () -> {
                            command(player, "pos1");
                            command(player, "pos2 ~57 ~57 ~57");
                            command(player, "full cube");
                        })
                .thenWaitUntil(() -> preview(helper, player))
                .thenExecute(
                        () -> {
                            helper.assertTrue(
                                    session(player).preview(player.getUUID()).states().size()
                                            == 58 * 58 * 58,
                                    "Large result truncated");
                            command(player, "accept");
                            helper.assertTrue(
                                    level.getBlockState(pos.offset(57, 57, 57)).is(Blocks.STONE),
                                    "Far corner not pasted");
                            command(player, "undo");
                            helper.assertTrue(
                                    level.getBlockState(pos).isAir()
                                            && level.getBlockState(pos.offset(57, 57, 57)).isAir(),
                                    "Large edit did not undo");
                            remove(player);
                        })
                .thenSucceed();
    }

    @GameTest
    public void buildPreservationRulesRemainEnabled(GameTestHelper helper) {
        var rules = helper.getLevel().getServer().overworld().getGameRules();
        helper.assertTrue(rules.get(GameRules.RANDOM_TICK_SPEED) == 0, "Random ticks enabled");
        helper.assertTrue(
                rules.get(GameRules.FIRE_SPREAD_RADIUS_AROUND_PLAYER) == 0, "Fire ticking enabled");
        helper.succeed();
    }
}
