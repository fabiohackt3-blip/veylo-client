package de.veylo.client;

import net.minecraft.client.gui.Click;
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

    private enum Category {
        ALL("Alle"), STATS("Statistik"), PLAYER("Spieler"), WORLD("Welt");
        final String label;
        Category(String label) { this.label = label; }
    }

    private record Controls(VeyloButton toggle, VeyloButton options) {
        void show(boolean visible, boolean active) {
            toggle.visible = options.visible = visible;
            toggle.active = options.active = active;
        }
    }

    private static final int ROW = 41;
    private final HudSettings settings;
    private final Runnable save;
    private final EnumMap<HudSettings.Module, Controls> rows = new EnumMap<>(HudSettings.Module.class);
    private final EnumMap<Category, VeyloButton> categories = new EnumMap<>(Category.class);
    private final List<HudSettings.Module> filtered = new ArrayList<>();
    private Tab tab = Tab.MODULES;
    private Category category = Category.ALL;
    private String query = "";
    private int left, top, panelWidth, panelHeight, listX, listTop, listBottom, listRight, columns, cardWidth;
    private int maxScroll;
    private float scroll, targetScroll;
    private long lastFrame;
    private double scrollbarGrab = -1;

    VeyloScreen(HudSettings settings, Runnable save) {
        super(VeyloTheme.styled("Veylo Client"));
        this.settings = settings;
        this.save = save;
    }

    @Override
    protected void init() {
        panelWidth = Math.min(width - 14, 554);
        panelHeight = Math.min(height - 12, 271);
        left = (width - panelWidth) / 2;
        top = (height - panelHeight) / 2;
        int tabsX = left + panelWidth - 184;
        tabButton("Module", Tab.MODULES, tabsX, 47);
        tabButton("HUD", Tab.HUD, tabsX + 51, 33);
        tabButton("Cosmetics", Tab.COSMETICS, tabsX + 88, 60);
        button("×", tabsX + 155, top + 8, 20, 20, this::close);
        switch (tab) {
            case MODULES -> initModules();
            case HUD -> initHud();
            case COSMETICS -> initCosmetics();
        }
    }

    private void tabButton(String title, Tab value, int x, int width) {
        button(title, x, top + 8, width, 20, () -> {
            if (tab == value) return;
            tab = value;
            clearChildren();
            init();
        }).highlighted(tab == value);
    }

    private void initModules() {
        rows.clear();
        categories.clear();
        listX = left + 85;
        listTop = top + 64;
        listBottom = top + panelHeight - 20;
        listRight = left + panelWidth - 13;
        columns = listRight - listX >= 325 ? 2 : 1;
        cardWidth = (listRight - listX - 6 - (columns - 1) * 6) / columns;
        for (var filter : Category.values()) {
            var control = button(filter.label, left + 8, top + 64 + filter.ordinal() * 23, 67, 19, () -> {
                category = filter;
                filterRows(true);
            });
            categories.put(filter, control);
        }
        var search = addDrawableChild(new TextFieldWidget(textRenderer, listX + 6, top + 43,
                listRight - listX - 12, 16, VeyloTheme.styled("Module suchen")));
        search.setDrawsBackground(false);
        search.setPlaceholder(VeyloTheme.styled("Module suchen …"));
        search.setMaxLength(40);
        search.setText(query);
        search.setChangedListener(value -> {
            query = value;
            filterRows(true);
        });
        for (var module : HudSettings.Module.values()) {
            var widget = settings.widgets.get(module);
            var toggle = new VeyloButton(module.label, 0, 0, 25, 18, () -> {
                widget.enabled = !widget.enabled;
                rows.get(module).toggle.toggleStyle(widget.enabled);
                save.run();
            });
            toggle.toggleStyle(widget.enabled);
            toggle.switchOnly();
            addDrawableChild(toggle);
            var options = button("⚙", 0, 0, 18, 18,
                    () -> client.setScreen(new HudWidgetScreen(this, settings, module, save)));
            rows.put(module, new Controls(toggle, options));
        }
        filterRows(false);
    }

    private void filterRows(boolean resetScroll) {
        categories.forEach((value, button) -> button.highlighted(value == category));
        filtered.clear();
        String normalized = query.strip().toLowerCase(Locale.ROOT);
        for (var module : HudSettings.Module.values()) {
            if (category != Category.ALL && category != group(module)) continue;
            if (module.label.toLowerCase(Locale.ROOT).contains(normalized) ||
                    module.name().toLowerCase(Locale.ROOT).contains(normalized)) filtered.add(module);
        }
        maxScroll = Math.max(0, (filtered.size() + columns - 1) / columns * ROW - 5 - (listBottom - listTop));
        targetScroll = resetScroll ? 0 : Math.min(maxScroll, targetScroll);
        scroll = resetScroll ? 0 : Math.min(maxScroll, scroll);
        positionRows();
    }

    private void positionRows() {
        rows.values().forEach(controls -> controls.show(false, false));
        for (int index = 0; index < filtered.size(); index++) {
            var control = rows.get(filtered.get(index));
            int x = cardX(index) + cardWidth;
            int y = cardY(index) + 9;
            control.toggle.setPosition(x - 49, y);
            control.options.setPosition(x - 22, y);
            boolean fits = y >= listTop && y + 18 <= listBottom;
            control.show(fits, fits);
        }
    }

    private int cardX(int index) { return listX + index % columns * (cardWidth + 6); }
    private int cardY(int index) { return listTop + index / columns * ROW - Math.round(scroll); }

    private static Category group(HudSettings.Module module) {
        return switch (module) {
            case FPS, PING, MEMORY, CPS, CLOCK, SESSION -> Category.STATS;
            case KEYS, ARMOR, SPEED, SPRINT, ATTACK, EFFECTS -> Category.PLAYER;
            case COORDINATES, DIRECTION, BIOME -> Category.WORLD;
        };
    }

    private static String description(HudSettings.Module module) {
        return switch (module) {
            case FPS -> "Bilder pro Sekunde";
            case COORDINATES -> "Position in der Welt";
            case PING -> "Server-Latenz";
            case KEYS -> "Bewegungstasten";
            case ARMOR -> "Getragene Ausrüstung";
            case DIRECTION -> "Blickrichtung";
            case SPEED -> "Blöcke pro Sekunde";
            case CLOCK -> "Lokale Uhrzeit";
            case MEMORY -> "Java-Speicher";
            case CPS -> "Linke und rechte Maustaste";
            case EFFECTS -> "Aktive Statuseffekte";
            case SPRINT -> "Aktueller Sprintstatus";
            case SESSION -> "Dauer der Spielsitzung";
            case ATTACK -> "Ladezustand des Angriffs";
            case BIOME -> "Aktuelles Biom";
        };
    }

    private void initHud() {
        button("HUD-Editor öffnen", left + 13, top + 70, 102, 21,
                () -> client.setScreen(new HudEditor(this, settings, save))).highlighted(true);
        button("Tastenbelegung", left + 122, top + 70, 93, 21,
                () -> client.setScreen(new KeybindsScreen(this, client.options)));
        var all = new VeyloButton("HUD sichtbar", left + 13, top + 101, 102, 21, () -> {
            settings.visible = !settings.visible;
            save.run();
            clearChildren();
            init();
        });
        all.toggleStyle(settings.visible);
        addDrawableChild(all);
        button("HUD zurücksetzen", left + 122, top + 101, 93, 21, () -> {
            settings.reset();
            save.run();
            clearChildren();
            init();
        });
        button("Alle transparenter", left + 13, top + 130, 102, 21, () -> adjustAllOpacity(-5));
        button("Alle deckender", left + 122, top + 130, 93, 21, () -> adjustAllOpacity(5));
    }

    private void adjustAllOpacity(int change) {
        for (var widget : settings.widgets.values())
            widget.opacity = Math.max(0, Math.min(100, widget.opacity + change));
        save.run();
    }

    private void initCosmetics() {
        int cellWidth = (panelWidth - 34) / 2;
        for (var cosmetic : HudSettings.Cosmetic.values()) {
            int col = cosmetic.ordinal() % 2;
            int row = cosmetic.ordinal() / 2;
            var choice = button(cosmetic.label, left + 13 + col * (cellWidth + 8),
                    top + 72 + row * 28, cellWidth, 22, () -> {
                settings.cosmetic = cosmetic;
                save.run();
                clearChildren();
                init();
            });
            choice.toggleStyle(settings.cosmetic == cosmetic);
        }
    }

    private VeyloButton button(String title, int x, int y, int width, int height, Runnable action) {
        return addDrawableChild(new VeyloButton(title, x, y, width, height, action));
    }

    @Override
    public boolean mouseScrolled(double x, double y, double horizontal, double vertical) {
        if (tab != Tab.MODULES || x < listX || x > listRight + 5 || y < listTop || y >= listBottom)
            return super.mouseScrolled(x, y, horizontal, vertical);
        targetScroll = Math.max(0, Math.min(maxScroll, targetScroll - (float) vertical * 32));
        return true;
    }

    private int thumbHeight() {
        int view = listBottom - listTop;
        return Math.max(14, view * view / (view + maxScroll));
    }

    private int thumbY() {
        return listTop + Math.round(scroll * (listBottom - listTop - thumbHeight()) / Math.max(1, maxScroll));
    }

    @Override
    public boolean mouseClicked(Click click, boolean doubled) {
        if (tab == Tab.MODULES && maxScroll > 0 && click.button() == 0 && click.x() >= listRight - 5 &&
                click.x() < listRight + 5 && click.y() >= listTop && click.y() < listBottom) {
            int thumbY = thumbY();
            scrollbarGrab = click.y() >= thumbY && click.y() < thumbY + thumbHeight()
                    ? click.y() - thumbY : thumbHeight() / 2.0;
            moveScrollbar(click.y());
            return true;
        }
        return super.mouseClicked(click, doubled);
    }

    @Override
    public boolean mouseDragged(Click click, double dx, double dy) {
        if (scrollbarGrab < 0) return super.mouseDragged(click, dx, dy);
        moveScrollbar(click.y());
        return true;
    }

    @Override
    public boolean mouseReleased(Click click) {
        if (scrollbarGrab < 0) return super.mouseReleased(click);
        scrollbarGrab = -1;
        return true;
    }

    private void moveScrollbar(double y) {
        double fraction = (y - listTop - scrollbarGrab) / Math.max(1, listBottom - listTop - thumbHeight());
        targetScroll = scroll = (float) Math.max(0, Math.min(maxScroll, fraction * maxScroll));
        positionRows();
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        context.fill(0, 0, width, height, 0x5604080e);
        VeyloTheme.rounded(context, left + 2, top + 3, panelWidth, panelHeight, 0x8803070c);
        VeyloTheme.card(context, left, top, panelWidth, panelHeight, VeyloTheme.PANEL, VeyloTheme.BORDER);
        context.fill(left + 8, top + 34, left + panelWidth - 8, top + 35, VeyloTheme.BORDER);
        VeyloTheme.brand(context, textRenderer, left + 11, top + 14);

        switch (tab) {
            case MODULES -> renderModules(context, mouseX, mouseY);
            case HUD -> draw(context, "Layout, Anzeige und Tasten", left + 13, top + 49, VeyloTheme.MUTED);
            case COSMETICS -> draw(context, "Partikelspur · lokal sichtbar", left + 13, top + 49, VeyloTheme.MUTED);
        }
        super.render(context, mouseX, mouseY, delta);
    }

    private void renderModules(DrawContext context, int mouseX, int mouseY) {
        long now = System.nanoTime();
        float step = lastFrame == 0 ? 1f : Math.min(1f, (now - lastFrame) / 80_000_000f);
        lastFrame = now;
        scroll += (targetScroll - scroll) * step;
        if (Math.abs(targetScroll - scroll) < .1f) scroll = targetScroll;
        positionRows();
        draw(context, "KATEGORIEN", left + 9, top + 48, VeyloTheme.MUTED);
        VeyloTheme.card(context, listX, top + 39, listRight - listX, 19, 0xff121d25, VeyloTheme.BORDER);
        context.enableScissor(listX, listTop, listRight, listBottom);
        if (filtered.isEmpty()) draw(context, "Keine passenden Module", listX + 4, listTop + 7, VeyloTheme.MUTED);
        for (int i = 0; i < filtered.size(); i++) {
            var module = filtered.get(i);
            int x = cardX(i);
            int y = cardY(i);
            if (y >= listBottom || y + 36 <= listTop) continue;
            boolean hover = mouseX >= x && mouseX < x + cardWidth && mouseY >= Math.max(y, listTop)
                    && mouseY < Math.min(y + 36, listBottom);
            VeyloTheme.card(context, x, y, cardWidth, 36, hover ? 0xff1a2a34 : VeyloTheme.CARD,
                    hover ? 0xff3b5d6e : VeyloTheme.BORDER);
            if (settings.widgets.get(module).enabled) context.fill(x + 3, y + 9, x + 4, y + 27, VeyloTheme.ACCENT);
            draw(context, module.label, x + 8, y + 7, VeyloTheme.TEXT);
            context.enableScissor(x + 8, y + 19, x + cardWidth - 52, y + 30);
            draw(context, description(module), x + 8, y + 20, VeyloTheme.MUTED);
            context.disableScissor();
        }
        context.disableScissor();
        if (maxScroll > 0) {
            context.fill(listRight - 3, listTop, listRight, listBottom, VeyloTheme.BORDER);
            VeyloTheme.rounded(context, listRight - 4, thumbY(), 4, thumbHeight(), 0xff3895ad);
        }
        draw(context, filtered.size() + " Module", listX, listBottom + 6, VeyloTheme.MUTED);
    }

    private void draw(DrawContext context, String text, int x, int y, int color) {
        VeyloTheme.text(context, textRenderer, text, x, y, color);
    }

    @Override
    public boolean shouldPause() { return false; }
}
