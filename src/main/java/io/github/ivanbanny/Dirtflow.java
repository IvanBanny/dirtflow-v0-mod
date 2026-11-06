package io.github.ivanbanny;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Items;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class Dirtflow implements ModInitializer {
    public static final Logger LOGGER = LoggerFactory.getLogger("dirtflow");
    private static final Map<MinecraftServer, ServerSession> SERVERS = new ConcurrentHashMap<>();

    public static ServerSession session(MinecraftServer server) {
        ServerSession session = SERVERS.get(server);
        if (session == null) throw new IllegalStateException("Dirtflow server has not started");
        return session;
    }

    @Override
    public void onInitialize() {
        Packets.register();
        DirtflowCommands.register();
        ServerLifecycleEvents.SERVER_STARTED.register(
                server -> SERVERS.put(server, new ServerSession(server)));
        ServerLifecycleEvents.SERVER_STOPPING.register(
                server -> {
                    ServerSession session = SERVERS.remove(server);
                    if (session != null) session.close();
                });
        ServerTickEvents.END_SERVER_TICK.register(
                server -> {
                    ServerSession session = SERVERS.get(server);
                    if (session != null) session.tick();
                });
        ServerPlayConnectionEvents.DISCONNECT.register(
                (handler, server) -> {
                    ServerSession session = SERVERS.get(server);
                    if (session != null) session.disconnect(handler.getPlayer());
                });
        UseBlockCallback.EVENT.register(
                (player, level, hand, hit) -> {
                    if (hand != InteractionHand.MAIN_HAND
                            || !player.getItemInHand(hand).is(Items.WOODEN_HOE))
                        return InteractionResult.PASS;
                    if (player instanceof ServerPlayer serverPlayer)
                        session(serverPlayer.level().getServer())
                                .click(serverPlayer, hit.getBlockPos());
                    return InteractionResult.SUCCESS;
                });
        AttackBlockCallback.EVENT.register(
                (player, level, hand, pos, direction) -> {
                    if (!player.getItemInHand(hand).is(Items.WOODEN_HOE))
                        return InteractionResult.PASS;
                    if (player instanceof ServerPlayer serverPlayer)
                        session(serverPlayer.level().getServer()).select(serverPlayer, null, null);
                    return InteractionResult.FAIL;
                });
    }
}
