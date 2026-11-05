# RankSys on Android: isolated compatibility probe

This is **not a CinemaTV production dependency**. It evaluates the published `org.ranksys:RankSys-diversity:0.4.3` recommender implementation on minSdk26, with its real cosine-distance and MMR code. No network permission, account, licensed movie dataset or copied upstream implementation source is used. An emulator pass establishes only those API/ABI/functional checks, not low-end TV performance or recommendation quality.

RankSys is [MPL 2.0](RANKSYS-MPL-2.0.txt); original source is [RankSys/RankSys](https://github.com/RankSys/RankSys) and the versioned Maven Central source artifact. Preserve MPL notices/source availability if eventually distributed. Other resolved modules retain their licenses. CinemaTV-authored harness code is MIT. No production licensing approval is inferred from this experiment.

Build: `gradle -p experiments/ranksys-android :app:assembleDebug :app:assembleDebugAndroidTest :app:reportDependencies`

Run `:app:connectedDebugAndroidTest` on API26 and API33. Save resolved artifact sizes, APK sizes, emulator details, XML results and RankSysProbe log lines. Report runtime errors and missing APIs honestly. Timing measures synthetic 2,000 candidates to 50 outputs including feature/model setup; heap is whole-process used heap, not isolated library allocation.

Source-review caveats:
- Whole diversity module pulls fastutil, jOOλ, RankSys matrix factorization, Colt and Commons Math. Measure actual overhead.
- RankSys's MMR uses average distance and per-step z-score normalization, not the max-similarity MMR formula in CinemaTV's Kotlin baseline. A production trace must reflect its actual selection process.
- Cold start, missing features, zero-variance normalization, exclusions, identity, status feedback, provenance, reversible imports and privacy controls need application contracts.
- Java8 code or a desktop JVM pass does not prove Android viability. This workflow obtains that evidence; it is not a claim of an existing pass.
