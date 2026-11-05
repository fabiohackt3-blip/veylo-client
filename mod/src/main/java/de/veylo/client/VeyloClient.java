package de.veylo.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.util.Identifier;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;

public final class VeyloClient implements ClientModInitializer {
    private static final Logger LOG = LoggerFactory.getLogger("Veylo");
    private final Path settingsPath = FabricLoader.getInstance().getConfigDir().resolve("veylo-hud.properties");
    private HudSettings settings = new HudSettings();

    @Override
    public void onInitializeClient() {
        try {
            settings = HudSettings.load(settingsPath);
        } catch (IOException | IllegalArgumentException error) {
            LOG.warn("Veylo HUD settings could not be loaded", error);
        }
        KeyBinding.Category category = KeyBinding.Category.create(Identifier.of("veylo", "client"));
        KeyBinding menu = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.veylo.menu", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_RIGHT_SHIFT, category));
        KeyBinding toggle = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.veylo.toggle_hud", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_F8, category));

        HudElementRegistry.attachElementBefore(VanillaHudElements.CHAT, Identifier.of("veylo", "hud"),
                (context, ticks) -> VeyloHud.render(context, settings));
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (menu.wasPressed()) {
                if (client.player != null && client.currentScreen == null) {
                    client.setScreen(new VeyloScreen(settings, this::save));
                }
            }
            while (toggle.wasPressed()) {
                if (client.player != null && client.currentScreen == null) {
                    settings.visible = !settings.visible;
                    save();
                }
            }
        });
    }

    private void save() {
        try {
            settings.save(settingsPath);
        } catch (IOException error) {
            LOG.warn("Veylo HUD settings could not be saved", error);
        }
    }
}
