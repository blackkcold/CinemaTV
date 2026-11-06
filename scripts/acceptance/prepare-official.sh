#!/usr/bin/env bash
# Download only the existing checksum-pinned official fixture; never a user's service.
set -euo pipefail
: "${RUNNER_TEMP:?}"
ROOT=$(pwd)
DIRECTORY="$RUNNER_TEMP/official-libretv-android"
mkdir -p "$DIRECTORY" qa
cd "$DIRECTORY"
CURL=(curl --fail --silent --show-error --location --proto '=https' --proto-redir '=https' --connect-timeout 20 --max-time 90 --max-filesize 67108864)
"${CURL[@]}" 'https://github.com/LibreSpark/LibreTV/releases/download/v2.21.1/libretv-2.21.1-standalone.tar.gz' -o libretv-2.21.1-standalone.tar.gz
"${CURL[@]}" 'https://github.com/LibreSpark/LibreTV/releases/download/v2.21.1/SHA256SUMS' -o SHA256SUMS
"${CURL[@]}" 'https://nodejs.org/dist/v22.23.4/node-v22.23.4-linux-x64.tar.xz' -o node-v22.23.4-linux-x64.tar.xz
"${CURL[@]}" 'https://nodejs.org/dist/v22.23.4/SHASUMS256.txt' -o SHASUMS256.txt
cd "$ROOT"
python3 scripts/libretv-contracts/prepare_official.py --directory "$DIRECTORY" --report qa/official-artifact-verification.json
