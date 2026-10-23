# Division-of-labor log (clean-room independence)

Owner permission: sworrl, on his own Sony BRAVIA VH21 he materially owns. Goal: a FOSS launcher
and remote-stack replacement, remove ACR/ad/telemetry, regain hardware control. Interoperability
replacement, not an attack on anyone else's system.

## Spec side (this account, this session)
- 2026-10-22: read-only adb sweep as `shell` (package lists, getprop, audio/camera/bt dumpsys,
  /proc/net snapshots). Pulled stock APKs to apks/. May study them + jadx output (kept OUTSIDE
  this repo, in a private working folder `cleanroom-spec-private/`). Writes specs only: interfaces,
  effects, acceptable ranges. No decompiled code, no control-flow transcription in docs/.

- 2026-10-22, stage 2 (layout measurements + capability inventory, spec sections 4.4 and 13):
  spec team decoded launcherx base.apk resources with apktool (`--no-src`) and read the existing
  jadx output, all inside the private working folder `cleanroom-spec-private/`. Looked at:
  resolved dimensions, styles/themes, animators and interpolators, fractions, layouts (sizes
  only), the manifest component list, and the card-sizing, focus-animation and side-panel code
  for numbers. Read-only adb on <tv-ip>: `cmd uimode night`, `wm size`, `wm density`,
  `settings get system font_scale`, `dumpsys activity activities` (foreground check only), and
  `cmd package query-activities` for public intents. An attempt to dump the stock home
  activity's view state returned the foreground app's state instead (stock home was not on
  screen); that output stayed in the private folder and was not used. No activities launched, no
  keys injected, no screenshots, nothing on screen changed. Written into docs/: measured
  values as numbers with ranges, behaviors as effects, interop names only. No resource names or
  ids, no code, no layout or style excerpts, no UI strings.

## Implementation side (to be run separately)
- Reads only docs/, public AOSP / Android-TV Leanback launcher, and Android SDK docs. Never the
  pulled APKs, never jadx output, never this log's evidence beyond the stated interfaces.
- Statement to be recorded here when app/ work starts.

## Interop exceptions (copied strings allowed, tagged)
- HOME/LEANBACK_LAUNCHER intent categories, component/action names other code triggers,
  the global-key key codes the hardware buttons emit, and the Android TV remote pairing protocol
  — kept exact because a drop-in must interoperate. Everything else is described, not copied.

## Rights notices
- None obeyed/hidden. If a pulled APK carries a license or anti-RE notice, quote to owner here.
- 2026-10-22 stage 2: searched launcherx's decoded resources and assets and the customizer for
  license or anti-reverse-engineering notices. None found. launcherx ships an in-app
  open-source license screen (third-party attributions), which is not a restriction on us.

## docs/hardware/ note
- `docs/hardware/*` is hardware/bootloader/privacy RESEARCH (board facts, unlock tracking, consumer-SoC options, residual privacy). It is not launcher-implementation material; the launcher impl side can ignore it. No decompiled app internals are in it.
