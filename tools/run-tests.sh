#!/bin/bash
# Compiles the plugin and its tests against the hand-written Paper API stubs in ./stubs and runs them.
# Needs: a JDK 21 (javac, java) and python3 with PyYAML (only for the config.yml check).
# This is NOT a real Maven/Paper build: run `mvn clean package` for that.
set -e
cd "$(dirname "$0")/.."
B="$(mktemp -d)"
mkdir -p "$B/stubs" "$B/main" "$B/test"
javac -nowarn -d "$B/stubs" $(find stubs -name "*.java")
javac -Xlint:all -Werror -cp "$B/stubs" -d "$B/main" $(find src/main/java -name "*.java")
javac -Xlint:all -Werror -cp "$B/stubs:$B/main" -d "$B/test" $(find src/test/java -name "*.java")
python3 -I tools/flatten-config.py src/main/resources/config.yml "$B/shipped.flat" >/dev/null
for t in LogicTest FlowTest; do
  echo "== $t"
  java -Dshipped.config="$B/shipped.flat" -cp "$B/stubs:$B/main:$B/test" "com.nexuscraft.nexuswaiting.$t" 2>&1 | grep -E "FAIL|checks"
done
rm -rf "$B"
