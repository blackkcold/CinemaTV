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
no real catalog, Douban dataset, purchased data or permission to use
third-party metadata. Tests use invented titles and scores only. The BFF rejects
an entire malformed or expired catalog, does not cache it, and rereads it on each
request. Clients retaining licensed results must enforce `license.expiresAt` and
show the supplied attribution; availability of a JSON file alone grants no rights.
The separately gated acquisition implementation below does not change this rights requirement.

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

## Optional direct Douban Top250 acquisition (disabled until rights are verified)

This is a **functional clean-room implementation**, with transport, HTML parser,
all ten Top250 pages, persistent request controls and cache. It is **not verified
against live Douban access**. No live acquisition was performed during development;
all regression fixtures contain invented titles and ratings. No DpadTV GPL source,
assets or bundled metadata were copied. We have no evidence of written permission
for CinemaTV, so **live activation remains blocked pending verified rights**.

[Douban's legal notice](https://www.douban.com/about/legal), section II.1–3,
requires written permission for the relevant metadata/ratings, derivative apps
and data collection. Public accessibility is not permission. Before enabling,
the operator must obtain and verify written authorization covering acquisition,
caching and distribution to these clients, and follow its scope/expiry. These
configuration declarations are required safeguards, **not proof of a license**.

Only after that verification, configure server-side:

```bash
export DOUBAN_ACQUISITION_ENABLED=true
export DOUBAN_WRITTEN_PERMISSION=true
export DOUBAN_LICENSE_NAME='The actual written authorization name'
export DOUBAN_LICENSE_URL='https://your-rights-record.example/authorization'
export DOUBAN_LICENSE_EXPIRES_AT='YYYY-MM-DDTHH:MM:SSZ'
export DOUBAN_ATTRIBUTION='The actual required rights-holder attribution'
export DOUBAN_STATE_DIR='/var/lib/cinematv/douban'
```

The expiry placeholder is intentionally invalid and does not enable anything.
The authorization URL must be HTTPS without credentials; license name,
attribution and a valid future UTC expiry are mandatory. Expired/missing rights
prevent acquisition **and serving any cached/stale acquired data**. Rights are
checked before each outbound attempt, after its response and before returning
cached records. Renewing/changing declarations does not reset request controls;
cached snapshots are bound to the exact declaration that authorized acquisition.

### Routing and fast responses

Only `GET /v1/catalog?chart=douban-top250&type=movie` uses the acquisition provider.
It takes precedence over the static feed when explicitly enabled. If its rights
or storage are invalid, the endpoint fails closed rather than silently switching
that acquisition request to another source. Disable acquisition to use the
operator-supplied static catalog instead. Ordinary query/type requests retain the
existing static-catalog/TMDB chain. TV chart requests do not start acquisition or
reuse movie acquisition status. Chart requests never fall back to TMDB/LibreTV.

The HTTP handler does **not wait for the entire download**. A cold request returns
`status: "empty"`, `items: []`, the declared license/attribution and
`acquisition.refreshing: true`. One background fill obtains starts 0,25,...,225.
Subsequent requests return a validated cached/partial snapshot immediately. User
queries, aliases, actors and pagination are filtered locally and are never sent
to Douban. Only the fixed Top250 page offsets are outbound.

An acquisition result adds:

```json
{
  "acquisition": {
    "source": "cache",
    "fetchedAt": "2026-11-04T00:00:30.000Z",
    "stale": false,
    "complete": true,
    "expectedTotal": 250,
    "availableTotal": 250,
    "warnings": [],
    "blocked": false,
    "retryAt": null,
    "snapshotId": "opaque-snapshot-id",
    "refreshing": false,
    "state": "fresh"
  }
}
```

- `source`: `network`, `cache`, `stale-cache`, or null. This asynchronous adapter
  serves stored snapshots and reports `cache`/`stale-cache`, never labels a later
  read as a new network refresh. `fetchedAt` is the last
  successfully acquired page time in that snapshot, not the time the cache was
  read. Missing data is never assigned a fabricated timestamp, year, score or cast.
- `state`: `fresh`, `refreshing`, `partial`, `rate_limited`, `access_blocked`,
  `disabled`, or `unavailable`. Always show partial/stale/blocked warnings and the
  actual count/time instead of claiming a complete, freshly refreshed Top250.
- `complete` requires all ten pages and 250 unique subject IDs/contiguous ranks
  with valid scores. Parser changes, duplicate/missing ranks, missing scores or
  page failures cannot silently turn a partial result into a complete chart.
  Missing year stays blank; missing scores stay null and are excluded by the
  existing score-qualified chart filter. Thus `availableTotal` can differ from
  the post-filter `total`. Unknown actors stay empty.
- `snapshotId` identifies one acquisition generation. Normal partial append is
  rank-ordered; ambiguity that removes earlier rows changes the identifier. A
  previous complete snapshot is preferred while a new one fills. Never append
  pages with different snapshot IDs. Restart paging once on a change, and keep
  partial results explicitly labeled if a stable full snapshot is unavailable.
- Clients may poll only while `refreshing` is true, within a bounded observation
  window. Do not automatically retry a rate limit or access block. `retryAt`
  identifies a persisted cooldown expiry; it is not permission to bypass access
  controls. A completed/failed background attempt sets refreshing false.

### Shared gate, durable cache and stop conditions

Every acquisition request, including failures, goes through one serialized gate:
minimum 3 seconds plus 0–900ms jitter, and at most 20 attempts in a rolling ten
minutes. The attempt reservation and next allowed time are persisted **before**
network access. All query/page callers share the same in-flight fill. The gate,
rolling budget, exponential failure cooldown and snapshots survive process restarts.
Both numeric and HTTP-date `Retry-After` values are honored. Server-requested long
waits are never shortened to the local backoff cap. Cooldowns stop the fill instead
of occupying a catalog response or sleeping/retrying through a ten-minute block.

403, 418 and recognized CAPTCHA/access-denial pages open a **persistent sticky
circuit**. There is no automated retry/reset, CAPTCHA handling, cookie import,
login, rotating identity, rotating user agent, proxy rotation or access-limit
bypass. Resolve permission/access with the provider before any operator-led
re-enablement; do not erase state or rotate deployments to evade a block. A
transparent fixed CinemaTV user agent is used. No poster/backdrop URLs are exposed
from acquired pages, so Android image loading cannot bypass this metadata gate.
There is no synthetic/shuffled “Douban hot” or “popular” endpoint or claim.

The only permitted origin is `https://movie.douban.com`; `DOUBAN_ORIGIN`, if set,
must equal it exactly. No request-selected hosts, paths, credentials or source
URLs are accepted. Redirects are rejected, each response is bounded to 1 MiB of
decoded bytes, and the transport timeout defaults to five seconds
(`DOUBAN_TIMEOUT_MS`, 100–10000ms). HTML parsing additionally bounds token/depth
counts and fails closed on malformed/ambiguous data.

Fresh complete snapshots default to seven days (`DOUBAN_CACHE_TTL_MS`, 1000ms to
seven days). Stale fallback defaults to at most 30 days
(`DOUBAN_STALE_MAX_MS`, at least the fresh TTL and at most 90 days), remains visibly
stale, and never overrides rights expiry. Partial snapshots never qualify as a
fresh complete cache. A failed refresh retains an older complete snapshot within
that stale/rights window rather than mixing old and new ranks.

Mount `DOUBAN_STATE_DIR` as a persistent, local, writable directory belonging to
the BFF service user. Do not use an ephemeral container directory, network pipe or
special file. Files are written atomically with restrictive permissions. Corrupt,
missing-rights, inaccessible or conflicting-owner state fails closed with no
outbound request. A process-owner lock prevents two BFF processes sharing one
state directory; dead process ownership can be recovered without resetting the
budget. This is a **single-BFF-instance control**, not a distributed global rate
limit: do not run independent replicas against the same authorized account/site
without a separately coordinated shared gate. Keep the persistent volume through
upgrades/restarts. Configuration changes require restarting that BFF instance.
