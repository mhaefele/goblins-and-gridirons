# Training scenarios

Curriculum drills: scripted board placement, opening-legals validation, expected-move
flagging (`expectedScenarioMove`), and episode grading (`TrainingDrillStatus`). Kotlin
owns all of this; the Python harness consumes difficulty and catalog metadata only.

**Code:** `backend/src/main/kotlin/com/mhaefele/gridiron/api/ai/training/`  
**Harness / curriculum:** [`ai/OWNERSHIP.md`](../ai/OWNERSHIP.md) · [`ai/TRAINING_ROADMAP.md`](../ai/TRAINING_ROADMAP.md)  
**HTTP:** `GET /api/ai/training/scenarios`, `POST` scenario match creation via `TrainingScenarios.openScenario`

---

## Why scenarios exist

Self-play alone was not giving the policy enough exposure to the things we care about.
Scenarios are **targeted lessons**: synthetic boards that oversample rare or confusing
situations so the model sees them often enough to learn.

**What we are trying to teach**

| Theme | Why scenarios |
|-------|----------------|
| **Challenge claims** | Many claim conditions never appear often enough in random games; claim drills pin a focus card and scatter filler cards that must not leak onto opening legals. Episodes open on forced Pre-Turn replace (keep focus / Clean Sweep trio; discard bait) then the claim line. |
| **Touchdowns** | TD paths were too sparse in regular play; board drills (marked run, throw to end zone, strip-and-score, etc.) put the scoring line in reach on the opening decision. |
| **What the ball means** | Possession state drives almost everything: ball on **our** player, **loose** on the pitch, or on an **opponent** carrier. Different drills isolate each case so the model learns distinct responses (run to score, run to pick up, block/strip/foul) instead of treating “ball visible” as one blob. |
| **Deny opponent** | Blind-spot defense: `DENY_TD`; gated claim denies `DENY_STRIP_IT`, `DENY_DROP_THEM`; exposure-only `DENY_PICKUP`, `DENY_LOCK_THEM_DOWN` (same lookahead test, no PASS assert). |
| **Challenge replace** | Blind-spot `tableau-replace`: claim drills teach keep-set on their own boards; standalone `CHALLENGE_REPLACE` remains exposure-only (random trio; not a gate). |

**The core tension**

The goal is **as much placement variance as we can afford** — different geometries,
rosters, difficulty scatter, claim tableau fillers — without accidentally:

1. **Breaking the lesson** — opening legals do not include the move we meant to teach
   (placement retries help, but high difficulty and weak avoid zones make this fragile).
2. **Offering a better play** — the board is valid, but a different legal is objectively
   stronger than the drill’s “right” answer, so training signal fights game sense.
3. **Lying about the rule** — soft-mask / `expectedScenarioMove` encode a fixed ritual
   (e.g. Show Them How v1: always Run → Mark → Block) when the card only cares about
   three distinct action types by one player. The model learns the script, not the claim.

Every new random dimension (extra reserves, variant generators, scatter order) trades
coverage for that risk. Validation + retries are the safety net, not proof the lesson is
unique.

**Grading matches the lesson, not always the outcome**

Some drills PASS on **attempt** because the point is the *decision*, not dice resolution
— e.g. claim throws/blocks (`succeedsOnAttempt`), throw target selection, or
`LOOSE_BALL_BOUNCE` (opponent adjacent to the loose ball → pickup bounces). Layout puts
the ball on a TD path with a guard; the runner is **not** guaranteed adjacent for an
opening block. **No** `passingLegal` / soft-mask / expected opening — exposure board
only (proxy of `LOOSE_BALL_RUN`). Do not grade empty-EZ jogs or invent a block lesson.

**Where this is headed (and where we want to stop)**

- **Split crowded drills** — e.g. Show Them How could become several scenarios keyed by
  which action types and order we want (run→mark→block vs throw→foul→stand-up), instead
  of one drill with five random geometry variants and a fixed action-type checklist.
- **Negative lessons** — boards where the *right* answer is “you cannot reach the end
  zone because of X/Y/Z” (marks, range, guards). We do not have many of these yet; they
  are the natural complement to TD oversampling.

