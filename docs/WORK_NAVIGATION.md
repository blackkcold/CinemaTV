# Work, season and source navigation

The library's favorite and status shelves now show one representative per explicit work ID.
Filtering happens before deduplication: a source marked watched and another marked watching
can still appear on their respective shelves. Favorites, statuses and episode checkpoints
are never unioned or copied. History deliberately retains source-specific entries.

Detail pages on phone and TV expose known seasons and their individual source listings when
there is more than one choice. Unmapped seasons are labeled “季数未知”, not season one.
Source labels include the provider's listing ID and individual library flags. Switching
loads that exact source and resumes only its own checkpoint. Back returns to the originating
section; season/source changes keep the detail focus route, and the controls register stable
logical focus IDs. Removed choices and callbacks from another work are ignored.

The projection uses the safe metadata policy before constructing the work graph. Expired
catalog data does not continue associating sources; metadata and attribution are read from
the selected source, not arbitrarily taken from another season. Equal titles alone do not
group anything. This is local library navigation, not discovery of every season on a server.

## User-confirmed local mapping

Phone and TV detail pages include “管理本地作品关联”. The editor lists only retained local
entries, never server discovery or title-similarity matches. It displays the selected source
and target's provider, service URL, listing ID, title, year, media type, original season and
external IDs. Different years/types or conflicting external IDs produce an explicit warning.
The final evidence-review button is required before linking. Season unknown, specials/zero,
and numbered seasons are explicit manual labels. These labels never change `MediaItem`,
external IDs, episode keys or playback checkpoints. “手动关联” is not catalog verification.

The versioned `local_work_associations_v1` Room metadata overlay contains immutable original
entry IDs, random local work IDs, created/updated timestamps and `user-confirmed` provenance.
No Room schema change, destructive migration or legacy import rewrite is required. Unsupported
versions and malformed payloads disable editing and are retained verbatim. Transactional
revision checks reject stale concurrent writers; complete preview membership plus current-source and every target-member identity evidence
are rechecked before commit, including metadata changes that do not bump the overlay revision. A rejected write reloads the winning state for another review. This is **local-only**:
there is no multi-device sync or distributed conflict resolution. Concurrent-instance tests
are not a claim of cross-device synchronization.

Joining an unassociated target creates a group containing exactly that target and current
source. It does not drag other catalog-linked listings into the manual group. Joining an
existing manual group moves only the selected source, not its former group. Singleton
manual groups are removed. The preview lists affected target members. Unlink restores original
grouping; one-level undo of the last association change is persisted across restart. Undo is
global to this device's association overlay and the control says so. Source-specific flags,
status and checkpoints are never merged, rekeyed, deleted or copied by these operations.

The navigation and shelf projections apply metadata expiry before applying manual groups.
Local confirmations survive catalog refresh/expiry, but cannot revive licensed artwork,
descriptions, IDs or attribution. Original library domain graphs remain provider-native;
manual IDs are presentation-only and must not be passed to episode or progress mapping.

Remaining limits: no candidate search/ranking or bulk editing, no import/export/sync of
associations, no episode equivalence/progress transfer, and only the latest edit can be undone.
Large libraries use a scrollable candidate list. Season +/- controls support 0–9999; unknown
is distinct from zero. Device D-pad focus/Back, accessibility and durable Room tests are
included in the instrumentation gate and still require an Android SDK/emulator run.

## Tests and registration

- JVM: `dev.cinematv.ui.shared.WorkNavigationTest` (six focused model tests).
- Phone instrumentation: `dev.cinematv.MobileWorkNavigationTest`.
- TV instrumentation: `dev.cinematv.TvWorkNavigationTest`.

The two instrumentation classes use the existing local fixture server and actual app roots.
Register the phone class in the phone matrix and the TV class in the TV input/focus matrix.
They cover two known seasons, two sources, unknown seasons, conflicting work IDs, stale
selection callbacks, independent progress/status/favorites and Back. TV activation uses
D-pad center with focused-control assertions. No screenshot baselines or thresholds change.

Manual association coverage: JVM `LocalWorkAssociationTest`; Android
`dev.cinematv.data.LocalWorkAssociationPersistenceTest` exercises actual Room persistence,
codec version guards, undo after reopening, evidence changes and concurrent stale writers.
