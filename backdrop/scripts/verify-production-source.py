#!/usr/bin/env python3
"""Fast local source/provenance contracts, not Android runtime validation."""
import hashlib
import json
from pathlib import Path

root = Path(__file__).resolve().parents[1]
java = root / "src/main/java/com/kyant/backdrop"
lock = json.loads((root / "upstream-lock.json").read_text())
assert len(lock["files"]) == 26
for entry in lock["files"]:
    path = root / "src/main/java" / entry["path"]
    digest = hashlib.sha256(path.read_bytes()).hexdigest()
    assert digest == entry["vendored_sha256"], f"Stale vendored hash: {path}"
    assert entry["modified"] == (digest != entry["upstream_sha256"])
    if entry["path"].endswith("/Shaders.kt"):
        assert digest == entry["upstream_sha256"], "Approved optical shader changed"

build = (root / "build.gradle.kts").read_text()
assert "minSdk = 26" in build
assert "compose-bom:2026.03.01" in build
assert 'version "2.3' not in build
assert "-Xcontext-parameters" in build
assert (root / "LICENSE").read_bytes() == (root / "src/main/assets/licenses/Apache-2.0-Backdrop.txt").read_bytes()
assert (root / "NOTICE.md").read_bytes() == (root / "src/main/assets/licenses/NOTICE-Backdrop.txt").read_bytes()

state = (java / "BackdropFailureState.kt").read_text()
assert "catch (cause: RuntimeException)" in state
assert "catch (" not in state.replace("catch (cause: RuntimeException)", "")
assert "if (failed) return null" in state
assert "cause.javaClass.simpleName" in state and "cause.message" not in state
assert "nativeCanvas.isHardwareAccelerated" in state
assert "finally {\n            canvas.restore()" in state

material = (java / "DrawBackdropModifier.kt").read_text()
assert material.count("if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S)") == 2
attach = material.split("override fun onAttach() {", 1)[1].split("override fun onDetach()", 1)[0]
assert "observeEffects()" not in attach and "updateEffects()" not in attach
assert 'attemptBackdropDraw(failureState, "material.draw")' in material
assert 'failureState.attempt("material.effects")' in material
assert 'failureState.attempt("material.attach")' in material
node_draw = material.split("override fun ContentDrawScope.draw() {", 1)[1].split("override fun onGloballyPositioned", 1)[0]
assert node_draw.count("drawContent()") == 1, "No foreground replay allowed"

for name in ("highlight/HighlightModifier.kt", "shadow/ShadowModifier.kt", "shadow/InnerShadowModifier.kt"):
    source = (java / name).read_text()
    body = source.split("override fun ContentDrawScope.draw() {", 1)[1].split("override fun onAttach()", 1)[0]
    assert body.count("drawContent()") == 1, f"Foreground replay in {name}"
    assert "attemptBackdropDraw(failureState," in body
    assert "onDrawFallback?.invoke(this)" in body
    assert "failureState.attempt(" in source
source = (java / "backdrops/LayerBackdropModifier.kt").read_text()
assert 'attemptBackdropDraw(failureState, "source.record")' in source
assert "Build.VERSION.SDK_INT < Build.VERSION_CODES.S" in source
assert "if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return" in (java / "effects/Lens.kt").read_text()
assert "Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU" in (java / "highlight/HighlightStyle.kt").read_text()
factory = (java / "backdrops/SafeLayerBackdrop.kt").read_text()
assert "import androidx.compose.ui.platform.LocalGraphicsContext" in factory
assert factory.index("Build.VERSION.SDK_INT < Build.VERSION_CODES.S") < factory.index("LocalGraphicsContext.current")
assert "remember(graphicsContext, failureState)" in factory
assert "rememberLayerBackdrop(graphicsLayer = layer, onDraw = onDraw)" in factory
assert "try {" not in factory, "Never wrap composable invocation in try/catch"
resource = (java / "RememberedBackdropResource.kt").read_text()
assert ": RememberObserver" in resource
assert "onForgotten() = releaseOnce()" in resource
assert "onAbandoned() = releaseOnce()" in resource
assert 'failureState.attempt("source.create", create)' in resource
assert resource.index("value = null") < resource.index("release(owned)")
assert 'failureState.fail("source.release", cause)' in resource
print("PASS: 26 pinned sources, unchanged shader, notices, API/failure guards, no foreground replay and safe source ownership")
