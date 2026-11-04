# CinemaTV A1 polished identity

The approved original A1 pack is the source of truth. Do not substitute the later rainbow treatment or UI typography for its custom outlined wordmark. The separate V3 UI icon set is unchanged.

Provenance: `CinemaTV-A1-Polished-Pack.zip`, SHA-256 `6c590345640aff1749be5147299ee378c733f1731cb8665d94b1f8dc6e4f6934`. Approved 2026-11-04.

- `cinematv-app-icon.svg`: original 1024 × 1024 square.
- `cinematv-player-mark.svg`: original transparent player symbol.
- `cinematv-lockup.svg`: original custom symbol and wordmark outlines, no font dependency.
- `cinematv-tv-banner.svg`: original centered 1920 × 1080 banner. The committed 320 × 180 PNG is the approved pack's export.
- `cinematv-background.svg`: square with only its centered logo removed, preserving the subtle cool/warm horizontal lens streaks.

Android uses a static 320 × 180 TV launcher banner and a two-layer adaptive phone/TV icon. The foreground preserves the square's path and center (`translate(512 512) scale(2.65) translate(-132.5 -128)`), uniformly reducing the square's original 2.9 scale to 2.65 so the entire stroke is safely inside the 66/108 adaptive safe circle. The background is rasterized because Android VectorDrawable does not support SVG Gaussian blur. A 512px legacy export is retained for consumers needing a static square. Both launcher entry points remain enabled; minSdk is 26, so adaptive mipmap resources apply to every supported device.

The in-app `CinemaBrandLockup` uses native VectorDrawable paths with transparent outer margins trimmed. It has an accessible app-name label, no focus target, no tint, and no dependency on the selected app font. Static headers remain available. `CinemaBrandStartup` retains the original horizontal motion language inside the existing TV/phone header brand area: fixed logo, three rightward preblurred layers, original CSS ease-in-out and 12s/9s/6s rates, stopping after the first six-second sweep. Reduced-motion, system animator-off, low-RAM, or explicit low-cost mode uses a static presentation. It never captures focus, intercepts input, or gates access. Three small 640 × 360 transparent layers total roughly 56 KiB; no runtime blur, video decoder, infinite animation, MP4, or HTML payload is bundled. `CinemaBrandSession` is hoisted in MainActivity above navigation, so leaving/re-entering a page cannot restart it. `rememberSaveable` prevents replay on configuration recreation after completion. No overlay covers settings or content, and all existing control widths/heights/focus handlers are unchanged. No animation is installed into launchers.

To rebuild the three motion layers, run `python scripts/branding/render-motion-layers.py` (Inkscape or librsvg). The extractor retains only the original named trail group and its definitions.

To re-render static raster resources, use `bash scripts/render-branding.sh /tmp/cinematv-brand-candidates` with librsvg installed. The output directory is required and cannot be inside Android resources. SVG filter renderer differences may slightly affect streak softness; review rendered pixels before replacing the approved banner export. Do not flatten the wordmark to platform text.

Checks: `python -m unittest discover -s scripts/tests -p 'test_brand_resources.py'`. After Android build, check masked icons on round/squircle launchers, TV banner centering at 320 × 180, TalkBack label, TV header/phone header fit, and unchanged navigation focus and Back behavior. Source tests do not replace device QA.


## Reviewed bytes and CI

`approved-raster-sha256.json` pins the six reviewed runtime PNGs. The banner is the exact approved pack export; the square/background and three motion layers are documented integration derivatives. A renderer change can alter subtle Gaussian blur, fringe brightness, or antialiasing even when geometry is unchanged. A regenerated banner must not be described as the exact pack export.

The branding workflow is validation-only on pull requests, main pushes, and manual runs: read-only checkout, resource contracts, approved hashes, and clean-worktree check. It cannot render, commit, or push new assets. In particular, automatic bot regeneration previously replaced three reviewed PNGs after merge; those renderer outputs are not the approved originals. Any deliberate replacement requires pixel review, an explicit manifest update, and the normal Android build/test PR gate. Never update hashes just to make a drift failure green.
