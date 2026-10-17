# Frontend tour for backend developers

A guided walkthrough of the Goblins & Gridirons React client for someone comfortable with Java/Kotlin and Spring, but less so with modern frontend tooling. Not an API reference — see [ARCHITECTURE.md](ARCHITECTURE.md) for contracts and [README.md](README.md) for commands.

## Mental-model cheat sheet

| Frontend | JVM analogy |
|----------|-------------|
| **Vite** | Dev server + bundler ≈ Spring Boot DevTools + `mvn package`. HMR reloads changed modules without a full restart. |
| **`package.json` / npm** | ≈ `pom.xml` + Maven goals. `dependencies` are runtime; `devDependencies` are build/test; `scripts` are named goals. |
| **TypeScript `strict`** | ≈ Kotlin null-safety. `noUncheckedIndexedAccess` is like treating array access as nullable. |
| **Discriminated unions on `kind`** | ≈ sealed classes + `when`. Exhaustive `switch` with a `never` fallthrough is the compile-time alarm when the backend adds a subtype. |
| **React components + JSX** | Declarative UI tree. Re-render when props/state change — you describe *what* to show, not *how* to mutate the DOM. |
| **Zustand store** | Thin holder of app state (board view + connection). The pure `applyEvent` fold is the interesting part — same idea as folding domain events on the server. |
| **`EventSource` (SSE)** | The same `GET .../events` stream the backend already emits. Browser API; reconnect with `Last-Event-ID`. |
| **Vitest / Playwright** | ≈ JUnit pure-domain tests / black-box API scenario tests. Prefer asserting what the user/server sees. |
| **OpenAPI type-sync** | Generated TS types from `/v3/api-docs`. Never hand-copy Kotlin DTOs — regenerate when the wire contract changes. |

## Where things live

```text
frontend/src/
├── app/           Screens: create/join → play → game-over
├── api/           Thin fetch + EventSource; generated/ is openapi-typescript output
├── state/         BoardView + pure applyEvent + Zustand store (Phases 12–13)
├── render/        SVG pitch, tokens, HUD, panels (Phase 11+)
├── assets/        Art registry + files (Phase 14)
└── test/          Fixtures and Vitest setup
```

## How a click becomes a command becomes an event

1. **Create** — UI reads catalog (`GET /api/catalog/teams|pitches`), posts `POST /api/matches`, navigates to play with `matchId` + opening snapshot.
2. **Hydrate** — Play view loads pitch + roster (static) and snapshot (mutable), then opens SSE on `/api/matches/{id}/events`.
3. **Opponent / any coach acts** — each SSE frame is a `DomainEvent`; `applyEvent(view, event)` patches the board; React re-renders.
4. **Your turn** — `playerActionRequired` (hotseat: when phase is player action for the active coach) → fetch `legal-actions` → highlight → click → `POST .../players/{id}/{verb}` → fold response events / reconcile snapshot.
5. **Game over** — HUD / result from game state; offer a new match.

Token art resolves through `src/assets/registry.ts` (team + position + status keys). The event log is an `aria-live` region fed from streamed `DomainEvent`s.

The client never decides legality. If something looks wrong, check the backend events and `legal-actions` first.

## Growing this doc

Updated at the end of each frontend batch as Phases 10b–15 land (API client, rendering, interaction, SSE, assets, e2e).
