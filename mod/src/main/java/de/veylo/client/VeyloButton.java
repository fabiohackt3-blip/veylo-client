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

    VeyloButton(String title, int x, int y, int width, int height, Runnable action) {
        super(x, y, width, height, VeyloTheme.styled(title));
        this.action = action;
    }

    void toggleStyle(boolean selected) {
        switched = selected;
    }

    @Override
    protected void renderWidget(DrawContext context, int mouseX, int mouseY, float delta) {
        int x = getX();
        int y = getY();
        int width = getWidth();
        int height = getHeight();
        var font = MinecraftClient.getInstance().textRenderer;
        boolean hovered = active && isSelected();
        VeyloTheme.card(context, x, y, width, height, hovered ? 0xff223a4b : VeyloTheme.CARD,
                hovered ? 0xff50778f : VeyloTheme.BORDER);
        int textX = x + (width - font.getWidth(getMessage())) / 2;
        if (switched != null) {
            textX = x + 6;
            int track = x + width - 23;
            int middle = y + height / 2;
            VeyloTheme.rounded(context, track, middle - 4, 17, 8, switched ? 0xff2188b5 : 0xff385164);
            VeyloTheme.rounded(context, track + (switched ? 9 : 2), middle - 3, 6, 6, 0xffdff6ff);
        }
        context.drawText(font, getMessage(), textX, y + (height - 8) / 2,
                active ? VeyloTheme.TEXT : 0xff748d9e, false);
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
