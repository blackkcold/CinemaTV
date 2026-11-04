# Local library, search and metadata

## Storage and migration

The database `cinema_library.db` has explicit work, season, source mapping,
episode, library entry, episode progress and import metadata tables. Provider
URL + provider item ID scopes each source listing. Title similarity is not a
database identity. Known external IDs may associate works; playback progress
stays source/episode-scoped until an explicit mapping exists.

On first use, a single Room transaction imports legacy favorites, history and
statuses. It retains both original SharedPreferences and raw JSON backup rows.
A malformed whole array aborts import without writes; malformed individual
records are counted and skipped. No destructive Room fallback is configured.
Migration/cache failure is reported separately and does not block network
playback. Writes run on IO; Compose reads immutable in-memory snapshots.

Each episode stores measured position and duration. Unknown durations remain
unknown; switching episodes never borrows the previous episode's duration.
Completion saves before auto-next. Unknown-to-known season enrichment migrates
only unambiguous source checkpoints; different known seasons remain separate.

Database/preferences are excluded from Android backup and device transfer.
This app has no cloud profile or recommendation-history upload. Source URLs
and playback links remain private local data and are not sent to TMDB.

## Search and recommendations

Search keeps a local stage usable while catalog/source requests finish or fail.
Aliases and actors come from supplied metadata, not invented associations.
Filters apply to every stage. Missing metadata does not satisfy a type, score
or chart restriction. Source alternatives remain available without collapsing
ambiguous remakes, movie/series matches, or explicit season conflicts.

Only explicit local favorites, statuses and measured viewing activity affect
recommendations. Dropped/not-interested signals exclude matching works;
completed titles are deprioritized. Reasons identify the local signal. Empty
profiles keep provider order, and catalogs remain unpersonalized for browsing.

## Optional catalog rights

The BFF's licensed catalog is off by default. It requires a bounded operator
file or fixed URL, attribution, license reference and future expiry. This is a
technical declaration, not verification of rights. No Douban dataset, scraper,
public stream or account is supplied. See the BFF README for the full contract.

Douban filters require explicit Douban provenance and an unexpired license.
Top means a supplied valid Top 250 rank, never an inferred popular ordering.
TMDB and LibreTV scores retain separate labels. Expired licensed metadata is
removed from discovery/search and stripped from source-backed records while
retaining user-owned library/progress data. Attribution accompanies current
catalog results and details. Operators using TMDB must satisfy its logo/notice
requirements before deployment; no production licensing audit is implied.

## Validation boundaries

JVM tests cover identity, filters, ranking, expiry, capacity and checkpoints.
Android instrumentation exercises real Room import/reopen/failure behavior and
loopback HTTP contracts. Emulator viewport tests cover TV focus and phone
navigation/playback. They are not proof of physical TV performance, decoder
coverage, source compatibility with every LibreTV version or production signing.
