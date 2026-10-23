# Shart TV launcher specification

Target: Sony BRAVIA 4K VH21, Google TV, Android 12. Replaces the HOME role holder
`com.google.android.apps.tv.launcherx` (Google TV launcher, "launcherx" below) with Shart TV,
a user-installed (non-system) app. Stage 1: sections 0-12. Stage 2 (2026-10-22): sections 4.4 and 13.

---

## 0. Legend and sources

### Source tags (on every fact)

- **[O]** observed on the TV over adb: dumpsys, pm / cmd package queries, content query/read,
  getprop, settings get, logcat. Date 2026-10-22.
- **[I]** inferred from the stock APKs' code (what it does, not how it is written).
- **[F]** read from an APK's manifest, resources or bundled config files.
- **[D]** public Android developer docs or AOSP source, with a link.
- **[M]** public open-source TV launchers and samples (list below).

Where [I] or [F] disagrees with [D], the spec says which wins and why. Anything marked
**unverified** has not been confirmed on the device and is listed in section 12.

### Stock components studied (spec team only)

| Package | Version | Role on this TV |
|---|---|---|
| `com.google.android.apps.tv.launcherx` | 1.0.1002449598 (update over system 1.0.660160223) | Current HOME. Google TV launcher. |
| `com.sony.dtv.launcherxcustomizer` | 1.1.7 | Sony's partner config for launcherx (one content provider). |
| `com.sony.dtv.tvx` | 14.1.12-S | Sony TV player: tuner, HDMI inputs, guide. |
| `com.sony.dtv.promos` | 4.8.4 | Sony promos (ads). Disabled by the owner. |
| `com.sony.dtv.sonyselect` | 11.10.3 | Sony Select (promoted apps). Disabled by the owner. |

### Public references for the implementation team

[D] Android / AOSP:

- HOME role and default-home rules (Android 12):
  `https://android.googlesource.com/platform/packages/modules/Permission/+/refs/heads/android12-release/PermissionController/res/xml/roles.xml`
  and `.../PermissionController/src/com/android/permissioncontroller/role/model/HomeRoleBehavior.java`
  in the same tree.
- `cmd package set-home-activity` implementation:
  `https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android12-release/services/core/java/com/android/server/pm/PackageManagerShellCommand.java`
  (`runSetHomeActivity`).
- Intent-filter priority clamping:
  `https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android12-release/services/core/java/com/android/server/pm/ComponentResolver.java`
  (`adjustPriority`).
- RoleManager: `https://developer.android.com/reference/android/app/role/RoleManager`
- TV Input Framework: `https://developer.android.com/reference/android/media/tv/TvInputManager`,
  `https://developer.android.com/reference/android/media/tv/TvInputInfo`,
  `https://developer.android.com/reference/android/media/tv/TvContract`
  (`buildChannelUriForPassthroughInput`).
- Leanback launcher category and TV app basics:
  `https://developer.android.com/training/tv/start/start` and
  `https://developer.android.com/reference/android/content/Intent#CATEGORY_LEANBACK_LAUNCHER`.
- Package visibility (`<queries>`, `QUERY_ALL_PACKAGES`):
  `https://developer.android.com/training/package-visibility`.

[M] Open-source TV launchers and samples:

- AOSP TvSampleLeanbackLauncher (Apache-2.0), Google's minimal reference HOME for Android TV
  builds: `https://android.googlesource.com/device/google/atv/+/refs/heads/main/TvSampleLeanbackLauncher/`
- AOSP Live TV reference app (TV Input Framework client, passthrough inputs):
  `https://android.googlesource.com/platform/packages/apps/TV/`
- FLauncher (GPL-3.0, open-source Android TV launcher, Flutter):
  `https://gitlab.com/flauncher/flauncher`
- Android TV samples (Apache-2.0): `https://github.com/android/tv-samples`

