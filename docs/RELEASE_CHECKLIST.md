# CinemaTV release acceptance scope

Status: **v0.8.0 stable released with the evidence and limits below.**
Dev **0.8.0-dev.2 / code12** is a separate published prerelease with its scoped
acceptance below. The stable feed is public; Dev12 requires manual APK download
while channel-dev remains unpublished. Dev11 remains unpublished. Stable and main
exclude the account module.
This is not physical Android TV, codec/HDR or hardware-performance certification.
A stable channel label does not mean every possible device or workflow was tested.

## Stable release identity and source

| Item | Recorded value |
|---|---|
| Main release commit | `95a6704e1c7298eb5aa11a74106d0f25573c5232` |
| Source tree | `4aa3a187e711dd024caa7c5fc5bddc38baad2c8e` |
| Unsigned CI checkout embedded in both APKs | `2c119ce4d7926bbb2252ed2c0e0afa67a98a0dcd` |
| Production certificate SHA-256 | `0798f1686a28ae4a386a376efcabba3a4c4bb547de760210d944a58455092e8c` |
| Stable | `0.8.0`, code `10`, `CinemaTV-0.8.0-code10-stable.apk` |
| Stable final signed SHA-256 | `5bf5d7ff746ccde281286365e138d12050a67d8ad23d5893b3570b6127607236` |
| Unpublished acceptance target | `0.8.0-dev.1`, code `11`; not a download/release |
| Unpublished dev target signed SHA-256 | `1bd9f3f6fb28d7d9c62fb14bfa79682e5a0c6213e79081a132298c1063249262` |

The main merge and CI checkout have the same source tree. The APKs retain the
actual CI checkout revision; merging documentation does not rewrite that provenance.
Both final signed APKs are 42,839,907 bytes. Public certificates are not private keys.

## Verified coverage

