package com.forgeward.pano.mixin;

import net.minecraft.client.gui.screens.PauseScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** PanoDebug.disconnect presses Disconnect through the real method (so it goes through PauseScreenMixin like a click). */
@Mixin(value = PauseScreen.class, remap = false)
public interface PauseScreenInvoker {
    @Invoker(value = "onDisconnect", remap = false) void forgeward_pano$onDisconnect();
}
