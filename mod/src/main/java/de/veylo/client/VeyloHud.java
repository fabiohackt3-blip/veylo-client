package de.veylo.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.item.ItemStack;

final class VeyloHud {
    private static final int BACKGROUND = 0xc5171327;
    private static final int TEXT = 0xfff5efff;
    private static final int ACCENT = 0xffb28cff;
    private static final EquipmentSlot[] ARMOR = {
            EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET
    };

    record Bounds(int x, int y, int width, int height) {
        boolean contains(double mx, double my) {
            return mx >= x && mx < x + width && my >= y && my < y + height;
        }
    }

    static void render(DrawContext context, HudSettings settings) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (!settings.visible || client.player == null || client.currentScreen instanceof HudEditor) return;
        settings.widgets.forEach((module, widget) -> {
            if (widget.enabled) draw(context, module, widget, false);
        });
    }

    static Bounds bounds(int screenWidth, int screenHeight, HudSettings.Module module, HudSettings.Widget widget) {
        int baseWidth = switch (module) {
            case FPS, PING -> 74;
            case COORDINATES -> 154;
            case KEYS -> 74;
            case ARMOR -> 84;
        };
        int baseHeight = switch (module) {
            case KEYS -> 38;
            case ARMOR -> 23;
            default -> 18;
        };
        float scale = Math.min(widget.percent / 100f, Math.min(
                (float) screenWidth / baseWidth,
                (float) screenHeight / baseHeight));
        int width = (int) Math.ceil(baseWidth * scale);
        int height = (int) Math.ceil(baseHeight * scale);
        int x = Math.max(0, Math.min(screenWidth - width, widget.x));
        int y = Math.max(0, Math.min(screenHeight - height, widget.y));
        return new Bounds(x, y, width, height);
    }

    static void draw(DrawContext context, HudSettings.Module module, HudSettings.Widget widget, boolean editor) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) return;
        Bounds box = bounds(context.getScaledWindowWidth(), context.getScaledWindowHeight(), module, widget);
        int baseWidth = switch (module) {
            case COORDINATES -> 154;
            case ARMOR -> 84;
            default -> 74;
        };
        float scale = box.width / (float) baseWidth;
        context.fill(box.x, box.y, box.x + box.width, box.y + box.height, BACKGROUND);
        context.fill(box.x, box.y, box.x + 2, box.y + box.height, editor ? 0xffdbc9ff : ACCENT);
        var matrix = context.getMatrices();
        matrix.pushMatrix();
        matrix.translate(box.x, box.y);
        matrix.scale(scale, scale);
        switch (module) {
            case FPS -> text(context, client, "FPS  " + client.getCurrentFps(), 7, 5);
            case COORDINATES -> {
                var pos = client.player.getBlockPos();
                text(context, client, "X " + pos.getX() + "  Y " + pos.getY() + "  Z " + pos.getZ(), 7, 5);
            }
            case PING -> {
                var network = client.getNetworkHandler();
                var entry = network == null ? null : network.getPlayerListEntry(client.player.getUuid());
                text(context, client, entry == null ? "Ping  -" : "Ping  " + entry.getLatency() + " ms", 7, 5);
            }
            case KEYS -> {
                var keys = client.options;
                key(context, client, keys.forwardKey, 26, 3);
                key(context, client, keys.leftKey, 4, 20);
                key(context, client, keys.backKey, 26, 20);
                key(context, client, keys.rightKey, 48, 20);
            }
            case ARMOR -> {
                for (int i = 0; i < ARMOR.length; i++) {
                    int x = 4 + i * 20;
                    ItemStack item = client.player.getEquippedStack(ARMOR[i]);
                    context.fill(x, 3, x + 17, 20, 0x60544475);
                    if (!item.isEmpty()) {
                        context.drawItem(item, x, 3);
                        context.drawStackOverlay(client.textRenderer, item, x, 3);
                    }
                }
            }
        }
        matrix.popMatrix();
    }

    private static void key(DrawContext context, MinecraftClient client,
                            net.minecraft.client.option.KeyBinding binding, int x, int y) {
        context.fill(x, y, x + 20, y + 15, binding.isPressed() ? 0xff7850ba : 0xff382c53);
        String label = client.textRenderer.trimToWidth(binding.getBoundKeyLocalizedText().getString(), 17);
        text(context, client, label, x + (20 - client.textRenderer.getWidth(label)) / 2, y + 4);
    }

    private static void text(DrawContext context, MinecraftClient client, String label, int x, int y) {
        context.drawTextWithShadow(client.textRenderer, label, x, y, TEXT);
    }
}
