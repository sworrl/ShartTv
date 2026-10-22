# Shart TV — remote stack specification

Target: Sony BRAVIA 4K VH21, Google TV, Android 12. Owner (sworrl) owns the device. This spec
describes a FOSS replacement for the TV-side remote-handling apps, built from documented and
observed Android interfaces. It is an interoperability replacement for the owner's own hardware,
not a way around anyone's security.

Scope note: the physical Sony remote is a sealed BLE/IR microcontroller. Its firmware is not
flashable from the TV and is out of scope. "Clean-rooming the remote" here means the **TV-side
services** that receive and act on the remote, plus an optional **soft remote** app that uses the
standard Android TV remote mechanism, with the microphone off by default.

---

## 0. Legend and sources

- **[O]** observed over adb as `shell` (dumpsys, pm/cmd queries, getprop, settings). 2026-10-22.
- **[I]** inferred from the stock APKs' behavior (what they do, not how written).
- **[F]** read from an APK manifest/resource/config.
- **[D]** public Android developer docs / AOSP, linked.
- **[M]** public open-source projects.

Hardware identity: `BRAVIA_VH21`, board `mt5895`, Android 12 / SDK 31. Mic: no built-in far-field
mic (`ro.boot.svp.builtinmic=0`); the only microphone is in the handheld remote
(`ro.boot.svp.remotevoice=1`) [O]. Bonded BT shows the TV itself; the remote was not bonded at
sweep time [O], consistent with a BLE-HID remote that connects on demand or an IR remote.

### Public references for the implementation side

- [D] `android.view.KeyEvent` key-code constants (the hardware buttons emit standard codes):
  `https://developer.android.com/reference/android/view/KeyEvent`
- [D] Runtime Resource Overlays (RRO), for remapping without replacing a system APK:
  `https://source.android.com/docs/core/runtime/rros`
- [D] `android.service.quicksettings` / media key handling; `MediaSession` for CEC-style control.
- [M] Public Android TV remote clients that speak Google's TV remote mechanism (reference for a
  soft remote): the openly documented Android TV Remote v2 approach used by community projects.
- [M] AOSP `packages/apps/TV` and leanback input handling for DPAD/BACK/HOME semantics.

---

## 1. Summary for the implementation side

The stock remote experience on this TV is spread across several apps. Shart TV replaces or
neutralizes the parts the owner objects to and keeps the parts that are load-bearing:

| Component | Version / path | What it does | Shart TV action |
|---|---|---|---|
| `com.google.android.tv.remote.service` | system | Receives the BLE/HID remote; also the endpoint a phone "TV remote" app connects to; routes the voice-mic button | KEEP installed (remote depends on it); provide a soft remote that uses the same public mechanism; voice OFF by default |
| `com.sony.dtv.globalkeyoverlay` | `/product/overlay/SonyGlobalKeyOverlay.apk` | Maps the dedicated app-launch buttons (streaming-service keys) to launch those apps | REMAP via our own RRO so the buttons do what the owner wants |
| `com.uei.quicksetsdk.sony` | `/system/priv-app/QuicksetSDK-.../...apk` | UEI QuickSet: universal-remote IR code database to control a soundbar/AVR | KEEP (useful); document its public entry points only |
| `com.sony.dtv.irblaster` / `com.sony.dtv.irbservice` | priv-app | IR blaster output for the above | KEEP |
| `com.sony.dtv.remotecontrolsettings` | `/system/app/RemoteControlSettings/...` | Remote pairing/settings UI | KEEP or reskin |
| `com.sony.dtv.voicesearch` | system | Voice search path for the remote mic | DISABLE by default (mic privacy) |
| `com.sony.dtv.voicerecognitionconfirmation` | system | Voice capture confirmation prompt | DISABLE by default |
| `com.sony.dtv.assistantadapter` | system | Bridges remote voice button to Assistant | DISABLE by default |

Biggest risks: (1) disabling the remote service could break the physical remote — verify it does
not before shipping (open question 1); (2) the global-key RRO may need to target the right overlay
category — verify it takes effect over `shell` without a system write (open question 2).

---

## 2. Components and interfaces

### 2.1 Remote receiver service (`com.google.android.tv.remote.service`)

Effect [I/O]: it is the TV-side endpoint for the handheld remote and for any companion "TV
remote" running on a phone on the same network. The companion connection uses Google's standard,
publicly documented Android TV remote mechanism (a pair step, then a control channel). Shart TV's
soft remote, if built, uses that **same public mechanism** — it is a normal client, like the
official app. This spec does not attempt to bypass the pair step; a soft remote pairs the ordinary
way and the owner confirms the pairing on the TV.

Contract for Shart TV:
- Do **not** uninstall or hard-disable this service; the physical remote relies on it (open
  question 1 confirms before any disable). If voice must be silenced, do it by disabling the
  voice apps in 2.4, not by crippling the receiver.
- A soft remote sends standard key events (section 3) over the public control channel. No special
  permission beyond what the mechanism already grants a paired client.

### 2.2 Global key overlay (`com.sony.dtv.globalkeyoverlay`)

