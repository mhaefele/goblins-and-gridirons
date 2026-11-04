# AI2 — Trainable model (initiative backlog)

Source of truth for trainable-AI **tasks**. Root [`BACKLOG.md`](../BACKLOG.md) defers here.

**Status:** Open. Live contract is **schema 22** ([`constants.py`](gridiron_ai/constants.py)). Train/eval still HTTP packed JSON against `./dev.sh` `:18080`. AI1 shipped — [archive](../archive/BACKLOG-done.md).

**Near-term (product):** [AI2.12](#ai212--held-dice--mid-check-obs--scenarios) scenarios · [AI2.17](#ai217--blind-spot-remediation-from-arena-diagnostics) · [AI2.6](#ai26--onnx-export--kotlin-compute-opponent) regen · [AI2.5](#ai25--multi-team-curriculum).

**Hygiene:** [AI2.22](#ai222--python-typeddicts-for-harness-wire) · [AI2.14](#ai214--split-train_self_play) · optional [AI2.23](#ai223--flat-cell-table-drop-pitch-chw)→[AI2.24](#ai224--packed-box-obs-instead-of-gym-dict-slice).

**Transport optimize (own section, not next):** [Headless binary harness](#headless-binary-harness-transport) — AI2.25 → 26 → opt → 27 → 28.

**Design:** [`DESIGN.md`](DESIGN.md) · **Owners:** [`OWNERSHIP.md`](OWNERSHIP.md) · **Architecture:** [Backend § Long-term product goals](../backend/ARCHITECTURE.md#long-term-product-goals).

---

## Prime — do before more curriculum polish

- [x] **[AI2.12](#ai212--held-dice--mid-check-obs--scenarios) — Held dice in obs** (schema 11). Scenarios still open.
- [ ] **Summary:** replace pre-split `scenario_b` fallback in `_scenario_b_summary_row` with first-class export-freeze + `evals/scenario_b` (and fill `export` / `scenario_a` the same way).

---

## Checklist

### Done

- [x] [AI2.0](#ai20--learning-contract-kotlin-dtos) — Learning contract as Kotlin DTOs
- [x] [AI2.sse](#ai2sse--disable-sse-on-match-create-wont-do) — Disable SSE on match create *(won't do)*
- [x] [AI2.1](#ai21--dedicated-ai-http-api) — Dedicated AI HTTP API
- [x] [AI2.2](#ai22--python-gym--sb3-self-play) — Python gym + SB3 self-play
- [x] [AI2.3](#ai23--eval-vs-computer) — Eval vs computer
- [x] [AI2.4](#ai24--unattended-traineval-cycles) — Unattended train–eval cycles
- [x] [AI2.7](#ai27--optional-reward-shaping-day-2) — Reward shaping profiles
- [x] [AI2.9](#ai29--conditional-action-heads-follow-up) — Superseded by legal-row join scores
- [x] [AI2.13](#ai213--scenario-catalog-metadata-api) — Scenario catalog metadata API
- [x] [AI2.15](#ai215--coachcontrol-seat-semantics) — CoachControl seat semantics
- [x] [AI2.16](#ai216--remove-ai-choose--in-process-scenario-lookahead-test) — Remove `/choose`
- [x] [AI2.21](#ai221--harness-legalrows-wrap-legalaction) — `legalRows` wrap `LegalAction` *(schema 21)*

### Open — product / curriculum

- [ ] [AI2.5](#ai25--multi-team-curriculum) — Multi-team curriculum
- [ ] [AI2.6](#ai26--onnx-export--kotlin-compute-opponent) — ONNX + Kotlin opponent *(partial; regen for current schema)*
- [ ] [AI2.8](#ai28--evaluate-obs-enrichment-vs-truncated-legals) — Obs enrichment vs truncated legals
- [ ] [AI2.10](#ai210--legal-move-inputs--obs-dict-shape) — Legal-row inputs beyond mask *(dict-shape half landed)*
- [ ] [AI2.11](#ai211--action-sampling--factor-space-inventory) — Action sampling inventory → [`ACTION_SAMPLING.md`](ACTION_SAMPLING.md)
- [ ] [AI2.12](#ai212--held-dice--mid-check-obs--scenarios) — Mid-check scenarios **(prime; obs landed)**
- [ ] [AI2.17](#ai217--blind-spot-remediation-from-arena-diagnostics) — Blind-spot remediation
- [ ] [AI2.18](#ai218--one-bonus-play-action-kind-retrain) — One bonus-play action kind *(bundle with next layout bump)*
- [ ] [AI2.19](#ai219--fold-ai-action-vocabulary-investigate) — Shared wire kind on `LegalAction` *(investigate)*

### Open — hygiene

- [ ] [AI2.14](#ai214--split-train_self_play) — Split `train_self_play.py`
- [ ] [AI2.20](#ai220--remove-local-sb3-pickle-aliases) — Drop pickle-alias shim *(after resume zips re-saved)*
- [ ] [AI2.22](#ai222--python-typeddicts-for-harness-wire) — TypedDicts for harness wire
- [ ] [AI2.23](#ai223--flat-cell-table-drop-pitch-chw) — Flat cell table; drop pitch `(C,H,W)`
- [ ] [AI2.24](#ai224--packed-box-obs-instead-of-gym-dict-slice) — Packed `Box` obs

### Open — transport optimize → [section](#headless-binary-harness-transport)

- [ ] [AI2.25](#ai225--measure-harness-submit-timing) — Measure submit timing *(gate)*
- [ ] [AI2.26](#ai226--harness-episode-facade) — `HarnessEpisode` facade
- [ ] [AI2.opt](#ai2opt--binary-observation-wire) — Binary frame codec
- [ ] [AI2.27](#ai227--binary-uds-harness-headless) — Headless UDS train host
- [ ] [AI2.28](#ai228--python-binary-client--train-scripts) — Python client + train scripts

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
| **Status** | **Partial.** Kotlin path (`OnnxPolicySession.chooseRow` / obs+`legal_moves`→row index) landed; classpath artifacts lag live **schema 22** until re-export. |
| **Goal** | Freeze obs + action schema version; export trained policy to ONNX; Kotlin `OpponentStrategy` builds the same obs/mask, runs the model, submits the chosen legal action. Expose alongside heuristic/lookahead. |
| **Depends on** | AI2.3+ with a schema worth freezing; preferably AI2.4 so quality is measured. |
| **Files** | Export script under `ai/`; ONNX artifact convention; `backend/.../opponent/` model-backed strategy; match create / coach control wiring. |
| **Acceptance** | A match can seat the ONNX opponent; it only ever submits currently legal actions; smoke playthrough completes; version mismatch fails loudly. |
| **Rulebook** | n/a |

**After next ONNX regen (match `SCHEMA_VERSION`):**

- [ ] Re-export every classpath profile (`export_opponent.sh`) from a current-schema zip; drop leftover factor-logit / wrong-schema `.onnx` / manifests under `backend/.../resources/ai-opponent/`.
- [ ] Resume zips must be `LegalRowActorCriticPolicy` (Discrete) — old MultiDiscrete zips will not load.
- [ ] Smoke: match create with ONNX seat + vs-lookahead/heuristic eval; confirm manifest `schemaVersion` matches Python; hard-fail on mismatch.
- [ ] Drop temporary “broken until re-export” comments once artifacts are live ([`OnnxPolicySession`](../backend/src/main/kotlin/com/mhaefele/gridiron/game/opponent/onnx/OnnxPolicySession.kt)); manifest output is `action` (masked-argmax row index).
- [ ] Revisit [AI2.20](#ai220--remove-local-sb3-pickle-aliases) after new zips are saved under current module paths.

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
| **Status** | **Done / superseded (schema 16).** Factor MultiDiscrete remask + head-agreement diagnostics removed. Policy is `Discrete(MAX_LEGAL_MOVES)` join-score over packed legal rows (`LegalRowActorCriticPolicy`); ONNX emits the chosen row index. Soft-mask remains as a **row** logit bias. Packing widths + further representation work → [`ACTION_SAMPLING.md`](ACTION_SAMPLING.md) ([AI2.11](#ai211--action-sampling--factor-space-inventory)). |
| **Goal** | *(was)* Sample dependent factor heads instead of independent MultiDiscrete. |
| **Depends on** | — |
| **Files** | `legal_row_policy.py`, `actions.py`, Kotlin `OnnxPolicySession` / ONNX export. |
| **Acceptance** | Legal-row policy trains and evals; ONNX picks among live legal rows (after regen — [AI2.6](#ai26--onnx-export--kotlin-compute-opponent)). |
| **Rulebook** | n/a |

**Why superseded:** Independent factor heads (even with remask / conditioning) could not express “best complete move.” Scoring the legal menu jointly is the honest fix.

---

## AI2.10 — Legal move inputs & obs dict shape

| Field | Detail |
|-------|--------|
| **Goal** | **Evaluate (not presuppose)** how much legal-move information the policy and value nets should see beyond conditional masking alone — and whether the Dict observation should split the monolithic game-state vector into logical sections now that `legal_moves` / `legal_move_count` already ride as separate keys. Decide *what* to add (cheap scalars, per-kind summaries, encoded legal rows, something else) and *where* it lives (concat to features, separate encoder branch, etc.). Keep the wire/DTO contract stable unless a split forces a version bump. |
| **Depends on** | Schema 16 legal-row baseline (board features + join-score action); [AI2.3](#ai23--eval-vs-computer)+ to compare ablations. |
| **Files** | `ai/gridiron_ai/obs.py`, `env.py`, `legal_row_policy.py` / `features.py`; optional Kotlin packer / `schemaVersion` only if dict keys or flat layout change. |
| **Acceptance** | Written decision + eval evidence: either “board-only + masking is enough” or a landed approach with ablation vs the baseline on the AI2.3 suite. If splitting game state, document the new Dict keys and whether the flat packed vector (`OBSERVATION_VECTOR_LENGTH`) remains for ONNX/wire compat. |
| **Rulebook** | n/a |

**Notes**

- Distinct from [AI2.8](#ai28--evaluate-obs-enrichment-vs-truncated-legals): that item is extra *game-state* affordance hints on the DTO; this one is feeding the *legal menu* to the net and reshaping the Python obs Dict.
- **Dict-shape half (landed, schema 7):** gym keys `scalars` / `challenges` / `deck` / `hand` / `pitch` `(C,15,11)` / `benches` `(2,10,C)` + enum embeddings + pitch `(x,y,dist-to-ball)`; MLP concat, no CNN. Kotlin `AiObsDto.cells[185]` is unchanged; `schemaVersion` bumped because the gym/model packing changed. **Schema 8:** pitch-only `boardTarget` pack column; play-bonus `HandSlot` removed (identity in kind). **Schema 9:** free factors + `sugarFactors`; `declineOffer` / `reroll` / `adjustByDoOver`; drop reroll MultiDiscrete axis; omit `cancelThrowTeammate` from AI legals. Prior schema checkpoints/onnx are incompatible.
- Remaining: whether the policy/value nets should see legal-row content beyond masking; CNN vs this MLP concat (ablate later). Raw padded legal matrix into an MLP is still a candidate to reject early.
- Renaming `board` → something like `game_state` (or splitting into scalars / grid / cards / hand) is in scope for the dict-shape half of this item.

---

## AI2.11 — Action sampling & factor-space inventory

| Field | Detail |
|-------|--------|
| **Goal** | Next steps to improve **action representation & sampling** (legal-row pack columns + how we pick among rows). Checklist lives in [`ACTION_SAMPLING.md`](ACTION_SAMPLING.md#inventory). |
| **Depends on** | Schema 16 legal-row join scores in use. |
| **Files** | Checklist + factor maps + uniqueness notes: **[`ACTION_SAMPLING.md`](ACTION_SAMPLING.md)**. Implementation touches `ai/gridiron_ai/actions.py`, `constants.py`, `legal_row_policy.py`; Kotlin `AiFactor` / `AiLegalKind` / mapping as needed; `schemaVersion`. |
| **Acceptance** | Inventory in `ACTION_SAMPLING.md` triaged (done / won’t do / deferred with reason); any Yes-do item either shipped or explicitly deferred; eval notes for pack / join-score experiments (not MultiDiscrete head revival). |
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

## AI2.19 — Shared wire kind on LegalAction DTOs (investigate)

| Field | Detail |
|-------|--------|
| **Status** | **Open — optimize / investigate, not a next step.** Moved from root Quick TODO. Pointer on [`AiLegalKind`](../backend/src/main/kotlin/com/mhaefele/gridiron/api/ai/dto/AiLegalKind.kt). |
| **Problem** | [`AiLegalKind`](../backend/src/main/kotlin/com/mhaefele/gridiron/api/ai/dto/AiLegalKind.kt) is still an **AI-only** catalog mapped from [`LegalAction`](../backend/src/main/kotlin/com/mhaefele/gridiron/api/dto/response/LegalAction.kt) ([`AiLegalChoiceMapper`](../backend/src/main/kotlin/com/mhaefele/gridiron/api/ai/mapping/AiLegalChoiceMapper.kt)). GUI wire uses Jackson subtype names / [`ActionType`](../backend/src/main/kotlin/com/mhaefele/gridiron/shared/ActionType.kt) collapses separately. Schema 16 unmerged most AI coarsenings. Loud leftover: [`ChooseBlockReroll`](../backend/src/main/kotlin/com/mhaefele/gridiron/api/dto/response/LegalAction.kt) / `ChooseThrowReroll` pack **two choices in one subtype** via `reroll: Boolean` — the only mapper arms that branch on a field (`reroll` vs `keepOffer`). Inventory: [`ACTION_SAMPLING.md`](ACTION_SAMPLING.md). |
| **Goal** | Promote the catalog to a **non-`Ai*` wire kind** on `LegalAction` DTOs (GUI + AI same enum / ordinals). Prefer **splitting** keep vs reroll into distinct subtypes (or drop the boolean) so kind mapping stays 1:1. Also decide vs `ActionType` collapses / Do-Over / bonus-play identity ([AI2.18](#ai218--one-bonus-play-action-kind-retrain)). Then delete the AI-only mirror + mapper arms. **Keep AI-only unless product wants:** MultiDiscrete pads, `sugarFactors`, soft-mask filters. |
| **Acceptance** | Decision (or `ACTION_SAMPLING.md` section) + schema-bump plan before unify; no drive-by rename-only. |
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

## AI2.21 — Harness `legalRows` wrap `LegalAction`

| Field | Detail |
|-------|--------|
| **Status** | **Done (schema 21).** Follow-on: schema 22 raw pitch ints on the cell wire. |
| **Problem** | Harness rows were a factor / kind / pitch mirror of [`LegalAction`](../backend/src/main/kotlin/com/mhaefele/gridiron/api/dto/response/LegalAction.kt). Join-score only needs packed `legalMoves`; typed harness work should reuse the engine action. Reward board facts were jammed onto rows / HxW cells. |
| **Goal** | `legalRows` ≈ `{ action: LegalAction, softMaskLegal, passingLegal }`. Absolute engine coords on `action`; packer (+ obs) own perspective / 0-based. Harness `rewards.players` + single `ballCoordinate` (null if none on pitch; Kotlin fails if >1). Join by `playerId` / absolute coords — no pitch-index remap, no `hasBall` on every player. |
| **Depends on** | Schema-19 dest xy committed. Pairs with [AI2.19](#ai219--fold-ai-action-vocabulary-investigate). |
| **Files** | `AiLegalChoiceDto` / `HarnessRewardSnapshotDto` / `AiGameStateMapper`; Python `reward.py` / `eval_debug.py` / `env.py` / `client.py` / `harness_wire.py`; schema 21. |
| **Acceptance** | Nested step `legalRows` = LegalAction + AI flags only; rewards = players + ballCoordinate; reward/eval/metrics join absolute coords; pack parity tests green. |
| **Rulebook** | n/a |

---

## AI2.22 — Python TypedDicts for harness wire

| Field | Detail |
|-------|--------|
| **Status** | Beachhead started (`harness_wire.py`); expand call sites. |
| **Problem** | Harness JSON is threaded as bare `dict[str, Any]` — readers cannot tell rewards snapshot vs LegalAction vs legal row vs step envelope. |
| **Goal** | Named `TypedDict` (or equivalent) aliases matching Kotlin harness DTOs (`HarnessRewardSnapshotDto`, `HarnessPlayerDto`, `AiLegalChoiceDto` / `LegalAction`, packed step/submit). Runtime can stay dict; the **name** is the point. Prefer one module (`harness_wire.py`) over scattering. |
| **Depends on** | [AI2.21](#ai221--harness-legalrows-wrap-legalaction) (done). |
| **Files** | `gridiron_ai/harness_wire.py`; call sites in `reward.py` / `env.py` / `client.py` / `eval_debug.py` / `actions.py`. |
| **Acceptance** | Public helpers annotate harness payloads with named types; `dict[str, Any]` not used as the primary name for those shapes; mypy/pyright optional but types importable. |
| **Rulebook** | n/a |

---

## AI2.23 — Flat cell table; drop pitch `(C,H,W)`

| Field | Detail |
|-------|--------|
| **Status** | Open — optional hygiene toward stock SB3 (no CNN). |
| **Problem** | Gym Dict keeps `pitch` as `(C,H,W)` for a spatial layout we do not convolve. Feature extractor already treats cells as a table; H×W is ceremony and duplicates the linear pack index space. |
| **Goal** | Observe all board slots as one table `(BOARD_SLOT_COUNT, C)` (or pitch `(165,C)` + benches `(2,10,C)` without H×W). Drop transpose / `(C,H,W)` in `obs.py` / `export_onnx.py` / `features.py`. |
| **Depends on** | Schema 22 cell channels settled. Bundle with a retrain / schema bump if gym Dict shapes change. |
| **Files** | `obs.py`, `features.py`, `export_onnx.py`, `constants.py`; tests that hard-code pitch shape. |
| **Acceptance** | No `(C,H,W)` pitch in the gym Dict; extractor encodes a flat cell table; ONNX slice matches; train/eval green after retrain. |
| **Rulebook** | n/a |

---

## AI2.24 — Packed `Box` obs instead of gym Dict slice

| Field | Detail |
|-------|--------|
| **Status** | Open — optional; closer to ONNX / stock Box envs. |
| **Problem** | Kotlin packs one float vector; Python slices into a multi-key Dict for readability. ONNX already consumes the packed vector. Dict slicing + key plumbing is overhead once cells are a flat table and there is no CNN. |
| **Goal** | Primary game-state obs = `Box(OBSERVATION_VECTOR_LENGTH)` (plus padded `legal_moves` / masks as today). Feature extractor indexes enum/numeric channels by `AiObsLayout` offsets instead of Dict keys. Optionally keep a debug Dict view for humans, not for the policy. |
| **Depends on** | [AI2.23](#ai223--flat-cell-table-drop-pitch-chw) (or do both together). Retrain / schema bump. |
| **Files** | `obs.py`, `features.py`, `env.py`, `export_onnx.py`, `legal_row_policy.py`, clone/expert paths. |
| **Acceptance** | Policy train/predict/ONNX share one packed obs tensor layout; Dict slice not on the hot path; MaskablePPO still works with legal-row Discrete + masks. |
| **Rulebook** | n/a |

**Keep (not todos):** `nn.Embedding` for enum channels and `LegalRowActorCriticPolicy` join-score — those are the product architecture, not leftover glue.

---

## Headless binary harness (transport)

**Status:** Open — **optimize, not next.** Do not start until [AI2.25](#ai225--measure-harness-submit-timing) says pack/wire/contention is worth it (or the gate is explicitly skipped).

Faster PPO rollout by dropping localhost HTTP+JSON for train, without changing rules, obs packing ownership, or the SB3 harness. Product GUI stays on today’s HTTP game host.

### Intent

| Today | Target |
|-------|--------|
| 30 `SubprocVecEnv` workers → one Spring Boot on `:18080` | Same workers → **headless** Boot over **Unix-domain socket** |
| One `POST …/submit/packed` JSON per policy step | Length-prefixed **binary** frames (same tensors) |
| Opponent already in-process on `COMPUTER` | Unchanged |
| Expert dump is headless one-shot (`--export-expert`) | Train host is headless **long-lived** UDS server (same Boot family, different entry) |

**Two launch shapes (intentional):**

1. **Game host** — `./dev.sh` HTTP `:18080` (GUI, curl, play-vs-human). Unchanged.
2. **Train host** — headless Boot (`web-application-type=none`) + UDS. Train/eval scripts start or require this; no dependency on `:18080`.

**Shared code, two boots:** extract [AI2.26](#ai226--harness-episode-facade) so HTTP controllers and the UDS acceptor call one episode implementation (no grading fork). Catalog stays Kotlin-owned ([OWNERSHIP](OWNERSHIP.md) / no mirrored enums).

**Why headless (not UDS on the game host):** train saturates the JVM with ~30 env workers; Tomcat, SSE hub, and human-match TTL are tax. Separate process = own heap/GC/threads, no port fight with `:18080`, stop/start train without taking down the GUI. Expert export already proved headless Boot — incremental work is “bind UDS + block,” not a second Spring graph. Optional later dual-listen on the game host is debug-only, not the train default.

**Abandoned for this track:** JPype/JNI embed; rewriting the SB3 loop in Kotlin; making UDS-on-`./dev.sh` the primary train path.

### Expected speed (order of magnitude)

Conditional on [AI2.25](#ai225--measure-harness-submit-timing). If `lookahead_ms` dominates full-game submits, park this section and change opponent/search instead.

| Change | Scenario drills (little handoff) | Full game vs LOOKAHEAD |
|--------|----------------------------------|------------------------|
| Binary codec ([AI2.opt](#ai2opt--binary-observation-wire)) | ~1.2–2× if JSON/parse hot | ~1.05–1.2× |
| + headless UDS ([AI2.27](#ai227--binary-uds-harness-headless)) | ~1.3–2.5× vs today’s HTTP+JSON | ~1.1–1.3× |
| + multi harness JVMs (later) | ~2–5× if one process saturates | ~1.5–3× |

### Work order

`AI2.25` (gate) → `AI2.26` (facade) → `AI2.opt` (codec) → `AI2.27` (headless UDS) → `AI2.28` (Python + scripts). Codec + listener may land in one PR if small.

### AI2.25 — Measure harness submit timing

| Field | Detail |
|-------|--------|
| **Status** | **Open — gate for this section.** |
| **Goal** | Decide whether wire/contention work is worth it. Capture [`AiSubmitTiming`](../backend/src/main/kotlin/com/mhaefele/gridiron/api/ai/AiSubmitTiming.kt) INFO batches (`next_legals_ms`, `engine_ms`, `turnWait_ms`/`lookahead_ms`, `pack_dto_ms`, `pack_flat_ms`) and Python `submit_avg_ms` on (1) scenario drills and (2) a LOOKAHEAD full-game eval. Note pack+HTTP vs search share (comment here or DESIGN footnote). |
| **Depends on** | None (timing already logs every 400 submits). |
| **Files** | Ops note; optional tiny summarizer for timing lines. |
| **Acceptance** | Go/no-go: proceed if pack/wire/contention is roughly **≥ ~30%** of scenario `submit` time; else park the section. |
| **Rulebook** | n/a |

### AI2.26 — HarnessEpisode facade

| Field | Detail |
|-------|--------|
| **Status** | **Open.** |
| **Goal** | Extract the synchronous harness loop from [`AiMatchController`](../backend/src/main/kotlin/com/mhaefele/gridiron/api/ai/AiMatchController.kt) (create / wait-for-seat / pack step / submit / pack next / delete + scenario create) into a Spring `@Service` (`HarnessEpisode` / `AiHarnessService`) that HTTP controllers **and** the UDS listener both call. No rule changes; keep `AiSubmitTiming` hooks. |
| **Depends on** | [AI2.25](#ai225--measure-harness-submit-timing) go (or explicit skip). |
| **Files** | New service under `api/ai/`; thin `AiMatchController` / `TrainingScenarioController`; facade-only integration test (parity with packed step/submit). |
| **Acceptance** | Controllers are thin delegates; facade test creates a scenario episode and submits without HTTP; GUI HTTP behavior unchanged. |
| **Rulebook** | n/a |

**Do not:** invent a second grading path.

### AI2.opt — Binary observation wire

| Field | Detail |
|-------|--------|
| **Status** | **Open.** Codec for [AI2.27](#ai227--binary-uds-harness-headless); HTTP binary optional debug/compat. |
| **Goal** | Length-prefixed binary frame for the same concat as packed JSON (`obs` float vector + legal matrix + compact legalRows / reward sidecars) — **no** obs `schemaVersion` bump. Transport-agnostic encode/decode. |
| **Depends on** | Packed JSON path in use; prefer AI2.25 evidence; AI2.26 so packers are not controller-only. |
| **Files** | Kotlin binary writer/reader; Python decode; shared layout via `OBSERVATION_VECTOR_LENGTH` / current constants. |
| **Acceptance** | Binary ≡ packed-JSON tensors; nested JSON remains for curl/tests; codec has no HTTP/UDS dependency. |
| **Rulebook** | n/a |

**Landed (JSON-array packed):** `GET/POST …/packed`; obs length tracked in `constants.py` / Kotlin packer. Reward/metrics use explicit sidecars (not reverse-unpacked from `obs`).

### AI2.27 — Binary UDS harness (headless train host)

| Field | Detail |
|-------|--------|
| **Status** | **Open.** |
| **Goal** | Long-lived headless Boot (`web-application-type=none`, same family as `--export-expert`) binds a Unix-domain socket and serves binary RPC via AI2.26. Leave HTTP game host untouched. |
| **Depends on** | AI2.26; AI2.opt (same PR OK if small). |
| **Files** | Harness CLI / flag (e.g. `--harness-socket=…`); socket acceptor + framing → facade; readiness path for scripts. |
| **Acceptance** | UDS create/step/submit/catalog tensors match HTTP packed on the game host; DESIGN unlocked from “Training loop = HTTP only” to “train = binary UDS headless host; GUI = HTTP game host”; multi-JVM shard only if one harness saturates later. |
| **Rulebook** | n/a |

**Out:** UDS on the GUI JVM as primary train path; turning `--export-expert` into the train server (dump stays one-shot).

### AI2.28 — Python binary client + train scripts

| Field | Detail |
|-------|--------|
| **Status** | **Open.** |
| **Goal** | [`GridironClient`](gridiron_ai/client.py) / env speak binary UDS (Gym loop unchanged). **Train/eval** start or require the headless harness; **play-vs-human / GUI** keep requiring `./dev.sh`. Clear errors when the wrong host is up. |
| **Depends on** | [AI2.27](#ai227--binary-uds-harness-headless). |
| **Files** | Client transport; `env.py` kwargs; [`dev_stack.py`](gridiron_ai/dev_stack.py) (socket vs HTTP health); [`common.sh`](common.sh); [`train.sh`](train.sh) / [`eval.sh`](eval.sh); harness start helper (mirror expert-export `mvn spring-boot:run` / web none); [`README.md`](README.md); [`OWNERSHIP.md`](OWNERSHIP.md). Catalog: UDS `catalog` RPC preferred (no HTTP dependency for train); server still owns membership. |
| **Acceptance** | `./train.sh` works without `:18080`; play-vs-human still documents `./dev.sh`; failure text names which host to start; no mirrored scenario enum lists in Python. |
| **Rulebook** | n/a |

---

## Suggested order of work

```text
Product / curriculum (current)
  → AI2.12 mid-check scenarios (obs landed; prime)
  → AI2.17 blind-spot remediation (17a → 17e → 17b → 17c → 17d)
  → AI2.6 ONNX regen to schema 22 + smoke
  → AI2.5 multi-team
  → AI2.8 / AI2.10 / AI2.11 only if baseline still struggles
  → AI2.18 + AI2.19 with next layout/schema bump

Hygiene (anytime, low priority)
  → AI2.22 TypedDicts · AI2.14 split train_self_play · AI2.20 pickle aliases
  → AI2.23 → AI2.24 stock-layout (optional; needs retrain)

Transport optimize (after AI2.25 go/no-go) — see section above
  → AI2.25 → AI2.26 → AI2.opt → AI2.27 → AI2.28
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
| Headless binary harness (transport) | [section](#headless-binary-harness-transport) |
| Split train entrypoint | [AI2.14](#ai214--split-train_self_play) |
| Remove local SB3 pickle aliases | [AI2.20](#ai220--remove-local-sb3-pickle-aliases) |
| Root backlog pointer | [`BACKLOG.md` AI2](../BACKLOG.md#deferred--phase-ai2-trainable-model) |
| Heuristic / lookahead bots | [BACKLOG-done.md AI1](../archive/BACKLOG-done.md#ai1--basic-computer-opponent) |
| Legal actions (GUI) | `GET /api/matches/{id}/legal-actions` — **not** the AI contract |
| Lookahead leaf scorer (do not clone as RL reward) | `backend/.../opponent/lookahead/LookaheadScorer.kt` |