Be honest: most of this catalog is an **exhausting reaction to the model learning poorly**
on sparse real-game experience. The hope is that as the baseline improves, we need fewer
special-case boards and less placement machinery — not an ever-growing scenario zoo.
Until then, this doc tracks the compromises we already baked in.

---

## Adding a drill

1. Add a `TrainingScenarioEnum` value.
2. Add a `@Component` extending `AbstractTrainingScenario` (or `AbstractChallengeClaimTrainingScenario` / `AbstractHeldCheckTrainingScenario` / `AbstractLooseBallTrainingScenario`).
3. Implement `buildBoard`, `validateBoard`, and usually `isExpectedOpeningMove` (or claim/bounce overrides).
4. Set catalog knobs on the bean when needed: `family`, `difficultyProxy`, `gatesProgress` (see below).
5. Add placement and/or integration tests.

Train startup refreshes `GET /api/ai/training/scenarios` into the Python catalog cache — new names start at difficulty 0. Do **not** mirror enum lists in Python or the frontend.

---

## Placement pipeline

`TrainingScenarios.openScenario` (up to 64 silent retries):

1. `buildBoard(setup, trainerSide, difficulty)` — deploy lesson pieces; scatter difficulty extras.
2. Attach `TrainingEpisodeConfig` (scenario, difficulty, trainer side, opening turn).
3. `configureEpisode` — tableau, turn number, fake prior actions, etc.
4. Crank to first trainer decision; optional `actionToPrime` + `placementDiceScript` (held-check drills).
5. `validateBoard` — trainer acts first with the intended opening legals.

`TrainingBoardBuilder` places from reserves without domain events. Claim drills call
`markScatterAvoid` on lesson squares (+ neighbors) so difficulty scatter does not leak
adjacency.

---

## Difficulty (0–5)

Difficulty caps how many **extra** reserves scatter per side **after** scripted lesson
pieces. At 0, only lesson pieces are on the pitch.

| Family | Scatter helper | Order |
|--------|----------------|-------|
| Board (default) | `AbstractTrainingScenario.scatterDifficultyExtras` | opponent → trainer |
| Claim | `AbstractChallengeClaimTrainingScenario.buildBoard` | trainer → opponent |

Claim scatter also merges lesson `scatterAvoidZones()` and post-trainer `onPitchScatterAvoid(trainerSide)` so opposition extras cannot crowd lesson pieces.

**TODO:** Unify board vs claim scatter order and avoid-zone policy (one helper, same
semantics). Tracked in `scatterDifficultyExtras` and claim `buildBoard`.

Board drills without explicit avoid sets (e.g. `STRIP_AND_SCORE`) may place extras beside
lesson pieces at high difficulty; validation retries usually recover, but placement is
noisier than claim drills.

---

## Catalog metadata (Python harness)

Exposed on each bean → `GET /api/ai/training/scenarios`:

| Field | Meaning |
|-------|---------|
| `family` | `BOARD` or `CLAIM` — unlock gate taxonomy |
| `difficultyProxy` | When set, difficulty JSON is stored/read under the proxy scenario (e.g. `LOOSE_BALL_BOUNCE` → `LOOSE_BALL_RUN`). The harness **skips scenario-specific after-eval** on the proxy drill; difficulty tracks the target instead. **Requires** `gatesProgress=false`. |
| `gatesProgress` | When `false`, drill still trains, evals, and climbs d0→d5, but does **not** count toward curriculum unlock gates. |

### Optional drills (`gatesProgress = false`)

We still want these in the training mix — the policy should see the situations — but we do **not** yet trust them to gate curriculum progress. Typical reasons:

1. **Oracle gap** — we do not know the right expert/lookahead moves well enough to grade after-eval fairly (multi-step claim lines, contested loose-ball geometry, etc.).
2. **Placement trust** — scatter/placement can still produce boards we cannot guarantee are solvable under the current eval path, even after validation retries.

Optional drills train and bump local difficulty on perfect eval like any other drill; they simply do not block lead-pack, claim unlock, scenario_a/b, or full-game gates.

