package io.github.ivanbanny.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import io.github.ivanbanny.world.Region;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.core.BlockPos;

final class SelectionRenderer {
    private static final int[][] EDGES = {
        {0, 1}, {1, 3}, {3, 2}, {2, 0}, {4, 5}, {5, 7}, {7, 6}, {6, 4}, {0, 4}, {1, 5}, {2, 6},
        {3, 7}
    };
    private static final int[][] FACES = {
        {1, 3, 7, 5}, {0, 4, 6, 2}, {2, 6, 7, 3}, {0, 1, 5, 4}, {4, 5, 7, 6}, {0, 2, 3, 1}
    };
    BlockPos first, second;

    void render(WorldRenderContext context) {
        var camera = context.worldState().cameraRenderState;
        if ((first == null && second == null) || !camera.initialized) return;
        BlockPos a = first == null ? second : first;
        BlockPos b = second == null ? a : second;
        Region region = Region.between(a, b);
        float[][] corners = new float[8][3];
        for (int i = 0; i < 8; i++) {
            corners[i][0] =
                    (float)
                            (((i & 1) == 0 ? region.min().getX() : region.max().getX() + 1.0)
                                    - camera.pos.x());
            corners[i][1] =
                    (float)
                            (((i & 2) == 0 ? region.min().getY() : region.max().getY() + 1.0)
                                    - camera.pos.y());
            corners[i][2] =
                    (float)
                            (((i & 4) == 0 ? region.min().getZ() : region.max().getZ() + 1.0)
                                    - camera.pos.z());
        }
        int color = first != null && second != null ? 0xFF00FFFF : 0xFFFFFF00;
        var matrices = context.matrices();
        lines(
                context.consumers().getBuffer(RenderTypes.lines()),
                matrices.last(),
                corners,
                color,
                3);
        lines(
                context.consumers().getBuffer(RenderTypes.linesTranslucent()),
                matrices.last(),
                corners,
                (color & 0xFFFFFF) | 0x50000000,
                2);
        if (first == null || second == null) return;
        var fill = context.consumers().getBuffer(RenderTypes.debugFilledBox());
        for (int[] face : FACES) {
            for (int index : face) {
                float[] pos = corners[index];
                fill.addVertex(matrices.last(), pos[0], pos[1], pos[2]).setColor(0x3000FFFF);
            }
        }
        String label = region.sizeX() + " x " + region.sizeY() + " x " + region.sizeZ();
        var font = Minecraft.getInstance().font;
        matrices.pushPose();
        matrices.translate(
                (corners[0][0] + corners[7][0]) / 2,
                corners[7][1] + 0.5,
                (corners[0][2] + corners[7][2]) / 2);
        matrices.mulPose(camera.orientation);
        matrices.scale(0.02f, -0.02f, 0.02f);
        font.drawInBatch(
                label,
                -font.width(label) / 2f,
                0,
                0xFFFFFFFF,
                false,
                matrices.last().pose(),
                context.consumers(),
                Font.DisplayMode.NORMAL,
                0x80000000,
                LightTexture.FULL_BRIGHT);
        matrices.popPose();
    }

    private static void lines(
            VertexConsumer out, PoseStack.Pose pose, float[][] corners, int color, float width) {
        for (int[] edge : EDGES) {
            float[] a = corners[edge[0]], b = corners[edge[1]];
            float dx = b[0] - a[0], dy = b[1] - a[1], dz = b[2] - a[2];
            out.addVertex(pose, a[0], a[1], a[2])
                    .setNormal(pose, dx, dy, dz)
                    .setColor(color)
                    .setLineWidth(width);
            out.addVertex(pose, b[0], b[1], b[2])
                    .setNormal(pose, dx, dy, dz)
                    .setColor(color)
                    .setLineWidth(width);
        }
    }
}