Licensing note for the implementers: Apache-2.0 sources may be reused with attribution. FLauncher
is GPL-3.0; read it for ideas only unless Shart TV is itself GPL-3.0 (owner's call).

### Device identity [O]

- Model `BRAVIA 4K VH21`, device `BRAVIA_VH21`, platform/SoC `mt5895`, build fingerprint
  `Sony/BRAVIA_VH21_UC/BRAVIA_VH21:12/STT2.230505.001.S136/<redacted>:user/release-keys`.
- Serial and account names redacted throughout.

---

## 1. Summary for the implementation team

**What gets replaced.** Only the HOME activity. Shart TV is a normal user-installed APK that
declares a `MAIN` + `HOME` + `DEFAULT` activity and is made the HOME role holder (section 3.5).
It shows: a first row of TV inputs (HDMI 1-4, tuner) that switch the TV with one click, then
the installed TV apps, then shortcuts to Settings and notifications. No ads, no promos, no
telemetry.

**What stays.** launcherx stays installed and enabled. It keeps the system roles a third-party
app can't hold (TV notification panel handler, wellbeing), its background `:coreservices`
process, its content providers that Sony's TV player queries (profile lock / device mode), and
its notification panel. Sony's TV player `com.sony.dtv.tvx` stays and does the actual input
switching and live TV. Sony's customizer stays; Shart TV may read its config (section 3.7).

**How inputs are switched.** Public API only: list inputs with `TvInputManager`, then start an
activity with `ACTION_VIEW` on `TvContract.buildChannelUriForPassthroughInput(inputId)`. On this
TV that resolves to Sony's TV player [O]. No privileged permission is needed for this.

**Biggest risks.**

1. **Disabling launcherx is dangerous.** The HOME role's fallback picks the highest-priority
   HOME activity. Without launcherx the next one is the setup wizard's recovery screen
   (`com.google.android.tungsten.setupwraith/.RecoveryActivity`, priority 1) [O][D]. A
   third-party HOME is capped at priority 0 and is never the automatic fallback [D]. Shart TV
   must be set as HOME explicitly, and launcherx must not be disabled.
2. **The role may not stick.** Updates to launcherx or a factory reset may move HOME back.
   Unverified; see section 12. Shart TV should detect when it is not the HOME holder and say so.
3. **launcherx still runs in the background.** Its "boot to last input" logic and screen-on
   receivers live in `:coreservices`, not in its home activity [F]. Whether they still fire when
   it is not HOME is unverified (section 12).
4. **Things a user app can't get.** Notification-panel role, HDMI-CEC control, all-EPG access,
   other apps' tasks, user management (section 3.3). The design must not depend on them.

---

## 2. Device and platform facts

### 2.1 Platform [O]

- Android 12, API 31 (`ro.build.version.sdk=31`), security patch 2026-05-01, `user` build,
  `release-keys`.
- 32-bit only: `ro.product.cpu.abilist=armeabi-v7a,armeabi`, no 64-bit ABI. Native code (if
  any) must ship `armeabi-v7a`.
- Display: physical 3840x2160, UI override 1920x1080, density 320 (`ro.sf.lcd_density=320`,
  xhdpi). Lay out for 1920x1080 dp-equivalent at xhdpi.
- `ro.build.characteristics=nosdcard,tv`, `ro.product.locale=en-US`.
- Features: `android.software.leanback`, `android.software.leanback_only`,
  `android.software.live_tv`, `android.hardware.type.television`, `android.hardware.hdmi.cec`.
- Two users: user 0 (primary, system, admin) and user 10 (a profile, flags include profile and
  quiet mode; likely a Google TV kids/managed profile). Shart TV targets user 0.
- adb runs over Wi-Fi. Never toggle Wi-Fi from tooling.

### 2.2 Current HOME state [O]

- HOME candidates (`MAIN` + `HOME`), in resolution order:
  1. `com.google.android.apps.tv.launcherx/.home.HomeActivity`, priority 2.
  2. `com.google.android.tungsten.setupwraith/.RecoveryActivity`, priority 1.
  3. `com.android.tv.settings/.system.FallbackHome`, priority -1000.
- launcherx also declares a second HOME activity (a "basic mode" home) that is disabled in its
  manifest [F]; Google TV switches to it in basic / apps-only mode [I].
- Role `android.app.role.HOME` holder (user 0): `com.google.android.apps.tv.launcherx`.
- Preferred activity for `MAIN`/`HOME`/`DEFAULT`: launcherx `.home.HomeActivity`.
- launcherx also holds `android.app.role.SYSTEM_TELEVISION_NOTIFICATION_HANDLER` and
  `android.app.role.SYSTEM_WELLBEING`.
- `android.settings.HOME_SETTINGS` resolves to
  `com.google.android.permissioncontroller/com.android.permissioncontroller.role.ui.HomeSettingsActivity`
  (priority 2) and a TV Settings stub (priority 1). So a default-home picker exists in the
  permission controller; whether it is reachable from the TV's Settings menus is unverified.
- `android.intent.action.ALL_APPS` resolves only to launcherx.
- 26 activities answer `MAIN` + `LEANBACK_LAUNCHER` (the TV app list).

### 2.3 Package flags [O]

| Package | Location | Flags |
|---|---|---|
| launcherx | `/product/priv-app/` + Play update in `/data/app` | system, privileged, updated system app |
| setupwraith | `/product/priv-app/` + Play update | system, privileged |
| launcherxcustomizer | `/system/priv-app/` | system, privileged |
| tvx | `/system/priv-app/` | system, privileged |
| TV Settings | `/system/system_ext/priv-app/`, shared uid `android.uid.system` | system, privileged |
| promos | `/system/priv-app/` + Play update | disabled for user 0 (owner) |
| sonyselect | `/system/priv-app/` | disabled for user 0 (owner) |

The owner's earlier debloat (promos, Sony Select and others) has a rollback script in
`../scripts/sharttv.py rollback`.

### 2.4 TV inputs [O]

TV Input Framework input ids on this TV (`dumpsys tv_input`):

| Input id | What it is |
|---|---|
| `com.sony.dtv.tvinput.external/.ExternalTvInputService/HW1` .. `HW4` | HDMI 1-4 (hardware type HDMI, ports 1-4) |
| `com.sony.dtv.tvinput.external/.ExternalTvInputService/HW5` | Composite/AV (hardware type composite) |
| `com.sony.dtv.tvinput.tuner/.AtscTunerInputService/HW0` | ATSC 1.0 tuner |
| `com.sony.dtv.tvinput.atsc3tuner/.app.tif.Atsc3TvInputService/HW0` | ATSC 3.0 tuner |

- Per-input state from the input manager: HDMI inputs report state 2 at the time of the dump
  (`INPUT_STATE_DISCONNECTED` per [D] TvInputManager constants), tuners state 0 (connected).
  Whether HDMI state tracks a live source on this TV is unverified (section 12).
- User-set input names are stored in Settings.Secure `tv_input_custom_labels` (the owner has
  named all four HDMI ports) and hidden inputs in `tv_input_hidden_inputs` (the owner hid AV and
  both tuners) [O]. Apps should read these through the public API, not the raw keys:
  `TvInputInfo.loadCustomLabel(Context)` and `TvInputInfo.isHidden(Context)` [D]. The input
  row shows custom labels and skips hidden inputs.
- Input-type and HDMI-CEC device info come from `TvInputInfo.getType()` and
  `TvInputInfo.getHdmiDeviceInfo()` [D].

### 2.5 Switching an input [O][D]

- `ACTION_VIEW` with data `TvContract.buildChannelUriForPassthroughInput(inputId)` (a
  `content://android.media.tv/passthrough/<encoded input id>` URI) and type
  `vnd.android.cursor.item/channel` resolves to exactly one activity:
  `com.sony.dtv.tvx/.MainActivity` [O]. Sony's TV player then shows that input.
- `ACTION_VIEW` on a channel URI (`TvContract.buildChannelUri(id)`) resolves to the same
  activity [O][F].
- When starting the intent, let the platform resolve the type (pass the URI only, as AOSP Live
  TV and other launchers do); the type is what makes it match on this TV [O].
- Embedding a picture with `TvView` is the other public route [D][M]; the first release should
  hand off to Sony's player instead (simpler, no surface or audio focus issues).

---

## 3. HOME-app contract

### 3.1 Manifest: what makes an app a HOME on Android 12 [D][M][O]

- One exported activity with an intent filter: action `android.intent.action.MAIN`, categories
  `android.intent.category.HOME` and `android.intent.category.DEFAULT`. The HOME role's
  required component is `MAIN` + `HOME`, and the platform resolves HOME with the default-only
  flag, so `DEFAULT` must be present [D roles.xml, HomeRoleBehavior].
- Suggested activity attributes, from the AOSP TvSampleLeanbackLauncher [M] and observed on
  launcherx's home activity [F]: `launchMode="singleTask"`, `resumeWhilePausing="true"`,
  `stateNotNeeded="true"`, `excludeFromRecents="true"`, `exported="true"`.
- `android:priority` on the HOME filter is pointless for Shart TV: priorities above 0 are capped
  to 0 for non-privileged apps [D ComponentResolver.adjustPriority]. launcherx has 2, the
  setup wizard recovery screen has 1 [O]. Shart TV becomes HOME only by holding the role.
- `<uses-feature android:name="android.software.leanback" android:required="false"/>` and
  `android.hardware.touchscreen required="false"` [D].
- Shart TV should also declare its own `MAIN` + `LEANBACK_LAUNCHER` activity (with a banner) so
  it shows up in app lists and can be launched when it is not HOME [D].
- Optional: also handle `android.intent.action.ALL_APPS` (`DEFAULT` category) so "all apps"
  requests from other apps can reach Shart TV. launcherx is the only current handler [O]; with
  two handlers the system would ask the user, so leave this off by default.
- `LEANBACK_LAUNCHER` is not a HOME category. launcherx's home filter has no
  `LEANBACK_LAUNCHER` [F]; it is the category every TV app uses for its own entry point, and the
  launcher queries it to list apps [D].

### 3.2 Package visibility and app list [D][O]

- Shart TV targets API 31+, so package visibility applies. To list TV apps it needs either a
  `<queries>` element with an intent (`MAIN` + `LEANBACK_LAUNCHER`) or `QUERY_ALL_PACKAGES`.
  `QUERY_ALL_PACKAGES` is `normal` on this TV [O]; it is sideloaded, so Play policy doesn't
  apply. `<queries>` is enough and preferred.
- Also query `TvInputService` visibility if needed: `TvInputManager.getTvInputList()` needs no
  permission [D].
- Refresh the list on `PACKAGE_ADDED`, `PACKAGE_REMOVED`, `PACKAGE_CHANGED` (registered at
  runtime while running) or via `LauncherApps.Callback` [D].

### 3.3 Permissions: what launcherx uses vs what Shart TV can get

Protection levels are as defined on this TV [O]. "Can get" means a user-installed app,
with or without an owner-run `adb shell` grant.

**Granted at install (normal) - Shart TV may declare these:**

| Permission | Use in Shart TV |
|---|---|
| `android.permission.INTERNET` | Only if a feature needs it (none planned). |
| `android.permission.ACCESS_NETWORK_STATE`, `ACCESS_WIFI_STATE` | Network status icon. |
| `android.permission.RECEIVE_BOOT_COMPLETED` | Not needed for HOME (HOME starts on boot). |
| `android.permission.QUERY_ALL_PACKAGES` | Optional, see 3.2. |
| `android.permission.SET_WALLPAPER` | Not needed. |
| `android.permission.WAKE_LOCK` | Not needed. |
| `com.android.providers.tv.permission.READ_EPG_DATA` | Normal on this TV. Reading other inputs' channels may still be restricted by the TV provider; unverified. |
| `com.android.providers.tv.permission.WRITE_EPG_DATA` | Normal. Not needed. |

**Runtime (dangerous):** `android.permission.READ_TV_LISTINGS` (channel/program listings
for "now on TV", optional).

**Owner-grantable from adb shell (development / appop):** `pm grant` works for permissions
flagged `development` on user builds, and `appops set` for appop permissions [D]. None are
needed for the first release. Listed so the design knows the ceiling:

| Permission | Level [O] | Possible use |
|---|---|---|
| `android.permission.PACKAGE_USAGE_STATS` | `signature|privileged|development|appop` | "Recently used" app ordering. Optional. |
| `android.permission.WRITE_SECURE_SETTINGS` | `signature|privileged|development` | Not needed. Do not use. |
| `android.permission.SYSTEM_ALERT_WINDOW` | `...|appop|...` | Not needed. |
| Notification listener (bind permission `BIND_NOTIFICATION_LISTENER_SERVICE`, signature) | user/shell enables the listener, e.g. `cmd notification allow_listener` | Notification badge on the home screen. Optional, later. |

**Not available to Shart TV (signature/privileged/role) - design must not depend on these:**

| Permission or role | Level [O] | What launcherx uses it for [F][I] |
|---|---|---|
| `com.android.providers.tv.permission.ACCESS_ALL_EPG_DATA` | signature/privileged | Live TV rows across all inputs. |
| `android.permission.HDMI_CEC` | signature/privileged/vendor | CEC device info and control. |
| `android.permission.TV_INPUT_HARDWARE`, `CAPTURE_TV_INPUT` | signature/privileged | (Not used by launcherx; noted because input hardware is off-limits.) |
| `android.permission.ACCESS_NOTIFICATIONS` | signature/privileged/appop | Notification panel. |
| `android.permission.STATUS_BAR_SERVICE` | signature | Guards the notification panel entry. |
| `android.permission.MANAGE_USERS`, `INTERACT_ACROSS_USERS` | signature/privileged | Profiles (kids, accounts). |
| `android.permission.REAL_GET_TASKS` | signature/privileged | Running tasks. |
| `android.permission.FORCE_STOP_PACKAGES`, `DELETE_PACKAGES`, `CHANGE_COMPONENT_ENABLED_STATE`, `SUSPEND_APPS` | signature/privileged or role | App management, kids restrictions. |
| `android.permission.READ_DREAM_STATE`, `WRITE_DREAM_STATE` | signature/privileged | Screensaver control. |
| `android.permission.GET_ACCOUNTS_PRIVILEGED` | signature/privileged | Google account profile. |
| `android.permission.MEDIA_CONTENT_CONTROL` | signature/privileged | "Now playing" controls. |
| `android.permission.READ_LOGS`, `DUMP` | signature/privileged/development | Diagnostics. Shart TV doesn't need them. |
| `android.permission.READ_CONTENT_RATING_SYSTEMS` | signature/privileged | Parental ratings. |
| Roles `SYSTEM_TELEVISION_NOTIFICATION_HANDLER`, `SYSTEM_WELLBEING` | system roles | Notification panel, screen-time. |

launcherx's remaining permissions (ad id `com.google.android.gms.permission.AD_ID`, Google
services config, cloud messaging, Play setup, camera, microphone, assistant hotword, Netflix
partner permission, Google TV internal permissions) are tied to ads, tracking, accounts or
Google-only features. Shart TV declares none of them.