**Invariant:** any drill with `difficultyProxy` must also set `gatesProgress=false` (enforced at Spring wiring). Proxy drills opt out of scenario eval *and* gating for the same underlying reason — we are not confident we are measuring the model accurately on that drill yet.

**Current optional (non-proxy):** `CLAIM_STAR_TURN`, `CLAIM_CLEAN_SWEEP`, `CHALLENGE_REPLACE`, `DENY_LOCK_THEM_DOWN`, `DENY_PICKUP` — train for exposure; gating off until oracle is trustworthy (`lookaheadSolveEval=false`). Gated claim denies: `DENY_STRIP_IT`, `DENY_DROP_THEM`.

**Lookahead solve off, still curriculum-gated:** `CLAIM_KEEP_RUNNING`, `CLAIM_ALL_HANDS` — soft-mask trains; fat LA is not a reliable PASS oracle on every difficulty yet.

**Current optional (proxy):** `LOOSE_BALL_BOUNCE` → `LOOSE_BALL_RUN` — trains bounce boards; difficulty and eval ride on run. Proxy train uses score-margin (+ profile shaping) only — no curriculum ± / match or scenario terminals.

---

## Episode shape

### Coach-turn window

`scenarioCoachTurnWindow(difficulty)`:

- **d0–d3:** 1 coach turn (opening trainer turn only).
- **d4–d5:** 6 coach turns (opponent can reply; more board context).

### Grading window vs play window

`drillStatus` only promotes **PASS on the episode opening coach turn**
(`openingTurnNumber`, trainer active). Later window turns never upgrade a miss to PASS;
coach-window timeout without PASS grades **THEM** (`TrainingEpisodeEnd`).

**One-shot drills** (`expectedBudgetedActions == 1`): freeze opening PASS action ids on the first
player-action menu; later budgeted actions re-flag an id only when it is still legal **and** the
opening soft-mask / PASS predicate still holds (throw-then-ball-less EZ run keeps the id but
fails). Soft-mask / PASS packing skips once `drillStatus` is no longer `STILL_IN_PROGRESS`.
Multi-step drills keep their own follow-up overrides.

### Claim-specific

- Episodes open on **turn 3** (`ChallengeClaimTableau.OPENING_TURN`) — past the turn-1 claim gate, still a full three-action turn.
- **Forced replace first:** focus (or Clean Sweep keepables) + baits in the tableau; one extra bait (or missing trio title) on deck-top. Soft-mask discards the bait only; PASS stays on the claim line after.
- Multi-action claims override `expectedBudgetedActions` and follow-up soft-mask.
  On **TRAIN** claim follow-up player-action menus (`expectedBudgetedActions > 1`, after the
  first budgeted action), soft-mask prefers root legals from which [DeterministicActionSearch]
  can still reach PASS before end of turn (adapter [TrainingSolvableMoveSearch]). Opening select
  keeps scenario heuristic soft-mask (wider, cheaper). Shared apply is `DryRunApply` only —
  **no** lookahead ranking / prune / focused deepen. Forced replace keeps heuristic bait
  soft-mask; empty search falls back to heuristic. Non-claim multi-step drills keep scenario
  soft-mask.
- **Emergency Reserves:** sparse claim boards would arm Pre-Turn ER after replace (not the lesson). `TrainingScenarios.parkForcedReplace` raises both ER thresholds to 99 so the engine never queues it — scenario setup only, not a TurnEngine special case.
  - **TODO:** Retire that ER threshold override once claim boards keep dugout below real team ER.
- **Ball in play:** no-ball claim lessons still open mid-game — setup places a far non-end-zone loose ball before keep-set assembly so Pre-Turn NEW_BALL is a no-op (real “ball already on the pitch”) and Grab It stays out of the bait pool.

### Board tricks

- **Throw open receiver / closer range:** `configureEpisode` injects a prior `Run` into `actionsThisTurn` so the episode opens on the throw decision.
- **Held-check drills** (`AbstractHeldCheckTrainingScenario`): `actionToPrime` plays one budgeted action during placement; `placementDiceScript` binds deterministic dice for crank + prime.

---

## Grading semantics (`drillStatus`)

