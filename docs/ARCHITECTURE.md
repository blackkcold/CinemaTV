# CinemaTV architecture

## Intent and provenance
The project is a clean-room Android TV application. DpadTV and FongMi are architecture references only; their source files are NOT imported. LibreTV remains a separately deployed upstream service.

## Layers
- Compose presentation + TV focus components
- CinemaViewModel as application state and navigation owner
- LibreTvRepository as sole LibreTV protocol boundary
- Media3 ExoPlayer as independent playback owner
- Optional TV-BFF with self-hosted TMDB exact-match metadata enrichment

## API contract verified against LibreSpark/LibreTV
- GET /api/status -> passwordRequired, verified, defaultSources[], defaultLiveSources[]
- POST /api/auth {password} -> Set-Cookie
- GET /api/douban?type=movie|tv -> {items:[{id,title,cover,rating}]}
- POST /api/search {wd,sources,filterAdult} -> {list,failures}
- GET /api/detail?id=<vodId>&source=<encoded JSON config> -> {episodes,videoInfo}
- GET /api/live/playlist?url=<encoded M3U URL> -> {channels,groups}
- GET /api/live/epg?url=<xmltv>&channel=<tvgId> -> {programs,current,next}

## Privacy
- No server credentials embedded in APK.
- Password is sent only on explicit submit and never persisted.
- Session cookie kept in memory; re-login after process death.
- Server URL and user-managed sources stored in local preferences.
- Avoid sending source URLs to third-party metadata services.
- HTTP is supported only for private/self-hosted setups at the user's risk; use HTTPS in production.

## Functional specification
Home, explore, movies, series, search, detail, player, live, library and settings routes. Material-like remote focus and Back behavior; no touch dependencies.

## Known scope gaps
This source tree is an implementation in progress, not a production-certified TV product. Advanced real-time refraction, metadata reconciliation, cross-device sync, automatic source failover, codec fallback and DRM are not yet implemented. The repository CI status is authoritative for compile success.

## Development architecture additions

- **Domain matching:** conservative same-title/same-year checks prevent false
  cross-origin media switching and deduplicate progressive search results.
- **Media3 controls:** Compose owns D-pad controls while PlayerView owns the
  video surface. Never blur Media3's video Surface.
- **Backdrop:** 16:9 metadata is optional and supplied by a self-hosted BFF
  using operator-managed TMDB credentials. Client BFF access token remains
  in memory, not in plaintext preferences.
- **Optical sheen:** Android 13+ AGSL highlights are opt-in and are NOT
  equivalent to realtime backdrop sampling/refraction. Low-end fallback is
  a static gradient.
- **Persistence:** current bookmarks/source/library records use Android
  SharedPreferences; Room and cross-device synchronization remain release gaps.
- **CI:** Android JVM tests and AndroidTest compilation are mandatory; emulator
  and real-TV hardware validation are separately tracked.
