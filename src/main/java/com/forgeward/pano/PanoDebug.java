package com.forgeward.pano;

import com.forgeward.pano.mixin.PauseScreenInvoker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.PauseScreen;

import java.util.List;

/**
 * Hooks for an external test or benchmark mod: read the capture and title-screen state, and do what a player's click
 * would. The mod itself never calls these. Client thread only.
 */
public final class PanoDebug {
    /** One complete capture: "world key/id", and whether it is a favourite. */
    public record Entry(String key, boolean favourite) { }

    /** A capture is running. */
    public static boolean capturing() { return Capture.capturing(); }
    /** The PNG writer has nothing pending. */
    public static boolean writerIdle() { return Capture.writerIdle(); }
    /** The pause menu's "Save this view" button; false if it could not start. */
    public static boolean saveView(PauseScreen pause) { return Capture.saveView(pause); }
    /** The pause menu's Disconnect, pressed at the vanilla click point (right after the next input poll). */
    public static void disconnect(PauseScreen pause) { Capture.afterEvents(() -> ((PauseScreenInvoker) pause).forgeward_pano$onDisconnect()); }
    /** The near terrain around the eye is loaded and drawn (a capture needs it). */
    public static boolean nearReady(Minecraft mc) { return Capture.nearReady(mc); }
    /** The near-terrain state as text. */
    public static String nearInfo(Minecraft mc) { return Capture.nearInfo(mc); }
    /** Seconds the last capture (or skip) added before the leave or the return to the pause menu. */
    public static double lastOverheadSeconds() { return Capture.lastOverheadNs / 1e9; }
    /** "world key/id" of the newest capture this session, or "". */
    public static String lastId() { return Capture.lastId; }
    /** Player renders hidden while capturing, this session. */
    public static int hiddenPlayerRenders() { return Capture.hiddenPlayer; }

    /** Every complete capture, newest first. */
    public static List<Entry> library() { return Library.all().stream().map(s -> new Entry(TitlePano.key(s), s.favourite())).toList(); }
    /** The title screen is loading, fading or has a panorama queued. */
    public static boolean titleBusy() { return TitlePano.busy(); }
    /** "world key/id" of the panorama on the title screen, or "" for the vanilla one. */
    public static String shownKey() { return TitlePano.shownKey(); }
    /** The title screen's star button. */
    public static void toggleStar() { TitlePano.toggleStar(); }
    /** The title screen's prev (-1) / next (+1) buttons. */
    public static void step(int d) { TitlePano.step(d); }

    private PanoDebug() { }
}
