package io.github.ivanbanny.protocol;

import java.io.ByteArrayOutputStream;
import java.util.Base64;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/** Bounded raw/zlib envelopes for HTTP and preview packets. */
public record TensorPayload(String encoding, String data) {
    public static final String RAW = "voxel-tensor-v1";
    public static final String ZLIB = RAW + "+zlib";

    public static TensorPayload of(VoxelTensor tensor) {
        byte[] bytes = pack(VoxelTensorCodec.encode(tensor));
        return new TensorPayload(
                isRaw(bytes) ? RAW : ZLIB, Base64.getEncoder().encodeToString(bytes));
    }

    public byte[] bytes() {
        if (data.length() > 4 * ((VoxelTensorCodec.MAX_BYTES + 2) / 3)) {
            throw new IllegalArgumentException("Encoded tensor exceeds 8 MiB");
        }
        byte[] bytes = Base64.getDecoder().decode(data);
        if (!(RAW.equals(encoding) && isRaw(bytes)) && !(ZLIB.equals(encoding) && !isRaw(bytes))) {
            throw new IllegalArgumentException("Unknown or mismatched tensor encoding");
        }
        return bytes;
    }

    public VoxelTensor decode() {
        return VoxelTensorCodec.decode(unpack(bytes()));
    }

    public static byte[] pack(byte[] raw) {
        if (raw.length > VoxelTensorCodec.MAX_BYTES)
            throw new IllegalArgumentException("Tensor exceeds 8 MiB");
        var deflater = new Deflater(Deflater.BEST_SPEED);
        try {
            deflater.setInput(raw);
            deflater.finish();
            var out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            while (!deflater.finished()) out.write(buffer, 0, deflater.deflate(buffer));
            return out.size() < raw.length ? out.toByteArray() : raw;
        } finally {
            deflater.end();
        }
    }

    public static byte[] unpack(byte[] bytes) {
        if (bytes.length > VoxelTensorCodec.MAX_BYTES)
            throw new IllegalArgumentException("Tensor exceeds 8 MiB");
        if (isRaw(bytes)) return bytes;
        var inflater = new Inflater();
        try {
            inflater.setInput(bytes);
            var out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            while (!inflater.finished()) {
                int count = inflater.inflate(buffer);
                if (out.size() + count > VoxelTensorCodec.MAX_BYTES)
                    throw new IllegalArgumentException("Inflated tensor exceeds 8 MiB");
                if (count == 0 && !inflater.finished())
                    throw new IllegalArgumentException("Incomplete zlib tensor");
                out.write(buffer, 0, count);
            }
            if (inflater.getRemaining() != 0)
                throw new IllegalArgumentException("Trailing zlib data");
            return out.toByteArray();
        } catch (DataFormatException error) {
            throw new IllegalArgumentException("Invalid zlib tensor", error);
        } finally {
            inflater.end();
        }
    }

    private static boolean isRaw(byte[] bytes) {
        return bytes.length >= 4
                && bytes[0] == 'D'
                && bytes[1] == 'E'
                && bytes[2] == 'E'
                && bytes[3] == 'P';
    }
}
