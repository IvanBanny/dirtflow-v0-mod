package io.github.ivanbanny.engine;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.ivanbanny.protocol.TensorPayload;
import io.github.ivanbanny.protocol.VoxelTensor;

/** Events are decoded on the HTTP worker; world access belongs to the caller. */
public sealed interface Event {
    record Status(String phase, int queue) implements Event {}

    record Progress(double fraction, String message) implements Event {}

    record Preview(
            long sequence, String dimension, String stage, VoxelTensor.Dense tensor, byte[] wire)
            implements Event {}

    record Result(VoxelTensor tensor) implements Event {}

    record Failure(String code, String message) implements Event {}

    static Event parse(String name, String data, String job) {
        if (!java.util.Set.of("status", "progress", "preview", "result", "error").contains(name))
            return null;
        JsonObject json = JsonParser.parseString(data).getAsJsonObject();
        if (!job.equals(json.get("job_id").getAsString()))
            throw new IllegalArgumentException("SSE job identity mismatch");
        return switch (name) {
            case "status" ->
                    new Status(
                            text(json, "phase"),
                            json.has("queue_position") && !json.get("queue_position").isJsonNull()
                                    ? json.get("queue_position").getAsInt()
                                    : -1);
            case "progress" -> {
                double fraction = json.get("fraction").getAsDouble();
                if (!Double.isFinite(fraction) || fraction < 0 || fraction > 1)
                    throw new IllegalArgumentException("Invalid progress fraction");
                yield new Progress(fraction, text(json, "message"));
            }
            case "error" -> new Failure(text(json, "code"), text(json, "message"));
            case "result" -> new Result(payload(json.getAsJsonObject("diff")).decode());
            case "preview" -> {
                if (!json.get("provisional").getAsBoolean()
                        || !json.get("full_replacement").getAsBoolean())
                    throw new IllegalArgumentException(
                            "Preview must be a provisional full replacement");
                TensorPayload payload = payload(json.getAsJsonObject("snapshot"));
                VoxelTensor tensor = payload.decode();
                if (!(tensor instanceof VoxelTensor.Dense dense)
                        || tensor.sizeX() > 58
                        || tensor.sizeY() > 58
                        || tensor.sizeZ() > 58)
                    throw new IllegalArgumentException("Preview must be a bounded dense tensor");
                var origin = json.getAsJsonArray("origin");
                var size = json.getAsJsonArray("size");
                if (origin.size() != 3
                        || size.size() != 3
                        || origin.get(0).getAsInt() != tensor.origin().getX()
                        || origin.get(1).getAsInt() != tensor.origin().getY()
                        || origin.get(2).getAsInt() != tensor.origin().getZ()
                        || size.get(0).getAsInt() != tensor.sizeX()
                        || size.get(1).getAsInt() != tensor.sizeY()
                        || size.get(2).getAsInt() != tensor.sizeZ())
                    throw new IllegalArgumentException("Preview region mismatch");
                long sequence = json.get("frame_sequence").getAsLong();
                int candidate = json.get("candidate_index").getAsInt(),
                        count = json.get("candidate_count").getAsInt();
                int step = json.get("completed_step").getAsInt(),
                        total = json.get("total_steps").getAsInt();
                String stage = text(json, "stage"), dimension = text(json, "dimension");
                if (sequence < 1
                        || candidate < 0
                        || candidate >= count
                        || count > 16
                        || step < 1
                        || step > total
                        || total > 200
                        || (json.get("stage_end").getAsBoolean() && step != total)
                        || !(stage.equals("structure") || stage.equals("blockstates"))
                        || dimension.isBlank()
                        || dimension.length() > 128)
                    throw new IllegalArgumentException("Invalid preview envelope");
                yield new Preview(sequence, dimension, stage, dense, payload.bytes());
            }
            default -> throw new IllegalArgumentException("Unknown event");
        };
    }

    private static TensorPayload payload(JsonObject json) {
        return new TensorPayload(
                json.get("encoding").getAsString(), json.get("data").getAsString());
    }

    private static String text(JsonObject json, String key) {
        return json.has(key) && !json.get(key).isJsonNull() ? json.get(key).getAsString() : "";
    }
}
