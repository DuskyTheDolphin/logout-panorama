package com.forgeward.pano;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Properties;
import java.util.stream.Stream;

/**
 * The capture library, plain files in the game folder (never sent anywhere):
 *   forgeward_pano/&lt;world key&gt;/&lt;id&gt;/0.png .. 5.png  (vanilla panorama order: yaw +0, +90, +180, -90, up, down)
 *                                     info.properties      (world name, spot, time)
 *                                     favourite            (marker: never pruned)
 * A capture is written to &lt;id&gt;.part and renamed when complete, so a half-written one is never listed.
 */
final class Library {
    record Shot(Path dir, String world, String id, String name, boolean favourite) {
        Path face(int i) { return dir.resolve(i + ".png"); }
        String label() { return name + ", " + id.replace('_', ' '); }
    }

    static Path root() { return Minecraft.getInstance().gameDirectory.toPath().resolve("forgeward_pano"); }

    /** "sp_&lt;save folder&gt;" or "mp_&lt;server address&gt;". */
    static String worldKey(Minecraft mc) {
        IntegratedServer sp = mc.getSingleplayerServer();
        if (sp != null) {
            Path p = sp.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
            return "sp_" + safe(String.valueOf(p.getFileName()));
        }
        ServerData sd = mc.getCurrentServer();
        return "mp_" + safe(sd != null ? sd.ip : "unknown");
    }

    static String worldName(Minecraft mc) {
        IntegratedServer sp = mc.getSingleplayerServer();
        if (sp != null) return sp.getWorldData().getLevelName();
        ServerData sd = mc.getCurrentServer();
        return sd != null ? sd.name : "server";
    }

    private static String safe(String s) {
        String t = s.replaceAll("[^A-Za-z0-9._-]", "_");
        return t.length() > 64 ? t.substring(0, 64) : t;
    }

    /** Every complete capture, newest first. */
    static List<Shot> all() {   // not synchronized: the render thread lists while the writer works (.part keeps it safe)
        List<Shot> out = new ArrayList<>();
        Path root = root();
        if (!Files.isDirectory(root)) return out;
        try (Stream<Path> worlds = Files.list(root)) {
            for (Path w : (Iterable<Path>) worlds::iterator) {
                if (!Files.isDirectory(w)) continue;
                try (Stream<Path> shots = Files.list(w)) {
                    for (Path d : (Iterable<Path>) shots::iterator) {
                        String id = d.getFileName().toString();
                        if (!Files.isDirectory(d) || id.endsWith(".part") || !complete(d)) continue;
                        out.add(new Shot(d, w.getFileName().toString(), id, name(d, w), Files.exists(d.resolve("favourite"))));
                    }
                }
            }
        } catch (IOException e) {
            PanoMod.LOG.warn("[forgeward_pano] library: could not list {}: {}", root, e.toString());
        }
        out.sort(Comparator.comparing(Shot::id).reversed());
        return out;
    }

    private static boolean complete(Path d) {
        for (int i = 0; i < 6; i++) if (!Files.isRegularFile(d.resolve(i + ".png"))) return false;
        return true;
    }

    private static String name(Path d, Path w) {
        Properties p = new Properties();
        try (var r = Files.newBufferedReader(d.resolve("info.properties"), StandardCharsets.UTF_8)) { p.load(r); }
        catch (IOException e) { return w.getFileName().toString(); }
        return p.getProperty("name", w.getFileName().toString());
    }

    static synchronized void setFavourite(Shot s, boolean on) {
        Path m = s.dir().resolve("favourite");
        try { if (on) { if (!Files.exists(m)) Files.createFile(m); } else Files.deleteIfExists(m); }
        catch (IOException e) { PanoMod.LOG.warn("[forgeward_pano] library: favourite {} for {} failed: {}", on, s.id(), e.toString()); }
    }

    /**
     * Writer thread: crop each frame to its centre square (vertical FOV 90 = the centre H x H is exactly a 90 x 90 face),
     * scale it to faceSize, write the PNGs, then rename the folder into place and prune. Closes the frames.
     */
    static synchronized Shot write(String world, String id, NativeImage[] frames, int faceSize, Properties info, boolean favourite) throws IOException {
        Path dir = root().resolve(world).resolve(id), part = dir.resolveSibling(id + ".part");
        Files.createDirectories(part);
        try {
            for (int i = 0; i < 6; i++) {
                NativeImage src = frames[i];
                int h = src.getHeight(), x0 = (src.getWidth() - h) / 2, size = Math.min(faceSize, h);
                try (NativeImage face = new NativeImage(size, size, false)) {
                    src.resizeSubRectTo(x0, 0, h, h, face);
                    face.writeToFile(part.resolve(i + ".png"));
                }
            }
        } finally {
            for (NativeImage f : frames) if (f != null) f.close();
        }
        try (var w = Files.newBufferedWriter(part.resolve("info.properties"), StandardCharsets.UTF_8)) { info.store(w, "forgeward_pano capture"); }
        if (favourite) Files.createFile(part.resolve("favourite"));
        Files.move(part, dir, StandardCopyOption.ATOMIC_MOVE);
        prune(world);
        return new Shot(dir, world, id, info.getProperty("name", world), favourite);
    }

    /** Keep the newest keepPerWorld non-favourites of one world; also clears .part leftovers of a crash. */
    static synchronized int prune(String world) {
        int keep = PanoConfig.get(PanoConfig.KEEP_PER_WORLD, 10), removed = 0, seen = 0;
        Path w = root().resolve(world);
        List<Path> dirs = new ArrayList<>();
        try (Stream<Path> s = Files.list(w)) { s.filter(Files::isDirectory).forEach(dirs::add); }
        catch (IOException e) { return 0; }
        dirs.sort(Comparator.comparing((Path p) -> p.getFileName().toString()).reversed());
        for (Path d : dirs) {
            boolean part = d.getFileName().toString().endsWith(".part");
            if (part ? isOld(d) : !Files.exists(d.resolve("favourite")) && ++seen > keep) { delete(d); removed++; }
        }
        if (removed > 0) PanoMod.LOG.info("[forgeward_pano] library: pruned {} old capture(s) of {} (keeping {} + favourites)", removed, world, keep);
        return removed;
    }

    private static boolean isOld(Path d) {
        try { return System.currentTimeMillis() - Files.getLastModifiedTime(d).toMillis() > 3_600_000L; } catch (IOException e) { return false; }
    }

    private static void delete(Path d) {
        try (Stream<Path> s = Files.walk(d)) {
            s.sorted(Comparator.reverseOrder()).forEach(p -> { try { Files.delete(p); } catch (IOException ignored) { } });
        } catch (IOException e) {
            PanoMod.LOG.warn("[forgeward_pano] library: could not delete {}: {}", d, e.toString());
        }
    }

    private Library() { }
}
