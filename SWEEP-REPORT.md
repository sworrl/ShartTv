# Shart TV — hardware/software sweep, scored inventory, and plan

Owner: sworrl. Device: Sony BRAVIA 4K VH21, mt5895, Android 12 (Google TV), SDK 31.
Access: wireless adb as `shell` (uid 2000). Date: 2026-10-22.

Source tags (same scheme as the clean-room skill): **[O]** observed on device,
**[I]** inferred from a pulled binary, **[F]** from a firmware/config file, **[D]** datasheet/docs,
**[M]** mainline/open source.

---

## 1. The Samba problem — solved

Your "SMB traffic that won't die, blocked at DNS but that's not enough" is almost certainly
**not** the SMB/CIFS file protocol. It is **Samba TV** — an ACR (automatic content recognition)
analytics company whose SDK Sony ships inside BRAVIA. Two system packages [O]:

- `tv.samba.ssm` — `/system/priv-app/SSM/SSM.apk`, PRIVILEGED, `INTERNET` granted,
  `ALLOW_AUDIO_PLAYBACK_CAPTURE`. Version `3.4.4.1_3.10.8.1`.
- `tv.samba.ssm.ui` — `/system/app/SSM_UI/SSM_UI.apk`, the opt-out UI shim.

It fingerprints what's on screen (pixel/audio sampling) and reports to Samba's servers. DNS
blocking fails because the SDK rotates domains and falls back to hard-coded endpoints — exactly
your symptom. `SSM` = "Samba Smart Manager".

**Done:** both packages are `disabled-user` + `stopped` for user 0 [O]. Verified: no `ssm`/samba
process running, and a live `/proc/net/tcp` snapshot shows only 443/80 outbound — no port 445,
no NetBIOS 137-139. The DNS block is now belt-and-suspenders, not the only thing holding.
Reversible with `scripts/rollback-debloat.sh`. Survives reboot; does **not** survive factory reset.

If there is *also* genuine SMB/UPnP on the wire, it comes from the DLNA stack (section 4), which
we can disable too.

---

## 2. Hardware sweep

| Subsystem | Finding | Source | Toggle reality |
|---|---|---|---|
| SoC | MediaTek `mt5895` (Pentonic 700 class), 32-bit `armeabi-v7a` | [O] | — |
| Built-in mic | `ro.boot.svp.builtinmic=0` — **none**. Voice mic is in the remote (`remotevoice=1`) | [O] | No far-field mic to tape over. Remote mic = don't pair / disable voice. |
| Camera | `media.camera`: **0 devices**. BRAVIA CAM sensing stack present (`sensingcamera.*`, `sensingfusionservice`, `gestureapp`, `distancealert`) but no camera attached | [O] | Nothing to blind today; disable the sensing apps so an attached cam can't be used silently. |
| Bluetooth | present + LE, `bluetooth_on=1` | [O] | Settings toggle + `cmd bluetooth_manager disable` (per-boot, shell). |
| Wi-Fi | `wlan0` carries the TV's IP (<tv-ip>/20), `wifi_on=1`, wifidisplay HAL running | [O] | Can't kill the only NIC without losing adb; wired is the escape hatch. |
| Audio-in routes | BUILTIN_MIC, BACK_MIC, HDMI, LINE, TV_TUNER, BT_SCO, USB all defined in audio policy | [O] | Policy-level; real capture gated by the (absent) mic. |
| Inputs | `/dev/input/event0..2` + mice | [O] | — |

**Bottom line on hardware toggles:** the scary stuff (always-listening far-field mic, built-in
camera) is physically absent on this unit. The achievable "hardware toggles" are software kill
switches for the radios and the sensing/voice stacks, plus a remote with a mic you simply don't
have to use. Kernel-level cutoffs (the GrapheneOS-style hard toggles) need the bootloader, which
this model locks — see below.

---

## 3. Bootloader reality (so we don't chase a ghost)

You asked to flip an "OEM unlocking" toggle. On **phones** that toggle flips a flag the
bootloader honors. On this BRAVIA it does not exist:

- `ro.oem_unlock_supported` = empty, `sys.oem_unlock_allowed` = empty [O].
- `vbmeta.device_state=locked`, `verifiedbootstate=green`, no `oem_lock` service [O].
- `pm` has no `get-oem-unlock-state`; Developer Options on Sony Google TV has no unlock entry.

Sony's MTK BRAVIA bootloaders are signed and reject `fastboot flashing unlock`. So:

- **No root, no custom kernel, no module swap on this unit without an mt5895 exploit.** That
  rules out the Blackjack-style GKI drop-in and true kernel hardware cutoffs *for now*.
- Everything in sections 1, 4, 6 is achievable **today** at the app/settings layer over `shell`.
- Root paths worth tracking as open questions: known mt5895/Pentonic chain-of-trust bugs, `su`
  via a service-mode/engineering APK, or a downgrade to a vulnerable build. Tracked in
  `docs/QUESTIONS.md`. We do **not** fuzz or attack the device to find them (safety + skill rule).

If you want GrapheneOS-level sandboxing and hard kernel toggles, the realistic route is a
**second device we can root** (an mt-unlockable Google TV dongle/box), not this sealed BRAVIA.
Say the word and I'll spec that as a parallel track.

