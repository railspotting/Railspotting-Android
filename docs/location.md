---
title: "How location is used"
permalink: /location/
---

A phone receiver moves, so RailSpotting needs to know where it is to make sense of what it hears:
which crossings a train passed, which channel list to listen to, and where to put the trains it
heard on the map. This page says exactly when the app uses your position and what happens to it.

## When

- **Only while listening.** Listening means the dongle is connected and streaming. GPS is off at
  all other times, and nothing is sent.
- While listening, the app sends a position after you have moved a set distance (set by
  RailSpotting, a few hundred metres), and at least every few minutes. Fixes are batched for up to
  30 seconds to save battery.
- Each event (a call, an EOT burst) carries the position where it was heard.
- If the phone is offline, events wait on the phone with their own times and positions and go when
  it is back online. Old positions are not kept: RailSpotting ignores fixes older than an hour.

## What RailSpotting does with it

- **Your position is never public.** Your receiver's name isn't either.
- Trains your phone hears appear on the public map at the **crossings** they passed, not at your
  position. Turn **Sharing** off (on by default) and nothing your phone hears reaches the public
  map at all.
- The **channel list** follows you: RailSpotting picks the plan for the area you're in. **Refresh**
  on the Activity tab asks for the list for where you are now.

## Things that use the position only on the phone

- **Nearby alerts** (end-of-train heard by other receivers within 10 to 100 miles): the app reads
  the public live map and compares it with your position **on the phone**. Your position is not
  sent for this.
- **The live map** opens on your position, about a mile across, and its **Here** button centers it
  on you. The position goes into the map page on your phone, nowhere else.
- **Android Auto** lists the crossings near the phone, from the public map, compared on the phone.

## Permission

The app asks for **precise location**, with a word on why, when you first turn listening on. It
does **not** need background location ("Allow all the time"): listening runs as a foreground
service with a notification, which keeps location while the screen is off.
