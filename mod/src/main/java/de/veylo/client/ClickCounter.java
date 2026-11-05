package de.veylo.client;

import net.minecraft.client.MinecraftClient;
import org.lwjgl.glfw.GLFW;

public final class ClickCounter {
    private static final long WINDOW = 1_000_000_000L;
    private static final long[][] samples = {new long[64], new long[64]};
    private static final int[] next = new int[2];

    private ClickCounter() {}

    public static void press(int button) {
        if (button != GLFW.GLFW_MOUSE_BUTTON_LEFT && button != GLFW.GLFW_MOUSE_BUTTON_RIGHT) return;
        var client = MinecraftClient.getInstance();
        if (client.player == null || client.currentScreen != null) return;
        int side = button == GLFW.GLFW_MOUSE_BUTTON_LEFT ? 0 : 1;
        samples[side][next[side]] = System.nanoTime();
        next[side] = (next[side] + 1) % samples[side].length;
    }

    static int count(int side) {
        long now = System.nanoTime();
        int clicks = 0;
        for (long time : samples[side]) if (time != 0 && now - time < WINDOW) clicks++;
        return clicks;
    }
}
