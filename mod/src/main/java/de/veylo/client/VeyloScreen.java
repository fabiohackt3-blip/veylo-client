package de.veylo.client;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.option.KeybindsScreen;
import net.minecraft.client.gui.widget.TextFieldWidget;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;

final class VeyloScreen extends Screen {
    private enum Tab { MODULES, HUD, COSMETICS }
    private enum Category { ALLE, HUD, SPIELER }

    private record Controls(VeyloButton toggle, VeyloButton decrease, VeyloButton increase) {
        void show(boolean visible) {
            toggle.visible = decrease.visible = increase.visible = visible;
        }
    }

    private final HudSettings settings;
    private final Runnable save;
    private final EnumMap<HudSettings.Module, Controls> rows = new EnumMap<>(HudSettings.Module.class);
    private final List<HudSettings.Module> visible = new ArrayList<>();
    private Tab tab = Tab.MODULES;
    private Category category = Category.ALLE;
    private TextFieldWidget search;
    private VeyloButton previous;
    private VeyloButton next;
    private int left, top, panelWidth, panelHeight, rowCount, page, matches;

    VeyloScreen(HudSettings settings, Runnable save) {
        super(VeyloTheme.styled("Veylo Client"));
        this.settings = settings;
        this.save = save;
    }

    @Override
    protected void init() {
        panelWidth = Math.min(width - 18, 540);
        panelHeight = Math.min(height - 14, 278);
        left = (width - panelWidth) / 2;
        top = (height - panelHeight) / 2;
        rowCount = Math.max(1, (panelHeight - 107) / 32);
        rows.clear();
        visible.clear();
        int tabsX = left + panelWidth - 223;
        tabButton("Module", Tab.MODULES, tabsX, 55);
        tabButton("HUD", Tab.HUD, tabsX + 59, 47);
        tabButton("Cosmetics", Tab.COSMETICS, tabsX + 110, 73);
        button("×", tabsX + 191, top + 10, 23, 20, this::close);

        switch (tab) {
            case MODULES -> initModules();
            case HUD -> initHud();
            case COSMETICS -> initCosmetics();
        }
    }

    private void tabButton(String title, Tab value, int x, int width) {
        var control = button(title, x, top + 10, width, 20, () -> switchTab(value));
        control.active = tab != value;
    }

    private void switchTab(Tab value) {
        if (value == tab) return;
        tab = value;
        page = 0;
        clearChildren();
        init();
    }

    private void initModules() {
        int categoryWidth = Math.min(54, (panelWidth - 22) / 5);
        for (var filter : Category.values()) {
            String name = switch (filter) {
                case ALLE -> "Alle";
                case HUD -> "HUD";
                case SPIELER -> "Spieler";
            };
            button(name, left + 10 + filter.ordinal() * (categoryWidth + 4), top + 46, categoryWidth, 19,
                    () -> { category = filter; page = 0; layoutRows(); });
        }
        int searchX = left + 14 + 3 * (categoryWidth + 4);
        search = addDrawableChild(new TextFieldWidget(textRenderer, searchX + 5, top + 47,
                left + panelWidth - 18 - searchX, 17, VeyloTheme.styled("Module suchen")));
        search.setDrawsBackground(false);
        search.setPlaceholder(VeyloTheme.styled("Module suchen …"));
        search.setMaxLength(40);
        search.setChangedListener(query -> { page = 0; layoutRows(); });

        int cellWidth = (panelWidth - 28) / 2;
        for (var module : HudSettings.Module.values()) {
            var widget = settings.widgets.get(module);
            var toggle = new VeyloButton(textRenderer.trimToWidth(module.label, cellWidth - 76),
                    0, 0, cellWidth - 44, 22, () -> {
                widget.enabled = !widget.enabled;
                rows.get(module).toggle.toggleStyle(widget.enabled);
                save.run();
            });
            toggle.toggleStyle(widget.enabled);
            addDrawableChild(toggle);
            var decrease = button("−", 0, 0, 18, 19, () -> resize(widget, -10));
            var increase = button("+", 0, 0, 18, 19, () -> resize(widget, 10));
            rows.put(module, new Controls(toggle, decrease, increase));
        }
        int bottom = top + panelHeight - 27;
        previous = button("<", left + panelWidth - 54, bottom, 19, 17, () -> { page--; layoutRows(); });
        next = button(">", left + panelWidth - 30, bottom, 19, 17, () -> { page++; layoutRows(); });
        layoutRows();
    }

    private void initHud() {
        button("HUD anordnen", left + 16, top + 82, 90, 24,
                () -> client.setScreen(new HudEditor(this, settings, save)));
        button("Tastenbelegung", left + 113, top + 82, 91, 24,
                () -> client.setScreen(new KeybindsScreen(this, client.options)));
        button("−", left + 16, top + 138, 23, 21, () -> opacity(-5));
        button("+", left + 99, top + 138, 23, 21, () -> opacity(5));
        var all = button("HUD sichtbar", left + 16, top + 171, 110, 22, () -> {
            settings.visible = !settings.visible;
            save.run();
            clearChildren();
            init();
        });
        all.toggleStyle(settings.visible);
        button("HUD zurücksetzen", left + 133, top + 171, 104, 22, () -> {
            var cosmetic = settings.cosmetic;
            settings.reset();
            settings.cosmetic = cosmetic;
            save.run();
            clearChildren();
            init();
        });
    }

