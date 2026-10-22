# Consumer-buyable chips/devices that ARE unlockable

Short answer to "are there other chips sold to consumers that will work": **not for re-chipping
the Bravia** — no SoC you can buy will boot on Sony's BM5A board with Sony's signed, eMMC-bound
firmware (see `01-board-soc-id.md`). But if the real goal is *a device you fully own*, yes — there
are consumer boxes whose vendors ship them **unlockable**, so you root them without any exploit.

## The chip families, by openness

| SoC family | In consumer devices | Unlock reality | Verdict for us |
|---|---|---|---|
| **MediaTek Pentonic (MT5895 etc.)** | Sony/Philips/etc. Google TVs | OEM-fused, locked, no public unlock | **Avoid** — this is the wall we're at |
| **Amlogic S905Y2 / S905X-class** | Dynalink 4K, Walmart onn 4K, Google ADT-3 | **"Burn Mode" forcibly unlocks the bootloader — no exploit needed.** LineageOS officially supports several | **Best cheap path** |
| **Amlogic (Chromecast w/ Google TV)** | Chromecast 4K "sabrina", HD "boreal" | Only via exploits that Google **patched** (4K: Oct 2023; HD: Dec 2023). Anti-rollback blocks downgrade | Only if you find a pre-patch unit; not reliable to buy new |
| **Rockchip RK3588 / RK3566** | Many Android TV boxes & SBCs (Radxa, Orange Pi, generic boxes) | Very open; vendor images + community AOSP/LineageOS/mainline Linux; typically no locked bootloader | **Best power path** (more RAM/CPU, mainline Linux possible) |
| **Nvidia Tegra (Shield TV)** | Nvidia Shield | Bootloader locked, no unlock | Avoid |

## Concrete recommendations

**Cheapest, zero-exploit, officially supported — Amlogic S905Y2 running LineageOS:**
- **Walmart onn. 4K / onn. 4K Pro** (codename `dopinder`) or **Dynalink 4K** (codename `wade`).
  Both unlock through Amlogic **Burn Mode** (a Linux flashing tool, no bug required) and have
  official LineageOS builds (LineageOS 22 / Android 15 as of 2024). ~$20–30. You get root, a clean
  AOSP-based TV OS, and you can run Shart TV launcher on it as a normal (even system) app.

**Most capable — Rockchip RK3588 box/SBC:**
- RK3588 boards (Radxa Rock 5, Orange Pi 5, or an RK3588 TV box) are generally unlocked, have 4–16
  GB RAM, and support Android 13/14 vendor images plus mainline-ish Linux. More work to set up,
  but the closest thing to "build whatever you want," including real per-app sandboxing and
  hardware-level control.

**Google ADT-3** (codename `deadpool`, Amlogic S905Y2) — Google's own Android TV dev box, ships
developer-unlockable; LineageOS-supported. Harder to buy now, but it's the "intended to be
unlocked" reference device.

## How this fits Shart TV

The Bravia stays the panel (ARC/eARC audio, one HDMI in). The unlockable box becomes the brain:
root, our launcher as the real HOME, our own sandboxing/firewall, hardware paths we control. Shart
TV launcher (being built now) runs on **both** — on the Bravia as a user-HOME app within the
locked OS, and on the box as a first-class/system launcher with far fewer limits.

Sources (unlock status, verify before buying):
- LineageOS devices: Dynalink `wade`, onn `dopinder`, ADT-3 `deadpool` (wiki.lineageos.org).
- Chromecast w/ Google TV exploit + patch status (9to5google, Dec 2023 / Jun 2024).
