---
title: "USB and SDR adapters"
permalink: /usb-and-sdr/
---

The phone talks to the radio through its USB port, with Android's own USB support: **no root, no
drivers to install**. The app includes the RTL-SDR driver (librtlsdr) and libusb itself.

## What you need

| Part | What to get |
|---|---|
| **RTL-SDR dongle** | any dongle with the **RTL2832U** chip and an **R820T/R820T2** (or R860) tuner. The app knows the USB IDs of every dongle librtlsdr supports. |
| **USB OTG adapter or cable** | to plug a USB-A dongle into the phone's USB-C port. A short cable is better than a rigid adapter: it keeps the dongle's weight off the phone's port. |
| **Antenna** | for EOT, one cut for UHF (about 460 MHz; a quarter-wave whip is about 16 cm). For road channels, one for VHF (about 160 MHz; a quarter-wave is about 46 cm). The small antenna that comes with most dongles works, but poorly. See the receiver wiki's antenna page. |

### Tested

| Phone | Android | Dongle | Notes |
|---|---|---|---|
| Google Pixel 9a | 17 | Nooelec NESDR SMArt v5 (R820T2) | EOT and road channels, live listen, Android Auto |

If you try other phones or dongles, a report of what worked (or didn't) is welcome as an issue.

## Power and heat

- The dongle is powered by the phone, and draws a few hundred milliamps while it streams. Expect
  the battery to go faster; a car charger or a **USB-C hub with power pass-through** (charging the
  phone while the dongle is plugged in) is the way to listen for hours.
- Dongles get warm. A dongle that overheats, or a weak or loose connector, makes the phone drop
  the USB device: the app shows "Dongle lost" and reopens it when it comes back.

## Gain

The dongle's gain is on the **Activity** tab, under **Radio**. Phones default to **40.2 dB** (fixed
receivers with roof antennas use 20.7 dB): a small antenna needs more. Raise it if bursts are heard
but not decoded; lower it if everything is noisy or near a strong transmitter. **Automatic** lets
the tuner choose.

## USB permission, and phones that cut USB when locked

Android asks once whether RailSpotting may use the dongle. If you tick **always** (offered when the
app opens for the dongle), it doesn't ask again for that dongle.

Some phones switch USB data off while they are locked. On Android 16 and later this comes with
**Advanced Protection** (Settings > Security & privacy > Advanced Protection), whose USB protection
turns USB data off when the phone locks and back on when you unlock it. The dongle then drops off
and comes back as a new device, which needs USB permission again.

- The app asks again as soon as you unlock the phone, and its notification says "Unlock the phone
  and allow USB access to the dongle" while it waits.
- To listen with the phone locked, turn **USB protection** off in Advanced Protection (your call:
  it protects against data theft over USB), or keep the phone unlocked and on a charger.

## Recordings

In EOT mode the app keeps the last 25 EOT bursts it heard as recordings, so a missed decode can be
looked at later. They stay on the phone and are deleted with the app.
