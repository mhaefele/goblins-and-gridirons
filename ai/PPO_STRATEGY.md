# PPO strategy (policy + learning)

**Single detailed home for how MaskablePPO decides and learns.** Curriculum phase
gates, drill mix amounts, and per-cycle cadence live in
[`TRAINING_ROADMAP.md`](TRAINING_ROADMAP.md) — this file owns the *policy
architecture* and the *learning-signal contract*, not the scenario ladder.

Related: [`DESIGN.md`](DESIGN.md) (wire locks) · [`ACTION_SAMPLING.md`](ACTION_SAMPLING.md)
(legal rows / soft-mask) · [`OWNERSHIP.md`](OWNERSHIP.md) (who edits what) ·
[`PPO_LEARNING_DEBUG.md`](PPO_LEARNING_DEBUG.md) (incident notes, not the strategy).

---

## One-sentence strategy

**Score every legal menu row given a board summary**, sample a `Discrete(1024)`
index under a hard legality mask (plus optional soft preference bias), and train
with MaskablePPO on harness rewards — soft-mask is exposure only, never the grade.

---

## Stack

| Layer | Choice |
|-------|--------|
| Loop | HTTP against Kotlin `/api/ai/...` (`scripts/train_self_play.py`) |
| Algo | SB3-Contrib **MaskablePPO** |
| Obs | Gym `Dict` sliced from Kotlin packed float vector (`schemaVersion` gates) |
| Action | `Discrete(MAX_LEGAL_MOVES)` = **1024** row index into this step’s legal menu |
| Policy class | `LegalRowActorCriticPolicy` |
| Features | `GridironFeaturesExtractor` |
| Product play | ONNX export of the same join-score path (soft-mask off) |

Python does **not** invent eligibility. Kotlin packs facts + legal rows; the net
embeds and scores.

---

## Forward pass (what the net does)

```text
Dict obs
  ├─ pitch (15,15,11) ──► per-cell emb ──► Conv2d×3 ──► GAP ──► 128-d
  ├─ benches (2,10,15) ─► per-slot emb ──► flatten
  ├─ scalars / challenges / deck / hand / held_check ─► emb + scaled floats
  └─ concat ──► Linear → ReLU → 512-d board latent
                    │
                    ├─ value head (empty pi/vf MLPs → latent_vf == features)
                    │
                    └─ for each live legal row:
                         encode_legal_rows(row) → 93-d
                         row_scorer([ latent_pi ‖ row_emb ]) → logit
                         + soft_mask_legal × SOFT_MASK_LOGIT_BIAS × soft_mask_strength
                         pad / illegal → −1e8
                    │
                    └─ MaskableCategorical over 1024 → row index
```

