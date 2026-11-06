package io.github.ivanbanny;

import io.github.ivanbanny.engine.Settings;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

class PreferencesTest {
    @TempDir Path directory;

    @Test
    void settingsAndEndpointSurviveReopening() {
        Path file = directory.resolve("preferences.json");
        var preferences = new Preferences(file);
        UUID player = UUID.randomUUID();
        Assertions.assertEquals(Settings.DEFAULT, preferences.settings(player));
        preferences.settings(player, new Settings(7, 12, 13, Settings.MAX_SEED, 0));
        preferences.setEngine("localhost:9000/prefix/");
        var reopened = new Preferences(file);
        Assertions.assertEquals(
                new Settings(7, 12, 13, Settings.MAX_SEED, 0), reopened.settings(player));
        Assertions.assertEquals("http://localhost:9000/prefix", reopened.engine("default"));
        reopened.setEngine(null);
        Assertions.assertEquals("default", new Preferences(file).engine("default"));
    }

    @Test
    void failedSaveLeavesPreviousSettings() throws Exception {
        Path blocked = directory.resolve("file");
        Files.writeString(blocked, "occupied");
        var preferences = new Preferences(blocked.resolve("preferences.json"));
        UUID player = UUID.randomUUID();
        Assertions.assertThrows(
                java.io.UncheckedIOException.class,
                () -> preferences.settings(player, new Settings(1, 1, 1, null, 1)));
        Assertions.assertEquals(Settings.DEFAULT, preferences.settings(player));
    }

    @Test
    void invalidAddressesAndSettings() {
        for (String address :
                List.of(
                        "ftp://host",
                        "http://user:secret@host",
                        "http://host?token=x",
                        "http://host#fragment",
                        "http://host:0",
                        "http://host:70000",
                        "http://host:")) {
            Assertions.assertThrows(
                    IllegalArgumentException.class, () -> Preferences.address(address));
        }
        Assertions.assertThrows(
                IllegalArgumentException.class, () -> new Settings(Double.NaN, 30, 30, null, 3));
        Assertions.assertThrows(
                IllegalArgumentException.class, () -> new Settings(5, 0, 30, null, 3));
        Assertions.assertThrows(
                IllegalArgumentException.class, () -> new Settings(5, 30, 201, null, 3));
        Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> new Settings(5, 30, 30, Settings.MAX_SEED + 1, 3));
        Assertions.assertThrows(
                IllegalArgumentException.class, () -> new Settings(5, 30, 30, null, -1));
    }
}
