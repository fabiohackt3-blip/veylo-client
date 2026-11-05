package de.veylo.client;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
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

    static final int DEFAULT_OPACITY = 65;
    static final int DEFAULT_ACCENT = 0x66d9ff;
    static final int MAX_LAYOUTS = 12;

    static final class Widget {
        boolean enabled = true;
        int x = 8;
        int y;
        int percent = 100;
        int opacity = DEFAULT_OPACITY;
        int accent = DEFAULT_ACCENT;
        boolean right;
        boolean bottom;

        Widget(Module module) {
            enabled = module.ordinal() <= Module.ARMOR.ordinal();
            x = module.ordinal() <= Module.ARMOR.ordinal() ? 8 : module.ordinal() <= Module.EFFECTS.ordinal() ? 180 : 320;
            right = module == Module.COORDINATES;
            y = switch (module) {
                case COORDINATES -> 8;
                case PING -> 28;
                case KEYS -> 48;
                case ARMOR -> 94;
                default -> module.startY;
            };
        }

        Widget copy(Module module) {
            var result = new Widget(module);
            result.enabled = enabled;
            result.x = x;
            result.y = y;
            result.percent = percent;
            result.opacity = opacity;
            result.accent = accent;
            result.right = right;
            result.bottom = bottom;
            return result;
        }
    }

    final EnumMap<Module, Widget> widgets = new EnumMap<>(Module.class);
    boolean visible = true;
    Cosmetic cosmetic = Cosmetic.OFF;
    boolean snap = true;
    private final LinkedHashMap<String, EnumMap<Module, Widget>> layouts = new LinkedHashMap<>();

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
        settings.snap = Boolean.parseBoolean(data.getProperty("snap", "true"));
        try {
            settings.cosmetic = Cosmetic.valueOf(data.getProperty("cosmetic", "OFF"));
        } catch (IllegalArgumentException ignored) {
            settings.cosmetic = Cosmetic.OFF;
        }
        int version = integer(data, "layoutVersion", 1, 1, 3);
        int legacyOpacity = integer(data, "opacity", DEFAULT_OPACITY, 0, 100);
        readWidgets(data, "", settings.widgets, version, legacyOpacity);
        int count = integer(data, "layout.count", 0, 0, MAX_LAYOUTS);
        for (int i = 0; i < count; i++) {
            String prefix = "layout." + i + ".";
            String name = data.getProperty(prefix + "name", "").strip();
            if (name.isEmpty() || name.length() > 24 || settings.layouts.containsKey(name)) continue;
            var layout = new EnumMap<Module, Widget>(Module.class);
            readWidgets(data, prefix, layout, 3, DEFAULT_OPACITY);
            settings.layouts.put(name, layout);
        }
        return settings;
    }

    void save(Path path) throws IOException {
        Files.createDirectories(path.getParent());
        Properties data = new Properties();
        data.setProperty("layoutVersion", "3");
        data.setProperty("visible", Boolean.toString(visible));
        data.setProperty("snap", Boolean.toString(snap));
        data.setProperty("cosmetic", cosmetic.name());
        writeWidgets(data, "", widgets);
        data.setProperty("layout.count", Integer.toString(layouts.size()));
        int index = 0;
        for (var entry : layouts.entrySet()) {
            String prefix = "layout." + index++ + ".";
            data.setProperty(prefix + "name", entry.getKey());
            writeWidgets(data, prefix, entry.getValue());
        }
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

    List<String> layoutNames() {
        return List.copyOf(layouts.keySet());
    }

    boolean saveLayout(String name) {
        name = name.strip();
        if (name.isEmpty() || name.length() > 24 || (!layouts.containsKey(name) && layouts.size() >= MAX_LAYOUTS))
            return false;
        layouts.put(name, copyWidgets(widgets));
        return true;
    }

    boolean loadLayout(String name) {
        var layout = layouts.get(name);
        if (layout == null) return false;
        widgets.putAll(copyWidgets(layout));
        return true;
    }

    private static EnumMap<Module, Widget> copyWidgets(EnumMap<Module, Widget> widgets) {
        var result = new EnumMap<Module, Widget>(Module.class);
        widgets.forEach((module, widget) -> result.put(module, widget.copy(module)));
        return result;
    }

    private static void readWidgets(Properties data, String prefix, EnumMap<Module, Widget> widgets,
                                    int version, int fallbackOpacity) {
        for (var module : Module.values()) {
            var widget = new Widget(module);
            String key = prefix + module.name().toLowerCase(Locale.ROOT) + ".";
            widget.enabled = Boolean.parseBoolean(data.getProperty(key + "enabled", Boolean.toString(widget.enabled)));
            widget.x = integer(data, key + "x", widget.x, 0, 16384);
            widget.y = integer(data, key + "y", widget.y, 0, 16384);
            widget.percent = integer(data, key + "scale", 100, 40, 240);
            widget.opacity = integer(data, key + "opacity", fallbackOpacity, 0, 100);
            widget.accent = integer(data, key + "accent", DEFAULT_ACCENT, 0, 0xffffff);
            widget.right = Boolean.parseBoolean(data.getProperty(key + "right", version >= 3 && widget.right ? "true" : "false"));
            widget.bottom = Boolean.parseBoolean(data.getProperty(key + "bottom", "false"));
            if (version < 3 && widget.x == 8 && widget.percent == 100 && data.containsKey(key + "y")) {
                int oldY = version == 2 ? module.startY : switch (module) {
                    case COORDINATES -> 30;
                    case PING -> 52;
                    case KEYS -> 74;
                    case ARMOR -> 119;
                    default -> -1;
                };
                if (widget.y == oldY) {
                    var defaults = new Widget(module);
                    widget.y = defaults.y;
                    widget.right = defaults.right;
                }
            }
            if (!data.containsKey(key + "x") && !data.containsKey(key + "y")) {
                widget.right = new Widget(module).right;
            }
            widgets.put(module, widget);
        }
    }

    private static void writeWidgets(Properties data, String prefix, EnumMap<Module, Widget> widgets) {
        widgets.forEach((module, widget) -> {
            String key = prefix + module.name().toLowerCase(Locale.ROOT) + ".";
            data.setProperty(key + "enabled", Boolean.toString(widget.enabled));
            data.setProperty(key + "x", Integer.toString(widget.x));
            data.setProperty(key + "y", Integer.toString(widget.y));
            data.setProperty(key + "scale", Integer.toString(widget.percent));
            data.setProperty(key + "opacity", Integer.toString(widget.opacity));
            data.setProperty(key + "accent", Integer.toString(widget.accent));
            data.setProperty(key + "right", Boolean.toString(widget.right));
            data.setProperty(key + "bottom", Boolean.toString(widget.bottom));
        });
    }

    private static int integer(Properties properties, String key, int fallback, int min, int max) {
        try {
            return Math.max(min, Math.min(max, Integer.parseInt(properties.getProperty(key))));
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }
}
