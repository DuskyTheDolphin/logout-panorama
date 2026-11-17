# Changelog

## 1.0.9
- First public release. No behaviour change for players; the config screen and mod list now say "Logout Panorama".
- Built-in test and benchmark runs removed from the mod; a small `PanoDebug` class gives an external test mod read
  access to the capture and title-screen state.
- MIT license.

## 1.0.8
- Unreleased internal version: `capture.skipPorts`, no leave capture on the listed server ports.

## 1.0.7
- Unreleased internal version: defaults of 20 frames a face and a 6 s budget.

## 1.0.6
- Unreleased internal version: the faces use the FOV option at 90 instead of the panoramic camera mode, so shaders with
  Distant Horizons keep the near ground.

## 1.0.5
- Unreleased internal version: the near-terrain check alone decides whether a face is complete.

## 1.0.4
- Unreleased internal version: no capture on closing the window; a capture needs the near terrain drawn; the player's
  own body hidden while capturing.

## 1.0.3
- Unreleased internal version: leave capture on servers only; single-player leaves stay vanilla.

## 1.0.2
- Unreleased internal version: the leave resumes at the vanilla click point.

## 1.0.1
- Unreleased internal version: the newest capture shows on the title screen; a face whose terrain is still meshing
  waits up to 0.4 s; capture ids carry milliseconds.

## 1.0.0
- Unreleased internal version: logout panorama for the title screen, library and favourites.
