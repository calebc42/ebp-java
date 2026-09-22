#!/bin/sh
# Compile the Companion core (and, with `test`, the host tests) at Java
# language level 7 — the same --release 7 the GNU Emacs Android build uses,
# so nothing that cannot ship in the APK can creep into the core.
set -eu
cd "$(dirname "$0")"
JAVA_HOME=${JAVA_HOME:-"$HOME/.gradle/jdks/eclipse_adoptium-17-amd64-linux.2"}
export JAVA_HOME
export PATH="$JAVA_HOME/bin:$PATH"
FILTER='source value 7 is obsolete|target value 7 is obsolete|To suppress warnings about obsolete|^[0-9]+ warnings?$'
rm -rf build/classes build/test-classes
mkdir -p build/classes build/test-classes
find src/main/java -name '*.java' > build/main-sources.txt
javac --release 7 -Xlint:all -d build/classes @build/main-sources.txt > build/main-javac.log 2>&1 || { cat build/main-javac.log; exit 1; }
if grep -Ev "$FILTER" build/main-javac.log | grep -q .; then grep -Ev "$FILTER" build/main-javac.log; echo "core build has warnings"; exit 1; fi
test -f build/classes/com/calebc42/ebp/companion/engine/Companion.class
if [ "${1:-}" = "test" ]; then
  find src/test/java -name '*.java' > build/test-sources.txt
  javac --release 7 -Xlint:all -d build/test-classes \
    -cp build/classes:lib/junit-4.13.2.jar:lib/hamcrest-core-1.3.jar @build/test-sources.txt > build/test-javac.log 2>&1 || { cat build/test-javac.log; exit 1; }
  if grep -Ev "$FILTER" build/test-javac.log | grep -q .; then grep -Ev "$FILTER" build/test-javac.log; echo "test build has warnings"; exit 1; fi
  test -f build/test-classes/com/calebc42/ebp/companion/WireConformanceTest.class
fi
echo "build ok"
