#!/usr/bin/env python3
"""
Shart TV deploy tool for the Sony BRAVIA (Google TV, Android 12).

One command, safe to run as often as you like. Every step checks the TV first and only changes
what isn't already right:

  1. find the TV on the LAN (cached address, adb mDNS, SSDP, a port-5555 sweep)
  2. walk you through turning on network adb if it's off, and wait for you to allow it
  3. build the launcher if the sources are newer than the APK (or with --build)
  4. install it only if the TV has a different build
  5. permissions, location, HOME, and the HOME-redirect accessibility service
  6. disable Samba TV ACR and the stock launcher's ad/content feeds (reversible: `rollback`)
     and clear any background restriction on launcherx (it breaks the personal-photo screensaver)
  7. keep the Photos screensaver on its factory build (the Play update from 2026-10 can't get
     its photo token from Chromecast on this TV, so it shows a black screen with only the clock)

Usage:
  scripts/sharttv.py               discover + deploy + debloat (the default)
  scripts/sharttv.py status        show what's set and what isn't, change nothing
  scripts/sharttv.py discover      just find the TV
  scripts/sharttv.py guide         print the steps to turn on network adb
  scripts/sharttv.py rollback      re-enable everything this tool disabled
  options: --tv IP[:PORT]  --build  --apk PATH  --groups samba,launcher,...  --dry-run

Standard library only. Needs `adb` (Android platform-tools) on PATH or in $ANDROID_HOME.
Nothing about the TV or your network is written into the repo; the last-found address and what
this tool changed live in ~/.config/sharttv/state.json.
"""
import argparse
import concurrent.futures
import hashlib
import ipaddress
import json
import os
import re
import shutil
import socket
import subprocess
import sys
import time
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
APP = REPO / "app"
APK = APP / "launcher/build/outputs/apk/release/launcher-release.apk"
PKG = "com.sharttv.launcher"
HOME_ACT = f"{PKG}/.LauncherActivity"
A11Y = f"{PKG}/{PKG}.HomeRedirectService"
STOCK_HOME = "com.google.android.apps.tv.launcherx"
AMBIENT = "com.google.android.apps.tv.dreamx"   # the Google Photos screensaver
STATE = Path(os.environ.get("XDG_CONFIG_HOME") or Path.home() / ".config") / "sharttv" / "state.json"

# What gets disabled. `pm disable-user` only: nothing is uninstalled, `rollback` undoes it all.
GROUPS = {
    "samba": ("Samba TV ACR (fingerprints whatever is on screen)",
              ["tv.samba.ssm", "tv.samba.ssm.ui"]),
    "launcher": ("Stock launcher ad and content feeds",
                 ["com.sony.dtv.sonytab", "com.netflix.partner.ncm", "com.sony.dtv.promos",
                  "com.sony.dtv.sonyselect", "com.sony.dtv.launcherxcustomizer"]),
    "telemetry": ("Sony/Google telemetry and store-demo mode",
                  ["com.sonyericsson.idd.agent", "com.sony.dtv.demosupport", "com.google.android.feedback"]),
    "voice": ("Sony voice stack (the remote's mic)",
              ["com.sony.dtv.voicesearch", "com.sony.dtv.voicerecognitionconfirmation",
               "com.sony.dtv.assistantadapter"]),
    "sensing": ("BRAVIA CAM sensing stack (no camera attached)",
                ["com.sony.dtv.sensingcamera.settings", "com.sony.dtv.sensingcamera.fwupdate",
                 "com.sony.dtv.sensingfusionservice", "com.sony.dtv.gestureapp", "com.sony.dtv.distancealert"]),
    "dlna": ("DLNA renderer (casting from phones over DLNA; Chromecast is separate)",
             ["com.sonyericsson.dlna", "com.sony.huey.dlna.module", "com.sony.huey.dlna.renderersettings"]),
}
DEFAULT_GROUPS = ["samba", "launcher", "telemetry", "voice", "sensing"]
# Never disabled, whatever --groups says: disabling launcherx makes HOME resolve to Sony's
# recovery screen on this locked build, and the screensaver is wanted.
NEVER = {STOCK_HOME, "com.google.android.apps.tv.dreamx", "com.google.android.tungsten.setupwraith"}

