# Logout Panorama

A client-side NeoForge mod for Minecraft 1.21.1 that puts the place you last stood behind the title screen.

When you leave a server, Logout Panorama takes a six-sided panorama from your eye and shows it as the rotating title-screen
background. Captures pile up into a small library per world or server; star the ones you like and the title screen can
cycle through your favourites instead. The pause menu also gets a small "Save this view" button for any spot you want to
keep.

The faces come from the real rendered frame, so shaders and Distant Horizons are in the picture. The capture runs behind
an opaque "Leaving server" screen, takes about one to five seconds, and never holds you up: if it can't finish inside its
time budget it is skipped (and logged) and you leave at once. Nothing is ever sent to the server.

Designed by DuskyTheDolphin, written with AI assistance.

## Using it

- **Leave a server** with Disconnect: a panorama is taken first. Single-player worlds are left exactly as vanilla does,
  with no capture.
- **"Save this view"** (bottom-left of the pause menu): captures now, in any world, kept as a favourite.
- **Title screen**: small `<`, `☆` and `>` buttons in the top-right corner step through your captures and star or
  unstar the one shown. Every menu without a world (title, options, server list) shows the same panorama.
- What the title shows is set in the config: your newest capture, a cycle through favourites with a crossfade, one
  random favourite per launch, or the normal vanilla panorama.

A capture is kept only when the terrain around you is actually drawn: right after a teleport, when only far terrain is
on screen, it is skipped rather than saving a half-empty view. A portrait (taller than wide) window is skipped too. Your
own body (from first-person body mods) is hidden while capturing, and your FOV, view bobbing and camera are put back
exactly afterwards.

## Compared with Better Panorama and Panorium

Better Panorama (Fabric and NeoForge, 1.21.1) captures a panorama with F4 and adds a panorama menu; Panorium (Fabric)
captures panoramas into a library and sets one as the title background. Logout Panorama differs in that it captures by itself
every time you leave a server, so the title screen follows where you have been, and it keeps a favourites library the
title screen can cycle through. If one of those mods already works for you, you don't need this one.

## Install

Client only. Put the jar in your `mods/` folder; servers do not need it, and it works on any server.

- Minecraft 1.21.1
- NeoForge 21.1 or newer (built against 21.1.250)
- No other dependencies

Release test for 1.0.9: a NeoForge 21.1.250 client with only this mod (and a test-only driver) installed, against a
dedicated server with no mods; an automatic run of save view, leave capture, title display, star and cycle passed.

## Config

`config/forgeward_pano-client.toml`, also in game under Mods > Logout Panorama > Config.

| Key | Default | Meaning |
|---|---|---|
| `capture.onLeave` | `true` | Take a panorama when you leave a server |
| `capture.skipPorts` | `[]` | Server ports whose leave is not captured, whatever host you connect through, e.g. `[25566]` for a creative test server; empty captures everywhere. "Save this view" still works there |
| `capture.faceSize` | `1024` | Size of each of the six faces in pixels (256..4096, never more than the window height) |
| `capture.budgetSeconds` | `6.0` | Most time a capture may take before it is skipped (0.5..15) |
| `capture.settleFrames` | `20` | Frames drawn per face before it is grabbed, so shader smoothing can settle (2..120) |
| `capture.pauseMenuButton` | `true` | Show "Save this view" in the pause menu |
| `library.keepPerWorld` | `10` | Captures kept per world or server, newest first; favourites are never deleted (1..200) |
| `titleScreen.mode` | `LATEST` | `LATEST`, `CYCLE_FAVOURITES`, `RANDOM_FAVOURITE` or `VANILLA` |
| `titleScreen.cycleSeconds` | `30.0` | `CYCLE_FAVOURITES`: seconds per panorama; `0` = the next one each launch |
| `titleScreen.crossfadeSeconds` | `2.0` | Crossfade between panoramas, seconds |
| `titleScreen.controls` | `true` | Show the prev / star / next buttons on the title screen |

The library is plain files in the game folder: `forgeward_pano/<world>/<capture>/0.png` .. `5.png` (vanilla panorama
order), an `info.properties` with the world name, spot and time, and a `favourite` marker file. Delete a folder to drop
a capture.

## Log

On the first title screen of a session:

```
[forgeward_pano] 1.0.9 ready: capture on leave ON (face 1024 px, budget 6.0 s, 20 frames per face), pause button ON, leave capture skipped on ports []; library 3 captures in 1 worlds, 1 favourites (keep 10 per world); title mode LATEST, showing mp_example.org/2026-11-17_20-15-02-118
```

Each capture logs a `captured ...` line (faces, time, frames per face) and a `saved ...` line from the writer thread,
or a `capture skipped: <why>` line.

## Building

`build.ps1` compiles with `javac` against a NeoForge 21.1.250 server's `libraries/` (set `SERVER` and `JDK`) and the
named client jars in `clientlibs/`, which it regenerates with that server's NeoForge installer if they are missing.
Mod id `forgeward_pano`.

## License

MIT, see [LICENSE](LICENSE).
