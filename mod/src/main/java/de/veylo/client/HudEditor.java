package de.veylo.client;

import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

final class HudEditor extends Screen {
    private final Screen parent;
    private final HudSettings settings;
    private final Runnable save;
    private HudSettings.Widget dragging;
    private double offsetX;
    private double offsetY;
    private HudSettings.Module draggedModule;

    HudEditor(Screen parent, HudSettings settings, Runnable save) {
        super(Text.literal("Veylo HUD anordnen"));
        this.parent = parent;
        this.settings = settings;
        this.save = save;
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        context.fill(0, 0, width, height, 0x58080510);
        settings.widgets.forEach((module, widget) -> {
            if (widget.enabled) VeyloHud.draw(context, module, widget, true);
        });
        context.drawCenteredTextWithShadow(textRenderer, "HUD ziehen  /  Esc zum Speichern", width / 2,
                height - 14, 0xffe6ddf8);
    }

    @Override
    public boolean mouseClicked(Click click, boolean doubled) {
        if (click.button() == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            HudSettings.Module[] modules = HudSettings.Module.values();
            for (int index = modules.length - 1; index >= 0; index--) {
                var module = modules[index];
                var widget = settings.widgets.get(module);
                var box = VeyloHud.bounds(width, height, module, widget);
                if (widget.enabled && box.contains(click.x(), click.y())) {
                    dragging = widget;
                    offsetX = click.x() - box.x();
                    offsetY = click.y() - box.y();
                    draggedModule = module;
                    return true;
                }
            }
        }
        return super.mouseClicked(click, doubled);
    }

    @Override
    public boolean mouseDragged(Click click, double dx, double dy) {
        if (dragging == null) return super.mouseDragged(click, dx, dy);
        var box = VeyloHud.bounds(width, height, draggedModule, dragging);
        dragging.x = Math.max(0, Math.min(width - box.width(), (int) Math.round(click.x() - offsetX)));
        dragging.y = Math.max(0, Math.min(height - box.height(), (int) Math.round(click.y() - offsetY)));
        return true;
    }

    @Override
    public boolean mouseReleased(Click click) {
        if (dragging == null) return super.mouseReleased(click);
        dragging = null;
        draggedModule = null;
        save.run();
        return true;
    }

    @Override
    public void close() {
        save.run();
        client.setScreen(parent);
    }

    @Override
    public boolean shouldPause() {
        return false;
    }
}