DRY = False


# ---------------------------------------------------------------- output

def say(msg=""): print(msg, flush=True)
def ok(msg): say(f"  ✓ {msg}")
def todo(msg): say(f"  → {msg}")
def warn(msg): say(f"  ! {msg}")
def die(msg): say(f"\n✗ {msg}"); sys.exit(1)
def step(msg): say(f"\n== {msg}")


# ---------------------------------------------------------------- state

def load_state():
    try: return json.loads(STATE.read_text())
    except Exception: return {}

def save_state(s):
    if DRY: return
    STATE.parent.mkdir(parents=True, exist_ok=True)
    STATE.write_text(json.dumps(s, indent=2, sort_keys=True))


# ---------------------------------------------------------------- adb

def find_adb():
    exe = shutil.which("adb")
    if exe: return exe
    for root in (os.environ.get("ANDROID_HOME"), os.environ.get("ANDROID_SDK_ROOT"),
                 Path.home() / "Library/Android/sdk", Path.home() / "Android/Sdk",
                 Path.home() / ".local/opt/android-sdk"):
        if root and (Path(root) / "platform-tools/adb").exists():
            return str(Path(root) / "platform-tools/adb")
    die("adb not found. Install Android platform-tools:\n"
        "  https://developer.android.com/tools/releases/platform-tools\n"
        "  (macOS: brew install android-platform-tools   Debian/Ubuntu: apt install adb)")


class Adb:
    def __init__(self, exe):
        self.exe, self.serial = exe, None

    def run(self, *args, timeout=60, serial=True):
        cmd = [self.exe] + (["-s", self.serial] if serial and self.serial else []) + list(args)
        try:
            p = subprocess.run(cmd, capture_output=True, text=True, timeout=timeout)
            return p.returncode, (p.stdout + p.stderr).strip()
        except subprocess.TimeoutExpired:
            return 124, "timeout"

    def sh(self, cmd, timeout=60):
        return self.run("shell", cmd, timeout=timeout)[1]

    def change(self, cmd, what):
        """A state-changing shell command; skipped (and reported) under --dry-run."""
        if DRY: todo(f"would {what}"); return ""
        out = self.sh(cmd)
        todo(what)
        return out

    def devices(self):
        _, out = self.run("devices", serial=False)
        res = {}
        for line in out.splitlines()[1:]:
            parts = line.split()
            if len(parts) >= 2: res[parts[0]] = parts[1]
        return res


# ---------------------------------------------------------------- discovery

def local_networks():
    """IPv4 networks this machine is on (capped at /22 so a sweep stays quick)."""
    nets = set()
    try:
        out = subprocess.run(["ifconfig"], capture_output=True, text=True).stdout
        for ip, mask in re.findall(r"inet (\d+\.\d+\.\d+\.\d+) netmask (0x[0-9a-f]+|\d+\.\d+\.\d+\.\d+)", out):
            if mask.startswith("0x"): mask = socket.inet_ntoa(int(mask, 16).to_bytes(4, "big"))
            nets.add(ipaddress.ip_interface(f"{ip}/{mask}").network)
    except Exception: pass
    try:
        out = subprocess.run(["ip", "-4", "-o", "addr"], capture_output=True, text=True).stdout
        for cidr in re.findall(r"inet (\d+\.\d+\.\d+\.\d+/\d+)", out):
            nets.add(ipaddress.ip_interface(cidr).network)
    except Exception: pass
    if not nets:
        try:
            s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM); s.connect(("192.0.2.1", 9))
            nets.add(ipaddress.ip_interface(s.getsockname()[0] + "/24").network); s.close()
        except Exception: pass
    out = []
    for n in nets:
        if n.is_loopback or n.is_link_local or not n.is_private: continue
        if n.prefixlen < 22: n = ipaddress.ip_network(f"{n.network_address}/22", strict=False)
        out.append(n)
    return out


