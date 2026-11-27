#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
kotlinc app/src/main/java/com/aicommandcenter/app/CommandEngine.kt tests/EngineTests.kt -include-runtime -d "$tmp/engine-tests.jar"
java -jar "$tmp/engine-tests.jar"
kotlinc app/src/main/java/com/aicommandcenter/app/UpdateRules.kt tests/UpdateTests.kt -include-runtime -d "$tmp/update-tests.jar"
java -jar "$tmp/update-tests.jar"
python3 tests/delta_fixtures.py "$tmp"
kotlinc app/src/main/java/com/aicommandcenter/app/{UpdateRules,ApkDelta}.kt tests/DeltaTests.kt -include-runtime -d "$tmp/delta-tests.jar"
java -jar "$tmp/delta-tests.jar" "$tmp"
kotlinc app/src/main/java/com/aicommandcenter/app/{CommandEngine,IntegrationRules}.kt tests/IntegrationTests.kt -include-runtime -d "$tmp/integration-tests.jar"
java -jar "$tmp/integration-tests.jar"
