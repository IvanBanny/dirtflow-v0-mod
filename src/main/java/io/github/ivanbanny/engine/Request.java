package io.github.ivanbanny.engine;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.github.ivanbanny.protocol.TensorPayload;
import io.github.ivanbanny.protocol.VoxelTensor;
import io.github.ivanbanny.world.Region;
import net.minecraft.core.BlockPos;

import java.util.Set;
import java.util.UUID;

public record Request(
        String prompt,
        String dimension,
        Region region,
        String task,
        Settings settings,
        VoxelTensor snapshot) {
    public Request {
        if (prompt.isBlank() || prompt.length() > 4096)
            throw new IllegalArgumentException("Prompt must contain 1..4096 characters");
        if (dimension.isBlank() || dimension.length() > 128)
            throw new IllegalArgumentException("Invalid dimension");
        if (!Set.of("replacement", "completion", "repair", "material_only").contains(task))
            throw new IllegalArgumentException("Unknown generation task");
        if (!region.expand(region.padding()).matches(snapshot))
            throw new IllegalArgumentException("Snapshot must cover the padded selection");
    }

    public String json() {
        var json = new JsonObject();
        json.addProperty("protocol_version", 2);
        json.addProperty("client_version", "dirtflow-mod-0.1.0");
        json.addProperty("correlation_id", UUID.randomUUID().toString());
        json.addProperty("game_version", "1.21.11");
        json.addProperty("dimension", dimension);
        json.addProperty("prompt", prompt);
        var bounds = new JsonObject();
        bounds.add("min", xyz(region.min()));
        bounds.add("max", xyz(region.max()));
        json.add("region", bounds);
        json.addProperty("context_padding", region.padding());
        json.add("snapshot", new Gson().toJsonTree(TensorPayload.of(snapshot)));
        var options = new JsonObject();
        options.addProperty("task_type", task);
        options.addProperty("stage1_steps", settings.shapeSteps());
        options.addProperty("stage2_steps", settings.materialSteps());
        options.addProperty("cfg_scale", settings.cfgScale());
        options.addProperty("batch_size", 1);
        options.addProperty("candidates", 1);
        options.addProperty("preview_every_steps", settings.previewEverySteps());
        options.addProperty("tensor_compression", "zlib");
        if (settings.seed() != null) options.addProperty("seed", settings.seed());
        json.add("options", options);
        return json.toString();
    }

    private static JsonArray xyz(BlockPos pos) {
        var array = new JsonArray();
        array.add(pos.getX());
        array.add(pos.getY());
        array.add(pos.getZ());
        return array;
    }
}
