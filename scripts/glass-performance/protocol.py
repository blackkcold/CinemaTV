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