Wire / pack widths: `gridiron_ai/constants.py` (`CELL_CH_*`, `ACTION_FACTOR_WIDTHS`,
schema 25). Encoding: `features.py`. Deeper “engine knows vs pack exposes” inventory:
[`ACTION_SAMPLING.md`](ACTION_SAMPLING.md#legal-move-information-available-vs-exposed).

### Board trunk

- Pitch is already spatial in the gym Dict: `(C,H,W) = (15, 15, 11)`.
- Each cell is embedded (enums + pitch-axis ids + scaled stats) into an
  `_OCCUPANT_WIDTH`-channel map (**64**), then a small **Conv2d** stack
  (`64 → 64 → 128`, `k=3`, pad 1) + **global average pool**.
- Benches are **not** convolved — they are a dugout table, not pitch neighbors
  (same 15 channels per slot, emb + flatten).
- Non-pitch tensors fuse with the CNN vector through one projection Linear to
  **512** (`_PROJECTED_FEATURES`).

**Intentional non-goals (for now):**

- No source/target gather from the CNN feature map into the join (Global+Local
  micro features). Join still uses the **global** 512-d latent ⊕ independently
  encoded row factors (including categorical source/target pitch embeddings).
- No explicit intervening-lane / path features. Endpoint emb ids + global pool
  are not a lane model.

#### Per-cell / bench channels (15)

Same layout for every pitch square and every bench slot. Shared `EMBED_DIM = 8`.
Pack = 1 float/int per channel; **Feat** = size after encode (**64** total).
**Player?** = occupant-only (`SENTINEL` when empty). Square geometry + ball first;
occupant fields last (schema 25).

| Ch | Field | Player? | Definition | Encode | Vocab | Feat |
|---:|-------|:-------:|------------|--------|------:|-----:|
| 0 | terrain | no | Square type (pitch / EZ / bench / …) | emb | 7 | 8 |
| 1 | hasBall | no | Ball on this square — loose **or** held by occupant (always false on bench) | 0/1 | — | 1 |
| 2 | pitchX | no | This square’s perspective column `0..W-1` (`SENTINEL` on bench) | pitch-x emb | 12 | 8 |
| 3 | pitchY | no | This square’s perspective row `0..H-1` (`SENTINEL` on bench) | pitch-y emb | 16 | 8 |
| 4 | distToBall | no | Chebyshev steps to the on-pitch ball (`SENTINEL` if none / on bench) | `/max(W,H)` | — | 1 |
| 5 | playerIndex | **yes** | Roster id `1..10`; same id as legal-row actor when this player acts | emb | 11 | 8 |
| 6 | teamRel | **yes** | Us vs them relative to perspective seat | emb | 3 | 8 |
| 7 | status | **yes** | Standing / prone / reserves (dugout usually reserves) | emb | 4 | 8 |
| 8 | turnEnded | **yes** | This player already finished their action this team-turn | 0/1 | — | 1 |
| 9 | move | **yes** | Move allowance (1..6 or random-d6 code) | `/10` | — | 1 |
| 10 | blockDice | **yes** | Printed block-dice **stat** (not declare-time pool on a Block legal) | `/3` | — | 1 |
| 11 | throwStat | **yes** | Printed throw threshold / unthrowable code (not P(success) on a throw legal) | `/10` | — | 1 |
| 12 | armour | **yes** | Printed armour threshold / none code | `/10` | — | 1 |
| 13 | ability | **yes** | Catalog ability enum (or absent) | emb | 32 | 8 |
| 14 | canThrowTeammate | **yes** | Profile flag: allowed to declare Throw Team-mate | 0/1 | — | 1 |

Pitch map: `165 × 64` → Conv → **128**-d GAP. Benches: `2 × 10 × 64` → flatten **1280**-d
(before the shared projection with non-pitch).

Dropped from the cell pack (schema 25) — were **player-level menu summaries**, not
square facts. Nested JSON may still expose `AiPlayerAffordance` for tests.

| Was | Already on legal row? | Legal-row schema candidate? |
|-----|----------------------|-----------------------------|
| `canScoreTouchdown` | **No** — join had to bind cell flag ↔ dest | **Yes** — per-row “this legal scores a TD” |
| `mightClaimChallenge` | **No** (`challengeSlot` = which slot, not certainty) | **Yes** — POTENTIAL claim flag / certainty enum on claim rows |
| `certainClaimChallenge` | **No** | **Yes** — CERTAIN claim (or fold with might into one certainty column) |
| `canReachBallSquare` | **Partial** — `targetHasBall` when dest is the ball square | **No** — player aggregate; row already has dest ball bit |
| `canPickupBall` | **Partial** — `targetHasBall` + kind; not `BallEncounter.PICK_UP` | **Yes** — pack `ballEncounter` / PICK_UP on the row |
| reach Min/Max X/Y | **No** | **No** — bbox over *all* of a player’s legals; nonsense on one row |

#### Non-pitch obs tensors

| Tensor | Pack shape | Feat after encode | Contents |
|--------|------------|------------------:|----------|
| scalars | 17 | **24** (phase emb 8 + 16 scaled) | turn, **phase**, actionsLeft, scores, TDs, challenge cards, pointsVictoryTurns, reserves, claimsThisTurn, oppHandSize, HoldTheLine / GangUp / AllInTackle mods |
| challenges | 3×2 | **27** (3 × (cond emb 8 + pts 1)) | condition + points `/5` per slot |
| deck | 3 | **10** (cond emb 8 + 2 counts) | top condition + deckSize / discardSize `/30` |
| hand | 6 | **48** (6 × emb 8) | own bonus-play codes |
| held_check | 5 | **33** (kind 8 + d6 1 + 3× face 8) | OS / EP / Do-Over window |

### Legal-row join

- Each packed row is **15 ints** (`FACTORS_PER_LEGAL_MOVE`, schema 25).
- `encode_legal_rows` → **93-d**; reuses board emb tables (`pitch_x/y_emb`, kind,
  player index, challenge, bonus, faces).
- `row_scorer`: `Linear(512 + 93 → 512) → ReLU → Linear → 1`.
- Score only through this batch’s `legal_move_count.max()`; pad tail stays −1e8.
- SB3 `net_arch = {pi: [], vf: []}` so stock `[64,64]` MLPs do **not** crush the
  512-d trunk before the join (`POLICY_NET_ARCH`).

#### Packed legal-row factors (15)

Absent axes use `SENTINEL` (`-1`). **Pack** = live `ACTION_FACTOR_WIDTHS` (wire int
domain). **Feat** = size in the 93-d row vector (`EMBED_DIM = 8`).

| Slot | Factor | Pack | Vocab | Encode | Feat | Definition |
|-----:|--------|-----:|------:|--------|-----:|------------|
| 0 | actor | 10 | 11 | emb | 8 | Who acts — roster id matching cell `playerIndex` (`SENTINEL` if kind has no free actor) |
| 1 | kind | 41 | 41 | emb | 8 | Legal verb (`run` / `throw` / `block` / claim / …) |
| 2 | sourceX | 11 | 12 | pitch-x emb | 8 | Actor’s current square x (not who-id) |
| 3 | sourceY | 15 | 16 | pitch-y emb | 8 | Actor’s current square y |
| 4 | targetX | 11 | 12 | pitch-x emb | 8 | Destination column (path end / throw / block target); `SENTINEL` if unused |
| 5 | targetY | 15 | 16 | pitch-y emb | 8 | Destination row |
| 6 | actorHasBall | 2 | — | 0/1 | 1 | Actor holds the ball right now (also on their cell `hasBall`) |
| 7 | targetHasBall | 2 | — | 0/1 | 1 | Dest square has ball (loose or carrier) — LBR pickup / bounce cue |
| 8 | throwSuccessChancePercent | 101 | — | `/100` | 1 | Throw P(success)×100; `0` = non-throw (not cell `throwStat`) |
| 9 | blockDiceCount | 3 | — | `/3` | 1 | Declare-time Block dice pool `1..3`; `SENTINEL` / 0 feat if non-block |
| 10 | challengeSlot | 3 | 16† | gather → cond emb + pts | 9 | Which active challenge this claim/replace targets (`SENTINEL` if not a claim row); † cond vocab |
| 11 | attachedBonus | 20 | 21 | emb | 8 | Bonus card spent on this twin / Play* / Do-Over (`SENTINEL` if none) |
| 12 | face0 | 5 | 6 | emb | 8 | Enforcer face-order slot 0 (`SENTINEL` unused) |
| 13 | face1 | 5 | 6 | emb | 8 | Face-order slot 1 |
| 14 | face2 | 5 | 6 | emb | 8 | Face-order slot 2 |

Parallel (not pack columns): `soft_mask_legal` / `passing_legal` — length
`MAX_LEGAL_MOVES` (1024) int8 flags; sampling / grade sidecars, not join features.

#### What is *not* on the row (promote / schema candidates)

Full inventory: [`ACTION_SAMPLING.md`](ACTION_SAMPLING.md#legal-move-information-available-vs-exposed).
After schema 25, cell menu-summaries are gone — prefer **row** bits for choice-level facts.

| Signal | Today | Candidate? |
|--------|-------|:----------:|
| Soft-mask preferred row | Logit bias only | sampling lever (not a pack column) |
| Passing / PASS-grade flag | Eval / reward sidecar | optional if soft-mask ≠ grade |
| This legal scores a TD | Missing (was cell summary) | **yes** |
| Claim certainty (potential / certain) | Missing (`challengeSlot` only) | **yes** |
| `BallEncounter.PICK_UP` | Missing (`targetHasBall` only) | **yes** |
| Full path / intervening lane | Missing | later / separate |
| Available bonus prompt set | Hand tensor | no (not per-row) |
| Reach bbox | Dropped | **no** |

**Promote ≠ pack more by default.** Soft-mask tilts sampling; reward grades. New row
bits when the join cannot bind board facts to the menu row (measure first).

### Three separate “mask” ideas (do not merge)

| Mechanism | Job |
|-----------|-----|
| **Hard legality mask** | Only indices `0 .. legal_move_count-1` are available |
| **Soft-mask bias** | Preferred rows get a logit tilt (`look at / sample these`) — **not** a reward |
| **Reward / curriculum ±** | Grades what happened after the look |

Full soft-mask contract: [`ACTION_SAMPLING.md`](ACTION_SAMPLING.md#soft-mask-preference).

---

## Learning signal

| Signal | Role |
|--------|------|
| Δ score margin | Dense step reward from consecutive game-state scores |
| Terminal W/L/D | Primary match outcome |
| Scenario PASS / THEM window ± | Curriculum coach-window edges (`reward.py`) |
| Profile shaping | `general` / `spread` / `td` / `defense` — train only; eval off |
| Soft-mask | Sampling tilt only |

Reward ownership and formulas: `gridiron_ai/reward.py` +
[`TRAINING_ROADMAP.md`](TRAINING_ROADMAP.md). Packed legal-row ints are **never**
business logic for reward (schema 21+ harness snapshots).

### VecNormalize

- **Scenario train:** reward normalize **off** — sparse drill ± must stay loud.
- **Full-game train:** reward normalize **on** — long episodes need calm scale.
- Obs are not normalized either way.

Rationale / failure modes when normalize crushed drills:
[`PPO_LEARNING_DEBUG.md`](PPO_LEARNING_DEBUG.md).

---

## Hyperparameters (MaskablePPO)

Standing table vs SB3 stock: [`TRAINING_ROADMAP.md` § PPO vs SB3 stock](TRAINING_ROADMAP.md#ppo-vs-sb3-stock).

Summary of intentional deviations:

| Knob | Ours | Why |
|------|------|-----|
| `learning_rate` | `1e-3` | Opening mass stayed flat at stock `3e-4` |
| `ent_coef` | `0.005` | Large Discrete peaks fast at 0 |
| `net_arch` | empty pi/vf | Keep 512-d board summary into the join |
| board / join hidden | `512` / `512` | Trunk and scorer track each other |
| `n_steps` × `n_envs` | `128` × `30` | Parallel HTTP envs |
| `batch_size` | `256` | Matches multi-env chunks |
| `device` | `cpu` | Harness default |

Create **and** resume paths re-apply LR / ent / vf / stability knobs so old zips
cannot silently keep factor-head-era schedules.

---

## Bootstrap and artifacts

1. **Expert dump** (Kotlin Lookahead labels) → pack NPZ → **clone** into
   `maskable_ppo_seed.zip` (supervised into the same policy class).
2. Scenario / profile phases **fork** weights from the prior stage (never copy the
   expert seed onto shaped profiles).
3. Architecture or gym-space changes **invalidate** SB3 zips and ONNX exports —
   re-clone / retrain / re-export. A pitch-CNN trunk change does **not** by itself
   bump `schemaVersion` (wire + Dict shapes unchanged); weights are still incompatible.

ONNX: `export_onnx.py` mirrors extract → join → masked argmax (soft-mask omitted
for product seats).

---

## Explicitly rejected / superseded

| Idea | Status |
|------|--------|
| MultiDiscrete factor heads + remask | Superseded (schema 16 join-score) |
| Hard-removing distractors from the legal set | Rejected — broke transfer |
| Soft-mask as a grade / “preferred = correct” | Forbidden (exposure only) |
| Flatten pitch `(C,H,W)` hygiene (AI2.23) while convolving | Conflicts with this trunk — parked |
| Cloning Lookahead as the RL reward | Out for v1 (`DESIGN.md`) |

---

## Next ablation (not landed)

**Global + Local join:** keep the CNN map before GAP; gather feature vectors at
each row’s `sourceX/Y` and `targetX/Y`; concat
`[ global ‖ src ‖ tgt ‖ row_flags/embs ]` into `row_scorer`.

That still does not model intervening lanes unless path/ray features are added
separately. Measure trunk-only CNN against BlindSpotArena / drill openings before
spending on gather.