Effect [F/I]: this is a Runtime Resource Overlay on `/product` that binds the remote's dedicated
app-launch buttons to specific apps. The buttons emit standard `KeyEvent` codes; the overlay maps
each code to a launch target.

Contract for Shart TV:
- Ship our own RRO that redefines the same mapping resources so each dedicated button launches
  what the owner chooses (including "do nothing" or "open Shart TV"). This is the documented RRO
  mechanism [D], not a patch of Sony's APK.
- Install path over `shell`: confirm whether `cmd overlay` can enable a user-installed RRO on this
  build without a system partition write (open question 2). If it needs a system write, it becomes
  a root-only item and is tracked as such; the fallback is to intercept the key codes inside Shart
  TV while it is foreground (section 3).
- The exact resource names and the per-button key codes are an interop detail; record them in the
  spec as a table of `KeyEvent` code → intended target, tagged [F], so the implementation side can
  author the overlay. Standard `KeyEvent` names are public [D].

### 2.3 Universal remote / IR (`com.uei.quicksetsdk.sony`, `irblaster`, `irbservice`)

Effect [I]: UEI QuickSet provides an IR code database and the TV drives an IR blaster / CEC to
control a connected soundbar or AVR (volume, power). Keep this — it is useful and not a privacy
problem.

Contract for Shart TV:
- Do not replace QuickSet. If Shart TV offers volume/power pass-through, it calls the public
  `MediaSession` / audio APIs and lets the existing CEC/IR path act, rather than reimplementing
  the IR database.
- Document only the public intents/services QuickSet exposes that Shart TV might invoke (device
  setup entry point). No internal code-base extraction.

### 2.4 Voice / microphone path

Effect [O/I]: the remote's mic button routes through `voicesearch`, with
`voicerecognitionconfirmation` showing the capture prompt and `assistantadapter` bridging to
Assistant. With no built-in mic on this TV, the handheld remote mic is the only microphone.

Contract for Shart TV — **mic off by default**:
- Disable `com.sony.dtv.voicesearch`, `com.sony.dtv.voicerecognitionconfirmation`,
  `com.sony.dtv.assistantadapter` for user 0 (`pm disable-user`, reversible, no root). This leaves
  the remote working as a normal remote and takes the mic button out of the capture path.
- Verify the mic button then does nothing and no hotword path remains (open question 3).
- Shart TV itself requests **no** `RECORD_AUDIO` permission. A voice feature, if ever added, is
  opt-in per use and visibly indicated.

---

## 3. Key handling contract

The remote's buttons arrive as standard `android.view.KeyEvent` codes [D]. Shart TV, as the HOME
app and as the foreground app, handles:

- DPAD up/down/left/right + CENTER: navigation/selection.
- `KEYCODE_BACK`: back / up one level.
- `KEYCODE_HOME`: to Shart TV home. Long-press HOME: Shart TV's own recents/quick panel (not the
  stock panel, which a user app can't own — see launcher-spec section 3).
- `KEYCODE_TV_INPUT` and the input/source button: open Shart TV's input switcher (promoted to the
  top of home, the opposite of the stock "buried" behavior — launcher-spec section 4).
- Media transport keys: forwarded to the active `MediaSession`.
- The dedicated app-launch buttons: remapped per 2.2. While Shart TV is foreground, it can also
  catch those key codes directly as a fallback to the RRO.

The code→action table (per-button `KeyEvent` codes for this remote) is recorded as [F], filled
from the remote's observed events and the overlay's public resource map. Standard codes only; no
private key material or secrets are involved and none are recorded.

---

## 4. Test plan

Run on the owner's TV; the owner runs anything that changes state.

1. Disable the three voice apps (2.4). Confirm: physical remote still navigates; mic button does
   nothing; `dumpsys media.audio_policy` shows no active input; Assistant hotword absent.
2. Install the global-key RRO (2.2). Confirm each dedicated button now does the chosen action; if
   `cmd overlay` can't enable it without a system write, fall back to in-app key catch and record
   it as root-only.
3. Install a soft remote (if built), pair it the normal way (owner confirms on TV), confirm DPAD
   /BACK/HOME and media keys work over the public channel.
4. Reboot. Confirm the voice-app disables and the RRO persist, and the physical remote still works.
5. Rollback: re-enable the voice apps and remove the RRO; confirm stock behavior returns.

---

## 12. Open questions (answer on the TV; owner runs state-changing commands)

1. Does disabling (or the owner choosing to keep) `com.google.android.tv.remote.service` affect
   the physical remote? Confirm the remote keeps working with the voice apps disabled but the
   service kept.
2. Can a user-installed RRO be enabled over `shell` with `cmd overlay` on this build, or does the
   global-key remap require a system-partition write (root)? If root-only, the in-app key catch
   (section 3) is the no-root fallback.
3. With the three voice apps disabled, does the remote mic button go fully inert and the Assistant
   hotword stay off across a reboot?
4. Does the soft-remote public channel require the stock Google TV launcher to remain installed
   (it stays installed per launcher-spec), or is the remote service independent of HOME?
5. Which exact `KeyEvent` codes do this remote's dedicated buttons emit? Fill the section 3 table
   from observed events.