def port_open(ip, port, timeout=0.4):
    try:
        with socket.create_connection((str(ip), port), timeout=timeout): return True
    except OSError: return False


def sweep(nets, port=5555):
    hosts = [h for n in nets for h in n.hosts()]
    with concurrent.futures.ThreadPoolExecutor(128) as ex:
        return [str(h) for h, up in zip(hosts, ex.map(lambda h: port_open(h, port), hosts)) if up]


def ssdp_sony(timeout=3.0):
    """Sony TVs answer SSDP even with adb off, so we can find the TV before it's set up."""
    found = {}
    msg = ("M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: \"ssdp:discover\"\r\n"
           "MX: 2\r\nST: ssdp:all\r\n\r\n").encode()
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM, socket.IPPROTO_UDP)
        s.settimeout(0.5)
        s.sendto(msg, ("239.255.255.250", 1900))
        end = time.time() + timeout
        while time.time() < end:
            try: data, (ip, _) = s.recvfrom(4096)
            except socket.timeout: continue
            text = data.decode(errors="ignore").lower()
            if "sony" in text or "bravia" in text: found[ip] = True
        s.close()
    except OSError: pass
    return sorted(found)


def mdns_adb(adb):
    """`adb mdns services` -> {'connect': [ip:port], 'pair': [ip:port], 'legacy': [ip:port]}."""
    _, out = adb.run("mdns", "services", serial=False, timeout=10)
    res = {"connect": [], "pair": [], "legacy": []}
    for line in out.splitlines():
        m = re.search(r"(_adb[\w-]*)\._tcp\.?\s+(\d+\.\d+\.\d+\.\d+:\d+)", line)
        if not m: continue
        kind = {"_adb-tls-connect": "connect", "_adb-tls-pairing": "pair", "_adb": "legacy"}.get(m.group(1))
        if kind: res[kind].append(m.group(2))
    return res


def identify(adb, addr):
    """Connect to addr and return (serial, description) if it's a Sony TV, else None."""
    serial = addr if ":" in addr else f"{addr}:5555"
    if adb.devices().get(serial) != "device":
        adb.run("connect", serial, serial=False, timeout=8)
    state = adb.devices().get(serial)
    if state == "unauthorized": return serial, "unauthorized"
    if state != "device": return None
    old, adb.serial = adb.serial, serial
    brand = adb.sh("getprop ro.product.manufacturer", timeout=8)
    model = adb.sh("getprop ro.product.model", timeout=8)
    adb.serial = old
    if "sony" in brand.lower(): return serial, f"{brand} {model}".strip()
    return None


def wait_authorized(adb, serial, secs=180):
    if adb.devices().get(serial) == "device": return True
    say("\n  Look at the TV: an 'Allow USB debugging?' prompt is up.")
    say("  Tick 'Always allow from this computer', then choose Allow.")
    end = time.time() + secs
    while time.time() < end:
        st = adb.devices().get(serial)
        if st == "device": ok("authorized"); return True
        if st is None: adb.run("connect", serial, serial=False, timeout=8)
        time.sleep(2)
    return False


GUIDE = """
  Turn on network adb on the BRAVIA (one time):

   1. Settings > System > About. Scroll to "Android TV OS build" and press OK on it 7 times
      until it says you're a developer.
   2. Back out to Settings > System > Developer options.
   3. Turn on "USB debugging". If you also see "Network debugging" or "ADB over network",
      turn that on (it listens on port 5555).
   4. Newer firmware may show "Wireless debugging" instead: turn it on, open it, choose
      "Pair device with pairing code", and leave that screen up. This tool will ask for the
      IP:port and the 6-digit code shown there.
   5. Make sure this computer is on the same network as the TV.
"""


