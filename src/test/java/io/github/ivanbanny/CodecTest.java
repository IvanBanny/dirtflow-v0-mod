package io.github.ivanbanny;

import io.github.ivanbanny.protocol.TensorPayload;
import io.github.ivanbanny.protocol.VoxelTensor;
import io.github.ivanbanny.protocol.VoxelTensorCodec;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

class CodecTest {
    private static final List<String> PALETTE = List.of("minecraft:air", "minecraft:stone");

    @Test
    void handEncodedSparseCarve() {
        var tensor =
                new VoxelTensor.Sparse(
                        new BlockPos(-1, 2, 3),
                        2,
                        1,
                        1,
                        PALETTE,
                        new int[] {0, 1},
                        new int[] {1, 0});
        byte[] expected =
                java.util.HexFormat.of()
                        .parseHex(
                                "4445455001000000ffffffff0000000200000003000200010001000200000010000d6d696e6563726166743a616972000f6d696e6563726166743a73746f6e6500000002000000000001000000010000");
        Assertions.assertArrayEquals(expected, VoxelTensorCodec.encode(tensor));
        var decoded = (VoxelTensor.Sparse) VoxelTensorCodec.decode(expected);
        Assertions.assertArrayEquals(new int[] {1, 0}, decoded.paletteIds());
        Assertions.assertEquals(new BlockPos(0, 2, 3), decoded.worldPos(1));
    }

    @Test
    void rejectsEveryTruncationAndMalformedHeader() {
        byte[] valid =
                VoxelTensorCodec.encode(
                        new VoxelTensor.Sparse(
                                BlockPos.ZERO,
                                2,
                                1,
                                1,
                                PALETTE,
                                new int[] {0, 1},
                                new int[] {1, 0}));
        for (int length = 0; length < valid.length; length++) {
            byte[] truncated = Arrays.copyOf(valid, length);
            Assertions.assertThrows(
                    IllegalArgumentException.class, () -> VoxelTensorCodec.decode(truncated));
        }
        for (int offset : new int[] {0, 4, 5, 6, 26, 28, 34, valid.length - 1}) {
            byte[] bad = valid.clone();
            bad[offset] = (byte) 255;
            Assertions.assertThrows(
                    IllegalArgumentException.class,
                    () -> VoxelTensorCodec.decode(bad),
                    "offset " + offset);
        }
        byte[] duplicate = valid.clone();
        ByteBuffer.wrap(duplicate).putInt(duplicate.length - 6, 0);
        Assertions.assertThrows(
                IllegalArgumentException.class, () -> VoxelTensorCodec.decode(duplicate));
    }

    @Test
    void denseLongRunsAndCompression() {
        int volume = 58 * 58 * 58;
        var tensor =
                new VoxelTensor.Dense(
                        BlockPos.ZERO,
                        58,
                        58,
                        58,
                        PALETTE,
                        new int[] {1, 1, 1},
                        new int[] {65535, 65535, volume - 131070});
        var payload = TensorPayload.of(tensor);
        Assertions.assertEquals(TensorPayload.ZLIB, payload.encoding());
        var decoded = (VoxelTensor.Dense) payload.decode();
        Assertions.assertEquals(volume, decoded.totalVoxels());
        Assertions.assertEquals(new BlockPos(57, 57, 57), decoded.worldPos(volume - 1));
        Assertions.assertArrayEquals(
                VoxelTensorCodec.encode(tensor), VoxelTensorCodec.encode(decoded));
    }

    @Test
    void invalidRunsAndPalette() {
        for (int run : new int[] {0, -1, 65536, 2}) {
            var bad =
                    new VoxelTensor.Dense(
                            BlockPos.ZERO, 1, 1, 1, PALETTE, new int[] {1}, new int[] {run});
            Assertions.assertThrows(
                    IllegalArgumentException.class, () -> VoxelTensorCodec.encode(bad));
        }
        var bad = new VoxelTensor.Sparse(BlockPos.ZERO, 0, 1, 1, PALETTE, new int[0], new int[0]);
        Assertions.assertThrows(IllegalArgumentException.class, () -> VoxelTensorCodec.encode(bad));
        var palette =
                new VoxelTensor.Dense(
                        BlockPos.ZERO,
                        1,
                        1,
                        1,
                        List.of("minecraft:stone"),
                        new int[] {0},
                        new int[] {1});
        Assertions.assertThrows(
                IllegalArgumentException.class, () -> VoxelTensorCodec.encode(palette));
    }

    @Test
    void compressionRejectsTrailingTruncatedAndOversizedData() {
        byte[] raw =
                VoxelTensorCodec.encode(
                        new VoxelTensor.Dense(
                                BlockPos.ZERO,
                                58,
                                58,
                                58,
                                PALETTE,
                                new int[] {1, 1, 1},
                                new int[] {65535, 65535, 64042}));
        byte[] compressed = TensorPayload.pack(raw);
        Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> TensorPayload.unpack(Arrays.copyOf(compressed, compressed.length - 1)));
        Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> TensorPayload.unpack(Arrays.copyOf(compressed, compressed.length + 1)));
        Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> new TensorPayload("unknown", "AAAA").decode());
        Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> new TensorPayload(TensorPayload.RAW, "!!!").decode());
        var deflater = new java.util.zip.Deflater();
        deflater.setInput(new byte[VoxelTensorCodec.MAX_BYTES + 1]);
        deflater.finish();
        byte[] bomb = new byte[32768];
        int size = deflater.deflate(bomb);
        deflater.end();
        Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> TensorPayload.unpack(Arrays.copyOf(bomb, size)));
    }

    @Test
    void denseCoordinatesMatchFlatArray() {
        var random = new Random(4);
        int[] ids = new int[3 * 5 * 7], lengths = new int[ids.length];
        for (int i = 0; i < ids.length; i++) {
            ids[i] = random.nextInt(2);
            lengths[i] = 1;
        }
        var tensor = new VoxelTensor.Dense(new BlockPos(-20, 4, 9), 3, 5, 7, PALETTE, ids, lengths);
        var decoded = (VoxelTensor.Dense) VoxelTensorCodec.decode(VoxelTensorCodec.encode(tensor));
        for (int z = 0; z < 7; z++)
            for (int y = 0; y < 5; y++)
                for (int x = 0; x < 3; x++) {
                    int index = (z * 5 + y) * 3 + x;
                    Assertions.assertEquals(
                            new BlockPos(x - 20, y + 4, z + 9), decoded.worldPos(index));
                    Assertions.assertEquals(ids[index], decoded.runPaletteIds()[index]);
                }
    }
}
