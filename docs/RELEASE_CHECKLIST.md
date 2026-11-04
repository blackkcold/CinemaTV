# CinemaTV Stable Release Certification

Status: **NOT CERTIFIED**. A successful GitHub Actions APK build alone does not
establish a production-grade Android TV release.

## Mandatory TV hardware QA

- [ ] 2GB RAM TV box: model / Android version / build fingerprint
- [ ] Mainstream Google TV: model / Android version / build fingerprint
- [ ] Physical 4K Android TV: model / Android version / build fingerprint
- [ ] Full 1080p and 4K screenshot comparisons to original UI reference images
- [ ] Full Glass effect performance and low-end fallback verified on hardware
- [ ] D-pad navigation, keyboard/IME, focus and scroll restoration across routes
- [ ] 4-hour continuous playback without crash, OOM, or sustained memory growth
- [ ] MP4, HLS, DASH, H.264, H.265 and 4K/HDR on explicitly capable hardware
- [ ] Player audio, subtitle, video track override and decoder failover
- [ ] Network 401/403/404, offline/online, source failover and session recovery
- [ ] Series episode indexing, progress, resume, next episode, library after restart
- [ ] Live TV channel switch, M3U, XMLTV timezones and EPG now/next
- [ ] Production signing, fresh install, debug-to-release migration and update paths
- [ ] Instrumented remote smoke tests and Macrobenchmark frame-time report
- [ ] Permissions, OSS license notices, metadata credit and lawful asset audit

## Release record

Git commit SHA: **TBD**

TV hardware test evidence URLs: **TBD**

Performance benchmark report: **TBD**

Source/metadata/stream legality review: **TBD**

Signed APK SHA-256: **TBD**

Reviewer + date: **TBD**

Open Release Gate blockers: **NOT ZERO**

This checklist is a gate, not a certification statement. Do not publish stable
until actual hardware evidence is recorded and Release Gate #1 is closed.

## v0.4.0 preview automation

Preview publication is separately gated on the exact source commit: APK archive
and debug-signature validation, Lint, JVM/BFF tests, phone instrumentation, and
1080p/4K Compose TV scenarios with reviewed visual comparisons. Missing screenshots,
focus traces, viewport metadata or rendered frame samples block publication.
The release notes link the executing workflow and the evidence ZIP carries its
reports. These automated preview checks do not check off the physical hardware
or private-signing requirements above.
