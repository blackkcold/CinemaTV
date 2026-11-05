> This document records the Kotlin baseline and original research. The stacked RankSys integration uses a real published core with explicit numerical safeguards; see [RANKSYS_INTEGRATION.md](RANKSYS_INTEGRATION.md) for the current branch's engine and verification gates. Historical baseline claims below should not be read as the active engine on that branch.

# Local recommendations: implementation, research and audit contract

Status: development baseline, not a claim that a mature third-party Android recommender SDK or trained model has been integrated. Research checked 2026-11-04. Android build/device results must be attached to the PR before promotion.

## What is implemented

`data/recommendation/` is a small, network-free Kotlin implementation of established content-based methods:

1. Use supplied genres and actors only, with typed `genre:` and `actor:` tags. Do not invent metadata from titles, infer film quality from a transport score, or treat missing metadata as dislike.
2. Normalize each work's feature vector. Build a positive Rocchio-style weighted centroid using the strongest signal for each conservatively identified work, then normalize it. Favorites have weight 4, watching 2.5, want-to-watch 1.5, watched and measured progress 1. Genre feature weight is 1 and actor feature weight 1.25. These are transparent product choices, not fitted parameters.
3. Compute cosine similarity to that local preference vector. No semantic embedding, collaborative population model, remote inference or probability of liking is implied.
4. Hard-filter not-interested/dropped works and expired metadata. Preserve strict movie/series, season, year and explicit-ID conflict boundaries. Never merge unknown identities just because titles look similar.
5. Choose unseen candidates before in-progress titles, and those before watched/at-least-70%-complete titles. Within each tier, greedily select `0.8 * cosine - 0.2 * maximum cosine to a previously selected item` (MMR). Stable ties use catalog order.
6. With no usable genre/actor evidence, preserve provider order after hard filters. An empty profile does not masquerade as personalized ML.

The old `RecommendationEngine` remains as a regression reference. The new pipeline drives the home shelves, and publishes the **same snapshot** to the dashboard. The dashboard does not independently rescore items or fabricate a graph.

