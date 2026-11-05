package de.veylo.client;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Properties;

final class HudSettings {
    enum Module {
        FPS("FPS", 8), COORDINATES("Koordinaten", 30), PING("Ping", 52),
        KEYS("Tasteneingaben", 74), ARMOR("Rüstung", 119);

        final String label;
        final int startY;

        Module(String label, int startY) {
            this.label = label;
            this.startY = startY;
        }
    }

    static final class Widget {
        boolean enabled = true;
        int x = 8;
        int y;
        int percent = 100;

        Widget(Module module) {
            y = module.startY;
        }
    }

    final EnumMap<Module, Widget> widgets = new EnumMap<>(Module.class);
    boolean visible = true;

    HudSettings() {
        reset();
    }

    void reset() {
        visible = true;
        for (Module module : Module.values()) widgets.put(module, new Widget(module));
    }

    static HudSettings load(Path path) throws IOException {
        HudSettings settings = new HudSettings();
        if (!Files.exists(path)) return settings;
        Properties data = new Properties();
        try (var reader = Files.newBufferedReader(path)) {
            data.load(reader);
        }
        settings.visible = Boolean.parseBoolean(data.getProperty("visible", "true"));
        settings.widgets.forEach((module, widget) -> {
            String key = module.name().toLowerCase(Locale.ROOT) + ".";
            widget.enabled = Boolean.parseBoolean(data.getProperty(key + "enabled", "true"));
            widget.x = integer(data, key + "x", widget.x, 0, 16384);
            widget.y = integer(data, key + "y", widget.y, 0, 16384);
            widget.percent = integer(data, key + "scale", 100, 60, 180);
        });
        return settings;
    }

    void save(Path path) throws IOException {
        Files.createDirectories(path.getParent());
        Properties data = new Properties();
        data.setProperty("visible", Boolean.toString(visible));
        widgets.forEach((module, widget) -> {
            String key = module.name().toLowerCase(Locale.ROOT) + ".";
            data.setProperty(key + "enabled", Boolean.toString(widget.enabled));
            data.setProperty(key + "x", Integer.toString(widget.x));
            data.setProperty(key + "y", Integer.toString(widget.y));
            data.setProperty(key + "scale", Integer.toString(widget.percent));
        });
        Path temporary = Files.createTempFile(path.getParent(), "veylo-", ".tmp");
        try {
            try (var writer = Files.newBufferedWriter(temporary)) {
                data.store(writer, "Veylo Client HUD");
            }
            Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static int integer(Properties properties, String key, int fallback, int min, int max) {
        try {
            return Math.max(min, Math.min(max, Integer.parseInt(properties.getProperty(key))));
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }
}
