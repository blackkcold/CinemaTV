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

The November 6 audit additionally checked **v2.21.2**, commit
`722e86fce102aa6701e73a83dd0a14eb5ba707c9`. Status, search and detail response
contracts remain compatible. The source-list implementation uses the normalized
[`source-list` route](https://github.com/LibreSpark/LibreTV/blob/722e86fce102aa6701e73a83dd0a14eb5ba707c9/src/app/api/source-list/route.ts),
[`status` defaults](https://github.com/LibreSpark/LibreTV/blob/722e86fce102aa6701e73a83dd0a14eb5ba707c9/src/app/api/status/route.ts)
and [public response types](https://github.com/LibreSpark/LibreTV/blob/722e86fce102aa6701e73a83dd0a14eb5ba707c9/src/lib/types.ts).
These links document the wire schema; no upstream parser or application code is
bundled into the Android app. CI executes the separately supplied pinned releases.

## Authentication

`GET /api/status` is public. `passwordRequired` means the operator configured
PASSWORD; it does **not** mean authentication is optional when false. Official
false/false means missing configuration; protected routes return 503. Only a
verified session permits protected operations. 401 clears the session cookie for
ordinary JSON and streaming search. Redirect following is disabled.

Each request captures an immutable server/generation context and a private
in-memory cookie revision. Switching servers or submitting a new login replaces
that ownership. Late authentication, status, detail, stream and 401 responses
cannot write into a new server or a newly authenticated session on the same
server. Status and HTTP-error receipts contain only the generation and revision;
the ViewModel checks them again after dispatch before publishing state. Cancelling
old network work does not delete the local library. A search 401 exposes login
while retaining the query, local matches and already received successful results;
429, timeout and protocol errors have separate messages.

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

LibreTV's detail cache is scoped by provider URL/root/item ID rather than the
client's local source key. When `sourceUrl` is supplied it must match the requested
URL; an old cached `sourceKey` from another client is then rebound to the current
source. Without a URL, a supplied key must still match. An explicit different URL
never falls back to a matching key. Search uses the same URL-first source policy.

## SourceList subscriptions

Settings on both native TV and phone expose **片源列表订阅**. The local hybrid
frontend opens the native section; raw subscription/source URLs and credentials
never enter the WebView bridge DTOs.

- `defaultSubscriptions` is discovered independently of default VOD/live sources,
  including deployments which advertise only SourceLists. Users may also supply
  their own HTTP(S) subscription URL. Canonicalization preserves meaningful path
  slashes and query bytes; URL credentials and invalid ports are rejected.
- An authenticated `GET /api/source-list?url=...` returns normalized `sources`,
  `liveSources` and optional `stats`. LibreTV owns fetching, URL access policy,
  SourceList/TVBOX/source-catalog parsing, and unsupported-entry filtering. Android
  does not execute Spider, XML, scripts or alternative direct-provider parsers.
- The native preview shows VOD/live counts, added/changed/removed memberships,
  individual source names/addresses, upstream skipped reasons/samples, and local
  invalid/duplicate/truncation counts. All TV rows are native focus targets in a
  lazy list. No saved source changes until the user confirms the reviewed preview.
- A subscription stores its URL/name/refresh interval, last accepted snapshot,
  update time and revision. A refresh replaces that record **in place**, preserving
  precedence when multiple subscriptions share a source URL. The preview's prior
  revision and connection/session receipt must still match at confirmation.
- Manual sources take precedence over subscription records, then server defaults.
  Refresh never changes the URL-based disabled-source preference, a manual source,
  another subscription, or library/progress records. Failure, malformed/empty output
  and stale previews preserve the last accepted record. Corrupt persisted data is
  retained and blocks overwrite rather than becoming an empty writable store.
- **停止订阅，保留片源** materializes only this subscription's currently effective
  sources as manual entries before removing its refresh relationship. A shadowed
  entry cannot become an override of an active subscription. Shared preference
  read/modify/write operations for subscriptions and manual sources are serialized.
  Removing a manual entry explicitly reports if a subscribed/default source remains.
- Limits are 20 saved/advertised subscriptions, 100 VOD and 50 live entries per
  snapshot, a 2,048-character URL, and bounded response/scan sizes. Search retains
  its existing 50-enabled-source budget, stated in the native subscription UI.
  Refresh is explicit; the server-provided interval is shown as a recommendation,
  without background fetching or importing unreviewed changes.

`SourceSubscriptionsTest` exercises normalized decoding, limits, URL preservation,
storage round trips, diffs and invalid output. `LibreTvProtocolTest` adds real
loopback default-only discovery, authenticated import, refresh/overlap precedence,
disable/manual/library preservation, restart, stale/error protection and a gated
storage/manual-add race. `SourceSubscriptionsUiTest` exercises the production
phone controls and native D-pad traversal of long previews. Device execution
evidence belongs to the exact candidate's Actions run, not this source description.

## Native replay and guide identity

Episode playback has explicit RESUME/RESTART intent. Selecting a completed
checkpoint (the existing 95% duration criterion) starts at zero; incomplete RESUME
uses only that source/episode's saved position. Explicit RESTART and automatic next
episode start at zero without erasing saved history. Live channel selection uses
source identity, channel ID and full stream URL; EPG/tvg IDs can therefore be shared
by legitimate HD/SD alternatives. The visible guide may refresh its selected
channel at most once per 15 minutes, without parallel requests; source/session
and channel ownership still gate response publication.

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

### Instrumentation fixture storage isolation

Candidate run `41971271586` found two `LibreTvProtocolTest` failures before any
request was sent: `row()` could not select a unique fixture source. All cases had
reused one preference-file name and deleted it between cases, while repository
settings are intentionally persisted with asynchronous `SharedPreferences.apply()`.
A pending write can race deletion/reopening of that shared test file. This is
fixture contamination, not evidence of accepting a forbidden provider response.

Protocol and repository contract cases now use unique UUID-suffixed preference
names and hold the same SharedPreferences instance for their lifetime. Setup
requires an empty fixture; protocol setup also requires exactly its own loopback
source. Cleanup commits an empty state on that same instance before deleting its
file. An overlapping-fixtures regression uses a gated IO-thread writer, asserts distinct
backing files, reads the first fixture during repeated asynchronous writes to the
second, then joins/cleans up and verifies the first remains unchanged. The original
single-source, 403-stop, cached-done ordering and cancellation assertions remain
intact. No product storage, user data, transport or ranking behavior changes.
Exact-candidate Android execution is still required before release.

Platform behavior checked against AOSP Android 11: `ContextImpl.deleteSharedPreferences`
evicts the cache and deletes files without draining another instance's queued writes;
`SharedPreferencesImpl.EditorImpl.commit` waits on its disk-write completion.
- https://android.googlesource.com/platform/frameworks/base/+/android-11.0.0_r1/core/java/android/app/ContextImpl.java
- https://android.googlesource.com/platform/frameworks/base/+/android-11.0.0_r1/core/java/android/app/SharedPreferencesImpl.java