- [x] PR32 exact-tree [Android build/lint/tests](https://github.com/blackkcold/CinemaTV/actions/runs/42044983340).
- [x] All 11 [update-channel CI jobs](https://github.com/blackkcold/CinemaTV/actions/runs/42044983304): stable/dev builds, unsigned candidates, phone/TV settings UI, disposable installer fixtures, API26/35 installer probes and repository acceptance.
- [x] Final stable and dev APK signatures independently verified against the production fingerprint. Installed APKs pulled from API26 match their respective final input SHA-256 values.
- [x] API26 final stable → unpublished dev11 target upgrade with `adb install -r`, preserving one real synthetic Favorite/Want to Watch record created through production UI, the synthetic server address and selected channel subscription across restart.
- [x] Installed channel/version remains distinct from selected subscription. Dev/code11 selecting stable explains that no downgrade or data clearing is performed.
- [x] Main merge [CI 42050433165](https://github.com/blackkcold/CinemaTV/actions/runs/42050433165) passed.
- [ ] API33 final signed APK installation: **infrastructure-unverified**. Android booted, then the emulator was killed during install, without an installed hash or app launch. Neither an app pass nor an app failure is established.
- [x] Separate API26 empty-device final-stable installation: installed-byte hash matches; first launch rendered with stable/code10 defaults, empty server/library, unknown-source authorization OFF, successful Back return and correct restart state. The cold launch exceeded the am-start wait threshold, with display recorded at 10.746 seconds; this is not a performance pass.

Earlier [full source UI acceptance](https://github.com/blackkcold/CinemaTV/actions/runs/42016408458) passed phone 74, API26 43, TV1080 62 and TV4K 18 cases. That test checkout was `6263d5a068bf7266aae9c95bad0b1ff9462bbc62`, source tree `aeec2b5c04d2d016d65a7ce81ee0163fcab42c5b`, and used debug/test APKs. The release tree differs in public metadata, scripts and documentation; app/src, backdrop/src and the app build configuration are unchanged. TV4K attempt1 hit its 24-minute timeout after 7/18 progress; the same head and unchanged coverage/time limits passed all 18 in attempt2 (704 seconds overall, JUnit test duration 681.079 seconds). The failed attempt remains part of the record, not a removed test or relaxed timeout.

The API26 final stable was installed over a previous same-certificate code10
candidate, then upgraded to final dev. That earlier empty-emulator installation
alone does not establish a clean install of the final stable bytes; the separate
empty-device test above provides that evidence. The retained record
was real application data backed by Room, not a separate SQLite sentinel; its
single-record scope does not establish every table, historical schema or watched
progress migration.

## Not established by this release

- Physical 2GB TV boxes, mainstream Google TV and physical 4K Android TV.
- Physical remote/IME/focus behaviour, HDMI/decoder handling and device-specific installation.
- Four-hour continuous playback, thermal behaviour, OOM resistance and sustained memory growth.
- Broad MP4/HLS/DASH/H.264/H.265/4K/HDR support across real devices.
- Full network/authentication/source/decoder failure matrix and real home-server compatibility.
- Hardware FPS/frame-time targets, optical-glass GPU cost or low-end hardware performance guarantees.
- Complete Room schema/history/progress migrations or arbitrary existing user-library retention.
- Final production online feed → validated download → permission/installer UI → completed install end-to-end.
- Seamless migration from historical debug signatures; no generic export/import or data recovery exists.

The isolated local emulator used software CPU emulation without KVM. A parallel
attempt caused host resource pressure and was not counted as a pass. A recovery
SystemUI ANR is recorded separately from CinemaTV; it is not hidden or relabelled
as app success. No CinemaTV fatal exception or app ANR was found in the captured
final API26 chain logs. Emulator launch timings are diagnostic observations, not
physical-device performance benchmarks. No real media playback was exercised in
that final-package retention run.

A later production stable10 UI check failed closed while the emulator could not
resolve `github.com`; a separate HTTPS retrieval returned the official stable feed
successfully. No in-app feed acceptance, download or installation was established,
and no Dev feed was requested. This environment blocker is not evidence of an
app or published-feed defect; production updater end-to-end remains unverified.

## Dev 0.8.0-dev.2 scope

[Dev release](https://github.com/blackkcold/CinemaTV/releases/tag/v0.8.0-dev.2) includes the default-OFF Douban personal-account
experiment, with source and detailed acceptance attachments. It does not change
the stable APK or add account code to main.

- Actual build commit: `5a076b0b715750a4de43253726fa66c205aa9b32`;
  dev merge: `553d37076ba0f9989ad5d24942f82638cf722478`;
  both trees: `c1efb18c945034e51c03ab940875cc98ea2fb743`.
- Final APK: `CinemaTV-0.8.0-dev.2-code12-dev.apk`, 43,328,036 bytes;
  SHA-256 `83ffa9aef559dded9454adb9fdb5f9864a37341ea18bb8b4961660434dcd92f9`.
  Same production certificate as stable; v2/v3 signatures and 16KB alignment passed.
- [Android build/lint/tests](https://github.com/blackkcold/CinemaTV/actions/runs/42063468515),
  [channel safety](https://github.com/blackkcold/CinemaTV/actions/runs/42063468145) and
  [API26/API33 synthetic tests](https://github.com/blackkcold/CinemaTV/actions/runs/42063468193)
  passed; the last suite ran 17 cases per device, with no failures or skips.
- API26 final-signed stable10 → dev12 `adb install -r` passed without uninstall or
  clearing data; retrieved installed bytes match. One UI-created synthetic
  Favorite/Want record and a test server survived upgrade and restart.
- Default-OFF, cancel/confirm enable, empty-account disable/cleanup and OFF after
  restart passed. A Stable channel preference selected on dev12 survived restart.
  The stable baseline had no saved channel preference, so the default became Dev;
  pre-upgrade explicit preference migration was **not** tested.
- Real login, complete sync and real-session cleanup, all-table/history migration,
  Dev → Stable migration, fresh dev12 install, API33 final-APK installation,
  production updater end-to-end, hardware and four-hour playback remain unverified.
  Synthetic instrumentation and `adb install -r` do not cover these gaps.

Dev code12 cannot downgrade to stable code10. Before a future higher-code stable
upgrade, explicitly disable and confirm account cleanup in dev; preserve the
installation rather than uninstalling. See [installation instructions](INSTALL.md).

## Future release checks

1. Reserve one new globally increasing versionCode across both channels.
2. Record source commit/tree, exact final APK hash, package/channel/version/minSdk
   and production certificate; never substitute a debug or earlier candidate hash.
3. Run applicable build/lint/unit/backend and UI checks. State which test artifacts
   differ from the final production binary.
4. Exercise the actual final APKs for the declared acceptance scope and preserve
   evidence. Report outstanding hardware or workflow limits explicitly rather
   than claiming certification.
5. Verify uploaded release bytes and any signed feed independently. Record feed
   signature, payload identity, URL/hash/size and expiry. Feed availability alone
   is not a final-APK updater end-to-end test.
6. Retain lawful-source/metadata/asset boundaries and OSS notices. This client
   bundles no public stream source, user account or media catalogue.

## Historical previews

Preview automation uses debug signatures and its own exact-tree promotion checks.
Those results remain attributed to those versions and do not become production
signature or physical-device evidence. README screenshots remain explicitly
labelled **v0.7.0-preview**; do not relabel them as v0.8 captures.
