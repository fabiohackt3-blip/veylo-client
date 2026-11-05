package de.veylo.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.narration.NarrationMessageBuilder;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.client.input.KeyInput;
import org.lwjgl.glfw.GLFW;

final class VeyloButton extends ClickableWidget {
    private final Runnable action;
    private Boolean switched;
    private boolean highlighted;
    private boolean switchOnly;
    private float hoverProgress;
    private float switchProgress = -1;
    private long lastFrame;

    VeyloButton(String title, int x, int y, int width, int height, Runnable action) {
        super(x, y, width, height, VeyloTheme.styled(title));
        this.action = action;
    }

    void toggleStyle(boolean selected) {
        switched = selected;
    }

    void highlighted(boolean highlighted) {
        this.highlighted = highlighted;
    }

    void switchOnly() {
        switchOnly = true;
    }

    @Override
    protected void renderWidget(DrawContext context, int mouseX, int mouseY, float delta) {
        int x = getX();
        int y = getY();
        int width = getWidth();
        int height = getHeight();
        var font = MinecraftClient.getInstance().textRenderer;
        boolean hovered = active && isSelected();
        long now = System.nanoTime();
        float step = lastFrame == 0 ? 1f : Math.min(1f, (now - lastFrame) / 125_000_000f);
        lastFrame = now;
        hoverProgress = approach(hoverProgress, hovered ? 1f : 0f, step);
        int idle = highlighted ? 0xff17303b : VeyloTheme.CARD;
        int border = highlighted ? 0xff34788b : VeyloTheme.BORDER;
        if (!switchOnly) VeyloTheme.card(context, x, y, width, height,
                blend(idle, 0xff263b48, hoverProgress), blend(border, 0xff4a7b8f, hoverProgress));
        int textX = x + (width - font.getWidth(getMessage())) / 2;
        if (switched != null) {
            float target = switched ? 1f : 0f;
            switchProgress = switchProgress < 0 ? target : approach(switchProgress, target, step);
            textX = x + 6;
            int track = x + width - 23;
            int middle = y + height / 2;
            VeyloTheme.rounded(context, track, middle - 5, 21, 10,
                    blend(0xff354652, 0xff23bedc, switchProgress));
            VeyloTheme.rounded(context, track + 2 + Math.round(11 * switchProgress), middle - 3, 6, 6, 0xffedfaff);
        }
        if (!switchOnly) context.drawText(font, getMessage(), textX, y + (height - 8) / 2,
                active ? VeyloTheme.TEXT : VeyloTheme.MUTED, false);
    }

    private static float approach(float current, float target, float step) {
        return target > current ? Math.min(target, current + step) : Math.max(target, current - step);
    }

    private static int blend(int from, int to, float amount) {
        int red = Math.round(((from >> 16) & 255) * (1 - amount) + ((to >> 16) & 255) * amount);
        int green = Math.round(((from >> 8) & 255) * (1 - amount) + ((to >> 8) & 255) * amount);
        int blue = Math.round((from & 255) * (1 - amount) + (to & 255) * amount);
        return 0xff000000 | red << 16 | green << 8 | blue;
    }

    @Override
    public void onClick(Click click, boolean doubled) {
        action.run();
    }

    @Override
    public boolean keyPressed(KeyInput input) {
        if (!active || !visible || !isFocused()) return false;
        if (input.key() != GLFW.GLFW_KEY_ENTER && input.key() != GLFW.GLFW_KEY_KP_ENTER &&
                input.key() != GLFW.GLFW_KEY_SPACE) return false;
        action.run();
        return true;
    }

    @Override
    protected void appendClickableNarrations(NarrationMessageBuilder builder) {
        appendDefaultNarrations(builder);
    }
}
