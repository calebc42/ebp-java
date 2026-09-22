#!/bin/sh
# Vendor the Companion core and the Android host classes into the Emacs
# Android tree, where java/Makefile.in compiles every listed .java with
# --release 7 against android.jar and d8 collects every .class under
# java/.  The ebp-java checkout stays the canonical source; this copy is
# what the APK is built from.  Host-only classes (the runner and the
# software rasterizer) are not vendored.
set -eu
cd "$(dirname "$0")/.."
EMACS=${EMACS_TREE:-"$(pwd)/../emacs/emacs"}
DEST="$EMACS/java/com/calebc42/ebp/companion"
rm -rf "$DEST"
mkdir -p "$DEST"
for pkg in json wire rpc auth store engine; do
  mkdir -p "$DEST/$pkg"
  cp src/main/java/com/calebc42/ebp/companion/$pkg/*.java "$DEST/$pkg/"
done
mkdir -p "$DEST/host" "$DEST/android"
cp src/main/java/com/calebc42/ebp/companion/host/LoopbackServer.java \
   src/main/java/com/calebc42/ebp/companion/host/StreamSession.java \
   src/main/java/com/calebc42/ebp/companion/host/PreauthPool.java \
   src/main/java/com/calebc42/ebp/companion/host/PairingFile.java "$DEST/host/"
cp android/java/com/calebc42/ebp/companion/android/*.java "$DEST/android/"
cp android/java/org/gnu/emacs/EmacsEbpHost.java "$EMACS/java/org/gnu/emacs/EmacsEbpHost.java"
find "$DEST" -name '*.java' | wc -l | sed 's/^/vendored java files: /'
