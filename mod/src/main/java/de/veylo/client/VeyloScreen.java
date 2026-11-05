package de.veylo.client;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.option.KeybindsScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;

final class VeyloScreen extends Screen {
    private enum Category {
        ALL("Alle"), HUD("HUD"), PLAYER("Spieler");
        final String title;
        Category(String title) { this.title = title; }
    }

    private record Controls(ButtonWidget toggle, ButtonWidget decrease, ButtonWidget increase) {
        void show(boolean visible) {
            toggle.visible = decrease.visible = increase.visible = visible;
        }
    }

    private final HudSettings settings;
    private final Runnable save;
    private final EnumMap<HudSettings.Module, Controls> rows = new EnumMap<>(HudSettings.Module.class);
    private final List<HudSettings.Module> visible = new ArrayList<>();
    private Category category = Category.ALL;
    private TextFieldWidget search;
    private ButtonWidget previous;
    private ButtonWidget next;
    private int left, top, panelWidth, panelHeight, rowCount, page, matches;

    VeyloScreen(HudSettings settings, Runnable save) {
        super(Text.literal("Veylo Client"));
        this.settings = settings;
        this.save = save;
    }

    @Override
    protected void init() {
        panelWidth = Math.min(width - 16, 510);
        panelHeight = Math.min(height - 12, 248);
        left = (width - panelWidth) / 2;
        top = (height - panelHeight) / 2;
        rowCount = Math.max(1, (panelHeight - 91) / 29);
        rows.clear();

        int categoryWidth = Math.min(55, (panelWidth - 16) / 5);
        for (var filter : Category.values()) {
            button(filter.title, left + 8 + filter.ordinal() * (categoryWidth + 4), top + 29,
                    categoryWidth, b -> { category = filter; page = 0; layoutRows(); });
        }
        int searchX = left + 10 + 3 * (categoryWidth + 4);
        search = addDrawableChild(new TextFieldWidget(textRenderer, searchX, top + 29,
                left + panelWidth - 9 - searchX, 19, Text.literal("Module suchen")));
        search.setPlaceholder(Text.literal("Suchen …"));
        search.setMaxLength(40);
        search.setChangedListener(query -> { page = 0; layoutRows(); });

        int cellWidth = (panelWidth - 24) / 2;
        for (var module : HudSettings.Module.values()) {
            var widget = settings.widgets.get(module);
            var toggle = button(toggleLabel(module, widget, cellWidth), 0, 0, cellWidth - 46, b -> {
                widget.enabled = !widget.enabled;
                b.setMessage(Text.literal(toggleLabel(module, widget, cellWidth)));
                save.run();
            });
            var decrease = button("-", 0, 0, 18, b -> resize(widget, -10));
            var increase = button("+", 0, 0, 18, b -> resize(widget, 10));
            rows.put(module, new Controls(toggle, decrease, increase));
        }
        int bottom = top + panelHeight - 26;
        int x = left + 8;
        button("HUD-Editor", x, bottom, 68, b -> client.setScreen(new HudEditor(this, settings, save)));
        button("Hotkeys", x + 72, bottom, 48, b -> client.setScreen(new KeybindsScreen(this, client.options)));
        button("Reset", x + 124, bottom, 42, b -> {
            settings.reset();
            save.run();
            client.setScreen(new VeyloScreen(settings, save));
        });
        button("Fertig", x + 170, bottom, 43, b -> close());
        int right = left + panelWidth - 8;
        previous = button("<", right - 42, bottom, 19, b -> { page--; layoutRows(); });
        next = button(">", right - 19, bottom, 19, b -> { page++; layoutRows(); });
        layoutRows();
    }

    private void layoutRows() {
        String query = search.getText().strip().toLowerCase(Locale.ROOT);
        var filtered = new ArrayList<HudSettings.Module>();
        rows.forEach((module, controls) -> {
            controls.show(false);
            boolean player = switch (module) {
                case ARMOR, COORDINATES, DIRECTION, SPEED -> true;
                default -> false;
            };
            if ((category == Category.ALL || (category == Category.PLAYER) == player) &&
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
            int y = top + 59 + index / 2 * 29;
            int cellWidth = (panelWidth - 24) / 2;
            controls.toggle.setPosition(x, y);
            controls.decrease.setPosition(x + cellWidth - 40, y);
            controls.increase.setPosition(x + cellWidth - 19, y);
            controls.show(true);
        }
    }

    private int cellX(int index) {
        return left + 9 + (index % 2) * (panelWidth / 2);
    }

    private void resize(HudSettings.Widget widget, int delta) {
        widget.percent = Math.max(60, Math.min(180, widget.percent + delta));
        save.run();
    }

    private String toggleLabel(HudSettings.Module module, HudSettings.Widget widget, int cellWidth) {
        String status = widget.enabled ? " + " : " - ";
        return status + textRenderer.trimToWidth(module.label, Math.max(20, cellWidth - 66));
    }

    private ButtonWidget button(String title, int x, int y, int width, ButtonWidget.PressAction action) {
        return addDrawableChild(ButtonWidget.builder(Text.literal(title), action).dimensions(x, y, width, 18).build());
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        context.fill(0, 0, width, height, 0x85070d13);
        context.fill(left, top, left + panelWidth, top + panelHeight, 0xf0101923);
        context.fill(left, top, left + panelWidth, top + 2, 0xff66d2ff);
        context.drawTextWithShadow(textRenderer, "V E Y L O   /   MODULE", left + 9, top + 10, 0xff94e0ff);
        if (matches == 0) context.drawTextWithShadow(textRenderer, "Keine passenden Module", left + 9, top + 62, 0xffbdcdd8);
        for (int index = 0; index < visible.size(); index++) {
            var widget = settings.widgets.get(visible.get(index));
            context.drawTextWithShadow(textRenderer, widget.percent + " %", cellX(index),
                    top + 78 + index / 2 * 29, 0xffa3b6c7);
        }
        super.render(context, mouseX, mouseY, delta);
    }

    @Override
    public boolean shouldPause() { return false; }
}
