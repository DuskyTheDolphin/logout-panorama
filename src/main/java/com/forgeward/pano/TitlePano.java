package com.forgeward.pano;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.ChatFormatting;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.renderer.CubeMap;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.client.event.ScreenEvent;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;

/**
 * The title-screen panorama. Vanilla's menu CubeMap (Screen.CUBE_MAP, "textures/gui/title/background/panorama") binds each
 * face by ResourceLocation; CubeMapMixin swaps those for one of two texture slots of ours. A crossfade draws the cube a
 * second time from the other slot at alpha x t, then the slots swap. Every menu without a world shows it (title, options,
 * server list), the same as vanilla's panorama.
 */
public final class TitlePano {
    private static final String TITLE_PATH = "textures/gui/title/background/panorama_0.png";
    private static final ResourceLocation[][] SLOTS = new ResourceLocation[2][6];
    static {
        for (int s = 0; s < 2; s++) for (int i = 0; i < 6; i++) SLOTS[s][i] = ResourceLocation.fromNamespaceAndPath(PanoMod.MODID, "dynamic/" + (char) ('a' + s) + "_" + i);
    }

    private static int curSlot = -1, nextSlot = -1;
    private static Library.Shot shown, incoming, wanted;
    private static volatile Library.Shot latest;
    private static boolean loading, nested, firstInit = true;
    private static long fadeStart, shownAt;
    private static Button star;

    // ---- CubeMapMixin ----------------------------------------------------------------------------------------------

    static boolean isTitle(ResourceLocation[] images) {
        return images.length == 6 && images[0] != null && TITLE_PATH.equals(images[0].getPath()) && "minecraft".equals(images[0].getNamespace());
    }

    /** The texture to bind instead of a vanilla face. */
    public static ResourceLocation face(ResourceLocation[] images, ResourceLocation loc) {
        int slot = nested ? nextSlot : curSlot;
        if (slot < 0 || !isTitle(images)) return loc;
        for (int i = 0; i < 6; i++) if (images[i].equals(loc)) return SLOTS[slot][i];
        return loc;
    }

    /** CubeMap.render HEAD: end a finished fade, step the cycle. */
    public static void beforeRender(ResourceLocation[] images) {
        if (nested || !isTitle(images)) return;
        if (nextSlot >= 0 && fadeT() >= 1f) swap();
        PanoConfig.TitleMode mode = PanoConfig.get(PanoConfig.MODE, PanoConfig.TitleMode.LATEST);
        // LATEST: the newest finished capture wins over anything queued (else an earlier "Save this view" could be shown instead)
        Library.Shot l = latest;
        if (l != null && mode == PanoConfig.TitleMode.LATEST) {
            if (shown != null && key(shown).equals(key(l))) latest = null;
            else if (!loading && nextSlot < 0) { latest = null; wanted = null; show(l); }
            else wanted = l;
        }
        double cycle = PanoConfig.get(PanoConfig.CYCLE_SECONDS, 30.0);
        if (mode == PanoConfig.TitleMode.CYCLE_FAVOURITES && cycle > 0 && nextSlot < 0 && !loading && shown != null
                && Util.getMillis() - shownAt > (long) (cycle * 1000)) step(1);
    }

    /** CubeMap.render RETURN: the incoming panorama over the shown one. */
    public static void afterRender(CubeMap cube, ResourceLocation[] images, Minecraft mc, float pitch, float yaw, float alpha) {
        if (nested || nextSlot < 0 || !isTitle(images)) return;
        nested = true;
        try { cube.render(mc, pitch, yaw, alpha * fadeT()); } finally { nested = false; }
    }

    private static float fadeT() {
        double fade = PanoConfig.get(PanoConfig.FADE_SECONDS, 2.0);
        if (fade <= 0) return 1f;
        return (float) Math.min(1.0, (Util.getMillis() - fadeStart) / (fade * 1000));
    }

    private static void swap() {
        int old = curSlot;
        curSlot = nextSlot; nextSlot = -1; shown = incoming; incoming = null; shownAt = Util.getMillis();
        if (old >= 0 && old != curSlot) for (ResourceLocation l : SLOTS[old]) Minecraft.getInstance().getTextureManager().release(l);
        PanoMod.LOG.info("[forgeward_pano] title: now showing {}", shown == null ? "vanilla" : key(shown));
        updateStar();
        if (wanted != null && !wanted.equals(shown)) { Library.Shot w = wanted; wanted = null; show(w); } else wanted = null;
    }

    // ---- choosing ------------------------------------------------------------------------------------------------

