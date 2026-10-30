# AI2 — Trainable model (initiative backlog)

Source of truth for trainable-AI **tasks**. Root [`BACKLOG.md`](../BACKLOG.md) defers here.

**Status:** Open — AI2.0–AI2.4 + AI2.7 done; [AI2.12](#ai212--held-dice--mid-check-obs--scenarios) **obs landed** (schema 11; scenarios still open); [AI2.13](#ai213--scenario-catalog-metadata-api) catalog metadata done; [AI2.15](#ai215--coachcontrol-seat-semantics) seat naming done; [AI2.17](#ai217--blind-spot-remediation-from-arena-diagnostics) blind-spot taxonomy open. Then [AI2.5](#ai25--multi-team-curriculum). Sampling follow-ups: [`ACTION_SAMPLING.md`](ACTION_SAMPLING.md) ([AI2.11](#ai211--action-sampling--factor-space-inventory)). Harness hygiene: [AI2.14](#ai214--split-train_self_play). Optimize (not next): [AI2.opt](#ai2opt--binary-observation-wire), [AI2.18](#ai218--one-bonus-play-action-kind-retrain), [AI2.19](#ai219--fold-ai-action-vocabulary-investigate). Ship AI1 done; see [archive/BACKLOG-done.md](../archive/BACKLOG-done.md).

**Design:** [`DESIGN.md`](DESIGN.md) · **Owners:** [`OWNERSHIP.md`](OWNERSHIP.md) · **Architecture:** [Backend § Long-term product goals](../backend/ARCHITECTURE.md#long-term-product-goals).

---

## Prime — do before more curriculum polish

- [x] **[AI2.12](#ai212--held-dice--mid-check-obs--scenarios) — Held dice in obs** (schema 11). Scenarios still open.
- [ ] **Summary:** replace pre-split `scenario_b` fallback in `_scenario_b_summary_row` with first-class export-freeze + `evals/scenario_b` (and fill `export` / `scenario_a` the same way).

---

## Phase checklist

- [x] [AI2.0](#ai20--learning-contract-kotlin-dtos) — Learning contract as Kotlin DTOs
- [x] [AI2.sse](#ai2sse--disable-sse-on-match-create-wont-do) — Disable SSE on match create *(won't do)*
- [x] [AI2.1](#ai21--dedicated-ai-http-api) — Dedicated AI HTTP API (obs / mask / step)
- [x] [AI2.2](#ai22--python-gym--sb3-self-play) — Python gym + SB3 self-play
- [x] [AI2.3](#ai23--eval-vs-computer) — Eval vs computer
- [x] [AI2.4](#ai24--unattended-traineval-cycles) — Unattended train–eval cycles
- [ ] [AI2.5](#ai25--multi-team-curriculum) — Multi-team curriculum
- [ ] [AI2.6](#ai26--onnx-export--kotlin-compute-opponent) — ONNX + Kotlin opponent
- [x] [AI2.7](#ai27--optional-reward-shaping-day-2) — Optional reward shaping (day 2)
- [ ] [AI2.8](#ai28--evaluate-obs-enrichment-vs-truncated-legals) — Evaluate obs enrichment vs truncated legals
- [ ] [AI2.9](#ai29--conditional-action-heads-follow-up) — Conditional action heads (follow-up)
- [ ] [AI2.10](#ai210--legal-move-inputs--obs-dict-shape) — Legal move inputs & obs dict shape
- [ ] [AI2.11](#ai211--action-sampling--factor-space-inventory) — Action sampling & factor-space inventory
- [ ] [AI2.12](#ai212--held-dice--mid-check-obs--scenarios) — Held dice / mid-check obs + scenarios **(prime)**
- [x] [AI2.13](#ai213--scenario-catalog-metadata-api) — Scenario catalog metadata API (drop Python board/claim mirror)
- [ ] [AI2.14](#ai214--split-train_self_play) — Split `train_self_play.py` orchestration
- [x] [AI2.15](#ai215--coachcontrol-seat-semantics) — CoachControl: human vs harness vs in-process computer
- [x] [AI2.16](#ai216--remove-ai-choose--in-process-scenario-lookahead-test) — Remove `/choose`; in-process scenario lookahead test
- [ ] [AI2.17](#ai217--blind-spot-remediation-from-arena-diagnostics) — Blind-spot remediation (from arena diagnostics)
- [ ] [AI2.opt](#ai2opt--binary-observation-wire) — Binary observation wire (optimization)
- [ ] [AI2.18](#ai218--one-bonus-play-action-kind-retrain) — One bonus-play action kind *(retrain; bundle with the next layout change)*
- [ ] [AI2.19](#ai219--fold-ai-action-vocabulary-investigate) — Fold AI action vocabulary into engine *(investigate; not next)*
- [ ] [AI2.20](#ai220--remove-local-sb3-pickle-aliases) — Remove local SB3 pickle-alias shim *(after resume zips re-saved)*

---

## AI2.0 — Learning contract (Kotlin DTOs)

| Field | Detail |
|-------|--------|
| **Status** | **Done.** |
| **Goal** | **Ship Kotlin DTOs** that *are* the learning contract — flat obs, action factors / catalog rows (+ optional `LegalActionId` sidecar), step envelope, `schemaVersion`, and harness metadata (`actingSeat`). Reward is **not** a DTO — harness derives it from obs scores/result. No parallel markdown/OpenAPI field table: the DTO source is what you read. Packer stubs or TODOs on the types are fine; HTTP wiring can wait for AI2.1. |
| **Depends on** | AI1 done (legal-action graph exists). |
| **Files** | [`api/ai/dto/`](../backend/src/main/kotlin/com/mhaefele/gridiron/api/ai/dto/) — contract types + KDoc. [`api/ai/mapping/`](../backend/src/main/kotlin/com/mhaefele/gridiron/api/ai/mapping/) — `MatchState` / `LegalAction` → those DTOs (`toAiStepDto`, etc.). |
| **Acceptance** | Compiling Kotlin DTOs for step obs, legal-row **`kind` + typed [AiFactor](../backend/src/main/kotlin/com/mhaefele/gridiron/api/ai/dto/AiFactor.kt) list** + id sidecar, acting seat; nested GUI snapshot types are not reused; KDoc states schema / sentinel / factor conventions (omit unused factors — no NONE pad). Harness-only fields on [`AiGameStateDto`](../backend/src/main/kotlin/com/mhaefele/gridiron/api/ai/dto/AiGameStateDto.kt); packable body nested [`AiObsDto`](../backend/src/main/kotlin/com/mhaefele/gridiron/api/ai/dto/AiGameStateDto.kt); envelope [`AiStepDto`](../backend/src/main/kotlin/com/mhaefele/gridiron/api/ai/dto/AiStepDto.kt). Domain→DTO mapping in place. Flat float packer + HTTP = [AI2.1](#ai21--dedicated-ai-http-api) / [AI2.opt](#ai2opt--binary-observation-wire). Flat single-Discrete catalog is **out**. |
| **Rulebook** | n/a (engine fidelity stays on existing resolvers). |

**Do not:** write `ai/docs/contract.md` (or similar) that duplicates DTO fields. If prose is needed, put it in KDoc on the DTO. Rationale lives in [`DESIGN.md`](DESIGN.md).

---

## AI2.sse — Disable SSE on match create *(won't do)*

| Field | Detail |
|-------|--------|
| **Status** | **Won't do (2026-08).** |
| **Why** | Unused SSE already costs nothing: hub channels/sinks are created on subscribe; with no `/events` client (and no computer listener on HvH train), `publish` is a no-op. A create flag only rejects accidental subscribe — not worth the MatchConfig/API surface. Harness simply never opens `/events`; wait on AI submit/`forSeat` instead ([Waiting without SSE](DESIGN.md#waiting-without-sse)). |
| **Was** | Opt-out on `POST /api/matches` to reject/no-op `GET …/events` for train/eval matches. |

---

## AI2.1 — Dedicated AI HTTP API

| Field | Detail |
|-------|--------|
| **Status** | **Done.** |
| **Goal** | Wire HTTP over the **AI2.0 DTOs**: `/api/ai/...` **step** routes ([`AiStepDto`](../backend/src/main/kotlin/com/mhaefele/gridiron/api/ai/dto/AiStepDto.kt) — obs + legals; optional id sidecar; **`actingSeat` harness metadata**). Reuse existing match create/start/submit — create is not AI-specific (coach controls only). Harness-owned seats for train; one seat + `COMPUTER` for eval. |
| **Depends on** | AI2.0 (DTOs exist). |
| **Files** | [`AiMatchController`](../backend/src/main/kotlin/com/mhaefele/gridiron/api/ai/AiMatchController.kt) `GET /api/ai/matches/{id}/step`, `POST /api/ai/matches/{id}/submit`; AI2.0 mapping; Jackson on [`AiFactor`](../backend/src/main/kotlin/com/mhaefele/gridiron/api/ai/dto/AiFactor.kt). |
| **Acceptance** | Python (or curl) can create via normal `POST /api/matches` (seats as needed; no `/events`), loop AI get-step → `POST /api/ai/.../submit` until terminal; responses match the AI2.0 DTO shapes; acting seat present for harness routing; mask aligned when that seat must act; submitting a masked-out action fails closed; computer agent does not race harness-owned seats; one-seat callers pass `forSeat` and the server waits (no `/events` dependency). |
| **Rulebook** | n/a |

---

## AI2.2 — Python gym + SB3 self-play

| Field | Detail |
|-------|--------|
| **Status** | **Done.** |
| **Goal** | Gymnasium env wrapping the AI API; action = **`kind` + typed factors** (flatten to MultiDiscrete / masks in Python for v1); self-play via **`actingSeat`**. Follow [Python conventions](DESIGN.md#python-harness-conventions); mark masking / MultiDiscrete setup as `# DEVIATION` where it leaves the simplest MaskablePPO Discrete tutorial. **Do not** build a flat stable catalog index as a prerequisite. |
| **Depends on** | AI2.1 |
| **Files** | [`gridiron_ai/`](.) (env, client, obs, actions, reward); [`scripts/train_self_play.py`](scripts/train_self_play.py); [`requirements.txt`](requirements.txt); [`output/checkpoints/`](output/checkpoints/). |
| **Acceptance** | Short self-play train job works end-to-end; policy outputs factor tuples mapped to `legalActionId`; acting-seat routing without SSE; checkpoints + entropy (or similar) logged; thin DTO → tensor glue only. |
| **Rulebook** | n/a |

---

## AI2.3 — Eval vs computer

| Field | Detail |
|-------|--------|
| **Status** | **Done.** |
| **Goal** | After (or interleaved with) training, play M games as one seat vs built-in `COMPUTER` (lookahead primary; also heuristic baseline). **Submit with `forSeat`** (no `/events`) so the server waits until `actingSeat` is the eval policy’s seat (computer moves in-process). Record winrate, draws, mean score differential, TD / challenge point breakdown, and policy entropy. Snapshot the model with the eval summary. |
| **Depends on** | AI2.2 |
| **Files** | [`gridiron_ai/env.py`](gridiron_ai/env.py) (self vs computer; `forSeat` wait); [`scripts/train_self_play.py`](scripts/train_self_play.py) (`--eval-lookahead` / `--eval-heuristic`); create-match `computerOpponent` ([`ComputerOpponentKind`](../backend/src/main/kotlin/com/mhaefele/gridiron/shared/ComputerOpponentKind.kt)). Shared `info["bb_episode"]` + [`aggregate_episodes`](gridiron_ai/metrics.py). |
| **Acceptance** | Automated eval run produces a comparable metrics artifact; vs-lookahead and vs-heuristic both runnable **without SSE**; harness only acts when acting-seat says so; results are enough to tell “better than last checkpoint” on winrate and score margin. |
| **Rulebook** | n/a |

**Note:** This is the product-relevant vs-computer step — keep it early in the cycle, not blocked on ONNX.

---

## AI2.4 — Unattended train–eval cycles

| Field | Detail |
|-------|--------|
| **Status** | **Done.** |
| **Goal** | Single entrypoint that repeats: train N games → eval M games → log metrics → optional checkpoint snapshot — without manual intervention. |
| **Depends on** | AI2.3 |
| **Files** | [`scripts/train_self_play.py`](scripts/train_self_play.py) (`--cycles` + train/eval knobs); cycle TSV `output/runs/train_eval_cycles.tsv`. Expert prior: `--export-expert` / [`scripts/import_expert.py`](scripts/import_expert.py) / [`scripts/clone_expert.py`](scripts/clone_expert.py). |
| **Acceptance** | Leaving the job running overnight produces a time series of eval metrics and checkpoints; failure of one match does not silently stall the whole loop (clear logs / continue or abort policy documented). |
| **Rulebook** | n/a |

---

## AI2.5 — Multi-team curriculum

| Field | Detail |
|-------|--------|
| **Goal** | After a basic single-team policy works, introduce multiple teams. Reserve team/ability channels in obs from AI2.0 so this is not a full encoder rewrite. Eval mixes teams **equally** and records per-matchup or per-team aggregates. |
| **Depends on** | AI2.4 (or solid AI2.3 if cycling early). |
| **Files** | Train/eval config; obs schema team features; catalog if team-specific actions need mask slots. |
| **Acceptance** | Train can sample more than one roster; eval reports results with equal team weighting (or documented stratified schedule); single-team mode still available. |
| **Rulebook** | Team cards / catalog — behavior still from engine. |

---

## AI2.6 — ONNX export + Kotlin compute opponent

| Field | Detail |
|-------|--------|
| **Goal** | Freeze obs + action schema version; export trained policy to ONNX; add Kotlin libraries to load it; implement `OpponentStrategy` that builds the same obs/mask, runs the model, submits the chosen legal action. Expose as a compute-opponent option alongside heuristic/lookahead. |
| **Depends on** | AI2.3+ with a schema worth freezing; preferably AI2.4 so quality is measured. |
| **Files** | Export script under `ai/`; ONNX artifact convention; `backend/.../opponent/` model-backed strategy; match create / coach control wiring. |
| **Acceptance** | A match can seat the ONNX opponent; it only ever submits currently legal actions; smoke playthrough completes; version mismatch fails loudly. |
| **Rulebook** | n/a |

---

## AI2.7 — Optional reward shaping (day 2)

| Field | Detail |
|-------|--------|
| **Status** | **Done (profiles).** |
| **Goal** | Experiments only if v1 learning stalls: light terms (e.g. possession, end-zone proximity) — **not** a copy of `LookaheadScorer`. Keep win/loss + point diff dominant. |
| **Depends on** | Evidence from AI2.3/AI2.4 that sparse reward is insufficient. |
| **Files** | [`reward.py`](gridiron_ai/reward.py) presets `general` / `spread` / `td` / `defense`; [`train_self_play.py`](scripts/train_self_play.py) `--profile` rotation + per-profile checkpoints. Obs career TD/cards + submit `scoring` + legal `potentialChallengeClaims`. |
| **Acceptance** | Shaping is flaggable/off-by-default on eval; train uses named profiles; ablations compare vs pure v1 on the same eval suite. |
| **Rulebook** | n/a |

**Presets:** shared idle / light ball / uncertain-challenge tease; **td** adds +3/TD + Δproximity possession; **defense** uses opponent-ball deltas + dugout gains + softer block bonuses. Terminal ±1 unchanged (specialists may optimize the proxy).

---

## AI2.8 — Evaluate obs enrichment vs truncated legals

| Field | Detail |
|-------|--------|
| **Goal** | **Evaluate (not presuppose)** whether the truncated AI legal-move representation loses planning signal that the GUI legal set used to carry — and whether extra **obs** features should compensate. Candidates: per-player / per-cell highlights (e.g. “this occupant could claim an active challenge after a plausible action”), challenge/bonus/ability **family tags** (run-related, block-related, …), other affordance hints derived from engine eligibility. Compare against letting ordinals + board + rewards teach associations in training. |
| **Depends on** | Working train/eval loop ([AI2.3](#ai23--eval-vs-computer)+) with a baseline policy on the lean DTO; enough games to see failure modes (ignores challenges, never claims, etc.). |
| **Files** | Write-up / decision in this backlog (or KDoc on rejected/accepted DTO fields); only then bump `schemaVersion` and extend [`AiGameStateDto`](../backend/src/main/kotlin/com/mhaefele/gridiron/api/ai/dto/AiGameStateDto.kt) / cell player fields if we opt in. |
| **Acceptance** | Explicit go/no-go: either document “training discovers X; no enrichment” with eval evidence, or land a versioned obs addition with an ablation vs the lean baseline. Do not add speculative highlight channels before that. |
| **Rulebook** | n/a |

**Notes for the evaluation**

- Truncated actions (kind × from × to) drop path/annotation richness the human UI has; obs highlights are one way to put *consequences* back without exploding the action space.
- Hand-authored “this card is run-related” tags can help sample efficiency early, but they can also bake in wrong taxonomies and fight what the net would learn from claim outcomes + scores. Prefer measuring failure modes first.
- Reuse engine claim/eligibility helpers where possible if we do add highlights — don’t invent a second rules brain in the packer.
- **Held dice / mid-check rolls** moved to **[AI2.12](#ai212--held-dice--mid-check-obs--scenarios)** (prime) — not an optional enrichment experiment.

---

## AI2.9 — Conditional action heads (follow-up)

| Field | Detail |
|-------|--------|
| **Status** | **Partial.** Sequential **masking** landed (`ConditionalActorCriticPolicy`, actor→kind→target… via `CONDITIONAL_SAMPLE_ORDER`). Preference conditioning and factor-space cleanups → [`ACTION_SAMPLING.md`](ACTION_SAMPLING.md) ([AI2.11](#ai211--action-sampling--factor-space-inventory)). |
| **Goal** | After **basic factored** training works ([AI2.2](#ai22--python-gym--sb3-self-play)), optionally sample heads from the typed [`AiFactor`](../backend/src/main/kotlin/com/mhaefele/gridiron/api/ai/dto/AiFactor.kt) list after `kind` (Actor / BoardTarget / ChallengeSlot / …) instead of a fixed MultiDiscrete pad. Same DTO rows and id sidecar. Clear `# DEVIATION` from the v1 always-on flattened factors. |
| **Depends on** | Measurable 4-factor baseline from AI2.2+. |
| **Files** | `ai/` conditional policy / masking; Kotlin DTO unchanged if factors already correct. |
| **Acceptance** | Conditional-head policy can play full games; ablation vs basic 4-factor on AI2.3 eval; 4-factor remains default until conditional wins clearly. |
| **Rulebook** | n/a |

**Intent:** learning experience + possible sample-efficiency win — not a blocker for first training loops.

**Landed vs not:** one forward → seven logits + remask after each pick (legality). Not sequential **conditioning** (next-head logits recomputed given prior picks). See [`ACTION_SAMPLING.md`](ACTION_SAMPLING.md).

---

## AI2.10 — Legal move inputs & obs dict shape

| Field | Detail |
|-------|--------|
| **Goal** | **Evaluate (not presuppose)** how much legal-move information the policy and value nets should see beyond conditional masking alone — and whether the Dict observation should split the monolithic game-state vector into logical sections now that `legal_moves` / `legal_move_count` already ride as separate keys. Decide *what* to add (cheap scalars, per-kind summaries, encoded legal rows, something else) and *where* it lives (concat to features, separate encoder branch, etc.). Keep the wire/DTO contract stable unless a split forces a version bump. |
| **Depends on** | [AI2.9](#ai29--conditional-action-heads-follow-up) baseline (board-only features + conditional sampling); [AI2.3](#ai23--eval-vs-computer)+ to compare ablations. |
| **Files** | `ai/gridiron_ai/obs.py`, `env.py`, `conditional_policy.py` (features extractor); optional Kotlin packer / `schemaVersion` only if dict keys or flat layout change. |
| **Acceptance** | Written decision + eval evidence: either “board-only + masking is enough” or a landed approach with ablation vs the baseline on the AI2.3 suite. If splitting game state, document the new Dict keys and whether the flat 2067 vector remains for ONNX/wire compat. |
| **Rulebook** | n/a |

**Notes**

- Distinct from [AI2.8](#ai28--evaluate-obs-enrichment-vs-truncated-legals): that item is extra *game-state* affordance hints on the DTO; this one is feeding the *legal menu* to the net and reshaping the Python obs Dict.
- **Dict-shape half (landed, schema 7):** gym keys `scalars` / `challenges` / `deck` / `hand` / `pitch` `(C,15,11)` / `benches` `(2,10,C)` + enum embeddings + pitch `(x,y,dist-to-ball)`; MLP concat, no CNN. Kotlin `AiObsDto.cells[185]` is unchanged; `schemaVersion` bumped because the gym/model packing changed. **Schema 8:** pitch-only `boardTarget` head; play-bonus `HandSlot` removed (identity in kind). **Schema 9:** free factors + `sugarFactors`; `declineOffer` / `reroll` / `adjustByDoOver`; drop reroll MultiDiscrete head; omit `cancelThrowTeammate` from AI legals. Prior schema checkpoints/npz are incompatible.
- Remaining: whether the policy/value nets should see legal-row content beyond masking; CNN vs this MLP concat (ablate later). Raw padded legal matrix into an MLP is still a candidate to reject early.
- Renaming `board` → something like `game_state` (or splitting into scalars / grid / cards / hand) is in scope for the dict-shape half of this item.

---

## AI2.11 — Action sampling & factor-space inventory

| Field | Detail |
|-------|--------|
| **Goal** | Next steps to improve **action representation & sampling** (factor heads + how we pick them). Checklist lives in [`ACTION_SAMPLING.md`](ACTION_SAMPLING.md#inventory). |
| **Depends on** | [AI2.9](#ai29--conditional-action-heads-follow-up) masking baseline in use. |
| **Files** | Checklist + factor maps + uniqueness notes: **[`ACTION_SAMPLING.md`](ACTION_SAMPLING.md)**. Implementation touches `ai/gridiron_ai/actions.py`, `constants.py`, `conditional_policy.py`; Kotlin `AiFactor` / `AiLegalKind` / mapping as needed; `schemaVersion`. |
| **Acceptance** | Inventory in `ACTION_SAMPLING.md` triaged (done / won’t do / deferred with reason); any Yes-do item either shipped or explicitly deferred; eval notes for conditioning/joint-head experiments. |
| **Rulebook** | n/a |

**Do not** duplicate the Yes-do / Measure / Maybe lists here — edit [`ACTION_SAMPLING.md`](ACTION_SAMPLING.md#inventory).

---

## AI2.12 — Held dice / mid-check obs + scenarios

| Field | Detail |
|-------|--------|
| **Status** | **Partial.** Obs contract landed (schema 11: [`AiHeldCheckObs`](../backend/src/main/kotlin/com/mhaefele/gridiron/api/ai/dto/AiHeldCheckObs.kt)). Training scenarios still open. |
| **Goal** | Expose **what the coach is looking at** on held-check steps so `reroll` / `declineOffer` / `adjustByDoOver` / `chooseBlockFaceOrder` are not blind coin-flips. Domain already has faces / `naturalRoll` / armour hold on the pending + `LegalAction`s; pack them into the learning contract (obs and/or free factors). Add **training scenarios** that open each window so curriculum can hit them on purpose. |
| **Depends on** | Schema 9 action kinds ([AI2.11](#ai211--action-sampling--factor-space-inventory) Yes-do); existing [`TrainingScenario`](../backend/src/main/kotlin/com/mhaefele/gridiron/api/ai/training/) placer/validator pattern. |
| **Files** | [`AiObsDto`](../backend/src/main/kotlin/com/mhaefele/gridiron/api/ai/dto/AiGameStateDto.kt) / mapping / `schemaVersion`; Python pack + `constants.py`; [`TrainingScenario`](../backend/src/main/kotlin/com/mhaefele/gridiron/api/ai/training/) + placer/validator (+ expert/train hooks). |
| **Acceptance** | On OS / EP / Do-Over / Enforcer windows, packed obs (or factors) include the held roll(s) the GUI would show; policy can distinguish “bad face → reroll” vs “good face → keep” in a scripted/seeded eval. New scenarios cover at least: block OS keep/reroll, throw EP keep/reroll, Do-Over adjust (throw and/or block; armour if practical), Enforcer face-order. Schema bump. |
| **Rulebook** | n/a (fidelity stays on existing resolvers). |

**Why prime:** Schema 9 correctly made keep vs spend a kind choice, but without the held roll in the state vector the net has **no input** for that decision — quality looking “not wrong” would be luck or board proxy, not skill.

**Scenario list (minimum):**

| Scenario intent | Opens |
|-----------------|--------|
| Second Swing keep vs reroll | `HANDLE_POST_POOL` with OS; known bad/good faces via scripted dice |
| Pinpoint Passer keep vs reroll | throw check hold; known natural roll |
| Do-Over (throw / block) | RT card in hand + improvable hold; vs `declineOffer` / ability reroll when both offered |
| Enforcer face order | **Dropped** — HARNESS/COMPUTER auto `bestEnforcerOrder`; HUMAN keeps GUI prompt when ≥2 distinct perms |
| (Nice) armour Do-Over | armour hold + RT |

---

## AI2.13 — Scenario catalog metadata API

| Field | Detail |
|-------|--------|
| **Status** | **Done.** |
| **Goal** | Expose scenario **taxonomy** from Kotlin so Python stops mirroring `BOARD_SCENARIOS` / `CLAIM_SCENARIOS` (and short names) in `scenarios.py`. New drills should be Kotlin-only for catalog membership; Python keeps curriculum *policy* (unlock levels, lead, timesteps, phase gates). |
| **Depends on** | Existing `TrainingScenarioEnum` + `GET /api/ai/training/scenarios`. |
| **Files** | `TrainingScenarioCatalogEntry` / `TrainingScenarioFamily`; bean `family` / `difficultyProxy`; Python `scenarios.refresh_catalog` → `output/scenario_catalog.json`. |
| **Acceptance** | Adding a board/claim drill requires Kotlin enum + `@Component` (+ tests) only — no Python catalog edit unless introducing a new *policy* knob. Offline tools read the cache written by train. |
| **Rulebook** | n/a |

**Landed:** list endpoint returns `{ name, family, difficultyProxy? }`; train refreshes once per process; new catalog names start at difficulty 0; stale difficulty keys ignored.

---

## AI2.14 — Split `train_self_play.py`

| Field | Detail |
|-------|--------|
| **Status** | **Open.** |
| **Goal** | Break the ~1.3k-LOC train/eval entrypoint into modules that match concepts already named elsewhere (env factory, PPO create/resume + VecNormalize, cycle segments, TSV/session logging). Keep one thin CLI `main`. |
| **Depends on** | None (hygiene). Prefer after [AI2.13](#ai213--scenario-catalog-metadata-api) only if both are in flight — otherwise independent. |
| **Files** | `scripts/train_self_play.py` → e.g. `gridiron_ai/train_loop/` (or `scripts/train_*.py` helpers); update [`OWNERSHIP.md`](OWNERSHIP.md). |
| **Acceptance** | `./train.sh` / `./eval.sh` / advanced flags behave the same; no single orchestration file >~400 LOC without a clear “CLI only” reason; section ownership matches the map. |
| **Rulebook** | n/a |

**Why it bloated:** Phase/curriculum *policy* correctly landed in `training_phases.py` / `scenario_curriculum.py`, but every new cycle feature (scenario sandwich, full-game unlock, profile rotation, VecNormalize, TSV) kept appending to the same driver. Conceptual org never made it into Python file boundaries — see [OWNERSHIP.md](OWNERSHIP.md#why-train_self_playpy-is-large).

---

## AI2.15 — CoachControl seat semantics

| Field | Detail |
|-------|--------|
| **Status** | **Done.** Renamed `TRAINER` → `HARNESS` on the wire. |
| **Goal** | Make seat control mean three distinct things — not “HUMAN means external submitter.” [`CoachControl`](../backend/src/main/kotlin/com/mhaefele/gridiron/shared/CoachControl.kt) is `HUMAN` / `COMPUTER` / `HARNESS`. Expert dump and PPO use **HARNESS** (Enforcer auto-sorts; no FO park). Wire answers: (1) **browser human**, (2) **external chooser** (PPO / expert / harness HTTP — no in-process agent), (3) **server-started computer opponent** (`ComputerCoachAgent`). |
| **Depends on** | None. |
| **Files** | `CoachControl.kt` + create-match / scenario create DTOs; `ComputerCoachRegistry`; `MatchGame` TTL (`hasHuman`); AI create docs in [`DESIGN.md`](DESIGN.md) / [`OWNERSHIP.md`](OWNERSHIP.md). |
| **Acceptance** | Names/KDoc make the three roles obvious; `COMPUTER` remains the only value that spawns an in-process agent; browser GUI still gets meaningful mid-check prompts. No silent “HUMAN means bot.” |
| **Rulebook** | n/a (control plane, not dice). |

**Context:** Enforcer order is hardcoded best-first for non-browser seats; `HELD_ENFORCER_ORDER` scenario removed. **Schema 15 landed:** `chooseBlockFaceOrder` omitted from AI kinds; faceOrder MultiDiscrete head deleted; Lookahead `HANDLE_ENFORCER_ORDER` → `error(...)`. Expert dump seats are HARNESS (no FO park).

---

## AI2.16 — Remove `/choose`; in-process scenario lookahead test

| Field | Detail |
|-------|--------|
| **Status** | **Done.** |
| **Goal** | Drop `POST /api/ai/matches/{id}/choose` (and `/choose/packed`). Train/eval never use it — opponent moves via in-process `ComputerCoachAgent` on `COMPUTER` seats. `/choose` duplicates that strategy pick in HTTP with odd constraints (acting seat must be non-`COMPUTER` so the agent does not race). Move scenario solvability checks off the AI HTTP surface. |
| **Depends on** | None (hygiene). Pairs with [AI2.15](#ai215--coachcontrol-seat-semantics) — no “HUMAN + `/choose`” back door. |
| **Files** | [`AiMatchController`](../backend/src/main/kotlin/com/mhaefele/gridiron/api/ai/AiMatchController.kt) (`choose`, `choosePacked`, `applyChooseSubmit`, `createChooseSubmitResult`); [`GridironApiClient`](../backend/src/test/kotlin/com/mhaefele/gridiron/integration/support/GridironApiClient.kt) `aiChoose`; [`client.py`](gridiron_ai/client.py) `ai_choose`; [`AiSubmitApiTest`](../backend/src/test/kotlin/com/mhaefele/gridiron/integration/AiSubmitApiTest.kt) choose smoke tests; [`TrainingScenarioLookaheadTest`](../backend/src/test/kotlin/com/mhaefele/gridiron/integration/TrainingScenarioLookaheadTest.kt); [`DESIGN.md`](DESIGN.md) / DTO KDoc mentioning choose. |
| **Acceptance** | No `/choose` routes or client helpers. [`TrainingScenarioLookaheadTest`](../backend/src/test/kotlin/com/mhaefele/gridiron/integration/TrainingScenarioLookaheadTest.kt) drives drills via `MatchGame` + injected [`LookaheadOpponentStrategy`](../backend/src/main/kotlin/com/mhaefele/gridiron/game/opponent/lookahead/LookaheadOpponentStrategy.kt) (same pattern as [`ExpertGameExporter`](../backend/src/main/kotlin/com/mhaefele/gridiron/api/ai/export/ExpertGameExporter.kt)) — asserts `TrainingDrillStatus.PASS` without `aiStep` / `aiChoose`. Remaining integration tests cover submit + `forSeat` wait only. |
| **Rulebook** | n/a |

**Why:** Scenario grading is domain/engine invariant, not HTTP contract fidelity. `/choose` only existed to run lookahead once over REST for tests and curl — expert export already does the right thing in-process.

---

## AI2.17 — Blind-spot remediation (from arena diagnostics)

| Field | Detail |
|-------|--------|
| **Status** | **Open.** Taxonomy + harness landed; remediation items below are the work. |
| **Goal** | Close systematic gaps where ONNX `general` disagrees with fat lookahead and loses on shared-dice turn forks. Source of truth for **failure shapes**: [`BLINDSPOTS.md`](BLINDSPOTS.md). Harness: [`BlindSpotArena`](../backend/src/test/kotlin/com/mhaefele/gridiron/integration/playthrough/arena/blindspot/BlindSpotArena.kt) (`mvn test -Dtest=BlindSpotArenaTest`). |
| **Depends on** | Working ONNX `general` + lookahead ([AI2.6](#ai26--onnx-export--kotlin-compute-opponent) path in use). |
| **Files** | [`ai/BLINDSPOTS.md`](BLINDSPOTS.md); arena package under `backend/.../playthrough/arena/blindspot/`; then scenario / reward / shaping files per child item. |
| **Acceptance** | Each child item either closed with a measurable eval change (claim/TD/sweep rates or blind-spot tag rates down) or explicitly deferred with why. Re-run BlindSpotArena on a fixed seed series when claiming progress. |
| **Rulebook** | n/a (policy quality). |

**Children** (grouped by primary fix type; letter ids stable for links):

### Scenario / curriculum first

#### AI2.17a — Missed touchdown

| Field | Detail |
|-------|--------|
| **Tag** | `missed-td` |
| **Fix type** | Scenario/curriculum → optional TD shaping audit |
| **Difficulty** | **S** (extend existing TD drills; days–~1 week to first signal) |
| **Goal** | Reduce forks where advisor scores `+4us Touchdown` and the model does not on the same shared-dice window. |
| **Suggested fixes** | Carrier / endzone drills (extend existing TD scenario family); audit `general` TD shaping vs margin; optional soft-mask or expert weight on “scoring Run this step”; re-measure with BlindSpotArena + full-game eval TD rate. |
| **Acceptance** | Lower `missed-td` rate on a fixed blind-spot seed series; no large regression vs lookahead winrate. |

#### AI2.17e — Challenge tableau replace

| Field | Detail |
|-------|--------|
| **Tag** | `tableau-replace` |
| **Fix type** | Scenario/curriculum (forced Replace) → expert keep-set prior |
| **Difficulty** | **S–M** (narrow decision; not architecture) |
| **Goal** | Better Pre-Turn replace slot choice when the tableau is full. |
| **Suggested fixes** | **Landed:** fat lookahead branches replace slots + claim-focused deepen; claim drills open on keep-set replace (soft-mask bait discard). Standalone `CHALLENGE_REPLACE` remains exposure-only. Claim episodes raise ER thresholds in setup so Pre-Turn Reserves never arms after forced replace (**TODO:** retire once boards keep dugout below real team ER). |
| **Acceptance** | Fewer `tableau-replace` interesting splits that later cost claims; qualitative agreement with lookahead on keep-set in sampled games. |

#### AI2.17b — Missed challenge claims

| Field | Detail |
|-------|--------|
| **Tag** | `missed-claim` |
| **Fix type** | Scenario/curriculum (by claim title) → expert / soft-mask |
| **Difficulty** | **M** (many titles; weeks of board coverage + retrain) |
| **Goal** | Model more often satisfies active claim facts (including **auto-claims** on the completing action). |
| **Suggested fixes** | Claim-family scenario coverage for high-frequency misses (`Star Turn!`, `Fan Out!`, `Keep Running!`, `Push Forward!`, … — refresh list from BlindSpotArena aggregates); ensure potential-claim legal annotations stay in the train signal ([AI2.8](#ai28--evaluate-obs-enrichment-vs-truncated-legals) if board-only is blind); weight expert claim steps. |
| **Acceptance** | Higher claim points / game in eval; fewer `missed-claim` tags on fixed blind-spot runs. |

### Reward / shaping first

#### AI2.17c — Missed Clean Sweep

| Field | Detail |
|-------|--------|
| **Tag** | `missed-clean-sweep` |
| **Fix type** | Reward/shaping → one multi-claim scenario |
| **Difficulty** | **S** |
| **Goal** | Prefer turn plans that bank a third claim when available (+2 Clean Sweep). |
| **Suggested fixes** | Light shaping on `claimsThisTurn` / sweep; one scenario that grades a three-claim turn; track sweeps in eval metrics. |
| **Acceptance** | Non-zero Clean Sweep rate improvement vs prior checkpoint on eval + fewer `missed-clean-sweep` tags. |

#### AI2.17d — Defense leaks (TD + claims)

| Field | Detail |
|-------|--------|
| **Tags** | `defense-leak` family — `left-td-open`, `left-claim-open` |
| **Fix type** | Reward/shaping (defense) + deny scenarios; claim-deny curriculum for `left-claim-open` |
| **Difficulty** | **M** (`left-td-open`); **M–L** (`left-claim-open` volume + fuzziness) |
| **Goal** | Fewer model forks where **they** score more TDs and/or challenge points than on the advisor fork (after our turn shape). Keep distinct from offense misses (`missed-td` / `missed-claim`). |
| **Suggested fixes** | Defense / opponent-carrier shaping audit (`defense` profile or `general` terms); **landed** `DENY_TD`; **gated** `DENY_STRIP_IT`, `DENY_DROP_THEM`; **exposure-only** `DENY_PICKUP`, `DENY_LOCK_THEM_DOWN`; track `left-td-open` vs `left-claim-open` rates separately in BlindSpotArena tag tables. |
| **Acceptance** | Lower `defense-leak` share on a fixed 30-game blind-spot series; better after-opp diff vs lookahead without tanking offense metrics. |

### Mid-check / obs (blocked)

**Bonus/interrupt** (`bonus-choice`) — fix type mid-check/obs + scenarios; difficulty **M**, blocked on [AI2.12](#ai212--held-dice--mid-check-obs--scenarios). See remediation table in [`BLINDSPOTS.md`](BLINDSPOTS.md).

Work order within AI2.17: **17a → 17e → 17b** (scenarios, easy→hard), then **17c → 17d** (shaping), then bonus after AI2.12.

---

## AI2.opt — Binary observation wire

| Field | Detail |
|-------|--------|
| **Goal** | Cut remaining wire cost after the **JSON-array packed path** (done): lean `…/step\|submit/packed` already sends `obs: float[3922]` + legal matrix (Python live client is packed-only; nested JSON stays for curl / functional tests). Optional next step: raw **binary** `Content-Type` (or gRPC) for the same concat — no schema bump. |
| **Depends on** | Packed JSON-array path in use (AiObsPacker / harness `obs_flat_to_dict`). |
| **Files** | Kotlin binary writer; Python client path; shared `schemaVersion` / concat order (`OBSERVATION_VECTOR_LENGTH`). |
| **Acceptance** | Binary and packed-JSON-array paths produce identical tensors; binary is opt-in; nested JSON remains for debug/curl. |
| **Rulebook** | n/a |

**Landed (JSON-array packed):** `GET/POST …/packed`; learning `obs` concat = scalars(17) + challenges + deck + hand + heldCheck + cells[0..184]×21. Harness reward/metrics use explicit `reward` + `legalRowMeta` sidecars (not reverse-unpacked from `obs`).

---

## AI2.18 — One bonus-play action kind (retrain)

| Field | Detail |
|-------|--------|
| **Problem** | Each bonus card has its own `ActionType` (`PLAY_UNDERDOG`, …), wire kind, and `AiLegalKind`; Do-Over has three `ADJUST_*_BY_DO_OVER`. Card names leak into the action vocabulary, so a card rename touches engine, wire, AI, and Python. (Moved from public-release PR.TD8.) |
| **Goal** | `PLAY_BONUS` + `bonusPlay` factor (and one Do-Over adjust). The GUI wire already carries `bonusPlay` + `description` on every bonus choice (`BonusCardChoice`), so the frontend needs little change. |
| **Cost** | Changes `AiLegalKind` layout / `LEGAL_ACTION_KIND_COUNT` → **retrain every ONNX profile**. Existing profiles must keep working until then: do **not** land alone — bundle with the next planned layout/schema bump. |
| **Depends on** | [AI2.19](#ai219--fold-ai-action-vocabulary-investigate) write-up (same family: one Do-Over adjust kind). |
| **Acceptance** | One kind for bonus plays; card identity is a factor; card rename needs no `AiLegalKind` edit. |
| **Rulebook** | n/a |

---

## AI2.19 — Fold AI action vocabulary (investigate)

| Field | Detail |
|-------|--------|
| **Status** | **Open — optimize / investigate, not a next step.** Moved from root Quick TODO. |
| **Problem** | Schema 9’s learning contract is intentionally an **AI mapping layer** ([`AiLegalChoiceMapping`](../backend/src/main/kotlin/com/mhaefele/gridiron/api/ai/mapping/AiLegalChoiceMapping.kt) / [`AiLegalKind`](../backend/src/main/kotlin/com/mhaefele/gridiron/api/ai/dto/AiLegalKind.kt)): domain [`LegalAction`](../backend/src/main/kotlin/com/mhaefele/gridiron/api/dto/response/LegalAction.kt) + GUI wire kinds / `LegalActionId`s stay as-is. Inventory: [`ACTION_SAMPLING.md`](ACTION_SAMPLING.md). |
| **Goal** | Write-up that decides **per item**: engine `LegalAction` reshape vs keep dual vocabulary; GUI impact; id / submit-by-id; whether `ActionType` collapses stay. **Candidates to pull into engine (or shared wire):** coarse `declineOffer` (Skip / DeclineBonus / ContinueBlock / keep-reroll); single `reroll` vs keep instead of `Choose*Reroll(reroll: Boolean)`; one Do-Over adjust kind (pending already scopes throw/block/armour); free-vs-sugar factor story if GUI ever stops needing sugar on the id; hide/omit cancel-TT after select (force landing). **Keep AI-only unless product wants:** MultiDiscrete pads, `sugarFactors` sidecar, AI legal-set filters (e.g. drop DeclineBonus when OS/EP keep is already offered). Do not blindly unify — mapping is tidy for a reason. Pairs with [AI2.18](#ai218--one-bonus-play-action-kind-retrain). |
| **Acceptance** | Decision doc (or `ACTION_SAMPLING.md` section) with per-candidate keep/fold; no drive-by unify without a schema-bump plan. |
| **Rulebook** | n/a |

---

## AI2.20 — Remove local SB3 pickle aliases

| Field | Detail |
|-------|--------|
| **Status** | **Open — hygiene; not next.** |
| **Goal** | Drop the optional gitignored remap once every resume zip on disk pickles under current `gridiron_ai` module paths (one successful continue+save per zip is enough; prune stale `*_steps.zip`). |
| **Files** | `install_legacy_pickle_aliases` + call in [`__init__.py`](gridiron_ai/__init__.py); [`PICKLE_ALIASES_PATH`](gridiron_ai/paths.py); `.gitignore` entry; alias tests in [`test_checkpoints.py`](tests/test_checkpoints.py); delete local `local.pickle_aliases.properties` if present. |
| **Acceptance** | Resume works with no aliases file; no pickle-alias install path left in the package. |
| **Rulebook** | n/a |

---

## Suggested order of work

```text
AI2.0 Kotlin DTOs (contract = source)
  → AI2.1 /api/ai step HTTP (wire the DTOs; reuse create; harness never opens /events)
  → AI2.2 Python self-play (basic 4-factor actions + masks)
  → AI2.3 eval vs computer   ← keep early
  → AI2.4 unattended cycles
  → AI2.12 held dice / mid-check obs + scenarios  ← prime (reroll blind without this)
  → AI2.13 scenario catalog metadata (optional hygiene; kills Python board/claim mirror)
  → AI2.14 split train_self_play (optional hygiene; orchestration only)
  → AI2.5 multi-team
  → AI2.6 ONNX + Kotlin opponent (same DTO/packer layout)
  → AI2.7 shaping only if needed
  → AI2.8 evaluate other obs enrichment vs truncated legals (only if baseline struggles)
  → AI2.9 conditional action heads (optional)
  → AI2.10 legal move inputs & obs dict shape (after conditional baseline)
  → AI2.11 action sampling → [`ACTION_SAMPLING.md`](ACTION_SAMPLING.md) (inventory lives there)
  → AI2.16 remove /choose + scenario lookahead test
  → AI2.17 blind-spot remediation (from BlindSpotArena / BLINDSPOTS.md)
  → AI2.opt binary wire when packed JSON-array is still hot (JSON float[] path landed)
```

---

## Cross-links

| Topic | Where |
|-------|--------|
| Ownership map | [`OWNERSHIP.md`](OWNERSHIP.md) |
| Trainer design | [`DESIGN.md`](DESIGN.md) |
| Training phases / knobs | [`TRAINING_ROADMAP.md`](TRAINING_ROADMAP.md) |
| Action sampling inventory | [`ACTION_SAMPLING.md`](ACTION_SAMPLING.md) |
| Blind spots vs lookahead | [`BLINDSPOTS.md`](BLINDSPOTS.md) / [AI2.17](#ai217--blind-spot-remediation-from-arena-diagnostics) |
| Held dice / mid-check (prime) | [AI2.12](#ai212--held-dice--mid-check-obs--scenarios) |
| Scenario catalog metadata | [AI2.13](#ai213--scenario-catalog-metadata-api) |
| Split train entrypoint | [AI2.14](#ai214--split-train_self_play) |
| Remove `/choose` + scenario lookahead test | [AI2.16](#ai216--remove-ai-choose--in-process-scenario-lookahead-test) |
| Remove local SB3 pickle aliases | [AI2.20](#ai220--remove-local-sb3-pickle-aliases) |
| Root backlog pointer | [`BACKLOG.md` AI2](../BACKLOG.md#deferred--phase-ai2-trainable-model) |
| Heuristic / lookahead bots | [BACKLOG-done.md AI1](../archive/BACKLOG-done.md#ai1--basic-computer-opponent) |
| Legal actions (GUI) | `GET /api/matches/{id}/legal-actions` — **not** the AI contract |
| Lookahead leaf scorer (do not clone as RL reward) | `backend/.../opponent/lookahead/LookaheadScorer.kt` |
