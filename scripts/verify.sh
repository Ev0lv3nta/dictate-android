#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
./scripts/check-public-tree.sh
./gradlew --no-daemon test lint assembleDebug assembleRelease :app:assembleIntegration
echo 'Build, unit/contract tests and lint completed. Emulator checks run separately: scripts/emulator_smoke.py.'
