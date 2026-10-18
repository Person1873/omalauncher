#!/bin/sh
# Builds the guest's portal program and GTK3 module (guest/omalauncher-portal) into the APK's
# assets, which the launcher installs in Linux (GuestPortal.kt). Run in an aarch64 Arch Linux
# with gcc, pkgconf, glib2, gtk3 and at-spi2-core; the launcher's own Linux will do.
set -eu
here=$(cd "$(dirname "$0")/../.." && pwd)
src=$here/guest/omalauncher-portal
out=$here/app/src/main/assets/guest-portal
mkdir -p "$out"
gcc -O2 -Wall -o "$out/omalauncher-portal" "$src/omalauncher-portal.c" $(pkg-config --cflags --libs gio-2.0)
gcc -O2 -Wall -shared -fPIC -o "$out/libomalauncher-filechooser.so" "$src/filechooser-module.c" $(pkg-config --cflags --libs gtk+-3.0)
gcc -O2 -Wall -o "$out/omalauncher-dialog" "$src/omalauncher-dialog.c" $(pkg-config --cflags --libs atspi-2 gobject-2.0)
strip --strip-unneeded "$out/omalauncher-portal" "$out/libomalauncher-filechooser.so" "$out/omalauncher-dialog"
sha256sum "$out/omalauncher-portal" "$out/libomalauncher-filechooser.so" "$out/omalauncher-dialog"
