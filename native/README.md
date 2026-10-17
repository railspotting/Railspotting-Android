# The SDR driver: libusb and a patched librtlsdr

The app drives the RTL-SDR dongle with **librtlsdr**, built here from source with the NDK
(`build.sh`, run by Gradle's `sdrDriver` task) as its own shared library, with **libusb** under it.

| Library | Version | Licence | Source |
|---|---|---|---|
| librtlsdr (`rtl-sdr/`) | 2.0.3 | GNU GPL 2.0 or later | https://gitea.osmocom.org/sdr/rtl-sdr |
| libusb (`libusb/`) | 1.0.30 | GNU LGPL 2.1 or later | https://github.com/libusb/libusb |

Both are git submodules pinned to their release tags.

## The patch

`patches/rtl-sdr-open-sys-dev.patch` adds one function to librtlsdr:

    int rtlsdr_open_sys_dev(rtlsdr_dev_t **dev, intptr_t sys_dev);

Android lets an app list no USB devices. It gets a file descriptor for a dongle the owner allowed.
The function wraps that descriptor with libusb's `libusb_wrap_sys_device` (device discovery off),
then runs the rest of `rtlsdr_open` unchanged: claim, reset, tuner probe and init. That part moves
into a shared `rtlsdr_open_finish`. Nothing else in librtlsdr changes. The build applies the patch
to a copy, so the submodule stays clean.

## How the app uses it

- `librtlsdr.so` and `libusb-1.0.so` are separate libraries in the APK.
- The receiver core (`receiver/crates/engine/src/rtl.rs`) loads librtlsdr at run time with
  `libloading`, as it does on Linux, and calls `rtlsdr_open_sys_dev` when the library has it.
- Nothing links librtlsdr at build time.

## Licence obligations

Shipping the app distributes librtlsdr and libusb. Anyone who gets the APK must be able to get:
- the libraries' source;
- the patch;
- the build script;
- the licence texts.

This folder holds all of that. Where these obligations reach, and how they bear on the app's own
code, is for the maintainers to settle before the app is published (see the README).
