---
title: "Installing the app"
permalink: /install/
---

The app is not in the Google Play Store. It is installed from this repository's
[Releases](https://github.com/railspotting/Railspotting-Android/releases), and then **keeps itself
up to date** from there.

## Requirements

- Android 10 or later, on a 64-bit phone (almost every phone made since 2019).
- A Google account, to sign in to RailSpotting.
- An RTL-SDR dongle and a USB OTG adapter or cable: see [USB and SDR adapters]({{ '/usb-and-sdr/' | relative_url }}).

## Install

1. On the phone, open the [latest release](https://github.com/railspotting/Railspotting-Android/releases/latest)
   and download `railspotting-<version>.apk`.
2. Open the downloaded file. Android asks to **allow installing apps from this source** (your
   browser or Files app): allow it, go back, and tap **Install**.
3. Open **RailSpotting**, give the receiver a name if you like (only you and the admins see it), and
   tap **Sign in with Google**. Signing in creates a RailSpotting account if you don't have one.
4. Allow **notifications** (listening shows one, with a Stop button) and **precise location** when
   asked: see [How location is used]({{ '/location/' | relative_url }}).
5. Plug the dongle in. Android asks whether RailSpotting may use the USB device: tick **always**
   if offered, and allow. Turn on the **listening switch** at the top right.

A new receiver is **provisional**: it is private until you apply for go-live on the website (My
receivers) and an admin approves it.

## Updates

The app checks for a new release once a day (and when you tap **Check now** under **Updates** in
the menu). It downloads it, checks that it is genuine (its checksum, and that it is signed with the
same key as the app you have), and installs it **when the phone is not listening**, so an update
never cuts a session short.

- The **first** update asks you to confirm, through a notification.
- After that, updates install by themselves (Android 12 and later).
- Turn automatic updates off in the menu if you prefer to update by hand.

## Signing in on another phone

A RailSpotting account has one phone receiver. Signing in on a new phone moves it there; the old
phone stops listening and says so. Signing out stops listening and forgets the phone's key; the
receiver stays on your account.

## Building it yourself

The app builds from source with Android Studio's tools, Rust and the Android NDK: see the
repository's [README](https://github.com/railspotting/Railspotting-Android#building). A build you make
yourself is signed with your own key, so it can't update an installed release (or the other way
round) without uninstalling first.
