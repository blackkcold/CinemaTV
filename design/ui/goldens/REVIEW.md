# Reviewed settings baseline refresh · 2026-11-04

Only `tv1080/11-settings.png` and `tv4k/11-settings.png` are refreshed. Every other golden, mask, mean-error limit (0.020) and changed-pixel limit (0.08) is unchanged.

Source: commit `5576a108ee44f15d86adc2f215567cfeb79a31fb`, [run 41887458013](https://github.com/blackkcold/CinemaTV/actions/runs/41887458013). Both TV targets passed all eight instrumentation tests, including keyboard ownership and actual Compose CJK weight. Their settings images exceeded the old visual baseline; that source run remains failed and is not retroactively relabeled as passed. Per-screen manifests record exact artifact IDs, raw dimensions, raw-image SHA-256 and normalized-image SHA-256.

Two separate visual reviews inspected both actual resolutions and the difference overlays. The approved V4 pills replace old white active/primary controls with dark layered depth, the approved Back icon replaces a text glyph, and corrected Noto fonts change glyph shapes, weight and natural text measurements. CJK/Latin hierarchy, clear focus, labels, panel bounds and sidebar spacing are intact. New 1080p and 4K geometry agrees; an approximately six-pixel normalized old 4K vertical offset reflects the changed header font measurement, with unchanged layout code. The partially visible bottom field already existed at the scroll-panel edge. No new clipping, overlap, keyboard displacement or rendering regression was found. A1 startup motion is not on this page.

Existing masks cover the ephemeral loopback port and transient bottom notification; they are not expanded. Raw CI screenshots stay untouched. Golden images use the existing comparator's 960×540 RGB/LANCZOS normalization, without masks baked into the image. The comparison report includes each screen's baseline commit/run, using manifest defaults for the five original screens and explicit provenance for settings.

A new exact-commit device run must pass these reviewed baselines and every unchanged acceptance gate before preview publication. This review does not certify physical-device performance or a real LibreTV deployment.
