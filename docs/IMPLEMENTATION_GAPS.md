# Product completion gaps after v0.5.0-preview

Audited 2026-11-04 against `5d4afc6d8a1014d9972125815aadb156abc9382c`.
A tested preview is a useful milestone, not completion of every original goal.
This record separates missing product behavior from missing external evidence.

## Verified baseline

The [exact-commit gate](https://github.com/blackkcold/CinemaTV/actions/runs/41896588381)
passed 151 JVM, 70 BFF and 84 host checks, plus phone 35, TV1080 9, TV4K 9
and API26 compatibility 14 instrumented tests. Both TV viewports passed six
reviewed screenshot comparisons each. These are emulator results, not physical
TV, decoder, sustained-performance or production-signing certification.

Room legacy import, source-scoped per-episode checkpoints, local recommendations,
progressive search, provenance-aware filtering, capacity budgets, approved visual
assets and regional offline Noto typography are implemented. The issues below do
not imply those existing capabilities are placeholders.

## Follow-on implementation snapshot (2026-11-04, 20:35 UTC)

The list below is the original audit, retained to explain why work was needed. It
is not a statement that those gaps remain unchanged.

- Episode identity/quarantine: [PR14](https://github.com/blackkcold/CinemaTV/pull/14),
  merged after Android build/Lint/JVM checks. Unknown legacy positional records are
  preserved without speculative transfer. Room runtime regressions are queued below.
- Bounded source resolution and protocol handling:
  [PR15](https://github.com/blackkcold/CinemaTV/pull/15), merged after build/Lint/JVM
  checks, including real stalled-socket cancellation tests. Android loopback protocol
  scenarios are registered for the combined device gate.
- Ordinary catalog paging and library cleanup controls:
  [PR16](https://github.com/blackkcold/CinemaTV/pull/16), merged after build/Lint/JVM
  checks. Partial/expired-page and intentional cleanup UI tests are registered.
- Explicit work/season/source navigation:
  [PR17](https://github.com/blackkcold/CinemaTV/pull/17), merged after build/Lint/JVM
  checks. Unknown identity stays unknown. Manual local associations are implemented
  separately in [PR21](https://github.com/blackkcold/CinemaTV/pull/21), with full-group
  stale-evidence checks, reversible overlay and source-review fixes. They remain
  draft pending Android acceptance, not assumed complete from host tests.
- Identity-safe playback recovery and stale checkpoint ownership:
  [PR18](https://github.com/blackkcold/CinemaTV/pull/18), merged after build/Lint/JVM
  checks. No automatic cross-provider progress guessing remains.
- A newly found automatic unlicensed `/api/douban` fallback and transport-labeled
  scores are removed in [PR20](https://github.com/blackkcold/CinemaTV/pull/20).
  Android build/Lint/JVM checks passed; merge is held until actual combined device
  acceptance. Local personalized records and configured catalog services remain.
- [PR19](https://github.com/blackkcold/CinemaTV/pull/19) adds a real unmodified
  LibreTV 2.21.1 contract runner. Its controlled static source passed
  [27 positive checks](https://github.com/blackkcold/CinemaTV/actions/runs/41905547692),
  alongside the earlier 52 negative/auth/configuration checks. This does not prove
  dynamic CMS filtering or Android playback. Historical cloud DNS failure is kept.
- [PR22](https://github.com/blackkcold/CinemaTV/pull/22) adds direct Android repository
  to Media3 acceptance through that official server on phone and TV1080, within the
  existing four-device matrix. It requires rendered frames, clock advancement,
  pause/resume and checkpoint reload. It is prepared, not yet executed.

At the snapshot above, the full integrated phone/TV1080/TV4K/API26 matrix, exact
source-tree provenance, new UI focus/Back behavior and release artifact verification
were pending. Later successful results must be identified by their actual release
provenance and CI run rather than inferred from this planning snapshot.
Prior v0.5 emulator results cannot stand in for these modified paths. No stable
certification, private-backend access or live Douban rights is implied.

## Original product gaps identified at v0.5

1. **Source resolution and real upstream semantics.** `LibreTvRepository.detail`
   currently tries only the first title/year candidate for a catalog work. A bad
   first provider blocks opening even when another conservatively matched source
   exists. Resolution needs bounded cancellation, explicit denial handling,
   identity validation and usable episode checks. Genuine LibreTV 2.21.1 also
   supplies `videoInfo.actor`; search failure/timing fields and incomplete streams
   need visible handling. An unconfigured official server must not appear logged in.
2. **Ordinary catalog pagination.** `catalog()` decodes `nextOffset`, but ordinary
   home/catalog and query paths load only the first 50 items per media type. Add
   explicit, bounded incremental loading, deduplication and stale-query protection.
   Licensed Top250 paging is already separate and must remain correctly labeled.
3. **Episode identity after playlist changes.** Checkpoints currently use a source
   listing, season and numeric index. Insertion or reordering can move a different
   episode under an old checkpoint. Reconcile only with supported identity evidence;
   preserve ambiguous historical records instead of transferring them by guesswork.
4. **Usable work/season/source graph.** The database contains richer entities, but
   library records and detail pages still primarily expose one source listing and
   one flat season. Add a read model and explicit season/source navigation without
   inferring cross-provider identity from similar titles or inventing missing seasons.
5. **Library controls.** Favorites can be toggled, but status removal and history
   clearing are not fully exposed in both interfaces. Provide clear, intentional
   controls with consequences explained and D-pad/touch behavior tested.

These are implementation tasks. A future commit or passing test must update their
status with its actual evidence; this plan alone does not mark them complete.

## Separate integration evidence

- The previous legally public Blender test used a PeerTube-to-LibreTV-compatible
  adapter on production base `449f358`, not a deployed LibreTV process. Its exact
  scope remains in [PUBLIC_MEDIA_ACCEPTANCE.md](PUBLIC_MEDIA_ACCEPTANCE.md).
- Official [LibreTV v2.21.1](https://github.com/LibreSpark/LibreTV/releases/tag/v2.21.1)
  is commit `29cbeb3d37b4876f909a0354087a3531af0447f6`. It must remain an independent,
  unmodified upstream process; its AGPL implementation is not copied into this app.
- A full positive chain requires the real server to call an authorized public
  HTTPS Apple CMS adapter for the real source. PeerTube is not itself Apple CMS.
  Official SSRF guards reject loopback/private upstreams and must not be bypassed.
- Authentication/configuration/negative protocol checks, controlled protocol
  fixtures, real dynamic catalog search, and actual decoded playback are different
  evidence categories. Report them separately with versions and commit IDs.

## External gates that code cannot substitute for

- Douban acquisition stays disabled until applicable rights and a permitted live
  endpoint are verified. Configuration declarations or synthetic tests are not
  permission or live acceptance.
- Private deployment testing requires separate explicit user authorization after
  isolated testing. No private address, credentials or password-manager data are
  needed for the current implementation work.
- Physical low-memory/Google TV/4K devices, codec/HDR breadth, four-hour playback,
  GPU measurements and production signing remain in [RELEASE_CHECKLIST.md](RELEASE_CHECKLIST.md).

The open stable-release checklist remains a certification gate. Older issue text
may describe pre-v0.5 behavior; use source, exact-commit test evidence and this
explicit gap distinction rather than treating an unchecked historical box as proof
that an already implemented module is absent.
