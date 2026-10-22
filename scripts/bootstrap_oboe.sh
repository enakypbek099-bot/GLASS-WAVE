#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OBOE_DIR="$ROOT/app/src/main/cpp/third_party/oboe"

rm -rf "$OBOE_DIR"
mkdir -p "$(dirname "$OBOE_DIR")"

git clone \
  --depth 1 \
  --branch 1.9.3 \
  https://github.com/google/oboe.git \
  "$OBOE_DIR"

echo "Google Oboe 1.9.3 дайын: $OBOE_DIR"