    public static void screenInit(ScreenEvent.Init.Post e) {
        if (!(e.getScreen() instanceof TitleScreen screen)) return;
        PanoConfig.TitleMode mode = PanoConfig.get(PanoConfig.MODE, PanoConfig.TitleMode.LATEST);
        if (firstInit) {
            firstInit = false;
            List<Library.Shot> all = Library.all();
            Library.Shot pick = initial(mode, all);
            long favs = all.stream().filter(Library.Shot::favourite).count(), worlds = all.stream().map(Library.Shot::world).distinct().count();
            PanoMod.LOG.info(String.format(Locale.ROOT, "[forgeward_pano] %s ready: capture on leave %s (face %d px, budget %.1f s, %d frames per face), pause button %s, leave capture skipped on ports %s; library %d captures in %d worlds, %d favourites (keep %d per world); title mode %s, showing %s",
                    PanoMod.VERSION, PanoConfig.get(PanoConfig.ON_LEAVE, true) ? "ON" : "off", PanoConfig.get(PanoConfig.FACE_SIZE, 1024), PanoConfig.get(PanoConfig.BUDGET, 6.0),
                    PanoConfig.get(PanoConfig.SETTLE_FRAMES, 20), PanoConfig.get(PanoConfig.PAUSE_BUTTON, true) ? "ON" : "off", PanoConfig.get(PanoConfig.SKIP_PORTS, PanoConfig.SKIP_PORTS_DEFAULT), all.size(), worlds, favs,
                    PanoConfig.get(PanoConfig.KEEP_PER_WORLD, 10), mode, pick == null ? "the vanilla panorama" : pick.world() + "/" + pick.id()));
            if (pick != null) show(pick);
        } else if (mode == PanoConfig.TitleMode.VANILLA && (shown != null || incoming != null)) {
            toVanilla();
        }
        star = null;
        if (mode == PanoConfig.TitleMode.VANILLA || !PanoConfig.get(PanoConfig.CONTROLS, true) || (shown == null && incoming == null && wanted == null && latest == null && Library.all().isEmpty())) return;
        int s = 16, gap = 2, x = screen.width - 4 - 3 * s - 2 * gap, y = 4;
        e.addListener(Button.builder(Component.literal("<"), b -> step(-1)).bounds(x, y, s, s).tooltip(Tooltip.create(Component.translatable("forgeward_pano.prev"))).build());
        star = Button.builder(Component.literal("*"), b -> toggleStar()).bounds(x + s + gap, y, s, s).build();
        e.addListener(star);
        e.addListener(Button.builder(Component.literal(">"), b -> step(1)).bounds(x + 2 * (s + gap), y, s, s).tooltip(Tooltip.create(Component.translatable("forgeward_pano.next"))).build());
        updateStar();
    }

    private static Library.Shot initial(PanoConfig.TitleMode mode, List<Library.Shot> all) {
        if (all.isEmpty() || mode == PanoConfig.TitleMode.VANILLA) return null;
        List<Library.Shot> favs = all.stream().filter(Library.Shot::favourite).toList();
        if (favs.isEmpty() || mode == PanoConfig.TitleMode.LATEST) return all.get(0);
        if (mode == PanoConfig.TitleMode.RANDOM_FAVOURITE) return favs.get((int) (Math.random() * favs.size()));
        // CYCLE_FAVOURITES: the favourite after the one shown last launch
        Properties st = state();
        String last = st.getProperty("cycle.last", "");
        int i = 0;
        for (int k = 0; k < favs.size(); k++) if (key(favs.get(k)).equals(last)) { i = (k + 1) % favs.size(); break; }
        st.setProperty("cycle.last", key(favs.get(i))); saveState(st);
        return favs.get(i);
    }

    /** Writer thread: a capture is saved. Nothing loads while in the world; the next menu render picks it up (beforeRender). */
    static void captured(Library.Shot s) { latest = s; }

    private static List<Library.Shot> pool() {
        List<Library.Shot> all = Library.all();
        PanoConfig.TitleMode mode = PanoConfig.get(PanoConfig.MODE, PanoConfig.TitleMode.LATEST);
        if (mode == PanoConfig.TitleMode.CYCLE_FAVOURITES || mode == PanoConfig.TitleMode.RANDOM_FAVOURITE) {
            List<Library.Shot> favs = all.stream().filter(Library.Shot::favourite).toList();
            if (!favs.isEmpty()) return favs;
        }
        return all;
    }

