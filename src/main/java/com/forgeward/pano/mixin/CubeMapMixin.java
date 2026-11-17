package com.forgeward.pano.mixin;

import com.forgeward.pano.TitlePano;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.CubeMap;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The menu panorama's faces come from TitlePano (javap: render binds images[face] via setShaderTexture(0, RL), once per face). */
@Mixin(value = CubeMap.class, remap = false)
public abstract class CubeMapMixin {
    @Shadow(remap = false) @Final private ResourceLocation[] images;

    @Inject(method = "render", at = @At("HEAD"), remap = false)
    private void forgeward_pano$before(Minecraft mc, float pitch, float yaw, float alpha, CallbackInfo ci) { TitlePano.beforeRender(images); }

    @Redirect(method = "render", remap = false,
            at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/systems/RenderSystem;setShaderTexture(ILnet/minecraft/resources/ResourceLocation;)V", remap = false))
    private void forgeward_pano$face(int unit, ResourceLocation loc) { RenderSystem.setShaderTexture(unit, TitlePano.face(images, loc)); }

    @Inject(method = "render", at = @At("RETURN"), remap = false)
    private void forgeward_pano$after(Minecraft mc, float pitch, float yaw, float alpha, CallbackInfo ci) {
        TitlePano.afterRender((CubeMap) (Object) this, images, mc, pitch, yaw, alpha);
    }
}
