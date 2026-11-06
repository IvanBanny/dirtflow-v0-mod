package io.github.ivanbanny.client;

import io.github.ivanbanny.Dirtflow;
import io.github.ivanbanny.Packets;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.fabricmc.fabric.api.resource.v1.ResourceLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;

public final class DirtflowClient implements ClientModInitializer {
    final PreviewRenderer preview = new PreviewRenderer();
    final SelectionRenderer selection = new SelectionRenderer();
    private String dimension = "";

    @Override
    public void onInitializeClient() {
        ClientPlayNetworking.registerGlobalReceiver(
                Packets.Selection.TYPE,
                (packet, context) -> {
                    selection.first = packet.first();
                    selection.second = packet.second();
                });
        ClientPlayNetworking.registerGlobalReceiver(
                Packets.Status.TYPE, (packet, context) -> status(context.client(), packet));
        ClientPlayNetworking.registerGlobalReceiver(
                Packets.Preview.TYPE,
                (packet, context) -> {
                    try {
                        preview.receive(packet);
                    } catch (IllegalArgumentException error) {
                        Dirtflow.LOGGER.warn("Invalid preview packet", error);
                        preview.clear();
                    }
                });
        ClientTickEvents.END_CLIENT_TICK.register(
                client -> {
                    String current =
                            client.level == null
                                    ? ""
                                    : client.level.dimension().identifier().toString();
                    if (!current.equals(dimension)) {
                        dimension = current;
                        selection.first = null;
                        selection.second = null;
                        preview.clear();
                    }
                    preview.tick(client);
                });
        // END_MAIN runs with a bound render target before shared buffers are flushed
        WorldRenderEvents.END_MAIN.register(
                context -> {
                    preview.render(context);
                    selection.render(context);
                });
        ResourceLoader.get(PackType.CLIENT_RESOURCES)
                .registerReloader(
                        Identifier.fromNamespaceAndPath("dirtflow", "preview"),
                        (ResourceManagerReloadListener)
                                manager -> Minecraft.getInstance().execute(preview::reload));
        ClientPlayConnectionEvents.DISCONNECT.register(
                (handler, client) -> {
                    preview.clear();
                    selection.first = null;
                    selection.second = null;
                    dimension = "";
                });
    }

    private void status(Minecraft client, Packets.Status packet) {
        if (client.player == null || client.level == null) return;
        if (packet.phase().equals("SUBMITTING"))
            preview.begin(packet.request(), client.level.dimension().identifier().toString());
        if (!java.util.Set.of("SUBMITTING", "QUEUED", "GENERATING").contains(packet.phase()))
            preview.clear();
        if (packet.phase().equals("CLEAR")) return;
        boolean progress = packet.phase().equals("GENERATING");
        String text =
                progress
                        ? Math.round(packet.fraction() * 100) + "% " + packet.message()
                        : packet.message();
        ChatFormatting color =
                packet.phase().equals("FAILED")
                        ? ChatFormatting.RED
                        : progress ? ChatFormatting.AQUA : ChatFormatting.GREEN;
        client.player.displayClientMessage(
                Component.literal("[Dirtflow] " + text).withStyle(color), progress);
    }
}
