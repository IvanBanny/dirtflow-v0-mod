package io.github.ivanbanny.client;

import com.mojang.blaze3d.vertex.VertexConsumer;
import io.github.ivanbanny.Dirtflow;
import io.github.ivanbanny.Packets;
import io.github.ivanbanny.protocol.TensorPayload;
import io.github.ivanbanny.protocol.VoxelTensor;
import io.github.ivanbanny.protocol.VoxelTensorCodec;
import io.github.ivanbanny.world.Edit;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.block.model.BlockModelPart;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Incremental ghost geometry. The client thread owns frames, assembly and rendering. */
final class PreviewRenderer {
    private static final int[][][] CORNERS = {
        {{0, 0, 0}, {1, 0, 0}, {1, 0, 1}, {0, 0, 1}},
        {{0, 1, 0}, {0, 1, 1}, {1, 1, 1}, {1, 1, 0}},
        {{0, 0, 0}, {0, 1, 0}, {1, 1, 0}, {1, 0, 0}},
        {{0, 0, 1}, {1, 0, 1}, {1, 1, 1}, {0, 1, 1}},
        {{0, 0, 0}, {0, 0, 1}, {0, 1, 1}, {0, 1, 0}},
        {{1, 0, 0}, {1, 1, 0}, {1, 1, 1}, {1, 0, 1}}
    };

    private record Quad(BakedQuad quad, int tint) {}

    private record Model(BlockPos pos, List<Quad> quads) {}

    private record Face(BlockPos pos, Direction direction, int color) {}

    private record Frame(long sequence, String stage, byte[] bytes) {}

    private String request = "", dimension = "";
    private ByteArrayOutputStream assembly;
    private Packets.Preview header;
    private int nextPart;
    private Frame pending, latest;
    private Builder building;
    private List<Model> models = List.of();
    private List<Face> faces = List.of();
    private long received, displayed;

    void begin(String request, String dimension) {
        clear();
        this.request = request;
        this.dimension = dimension;
    }

    void receive(Packets.Preview packet) {
        if (!packet.request().equals(request)
                || !packet.dimension().equals(dimension)
                || packet.sequence() <= received) return;
        if (packet.part() == 0) {
            assembly = new ByteArrayOutputStream();
            header = packet;
            nextPart = 0;
        }
        if (assembly == null
                || packet.part() != nextPart
                || packet.sequence() != header.sequence()
                || packet.count() != header.count()
                || !packet.stage().equals(header.stage()))
            throw new IllegalArgumentException("Out-of-order preview chunks");
        assembly.writeBytes(packet.bytes());
        nextPart++;
        if (nextPart == packet.count()) {
            pending = new Frame(packet.sequence(), packet.stage(), assembly.toByteArray());
            received = packet.sequence();
            assembly = null;
            header = null;
        }
    }

    void clear() {
        request = "";
        dimension = "";
        assembly = null;
        header = null;
        pending = null;
        latest = null;
        building = null;
        models = List.of();
        faces = List.of();
        received = 0;
        displayed = 0;
    }

    void reload() {
        building = null;
        models = List.of();
        faces = List.of();
        if (pending == null) pending = latest;
    }

    long displayedSequence() {
        return displayed;
    }

    void tick(Minecraft client) {
        if (client.level == null
                || !dimension.equals(client.level.dimension().identifier().toString())) {
            clear();
            return;
        }
        try {
            if (building == null && pending != null) {
                latest = pending;
                pending = null;
                building = new Builder(latest);
            }
            if (building != null && building.advance(client)) {
                models = building.models;
                faces = building.faces;
                displayed = building.sequence;
                building = null;
            }
        } catch (RuntimeException error) {
            Dirtflow.LOGGER.warn("Cannot render Dirtflow preview", error);
            clear();
        }
    }

    void render(WorldRenderContext context) {
        var camera = context.worldState().cameraRenderState;
        if (!camera.initialized || (models.isEmpty() && faces.isEmpty())) return;
        var matrices = context.matrices();
        if (!models.isEmpty()) {
            VertexConsumer out =
                    context.consumers()
                            .getBuffer(RenderTypes.entityTranslucent(TextureAtlas.LOCATION_BLOCKS));
            for (Model model : models) {
                matrices.pushPose();
                matrices.translate(
                        model.pos.getX() - camera.pos.x() + 0.5,
                        model.pos.getY() - camera.pos.y() + 0.5,
                        model.pos.getZ() - camera.pos.z() + 0.5);
                // Separate ghost faces from coincident world faces
                matrices.scale(1.002f, 1.002f, 1.002f);
                matrices.translate(-0.5f, -0.5f, -0.5f);
                for (Quad face : model.quads) {
                    float shade =
                            Minecraft.getInstance()
                                    .level
                                    .getShade(face.quad.direction(), face.quad.shade());
                    out.putBulkData(
                            matrices.last(),
                            face.quad,
                            ((face.tint >> 16) & 255) / 255f * shade,
                            ((face.tint >> 8) & 255) / 255f * shade,
                            (face.tint & 255) / 255f * shade,
                            0.65f,
                            LightTexture.FULL_BRIGHT,
                            OverlayTexture.NO_OVERLAY);
                }
                matrices.popPose();
            }
        }
        if (!faces.isEmpty()) {
            var out = context.consumers().getBuffer(RenderTypes.debugFilledBox());
            for (Face face : faces) {
                for (int[] corner : CORNERS[face.direction.ordinal()]) {
                    out.addVertex(
                                    matrices.last(),
                                    (float) (face.pos.getX() + corner[0] - camera.pos.x()),
                                    (float) (face.pos.getY() + corner[1] - camera.pos.y()),
                                    (float) (face.pos.getZ() + corner[2] - camera.pos.z()))
                            .setColor(face.color);
                }
            }
        }
    }

