package io.github.ivanbanny;

import io.github.ivanbanny.engine.EngineClient;
import io.github.ivanbanny.engine.Event;
import io.github.ivanbanny.engine.Request;
import io.github.ivanbanny.engine.Settings;
import io.github.ivanbanny.world.Edit;
import io.github.ivanbanny.world.History;
import io.github.ivanbanny.world.Preview;
import io.github.ivanbanny.world.Region;
import io.github.ivanbanny.world.Snapshot;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.storage.LevelResource;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/** Server-thread owner of selections, in-flight requests and preview transactions. */
public final class ServerSession implements AutoCloseable {
    private final MinecraftServer server;
    private final EngineClient engine;
    private final Map<UUID, PlayerState> players = new HashMap<>();
    private final History history;
    public final Preferences preferences;
    private final String defaultEndpoint;
    private final String token;
    private boolean closed;

    private static final class PlayerState {
        ResourceKey<Level> dimension;
        BlockPos first, second;
        Selection previous;
        Retry retry;
        Attempt attempt;
    }

    private record Selection(ResourceKey<Level> dimension, Region region) {}

    private record Retry(String prompt, String task, Selection selection) {}

    private static final class Attempt {
        final String id = UUID.randomUUID().toString();
        final Retry retry;
        final Snapshot snapshot;
        final AtomicReference<Event.Progress> progress = new AtomicReference<>();
        final AtomicReference<Event.Preview> frame = new AtomicReference<>();
        EngineClient.Call call;
        Edit edit;
        long sequence;
        boolean queued;
        volatile boolean terminal;

        Attempt(Retry retry, Snapshot snapshot) {
            this.retry = retry;
            this.snapshot = snapshot;
        }
    }

    public ServerSession(MinecraftServer server) {
        this.server = server;
        var root = server.getWorldPath(LevelResource.ROOT).resolve("dirtflow");
        preferences = new Preferences(root.resolve("preferences.json"));
        history =
                new History(
                        root.resolve("undo"),
                        server.registryAccess().lookupOrThrow(Registries.BLOCK));
        defaultEndpoint =
                Preferences.address(
                        System.getProperty("dirtflow.server.url", "http://127.0.0.1:8000"));
        token = System.getProperty("dirtflow.server.token", System.getenv("DIRTFLOW_TOKEN"));
        var rules = server.overworld().getGameRules();
        rules.set(GameRules.RANDOM_TICK_SPEED, 0, server);
        rules.set(GameRules.FIRE_SPREAD_RADIUS_AROUND_PLAYER, 0, server);
        engine = new EngineClient(Duration.ofSeconds(120));
    }

    private PlayerState state(ServerPlayer player) {
        PlayerState state = players.computeIfAbsent(player.getUUID(), id -> new PlayerState());
        if (!player.level().dimension().equals(state.dimension)) {
            discard(player, state);
            state.first = null;
            state.second = null;
            state.dimension = player.level().dimension();
            sync(player, state);
        }
        return state;
    }

    Region selection(UUID player) {
        PlayerState state = players.get(player);
        return state == null || state.first == null || state.second == null
                ? null
                : Region.between(state.first, state.second);
    }

    Edit preview(UUID player) {
        PlayerState state = players.get(player);
        return state == null || state.attempt == null ? null : state.attempt.edit;
    }

    public String endpoint() {
        return preferences.engine(defaultEndpoint);
    }

    public Settings settings(ServerPlayer player) {
        return preferences.settings(player.getUUID());
    }

    public String previousPrompt(ServerPlayer player) {
        Retry retry = state(player).retry;
        return retry == null ? null : retry.prompt();
    }

    public void select(ServerPlayer player, BlockPos first, BlockPos second) {
        ServerLevel level = (ServerLevel) player.level();
        if ((first != null
                        && (level.isOutsideBuildHeight(first) || !Level.isInSpawnableBounds(first)))
                || (second != null
                        && (level.isOutsideBuildHeight(second)
                                || !Level.isInSpawnableBounds(second))))
            throw new IllegalArgumentException("Selection outside world bounds");
        PlayerState state = state(player);
        if (Objects.equals(first, state.first) && Objects.equals(second, state.second)) return;
        boolean canceled = discard(player, state);
        state.first = first == null ? null : first.immutable();
        state.second = second == null ? null : second.immutable();
        if (first != null && second != null)
            state.previous = new Selection(state.dimension, Region.between(first, second));
        sync(player, state);
        String message =
                first == null && second == null
                        ? "Selection cleared"
                        : first != null && second != null
                                ? "Selection: " + Region.between(first, second).describe()
                                : "Corner "
                                        + (first != null ? "1: " : "2: ")
                                        + (first != null ? first : second).toShortString();
        say(player, message + (canceled ? "; generation canceled" : ""));
    }

