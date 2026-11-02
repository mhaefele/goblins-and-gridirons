# AI2 — Trainer design

Locked decisions and contract rationale for the trainable model. **Tasks:** [`BACKLOG.md`](BACKLOG.md).
**Owners:** [`OWNERSHIP.md`](OWNERSHIP.md). **Phases / knobs:** [`TRAINING_ROADMAP.md`](TRAINING_ROADMAP.md).
**Action sampling / legal-row pack:** [`ACTION_SAMPLING.md`](ACTION_SAMPLING.md).

Root [`BACKLOG.md`](../BACKLOG.md) defers AI2 work to the checklist.

**Architecture pointer:** [Backend § Long-term product goals](../backend/ARCHITECTURE.md#long-term-product-goals) (incl. AI training routes).

---

## Locked decisions

| Topic | Choice |
|-------|--------|
| Training loop | HTTP against the Kotlin/Spring server. |
| Harness | Python under [`ai/`](.) — Stable-Baselines3 (Maskable PPO). |
| API | Dedicated `/api/ai/...` — obs/actions for learning, not GUI DTOs. |
| Obs wire | **Kotlin packs** (`AiObsPacker` / `…/step/packed`): `obs` float vector + legal matrix. Python **slices** into the gym Dict. Nested `…/step` for curl/tests only. Binary = [AI2.opt](BACKLOG.md#ai2opt--binary-observation-wire). |
| Python processing | Thin on facts — no invented eligibility. Gym Dict: slice packed obs; extractor embeds enums + pitch coords; policy join-scores packed legal rows. `schemaVersion` versions packing + gym + action together. |
| Submit | Policy join-scores packed legal rows → Discrete index → `LegalActionId` sidecar ([`ACTION_SAMPLING.md`](ACTION_SAMPLING.md); MultiDiscrete factor heads superseded — [AI2.9](BACKLOG.md#ai29--conditional-action-heads-follow-up)). |
| Reward | Harness: Δ score margin + terminal W/L/D from consecutive game-state / scoring delta. Profile shaping = [AI2.7](BACKLOG.md#ai27--optional-reward-shaping-day-2). Phases: [TRAINING_ROADMAP.md](TRAINING_ROADMAP.md). |
| Eval | Vs built-in computer (lookahead + heuristic) early in the cycle. |
| Teams | Single team both seats first; multi-team later ([AI2.5](BACKLOG.md#ai25--multi-team-curriculum)). |
| Product opponent | ONNX → Kotlin `OpponentStrategy` after schema freeze ([AI2.6](BACKLOG.md#ai26--onnx-export--kotlin-compute-opponent)). |

### Explicitly out

- Cloning `LookaheadScorer` as the RL reward in v1.
- Training while a `COMPUTER` seat’s `ComputerCoachAgent` also submits (race) — see [Train vs eval seats](#train-vs-eval-seats).

---

## Maskable PPO vs variable legals

SB3 needs a **fixed** action shape; the engine’s legal set changes every step. Contract pieces:

| Piece | Role |
|-------|------|
| Observation | Fixed gym Dict from packed `obs` (Python slices) |
| Action | `Discrete(MAX_LEGAL_MOVES)` row index; join-score over packed legal rows ([`ACTION_SAMPLING.md`](ACTION_SAMPLING.md)) |
| Mask | Server-aligned legality; soft preferred bias on `expectedScenarioMove` rows |
| Submit | Row index → `legalActionId` sidecar |

Design rules that still bind the API: coordinates-first factors; omit unused questions (no sentinel pad); each pending follow-up is its **own** env step; flatten nested domain on the server ([`AiObsDto`](../backend/src/main/kotlin/com/mhaefele/gridiron/api/ai/dto/AiGameStateDto.kt) / [`AiObsLayout`](../backend/src/main/kotlin/com/mhaefele/gridiron/api/ai/dto/AiObsLayout.kt)). Field-level truth is KDoc on those types — not a parallel markdown table.

Schema bumps invalidate checkpoints (and ONNX later). Prefer a version bump over speculative padding.

---

## Train vs eval seats

| Mode | Seats | Why |
|------|-------|-----|
| **Train / eval** | Policy one seat; other `COMPUTER` | Harness passes `forSeat`; server waits on handoff |
| **Vs human** | Both `HUMAN`; harness only acts for `policy_seat` | Browser plays the other seat |

Step responses expose **which seat must act** (`actingSeat`) so the harness routes the right policy instance. That is harness metadata — not required in the learning vector.

### Waiting without SSE

Harness never opens `/events`. Steady-state: one `POST /api/ai/matches/{id}/submit` per decision.

- **Expert dump:** in-process `--export-expert` (no HTTP train loop).
- **Harness** (vs computer / browser): pass `forSeat`; server waits until that seat must act.

`GET …/step?forSeat=` is the same wait for `reset()`. Packed paths are the live wire; nested JSON stays for curl/tests. Unused SSE already no-ops — [AI2.sse](BACKLOG.md#ai2sse--disable-sse-on-match-create-wont-do).

---

## Reward (v1)

Computed in the harness from game-state scores / result — no `AiRewardDto`. Phase gates and shaping presets: [TRAINING_ROADMAP.md](TRAINING_ROADMAP.md) + `reward.py`.

| Signal | Use |
|--------|-----|
| Terminal W/L/D | Primary terminal reward (`resultKind` / `resultWinnerRel`) |
| Point differential | Dense Δ`(scoreUs − scoreThem)` |
| Scenario coach-window | Small ± from `trainingOutcome` when `trainingEpisodeEnded` (not stacked on match W/L) |
| Profile shaping | `general` / `spread` / `td` / `defense` — train only; eval off |
| Marked scenario move | Soft-mask only (`preferred_legal`); drill grade also feeds curriculum/winrate |

**Schema 21+:** packed `legalMoves` = policy only (perspective). Harness `legalRows` = absolute `LegalAction` + curriculum flags. Harness `rewards.players` + `ballCoordinate` (absolute). Join by `playerId` / coords. Never use packed ints for reward.

---

## Python harness conventions

Optimize for readability and stock SB3/Gymnasium defaults:

1. Comment *why* on non-trivial paths; plain English over clever one-liners.
2. Convention over configuration — custom wrappers only when the API forces it.
3. Mark deviations: `# DEVIATION: … because …`.
4. Thin glue — HTTP + DTO → numpy → SB3; no reinvented trainers.

See [`OWNERSHIP.md`](OWNERSHIP.md) for which module owns which concern.