### 3.4 How the system picks the HOME on this build [O][D]

- Android 12 maps "default home" to the exclusive role `android.app.role.HOME`, held per user
  [D roles.xml]. The preferred activity for `MAIN`/`HOME` follows the role holder [O 2.2, D].
- The role is available on any non-work-profile user [D HomeRoleBehavior.isAvailableAsUser].
  Whether it is shown in the TV's Settings UI depends on the resource `config_showDefaultHome`
  [D]; on this TV the role screen resolves (2.2) but whether Settings links to it is unverified.
- **Fallback holder:** if no holder is set or the holder goes away, the permission controller
  picks the HOME activity with the strictly highest priority, skipping the Settings app's
  fallback home, and picks nobody on a tie [D HomeRoleBehavior.getFallbackHolder]. On this TV
  that is launcherx (2), then setupwraith RecoveryActivity (1) [O]. Shart TV (capped at 0) is
  never the fallback.
- Consequence: if Shart TV is uninstalled or loses the role, HOME goes back to launcherx. If
  launcherx were disabled, HOME would go to the setup wizard's recovery screen. Do not disable
  launcherx.

### 3.5 Setting Shart TV as HOME (owner action) [O][D]

Not run by the spec team (no settings changes on the TV). What is known:

- `cmd package set-home-activity [--user N] <package or component>` exists on this build [O
  `cmd package help`]. In Android 12 it only uses the package name and calls
  `RoleManager.addRoleHolderAsUser(ROLE_HOME, pkg, ...)`, printing `Success` or
  `Error: Failed to set default home.` [D PackageManagerShellCommand.runSetHomeActivity].
- `cmd role add-role-holder [--user N] android.app.role.HOME <package>` is the direct role
  route; `cmd role` on this TV offers add/remove/clear only, no `get` [O]. Read the holder with
  `dumpsys role` instead [O].
- The adb shell (`com.android.shell`) holds `MANAGE_ROLE_HOLDERS`, `SET_PREFERRED_APPLICATIONS`
  and `BYPASS_ROLE_QUALIFICATION` on user 0 [O]. So both commands should work from adb shell
  without root. **Unverified until the owner runs it** (section 12, Q1).
- The role still has to qualify: Shart TV must be installed for user 0 and have the `MAIN` +
  `HOME` + `DEFAULT` activity from 3.1 [D].
- Alternative without adb: start `android.settings.HOME_SETTINGS`, which opens the permission
  controller's default-home screen [O 2.2]. Shart TV can offer a "Make Shart TV the home
  screen" button that starts this intent, and can also call
  `RoleManager.createRequestRoleIntent(ROLE_HOME)` [D] (whether the TV shows the request
  dialog is unverified, Q2).
- Rollback (owner action): `cmd package set-home-activity com.google.android.apps.tv.launcherx`,
  or uninstall Shart TV (the fallback then returns to launcherx, 3.4).
- Shart TV should check `RoleManager.isRoleHeld(ROLE_HOME)` [D] on start and show a one-line
  hint if it was opened as a normal app while not HOME.

### 3.6 What other apps on this TV expect from the HOME app

