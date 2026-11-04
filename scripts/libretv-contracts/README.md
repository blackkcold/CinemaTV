# Isolated official LibreTV contracts

This is original MIT-licensed black-box test tooling, **not a LibreTV mock** and
not a copy of LibreTV implementation. It executes the separately downloaded,
unmodified upstream AGPL-3.0-or-later standalone release in a disposable directory.
No upstream implementation, dependencies, password or cookie is committed here.
The process listens only on `127.0.0.1`, inherits no service credentials/source
configuration, disables Next telemetry, and is stopped even after an assertion
failure. No service is installed. Its random test password exists only in memory
and the child environment; process output is discarded to avoid secret logging.

## Verified release

- Official repository: https://github.com/LibreSpark/LibreTV
- Release: https://github.com/LibreSpark/LibreTV/releases/tag/v2.21.1
- Annotated tag object: `ad9cad365540af1125b52a2cbbfa348a8f822478`
- Peeled commit: `29cbeb3d37b4876f909a0354087a3531af0447f6`
- Archive: `libretv-2.21.1-standalone.tar.gz`
- Published SHA-256: `df9b240c0fdbb8470aaccccc5435f74820d6bea6f4d905b2c83d1e6dd0973786`
- Node used: official Linux x64 `v22.23.4`, archive SHA-256
  `1805bcd35e6815559bd2eb97db438d3fdb0eb3a5e6a977b7c1badca76337e9ce`.

Tag resolution was checked through the official GitHub API. The tag is unsigned;
checksum consistency and tag resolution are not a cryptographic release signature
or proof that release binaries reproduce the commit. Runner checks both the pinned
archive digest and its matching entry in the supplied published `SHA256SUMS`.
Obtain artifacts from official release URLs normally; do not bypass denied access.
The runner does not install/download anything. Supply a separately verified official
Node 22 runtime. Python 3.12+ is required for safe tar extraction.

```sh
python scripts/libretv-contracts/run.py \
  --archive /tmp/official-downloads/libretv-2.21.1-standalone.tar.gz \
  --sums /tmp/official-downloads/SHA256SUMS \
  --node /tmp/official-downloads/node-v22.23.4-linux-x64/bin/node \
  --output /tmp/libretv-contract-evidence.json
python -m unittest discover -s scripts/tests -p test_libretv_contracts.py -v
```

Only a zero exit code and newly generated output from that execution constitute a
pass. Never use an older output after a failed run. JSON stores check names, version,
runtime and digest, never raw response bodies, session tokens or passwords.

## Coverage and observed result

On 2026-11-04 the actual standalone release passed **52 assertions** using Node
22.23.4. Offline host tests passed separately; they are not upstream acceptance.

- Missing PASSWORD: unverified status; auth and protected search return 503.
- Password configured: correct version, empty preset content sources/subscriptions,
  no recommendation override, and unauthenticated search/detail return 401.
- Malformed auth JSON returns 400; wrong password returns 401; correct password
  sets an HttpOnly, SameSite=Lax `ltv_session` cookie. Auth and status verify it.
- Invalid session is rejected; logout expires the client cookie and a cookie-free
  request is unverified. This does **not** claim server-side token revocation,
  expiration/time-travel coverage, rate-limit coverage, or HTTPS deployment QA.
- Malformed search JSON, empty/overlong search terms, missing sources, invalid
  detail IDs and source JSON return 400.
- Invalid scheme, loopback IPv4/IPv6, localhost, private IPv4 and metadata-link-local
  source URLs fail search and detail. The upstream SSRF/DNS/fetch code is unchanged.
- Negative NDJSON search emits `source` then `done`; cached negative search emits
  only `done`. Both preserve empty results and a source failure.

## Explicit acceptance boundary

No genuine positive public-media search/detail has been executed here. LibreTV
expects an Apple CMS protocol endpoint; a raw Blender PeerTube API is not one.
A loopback adapter correctly fails the unchanged SSRF checks. Positive acceptance
requires an authorized, genuinely public HTTPS Apple CMS bridge serving permitted
Blender content. Do not relax SSRF guards, intercept DNS/fetch, or count a mock as
actual upstream acceptance. No Douban or arbitrary content sources are queried.

An explicitly labeled static CMS JSON fixture hosted in the authorized CinemaTV
repository could later test upstream parsing and real permitted playback URLs.
It would not implement dynamic search, paging or ID filtering, and must not be
reported as a working dynamic bridge. This module does not publish such a fixture.
No Android/device/home service acceptance, production release, public deployment,
or integration with the user's real server is implied by these results.
