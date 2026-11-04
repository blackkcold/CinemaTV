# Episode checkpoint identity

This change is a semantic identity upgrade, not a Room schema upgrade. Database
version 1 and its tables/columns remain unchanged. There is no destructive fallback,
SQL migration, checkpoint deletion or speculative rekeying during upgrade.

New checkpoints use source listing + explicit season (including unknown) + the first
available unique identity: provider episode ID, non-generated provider episode name,
or exact playback URL. Index-generated labels are never identity. URLs are compared
exactly, including query parameters: no generic signature/token stripping is safe.
Explicit names/IDs allow signed URL rotation and playlist reorder/insertion/removal.
URL-only rotation cannot establish identity and leaves the original checkpoint held.
Duplicate/missing evidence also stays held. Identity changes, including unknown to
known season, never imply a checkpoint transfer. Provider identity itself is trusted;
we cannot detect a provider reusing an explicit unique ID/name for different content.

Old v1 positional checkpoints remain intact. The previous implementation overwrote
source snapshots on every cache refresh without tracking which snapshot produced a
checkpoint. Even a currently unique name is therefore not evidence of historical
identity. These checkpoints cannot safely be upgraded from the saved source snapshot.
Legacy SharedPreferences history imported now may use the identity in its own saved
history record; entries lacking such evidence remain held. Original preference
backups remain untouched.

LibrarySnapshot.quarantinedProgress exposes immutable progress records and reasons:
LEGACY_POSITION_ONLY, MISSING_OR_AMBIGUOUS_IDENTITY, NOT_IN_CURRENT_PLAYLIST. Numeric
positions are not applied to a replacement episode. New checkpoint evidence is saved
once per checkpoint in library_metadata, rather than once per playlist refresh.
Existing checkpoint/source evidence is retained before overwriting a source snapshot.
clearHistory removes active and held progress and checkpoint evidence, while retaining
favorites/status and the pre-existing original legacy-import backups.

Playback/history writes derive identity and evidence strictly from their own item,
never from a merged favorite/status/cache snapshot. Late callbacks cannot replace a
newer cached playlist. Their last-watched index is mapped only by matching identity
in that current snapshot; absent/ambiguous identity leaves it unresolved.

Playlist arrays and their aligned names/IDs are replaced together. playlistKnown
separates a successful authoritative empty playlist from a metadata-only response.
Malformed/failed detail requests must not manufacture an empty known playlist.

LibrarySnapshot.works groups currently saved source snapshots into work → season →
source views. Only existing trusted work IDs group sources. Unknown seasons remain
explicit. Each source keeps its own metadata, attribution and expiry fields. Callers
must enforce those expiry fields when rendering licensed metadata. This read model
is not a historical season catalog: a provider reusing one listing for multiple
seasons still has one current snapshot, with older checkpoint evidence retained.

Verification: host JVM domain tests exercise identity, reorder, signed URL rotation,
query identity, duplicates, missing evidence, empty playlists, metadata alignment,
source/season boundaries, quarantine and provenance. Instrumentation fixtures exercise
Room persistence/reopen, retained v1 positional records and history clearing. They
require Android instrumentation; host tests alone do not validate SQLite/Room runtime.