References for the established methods: [Rocchio relevance feedback](https://nlp.stanford.edu/IR-book/html/htmledition/the-rocchio-algorithm-for-relevance-feedback-1.html) and [MMR, Carbonell & Goldstein 1998](https://doi.org/10.1145/290941.291025). Algorithm maturity does not establish maturity of this new implementation.

## User controls and access

The existing detail controls remain the source of truth: favorite plus independent want-to-watch, watching, watched and dropped status; the existing library filters display them. No parallel profile database is introduced.

- Phone: Settings → 本地推荐看板.
- TV: Settings → 关于 CinemaTV → 本地推荐看板. Close/Back returns focus to its opener.
- The read-only dashboard shows engine version, snapshot time, active works/candidates, excluded/expired counts, policy weights, actual normalized priority tags and actual score contributions.
- Per-item cosine terms add to the cosine value; the relevance contribution minus diversity penalty reconstructs that selection's score (subject only to floating point rounding). Seen tier is lexicographic, not an undisclosed numeric penalty. A selection score is for its selection step, not a score that can sort the whole final list again.
- At most 16 tags and 20 result explanations are displayed, explicitly labeled as a truncated view; undisplayed terms still participate. There is no export of source URLs or playback links.

## NewsBar findings

Source reviewed: [`blackkcold/news-bar` at ddd5f9ecd70635c2b982bfd248b8948553c22489](https://github.com/blackkcold/news-bar/tree/ddd5f9ecd70635c2b982bfd248b8948553c22489), MIT.

- [`RecommendationEngine.swift`](https://github.com/blackkcold/news-bar/blob/ddd5f9ecd70635c2b982bfd248b8948553c22489/Sources/NewsBar/Services/RecommendationEngine.swift) uses title tags, source weights, long/short-term local interests, bounded negative adjustments, a confidence-weighted public baseline, and deterministic diversity/source rotation.
- [`RecommendationTrace.swift`](https://github.com/blackkcold/news-bar/blob/ddd5f9ecd70635c2b982bfd248b8948553c22489/Sources/NewsBar/Models/RecommendationTrace.swift) records the actual scoring and selection pass instead of running a second explanatory model.
- [Its user guide](https://github.com/blackkcold/news-bar/blob/ddd5f9ecd70635c2b982bfd248b8948553c22489/docs/recommendations-explained.zh-CN.md) explicitly says semantic embeddings are evaluation-only; live ranking is not semantic ML. Its network diagram is display-only co-occurrence, not a causal or embedding-distance graph.

We borrow the truthful trace and explicit privacy boundaries, not a claim that the macOS implementation is Android-compatible. No NewsBar implementation source is copied.

## Existing-core research and selection limits

The user requested an established, practical Android recommendation core. This requirement remains **open** until a candidate is built and measured on Android; the Kotlin baseline is not a substitute for that evidence. A generic math/ANN library or inference runtime does not count as a complete recommender.

| Candidate | Verified evidence | Current decision |
| --- | --- | --- |
| TensorFlow recommendation example + LiteRT | [Android/TF training example](https://github.com/tensorflow/examples/tree/master/lite/examples/recommendation), Apache-2.0 source; old example pins TFLite 2.2.0 and compileSdk29. [Current LiteRT Android](https://developers.google.com/edge/litert/android) supports min23. | Closest official on-device trained-recommendation reference. Need retraining, current runtime integration and real device tests. |
| MovieLens example assets | [Example declares research use](https://github.com/tensorflow/examples/blob/master/lite/examples/recommendation/android/README.md); [MovieLens 1M terms](https://files.grouplens.org/datasets/movielens/ml-1m-README.txt) restrict redistribution and commercial/revenue-bearing use without permission. | Do not bundle the example weights/ID vocabulary as a production asset. IDs do not map directly to a dynamic CinemaTV catalog. |
| ONNX Runtime Android | MIT; mature inference runtime, not a recommender. [v1.31.1 privacy document](https://github.com/microsoft/onnxruntime/blob/v1.31.1/docs/Privacy.md) says official Android builds include 1DS telemetry enabled by default; API disable may occur after initialization. | Only useful after selecting a valid model. Strict-local integration requires pre-initialization `ORT_DISABLE_TELEMETRY=1` or a `--no_telemetry` build and verified network behavior. Not included. |
| LibRec | [3.0.0 README](https://github.com/guoguibing/librec/blob/3.0.0/README.md) GPLv3+; [core dependencies](https://github.com/guoguibing/librec/blob/3.0.0/core/pom.xml) include heavy server/native stack. | Not an MIT-only lightweight Android dependency. Not included. |
| Smile | [Current README/license](https://github.com/haifengl/smile) require Java25 for current release and GPLv3/commercial core licensing. | Kotlin bindings do not imply Android compatibility. Not included. |
| LensKit Java | [Archived/deprecated Java project](https://github.com/lenskit/lenskit); [master license](https://github.com/lenskit/lenskit/blob/master/LICENSE.md) differs from some historical licensing descriptions. | No current Android support evidence; historical artifacts require version-specific audit. Python successor is offline research, not an Android core. |
| implicit | [MIT Python/Cython/OpenMP library](https://github.com/benfred/implicit), ALS/BPR and item-item recommenders. | Useful offline training candidate with lawful multi-user data; not a direct Android AAR. One local user's history cannot create collaborative signals by itself. |
| RankSys | [Java8 recommendation framework, MPL2](https://github.com/RankSys/RankSys); published 0.4.3. The actual 0.4.3 CI resolution is core/diversity/novdiv/fast/metrics/rec + fastutil7.0.6 + jOOλ0.9.10 + Commons Math3.5. It does not resolve matrix factorization/Colt; that is a later master POM, not the tested release. Last repository push observed 2022-01-21. | Worth an isolated Android compatibility/size probe. MPL file-level obligations can coexist with a larger MIT project but must be preserved. No claim of Android certification. The class named MMR uses average distance, not this baseline's maximum redundancy, so traces cannot pretend the formulas are identical. |
| Vowpal Wabbit | [Established C++ online learning/contextual bandits](https://github.com/VowpalWabbit/vowpal_wabbit), active 2026 development. Official README directs Windows/macOS/Linux setup. | More investigation needed for supported Android JNI/build/ABIs and update safety. Requires real feedback policy and evaluation, not merely loading a library. Not included. |

Optional future semantic encoding: [BAAI/bge-small-zh-v1.5](https://huggingface.co/BAAI/bge-small-zh-v1.5) is MIT, 512-dimensional Chinese text embedding, roughly 24M parameters; the official safetensors file is about 95.8MB. It is not a movie preference model. Conversion, quantization, tokenizer/pooling parity and low-end TV resource measurements are required before adoption. Ten thousand float32 512D vectors alone cost about 20.5MB, excluding index/model/runtime overhead.

## Privacy, storage and future import provenance

All current inputs are the existing local library snapshots. Features and traces live in memory, recomputed after changes. There is no new schema, credential, HTTP endpoint, cloud profile or behavior upload. Existing Room migration/backup exclusions continue to apply. Clearing playback history keeps explicit favorites/statuses, so remaining explicit choices can still shape recommendations. These controls must never silently erase independent marks.

A future **user-confirmed** personal Douban import is not connected by this work. Do not read cookies or log in. Before enabling import, add a versioned evidence/source ledger with:

- source provider and stable external item ID, import batch ID, retrieval time and original imported status/rating;
- separate local edits and imported assertions; an explicit preview of exact matches, ambiguous matches and conflicts, followed by confirmation;
- atomic deduplication and merge using verified IDs/type/year/season boundaries, not fuzzy title matching;
- reversible batch application, with undo removing only that batch's assertions and preserving later local edits;
- ratings retaining their provider/scale/time, without treating an imported rating as an intrinsic public score;
- dashboard provenance that identifies the evidence actually used. Imported private tags/history must not be sent to metadata services or included in external logs.

No existing local rows may be relabeled as imported, and no synthetic origin data should be fabricated while the import source is unavailable.

## Bounds and verification

Default ranking limits: 2,000 candidates, 1,000 distinct positive works, 64 features per work and 200 output items (home requests 50). Truncation counts are explicit. Hard negative feedback is applied before candidate truncation. Candidate/evidence identity buckets avoid comparing each item with the whole history; final identity checks still use the existing conservative `sameWork` logic. Background `Dispatchers.Default` computation is coalesced and cancelled on newer updates; snapshots are published atomically with shelves. D-pad input stays on the main thread.

JVM tests cover exact contributions, normalization, duplicate evidence, malformed/nonfinite inputs, stable cold start, exclusions, seen tiers, MMR, capacity counters, expiry, conflicting metadata IDs and source privacy. Instrumentation exercises the real settings entry, exact shelf/snapshot match, TV initial focus, Back, close and reopen. Android build/lint and device execution must be established in CI at the final commit. Desktop JVM timing is not a low-end Android TV performance claim. No recommendation-quality lift or trained-model accuracy is claimed without evaluation.


## Published-core Android evidence (2026-11-05)

[Probe PR25](https://github.com/blackkcold/CinemaTV/pull/25) isolates the actual published RankSys 0.4.3 JARs from the production application. [Run 41939557771](https://github.com/blackkcold/CinemaTV/actions/runs/41939557771), head `e8baada88f82ce58bb1ee6f874e8f7e997cf2b1f`, executed four tests each on x86_64 Android API26 and API33, all passing. It verified cosine distance, real MMR permutations at different diversity weights, and characterized the original library's NaN fallback rather than mistaking its final ordinal scores for decision values. The merged probe manifest has no INTERNET permission.

One synthetic 2,000-candidate/50-result measurement per emulator was 152,505 µs (API26) and 246,151 µs (API33). These are emulator observations, not confidence intervals, hardware certification, recommendation-quality results, or isolated allocation measurements. Whole-process used heaps differed with runtime/GC. The unminified probe app APK was 6,987,507 bytes, not an estimated CinemaTV APK increase.

Archived 0.4.3 source JAR SHA256s: core `3a09660867d88ebbe1741dcb030e970403afe520d7da90fd4018d5ba0e65535d`, diversity `6754607fb5d501c10d04e31268a40228970bf49a43777ed2d63beebac0da86bf`, novdiv `36795aedfb0885f9f118f48fd9598be607989e5d8e5ea680490872112b68774d`. MMR, vector-distance, Stats and SimpleFeatureData source match the reviewed master; Lambda/Greedy differences are generic declarations/import syntax, not the observed normalization behavior.

The next isolated revision tests a five-JAR dependency closure, explicit zero/singleton-variance safeguards, real per-step trace and an R8-minified executable entry point. This is **RankSys plus a new CinemaTV numerical policy**, not unmodified stock behavior. It must be licensed, tested and integrated separately before replacing the production Kotlin baseline. This document does not claim the mature-core requirement is complete merely because the baseline uses established algorithms.
