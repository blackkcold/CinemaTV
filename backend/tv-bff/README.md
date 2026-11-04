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
