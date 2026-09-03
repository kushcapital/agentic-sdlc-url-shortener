#!/usr/bin/env bash
# Runs the three bundled scenarios end-to-end and prints where the reports landed.
set -euo pipefail
cd "$(dirname "$0")/.."
for s in greenfield brownfield ambiguous; do
  ./scripts/orchestrate.sh run --scenario "$s" --auto-approve --quiet
done
echo; ./scripts/orchestrate.sh list
