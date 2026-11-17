package com.forgeward.pano;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.Util;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.client.event.ViewportEvent;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Locale;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * The capture: six faces from the REAL frame at the player's eye (so Iris/Photon and Distant Horizons are in it), driven
 * per rendered frame, not per tick, so it takes ~1 s at 60 fps instead of ~3 s per face with a per-tick capture.
 *
 * Per face: the camera is turned (ViewportEvent.ComputeCameraAngles; the player entity is never rotated) first 1 degree
 * short of the face for 2 frames, then exactly onto it, with LevelRenderer.needsUpdate() at each turn (after a plain
 * instant turn to yaw 180 the terrain was not redrawn; the detour + terrain update fixes it). The face is grabbed after settleFrames exact frames once Sodium reports the terrain complete (or after 4x
 * settleFrames regardless). The grab is at the head of GameRenderer.tryTakeScreenshotIfNeeded: the finished level
 * frame, before entity outlines, post effects and the GUI. PNG work runs on a writer thread.
 *
 * FOV: the options FOV at 90 (fovEffectScale 0, no bob; the hand is off with hideGui). That is the VERTICAL FOV, so the centre H x H square of a
 * landscape window is exactly a 90 x 90 cube face; the window is never resized (an Iris pipeline rebuild).
 *
 * The capture never holds the player up: past budgetSeconds it is dropped (logged) and the leave goes on at once.
 */
