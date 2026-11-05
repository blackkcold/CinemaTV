# Douban acquisition: scope and activation gate

CinemaTV keeps Douban metadata separate from LibreTV. A LibreTV-compatible server is not assumed to provide charts, licensed ratings, or a complete Top 250.

## What the implementation does

The optional BFF adapter is an independently written Top 250 HTML transport/parser, not a bundled dataset or a renamed feed placeholder. Its fixed request sequence covers offsets 0 through 225, 25 positions per page. It retains provider subject IDs and explicit ranks. Missing years and ratings remain missing; it does not invent actors, popularity, or awards.

The existing operator-supplied licensed catalog remains supported. TMDB scores remain TMDB scores. A Douban chart request must not silently fall back to an unrelated provider.

Acquisition is disabled by default. Once an operator has applicable written permission and configures its declaration, state directory and attribution, chart requests start one shared background refresh. HTTP responses return promptly with cached or partial results and explicit acquisition status; they do not hold an Android request open for the entire ten-page download.

Android distinguishes snapshot completeness from the number of query matches. It displays the snapshot timestamp, stale/partial state and access restrictions. Movie and series catalog snapshots are independent, and pages from changed snapshots are not combined. Short polling is bounded and stops on denial or rate limiting.

## Access and cache boundaries

- One persistent gate covers this adapter's direct metadata requests, including attempts that fail. It uses spacing, a sliding budget and persistent cooldown rather than resetting the limit after restart. These are conservative application limits, not a Douban-approved quota.
- Identical and concurrent chart requests share one acquisition. Query text is filtered locally against the resulting chart; it is not added to Douban requests.
- HTTP 429 honors both seconds and HTTP-date `Retry-After`. Provider-directed waits are not shortened to the application's backoff cap.
- HTTP access denials and verification pages stop acquisition. No proxy rotation, identity rotation, login-cookie import, CAPTCHA solver or alternative endpoint is used to evade them.
- The last complete snapshot is preferred during refresh. Partial data, stale data and failed refreshes are labelled; a cache hit is not described as an online update. Expired rights cannot serve even stale licensed data.
- The adapter exports no Douban image URLs. It does not turn clients into an unbudgeted image-fetch path or reproduce the reference project's hotlink proxy. Other configured artwork providers retain their own provenance and network behavior.
- The state directory has one provider-process owner. This is not a distributed rate limiter for unrelated deployments or all traffic from an IP address.

See [BFF configuration and tests](../backend/tv-bff/README.md) for the exact environment variables, state lifecycle and recovery procedure.

## Reference review and independent implementation

Reviewed reference: [DpadTV commit fa0fe3099aaa79d0716412f3cdf592d37952b60b](https://github.com/blackkcold/DpadTV/commit/fa0fe3099aaa79d0716412f3cdf592d37952b60b), on 2026-11-04.

The reference uses website JSON and HTML, rather than demonstrating an official licensed API. Its [Top 250 path](https://github.com/blackkcold/DpadTV/blob/fa0fe3099aaa79d0716412f3cdf592d37952b60b/app/src/main/java/io/github/blackkcold/librepro/CollectionRepo.kt#L145-L163) requests only offsets 0 and 25. Its [request gate](https://github.com/blackkcold/DpadTV/blob/fa0fe3099aaa79d0716412f3cdf592d37952b60b/app/src/main/java/io/github/blackkcold/librepro/RequestGate.kt#L17-L83) and [response/cache handling](https://github.com/blackkcold/DpadTV/blob/fa0fe3099aaa79d0716412f3cdf592d37952b60b/app/src/main/java/io/github/blackkcold/librepro/CollectionRepo.kt#L173-L250) motivated the explicit 429, persistence, single-flight and freshness requirements above.

No DpadTV implementation, bundled metadata, images or branding was copied. DpadTV's GPL license does not supply rights to Douban data, and CinemaTV's MIT source license does not supply those rights either.

## What has not been established

Douban's [published legal statement](https://www.douban.com/about/legal), information/data sections 1–3, states permission requirements for reuse of movie metadata and ratings, derivative applications and collection. Operators must obtain applicable authorization and comply with its permitted endpoints, request limits, retention and attribution. An environment flag or JSON declaration is not proof of permission. Account cookies are not a substitute.

No written permission or provider credentials were available for production activation. Transport/parser/cache unit tests use synthetic fixtures and injected responses; passing them does **not** establish production access rights. A later user-requested limited public endpoint probe is documented below and is not a substitute for that activation gate.

The shipped integration is therefore implemented but inactive until the operator completes that activation gate. Real endpoint/schema validation, rights review and hardware acceptance remain separate release qualifications.

## Limited public endpoint probe (2026-11-04)

At the user’s request, an isolated cloud probe made three single-concurrency requests,
spaced at least ten seconds apart, with an explicit compatibility-probe User-Agent.
The maximum budget was six, including redirects and retries. No credentials, cookies,
proxy rotation, browser impersonation or automatic redirect following were used.

- The movie service’s `robots.txt` returned HTTP 200. Its `/j/` JSON path is disallowed;
  the probe did not call those endpoints.
- `/top250?start=0&filter=` returned HTTP 200. The existing independent parser read
  25 records, ranks 1–25 and their ratings, with no warnings and a complete **page**.
  This is not a complete 250-record snapshot or a TV/series chart.
- A single linked subject detail returned HTTP 302 to `sec.douban.com` verification.
  The probe stopped immediately without following the redirect, retrying or requesting
  a poster. Details and image download therefore remain unverified.

The [sanitized request ledger](evidence/douban-public-probe-20261104.json) records
status, timestamps and response hashes, not a redistributed dataset or security
challenge parameters. Raw pages and images are not bundled in the repository.
This establishes a narrow point-in-time public list/parser compatibility result,
not ongoing availability, rights to redistribute data, or production activation.
Acquisition remains disabled by default.
