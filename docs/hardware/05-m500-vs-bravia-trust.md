# Trust-state comparison: the M500 (unfused) vs the Bravia (fused) — and how rare that is

Context: the owner's HiBy M500 DAP is **unfused** with full bootchain access (the Blackjack
project's whole basis). The Bravia is the opposite. This note records the distinction and the
rarity, so future device choices target the right property.

## Two different "open" states (name them precisely)

- **Unlocked bootloader (common).** The OEM lets you turn verified-boot *enforcement* off
  (AVB/dm-verity), but the **root-of-trust eFuses are still blown**: the BootROM/preloader still
  requires vendor-signed code at the earliest stages. You can flash a signed-chain image, not run
  your own bootloader/TF-A.
- **Unfused / secure boot never provisioned (rare — the M500).** The signing eFuses were **never
  burned**, so the chain accepts **unsigned code at every stage**: preloader → TF-A → bootloader
  → kernel. This is "full bootchain access." It is what let Blackjack build and load unsigned GKI
  modules and custom boot on the M500. The fastboot tell for this state is `secure: no` (observed
  by owners on sibling HiBy DAPs).

The Bravia (MT5895) is fused + enforced + no unlock path — the mirror image, a dead end
(see `02-unlock-investigation.md`, `01-board-soc-id.md`).

## How rare is the M500's state

- **Across all consumer Android devices (phones, tablets, big-brand TVs): very rare**, effectively
  "never" for mass-market gear. Samsung, Google, Sony et al. blow the verified-boot fuses at
  manufacture; even devices that *allow* unlock keep secure boot enforced. A device that runs fully
  unsigned code from the preloader up is the exception.
- **Within niche small-OEM gear (audiophile DAPs — HiBy/FiiO/Shanling — and cheap Allwinner/
  Rockchip/Ingenic tablets and boxes): fairly common.** These vendors ship the chip vendor's
  reference BSP and never provision the signing fuses (engineering effort that only serves to lock
  out the owner). It flips per model even within one brand: FiiO M6 shipped "unlocked and
  permissive," FiiO M11 shipped locked with no OEM-unlock toggle; HiBy R5 reports `secure: no`
  while other HiBy models need EDL/Firehose.

**Net:** the M500's combination — a modern, capable **Android 13 / GKI 5.15** device that is also
**unfused end-to-end** — is rare in absolute terms and genuinely valuable. "Modern AND unfused" is
the sweet spot that makes real OS-level ownership possible.

## Lesson for picking a second device (Track C)

Don't chase "unlockable bootloader." Chase **"secure boot not enforced / `secure: no`"** — the rare
property that actually lets you own the stack. Among the `03-consumer-soc-options.md` picks, the
Amlogic "Burn Mode" boxes (onn/Dynalink/ADT-3) and open Rockchip RK3588 boards are the realistic
places to find a permissive-enough chain without an exploit.

Sources: HiBy R5 fastboot `secure: no` (xdaforums HiBy R5 Gen 1 thread); HiBy R4 EDL/Firehose root
(xdaforums); FiiO M6 unlocked out of the box (xdaforums WETA ROM thread); "eFuse is a
software-checked input, not the lock itself" (HN 33810766); Bootloader unlocking overview
(Wikipedia).