**Going home.** Sony's TV player returns to home by starting an implicit intent (`MAIN` +
category `HOME`, new task) when it can't tune at boot [I]. The HOME key and the system's
`startHome` path also resolve the role holder [D]. So Shart TV receives these as a HOME with no
extra work.

**Input selection broadcasts from Sony's TV player** [I], interface names [F/I]:

- `android.apps.tv.launcherx.INPUT_SELECTED`, extra `extra_input_id` (TIF input id string). Sent
  explicitly to package `com.google.android.apps.tv.launcherx` and protected by
  `com.android.providers.tv.permission.WRITE_EPG_DATA`. launcherx's receiver for it runs in
  `:coreservices` and records the last used input [F]. **Shart TV cannot receive this one**
  (targeted at another package). It is the stock "last used input" feed.
- `com.sony.dtv.tvplayer.intent.action.INPUT_CHANGED`: sent both implicitly and then explicitly
  to every manifest receiver that matches it, with no permission [I]. **Shart TV can receive
  it** with a manifest receiver. Extras (all optional, string unless noted):
  - `com.sony.dtv.tvplayer.intent.extra.INPUT`: main input type, `EXTERNAL_INPUT` or `TUNER`,
    or null when the TV player stopped.
  - `com.sony.dtv.tvplayer.intent.extra.INPUT_ID`: main TIF input id, null when stopped.
  - `com.sony.dtv.tvplayer.intent.extra.INPUT_2`, `...extra.INPUT_ID_2`: sub-picture (twin
    picture / PIP) type and id.
  - `com.sony.dtv.tvplayer.intent.extra.VIEWMODE`: twin-picture mode name or null.
  - `com.sony.dtv.tvplayer.intent.extra.FOCUS` (int): 1 main, 2 sub.
  - `com.sony.dtv.tvplayer.intent.extra.PIP_SUBSCREEN_POSITION`: PIP position or null.
  - `com.sony.dtv.tvplayer.intent.extra.SHOW_GUIDE` (boolean), `...extra.SHOW_STB` (boolean).
  - Sent only when something changed since the last send [I].
  - Use: Shart TV keeps its own "last input" from this broadcast and highlights it first in the
    input row.

**Things that stay with launcherx** (Shart TV must not try to replace them):

- Notification panel: `android.app.action.TOGGLE_NOTIFICATION_HANDLER_PANEL` and
  `OPEN_NOTIFICATION_HANDLER_PANEL` go to launcherx's dashboard, guarded by
  `STATUS_BAR_SERVICE` [F]; launcherx holds the TV notification handler role [O]. Shart TV's
  "notifications / quick settings" tile can simply start the platform's notification panel path
  if one is public, or link to Settings; exact public route is Q5.
- Profile lock and device mode: Sony's TV player queries
  `content://com.google.android.apps.tv.launcherx.coreservices.DeviceModeContentProvider/queryDeviceMode`
  (first row: account mode int, profile lock status int) and opens
  `android.apps.tv.launcherx.PROFILE_LOCK_REAUTH` in launcherx when a profile is locked [I].
  These keep working only while launcherx is installed and enabled.
- Remote "global" buttons (`android.intent.action.GLOBAL_BUTTON`) and the assistant/remote
  button config go to launcherx's `:coreservices` receivers [F].
- Partner config trigger `com.google.android.apps.tv.launcherx.action.PARTNER_CONFIGURATION`
  and the setup wizard's partner customization are launcherx's business [F][I].

### 3.7 What Sony's launcherxcustomizer provides (interface only)

- One exported content provider, authority `tvlauncher.config`, no permission [F].
- One URI: `content://tvlauncher.config/configuration`. `openFile` (read) returns an XML document,
  MIME `text/xml` [I]; `query` returns nothing; insert/update/delete/getType are unsupported [I].
- Readable from adb shell: `content read --uri content://tvlauncher.config/configuration`
  returns about 42 KB of XML [O]. Shart TV can read it with
  `ContentResolver.openInputStream` [D]; it must tolerate the provider being absent.
- The file is chosen by `Build.DEVICE`: a few newer models get an alternate file, everything
  else (including `BRAVIA_VH21`) gets the worldwide one [I]. The two differ only in app lists.
- Schema [F] (element names are interop names; values are package names or URIs):
  - Root `configuration`, children `country` with attribute `value` = a space-separated list of
    ISO country codes, or `all` (applies everywhere), or `default` (used when no list matches).
  - In `all`: `basic-mode-live-tv-app-uri` and `basic-mode-epg-app-uri`, each with a `value`
    holding an `android-app://` URI. Live TV app is `com.sony.dtv.tvx`; the guide is an intent
    URI for `com.sony.dtv.tvx` action `com.sony.dtv.intent.action.GUIDE` on its event-handler
    activity [F]. Shart TV can use these two to open "Live TV" and "Guide" without hard-coding
    Sony names.
  - Per country: `partner-system-apps-basic-mode-order` (ordered `app` elements, attribute
    `package_name`): the preferred app order for basic mode.
  - `basic-mode-blocked-apps` with `partner-basic-mode-blocked-apps` and
    `google-basic-mode-blocked-apps` (`app` / `package_name`): apps hidden in basic mode. The
    partner list includes Sony Select and Sony's promo apps [F].
  - `notifications-allowed-apps` (`app` / `package_name`): apps allowed to post to the TV
    notification panel.
  - `oem-tab-content-provider-uri` (`content://com.sony.dtv.sonytab.TabProvider/data`) and
    `oem-tab-content-provider-package-name` (`com.sony.dtv.sonytab`): Sony's content tab on the
    home screen (promotional content feed). **Shart TV ignores this.**
- The customizer has no activities, services or receivers and no permissions of its own [F].
  launcherx reads this provider and knows these element names [I].
- Use in Shart TV: optional. Read the live-TV and guide URIs from `all`; the app-order list for
  the owner's country (`US CA`) may seed the default app order. Never show OEM tab content.

---

## 4. Home-screen layout and behavior

Shart TV's home screen is the owner's reaction to launcherx: no ads, no sponsored rows, the
input/source switcher first, fast and quiet. Design, not a copy of launcherx's layout.

### 4.1 What is shown, top to bottom [D][M]
- **Inputs row first.** HDMI 1-4 and other sources as large tiles, left to right, last-used
  first (see 4.3). This is the inverse of the stock launcher, where inputs are buried behind a
  menu. One press from home to any input.
- **Apps row.** The owner's installed TV apps (`MAIN` + `LEANBACK_LAUNCHER`, section 3.2), in a
  user-arrangeable order; default order may be seeded from the customizer app-order list for
  `US CA` (section 3.5), minus anything the owner hides. No "sponsored" or "featured" app slots.
- **Optional Live TV / Guide tiles**, opened via the customizer's `basic-mode-live-tv-app-uri`
  and `basic-mode-epg-app-uri` (section 3.5) so Sony's names are not hard-coded.
- **Settings / power / clock** affordances.
- **No** OEM promo tab, **no** Google "Top picks"/recommendation rows, **no** content-feed rows.

### 4.2 Rows and focus [D]
- Build with Leanback (`BrowseSupportFragment`/`VerticalGridView`) or Compose-for-TV [M]. DPAD
  focus must be obvious and never trapped; BACK always returns to the inputs row.
- Remember the last focused tile across resume (`stateNotNeeded`/`singleTask`, section 3.1).

### 4.3 Input switching (public APIs only) [D][O]
- Enumerate inputs with `TvInputManager.getTvInputList()` (no permission) [D]. On this TV the
  physical HDMI inputs present as hardware inputs HW1-HW4 [O].
