#!/usr/bin/env bash
# Thin wrapper: ./scripts/orchestrate.sh run --scenario brownfield
set -euo pipefail
cd "$(dirname "$0")/.."
JAR=orchestrator/target/orchestrator.jar
if [ ! -f "$JAR" ]; then echo "building orchestrator jar..." >&2; mvn -q -pl orchestrator -DskipTests package; fi
export ORCHESTRATOR_REPO_ROOT="$(pwd)"
exec java -jar "$JAR" "$@"
