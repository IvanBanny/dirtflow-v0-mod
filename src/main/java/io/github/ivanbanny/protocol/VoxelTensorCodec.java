package io.github.ivanbanny.protocol;

import net.minecraft.core.BlockPos;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;

/**
 * Binary v1, big-endian. Header: DEEP, u8 version/layout, u16 reserved, i32 origin XYZ, u16 size
 * XYZ/palette count, u32 body bytes (32 bytes total). Palette strings have u16 UTF-8 lengths.
 * Sparse: u32 count, (u32 index, u16 ID). Dense: (u16 ID, u16 run). Explicit sparse air carves a
 * cell.
 */
public final class VoxelTensorCodec {
    public static final int MAX_BYTES = 8 * 1024 * 1024;
    private static final int MAGIC = 0x44454550;

    private VoxelTensorCodec() {}

    public static byte[] encode(VoxelTensor tensor) {
        validate(tensor);
        var palette = new ArrayList<byte[]>();
        long length =
                32L
                        + switch (tensor) {
                            case VoxelTensor.Sparse s -> 4L + 6L * s.entryCount();
                            case VoxelTensor.Dense d -> 4L * d.runCount();
                        };
        for (String state : tensor.palette()) {
            byte[] bytes;
            try {
                var encoded =
                        StandardCharsets.UTF_8
                                .newEncoder()
                                .onMalformedInput(CodingErrorAction.REPORT)
                                .encode(java.nio.CharBuffer.wrap(state));
                bytes = new byte[encoded.remaining()];
                encoded.get(bytes);
            } catch (CharacterCodingException error) {
                throw new IllegalArgumentException("Invalid palette text", error);
            }
            if (bytes.length > 65535)
                throw new IllegalArgumentException("Palette string exceeds u16");
            palette.add(bytes);
            length += 2 + bytes.length;
        }
        if (length > MAX_BYTES) throw new IllegalArgumentException("Tensor exceeds 8 MiB");
        int bodyLength =
                switch (tensor) {
                    case VoxelTensor.Sparse s -> 4 + 6 * s.entryCount();
                    case VoxelTensor.Dense d -> 4 * d.runCount();
                };
        var out = ByteBuffer.allocate((int) length);
        out.putInt(MAGIC).put((byte) 1).put((byte) (tensor instanceof VoxelTensor.Dense ? 1 : 0));
        out.putShort((short) 0);
        out.putInt(tensor.origin().getX())
                .putInt(tensor.origin().getY())
                .putInt(tensor.origin().getZ());
        out.putShort((short) tensor.sizeX())
                .putShort((short) tensor.sizeY())
                .putShort((short) tensor.sizeZ());
        out.putShort((short) palette.size()).putInt(bodyLength);
        for (byte[] state : palette) out.putShort((short) state.length).put(state);
        switch (tensor) {
            case VoxelTensor.Sparse s -> {
                out.putInt(s.entryCount());
                for (int i = 0; i < s.entryCount(); i++) {
                    out.putInt(s.indices()[i]).putShort((short) s.paletteIds()[i]);
                }
            }
            case VoxelTensor.Dense d -> {
                for (int i = 0; i < d.runCount(); i++) {
                    out.putShort((short) d.runPaletteIds()[i]).putShort((short) d.runLengths()[i]);
                }
            }
        }
        return out.array();
    }

