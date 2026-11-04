# TV Backend-for-Frontend (optional)

A small, dependency-free Node 22 BFF. It does not proxy arbitrary URLs: all Apple CMS and M3U sources are drawn from the operator's own LibreTV status configuration. This service has no third-party content bundled.

## Configure
```bash
export LIBRETV_URL=https://your-libretv.example
export LIBRETV_PASSWORD=your-server-password
export BFF_ACCESS_TOKEN=$(openssl rand -hex 24)
node server.mjs
```

Keep the access token on a trusted LAN or behind an identity-aware reverse proxy. **Do not embed server BFF tokens in a publicly distributed APK**. Native TV direct-to-LibreTV mode remains supported. For remote access use HTTPS.

## Endpoints
- GET /healthz (public)
- GET /v1/status
- GET /v1/home
- POST /v1/search {query}
- GET /v1/title/:id?sourceKey=...
- GET /v1/live/channels?sourceKey=...

Authenticated endpoints require Authorization: Bearer <BFF_ACCESS_TOKEN>. Run `npm test` to validate the schema normalization.

## Optional high-resolution backdrop metadata

Set `TMDB_READ_ACCESS_TOKEN` in the **BFF environment**, not in the APK.
The authenticated `GET /v1/metadata?title=...&year=2026&type=movie|tv`
endpoint returns `{item:{backdrop,poster,overview,releaseDate,provider,providerId},status:"matched"}`
or `{item:null,status:"unmatched"}` if an exact title/year match cannot be established.
Only title/year/type are shared with TMDB; no CMS source URLs or playback links.
Metadata images are returned as URLs, never redistributed by this service.
Respect TMDB attribution and applicable API/image usage terms.
