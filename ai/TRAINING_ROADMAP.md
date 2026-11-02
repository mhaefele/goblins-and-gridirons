# Training roadmap

Phased plan for teaching the MaskablePPO policy. **Keep this file current** as gates
move — if a phase starts growing extra incentives, curricula knobs, or special cases,
that is a signal we are adding complexity too early.

**Owners / file map:** [`OWNERSHIP.md`](OWNERSHIP.md). Soft-mask / legal-row sampling:
[`ACTION_SAMPLING.md`](ACTION_SAMPLING.md). Run commands: [`README.md`](README.md).

Related code: `reward.py`, `scenario_curriculum.py`, `training_phases.py`,
`scripts/train_self_play.py`.

Shell chain: `dump → pack → seed → scenario_a → scenario_b → checkpoint` (see `common.sh`).

---

## Reward stack (all phases)

1. **Usual scoring** — Δ score margin + terminal win/loss/draw when the **server**
   match ends
2. **Scenario coach-window** — ±0.5 from `trainingOutcome` when `trainingEpisodeEnded`
   (half match terminal; not stacked on real match W/L)
3. **Profile shaping** — `general` / `spread` / `td` / `defense` presets (train only; eval off);
   scenario_a / scenario_b use **baseline** (no shaping)

Drill US/THEM still grades curriculum/winrate. Direction on flagged openings comes from
**soft-mask** sampling ([`ACTION_SAMPLING.md`](ACTION_SAMPLING.md#soft-mask-preference)),
not per-move bonus/slap terms.

Train also: **VecNormalize** reward normalization, tighter PPO KL
(`n_epochs` / `clip_range` / `target_kl`).

Exploration: PPO `ent_coef` (default **0.005**; **defense** **0.009**).
Value fit: PPO `vf_coef` (default **0.5**; **td** **0.8**).

---

## How to read a phase

Each phase has:

- **Do** — what we train / evaluate
- **Gate** — measurable exit criteria before the next phase
- **Do not** — complexity we are deliberately deferring

If a gate is not met after a large step budget, **fix the signal or exploration**, do
not unlock the next phase or stack new incentives.

Requesting a shaped profile before `scenario_b` is ready trains the incomplete baseline
phase first (`resolve_targets` deferral).

**No auto rollback:** gates only decide *where to train next*. They never delete later
phase trees. Going backwards is explicit (`./start_over.sh --phase …`).

---

## Phase 0 — Bootstrap (`dump` / `pack` / `seed`)

**Do**

- Expert clone into a seed checkpoint (behavioral prior)
- Confirm obs/action contract + train/eval loop end-to-end

**Gate**

- Clone finishes; `maskable_ppo_seed.zip` exists; seed health eval ran (stdout `Eval [baseline] vs-…`)

**Do not**

- Copy the seed onto shaped profile zips (Python forks from `scenario_b`)

**What the seed is**

Lookahead dumps (obs → chosen action) are **supervised** into MaskablePPO weights
(`clone.py`: raise log-prob of the recorded move). That is a **prior**, not a finished
player: the net already puts mass on expert-like choices in similar states, but it has
not been **reinforced** on our drill openings, soft-mask tilt, or win/score reward.
Scenario / PPO phases are where those preferences get practiced and locked under our
gates — not a second “believe the expert” clone.

---

## scenario_a — Board / endzone drills (baseline)

**Checkpoint:** `output/checkpoints/scenario_a/`

**Goal:** teach unambiguous board openings before claim-card dialects contaminate shared
heads.

**Why board before claims**

Action heads are shared across every drill ([`ACTION_SAMPLING.md`](ACTION_SAMPLING.md)).
Claim openings pull on different kinds (claim/replace/slot) and different menus; if they
train in the same mix while TD_RUN / throw / block openings are still soft, gradients from
claims rewrite the shared priors before the core openings have stable values. Board-first
lets us **establish** those core opening habits (and a recoverable `scenario_a` zip), then
add claims in scenario_b without drowning the signal. Same idea as “don’t break core
mechanics before they’ve settled” — the breakage path is **shared-head interference**, not
deleting board drills from the catalog.

**Do**

- Train/eval **board scenarios only** (catalog `family=BOARD`)
- Defer all `CLAIM_*` drills until every board drill reaches
  `CLAIM_UNLOCK_BOARD_DIFFICULTY` (**4**)
- Short horizon (trainer turn) while difficulties are low; promote on perfect after-eval
- Soft-mask on flagged openings (sampling bias only)
- Every active drill trains/evals each cycle (no lead-pack skip)

**Gate**

- Every board drill at **d4+**
- Deterministic `drill_rate` rising on flagged openings (esp. TD_RUN)

**Do not**

- Claim scenarios
- Full games
- Hard-mask openings

---

## scenario_b — Combined scenarios (baseline)

**Checkpoint:** `output/checkpoints/scenario_b/`

**Goal:** add challenge-claim drills once board openings are solid at high difficulty.
Full games stay **off**.

**Do**

- Unlock claim catalog when scenario_a gate holds (see **Why board before claims** above)
- Soft-mask still applies wherever the server flags `expectedScenarioMove`

**Gate**

- Every catalog drill (board + claim) at **MAX_DIFFICULTY (d5)**
- Board drills hold success at d4+ while claims climb

**Do not**

- Open full-game train early “to mix things up”
- Fork shaped profiles before this gate

---

## profiles — Full games + shaped specialists

**Checkpoints:** `output/checkpoints/{baseline,general,spread,td,defense}/` (forked from frozen `scenario_b`)

**Goal:** transfer drill habits into real matches; rotate / pin shaped reward presets.

**Do**

- Unlock full-game train **only after** scenario_b gate — equal parts vs HEURISTIC,
  LOOKAHEAD, and ONNX seats from `FULL_GAME_ONNX_OPPONENT_PROFILES` (today: general, defense)
- Scenario drills: **stragglers** (< d5) keep learning budget; **soft d5** (imperfect
  after-eval at MAX) get maintenance budget; healthy d5 stay eval-only
- Judge transfer with shaping-off eval (winrate / score_diff / TD rates)
- Rotate `baseline` + `profiles_for_all("train")` shaped names with `--profile all`
  (today: `general`, `spread`; pin `td` / `defense` explicitly). Summary `--profile all` still
  lists every reward profile via `profiles_for_all("summary")`.

**Why baseline stays in the rotation post-split:** shaped profiles add training-time
reward shaping; **eval always runs with shaping off** for every profile. Baseline is a
real profile zip (own tree under `checkpoints/baseline/`) forked once from frozen
`scenario_b`, then trained with margin + terminal only — the unshaped control. Without
it in the rotation you cannot tell whether a shaped profile beats plain PPO or only
beats itself. `active_targets()` lists baseline first, then shaped names from
`profiles_for_all(purpose)`; do not drop baseline from that set. After split, do not
keep writing the `scenario_b` tree.

**Gate**

- Full-game metrics trend up without drill_rate collapsing
- Clear story per profile without new secret rewards

**Do not**

- Drop soft-d5 maintenance while games-only (healthy d5 stay eval-only; soft/stragglers train)
- Parallel experimental reward stacks beyond the named presets

---

## Complexity checklist (use when tempted to add something)

Ask before merging:

1. Does this change the **reward stack** (new term, slap, amplify)? Prefer no until a
   scenario_a / scenario_b gate fails for a clear reason — and never reintroduce
   **per-move** scenario bonus/slap (coach-window ±0.5 is the intentional exception).
2. Does this **unlock games or claims** earlier than the gate? Prefer no.
3. Does this add a **Python re-implementation** of something the server already flags
   (`expectedScenarioMove`)? Prefer delete the duplicate.
4. Is this an **entropy/schedule** tweak? Fine as a knob; document the value here if it
   becomes the new default.
5. Soft-mask only — **never** hard-remove distractors from the legal set (transfer broke).

---

## Current defaults (implementation pointers)

| Knob | Where | Notes |
|------|--------|--------|
| Scenario learning (below d5) | `scenario_curriculum` — 3840 ts/drill/cycle (1×3840 rollout) | Contiguous train → after-eval |
| Scenario mastered (d=5) | `scenario_curriculum` — 3840 ts/drill/cycle | While pack still climbing; game phase uses maintenance for soft d5 only |
| Scenario soft-d5 maintenance | `scenario_curriculum` — 3840 ts/drill/cycle | Game phase: imperfect after-eval at MAX; healthy d5 eval-only |
| vs HEURISTIC / LOOKAHEAD / ONNX train | `train_self_play` — 20k ts each per cycle (ONNX × `FULL_GAME_ONNX_OPPONENT_PROFILES`: general, defense) | 0 until full-game unlock (all drills d5) |
| vs HEURISTIC / LOOKAHEAD / ONNX eval | `train_self_play` — 200 games each seat, every 4 visits per profile (`FULL_GAME_EVAL_EVERY_CYCLES`; eval-only each pass) | Shaping off, `deterministic=True`; ONNX seats use reward-profile ids as `onnxProfile`; multi-wave up to `n_envs` |
| `n_envs` / `n_steps` / `batch_size` | `train_self_play` — 30 / 128 / 256 | `device=cpu` |
| `learning_rate` `3e-5` | `train_self_play` `_DEFAULT_LEARNING_RATE` | Create **and** resume |
| `ent_coef` `0.005` (`defense` `0.009`) | `train_self_play` `DEFAULT_ENT_COEF` / `PROFILE_ENT_COEF` | Create **and** resume |
| `vf_coef` `0.5` (`td` `0.8`) | `train_self_play` `DEFAULT_VF_COEF` / `PROFILE_VF_COEF` | Create **and** resume |
| `n_epochs` `4` / `clip_range` `0.1` / `target_kl` `0.02` | `train_self_play` | Critic/KL stability |
| VecNormalize `norm_reward` | train segments; `{stem}_vecnormalize.pkl` | Obs not normalized |
| Soft-mask bias `2.5` | `constants.SOFT_MASK_LOGIT_BIAS` | [`ACTION_SAMPLING.md`](ACTION_SAMPLING.md#soft-mask-preference); not a reward |
| Scenario coach-window ±0.5 | `reward.SCENARIO_TERMINAL_*` | Only when `trainingEpisodeEnded`; not stacked on match ±1 |
| Claim unlock board d4 | `scenario_curriculum.CLAIM_UNLOCK_BOARD_DIFFICULTY` | scenario_a → scenario_b |
| Full-game unlock d5 | `scenario_curriculum.FULL_GAME_UNLOCK_DIFFICULTY` | After all drills mastered |
| Gate-fail double run | `scenario_curriculum.SCENARIO_TRAIN_GATE_FAIL_MULTIPLIER` | 2× when gating drill has current-difficulty `gate_fails` > 0 below d5; optional drills never multiply; d5 fails excluded |
| Behind-pack / bottleneck / demote run | `SCENARIO_TRAIN_BELOW_PACK_MAX_MULTIPLIER` / `SCENARIO_TRAIN_HIGH_DEMOTE_THRESHOLD` | 2× below pack max (no claim-unlock door) or current-level fail; 4× current fail at pack **min**, or below-d4 while boards are not yet d4+, or + `d5_demotes` ≥ 5 once boards are d4+ |
| Progress waive / recover | `scenario_curriculum.GATE_PROGRESS_WAIVE_FAILURES` (20) | Gating **claims** only — stop blocking gates; boards never waive; perfect at level clears current fails only |
| Scenario eval games | `scenario_curriculum.SCENARIO_EVAL_GAMES` | 10 games per drill after-eval; `n_envs` = game count |
| Scenario pass / demote | `SCENARIO_EVAL_PASS_MIN_WINS` (9) / `DEMOTE_FROM_MAX_MAX_WINS` (7) | Pass 9–10/10 to bump; ≤7/10 at d5 demotes; 8/10 is gate-fail only |
| d5 demotion / gate failures | `DEMOTE_FROM_MAX_MAX_WINS` + `.scenario_gate_failures.json` / `.scenario_drill_stats.json` | ≤7 wins/10 at d5 demotes on train sandwich after-eval; per-level fails kept (all-time sum); per-phase `cycles` files; summary sums scenario_a→current; `--eval-only` does not mutate curriculum |
| Phase routing | `training_phases.resolve_targets` / `active_targets` | Defers shaped profiles until scenario_b; never auto-wipes |
| Destructive rollback | `./start_over.sh --phase …` | Explicit only |
| Profile presets | `reward.PROFILE_SHAPING` / `SHAPED_PROFILES` | Eval uses empty shaping |

Update this table when defaults change. Cycle mix flags: `./train.sh --help`.