    public void corner(ServerPlayer player, BlockPos pos, boolean first) {
        PlayerState state = state(player);
        select(player, first ? pos : state.first, first ? state.second : pos);
    }

    public void click(ServerPlayer player, BlockPos pos) {
        PlayerState state = state(player);
        if (state.first != null && (state.second == null || player.isShiftKeyDown()))
            select(player, state.first, pos);
        else select(player, pos, null);
    }

    public void previous(ServerPlayer player) {
        Selection selection = state(player).previous;
        if (selection == null) throw new IllegalStateException("No previous selection");
        if (!selection.dimension().equals(player.level().dimension()))
            throw new IllegalStateException("Return to " + selection.dimension().identifier());
        select(player, selection.region().min(), selection.region().max());
    }

    public void generate(ServerPlayer player, String prompt, String task) {
        PlayerState state = state(player);
        if (state.first == null || state.second == null)
            throw new IllegalStateException("Select two corners with a wooden hoe or /df select");
        start(
                player,
                state,
                new Retry(
                        prompt,
                        task,
                        new Selection(state.dimension, Region.between(state.first, state.second))),
                settings(player));
    }

    private void start(ServerPlayer player, PlayerState state, Retry retry, Settings settings) {
        Region region = retry.selection().region();
        Snapshot snapshot = Snapshot.capture((ServerLevel) player.level(), region);
        var request =
                new Request(
                        retry.prompt(),
                        state.dimension.identifier().toString(),
                        region,
                        retry.task(),
                        settings,
                        snapshot.tensor());
        history.clearRedo(player.getUUID());
        discard(player, state);
        var attempt = new Attempt(retry, snapshot);
        state.attempt = attempt;
        state.retry = retry;
        String endpoint = endpoint();
        status(
                player,
                attempt.id,
                "SUBMITTING",
                -1,
                "Request submitted. Prompt: " + retry.prompt());
        attempt.call =
                engine.generate(
                        endpoint,
                        endpoint.equals(defaultEndpoint) ? token : null,
                        request,
                        event -> {
                            if (event instanceof Event.Progress progress)
                                attempt.progress.set(progress);
                            else if (event instanceof Event.Preview frame) attempt.frame.set(frame);
                            else {
                                if (event instanceof Event.Result
                                        || event instanceof Event.Failure) {
                                    attempt.terminal = true;
                                    attempt.progress.set(null);
                                    attempt.frame.set(null);
                                }
                                server.execute(() -> handle(player, state, attempt, event));
                            }
                        });
        attempt.call
                .done()
                .whenComplete(
                        (value, error) -> {
                            if (error != null)
                                server.execute(
                                        () -> {
                                            if (current(player, state, attempt))
                                                fail(player, state, attempt, error);
                                        });
                        });
    }

    public void retry(ServerPlayer player) {
        PlayerState state = state(player);
        Retry retry = state.retry;
        if (retry == null) throw new IllegalStateException("No generation to retry");
        if (!retry.selection().dimension().equals(state.dimension))
            throw new IllegalStateException("Return to the generation dimension");
        Settings old = settings(player);
        Settings fresh =
                new Settings(
                        old.cfgScale(),
                        old.shapeSteps(),
                        old.materialSteps(),
                        null,
                        old.previewEverySteps());
        start(player, state, retry, fresh);
        state.first = retry.selection().region().min();
        state.second = retry.selection().region().max();
        sync(player, state);
    }

    private boolean current(ServerPlayer player, PlayerState state, Attempt attempt) {
        return !closed
                && players.get(player.getUUID()) == state
                && state.attempt == attempt
                && player.level().dimension().equals(attempt.retry.selection().dimension());
    }

    private void handle(ServerPlayer player, PlayerState state, Attempt attempt, Event event) {
        if (!current(player, state, attempt)) return;
        try {
            switch (event) {
                case Event.Status update -> {
                    if (update.phase().equals("QUEUED")) {
                        if (!attempt.queued)
                            status(
                                    player,
                                    attempt.id,
                                    "QUEUED",
                                    -1,
                                    "Queued (#" + update.queue() + ")");
                        attempt.queued = true;
                    }
                }
                case Event.Progress progress ->
                        status(
                                player,
                                attempt.id,
                                "GENERATING",
                                (float) progress.fraction(),
                                progress.message());
                case Event.Preview frame -> {
                    if (attempt.terminal || frame.sequence() <= attempt.sequence) return;
                    if (!frame.dimension().equals(state.dimension.identifier().toString())
                            || !attempt.retry.selection().region().matches(frame.tensor()))
                        throw new IllegalArgumentException(
                                "Preview does not match the request region");
                    attempt.sequence = frame.sequence();
                    Packets.preview(
                            player,
                            attempt.id,
                            frame.dimension(),
                            frame.sequence(),
                            frame.stage(),
                            frame.wire());
                }
                case Event.Result result -> {
                    attempt.edit =
                            Edit.decode(
                                    (ServerLevel) player.level(),
                                    result.tensor(),
                                    attempt.retry.selection().region(),
                                    attempt.snapshot.selected());
                    Preview.send(player, (ServerLevel) player.level(), attempt.edit.states());
                    status(
                            player,
                            attempt.id,
                            "PREVIEW",
                            1,
                            "Preview: "
                                    + attempt.edit.states().size()
                                    + " blocks. /df accept, reject or retry");
                }
                case Event.Failure error -> {
                    discard(player, state);
                    status(
                            player,
                            attempt.id,
                            error.code().equals("CANCELED") ? "CANCELED" : "FAILED",
                            -1,
                            error.message());
                }
            }
        } catch (RuntimeException error) {
            fail(player, state, attempt, error);
        }
    }

