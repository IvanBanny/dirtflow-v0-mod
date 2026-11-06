package io.github.ivanbanny;

import io.github.ivanbanny.engine.EngineClient;
import io.github.ivanbanny.engine.Event;
import io.github.ivanbanny.engine.Request;
import io.github.ivanbanny.engine.Settings;
import io.github.ivanbanny.protocol.VoxelTensor;
import io.github.ivanbanny.protocol.VoxelTensorCodec;
import io.github.ivanbanny.world.Region;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

@Timeout(60)
class EngineIntegrationTest {
    @TempDir Path directory;

    @Test
    void pythonCodecAndRealHttpQueue() throws Exception {
        String python = System.getenv("DIRTFLOW_ENGINE_PYTHON");
        Assertions.assertNotNull(
                python,
                "Set DIRTFLOW_ENGINE_PYTHON to an environment with dirtflow-engine installed");
        Path script = directory.resolve("engine_peer.py");
        try (var input = getClass().getResourceAsStream("/engine_peer.py")) {
            Files.copy(input, script);
        }
        var codec =
                new ProcessBuilder(python, script.toString(), directory.toString())
                        .inheritIO()
                        .start();
        Assertions.assertTrue(codec.waitFor(20, TimeUnit.SECONDS));
        Assertions.assertEquals(0, codec.exitValue());
        byte[] raw = Files.readAllBytes(directory.resolve("python.vxt"));
        VoxelTensor decoded = VoxelTensorCodec.decode(raw);
        Assertions.assertEquals(new BlockPos(-7, 11, 25), decoded.origin());
        Assertions.assertEquals(58 * 58 * 58, decoded.totalVoxels());
        Assertions.assertArrayEquals(raw, VoxelTensorCodec.encode(decoded));
        Process peer =
                new ProcessBuilder(python, script.toString())
                        .redirectError(directory.resolve("engine.log").toFile())
                        .start();
        try (var reader = new BufferedReader(new InputStreamReader(peer.getInputStream()));
                var http = HttpClient.newHttpClient();
                var client = new EngineClient(Duration.ofSeconds(5))) {
            String port = reader.readLine();
            Assertions.assertNotNull(port, "Python engine failed to start");
            String endpoint = "http://127.0.0.1:" + Integer.parseInt(port);
            http.send(
                    HttpRequest.newBuilder(URI.create(endpoint + "/v1/health"))
                            .timeout(Duration.ofSeconds(10))
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.discarding());
            var events = new CopyOnWriteArrayList<Event>();
            var call = client.generate(endpoint, "interop-token", request("interop"), events::add);
            call.done().get(15, TimeUnit.SECONDS);
            Assertions.assertTrue(events.stream().anyMatch(Event.Preview.class::isInstance));
            Assertions.assertTrue(events.stream().anyMatch(Event.Progress.class::isInstance));
            var result =
                    events.stream()
                            .filter(Event.Result.class::isInstance)
                            .map(Event.Result.class::cast)
                            .findFirst()
                            .orElseThrow();
            var tensor = (VoxelTensor.Sparse) result.tensor();
            Assertions.assertEquals(58 * 58 * 58 / 2, tensor.entryCount());
            for (int i = 0; i < tensor.entryCount(); i++) {
                int index = tensor.indices()[i];
                Assertions.assertEquals(
                        1,
                        (tensor.localX(index) + tensor.localY(index) + tensor.localZ(index)) % 2);
                Assertions.assertEquals(
                        "minecraft:stone", tensor.palette().get(tensor.paletteIds()[i]));
            }
            var running = new java.util.concurrent.CountDownLatch(1);
            var canceled =
                    client.generate(
                            endpoint,
                            "interop-token",
                            request("wait for cancellation"),
                            event -> {
                                if (event instanceof Event.Status status
                                        && status.phase().equals("GENERATING")) running.countDown();
                            });
            Assertions.assertTrue(running.await(5, TimeUnit.SECONDS));
            canceled.cancel();
            canceled.done().get(15, TimeUnit.SECONDS);
        } finally {
            peer.destroy();
            if (!peer.waitFor(10, TimeUnit.SECONDS)) {
                peer.destroyForcibly();
                peer.waitFor(5, TimeUnit.SECONDS);
            }
        }
    }

    private static Request request(String prompt) {
        return new Request(
                prompt,
                "minecraft:overworld",
                Region.between(new BlockPos(-7, 11, 25), new BlockPos(50, 68, 82)),
                "replacement",
                Settings.DEFAULT,
                new VoxelTensor.Dense(
                        new BlockPos(-10, 8, 22),
                        64,
                        64,
                        64,
                        List.of("minecraft:air"),
                        new int[] {0, 0, 0, 0, 0},
                        new int[] {65535, 65535, 65535, 65535, 4}));
    }
}
