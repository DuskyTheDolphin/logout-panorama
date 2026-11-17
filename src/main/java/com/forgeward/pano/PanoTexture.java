package com.forgeward.pano;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.platform.TextureUtil;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.server.packs.resources.ResourceManager;

/** One panorama face: linear filtered and CLAMPED (a repeating edge would bleed the opposite edge into the cube seams). */
final class PanoTexture extends AbstractTexture {
    private NativeImage image;

    PanoTexture(NativeImage image) { this.image = image; }

    @Override public void load(ResourceManager rm) { }

    /** Render thread, after TextureManager.register. Frees the image. */
    void upload() {
        RenderSystem.assertOnRenderThread();
        NativeImage img = image; image = null;
        if (img == null) return;
        TextureUtil.prepareImage(getId(), img.getWidth(), img.getHeight());
        img.upload(0, 0, 0, 0, 0, img.getWidth(), img.getHeight(), true, true, false, true);
    }

    @Override public void close() {
        if (image != null) { image.close(); image = null; }
        super.close();
    }
}
