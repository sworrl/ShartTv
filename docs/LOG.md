# Division-of-labor log (clean-room independence)

Owner permission: sworrl, on his own Sony BRAVIA VH21 he materially owns. Goal: a FOSS launcher
and remote-stack replacement, remove ACR/ad/telemetry, regain hardware control. Interoperability
replacement, not an attack on anyone else's system.

## Spec side (this account, this session)
- 2026-10-22: read-only adb sweep as `shell` (package lists, getprop, audio/camera/bt dumpsys,
  /proc/net snapshots). Pulled stock APKs to apks/. May study them + jadx output (kept OUTSIDE
  this repo, in the session scratchpad `cleanroom-spec-private/`). Writes specs only: interfaces,
  effects, acceptable ranges. No decompiled code, no control-flow transcription in docs/.

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

## docs/hardware/ note
- `docs/hardware/*` is hardware/bootloader/privacy RESEARCH (board facts, unlock tracking, consumer-SoC options, residual privacy). It is not launcher-implementation material; the launcher impl side can ignore it. No decompiled app internals are in it.
