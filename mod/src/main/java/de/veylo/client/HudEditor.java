package de.veylo.client;

import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;

final class HudEditor extends Screen {
    private static final HudSettings.Module[] MODULES = HudSettings.Module.values();
    private final Screen parent;
    private final HudSettings settings;
    private final Runnable save;
    private HudSettings.Module selected = HudSettings.Module.FPS;
    private double offsetX, offsetY, grabX, grabY;
    private float startScale;
    private VeyloHud.Bounds startBounds;
    private boolean resizing;
    private int panelX, panelY, panelWidth, panelHeight, canvasX, canvasY, inspectorX;
    private float previewScale;
    private VeyloButton toggle;
    private VeyloButton snap;

    HudEditor(Screen parent, HudSettings settings, Runnable save) {
        super(VeyloTheme.styled("Veylo HUD-Editor"));
        this.parent = parent;
        this.settings = settings;
        this.save = save;
    }

    @Override
    protected void init() {
        panelWidth = Math.min(width - 12, 632);
        panelHeight = Math.min(height - 12, 340);
        panelX = (width - panelWidth) / 2;
        panelY = (height - panelHeight) / 2;
        int previewWidth = panelWidth - 133;
        int previewHeight = panelHeight - 62;
        previewScale = Math.min(previewWidth / (float) width, previewHeight / (float) height);
        canvasX = panelX + 9 + Math.round((previewWidth - width * previewScale) / 2);
        canvasY = panelY + 34 + Math.round((previewHeight - height * previewScale) / 2);
        inspectorX = panelX + panelWidth - 114;

        button("×", panelX + panelWidth - 27, panelY + 6, 19, this::close);
        int y = panelY + 50;
        button("‹", inspectorX, y, 48, () -> selectRelative(-1));
        button("›", inspectorX + 54, y, 48, () -> selectRelative(1));
        toggle = button("Sichtbar", inspectorX, y + 23, 102, () -> {
            var widget = settings.widgets.get(selected);
            widget.enabled = !widget.enabled;
            toggle.toggleStyle(widget.enabled);
            save.run();
        });
        button("Stil & Größe", inspectorX, y + 46, 102,
                () -> client.setScreen(new HudWidgetScreen(this, settings, selected, save)));
        snap = button("Einrasten", inspectorX, y + 69, 102, () -> {
            settings.snap = !settings.snap;
            snap.toggleStyle(settings.snap);
            save.run();
        });
        button("Layouts", inspectorX, y + 92, 102,
                () -> client.setScreen(new HudLayoutsScreen(this, settings, save)));
        button("Fertig", inspectorX, panelY + panelHeight - 26, 102, this::close).highlighted(true);
        updateToggle();
        snap.toggleStyle(settings.snap);
    }

    private void selectRelative(int delta) {
        selected = MODULES[Math.floorMod(selected.ordinal() + delta, MODULES.length)];
        updateToggle();
    }

    private void updateToggle() {
        toggle.toggleStyle(settings.widgets.get(selected).enabled);
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        context.fill(0, 0, width, height, 0x70060a0e);
        VeyloTheme.card(context, panelX, panelY, panelWidth, panelHeight, VeyloTheme.PANEL, VeyloTheme.BORDER);
        VeyloTheme.brand(context, textRenderer, panelX + 10, panelY + 12);
        draw(context, "HUD-Editor", panelX + 70, panelY + 12, VeyloTheme.TEXT);
        int previewRight = canvasX + Math.round(width * previewScale);
        int previewBottom = canvasY + Math.round(height * previewScale);
        VeyloTheme.card(context, canvasX - 1, canvasY - 1, previewRight - canvasX + 2,
                previewBottom - canvasY + 2, 0xff101b23, 0xff2a4451);
        for (int x = canvasX + 8; x < previewRight; x += 8) {
            for (int y = canvasY + 8; y < previewBottom; y += 8) context.fill(x, y, x + 1, y + 1, 0xff253740);
        }
        context.enableScissor(canvasX, canvasY, previewRight, previewBottom);
        var matrix = context.getMatrices();
        matrix.pushMatrix();
        matrix.translate(canvasX, canvasY);
        matrix.scale(previewScale, previewScale);
        settings.widgets.forEach((module, widget) -> {
            if (widget.enabled && module != selected) VeyloHud.draw(context, module, widget);
        });
        var widget = settings.widgets.get(selected);
        if (widget.enabled) VeyloHud.draw(context, selected, widget);
        var box = VeyloHud.bounds(width, height, selected, widget);
        int x = box.x(), y = box.y(), right = x + box.width(), bottom = y + box.height();
        int border = widget.enabled ? VeyloTheme.ACCENT : VeyloTheme.MUTED;
        context.fill(x, y, right, y + 1, border);
        context.fill(x, bottom - 1, right, bottom, border);
        context.fill(x, y, x + 1, bottom, border);
        context.fill(right - 1, y, right, bottom, border);
        context.fill(right - 7, bottom - 7, right, bottom, border);
        matrix.popMatrix();
        context.disableScissor();

        context.enableScissor(inspectorX, panelY + 30, inspectorX + 104, panelY + 49);
        draw(context, selected.label, inspectorX, panelY + 35, VeyloTheme.TEXT);
        context.disableScissor();
        draw(context, widget.percent + " %  ·  X " + box.x() + "  Y " + box.y(),
                inspectorX, panelY + 166, VeyloTheme.MUTED);
        draw(context, "Ziehen  ·  Ecke: Größe", panelX + 10, panelY + panelHeight - 20, VeyloTheme.MUTED);
        super.render(context, mouseX, mouseY, delta);
    }

