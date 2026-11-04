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

## Remaining limitation: user-confirmed mapping (not implemented)

Many LibreTV providers supply no trustworthy external work ID or season number. Those
listings intentionally remain separate works or unknown seasons. A graph cannot establish
missing identity. The next feature should be an explicit local mapping editor:

1. Offer “associate with a work” from a source detail page, showing provider, listing ID,
   title, year, media type, and existing external IDs side by side. Title similarity may
   propose candidates but must not confirm a match.
2. Require a user-confirmed target work and optional season number. “Unknown” remains an
   explicit choice; specials/season zero should be supported. Conflicting trusted IDs need
   a distinct warning and must not silently overwrite provider metadata.
3. Store a versioned, reversible local association overlay with original source entry IDs,
   creation/update provenance and explicit user-confirmed origin. Keep external IDs intact.
   Show “manually associated” rather than implying catalog verification.
4. Preview affected sources/seasons before confirming; provide unlink and undo. Unlinking
   must restore original grouping without deleting source flags or history.
5. Keep source entry and episode identities unchanged. No checkpoint transfer, even with
   the same episode number. Manual episode equivalence and progress copying would need a
   separate explicit policy and confirmation, not a side effect of grouping.
6. Add migration, expiry, conflicting-ID, undo, accessibility and multi-device conflict tests
   before exposing the editor. The overlay should survive catalog refresh without reviving
   expired descriptions, artwork or licensed attribution.

## Tests and registration

- JVM: `dev.cinematv.ui.shared.WorkNavigationTest` (six focused model tests).
- Phone instrumentation: `dev.cinematv.MobileWorkNavigationTest`.
- TV instrumentation: `dev.cinematv.TvWorkNavigationTest`.

The two instrumentation classes use the existing local fixture server and actual app roots.
Register the phone class in the phone matrix and the TV class in the TV input/focus matrix.
They cover two known seasons, two sources, unknown seasons, conflicting work IDs, stale
selection callbacks, independent progress/status/favorites and Back. TV activation uses
D-pad center with focused-control assertions. No screenshot baselines or thresholds change.