    private void opacity(int delta) {
        settings.opacity = Math.max(35, Math.min(100, settings.opacity + delta));
        save.run();
    }

    private void initCosmetics() {
        int cellWidth = (panelWidth - 42) / 2;
        for (var cosmetic : HudSettings.Cosmetic.values()) {
            int col = cosmetic.ordinal() % 2;
            int row = cosmetic.ordinal() / 2;
            var choice = button(cosmetic.label, left + 16 + col * (cellWidth + 10),
                    top + 105 + row * 35, cellWidth, 28, () -> {
                settings.cosmetic = cosmetic;
                save.run();
                clearChildren();
                init();
            });
            choice.toggleStyle(settings.cosmetic == cosmetic);
        }
    }

    private void layoutRows() {
        String query = search.getText().strip().toLowerCase(Locale.ROOT);
        var filtered = new ArrayList<HudSettings.Module>();
        rows.forEach((module, controls) -> {
            controls.show(false);
            boolean player = switch (module) {
                case ARMOR, COORDINATES, DIRECTION, SPEED, SPRINT, ATTACK, EFFECTS, BIOME -> true;
                default -> false;
            };
            if ((category == Category.ALLE || (category == Category.SPIELER) == player) &&
                    module.label.toLowerCase(Locale.ROOT).contains(query)) filtered.add(module);
        });
        int capacity = rowCount * 2;
        matches = filtered.size();
        page = Math.max(0, Math.min(page, Math.max(0, matches - 1) / capacity));
        visible.clear();
        visible.addAll(filtered.subList(page * capacity, Math.min(matches, (page + 1) * capacity)));
        previous.active = page > 0;
        next.active = (page + 1) * capacity < matches;
        for (int index = 0; index < visible.size(); index++) {
            var controls = rows.get(visible.get(index));
            int x = cellX(index);
            int y = top + 76 + index / 2 * 32;
            int cellWidth = (panelWidth - 28) / 2;
            controls.toggle.setPosition(x, y);
            controls.decrease.setPosition(x + cellWidth - 39, y + 1);
            controls.increase.setPosition(x + cellWidth - 18, y + 1);
            controls.show(true);
        }
    }

    private int cellX(int index) {
        return left + 11 + (index % 2) * (panelWidth / 2);
    }

    private void resize(HudSettings.Widget widget, int delta) {
        widget.percent = Math.max(60, Math.min(180, widget.percent + delta));
        save.run();
    }

    private VeyloButton button(String title, int x, int y, int width, int height, Runnable action) {
        return addDrawableChild(new VeyloButton(title, x, y, width, height, action));
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        context.fill(0, 0, width, height, 0x7904080e);
        VeyloTheme.rounded(context, left + 3, top + 4, panelWidth, panelHeight, 0x9903070c);
        VeyloTheme.card(context, left, top, panelWidth, panelHeight, VeyloTheme.PANEL, VeyloTheme.BORDER);
        context.fill(left + 12, top + 37, left + panelWidth - 12, top + 38, VeyloTheme.BORDER);
        draw(context, "VEYLO", left + 12, top + 15, VeyloTheme.ACCENT);

        switch (tab) {
            case MODULES -> renderModules(context);
            case HUD -> {
                draw(context, "HUD gestalten", left + 16, top + 54, VeyloTheme.TEXT);
                draw(context, "Elemente verschieben und Hintergrund einstellen.", left + 16, top + 67, VeyloTheme.MUTED);
                draw(context, "Deckkraft der Karten", left + 16, top + 122, VeyloTheme.TEXT);
                draw(context, settings.opacity + " %", left + 49, top + 145, VeyloTheme.ACCENT);
            }
            case COSMETICS -> {
                draw(context, "Partikelspur", left + 16, top + 59, VeyloTheme.TEXT);
                draw(context, "Sichtbar nur auf diesem Client.", left + 16, top + 75, VeyloTheme.MUTED);
            }
        }
        super.render(context, mouseX, mouseY, delta);
    }

    private void renderModules(DrawContext context) {
        VeyloTheme.card(context, search.getX() - 5, top + 46, search.getWidth() + 10, 19,
                0xff111b25, VeyloTheme.BORDER);
        if (matches == 0) draw(context, "Keine passenden Module", left + 11, top + 82, VeyloTheme.MUTED);
        for (int index = 0; index < visible.size(); index++) {
            var widget = settings.widgets.get(visible.get(index));
            draw(context, "SKALIERUNG  " + widget.percent + " %", cellX(index) + 4,
                    top + 100 + index / 2 * 32, VeyloTheme.MUTED);
        }
        draw(context, matches + " Module · Seite " + (page + 1), left + 12, top + panelHeight - 22, VeyloTheme.MUTED);
    }

    private void draw(DrawContext context, String text, int x, int y, int color) {
        VeyloTheme.text(context, textRenderer, text, x, y, color);
    }

    @Override
    public boolean shouldPause() { return false; }
}
