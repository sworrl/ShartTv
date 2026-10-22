# Shart TV

A 1:1 AI-direct replacement (with upgrades) for the stock Google/Sony launcher and
privacy-hostile apps on a Sony BRAVIA Google TV that the owner materially owns. Same family as
the LG webOS and HiBy M500 (Blackjack) projects: find the closed components, score them,
clean-room rebuild our own versions, and give back hardware control.

Owner: sworrl <agent.jearl@gmail.com>. This is work on the owner's own device. No bypass of
anyone else's security; interoperability and a FOSS replacement for hardware he owns.

## Target device (observed over wireless adb, 2026-10-22)

- Model: Sony BRAVIA 4K VH21 (`BRAVIA_VH21_UC`), board `mt5895` (MediaTek Pentonic), 32-bit
  userspace (`armeabi-v7a`).
- Android 12 / Google TV, SDK 31, build `STT2.230505.001.S136`, patch 2026-05-01.
- `ro.debuggable=0`, `ro.secure=1`, `verifiedbootstate=green`, `vbmeta.device_state=locked`.
  **No OEM-unlock capability is exposed** (`ro.oem_unlock_supported` empty, no unlock service).
  See `docs/REPORT.md` section "Bootloader reality".
- adb access: `shell` user (uid 2000). No `su`, adb root refused (production build).
- Mic: `ro.boot.svp.builtinmic=0` — **no built-in far-field mic**; voice is in the remote only
  (`remotevoice=1`). Camera: `media.camera` reports 0 devices — the BRAVIA CAM sensing stack is
  present but no camera is attached.

## What this repo is

1. `docs/` — the clean-room hand-off folder. The only thing the implementation side reads.
   Spec, scored inventory, status, division-of-labor log, open questions.
2. `sweep/` — raw read-only evidence pulled from the device (package lists, props). Facts, not
   code.
3. `apks/` — stock APKs pulled for the spec side to study. Study material, never shipped.
4. `scripts/` — `apply-debloat.sh` (what we turn off, with rollback) and `rollback-debloat.sh`.
5. `app/` — the Shart TV launcher (clean-room implementation, built later from `docs/` only).

Private spec-team working material (disassembly, jadx output) lives OUTSIDE this repo in the
session scratchpad under `cleanroom-spec-private/` and is never copied in. See
`~/.claude/skills/cleanroom_spec`.

## Status right now

- Samba TV ACR tracker (`tv.samba.ssm`, `tv.samba.ssm.ui`) and five other telemetry/ad packages
  are **disabled on the device already** (reversible, no root). Samba traffic confirmed stopped.
- APKs pulled; toolchain installed; scored inventory written. Launcher clean-room spec next.

See `docs/REPORT.md` for the full sweep, the scores, and the plan.
