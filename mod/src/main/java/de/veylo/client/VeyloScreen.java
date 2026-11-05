package de.veylo.client;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.option.KeybindsScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

final class VeyloScreen extends Screen {
    private final HudSettings settings;
    private final Runnable save;
    private int left;
    private int top;

    VeyloScreen(HudSettings settings, Runnable save) {
        super(Text.literal("Veylo Client"));
        this.settings = settings;
        this.save = save;
    }

    @Override
    protected void init() {
        left = width / 2 - 133;
        top = height / 2 - 92;
        int y = top + 36;
        for (var module : HudSettings.Module.values()) {
            var widget = settings.widgets.get(module);
            button(toggleLabel(module, widget), left + 10, y, 120, b -> {
                widget.enabled = !widget.enabled;
                b.setMessage(Text.literal(toggleLabel(module, widget)));
                save.run();
            });
            button("-", left + 141, y, 26, b -> resize(widget, -10));
            button("+", left + 228, y, 26, b -> resize(widget, 10));
            y += 22;
        }
        button("HUD anordnen", left + 10, top + 150, 81,
                b -> client.setScreen(new HudEditor(this, settings, save)));
        button("Hotkeys", left + 93, top + 150, 61,
                b -> client.setScreen(new KeybindsScreen(this, client.options)));
        button("Reset", left + 156, top + 150, 49, b -> {
            settings.reset();
            save.run();
            client.setScreen(new VeyloScreen(settings, save));
        });
        button("Fertig", left + 207, top + 150, 47, b -> close());
    }

    private void resize(HudSettings.Widget widget, int delta) {
        widget.percent = Math.max(60, Math.min(180, widget.percent + delta));
        save.run();
    }

    private String toggleLabel(HudSettings.Module module, HudSettings.Widget widget) {
        return module.label + (widget.enabled ? "  AN" : "  AUS");
    }

    private void button(String title, int x, int y, int width, ButtonWidget.PressAction action) {
        addDrawableChild(ButtonWidget.builder(Text.literal(title), action).dimensions(x, y, width, 19).build());
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        context.fill(0, 0, width, height, 0x85080510);
        context.fill(left, top, left + 266, top + 179, 0xff191329);
        context.fill(left, top, left + 266, top + 2, 0xffac82ff);
        context.drawTextWithShadow(textRenderer, "V E Y L O   /   HUD", left + 10, top + 11, 0xffdcc4ff);
        int y = top + 41;
        for (var module : HudSettings.Module.values()) {
            context.drawCenteredTextWithShadow(textRenderer, settings.widgets.get(module).percent + " %",
                    left + 197, y, 0xffe6ddf8);
            y += 22;
        }
        super.render(context, mouseX, mouseY, delta);
    }

    @Override
    public boolean shouldPause() {
        return false;
    }
}
