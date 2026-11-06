package io.github.ivanbanny;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;

import java.util.Arrays;

/** Mod packets are separate from the engine's HTTP and voxel protocols. */
public final class Packets {
    public static final int PREVIEW_CHUNK = 256 * 1024;

    private Packets() {}

    public record Selection(BlockPos first, BlockPos second) implements CustomPacketPayload {
        public static final Type<Selection> TYPE =
                new Type<>(Identifier.fromNamespaceAndPath("dirtflow", "selection"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Selection> CODEC =
                StreamCodec.of(
                        (buf, value) -> {
                            buf.writeBoolean(value.first != null);
                            if (value.first != null) buf.writeBlockPos(value.first);
                            buf.writeBoolean(value.second != null);
                            if (value.second != null) buf.writeBlockPos(value.second);
                        },
                        buf ->
                                new Selection(
                                        buf.readBoolean() ? buf.readBlockPos() : null,
                                        buf.readBoolean() ? buf.readBlockPos() : null));

        public Type<Selection> type() {
            return TYPE;
        }
    }

    public record Status(String request, String phase, float fraction, String message)
            implements CustomPacketPayload {
        public static final Type<Status> TYPE =
                new Type<>(Identifier.fromNamespaceAndPath("dirtflow", "status"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Status> CODEC =
                StreamCodec.of(
                        (buf, value) -> {
                            buf.writeUtf(value.request, 64);
                            buf.writeUtf(value.phase, 32);
                            buf.writeFloat(value.fraction);
                            buf.writeUtf(value.message, 512);
                        },
                        buf ->
                                new Status(
                                        buf.readUtf(64),
                                        buf.readUtf(32),
                                        buf.readFloat(),
                                        buf.readUtf(512)));

        public Type<Status> type() {
            return TYPE;
        }
    }

    public record Preview(
            String request,
            String dimension,
            long sequence,
            String stage,
            int part,
            int count,
            byte[] bytes)
            implements CustomPacketPayload {
        public static final Type<Preview> TYPE =
                new Type<>(Identifier.fromNamespaceAndPath("dirtflow", "preview"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Preview> CODEC =
                StreamCodec.of(
                        (buf, value) -> {
                            buf.writeUtf(value.request, 64);
                            buf.writeUtf(value.dimension, 128);
                            buf.writeLong(value.sequence);
                            buf.writeUtf(value.stage, 32);
                            buf.writeVarInt(value.part);
                            buf.writeVarInt(value.count);
                            buf.writeByteArray(value.bytes);
                        },
                        buf ->
                                new Preview(
                                        buf.readUtf(64),
                                        buf.readUtf(128),
                                        buf.readLong(),
                                        buf.readUtf(32),
                                        buf.readVarInt(),
                                        buf.readVarInt(),
                                        buf.readByteArray(PREVIEW_CHUNK)));

        public Preview {
            if (count < 1
                    || count > 32
                    || part < 0
                    || part >= count
                    || bytes.length > PREVIEW_CHUNK)
                throw new IllegalArgumentException("Invalid preview chunk");
        }

        public Type<Preview> type() {
            return TYPE;
        }
    }

    public static void register() {
        PayloadTypeRegistry.playS2C().register(Selection.TYPE, Selection.CODEC);
        PayloadTypeRegistry.playS2C().register(Status.TYPE, Status.CODEC);
        PayloadTypeRegistry.playS2C().register(Preview.TYPE, Preview.CODEC);
    }

    public static void preview(
            ServerPlayer player,
            String request,
            String dimension,
            long sequence,
            String stage,
            byte[] wire) {
        int count = (wire.length + PREVIEW_CHUNK - 1) / PREVIEW_CHUNK;
        for (int part = 0; part < count; part++) {
            ServerPlayNetworking.send(
                    player,
                    new Preview(
                            request,
                            dimension,
                            sequence,
                            stage,
                            part,
                            count,
                            Arrays.copyOfRange(
                                    wire,
                                    part * PREVIEW_CHUNK,
                                    Math.min(wire.length, (part + 1) * PREVIEW_CHUNK))));
        }
    }
}