    private void fail(ServerPlayer player, PlayerState state, Attempt attempt, Throwable error) {
        Dirtflow.LOGGER.warn("Generation {} failed", attempt.id, error);
        discard(player, state);
        status(
                player,
                attempt.id,
                "FAILED",
                -1,
                error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage());
    }

    public void tick() {
        for (var entry : players.entrySet()) {
            ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
            if (player == null) continue;
            PlayerState state = state(player);
            Attempt attempt = state.attempt;
            if (attempt == null) continue;
            Event.Progress progress = attempt.progress.getAndSet(null);
            Event.Preview frame = attempt.frame.getAndSet(null);
            if (!attempt.terminal && progress != null) handle(player, state, attempt, progress);
            if (!attempt.terminal && frame != null) handle(player, state, attempt, frame);
        }
    }

    public void accept(ServerPlayer player) {
        PlayerState state = state(player);
        Attempt attempt = state.attempt;
        if (attempt == null || attempt.edit == null)
            throw new IllegalStateException("No preview to accept");
        int changed =
                attempt.edit.apply(
                        (ServerLevel) player.level(),
                        cells -> history.push(player.getUUID(), state.dimension, cells));
        discard(player, state);
        state.first = null;
        state.second = null;
        sync(player, state);
        status(
                player,
                attempt.id,
                "COMPLETE",
                1,
                "Placed " + changed + " blocks. /df undo restores the last edit");
    }

    public void cancel(ServerPlayer player, boolean reject) {
        PlayerState state = state(player);
        boolean canceled = discard(player, state);
        if (reject) {
            state.first = null;
            state.second = null;
            sync(player, state);
        }
        status(
                player,
                "",
                reject ? "REJECTED" : "CANCELED",
                -1,
                reject ? "Rejected" : canceled ? "Canceled" : "No active generation");
    }

    public void restore(ServerPlayer player, boolean redo) {
        History.Entry entry = history.peek(player.getUUID(), redo);
        if (entry == null)
            throw new IllegalStateException("No edit to " + (redo ? "redo" : "undo"));
        if (!entry.dimension().equals(player.level().dimension()))
            throw new IllegalStateException("Return to " + entry.dimension().identifier());
        PlayerState state = state(player);
        discard(player, state);
        Edit.restore(
                Edit.world((ServerLevel) player.level()),
                entry.cells(),
                inverse -> history.transfer(entry, redo, inverse));
        status(player, "", "COMPLETE", 1, redo ? "Redid the last edit" : "Undid the last edit");
    }

    private boolean discard(ServerPlayer player, PlayerState state) {
        Attempt attempt = state.attempt;
        if (attempt == null) return false;
        state.attempt = null;
        attempt.call.cancel();
        attempt.progress.set(null);
        attempt.frame.set(null);
        if (attempt.edit != null
                && player.level().dimension().equals(attempt.retry.selection().dimension()))
            Preview.clear(player, (ServerLevel) player.level(), attempt.edit);
        status(player, attempt.id, "CLEAR", -1, "");
        return true;
    }

    public void disconnect(ServerPlayer player) {
        PlayerState state = players.remove(player.getUUID());
        if (state != null && state.attempt != null) state.attempt.call.cancel();
    }

    @Override
    public void close() {
        closed = true;
        engine.close();
        players.clear();
    }

    private static void sync(ServerPlayer player, PlayerState state) {
        ServerPlayNetworking.send(player, new Packets.Selection(state.first, state.second));
    }

    private static void status(
            ServerPlayer player, String request, String phase, float fraction, String message) {
        String text = message.length() <= 512 ? message : message.substring(0, 509) + "...";
        ServerPlayNetworking.send(player, new Packets.Status(request, phase, fraction, text));
    }

    static void say(ServerPlayer player, String text) {
        player.sendSystemMessage(
                Component.literal("[Dirtflow] " + text).withStyle(ChatFormatting.GREEN));
    }
}
