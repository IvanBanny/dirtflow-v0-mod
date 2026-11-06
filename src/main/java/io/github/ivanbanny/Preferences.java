package io.github.ivanbanny;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.ivanbanny.engine.Settings;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** World-local engine address and per-player inference settings. */
public final class Preferences {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private final Path file;
    private final Map<UUID, Settings> players = new HashMap<>();
    private String engine;

    public Preferences(Path file) {
        this.file = file;
        if (!Files.exists(file)) return;
        try {
            var json = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
            if (json.get("version").getAsInt() != 1)
                throw new IllegalArgumentException("Unknown preferences version");
            if (json.has("engine")) engine = address(json.get("engine").getAsString());
            for (var entry : json.getAsJsonObject("players").entrySet()) {
                players.put(
                        UUID.fromString(entry.getKey()),
                        Objects.requireNonNull(GSON.fromJson(entry.getValue(), Settings.class)));
            }
        } catch (IOException error) {
            throw new UncheckedIOException("Cannot read Dirtflow preferences", error);
        } catch (RuntimeException error) {
            throw new IllegalStateException("Cannot read Dirtflow preferences: " + file, error);
        }
    }

    public Settings settings(UUID player) {
        return players.getOrDefault(player, Settings.DEFAULT);
    }

    public String engine(String fallback) {
        return engine == null ? fallback : engine;
    }

    public void settings(UUID player, Settings settings) {
        var updated = new HashMap<>(players);
        updated.put(player, settings);
        save(engine, updated);
        players.put(player, settings);
    }

    public void setEngine(String value) {
        String updated = value == null ? null : address(value);
        save(updated, players);
        engine = updated;
    }

    public static String address(String text) {
        URI uri = URI.create(text.contains("://") ? text : "http://" + text).normalize();
        if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                || uri.getHost() == null
                || uri.getPort() < -1
                || uri.getPort() == 0
                || uri.getPort() > 65535
                || uri.getRawAuthority().endsWith(":")
                || uri.getUserInfo() != null
                || uri.getQuery() != null
                || uri.getFragment() != null) {
            throw new IllegalArgumentException(
                    "Use an HTTP/HTTPS engine address without credentials, query or fragment");
        }
        String result = uri.toASCIIString();
        while (result.endsWith("/")) result = result.substring(0, result.length() - 1);
        return result;
    }

    private void save(String address, Map<UUID, Settings> updated) {
        var json = new JsonObject();
        json.addProperty("version", 1);
        if (address != null) json.addProperty("engine", address);
        json.add("players", GSON.toJsonTree(updated));
        try {
            Files.createDirectories(file.getParent());
            Path temporary = Files.createTempFile(file.getParent(), "preferences-", ".tmp");
            try {
                Files.writeString(temporary, GSON.toJson(json) + "\n");
                Files.move(
                        temporary,
                        file,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } finally {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException error) {
                    Dirtflow.LOGGER.warn(
                            "Cannot remove temporary preferences {}", temporary, error);
                }
            }
        } catch (IOException error) {
            throw new UncheckedIOException("Cannot save Dirtflow preferences", error);
        }
    }
}
