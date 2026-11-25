# Native system playback

TV and phone both use `NativePlaybackSession`, which owns one ExoPlayer and one
Media3 MediaSession for the current source/episode. Existing checkpoint identity,
VOD cache and source-recovery ownership remain in the repository/ViewModel.

- ExoPlayer requests and abandons audio focus using media/movie audio attributes.
  Its noisy-output handling pauses audio when the output disconnects.
- The same foreground-aware Player is passed to PlayerView, Compose controls and
  MediaSession. Trusted controllers can control playback/seek/track/speed, but
  cannot replace the selected media or inject a playlist. Other controllers get
  read-only metadata. The session advertises a title and an opaque playback ID.
- Leaving the screen's started lifecycle pauses playback. Returning resumes only
  playback which was active and unsuppressed before leaving. User pause, audio
  focus interruption, output disconnection, and off-screen controller commands
  cancel pending resume. Off-screen Play cannot start hidden video.
- The noisy-output receiver also cancels pending resume while the screen is
  already stopped. The app does not independently request audio focus.
- TV consumes complete media-key presses once. Play/pause toggles the requested
  state, so buffering or transient focus suppression does not make Pause become
  another Play. Dedicated Play, Pause, Stop and ten-second seek keys are handled.
- Screen disposal releases the system session before the Player, unregisters the
  output receiver, and preserves the final checkpoint. Per-instance session IDs
  allow Compose to create a replacement while disposing its predecessor.
- A verified refreshed playback URL inherits the latest pause/output-suppression
  intent at the point of replacement. Pausing during link recovery cannot start
  the replacement automatically; an explicit episode selection can start again.

## Verification

`ForegroundPlaybackPolicyTest` covers resume intent and late lifecycle/commands.
`NativePlaybackSessionTest` uses the real Activity lifecycle, MediaController,
ExoPlayer and audio-focus manager with offline synthetic video. Its output-loss
case invokes the exact receiver handler: it is not a physical headphone test.
Phone playback/rotation and TV player-return suites exercise the actual UI owners.
Actual Bluetooth/HDMI/headset routes and vendor TV media keys still require device
acceptance; no hardware or long-play performance is inferred from these tests.

## Official implementation references

- [Media3 sessions](https://developer.android.com/media/media3/session/control-playback)
- [ExoPlayer audio focus](https://developer.android.com/media/optimize/audio-focus)
- [ExoPlayer builder](https://developer.android.com/reference/androidx/media3/exoplayer/ExoPlayer.Builder)
- [Player forwarding and state consistency](https://developer.android.com/reference/androidx/media3/common/ForwardingSimpleBasePlayer)
