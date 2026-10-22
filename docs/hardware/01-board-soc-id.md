# Shart TV — hardware / board identification and the SoC-swap verdict

Right-to-repair documentation for the owner's own set. Facts first, then the honest assessment of
the "replace the fused SoC with an unfused one" idea.

## Set and boards (confirmed)

- **TV:** Sony **KD-50X85K** (X85K series, 50", 2022). Chassis/variant **VH21** ("Valhalla"),
  3840×2160, boot device **eMMC**. Reported to Android as `BRAVIA_VH21`, model `BRAVIA 4K VH21`.
- **Main board:** Sony **BM5A**, service part **A-5042-692-A** (board marking **100972432**,
  silkscreen `>EP GW<`). Shared across the X80K/X85K family (KD-55/65/75/85X85K, X80K). Photo:
  `boards/pics/board_1.png`.
- **Power board:** 1-009-800-31 (family), per parts listings for this chassis.
- **SoC:** MediaTek **MT5895** (Pentonic-class TV SoC). On this board it is under the central
  silver heatsink. CPU: ARM **Cortex-A73** (part 0xd09), 4 cores, aarch64. ~3 GB RAM.
- **Adjacent BGAs** at the heatsink edge: LPDDR4 and the eMMC (the signed/bound storage).

## MT5895 — what is public

MediaTek does **not** publish a public datasheet for MT5895; TV SoCs are documented only under
OEM NDA. What is publicly known / confirmed on this unit:

- Pentonic-family 4K Google TV SoC, quad Cortex-A73, aarch64, HDMI 2.1 class, integrated tuner
  and MEMC, ARM Mali GPU, built-in video engines. eMMC boot.
- Boot chain: **MediaTek BootROM (on-die, immutable) → TF-A → LK/U-Boot → Android**, each stage
  signature-checked by the previous one. Root of trust = on-die BootROM + OTP **eFuses**.

## Where the fuses are (the key fact)

**The eFuses are inside the MT5895 die.** There is no separate "fuse chip" to replace. The OTP
eFuse bank is fabricated into the same piece of silicon as the CPU cores. At Sony's factory these
fuses are burned (one-time, irreversible) to: enable secure boot, pin the hash of the signing
certificate, and set anti-rollback indices. The BootROM reads them at power-on and will only run
code signed with the matching key.

## Why "swap in an unfused MT5895" does not get you an unlocked TV

Four independent walls, each fatal on its own:

1. **You cannot buy an MT5895.** MediaTek sells TV SoCs only to TV OEMs, under NDA, in reels/
   trays. There is no Digikey/Mouser/authorized-distributor channel for one, and none for a
   "blank" part. The only MT5895 a person can physically obtain is one **harvested off another
   scrap X85K-class board** — and that one is **already fused** by its OEM. Unfused parts exist
   only transiently inside the chip vendor's/OEM's provisioning line.

2. **A truly blank chip would not boot THIS TV either.** Sony's firmware on the eMMC is signed/
   bound to the fused key. A chip with no key provisioned rejects Sony's signed images too. To
   boot anything you would then have to provision your own keys **and** re-sign the entire boot
   chain (TF-A, LK, boot, vbmeta) **and** re-establish the eMMC binding — i.e. rebuild the whole
   signed stack. You do not have Sony's key, and doing it with your own key is a from-scratch
   secure-boot bring-up, not a chip swap.

3. **The eMMC is part of the trust, not just the SoC.** Replacing the SoC does not give you the
   storage's bindings; you would be re-pairing storage and SoC from zero with no vendor tooling.

4. **The rework almost certainly kills the set.** The MT5895 is a large fine-pitch BGA reflowed
   directly to a multilayer board, flanked by DDR and eMMC under the same thermal zone. Hand
   desolder + reball + reflow of a modern TV SoC has a low success rate; a slip warps the board or
   damages the neighbours. You would most likely turn a working TV into a dead one, and still be
   behind walls 1–3.

**Verdict:** the hardware-swap route is not a path to an unlocked Bravia. It is infeasible to
source the part, it would not boot without the vendor key regardless, and it risks destroying the
panel. This is a genuine dead end on this platform — not a "try harder" situation.

## What actually achieves the goal (see SWEEP-REPORT.md Track C)

The locked Bravia stays your **display**. Put a **deliberately-unlockable** Google TV / AOSP
streaming box in front of it (one whose vendor ships an owner-usable `fastboot flashing unlock`),
and build "Shart TV OS" there — real root, AOSP base, hardware kill paths, GrapheneOS-style app
sandboxing. That is the supported, non-destructive way to get everything the Bravia's fused SoC
denies. Ask and this gets specced as its own track.
