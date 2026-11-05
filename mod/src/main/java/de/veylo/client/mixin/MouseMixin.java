package de.veylo.client.mixin;

import de.veylo.client.ClickCounter;
import net.minecraft.client.Mouse;
import net.minecraft.client.input.MouseInput;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Mouse.class)
abstract class MouseMixin {
    @Inject(method = "onMouseButton", at = @At("HEAD"))
    private void veylo$countClick(long window, MouseInput input, int action, CallbackInfo callback) {
        if (action == GLFW.GLFW_PRESS) ClickCounter.press(input.button());
    }
}
