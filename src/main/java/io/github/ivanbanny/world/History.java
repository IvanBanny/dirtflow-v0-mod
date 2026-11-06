package io.github.ivanbanny.world;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.ivanbanny.Dirtflow;
import net.minecraft.SharedConstants;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Per-player journals. The index commits an undo/redo transition in one rename. */
public final class History {
    private static final Gson GSON = new Gson();
    private final Path root;
    private final HolderLookup<Block> blocks;

    private record Stacks(List<String> undo, List<String> redo) {}

    public record Entry(
            UUID player, String name, ResourceKey<Level> dimension, Map<BlockPos, Cell> cells) {}

    public History(Path root, HolderLookup<Block> blocks) {
        this.root = root;
        this.blocks = blocks;
    }

    public void push(UUID player, ResourceKey<Level> dimension, Map<BlockPos, Cell> cells) {
        if (cells.isEmpty()) return;
        Stacks stacks = readStacks(player);
        String name = writeEntry(player, dimension, cells);
        var undo = new ArrayList<>(stacks.undo());
        undo.add(name);
        publish(player, new Stacks(undo, List.of()), name);
        remove(player, stacks.redo());
    }

    public void clearRedo(UUID player) {
        Stacks stacks = readStacks(player);
        if (stacks.redo().isEmpty()) return;
        publish(player, new Stacks(stacks.undo(), List.of()), null);
        remove(player, stacks.redo());
    }

    public Entry peek(UUID player, boolean redo) {
        Stacks stacks = readStacks(player);
        var names = redo ? stacks.redo() : stacks.undo();
        if (names.isEmpty()) return null;
        String name = names.getLast();
        try {
            CompoundTag tag =
                    NbtIo.readCompressed(
                            root.resolve(player.toString()).resolve(name),
                            NbtAccounter.unlimitedHeap());
            if (tag.getIntOr("format", -1) != 1) throw new IOException("Unknown journal format");
            String version = tag.getString("minecraft_version").orElseThrow();
            if (!version.equals(SharedConstants.getCurrentVersion().name()))
                throw new IOException("Journal requires Minecraft " + version);
            var dimension =
                    ResourceKey.create(
                            Registries.DIMENSION,
                            Identifier.parse(tag.getString("dimension").orElseThrow()));
            ListTag cells = tag.getList("cells").orElseThrow();
            if (cells.isEmpty()
                    || cells.size() > Region.MAX_AXIS * Region.MAX_AXIS * Region.MAX_AXIS)
                throw new IOException("Invalid journal cell count");
            var states = new LinkedHashMap<BlockPos, Cell>();
            for (int i = 0; i < cells.size(); i++) {
                CompoundTag cell = cells.getCompound(i).orElseThrow();
                BlockPos pos = BlockPos.of(cell.getLong("pos").orElseThrow());
                var value =
                        new Cell(
                                Edit.parse(blocks, cell.getString("state").orElseThrow()),
                                cell.getCompound("data").orElse(null));
                if (states.put(pos, value) != null) throw new IOException("Duplicate journal cell");
            }
            return new Entry(player, name, dimension, states);
        } catch (IOException error) {
            throw new UncheckedIOException("Cannot read Dirtflow journal " + name, error);
        } catch (RuntimeException error) {
            throw new IllegalStateException("Cannot read Dirtflow journal " + name, error);
        }
    }

    public void transfer(Entry entry, boolean redo, Map<BlockPos, Cell> inverse) {
        Stacks stacks = readStacks(entry.player());
        var undo = new ArrayList<>(stacks.undo());
        var redos = new ArrayList<>(stacks.redo());
        var source = redo ? redos : undo;
        var destination = redo ? undo : redos;
        if (source.isEmpty() || !source.getLast().equals(entry.name()))
            throw new IllegalStateException("History changed before restoration");
        String name = writeEntry(entry.player(), entry.dimension(), inverse);
        source.removeLast();
        destination.add(name);
        publish(entry.player(), new Stacks(undo, redos), name);
        remove(entry.player(), List.of(entry.name()));
    }

