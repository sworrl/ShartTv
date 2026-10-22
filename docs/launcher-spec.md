# Shart TV launcher specification

Target: Sony BRAVIA 4K VH21, Google TV, Android 12. Replaces the HOME role holder
`com.google.android.apps.tv.launcherx` (Google TV launcher, "launcherx" below) with Shart TV,
a user-installed (non-system) app. Stage 1: sections 0-3 and the open questions.

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
`../scripts/rollback-debloat.sh`.

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
