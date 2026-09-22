#!/bin/sh
# Host-only test dependencies (never shipped in the APK): JUnit 4 and
# Hamcrest, pinned by SHA-256.
set -eu
cd "$(dirname "$0")/../lib"
fetch () {
  file=$1; url=$2; sum=$3
  if [ ! -f "$file" ]; then curl -sSL --max-time 120 -o "$file" "$url"; fi
  echo "$sum  $file" | sha256sum -c -
}
fetch junit-4.13.2.jar https://repo1.maven.org/maven2/junit/junit/4.13.2/junit-4.13.2.jar \
  8e495b634469d64fb8acfa3495a065cbacc8a0fff55ce1e31007be4c16dc57d3
fetch hamcrest-core-1.3.jar https://repo1.maven.org/maven2/org/hamcrest/hamcrest-core/1.3/hamcrest-core-1.3.jar \
  66fdef91e9739348df7a096aa384a5685f4e875584cce89386a7a47251c4d8e9
