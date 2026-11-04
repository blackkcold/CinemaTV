# Controlled static Apple CMS protocol fixture

`blender-bbb-static-cms.json` is an original, one-record protocol fixture for the
permitted Blender film identified in its adjacent manifest. It is **not a dynamic
source API**, search service, metadata catalog or deployed backend. It bundles no
media, posters, upstream code, credentials or dataset. Its own description is
original; title and identifiers are minimal public facts. The linked 240p media
is unchanged, with complete ending credits retained at Blender's origin. The
upstream title mentions 4K, but the actual test media is explicitly **240p**.

Fresh verification on 2026-11-04 checked the official API's exact UUID, channel,
account, Attribution licence and exact media URL. HTTP byte ranges, H264/AAC probe,
five-second host decode and newly decoded ending-credit frames are recorded by
digest in the manifest. The rights source requires the entire ending credits for
screening/broadcast. Do not truncate them. There is no implied Blender endorsement.

## Static semantics and evidence boundary

Every query string returns the same bytes. `pagecount: 1` describes the one fixture
page, not working pagination. The fixture does not filter `wd`, `pg` or `ids`.
LibreTV may apply its own relevance filter, but that does not make this a search
provider. In v2.21.1, detail parsing takes `list[0]`; an unknown ID would still
return this record. Never count that as correct missing-ID behavior.

A raw-GitHub probe of an existing CinemaTV file returned identical bytes for the
plain URL, the actual search query shape, and the detail query shape. This is not
a probe of this fixture's eventual URL. The exact immutable committed fixture URL
must be checked again after the parent reviews and publishes it through the
normal authorized repository workflow. This directory does not publish anything.

Offline fixture checks (not upstream or Android acceptance):

    python scripts/libretv-contracts/fixtures/test_fixture.py

## Exact next protocol test, after review and publication

1. Parent supplies `https://raw.githubusercontent.com/blackkcold/CinemaTV/<full-commit>/scripts/libretv-contracts/fixtures/blender-bbb-static-cms.json`.
   Fetch that URL, `?ac=videolist&wd=Big%20Buck%20Bunny&pg=1`, and
   `?ac=videolist&ids=6402b77c-b61f-4a06-96ca-c8420a2becf4`. Require HTTP 200 and
   the fixture SHA-256 from the manifest for all three. No redirects or private
   URL substitutions. Recheck permitted media availability at test time.
2. Launch the checksum-verified unmodified official LibreTV v2.21.1 archive using
   verified official Node 22.23.4, bound only to 127.0.0.1 with a random ephemeral
   test password and no source subscriptions. Keep all upstream SSRF checks intact.
3. Authenticate with `/api/auth`; retain the synthetic session only in memory.
   Define source `{key: "controlled-blender-static", name: "Controlled static
   Blender fixture (not dynamic API)", url: <committed URL>}`. Do not set `detail`
   or `baseUrl`: the valid embedded MP4 must resolve without fallback HTML.
4. POST `/api/search` with `{wd: "Big Buck Bunny", sources: [source],
   filterAdult: true}`. Assert one result with exact fixture `vodId`, `name` and
   source identity, plus no failures. Repeat on `/api/search?stream=1` with a fresh
   query that matches the film (e.g. its exact title) to inspect uncached `source`
   and `done` events; a cached result may emit only `done`.
5. GET `/api/detail?id=<UUID>&source=<URL-encoded source JSON>`. Assert title,
   attribution in description, and exactly one episode equal to the approved
   Blender MP4. Do not assert an episodeNames field: upstream parser does not
   synthesize that field. Check cached repeat equality. Stop the temporary process
   even on failure. Record fresh redacted evidence separately from the manifest.
6. Report only **positive controlled-static-fixture protocol parsing** acceptance.
   This does not establish a dynamic CMS bridge, real search/pagination/ID lookup,
   Android playback, a working home feed, or production readiness.

## Avoid implicit Douban traffic

The current Android connection path also loads `/api/douban`. Do not connect the
normal application UI to this official server for this protocol-only test. Use
HTTP contract calls limited to status/auth/search/detail/logout. No request to
`/api/douban`, arbitrary source, preset provider, recommendation feed or upstream
website UI is needed. An Android test must use a dedicated test entry point that
calls only these repository methods and does not invoke the application's normal
connect/home-loading flow, or wait for a separately reviewed explicit opt-out.
Do not intercept requests or patch the upstream server's SSRF/auth/security code.
