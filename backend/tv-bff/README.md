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
- GET /v1/catalog?query=...&type=movie|tv&limit=24 (optional providers)
- GET /v1/metadata?title=...&year=...&type=movie|tv (optional TMDB)
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

## Optional authorized catalog and provider fallback

`GET /v1/catalog?query=...&type=movie|tv&limit=24&offset=0` is authenticated like the other
`/v1` routes. `query` is optional (maximum 100 characters), `type` defaults to
`movie`, `limit` is an integer from 1 to 50 (default 24), and `offset` is an integer
from 0 to 4950 (default 0). Optional `chart=douban-top250` restricts results to the
licensed Douban chart described below. Unknown or repeated
parameters are rejected with HTTP 400. Missing/invalid bearer authentication is
HTTP 401. The response is always HTTP 200 for provider failures so the client can
keep using LibreTV:

```json
{
  "status": "available",
  "provider": "Example authorized provider",
  "items": [{
    "id": "catalog:Example%20authorized%20provider:movie:sample-1",
    "provider": "Example authorized provider",
    "providerId": "sample-1",
    "title": "Synthetic Film",
    "type": "movie",
    "year": "2025",
    "aliases": ["Synthetic Alternate Title"],
    "actors": ["Synthetic Actor"],
    "poster": "",
    "backdrop": "",
    "overview": "",
    "rating": 8.0,
    "ratingSource": "Synthetic ratings",
    "ratingScale": 10,
    "topRank": 5,
    "topSource": "Synthetic chart"
  }],
  "attribution": "Example attribution supplied by the rights holder",
  "license": {
    "name": "Example metadata license",
    "url": "https://provider.example/license",
    "expiresAt": "2099-01-01T00:00:00Z"
  },
  "attempts": [{"provider": "Example authorized provider", "status": "available"}],
  "total": 1,
  "nextOffset": null,
  "fallback": null
}
```

The example above is **synthetic**, not licensed real-world metadata. Fields:

- `status`: `available` means nonempty results; `empty` means at least one provider
  answered successfully but none returned matches; `unavailable` means every
  configured provider failed or was disabled.
- `provider`, `attribution`, `license`: identify the selected provider, or are all
  null when unavailable. TMDB always uses `provider: "TMDB"`, its attribution
  notice, and `license: null`. A null license does not grant unrestricted usage.
- `attempts`: ordered providers tried, with `available`, `empty`, `unavailable`, or
  `disabled` status. An unreadable/malformed licensed source is named `licensed`
  rather than disclosing its URL, file path, token or upstream error.
- `fallback`: `"libretv"` for ordinary empty/unavailable results, otherwise null.
  Chart-only requests always return null because LibreTV is not a licensed chart
  substitute. The client
  should retain its ordinary LibreTV browse/search flow. This endpoint does not
  silently scrape Douban, call LibreTV's `/api/douban`, merge scores across sources,
  or supply playback URLs. Existing `/v1/home`, search, metadata and other routes
  retain their compatibility behavior. Legacy LibreTV home ratings are upstream
  data and must not be treated as proof of licensed Douban provenance.
- `rating`, `ratingSource`, `ratingScale`: all null for an unrated item. A score
  must always be shown with its source and scale. TMDB results use TMDB/10 only
  when the upstream reports a valid score and a positive vote count.
- `topRank`, `topSource`: both null when no sourced chart position is supplied.
  TMDB popular ordering is **not** a Douban Top chart and never sets these fields.
  The Douban Top filter below requires explicit licensed Douban chart and rating
  provenance; no other source score may be relabeled.
- `aliases`, `actors`: arrays, empty if unavailable. TMDB search/popular contributes
  only the original title/name as an alias; no cast is guessed or fetched.
- IDs are provider/type scoped and do not imply an exact match to a playable
  LibreTV title. Resolve playback through the existing source search/detail flow.

### Licensed pagination and Douban Top250

Only a selected licensed response includes `total` (integer count after all
query/type/chart filtering) and `nextOffset` (next accepted offset, or null).
Filtering by title, alias and actor happens before slicing, so later matches are
not lost. Offsets past the filtered result set return a licensed `empty` response
with its original total, no items, and a null nextOffset. Fetch the next page with
the same query/type/chart/limit. Use `limit=50` to cover all 5000 possible records:
the maximum offset is 4950; smaller limits cannot advance beyond that boundary.
Operators should replace catalog files atomically and avoid changing content
mid-pagination; this API does not promise a multi-request snapshot. License
expiry is revalidated on every page, and clients must stop and discard expired
licensed results rather than retaining an earlier page indefinitely.

`chart=douban-top250` is **licensed-provider only**. It first requires both
`topSource` and `ratingSource` to normalize to `Douban` or `豆瓣` (Unicode width,
case and surrounding whitespace normalized), a rank in 1..250, a non-null valid
rating and `ratingScale: 10`. Matching records are sorted by rank, then stable ID,
before pagination. This selects the operator's supplied chart records; it does
not assert that the provider supplied a complete or unique 250-title chart.
Request `limit=50` and follow `nextOffset` for at most five pages when the user
selects the Top250 filter. Do not eagerly load chart pages on ordinary browse.
Chart requests never call TMDB and never offer LibreTV as a chart substitute.
A disabled, malformed or expired licensed provider returns `unavailable`, while
a valid source with no qualifying chart entries returns `empty` with total zero.

