package com.forgeward.pano;

import com.mojang.logging.LogUtils;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.client.event.ViewportEvent;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;

/**
 * Logout panorama (client only): a panorama taken when you leave a server, shown on the title screen, with a library and
 * favourites. Capture: {@link Capture}; storage: {@link Library}; title screen: {@link TitlePano}.
 * Hook points read in the shipped bytecode (javap, 21.1.250 / 1.21.1): GameRenderer.render calls renderLevel and then
 * tryTakeScreenshotIfNeeded (vanilla's world-icon grab) before outlines, post effects and the GUI; Camera.setup posts
 * ViewportEvent.ComputeCameraAngles and applies its yaw/pitch; CubeMap.render binds images[face] through
 * RenderSystem.setShaderTexture(0, ResourceLocation); Sodium 0.8.12 overrides LevelRenderer.hasRenderedAllSections
 * (isTerrainRenderComplete) and needsUpdate (scheduleTerrainUpdate).
 */
@Mod(value = PanoMod.MODID, dist = Dist.CLIENT)
public final class PanoMod {
    public static final String MODID = "forgeward_pano";
    public static final String VERSION = "1.0.9";
    public static final Logger LOG = LogUtils.getLogger();

    public PanoMod(IEventBus modBus, ModContainer container) {
        container.registerConfig(ModConfig.Type.CLIENT, PanoConfig.SPEC);
        container.registerExtensionPoint(IConfigScreenFactory.class, ConfigurationScreen::new);
        NeoForge.EVENT_BUS.addListener(ViewportEvent.ComputeCameraAngles.class, Capture::angles);
        NeoForge.EVENT_BUS.addListener(ClientTickEvent.Post.class, Capture::tick);
        NeoForge.EVENT_BUS.addListener(net.neoforged.neoforge.client.event.RenderPlayerEvent.Pre.class, Capture::hidePlayer);
        NeoForge.EVENT_BUS.addListener(ScreenEvent.Init.Post.class, Capture::pauseInit);
        NeoForge.EVENT_BUS.addListener(ScreenEvent.Init.Post.class, TitlePano::screenInit);
    }
}