Default board behavior: **PASS** on opening-turn touchdown or flagged `expectedScenarioMove`
(for one-shot drills: that flagged move on any of the three budgeted actions).

Several drills intentionally PASS on **attempt**, not full resolution:

| Pattern | Examples |
|---------|----------|
| `succeedsOnAttempt = true` | Claim dice drills: `CLAIM_CONNECT`, `CLAIM_DROP_THEM`, `CLAIM_SEND_THEM_OFF`, `CLAIM_THREAD_THE_NEEDLE`, `CLAIM_STRIP_IT` — right flagged move passes even if throw/block fails to bank the card |
| Custom `drillStatus` | `THROW_OPEN_RECEIVER`, `THROW_CLOSER_RANGE` — correct target/range, not catch; `LOOSE_BALL_BOUNCE` — no PASS-grade opening (exposure / proxy only) |
| Run-only TD | `TD_RUN`, `MARKED_RUN`, `LOOSE_BALL_RUN` (d2–d5) — PASS only when the scoring submit is a `Run`; non-Run TDs → `DISTRACTED` |
| Pickup one-shot | `LOOSE_BALL_RUN` **d0–d1** — PASS on first-step pickup, THEM otherwise; episode ends after that submit (no continue-to-score) |
| Bank-all-three | `CLAIM_CLEAN_SWEEP` — PASS only when all trio cards are in hand |

Curriculum winrate can climb while physical resolution still fails. That is deliberate
for dice-dependent claims; revisit if eval should require banking.

---

## Show Them How (`CLAIM_STAR_TURN`)

Card text: one player finishes **three distinct budgeted actions** on the same turn; claim
on the third. The drill must teach *that rule*, not a memorized action script.

### Anti-pattern: fixed action order

The first version scripted **Run → Mark → Block** every time: opening had to be a short
`Run`, then `isExpectedFollowUpMove` forced `Mark`, then `Block`. Soft-mask and
`expectedScenarioMove` flagged only that sequence. From the model's perspective the lesson
was “always do those three in that order to claim Show Them How” — not “three different
action types by one player, in whatever order the board allows.”

That is exactly the failure mode of **variance that lies**: placement looked varied, but
the training signal was a single ritual.

### Current compromise

- Soft-mask **and** PASS flag the star’s unused action types on **every** budgeted step
  (opening + follow-ups), from `SHOW_THEM_PLAYER_ACTIONS` (any order among distinct types).
- Placement picks one of five random geometry variants (`ClaimBoardPhrases.StarTurnVariant`).

Better than a fixed script, still imperfect: the allowed-type set is hard-coded, and each
variant only makes some action types legal — so the model still sees a narrow menu, not
“all legal ways to spend your third distinct type.”

### Target shape

- **Dynamic narrowing:** at each step, flag every legal star move whose action type is not
  already used this turn; do not maintain a parallel checklist the board did not create.
- **Or split scenarios:** one drill per intended action triple (or per first action type),
  with placement that reliably opens the others — instead of one drill × random geometry ×
  implicit sequence bias.

See **Why scenarios exist** for the general variance-vs-signal tension.

---

## Tests

- **Integration:** `ChallengeClaimTrainingScenarioTest`, `TrainingScenarioApiTest` — live dice with retries.
- **Unit:** scenario-specific `drillStatus` / placement tests where integration is too heavy.
- Claim difficulty scatter: `claim difficulty scatters extras after the lesson pieces` (d0/d3/d5).

---

## Known follow-ups

| Item | Notes |
|------|-------|
| Unify scatter order | Board vs claim (see Difficulty) |
| Show Them How | Fixed Run→Mark→Block script (v1); now any-order distinct types but hard-coded menu — needs dynamic legals or per-sequence scenarios |
| Negative-response drills | End zone blocked / unreachable for stated reasons (marks, range, guards) |
| `succeedsOnAttempt` claim grading | `ChallengeClaimDrillStatusTest` TODO — restore API-level dice test or delete if trivial |
| Per-variant Show Them How coverage | Five placement variants; integration tests rely on random placement + retries (no production test hooks) |
| Shrink the catalog | As baseline play improves, retire drills that self-play already covers |