---

## 4. Scored inventory — find / score / fork

Score = replace-or-kill priority, 1 (leave alone) … 10 (top target). "Action" is what we do:
KILLED = already disabled this session; KILL = disable next; REPLACE = clean-room rebuild as
Shart TV; KEEP = leave; WATCH = optional.

### Launcher stack (the core Shart TV fork target)

| Pkg | What it is | Score | Action | Notes |
|---|---|---|---|---|
| `com.google.android.apps.tv.launcherx` | Google TV home ("launcherx"): ad rows, "Top picks", sponsored tiles, recommendation engine | **10** | REPLACE | The main rebuild. Your gripe: ads + buried input/source swapper. |
| `com.sony.dtv.launcherxcustomizer` | Sony overlay that injects Sony rows/branding into launcherx | **8** | REPLACE/absorb | Folds into Shart TV. |
| `com.sony.dtv.tvx` (+ `tvx.overlay.sortui`) | Sony "TV Experience" shell / app ordering | 6 | WATCH | Decide after launcher spec whether to replace or coexist. |
| `com.sony.dtv.promos` | Promotional content pusher | **9** | KILLED | Ads. |
| `com.sony.dtv.sonyselect` | Sony content recommendations/ads feed | **8** | KILLED | Ads. |

### Trackers / telemetry

| Pkg | What it is | Score | Action |
|---|---|---|---|
| `tv.samba.ssm` / `tv.samba.ssm.ui` | Samba TV ACR | **10** | KILLED |
| `com.sonyericsson.idd.agent` | Sony "IDD" interaction-data telemetry agent | **9** | KILLED |
| `com.google.android.feedback` | Google crash/feedback uploader | 6 | KILLED |
| `com.sony.dtv.demosupport` | Retail demo/store mode | 6 | KILLED |
| `com.sony.dtv.privacypolicy` | Consent shim | 3 | KEEP |

### SMB / DLNA / discovery (real file-share + multicast chatter)

| Pkg | What it is | Score | Action |
|---|---|---|---|
| `com.sonyericsson.dlna` (`SemcDlna`) | DLNA/UPnP renderer+server — real SSDP multicast, can look "SMB-ish" | 6 | KILL (optional) |
| `com.sony.huey.dlna.module` / `renderersettings` | Huey DLNA engine | 6 | KILL (optional) |
| `com.sony.dtv.homenetwork` | Home-network/"send to TV" settings | 5 | WATCH |
| `com.sony.dtv.smartmediaapp` | Media browsing over network shares | 5 | WATCH |

### Privacy hardware stack (camera/mic/sensing)

| Pkg | What it is | Score | Action |
|---|---|---|---|
| `com.sony.dtv.sensingcamera.settings` / `.fwupdate` | BRAVIA CAM control + firmware | 7 | KILL (no cam attached) |
| `com.sony.dtv.sensingfusionservice` | Sensor fusion for the cam (presence/gesture) | 7 | KILL |
| `com.sony.dtv.gestureapp` / `com.sony.dtv.distancealert` | Gesture + "too close" using the cam | 6 | KILL |

### Keep (system-critical — do not touch)

Tuner/input (`tvinput.*`), `webapi.core`, `systemcontrolproxy`, `networksettings`,
`audiooutput`, `settings`, Google Play/GMS core, `com.hione.tv` (your own installed launcher,
v0.2.1, a LEANBACK_LAUNCHER — currently the focused app). Full list in `sweep/packages_system.txt`.

---

## 5. Already done this session (reversible)

Disabled for user 0 via `pm disable-user` (rollback in `scripts/rollback-debloat.sh`):
`tv.samba.ssm`, `tv.samba.ssm.ui`, `com.sony.dtv.promos`, `com.sony.dtv.sonyselect`,
`com.sonyericsson.idd.agent`, `com.sony.dtv.demosupport`, `com.google.android.feedback`. [O]

APKs pulled to `apks/` (16): launcherx + splits, LauncherXCustomizer, SSM + SSM_UI, the sensing
stack, promos, sonyselect, tvx, homenetwork, SemcDlna.

---

## 6. Plan

**Track A — debloat + privacy (today, no root).** `scripts/apply-debloat.sh` makes the whole set
repeatable and adds the optional DLNA/sensing kills behind flags. Add a per-UID network denylist
for anything we keep but distrust (`cmd netpolicy add restrict-background-blacklist <uid>`).

**Track B — Shart TV launcher (clean-room).** Rebuild `launcherx` as our own Android TV / Google
TV home: no ads, no sponsored rows, inputs/source-swapper promoted to the top instead of buried,
fast, our branding. Built from `docs/launcher-spec.md` + the public AOSP/Android-TV leanback
launcher — never from the pulled binary. Set as default HOME over adb
(`cmd package set-home-activity`). Spec drafted next; see `docs/STATUS.md`.

**Track C — root (research only, parallel).** Track mt5895/Pentonic unlock or root options in
`QUESTIONS.md`. No attacks on the device. If we want Graphene-level sandboxing + hard kernel
toggles, spec a rootable second box instead.

See `docs/launcher-spec.md` for the launcher clean-room spec (in progress) and `docs/LOG.md` for
the division-of-labor record.
