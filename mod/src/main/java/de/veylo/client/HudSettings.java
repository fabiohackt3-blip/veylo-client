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
        FPS("FPS", 8), COORDINATES("Koordinaten", 40), PING("Ping", 72),
        KEYS("Tasteneingaben", 104), ARMOR("Rüstung", 162),
        DIRECTION("Himmelsrichtung", 8), SPEED("Tempo", 40), CLOCK("Uhrzeit", 72),
        MEMORY("Speicher", 104), CPS("Klicks pro Sekunde", 136), EFFECTS("Trankeffekte", 168),
        SPRINT("Sprintstatus", 8), SESSION("Spielzeit", 40), ATTACK("Angriffsabklingzeit", 72),
        BIOME("Biom", 108);

        final String label;
        final int startY;

        Module(String label, int startY) {
            this.label = label;
            this.startY = startY;
        }
    }

    enum Cosmetic {
        OFF("Aus"), FROST("Frostfunken"), SOUL("Seelenfeuer"), LEAF("Waldglanz");

        final String label;
        Cosmetic(String label) { this.label = label; }
    }

    static final class Widget {
        boolean enabled = true;
        int x = 8;
        int y;
        int percent = 100;

        Widget(Module module) {
            enabled = module.ordinal() <= Module.ARMOR.ordinal();
            x = module.ordinal() <= Module.ARMOR.ordinal() ? 8 : module.ordinal() <= Module.EFFECTS.ordinal() ? 180 : 320;
            y = module.startY;
        }
    }

    final EnumMap<Module, Widget> widgets = new EnumMap<>(Module.class);
    boolean visible = true;
    Cosmetic cosmetic = Cosmetic.OFF;
    int opacity = 85;

    HudSettings() {
        reset();
    }

    void reset() {
        visible = true;
        opacity = 85;
        cosmetic = Cosmetic.OFF;
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
        settings.opacity = integer(data, "opacity", 85, 35, 100);
        try {
            settings.cosmetic = Cosmetic.valueOf(data.getProperty("cosmetic", "OFF"));
        } catch (IllegalArgumentException ignored) {
            settings.cosmetic = Cosmetic.OFF;
        }
        boolean migrate = !data.containsKey("layoutVersion");
        settings.widgets.forEach((module, widget) -> {
            String key = module.name().toLowerCase(Locale.ROOT) + ".";
            widget.enabled = Boolean.parseBoolean(data.getProperty(key + "enabled", Boolean.toString(widget.enabled)));
            widget.x = integer(data, key + "x", widget.x, 0, 16384);
            widget.y = integer(data, key + "y", widget.y, 0, 16384);
            widget.percent = integer(data, key + "scale", 100, 60, 180);
            if (migrate && widget.x == 8 && widget.percent == 100) {
                int oldY = switch (module) {
                    case COORDINATES -> 30;
                    case PING -> 52;
                    case KEYS -> 74;
                    case ARMOR -> 119;
                    default -> -1;
                };
                if (widget.y == oldY) widget.y = module.startY;
            }
        });
        return settings;
    }

    void save(Path path) throws IOException {
        Files.createDirectories(path.getParent());
        Properties data = new Properties();
        data.setProperty("layoutVersion", "2");
        data.setProperty("visible", Boolean.toString(visible));
        data.setProperty("opacity", Integer.toString(opacity));
        data.setProperty("cosmetic", cosmetic.name());
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
