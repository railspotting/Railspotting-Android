#!/bin/sh
# Builds the SDR driver with the NDK: libusb-1.0.so and a patched librtlsdr.so per ABI.
#
#   ANDROID_NDK_HOME=<ndk-dir> native/build.sh <min-api> <out-dir> <abi>...
#
# librtlsdr (GPL-2.0) and libusb (LGPL-2.1) are built from the pinned submodules here, with
# patches/rtl-sdr-open-sys-dev.patch added (open a dongle from an Android USB descriptor).
# They stay separate shared libraries; the core loads librtlsdr at run time, as on Linux.
set -eu

NDK=$ANDROID_NDK_HOME API=$1 OUT=$2
shift 2
HERE=$(cd "$(dirname "$0")" && pwd)

for ABI in "$@"; do
    case $ABI in
        arm64-v8a) TRIPLE=aarch64-linux-android ;;
        x86_64) TRIPLE=x86_64-linux-android ;;
        armeabi-v7a) TRIPLE=armv7a-linux-androideabi ;;
        *) echo "unknown ABI $ABI" >&2; exit 1 ;;
    esac
    HOST=$(ls "$NDK/toolchains/llvm/prebuilt" | head -n 1)
    CC="$NDK/toolchains/llvm/prebuilt/$HOST/bin/$TRIPLE$API-clang"
    # 16 KB pages (Android 15 and later) and no unresolved symbols
    LDFLAGS="-shared -Wl,-z,max-page-size=16384 -Wl,--no-undefined"
    DEST="$OUT/$ABI"
    WORK="$OUT/.work/$ABI"
    mkdir -p "$DEST" "$WORK"

    # libusb, as its own android/jni/libusb.mk builds it
    U="$HERE/libusb/libusb"
    "$CC" -O2 -fPIC -fvisibility=hidden -pthread $LDFLAGS -Wl,-soname,libusb-1.0.so \
        -I"$HERE/libusb/android" -I"$U" -I"$U/os" \
        "$U/core.c" "$U/descriptor.c" "$U/hotplug.c" "$U/io.c" "$U/sync.c" "$U/strerror.c" \
        "$U/os/linux_usbfs.c" "$U/os/events_posix.c" "$U/os/threads_posix.c" "$U/os/linux_netlink.c" \
        -llog -o "$DEST/libusb-1.0.so"

    # librtlsdr, patched in a copy so the submodule stays clean
    rm -rf "$WORK/rtl-sdr"
    mkdir -p "$WORK/rtl-sdr"
    cp -R "$HERE/rtl-sdr/include" "$HERE/rtl-sdr/src" "$WORK/rtl-sdr/"
    (cd "$WORK/rtl-sdr" && patch -p1 -s < "$HERE/patches/rtl-sdr-open-sys-dev.patch")
    R="$WORK/rtl-sdr"
    "$CC" -O2 -fPIC -fvisibility=hidden -Drtlsdr_EXPORTS \
        $LDFLAGS -Wl,-soname,librtlsdr.so -I"$R/include" -I"$U" \
        "$R/src/librtlsdr.c" "$R/src/tuner_e4k.c" "$R/src/tuner_fc0012.c" "$R/src/tuner_fc0013.c" \
        "$R/src/tuner_fc2580.c" "$R/src/tuner_r82xx.c" \
        -L"$DEST" -lusb-1.0 -llog -lm -o "$DEST/librtlsdr.so"
done
