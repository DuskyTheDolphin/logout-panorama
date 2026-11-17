package com.forgeward.pano;

import net.neoforged.neoforge.common.ModConfigSpec;

import java.util.List;

/** CLIENT config (config/forgeward_pano-client.toml). Every read goes through {@link #get} (a read before load throws). */
public final class PanoConfig {
    public enum TitleMode { LATEST, CYCLE_FAVOURITES, RANDOM_FAVOURITE, VANILLA }

    static final ModConfigSpec SPEC;
    public static final ModConfigSpec.BooleanValue ON_LEAVE, PAUSE_BUTTON, CONTROLS;
    public static final ModConfigSpec.IntValue FACE_SIZE, SETTLE_FRAMES, KEEP_PER_WORLD;
    public static final ModConfigSpec.DoubleValue BUDGET, CYCLE_SECONDS, FADE_SECONDS;
    public static final ModConfigSpec.EnumValue<TitleMode> MODE;
    static final ModConfigSpec.ConfigValue<List<? extends Integer>> SKIP_PORTS;
    static final List<Integer> SKIP_PORTS_DEFAULT = List.of();

    static {
        ModConfigSpec.Builder b = new ModConfigSpec.Builder();
        b.push("capture");
        ON_LEAVE = b.comment("Take a panorama from your eye when you leave a SERVER (single-player leaves are never captured).").define("onLeave", true);
        SKIP_PORTS = b.comment("Server ports whose leave is not captured, whatever host you connect through (matched on the port). \"Save this view\" still works there.")
                .defineListAllowEmpty("skipPorts", SKIP_PORTS_DEFAULT, () -> 25565, o -> o instanceof Number n && n.intValue() > 0 && n.intValue() <= 65535);
        FACE_SIZE = b.comment("Size of each of the 6 faces in pixels (never more than the window height).").defineInRange("faceSize", 1024, 256, 4096);
        BUDGET = b.comment("Most time a capture may take; past it the capture is skipped (logged) and you leave at once. About 4.4 s at ~70 fps with shaders and Distant Horizons.").defineInRange("budgetSeconds", 6.0, 0.5, 15.0);
        SETTLE_FRAMES = b.comment("Frames drawn per face before it is grabbed (lets shader smoothing settle; 20 was clean with Photon + DH).").defineInRange("settleFrames", 20, 2, 120);
        PAUSE_BUTTON = b.comment("A small \"Save this view\" button in the pause menu (kept as a favourite).").define("pauseMenuButton", true);
        b.pop();
        b.push("library");
        KEEP_PER_WORLD = b.comment("Captures kept per world/server, newest first. Favourites are never deleted.").defineInRange("keepPerWorld", 10, 1, 200);
        b.pop();
        b.push("titleScreen");
        MODE = b.comment("LATEST: your newest capture. CYCLE_FAVOURITES: step through favourites. RANDOM_FAVOURITE: one per launch. VANILLA: the normal title panorama.")
                .defineEnum("mode", TitleMode.LATEST);
        CYCLE_SECONDS = b.comment("CYCLE_FAVOURITES: seconds per panorama; 0 = the next one each launch.").defineInRange("cycleSeconds", 30.0, 0.0, 3600.0);
        FADE_SECONDS = b.comment("Crossfade between panoramas, seconds.").defineInRange("crossfadeSeconds", 2.0, 0.0, 10.0);
        CONTROLS = b.comment("Small prev / star / next buttons in the top-right corner of the title screen.").define("controls", true);
        b.pop();
        SPEC = b.build();
    }

    static <T> T get(ModConfigSpec.ConfigValue<T> v, T fallback) {
        try { return SPEC.isLoaded() ? v.get() : fallback; } catch (IllegalStateException e) { return fallback; }
    }

    private PanoConfig() { }
}
