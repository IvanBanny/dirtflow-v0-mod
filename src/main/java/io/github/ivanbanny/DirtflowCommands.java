package io.github.ivanbanny;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import io.github.ivanbanny.engine.Settings;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;

/** Command parsing; player state and world operations belong to ServerSession. */
public final class DirtflowCommands {
    private DirtflowCommands() {}

    @FunctionalInterface
    private interface Action {
        void run(
                CommandContext<CommandSourceStack> context,
                ServerSession session,
                ServerPlayer player)
                throws CommandSyntaxException;
    }

    public static void register() {
        List<String> prompts = prompts();
        CommandRegistrationCallback.EVENT.register(
                (dispatcher, registry, environment) -> register(dispatcher, prompts));
    }

    private static void register(
            CommandDispatcher<CommandSourceStack> dispatcher, List<String> prompts) {
        var root = Commands.literal("df").executes(DirtflowCommands::help);
        root.then(Commands.literal("help").executes(DirtflowCommands::help));
        root.then(action("accept", ServerSession::accept));
        root.then(action("reject", (session, player) -> session.cancel(player, true)));
        root.then(action("cancel", (session, player) -> session.cancel(player, false)));
        root.then(action("retry", ServerSession::retry));
        root.then(action("undo", (session, player) -> session.restore(player, false)));
        root.then(action("redo", (session, player) -> session.restore(player, true)));
        root.then(
                action(
                        "random",
                        (session, player) -> {
                            int previous = prompts.indexOf(session.previousPrompt(player));
                            int index =
                                    ThreadLocalRandom.current()
                                            .nextInt(prompts.size() - (previous < 0 ? 0 : 1));
                            if (previous >= 0 && index >= previous) index++;
                            session.generate(player, prompts.get(index), "replacement");
                        }));
        root.then(selection());
        root.then(settings());
        root.then(engine());
        for (boolean first : new boolean[] {true, false}) {
            var corner =
                    action(
                            first ? "pos1" : "pos2",
                            (session, player) ->
                                    session.corner(player, player.blockPosition(), first));
            var position = Commands.argument("position", BlockPosArgument.blockPos());
            position.executes(
                    player(
                            (ctx, session, player) ->
                                    session.corner(
                                            player,
                                            BlockPosArgument.getBlockPos(ctx, "position"),
                                            first)));
            root.then(corner.then(position));
        }
        for (String task : List.of("repair", "completion", "material")) {
            var prompt = Commands.argument("prompt", StringArgumentType.greedyString());
            prompt.executes(
                    player(
                            (ctx, session, player) ->
                                    session.generate(
                                            player,
                                            StringArgumentType.getString(ctx, "prompt"),
                                            task.equals("material") ? "material_only" : task)));
            root.then(Commands.literal(task).then(prompt));
        }
        var prompt = Commands.argument("prompt", StringArgumentType.greedyString());
        prompt.executes(
                player(
                        (ctx, session, player) ->
                                session.generate(
                                        player,
                                        StringArgumentType.getString(ctx, "prompt"),
                                        "replacement")));
        root.then(prompt);
        var node = dispatcher.register(root);
        dispatcher.register(
                Commands.literal("dirtflow").executes(node.getCommand()).redirect(node));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> selection() {
        var select = Commands.literal("select");
        select.then(action("previous", ServerSession::previous));
        var radius = Commands.argument("radius", IntegerArgumentType.integer(0, 30_000_000));
        radius.executes(
                player(
                        (ctx, session, player) -> {
                            int n = IntegerArgumentType.getInteger(ctx, "radius");
                            BlockPos pos = player.blockPosition();
                            session.select(player, pos.offset(-n, -n, -n), pos.offset(n, n, n));
                        }));
        select.then(radius);
        var from = Commands.argument("from", BlockPosArgument.blockPos());
        var to = Commands.argument("to", BlockPosArgument.blockPos());
        to.executes(
                player(
                        (ctx, session, player) ->
                                session.select(
                                        player,
                                        BlockPosArgument.getBlockPos(ctx, "from"),
                                        BlockPosArgument.getBlockPos(ctx, "to"))));
        select.then(from.then(to));
        return select;
    }

    private static LiteralArgumentBuilder<CommandSourceStack> settings() {
        var root =
                action(
                        "settings",
                        (session, player) ->
                                ServerSession.say(player, session.settings(player).describe()));
        root.then(Commands.literal("reset").executes(setting((ctx, old) -> Settings.DEFAULT)));
        var cfg = Commands.argument("value", DoubleArgumentType.doubleArg(0, 20));
        cfg.executes(
                setting(
                        (ctx, old) ->
                                new Settings(
                                        DoubleArgumentType.getDouble(ctx, "value"),
                                        old.shapeSteps(),
                                        old.materialSteps(),
                                        old.seed(),
                                        old.previewEverySteps())));
        root.then(Commands.literal("cfg").then(cfg));
        var seed = Commands.literal("seed");
        seed.then(
                Commands.literal("random")
                        .executes(
                                setting(
                                        (ctx, old) ->
                                                new Settings(
                                                        old.cfgScale(),
                                                        old.shapeSteps(),
                                                        old.materialSteps(),
                                                        null,
                                                        old.previewEverySteps()))));
        var value = Commands.argument("value", LongArgumentType.longArg(0, Settings.MAX_SEED));
        value.executes(
                setting(
                        (ctx, old) ->
                                new Settings(
                                        old.cfgScale(),
                                        old.shapeSteps(),
                                        old.materialSteps(),
                                        LongArgumentType.getLong(ctx, "value"),
                                        old.previewEverySteps())));
        root.then(seed.then(value));
        for (String name : List.of("shape-steps", "material-steps", "preview-every")) {
            boolean preview = name.equals("preview-every");
            var number =
                    Commands.argument(
                            "value",
                            IntegerArgumentType.integer(preview ? 0 : 1, preview ? 10000 : 200));
            number.executes(
                    setting(
                            (ctx, old) -> {
                                int n = IntegerArgumentType.getInteger(ctx, "value");
                                return new Settings(
                                        old.cfgScale(),
                                        name.equals("shape-steps") ? n : old.shapeSteps(),
                                        name.equals("material-steps") ? n : old.materialSteps(),
                                        old.seed(),
                                        preview ? n : old.previewEverySteps());
                            }));
            root.then(Commands.literal(name).then(number));
        }
        return root;
    }

    private static LiteralArgumentBuilder<CommandSourceStack> engine() {
        var engine = Commands.literal("engine").executes(ctx -> engine(ctx, false, null));
        engine.then(
                Commands.literal("reset")
                        .requires(DirtflowCommands::canConfigure)
                        .executes(ctx -> engine(ctx, true, null)));
        var address =
                Commands.argument("address", StringArgumentType.greedyString())
                        .requires(DirtflowCommands::canConfigure);
        address.executes(ctx -> engine(ctx, true, StringArgumentType.getString(ctx, "address")));
        return engine.then(address);
    }

    private static LiteralArgumentBuilder<CommandSourceStack> action(
            String name, BiConsumer<ServerSession, ServerPlayer> action) {
        return Commands.literal(name)
                .executes(player((ctx, session, player) -> action.accept(session, player)));
    }

    private static Command<CommandSourceStack> setting(
            BiFunction<CommandContext<CommandSourceStack>, Settings, Settings> change) {
        return player(
                (ctx, session, player) -> {
                    Settings settings = change.apply(ctx, session.settings(player));
                    session.preferences.settings(player.getUUID(), settings);
                    ServerSession.say(player, settings.describe());
                });
    }

    private static Command<CommandSourceStack> player(Action action) {
        return ctx -> {
            ServerPlayer player = ctx.getSource().getPlayerOrException();
            try {
                action.run(ctx, Dirtflow.session(ctx.getSource().getServer()), player);
                return 1;
            } catch (IllegalArgumentException
                    | IllegalStateException
                    | UncheckedIOException error) {
                Dirtflow.LOGGER.debug("Dirtflow command failed", error);
                ctx.getSource().sendFailure(Component.literal(error.getMessage()));
                return 0;
            }
        };
    }

    private static boolean canConfigure(CommandSourceStack source) {
        return Commands.hasPermission(Commands.LEVEL_ADMINS).test(source)
                || (source.getPlayer() != null
                        && source.getServer().isSingleplayerOwner(source.getPlayer().nameAndId()));
    }

    private static int engine(
            CommandContext<CommandSourceStack> ctx, boolean change, String address) {
        try {
            ServerSession session = Dirtflow.session(ctx.getSource().getServer());
            if (change) session.preferences.setEngine(address);
            ctx.getSource()
                    .sendSuccess(() -> Component.literal("Engine: " + session.endpoint()), false);
            return 1;
        } catch (IllegalArgumentException | IllegalStateException | UncheckedIOException error) {
            ctx.getSource().sendFailure(Component.literal(error.getMessage()));
            return 0;
        }
    }

    private static int help(CommandContext<CommandSourceStack> ctx) {
        ctx.getSource()
                .sendSuccess(
                        () ->
                                Component.literal(
                                        """
            /df <prompt> - Generate inside the selection.
            /df random - Pick a demo prompt.
            /df repair|completion|material <prompt> - Choose an editing task.
            /df select <radius> - Select a cube around your feet.
            /df select <x y z> <x y z> - Select two corners. ~ and ^ work.
            /df select previous - Restore the last complete selection.
            /df pos1|pos2 [x y z] - Set a corner, defaulting to your feet.
            /df accept|reject|retry|cancel - Review or stop generation.
            /df undo|redo - Restore the last edit.
            /df settings - Show your settings.
            /df settings cfg|shape-steps|material-steps|preview-every <n>
            /df settings seed <n|random>, /df settings reset
            /df engine [address|reset] - Engine address; changes require an operator.
            /dirtflow is an alias. Selections fit up to 58 blocks per axis.
            """),
                        false);
        return 1;
    }

    private static List<String> prompts() {
        try (var reader =
                new InputStreamReader(
                        Objects.requireNonNull(
                                DirtflowCommands.class.getResourceAsStream(
                                        "/assets/dirtflow/demo-prompts.json")),
                        StandardCharsets.UTF_8)) {
            List<String> prompts =
                    JsonParser.parseReader(reader).getAsJsonArray().asList().stream()
                            .map(JsonElement::getAsString)
                            .toList();
            if (prompts.size() < 2
                    || prompts.stream().anyMatch(String::isBlank)
                    || prompts.stream().distinct().count() != prompts.size())
                throw new IllegalStateException("Demo prompts must be distinct and nonempty");
            return prompts;
        } catch (IOException error) {
            throw new UncheckedIOException("Cannot read demo prompts", error);
        }
    }
}
