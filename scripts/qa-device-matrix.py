#!/usr/bin/env python3
"""Select diagnostic targets without weakening the default acceptance matrix."""
import json
import sys

DEVICES = [
    {"name": "phone", "target": "google_apis", "api": 30, "size": "1080x1920", "density": 420,
     "classes": "dev.cinematv.MobileNavigationSmokeTest,dev.cinematv.TvLaunchSmokeTest,dev.cinematv.MobilePlaybackSmokeTest,dev.cinematv.data.LocalMediaLibraryTest,dev.cinematv.data.RepositoryContractTest,dev.cinematv.MobileGlassButtonTest,dev.cinematv.CinemaBrandStartupTest,dev.cinematv.ui.typography.NotoFontsDeviceTest"},
    {"name": "tv1080", "target": "default", "api": 33, "size": "1920x1080", "density": 320,
     "classes": "dev.cinematv.TvUiRegressionTest,dev.cinematv.GlassMaterialSmokeTest,dev.cinematv.ui.typography.NotoFontsDeviceTest"},
    {"name": "tv4k", "target": "default", "api": 33, "size": "3840x2160", "density": 640,
     "classes": "dev.cinematv.TvUiRegressionTest,dev.cinematv.GlassMaterialSmokeTest,dev.cinematv.ui.typography.NotoFontsDeviceTest"},
    # Minimum-supported Android: offline fonts, glass fallback and brand motion,
    # without duplicating the full navigation/player matrix or skipping AGSL.
    {"name": "compat26", "target": "google_apis", "api": 26, "size": "1920x1080", "density": 320,
     "classes": "dev.cinematv.ui.typography.NotoFontsDeviceTest,dev.cinematv.GlassMaterialSmokeTest#materialSurvivesFocusPressReducedMotionMissingArtAndFailure,dev.cinematv.CinemaBrandStartupTest"},
]


def select(target):
    if target == "all":
        return {"include": DEVICES}
    devices = [device for device in DEVICES if device["name"] == target]
    if not devices:
        raise ValueError(f"Unknown QA target: {target!r}; expected all, phone, tv1080, tv4k or compat26")
    return {"include": devices}


if __name__ == "__main__":
    try:
        if len(sys.argv) != 2:
            raise ValueError("Usage: qa-device-matrix.py TARGET")
        print(json.dumps(select(sys.argv[1]), separators=(",", ":")))
    except ValueError as error:
        sys.exit(str(error))