TMDB continues to request its first upstream page only and does not expose total
or nextOffset. For `offset>0`, a valid licensed result is returned, including an
empty final page. If no valid licensed source is available and TMDB is configured,
the response is `unavailable` with `reason: "tmdb_offset_unsupported"`; no TMDB
request is made. Offset-zero requests without a chart preserve the usual fallback
chain and existing client behavior.

### Enable an operator-supplied catalog

The provider is **disabled by default**, even if a path or URL is present. Enable
it only after verifying rights to the metadata, ratings, chart rankings, names,
artwork URLs and any required attribution for your intended distribution/use:

```bash
export LICENSED_CATALOG_ENABLED=true
# Choose exactly one fixed, operator-controlled source:
export LICENSED_CATALOG_FILE=/etc/cinematv/authorized-catalog.json
# Or: export LICENSED_CATALOG_URL=https://your-provider.example/catalog.json
```

Both sources use the same JSON document contract:

```json
{
  "schemaVersion": 1,
  "provider": "Example authorized provider",
  "attribution": "Required rights-holder attribution",
  "license": {
    "name": "Applicable license or agreement name",
    "url": "https://provider.example/license",
    "expiresAt": "2099-01-01T00:00:00Z"
  },
  "items": [{
    "providerId": "sample-1",
    "title": "Synthetic Film",
    "type": "movie",
    "year": "2025",
    "aliases": ["Synthetic Alternate Title"],
    "actors": ["Synthetic Actor"],
    "rating": 8.0,
    "ratingSource": "Synthetic ratings",
    "ratingScale": 10,
    "topRank": 5,
    "topSource": "Synthetic chart"
  }]
}
```

Required document fields are `schemaVersion: 1`, a nonempty provider/attribution,
license name/HTTP(S) URL/future UTC ISO expiry, and an items array. `TMDB` is reserved
for the real TMDB adapter. Each item requires a string `providerId` unique within
its type, a title, and `type: movie|tv`. Optional text fields default to empty
strings, arrays to empty arrays, and rating/chart fields to null. `year`, when
provided, is four digits. Strings must contain no control characters. Maximum
lengths: provider/source/ID 120, title 300, overview 4000, attribution 1000, license
name 200 and URL 2048 characters. Aliases/actors each allow 30 strings of up to 200
characters. A rating requires an explicit source and numeric scale greater than
zero and no more than 100, with score between zero and that scale. A chart rank
requires its own source and an integer rank from 1 to 100000. Image/attribution
links accept only HTTP(S), without embedded credentials or fragments.

The declaration is **not evidence of legal licensing**. The operator, not the
configuration parser, must verify that the license is genuine, covers every
supplied field and image, permits distribution to clients, and remains in force.
Do not set a distant expiry to circumvent an actual license term. CinemaTV ships
no real catalog, Douban dataset, scraper, purchased data or permission to use
third-party metadata. Tests use invented titles and scores only. The BFF rejects
an entire malformed or expired catalog, does not cache it, and rereads it on each
request. Clients retaining licensed results must enforce `license.expiresAt` and
show the supplied attribution; availability of a JSON file alone grants no rights.

### TMDB fallback, privacy and operational limits

With `TMDB_READ_ACCESS_TOKEN` configured, ordinary offset-zero catalog requests
without a chart fall back to TMDB when
the licensed provider is disabled, unavailable, expired or has no matches. A
nonempty query uses `/search/movie` or `/search/tv`; no query uses `/movie/popular`
or `/tv/popular`. Only the first result page is requested (so fewer than `limit`
results may be returned), and adult-flagged entries are omitted. Set
`CATALOG_TMDB_ENABLED=false` to disable **catalog** fallback without changing the
existing `/v1/metadata` route. Keep TMDB secrets on the server. Operators must
comply with [TMDB API/attribution requirements](https://developer.themoviedb.org/docs/faq),
including the required notice/logo and any commercial licensing requirements.
See the official [search API](https://developer.themoviedb.org/reference/search-tv)
and [popular API](https://developer.themoviedb.org/reference/movie-popular-list).

The catalog adapter accepts only fixed operator-configured URL/file destinations.
It never accepts a request-selected URL/path, forwards client headers/cookies,
or follows redirects. HTTPS is recommended outside a trusted test/LAN network.
The licensed endpoint receives a plain document GET with **no user query**, watch
history, preferences or profile. TMDB receives only an explicitly requested query
and type, plus fixed language/adult-filter/page parameters. There are no automatic
personalized queries, analytics, or outbound watch-history updates in this API.

Remote calls have a 5-second timeout per provider, configurable through
`CATALOG_TIMEOUT_MS` from 100 to 10000 milliseconds. Each JSON document is limited
to 1 MiB of decoded bytes and 5000 input items, regardless of Content-Length;
responses return at most 50 items. Local files must be regular files within the
same size limit. Do not mount network pipes or special files. Both providers
unavailable can take up to twice the per-provider timeout. Restrict endpoint
access and apply ordinary reverse-proxy request/rate limits for your deployment.
`TMDB_API_URL` is an operator-only override, mainly for mocks/proxies; never expose
it to clients or point it at an untrusted service, since it receives the TMDB token.
