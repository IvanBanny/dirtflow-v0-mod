package io.github.ivanbanny;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.ivanbanny.engine.EngineClient;
import io.github.ivanbanny.engine.Event;
import io.github.ivanbanny.engine.Request;
import io.github.ivanbanny.engine.Settings;
import io.github.ivanbanny.protocol.TensorPayload;
import io.github.ivanbanny.protocol.VoxelTensor;
import io.github.ivanbanny.world.Region;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

@Timeout(15)
class EngineClientTest {
    private HttpServer server;
    private java.util.concurrent.ExecutorService executor;
    private EngineClient client;
    private final AtomicInteger deletes = new AtomicInteger();
    private final AtomicReference<Throwable> handlerError = new AtomicReference<>();
    private final List<Event> events = new CopyOnWriteArrayList<>();
    private final List<String> authorization = new CopyOnWriteArrayList<>();
    private Handler submission, stream;
    private String endpoint;

    @FunctionalInterface
    private interface Handler {
        void handle(HttpExchange exchange) throws Exception;
    }

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        executor = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(executor);
        endpoint = "http://127.0.0.1:" + server.getAddress().getPort() + "/prefix";
        submission = exchange -> reply(exchange, 202, "{\"job_id\":\"test-job\"}");
        stream = exchange -> reply(exchange, 200, result(1));
        server.createContext(
                "/prefix/v1/",
                exchange -> {
                    try {
                        authorization.add(
                                String.valueOf(
                                        exchange.getRequestHeaders().getFirst("Authorization")));
                        if (exchange.getRequestMethod().equals("DELETE")) {
                            deletes.incrementAndGet();
                            reply(exchange, 204, "");
                        } else if (exchange.getRequestMethod().equals("POST"))
                            submission.handle(exchange);
                        else stream.handle(exchange);
                    } catch (Throwable error) {
                        handlerError.compareAndSet(null, error);
                        exchange.close();
                    }
                });
        server.start();
        client = new EngineClient(Duration.ofSeconds(3));
    }

    @AfterEach
    void stop() {
        client.close();
        server.stop(0);
        executor.shutdownNow();
    }

    @Test
    void requestAndCompressedResultUseActualFields() throws Exception {
        submission =
                exchange -> {
                    var json =
                            JsonParser.parseString(
                                            new String(
                                                    exchange.getRequestBody().readAllBytes(),
                                                    StandardCharsets.UTF_8))
                                    .getAsJsonObject();
                    Assertions.assertEquals("literal prompt", json.get("prompt").getAsString());
                    Assertions.assertEquals(2, json.get("protocol_version").getAsInt());
                    Assertions.assertEquals(8, json.get("context_padding").getAsInt());
                    var options = json.getAsJsonObject("options");
                    Assertions.assertEquals(30, options.get("stage1_steps").getAsInt());
                    Assertions.assertEquals(30, options.get("stage2_steps").getAsInt());
                    Assertions.assertEquals(5, options.get("cfg_scale").getAsDouble());
                    Assertions.assertEquals(1, options.get("candidates").getAsInt());
                    Assertions.assertEquals(1, options.get("batch_size").getAsInt());
                    var snapshot =
                            new Gson().fromJson(json.get("snapshot"), TensorPayload.class).decode();
                    Assertions.assertEquals(new BlockPos(-8, -8, -8), snapshot.origin());
                    Assertions.assertEquals(17 * 17 * 17, snapshot.totalVoxels());
                    reply(exchange, 202, "{\"job_id\":\"test-job\"}");
                };
        call().done().get(5, TimeUnit.SECONDS);
        Assertions.assertNull(handlerError.get());
        Assertions.assertEquals(1, events.size());
        var tensor = (VoxelTensor.Sparse) ((Event.Result) events.getFirst()).tensor();
        Assertions.assertArrayEquals(new int[] {1}, tensor.paletteIds());
        Assertions.assertEquals(0, deletes.get());
        Assertions.assertEquals(List.of("Bearer test-token", "Bearer test-token"), authorization);
    }

    @Test
    void reconnectResumesWithoutRepeatingProgress() throws Exception {
        var count = new AtomicInteger();
        stream =
                exchange -> {
                    if (count.getAndIncrement() == 0) {
                        Assertions.assertEquals(
                                "0", exchange.getRequestHeaders().getFirst("Last-Event-ID"));
                        reply(
                                exchange,
                                200,
                                "id: 1\nevent: progress\ndata: {\"job_id\":\"test-job\",\"fraction\":0.5}\n\n");
                    } else {
                        Assertions.assertEquals(
                                "1", exchange.getRequestHeaders().getFirst("Last-Event-ID"));
                        reply(
                                exchange,
                                200,
                                "id: 1\nevent: progress\ndata: {\"job_id\":\"test-job\",\"fraction\":0.5}\n\n"
                                        + result(2));
                    }
                };
        call().done().get(5, TimeUnit.SECONDS);
        Assertions.assertNull(handlerError.get());
        Assertions.assertEquals(2, events.size());
        Assertions.assertEquals(2, count.get());
    }

    @Test
    void canceledSubmissionDeletesLateJob() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        submission =
                exchange -> {
                    entered.countDown();
                    Assertions.assertTrue(release.await(5, TimeUnit.SECONDS));
                    reply(exchange, 202, "{\"job_id\":\"test-job\"}");
                };
        var call = call();
        Assertions.assertTrue(entered.await(3, TimeUnit.SECONDS));
        call.cancel();
        call.cancel();
        release.countDown();
        call.done().get(5, TimeUnit.SECONDS);
        Assertions.assertEquals(1, deletes.get());
        Assertions.assertTrue(events.isEmpty());
    }

    @Test
    void cancellationClosesRunningStream() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        stream =
                exchange -> {
                    exchange.sendResponseHeaders(200, 0);
                    exchange.getResponseBody()
                            .write(": heartbeat\n\n".getBytes(StandardCharsets.UTF_8));
                    exchange.getResponseBody().flush();
                    entered.countDown();
                    release.await(5, TimeUnit.SECONDS);
                    exchange.close();
                };
        var call = call();
        Assertions.assertTrue(entered.await(3, TimeUnit.SECONDS));
        call.cancel();
        call.done().get(5, TimeUnit.SECONDS);
        release.countDown();
        Assertions.assertEquals(1, deletes.get());
        Assertions.assertTrue(events.isEmpty());
    }

    @Test
    void completedResultDoesNotSendDelete() throws Exception {
        var call = call();
        call.done().get(5, TimeUnit.SECONDS);
        call.cancel();
        Assertions.assertEquals(0, deletes.get());
    }

    @Test
    void unauthorizedStreamIsNotRetried() throws Exception {
        var reads = new AtomicInteger();
        stream =
                exchange -> {
                    reads.incrementAndGet();
                    reply(exchange, 401, "unauthorized");
                };
        Assertions.assertThrows(
                ExecutionException.class, () -> call().done().get(5, TimeUnit.SECONDS));
        Assertions.assertEquals(1, reads.get());
        Assertions.assertEquals(1, deletes.get());
    }

    @Test
    void submissionFailureDoesNotCreateOrDeleteJob() {
        submission = exchange -> reply(exchange, 429, "queue full");
        Assertions.assertThrows(
                ExecutionException.class, () -> call().done().get(5, TimeUnit.SECONDS));
        Assertions.assertEquals(0, deletes.get());
    }

    @Test
    void mismatchedJobAndMalformedTensorFailClosed() throws Exception {
        stream = exchange -> reply(exchange, 200, result(1).replace("test-job", "foreign-job"));
        Assertions.assertThrows(
                ExecutionException.class, () -> call().done().get(5, TimeUnit.SECONDS));
        Assertions.assertTrue(events.isEmpty());
        Assertions.assertEquals(1, deletes.get());
        stream =
                exchange ->
                        reply(
                                exchange,
                                200,
                                "id: 1\nevent: result\ndata: {\"job_id\":\"test-job\",\"diff\":{\"encoding\":\"voxel-tensor-v1\",\"data\":\"bad\"}}\n\n");
        Assertions.assertThrows(
                ExecutionException.class, () -> call().done().get(5, TimeUnit.SECONDS));
        Assertions.assertTrue(events.isEmpty());
    }

    @Test
    void errorIsTerminalAndCanceledIsPreserved() throws Exception {
        stream =
                exchange ->
                        reply(
                                exchange,
                                200,
                                "id: 1\nevent: error\ndata: {\"job_id\":\"test-job\",\"code\":\"CANCELED\",\"message\":\"canceled\"}\n\n");
        call().done().get(5, TimeUnit.SECONDS);
        Assertions.assertEquals("CANCELED", ((Event.Failure) events.getFirst()).code());
        Assertions.assertEquals(0, deletes.get());
    }

    @Test
    void truncatedStreamsHaveBoundedRetries() {
        var reads = new AtomicInteger();
        stream =
                exchange -> {
                    reads.incrementAndGet();
                    reply(exchange, 200, ": ping\n\n");
                };
        Assertions.assertThrows(
                ExecutionException.class, () -> call().done().get(5, TimeUnit.SECONDS));
        Assertions.assertEquals(3, reads.get());
        Assertions.assertEquals(1, deletes.get());
    }

    @Test
    void closeCancelsPendingSubmission() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        submission =
                exchange -> {
                    entered.countDown();
                    release.await(5, TimeUnit.SECONDS);
                    reply(exchange, 202, "{\"job_id\":\"test-job\"}");
                };
        var call = call();
        Assertions.assertTrue(entered.await(3, TimeUnit.SECONDS));
        client.close();
        release.countDown();
        call.done().get(5, TimeUnit.SECONDS);
        Assertions.assertEquals(1, deletes.get());
        Assertions.assertTrue(events.isEmpty());
    }

    @Test
    void invalidJobIdCompletesExceptionallyWithoutUsingItAsAPath() {
        submission = exchange -> reply(exchange, 202, "{\"job_id\":\"../../bad path\"}");
        Assertions.assertThrows(
                ExecutionException.class, () -> call().done().get(5, TimeUnit.SECONDS));
        Assertions.assertEquals(0, deletes.get());
    }

    @Test
    void stalledSubmitBodyTimesOutDuringShutdown() throws Exception {
        client.close();
        client = new EngineClient(Duration.ofMillis(500));
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        submission =
                exchange -> {
                    exchange.sendResponseHeaders(202, 0);
                    exchange.getResponseBody().write('{');
                    exchange.getResponseBody().flush();
                    entered.countDown();
                    release.await(5, TimeUnit.SECONDS);
                    exchange.close();
                };
        var call = call();
        Assertions.assertTrue(entered.await(3, TimeUnit.SECONDS));
        client.close();
        try {
            call.done().get(3, TimeUnit.SECONDS);
        } finally {
            release.countDown();
        }
    }

    private EngineClient.Call call() {
        return client.generate(endpoint, "test-token", request(), events::add);
    }

    static Request request() {
        return new Request(
                "literal prompt",
                "minecraft:overworld",
                Region.between(BlockPos.ZERO, BlockPos.ZERO),
                "replacement",
                Settings.DEFAULT,
                new VoxelTensor.Dense(
                        new BlockPos(-8, -8, -8),
                        17,
                        17,
                        17,
                        List.of("minecraft:air"),
                        new int[] {0},
                        new int[] {4913}));
    }

    private static String result(int id) {
        var payload =
                TensorPayload.of(
                        new VoxelTensor.Sparse(
                                BlockPos.ZERO,
                                1,
                                1,
                                1,
                                List.of("minecraft:air", "minecraft:stone"),
                                new int[] {0},
                                new int[] {1}));
        var json = new JsonObject();
        json.addProperty("job_id", "test-job");
        json.add("diff", new Gson().toJsonTree(payload));
        return "id: " + id + "\nevent: result\ndata: " + json + "\n\n";
    }

    private static void reply(HttpExchange exchange, int status, String text) throws IOException {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, status == 204 ? -1 : bytes.length);
        try (var out = exchange.getResponseBody()) {
            if (status != 204) out.write(bytes);
        }
    }
}
