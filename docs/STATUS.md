# Shart TV — status

The implementation side reads only `docs/`. Sections not marked final may change. The
hardware/software sweep and the scored inventory live at the PROJECT ROOT (`../SWEEP-REPORT.md`),
not here, because they contain facts inferred from the stock APKs ([I]) that the implementation
side must not read.

## Spec stages
- 2026-10-22, stage 0: device swept, scored inventory final (`../SWEEP-REPORT.md`). Samba ACR +
  6 telemetry/ad packages disabled on-device (reversible). APKs pulled; toolchain installed.
- `launcher-spec.md`: FINAL — sections 0-11 + open questions (HOME role, becoming default home,
  input switching via public APIs, Sony customizer config, permission map, home-screen layout,
  ads/telemetry NOT to copy, what to keep, test plan).
- 2026-10-22, stage 2: `launcher-spec.md` sections **4.4** (layout measurements of the stock
  launcher on this panel: margins, card sizes, focus scale/timing, top bar, type scale, colours,
  evidence gaps) and **13** (capability inventory with SHOULD/CAN'T/WON'T verdicts and a
  prioritized SHOULD list) FINAL.
- `remote-spec.md`: FINAL — remote stack (remote/pairing service kept, soft remote via public
  mechanism, global-key hardware-button remap via RRO, UEI QuickSet IR/CEC kept, voice/mic path
  disabled by default).

## Implementation
- `app/` : not started. Shart TV launcher built from `launcher-spec.md` + public AOSP Leanback
  launcher only. Never from the pulled APKs.

## On-device changes applied (reversible via `scripts/sharttv.py rollback`)
- disabled-user (user 0): tv.samba.ssm, tv.samba.ssm.ui, com.sony.dtv.promos,
  com.sony.dtv.sonyselect, com.sonyericsson.idd.agent, com.sony.dtv.demosupport,
  com.google.android.feedback.
