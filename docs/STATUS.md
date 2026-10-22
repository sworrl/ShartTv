# Shart TV — status

The implementation side reads only `docs/`. Sections not marked final may change. The
hardware/software sweep and the scored inventory live at the PROJECT ROOT (`../SWEEP-REPORT.md`),
not here, because they contain facts inferred from the stock APKs ([I]) that the implementation
side must not read.

## Spec stages
- 2026-10-22, stage 0: device swept, scored inventory final (`../SWEEP-REPORT.md`). Samba ACR +
  6 telemetry/ad packages disabled on-device (reversible). APKs pulled; toolchain installed.
- `launcher-spec.md`: stage 1 — sections 0-3 and open questions final (HOME role, becoming
  default home, input switching via public APIs, Sony customizer config, permissions). Sections
  4-11 (home-screen behavior, key handling, ads/bugs not to copy, test plan) in progress.
- `remote-spec.md`: to be written — remote stack (`com.google.android.tv.remote.service`,
  `globalkeyoverlay` hardware-button remap, UEI QuickSet IR/CEC, voice/mic path).

## Implementation
- `app/` : not started. Shart TV launcher built from `launcher-spec.md` + public AOSP Leanback
  launcher only. Never from the pulled APKs.

## On-device changes applied (reversible via `scripts/rollback-debloat.sh`)
- disabled-user (user 0): tv.samba.ssm, tv.samba.ssm.ui, com.sony.dtv.promos,
  com.sony.dtv.sonyselect, com.sonyericsson.idd.agent, com.sony.dtv.demosupport,
  com.google.android.feedback.
