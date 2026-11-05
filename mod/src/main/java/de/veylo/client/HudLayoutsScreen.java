package de.veylo.client;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.TextFieldWidget;

final class HudLayoutsScreen extends Screen {
    private final Screen parent;
    private final HudSettings settings;
    private final Runnable save;
    private TextFieldWidget name;
    private String feedback = "";
    private int index, left, top;

    HudLayoutsScreen(Screen parent, HudSettings settings, Runnable save) {
        super(VeyloTheme.styled("HUD-Layouts"));
        this.parent = parent;
        this.settings = settings;
        this.save = save;
    }

    @Override
    protected void init() {
        left = (width - 230) / 2;
        top = (height - 140) / 2;
        name = addDrawableChild(new TextFieldWidget(textRenderer, left + 17, top + 65, 133, 15,
                VeyloTheme.styled("Layoutname")));
        name.setDrawsBackground(false);
        name.setPlaceholder(VeyloTheme.styled("Layoutname …"));
        name.setMaxLength(24);
        button("‹", 12, 31, 20, () -> cycle(-1));
        button("›", 137, 31, 20, () -> cycle(1));
        button("Laden", 164, 31, 54, () -> {
            var names = settings.layoutNames();
            if (names.isEmpty()) {
                feedback = "Es gibt noch keine gespeicherten Layouts";
                return;
            }
            settings.loadLayout(names.get(index));
            save.run();
            close();
        });
        button("Speichern", 164, 59, 54, () -> {
            if (!settings.saveLayout(name.getText())) {
                feedback = name.getText().isBlank() ? "Gib dem Layout einen Namen" : "Maximal 12 Layouts";
                return;
            }
            index = settings.layoutNames().indexOf(name.getText().strip());
            save.run();
            feedback = "Layout gespeichert";
        }).highlighted(true);
        button("Fertig", 164, 107, 54, this::close);
    }

    private void cycle(int change) {
        var names = settings.layoutNames();
        if (!names.isEmpty()) index = Math.floorMod(index + change, names.size());
    }

    private VeyloButton button(String title, int x, int y, int width, Runnable action) {
        return addDrawableChild(new VeyloButton(title, left + x, top + y, width, 18, action));
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        context.fill(0, 0, width, height, 0x5903080c);
        VeyloTheme.card(context, left, top, 230, 140, VeyloTheme.PANEL, VeyloTheme.BORDER);
        VeyloTheme.brand(context, textRenderer, left + 12, top + 12);
        draw(context, "HUD-Layouts", 70, 12, VeyloTheme.TEXT);
        var names = settings.layoutNames();
        context.enableScissor(left + 39, top + 31, left + 132, top + 50);
        draw(context, names.isEmpty() ? "Keine Layouts" : names.get(index), 40, 36, VeyloTheme.TEXT);
        context.disableScissor();
        VeyloTheme.card(context, left + 12, top + 59, 145, 18, 0xff111a22, VeyloTheme.BORDER);
        draw(context, feedback, 12, 88, VeyloTheme.ACCENT);
        draw(context, names.size() + " / 12 Layouts", 12, 113, VeyloTheme.MUTED);
        super.render(context, mouseX, mouseY, delta);
    }

    private void draw(DrawContext context, String value, int x, int y, int color) {
        VeyloTheme.text(context, textRenderer, value, left + x, top + y, color);
    }

    @Override
    public void close() { client.setScreen(parent); }

    @Override
    public boolean shouldPause() { return false; }
}
