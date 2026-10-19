# Goblins & Gridirons Engine — Implementation Backlog

Open work only. Completed phases, polish, and closed items live in [archive/BACKLOG-done.md](archive/BACKLOG-done.md) (Phases 0–15, UX/GFX, RF.1–RF.6, BG.1, PA.* / PA.C / PA.F / D2, PM.0–PM.6, D1.A–H / D1.C-fix, D3, AI1 incl. AI1.6, FF.*, Task 7.12, TD.1–8 / TD.10, 2026-08 pending-queue / bonus / shake-loose). [FF.8](archive/BACKLOG-done.md#ff8--pitch--join-chrome-punch-up-wont-do) / [F.5](archive/BACKLOG-done.md#f5--openmarked-on-piece-snapshot-and-events-wont-do) / [F.8](archive/BACKLOG-done.md#f8--gui-openmarked-rings-wont-do) / [TD.9](archive/BACKLOG-done.md#td9--event-driven-turn-and-progress-updates-wont-do) won't do.

**Architecture:** [System](ARCHITECTURE.md) · [Backend](backend/ARCHITECTURE.md) · [Frontend](frontend/ARCHITECTURE.md)

**Rules:** [`reference/rulebook-digest.md`](reference/rulebook-digest.md) (agent-readable). Cards: [`reference/cards.md`](reference/cards.md).

**Engine module:** Kotlin/Spring sources and `pom.xml` live under [`backend/`](backend/). Run `mvn` from `backend/`.

---

## How to use this backlog

- Check off tasks as completed: `- [x]` — then move them to [archive/BACKLOG-done.md](archive/BACKLOG-done.md).
- **Status:** Open work below: [Quick TODO](#quick-todo), [SP — single nullable pending](#sp--single-nullable-pending-decision-fsm), remnant [Task 7.0](#task-70--test-fixture-builders) / [7.13](#task-713--better-api--domain-test-style), [Future items](#future-items-base-game-later) (F.6 / F.9), [parking lot](#parking-lot-unlikely-soon), deferred [D4](#deferred--phase-d4-optional-rules) / [AI2](#deferred--phase-ai2-trainable-model) ([`ai/BACKLOG.md`](ai/BACKLOG.md)).
- **Frontend** consumes the done backend contract; never re-implements rules. See [frontend architecture](frontend/ARCHITECTURE.md).
- Tasks marked **DEFERRED** are out of scope for the base playable game.
- **Future items** are still base-game rules/UX, but blocked on wire or product decisions we do not have yet.
- **Rules source of truth for fidelity:** [`reference/rulebook-digest.md`](reference/rulebook-digest.md). Card text still wins when encoded. Card and team text lives in the Kotlin catalogs ([`reference/cards.md`](reference/cards.md) mirrors them).
- **When the user asks what's next:** prefer the [Quick TODO](#quick-todo), [SP](#sp--single-nullable-pending-decision-fsm), a [Future item](#future-items-base-game-later), [Task 7.13](#task-713--better-api--domain-test-style), or [D4](#deferred--phase-d4-optional-rules) / [AI2](#deferred--phase-ai2-trainable-model) ([`ai/BACKLOG.md`](ai/BACKLOG.md)) — not re-litigate archived phases.

### Task template

| Field | Meaning |
|-------|---------|
| **Goal** | What to implement |
| **Depends on** | Prerequisite task IDs |
| **Files** | Primary files to create/modify |
| **Acceptance** | Testable done criteria |
| **Rulebook** | PDF page/section |

---

## Quick TODO

Scratch list — not phased work. Prefer these when picking up engine cleanup.

- [ ] **Card spends follow `requiredBonusCard`.** That field means the submitted action spends a card; [`RegularPlayerActionResolver`](backend/src/main/kotlin/com/mhaefele/gridiron/game/action/pending/regular/RegularPlayerActionResolver.kt) already plays it on submit. Every other `playBonusCard` still spends the card because the action type says so. A `TODO` marks each one: Dead Eye, Hail Mary, and throw Do-Over; Brace, Underdog, and block Do-Over; Spiked Gauntlets and armour Do-Over; Sprint and Cut Off; Slip Away, Momentum, and All-In Tackle; Stick Close; Back on Your Feet; Fresh Legs; Extra Effort, Hold the Line, and Gang Up. Make each spend a required card and follow that path, or decide the pause stays and delete the TODO.
- [ ] **CoachControl seat semantics (human vs harness vs spawned computer)** — see [AI2.15](ai/BACKLOG.md#ai215--coachcontrol-seat-semantics). Expert dumps should not fake `HUMAN`; only `COMPUTER` starts an in-process opponent.
- [ ] **Investigate folding AI action vocabulary into the main engine (some/all).** Schema 9’s learning contract is intentionally an **AI mapping layer** ([`AiLegalChoiceMapping`](backend/src/main/kotlin/com/mhaefele/gridiron/api/ai/mapping/AiLegalChoiceMapping.kt) / [`AiLegalKind`](backend/src/main/kotlin/com/mhaefele/gridiron/api/ai/dto/AiLegalKind.kt)): domain [`LegalAction`](backend/src/main/kotlin/com/mhaefele/gridiron/api/dto/response/LegalAction.kt) + GUI wire kinds / `LegalActionId`s stay as-is. Inventory: [`ai/ACTION_SAMPLING.md`](ai/ACTION_SAMPLING.md) (Yes-do). **Candidates to pull into engine (or shared wire):** coarse `declineOffer` (Skip / DeclineBonus / ContinueBlock / keep-reroll); single `reroll` vs keep instead of `Choose*Reroll(reroll: Boolean)`; one Do-Over adjust kind (pending already scopes throw/block/armour); free-vs-sugar factor story if GUI ever stops needing sugar on the id; hide/omit cancel-TT after select (force landing). **Keep AI-only unless product wants:** MultiDiscrete pads, `sugarFactors` sidecar, AI legal-set filters (e.g. drop DeclineBonus when OS/EP keep is already offered). **Write-up should decide** per item: engine `LegalAction` reshape vs keep dual vocabulary; GUI impact; id / submit-by-id; whether `ActionType` collapses stay. Do not blindly unify — mapping is tidy for a reason.
- [ ] **LegalAction Jackson `kind` from the object (`EXISTING_PROPERTY` / `@JsonTypeId`).** Today [`LegalAction`](backend/src/main/kotlin/com/mhaefele/gridiron/api/dto/response/LegalAction.kt) uses `@JsonTypeInfo(As.PROPERTY)` + `@JsonSubTypes` names — Jackson invents `"kind"` from annotations; the Kotlin type only exposes `actionType` (which collapses some wire kinds). Prefer each variant owning its kind (e.g. `val kind` / getter) with `As.EXISTING_PROPERTY` or `@JsonTypeId` so the object is the source of truth and stays aligned with [`AiLegalKind`](backend/src/main/kotlin/com/mhaefele/gridiron/api/ai/dto/AiLegalKind.kt) wire names. Keep GUI JSON compatible.
- [ ] **Submit-by-id: targeted legal-move resolve (no full regen).** When applying a `LegalActionId` (GUI or AI harness reusing `POST .../legal-actions/submit`), avoid regenerating the entire legal set just to find the match. Use id tokens / action kind + coordinates (and current world/pending) to generate or validate **only the candidate** (or a narrow filter). Same fidelity as today’s “must be in the legal set”; cheaper when the client already holds the key from the last legal listing. Relevant to AI trainer flat-factor / id sidecar — see [`ai/BACKLOG.md`](ai/BACKLOG.md) (legal moves / submit).
- [ ] **Review `ChallengeClaimDrillStatusTest` — suspicious unit-test stopgap.** Replaced a fixed-dice integration test for claim `succeedsOnAttempt` grading when scenario tests moved to live dice. Decide: restore an API-level assertion without queuing dice, or delete if `drillStatus` is trivial. [`ChallengeClaimDrillStatusTest.kt`](backend/src/test/kotlin/com/mhaefele/gridiron/api/ai/training/claim/ChallengeClaimDrillStatusTest.kt)
- [ ] **Flaky `TrainingScenarioLookaheadTest` (`lookahead plays every non-proxy scenario`).** Random dice: some `CLAIM_*` scenarios fail with `used 2/1 budgeted actions` (different scenarios each run, also at HEAD). `ChallengeClaimTrainingScenarioTest` "suggested moves claim the focus card" flakes similarly. Make deterministic or widen budgets.
- [ ] **Frontend `npm run typecheck` is red (~376 errors).** Mostly TS2345 / TS18048 (possibly-undefined) because springdoc emits no `required`, so every generated field is optional. Fix at the source (mark required in OpenAPI) rather than `!` at call sites; worst files `state/presentEvents.ts`, `state/store.ts`, `state/applyEvent.ts`.
- [x] **ThrowResolver questions.** Hail Mary trusts the legal-action target. Dead Eye stays a before-roll pause (it does not add throws). One reroll per check: Pinpoint and Do-Over are offered together, and Do-Over no longer opens Pinpoint on the new roll. Pinpoint stays optional.
- [x] **PlayBonusCard vs required card.** [`requiredBonusCard`](backend/src/main/kotlin/com/mhaefele/gridiron/api/dto/response/LegalAction.kt) means this action spends a card. Moving the implicit spends onto that path is the open item above.
- [x] **Slip Away skip already returns the card.** Removed the stale TODO; the test name matches `returnBonusCard`.
- [x] **Reserves onto the ball.** The test builds a tiny end zone where the ball square is the fallback placement, then asserts the bounce.
- [x] **Single nullable pending decision (FSM, not a stack)** — see [SP.1–SP.6](#sp--single-nullable-pending-decision-fsm). `TurnState.pendingDecision: PendingDecision?`; list / prepend / `COMPOUND_PENDING` removed.
- [ ] **README: say this is an education project about working with AI.** Reword the "What I built" / lessons sections so that is the point of the repo, not an apology. Keep the experiment: the three modules were different review policies on purpose. Backend was reviewed and refactored closely. Frontend was left unreviewed on purpose, to see what happens when the engine is the source of truth and the client only draws and submits. AI training is the stack being learned (RL), so it is still the part changing most. The lessons stay. What has to change is the wording that sounds like the frontend was neglected or the project is unfinished ("Cursor wrote the code", "no human has reviewed the frontend code").

---

## SP — Single nullable pending decision (FSM)

> **Goal:** `TurnState.pendingDecision: PendingDecision?` (one coach prompt). Sequencing that today uses `List` + prepend/drain becomes **phase/step stay + re-query**, or state already on `Ongoing*` / `openPlayerAction`.
>
> **Already close:** one RPA / InitialPlacement / ER at a time; Block/Throw/Throw-teammate as single continuation + internal stage; client already peeks one `pendingDecisionType`.
>
> **Do not** invent a second queue elsewhere — if something must wait, it is an FSM step or a field on the ongoing process.

### SP.1 — Stop batching free-ability / Undying grants

- [x] **Goal:** One [FreeActionOffer.PreTurnAbility] with `remainingPlayerIds` on the pending (continuation-style). Parks once at `PLAYER_ABILITIES`, then advances/clears itself — no TurnState side set.
- [x] **Files:** `FreeFollowUpAbilityHook.kt`, `TurnEngine.kt`, `BasicPlayerActionHandler`
- [x] **Acceptance:** [`CompoundPendingQueueApiTest`](backend/src/test/kotlin/com/mhaefele/gridiron/integration/CompoundPendingQueueApiTest.kt) Undying ×2 one-at-a-time via remaining list; Pick Me Up ×2 on Run continuation.
- [x] **Rulebook:** n/a (engine shape)

### SP.2 — After-own Mark window as one FSM beat (Momentum / All-In Tackle)

- [x] **Shipped:** [MarkResolver](backend/src/main/kotlin/com/mhaefele/gridiron/game/action/MarkResolver.kt) `HANDLE_AFTER_OWN` via `beginAfterOwnIfEligible`; play Momentum/DT spawns one `AfterOwnBonusFreeAction` and completes; TurnEngine `EXECUTE_AFTER_OWN` re-queries for the other card. Deleted `OfferAfterOwnMark` / applier queue stacking.
- [x] **Acceptance:** Momentum→DT characterization tests see one grant at a time (no `OfferAfterOwnMark > grant` / `grant > grant`); both cards still playable across sequential windows.

### SP.3 — Interrupt offers: replace, don’t prepend-over-keeper

- [x] **Partial (Mark / Sidestep / Run):** Slip Away / Stick Close / Cut Off owned by Ongoing* FSMs; grants `setPendingDecision` into empty queue. Deleted `OfferSlipAway` / `OfferStickClose` / `OfferCutOff`.
- [x] **Back on Your Feet / Reserves / phase bonus offers:** `BonusOffers` uses `setPendingDecision` only (no `prependPending*`).

### SP.4 — BlockContinuation never stacks over sibling grants

- [x] **Superseded by SP.5:** single nullable pending makes stacking impossible; Block parks via `replace`/`update` into the sole slot.

### SP.5 — Collapse list-shaped APIs / close-gates

- [x] **Goal:** `TurnState.pendingDecision: PendingDecision?`; deleted `prependPendingDecisions` / multi-arg replace / `noteCompoundPending`.
- [x] **Files:** `TurnState.kt`, `MatchMutator.kt`, `LegalMoves.kt`, `LookaheadSearch.kt`, callers
- [x] **Acceptance:** Compile-time single pending; characterization suites green.

### SP.6 — Fixture / test hygiene

- [x] **Goal:** `MatchBuilder.pendingDecision(single)`; Compound / Bonus / FreeAbility asserts use the singular field.
- [x] **Files:** `MatchBuilder.kt`, `CompoundPendingQueueApiTest`, Bonus/FreeAbility suites
- [ ] **Remnant:** MatchBuilder still infers `step` from seeded pending ([7.13](#task-713--better-api--domain-test-style)) — prefer playing into the beat.

---

## Remaining rules coverage (gap map)

> Rules still not done vs what’s already shipped. Prefer linking existing IDs. Source: [`reference/rulebook-digest.md`](reference/rulebook-digest.md).

### Base game — still open

| Gap | Tracked as | Notes |
|-----|------------|--------|
| Dim inactive / action reasons | [F.6](#f6--per-player-action-constraints-on-the-wire-open)–[F.9](#f9--gui-dim-inactive-players-with-reasons) | Presentation / affordance |

### Deferred product rules

| Rules area | Tracked as | Rulebook |
|------------|------------|----------|
| Random Events, rookie setup card | [D4.1](#deferred--phase-d4-optional-rules)–[D4.3](#deferred--phase-d4-optional-rules) | [`cards.md`](reference/cards.md) (events); rest not yet in digest |
| Special balls | [D4.4](#deferred--phase-d4-optional-rules) | not yet in digest |
| Arena D8 regional rules | [D4.5](#deferred--phase-d4-optional-rules) | not yet in digest |

### Intentionally out of engine scope (presentation / physical)

- Printed score-track coins at +10/+20/+30 — optional UI; integer score is enough ([D1.C.2](archive/BACKLOG-done.md#d1c--scoring--points-victory-2026-07)).
- Component construction / dugout cardboard placement — n/a.
- Exact Bounce Chart art — D8 + clockwise skip is implemented; chart face is pitch art.
- Devoted coach choice / opponent hand-count UI — parked in [`docs/D1-open-questions.md`](docs/D1-open-questions.md) only.

### Already covered

Core play, challenge cards, player abilities, Foul, Throw Team-mate, vs-computer (incl. lookahead), frontend polish/graphics — see [archive](archive/BACKLOG-done.md) and the [coverage matrix](#base-game-rulebook-coverage-matrix) below.

---

## Open remnant — Phase 7

> Most of [Phase 7](archive/BACKLOG-done.md#phase-7-testing) is done (including [7.12](archive/BACKLOG-done.md#task-712--action-unit-test-coverage-gaps)). Remnants: fixture-builder fluency ([7.0](#task-70--test-fixture-builders)) and better API/domain test style ([7.13](#task-713--better-api--domain-test-style)).

### Task 7.0 — Test fixture builders

- [ ] **Goal:** Extend **[GameScenario](backend/src/test/kotlin/com/mhaefele/gridiron/support/GameScenario.kt)** (seed in place) with fluent convention-over-configuration builders — e.g. `.withHomePlayer()`, `.withActionsRemaining(n)`, `.homeRunsToMark()`, optional API-backed `matchViaApi()`. Every test eventually starts from the harness, requesting only as much game as it needs. Builders may call real HTTP when setup is naturally API-reachable. Sensible hardcoded defaults for coordinates and ids when tests do not depend on them.
- [ ] **Depends on:** 5.3
- [ ] **Files:** `backend/src/test/kotlin/.../support/GameScenario.kt` (extend), `MatchFixtures.kt`, `MatchScenario.kt` (API-backed fluent wrapper)
- [ ] **Acceptance:** Domain test can build a marked-player scenario in ≤3 meaningful overrides; API test can read like English via fluent scenario helper; mid-game state via repository or API as appropriate; no production setup endpoints added.
- [ ] **Rulebook:** n/a
- [ ] **Note:** **Partial** — `GameScenario`, `BoardScenario`, `TurnContextScenario`, and `matchState()` / [MatchFixtures.kt](backend/src/test/kotlin/com/mhaefele/gridiron/support/MatchFixtures.kt) are in place; fluent mid-game chains and `matchViaApi()` still open. Pairs with [7.13](#task-713--better-api--domain-test-style).

### Task 7.13 — Better API / domain test style

> Incremental quality bar — helpers, negative controls, tiny pitches, and the first migrated suites are [archived](archive/BACKLOG-done.md#task-713--better-api--domain-test-style-partial-ship-2026-08). Remnants only:

- [ ] **Lookup profile by ability / capability:** [TeamCatalogTestHelper](backend/src/test/kotlin/com/mhaefele/gridiron/support/TeamCatalogTestHelper.kt) (`profileWith`, `throwTeammateProfile`, `throwableTeammateOf`) — migrate all tests that hardcode roster names when next touched. See TODO on [TestProfiles](backend/src/test/kotlin/com/mhaefele/gridiron/support/TestProfiles.kt).
- [ ] **Avoid prestaging pending + inferring FSM step:** [MatchBuilder](backend/src/test/kotlin/com/mhaefele/gridiron/support/MatchBuilder.kt) still guesses `step` from seeded `pendingDecision`. Prefer playing into that beat (or an explicit step setter).
- [ ] **Gradual migration:** remaining movement / block / acceptance bodies still use some `filterIsInstance` — convert when next touched.

---

## Future items (base game, later)

> Split-action prompts, ability provenance, Open/Marked rings, and related future items that shipped or won't do are in the [archive](archive/BACKLOG-done.md#completed-future-items-archived).
>
> **GUI that needs new wire** ([F.9](#f9--gui-dim-inactive-players-with-reasons)) stays here with its backend enabler ([F.6](#f6--per-player-action-constraints-on-the-wire-open)).

### F.6 — Per-player action constraints on the wire (open)

- [ ] **Open:** Does the GUI need to know *why* a player has no run (or block, etc.) — e.g. already used that action type this activation (`actionsUsedThisTurn`), turn ended after a tackle (`turnEndedPlayers`), wrong phase/coach — or is “absent from `legal-actions`” enough?
- [ ] **Today:** `GET .../legal-actions` returns only legal choices; `MatchSnapshotDto.gameState` exposes team-level `actionsRemaining`, not per-player action history. Clients cannot distinguish “illegal geometry” from “already ran” without inferring or re-deriving rules locally.
- [ ] **Options (pick when wiring [F.9](#f9--gui-dim-inactive-players-with-reasons)):** per-player flags on `gameState` or `pieces`; a grouped legal-moves payload with exclusion reasons; rely on events (`PlayerTurnEnded`, action bracket) plus local state — only if that stays consistent with snapshot refresh without encoding rules in the client.
- [ ] **Note:** Integration tests should assert observable behavior (legal list, 400 on illegal POST), not `TurnState` internals — see [TurnRulesApiTest](backend/src/test/kotlin/com/mhaefele/gridiron/integration/TurnRulesApiTest.kt).
- [ ] **GUI follow-up:** [F.9](#f9--gui-dim-inactive-players-with-reasons).

### F.9 — GUI: dim inactive players with reasons

- [ ] **Module:** frontend (blocked on backend wire / decision in F.6)
- [ ] **Goal:** Soft-dim or badge players who cannot usefully act this turn and show a **one-line reason on hover** (already used that action type, turn ended after tackle/miss, not active coach, etc.) so absence from `legal-actions` feels fair instead of mysterious.
- [ ] **Depends on:** [F.6](#f6--per-player-action-constraints-on-the-wire-open) (chosen wire shape); pairs with [UX.1](archive/BACKLOG-done.md#ux1--selection-first-action-targeting) eligible-token highlighting.
- [ ] **Files:** `frontend/src/render/Token.tsx`, Play / card hover; store fields folded from snapshot or events per F.6
- [ ] **Acceptance:** During the active coach’s turn, players with no remaining legal actions are visually distinct; hover (or card) explains why when the server exposes a reason; no client-side rule re-implementation beyond displaying server fields / events.
- [ ] **Rulebook:** n/a (affordance for turn restrictions already enforced server-side)

---

## Parking lot (unlikely soon)

> Not on the current path. Revisit only if a real need appears. Engine leftovers from the 2026-08 pending-queue / bonus / shake-loose pass: [archive](archive/BACKLOG-done.md#quick-todo--pending-queue--bonus--shake-loose-2026-08).

### Engine (parked)

- Interrupt timing vs challenge claims — should interrupts fire **before** challenge card claims? *Product decision; engine currently offers mid-action interrupts before claim, after-own bonuses before claim.*
- **Consolidate turn tracking** between `MatchMutator` and `TurnState` (or pick one home). Feels like arbitrary overlap (`lastCompletedPlayerAction`, claim subject, open action, pending, bracket indexes, etc.). *Do not start until a real pain appears.*

### P.1 — Backend CORS (if UI and API are different origins)

- [ ] **Module:** backend
- [ ] **Goal:** Allow true cross-origin browser calls when the Vite proxy is not in play — GET/POST on `/api/**`, `text/event-stream` on `/api/matches/*/events`, and the `Last-Event-ID` request header.
- [ ] **Depends on:** a topology that serves the frontend from a different origin than the API (not the local Vite proxy setup).
- [ ] **Files:** `spring/` `WebMvcConfigurer` (or `@CrossOrigin`) with a configurable allowed-origins property (not `*`)
- [ ] **Acceptance:** Preflight returns the expected headers; an API test asserts them; SSE still works with `Last-Event-ID`.
- [ ] **Note:** Skipped for this hobby project — [10.7](archive/BACKLOG-done.md#task-107--vite-dev-proxy) keeps the browser same-origin via the Vite proxy.
- [ ] **Rulebook:** n/a

### P.2 — Sound cues

- [ ] **Module:** frontend
- [ ] **Goal:** Optional short SFX for big beats — e.g. block/tackle thud, whistle or sting on touchdown, light dice tick — muted by default or behind a simple toggle; respect system / reduced-motion preferences where appropriate.
- [ ] **Depends on:** playable UX clarity; not needed for vs-computer.
- [ ] **Acceptance:** Sounds never gate input; toggle persists locally; no sound required to understand the rules or outcomes.
- [ ] **Note:** Parking lot until click-to-click play feels clear — visual feedback already shipped ([UX.6](archive/BACKLOG-done.md#ux6--dice-theatre-overlay), [UX.8](archive/BACKLOG-done.md#ux8--outcome-flash), [UX.10](archive/BACKLOG-done.md#ux10--touchdown-and-match-end-beat)).
- [ ] **Rulebook:** n/a

---

## DEFERRED — Phase D4: Optional Rules

- [ ] **D4.1** — Shuffle 6 Random Event Cards into challenge deck; draw on reveal
- [ ] **D4.2** — Event effects per [`cards.md`](reference/cards.md#random-events-optional-rule--not-implemented) (e.g. Fresh Bodies, Blind Ref)
- [ ] **D4.3** — Rookie setup card
- [ ] **D4.4** — Special balls (three variants)
- [ ] **D4.5** — Arena D8 regional rules (8 arenas)

**Rules:** advanced and optional rules — paraphrase into `rulebook-digest.md` when D4 starts.

---

## DEFERRED — Phase AI2: Trainable Model

Full phased backlog (HTTP trainer API, SB3 harness, eval vs computer, ONNX): **[`ai/BACKLOG.md`](ai/BACKLOG.md)**. Harness root: [`ai/`](ai/).

---

## Base-game rulebook coverage matrix

| Mechanic | Backlog phase | Rulebook | Status |
|----------|---------------|----------|--------|
| Core pitch / actions / turn / TD / Sudden Death / API / SSE / frontend | 0–15 | digest | Done — [archive](archive/BACKLOG-done.md) |
| Challenge cards / bonus / Points Victory | D1 | [`cards.md`](reference/cards.md) | Done |
| Player abilities (enforcement + catalog) | PA.* / D2 | team cards | Done |
| Foul / Throw teammate | D3 | digest § Foul / Throw Team-mate | Done |
| Vs computer (heuristic + lookahead) | AI1 / [AI1.6](archive/BACKLOG-done.md#ai16--lookahead-over-the-legal-move-graph-2026-08) | n/a | Done |
| Dim inactive with reasons (GUI; wire first) | F.6→F.9 | n/a | Future |
| Domain + API test fluency | 7.0 / 7.13 | n/a | Partial |
| Random events / optional rules | D4 | not yet in digest | Deferred |
| Trainable model | [AI2 → ai/BACKLOG.md](ai/BACKLOG.md) | n/a | Deferred |
| Parking: CORS / sound | P.1 / P.2 | n/a | Unlikely |

---

## Cross-reference index

| Topic | Architecture section | Backlog |
|-------|---------------------|---------|
| Testing / fixtures / test style | [§ Testing strategy](backend/ARCHITECTURE.md#testing-strategy) | [7.0](#task-70--test-fixture-builders), [7.13](#task-713--better-api--domain-test-style) |
| Inactive reasons (wire → GUI) | [Future items](#future-items-base-game-later) | F.6, F.9 |
| Optional rules | [§ Extension points](backend/ARCHITECTURE.md#extension-points-deferred) | [D4](#deferred--phase-d4-optional-rules) |
| Vs computer / lookahead | [§ Long-term product goals](backend/ARCHITECTURE.md#long-term-product-goals) | [AI1 archive](archive/BACKLOG-done.md#ai1--basic-computer-opponent), [AI1.6](archive/BACKLOG-done.md#ai16--lookahead-over-the-legal-move-graph-2026-08) |
| Trainable model | [§ Long-term product goals](backend/ARCHITECTURE.md#long-term-product-goals) | [AI2](#deferred--phase-ai2-trainable-model) → [`ai/BACKLOG.md`](ai/BACKLOG.md) |
| Sound / CORS | — | [Parking lot](#parking-lot-unlikely-soon) |
| Challenge cards / abilities / polish / tech debt | see [archive/BACKLOG-done.md](archive/BACKLOG-done.md) | archived |
