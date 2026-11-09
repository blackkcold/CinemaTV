# CinemaTV architecture

## Boundaries

CinemaTV is an original MIT-licensed Android client. TV and mobile are separate native
Compose presentations over one shared Kotlin core. LibreTV remains a separately deployed
service. Source JSON parsing stays in `data/`; TV and mobile share the ViewModel, domain
identity, repository and playback factory. `MainActivity` dispatches on
`UI_MODE_TYPE_TELEVISION`; `TvRootApp` is the only TV route owner.

The frozen `hybrid/` host and `frontend/tv-web/` bundle are retained only as historical
A/B evidence and are scheduled for deletion. They are not a TV mainline and must not be
extended.

| Layer | Responsibility |
|---|---|
| `ui/tv/TvRootApp.kt` | The only TV route owner: route key, Back ladder, focus restoration and player handoff |
| `ui/tv/pages` | Native home, catalogs, discovery, search, detail, live, library and settings |
| `ui/tv/components` | Scene source, glass consumers, focusable cards, rails, fields, dialogs and status states |
| `ui/tv/design` | Tokens, theme, motion and the platform/role glass policy |
| `ui/tv/focus` | Live requester registry, lazy region preparation and bounded restoration |
| `ui/tv/motion` | Poster and navigation motion with reduced-motion variants |
| `ui/mobile` | Touch Material 3 screens and portrait/landscape video presentation |
| `CinemaViewModel` | Routes, presentation state, service jobs and library updates |
| `FocusCoordinator` | Logical item keys and list/grid scroll positions per route/region/title |
| `data` | LibreTV schemas, authentication, source identity, metadata cache and local records |
| `playback` | Media3 construction, bounded cache and foreground/background lifecycle |
| `ui/TvPlayerPage.kt` | TV video Surface and remote controller/track panel ownership |
| `backend/tv-bff` | Independently configured TMDB exact-match metadata adapter |

## TV rendering and application boundary

`BackdropLayer` owns the one full-screen artwork scene. It validates landscape aspect and
decoded size, keeps the previous still during loading, falls back to original Canvas art,
crossfades between works, and applies the page readability scrim. `ProductionGlassScene`
records that scene for the duration of one composition owner and exposes it through
`ProductionGlassProvider`; a consumer never records itself.

Text, icons and controls are later siblings, so glass never samples them. The moving
navigation lens samples only the scene and the glass container, never labels. Video
Surfaces, Dialog windows and the glass's own consumers are excluded. `cinemaGlassMaterial`
draws Basic as constant-cost opaque depth, Blur through API31 RenderEffect, and Full
through the approved API33 optical shader. Lifecycle, hardware, memory, power, thermal and
renderer-failure guards select a temporary fallback without rewriting the stored
preference. Reduced Motion stops animation without changing the static material. See
[production glass](GLASS_PRODUCTION.md) for exact boundaries.

Glass evidence keeps four distinct facts: the requested preference, the policy decision,
the role cap and the tier actually drawn. A policy that resolves to Full is not evidence
that Full was drawn; a missing source or a failure latch must be reported as Blur or
Basic.

TV dimensions derive from dp constraints rather than hard-coded 1920px. Five main
navigation entries and separate tools retain safe screen gutters. Movies and series share
card behavior but have different compositions.

## Focus and state

`TvRootApp` owns one Back ladder: a visible IME first, then a dialog, then a player panel
or control layer, then the route, and finally the system home behaviour. Duplicate
consumers are a defect, not a safety net.

Routes are keyed by `model.focusRoute` and wrapped in per-route state providers.
`FocusCoordinator` stores logical bookmarks per route/region plus scroll positions, while
`TvFocusRegistry` resolves live requesters and lazy regions. Restoration prefers the
stable key, falls back to the nearest surviving item in the same region, prepares an
offscreen Lazy viewport before requesting focus, and abandons a stale request as soon as
the route, the data or the user's own input changes. No View reference escapes
composition.

Catalog and episode payloads are bounded. Explicit setters make favorite updates safe when
a request fails. Room is the single library state source; no web storage is added.

## LibreTV contract

- `GET /api/status`: password/verification state and source configurations
- `POST /api/auth`: explicit password submit and in-memory session cookie
- `POST /api/search[?stream=1]`: aggregate JSON or progressive NDJSON search
- `GET /api/detail?id=...&source=...`: episode URLs and work information
- `GET /api/live/playlist?url=...`: M3U channels
- `GET /api/live/epg?url=...&channel=...`: XMLTV programs

The client never calls LibreTV’s `/api/douban` proxy. Home uses the explicitly
configured BFF catalog, falling back to local records when it is absent or unavailable.
An empty local library stays empty until the user searches LibreTV or configures a
catalog; transport availability does not establish metadata authorization. Scores retain
the catalog’s validated provenance and are never relabeled as LibreTV recommendations.

The optional BFF receives only title, year and type for metadata. It rejects
ambiguous matches and absent requested release years; image paths are validated.
Client metadata cache is bounded with positive/negative expiry. Tokens remain in
memory. No source or playback URLs are sent to TMDB.

## Playback and persistence

A player is created per playback URI, paused when the activity stops and released
on disposal. Resume follows episode identity. Measured duration is persisted with
position; unknown duration never yields a made-up percentage. Main library and per-episode history use Room with a transactional SharedPreferences import and immutable in-memory snapshots. Original legacy records remain intact; destructive fallback is disabled. Settings stay in SharedPreferences, and backup/device-transfer exports are excluded. Media3 VOD cache is 64 MiB on the basic tier and up to 256 MiB otherwise; live bypasses it. See `LOCAL_LIBRARY.md` for identity, expiry, privacy and migration details.

## QA and release

CI runs JVM/BFF/protocol tests, AndroidTest compilation, Lint and APK archive/signature
verification. Device QA lanes are not interchangeable:

- `tvNative` runs the production `MainActivity` on an Android TV system image, where
  `isTv` is true.
- `tv1080` and `tv4k` use phone images at wide viewports. They prove geometry and
  rendering only, not television device behaviour.
- `compat26` proves that the API26 install and Basic fallback path still work.

Full UI QA runs phone and TV viewports with a local protocol fixture, original art,
synthetic H.264/AAC media and screenshot/frame evidence. Performance evidence comes from
`FrameTimingRecorder` and the native-tv-perf protocol: a sample window is valid only when
the drawn tier, the source readiness and the input-to-draw pairing are all reported.
Emulator frame timings are E-gate evidence, not hardware certification. Physical TV,
low-end SoC, HDR/codec breadth, long soak, reference fidelity and production signing are
separately tracked in `RELEASE_CHECKLIST.md`.

The frozen hybrid lane and its workflows are historical. They are not TV evidence and
must not gate a native-TV release.

## Local discovery and provider provenance

Search merges local library/catalog snapshots with independent BFF and LibreTV stages.
Source snapshots reconcile before work grouping; alias/actor matches and provider-specific
filters never relax missing type, year, score or licensed chart requirements. The home
ranker uses only local explicit signals and measured progress. Browse catalogs retain
provider order. Capacity tiers also bound playback buffers and adjacent-art prefetch.

The optional BFF catalog is disabled by default. Operator-declared licensed data needs
attribution and future expiry; that declaration is not proof of rights. TMDB remains
labeled TMDB and LibreTV scores remain labeled LibreTV. No dataset is shipped. A separate,
disabled-by-default BFF Top 250 acquisition module implements fixed-origin HTML parsing,
persistent request control and snapshot caching. Its activation requires applicable written
permission; synthetic tests are not live-access verification. See [scope and activation](DOUBAN_ACQUISITION.md).