    public static VoxelTensor decode(byte[] bytes) {
        if (bytes.length < 32 || bytes.length > MAX_BYTES) {
            throw new IllegalArgumentException("Tensor must contain 32 bytes to 8 MiB");
        }
        var in = ByteBuffer.wrap(bytes);
        int magic = in.getInt(), version = in.get(), layout = in.get();
        if (magic != MAGIC || version != 1 || in.getShort() != 0 || layout < 0 || layout > 1) {
            throw new IllegalArgumentException("Unsupported voxel header");
        }
        var origin = new BlockPos(in.getInt(), in.getInt(), in.getInt());
        int sx = u16(in), sy = u16(in), sz = u16(in), count = u16(in);
        int bodyLength = in.getInt();
        if (bodyLength < 0 || bodyLength > in.remaining()) {
            throw new IllegalArgumentException("Invalid body length");
        }
        int bodyStart = bytes.length - bodyLength;
        var palette = new ArrayList<String>();
        for (int i = 0; i < count; i++) {
            if (in.position() + 2 > bodyStart)
                throw new IllegalArgumentException("Truncated palette");
            int size = u16(in);
            if (in.position() + size > bodyStart)
                throw new IllegalArgumentException("Truncated state");
            try {
                palette.add(
                        StandardCharsets.UTF_8
                                .newDecoder()
                                .onMalformedInput(CodingErrorAction.REPORT)
                                .decode(in.slice(in.position(), size))
                                .toString());
            } catch (CharacterCodingException error) {
                throw new IllegalArgumentException("Invalid UTF-8 state", error);
            }
            in.position(in.position() + size);
        }
        if (in.position() != bodyStart) throw new IllegalArgumentException("Incorrect body length");
        VoxelTensor tensor;
        if (layout == 0) {
            if (bodyLength < 4 || (bodyLength - 4) % 6 != 0) {
                throw new IllegalArgumentException("Truncated sparse body");
            }
            int entries = in.getInt();
            if (entries != in.remaining() / 6)
                throw new IllegalArgumentException("Incorrect sparse count");
            int[] positions = new int[entries], ids = new int[entries];
            for (int i = 0; i < entries; i++) {
                positions[i] = in.getInt();
                ids[i] = u16(in);
            }
            tensor = new VoxelTensor.Sparse(origin, sx, sy, sz, palette, positions, ids);
        } else {
            if (bodyLength % 4 != 0) throw new IllegalArgumentException("Truncated dense run");
            int[] ids = new int[bodyLength / 4], runs = new int[bodyLength / 4];
            for (int i = 0; i < ids.length; i++) {
                ids[i] = u16(in);
                runs[i] = u16(in);
            }
            tensor = new VoxelTensor.Dense(origin, sx, sy, sz, palette, ids, runs);
        }
        validate(tensor);
        return tensor;
    }

    public static void validate(VoxelTensor tensor) {
        if (tensor.sizeX() < 1
                || tensor.sizeY() < 1
                || tensor.sizeZ() < 1
                || tensor.sizeX() > 65535
                || tensor.sizeY() > 65535
                || tensor.sizeZ() > 65535) {
            throw new IllegalArgumentException("Tensor dimensions must be positive u16 values");
        }
        if (tensor.palette().isEmpty()
                || tensor.palette().size() > 65535
                || !tensor.palette().getFirst().equals(VoxelTensor.AIR)) {
            throw new IllegalArgumentException(
                    "Palette must start with minecraft:air and fit in u16");
        }
        int[] ids;
        switch (tensor) {
            case VoxelTensor.Sparse s -> {
                var seen = new HashSet<Integer>();
                for (int index : s.indices()) {
                    if (Integer.toUnsignedLong(index) >= s.totalVoxels() || !seen.add(index)) {
                        throw new IllegalArgumentException(
                                "Sparse index out of range or duplicated");
                    }
                }
                ids = s.paletteIds();
            }
            case VoxelTensor.Dense d -> {
                long sum = 0;
                for (int run : d.runLengths()) {
                    if (run < 1 || run > 65535)
                        throw new IllegalArgumentException("Dense run must fit in u16");
                    sum += run;
                }
                if (sum != d.totalVoxels())
                    throw new IllegalArgumentException("Runs must cover the volume exactly");
                ids = d.runPaletteIds();
            }
        }
        for (int id : ids) {
            if (id < 0 || id >= tensor.palette().size())
                throw new IllegalArgumentException("Palette ID out of range");
        }
    }

    private static int u16(ByteBuffer in) {
        return Short.toUnsignedInt(in.getShort());
    }
}
