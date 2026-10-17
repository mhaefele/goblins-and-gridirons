# Nick integration — starting points

External agents use **standard GUI JSON** under `/api/integration/…` — same types as the browser (`MatchSnapshotDto`, `LegalAction`, …).

---

## Try it as a human first

From the repo root:

```bash
./dev.sh
```

Opens the game UI at `http://localhost:15173` (backend on `:18080`). Play a match in the browser before wiring an agent. Ctrl+C stops both.

---

## OpenAPI spec

From the repo root (starts its own backend on `:18081`, writes `integration-openapi.json`):

```bash
./gen-integration-api.sh
```

Optional output path: `./gen-integration-api.sh path/to/out.json`

If `./dev.sh` is already running, you can browse live docs instead:

| What | URL |
|------|-----|
| **Integration routes only** | `http://localhost:18080/v3/api-docs/integration` |
| Swagger UI | `http://localhost:18080/swagger-ui.html` |

Field descriptions are on the DTOs (`@Schema`) and operations in [`IntegrationMatchController`](backend/src/main/kotlin/com/mhaefele/gridiron/api/integration/IntegrationMatchController.kt).

---

## Agent loop (vs computer)

Everything is under `/api/integration/matches`. Defaults on create (no body):

- **Roster:** random distinct teams from the catalog (see `GET /api/catalog/teams`)
- **HOME (you):** `TRAINER` — external HTTP agent via this API
- **AWAY:** `COMPUTER` with LOOKAHEAD opponent

```text
POST /api/integration/matches
POST /api/integration/matches/{id}/play     body: "<legalActionId>"
```

Repeat `play` until `snapshot.gameState.result` is set.

### `state` vs `play`

Both return the same type: [`IntegrationMatchStateDto`](backend/src/main/kotlin/com/mhaefele/gridiron/api/integration/dto/IntegrationMatchStateDto.kt). The `snapshot` is an [`IntegrationMatchSnapshotDto`](backend/src/main/kotlin/com/mhaefele/gridiron/api/integration/dto/IntegrationMatchSnapshotDto.kt) — board state plus your hidden `bonusHand` (for `agentSeat`).

| | **`GET …/state`** | **`POST …/play`** |
|---|---------------------|-------------------|
| Applies a move? | No | Yes |
| Typical use | Refresh / poll events | Main loop |

**`state`** — read the board and `legalActions` (waits until HOME must act). `events` is always empty.

**`play`** — POST one `id` from `legalActions`. Sets `chosenLegalActionId` and `events` for everything since before your pick (including computer replies while waiting).

You usually **`create` once, then `play` in a loop.**

After create you may need several `play` calls for **piece placement** before regular turns — same as the GUI.

Each `legalActions` row already has an **`id`** — that string is the `play` body.

Optional reads (once per match): `…/roster`, `…/pitch`.

### Minimal curl

```bash
BASE=http://localhost:18080

CREATE=$(curl -s -X POST "$BASE/api/integration/matches")
MATCH=$(echo "$CREATE" | jq -r .matchId)
ID=$(echo "$CREATE" | jq -r '.state.legalActions[0].id')

curl -s -X POST "$BASE/api/integration/matches/$MATCH/play" \
  -H 'Content-Type: application/json' \
  -d "\"$ID\"" | jq '{score: .snapshot.gameState, nLegals: (.legalActions | length)}'
```

(`create` returns the frame under `.state`; `play` returns it at the top level.)

---

## Offline JSON export

```bash
cd backend
mvn spring-boot:run -Dspring-boot.run.arguments="--export-integration --games=4 --out=../output/integration/games.jsonl"
```

One `IntegrationMatchStateDto` per line (includes `chosenLegalActionId` and `events`).

---

## Not this API

`/api/ai/…` is the in-repo RL trainer (packed tensors). Nick wants `/api/integration/…`.
