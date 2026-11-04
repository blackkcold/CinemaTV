# LibreTV adapter contract and playable-source resolution

## Version and primary evidence

Contract reviewed against official LibreSpark/LibreTV **v2.21.1**, commit
`29cbeb3d37b4876f909a0354087a3531af0447f6`. These are protocol references,
not embedded/copied upstream implementation:

- [Package version](https://github.com/LibreSpark/LibreTV/blob/29cbeb3d37b4876f909a0354087a3531af0447f6/package.json)
- [Status](https://github.com/LibreSpark/LibreTV/blob/29cbeb3d37b4876f909a0354087a3531af0447f6/src/app/api/status/route.ts)
- [Authentication guard](https://github.com/LibreSpark/LibreTV/blob/29cbeb3d37b4876f909a0354087a3531af0447f6/src/lib/api-guard.ts)
- [Search](https://github.com/LibreSpark/LibreTV/blob/29cbeb3d37b4876f909a0354087a3531af0447f6/src/app/api/search/route.ts)
- [Detail](https://github.com/LibreSpark/LibreTV/blob/29cbeb3d37b4876f909a0354087a3531af0447f6/src/app/api/detail/route.ts)
- [Public response types](https://github.com/LibreSpark/LibreTV/blob/29cbeb3d37b4876f909a0354087a3531af0447f6/src/lib/types.ts)

No speculative alternate endpoints, embedded source feeds, credentials, or automatic direct-provider bypasses are added.

## Authentication

`GET /api/status` is public. `passwordRequired` means the operator configured
PASSWORD; it does **not** mean authentication is optional when false. Official
false/false means missing configuration; protected routes return 503. Only a
verified session permits protected operations. 401 clears the session cookie for
ordinary JSON and streaming search. Redirect following is disabled.

## Search

`POST /api/search` sends `{wd,sources,filterAdult:true}` with enabled configured
sources only (maximum 50), and receives `{list,failures}`. The list fields include
`vodId`, `name`, `sourceKey`, `sourceName`, optional `sourceUrl`, `year`, `pic`,
`typeName`. Search results do not officially provide actors, aliases, season,
explicit movie/TV booleans, or authoritative metadata IDs. Unknown media type
stays unknown; a small explicit set of unambiguous Chinese film/drama category
names can provide type evidence. Animation/documentary/variety are not inferred.

`?stream=1` produces newline-delimited `source` events carrying `sourceKey`,
`ok`, `list`, optional `error`, `timedOut`, `ms`; a final `done` event carries
ordered `list` and `failures`. `SearchSnapshot` preserves invocation-local
outcomes, timeout and latency. The final list is authoritative, not appended.
A cache hit may consist of `done` alone, with no fabricated successful-source
latencies. End-of-stream without `done` fails; already emitted partial lists may
remain visible with an incomplete-search warning. Cancellation disconnects the
connection rather than waiting for another event. One ordinary JSON response
without a `type` remains supported for older compatible proxies.

## Detail

`GET /api/detail?id=...&source=<encoded JSON>` uses the actual selected source and
its original item ID. Response contains `episodes: string[]` and `videoInfo`.
The official cast key is singular `actor`. The adapter also accepts documented
legacy `actors` and `vod_actor`; positional optional `episodeNames` and
`episodeIds` are preserved without deduplication, filtering, or invented values.
Official responses omit these richer episode fields. An authoritative new playlist's
season comes only from the detail response; missing season remains unknown, not
borrowed from cached/catalog metadata. This prevents known-season checkpoints
from attaching to reused listings with identical episode labels. An explicit valid episodes
array marks an authoritative playlist; missing/malformed arrays are protocol
errors, not empty playlists. Direct-source authoritative empty detail can update
the local record while preserving quarantined checkpoints, but cannot play.

JSON and NDJSON use the app's explicit OkHttp 4.12.0 dependency (Apache-2.0;
[Maven Central published POM](https://repo.maven.apache.org/maven2/com/squareup/okhttp3/okhttp/4.12.0/okhttp-4.12.0.pom)).
Cancellation calls `Call.cancel()` to close active sockets, including blocked
response-body reads. Redirects, SSL redirects, and implicit connection retries
are disabled. The streaming API remains a callbackFlow with cancellation cleanup.
`HttpURLConnection.disconnect()` alone was rejected after a real JVM loopback
body-stall test showed it could block behind a read; a coroutine-only delay test
would not have exposed this.

The JSON read timeout is 25 seconds to cover the official detail route's possible
two sequential 10-second upstream attempts. Transport is cancellable and bodies
are bounded; it does not probe media URLs or promise that a syntactically valid
URL will decode successfully on a device.

## Catalog-to-playable resolution

- Search the canonical title plus at most two distinct supplied aliases, never
  generated/transliterated/fuzzy titles. Up to six distinct `(source URL, item ID)`
  details total, with a 75-second coroutine deadline.
- Consider only results belonging to enabled configured sources and matching a
  normalized exact known title. Conflicting known year, type, or season rejects
  a candidate. Repeat validation against both the catalog work and search
  candidate after detail is returned.
- A detail must have at least one episode and every URL must be absolute HTTP(S)
  with a host and without URL credentials. Do not silently drop invalid entries
  or shift episode indexes.
- Try another known matching candidate after ordinary connection/unavailability
  failures, 404/408/500/502/504, or valid empty/unusable/conflicting detail.
  Access/authentication errors, rate limits, 503 configuration failures,
  protocol failures, and cancellation stop. Search outcomes indicating access
  denial also stop the operation, even if another result is present. No changing
  identity, routes, credentials or sources to work around a denial.
- Expose bounded search/attempt/resolved progress with source name. Preserve the
  chosen source and its ID. Catalog attribution/rating licensing can accompany
  the result, but title matching never copies catalog IDs into an authoritative
  cross-provider mapping.

## Verification

`SourceResolutionTest` covers fallback, empty/unsafe playlists, identity conflict,
aliases, budgets, denial and cancellation. It also executes real loopback HTTP
header-stall and body-stall cases against the exact shared transport helper:
a 500 ms coroutine deadline cancels a 20-second-read-timeout call, and the peer
must observe EOF before teardown. These tests verify actual socket behavior on
the host JVM; Android's own socket tests remain a separate required gate. The
75-second value is the operation's cancellation budget, not a guarantee about OS
scheduling or platform DNS cancellation; the verified cases are blocked HTTP
headers/body, not every possible network stack stall.
`LibreTvProtocolTest` uses only a synthetic loopback HTTP server and in-memory
Room to cover official actor decoding, source fallback, empty/conflicting detail,
stream outcomes/truncation/legacy form, cookie reset and blocked-request
cancellation. These tests do not exercise live Douban, public media, remote
provider access, or real-device decode capability. Android instrumentation must
be run by the normal device/CI gate before claiming device verification.
