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

**Score every legal menu row against the full board (flattened pitch map +
non-pitch) and the full row pack**, sample a `Discrete(1024)` index under a hard
legality mask (optional soft preference bias), train with MaskablePPO on harness
rewards — soft-mask is exposure only, never the grade.

---

## Stock framework vs our deviations

### Riding the rails (standard SB3 / sb3-contrib)

| Piece | Stock behavior |
|-------|----------------|
| Algo | `MaskablePPO` + `MaskableActorCriticPolicy` |
| Obs | Gym `Dict`; `extract_features(obs)` → one `features_dim` vector |
| Value | `mlp_extractor` + `value_net` on that vector |
| Action space | `Discrete(n)` + `MaskableCategoricalDistribution` (hard mask) |
| Train loop | collect rollouts → GAE → `evaluate_actions` → PPO update |

### Deviations (why we left the rails)

| Deviation | Why |
|-----------|-----|
| Custom `GridironFeaturesExtractor` instead of `CombinedExtractor` | Stock would Flatten each Box; we embed cells + Conv2d pitch |
| `forward` returns value features **and** stashes join board | SB3 extractor API is one tensor; join needs the unflattened pitch map |
| Custom `LegalRowActorCriticPolicy` — ignore stock `action_net` | Stock is `Linear(latent → n_actions)`; we score **each legal row** with board‖row |
| Join = `flatten(pitch CNN map) ‖ non_pitch ‖ row_emb` | Expose full board + full move — **no** GAP / attention / gather on the policy path |
| Soft-mask logit bias after scores | Curriculum sampling tilt; not in the feature vector |
| ONNX wrapper mirrors join + masked argmax | Product seats; soft-mask omitted |

### Do not confuse value GAP with the policy join

| Path | Pitch after CNN `(N,128,15,11)` | Role |
|------|--------------------------------|------|
| **Policy** | **Flatten** → 21120-d ‖ non-pitch ‖ `row_emb` → `row_scorer` | Every square’s features stay present next to the move |
| **Value** | **GAP** → 128-d ‖ non-pitch → Linear → 512 | SB3 critic contract only |

Readers / external reviews that say “GAP erases space before the join” are describing
a **rejected** design (or the **value** trunk), not the live policy. Live cost of the
policy join is the fat first Linear on the flatten — see below — not spatial erasure.

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
  pitch (15,15,11) → emb → Conv2d×3 → map (128,15,11)
  benches / scalars / challenges / deck / hand / held → emb = non_pitch (1422)

  VALUE (SB3 rail only — not the policy join):
    GAP(map) → 128 ‖ non_pitch → Linear → 512 → value_net

  POLICY (per live legal row):
    encode_legal_rows(row) → 93-d
    row_scorer([ flatten(map)=21120 ‖ non_pitch ‖ row_emb ]) → logit
      (+ soft-mask bias; pad / illegal → −1e8)
    → MaskableCategorical → row index
