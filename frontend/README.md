# Goblins & Gridirons — Frontend

React + Vite client for the Goblins & Gridirons engine API. The client **draws state and submits commands**; it never re-implements rules.

Architecture: [ARCHITECTURE.md](ARCHITECTURE.md) · Tour for backend devs: [TOUR.md](TOUR.md) · Shared backlog: [BACKLOG.md](../BACKLOG.md)

## Prerequisites

- **Node** 20+ (`node -v` / `npm -v`)
- **Java 21** + **Maven** for the backend

## Running the stack

### 1. Backend

```bash
cd backend
mvn spring-boot:run
```

API listens on `http://localhost:8080`. OpenAPI docs: `http://localhost:8080/v3/api-docs`.

### 2. Frontend (dev)

```bash
cd frontend
npm install
npm run dev
```

Vite serves `http://localhost:5173` and proxies `/api` (and `/v3/api-docs`) to `:8080` — no CORS needed for local hobby use.

### 3. Regenerate API types

Backend must be running. Types are committed under `src/api/generated/` — do not hand-edit them.

```bash
cd frontend
npm run gen:api          # write openapi.ts from live /v3/api-docs
npm run gen:api:check    # fail if committed types are stale (backend must be up)
```

`gen:api:check` is a **local** drift guard (no cloud CI). It regenerates into a temp file and exits non-zero when the committed `openapi.ts` differs.

### Scripts

| Script | Purpose |
|--------|---------|
| `npm run dev` | Vite HMR on `:5173` |
| `npm run build` | Production build → `dist/` |
| `npm run preview` | Serve `dist/` (used by e2e) |
| `npm run typecheck` | `tsc -b` |
| `npm run lint` | ESLint |
| `npm run format` | Prettier |
| `npm run gen:api` | Generate types from OpenAPI (Phase 10b) |
| `npm run gen:api:check` | Local drift guard (Phase 10b) |
| `npm test` | Vitest unit/component tests (Phase 13+) |
| `npm run e2e` | Playwright full-stack e2e (real backend + Vite preview) |

Scripts marked "Phase …" become available when that work lands; they may be missing until then.

### 4. Full-stack e2e

Boots the real backend (`scripted-dice` profile) and Vite preview via Playwright `webServer`:

```bash
cd frontend
npm run e2e
```

Preview proxies `/api` to `:8080` (see `vite.config.ts` `preview.proxy`). Token art lives under `src/assets/` and resolves through `src/assets/registry.ts` — swap files there without touching render code.
