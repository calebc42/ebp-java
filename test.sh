#!/bin/sh
# Run the host test suite against the pinned sibling ebp corpus.
set -eu
cd "$(dirname "$0")"
./build.sh test
JAVA_HOME=${JAVA_HOME:-"$HOME/.gradle/jdks/eclipse_adoptium-17-amd64-linux.2"}
export PATH="$JAVA_HOME/bin:$PATH"
EBP_DIR=${EBP_DIR:-"$(pwd)/../ebp"}
python3 tools/gen-vocabulary.py --check --spec-dir "$EBP_DIR"
exec java -Debp.dir="$EBP_DIR" -cp build/classes:build/test-classes:lib/junit-4.13.2.jar:lib/hamcrest-core-1.3.jar \
  org.junit.runner.JUnitCore \
  com.calebc42.ebp.companion.JsonTest \
  com.calebc42.ebp.companion.AuthTest \
  com.calebc42.ebp.companion.EnvelopeTest \
  com.calebc42.ebp.companion.WireConformanceTest \
  com.calebc42.ebp.companion.ValidatorTest \
  com.calebc42.ebp.companion.EngineTest \
  com.calebc42.ebp.companion.RecoveryTest \
  com.calebc42.ebp.companion.PreauthPoolTest
