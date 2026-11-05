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
            if (widget.enabled) draw(context, module, widget);
        });
    }

    static Bounds bounds(int screenWidth, int screenHeight, HudSettings.Module module, HudSettings.Widget widget) {
        int baseWidth = baseWidth(module);
        int baseHeight = baseHeight(module);
        float scale = Math.min(widget.percent / 100f, Math.min(
                (float) screenWidth / baseWidth, (float) screenHeight / baseHeight));
        int width = (int) Math.ceil(baseWidth * scale);
        int height = (int) Math.ceil(baseHeight * scale);
        int x = HudGeometry.position(widget.x, width, screenWidth, widget.right);
        int y = HudGeometry.position(widget.y, height, screenHeight, widget.bottom);
        return new Bounds(x, y, width, height, scale);
    }

    static void draw(DrawContext context, HudSettings.Module module, HudSettings.Widget widget) {
        MinecraftClient client = MinecraftClient.getInstance();
        var player = client.player;
        if (player == null) return;
        Bounds box = bounds(context.getScaledWindowWidth(), context.getScaledWindowHeight(), module, widget);
        int width = baseWidth(module);
        int height = baseHeight(module);
        int alpha = widget.opacity * 255 / 100;
        int accent = 0xff000000 | widget.accent;
        var matrix = context.getMatrices();
        matrix.pushMatrix();
        matrix.translate(box.x, box.y);
        matrix.scale(box.scale, box.scale);
        if (alpha != 0 && module != HudSettings.Module.KEYS) {
            VeyloTheme.rounded(context, 0, 0, width, height, alpha << 24 | 0x101820);
        }
        int labelWidth = switch (module) {
            case CPS -> 23;
            case DIRECTION -> 25;
            case ATTACK, EFFECTS, COORDINATES, KEYS, ARMOR -> 0;
            default -> client.textRenderer.getWidth(VeyloTheme.styled(module.label)) + 4;
        };
        if (labelWidth != 0) {
            String title = switch (module) {
                case CPS -> "CPS";
                case DIRECTION -> "Kurs";
                default -> module.label;
            };
            label(context, client, title, 5, 4, accent);
        }
        if (labelWidth != 0) context.enableScissor(5 + labelWidth, 0, width - 4, height);
        switch (module) {
            case FPS -> value(context, client, width, Integer.toString(client.getCurrentFps()));
            case PING -> value(context, client, width, ServerLatency.display(client));
            case CLOCK -> value(context, client, width, clock);
            case SESSION -> value(context, client, width, playtime);
            case CPS -> value(context, client, width, "L " + ClickCounter.count(0) + "  ·  R " + ClickCounter.count(1));
            case SPRINT -> value(context, client, width, player.isSprinting() ? "Sprintet" : "Normal");
            case DIRECTION -> value(context, client, width, player.getHorizontalFacing().asString().toUpperCase(Locale.ROOT));
            case SPEED -> value(context, client, width, Math.round(player.getVelocity().horizontalLength() * 200) / 10.0 + " m/s");
            case MEMORY -> value(context, client, width, (RUNTIME.totalMemory() - RUNTIME.freeMemory()) / (1024 * 1024) + " MB");
            case COORDINATES -> {
                var pos = player.getBlockPos();
                coordinate(context, client, "X", pos.getX(), 4, accent);
                coordinate(context, client, "Y", pos.getY(), 15, accent);
                coordinate(context, client, "Z", pos.getZ(), 26, accent);
            }
            case BIOME -> {
                String name = client.world == null ? "–" : client.world.getBiome(player.getBlockPos()).getKey()
                        .map(key -> key.getValue().getPath().replace('_', ' ')).orElse("–");
                value(context, client, width, client.textRenderer.trimToWidth(name, width - 14));
            }
            case ATTACK -> {
                int power = Math.round(player.getAttackCooldownProgress(0.0f) * 100);
                label(context, client, "Angriff", 5, 4, accent);
                value(context, client, width, power + " %");
                context.fill(5, 15, width - 5, 16, VeyloTheme.BORDER);
                context.fill(5, 15, 5 + power * (width - 10) / 100, 16, accent);
            }
            case EFFECTS -> {
                int y = 4;
                for (var effect : player.getStatusEffects()) {
                    if (y > 24) break;
                    String name = effect.getEffectType().value().getName().getString();
                    String duration = effect.isInfinite() ? "" : "  " + effect.getDuration() / 20 + "s";
                    label(context, client, client.textRenderer.trimToWidth(name + duration, width - 17),
                            11, y, VeyloTheme.TEXT);
                    context.fill(5, y + 3, 8, y + 5, accent);
                    y += 10;
                }
                if (y == 4) label(context, client, "Keine Effekte", 5, y, accent);
            }
            case KEYS -> {
                var keys = client.options;
                key(context, client, keys.forwardKey, 20, 0, alpha, accent);
                key(context, client, keys.leftKey, 0, 19, alpha, accent);
                key(context, client, keys.backKey, 20, 19, alpha, accent);
                key(context, client, keys.rightKey, 40, 19, alpha, accent);
            }
            case ARMOR -> {
                for (int i = 0; i < ARMOR.length; i++) {
                    int x = 5 + i * 18;
                    ItemStack item = player.getEquippedStack(ARMOR[i]);
                    if (!item.isEmpty()) {
                        context.drawItem(item, x, 2);
                        context.fill(x + 5, 18, x + 11, 19, accent);
                        context.drawStackOverlay(client.textRenderer, item, x, 2);
                    }
                }
            }
        }
        if (labelWidth != 0) context.disableScissor();
        matrix.popMatrix();
    }

    private static int baseWidth(HudSettings.Module module) {
        return switch (module) {
            case BIOME -> 132;
            case COORDINATES -> 82;
            case PING, EFFECTS -> 112;
            case KEYS -> 58;
            case ARMOR -> 80;
            case CPS, SPRINT, MEMORY -> 96;
            default -> 75;
        };
    }

    private static int baseHeight(HudSettings.Module module) {
        return switch (module) {
            case KEYS, EFFECTS, COORDINATES -> 37;
            case ARMOR, ATTACK -> 20;
            default -> 16;
        };
    }

    private static void value(DrawContext context, MinecraftClient client, int width, String value) {
        int textWidth = client.textRenderer.getWidth(VeyloTheme.styled(value));
        label(context, client, value, Math.max(5, width - 5 - textWidth), 4, VeyloTheme.TEXT);
    }

    private static void coordinate(DrawContext context, MinecraftClient client, String axis, int value, int y, int accent) {
        label(context, client, axis, 5, y, accent);
        label(context, client, Integer.toString(value), 17, y, VeyloTheme.TEXT);
    }

    private static void key(DrawContext context, MinecraftClient client, KeyBinding binding,
                            int x, int y, int alpha, int accent) {
        if (alpha != 0) {
            VeyloTheme.rounded(context, x, y, 18, 17, alpha << 24 | (binding.isPressed() ? 0x244452 : 0x101820));
        }
        String keyName = client.textRenderer.trimToWidth(binding.getBoundKeyLocalizedText().getString(), 14);
        int width = client.textRenderer.getWidth(VeyloTheme.styled(keyName));
        label(context, client, keyName, x + (18 - width) / 2, y + 5,
                binding.isPressed() ? VeyloTheme.TEXT : accent);
    }

    private static void label(DrawContext context, MinecraftClient client, String value, int x, int y, int color) {
        VeyloTheme.text(context, client.textRenderer, value, x, y, color);
    }
}
