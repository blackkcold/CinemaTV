# Playback recovery identity

Automatic recovery refreshes the current source listing at most once per playback chain.
A rotated URL can retain the captured position only when the source listing, known media
type, season (explicit for TV), and unique provider episode identity all agree. Episode
identity is supplied by `MediaDomainMapper`; generated labels and ordinals are not proof.
Provider episode IDs are never treated as global IDs across providers.

An unknown/conflicting identity, unchanged URL, invalid URL, or failed refresh leaves the
failed player in place. The existing “返回换源” action takes the viewer back to detail to
choose a source and episode. Explicit playback reads that selected source's checkpoint,
starting at zero if none is safely attached. It never imports the failed source's position.
The legacy two-alternative guard remains bounded; automatic recovery no longer searches
or tries other providers. A same-source refresh does not reset either retry budget.

The fetch helper checks coroutine cancellation before/after fetching and session ownership
after fetching. Publishing the replacement is synchronous after the final ViewModel ownership
check. Navigation still cancels the job, and player lifecycle/release code is unchanged.

Host JVM coverage: `PlaybackRecoveryPolicyTest` plus `PlaybackSessionGuardTest` (30 tests).
This includes late non-cancellable responses after cancellation and replacement sessions.
Android build and TV/device validation are separate gates, not implied by these JVM tests.

Checkpoint ownership is keyed by the persisted source/season/episode ID, not playlist index.
Restarting an episode after reordering revokes its older save owner; distinct episodes remain
independent. A session with no reliable episode identity cannot persist a checkpoint.
