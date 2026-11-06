#!/usr/bin/env bash
# Host-only safety checks; no emulator, signing key, merge or publication.
set -euo pipefail
python3 -m pip install --disable-pip-version-check -r scripts/acceptance/requirements.txt
python3 -m unittest discover -s scripts/acceptance -v
python3 -m unittest discover -s scripts/tests -v
python3 -m unittest discover -s scripts/hybrid -p 'test_*.py' -v
python3 -m unittest discover -s scripts/glass-production -p 'test_*.py' -v
python3 -m unittest discover -s scripts/glass-performance -p 'test_*.py' -v
for script in scripts/acceptance/*.sh; do bash -n "$script"; done
