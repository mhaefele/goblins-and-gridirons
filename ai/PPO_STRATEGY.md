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
  ├─ pitch (24,15,11) ──► per-cell emb ──► Conv2d×3 ──► GAP ──► 128-d
  ├─ benches (2,10,24) ─► per-slot emb ──► flatten
  ├─ scalars / challenges / deck / hand / held_check ─► emb + scaled floats
  └─ concat ──► Linear → ReLU → 512-d board latent
                    │
                    ├─ value head (empty pi/vf MLPs → latent_vf == features)
                    │
                    └─ for each live legal row:
                         encode_legal_rows(row) → ~91-d
                         row_scorer([ latent_pi ‖ row_emb ]) → logit
                         + soft_mask_legal × SOFT_MASK_LOGIT_BIAS × soft_mask_strength
                         pad / illegal → −1e8
                    │
                    └─ MaskableCategorical over 1024 → row index
```

### Board trunk

- Pitch is already spatial in the gym Dict: `(C,H,W) = (24, 15, 11)`.
- Each cell is embedded (enums + pitch-axis ids + scaled stats) into an
  `_OCCUPANT_WIDTH`-channel map, then a small **Conv2d** stack
  (`64 → 64 → 128`, `k=3`, pad 1) + **global average pool**.
- Benches are **not** convolved — they are a dugout table, not pitch neighbors.
- Non-pitch tensors fuse with the CNN vector through one projection Linear to
  **512** (`_PROJECTED_FEATURES`).

**Intentional non-goals (for now):**

- No source/target gather from the CNN feature map into the join (Global+Local
  micro features). Join still uses the **global** 512-d latent ⊕ independently
  encoded row factors (including categorical source/target pitch embeddings).
- No explicit intervening-lane / path features. Endpoint emb ids + global pool
  are not a lane model.

### Legal-row join

- Each packed row (13 ints): actor, kind, targetX/Y, challengeSlot, bonus,
  faces, sourceX/Y, actorHasBall, targetHasBall.
- `encode_legal_rows` reuses board emb tables (shared `pitch_x/y_emb`, kind,
  player index, …).
- `row_scorer`: `Linear(512 + row_dim → 512) → ReLU → Linear → 1`.
- Score only through this batch’s `legal_move_count.max()`; pad tail stays −1e8.
- SB3 `net_arch = {pi: [], vf: []}` so stock `[64,64]` MLPs do **not** crush the
  512-d trunk before the join (`POLICY_NET_ARCH`).

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
