package com.forgeward.pano.mixin;

import com.forgeward.pano.Capture;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The finished level frame (render: renderLevel, then tryTakeScreenshotIfNeeded), and no view bob / hurt tilt while capturing. */
@Mixin(value = GameRenderer.class, remap = false)
public abstract class GameRendererMixin {
    @Inject(method = "tryTakeScreenshotIfNeeded", at = @At("HEAD"), remap = false)
    private void forgeward_pano$frame(CallbackInfo ci) { Capture.frameRendered(); }

    @Inject(method = "bobView", at = @At("HEAD"), cancellable = true, remap = false)
    private void forgeward_pano$noBob(PoseStack pose, float pt, CallbackInfo ci) { if (Capture.capturing()) ci.cancel(); }

    @Inject(method = "bobHurt", at = @At("HEAD"), cancellable = true, remap = false)
    private void forgeward_pano$noHurt(PoseStack pose, float pt, CallbackInfo ci) { if (Capture.capturing()) ci.cancel(); }
}
