package io.github.ivanbanny.engine;

import com.google.gson.JsonParser;
import io.github.ivanbanny.Dirtflow;
import io.github.ivanbanny.Preferences;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** One HTTP client per server, with cancelable calls and bounded SSE reconnection. */
public final class EngineClient implements AutoCloseable {
    private static final int MAX_EVENT = 12 * 1024 * 1024;
    private final HttpClient http;
    private final Duration timeout;
    private final Set<Call> calls = ConcurrentHashMap.newKeySet();
    private final ScheduledExecutorService watchdog = Executors.newSingleThreadScheduledExecutor();
    private volatile boolean closed;

    public EngineClient(Duration timeout) {
        this.timeout = timeout;
        http =
                HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(10))
                        .version(HttpClient.Version.HTTP_1_1)
                        .build();
        watchdog.scheduleAtFixedRate(
                () ->
                        calls.forEach(
                                call -> {
                                    if (System.nanoTime() - call.lastRead > timeout.toNanos()) {
                                        call.closeBody();
                                        closeInput(call.submitBody);
                                    }
                                }),
                1,
                1,
                TimeUnit.SECONDS);
    }

    public Call generate(String endpoint, String token, Request request, Consumer<Event> events) {
        if (closed) throw new IllegalStateException("Engine client is closed");
        var call = new Call(Preferences.address(endpoint), token, request, events);
        calls.add(call);
        Thread.startVirtualThread(call::run);
        return call;
    }

    public final class Call {
        private final String endpoint;
        private final String token;
        private final Request request;
        private final Consumer<Event> events;
        private final CompletableFuture<Void> done = new CompletableFuture<>();
        private volatile boolean canceled;
        private volatile InputStream body;
        private volatile InputStream submitBody;
        private volatile long lastRead = System.nanoTime();
        private String job;
        private long cursor;
        private boolean terminal;

        private Call(String endpoint, String token, Request request, Consumer<Event> events) {
            this.endpoint = endpoint;
            this.token = token;
            this.request = request;
            this.events = events;
        }

        public CompletableFuture<Void> done() {
            return done;
        }

        public void cancel() {
            canceled = true;
            closeBody();
        }

        private HttpRequest.Builder route(String path) {
            var builder = HttpRequest.newBuilder(URI.create(endpoint + path)).timeout(timeout);
            if (token != null && !token.isBlank())
                builder.header("Authorization", "Bearer " + token);
            return builder;
        }

        private void run() {
            Throwable failure = null;
            try {
                if (canceled || closed) return;
                var response =
                        http.send(
                                route("/v1/generate")
                                        .header("Content-Type", "application/json")
                                        .POST(HttpRequest.BodyPublishers.ofString(request.json()))
                                        .build(),
                                BodyHandlers.ofInputStream());
                // Keep the POST response even after cancellation so its job can be deleted
                submitBody = response.body();
                lastRead = System.nanoTime();
                try (var input = response.body()) {
                    byte[] bytes = input.readNBytes(65537);
                    if (bytes.length > 65536)
                        throw new IllegalArgumentException("Oversized submit response");
                    String text = new String(bytes, StandardCharsets.UTF_8);
                    if (response.statusCode() != 202)
                        throw new IllegalStateException(
                                "Engine submit: HTTP " + response.statusCode() + " " + text);
                    String id =
                            JsonParser.parseString(text)
                                    .getAsJsonObject()
                                    .get("job_id")
                                    .getAsString();
                    if (!id.matches("[A-Za-z0-9_-]{1,128}"))
                        throw new IllegalArgumentException("Invalid engine job ID");
                    job = id;
                } finally {
                    submitBody = null;
                }
                for (int attempt = 0; attempt < 3 && !canceled && !closed; attempt++) {
                    try {
                        var stream =
                                http.send(
                                        route("/v1/jobs/" + job + "/events")
                                                .header("Accept", "text/event-stream")
                                                .header("Last-Event-ID", Long.toString(cursor))
                                                .GET()
                                                .build(),
                                        BodyHandlers.ofInputStream());
                        body = stream.body();
                        lastRead = System.nanoTime();
                        if (canceled || closed) return;
                        int status = stream.statusCode();
                        if (Set.of(400, 401, 403, 404, 422).contains(status))
                            throw new IllegalStateException("Engine events: HTTP " + status);
                        if (status != 200) throw new IOException("Engine events: HTTP " + status);
                        if (consume()) return;
                        throw new IOException("Engine stream ended before a result");
                    } catch (IOException error) {
                        if (canceled || closed) return;
                        if (attempt == 2) throw error;
                        Thread.sleep(250L * (attempt + 1));
                    } finally {
                        closeBody();
                    }
                }
            } catch (IOException | InterruptedException | RuntimeException error) {
                if (error instanceof InterruptedException) Thread.currentThread().interrupt();
                failure = error;
            } finally {
                closeBody();
                if (job != null && !terminal) delete();
                calls.remove(this);
                if (failure == null || canceled || closed) done.complete(null);
                else done.completeExceptionally(failure);
            }
        }

        private boolean consume() throws IOException {
            try (var reader =
                    new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8))) {
                String name = "";
                long id = cursor;
                var data = new StringBuilder();
                String line;
                while (!canceled && !closed && (line = line(reader)) != null) {
                    lastRead = System.nanoTime();
                    if (line.isEmpty()) {
                        if (name.equals("end"))
                            throw new IOException("Engine ended without result or error");
                        if (!name.isEmpty() && id > cursor) {
                            Event event = Event.parse(name, data.toString(), job);
                            if (event instanceof Event.Result || event instanceof Event.Failure)
                                terminal = true;
                            if (event != null && !canceled && !closed) events.accept(event);
                            cursor = id;
                            if (terminal) return true;
                        }
                        name = "";
                        data.setLength(0);
                    } else if (line.startsWith("event:")) name = field(line, 6);
                    else if (line.startsWith("id:")) {
                        id = Long.parseLong(field(line, 3));
                        if (id < cursor)
                            throw new IllegalArgumentException("SSE sequence regressed");
                    } else if (line.startsWith("data:")) {
                        if (data.length() + line.length() > MAX_EVENT)
                            throw new IllegalArgumentException("SSE event exceeds 12 MiB");
                        if (!data.isEmpty()) data.append('\n');
                        data.append(field(line, 5));
                    }
                }
                return false;
            }
        }

        private void delete() {
            try {
                int status =
                        http.send(
                                        route("/v1/jobs/" + job)
                                                .timeout(Duration.ofSeconds(10))
                                                .DELETE()
                                                .build(),
                                        BodyHandlers.discarding())
                                .statusCode();
                if (status != 200 && status != 204 && status != 404)
                    Dirtflow.LOGGER.warn(
                            "Engine cancellation returned HTTP {} for {}", status, job);
            } catch (IOException | InterruptedException error) {
                if (error instanceof InterruptedException) Thread.currentThread().interrupt();
                Dirtflow.LOGGER.warn("Cannot cancel engine job {}", job, error);
            }
        }

        private void closeBody() {
            closeInput(body);
        }
    }

    private static void closeInput(InputStream input) {
        if (input == null) return;
        try {
            input.close();
        } catch (IOException error) {
            Dirtflow.LOGGER.debug("Closing engine stream", error);
        }
    }

    private static String line(BufferedReader reader) throws IOException {
        var text = new StringBuilder();
        for (int c; (c = reader.read()) != -1; ) {
            if (c == '\n') return text.toString();
            if (c != '\r') text.append((char) c);
            if (text.length() > MAX_EVENT)
                throw new IllegalArgumentException("SSE line exceeds 12 MiB");
        }
        return text.isEmpty() ? null : text.toString();
    }

    private static String field(String line, int start) {
        if (line.length() > start && line.charAt(start) == ' ') start++;
        return line.substring(start);
    }

    @Override
    public void close() {
        closed = true;
        calls.forEach(Call::cancel);
        Thread.startVirtualThread(
                () -> {
                    CompletableFuture.allOf(
                                    calls.stream()
                                            .map(Call::done)
                                            .toArray(CompletableFuture[]::new))
                            .handle((value, error) -> null)
                            .join();
                    watchdog.shutdownNow();
                    http.close();
                });
    }
}
