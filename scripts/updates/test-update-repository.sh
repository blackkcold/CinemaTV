#!/usr/bin/env bash
# Non-debug release instrumentation. No run-as, exported provider or storage permission needed.
set -euo pipefail
FIXTURES=$(realpath "${1:?Public TEST ONLY fixture directory}")
EVIDENCE=${2:?Evidence directory}
mkdir -p "$EVIDENCE"
exec > >(tee "$EVIDENCE/update-repository.log") 2>&1
(cd "$FIXTURES" && sha256sum -c SHA256SUMS.txt)
cp "$FIXTURES/SHA256SUMS.txt" "$FIXTURES/fixture-receipt.json" "$EVIDENCE/"
# Fresh isolated emulator only. Refuse an existing target/test package; never remove user data.
adb shell pm list packages > "$EVIDENCE/installed-before.txt"
python3 - "$EVIDENCE/installed-before.txt" <<'PYFRESH'
from pathlib import Path
import sys
installed = set(Path(sys.argv[1]).read_text().splitlines())
assert not installed.intersection({'package:dev.cinematv', 'package:dev.cinematv.test'}), \
    'Refusing existing package. Use a fresh isolated emulator for repository acceptance.'
PYFRESH
adb install "$FIXTURES/base.apk"
adb install -t "$FIXTURES/repository-test.apk"
PORT=18765
python3 -m http.server "$PORT" --bind 127.0.0.1 --directory "$FIXTURES" > "$EVIDENCE/fixture-server.log" 2>&1 &
SERVER_PID=$!
trap 'kill "$SERVER_PID" 2>/dev/null || true; adb reverse --remove "tcp:$PORT" >/dev/null 2>&1 || true' EXIT
for attempt in $(seq 1 30); do
  if curl --fail --silent "http://127.0.0.1:$PORT/SHA256SUMS.txt" >/dev/null; then break; fi
  kill -0 "$SERVER_PID" || exit 1
  sleep 1
done
curl --fail --silent "http://127.0.0.1:$PORT/SHA256SUMS.txt" >/dev/null
adb reverse "tcp:$PORT" "tcp:$PORT"
adb shell am instrument -w -r -e fixtureBaseUrl "http://127.0.0.1:$PORT/" \
  -e class dev.cinematv.data.update.UpdateRepositoryAcceptanceTest \
  dev.cinematv.test/androidx.test.runner.AndroidJUnitRunner | tee "$EVIDENCE/instrumentation.txt"
python3 - "$EVIDENCE/instrumentation.txt" <<'PY'
from pathlib import Path
import re, sys
result = Path(sys.argv[1]).read_text()
assert not re.search(r'FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed|shortMsg=', result), result
assert re.search(r'OK \(18 tests?\)', result), result
PY
printf 'PASS: real UpdateRepository signed envelope, private download and PackageManager verification on TEST ONLY release fixtures. Installer UI, real Room migrations, physical TV and production signature remain separate gates.\n' > "$EVIDENCE/result.txt"
