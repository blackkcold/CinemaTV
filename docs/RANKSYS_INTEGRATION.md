# Local recommendations: published RankSys integration

This change stacks on the local-feedback and dashboard PR24. It does not merge or publish a release by itself. PR25 contains the separate Android compatibility experiment. The production path now requests a real, published RankSys 0.4.3 backend from the ViewModel; the Kotlin baseline remains an explicit fallback.

## What the existing core actually does

CinemaTV adapts existing local Room favorites, WANT_TO_WATCH, WATCHING, WATCHED and measured progress into a weighted, normalized tag centroid. These manually selected product weights are not learned coefficients or a trained model. Identity grouping, negative exclusions, expiry checks and viewing tiers remain application policy. There is no collaborative user database, remote profile upload, embedding download or claimed ML training.

The backend invokes the original RankSys `SimpleFeatureData`, `CosineFeatureItemDistanceModel` and `MMR` classes from Maven Central. RankSys computes profile/candidate cosine scores and greedy average-distance reranking. The actual core cosine is checked against the sparse contribution terms shown in the dashboard. The core is not merely present as an unused dependency.

RankSys normalizes relevance and novelty to z-scores at each step. Its lambda is the diversity weight. Its MMR uses average distance to selected items, not the baseline's maximum redundancy. The independent CinemaTV subclass changes zero/tiny-variance and singleton dimensions to zero, bounds/validates inputs and records actual value/update decisions. It must be described as **RankSys 0.4.3 plus CinemaTV numerical safeguards**, not unmodified stock behavior. Guards do not replace the library's selection or average-distance updates.

Each viewing tier is ranked separately. Missing-feature candidates cannot acquire fake novelty: they are appended within their tier as labeled catalog-order fillers. Cold start preserves provider order and creates no fake profile. A runtime backend failure discards all partial output, uses the Kotlin baseline and exposes a fallback message. Cancellation escapes without publishing a result. Fatal JVM errors are not swallowed.

## Bounds, dependency footprint and privacy

The application requests at most 2,000 candidates, 1,000 positive works, 64 features per work and 50 results. Work remains on a cancellable background dispatcher. It does not run in Compose or the player thread.

The five original runtime JARs are RankSys core/novdiv/diversity 0.4.3, fastutil 7.0.6 and jOOL 0.9.10, with transitive resolution disabled. These components introduce no JNI ABI, account, service or network transport. Other CinemaTV functionality still uses its existing network permissions; local ranking does not claim that the entire app is offline. The normal app release shrinking setting is intentionally unchanged. Probe R8 APK sizes are not a prediction of the main APK delta.

## License/source delivery

RankSys is MPL-2.0. fastutil and jOOL are Apache-2.0, with additional fastutil CERN attribution retained. CinemaTV's independently written adapter remains MIT. Full license texts, exact version attribution and three SHA256-pinned original RankSys source JARs are included under `assets/recommendation-third-party/` in the APK. The dashboard's license dialog reads these locally and can export a ZIP through Android's system document picker. The export contains only public library sources/notices, never preference vectors, titles, playback URLs or local history. No external account or storage permission is requested by the app.

The source configuration is non-transitive and checks the exact three filenames plus pinned bytes, so it cannot accidentally bundle runtime JARs as sources. The system file picker may be absent on a TV, in which case the UI explains how to extract the same sources from the APK archive. This is engineering license/source delivery, not a claim of formal legal review of every upstream source file.

## Verification record and gates

- Original published implementation: PR25 run 41939557771, four tests each on API26/API33 passed, including real permutations and characterization of the stock NaN fallback.
- Five-JAR guarded path: PR25 run 41940823814, ten Android tests each passed. The actual R8-minified entry point passed on API33; API26's post-test log-clear step failed before launch. A fresh-nonce launch check replaces that fragile step in later runs. Do not report the failed job as a pass.
- This integration's 35 local JVM tests passed, including same-work conflicts, actual guarded reranking, missing-feature fallback, viewing tiers, exclusions, exact trace arithmetic and cancellation. The local MMR compilation used the matching published source archive alongside the actual other JARs; it is not Android evidence.
- Integration Android compile/unit/lint, phone/TV dashboard tests with real favorite feedback, licensed-source packaging, and exact resulting APK verification remain required after each committed revision. Main merge/release waits for the existing v0.6 promotion and coordinated acceptance.

Future personal Douban imports remain separate and unconnected. Any future import must preserve source identifiers/timestamps, preview identity matches, ask the user to confirm a merge, deduplicate feedback and support undo. No cookies or account credentials are read by this feature.