- Switch by starting `ACTION_VIEW` on the input's passthrough URI
  (`TvContract.buildChannelUriForPassthroughInput(inputId)`); on this TV that hands off to Sony's
  TV player for that input [O]. No CEC permission required to request the switch.
- **Last-used-first ordering:** Shart TV registers a receiver for
  `com.sony.dtv.tvplayer.intent.action.INPUT_CHANGED`, which Sony's TV player broadcasts to any
  app with no permission required [O]. Its extras carry the current input; Shart TV records it and
  floats that tile to the front of the inputs row. (The companion "input selected" broadcast is
  sent only to the Google launcher and is not receivable by Shart TV [O] — so we use
  INPUT_CHANGED, section 12 tracks confirming its extras.)
- HDMI connected/disconnected state: all four HDMI ports read disconnected at sweep time even
  though a source was on [O]; whether `TvInputManager` state callbacks fire reliably here is open
  question (section 12). Shart TV shows all inputs regardless and does not gray out on that signal
  until verified.

### 4.4 Layout measurements (stock launcher, for sizing reference)

Added 2026-10-22 (stage 2). These are the stock launcher's effective sizes on this TV, so Shart
TV can feel familiar on the same panel. They are a reference, not a target: section 4.1-4.3
decide what Shart TV shows, and any value below may be changed for legibility. Where a range
is given, anything inside it is fine.

**Reference frame [O].** Panel 1920x1080 px at density 320 (xhdpi), so 1 dp = 2 px and the
screen is **960 x 540 dp**. Font scale is the default (1.0), so 1 sp = 1 dp. The system runs in
night (dark) UI mode. No display cutout or rounded screen corners. Values below come from the
stock launcher's resources resolved for this configuration (television, landscape, large,
height >= 480 dp, night) [I] unless tagged otherwise. The stock launcher reserves no overscan
margin beyond the side margins below.

#### Home screen frame

| Measurement | Stock value | Acceptable for Shart TV | Tag |
|---|---|---|---|
| Left/right content margin (row start and end inset, top bar inset) | 58 dp | 48-64 dp | [I] |
| Content width between margins | 844 dp (960 - 2 x 58) | follows from margin | [I] |
| Top bar: space above the tab/nav row | 27 dp | 20-32 dp | [I] |
| Top bar: nav row height | 44 dp | 40-56 dp | [I] |
| Top bar: space below nav row before content | 48 dp | 24-48 dp | [I] |
| Total top bar band (27 + 44 + 48) | about 119 dp | 90-130 dp | [I] |
| Vertical padding above and below each row's card strip | 8 dp each | 6-12 dp | [I] |
| Row header strip height | 39 dp | 32-44 dp | [I] |
| Gap between row header and its cards | 4.5 dp | 4-8 dp | [I] |
| Featured/hero area at the top of the home tab (promo carousel) | about 365 dp tall, 16 dp above, 8 dp below | **Not copied** (section 9) | [I] |
| Screen background | near-black, very slightly cool (about 5-6% grey) | any near-black | [I] |

**Rows visible at once [I], derived.** An app row is about 39 + 4.5 + 8 + 110.5 + 8 = 170 dp
tall. Below the 119 dp top band that leaves about 420 dp, so **two full card rows plus the top
of a third** fit on screen. On the stock home tab the hero area takes most of the first screen,
so only one row is fully visible until the user scrolls. Shart TV has no hero area, so the
inputs row and apps row are both fully visible on the first screen at these sizes.

**Cards visible per row [I], derived.** With the 196 x 110.5 dp app card and 20 dp gap, about
4 full cards plus part of a fifth fit in a row; with the compact 124 x 70 dp size, about 6.

#### Cards

| Measurement | Stock value | Acceptable for Shart TV | Tag |
|---|---|---|---|
| App card (banner) aspect | 16:9 | 16:9 (Android TV app banners are 16:9 [D]) | [I] |
| App card size, standard | 196 x 110.5 dp | 180-210 dp wide | [I] |
| App card size, compact variant | 124 x 70 dp | 110-140 dp wide | [I] |
| Other card heights the stock launcher uses (content rows) | 86, 110.5, 151, 232 dp | for the inputs row, 86-151 dp | [I] |
| Content card aspects in use | 16:9 (most), 1:1, 2:3 (posters), about 2.45:1 (wide promos) | 16:9 for inputs and apps | [I] |
| Gap between cards in a row | 20 dp | 12-24 dp | [I] |
| Focused card scale | 1.10 | 1.05-1.12 | [I] |
| Pressed-while-focused scale (some card types) | 1.05 | 1.0-1.05 | [I] |
| Larger cards use smaller focus scales | 1.075, 1.05, 1.025 steps | scale down as cards grow | [I] |
| Focus animation duration (scale + elevation) | 150 ms | 120-250 ms | [I] |
| Focus animation curve | platform default ease-in-out for app/content cards; design-system cards use a fast-out, slow-settle curve, 250-300 ms in, 350-500 ms out | any ease-out style curve | [I] |
| Focus elevation (shadow) | 0 dp unfocused, 4 dp focused (6 dp on some design-system cards) | 2-8 dp | [I] |
| Focus outline / ring on cards | none found; focus is shown by scale + shadow | Shart TV may add a 2-3 dp light outline for clarity | [I] |
| Card text fade/slide when focus changes | 150 ms | 100-200 ms | [I] |
| Gap between card image and its text block | 9 dp | 6-12 dp | [I] |
| Card corner radius | **evidence gap** (see below) | 8-16 dp | - |
| Label visibility | per-row setting with three modes: always, only when the card is focused, only when the row is focused | Shart TV: app name under the focused card only; input names always | [I] |

#### Row headers

| Measurement | Stock value | Acceptable for Shart TV | Tag |
|---|---|---|---|
| Header strip height | 39 dp, text vertically centred, starts at the 58 dp margin | 32-44 dp | [I] |
| Header text nominal size | 32 dp (the strip's text style) | Shart TV: 18-24 sp | [I] |
| Optional header icon | 32 dp tall, 10-16 dp before the text | optional | [I] |
| Optional subtitle line under header | 16 sp, 22 dp line, 12 dp below | not needed | [I] |
| Unfocused rows | header text fades with the row's selection level (full opacity on the focused row, dimmed on others) | do the same | [I] |

#### Inputs and sources (stock)

- **How inputs are reached [I][F].** There is no inputs row on the stock home screen. Inputs
  are reached through (a) the TV's input button, which Sony's TV player handles (remote-spec),
  (b) an optional input shortcut the user can pin in the top bar's shortcuts area, and
  (c) a recently-watched-input entry and the last-used-input logic in the background process, which
  can return to the last input at power-on. This is the behavior Shart TV fixes (4.1).
- **Input tiles [I].** The stock launcher has a dedicated card type for TV inputs, rendered as a
  square (1:1) card with the input name in a single line inside the card, 15 dp in from the
  edge, near-white text. The card's height comes from the row it sits in (one of the card
  heights above); the exact height used for inputs is an evidence gap.
- Shart TV inputs row (design, not copied): 16:9 tiles at 160-196 dp wide, input name always
  shown, last-used input first (4.3).

#### Top bar