    @Override
    public boolean mouseClicked(Click click, boolean doubled) {
        double x = (click.x() - canvasX) / previewScale;
        double y = (click.y() - canvasY) / previewScale;
        if (click.button() != 0 || x < 0 || x >= width || y < 0 || y >= height)
            return super.mouseClicked(click, doubled);
        var selectedBox = VeyloHud.bounds(width, height, selected, settings.widgets.get(selected));
        if (selectedBox.contains(x, y)) {
            boolean handle = x >= selectedBox.x() + selectedBox.width() - 9
                    && y >= selectedBox.y() + selectedBox.height() - 9;
            beginDrag(x, y, selectedBox, handle);
            return true;
        }
        for (int i = MODULES.length - 1; i >= 0; i--) {
            var module = MODULES[i];
            var widget = settings.widgets.get(module);
            if (!widget.enabled) continue;
            var box = VeyloHud.bounds(width, height, module, widget);
            if (!box.contains(x, y)) continue;
            selected = module;
            updateToggle();
            beginDrag(x, y, box, false);
            return true;
        }
        return super.mouseClicked(click, doubled);
    }

    private void beginDrag(double x, double y, VeyloHud.Bounds box, boolean resize) {
        startBounds = box;
        startScale = box.scale();
        offsetX = x - box.x();
        offsetY = y - box.y();
        grabX = x;
        grabY = y;
        resizing = resize;
    }

    @Override
    public boolean mouseDragged(Click click, double dx, double dy) {
        if (startBounds == null || click.button() != 0) return super.mouseDragged(click, dx, dy);
        var widget = settings.widgets.get(selected);
        double x = (click.x() - canvasX) / previewScale;
        double y = (click.y() - canvasY) / previewScale;
        if (resizing) {
            double change = ((x - grabX) * startBounds.width() + (y - grabY) * startBounds.height()) /
                    ((double) startBounds.width() * startBounds.width() + (double) startBounds.height() * startBounds.height());
            widget.percent = Math.max(40, Math.min(240, (int) Math.round((1 + change) * startScale * 100)));
        } else {
            var horizontal = HudGeometry.snapPosition((int) Math.round(x - offsetX), startBounds.width(), width, settings.snap);
            var vertical = HudGeometry.snapPosition((int) Math.round(y - offsetY), startBounds.height(), height, settings.snap);
            widget.x = horizontal.offset();
            widget.y = vertical.offset();
            widget.right = horizontal.far();
            widget.bottom = vertical.far();
        }
        return true;
    }

    @Override
    public boolean mouseReleased(Click click) {
        if (startBounds == null || click.button() != 0) return super.mouseReleased(click);
        startBounds = null;
        save.run();
        return true;
    }

    private VeyloButton button(String text, int x, int y, int width, Runnable action) {
        return addDrawableChild(new VeyloButton(text, x, y, width, 18, action));
    }

    private void draw(DrawContext context, String text, int x, int y, int color) {
        VeyloTheme.text(context, textRenderer, text, x, y, color);
    }

    @Override
    public void close() {
        save.run();
        client.setScreen(parent);
    }

    @Override
    public boolean shouldPause() { return false; }
}
