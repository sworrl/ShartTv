# Residual privacy exposure on the locked Bravia (no bootloader, no root)

What we CAN'T fix on this TV while the bootloader stays locked, after the debloat already applied.
Honest list so there are no illusions. "Mitigation" = best available without root.

## What we already closed (app layer, reversible)
- Samba TV ACR (the paramount one), Sony promos/Select, IDD telemetry, demo mode, Google feedback,
  the voice/mic apps, the camera-sensing stack — all `pm disable-user`. The Bravia launcher's own
  ad surfaces go away once Shart TV holds HOME.

## What remains exposed

1. **Google Play Services / GMS telemetry — CANNOT remove.** GMS runs as a privileged system app
   and is the account/Play/cast backbone. Without root you can't uninstall or strip it. It phones
   home for account, Play, cast, and usage signals.
   *Mitigation:* sign in with a throwaway/minimal Google account or stay signed out; deny GMS
   optional permissions in Settings; per-UID background-data restriction
   (`cmd netpolicy add restrict-background-blacklist <gms-uid>`) — but cutting GMS network breaks
   Play/cast, so it's a trade.

2. **Vendor/MediaTek system services run below us — CAN'T inspect or stop.** `tv-mtkdmservice`,
   `tv-mtkdrmservice`, `mtk_rm`, `sensingFusion`, Sony `sonysystemservice`/`webapi.core`, etc. run
   as system/root in the OS. We can't audit what they send or disable them without root. We assume
   they may carry diagnostics.
   *Mitigation:* network-level only (below).

3. **No kernel-level kill switches.** The mic/camera/radio "offs" we did are `pm disable-user` on
   the *apps*, not a hardware cut. A system/privileged component could still use the mic, camera,
   BT or Wi-Fi; an OTA or factory reset re-enables the disabled apps. True hardware toggles need
   the kernel = root = unavailable here.
   *Mitigation:* physically, the remote mic is the only mic (don't pair / don't use it); the
   camera is an external accessory (leave it unplugged).

4. **DNS blocking is bypassable.** Apps can hardcode IPs or use DoH and skip your DNS blocklist —
   exactly how Samba kept talking. We removed Samba, but any future privileged phone-home can do
   the same.
   *Mitigation:* block at the **router/gateway by IP/ASN**, not just DNS; or put the TV on a VLAN
   with an explicit allowlist. This is the single highest-value remaining control and it lives on
   your network gear, not the TV.

5. **System/firmware integrity is unverifiable.** We can't read the system partition's true state,
   confirm what the signed OS does at boot, or prevent a Sony OTA from re-adding trackers or
   re-enabling Samba. Verified boot works *for Sony*, not for you.
   *Mitigation:* consider disabling automatic OTA (`com.sony.dtv.update` behavior / Settings), and
   re-run `scripts/sharttv.py` after any update that slips through.

6. **Closed radio/CEC/Wi-Fi firmware blobs** — opaque, unchangeable without root (and mostly not
   even then). Standard for all consumer TVs.

## The one control that actually contains all of this

**Network egress control on your gateway.** Since you can't trust the OS, treat the TV as hostile
and cage it at the network:
- Put it on an isolated VLAN/SSID.
- Default-deny outbound; allowlist only what you use (your streaming apps' CDNs, time sync).
- Block by IP/ASN (Samba TV, ACR, ad/telemetry ranges), not just DNS names.
- Optionally no internet at all for the TV and feed it from the unlockable box (see
  `03-consumer-soc-options.md`), which you *can* fully control.

That gateway policy, plus the app-layer debloat already done, is the realistic privacy ceiling for
a locked Bravia. The only way past it is owning the platform — the second box.
