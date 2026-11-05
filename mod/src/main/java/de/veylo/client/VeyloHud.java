package de.veylo.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.item.ItemStack;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

final class VeyloHud {
    private static final DateTimeFormatter CLOCK_FORMAT = DateTimeFormatter.ofPattern("HH:mm");
    private static final Runtime RUNTIME = Runtime.getRuntime();
    private static final EquipmentSlot[] ARMOR = {
            EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET
    };
    private static ClientPlayNetworkHandler session;
    private static long joinedAt;
    private static int ticks;
    private static String clock = "–";
    private static String playtime = "00:00";

    record Bounds(int x, int y, int width, int height, float scale) {
        boolean contains(double mx, double my) {
            return mx >= x && mx < x + width && my >= y && my < y + height;
        }
    }

    static void tick(MinecraftClient client) {
        if (session != client.getNetworkHandler()) {
            session = client.getNetworkHandler();
            joinedAt = System.nanoTime();
            ticks = 0;
        }
        if (session != null && ticks++ % 20 == 0) {
            clock = LocalTime.now().format(CLOCK_FORMAT);
            long seconds = (System.nanoTime() - joinedAt) / 1_000_000_000L;
            playtime = String.format(Locale.ROOT, "%02d:%02d", seconds / 60, seconds % 60);
        }
    }

    static void render(DrawContext context, HudSettings settings) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (!settings.visible || client.player == null || client.currentScreen instanceof HudEditor) return;
        settings.widgets.forEach((module, widget) -> {
            if (widget.enabled) draw(context, module, widget, false, settings.opacity);
        });
    }

    static Bounds bounds(int screenWidth, int screenHeight, HudSettings.Module module, HudSettings.Widget widget) {
        int baseWidth = baseWidth(module);
        int baseHeight = baseHeight(module);
        float scale = Math.min(widget.percent / 100f, Math.min(
                (float) screenWidth / baseWidth, (float) screenHeight / baseHeight));
        int width = (int) Math.ceil(baseWidth * scale);
        int height = (int) Math.ceil(baseHeight * scale);
        int x = Math.max(0, Math.min(screenWidth - width, widget.x));
        int y = Math.max(0, Math.min(screenHeight - height, widget.y));
        return new Bounds(x, y, width, height, scale);
    }

    static void draw(DrawContext context, HudSettings.Module module, HudSettings.Widget widget, boolean editor, int opacity) {
        MinecraftClient client = MinecraftClient.getInstance();
        var player = client.player;
        if (player == null) return;
        Bounds box = bounds(context.getScaledWindowWidth(), context.getScaledWindowHeight(), module, widget);
        int width = baseWidth(module);
        int height = baseHeight(module);
        int alpha = opacity * 255 / 100;
        var matrix = context.getMatrices();
        matrix.pushMatrix();
        matrix.translate(box.x, box.y);
        matrix.scale(box.scale, box.scale);
        VeyloTheme.card(context, 0, 0, width, height, alpha << 24 | 0x101a23,
                editor ? VeyloTheme.ACCENT : alpha << 24 | 0x304658);
        label(context, client, module.label.toUpperCase(Locale.ROOT), 7, 5, VeyloTheme.ACCENT);
        switch (module) {
            case FPS -> value(context, client, Integer.toString(client.getCurrentFps()));
            case PING -> value(context, client, ServerLatency.display(client));
            case CLOCK -> value(context, client, clock);
            case SESSION -> value(context, client, playtime);
            case CPS -> value(context, client, "L " + ClickCounter.count(0) + "  ·  R " + ClickCounter.count(1));
            case SPRINT -> value(context, client, player.isSprinting() ? "Sprintet" : "Normal");
            case DIRECTION -> value(context, client, player.getHorizontalFacing().asString().toUpperCase(Locale.ROOT));
            case SPEED -> value(context, client, Math.round(player.getVelocity().horizontalLength() * 200) / 10.0 + " m/s");
            case MEMORY -> value(context, client, (RUNTIME.totalMemory() - RUNTIME.freeMemory()) / (1024 * 1024) + " MB");
            case COORDINATES -> {
                var pos = player.getBlockPos();
                value(context, client, "X " + pos.getX() + "  Y " + pos.getY() + "  Z " + pos.getZ());
            }
            case BIOME -> {
                String name = client.world == null ? "–" : client.world.getBiome(player.getBlockPos()).getKey()
                        .map(key -> key.getValue().getPath().replace('_', ' ')).orElse("–");
                value(context, client, client.textRenderer.trimToWidth(name, width - 14));
            }
            case ATTACK -> {
                int power = Math.round(player.getAttackCooldownProgress(0.0f) * 100);
                value(context, client, power + " %");
                int track = width - 14;
                context.fill(7, 25, 7 + track, 27, VeyloTheme.BORDER);
                context.fill(7, 25, 7 + power * track / 100, 27, VeyloTheme.ACCENT);
            }
            case EFFECTS -> {
                int y = 17;
                for (var effect : player.getStatusEffects()) {
                    if (y > 37) break;
                    String name = effect.getEffectType().value().getName().getString();
                    String duration = effect.isInfinite() ? "" : "  " + effect.getDuration() / 20 + "s";
                    label(context, client, client.textRenderer.trimToWidth(name + duration, width - 14),
                            7, y, VeyloTheme.TEXT);
                    y += 10;
                }
                if (y == 17) value(context, client, "Keine Effekte");
            }
            case KEYS -> {
                var keys = client.options;
                key(context, client, keys.forwardKey, 30, 16);
                key(context, client, keys.leftKey, 7, 33);
                key(context, client, keys.backKey, 30, 33);
                key(context, client, keys.rightKey, 53, 33);
            }
            case ARMOR -> {
                for (int i = 0; i < ARMOR.length; i++) {
                    int x = 7 + i * 21;
                    ItemStack item = player.getEquippedStack(ARMOR[i]);
                    VeyloTheme.rounded(context, x, 16, 17, 17, 0xff243746);
                    if (!item.isEmpty()) {
                        context.drawItem(item, x, 16);
                        context.drawStackOverlay(client.textRenderer, item, x, 16);
                    }
                }
            }
        }
        matrix.popMatrix();
    }

    private static int baseWidth(HudSettings.Module module) {
        return switch (module) {
            case COORDINATES -> 166;
            case ATTACK, EFFECTS -> 136;
            case CPS, BIOME -> 108;
            case ARMOR -> 94;
            default -> 88;
        };
    }

    private static int baseHeight(HudSettings.Module module) {
        return switch (module) {
            case KEYS, EFFECTS -> 54;
            case ARMOR -> 38;
            case ATTACK -> 31;
            default -> 28;
        };
    }

    private static void value(DrawContext context, MinecraftClient client, String value) {
        label(context, client, value, 7, 16, VeyloTheme.TEXT);
    }

    private static void key(DrawContext context, MinecraftClient client, KeyBinding binding, int x, int y) {
        VeyloTheme.rounded(context, x, y, 21, 15, binding.isPressed() ? 0xff247ca5 : 0xff263b4b);
        String label = client.textRenderer.trimToWidth(binding.getBoundKeyLocalizedText().getString(), 17);
        int width = client.textRenderer.getWidth(VeyloTheme.styled(label));
        label(context, client, label, x + (21 - width) / 2, y + 4, VeyloTheme.TEXT);
    }

    private static void label(DrawContext context, MinecraftClient client, String value, int x, int y, int color) {
        VeyloTheme.text(context, client.textRenderer, value, x, y, color);
    }
}
