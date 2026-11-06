# Hybrid TV implementation

Approved direction: API26 installation support; local React/CSS TV presentation;
existing Kotlin services, Room and native Media3 playback. Mobile remains native.
Branch: `feature/hybrid-tv-liquid-glass`, based on `dev@553d370`.

## Delivery ledger

- [x] Update the superseded no-WebView architecture instruction.
- [x] Add browser project, bounded typed transport and D-pad spatial focus foundation.
- [ ] Complete cinematic glass TV pages and browser fixtures/tests.
- [ ] Add origin-restricted Android host and native presentation gateway.
- [ ] Wire TV routing, native input/settings, lifecycle and playback return.
- [ ] Build and execute exact-commit browser/native tests.
- [ ] Validate signed Dev artifact and publish with the existing external key.

A checked-in source is not a tested APK. Do not mark unchecked gates complete based
on implementation alone. Never change the public signing identity to bypass signing.

## Frontend workflow

Node 22.12+; `cd frontend/tv-web && npm install && npm run dev`.
`npm run build` emits local static resources. Vite targets Chromium111 explicitly.
The development-only synthetic adapter is eliminated from production. No remote UI,
CDN scripts, JavaScript media decoder or database copy is part of this architecture.

UI functions send opaque item/episode keys; raw provider credentials and playback
URLs remain native. Primary focus nodes retain fixed geometry while child artwork
animates. Native navigation owns business routes; the browser owns focus/scroll only.
