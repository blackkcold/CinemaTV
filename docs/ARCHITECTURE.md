# CinemaTV architecture

## Boundaries

CinemaTV is an original MIT-licensed Android client. On the hybrid Dev line, TV
browsing uses APK-local React/TypeScript/CSS; playback, services and persistence
remain Kotlin. LibreTV remains a separately deployed service. Source JSON parsing
stays in `data/`; TV and mobile share the ViewModel, domain identity, repository and
playback factory. Mobile retains its independent native presentation.

| Layer | Responsibility |
|---|---|
| `frontend/tv-web` | Local TV pages, restrained liquid glass, bounded lists and D-pad focus/scroll restoration |
| `hybrid` | Capability policy, same-origin WebView, bounded presentation DTOs, artwork proxy and explicit native actions |
| `ui/tv/design` | TV theme, constraint-based dp dimensions, motion and conservative effects policy |
| `ui/tv/components` | Validated scene, source-owned live glass, focusable cards, rails and floating navigation |
| `ui/tv/focus` | Live requester registry; no View references escape composition |
| `ui/tv/pages` | Independent home, discovery, catalogs, search, details, live, library and settings |
| `ui/mobile` | Touch Material 3 screens and portrait/landscape video presentation |
| `CinemaViewModel` | Routes, presentation state, service jobs and library updates |
| `FocusCoordinator` | Logical item keys and list/grid scroll positions per route/region/title |
| `data` | LibreTV schemas, authentication, source identity, metadata cache and local records |
| `playback` | Media3 construction, bounded cache and foreground/background lifecycle |
| `ui/TvPlayerPage.kt` | TV video Surface and remote controller/track panel ownership |
| `backend/tv-bff` | Independently configured TMDB exact-match metadata adapter |

## Hybrid rendering and application boundary

The app packages one static web bundle under `assets/tv-web/`, served through
`WebViewAssetLoader` at `https://appassets.androidplatform.net`. There is no runtime
Node server, remote website shell or JavaScript video decoder. Gradle installs the
locked frontend dependencies, type-checks/builds the bundle and stages its exact
bytes before Android packaging. Runtime dependency licenses are included.

The host checks Android API, installed WebView version and message-listener support
independently. API26 installation remains supported; the hybrid presentation is
enabled on API29+ with Chromium111+ and the restricted messaging capability. An
unsupported provider or a renderer failure exposes native recovery. System RAM and
the user's effects/motion preference bound visual work; a capability check is not
a measured device-performance certificate.

The Web UI owns focus and scroll, while `CinemaViewModel` owns business navigation.
Requests use a versioned whitelist and opaque keys. Cookies, account credentials,
arbitrary playback URLs and provider JSON stay native. Artwork is resized and
loaded through a bounded native registry. External documents, frames, scripts and
unrestricted WebView networking are excluded. Search text entry, sensitive settings
and system installation use native surfaces; playback uses the existing Media3
factory and TV player. Returning restores the saved route and logical focus.

Glass is confined to navigation and important controls, using CSS background blur,
translucent layers, highlights and shadows. It does not sample the native video
Surface or claim physical optical equivalence to Apple's renderer. Basic mode
removes blur; Reduced Motion removes transitions and card transforms. CSS capability
fallback and WebView failure recovery are independent mechanisms.

## Native recovery, player and phone rendering

A scene validates landscape aspect and decoded size before swapping; it keeps the
previous decoded still during loading. Posters remain posters. Missing art uses
original atmospheric Canvas art. Adjacent still prefetch is bounded and disabled
in basic mode. A scene-only Compose recording feeds independent glass material
boxes; text/icons are later siblings. TV owns one non-player background, while
mobile Home owns a local hero recording. The moving navigation lens samples only
scene and glass container, never labels. Video Surfaces and separate windows are
not recorded. Basic is constant-cost opaque depth, Blur uses API31 RenderEffect,
and explicit Full retains the approved API33 optical shader. Lifecycle, hardware,
memory, power, thermal and renderer-failure guards choose temporary fallback without
rewriting preferences. Reduced Motion stops animation without changing static
material. See [production glass](GLASS_PRODUCTION.md) for exact boundaries.

TV dimensions derive from dp constraints rather than hard-coded 1920px. Five main
navigation entries and separate tools retain safe screen gutters. Movies and series
share card behavior but have different compositions.

## Focus and state

Hybrid nodes keep stable geometry while an inner artwork layer animates. Navigation
remembers focus per group and saves logical bookmarks before detail/native/player
handoff. Catalog and episode payloads are bounded; the frontend mounts only a
window of each long rail. Missing or replaced items recover to a reachable control.
Explicit setters make favorite updates safe if a bridge acknowledgement is lost.
Native and web state share Room; no web database or `localStorage` library is added.

The native recovery root owns a live requester registry. Its focus and scroll bookmarks live
in the ViewModel's `FocusCoordinator`; title sessions isolate detail state from
other works. Stable provider/id keys survive progressive search and metadata
updates. Rails own horizontal key traversal, bringing offscreen targets into the
lazy viewport before requesting focus. Vertical rows retain their focus groups.
Dialogs and player panels restore their actual trigger. TV controls remain
focusable in touch-capable emulator environments; phone UI stays independent.

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

CI runs web type/build/browser tests, JVM/BFF tests, AndroidTest compilation, Lint and APK archive/signature
verification. Full UI QA runs phone and two TV viewports with a local protocol
fixture, original art, synthetic H.264/AAC media and screenshot/frame evidence.
UTP's additional-output directory retains files before APK uninstall. Preview
publishing depends on build and UI jobs for the exact commit.

The hybrid workflow adds an actual TV WebView lane and API26 recovery lane using
prebuilt exact-commit APKs. A dedicated unsigned Dev candidate records source/tree,
web lockfile, complete packaged web bytes and artifact hashes. It is not installable
until the existing external signing identity finishes it and the final signed bytes
pass installation/upgrade acceptance. See [hybrid delivery status](HYBRID_TV.md).

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
