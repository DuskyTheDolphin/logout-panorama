package com.forgeward.pano.mixin;

import com.forgeward.pano.Capture;
import net.minecraft.client.gui.screens.PauseScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Disconnect / Save and Quit: capture first, then the same onDisconnect runs (Capture.bypass lets it through). */
@Mixin(value = PauseScreen.class, remap = false)
public abstract class PauseScreenMixin {
    @Shadow(remap = false) private void onDisconnect() { }

    @Inject(method = "onDisconnect", at = @At("HEAD"), cancellable = true, remap = false)
    private void forgeward_pano$captureFirst(CallbackInfo ci) {
        if (Capture.interceptLeave(this::onDisconnect)) ci.cancel();
    }
}