```

Wire / pack widths: `gridiron_ai/model/constants.py` (`CELL_CH_*`, `ACTION_FACTOR_WIDTHS`,
schema 25). Encoding: `features.py`. Deeper “engine knows vs pack exposes” inventory:
[`ACTION_SAMPLING.md`](ACTION_SAMPLING.md#legal-move-information-available-vs-exposed).

### Board encoding

- Pitch gym shape: `(C,H,W) = (15, 15, 11)`.
- Per-cell emb → `_OCCUPANT_WIDTH` (**64**) → Conv2d `64→64→128` → map
  **(N, 128, 15, 11)**.
- **Policy join:** flatten that map (**21120**) ‖ non-pitch (**1422**) ‖ full
  `row_emb` (**93**) → `row_scorer` input **22635**. First Linear
  `22635 → 512` ≈ **11.6M** weights — the real flatten cost (CPU-fine at our
  batch sizes; not “melts the laptop,” but sample-inefficient vs a thinner join).
- **Value / SB3 features:** GAP map → 128 ‖ non-pitch → Linear → **512**.
- Benches are a table (not convolved).

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

Pitch map: `165 × 64` → Conv → **(128, 15, 11)** — join flattens (**21120**); value GAPs to
**128**. Benches: `2 × 10 × 64` → flatten **1280**-d (into non-pitch / value trunk).

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
- `row_scorer`: `Linear(22635 → 512) → ReLU → Linear → 1`
  (`flatten(pitch) ‖ non_pitch ‖ row_emb`). ≈11.6M in the first Linear.
  Invalidates older GAP/attn-join policy zips.
- Score only through this batch’s `legal_move_count.max()`; pad tail stays −1e8.
- SB3 `net_arch = {pi: [], vf: []}` so stock `[64,64]` MLPs do **not** crush the
  512-d **value** features (`POLICY_NET_ARCH`).

#### Packed legal-row factors (15)

Absent axes use `SENTINEL` (`-1`). **Pack** = live `ACTION_FACTOR_WIDTHS` (wire int
domain). **Feat** = size in the 93-d row vector (`EMBED_DIM = 8`).

| Slot | Factor | Pack | Vocab | Encode | Feat | Definition |
|-----:|--------|-----:|------:|--------|-----:|------------|
| 0 | kind | 41 | 41 | emb | 8 | Legal verb (`run` / `throw` / `block` / claim / …) |
| 1 | actorHasBall | 2 | — | 0/1 | 1 | Actor holds the ball (cell `hasBall` on source) |
| 2 | sourceX | 11 | 12 | pitch-x emb | 8 | Actor’s current square x (not who-id) |
| 3 | sourceY | 15 | 16 | pitch-y emb | 8 | Actor’s current square y |
| 4 | actor | 10 | 11 | emb | 8 | Roster id matching cell `playerIndex` (`SENTINEL` if unused) |
| 5 | targetHasBall | 2 | — | 0/1 | 1 | Dest has ball (loose or carrier) — LBR pickup cue |
| 6 | targetX | 11 | 12 | pitch-x emb | 8 | Destination column; `SENTINEL` if unused |
| 7 | targetY | 15 | 16 | pitch-y emb | 8 | Destination row |
| 8 | blockDiceCount | 3 | — | `/3` | 1 | Declare-time Block dice pool `1..3`; `SENTINEL` / 0 feat if non-block |
| 9 | throwSuccessChancePercent | 101 | — | `/100` | 1 | Throw P(success)×100; `0` = non-throw (not cell `throwStat`) |
| 10 | challengeSlot | 3 | 16† | gather → cond emb + pts | 9 | Claim/replace slot (`SENTINEL` if not); † cond vocab |
| 11 | attachedBonus | 20 | 21 | emb | 8 | Bonus card on twin / Play* / Do-Over (`SENTINEL` if none) |
| 12 | face0 | 5 | 6 | emb | 8 | Enforcer face-order slot 0 (`SENTINEL` unused) |
| 13 | face1 | 5 | 6 | emb | 8 | Face-order slot 1 |
| 14 | face2 | 5 | 6 | emb | 8 | Face-order slot 2 |

Source block order matches cell `hasBall → pitchX/Y → playerIndex`; outcomes match
`blockDice` then `throwStat`.

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

Reward ownership and formulas: `gridiron_ai/model/reward.py` +
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
| `net_arch` | empty pi/vf | Keep 512-d value features (stock `[64,64]` would crush) |
| value / join hidden | `512` / `512` | Value trunk width; `row_scorer` hidden |
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

## What has not worked (and why)

Architecture and learning bets we tried or briefly believed — kept here so we do
not re-litigate them. Incident detail for stuck openings:
[`PPO_LEARNING_DEBUG.md`](PPO_LEARNING_DEBUG.md). Soft-mask contract:
[`ACTION_SAMPLING.md`](ACTION_SAMPLING.md#soft-mask-preference).

### Action / sampling shape

| Attempt | Why it failed / was dropped |
|---------|-----------------------------|
| **Independent MultiDiscrete factor heads** (actor × kind × target × …) + remask / sequential conditioning | Heads could not express “best *complete* legal.” Remask only filtered illegals; it never scored whole moves. Schema 16 → join-score each legal row. Schema 15 ONNX fork only. |
| **Hard-removing distractors** from the legal set (train on preferred-only menus) | Broke transfer: eval / live still see the full menu; the net never practiced ranking among junk. Soft-mask tilts sampling instead. |
| **Soft-mask as a grade** (“preferred = correct / pays”) | Soft-mask only raises try rate. Same bias on pickup *and* empty-EZ leaves their relative softmax order unchanged; only reward separates them. Treating bias as label confuses exposure with grade. |
| **Equal soft-mask on LBR pickup and every empty-EZ** | Same +2.5 on both → TD_RUN endzone prior stays hot; train US rate stayed ~6%. Empty-EZ soft-mask is probabilistic exposure now, not a second “correct” flag. |
| **Naive flat MultiDiscrete catalog** of all factor combos | Softmax over ~millions of pad products; not the live action space. Live gym is `Discrete(MAX_LEGAL_MOVES)` over this step’s packed rows. |

### Board ↔ move representation

| Attempt | Why it failed / was dropped |
|---------|-----------------------------|
| **GAP / attention / gather as the policy’s board view** | **Rejected prior** — summarizing the pitch *before* join loses square-level binding to the move. **Not the live policy** (that flattens the map). Value may still GAP for SB3 `features_dim`. Do not re-diagnose “spatial erasure on the join” against current code. |
| **Flatten join’s fat first Linear (~11.6M)** | **Live tradeoff, not a crash.** Preserves every square next to the row; costs a wide `Linear(22635→512)`. Cross-attn (move queries 165 tokens) is an unadopted alternative that shrinks that matrix but *is* a soft spatial summary — conflicts with the current “no summarizing on policy path” lock until explicitly approved. |
| **Stock SB3 `action_net`: Linear(latent → n_actions)** | Fixed catalog index ≠ “score this step’s legal menu.” Custom `row_scorer` over board‖row. |
| **Stock `net_arch = [64, 64]` on pi/vf** | Crushed the 512-d value trunk into a tiny MLP. Empty `pi`/`vf` lists keep features intact. |
| **Cell menu-summary affordances** (TD / claim / pickup / reach bbox on every player cell) | Choice-level facts jammed onto *player* squares; join had to re-bind cell flag ↔ dest. Dropped schema 25 — prefer per-row bits when measured necessary. |
| **Missing row facts the join cannot recover** (e.g. claim certainty, `BallEncounter.PICK_UP`, per-row TD) | Not “worked then failed” — known holes. Board channels alone do not name *which menu row* claims/scores/picks up. Candidates in the tables above. |

### Learning signal / PPO knobs

| Attempt | Why it failed / was dropped |
|---------|-----------------------------|
| **Stock LR `3e-4`** | Opening policy mass stayed flat on drills. Raised to `1e-3` (create + resume re-apply). |
| **`ent_coef = 0`** | Large Discrete peaked immediately; keep a small entropy bonus (`0.005`). |
| **VecNormalize reward on sparse drills** | Compressed TD `+4.75` / THEM `−0.5` into mush; openings stayed advantage-neutral. Scenario path keeps rewards hearable; full-game still normalizes for long episodes. |
| **“Just turn normalize off and openings will learn”** | Raw-scenario overnight (~27 cycles): LBR stayed d0, eval `E[p]` ~0.015, ranks stuck. Scale was not the missing representation/bind fix. See debug doc. |
| **Assuming GAE automatically credits the opening** after a two-step pickup→TD | Loud advantage landed on the *scoring* step; openings often ~neutral even when env paid correctly. Credit assignment ≠ “big terminal exists.” |
| **Assuming fail-window `−0.5` punishes the empty-EZ click** | Terminal loss is on window close, not on the bad opening submit. Soft-mask empty-EZ + delayed −0.5 ≠ “this opening is labeled bad.” |
| **“Ball isn’t in the obs” as the LBR root cause** | `hasBall` / `distToBall` (and earlier affordances) were packed and tested. Stuck openings tracked learning signal / endzone prior, not a missing ball channel. |
| **Cloning Lookahead as the RL reward** | Out for v1 — BC seed is imitation; PPO grades harness rewards (`DESIGN.md`). |
| **Flatten pitch `(C,H,W)` hygiene (AI2.23) while keeping this Conv trunk** | Parked — conflicts with the spatial CNN + flatten-join design. |

### Still true (do not “fix” by undoing)

- Soft-mask = exposure; reward = grade.
- Policy sees **full flat pitch map** + full row pack — no GAP/attn/gather shortcut on that path (value GAP is separate).
- Full legal menus at train and eval; prefer soft tilt over hard curation.
- Openings stuck on drills are still primarily a **learning-signal / prior** story ([`PPO_LEARNING_DEBUG.md`](PPO_LEARNING_DEBUG.md)), not proof that the pitch was erased before the join.

---

## Next ablation

**Path / lane features** (optional): intervening squares between source and
target — not encoded as an explicit path tensor today (the flat map still
contains every cell).