    static void step(int d) {
        List<Library.Shot> pool = pool();
        if (pool.isEmpty()) return;
        Library.Shot at = wanted != null ? wanted : incoming != null ? incoming : shown;
        int i = -1;
        for (int k = 0; k < pool.size(); k++) if (at != null && key(pool.get(k)).equals(key(at))) i = k;
        Library.Shot next = pool.get(Math.floorMod(i + d, pool.size()));
        if (at != null && key(next).equals(key(at))) { shownAt = Util.getMillis(); return; }
        show(next);
        if (PanoConfig.get(PanoConfig.MODE, PanoConfig.TitleMode.LATEST) == PanoConfig.TitleMode.CYCLE_FAVOURITES) {
            Properties st = state(); st.setProperty("cycle.last", key(next)); saveState(st);
        }
    }

    static void toggleStar() {
        Library.Shot s = shown;
        if (s == null) return;
        Library.setFavourite(s, !s.favourite());
        shown = new Library.Shot(s.dir(), s.world(), s.id(), s.name(), !s.favourite());
        PanoMod.LOG.info("[forgeward_pano] {} {}/{}", shown.favourite() ? "starred" : "unstarred", s.world(), s.id());
        updateStar();
    }

    private static void updateStar() {
        if (star == null) return;
        Library.Shot s = shown;
        star.active = s != null;
        boolean fav = s != null && s.favourite();
        star.setMessage(Component.literal(fav ? "★" : "☆").withStyle(fav ? ChatFormatting.GOLD : ChatFormatting.WHITE));
        if (s != null) star.setTooltip(Tooltip.create(Component.translatable(fav ? "forgeward_pano.star.on" : "forgeward_pano.star.off", s.label())));
    }

    static String shownKey() { return shown == null ? "" : key(shown); }
    static boolean busy() { return loading || nextSlot >= 0 || wanted != null || latest != null; }

    static String key(Library.Shot s) { return s.world() + "/" + s.id(); }

    // ---- loading -------------------------------------------------------------------------------------------------

    private static void toVanilla() {
        for (ResourceLocation[] slot : SLOTS) for (ResourceLocation l : slot) Minecraft.getInstance().getTextureManager().release(l);
        curSlot = -1; nextSlot = -1; shown = incoming = wanted = null;
    }

    /** Load the six PNGs off-thread, upload into the free slot, start the fade. A request while loading is queued. */
    static void show(Library.Shot s) {
        if (s == null) return;
        if (loading || nextSlot >= 0) { wanted = s; return; }
        if (shown != null && key(shown).equals(key(s))) return;
        loading = true;
        Minecraft mc = Minecraft.getInstance();
        CompletableFuture.supplyAsync(() -> read(s), Util.backgroundExecutor()).thenAcceptAsync(imgs -> {
            loading = false;
            if (imgs == null) { next(); return; }
            int slot = curSlot == 0 ? 1 : 0;
            for (int i = 0; i < 6; i++) {
                mc.getTextureManager().release(SLOTS[slot][i]);
                PanoTexture t = new PanoTexture(imgs[i]);
                mc.getTextureManager().register(SLOTS[slot][i], t);
                t.upload();
            }
            nextSlot = slot; incoming = s; fadeStart = Util.getMillis();
        }, mc);
    }

    private static void next() {
        if (wanted != null) { Library.Shot w = wanted; wanted = null; show(w); }
    }

    private static NativeImage[] read(Library.Shot s) {
        NativeImage[] out = new NativeImage[6];
        try {
            for (int i = 0; i < 6; i++) try (InputStream in = Files.newInputStream(s.face(i))) { out[i] = NativeImage.read(in); }
            return out;
        } catch (IOException ex) {
            for (NativeImage n : out) if (n != null) n.close();
            PanoMod.LOG.warn("[forgeward_pano] could not load {}: {}", s.dir(), ex.toString());
            return null;
        }
    }

    private static Path statePath() { return Library.root().resolve("state.properties"); }

    private static Properties state() {
        Properties p = new Properties();
        try (var r = Files.newBufferedReader(statePath(), StandardCharsets.UTF_8)) { p.load(r); } catch (IOException ignored) { }
        return p;
    }

    private static void saveState(Properties p) {
        try { Files.createDirectories(statePath().getParent()); try (var w = Files.newBufferedWriter(statePath(), StandardCharsets.UTF_8)) { p.store(w, "forgeward_pano"); } }
        catch (IOException ex) { PanoMod.LOG.warn("[forgeward_pano] could not save {}: {}", statePath(), ex.toString()); }
    }

    private TitlePano() { }
}