public final class Capture {
    private static final float[] YAW = { 0f, 90f, 180f, -90f, 0f, 0f }, PITCH = { 0f, 0f, 0f, 0f, -90f, 90f };
    private static final int DETOUR_FRAMES = 2;
    private static final DateTimeFormatter ID = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss-SSS", Locale.ROOT);
    private static final ExecutorService WRITER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "forgeward_pano-writer"); t.setDaemon(true); return t;
    });

    static volatile boolean active;
    public static boolean capturing() { return active; }
    /** True while the deferred leave runs, so the hooks let it through. */
    static boolean bypass;
    private static boolean manual;
    private static Runnable onDone;
    private static long t0, faceT0, exactT0;
    /** A face whose terrain is still meshing waits up to this long on the exact view (24 frames can be only ~0.2 s). */
    private static final long TERRAIN_WAIT_NS = 400_000_000L;
    private static int face, frames;
    private static boolean exact;
    private static float baseYaw;
    private static final NativeImage[] shots = new NativeImage[6];
    private static final int[] faceFrames = new int[6];
    private static final boolean[] faceComplete = new boolean[6];
    private static boolean prevHideGui, prevBob;
    private static Integer prevFov;
    private static double prevFovEffect;
    private static CameraType prevCamera;
    private static String world, name;
    private static Properties info;
    private static Future<?> pending;
    private static int captured, skipped;

    // ---- hooks -----------------------------------------------------------------------------------------------------

    /**
     * Single-player leaves are NOT captured: the leave stays plain vanilla, no hook at all. In testing, every single-player
     * leave resumed from mod code (a task, a tick, or the click point after Window.updateDisplay) left the integrated
     * server running after "left the game", so Minecraft.disconnect waited for ever, even with the capture off.
     * "Save this view" still works in single player.
     */
    private static boolean singleplayer(String what) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.getSingleplayerServer() == null && !mc.isLocalServer()) return false;
        PanoMod.LOG.info("[forgeward_pano] leave ({}): single player, no capture (multiplayer only)", what);
        return true;
    }

    /**
     * capture.skipPorts: no leave capture on those servers. Matched on the PORT, not the host: the port of the live
     * connection (it covers an SRV record), or the one in the address as typed. "Save this view" does not come through here.
     */
    private static boolean skippedServer() {
        Minecraft mc = Minecraft.getInstance();
        int live = -1, typed = -1;
        if (mc.getConnection() != null && mc.getConnection().getConnection().getRemoteAddress() instanceof java.net.InetSocketAddress a) live = a.getPort();
        net.minecraft.client.multiplayer.ServerData sd = mc.getCurrentServer();
        if (sd != null) typed = net.minecraft.client.multiplayer.resolver.ServerAddress.parseString(sd.ip).getPort();
        for (Number p : PanoConfig.get(PanoConfig.SKIP_PORTS, PanoConfig.SKIP_PORTS_DEFAULT)) {
            if (p.intValue() == live || p.intValue() == typed) {
                PanoMod.LOG.info("[forgeward_pano] leave ({}, live port {}): port {} is on capture.skipPorts, no capture", sd != null ? sd.ip : "?", live, p.intValue());
                return true;
            }
        }
        return false;
    }

    /** PauseScreen.onDisconnect HEAD: true = the capture has started and will run {@code leave} itself. */
    public static boolean interceptLeave(Runnable leave) {
        if (bypass || active || !PanoConfig.get(PanoConfig.ON_LEAVE, true)) return false;
        if (singleplayer("Disconnect")) return false;
        if (skippedServer()) return false;
        return start(false, leave);
    }

    /** Camera.setup: the face being captured. */
    public static void angles(ViewportEvent.ComputeCameraAngles e) {
        if (!active) return;
        float yaw = baseYaw + YAW[face], pitch = PITCH[face];
        if (!exact) { if (face < 4) yaw -= 1f; else pitch = face == 4 ? -89f : 89f; }
        e.setYaw(yaw); e.setPitch(pitch); e.setRoll(0f);
    }

    /** world key / id of the newest capture (PanoDebug). */
    static volatile String lastId = "";

    /** GameRenderer.tryTakeScreenshotIfNeeded HEAD: one finished level frame. */
    public static void frameRendered() {
        if (!active) return;
        Minecraft mc = Minecraft.getInstance();
        if (overBudget(0)) { abort(String.format(Locale.ROOT, "budget %.1f s exceeded at face %d", budget(), face), true); return; }
        frames++;
        if (!exact) {
            if (frames >= DETOUR_FRAMES) { exact = true; frames = 0; exactT0 = Util.getNanos(); mc.levelRenderer.needsUpdate(); }
            return;
        }
        int settle = PanoConfig.get(PanoConfig.SETTLE_FRAMES, 20);
        boolean near = nearReady(mc), complete = near && mc.levelRenderer.hasRenderedAllSections();
        if (frames < settle || (!complete && Util.getNanos() - exactT0 < TERRAIN_WAIT_NS)) return;
        if (!near) { abort("the near terrain around the eye is not loaded/drawn (" + nearInfo(mc) + "); a far-only panorama is not kept", true); return; }
        faceFrames[face] = frames; faceComplete[face] = complete;
        shots[face] = Screenshot.takeScreenshot(mc.getMainRenderTarget());
        face++; frames = 0; exact = false; faceT0 = Util.getNanos();
        if (face < 6) { mc.levelRenderer.needsUpdate(); return; }
        finish();
    }

    /**
     * Right after a teleport the near chunks may not be there yet ("Waiting for chunk", 0 sections drawn), only Distant
     * Horizons' LODs, while Sodium's isTerrainRenderComplete() still says true (nothing to build). Near = the 3x3 chunks
     * around the eye loaded, the sections at the eye and under the feet built (Sodium: RenderSection.isBuilt), and at least
     * one section drawn last frame.
     */
    static boolean nearReady(Minecraft mc) {
        if (mc.level == null || mc.player == null) return false;
        net.minecraft.core.BlockPos eye = net.minecraft.core.BlockPos.containing(mc.player.getEyePosition());
        int cx = eye.getX() >> 4, cz = eye.getZ() >> 4;
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) if (!mc.level.hasChunk(cx + dx, cz + dz)) return false;
        return mc.levelRenderer.isSectionCompiled(eye) && mc.levelRenderer.isSectionCompiled(eye.below(2)) && mc.levelRenderer.countRenderedSections() > 0;
    }

    static String nearInfo(Minecraft mc) {
        if (mc.level == null || mc.player == null) return "no world";
        net.minecraft.core.BlockPos eye = net.minecraft.core.BlockPos.containing(mc.player.getEyePosition());
        int cx = eye.getX() >> 4, cz = eye.getZ() >> 4, loaded = 0;
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) if (mc.level.hasChunk(cx + dx, cz + dz)) loaded++;
        return "chunks " + loaded + "/9, eye section " + (mc.levelRenderer.isSectionCompiled(eye) ? "built" : "not built") + ", feet section "
                + (mc.levelRenderer.isSectionCompiled(eye.below(2)) ? "built" : "not built") + ", sections drawn " + mc.levelRenderer.countRenderedSections();
    }

    /** First-person body mods would draw the player's own body into the down face. Hidden while capturing. */
    public static void hidePlayer(net.neoforged.neoforge.client.event.RenderPlayerEvent.Pre e) {
        if (active && e.getEntity() == Minecraft.getInstance().player) { e.setCanceled(true); hiddenPlayer++; }
    }

    static int hiddenPlayer;
    /** Disconnect to the vanilla leave starting, the last capture (or skip): our own share of a leave. */
    static volatile long lastOverheadNs;

    /** Ticks still run when frames don't: the world went away, or no frame came within the budget. */
    public static void tick(ClientTickEvent.Post e) {
        if (!active) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) { abort("the world went away", false); return; }
        if (overBudget(1.0)) abort("no frames for the budget", true);
    }

    /** The pause menu's small "Save this view" (bottom-left; only the full pause menu, not F3+Esc). */
    public static void pauseInit(ScreenEvent.Init.Post e) {
        if (!(e.getScreen() instanceof PauseScreen pause) || !pause.showsPauseMenu() || !PanoConfig.get(PanoConfig.PAUSE_BUTTON, true)) return;
        e.addListener(Button.builder(Component.translatable("forgeward_pano.save_view"), b -> {
                    saveView(pause);
                })
                .bounds(4, pause.height - 24, 96, 20)
                .tooltip(Tooltip.create(Component.translatable("forgeward_pano.save_view.tip")))
                .build());
    }

    /** "Save this view": a capture now, kept as a favourite, then back to the pause menu. */
    static boolean saveView(PauseScreen pause) {
        return !active && start(true, () -> Minecraft.getInstance().setScreen(pause));
    }

    static boolean writerIdle() { Future<?> f = pending; return f == null || f.isDone(); }

    // ---- the capture ---------------------------------------------------------------------------------------------

    private static boolean start(boolean manualSave, Runnable done) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (mc.level == null || p == null) return false;
        if (mc.getWindow().getWidth() < mc.getWindow().getHeight()) {
            PanoMod.LOG.info("[forgeward_pano] capture skipped: the window is portrait ({}x{}), a 90-degree face would not fit", mc.getWindow().getWidth(), mc.getWindow().getHeight());
            return false;
        }
        try {
            world = Library.worldKey(mc); name = Library.worldName(mc);
            info = new Properties();
            info.setProperty("name", name);
            info.setProperty("dimension", mc.level.dimension().location().toString());
            info.setProperty("pos", String.format(Locale.ROOT, "%.2f %.2f %.2f", p.getX(), p.getEyeY(), p.getZ()));
            info.setProperty("yaw", String.format(Locale.ROOT, "%.1f", p.getYRot()));
            info.setProperty("dayTime", Long.toString(mc.level.getDayTime()));
            info.setProperty("trigger", manualSave ? "pause menu" : "leave");
        } catch (Exception ex) {
            PanoMod.LOG.warn("[forgeward_pano] capture skipped: {}", ex.toString());
            return false;
        }
        manual = manualSave; onDone = done;
        baseYaw = p.getViewYRot(1f);
        face = 0; frames = 0; exact = false; Arrays.fill(faceFrames, 0); Arrays.fill(faceComplete, false);
        prevHideGui = mc.options.hideGui; prevCamera = mc.options.getCameraType();
        mc.options.hideGui = true;
        mc.options.setCameraType(CameraType.FIRST_PERSON);
        // The FOV OPTION at 90, not GameRenderer.panoramicMode: under shaders + Distant Horizons the panoramic faces lost the
        // near ground (DH's pass follows the options FOV, e.g. 70, so its depth no longer matched the 90-degree frame).
        // Restored exactly in restore().
        prevFov = mc.options.fov().get(); prevFovEffect = mc.options.fovEffectScale().get(); prevBob = mc.options.bobView().get();
        mc.options.fov().set(90); mc.options.fovEffectScale().set(0.0); mc.options.bobView().set(false);
        active = true;
        t0 = faceT0 = Util.getNanos();
        mc.setScreen(new CaptureScreen(manualSave ? Component.translatable("forgeward_pano.saving_view")
                : Component.translatable(mc.isLocalServer() ? "menu.savingLevel" : "forgeward_pano.leaving")));
        mc.levelRenderer.needsUpdate();
        return true;
    }

    private static void finish() {
        Minecraft mc = Minecraft.getInstance();
        lastOverheadNs = Util.getNanos() - t0;
        double secs = (Util.getNanos() - t0) / 1e9;
        restore();
        NativeImage[] frames6 = shots.clone(); Arrays.fill(shots, null);
        String id = LocalDateTime.now().format(ID), w = world;
        lastId = w + "/" + id;
        int size = PanoConfig.get(PanoConfig.FACE_SIZE, 1024);
        Properties inf = info; boolean fav = manual;
        StringBuilder partial = new StringBuilder();
        for (int i = 0; i < 6; i++) if (!faceComplete[i]) partial.append(partial.length() == 0 ? "" : ",").append(i);
        if (partial.length() > 0) inf.setProperty("sectionsBuilding", "faces " + partial + " (far sections; the near terrain was drawn)");
        captured++;
        PanoMod.LOG.info(String.format(Locale.ROOT, "[forgeward_pano] captured %s/%s (%s): 6 faces, %dx%d frames cropped to %d px, %.2f s, frames per face %s, terrain complete %s%s",
                w, id, inf.getProperty("trigger"), frames6[0].getWidth(), frames6[0].getHeight(), Math.min(size, frames6[0].getHeight()), secs,
                Arrays.toString(faceFrames), Arrays.toString(faceComplete), partial.length() > 0 ? ", far sections still building on faces " + partial + " (near terrain drawn: not partial)" : ""));
        pending = WRITER.submit(() -> {
            long tw = System.nanoTime();
            try {
                Library.Shot s = Library.write(w, id, frames6, size, inf, fav);
                PanoMod.LOG.info(String.format(Locale.ROOT, "[forgeward_pano] saved %s (%.2f s on the writer thread)%s", s.dir(), (System.nanoTime() - tw) / 1e9, fav ? ", favourite" : ""));
                TitlePano.captured(s);
            } catch (Throwable ex) {
                PanoMod.LOG.warn("[forgeward_pano] could not save capture {}: {}", id, ex.toString());
            }
        });
        runDone();
    }

    private static void abort(String why, boolean proceed) {
        lastOverheadNs = Util.getNanos() - t0;
        skipped++;
        PanoMod.LOG.warn(String.format(Locale.ROOT, "[forgeward_pano] capture skipped: %s (%.2f s, %d of 6 faces)", why, (Util.getNanos() - t0) / 1e9, face));
        restore();
        for (int i = 0; i < 6; i++) if (shots[i] != null) { shots[i].close(); shots[i] = null; }
        if (proceed) runDone(); else onDone = null;
    }

    private static void restore() {
        Minecraft mc = Minecraft.getInstance();
        active = false;
        mc.options.hideGui = prevHideGui;
        if (prevCamera != null) mc.options.setCameraType(prevCamera);
        try { if (prevFov != null) { mc.options.fov().set(prevFov); mc.options.fovEffectScale().set(prevFovEffect); mc.options.bobView().set(prevBob); } }
        catch (RuntimeException ex) { PanoMod.LOG.warn("[forgeward_pano] could not restore the FOV options: {}", ex.toString()); }
        prevFov = null;
    }

    /**
     * The continuation (the real leave, or back to the pause menu) runs where vanilla runs a button click: right after
     * Window.updateDisplay() polled the input in Minecraft.runTick (MinecraftMixin), never from a task or a client tick.
     * (Single-player leaves no longer come here at all: see singleplayer().)
     */
    private static void runDone() {
        Runnable d = onDone; onDone = null;
        if (d == null) return;
        afterEvents(() -> { bypass = true; try { d.run(); } finally { bypass = false; } });
    }

    private static Runnable queued;

    /** Run once, right after the next input poll (the vanilla button-click point). */
    static void afterEvents(Runnable r) { queued = r; }

    /** MinecraftMixin: runTick, after Window.updateDisplay(). */
    public static void eventsPolled() {
        Runnable r = queued; queued = null;
        if (r != null) r.run();
    }


    static void waitForWriter(long ms) {
        Future<?> f = pending;
        if (f == null) return;
        try { f.get(ms, TimeUnit.MILLISECONDS); } catch (Exception ex) { PanoMod.LOG.warn("[forgeward_pano] writer not done in {} ms at exit: {}", ms, ex.toString()); }
    }

    private static double budget() { return PanoConfig.get(PanoConfig.BUDGET, 6.0); }
    private static boolean overBudget(double extra) { return Util.getNanos() - t0 > (long) ((budget() + extra) * 1e9); }

    static String counts() { return captured + " captured, " + skipped + " skipped"; }

    /** Opaque, like vanilla's "Saving world" screen: the spinning camera is never seen. */
    static final class CaptureScreen extends Screen {
        CaptureScreen(Component title) { super(title); }
        @Override public void renderBackground(GuiGraphics g, int mx, int my, float pt) {
            g.fill(0, 0, width, height, 0xFF000000);
            renderMenuBackground(g);
        }
        @Override public void render(GuiGraphics g, int mx, int my, float pt) {
            super.render(g, mx, my, pt);
            g.drawCenteredString(font, title, width / 2, height / 2 - 4, 0xFFFFFF);
        }
        @Override public boolean shouldCloseOnEsc() { return false; }
    }

    private Capture() { }
}
