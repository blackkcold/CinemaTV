#!/usr/bin/env bash
set -euo pipefail
if [ "$#" -ne 1 ]; then echo 'Usage: run-upstreams.sh EVIDENCE_DIRECTORY' >&2; exit 2; fi
OUTPUT=$1
: "${RUNNER_TEMP:?}"
ROOT=$(pwd)
mkdir -p "$OUTPUT"
for VERSION in 2.21.1 2.21.2; do
  DIRECTORY="$RUNNER_TEMP/upstream-$VERSION"
  mkdir -p "$DIRECTORY" "$OUTPUT/$VERSION"
  cd "$DIRECTORY"
  CURL=(curl --fail --silent --show-error --location --proto '=https' --proto-redir '=https' --connect-timeout 20 --max-time 120 --max-filesize 67108864)
  "${CURL[@]}" "https://github.com/LibreSpark/LibreTV/releases/download/v$VERSION/libretv-$VERSION-standalone.tar.gz" -o "libretv-$VERSION-standalone.tar.gz"
  "${CURL[@]}" "https://github.com/LibreSpark/LibreTV/releases/download/v$VERSION/SHA256SUMS" -o SHA256SUMS
  "${CURL[@]}" 'https://nodejs.org/dist/v22.23.4/node-v22.23.4-linux-x64.tar.xz' -o node-v22.23.4-linux-x64.tar.xz
  "${CURL[@]}" 'https://nodejs.org/dist/v22.23.4/SHASUMS256.txt' -o SHASUMS256.txt
  cd "$ROOT"
  python3 scripts/libretv-contracts/prepare_official.py --version "$VERSION" --directory "$DIRECTORY" --report "$OUTPUT/$VERSION/artifacts.json"
  NODE="$DIRECTORY/node-v22.23.4-linux-x64/bin/node"
  python3 scripts/libretv-contracts/run.py --version "$VERSION" \
    --archive "$DIRECTORY/libretv-$VERSION-standalone.tar.gz" --sums "$DIRECTORY/SHA256SUMS" \
    --node "$NODE" --output "$OUTPUT/$VERSION/session-contracts.json"
  python3 scripts/libretv-contracts/positive.py --version "$VERSION" \
    --archive "$DIRECTORY/libretv-$VERSION-standalone.tar.gz" --sums "$DIRECTORY/SHA256SUMS" \
    --node "$NODE" --node-archive "$DIRECTORY/node-v22.23.4-linux-x64.tar.xz" \
    --fixture-url 'https://raw.githubusercontent.com/blackkcold/CinemaTV/74d104aa2b4e212163376bcbb1da04bef6670918/scripts/libretv-contracts/fixtures/blender-bbb-static-cms.json' \
    --output "$OUTPUT/$VERSION/positive-contracts.json"
  python3 scripts/libretv-contracts/subscriptions.py --version "$VERSION" --commit "${EXPECTED_SOURCE_SHA:?}" \
    --archive "$DIRECTORY/libretv-$VERSION-standalone.tar.gz" --sums "$DIRECTORY/SHA256SUMS" \
    --node "$NODE" --node-archive "$DIRECTORY/node-v22.23.4-linux-x64.tar.xz" \
    --output "$OUTPUT/$VERSION/subscription-contracts.json"
done
PYTHONPATH=scripts/acceptance python3 - "$OUTPUT" <<'PY'
from pathlib import Path
import sys
from manifest import inventory, source, write, environment
folder = Path(sys.argv[1])
write(folder / 'upstream-receipt.json', {'schema': 1, 'source': source(), 'workflow': environment(),
    'status': 'passed-controlled-official-contracts', 'evidence': inventory(folder),
    'homeServiceAcceptance': False, 'publicationAllowed': False})
PY