def find_tv(adb, args, state):
    step("Finding the TV")
    tried = set()
    cands = []
    if args.tv: cands.append(args.tv)
    if state.get("tv"): cands.append(state["tv"])
    cands += [s for s, st in adb.devices().items() if st in ("device", "unauthorized") and ":" in s]
    for addr in cands:
        if addr in tried: continue
        tried.add(addr)
        r = identify(adb, addr)
        if r: return finish_find(adb, r, state)

    md = mdns_adb(adb)
    for addr in md["legacy"] + md["connect"]:
        if addr in tried: continue
        tried.add(addr)
        r = identify(adb, addr)
        if r: return finish_find(adb, r, state)

    nets = local_networks()
    say(f"  sweeping {', '.join(map(str, nets)) or 'nothing'} for adb (port 5555)...")
    for ip in sweep(nets):
        if f"{ip}:5555" in tried: continue
        r = identify(adb, ip)
        if r: return finish_find(adb, r, state)

    sonys = ssdp_sony()
    if sonys: say(f"  found a Sony TV at {', '.join(sonys)}, but adb isn't on yet.")
    else: say("  no TV with adb on was found.")
    if args.command == "discover" or not sys.stdin.isatty():
        say(GUIDE); die("Turn on network adb, then run this again.")
    say(GUIDE)
    return enable_wadb(adb, state, sonys)


def enable_wadb(adb, state, sonys):
    """Wait for the user to switch adb on: poll 5555 on the known Sony, or take a pairing code."""
    say("  Waiting for adb to come up (Ctrl-C to stop). Press Enter to pair with a code instead.")
    import select
    while True:
        for ip in sonys:
            if port_open(ip, 5555):
                r = identify(adb, ip)
                if r: return finish_find(adb, r, state)
        md = mdns_adb(adb)
        for addr in md["legacy"] + md["connect"]:
            r = identify(adb, addr)
            if r: return finish_find(adb, r, state)
        if select.select([sys.stdin], [], [], 3)[0]:
            sys.stdin.readline()
            pair = input("  Pairing IP:port (from the TV's pairing screen): ").strip()
            code = input("  6-digit code: ").strip()
            rc, out = adb.run("pair", pair, code, serial=False, timeout=30)
            say(f"  {out}")
            if rc == 0:
                time.sleep(2)
                for addr in mdns_adb(adb)["connect"]:
                    r = identify(adb, addr)
                    if r:
                        serial, _ = r
                        # pin a stable port so later runs find it at IP:5555 (lasts until reboot)
                        adb.serial = serial
                        adb.run("tcpip", "5555", timeout=15)
                        time.sleep(3)
                        r2 = identify(adb, serial.split(":")[0])
                        return finish_find(adb, r2 or r, state)


def finish_find(adb, r, state):
    serial, desc = r
    if desc == "unauthorized":
        if not wait_authorized(adb, serial): die("The TV never allowed this computer.")
        r = identify(adb, serial)
        if not r: die(f"{serial} isn't a Sony TV.")
        serial, desc = r
    adb.serial = serial
    ok(f"{desc} at {serial}")
    state["tv"] = serial
    save_state(state)
    return serial


# ---------------------------------------------------------------- build + install

def newest_source():
    roots = [APP / "launcher/src", APP / "launcher/build.gradle", APP / "build.gradle"]
    t = 0
    for r in roots:
        if r.is_file(): t = max(t, r.stat().st_mtime)
        elif r.is_dir():
            for p in r.rglob("*"):
                if p.is_file(): t = max(t, p.stat().st_mtime)
    return t


def find_gradle():
    if shutil.which("gradle"): return ["gradle"], None
    if (APP / "gradlew").exists(): return [str(APP / "gradlew")], None
    env_sh = Path.home() / ".local/opt/sharttv-env.sh"
    if env_sh.exists(): return ["bash", "-c", f'source "{env_sh}" && gradle "$@"', "gradle"], None
    return None, "no gradle on PATH (and no app/gradlew)"