| Item | Stock value | Acceptable for Shart TV | Tag |
|---|---|---|---|
| Nav row | horizontally scrolling tab row, 44 dp tall, 50 dp fading edge at the ends | Shart TV may use a plain row | [I] |
| Tab chip | 40 dp tall, 14 dp horizontal and 4 dp vertical padding, 16 sp text | 36-44 dp tall | [I] |
| Tab focus | no scale; the chip fills with a light colour, its text turns near-black, 4 dp elevation, 300 ms ease-out | a filled light pill on focus is a good pattern | [I] |
| Tab text unfocused | near-white at about 70% opacity | 60-80% | [I] |
| Round icon buttons in the nav row (search, profile) | 32 dp, 12 dp apart, about 60% opacity when unfocused | 32-40 dp | [I] |
| Shortcuts area (pinned shortcuts such as an input) | pill-shaped group with 6 dp inner padding | optional | [I] |
| Clock | **evidence gap** (built in code, size not in resources) | 16-20 sp, top right, at the 58 dp margin | - |
| Settings / notifications | opens a side panel (below), not a top-bar menu | Shart TV: Settings tile/button | [I][F] |

**Side panel (settings + notifications) [I].** A floating window on the right edge, **272 dp
wide**, full height minus 16 dp at top and bottom, vertically centred, with a 24 dp margin from
the right edge; it animates in from off-screen right. Its list uses 12 dp spacing between items. This panel belongs to the
notification-handler role (section 6); Shart TV doesn't rebuild it.

#### Typography

The stock launcher uses the public Material 3 type scale [I][D]
(`https://m3.material.io/styles/typography/type-scale-tokens`), in two proprietary Google
sans faces. Shart TV must use an open font instead (for example Roboto, which ships with
Android, or Inter/Noto Sans under the OFL).

| Role | Size | Letter spacing (em) | Used for (stock) | Tag |
|---|---|---|---|---|
| Display L / M / S | 57 / 45 / 36 sp | 0 | large hero titles | [I] |
| Headline L / M / S | 32 / 28 / 24 sp | 0 | page and hero titles (28 sp most common) | [I] |
| Title L / M / S | 22 / 16 / 14 sp | 0 | section titles, card titles | [I] |
| Body L / M / S | 16 / 14 / 12 sp | 0 / 0 / 0.008 | descriptions, metadata | [I] |
| Label L / M / S | 14 / 12 / 11 sp | 0 / 0.008 / 0.009 | buttons, chips, badges | [I] |
| Tab text | 16 sp | 0.006 | top nav tabs | [I] |
| Smallest caption | 10 sp | 0.008 (0.09 for all-caps tags) | badges, panel captions | [I] |

Shart TV guidance: never go below 12 sp for anything the user must read at TV distance; use
Title M (16 sp) for card labels and 20-24 sp for row headers.

#### Colors (general description only) [I]

- Background: near-black with a faint cool tint, opaque.
- Primary text: near-white, slightly warm-grey, about 90% luminance, fully opaque.
- Secondary text: mid grey, about 50% luminance.
- De-emphasis by opacity: dimmed icons at about 60% opacity; some captions at 40%.
- Focused elements are not recoloured much; they get larger and cast a shadow.
- Shart TV picks its own palette. No brand colours, logos or artwork are to be copied.

#### Evidence gaps (not determinable statically; check on screen with the owner)

1. **Card corner radius.** Applied in code at image-load time, value not found in resources.
   Shape radii present in the app cluster at 12, 16 and 20 dp. Use 8-16 dp.
2. **Row header rendered size.** The strip's text style is 32 dp, but the header view supports
   a runtime scale and opacity animation; the on-screen size may be smaller. Measure from a
   screenshot only if the owner takes one.
3. **Clock size and position**, and the profile avatar size: built in code.
4. **Input tile height** in the stock input surfaces.
5. **Whether the stock apps row uses the standard (110.5 dp) or compact (70 dp) card** on this
   TV: both exist and the choice is server-driven per row.
6. A live view-hierarchy dump of the stock home screen was not possible during this session (the
   TV was in use and the home activity was not on screen); no screen state was changed to get one.

## 5. Becoming and staying HOME

Covered mechanically in section 3.4-3.5. Behavior Shart TV must get right:
- On first run, Shart TV shows a one-screen "set me as your home screen" prompt that deep-links to
  the TV's default-home setting, or tells the owner the one `adb` line
  (`cmd package set-home-activity com.sharttv`); the spec team does not run it (section 3.5).
