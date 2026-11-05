package de.veylo.client;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.TextFieldWidget;

import java.util.Locale;

final class HudWidgetScreen extends Screen {
    private final Screen parent;
    private final HudSettings settings;
    private final HudSettings.Module module;
    private final Runnable save;
    private int left, top;
    private TextFieldWidget hex;
    private VeyloButton enabled;
    private String feedback = "";
    private static final int[] PALETTE = { 0x66d9ff, 0xffffff, 0x8be5aa, 0xffce83, 0xff909e };

    HudWidgetScreen(Screen parent, HudSettings settings, HudSettings.Module module, Runnable save) {
        super(VeyloTheme.styled(module.label));
        this.parent = parent;
        this.settings = settings;
        this.module = module;
        this.save = save;
    }

    @Override
    protected void init() {
        left = (width - 230) / 2;
        top = (height - 184) / 2;
        enabled = button("Sichtbar", 12, 31, 102, () -> {
            widget().enabled = !widget().enabled;
            enabled.toggleStyle(widget().enabled);
            save.run();
        });
        enabled.toggleStyle(widget().enabled);
        button("−", 131, 55, 19, () -> scale(-1));
        button("+", 156, 55, 19, () -> scale(1));
        button("−10", 181, 55, 20, () -> scale(-10));
        button("+10", 204, 55, 20, () -> scale(10));
        button("−", 131, 77, 19, () -> opacity(-5));
        button("+", 156, 77, 19, () -> opacity(5));
        button("0 %", 181, 77, 43, () -> { widget().opacity = 0; save.run(); });

        hex = addDrawableChild(new TextFieldWidget(textRenderer, left + 132, top + 104, 50, 14,
                VeyloTheme.styled("Akzentfarbe als Hexcode")));
        hex.setDrawsBackground(false);
        hex.setMaxLength(7);
        hex.setText(hexValue());
        button("Setzen", 184, 98, 40, () -> {
            String value = hex.getText().strip().replaceFirst("^#", "");
            if (!value.matches("[a-fA-F0-9]{6}")) {
                feedback = "Hexcode mit sechs Stellen eingeben";
                return;
            }
            accent(Integer.parseInt(value, 16));
        });
        for (int i = 0; i < PALETTE.length; i++) {
            int color = PALETTE[i];
            button("●", 12 + i * 21, 119, 18, () -> accent(color));
        }
        button("Stil zurücksetzen", 12, 155, 106, () -> {
            widget().percent = 100;
            widget().opacity = HudSettings.DEFAULT_OPACITY;
            accent(HudSettings.DEFAULT_ACCENT);
        });
        button("Fertig", 160, 155, 64, this::close).highlighted(true);
    }

    private HudSettings.Widget widget() { return settings.widgets.get(module); }

    private String hexValue() { return String.format(Locale.ROOT, "#%06X", widget().accent); }

    private void scale(int change) {
        widget().percent = Math.max(40, Math.min(240, widget().percent + change));
        save.run();
    }

    private void opacity(int change) {
        widget().opacity = Math.max(0, Math.min(100, widget().opacity + change));
        save.run();
    }

    private void accent(int color) {
        widget().accent = color;
        hex.setText(hexValue());
        feedback = "";
        save.run();
    }

    private VeyloButton button(String title, int x, int y, int width, Runnable action) {
        return addDrawableChild(new VeyloButton(title, left + x, top + y, width, 18, action));
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        context.fill(0, 0, width, height, 0x4903080c);
        VeyloTheme.card(context, left, top, 230, 184, VeyloTheme.PANEL, VeyloTheme.BORDER);
        VeyloTheme.brand(context, textRenderer, left + 12, top + 12);
        draw(context, module.label, 70, 12, VeyloTheme.TEXT);
        draw(context, "Größe · " + widget().percent + " %", 12, 61, VeyloTheme.TEXT);
        draw(context, "Hintergrund · " + widget().opacity + " %", 12, 83, VeyloTheme.TEXT);
        draw(context, "Akzentfarbe", 12, 103, 0xff000000 | widget().accent);
        draw(context, feedback, 12, 141, VeyloTheme.MUTED);
        VeyloTheme.card(context, left + 127, top + 98, 54, 18, 0xff111a22, VeyloTheme.BORDER);
        super.render(context, mouseX, mouseY, delta);
        for (int i = 0; i < PALETTE.length; i++) {
            VeyloTheme.rounded(context, left + 17 + i * 21, top + 124, 8, 8, 0xff000000 | PALETTE[i]);
        }
        // Render the actual widget in the preview using its configured style.
        context.enableScissor(left + 124, top + 26, left + 224, top + 54);
        var box = VeyloHud.bounds(width, height, module, widget());
        float preview = Math.min(1f, Math.min(96f / box.width(), 24f / box.height()));
        var matrix = context.getMatrices();
        matrix.pushMatrix();
        matrix.translate(left + 126, top + 28);
        matrix.scale(preview, preview);
        matrix.translate(-box.x(), -box.y());
        VeyloHud.draw(context, module, widget());
        matrix.popMatrix();
        context.disableScissor();
    }

    private void draw(DrawContext context, String value, int x, int y, int color) {
        VeyloTheme.text(context, textRenderer, value, left + x, top + y, color);
    }

    @Override
    public void close() { client.setScreen(parent); }

    @Override
    public boolean shouldPause() { return false; }
}
