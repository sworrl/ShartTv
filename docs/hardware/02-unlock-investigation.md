# Unlock investigation — areas to try, owner's own device

Goal: find any owner-usable way to run our own code below the Android layer on the Sony KD-50X85K
(MT5895, VH21). This is research tracking for the owner's own hardware. We observe and use
documented/observed interfaces and public research; we do **not** attack the live TV (no fault
injection on the running set, no DoS, no key extraction). If a public, reproducible method
appears, it moves from here into a proper procedure.

Status legend: **DEAD** (confirmed closed), **OPEN** (worth watching/trying), **DESTRUCTIVE**
(hardware risk, owner decides), **NEEDS-GEAR** (needs equipment we don't have).

## A. Software / on-device routes (no hardware risk)

1. **OEM-unlock toggle / fastboot** — **DEAD.** No `ro.oem_unlock_supported`, no unlock service,
   no Developer-Options entry; MTK signed bootloader rejects `fastboot flashing unlock`.
2. **`su` / engineering or service-mode APK** — **OPEN (low odds).** This firmware ships
   `com.sony.dtv.servicemode`, `com.sony.dtv.citool`, `com.sony.dtv.b2b.*` (hotel/pro, node
   runtime, admin). Worth cataloguing their exported activities/services for any that run a shell
   or write system settings as system uid. Read-only enumeration only (`dumpsys package <p>`,
   manifest from the pulled APK). Do not fuzz. Most B2B apps gate on a password we won't attack.
3. **Known Android 12 / Google-TV privilege-escalation CVE usable from `shell`** — **OPEN.** Track
   public, patched-after-our-build (patch level 2026-05-01) local-EoP CVEs with working PoCs that
   survive on this build. If one exists and is published, it's the cleanest root path. Research
   only; we don't weaponize.
4. **ADB-level app tricks already in hand** — DONE: `pm disable-user`, `appops`, per-UID
   `netpolicy`, user-installed RRO (pending test). These are the ceiling without root.
5. **Downgrade to a vulnerable signed build** — **OPEN but likely DEAD.** MTK anti-rollback
   (eFuse rollback index) usually blocks reflashing an older signed image. Check whether any
   older Sony signed package for VH21 is both downloadable and accepted. Don't force it.

## B. Service-port / firmware routes (needs gear, some risk)

6. **UART / serial console on the BM5A board** — **NEEDS-GEAR, OPEN for logging only.** Modern
   Sony boards usually expose a debug UART (3.3 V). It is almost always read-only at the LK/U-Boot
   stage on production-fused units (no interactive shell, boot command locked), but it gives boot
   logs that confirm the chain and anti-rollback behavior. Value: intelligence, not entry. Needs a
   USB-TTL adapter and pad identification from the board photo.
7. **eMMC ISP / "bad block" firmware reflash** — **DESTRUCTIVE, DEAD for our goal.** You can read/
   write the eMMC off an ISP clip, but the images are signed; writing a modified one just fails
   verification at boot. Useful only for *un-bricking* with a stock dump, not for running our code.
8. **BootROM download mode (MTK BROM / "Preloader" over USB)** — **OPEN research, likely DEAD on
   this part.** MTK SoCs have a BROM USB mode (the basis of SP Flash Tool / mtkclient). On phones,
   older SoCs had BROM bugs (the "Kamakiri"/NCC-glitch class). For MT5895 (Pentonic TV) there is
   **no public download-agent, no public exploit, and TV boards often don't route the BROM USB
   lines**. Track mtkclient / postmarketOS for any TV-SoC support landing. We do **not** attempt
   glitching/fault injection on the owner's working set.

## C. Silicon route

9. **Replace the SoC with an unfused MT5895** — **DEAD.** See `01-board-soc-id.md`: the part isn't
   sold to consumers, any obtainable part is already fused, a blank part still won't boot Sony's
   signed firmware, the eMMC is bound, and the BGA rework likely bricks the board. Not a path.

## What would change the verdict

- A published, reproducible MT5895/Pentonic BROM or LK vulnerability with a release (postmarketOS,
  a CVE with PoC, an mtkclient module). **Track:** postmarketOS wiki/mailing list, mtkclient repo,
  XDA "Pentonic"/"MT5895" threads, NCC/entry-level SoC-security blogs.
- A Sony or MediaTek engineering unlock leaking into a public tool. Unlikely.
- Us deciding the UART logs are worth the teardown (intelligence only).

Until one lands, **the Bravia is a locked display.** Everything we actually want (root, AOSP,
hardware kill switches, Graphene-style sandboxing) is delivered on a consumer-unlockable device —
see `03-consumer-soc-options.md`.

## Open tasks (safe, do next if the owner wants)
- [ ] Enumerate exported components of `servicemode`, `citool`, `b2b.*` from the pulled APKs
      (read-only) and list any that are `exported=true` and run as system — candidates only.
- [ ] Check Sony's open-source distribution site for a VH21/X85K GPL source drop (kernel/U-Boot)
      — useful for building, not for booting, and for board/UART intel.
- [ ] Identify the UART pads on `boards/pics/board_1.png` for passive boot logging (owner decides
      whether to open the set).
