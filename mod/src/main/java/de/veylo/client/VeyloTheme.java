package de.veylo.client;

import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Style;
import net.minecraft.text.StyleSpriteSource;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

final class VeyloTheme {
    static final int TEXT = 0xffeaf5fd;
    static final int MUTED = 0xffa5bacb;
    static final int ACCENT = 0xff66d9ff;
    static final int PANEL = 0xf50e151c;
    static final int CARD = 0xff151f28;
    static final int BORDER = 0xff263843;
    private static final Style UI_STYLE = Style.EMPTY.withFont(new StyleSpriteSource.Font(Identifier.of("veylo", "ui")));

    static Text styled(String text) {
        return Text.literal(text).setStyle(UI_STYLE);
    }

    static void text(DrawContext context, TextRenderer renderer, String text, int x, int y, int color) {
        context.drawText(renderer, styled(text), x, y, color, false);
    }

    static void brand(DrawContext context, TextRenderer renderer, int x, int y) {
        for (int line = 0; line < 8; line++) {
            context.fill(x + line / 2, y + line, x + line / 2 + 2, y + line + 1, ACCENT);
            context.fill(x + 8 - line / 2, y + line, x + 10 - line / 2, y + line + 1, ACCENT);
        }
        text(context, renderer, "VEYLO", x + 15, y, TEXT);
    }

    static void rounded(DrawContext context, int x, int y, int width, int height, int color) {
        if (width < 6 || height < 6) {
            context.fill(x, y, x + width, y + height, color);
            return;
        }
        context.fill(x + 3, y, x + width - 3, y + 1, color);
        context.fill(x + 1, y + 1, x + width - 1, y + 3, color);
        context.fill(x, y + 3, x + width, y + height - 3, color);
        context.fill(x + 1, y + height - 3, x + width - 1, y + height - 1, color);
        context.fill(x + 3, y + height - 1, x + width - 3, y + height, color);
    }

    static void card(DrawContext context, int x, int y, int width, int height, int fill, int border) {
        rounded(context, x, y, width, height, border);
        rounded(context, x + 1, y + 1, width - 2, height - 2, fill);
    }
}
