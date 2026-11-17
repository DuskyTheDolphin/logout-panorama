package com.forgeward.pano.mixin;

import com.forgeward.pano.Capture;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The deferred leave runs right after runTick's Window.updateDisplay() (javap: the one call; GLFW input callbacks, i.e. a
 * real button press, fire inside it), before the pause flag is recomputed: the vanilla click point.
 * (No capture on closing the window: faces taken during shutdown came out torn.)
 */
@Mixin(value = Minecraft.class, remap = false)
public abstract class MinecraftMixin {
    @Inject(method = "runTick", remap = false,
            at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/platform/Window;updateDisplay()V", shift = At.Shift.AFTER, remap = false))
    private void forgeward_pano$afterEvents(boolean renderLevel, CallbackInfo ci) {
        Capture.eventsPolled();
    }
}