    private static final class Builder {
        final VoxelTensor.Dense tensor;
        final int[] cells;
        final BlockState[] states;
        final List<List<BlockModelPart>> parts = new ArrayList<>();
        final List<Model> models = new ArrayList<>();
        final List<Face> faces = new ArrayList<>();
        final boolean textured;
        final long sequence;
        int paletteCursor = 1, cursor;

        Builder(Frame frame) {
            VoxelTensor decoded = VoxelTensorCodec.decode(TensorPayload.unpack(frame.bytes));
            if (!(decoded instanceof VoxelTensor.Dense dense)
                    || decoded.sizeX() > 58
                    || decoded.sizeY() > 58
                    || decoded.sizeZ() > 58)
                throw new IllegalArgumentException("Preview must be a bounded dense tensor");
            tensor = dense;
            sequence = frame.sequence;
            textured = frame.stage.equals("blockstates");
            cells = new int[(int) tensor.totalVoxels()];
            states = new BlockState[tensor.palette().size()];
            for (int i = 0; i < states.length; i++) parts.add(List.of());
            int start = 0;
            for (int i = 0; i < tensor.runCount(); i++) {
                Arrays.fill(
                        cells, start, start + tensor.runLengths()[i], tensor.runPaletteIds()[i]);
                start += tensor.runLengths()[i];
            }
        }

        boolean advance(Minecraft client) {
            long deadline = System.nanoTime() + 3_000_000;
            var blocks = client.level.registryAccess().lookupOrThrow(Registries.BLOCK);
            for (; paletteCursor < states.length; paletteCursor++) {
                if (System.nanoTime() >= deadline) return false;
                BlockState state = Edit.parse(blocks, tensor.palette().get(paletteCursor));
                states[paletteCursor] = state;
                if (textured && state.getRenderShape() == RenderShape.MODEL)
                    parts.set(
                            paletteCursor,
                            client.getBlockRenderer()
                                    .getBlockModel(state)
                                    .collectParts(RandomSource.create(42)));
            }
            for (; cursor < cells.length; cursor++) {
                if ((cursor & 31) == 0 && System.nanoTime() >= deadline) return false;
                int id = cells[cursor];
                if (id == 0 || states[id].isAir()) continue;
                int x = tensor.localX(cursor), y = tensor.localY(cursor), z = tensor.localZ(cursor);
                BlockPos pos = tensor.worldPos(cursor);
                var quads = new ArrayList<Quad>();
                boolean visible = false;
                for (Direction side : Direction.values()) {
                    int nx = x + side.getStepX(),
                            ny = y + side.getStepY(),
                            nz = z + side.getStepZ();
                    boolean exposed =
                            nx < 0
                                    || ny < 0
                                    || nz < 0
                                    || nx >= tensor.sizeX()
                                    || ny >= tensor.sizeY()
                                    || nz >= tensor.sizeZ();
                    if (!exposed) {
                        int neighbor = cells[tensor.linearIndex(nx, ny, nz)];
                        exposed = neighbor == 0 || !states[neighbor].isSolidRender();
                    }
                    if (!exposed) continue;
                    visible = true;
                    if (parts.get(id).isEmpty()) {
                        int color = states[id].getMapColor(client.level, pos).col;
                        faces.add(
                                new Face(pos, side, 0xB0000000 | (color == 0 ? 0x909090 : color)));
                    } else
                        for (var part : parts.get(id))
                            add(client, quads, part.getQuads(side), states[id], pos);
                }
                if (visible)
                    for (var part : parts.get(id))
                        add(client, quads, part.getQuads(null), states[id], pos);
                if (!quads.isEmpty()) models.add(new Model(pos, quads));
            }
            return true;
        }

        private static void add(
                Minecraft client,
                List<Quad> target,
                List<BakedQuad> source,
                BlockState state,
                BlockPos pos) {
            for (BakedQuad quad : source)
                target.add(
                        new Quad(
                                quad,
                                quad.isTinted()
                                        ? client.getBlockColors()
                                                .getColor(
                                                        state, client.level, pos, quad.tintIndex())
                                        : 0xFFFFFF));
        }
    }
}