    private String writeEntry(
            UUID player, ResourceKey<Level> dimension, Map<BlockPos, Cell> cells) {
        Path directory = root.resolve(player.toString());
        String name = UUID.randomUUID() + ".nbt";
        CompoundTag tag = new CompoundTag();
        tag.putInt("format", 1);
        tag.putString("minecraft_version", SharedConstants.getCurrentVersion().name());
        tag.putString("dimension", dimension.identifier().toString());
        var entries = new ListTag();
        for (var entry : cells.entrySet()) {
            var cell = new CompoundTag();
            cell.putLong("pos", entry.getKey().asLong());
            cell.putString("state", BlockStateParser.serialize(entry.getValue().state()));
            if (entry.getValue().data() != null) cell.put("data", entry.getValue().data());
            entries.add(cell);
        }
        tag.put("cells", entries);
        try {
            Files.createDirectories(directory);
            Path temporary = Files.createTempFile(directory, "journal-", ".tmp");
            try {
                NbtIo.writeCompressed(tag, temporary);
                Files.move(temporary, directory.resolve(name), StandardCopyOption.ATOMIC_MOVE);
            } finally {
                cleanTemporary(temporary);
            }
            return name;
        } catch (IOException error) {
            throw new UncheckedIOException("Cannot save Dirtflow journal", error);
        }
    }

    private Stacks readStacks(UUID player) {
        Path directory = root.resolve(player.toString());
        Path file = directory.resolve("history.json");
        try {
            if (!Files.exists(file)) {
                if (!Files.exists(directory)) return new Stacks(List.of(), List.of());
                // Earlier saves used ordered numeric journals without an index
                try (var entries = Files.list(directory)) {
                    return new Stacks(
                            entries.map(path -> path.getFileName().toString())
                                    .filter(name -> name.matches("[0-9]{20}\\.nbt"))
                                    .sorted()
                                    .toList(),
                            List.of());
                }
            }
            var json = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
            if (json.get("format").getAsInt() != 1)
                throw new IllegalArgumentException("Unknown history format");
            var seen = new HashSet<String>();
            return new Stacks(
                    names(json.getAsJsonArray("undo"), seen),
                    names(json.getAsJsonArray("redo"), seen));
        } catch (IOException error) {
            throw new UncheckedIOException("Cannot read Dirtflow history", error);
        } catch (RuntimeException error) {
            throw new IllegalStateException("Cannot read Dirtflow history index", error);
        }
    }

    private static List<String> names(JsonArray entries, HashSet<String> seen) {
        var names = new ArrayList<String>();
        for (var entry : entries) {
            String name = entry.getAsString();
            if (!name.matches("(?:[0-9]{20}|[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12})\\.nbt")
                    || !seen.add(name))
                throw new IllegalArgumentException("Invalid or duplicate journal name");
            names.add(name);
        }
        return names;
    }

    private void publish(UUID player, Stacks stacks, String created) {
        Path directory = root.resolve(player.toString());
        var json = new JsonObject();
        json.addProperty("format", 1);
        json.add("undo", GSON.toJsonTree(stacks.undo()));
        json.add("redo", GSON.toJsonTree(stacks.redo()));
        try {
            Files.createDirectories(directory);
            Path temporary = Files.createTempFile(directory, "index-", ".tmp");
            try {
                Files.writeString(temporary, json.toString());
                Files.move(
                        temporary,
                        directory.resolve("history.json"),
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } finally {
                cleanTemporary(temporary);
            }
        } catch (IOException error) {
            if (created != null) remove(player, List.of(created));
            throw new UncheckedIOException("Cannot advance Dirtflow history", error);
        }
    }

    private static void cleanTemporary(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException error) {
            Dirtflow.LOGGER.warn("Cannot remove temporary history file {}", file, error);
        }
    }

    private void remove(UUID player, List<String> names) {
        for (String name : names) {
            // The index has committed; cleanup cannot roll back the world transaction
            try {
                Files.deleteIfExists(root.resolve(player.toString()).resolve(name));
            } catch (IOException error) {
                Dirtflow.LOGGER.warn("Cannot remove retired journal {}", name, error);
            }
        }
    }
}