def ensure_apk(args):
    step("Launcher build")
    if args.apk:
        apk = Path(args.apk)
        if not apk.exists(): die(f"{apk} doesn't exist")
        ok(f"using {apk}"); return apk
    stale = not APK.exists() or newest_source() > APK.stat().st_mtime
    if not (args.build or stale):
        ok("APK is up to date with the sources"); return APK
    gradle, why = find_gradle()
    if not gradle:
        if APK.exists(): warn(f"sources changed but {why}; installing the existing APK"); return APK
        die(f"no APK and {why}. Install JDK 17 + Gradle 8, or pass --apk.")
    if not (APP / "keystore.properties").exists():
        warn("app/keystore.properties is missing, so the release APK will be unsigned and won't "
             "install. See README > Building.")
    if DRY: todo("would build :launcher:assembleRelease"); return APK
    todo("building :launcher:assembleRelease (first build can take a few minutes)")
    p = subprocess.run(gradle + [":launcher:assembleRelease", "-q"], cwd=APP)
    if p.returncode != 0 or not APK.exists(): die("build failed")
    ok("built")
    return APK


def sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""): h.update(chunk)
    return h.hexdigest()


def installed_sha(adb):
    out = adb.sh(f"pm path {PKG}")
    m = re.search(r"package:(\S+base\.apk)", out)
    if not m: return None
    s = adb.sh(f"sha256sum {m.group(1)}").split()
    return s[0] if s else None


def install(adb, apk):
    step("Install")
    want, have = sha256(apk), installed_sha(adb)
    if want == have: ok("the TV already has this build"); return False
    if DRY: todo("would install the launcher"); return True
    todo("installing" if have is None else "updating the launcher")
    rc, out = adb.run("install", "-r", str(apk), timeout=180)
    if rc != 0:
        if "UPDATE_INCOMPATIBLE" in out:
            die("The TV's copy was signed with a different key. Uninstall it first "
                f"(`adb uninstall {PKG}`, which clears its profiles and settings), then rerun.")
        die(f"install failed: {out}")
    ok("installed")
    return True


# ---------------------------------------------------------------- configure

def configure(adb):
    step("Settings")
    # location: the weather follows the TV's own Wi-Fi location
    if adb.sh("settings get secure location_mode").strip() in ("0", "null", ""):
        adb.change("cmd location set-location-enabled true", "turn on Location (network/Wi-Fi only)")
    else: ok("Location is on")
    perms = adb.sh(f"dumpsys package {PKG}")
    for p in ("ACCESS_COARSE_LOCATION", "ACCESS_FINE_LOCATION"):
        if re.search(rf"android\.permission\.{p}: granted=true", perms): ok(f"{p} granted")
        else: adb.change(f"pm grant {PKG} android.permission.{p}", f"grant {p}")

    # HOME: set it even though Sony still routes HOME to launcherx; the a11y service redirects
    if PKG in adb.sh("cmd shortcut get-default-launcher"): ok("Shart TV is the default launcher")
    else: adb.change(f"cmd package set-home-activity {HOME_ACT}", "set Shart TV as the default launcher")

    # the HOME-redirect + hour-chime accessibility service; keep any others already enabled
    cur = adb.sh("settings get secure enabled_accessibility_services").strip()
    svcs = [s for s in cur.split(":") if s and s != "null"]
    if A11Y not in svcs:
        adb.change(f"settings put secure enabled_accessibility_services {':'.join(svcs + [A11Y])}",
                   "enable the HOME-redirect service")
    if adb.sh("settings get secure accessibility_enabled").strip() != "1":
        adb.change("settings put secure accessibility_enabled 1", "switch accessibility on")
    if not DRY:
        time.sleep(2)
        if not a11y_bound(adb):   # an update can drop the binding: toggle it to rebind
            others = ":".join(s for s in svcs if s != A11Y)
            adb.sh(f"settings put secure enabled_accessibility_services {others or 'null'}")
            time.sleep(1)
            adb.sh(f"settings put secure enabled_accessibility_services {':'.join([s for s in svcs if s != A11Y] + [A11Y])}")
            time.sleep(2)
            todo("re-armed the HOME-redirect service")
        if a11y_bound(adb): ok("HOME-redirect service is running")
        else: warn("HOME-redirect service didn't bind; check Settings > Accessibility > Shart TV Home")


