# Open questions (answer on hardware / by research, not by attacking the device)

1. Root/unlock on mt5895 BRAVIA: is there any owner-usable path (engineering/service-mode APK
   exposing su, a signed-but-vulnerable older build to sideload, a public Pentonic secure-boot
   bug)? Needed for Track C (kernel toggles, Graphene-style sandbox). No fuzzing/exploiting the
   live TV to find out.
2. Default HOME over shell: does `cmd package set-home-activity com.sharttv/.Home` stick on this
   build, or does Sony's launcherxcustomizer re-assert itself at boot? Test after app/ exists.
3. Can `shell` persistently disable `com.google.android.tv.remote.service` without breaking
   remote pairing, or does the BLE remote need it? Test: disable, confirm remote still works via
   IR/CEC.
4. globalkeyoverlay: are the hardcoded app-launch buttons (Netflix etc.) remappable via a
   runtime overlay (RRO) we can install over shell, or only by replacing the overlay APK (needs
   system write = root)?
5. DLNA/UPnP: after disabling SemcDlna + huey, does any SSDP/multicast chatter remain? Snapshot
   /proc/net/udp for :076C (1900) before/after.
6. Samba persistence: does a Sony OTA re-enable tv.samba.ssm? Re-check after next update.
7. Remote mic: with voicesearch/voicerecognitionconfirmation disabled, does the remote's mic
   button go fully dead, and does Google Assistant hotword stay off?