- Shart TV never tries to disable launcherx or claim the role by priority (it can't; section 3.4).
  It holds the role or it doesn't.
- If Shart TV loses the role (update, role reset), it detects it is not the HOME holder on next
  launch (`RoleManager.isRoleHeld(ROLE_HOME)` is readable [D]) and re-offers the prompt. Whether
  the role survives reboot and launcherx updates is open question 1/2 (section 12).

## 6. Notifications and the system panel (what we cannot own) [O][D]
- The TV notification panel is a privileged/role surface (`ACCESS_NOTIFICATIONS`,
  `STATUS_BAR_SERVICE`, `SYSTEM_TELEVISION_NOTIFICATION_HANDLER`), off-limits to a user app
  (section 3.3). Shart TV does **not** reimplement it.
- Shart TV may show its own lightweight badge using a notification-listener service the owner
  enables over adb (`cmd notification allow_listener`, section 3.3), optional and later.
- How a normal app opens the stock panel at all (if we want a shortcut tile to it) is open
  question (section 12).

## 7. Settings and quick controls [D]
- Deep-link to the real Settings via public intents (`android.settings.*`) and the input/Wi-Fi
  screens; do not reimplement them.
- A quick panel on long-press HOME offers: input switch, Wi-Fi, display/sound settings link, and
  the Shart TV debloat toggles are **not** here (those are host-side scripts, not in-app, since a
  user app can't disable other packages — `CHANGE_COMPONENT_ENABLED_STATE` is privileged, 3.3).

## 8. Branding and app identity
- Package: `com.sharttv` (final name TBD by owner). Home activity `com.sharttv.HomeActivity`,
  `LEANBACK_LAUNCHER` entry with a banner. Shart TV name/art per the project's style; no Sony or
  Google marks.
- Same family as the owner's other projects; match their naming and any easter eggs as the owner
  decides.

## 9. Behaviors NOT to copy (the reason for the rewrite) [I][O]
Numbered, each with what Shart TV does instead. These are effects observed/inferred from the stock
stack, written as requirements — no stock code is reproduced.
1. **Ad / sponsored / "featured" rows** on the home screen (Google surfaces + Sony promos + Sony
   Select + the OEM promo tab). Shart TV shows none; it has no ad-id permission and no promo feed.
2. **Recommendation/content feeds** that pull titles and watch data. Shart TV has no recommender
   and makes no content calls; `INTERNET` is only declared if a future feature needs it.
3. **Advertising ID** (`com.google.android.gms.permission.AD_ID`) and account/tracking perms.
   Shart TV declares none (section 3.3).
4. **Input switcher buried** behind menus. Shart TV puts inputs first (section 4.1).
5. **Source swapper hard to reach.** Shart TV binds it to the input button and the top row, and
   can remap the remote's dedicated buttons to it (remote-spec section 2.2/3).
6. **OEM promo tab** content provider. Shart TV ignores `oem-tab-*` (section 3.5).
7. **Telemetry on launch/usage.** Shart TV emits none. (The device-wide ACR/telemetry apps are
   handled host-side; see SWEEP-REPORT.md — already disabled.)

## 10. What we keep working (don't break) [O]
- Live TV and Guide reachable via the customizer URIs (section 3.5).
- The stock launcherx stays installed (HOME fallback + privileged surfaces it owns, section 3.4);
  Shart TV simply holds the HOME role instead.
- The physical remote and CEC/soundbar control (remote-spec).
- Any app the owner already uses, including `com.hione.tv` (his own LEANBACK launcher, v0.2.1,
  present on the TV) — Shart TV lists it like any other app.

## 11. Test plan
Owner runs any state-changing step. Baseline the stock launcher first where possible.
1. **Install / build:** Shart TV installs as a user app; its HOME and LEANBACK activities resolve
   (`cmd package query-activities -c android.intent.category.HOME` shows it).
2. **Become HOME:** owner runs `cmd package set-home-activity com.sharttv`; `dumpsys role` shows
   Shart TV holds `android.app.role.HOME`; pressing HOME lands on Shart TV.
3. **Inputs:** every HDMI input switches in one press via the passthrough URI; last-used input
   floats to front after an INPUT_CHANGED broadcast.
4. **Apps:** installed TV apps list correctly; add/remove an app and confirm the row refreshes.
5. **No-ads check:** home screen shows no sponsored/promo/recommendation rows; no network calls
   on idle home (confirm with a `/proc/net` snapshot while home is foreground).
6. **Survive reboot:** after reboot Shart TV is still HOME and still ad-free; confirm role held.
7. **Survive launcherx update:** after a launcherx update, re-check the role (open question 2).
8. **Rollback:** owner sets home back to launcherx (`set-home-activity
   com.google.android.apps.tv.launcherx`); stock behavior returns.

## 12. Open questions (answer on the TV, owner runs anything that changes state)

1. Does `cmd package set-home-activity <shart package>` (or `cmd role add-role-holder
   android.app.role.HOME <shart package>`) print `Success` from adb shell, and does the HOME key
   then open Shart TV? Check `dumpsys role` and the preferred activities afterwards.
2. Does `RoleManager.createRequestRoleIntent(ROLE_HOME)` show a dialog on this TV, and is the
   default-home screen reachable from Settings (resource `config_showDefaultHome`)?
3. Does the HOME role survive: a launcherx update from Play, a reboot, a standby/wake cycle,
   and the setup wizard's recovery flows?
4. With Shart TV as HOME, does launcherx's `:coreservices` still do "boot to last input"
   (screen-on starts the last HDMI input instead of HOME)? Is that wanted? Sony's TV player also
   has its own app-on-boot setting.
5. What is the public route to open the TV notification / quick settings panel from a
   third-party app on this build? (launcherx's dashboard entry is guarded by
   `STATUS_BAR_SERVICE`.)
6. Do HDMI inputs report `INPUT_STATE_CONNECTED` when a source is powered on? (All four read
   disconnected at dump time.) This decides whether the input row can grey out empty ports.
7. With `READ_EPG_DATA` (normal) or `READ_TV_LISTINGS` (runtime), can a third-party app read
   tuner channels from `TvContract`? (adb shell's own query returned no rows.)
8. Does `INPUT_CHANGED` arrive at a third-party manifest receiver on this build (background
   broadcast limits apply to implicit sends; Sony also sends it explicitly per receiver)?
9. Which key does the remote's input button send, and which app handles it? Shart TV doesn't
   replace that, but the input row should behave the same.

---

## 13. Capability inventory: what launcherx does

Added 2026-10-22 (stage 2). Every user-visible or system-facing capability found in the stock
launcher, with a verdict for Shart TV. Sources: launcherx's manifest and code [F][I], resolver
queries on the TV [O], public docs [D]. Interop names (actions, authorities, permissions) are
given only where something else depends on them.

Verdicts:
- **SHOULD**: worth replicating; the public API to use is named.
- **CAN'T**: needs a signature/privileged permission or a system role a user app can't hold
  (named). launcherx stays installed and keeps doing it (section 10).
- **WON'T**: ads, tracking, telemetry, Google-account content, or not wanted.

### 13.1 Prioritized SHOULD list (implementation order)

| # | Capability | Public API / interface | Spec ref |
|---|---|---|---|
| 1 | Be the HOME screen and know when it isn't | `MAIN`+`HOME`+`DEFAULT` activity; `RoleManager.isRoleHeld(ROLE_HOME)`; offer `android.settings.HOME_SETTINGS` (resolves to the permission controller's home picker on this TV [O]) | 3.1, 3.5, 5 |
| 2 | Inputs row, one press per input, last-used first | `TvInputManager.getTvInputList()`; start `ACTION_VIEW` on `TvContract.buildChannelUriForPassthroughInput(id)`; listen for `com.sony.dtv.tvplayer.intent.action.INPUT_CHANGED` | 4.3, 3.6 |
| 3 | Apps row: every TV app, with its banner, launches on click | `PackageManager.queryIntentActivities` for `MAIN` + `CATEGORY_LEANBACK_LAUNCHER`; banner via `ActivityInfo.loadBanner` (fall back to `loadIcon`); launch with `getLeanbackLaunchIntentForPackage` [D] | 3.2, 4.1 |
| 4 | Keep the apps row current on install/uninstall/update | `LauncherApps.Callback` [D], or a receiver registered at runtime for `PACKAGE_ADDED` / `PACKAGE_REMOVED` / `PACKAGE_CHANGED` (data scheme `package`). launcherx uses a manifest receiver for added/fully-removed [F]; runtime registration is enough for a HOME that is always running | - |
| 5 | Settings shortcuts | Public `android.settings.*` actions. Resolve on this TV [O]: `SETTINGS`, `WIFI_SETTINGS`, `SOUND_SETTINGS`, `DISPLAY_SETTINGS` (goes to Sony's picture settings), `DATE_SETTINGS`, `APPLICATION_SETTINGS`, `ACCESSIBILITY_SETTINGS`, `DREAM_SETTINGS` (screensaver). **Do not resolve:** `BLUETOOTH_SETTINGS`, `NOTIFICATION_LISTENER_SETTINGS`; check `resolveActivity` before showing a tile | 7 |
| 6 | Live TV and Guide entries | Customizer's `basic-mode-live-tv-app-uri` / `basic-mode-epg-app-uri` from `content://tvlauncher.config/configuration`; start with `Intent.parseUri` | 3.7 |
| 7 | User app order and hidden apps | Local storage only (SharedPreferences/DataStore); optional seed from the customizer's per-country app order. launcherx's order and its apps-only mode setting are its own and not readable [I] | 3.7, 4.1 |
| 8 | Answer the "all apps" request | Handle `android.intent.action.ALL_APPS` + `DEFAULT` with an activity that opens Shart TV's apps view. Today only launcherx answers it [O]; with two handlers the system shows a chooser unless the owner picks Shart TV | - |
| 9 | Clock and network status in the top bar | `TextClock` (or `ACTION_TIME_TICK` while visible); `ConnectivityManager.registerDefaultNetworkCallback` with `ACCESS_NETWORK_STATE` | 4.4 |
| 10 | Focus, sizing and motion that read well at 3 m | Values in 4.4 | 4.4 |
| 11 | (later, optional) Notification count badge | A `NotificationListenerService` the owner enables over adb (`cmd notification allow_listener`); the Settings screen for it does not resolve on this TV [O] | 3.3, 6 |
| 12 | (later, optional) "Now playing" chip with play/pause | `MediaSessionManager.getActiveSessions(listenerComponent)`, allowed without `MEDIA_CONTENT_CONTROL` once the item-11 listener is enabled [D] | - |
| 13 | (later, optional, off by default) Search button | Start `Intent.ACTION_ASSIST` (resolves to Google's TV search app on this TV [O]). Off by default because the owner keeps voice/assistant disabled (remote-spec) | - |
| 14 | (later, optional) Return to last input at power-on | Runtime `SCREEN_ON` receiver + passthrough intent from item 2. Unverified whether a HOME app may start that activity from the background on Android 12, and it overlaps launcherx's own logic and Sony's app-on-boot setting (section 12, Q4). Build only if Q4 says launcherx stops doing it | 12 |

### 13.2 Full inventory

| Capability (stock) | What it does | Verdict for Shart TV |
|---|---|---|
| Home activity | The `HOME` activity at priority 2 (a second, simplified home activity ships disabled) [F] | **SHOULD**, item 1. Shart TV is capped at priority 0 and must be set explicitly (3.4). |
| App rows (installed apps) | Lists launchable TV apps as 16:9 banner cards; server-driven row layout [I] | **SHOULD**, items 3-4, 7. |
| App install/remove refresh | Manifest receiver for package added / fully removed [F] | **SHOULD**, item 4. |
| "All apps" deep link | Answers `android.intent.action.ALL_APPS` [F][O] | **SHOULD**, item 8. |
| Input switching | Input shortcut card type, a recently-watched-input entry, last-used-input store [I] | **SHOULD**, item 2 (first row instead of buried). |
| Last-used input feed from Sony's player | Receiver for `android.apps.tv.launcherx.INPUT_SELECTED`, guarded by `com.android.providers.tv.permission.WRITE_EPG_DATA` [F] | **CAN'T** receive (sent explicitly to launcherx). Use `INPUT_CHANGED` instead (3.6). |
| Boot to last input | Screen-on/off and dream receivers in the background process restore the last input or home location [F][I] | **SHOULD-later** (item 14), only if Q4 shows it's needed. |
| Live TV / EPG aggregation | Live TV rows and guide built from all inputs' channels; exports its own live-TV provider [F][I] | **CAN'T**: needs `com.android.providers.tv.permission.ACCESS_ALL_EPG_DATA` (signature/privileged). Shart TV links to Sony's Live TV and Guide (item 6). |
| Recommendation / preview channels from other apps | Rows of other apps' published channels and programs [I] | **CAN'T**: reading other apps' rows needs `ACCESS_ALL_EPG_DATA`; the TV provider limits other callers to their own package [D AOSP TvProvider]. Also **WON'T** by policy (section 9). |
| Channel "add to home" requests | Handles `android.media.tv.action.REQUEST_CHANNEL_BROWSABLE` and the matching broadcast [F][O] | **WON'T**. launcherx keeps answering these; Shart TV has no channel rows. |
| Continue watching / Watch Next | Reads the TV provider's watch-next table (title, episode, artwork, aspect, position, duration, last engagement) and an Engage-SDK verification receiver [I][F] | **CAN'T** across apps (same `ACCESS_ALL_EPG_DATA` limit); section 12 Q7 tracks a re-check. |
| Content tabs (movies, shows, library), entity pages, hero/featured carousel with video previews, trailers, free-play marketplace, YouTube TV hooks, news briefing | Google-account content surfaces [I][F] | **WON'T**. |
| Sponsored rows, ad signals provider, ad-id, "why this ad" menu (`com.google.android.tv.ads.intent.action.LAUNCH_ATC_MENU`) | Ads [F] | **WON'T** (section 9). |
| Search / voice entry | Search tab and text-input activity; voice via Google's TV search app [F][O] | **SHOULD-later, off by default** (item 13). Shart TV has no search UI of its own. |
| Gemini chat, AI image creation, app-function services | Assistant/AI features [F] | **WON'T**. |
| Notifications panel / dashboard | Side panel opened by `android.app.action.TOGGLE_NOTIFICATION_HANDLER_PANEL` / `OPEN_NOTIFICATION_HANDLER_PANEL`, guarded by `android.permission.STATUS_BAR_SERVICE`; launcherx holds the TV notification-handler role [F][O] | **CAN'T** own it (`STATUS_BAR_SERVICE`, `ACCESS_NOTIFICATIONS`, role `SYSTEM_TELEVISION_NOTIFICATION_HANDLER`). Optional badge via own listener (item 11). |
| Notification listener of its own | Feeds the panel [F] | Shart TV may run its own, owner-enabled (item 11). |
| Push notifications to the launcher | Google push (cloud messaging) receiver and notification SDK [F] | **WON'T**. |
| Now-playing controls | Requests/plays/pauses the current media session [F] | **CAN'T** as stock does (`MEDIA_CONTENT_CONTROL`); **SHOULD-later** via item 12. |
| Profiles, kids profiles, profile lock, account re-auth, profile picker | Multi-user Google profiles [F][I] | **CAN'T**: `MANAGE_USERS`, `INTERACT_ACROSS_USERS`, `GET_ACCOUNTS_PRIVILEGED`. Sony's player still queries launcherx's device-mode provider (3.6). |
| Digital wellbeing / screen time | Screen-on/off accounting [F] | **CAN'T**: system role `SYSTEM_WELLBEING`. |
| Sleep timer | Schedules device sleep [F] | **CAN'T** put the device to sleep (signature `android.permission.DEVICE_POWER`). Link to the TV's own sleep-timer setting instead. |
| Screensaver hand-off | Reacts to dream start/stop; screensaver control [F] | **CAN'T** start/stop dreams (`WRITE_DREAM_STATE`, `READ_DREAM_STATE`). The system starts the screensaver on its own; Shart TV offers `android.settings.DREAM_SETTINGS` (item 5) and pauses any animation when its window loses focus. |
| Remote "global" buttons | Receives `android.intent.action.GLOBAL_BUTTON` and a config action; defines `com.google.android.permission.GLOBAL_BUTTON` (signature/privileged) for app-launch buttons [F] | **CAN'T** receive (delivered only to the component in the system's global-key config). Remap path is in remote-spec (RRO). |
| Partner (OEM) customization | Reads Sony's `tvlauncher.config` provider; partner-config trigger action `com.google.android.apps.tv.launcherx.action.PARTNER_CONFIGURATION` [F][I] | **SHOULD** read the provider (items 6-7). The trigger stays launcherx's. |
| OEM promo tab | Sony content tab from the customizer's `oem-tab-*` entries [F] | **WON'T** (section 9). |
| Settings shortcuts and home-screen settings | Settings entry in the panel; home-screen settings exported to TV Settings as slices [F] | **SHOULD** for shortcuts (item 5). **WON'T** export slices. |
| Clock / status | Clock and status icons in the top bar [I] | **SHOULD** (item 9). |
| Network re-auth and trusted-network service | Captive-portal style re-login, network state service [F] | **WON'T**; the system handles networks. |
| Experiments, remote config, crash/perf metrics, usage reporting, surveys, feedback, incident and bug-report receivers, app-health "doctor" | Telemetry and remote control of features [F] | **WON'T**. Shart TV ships no analytics and no remote config. |
| Certification / server-compatibility providers and receivers | Internal Google TV state [F] | **WON'T** (not needed). |
| Open-source license screen | Shows third-party licenses from TV Settings [F] | Shart TV shows its own license screen for its own dependencies. |