def a11y_bound(adb):
    return any("Bound services" in l and "Shart TV" in l for l in adb.sh("dumpsys accessibility").splitlines())


# ---------------------------------------------------------------- screensaver

def pin_ambient(adb, state):
    """Ambient 3.3.x asks the Chromecast service for a photo token too early or gets none, and
    every Google Photos image then fails (black screen, clock only). The factory 2.4.x build
    works, so roll the Play update back whenever one has been installed."""
    step("Screensaver")
    out = adb.sh(f"dumpsys package {AMBIENT}")
    paths = re.findall(r"codePath=(\S+)", out)
    if not paths: ok("Ambient screensaver not on this TV"); return
    if not paths[0].startswith("/data/"):
        ok("Ambient screensaver is on its factory build")
        return
    ver = re.search(r"versionName=(\S+)", out)
    adb.change(f"am force-stop {AMBIENT}; pm uninstall-system-updates {AMBIENT}",
               f"roll Ambient {ver.group(1) if ver else ''} back to its factory build (photos fail on the update)")
    state["ambient_pinned"] = True
    save_state(state)


# ---------------------------------------------------------------- debloat

def disabled_set(adb):
    return {l.split(":", 1)[1] for l in adb.sh("pm list packages -d").splitlines() if l.startswith("package:")}

def installed_set(adb):
    return {l.split(":", 1)[1] for l in adb.sh("pm list packages").splitlines() if l.startswith("package:")}


def debloat(adb, groups, state):
    step("Debloat")
    off, have = disabled_set(adb), installed_set(adb)
    changed = set(state.get("disabled", []))
    for g in groups:
        desc, pkgs = GROUPS[g]
        say(f"  [{g}] {desc}")
        for pk in pkgs:
            if pk in NEVER: continue
            if pk not in have: ok(f"{pk} (not on this TV)"); continue
            if pk in off: ok(f"{pk} disabled"); continue
            adb.change(f"am force-stop {pk}; pm disable-user --user 0 {pk}", f"disable {pk}")
            changed.add(pk)
    uid = launcherx_uid(adb)
    if uid and uid in adb.sh("cmd netpolicy list restrict-background-blacklist").split():
        adb.change(f"cmd netpolicy remove restrict-background-blacklist {uid}", "restore launcherx background data (photos)")
    for op in ("RUN_ANY_IN_BACKGROUND", "RUN_IN_BACKGROUND"):
        if "ignore" in adb.sh(f"cmd appops get {STOCK_HOME} {op}"):
            adb.change(f"cmd appops set {STOCK_HOME} {op} allow", f"restore launcherx {op} (photos)")
    state["disabled"] = sorted(changed)
    save_state(state)


def launcherx_uid(adb):
    m = re.search(r"userId=(\d+)", adb.sh(f"dumpsys package {STOCK_HOME}"))
    return m.group(1) if m else None


# launcherx is left running normally. Cutting its background data/execution looked harmless, but
# the Photos screensaver gets the household's personal albums through it: with launcherx starved,
# Ambient silently falls back to Google's stock gallery. (Android also keeps the HOME app resident
# no matter what: it's exempt from standby buckets and background kills, and the shell can't
# disable its components.) rollback() still clears any restriction an older version applied.


