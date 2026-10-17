---
title: "RailSpotting for Android"
permalink: /
---

<div class="hero" markdown="1">

# RailSpotting for Android

RailSpotting for Android turns a phone into a **mobile RailSpotting receiver**. Plug an RTL-SDR
dongle into the phone, sign in with Google, and the phone listens for trains while you travel:

- **End-of-train (EOT) devices**, decoded on the phone: unit number, brake pipe pressure, motion,
  marker light and battery.
- **Road channels**, the railroad voice channels near you, with **live listen** to hear calls as
  they happen.

What the phone hears goes to [RailSpotting](https://railspotting.com), together with where the phone
is, so trains show up on the live map. The app also shows the live map, alerts you to trains heard
near you, and works in the car with Android Auto.

</div>

<div class="cards">
<a class="card" href="{{ '/install/' | relative_url }}"><b>Installing the app</b><span>Download, sign in, permissions, and updates.</span></a>
<a class="card" href="{{ '/usb-and-sdr/' | relative_url }}"><b>USB and SDR adapters</b><span>Dongles, OTG cables, antennas, power, gain, and phones that cut USB.</span></a>
<a class="card" href="{{ '/location/' | relative_url }}"><b>How location is used</b><span>When the app uses your position, and what happens to it.</span></a>
</div>

## Background

How railroads use radio, how EOT, HOT and defect detectors work, and how a receiver recognises
them is explained in the **[receiver wiki](https://railspotting.github.io/Railspotting-Receiver)**.
The phone runs the same receiver code (its Rust core is the receiver's), with one dongle instead of
a fixed receiver's two or three.


## What a phone receiver can and can't do

| | Phone | Fixed receiver |
|---|---|---|
| Dongles | one, in one mode at a time: EOT **or** road channels | up to three (EOT, VHF voice, HOT), plus a scanner |
| EOT decoding | yes | yes |
| Road channels | the ones that fit the dongle's 2.4 MHz window | the whole plan, on its VHF dongle |
| Defect detector speech recognition | no (no transcription on the phone) | yes |
| Position | moves with you; sent while listening | fixed, set once |

The phone switches modes on the **Activity** tab; the dongle reopens in the new mode at once.
EOT is the default, because it is what tells you a train is near.
