"""One authoritative real-key sequence for the driver, receipts and analysis."""
QUALITY = {"FULL": "ENHANCED", "BLUR": "BACKDROP", "BASIC": "BASIC"}
ORDERS = (("FULL", "BLUR", "BASIC"), ("BLUR", "BASIC", "FULL"), ("BASIC", "FULL", "BLUR"))
# Start on Home's primary hero. Focus-only navigation cannot activate a route.
# Back from Movies intentionally retains navigation focus; Down enters Home hero.
CYCLE = ((19, "HOME", "navigation-up"), (22, "HOME", "navigation-right"),
         (22, "HOME", "navigation-right"), (21, "HOME", "navigation-left"),
         (23, "MOVIES", "activate-movies"), (4, "HOME", "back-home-navigation-retained"),
         (20, "HOME", "return-to-hero"), (22, "HOME", "hero-right"), (21, "HOME", "hero-left"),
         (23, "DETAIL", "hero-open-detail"), (4, "HOME", "detail-back-home"))
WARMUP_COUNT = 2 + len(CYCLE)
MEASURED_COUNT = len(CYCLE) * 2
EXPECTED_RUNS = [(f"r{index}-{tier.lower()}", tier) for index, order in enumerate(ORDERS, start=1) for tier in order]

DEFAULT_PROFILE = "glass-controls"
POSTER_CYCLE = ((20, "HOME", "poster-down-0"),
                (22, "HOME", "poster-right-1"), (22, "HOME", "poster-right-2"),
                (22, "HOME", "poster-right-3"), (22, "HOME", "poster-right-4"),
                (21, "HOME", "poster-left-3"), (21, "HOME", "poster-left-2"),
                (23, "DETAIL", "poster-open-2"), (4, "HOME", "poster-back-2"),
                (21, "HOME", "poster-left-1"), (21, "HOME", "poster-left-0"),
                (19, "HOME", "poster-up-hero"))
PROFILES = {DEFAULT_PROFILE: CYCLE, "poster-motion": POSTER_CYCLE}
POSTER_INDICES = (0, 1, 2, 3, 4, 3, 2, None, 2, 1, 0, None)


def cycle_for(profile):
    assert profile in PROFILES, f"Unknown benchmark profile: {profile}"
    return PROFILES[profile]


def validate_profile(state, profile):
    cycle_for(profile)
    assert state.get("profile") == profile, "Missing or mismatched benchmark profile"


def poster_catalog(state, catalog=None):
    validate_profile(state, "poster-motion")
    assert state.get("movieCount") == 24 and state.get("tvShowCount") == 24, "Expected 24 movies and 24 shows"
    keys = state.get("featuredKeys", [])
    assert len(keys) == 5 and all(isinstance(k, str) and k for k in keys) and len(set(keys)) == 5, "Expected first five stable unique featured presentationKeys"
    shows = state.get("tvShowKeys", [])
    assert len(shows) == 5 and all(isinstance(k, str) and k for k in shows) and len(set(shows)) == 5, "Expected first five stable unique show presentationKeys"
    assert state.get("historyCount") == 0, "Continue history changes the native first rail"
    if catalog is not None:
        assert (keys, shows) == catalog, "Catalog identity/order changed during profile"
    return (keys, shows)


def poster_origin(state, catalog=None):
    catalog = poster_catalog(state, catalog)
    assert state["route"] == "HOME" and (state.get("bookmarkRegion"), state.get("bookmarkItem")) == ("hero", "primary"), "Poster profile must start/end at native Home hero primary"
    return catalog


def poster_step(state, offset, catalog):
    poster_catalog(state, catalog)
    offset %= len(POSTER_CYCLE)
    assert state["route"] == POSTER_CYCLE[offset][1], "Wrong poster route"
    if offset == 7:
        assert state.get("selectedKey") == catalog[0][2], "Detail selected identity must be featured[2]"
    elif offset == 11:
        poster_origin(state, catalog)
    else:
        assert (state.get("bookmarkRegion"), state.get("bookmarkItem")) == ("rail:为你精选", catalog[0][POSTER_INDICES[offset]]), "Wrong native poster rail/bookmark"