def rollback(adb, state):
    step("Rollback")
    pkgs = set(state.get("disabled", []))
    for _, (_, ps) in GROUPS.items(): pkgs |= set(ps)   # covers changes made before this tool existed
    off = disabled_set(adb)
    for pk in sorted(pkgs & off):
        adb.change(f"pm enable --user 0 {pk}", f"re-enable {pk}")
    uid = launcherx_uid(adb)
    if uid and uid in adb.sh("cmd netpolicy list restrict-background-blacklist").split():
        adb.change(f"cmd netpolicy remove restrict-background-blacklist {uid}", "restore launcherx background data")
    for op in ("RUN_ANY_IN_BACKGROUND", "RUN_IN_BACKGROUND"):
        if "ignore" in adb.sh(f"cmd appops get {STOCK_HOME} {op}"):
            adb.change(f"cmd appops set {STOCK_HOME} {op} allow", f"restore launcherx {op}")
    state["disabled"], state["launcherx_starved"] = [], False
    save_state(state)
    say("\n  Shart TV itself is left installed. To remove it too:")
    say(f"    adb uninstall {PKG}")


# ---------------------------------------------------------------- status

def status(adb, groups):
    step("Status")
    have = installed_sha(adb)
    if have is None: warn("Shart TV is not installed")
    elif APK.exists() and sha256(APK) == have: ok("Shart TV installed (matches the local build)")
    else: warn("Shart TV installed, but it differs from the local build")
    say(f"  location_mode        {adb.sh('settings get secure location_mode')}")
    home = re.search(r"\{([^}/]+)/", adb.sh("cmd shortcut get-default-launcher"))
    say(f"  default launcher     {home.group(1) if home else 'unknown'}")
    say(f"  a11y service bound   {a11y_bound(adb)}")
    amb = re.findall(r"codePath=(\S+)", adb.sh(f"dumpsys package {AMBIENT}"))
    if amb: say(f"  photos screensaver   {'factory build' if not amb[0].startswith('/data/') else 'Play update (photos may fail)'}")
    off, have_pk = disabled_set(adb), installed_set(adb)
    for g in GROUPS:
        pk = [p for p in GROUPS[g][1] if p in have_pk]
        n = sum(p in off for p in pk)
        mark = "default" if g in groups else "optional"
        say(f"  {g:<10} {n}/{len(pk)} disabled   ({mark}) {GROUPS[g][0]}")


# ---------------------------------------------------------------- main

def main():
    global DRY
    ap = argparse.ArgumentParser(description="Deploy Shart TV to the Sony BRAVIA. Safe to rerun.")
    ap.add_argument("command", nargs="?", default="deploy",
                    choices=["deploy", "status", "discover", "guide", "rollback"])
    ap.add_argument("--tv", help="the TV's IP[:port], skips discovery")
    ap.add_argument("--build", action="store_true", help="rebuild the launcher even if it looks current")
    ap.add_argument("--apk", help="install this APK instead of the local build")
    ap.add_argument("--groups", default=",".join(DEFAULT_GROUPS),
                    help=f"debloat groups (default {','.join(DEFAULT_GROUPS)}; all: {','.join(GROUPS)})")
    ap.add_argument("--no-debloat", action="store_true", help="install and configure only")
    ap.add_argument("--dry-run", action="store_true", help="show what would change, change nothing")
    ap.add_argument("--keep-ambient-update", action="store_true",
                    help="leave a Play-updated Photos screensaver alone (normally rolled back; see pin_ambient)")
    args = ap.parse_args()
    DRY = args.dry_run
    groups = [g.strip() for g in args.groups.split(",") if g.strip()]
    bad = [g for g in groups if g not in GROUPS]
    if bad: die(f"unknown group(s): {', '.join(bad)}")

    if args.command == "guide": say(GUIDE); return
    adb = Adb(find_adb())
    state = load_state()
    find_tv(adb, args, state)
    if args.command == "discover": return
    if args.command == "status": status(adb, groups); return
    if args.command == "rollback": rollback(adb, state); return

    apk = ensure_apk(args)
    install(adb, apk)
    configure(adb)
    if not args.no_debloat: debloat(adb, groups, state)
    if not args.keep_ambient_update: pin_ambient(adb, state)
    status(adb, groups)
    say("\nDone. Run it again any time; it only changes what isn't already right.")


if __name__ == "__main__":
    try: main()
    except KeyboardInterrupt: say("\nstopped")
