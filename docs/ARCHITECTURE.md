# CinemaTV architecture

## Boundaries

CinemaTV is an original MIT-licensed native client. LibreTV remains a separately
deployed service. Source JSON parsing stays in `data/`; TV and mobile presentations
share the ViewModel, domain identity, repository and playback factory.

| Layer | Responsibility |
|---|---|
| `ui/tv/design` | TV theme, constraint-based dp dimensions, motion and conservative effects policy |
| `ui/tv/components` | Validated scene, bounded still-image glass, focusable cards, rails and floating navigation |
| `ui/tv/focus` | Live requester registry; no View references escape composition |
| `ui/tv/pages` | Independent home, discovery, catalogs, search, details, live, library and settings |
| `ui/mobile` | Touch Material 3 screens and portrait/landscape video presentation |
| `CinemaViewModel` | Routes, presentation state, service jobs and library updates |
| `FocusCoordinator` | Logical item keys and list/grid scroll positions per route/region/title |
| `data` | LibreTV schemas, authentication, source identity, metadata cache and local records |
| `playback` | Media3 construction, bounded cache and foreground/background lifecycle |
| `ui/TvPlayerPage.kt` | TV video Surface and remote controller/track panel ownership |
| `backend/tv-bff` | Independently configured TMDB exact-match metadata adapter |

## Rendering

A scene validates landscape aspect and decoded size before swapping; it keeps the
previous decoded still during loading. Posters remain posters. Missing art uses
original atmospheric Canvas art. Adjacent still prefetch is bounded and disabled
in basic mode. Glass samples only the same still, clipped to a small surface;
video Surfaces are never blurred or sampled. AGSL highlights are explicit opt-in,
with API/memory/reduced-motion fallback.

TV dimensions derive from dp constraints rather than hard-coded 1920px. Five main
navigation entries and separate tools retain safe screen gutters. Movies and series
share card behavior but have different compositions.

## Focus and state

The root owns a live requester registry. Logical focus and scroll bookmarks live
in the ViewModel's `FocusCoordinator`; title sessions isolate detail state from
other works. Stable provider/id keys survive progressive search and metadata
updates. Rails own horizontal key traversal, bringing offscreen targets into the
lazy viewport before requesting focus. Vertical rows retain their focus groups.
Dialogs and player panels restore their actual trigger. TV controls remain
focusable in touch-capable emulator environments; phone UI stays independent.

## LibreTV contract

- `GET /api/status`: password/verification state and source configurations
- `POST /api/auth`: explicit password submit and in-memory session cookie
- `GET /api/douban?type=movie|tv`: recommendations; optional supplied art/metadata
- `POST /api/search[?stream=1]`: aggregate JSON or progressive NDJSON search
- `GET /api/detail?id=...&source=...`: episode URLs and work information
- `GET /api/live/playlist?url=...`: M3U channels
- `GET /api/live/epg?url=...&channel=...`: XMLTV programs

The optional BFF receives only title, year and type for metadata. It rejects
ambiguous matches and absent requested release years; image paths are validated.
Client metadata cache is bounded with positive/negative expiry. Tokens remain in
memory. No source or playback URLs are sent to TMDB.

## Playback and persistence

A player is created per playback URI, paused when the activity stops and released
on disposal. Resume follows episode identity. Measured duration is persisted with
position; unknown duration never yields a made-up percentage. Main library and per-episode history use Room with a transactional SharedPreferences import and immutable in-memory snapshots. Original legacy records remain intact; destructive fallback is disabled. Settings stay in SharedPreferences, and backup/device-transfer exports are excluded. Media3 VOD cache is 64 MiB on the basic tier and up to 256 MiB otherwise; live bypasses it. See `LOCAL_LIBRARY.md` for identity, expiry, privacy and migration details.

## QA and release

CI runs JVM/BFF tests, AndroidTest compilation, Lint and APK archive/signature
verification. Full UI QA runs phone and two TV viewports with a local protocol
fixture, original art, synthetic H.264/AAC media and screenshot/frame evidence.
UTP's additional-output directory retains files before APK uninstall. Preview
publishing depends on build and UI jobs for the exact commit.

Physical TV, low-end SoC, HDR/codec breadth, long soak, reference fidelity and
production signing are separately tracked in `RELEASE_CHECKLIST.md`; emulator
frame timings are evidence, not hardware certification.

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
