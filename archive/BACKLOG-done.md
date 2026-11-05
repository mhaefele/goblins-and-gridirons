# Goblins & Gridirons — Completed backlog (archive)

Shipped phases and finished polish/graphics checklist items. **Active open work lives in [BACKLOG.md](../BACKLOG.md).** Includes completed rulebook fidelity **RF.1–RF.6**, throw ruler **[BG.1](#bg1--physical-throw-ruler-geometry-2026-07)**, **PA.F** / **[PA.C](#pac--align-ability-behavior-with-official-card-text-2026-07)**, **[D2](#d2--player-abilities-phase-closed-2026-07)**, **PM.0–PM.6**, **D1.A–H** (incl. [D1.F.4](#d1f4--annotate-legal-actions-that-could-claim-a-challenge-2026-07), [D1.C-fix](#d1c-fix--challenge-claim--bonus-behavior-vs-official-site-2026-07)), **D3** (Foul + Throw Team-mate), **[AI1.6](#ai16--lookahead-over-the-legal-move-graph-2026-08)** lookahead, 2026-08 Quick TODO (pending-queue / bonus / shake-loose), frontend follow-ups **FF.1–FF.7 / FF.9–FF.10**, Task **7.12**, and tech debt **TD.1–8 / TD.10**; [TD.9](#td9--event-driven-turn-and-progress-updates-wont-do) / [FF.8](#ff8--pitch--join-chrome-punch-up-wont-do) / [F.5](#f5--openmarked-on-piece-snapshot-and-events-wont-do) / [F.8](#f8--gui-openmarked-rings-wont-do) won't do.

Historical context only — do not treat unchecked boxes here as current work (Task 7.0’s remaining work is tracked in the active backlog).

**Architecture:** [System](../ARCHITECTURE.md) · [Backend](../backend/ARCHITECTURE.md) · [Frontend](../frontend/ARCHITECTURE.md)

---

## Phase 0: Project Foundation

> **Architecture:** [Package layout](../backend/ARCHITECTURE.md#package-layout), [Source file organization](../backend/ARCHITECTURE.md#source-file-organization), [Layering](../backend/ARCHITECTURE.md#layering-overview)

### Task 0.1 — Domain package skeleton

- [x] **Goal:** Create empty domain package structure with no Spring dependencies.
- [x] **Depends on:** none
- [x] **Files:** `domain/model/`, `domain/rules/`, `domain/legalmoves/`, `domain/dice/`, `domain/extension/`
- [x] **Acceptance:** Domain packages compile; no imports from `org.springframework` or `application/` within `domain/`.
- [x] **Rulebook:** n/a

### Task 0.2 — Core value types and identifiers

- [x] **Goal:** Implement shared types in dedicated files; colocate entity-specific IDs/enums with their owning type per [ARCHITECTURE.md source file organization](../backend/ARCHITECTURE.md#source-file-organization).
- [x] **Depends on:** 0.1
- [x] **Files:** `domain/model/Coordinate.kt`, `TeamSide.kt`, `Direction.kt`, `ActionType.kt` (shared); `PlayerId` in `Player.kt`, `BallId` in `Ball.kt`, `MatchId` in `MatchState.kt` (colocated — create stub files if types not yet implemented)
- [x] **Acceptance:** No catch-all `Identifiers.kt` or `Enums.kt`; IDs are type-safe; `Coordinate` supports equality; `Direction` has 8 compass values matching bounce chart.
- [x] **Rulebook:** p. 38 — Glossary (Adjacent, Square)

> **Phase 0 done.** Tasks 0.3 (dice) and 0.4 (validation) landed in Phase 4 (tasks 4.5–4.6). **Phases 0–9 done** (7.0 partial). Next: GUI, [Future items](../BACKLOG.md#future-items-base-game-later), or [tech debt](../BACKLOG.md#tech-debt-cleanup).

---

## Phase 1: Board Layout

> **Architecture:** [Core domain models](../backend/ARCHITECTURE.md#core-domain-models), [Board vs turn/progress](../backend/ARCHITECTURE.md#2-physical-board-vs-turnphase-flow)
>
> Goal: model the table snapshot — pitch topology, players (as tokens), balls, and `Board`. *Where* things are, not what actions are legal. No marking, pathfinding, turn engine, or scoring yet. Ends with an ASCII board renderer for debugging and tests.

### Task 1.1 — PlayerStatus and Player

- [x] **Goal:** `PlayerStatus` (Standing/Prone/Reserves); `Player` entity with id, team, status, position, heldBallId — **no roster stats yet** (profile added in Phase 4 task 4.4).
- [x] **Depends on:** 0.2
- [x] **Files:** `domain/model/Player.kt`
- [x] **Acceptance:** Reserves players have `position = null`; Prone and Standing require coordinate on pitch.
- [x] **Rulebook:** pp. 10–11 — Player Status

### Task 1.2 — PitchDefinition and Pitch

- [x] **Goal:** Configurable grid with end zones, trapdoors, blocked squares; occupancy map per coordinate.
- [x] **Depends on:** 0.2
- [x] **Files:** `domain/model/Pitch.kt`
- [x] **Acceptance:** `isInBounds`, `isBlocked`, `isInEndZone(team, coord)`, `isTrapdoor(coord)` helpers work; `endZones` map holds one row of squares per team.
- [x] **Rulebook:** pp. 5, 12–13 — Setting Up the Game

### Task 1.3 — Ball and square occupancy

- [x] **Goal:** `Ball` entity; loose balls on [BoardSquare], held balls on [Player]; square holds player **or** loose ball, not both.
- [x] **Depends on:** 0.2, 1.1
- [x] **Files:** `domain/model/Ball.kt`, `domain/model/BoardSquare.kt`
- [x] **Acceptance:** Occupancy invariant: at most one player and one loose ball per square; held ball not on square; board init validates consistency.
- [x] **Rulebook:** p. 20 — Multiple Balls in Play

### Task 1.4 — Board aggregate

- [x] **Goal:** [Board] with `playerAt(coord)`; self-consistent squares, players, balls, reserves.
- [x] **Depends on:** 1.1, 1.2, 1.3
- [x] **Files:** `domain/model/Board.kt`
- [x] **Acceptance:** Lookup by coordinate returns optional player; board init rejects inconsistent placement.
- [x] **Rulebook:** n/a

### Task 1.5 — Default BoardDefinition

- [x] **Goal:** Physical board pitch layouts: `default` dungeon 11×15 with center trapdoor and corner pillars; `barricade` wooden side with two center-row trapdoors and seven barricades — registered in `PitchCatalog`.
- [x] **Depends on:** 1.2
- [x] **Files:** `infrastructure/DefaultBoardDefinition.kt`, `infrastructure/BarricadeBoardDefinition.kt`, `infrastructure/PitchCatalog.kt`
- [x] **Acceptance:** Both teams can deploy 6 players in respective end zones without overlap on each layout.
- [x] **Rulebook:** p. 12 — Setting Up (board sides from game components)

### Task 1.6 — ASCII board renderer

- [x] **Goal:** Pure function `renderBoard(board: Board): String` that prints a fixed-width text grid — for debugging, sharing state in chat/logs, and visual regression during development. No GUI, no Spring.
- [x] **Depends on:** 1.4, 1.5
- [x] **Files:** `domain/legalmoves/BoardRenderer.kt`
- [x] **Acceptance:** Multiline string shows pitch bounds, end zones, trapdoor(s), blocked squares, standing vs prone players (distinct glyphs per team), loose ball vs held ball indicator; fixture board with known layout produces stable golden output in a domain unit test; callable from tests via `println(renderBoard(board))` without extra setup.
- [x] **Rulebook:** n/a

---

## Phase 2: Spatial Queries

> **Architecture:** [Legal moves](../backend/ARCHITECTURE.md#legal-moves)
>
> Goal: pure functions over `Board` for action geometry — adjacency, distance, Open/Marked status, and movement paths (run/mark). No turn flow, legal-move enumeration, or action resolution yet.

### Task 2.1 — Adjacency and neighbors

- [x] **Goal:** Pure function `neighbors(square, board): List<BoardSquare>` — 8-direction on the board grid; no Spring.
- [x] **Depends on:** 1.2
- [x] **Files:** `domain/rules/Topology.kt`
- [x] **Acceptance:** Playable corner has 8 neighbors (including border); center has 8; neighbors throws on out-of-bounds input.
- [x] **Rulebook:** p. 38 — Adjacent Square

### Task 2.2 — Within distance

- [x] **Goal:** `within(from, to, n, board)` / `distance(from, to, board)` — shortest square path through players and blocked squares; no Spring.
- [x] **Depends on:** 2.1
- [x] **Files:** `domain/rules/Topology.kt`
- [x] **Acceptance:** Path through blocked square still counts those squares; distance matches rulebook Within definition.
- [x] **Rulebook:** p. 9 — Within

### Task 2.3 — Marking and Open/Marked derivation

- [x] **Goal:** Pure function `standingStatus(player, board)` — no Spring.
- [x] **Depends on:** 2.1, 1.4
- [x] **Files:** `domain/rules/MarkingCalculator.kt`
- [x] **Acceptance:** Two adjacent standing opposition players both Marked; isolated player is Open.
- [x] **Rulebook:** pp. 10–11 — Open/Marked Players

### Task 2.4 — Pathfinding for movement

- [x] **Goal:** BFS/DFS as **pure functions** `movementPaths(player, board, moveAllowance)`; Spring-free; `moveAllowance` is an explicit `Int` until `Player.profile` exists (Phase 4 task 4.4).
- [x] **Depends on:** 2.1, 1.4
- [x] **Files:** `domain/legalmoves/MovePathFinder.kt`, `MovePath.kt`
- [x] **Acceptance:** Path never enters occupied or blocked square; path length ≤ `moveAllowance`; testable without `@SpringBootTest`.
- [x] **Rulebook:** p. 16 — Run Action

### Task 2.5 — Run adjacency restriction

- [x] **Goal:** During Run, cannot enter any square adjacent to standing opposition (Open or Marked).
- [x] **Depends on:** 2.3, 2.4
- [x] **Files:** `domain/legalmoves/MovePathFinder.kt`
- [x] **Acceptance:** Run path rejected if any step enters opposition-adjacent square; Prone opposition ignored.
- [x] **Rulebook:** p. 16 — Run Action

### Task 2.6 — Mark path rules

- [x] **Goal:** Mark paths ≤2 squares; may pass through opposition-adjacent squares; must end on square adjacent to standing opposition.
- [x] **Depends on:** 2.4, 2.3
- [x] **Files:** `domain/legalmoves/MovePathFinder.kt`
- [x] **Acceptance:** Valid mark path ends Marking; path that ends Open is excluded.
- [x] **Rulebook:** p. 16 — Mark Action

## Phase 3: Legal Moves

> **Architecture:** [Legal moves](../backend/ARCHITECTURE.md#legal-moves)
>
> Goal: pure functions to enumerate legal actions — **one player**, then **one team**, then the **active coach** — from a `Board` snapshot plus a **minimal turn-context slice** when turn rules apply. No full `MatchState` or scores yet for the core overloads; turn slice is `TurnActionContext`.

### Task 3.1 — Action availability by status

- [x] **Goal:** Map `PlayerStatus` → allowed `ActionType`s per rulebook table.
- [x] **Depends on:** 1.1
- [x] **Files:** `domain/rules/ActionAvailability.kt`
- [x] **Acceptance:** Open→Run/Mark/Throw; Marked→Block/Sidestep/Throw; Prone→StandUp; Reserves→Reserves.
- [x] **Rulebook:** p. 15 — Actions table

### Task 3.2 — LegalAction sealed hierarchy

- [x] **Goal:** Define `LegalAction` variants (Run, Mark, Throw, Block, Sidestep, StandUp, Reserves) with parameters.
- [x] **Depends on:** 0.2
- [x] **Files:** `domain/legalmoves/LegalAction.kt`
- [x] **Acceptance:** Sealed interface; each variant carries `playerId` and action-specific data (`MovePath`, `ThrowTarget`, etc.).
- [x] **Rulebook:** n/a

### Task 3.3 — Movement legal moves for one player

- [x] **Goal:** Given `player` on `board`, enumerate Run, Mark, and Sidestep `LegalAction`s — delegate paths to `MovePathFinder`; Sidestep is 1 square and must end Open.
- [x] **Depends on:** 3.1, 3.2, 2.4, 2.5, 2.6, 2.3
- [x] **Files:** `domain/legalmoves/LegalMoves.kt`
- [x] **Acceptance:** Open player gets Run/Mark paths; Marked player gets Sidestep destinations only; Prone/Reserves get none from this task; Spring-free; testable with `GameScenario` board only.
- [x] **Rulebook:** pp. 16, 19 — Run, Mark, Sidestep

### Task 3.4 — Stand Up and Reserves for one player

- [x] **Goal:** Prone → `StandUp`; Reserves → valid end-zone placement coordinates as `Reserves` actions (not adjacent standing opposition; ball rules deferred to resolution).
- [x] **Depends on:** 3.1, 3.2, 1.2, 2.1
- [x] **Files:** `domain/legalmoves/LegalMoves.kt`
- [x] **Acceptance:** Prone player in square gets exactly StandUp; Reserves player lists legal deployment squares in own end zone; no turn-context needed.
- [x] **Rulebook:** p. 19 — Stand Up, Reserves

### Task 3.5 — Block and throw targets for one player

- [x] **Goal:** Marked player → Block targets (opposition being Marked); ball carrier → Throw targets (exclude Prone teammates; hand-off vs throw geometry stub OK until Phase 4 throw range).
- [x] **Depends on:** 3.1, 3.2, 2.3
- [x] **Files:** `domain/legalmoves/LegalMoves.kt`
- [x] **Acceptance:** Open player has no Block; non-carrier has no Throw; Block only lists opposition the player is Marking.
- [x] **Rulebook:** pp. 17, 19 — Throw, Block

### Task 3.6 — `legalMovesForPlayer` (board geometry)

- [x] **Goal:** Pure function `legalMovesForPlayer(player, board): List<LegalAction>` — combine 3.3–3.5; **status and geometry only**, no turn budget or same-action-once.
- [x] **Depends on:** 3.3, 3.4, 3.5
- [x] **Files:** `domain/legalmoves/LegalMoves.kt`
- [x] **Acceptance:** Marked dwarf with ball gets Block + Throw; Open runner gets Run/Mark; unit-testable with `Board` + `Player` only.
- [x] **Rulebook:** p. 15 — action types per status

### Task 3.7 — Turn action context (minimal)

- [x] **Goal:** `TurnActionContext` — only fields legal-move filtering needs: `activeCoach`, `actionsRemaining`, `actionsUsedThisTurn`, `turnEndedPlayers`. **Not** scores, phase enum, turn number, or config.
- [x] **Depends on:** 0.2, 3.1
- [x] **Files:** `domain/model/TurnActionContext.kt`
- [x] **Acceptance:** Immutable data class; constructable in tests without `MatchState`; `actionsRemaining` in 0–3.
- [x] **Rulebook:** p. 15 — action restrictions

### Task 3.8 — Per-player turn restrictions

- [x] **Goal:** `legalMovesForPlayer(player, board, context: TurnActionContext)` — filter 3.6 output by same-action-once and turn-ended set; empty list when player is turn-ended.
- [x] **Depends on:** 3.6, 3.7
- [x] **Files:** `domain/legalmoves/LegalMoves.kt`
- [x] **Acceptance:** Player who already Ran returns no Run; Tackle/Miss blocker excluded; no Spring.
- [x] **Rulebook:** p. 15 — same action once, turn ended

### Task 3.9 — `legalMovesForTeam`

- [x] **Goal:** `legalMovesForTeam(team, board, context): List<LegalAction>` — flatMap on-pitch players of `team` through 3.8.
- [x] **Depends on:** 3.8, 1.4
- [x] **Files:** `domain/legalmoves/LegalMoves.kt`
- [x] **Acceptance:** Home team enumeration ignores away players; respects per-player turn restrictions; testable with board + context fixture.
- [x] **Rulebook:** p. 15

### Task 3.10 — `legalMovesForActiveCoach`

- [x] **Goal:** `legalMovesForActiveCoach(board, context): List<LegalAction>` — delegate to 3.9 for `context.activeCoach`; return empty when `actionsRemaining == 0`. Thin `LegalActionGenerator` Spring bean may delegate here.
- [x] **Depends on:** 3.9
- [x] **Files:** `domain/legalmoves/LegalMoves.kt`, optional `domain/legalmoves/LegalActionGenerator.kt`
- [x] **Acceptance:** Only active coach's players; global action budget enforced at list level; `MatchState` overload added in Phase 5 when aggregate exists.
- [x] **Rulebook:** p. 15 — 3 actions per turn

---

## Phase 4: Action Resolution

> **Architecture:** [Movement summary](../backend/ARCHITECTURE.md#movement-summary), [Throw and bounce](../backend/ARCHITECTURE.md#throw-summary-rulebook-pp-1718), [Block dice](../backend/ARCHITECTURE.md#block-dice-rulebook-pp-1819)
>
> Goal: apply one action at a time — `(MatchState, ActionCommand) → ActionOutcome`. Turn orchestration deferred to Phase 5.
>
> **Work in order:** roster stats → validation/dice plumbing → command/outcome shell → dice helpers → movement resolvers → turn tracking → throw/bounce → block → touchdown. Tasks 4.1–4.4 and 4.5–4.6 were relocated from Phases 1 and 0 respectively.

### Group A — Roster prerequisites *(relocated from Phase 1)*

### Task 4.1 — StatLine and throw/armour sentinels

- [x] **Goal:** Model Move (fixed or D6-random), Block count, Throw threshold or `Unthrowable`, Armour threshold or `NoArmour`.
- [x] **Depends on:** 0.2
- [x] **Files:** `domain/model/StatLine.kt`
- [x] **Acceptance:** `-` throw cannot hold ball; `-` armour auto-fails checks without rolling.
- [x] **Rulebook:** p. 8 — Throw Checks and Armour Checks

### Task 4.2 — PositionProfile and PlaceholderTeam

- [x] **Goal:** `PositionProfile` (name + stats); `PlaceholderTeam` (id, name, emergencyReserves, roster list).
- [x] **Depends on:** 4.1
- [x] **Files:** `domain/model/PlaceholderTeam.kt`
- [x] **Acceptance:** Roster holds exactly 6 entries; no abilities field.
- [x] **Rulebook:** p. 9 — Team Cards

### Task 4.3 — PlaceholderTeamCatalog

- [x] **Goal:** Hard-code **Dwarves** and **Orcs** rosters per [ARCHITECTURE.md](../backend/ARCHITECTURE.md#starter-teams-dwarves-and-orcs-base-game) (ids `"dwarves"`, `"orcs"`).
- [x] **Depends on:** 4.2
- [x] **Files:** `infrastructure/PlaceholderTeamCatalog.kt`
- [x] **Acceptance:** Both teams ER=4; Orc Bruiser Block=2; Orc Linemen/Blocker have `-` throw; Dwarf roster matches rulebook stats.
- [x] **Rulebook:** pp. 6–7, 9 — Dwarf/Orc team profiles

### Task 4.4 — Player profile field

- [x] **Goal:** Add `profile: PositionProfile` to `Player`; roster stats now required on every on-pitch player.
- [x] **Depends on:** 4.2, 4.1
- [x] **Files:** `domain/model/Player.kt`
- [x] **Acceptance:** Existing Phase 1 player tests/fixtures updated; player without profile is invalid for match setup.
- [x] **Rulebook:** p. 9 — Team Cards

### Group B — Validation and dice plumbing *(relocated from Phase 0)*

### Task 4.5 — Validation result types

- [x] **Goal:** Sealed `ValidationResult` (Valid / Invalid with error list); `DomainError` sealed hierarchy for rule violations.
- [x] **Depends on:** 0.1
- [x] **Files:** `domain/model/ValidationResult.kt`
- [x] **Acceptance:** Invalid carries human-readable messages; Valid is a singleton or empty object.
- [x] **Rulebook:** n/a

### Task 4.6 — DiceRoller port and test double

- [x] **Goal:** Define `DiceRoller` interface (`d6`, `d8`, `blockDice`); implement `DeterministicDiceRoller` with scripted queue for tests.
- [x] **Depends on:** 0.1
- [x] **Files:** `domain/dice/DiceRoller.kt`, `infrastructure/DeterministicDiceRoller.kt`
- [x] **Acceptance:** Deterministic roller returns queued values in order; throws when queue exhausted.
- [x] **Rulebook:** p. 38 — D6, D8, Block Dice

### Group C — Command and outcome shell

### Task 4.7 — ActionCommand hierarchy

- [x] **Goal:** Sealed `ActionCommand` in `domain/action/` — minimum submit payload per action type; lossy projection from [LegalAction] via `toAction()`.
- [x] **Depends on:** 0.2
- [x] **Files:** `domain/action/ActionCommand.kt`, `domain/action/ActionMapping.kt`
- [x] **Acceptance:** All movement commands carry playerId and path/destination; validation checks submitted action against `legalMovesForActiveCoach(state).map { it.toAction() }`.
- [x] **Rulebook:** pp. 16–19

### Task 4.8 — ActionValidator skeleton

- [x] **Goal:** Validate command against MatchState: correct phase, active coach, player status, action budget, same-action-once.
- [x] **Depends on:** 4.7, 3.1, 3.7, 5.2, 4.5
- [x] **Files:** `domain/rules/ActionValidator.kt`
- [x] **Acceptance:** Invalid commands return ValidationResult.Invalid with reason; cannot submit when actionsRemaining=0.
- [x] **Rulebook:** p. 15

### Task 4.9 — ActionOutcome

- [x] **Goal:** Return new MatchState, dice roll records, optional event list from each resolution.
- [x] **Depends on:** 5.3
- [x] **Files:** `domain/action/ActionOutcome.kt`
- [x] **Acceptance:** Every successful action returns immutable ActionOutcome with new state.
- [x] **Rulebook:** n/a

### Group D — Dice helpers

### Task 4.10 — D6 check helper

- [x] **Goal:** `passesCheck(roll, threshold, modifiers)` with natural 1 fail, natural 6 success, ±1 cap.
- [x] **Depends on:** 4.6
- [x] **Files:** `domain/dice/DiceCheck.kt`
- [x] **Acceptance:** Roll 1 always fails even with +1; roll 6 always succeeds even with -1; total modifier capped at ±1.
- [x] **Rulebook:** p. 8 — Throw/Armour Checks

### Task 4.11 — Throw check

- [x] **Goal:** Apply Long (-1) and Obstructed (-1) modifiers; handle Unthrowable; Fumble on natural 1.
- [x] **Depends on:** 4.10, 4.1
- [x] **Files:** `domain/dice/DiceCheck.kt`
- [x] **Acceptance:** Fumble triggers on unmodified 1 before comparison; combined modifiers still cap at -1.
- [x] **Rulebook:** pp. 8, 17–18 — Throw Action, Fumble

### Task 4.12 — Armour check

- [x] **Goal:** Apply Flatten -1 modifier; NoArmour auto-fail; injury on fail.
- [x] **Depends on:** 4.10, 4.1
- [x] **Files:** `domain/dice/DiceCheck.kt`
- [x] **Acceptance:** NoArmour never rolls; Flatten applies -1 min 1.
- [x] **Rulebook:** pp. 8, 18 — Armour Check, Flatten

### Task 4.13 — Block die faces and pool

- [x] **Goal:** Enum `BlockDieFace` (Smash, Flatten, Shove, Tackle, Miss); roll N dice, attacker picks best.
- [x] **Depends on:** 4.6
- [x] **Files:** `domain/dice/BlockDie.kt`
- [x] **Acceptance:** Block value 2 rolls 2 dice; assisted adds +1 die; 3 dice max unless arena rule (deferred).
- [x] **Rulebook:** pp. 18–19 — Block Action, Assisting

### Task 4.14 — Re-roll policy

- [x] **Goal:** Document and implement: no re-roll of re-roll; must accept re-roll result; own rolls only. Stub for future Do-Over.
- [x] **Depends on:** 4.6
- [x] **Files:** `domain/dice/ReRollPolicy.kt`
- [x] **Acceptance:** Second re-roll attempt on same roll throws or returns error.
- [x] **Rulebook:** p. 8 — Re-rolling Dice

### Group E — Movement resolvers

### Task 4.15 — Run action resolver

- [x] **Goal:** Move player along path; pick up ball if ending on ball square (Run only); update occupancy. Wire legal-move pathfinding to `player.profile.stats.move` (`MoveValue.Fixed` only — see [Future items](../BACKLOG.md#future-items-base-game-later)).
- [x] **Depends on:** 4.7, 4.8, 2.5, 4.9
- [x] **Files:** `domain/rules/ActionResolver.kt`, `domain/legalmoves/LegalMoves.kt`
- [x] **Acceptance:** Paths ≤ fixed move from profile; player ends on last path square with ball on base if square had loose ball; occupancy updated atomically. `MoveValue.RandomD6` roster entries rejected or ignored until Future F.2.
- [x] **Rulebook:** p. 16 — Run, Picking Up a Ball

### Task 4.16 — Mark action resolver

- [x] **Goal:** Move ≤2 squares; must end Marking; ball on path causes bounce (not pickup).
- [x] **Depends on:** 4.15, 2.6
- [x] **Files:** `domain/rules/ActionResolver.kt`
- [x] **Acceptance:** Mark onto ball square triggers bounce from that square; player ends Marked.
- [x] **Rulebook:** p. 16 — Mark Action

### Task 4.17 — Sidestep action resolver

- [x] **Goal:** Move 1 square; must end Open; ball causes bounce.
- [x] **Depends on:** 4.15, 2.3
- [x] **Files:** `domain/rules/ActionResolver.kt`
- [x] **Acceptance:** Sidestep ending Marked is rejected by validator; stepping on ball bounces.
- [x] **Rulebook:** p. 19 — Sidestep

### Task 4.18 — Stand Up action resolver

- [x] **Goal:** Prone player stands in same square.
- [x] **Depends on:** 4.8, 4.9
- [x] **Files:** `domain/rules/ActionResolver.kt`
- [x] **Acceptance:** Status changes Prone→Standing; position unchanged.
- [x] **Rulebook:** p. 19 — Stand Up

### Task 4.19 — Reserves action resolver

- [x] **Goal:** Place Reserves player in own End Zone; not adjacent standing opposition; not on ball unless forced.
- [x] **Depends on:** 4.8, 1.2, 4.9
- [x] **Files:** `domain/rules/ActionResolver.kt`
- [x] **Acceptance:** Illegal placement rejected; forced ball square triggers bounce.
- [x] **Rulebook:** p. 19 — Reserves Action

### Group F — Turn tracking

### Task 4.21 — Action budget and same-action-once tracking

- [x] **Goal:** Decrement actionsRemaining; record action type per player; enforce no duplicate action type per player per turn.
- [x] **Depends on:** 4.8, 5.2, 4.9
- [x] **Files:** `domain/rules/ActionResolver.kt`
- [x] **Acceptance:** Fourth action rejected; second Run by same player rejected.
- [x] **Rulebook:** p. 15

### Task 4.22 — Post-touchdown reserves exception

- [x] **Goal:** Player who scored TD, returned via Reserves, may repeat action types same turn.
- [x] **Depends on:** 4.19, 4.21
- [x] **Files:** `domain/rules/TurnTracking.kt` (`clearActionsUsedForPlayer` on TD score); `ActionResolver.kt`
- [x] **Acceptance:** TD scorer can Run again after Reserves in same turn if actions remain.
- [x] **Note:** Touchdown resolution clears `actionsUsedThisTurn` for the scorer when they leave the pitch — no separate TD-scorer set on flow.
- [x] **Rulebook:** p. 19 — Reserves example

### Group G — Throw and bounce

### Task 4.23 — ThrowRangeCalculator

- [x] **Goal:** Determine Short vs Long range from thrower to target square using throw ruler geometry (square-distance bands acceptable for v1).
- [x] **Depends on:** 2.2, 1.2
- [x] **Files:** `domain/rules/ThrowResolver.kt`
- [x] **Acceptance:** Near targets Short; far targets Long; geometry port allows future ruler-accurate implementation.
- [x] **Rulebook:** p. 17 — Throw Ruler

### Task 4.24 — Obstructed throw detection

- [x] **Goal:** Obstructed if target Marked OR path crosses blocked square OR Open opposition under path.
- [x] **Depends on:** 4.23, 2.3
- [x] **Files:** `domain/rules/ThrowResolver.kt`
- [x] **Acceptance:** Marked catcher flagged obstructed; Open catcher with clear path not obstructed.
- [x] **Rulebook:** p. 17 — Obstructed Throw

### Task 4.25 — Throw action resolver

- [x] **Goal:** Hand-off (adjacent) auto-success; otherwise throw check; success transfers possession; fail bounces from target.
- [x] **Depends on:** 4.23, 4.24, 4.11, 4.7, 4.9
- [x] **Files:** `domain/rules/ThrowResolver.kt`, `domain/rules/ActionResolver.kt`
- [x] **Acceptance:** Adjacent hand-off never rolls; failed throw bounces from target square; fumble bounces from thrower.
- [x] **Rulebook:** pp. 17–18 — Throw, Hand-off, Fumble

### Task 4.26 — BounceResolver

- [x] **Goal:** D8 direction → square; Open catches; Marked/Prone re-bounce; obstruction clockwise skip.
- [x] **Depends on:** 4.6, 2.1, 2.3, 4.9
- [x] **Files:** `domain/rules/BounceResolver.kt`
- [x] **Acceptance:** Bounce into Marked player triggers chain bounce; off-board direction skipped clockwise.
- [x] **Rulebook:** p. 17 — Bouncing Ball

### Task 4.27 — Prone drops ball

- [x] **Goal:** When player becomes Prone while holding ball, immediate bounce from square.
- [x] **Depends on:** 4.26
- [x] **Files:** `domain/rules/BoardMovement.kt`
- [x] **Acceptance:** Knock down with ball triggers bounce before armour check completes.
- [x] **Rulebook:** p. 11 — Prone Players

### Task 4.28 — Multi-ball and duplicate handling

- [x] **Goal:** Player with ball cannot pick up second; entering square with another ball bounces it; bounce into occupied ball re-bounces.
- [x] **Depends on:** 4.26, 4.15
- [x] **Files:** `domain/rules/BounceResolver.kt`, `domain/rules/BoardMovement.kt`
- [x] **Acceptance:** Carrier moves onto loose ball → that ball bounces; only one loose ball per square.
- [x] **Rulebook:** p. 20 — Multiple Balls in Play

### Task 4.29 — Unthrowable player ball interaction

- [x] **Goal:** Player with `-` throw cannot hold ball; ball bounces when they would enter ball square or ball bounces onto them.
- [x] **Depends on:** 4.26, 4.1
- [x] **Files:** `domain/rules/BounceResolver.kt`, `domain/rules/BoardMovement.kt`
- [x] **Acceptance:** Lineman with `-` throw running onto ball causes bounce not possession.
- [x] **Rulebook:** p. 8 — `-` Throw Value

### Group H — Block

### Task 4.30 — Block action validator

- [x] **Goal:** Blocker must be Marked; target must be opposition player blocker is Marking.
- [x] **Depends on:** 4.8, 2.3
- [x] **Files:** `domain/rules/ActionValidator.kt`
- [x] **Acceptance:** Open player cannot block; cannot block player not adjacent.
- [x] **Rulebook:** p. 19 — Block Action

### Task 4.31 — Assisted block detection

- [x] **Goal:** +1 Block Die when other teammates also Mark target.
- [x] **Depends on:** 2.3, 4.13
- [x] **Files:** `domain/rules/BlockResolver.kt`
- [x] **Acceptance:** Two orcas marking same dwarf → 2 dice for block value 1 player; choose best result.
- [x] **Rulebook:** p. 19 — Assisting a Block Action

### Task 4.32 — Block result: Smash and Flatten

- [x] **Goal:** Knock Down (Prone); Flatten applies armour -1; then armour check.
- [x] **Depends on:** 4.12, 4.13, 4.27, 4.9
- [x] **Files:** `domain/rules/BlockResolver.kt`
- [x] **Acceptance:** Failed armour → Injured (Reserves); success → remains Prone on pitch.
- [x] **Rulebook:** pp. 18–19 — Block Dice, Injured Players

### Task 4.33 — Block result: Shove

- [x] **Goal:** Push target 1 square directly away; blocked push → Knock Down. (Optional blocker follow-up → [Future F.3](../BACKLOG.md#future-items-base-game-later).)
- [x] **Depends on:** 4.32, 2.1
- [x] **Files:** `domain/rules/BlockResolver.kt`
- [x] **Acceptance:** Push into occupied/blocked/off-board causes knockdown instead; follow-up prompt deferred.
- [x] **Rulebook:** p. 18 — Shove

### Task 4.34 — Block result: Tackle and Miss

- [x] **Goal:** Tackle knocks down; both Tackle and Miss add blocker to turnEndedPlayers set.
- [x] **Depends on:** 4.32, 4.21
- [x] **Files:** `domain/rules/BlockResolver.kt`
- [x] **Acceptance:** After Tackle/Miss, same blocker cannot take further actions including any free action hook.
- [x] **Rulebook:** p. 18 — Tackle, Miss

### Task 4.35 — Block action integration

- [x] **Goal:** Wire Block command through validator, BlockResolver, action budget; add Block to ActionCommand flow.
- [x] **Depends on:** 4.30–4.34, 4.7
- [x] **Files:** `domain/rules/ActionResolver.kt`
- [x] **Acceptance:** Full block flow updates board and flow; dice recorded in ActionOutcome.
- [x] **Rulebook:** p. 19

### Group I — Touchdown

### Task 4.36 — TouchdownChecker

- [x] **Goal:** After each action, detect if active coach's player is Open, holding ball, in opponent End Zone.
- [x] **Depends on:** 2.3, 1.2, 4.9
- [x] **Files:** `domain/rules/TouchdownChecker.kt`
- [x] **Acceptance:** Marked player in end zone with ball does not score; Open player scores.
- [x] **Rulebook:** p. 20 — Scoring Touchdowns

### Task 4.37 — Touchdown resolution

- [x] **Goal:** +4 points, remove ball from play, move scorer to dugout (Reserves).
- [x] **Depends on:** 4.36, 5.1, 4.9
- [x] **Files:** `domain/rules/TouchdownChecker.kt`
- [x] **Acceptance:** Score increases by 4; ball OutOfPlay; player status Reserves, position null; `clearActionsUsedForPlayer` for scorer (4.22); turn continues.
- [x] **Rulebook:** p. 20

---

## Phase 5: Match Flow

> **Architecture:** [Turn sequence](../backend/ARCHITECTURE.md#turn-sequence-state-machine)
>
> Goal: persisted match aggregate (`MatchState` = board + config + progress + turn) and multi-turn orchestration — setup, pre-turn steps, coach alternation, win conditions. Builds on Phase 4 action resolution.

### Task 5.1 — Score and MatchConfig

- [x] **Goal:** `Score` with `addTouchdown()` (+4); `MatchConfig` with turnLimit (default 20), pitchDefinitionId, homeTeamId, awayTeamId, `coachA`.
- [x] **Depends on:** none
- [x] **Files:** `domain/model/Score.kt`, `domain/model/MatchConfig.kt`
- [x] **Acceptance:** `addTouchdown` adds 4; `leadOver` computes difference.
- [x] **Rulebook:** pp. 11, 20 — Scoring Points, Touchdowns

### Task 5.2 — Turn and progress data model

- [x] **Goal:** `TurnPhase`, `TurnState`, `MatchProgress`, `MatchConfig` on `MatchState`. **No transition logic** — data containers only. `TurnActionContext` from Phase 3 is a slice of turn state.
- [x] **Depends on:** 0.2, 5.1, 3.7
- [x] **Files:** `domain/model/TurnState.kt`, `domain/model/MatchProgress.kt`, `domain/model/MatchConfig.kt`
- [x] **Acceptance:** Phase is explicit enum; `actionsRemaining` in range 0–3 during player action phase; can derive `TurnActionContext` for legal-move queries; no `TurnEngine` in this task.
- [x] **Note:** `MatchFlow` removed (TD.5) — scores live on `MatchProgress`, turn clock on `TurnState`, frozen config on `MatchConfig`.
- [x] **Rulebook:** pp. 14–15 — Playing the Game

### Task 5.3 — MatchState wrapper

- [x] **Goal:** `MatchState(id, board, config, progress, currentTurnState)` — root persisted aggregate; `legalMovesForActiveCoach(state)` delegating to Phase 3 query + turn slice.
- [x] **Depends on:** 1.4, 5.2, 3.10
- [x] **Files:** `domain/model/MatchState.kt`, `domain/legalmoves/LegalMoves.kt` (overload)
- [x] **Acceptance:** Immutable data class; no legal-action fields on MatchState; overload matches `(board, TurnActionContext)` results.
- [x] **Rulebook:** n/a — see [ARCHITECTURE.md persisted vs ephemeral](../backend/ARCHITECTURE.md#1-persisted-state-vs-ephemeral-computation)

### Task 5.4 — MatchSetup

- [x] **Goal:** Coin toss → Coach A, deploy both teams in end zones (fixed slot order v1), place ball on trapdoor, initial turn state. **No challenge cards.** Distinct from bare roster deploy in tests — production uses [setupMatchWithCoinToss]; tests may use [setupMatch] with fixed `config.coachA`.
- [x] **Depends on:** 1.3, 5.3, 1.5, 4.26
- [x] **Files:** `domain/rules/MatchSetup.kt`, `domain/rules/MatchBootstrap.kt` (test helper `createMatchState`)
- [x] **Acceptance:** Produces valid `MatchState` with turn 1, Coach A active, 3 actions remaining, loose ball on trapdoor, `isCoachAFirstTurn = true`, `phase = PLAYER_ACTION`. Coin toss: D6 1–3 HOME wins Coach A, 4–6 AWAY. Player placement is convention-based (sorted end-zone slots), not coach choice — acceptable v1 simplification.
- [x] **Rulebook:** pp. 12–13 — Setting Up the Game

### Task 5.5 — No Ball in Play step

- [x] **Goal:** If no ball on pitch at pre-turn, spawn ball at trapdoor (random if multiple) then bounce.
- [x] **Depends on:** 4.26, 1.5, 5.4
- [x] **Files:** `domain/rules/TurnEngine.kt`, `domain/rules/MatchSetup.kt` (`spawnBallAtTrapdoor`)
- [x] **Acceptance:** Empty pitch triggers new ball; ball bounces from trapdoor square. Reuse trapdoor selection from `MatchSetup`.
- [x] **Rulebook:** p. 20 — New Ball!

### Task 5.6 — Trapdoor injury on new ball

- [x] **Goal:** Player on trapdoor when ball emerges → Injured; ball they held removed; existing ball on trapdoor removed.
- [x] **Depends on:** 5.5, 4.32
- [x] **Files:** `domain/rules/MatchSetup.kt` (`emergeBallAtTrapdoor`), `domain/rules/TurnEngine.kt`
- [x] **Acceptance:** Standing player on trapdoor goes to dugout when new ball enters.
- [x] **Rulebook:** p. 20 — New Ball!

### Task 5.7 — Emergency Reserves step

- [x] **Goal:** If dugout count ≥ team `emergencyReserves` value, grant one free Reserves Action (auto or prompt).
- [x] **Depends on:** 4.19, 1.3, 5.8
- [x] **Files:** `domain/rules/TurnEngine.kt`
- [x] **Acceptance:** 4+ injured Dwarfs (ER=4) triggers free reserves placement once per pre-turn. **Does not decrement `actionsRemaining`** — pre-turn step, not one of the three player actions. Implement via dedicated path, not `recordActionConsumed`.
- [x] **Rulebook:** p. 19 — Emergency Reserves
- [x] **Note (v1):** Placement is **auto** — first dugout player onto the first legal end-zone square. Coach choice of square (and which reserve, if ever needed) is [F.7](../BACKLOG.md#f7--emergency-reserves-coach-placement-choice).

### Task 5.8 — TurnEngine orchestration

- [x] **Goal:** Implement full turn sequence: TurnStart → PreTurn (conditional) → 3× PlayerAction → TurnEnd → swap coach. Delegate each player action to Phase 4 `ActionResolver` (includes touchdown check).
- [x] **Depends on:** 4.21, 4.36, 4.37, 5.4–5.7
- [x] **Files:** `domain/rules/TurnEngine.kt`
- [x] **Acceptance:** Coach A turn 1 skips pre-turn; Coach B turn 1 runs pre-turn; coach alternation correct. **`isCoachAFirstTurn` → `false` after Coach A's first TurnEnd** (not after the whole first round). **`progress.turnNumber` increments once per coach activation** at TurnEnd (turn limit counts activations, not full rounds). Client never POSTs `advance-turn` — see 6.4.
- [x] **Rulebook:** pp. 14–15 — Game Sequence

### Task 5.9 — Sudden Death win check

- [x] **Goal:** At TurnStart, if **active coach's team** leads by ≥10, game over SuddenDeath.
- [x] **Depends on:** 5.1, 5.8
- [x] **Files:** `domain/rules/TurnEngine.kt`
- [x] **Acceptance:** 14–3 when active coach is ahead ends game; 13–4 when active coach is ahead does not.
- [x] **Rulebook:** p. 22 — Sudden Death Victory

### Task 5.10 — Turn limit win check

- [x] **Goal:** At TurnEnd, if `turnNumber >= config.turnLimit`, game over with highest score (draw if tied).
- [x] **Depends on:** 5.8, 5.1
- [x] **Files:** `domain/rules/TurnEngine.kt`
- [x] **Acceptance:** Turn 20 complete ends game; equal scores → `MatchResult.TurnLimit(winner=null)`.
- [x] **Rulebook:** n/a — base-game substitute; see [ARCHITECTURE.md win conditions](../backend/ARCHITECTURE.md#base-game-win-conditions)

---

## Phase 6: Application and API

> **Architecture:** [Application layer](../backend/ARCHITECTURE.md#application-layer), [API design](../backend/ARCHITECTURE.md#api-design)

**Build order (thin slices, bottom-up):**

| Layer | What it is | Tasks |
|-------|------------|-------|
| `infrastructure/` | Production adapters (dice, in-memory repo) | 6.1, 6.3 |
| Spring beans | Wire adapters, catalogs, domain rule components | 6.2, 6.4 |
| `application/` | [MatchGame](../backend/src/main/kotlin/com/mhaefele/gridiron/application/MatchGame.kt) — load → mutate → save | 6.3 |
| `api/` | **Entry point** — thin `@RestController` delegates to [MatchGame](../backend/src/main/kotlin/com/mhaefele/gridiron/application/MatchGame.kt) | 6.4 |

**No pass-through service layer.** Annotated controller methods *are* the application actions: load state, call `MatchSetup` / legal-move queries / `ActionResolver` / `TurnEngine`, persist, return `MatchState`. Shared logic between verb endpoints may be `private` helpers or package-internal functions in `api/` — **not** a `MatchService` bean with one method per HTTP route (that duplicates the API for no gain). **Acceptance is API/system tests** driving `POST/GET /api/matches` and verb actions.

### Task 6.1 — RandomDiceRoller production impl

- [x] **Goal:** Secure random D6/D8/block dice for production.
- [x] **Depends on:** 4.6
- [x] **Files:** `infrastructure/RandomDiceRoller.kt`
- [x] **Acceptance:** Implements `DiceRoller`; block dice respect face distribution. Tests keep using `DeterministicDiceRoller`.
- [x] **Rulebook:** n/a

### Task 6.2 — Spring wiring (foundation)

- [x] **Goal:** Register infrastructure beans the app already needs: `RandomDiceRoller` as production `DiceRoller`; test double in `backend/src/test` with `@Primary` via `@Import` on Spring tests.
- [x] **Depends on:** 6.1
- [x] **Files:** `@Component` on dice rollers and domain rule classes
- [x] **Acceptance:** Application starts; `/health` responds; Spring tests import `DeterministicDiceRoller` and get it instead of `RandomDiceRoller`.
- [x] **Rulebook:** n/a

### Task 6.3 — Match persistence (repository + MatchGame)

- [x] **Goal:** Save/load `MatchState` by `MatchId`; [MatchGame](../backend/src/main/kotlin/com/mhaefele/gridiron/application/MatchGame.kt) owns load → mutate → save.
- [x] **Depends on:** 5.3
- [x] **Files:** `infrastructure/InMemoryMatchRepository.kt`, `application/MatchGame.kt`, `application/MatchNotFoundException.kt`
- [x] **Acceptance:** Save then find returns equal state; missing id throws `MatchNotFoundException`; `MatchGame` round-trips persisted state.
- [x] **Rulebook:** n/a

### Task 6.4 — Match API (controller entry points)

- [x] **Goal:** `POST/GET /api/matches`, `GET .../legal-actions`, separate `POST .../run|mark|throw|block|sidestep|stand-up|reserves` — **not** a single `/actions` endpoint with type discriminator. Request DTOs where input differs from domain; return `MatchState` (and `List<LegalAction>` for legal-actions) when shapes align. **[MatchGame](../backend/src/main/kotlin/com/mhaefele/gridiron/application/MatchGame.kt) owns load → mutate → save**; controllers and test bootstrap delegate — no ad-hoc `repository.save` in tests or controllers.
- [x] **Depends on:** 6.2, 6.3, 3.10, 4.8, 5.8, 5.4
- [x] **Files:** `api/MatchController.kt`, `api/dto/request/*.kt`, `api/dto/response/*.kt`, `api/mapping/MatchCommandMapping.kt`, `application/MatchGame.kt`, `api/ApiExceptionHandler.kt`
- [x] **Acceptance:** HTTP test creates match and submits one run successfully; invalid action returns error response not 500. **`POST /api/matches` uses `setupMatchWithCoinToss` + injected dice.** **Action POSTs run `ActionResolver` only during `PLAYER_ACTION`; when `actionsRemaining` hits 0, `TurnEngine` advances before response** — no client `advance-turn` endpoint. **`GET .../legal-actions` empty when not in `PLAYER_ACTION` or game over.** `/health` and `/api/matches` both respond.
- [x] **Rulebook:** n/a

---

## Phase 7: Testing

> **Architecture:** [Testing strategy](../backend/ARCHITECTURE.md#testing-strategy) — **TDD when practical**; **fluent, English-readable tests**; **parameterized tests** where inputs repeat; **broad invariant tests** over many moves/states; **pure domain tests** (no Spring); **API tests** for whole-system flows; Spring **boots once** per suite; never mock-heavy tests. **HTTP for setup when the product allows it** (including inside fixture builders); **direct fixtures** for mid-game state the API should not expose. **Convention over configuration** — override only what each test cares about.

### Task 7.0 — Test fixture builders

- [ ] **Goal:** Extend **[GameScenario](../backend/src/test/kotlin/com/mhaefele/gridiron/support/GameScenario.kt)** (seed in place) with fluent convention-over-configuration builders — e.g. `.withHomePlayer()`, `.withActionsRemaining(n)`, `.homeRunsToMark()`, optional API-backed `matchViaApi()`. Every test eventually starts from the harness, requesting only as much game as it needs. Builders may call real HTTP when setup is naturally API-reachable. Sensible hardcoded defaults for coordinates and ids when tests do not depend on them.
- [ ] **Depends on:** 5.3
- [ ] **Files:** `backend/src/test/kotlin/.../support/GameScenario.kt` (extend), `MatchFixtures.kt`, `MatchScenario.kt` (API-backed fluent wrapper)
- [ ] **Acceptance:** Domain test can build a marked-player scenario in ≤3 meaningful overrides; API test can read like English via fluent scenario helper; mid-game state via repository or API as appropriate; no production setup endpoints added.
- [ ] **Rulebook:** n/a
- [ ] **Note:** **Partial** — `GameScenario`, `BoardScenario`, `TurnContextScenario`, and `matchState()` / [MatchFixtures.kt](../backend/src/test/kotlin/com/mhaefele/gridiron/support/MatchFixtures.kt) are in place; fluent mid-game chains and `matchViaApi()` still open.

### Task 7.1 — Shared API test base (Spring boots once)

- [x] **Goal:** [BaseApiTest](../backend/src/test/kotlin/com/mhaefele/gridiron/integration/support/BaseApiTest.kt) shared by all API tests; [GridironApiClient](../backend/src/test/kotlin/com/mhaefele/gridiron/integration/support/GridironApiClient.kt) for typed HTTP (success → Kotlin object, error → `HttpStatusCodeException`); `@Import(DeterministicDiceRoller::class)`; dice `reset()`/`queue()` in `@BeforeEach` on the **same bean** (no per-test `@MockBean`, no `@DirtiesContext`). **Delete [GridironApplicationTests](../backend/src/test/kotlin/com/mhaefele/gridiron/GridironApplicationTests.kt)** once the first real API test extends this base.
- [x] **Depends on:** 6.4, 7.0
- [x] **Files:** `backend/src/test/kotlin/.../integration/support/BaseApiTest.kt`, `ApiTestConfiguration.kt`, `application-test.yml`
- [x] **Acceptance:** All API test classes extend base; Spring context cache hit (one startup per suite run); zero `@MockBean` on application code; sample HTTP create + GET works.
- [x] **Rulebook:** n/a

### Task 7.2 — Domain unit: marking, topology, paths

- [x] **Goal:** Spring-free tests for `standingStatus`, `within`, `neighbors`, `runPaths`, `markPaths` using fixture builders from 7.0. **Write tests first (TDD)** where tasks 2.1–2.6 are not yet implemented.
- [x] **Depends on:** 2.1–2.6, 1.6, 7.0
- [x] **Files:** `backend/src/test/kotlin/.../domain/MarkingCalculatorTest.kt`, `TopologyTest.kt`, `MovePathFinderTest.kt`, `BoardRendererTest.kt`
- [x] **Acceptance:** No `@SpringBootTest`; mix of `@ParameterizedTest` tables and targeted cases; exhaustive edge cases (diagonal adjacency, run blocked from opposition-adjacent square, mark must end Marking).
- [x] **Rulebook:** pp. 9–11, 16

### Task 7.3 — Domain unit: legal moves

- [x] **Goal:** Spring-free tests layered with Phase 3: `legalMovesForPlayer` (geometry/status), per-player turn restrictions (3.8), `legalMovesForTeam` (3.9), `legalMovesForActiveCoach` (3.10). Use 7.0 fixtures; TDD when implementing each layer.
- [x] **Depends on:** 3.6, 3.8, 3.9, 3.10, 7.0
- [x] **Files:** `backend/src/test/kotlin/.../domain/LegalMovesTest.kt`
- [x] **Acceptance:** Fixture state in, list of moves out; no HTTP; `@ParameterizedTest` for status/budget restriction tables where applicable.
- [x] **Rulebook:** p. 15

### Task 7.4 — API: movement drill (smoke)

- [x] **Goal:** Representative API scenario proving HTTP wiring for run/mark — reach state via `POST /api/matches` and HTTP actions where possible; use 7.0 fixtures + repository only if mid-game setup would require invented API. Not every path edge case (see 7.2–7.3).
- [x] **Depends on:** 7.0, 7.1, 4.15, 4.16
- [x] **Files:** `backend/src/test/kotlin/.../integration/MovementDrillApiTest.kt`, [MatchApiTest.kt](../backend/src/test/kotlin/com/mhaefele/gridiron/integration/MatchApiTest.kt), [MovementApiTest.kt](../backend/src/test/kotlin/com/mhaefele/gridiron/integration/MovementApiTest.kt)
- [x] **Acceptance:** Illegal run returns error; valid mark updates GET response; extends `BaseApiTest`.
- [x] **Rulebook:** pp. 5, 16

### Task 7.5 — API: bounce (rulebook diagram)

- [x] **Goal:** API scenario for bounce obstruction skip (roll 7 → skip to 1); queue dice on shared `DeterministicDiceRoller` in `@BeforeEach`.
- [x] **Depends on:** 7.1, 4.26
- [x] **Files:** `backend/src/test/kotlin/.../integration/BounceApiTest.kt`
- [x] **Acceptance:** Final ball square matches rulebook clockwise-skip example.
- [x] **Rulebook:** p. 17 — Bouncing Ball example

### Task 7.6 — API: throw, hand-off, fumble

- [x] **Goal:** API scenarios for hand-off (no roll), failed throw bounce, fumble on natural 1; scripted dice via test profile.
- [x] **Depends on:** 7.1, 4.25
- [x] **Files:** `backend/src/test/kotlin/.../integration/ThrowApiTest.kt`
- [x] **Acceptance:** Hand-off succeeds without dice; fumble places ball per rulebook.
- [x] **Rulebook:** pp. 8, 17–18

### Task 7.7 — API: blocking and injury (Drill 3)

- [x] **Goal:** API scenario for assisted block, knockdown, armour fail → injury → dugout; assert via GET match state.
- [x] **Depends on:** 7.1, 4.35
- [x] **Files:** `backend/src/test/kotlin/.../integration/BlockApiTest.kt`
- [x] **Acceptance:** Injured player in reserves; Tackle/Miss prevents further actions for blocker this turn (via legal actions endpoint).
- [x] **Rulebook:** pp. 5, 18–19 — Drill 3, Blocking

### Task 7.8 — API: touchdown (Drill 7)

- [x] **Goal:** API scenario: throw/run into opponent end zone Open with ball → +4, scorer to dugout, ball removed.
- [x] **Depends on:** 7.1, 4.37
- [x] **Files:** `backend/src/test/kotlin/.../integration/TouchdownApiTest.kt`
- [x] **Acceptance:** Score +4 in GET response; scorer no longer on pitch.
- [x] **Rulebook:** pp. 5, 20 — Drill 7, Touchdowns

### Task 7.9 — API: full match

- [x] **Goal:** Play multi-turn match entirely via API until turn limit or sudden death.
- [x] **Depends on:** 7.1, 5.8
- [x] **Files:** `backend/src/test/kotlin/.../integration/FullMatchApiTest.kt`
- [x] **Acceptance:** Match ends with `GameOver` in GET response; scores consistent with touchdowns only.
- [x] **Rulebook:** pp. 14–15, 22

### Task 7.10 — Domain unit: dice checks (optional)

- [x] **Goal:** Spring-free tests for throw/armour check math and `DeterministicDiceRoller` queue — only if not covered elsewhere.
- [x] **Depends on:** 4.10–4.12, 4.6
- [x] **Files:** `backend/src/test/kotlin/.../domain/dice/DiceCheckTest.kt`, `.../domain/dice/BlockDieTest.kt`, `.../support/DeterministicDiceRollerTest.kt`
- [x] **Acceptance:** No Spring, no mocks; skip files if redundant.
- [x] **Rulebook:** p. 8

### Task 7.11 — API invariant: legal moves and resolution

- [x] **Goal:** Broad-coverage test(s): from a fully deployed default board, enumerate legal moves played via HTTP asserting **invariants** — occupancy consistent, at most one ball per square, action budget valid, etc. **Use `@ParameterizedTest` with `@MethodSource` per case** so failures identify the offending action.
- [x] **Depends on:** 3.10, 4.9, 7.0
- [x] **Files:** `backend/src/test/kotlin/.../integration/LegalMovesInvariantApiTest.kt`, [BoardAssertions.kt](../backend/src/test/kotlin/com/mhaefele/gridiron/integration/support/BoardAssertions.kt)
- [x] **Acceptance:** Each legal move is a parameter row; single clear invariant per test method; readable fluent setup; extends `BaseApiTest`.
- [x] **Rulebook:** n/a

### Task 7.12 — Action unit-test coverage gaps

- [x] **Goal:** JaCoCo on action / legal-move packages; close meaningful resolution gaps with **functional** tests — `GameScenario` setup, HTTP play/assert — not class-oriented resolver unit tests.
- [x] **Depends on:** 7.0 helpers where they cut setup cost
- [x] **Files:** `backend/pom.xml` (JaCoCo); `ThrowApiTest`, `BounceApiTest`, `BlockApiTest`, `TopologyTest`; `backend/target/site/jacoco/`
- [x] **Shipped behaviors:** successful short throw; bounce pickup / marked re-bounce; smash drops carrier ball; `shortestPath` endpoints; restored `TeamActionConsumed` emission; Get-the-Ball bot bias keeps Stand Up / Throw.
- [x] **Wontfix / covered elsewhere:** `error(...)` guards; Quick Release / Expert Passer / Violent / Catch and Go / bonus windows — ability & bonus API suites; orchestrator beans not vacuum-tested (testing strategy).
- [x] **Acceptance:** Coverage reviewed; suite green.
- [x] **Rulebook:** n/a

---

## Phase 8: Client API & event protocol *(done)*

> **Architecture:** [API design](../backend/ARCHITECTURE.md#api-design) (commands, events, response DTOs)
> **Done:** Synchronous client wire shapes — self-describing events, response DTOs, slim create/action envelopes, Jackson peeled off internal domain types ([TD.8](#td8--slim-match-state-json-for-clients)). Async push / hydrate / `InputRequired` follow in [Phase 9](#phase-9-async-client-protocol) *(also done)*.

**Historical order:** Client read DTOs first ([8.1](#task-81--piece-snapshot-read-api)–[8.4](#task-84--match-game-state-read-api)), then slim action/create envelopes ([8.5](#task-85--slim-action-response-events--snapshot-no-board), [8.9](#task-89--slim-create-match-response)).

**Principle (current):** Clients may apply events locally ([8.7](#task-87--every-event-reconstructs-board-deltas)). Mutable hydrate is **`GET .../snapshot`** → [MatchSnapshotDto](#task-95--mutable-sync-snapshot-hydrate-for-sse) (`lastEventId` + `gameState` + `pieces`); Phase 9 retired standalone `GET .../pieces` and `GET .../game-state`. Static join reads stay **`GET .../pitch`** and **`GET .../roster`**. Action and create POSTs return **`snapshot`** (+ `events` on actions) — not full `MatchState` / `Board`. Query endpoints use **`api/dto/response/`** types ([8.10](#task-810--legal-actions-api-same-shape-response-dto)).

**Wire shape — no `ballId` on the client:** expose **`holdsBall`** on each player and **`looseBallLocations`** (coordinates only) on [PieceSnapshotDto](#task-81--piece-snapshot-read-api). Balls out of play (e.g. touchdown) are absent from both.

**Jackson cleanup:** Once a domain or persisted type is **no longer serialized on the HTTP boundary**, remove `@Json*` / dedicated serializers that existed only for that exposure. Wire annotations belong on **`api/dto/response/`** (or request DTOs). Keep `JacksonConfiguration` for cross-cutting needs DTOs still require. See [TD.10](../BACKLOG.md#td10--extract-shared-package-after-dto-work) for further `shared/` consolidation.

**Legal actions:** `GET .../legal-actions` returns [LegalAction](../backend/src/main/kotlin/com/mhaefele/gridiron/api/dto/response/LegalAction.kt) from `api/dto/response/` — ephemeral query output; no parallel duplicate type.

### Task 8.1 — Piece snapshot DTO

- [x] **Goal:** Runtime board drawing DTO — not domain `Board` / `BoardSquare` JSON. Map from `MatchState.board`.
- [x] **Depends on:** 6.4, 7.11
- [x] **Files:** `api/dto/response/PieceSnapshotDto.kt`, `api/mapping/PieceSnapshotMapping.kt`
- [x] **DTO shape:** `PieceSnapshotDto(players, looseBallLocations)` / `PlayerPieceDto(id, team, status, at?, holdsBall)`. **No `ballId` on the wire.** Open/Marked stays off this DTO — [F.5](#f5--openmarked-on-piece-snapshot-and-events-wont-do) won't do; play uses `legal-actions`.
- [x] **Note:** Landed as a standalone `GET .../pieces`; [9.5](#task-95--mutable-sync-snapshot-hydrate-for-sse) folded pieces into `MatchSnapshotDto.pieces` and retired that GET.
- [x] **Acceptance:** Integration tests assert board via snapshot / create-action responses (no `response.match.board`); covers position, status, reserves, `holdsBall`, loose ball, touchdown ball removal.
- [x] **Rulebook:** n/a

### Task 8.2 — Pitch layout read API

- [x] **Goal:** `GET /api/matches/{matchId}/pitch` returns **static terrain** for the match's `pitchDefinitionId` — grid size, end-zone rows, trapdoors, blocked squares — drawing-only; no player or ball occupancy.
- [x] **Depends on:** 6.4
- [x] **Files:** `api/MatchController.kt`, `api/dto/response/PitchLayoutDto.kt`, `api/mapping/PitchLayoutMapping.kt`
- [x] **Acceptance:** Documented JSON for `default` and `barricade`; GUI draws empty pitch + overlays [8.1](#task-81--piece-snapshot-read-api) pieces from snapshot; no `BoardSquare` sealed types on the wire.
- [x] **Rulebook:** n/a

### Task 8.3 — Match roster read API

- [x] **Goal:** `GET /api/matches/{matchId}/roster` returns per-player **card display** info keyed by `playerId` for join to pieces on the snapshot.
- [x] **Depends on:** 6.4
- [x] **Files:** `api/MatchController.kt`, `api/dto/response/MatchRosterDto.kt`, `api/mapping/MatchRosterMapping.kt`
- [x] **Acceptance:** One entry per deployed player; no `StatLine` / `TeamCard` on the wire; integration test finds known dwarf/orc lineman labels after create.
- [x] **Rulebook:** n/a

### Task 8.4 — Match game state DTO

- [x] **Goal:** Slim HUD DTO — scores, `turnNumber`, `phase`, `activeCoach`, `actionsRemaining`, game-over / `result` — no `board`.
- [x] **Depends on:** 6.4
- [x] **Files:** `api/dto/response/MatchGameStateDto.kt`, `api/mapping/MatchGameStateMapping.kt`
- [x] **Note:** Landed as standalone `GET .../game-state`; [9.5](#task-95--mutable-sync-snapshot-hydrate-for-sse) folded HUD into `MatchSnapshotDto.gameState` and retired that GET.
- [x] **Acceptance:** Fields documented; embedded on snapshot / action and create responses; no raw `MatchState` / `Board` on the wire.
- [x] **Rulebook:** n/a

### Task 8.5 — Slim action response (events + snapshot, no board)

- [x] **Goal:** Action POST responses return **`events`** plus **`MatchSnapshotDto`** — not full `MatchState` with embedded `Board`. Clients refresh via `GET .../snapshot` or apply events per [8.7](#task-87--every-event-reconstructs-board-deltas).
- [x] **Depends on:** 8.1, 8.4, 6.4, 7.11
- [x] **Files:** `api/dto/response/MatchActionResponse.kt`, `api/MatchController.kt`, `api/MatchGame.kt`
- [x] **Acceptance:** Successful action POST body contains ordered `events` and `snapshot` (no `board` key); integration tests assert pieces/HUD via `snapshot` or `GET .../snapshot`; controllers return response DTOs, not `MatchState`.
- [x] **Rulebook:** n/a
- [x] **Note:** Early Phase 8 shipped `events` + bare `gameState`; [9.5](#task-95--mutable-sync-snapshot-hydrate-for-sse) replaced the envelope field with full `snapshot`.

### Task 8.6 — Dice events: threshold and outcome

- [x] **Goal:** Dice-related `DomainEvent` entries carry **what was required** (threshold, die type, modifier cap) and **what happened** (natural roll, adjusted result, pass/fail, block face, etc.) so clients can show check UI without inferring from before/after state.
- [x] **Depends on:** 8.5, 4.10–4.14
- [x] **Files:** `game/action/dto/DomainEvent.kt`, `game/dice/DiceCheck.kt`, throw/block/armour resolvers
- [x] **Acceptance:** Throw, armour, and block-dice events are self-describing on the wire; API tests queue deterministic rolls and assert event payloads include target + result; no parallel ad-hoc `diceRolls` fields on action responses once events cover the audit trail.
- [x] **Rulebook:** p. 8 — Dice Checks

### Task 8.7 — Every event reconstructs board deltas

- [x] **Goal:** Audit every `DomainEvent` variant; each must carry enough information for a client to **apply one board patch** without diffing full before/after `Board` snapshots. Wire events use the same **no-`ballId`** vocabulary as [8.1](#task-81--piece-snapshot-read-api).
- [x] **Depends on:** 8.5, 8.6, TD.4 (partial — events already emitted on resolution path)
- [x] **Files:** `game/action/dto/DomainEvent.kt`, `game/action/` resolvers, `api/dto/response/` event projections if wire shape differs from domain
- [x] **Acceptance:** Documented event→patch table; for each event type at least one test applies the patch to a fixture and matches server snapshot pieces; no event that only makes sense with implicit server-side context.
- [x] **Rulebook:** n/a

### Task 8.9 — Slim create-match response

- [x] **Goal:** **`POST /api/matches` returns `CreateMatchResponse`** (`matchId` + `snapshot`) — not `MatchState` / `Board`. Static join still loads pitch + roster ([8.2](#task-82--pitch-layout-read-api), [8.3](#task-83--match-roster-read-api)). Action POSTs keep [MatchActionResponse](#task-85--slim-action-response-events--snapshot-no-board) (`snapshot` + `events`).
- [x] **Depends on:** 8.5, 8.1, 8.2, 8.3, 6.4
- [x] **Files:** `api/dto/response/CreateMatchResponse.kt`, `api/MatchController.kt`, `api/MatchGame.kt`
- [x] **Acceptance:** Create-match integration test asserts `CreateMatchResponse` shape; client renders opening setup from `matchId` + pitch/roster + snapshot; fat `GET /api/matches/{id}` retired ([TD.8](#td8--slim-match-state-json-for-clients)).
- [x] **Rulebook:** pp. 12–13 — Match setup

### Task 8.10 — Legal actions API (same shape, response DTO)

- [x] **Goal:** **`GET .../legal-actions` unchanged for clients** — same logical payload. Move [LegalAction](../backend/src/main/kotlin/com/mhaefele/gridiron/api/dto/response/LegalAction.kt) to `api/dto/response/`; Jackson polymorphism stays on that type.
- [x] **Depends on:** 8.5
- [x] **Files:** `api/dto/response/LegalAction.kt`, `api/MatchController.kt`, `game/legalmoves/LegalMoves.kt`
- [x] **Acceptance:** Controller returns `List<LegalAction>` from `api/dto/response/`; legal-actions JSON shape unchanged; legal-move generators construct the response type directly.
- [x] **Rulebook:** p. 15 — Actions

### Task 8.11 — Remove Jackson wiring from types off the wire

- [x] **Goal:** **Delete `@Json*` only from types no longer returned from any HTTP endpoint.** Types still on the wire keep Jackson wherever they live — package location is not the rule; HTTP exposure is.
- [x] **Depends on:** [TD.8](#td8--slim-match-state-json-for-clients)
- [x] **Done with TD.8:** Removed `@Json*` from `Board`, `BoardSquare`, `MatchProgress` / `MatchResult`, `StatLine` hierarchies; trimmed `JacksonConfiguration` map-key serializers that served only the retired GET. `DomainEvent`, `Path`, and `LegalAction` keep Jackson — still on HTTP.
- [x] **Acceptance:** Grep shows no `@Json*` on model types; wire types that remain exposed retain annotations or `JacksonConfiguration` value serializers as needed.
- [x] **Rulebook:** n/a

---

## Phase 9: Async client protocol *(done)*

> **Not rulebook scope** — transport after [Phase 8](#phase-8-client-api--event-protocol). Phase 8 is **request → response with events inline**; Phase 9 adds a **pushed event stream**, a **mutable hydrate snapshot**, and **input-required** stream events. The stream **complements** sync responses — inline `events` on action POSTs remain for tests and simple clients.
>
> **Prerequisite:** board-patchable [DomainEvent](#task-87--every-event-reconstructs-board-deltas) ([8.6](#task-86--dice-events-threshold-and-outcome), [8.7](#task-87--every-event-reconstructs-board-deltas)).
>
> **Architecture:** [ARCHITECTURE.md § Event stream](../backend/ARCHITECTURE.md#event-stream-phase-9).

### Phase 9 tech choices (locked)

| Layer | Choice | Rejected for v1 |
|-------|--------|-----------------|
| **Transport** | **SSE** (`text/event-stream`) | WebSocket (bidirectional not needed); long poll |
| **Server** | **Spring MVC `SseEmitter`** on existing `spring-boot-starter-web` | WebFlux `Flux` (stack split); STOMP/SockJS (heavy) |
| **Fan-out** | **`SseDomainEventSink`** (one client) → **`MatchEventChannel`** (per match) → **`MatchEventHub`** (by match id) | `ApplicationEventPublisher` alone (no replay/scoping) |
| **Payload** | Reuse **`DomainEvent` JSON** from action POSTs | Parallel event DTO layer (unless wire diverges later) |
| **Browser** | Native **`EventSource`**; fetch-SSE when Bearer auth lands | Custom socket protocol |
| **Reconnect** | Controller replays from repo onto the new sink; hub stays live-only | Separate replay HTTP endpoint (defer) |

**Shipped order:** 9.1 (stream + [9.2](#task-92--stream-test-client) test client) → [9.5](#task-95--mutable-sync-snapshot-hydrate-for-sse) → [9.4](#task-94--input-required-events-pull-legal-moves-on-demand).

---

### Task 9.1 — Event stream delivery (SSE)

- [x] **Goal:** `GET /api/matches/{matchId}/events` opens an SSE subscription; clients receive ordered `DomainEvent` frames after any coach POST (and after setup events when we publish them). Same patch model as Phase 8 inline `events`.
- [x] **Depends on:** 8.6, 8.7
- [x] **Files:** `api/stream/SseDomainEventSink.kt`, `MatchEventChannel.kt`, `MatchEventHub.kt`, `MatchEventStreamController.kt`, hook in `api/MatchGame.kt`
- [x] **Acceptance:**
  - [x] `produces = MediaType.TEXT_EVENT_STREAM_VALUE`; each frame has `id`, `event` (= kind), `data` (= `DomainEvent` JSON).
  - [x] One sink per connection; channel removes on close (no emitter leaks).
  - [x] `MatchGame` appends to `InMemoryDomainEventRepository`, publishes `StoredDomainEvent` to hub (same ids as inline action POST `events`).
  - [x] Reconnect: controller queries `findByMatchIdAfter` and publishes onto the new sink; hub is live fan-out only.
  - [x] Integration test (via [9.2](#task-92--stream-test-client)): subscribe → POST run → stream receives matching ordered events within timeout.
  - [x] Reconnect/catch-up strategy documented in [ARCHITECTURE.md](../backend/ARCHITECTURE.md#event-stream-phase-9) (controller repo replay).
- [x] **Rulebook:** n/a

### Task 9.2 — Stream test client

- [x] **Goal:** Thin **`SseTestClient`** for API tests — reads `text/event-stream` via `java.net.http.HttpClient` (or OkHttp), parses `id` / `event` / `data` lines, supports `Last-Event-ID` header.
- [x] **Depends on:** 9.1
- [x] **Files:** `integration/support/SseTestClient.kt`
- [x] **Acceptance:** Used by 9.1 integration test; no browser; no WebFlux test dependency.

### Task 9.5 — Mutable sync snapshot (hydrate for SSE)

- [x] **Goal:** One GET that returns **all mutable match state needed to hydrate a live client**, including `lastEventId`, so init is: snapshot → `GET .../events` with `Last-Event-ID`. Consolidated Phase 8 split mutable reads (`pieces` + `game-state` DTOs) into [MatchSnapshotDto](../backend/src/main/kotlin/com/mhaefele/gridiron/api/dto/response/MatchSnapshotDto.kt); TD.8 had already retired fat `GET /api/matches/{id}`.
- [x] **Depends on:** 8.1, 8.4, 9.1 (`lastEventId` on `MatchState` / `MatchSnapshotDto`)
- [x] **Out of scope:** static pitch layout ([8.2](#task-82--pitch-layout-read-api)) and roster cards ([8.3](#task-83--match-roster-read-api)) — load separately at join; do not re-bundle into this snapshot.
- [x] **Files:** `MatchSnapshotDto`, `GET .../snapshot`, create/action responses use `snapshot`; retired standalone `pieces` / `game-state` GETs
- [x] **Acceptance:**
  - [x] One endpoint returns piece positions + HUD fields + `lastEventId` in one response (atomic relative to the event log cursor).
  - [x] Integration: create/play → hydrate → subscribe with returned `lastEventId` → no duplicate/missed patches for subsequent actions.
  - [x] Documented client init in [ARCHITECTURE.md](../backend/ARCHITECTURE.md#event-stream-phase-9): join (pitch + roster) → hydrate → SSE; reconnect = hydrate → re-subscribe.
  - [x] Phase 8 split mutable GETs consolidated into this endpoint.
- [x] **Rulebook:** n/a

### Task 9.4 — Input-required events (pull legal moves on demand)

- [x] **Goal:** Stream emits **`InputRequired`** when the server needs coach input (active player's turn, [F.1](../BACKLOG.md#f1--pending-action--coach-prompt-flow) prompt, etc.). Client calls `GET .../legal-actions`, presents choices, POSTs command. Legal moves are **not** embedded in the event. Replaces a separate HTTP `nextStep` field — stream + hydrate are enough for “what to do next.”
- [x] **Depends on:** 9.1, 9.5, 8.10; F.1 for prompt variants
- [x] **Files:** `DomainEvent.InputRequired` (+ `PlayerAction`); `MatchGame` appends after create/action; stream via hub
- [x] **Acceptance:** Action / create append `playerActionRequired` for the active coach when still in `PLAYER_ACTION`; SSE carries it with resolution events; piece replay treats `InputRequired` as no-op. Prompt subclasses later via F.1.
- [x] **Rulebook:** pp. 14–15 — Turn sequence

---

## Frontend (React GUI) — Phases 10–15

> **Architecture:** [Frontend](../frontend/ARCHITECTURE.md) · [System topology](../ARCHITECTURE.md#module-boundaries)
>
> The client **draws state and submits commands** — it never re-implements rules. Backend Phases 0–9 (the whole client surface: REST verbs, `legal-actions`, `snapshot`, `pitch`, `roster`, SSE `events`, `InputRequired`) are done and are the contract these phases consume. Geometry is data-driven from `PitchLayoutDto`; nothing hardcodes the pitch size or re-derives rules from the PDF.
>
> **Rulebook:** n/a for client tasks unless noted — the backend already encodes rulebook fidelity.

### Module convention for these phases

Tasks that touch the Kotlin server are tagged **Module: backend** (under `backend/`, follow [backend/.cursor/rules](../backend/.cursor/rules/)). **Every other task is frontend** (TypeScript under `frontend/`, follows [frontend/.cursor/rules](../frontend/.cursor/rules/)). A "lesser" agent should read the task's **Architecture** link and the linked DTO/source files before starting, and obey the rules for that module.

### Backend enablers (do these first — gathered as Phase A)

Most GUI work consumes the done backend as-is. Four **backend** tasks are still required to fully support the client. They are gathered into **[Phase A](#phase-a-backend-enablers-do-first--tight-review)** and done **first**, under tight commit-by-commit review, before the frontend blast. Suggested order: `10b.1 → 10b.2 → 13.6 → 15.1` (IDs are retained from their original phases):

| Backend task | Enables | Blocks |
|--------------|---------|--------|
| [10b.1](#task-10b1--backend-openapi-spec) OpenAPI spec | typed client + drift guard | 10b.3–10b.5 |
| [10b.2](#task-10b2--sealed-types-as-discriminated-schemas) `oneOf`/discriminator on sealed types | discriminated-union codegen | 10b.3, 13.1 |
| [13.6](#task-136--turnprogress-events-on-the-stream-backend) Turn/progress events on the stream | live HUD for spectators | 13.5 (removes the refresh workaround) |
| [15.1](#task-151--seedable-rng-backend-test-profile-enabler) Seedable RNG profile | deterministic e2e | 15.2 |

### First pixels — earliest visible progress

- **App shell on screen:** [10.1](#task-101--scaffold-vite--react--typescript) (blank app) → [10.4](#task-104--app-shell-and-screen-routing) (empty screens).
- **A real, live game board on screen:** [11.2](#task-112--svg-pitch-from-pitchlayoutdto) (pitch) + [11.3](#task-113--piece-overlay-from-piecesnapshotdto) (pieces), which depend on [11.1](#task-111--create--join-screen-from-catalog) create/join → so the practical path to "see the opening position" is **[Phase A](#phase-a-backend-enablers-do-first--tight-review) → 10.1 → [10.7](#task-107--vite-dev-proxy) → 10.4 → 10b.3/10b.5 → 11.1 → 11.2 → 11.3**. Interaction (clicking to move) starts at [Phase 12](#phase-12-interactive-actions); live opponent/turn updates at [Phase 13](#phase-13-event-driven-live-updates).

### Auto-run playbook (hobby project)

> **Project context:** personal **hobby project — no deployment, no remote git, no cloud CI.** Read "CI" / "drift guard" in these tasks as a **local npm script (optionally a git pre-commit hook)**, not a hosted pipeline. Anything about production hosting, deployment topology, or cross-origin CORS in prod is forward-looking only and can be skipped today.

**Preflight — do once before Phase A:**

- [x] **Install Node.** Done — `node` v26.5.0 / `npm` 11.17.0 via Homebrew (`/opt/homebrew/bin`). Java 21 + Maven were already present.
- [x] **Install Playwright browsers.** Done ahead of Phase 15 — Chromium, Firefox, WebKit, and ffmpeg cached under `~/Library/Caches/ms-playwright` (via `npx playwright install`). Re-run from `frontend/` after Playwright is pinned in `package.json` if the project version drifts from the cache.
- **Installs need network.** Later `npm install` (and a re-fetch of Playwright browsers if the pin changes) still reach the network; grant network / `full_network` when prompted.
- **springdoc + Spring Boot 4.1 caveat (Task 10b.1).** This repo is on Spring Boot 4.1 — pin a `springdoc-openapi` version compatible with Boot 4 / Spring Framework 7. If none is available, **stop and report** rather than thrashing; the type-sync can fall back to producing the OpenAPI JSON via a serialization test instead of the springdoc UI.

**Two very different execution styles — backend tight, frontend loose:**

- **Batch 0 — [Phase A](#phase-a-backend-enablers-do-first--tight-review) (backend, tight rein).** Do the four backend enablers **first** and review them the way you did Phases 0–9: one focused change at a time, commit-by-commit. They touch the Kotlin server (`pom.xml`, `DomainEvent`, `TurnEngine`, Spring config), so keep them out of the frontend blast. Order: `10b.1 → 10b.2 → 13.6 → 15.1`. After this the frontend has a stable typed contract, live HUD events, and deterministic dice.
- **Then blast the frontend.** With Phase A done, Phases 10–15 are **pure frontend** and safe to throw at Auto in bulk:
    - **Max aggression:** tell Auto **"implement Phases 10–15."** One long run using documented defaults (Vite, npm, Tailwind, Zustand). Clean up after.
    - **One optional checkpoint:** Batch 1 = **Phases 10 + 10b + 11** → stop when the **opening board is on screen** (first pixels) and eyeball it; Batch 2 = **Phases 12 + 13 + 14 + 15**. Keep 12 and 13 together (Task 12.3 folds events from 13.1).

**Every batch must end by telling you how to run it.** The implementing agent updates [`frontend/README.md`](#task-105--run-instructions-readme) (Task 10.5) and the [`frontend/TOUR.md`](#task-106--frontend-tour-for-backend-devs-tutorial-doc) backend-dev walkthrough (Task 10.6), and reports the exact run commands in chat after each batch.

## Phase A: Backend enablers (do first — tight review)

> **Architecture:** [Frontend § API & DTO synchronization](../frontend/ARCHITECTURE.md#api--dto-synchronization), [System § shared contract](../ARCHITECTURE.md#shared-contract--keep-the-two-modules-in-lockstep)
>
> Every **backend-module** task the GUI needs, gathered up front. Do these **first**, reviewed commit-by-commit like Phases 0–9 — they change the Kotlin server (`pom.xml`, `DomainEvent`, `TurnEngine`, Spring config), so finishing them before the frontend blast keeps the two review styles from mixing. **Task IDs are retained from their original phases** (`10b.1`, `10b.2`, `13.6`, `15.1`) so every cross-reference elsewhere stays valid. Suggested order: `10b.1 → 10b.2 → 13.6 → 15.1`.

### Task 10b.1 — Backend OpenAPI spec

- [x] **Module:** backend
- [x] **Goal:** Add `springdoc-openapi` so the running backend serves `/v3/api-docs`; the spec covers `/api/matches/*` and `/api/catalog/*`.
- [x] **Depends on:** 6.4 (done)
- [x] **Files:** `backend/pom.xml` (add `org.springdoc:springdoc-openapi-starter-webmvc-ui`), minimal springdoc config if needed
- [x] **Acceptance:** `GET /v3/api-docs` returns a valid OpenAPI 3 document listing all client endpoints; existing API tests still pass. Add one API test asserting `/v3/api-docs` is 200 and contains `/api/matches`.
- [x] **Rulebook:** n/a

### Task 10b.2 — Sealed types as discriminated schemas

- [x] **Module:** backend
- [x] **Goal:** Ensure `DomainEvent` and `LegalAction` appear in the spec as `oneOf` + `discriminator: kind` so codegen yields TS discriminated unions (enables the exhaustive `applyEvent` switch).
- [x] **Depends on:** 10b.1
- [x] **Files:** [OpenApiSealedTypesConfiguration.kt](../backend/src/main/kotlin/com/mhaefele/gridiron/spring/OpenApiSealedTypesConfiguration.kt) — springdoc only inferred `discriminator.propertyName`; it did not emit `oneOf`/`mapping`, and `@Schema(oneOf)` conflicts with `@JsonSubTypes`. Customizer rewrites both parents from Jackson annotations and flattens leaf schemas (breaks parent↔child `allOf` cycles).
- [x] **Acceptance:** Spec `components.schemas` for both types use `oneOf` with a `kind` discriminator mapping every subtype; a smoke test asserts each subtype name is present.
- [x] **Rulebook:** n/a

### Task 13.6 — Turn/progress events on the stream (backend)

- [x] **Module:** backend
- [x] **Goal:** Close the HUD-on-stream gap at the source. Today the SSE stream carries board patches + `InputRequired` only; HUD fields (`turnNumber`, `phase`, `activeCoach`, `actionsRemaining`, and non-touchdown score changes) are **not** individual events, so a pure spectator cannot keep the HUD live from the stream alone. Emit turn/progress changes as `DomainEvent`s (e.g. granular `TurnAdvanced(activeCoach, turnNumber, phase)` and an action-budget delta, plus confirm score is fully derivable — `TouchdownScored` already covers touchdown points) so the client folds HUD state from the stream like any other patch.
- [x] **Depends on:** 8.6, 8.7, 9.1 (all done); related to [F.6](../BACKLOG.md#f6--per-player-action-constraints-on-the-wire-open). Domain turn fold ([TD.9](#td9--event-driven-turn-and-progress-updates-wont-do)) won't do — mutator dual-write + stream events suffice.
- [x] **Files:** [DomainEvent.kt](../backend/src/main/kotlin/com/mhaefele/gridiron/game/action/dto/DomainEvent.kt) (new variants + `@JsonSubTypes` registration), [MatchMutator.kt](../backend/src/main/kotlin/com/mhaefele/gridiron/game/turn/MatchMutator.kt) (record each HUD beat in the same verb that mutates turn/progress), integration + turn-engine tests with a HUD fold oracle
- [x] **Acceptance:** After each action and each turn advance, the stream carries the HUD deltas; a spectator folding **only** events keeps `turnNumber` / `phase` / `activeCoach` / `actionsRemaining` / scores consistent with `GET .../snapshot`; inline action-POST `events` and existing patch clients are unaffected (new kinds are additive); frontend `applyEvent` gains arms for the new kinds and drops the 13.5 refresh. Update the [frontend HUD-gap note](../frontend/ARCHITECTURE.md#known-gap--hud-is-not-on-the-event-stream) once shipped.
- [x] **Note (shipped):** Lean on [MatchMutator](../backend/src/main/kotlin/com/mhaefele/gridiron/game/turn/MatchMutator.kt) — named beats (`teamActionConsumed`, `turnEnded`, `turnBegan`, `playerActionPhaseOpened`, `matchEnded`), not a generic "value changed" dump. Coach handoff lives inside `startTurn` (no separate swap event). [TD.9](#td9--event-driven-turn-and-progress-updates-wont-do) (fold turn state *from* events) won't do.
- [x] **Rulebook:** n/a

### Task 15.1 — Seedable RNG backend test profile (enabler)

- [x] **Module:** backend
- [x] **Goal:** A `ScriptedDiceRoller` on profiles `test` and `scripted-dice` — unit/API tests throw if a queue is empty (`fallback-to-default-pattern=false`); e2e leaves the default `true` plus `POST /api/test/dice/{d6|d8|block}`. Production `RandomDiceRoller` unchanged.
- [x] **Depends on:** 6.1 (done)
- [x] **Files:** `game/dice/ScriptedDiceRoller.kt`, `api/testonly/ScriptedDiceController.kt`, `src/test/resources/application-test.yml`
- [x] **Acceptance:** Boot with `--spring.profiles.active=scripted-dice` yields reproducible dice with default pattern + optional HTTP queues; Spring tests use the same roller with throw-on-empty; default profile still uses secure random.
- [x] **Rulebook:** n/a

---

## Phase 10: Frontend Foundation

> **Architecture:** [Frontend § Tech stack](../frontend/ARCHITECTURE.md#tech-stack), [§ Directory layout](../frontend/ARCHITECTURE.md#directory-layout)
>
> Goal: a running Vite/React/TypeScript app shell with strict typing, lint, agent rules, and context hygiene — no game logic yet.

### Task 10.1 — Scaffold Vite + React + TypeScript

- [x] **Goal:** Create the `frontend/` app with Vite (React + TS template); dev server runs; empty app renders.
- [x] **Depends on:** none
- [x] **Files:** `frontend/package.json`, `vite.config.ts`, `index.html`, `src/main.tsx`, `tsconfig.json`
- [x] **Acceptance:** `npm run dev` serves a blank app; `npm run build` succeeds; committed lockfile.

### Task 10.7 — Vite dev proxy

- [x] **Goal:** Proxy `/api` from the Vite dev server (`:5173`) to the backend (`:8080`) so browser `fetch`/`EventSource` are same-origin and need no CORS.
- [x] **Depends on:** 10.1, 6.4 (done)
- [x] **Files:** `frontend/vite.config.ts` (`server.proxy` for `/api`)
- [x] **Acceptance:** With the backend running, the dev client can `POST /api/matches`, `GET .../snapshot`, and open `GET .../events` through the proxy without CORS errors, and SSE frames flush (no proxy buffering).
- [x] **Note:** Backend CORS for true cross-origin hosting is parked under [P.1](../BACKLOG.md#p1--backend-cors-if-ui-and-api-are-different-origins) (unlikely soon — this proxy is enough for local hobby use). Also proxies `/v3/api-docs` for type generation.
- [x] **Rulebook:** n/a

### Task 10.2 — Strict TypeScript + lint/format

- [x] **Goal:** Enable `strict`, `noUncheckedIndexedAccess`, `exactOptionalPropertyTypes`; ESLint (ban `any`) + Prettier aligned with [code-style](../.cursor/rules/code-style.mdc) (four-space indent, flow over wrapping).
- [x] **Depends on:** 10.1
- [x] **Files:** `tsconfig.json`, `.eslintrc.*`, `.prettierrc`
- [x] **Acceptance:** `npm run lint` and `tsc --noEmit` pass on the scaffold; `any` triggers a lint error.

### Task 10.3 — Frontend agent rules + context hygiene

- [x] **Goal:** Frontend `.cursor/rules/` (TS style + scope discipline: backend is the rules source of truth, consume generated types, never re-implement rules); repo `.cursorignore` and `.gitignore` keep AI context scoped and build output out of git.
- [x] **Depends on:** none
- [x] **Files:** `frontend/.cursor/rules/frontend-style.mdc`, `.cursorignore`, `.gitignore`
- [x] **Acceptance:** Rules glob `frontend/**/*.{ts,tsx}`; `.cursorignore` excludes the rulebook PDF and build output (reference art is intentionally kept indexable for Phase 14 style-matching); `.gitignore` excludes `node_modules/`, `dist/`.
- [x] **Note:** Rules + ignore files land alongside this architecture task.

### Task 10.4 — App shell and screen routing

- [x] **Goal:** Screen scaffolding for create/join, play, and game-over ([Frontend § Screen map](../frontend/ARCHITECTURE.md#screen-map)); no data wiring yet.
- [x] **Depends on:** 10.1
- [x] **Files:** `src/app/`, `src/main.tsx`
- [x] **Acceptance:** Navigable empty screens; play view lays out placeholders for pitch, HUD, legal-action panel, event log.

### Task 10.5 — Run instructions (README)

- [x] **Goal:** A `frontend/README.md` that documents **exactly how to run everything** — backend (`cd backend && mvn spring-boot:run`), frontend dev (`cd frontend && npm run dev`), type generation (`npm run gen:api`, backend must be up), unit/component tests (`npm test`), and e2e (`npm run e2e`, added in Phase 15). Grows as later phases add scripts.
- [x] **Depends on:** 10.1
- [x] **Files:** `frontend/README.md` (and a short "Running the app" pointer from the root `README`/docs if one exists)
- [x] **Acceptance:** A newcomer can start the backend + frontend and open the app in a browser following only the README; every command listed works at the phase it is introduced. **The implementing agent also reports these commands in chat at the end of each batch** (see the [Auto-run playbook](#auto-run-playbook-hobby-project)).

### Task 10.6 — Frontend tour for backend devs (tutorial doc)

- [x] **Goal:** A living `frontend/TOUR.md` written **for a Java/Kotlin backend developer with little frontend experience** — a guided tour of what was built and *why*, not API reference. Explain the toolchain and mental-model mappings from the JVM world, e.g.: **Vite** (dev server + bundler ≈ Spring Boot devtools + Maven build), **npm/`package.json`** (≈ Maven `pom.xml`/dependencies + goals), **TypeScript strict mode** (≈ Kotlin null-safety/sealed types; discriminated unions ≈ sealed `when`), **React components + JSX** (declarative UI vs server-rendered), **Zustand store** (≈ a thin app-state holder; the pure `applyEvent` fold ≈ the backend event fold), **SSE/`EventSource`** (the same stream the backend emits), **Vitest/Playwright** (≈ JUnit/black-box scenario tests), and the **OpenAPI type-sync** flow (why types are generated, never hand-copied). Include a "where things live" file-map and a "how a click becomes a command becomes an event" walkthrough.
- [x] **Depends on:** 10.1 (seed early; expand as each phase lands)
- [x] **Files:** `frontend/TOUR.md`
- [x] **Acceptance:** A backend dev can read `TOUR.md` and understand the frontend's structure, each tool's role (with a JVM analogy), and the data flow from user action → API command → domain event → re-render, without prior React/Vite knowledge. Updated at the end of each batch alongside the [README](#task-105--run-instructions-readme).

---

## Phase 10b: API type-sync tooling

> **Architecture:** [Frontend § API & DTO synchronization](../frontend/ARCHITECTURE.md#api--dto-synchronization), [System § shared contract](../ARCHITECTURE.md#shared-contract--keep-the-two-modules-in-lockstep)
>
> Goal: turn the backend OpenAPI spec (produced in [Phase A](#phase-a-backend-enablers-do-first--tight-review)) into typed TypeScript and a thin runtime client. **Frontend-only** — OpenAPI + discriminators are Phase A ([10b.1](#task-10b1--backend-openapi-spec), [10b.2](#task-10b2--sealed-types-as-discriminated-schemas)); the Vite proxy is [10.7](#task-107--vite-dev-proxy). Frontend generates **types only**; a **local** drift check catches staleness (no cloud CI — see [playbook](#auto-run-playbook-hobby-project)). No hand-written DTO mirrors.

### Task 10b.3 — Generate TypeScript types

- [x] **Goal:** `openapi-typescript` generates `src/api/generated/openapi.ts` from the spec via `npm run gen:api`; **types only**, no runtime client.
- [x] **Depends on:** 10b.1, 10b.2, 10.2
- [x] **Files:** `frontend/package.json` (script), `src/api/generated/openapi.ts` (committed)
- [x] **Acceptance:** Generated file compiles under strict TS; `DomainEvent`/`LegalAction` are discriminated unions on `kind`; the file is never hand-edited.

### Task 10b.4 — Drift guard (local)

- [x] **Goal:** A **local** guard — no cloud CI in this project. An `npm run gen:api:check` script regenerates types against the running backend and fails on any diff versus the committed file; optionally wire it as a git pre-commit hook.
- [x] **Depends on:** 10b.3
- [x] **Files:** `frontend/package.json` (`gen:api:check`), optional pre-commit hook (`.husky/` or a plain `.git/hooks` script)
- [x] **Acceptance:** With the backend running, `npm run gen:api:check` exits non-zero when the committed types are stale and zero when in sync, with a clear message; documented in `frontend/README.md`.

### Task 10b.5 — Thin typed client + stream reader

- [x] **Goal:** Hand-written `client.ts` (fetch; typed by generated shapes) and `stream.ts` (fetch SSE with `Last-Event-ID`; named events need more than bare EventSource) — the only runtime API code.
- [x] **Depends on:** 10b.3, [10.7](#task-107--vite-dev-proxy)
- [x] **Files:** `src/api/client.ts`, `src/api/stream.ts`
- [x] **Acceptance:** Client methods return generated response types; non-2xx surfaces a typed error; stream yields parsed `DomainEvent`s. Swappable to fetch-SSE when Bearer auth lands.

> **Note:** OpenAPI + discriminators ([10b.1](#task-10b1--backend-openapi-spec), [10b.2](#task-10b2--sealed-types-as-discriminated-schemas)) live in [Phase A](#phase-a-backend-enablers-do-first--tight-review); the Vite proxy is [10.7](#task-107--vite-dev-proxy).

---

## Phase 11: Pitch & HUD rendering

> **Architecture:** [Frontend § Rendering](../frontend/ARCHITECTURE.md#rendering--responsiveness), [§ State model](../frontend/ARCHITECTURE.md#state-model)
>
> Goal: draw a match from the read APIs — read-only, no interaction yet.

### Task 11.1 — Create / join screen from catalog

- [x] **Goal:** Read `GET /api/catalog/teams` + `.../pitches`; pick teams, pitch, optional turn limit; `POST /api/matches`; navigate to play view with `matchId` + opening `snapshot`.
- [x] **Depends on:** 10.4, 10b.5, [10.7](#task-107--vite-dev-proxy)
- [x] **Files:** `src/app/CreateMatch.tsx`
- [x] **Acceptance:** Selecting Dwarves vs Orcs on the default pitch creates a match and lands on the play view.

### Task 11.2 — SVG pitch from `PitchLayoutDto`

- [x] **Goal:** Render the pitch as a responsive `<svg viewBox>` from `GET .../pitch` — grid, end-zone rows, trapdoors, blocked squares. No hardcoded dimensions.
- [x] **Depends on:** 11.1
- [x] **Files:** `src/render/Pitch.tsx`
- [x] **Acceptance:** `default` and `barricade` layouts draw correctly and scale to container width; squares carry coordinate `aria-label`s.

### Task 11.3 — Piece overlay from `PieceSnapshotDto`

- [x] **Goal:** Overlay player tokens (team, status, `holdsBall`) and loose balls from `snapshot.pieces`; reserves render off-pitch in a dugout strip. **No art files yet** — tokens are code-drawn **SVG primitives** (a team-colored disc + a position initial, dimmed/rotated for prone, a ball glyph for `holdsBall`). Art assets replace these in [Phase 14](#phase-14-assets--theming) via `AssetRegistry` without changing this component's logic.
- [x] **Depends on:** 11.2
- [x] **Files:** `src/render/Token.tsx`, `src/render/Dugout.tsx`
- [x] **Acceptance:** Opening snapshot shows both teams deployed and the trapdoor ball; standing vs prone and ball-carrier are visually distinct — using only shapes/colors/text, no image assets.

### Task 11.4 — Roster cards from `MatchRosterDto`

- [x] **Goal:** Show per-player card data (`PlayerCardDto`: position, move, block dice, throw/armour, ability) joined by `playerId`; hover/select a token reveals its card.
- [x] **Depends on:** 11.3
- [x] **Files:** `src/render/PlayerCard.tsx`
- [x] **Acceptance:** Selecting a dwarf lineman shows its stats; unthrowable (`—`) and no-armour cases render clearly.

### Task 11.5 — HUD from `MatchGameStateDto`

- [x] **Goal:** Header HUD: scores, turn number, phase, active coach, actions remaining, result on game over.
- [x] **Depends on:** 11.1
- [x] **Files:** `src/render/Hud.tsx`
- [x] **Acceptance:** HUD matches `snapshot.gameState`; game-over state shows the `MatchResult` winner or draw.

---

## Phase 12: Interactive actions

> **Architecture:** [Frontend § Rendering (affordances)](../frontend/ARCHITECTURE.md#rendering--responsiveness), backend [§ API endpoints](../backend/ARCHITECTURE.md#api-endpoints-base-game)
>
> Goal: the active coach picks and submits actions; the client maps choices to request DTOs and reconciles from the response.

### Task 12.1 — Fetch and highlight legal actions

- [x] **Goal:** `GET .../legal-actions`; highlight each `LegalAction.targetCoordinate`; group by `actionType` for the acting player.
- [x] **Depends on:** 11.3
- [x] **Files:** `src/render/LegalActionPanel.tsx`
- [x] **Acceptance:** Highlights match the returned list; empty when not this coach's turn / game over.

### Task 12.2 — Command dispatch per verb

- [x] **Goal:** Selecting a legal action POSTs the matching verb (`run`/`mark`/`sidestep` → `PathActionRequest`; `throw`/`block` → `TargetActionRequest`; `reserves` → `ReservesActionRequest`; `stand-up` → no body).
- [x] **Depends on:** 12.1, 10b.5
- [x] **Files:** `src/api/client.ts`, `src/app/Play.tsx`
- [x] **Acceptance:** A legal run/mark/throw/block/stand-up/reserves each submits successfully and updates the board.

### Task 12.3 — Reconcile from action response

- [x] **Goal:** Apply the `MatchActionResponse` (`snapshot` + `events`) after a POST — HUD from `snapshot`, board from folding `events`.
- [x] **Depends on:** 12.2, 13.1
- [x] **Files:** `src/state/store.ts`
- [x] **Acceptance:** After an action, board and HUD reflect the response without a manual refresh.

### Task 12.4 — Error handling

- [x] **Goal:** Surface 4xx (illegal command, wrong phase) as a non-blocking message; leave the board unchanged.
- [x] **Depends on:** 12.2
- [x] **Files:** `src/api/client.ts`, error UI
- [x] **Acceptance:** An illegal POST shows a readable message; the view state is untouched.

---

## Phase 13: Event-driven live updates

> **Architecture:** [Frontend § The event-driven loop](../frontend/ARCHITECTURE.md#the-event-driven-loop-responsiveness-during-the-opponents-turn), [§ event-fold reducer](../frontend/ARCHITECTURE.md#the-event-fold-reducer)
>
> Goal: the UI stays live during any coach's turn via SSE, folding `DomainEvent` patches and becoming interactive only on `InputRequired`.

### Task 13.1 — Pure `applyEvent` reducer

- [x] **Goal:** Pure `applyEvent(view, event)` folding every `DomainEvent` kind into a `BoardView`; exhaustive switch (compile error on an unhandled kind); no React/fetch imports.
- [x] **Depends on:** 10b.3
- [x] **Files:** `src/state/boardView.ts`, `src/state/applyEvent.ts`
- [x] **Acceptance:** Vitest fixtures (captured from real backend responses) fold to a view matching the server `snapshot.pieces` for run/mark/throw/bounce/block/touchdown sequences.

### Task 13.2 — SSE subscribe + connection state machine

- [x] **Goal:** `EventSource` on `GET .../events` with `Last-Event-ID`; store state machine `connecting → hydrating → spectating → awaitingInput`; fold every frame regardless of whose turn.
- [x] **Depends on:** 13.1, 10b.5
- [x] **Files:** `src/api/stream.ts`, `src/state/store.ts`
- [x] **Acceptance:** Board patches apply live while spectating; heartbeat/keepalive does not disturb state.

### Task 13.3 — Hydrate / reconnect

- [x] **Goal:** Join = `pitch` + `roster` + `snapshot` then subscribe with `snapshot.lastEventId`; on gap, re-fetch snapshot, reset view, re-subscribe.
- [x] **Depends on:** 13.2
- [x] **Files:** `src/state/store.ts`
- [x] **Acceptance:** Dropping and restoring the connection yields no missed or duplicated patches.

### Task 13.4 — `InputRequired` gating

- [x] **Goal:** Enter interactive mode only on `DomainEvent.InputRequired.PlayerAction` addressed to the local coach; otherwise stay spectating.
- [x] **Depends on:** 13.2, 12.1
- [x] **Files:** `src/state/store.ts`, `src/app/Play.tsx`
- [x] **Acceptance:** Legal-action UI appears only when it is this coach's turn; opponent turns render live but non-interactive.
- [x] **Note (seat identity):** v1 is **hotseat** — one browser, and "the local coach" is simply the active coach (`snapshot.gameState.activeCoach`), so gating reduces to "are we in `PLAYER_ACTION`." A true two-client seat model (this browser controls HOME only) needs the backend to tell a client which seat it holds — deferred until multiplayer/auth lands (see [system § auth/seats](../ARCHITECTURE.md#client--engine-contract-summary)). Design the gate around a `localCoach` value so swapping "active coach" for a real seat later is a one-line change.

### Task 13.5 — Spectator HUD via snapshot refresh (frontend, v1 fallback)

- [x] **Goal:** Until [13.6](#task-136--turnprogress-events-on-the-stream-backend) lands, keep a spectator's HUD accurate without polling: on each action-bracket close (`ActionComplete` / `playerActionRequired`) re-fetch `GET .../snapshot` for the HUD fields the stream does not carry (`actionsRemaining`, `turnNumber`, `phase`, `activeCoach`, non-touchdown score). The local coach already has these from the `MatchActionResponse` snapshot ([12.3](#task-123--reconcile-from-action-response)) and needs no refresh.
- [x] **Depends on:** 13.2, 11.5
- [x] **Files:** `src/state/store.ts`
- [x] **Acceptance:** Superseded by [13.6](#task-136--turnprogress-events-on-the-stream-backend): HUD folds from stream events (`teamActionConsumed`, `turnEnded`, `turnBegan`, `playerActionPhaseOpened`, `matchEnded`, `touchdownScored`) in `applyEvent` — no snapshot-refresh fallback implemented.
- [x] **Rulebook:** n/a

> **Note:** The backend counterpart, [Task 13.6 — Turn/progress events on the stream](#task-136--turnprogress-events-on-the-stream-backend), is in [Phase A](#phase-a-backend-enablers-do-first--tight-review) (do first). Once it ships, the 13.5 snapshot-refresh fallback is removed and `applyEvent` folds HUD state from the stream.

---

## Phase 14: Assets & theming

> **Architecture:** [Frontend § Assets](../frontend/ARCHITECTURE.md#assets)
>
> Goal: swappable AI-generated art behind an `AssetRegistry`; responsive, accessible, themed.

### Task 14.1 — AssetRegistry + folder structure

- [x] **Goal:** `src/assets/{tokens,cards,pitch,ui}/` and an `AssetRegistry` mapping logical keys (team + position + status) to URLs, with per-team fallbacks. Render code resolves by key, never a raw path.
- [x] **Depends on:** 11.3
- [x] **Files:** `src/assets/registry.ts`
- [x] **Acceptance:** Swapping a token file is a registry-only change; render components import only the registry.

### Task 14.2 — Generate MVP token & icon art

- [x] **Goal:** Produce the image files for Dwarves (lineman, runner, blitzer, doom seeker) and Orcs (lineman, thrower, blitzer, bruiser) plus ball and dice-face icons. **This is the image-generation step** — the source of truth for what to generate is the prompt set in [Frontend § MVP generation prompts](../frontend/ARCHITECTURE.md#mvp-generation-prompts).
- [x] **Depends on:** 14.1
- [x] **Files:** `src/assets/tokens/**`, `src/assets/ui/**`
- [x] **How to generate (pick one):**
    - **(a) In-tool:** an image-capable agent generates each asset directly from the documented prompts and writes it into `src/assets/`.
    - **(b) External:** copy the documented prompts into any image AI (Midjourney, DALL·E, etc.), then drop the returned files into `src/assets/` using the registry's naming (`{team}-{position}-{status}`).
    Either way the render code is untouched — files resolve through [`AssetRegistry`](#task-141--assetregistry--folder-structure) (14.1), replacing the Phase 11 SVG primitives.
- [x] **Acceptance:** Each position + a prone variant renders distinctly at 48px on light and dark pitches; swapping a file needs no component change.

### Task 14.3 — Responsive layout, theming, accessibility

- [x] **Goal:** Responsive HUD/panel layout around the scaling pitch; team-accent theming via design tokens; `aria-live` event log and labelled squares/tokens.
- [x] **Depends on:** 11.5, 12.1
- [x] **Files:** `src/render/**`, theme tokens
- [x] **Acceptance:** Usable on a phone-width and desktop viewport; keyboard focus and screen-reader labels present.

---

## Phase 15: Full-system e2e

> **Architecture:** [Frontend § Full-system integration testing](../frontend/ARCHITECTURE.md#full-system-integration-testing-playwright), [System § Full-stack testing](../ARCHITECTURE.md#full-stack-testing)
>
> Goal: one script boots real backend + real frontend + Playwright and drives a live match — black box, no mocks. Runs as a regular development step.
>
> **Backend enabler:** the seedable RNG profile ([Task 15.1](#task-151--seedable-rng-backend-test-profile-enabler)) that makes e2e deterministic is in [Phase A](#phase-a-backend-enablers-do-first--tight-review) (do first).

### Task 15.2 — Playwright config + boot orchestration

- [x] **Goal:** Playwright `webServer` boots the backend (seeded profile) and Vite `preview`, waits for health, and tears down; one `npm run e2e`.
- [x] **Depends on:** 15.1, 10.1
- [x] **Files:** `frontend/playwright.config.ts`, e2e boot script
- [x] **Acceptance:** `npm run e2e` starts both servers, runs specs, and shuts down cleanly on pass or fail.

### Task 15.3 — Smoke e2e

- [x] **Goal:** Create a match in the UI, submit one legal action, assert the board updates — end to end through the real browser.
- [x] **Depends on:** 15.2, 12.2
- [x] **Files:** `frontend/e2e/smoke.spec.ts`
- [x] **Acceptance:** Passes against the real stack with no mocked network.

### Task 15.4 — Full-match invariant e2e

- [x] **Goal:** Drive a full match to `GAME_OVER` via the UI; assert invariants (occupancy consistent, score non-decreasing except touchdowns, match ends).
- [x] **Depends on:** 15.3, 13.4
- [x] **Files:** `frontend/e2e/full-match.spec.ts`
- [x] **Acceptance:** Match reaches game over; invariants hold; deterministic under the seeded profile.

---

## Frontend polish (post Phase 15)

> Playable GUI exists (Phases 10–15). These are **client-only UX** items — not rule-engine work. Prefer [UX.1](#ux1--selection-first-action-targeting) and [UX.2](#ux2--ball-visibility) before flourish; then clarity ([UX.5](#ux5--coach-facing-turn-banner--action-pips)–[UX.7](#ux7--action-legend-hover-intent--square-disambiguation)), then spectacle ([UX.6](#ux6--dice-theatre-overlay), [UX.8](#ux8--outcome-flash), [UX.4](#ux4--event-animations)).
>
> **Atmospheric / intricate graphics** live in a dedicated push: [Atmospheric graphics](#atmospheric-graphics-push). Prefer that after UX.1–2 so legal targets stay readable on richer art; enlarging squares and tokens is explicitly in scope.
>
> GUI ideas that need **new wire / domain data** (Open/Marked rings, “why can’t this player act”) live under [Future items](../BACKLOG.md#future-items-base-game-later) with the backend enablers — not here. Sound cues are in the [parking lot](../BACKLOG.md#parking-lot-unlikely-soon).
>
> **When the user asks what's next:** offer one of these, an [atmospheric graphics](#atmospheric-graphics-push) item, *or* a [Future item](../BACKLOG.md#future-items-base-game-later) / [tech-debt](../BACKLOG.md#tech-debt-cleanup) item.

### UX.1 — Selection-first action targeting

- [x] **Goal:** Replace “highlight every legal square for every player at once” with a clearer two-step flow: (1) while it is the active coach’s turn, **highlight that coach’s eligible players** (tokens that have at least one legal action); (2) **click a player** to select them, then show **only that player’s** legal targets on the pitch. Color target squares **by `actionType`** (run / mark / sidestep / throw / block / stand-up / reserves); on **hover** over a highlighted square, show the action type (and throw range when relevant).
- [x] **Pain today:** [12.1](#task-121--fetch-and-highlight-legal-actions) paints all `targetCoordinate`s together; the sidebar lists every player’s actions; selecting a token can also auto-fire a same-square action ([`Play.tsx`](../frontend/src/app/Play.tsx)) — hard to see who can act and what a square means.
- [x] **Depends on:** 12.1, 12.2
- [x] **Files:** `src/app/Play.tsx`, `src/render/Pitch.tsx`, `src/render/LegalActionPanel.tsx` (slim or retarget as a legend / selected-player list), token highlight styles
- [x] **Acceptance:** No selected player → only eligible active-coach tokens are visually marked, pitch squares are not flooded with mixed targets; after selecting a player → squares for that player only, color-coded by action type; hover announces the action; click still POSTs the matching verb via existing dispatch.
- [x] **Rulebook:** n/a (client affordance only — still driven by `GET .../legal-actions`)

### UX.2 — Ball visibility

- [x] **Goal:** Make the ball immediately findable at a glance — both when **loose** on a square and when **held** by a player (`holdsBall`).
- [x] **Pain today:** Loose-ball glyph is a small yellow disc; held-ball is a tiny badge on the token corner — easy to miss, especially once tokens use art assets ([14.2](#task-142--generate-mvp-token--icon-art)).
- [x] **Depends on:** 11.3, 14.2
- [x] **Files:** `src/render/Token.tsx` (`LooseBall`, `BallGlyph`, held-ball treatment), pitch overlay / CSS as needed
- [x] **Acceptance:** Opening snap and mid-match: a new viewer can point to the ball within a second; loose vs carried remain distinct; a11y labels still name the ball square / carrier.
- [x] **Rulebook:** n/a

### UX.3 — Exciting create-match pickers

- [x] **Goal:** Replace plain `<select>` team/pitch pickers with **scrollable team cards** that surface team information (name, roster summary / positions, flavor) and a **pitch preview** (mini SVG or thumbnail of the layout) before `POST /api/matches`. This is the **interaction shell** — hero art itself is the [Atmospheric graphics](#atmospheric-graphics-push) card track ([GFX.9](#gfx9--team-selection-hero-cards), [GFX.12](#gfx12--selection-roster-lineup-strip), [GFX.13](#gfx13--home-vs-away-matchup-splash)).
- [x] **Pain today:** [11.1](#task-111--create--join-screen-from-catalog) — catalog is `id` + `name` only; create screen is functional but bland ([`CreateMatch.tsx`](../frontend/src/app/CreateMatch.tsx)).
- [x] **Depends on:** 11.1, 11.2 (pitch SVG reuse for preview); pair with [GFX.9](#gfx9--team-selection-hero-cards)+ for art (UI can land with placeholders, then swap via registry)
- [x] **Files:** `src/app/CreateMatch.tsx`, new card/preview components; possibly richer `GET /api/catalog/teams` (roster summary / art keys) and/or a lightweight pitch-preview fetch — expand the catalog DTO only if cards need data the client cannot get from existing endpoints
- [x] **Acceptance:** Coach browses team cards (not opaque dropdowns), sees a pitch preview for the selected layout, creates a match; still works with only Dwarves/Orcs + default/barricade until more catalog entries exist; card slots are sized for full trading-card art, not tiny thumbnails.
- [x] **Rulebook:** n/a

### UX.4 — Event animations *(lower priority)*

- [x] **Goal:** Animate board beats when stream / action `events` land — player slides along a path, ball bounce hops, prone/knockdown, pickup, touchdown — so resolution reads temporally instead of snap-folding.
- [x] **Depends on:** 13.1 (pure `applyEvent` stays source of truth); prefer after [UX.1](#ux1--selection-first-action-targeting) so interaction and motion do not fight; [UX.8](#ux8--outcome-flash) is the cheap subset if full path motion waits.
- [x] **Files:** `src/state/applyEvent.ts` (or a presentation layer above the folded view), `src/render/Token.tsx` / `Pitch.tsx`, optional animation helper
- [x] **Acceptance:** At least run path + loose-ball bounce + block knockdown have intentional short motion; final view after animations match the already-folded snapshot (no desync); reduced-motion preference respects `prefers-reduced-motion`.
- [x] **Note:** Keep domain/view fold instant and correct; animation is presentation over committed state, not a second rules path.
- [x] **Rulebook:** n/a

### UX.5 — Coach-facing turn banner + action pips

- [x] **Goal:** Surface “who may act” in coach language, not engine phase names — e.g. “Your turn — 2 actions left” / “Waiting for Away” — and show `actionsRemaining` as **pips** (or an equally glanceable meter) instead of a meta footnote.
- [x] **Pain today:** [Hud](../frontend/src/render/Hud.tsx) prints raw phases (`PRE_TURN`, `PLAYER_ACTION`, …) and a small “Actions left: N”; spectating is only a line in the legal-actions panel.
- [x] **Depends on:** 11.4, 13.2 (`gameState` on the stream)
- [x] **Files:** `src/render/Hud.tsx`, play-screen CSS; optional seat prop if “Your turn” needs which coach this browser is
- [x] **Acceptance:** Active coach and remaining actions are obvious without reading the event log; phase enum may stay available for debug but is not the primary label; spectating turn is visually distinct from interactive turn.
- [x] **Rulebook:** n/a

### UX.6 — Dice theatre overlay

- [x] **Goal:** When `DiceRolled` arrives (action response or stream), show a short **on-pitch or HUD overlay** — block faces, D6 (armour / throw / etc.), D8 — that lingers ~1–2s (dismissible), instead of only appending log text.
- [x] **Pain today:** [formatEvent](../frontend/src/state/formatEvent.ts) writes dice into the event log only; outcomes feel sudden with no tabletop beat.
- [x] **Depends on:** 13.1 (`diceRolled` already folded / logged)
- [x] **Files:** new overlay component; wire from store / Play when `DiceRolled` is applied; CSS; respect `prefers-reduced-motion` (instant label ok)
- [x] **Acceptance:** Block and key D6/D8 rolls are visible without scanning the log; final board state unchanged; overlay does not block submitting the next legal action after it settles (or while interactive).
- [x] **Rulebook:** n/a (presentation of existing events)
- [x] **Related:** [UX.13](#ux13--sticky-last-roll-chip) keeps the last roll after the overlay fades.

### UX.7 — Action legend, hover intent, and square disambiguation

- [x] **Goal:** Complete selection-first targeting: sticky **action-type color legend**; on hover of a highlighted square, announce **intent** (run / mark / throw + range / block **naming the target player** when the action is a block); when **more than one** legal action shares a square for the selected player, show a small chooser instead of first-match-wins.
- [x] **Pain today:** [Play](../frontend/src/app/Play.tsx) `findActionAtCoordinate` takes the first match; block and move can share a square; UX.1 colors help but newcomers still need a legend and unambiguous clicks.
- [x] **Depends on:** [UX.1](#ux1--selection-first-action-targeting)
- [x] **Files:** `src/app/Play.tsx`, `src/render/Pitch.tsx`, legend component (or retargeted `LegalActionPanel`), hover tooltip
- [x] **Acceptance:** Legend visible while targets are shown; hover copy matches the action that would POST; multi-action squares require an explicit choice; single-action squares still one-click.
- [x] **Rulebook:** n/a

### UX.8 — Outcome flash

- [x] **Goal:** After an action resolves, briefly **pulse** affected squares / tokens (move destination, knockdown, pickup, score change) so the last beat is findable without full path animation.
- [x] **Depends on:** 13.1; prefer after [UX.1](#ux1--selection-first-action-targeting)
- [x] **Files:** presentation layer above folded view; `Pitch.tsx` / `Token.tsx` highlight styles
- [x] **Acceptance:** At least move landing + status-to-prone + touchdown score tick flash once; fold remains source of truth; reduced-motion skips or shortens flash.
- [x] **Note:** Subset of [UX.4](#ux4--event-animations); ship this first if path slides wait.
- [x] **Rulebook:** n/a

### UX.9 — Token identity polish

- [x] **Goal:** Make crowded tokens readable — stronger **team frames**, clearer **prone** pose/glyph, and a stable **number or position** mark at pitch scale (not only a letter initial).
- [x] **Depends on:** 14.2 (asset path); works with placeholder discs too
- [x] **Files:** `src/render/Token.tsx`, CSS / SVG treatments; optional roster jersey numbers if already on `PlayerCardDto`
- [x] **Acceptance:** Home vs away and prone vs standing remain distinct at a glance on a full pitch; a11y labels still name status and team.
- [x] **Rulebook:** n/a

### UX.10 — Touchdown and match-end beat

- [x] **Goal:** One emotional beat per score and at game over — end-zone wash or score pop on `TouchdownScored`, richer [GameOver](../frontend/src/app/GameOver.tsx) summary (final score, winner) beyond a flat result string.
- [x] **Depends on:** [UX.8](#ux8--outcome-flash) helpful but not required; 13.1 for events
- [x] **Files:** Hud / Pitch flash hooks; `GameOver.tsx`
- [x] **Acceptance:** Touchdown is unmistakable in the UI; match-end screen states winner/draw and scores clearly.
- [x] **Rulebook:** n/a

### UX.11 — Pitch atmosphere *(light pass)*

- [x] **Goal:** Quick readability pass — clearer end-zone coloring, trapdoor / blocked cues, light grain — **without** waiting on the full art drive.
- [x] **Depends on:** 11.2
- [x] **Files:** `src/render/Pitch.tsx`, CSS variables / backgrounds
- [x] **Acceptance:** Playable squares, end zones, blocked, and trapdoors remain distinguishable; contrast for tokens and legal highlights still passes a quick glance check.
- [x] **Note:** For painted boards, terrain props, and tabletop chrome, see [Atmospheric graphics](#atmospheric-graphics-push) ([GFX.2](#gfx2--painted-pitch-boards-per-layout)–[GFX.3](#gfx3--terrain-props-trapdoors-barricades-end-zones)).
- [x] **Rulebook:** n/a

### UX.12 — Dugout reserves as mini-tokens

- [x] **Goal:** Show dugout / reserves as **small tokens** (team color + position cue), not only text rows, so bench presence matches the pitch language.
- [x] **Depends on:** 11.3, [UX.9](#ux9--token-identity-polish) optional for shared glyph; richer crest treatment in [GFX.8](#gfx8--dugout-and-team-crest-decoration)
- [x] **Files:** `src/render/Dugout.tsx`, reuse `Token` graphic at reduced scale
- [x] **Acceptance:** Home and away reserves are countable at a glance; selection / card hover still works if those affordances exist on the dugout today.
- [x] **Rulebook:** n/a

### UX.13 — Sticky last-roll chip

- [x] **Goal:** After dice theatre fades (or when overlay is skipped), keep a compact **“Last roll: …”** chip above the event log until the next roll.
- [x] **Depends on:** [UX.6](#ux6--dice-theatre-overlay) preferred; can ship from log state alone
- [x] **Files:** Play sidebar / EventLog adjacent chip; store field for last `DiceRolled` summary
- [x] **Acceptance:** Coach can recall the most recent faces/value without scrolling the log; chip updates on each new `DiceRolled`.
- [x] **Rulebook:** n/a

### UX.14 — Connection and spectating badge

- [x] **Goal:** Persistent badge for SSE / connection phase (`connecting`, `hydrating`, `live`, reconnecting, error) and spectating vs interactive, so a dropped stream is not mistaken for “nothing to do.”
- [x] **Depends on:** 13.2 store connection phases
- [x] **Files:** Hud or Play chrome; store `connectionPhase` already present
- [x] **Acceptance:** Disconnect / reconnect is visible; interactive vs spectating remains labeled when not your turn.
- [x] **Rulebook:** n/a

### UX.15 — Escape to deselect

- [x] **Goal:** **Escape** clears the selected player and returns to “eligible tokens only” (no target flood), matching selection-first flow.
- [x] **Depends on:** [UX.1](#ux1--selection-first-action-targeting)
- [x] **Files:** `src/app/Play.tsx` key handler
- [x] **Acceptance:** Esc with a selection clears it; Esc with no selection is a no-op (does not leave the match).
- [x] **Rulebook:** n/a

---

## Atmospheric graphics push

> **Intent:** Goblins & Gridirons should feel like a **specific plastic / cardboard tabletop** under arena light — intricate, reference-calibrated art, not flat UI discs on grey squares. Prefer after [UX.1](#ux1--selection-first-action-targeting) / [UX.2](#ux2--ball-visibility) so selection and the ball still read on busy surfaces.
>
> **Priority track — card & team selection art:** [GFX.6](#gfx6--in-match-player-trading-cards), [GFX.9](#gfx9--team-selection-hero-cards), [GFX.12](#gfx12--selection-roster-lineup-strip), [GFX.13](#gfx13--home-vs-away-matchup-splash) (and [GFX.14](#gfx14--challenge-card-art-pack) when challenge UI exists). These can run **in parallel** with pitch paint — create/join and the player-card panel are where atmosphere hits first for many coaches. Calibrate hard against the local reference photos (gitignored).
>
> **Scale is in scope:** enlarge square size, token radius, **and card surfaces** (selection carousel / player panel) even if that means scrolling or zoom on small viewports. Detail wins over packing everything into one phone screenful.
>
> **Art pipeline:** keep [AssetRegistry](../frontend/ARCHITECTURE.md#assets) as the only path from render → files; regenerate / replace assets without component rewrites. Extend generation prompts in [frontend architecture](../frontend/ARCHITECTURE.md#mvp-generation-prompts) as part of these tasks (document style bible from [`reference/`](../reference/) scans). Empty `src/assets/cards/` is the intended home for team fronts, portraits, and (later) challenge faces.
>
> Suggested order: **card track early** (GFX.9 → GFX.12 → GFX.13 → GFX.6, with [UX.3](#ux3--exciting-create-match-pickers) for the shell) ∥ **board track** (GFX.1 → GFX.2–3 → GFX.4–5) → chrome (GFX.7–8) → motion / explore (GFX.10–11). Challenge faces (GFX.14) whenever convenient for assets; on-screen use shipped with D1 tableau.

### GFX.1 — Enlarge pitch and token scale

- [x] **Goal:** Give art room to breathe — raise effective square size / pitch max width, increase token footprint on the SVG grid, and relax layout so the pitch can dominate the viewport (sidebar may scroll or collapse).
- [x] **Pain today:** `--pitch-max-width: min(100%, 56rem)` and token radius ~0.38 grid units keep MVP icons tiny; intricate art will mush at current scale ([`index.css`](../frontend/src/index.css), [`Token.tsx`](../frontend/src/render/Token.tsx)).
- [x] **Depends on:** 11.2, 14.3
- [x] **Files:** `src/index.css` (pitch max width, play layout), `Token.tsx` / `Pitch.tsx` radius and stroke weights, optional mobile scroll-into-pitch
- [x] **Acceptance:** At desktop, individual token details (face, kit, base ring) are readable without zoom; legal highlights and hit targets remain easy to click; phone may scroll/pan rather than shrink art into illegibility.
- [x] **Rulebook:** n/a

### GFX.2 — Painted pitch boards per layout

- [x] **Goal:** Replace flat filled `rect`s with **illustrated pitch boards** per `pitchDefinitionId` (default, barricade, …) — turf / sand / stone texture, yard lines or tile seams, painted end zones, subtle wear — sourced under `src/assets/pitch/` via the registry.
- [x] **Depends on:** [GFX.1](#gfx1--enlarge-pitch-and-token-scale); 14.1 registry extension for pitch keys; calibrate against the local pitch reference photos (gitignored)
- [x] **Files:** `src/assets/pitch/**`, `registry.ts` (`pitchBoard(layoutId)`), `Pitch.tsx` (board `<image>` under grid, or per-square atlases)
- [x] **Acceptance:** Default and barricade layouts look like distinct physical boards; playable vs non-playable still clear; tokens/ball remain high-contrast on the art; swapping a board file needs no component change beyond registry.
- [x] **Rulebook:** n/a (presentation of existing layout geometry)

### GFX.3 — Terrain props: trapdoors, barricades, end zones

- [x] **Goal:** Trapdoors, blocked/barricade squares, and end-zone bands are **specific props** (hatches, wooden obstacles, painted goal stripes / banners) — not solid color fills — layered as decals or tile overlays on the painted board.
- [x] **Depends on:** [GFX.2](#gfx2--painted-pitch-boards-per-layout)
- [x] **Files:** `src/assets/pitch/props/**` (or layout atlases), `Pitch.tsx`, registry prop keys
- [x] **Acceptance:** A new viewer can name trapdoors and barricades without a legend; end zones read as scoring ends; props never hide token clicks (hit targets stay on the square/token).
- [x] **Rulebook:** n/a

### GFX.4 — High-detail player tokens (art regen)

- [x] **Goal:** Regenerate the full Dwarves/Orcs token set (standing + prone) at **intricate tabletop miniature** fidelity — kit, face, weapons, team palette — readable at the enlarged scale from [GFX.1](#gfx1--enlarge-pitch-and-token-scale), calibrated to the local reference photos (gitignored).
- [x] **Depends on:** [GFX.1](#gfx1--enlarge-pitch-and-token-scale), 14.1–14.2; update architecture generation prompts away from “flat readable at 48px” toward “detailed circular base token, still distinct silhouette per position”
- [x] **Files:** `src/assets/tokens/**`, prompt docs in [frontend/ART.md](../frontend/ART.md); optional 2× PNG/WebP if SVG detail is insufficient
- [x] **Acceptance:** Every position has a unique silhouette and kit; prone is an obvious knockdown pose (not only desaturated); home/away (or team palette) still separable at pitch scale; registry swap only.
- [x] **Note:** Complements [UX.9](#ux9--token-identity-polish) (frames/numbers); this task is the art regeneration, UX.9 is chrome on top.
- [x] **Rulebook:** n/a

### GFX.5 — Hero ball and dice props

- [x] **Goal:** Replace flat ball / dice icons with **hero props** — oval spiked Goblins & Gridirons ball with materials; block-die faces and D6/D8 that look like physical dice (including each distinct block face if art allows).
- [x] **Depends on:** [UX.2](#ux2--ball-visibility), [UX.6](#ux6--dice-theatre-overlay) (theatre looks better with real props); [GFX.1](#gfx1--enlarge-pitch-and-token-scale)
- [x] **Files:** `src/assets/ui/**`, `registry.ts` icon keys (expand for smash/flatten/shove/tackle/miss faces if separate), `Token` / dice overlay consumers
- [x] **Acceptance:** Loose and carried ball are unmistakable and look like the same prop; dice theatre and last-roll chip use the upgraded faces; contrast holds on painted pitch.
- [x] **Rulebook:** n/a

### GFX.6 — In-match player trading cards

- [x] **Goal:** Turn the player-card panel into a **full trading-card surface** — large portrait, ornate border, team colors, position title, and stats overlaid on art — not a text `<dl>` with a tiny image. Art fills `src/assets/cards/` (per team + position, or per roster id) via registry.
- [x] **Pain today:** [`PlayerCard.tsx`](../frontend/src/render/PlayerCard.tsx) is stats text; `src/assets/cards/` is empty (`.gitkeep` only); Phase 14 MVP only generated tokens/icons.
- [x] **Depends on:** 11.5 `PlayerCard`; registry `cardArt(teamId, position)` (and fallbacks); style consistency with [GFX.4](#gfx4--high-detail-player-tokens-art-regen) / [GFX.9](#gfx9--team-selection-hero-cards); calibrate to the local reference photos (gitignored)
- [x] **Files:** `src/assets/cards/{team}/{position}.png` (or WebP), `registry.ts`, `PlayerCard.tsx` + CSS (card aspect ~3:4, readable type)
- [x] **Acceptance:** Hover/select a token shows a **hero card** matching that position’s kit; every Dwarves/Orcs position has distinct art; missing art falls back to a branded blank; card is large enough to appreciate detail (raise panel width if needed — same “enlarge” mandate as GFX.1).
- [x] **Art notes:** Generate prompts for *portrait / mid-shot trading card*, not top-down token; include team crest corner, grit/parchment or stadium light; no illegible micro-text in the bitmap (live DOM stats on top of art).
- [x] **Rulebook:** n/a

### GFX.7 — Tabletop arena chrome

- [x] **Goal:** Frame the match as a **table under lights** — wood / mat surround, soft vignette or arena glow, score markers and action pips as plastic counters, HUD typography that fits the kit (not generic system UI).
- [x] **Depends on:** [GFX.2](#gfx2--painted-pitch-boards-per-layout); [UX.5](#ux5--coach-facing-turn-banner--action-pips) for pip semantics
- [x] **Files:** play shell CSS / background assets under `src/assets/ui` or `pitch/`, `Hud.tsx` visual treatment
- [x] **Acceptance:** First viewport reads as a board game on a table, not a dashboard; score and turn remain glanceable; does not steal contrast from tokens or legal highlights.
- [x] **Rulebook:** n/a

### GFX.8 — Dugout and team crest decoration

- [x] **Goal:** Dugouts look like **team benches** — crest / banner, kit colors, mini-tokens seated on a strip — not a plain list beside the pitch. Reuse crest assets from [GFX.9](#gfx9--team-selection-hero-cards) where possible.
- [x] **Depends on:** [UX.12](#ux12--dugout-reserves-as-mini-tokens), [GFX.4](#gfx4--high-detail-player-tokens-art-regen) optional; crest assets in registry
- [x] **Files:** `Dugout.tsx`, `src/assets/ui` or `cards` crests, CSS
- [x] **Acceptance:** Home and away dugouts are instantly branded; reserve count remains clear; works with both placeholder and high-detail tokens.
- [x] **Rulebook:** n/a

### GFX.9 — Team selection hero cards

- [x] **Goal:** Produce **box-front / team-card hero art** for each catalog team (Dwarves, Orcs first) — full 3:4 (or landscape banner) pieces that dominate the [UX.3](#ux3--exciting-create-match-pickers) carousel: crest, faction mood, signature players, team name locking. Pitch picker gets **illustrated thumbs** (not grey SVG only).
- [x] **Pain today:** Create screen is dropdowns; no team art keys in the registry; selecting a side has no emotional beat.
- [x] **Depends on:** 14.1 registry (`teamCard(teamId)`, `pitchThumb(pitchId)`, `teamCrest(teamId)`); [UX.3](#ux3--exciting-create-match-pickers) for browse/select UX (can land art first and wire into placeholders); calibrate to the local team-card reference photos (gitignored)
- [x] **Files:** `src/assets/cards/teams/**`, `src/assets/pitch/thumbs/**`, `registry.ts`, `CreateMatch.tsx` card surfaces
- [x] **Acceptance:** Choosing Home/Away feels like picking a **physical team card** from the box; selected state is obvious (lift, glow, or foil frame); Dwarves vs Orcs are unmistakable without reading labels; pitch options show illustrated previews; adding a future team is registry + files only.
- [x] **Art notes:** One primary hero per team at high resolution; optional alternate “selected” or foil variant; keep a consistent card template (border, name plate) so the carousel reads as one set.
- [x] **Rulebook:** n/a

### GFX.10 — Cinematic board VFX

- [x] **Goal:** Short **presentation-only** flourishes on big beats — dust/poof on knockdown, ball spark trail on throw/bounce, end-zone flare on touchdown — layered on top of [UX.8](#ux8--outcome-flash) / [UX.4](#ux4--event-animations) without a second rules path.
- [x] **Depends on:** [UX.8](#ux8--outcome-flash); prefer after [GFX.2](#gfx2--painted-pitch-boards-per-layout) so VFX sit on the painted board
- [x] **Files:** overlay/SVG/CSS effects in `render/`; driven by already-folded events
- [x] **Acceptance:** At least knockdown + touchdown + ball bounce have a distinct flourish; `prefers-reduced-motion` disables or simplifies; final board state never depends on VFX finishing.
- [x] **Rulebook:** n/a

### GFX.11 — Pitch zoom and pan

- [x] **Goal:** Optional **zoom / pan** (buttons and/or pinch-drag) so coaches can inspect intricate tokens and terrain without permanently shrinking the ambient view.
- [x] **Depends on:** [GFX.1](#gfx1--enlarge-pitch-and-token-scale); works with painted boards ([GFX.2](#gfx2--painted-pitch-boards-per-layout))
- [x] **Files:** `Pitch.tsx` / play shell transform + controls; preserve click hit-testing for legal squares
- [x] **Acceptance:** Zoom in/out and pan; reset view control; submitting a legal action still works while zoomed; keyboard users are not trapped (focus / reset).
- [x] **Rulebook:** n/a

### GFX.12 — Selection roster lineup strip

- [x] **Goal:** On the create/select screen, under (or on) the selected team hero card, show a **horizontal lineup of position miniatures or cropped card faces** (all roster positions) so coaches see who they are fielding before kickoff — intricate, not a bullet list of ids.
- [x] **Depends on:** [GFX.9](#gfx9--team-selection-hero-cards); can reuse [GFX.4](#gfx4--high-detail-player-tokens-art-regen) or [GFX.6](#gfx6--in-match-player-trading-cards) crops; roster labels from catalog / [UX.3](#ux3--exciting-create-match-pickers) data
- [x] **Files:** `CreateMatch.tsx` (or `TeamSelectCard` child), registry keys for lineup thumbs
- [x] **Acceptance:** Selecting Dwarves or Orcs reveals a complete visual roster strip; positions remain named for a11y; strip scrolls horizontally on narrow viewports without crushing art.
- [x] **Rulebook:** n/a

### GFX.13 — Home vs Away matchup splash

- [x] **Goal:** After both teams (and pitch) are chosen — or as a brief beat when entering Play — show a **vs splash**: Home hero card vs Away hero card, crests, selected pitch thumbnail, then dismiss into the board. Optional continue button; skippable.
- [x] **Depends on:** [GFX.9](#gfx9--team-selection-hero-cards); [UX.3](#ux3--exciting-create-match-pickers) create flow
- [x] **Files:** new splash component; create → play transition in `App.tsx` / `CreateMatch.tsx`
- [x] **Acceptance:** Matchup is a memorable graphic beat; does not block match creation if art missing (graceful skip); Escape / click-through continues quickly.
- [x] **Rulebook:** n/a

### GFX.14 — Challenge card art pack *(assets now; UI with D1)*

- [x] **Module:** frontend assets (display waits on deferred challenge-card rules)
- [x] **Goal:** Generate **challenge-card fronts and shared backs** into `src/assets/cards/challenge/` (and registry keys), calibrated to the local challenge-card reference photos (gitignored).
- [x] **Depends on:** 14.1 registry extension; on-screen use with D1 tableau
- [x] **Files:** `src/assets/cards/challenge/**`, `registry.ts`
- [x] **Acceptance:** Named challenge faces + one back plate resolve via registry; style matches team trading cards (GFX.6 / GFX.9).
- [x] **Note:** Faces wired in `CHALLENGE_FACES` (c01–c26) and used by tableau / claim celebration.
- [x] **Rulebook:** pp. 21–22 (art fidelity only)

---

## Completed future items (archived)

### F.1 — Pending action / coach prompt flow

- [x] **Goal:** `TurnState.pendingDecisions` holds a pending decision after partial resolution; legal moves and action POSTs complete the prompt before the turn advances.
- [x] **Depends on:** [9.4](#task-94--input-required-events-pull-legal-moves-on-demand) (`InputRequired` / `pendingDecisionType` on wire).
- [x] **Files:** `model/PendingDecision.kt`, `game/turn/TurnEngine.kt`, `game/legalmoves/LegalMoves.kt`, `DomainEvent.InputRequired.PlayerAction`
- [x] **Acceptance:** Shove follow-up, Emergency Reserves, and regular player-action windows all use the same queue; client fetches legal actions per `pendingDecisionType`; turn does not advance until prompt resolved or skipped.
- [x] **Note:** Shipped 2026-07 — unblocks abilities, [F.2](../BACKLOG.md#f2--random-d6-move-run), and coach-choice grants (Safe Hands, re-rolls).

### F.3 — Block Shove follow-up

- [x] **Goal:** After Shove resolves, coach chooses whether the blocker moves into the square the target vacated.
- [x] **Depends on:** F.1, 4.33
- [x] **Files:** `game/action/BlockResolver.kt`, `PendingDecision.ShoveFollowup`, `integration/BlockApiTest.kt`
- [x] **Acceptance:** Follow-up optional via `Skip` or `Mark` into target square; separate prompt, not `followUp` on initial block POST.
- [x] **Rulebook:** p. 18 — Shove

### F.7 — Emergency Reserves coach placement choice

- [x] **Goal:** Pre-turn Emergency Reserves pauses for the active coach to choose dugout player and end-zone square.
- [x] **Depends on:** F.1, [5.7](#task-57--emergency-reserves-step)
- [x] **Files:** `game/turn/TurnEngine.kt`, `PendingDecision.EmergencyReserves`, `integration/EmergencyReservesApiTest.kt`
- [x] **Acceptance:** Pre-turn does not open the action window until reserves queue is empty; free placements do not consume `actionsRemaining`; coach picks among all dugout players.
- [x] **Rulebook:** p. 19 — Emergency Reserves

---

## D2.4 — Full team card catalog

- [x] **Goal:** All 23 Goblins & Gridirons team cards in [GridironTeamCatalog.kt](../backend/src/main/kotlin/com/mhaefele/gridiron/model/catalog/GridironTeamCatalog.kt) with roster counts, stats, and ability text.
- [x] **Files:** `model/catalog/GridironTeamCatalog.kt`, `TeamCatalog.kt`, `PlayerAbility.kt`, `api/CatalogController.kt`
- [x] **Acceptance:** `GET /api/catalog/teams` lists 23 teams; specialists expose `CardAbilityDto`; create-match accepts any catalog id.
- [x] **Note:** Kotlin catalog, not JSON resource — sufficient for play; ability **enforcement** is PA.* / [D1.G](#d1g--challenge-gated-player-abilities-2026-07). Phase closed: [D2](#d2--player-abilities-phase-closed-2026-07).

### D2 — Player abilities phase closed *(2026-07)*

- [x] **Closed:** Deferred Phase D2 had no remaining work beyond what already shipped — full catalog ([D2.4](#d24--full-team-card-catalog)), base-game ability enforcement (PA.0–PA.6 / PA.F), and challenge-gated Bloodlust / Minion ([D1.G](#d1g--challenge-gated-player-abilities-2026-07)).
- [x] **Architecture stub:** [AbilityHook](../backend/ARCHITECTURE.md#extension-points-deferred) remains documentary only.
- [x] **Rulebook:** team cards pp. 9, 23–24, 36; advanced pp. 23–24

---

## AI1 — Basic computer opponent

- [x] **AI1.1** — `OpponentStrategy` port (`game/opponent/OpponentStrategy.kt`)
- [x] **AI1.2** — Heuristic bot [`ScriptedOpponentStrategy`](../backend/src/main/kotlin/com/mhaefele/gridiron/game/opponent/ScriptedOpponentStrategy.kt): chase ball, mark/block carrier, run toward end zone
- [x] **AI1.3** — In-process [`ComputerCoachAgent`](../backend/src/main/kotlin/com/mhaefele/gridiron/game/opponent/ComputerCoachAgent.kt) + create-match [`CoachControl`](../frontend/src/app/CoachControlToggle.tsx) toggle (no separate `bot-move` endpoint)
- [x] **AI1.4** — Bot chooses only from `legalMovesForActiveCoach` outputs ([`ComputerCoachApiTest`](../backend/src/test/kotlin/com/mhaefele/gridiron/integration/ComputerCoachApiTest.kt))
- [x] **AI1.5** — Per-match write lock — [AI1.5](#ai15--per-match-write-lock-2026-07)
- [x] **AI1.6** — Lookahead over legal move graph — [AI1.6](#ai16--lookahead-over-the-legal-move-graph-2026-08)

### AI1.5 — Per-match write lock *(2026-07)*

- [x] **Goal:** Serialize load→mutate→save on `MatchGame` per match so concurrent writers (human HTTP + computer coach) cannot lost-update; publish SSE **after** releasing the lock.
- [x] **Shipped:** [`MatchGame`](../backend/src/main/kotlin/com/mhaefele/gridiron/api/MatchGame.kt) `ReentrantLock` map; `startMatch` / `submitActionOutcome` share `mutateAndPublish`; [ARCHITECTURE — Match persistence](../backend/ARCHITECTURE.md#match-persistence); [`MatchWriteLockApiTest`](../backend/src/test/kotlin/com/mhaefele/gridiron/integration/MatchWriteLockApiTest.kt).
- [x] **Acceptance:** Concurrent submit of the same legal action id → one success, one 400; match state / `lastEventId` advance once.

---

## Completed tech debt (archived)

### TD.1 — Revisit immutability / copy-on-write weight *(2026-07)*

- [x] **Goal:** Document keep / relax / hybrid decision for Board / MatchState updates.
- [x] **Decision:** **Hybrid** — immutable persisted `model/`; working state via `BoardMutator` / `MatchMutator` accumulators (not full CoW ceremony).
- [x] **Shipped:** [ARCHITECTURE — immutability strategy](../backend/ARCHITECTURE.md#immutability-strategy) aligned with [immutability.mdc](../backend/.cursor/rules/immutability.mdc); stale `ActionResolver.resolve(state, command, dice)` / CoW-ceremony wording removed.

### TD.2 — Board mutation vocabulary

- [x] **Goal:** Replace ad-hoc `withSquareUpdates` / scattered `copy` calls with named board operations that read like the rulebook: e.g. `playerMoved`, `playerStatusChanged`, `playerRemoved`, `playerDeployedFromReserves`, `ballPickedUpByPlayer`, `looseBallAt`.
- [x] **Acceptance:** Resolvers call vocabulary methods; occupancy and dugout consistency enforced in one place; tests cover each operation.
- [x] **Note:** Path resolution walks [MovePath] one square per step; enter square, then `ballPickedUpByPlayer` or bounce as separate mutations.

### TD.5 — Split `MatchFlow` responsibilities

- [x] **Goal:** `MatchFlow` currently mixes at least three concerns: **match config** (fixed for the game, e.g. `coachA`, `config`), **cumulative match state** (scores, `result`, `turnNumber`), and **per-turn state** (`phase`, `activeCoach`, `actionsRemaining`, `actionsUsedThisTurn`, `turnEndedPlayers`, `isCoachAFirstTurn`). Split into focused types composed on `MatchState` (or nested value objects) so turn engine and legal-move queries touch only the slice they need.
- [x] **Acceptance:** `TurnActionContext` maps cleanly from turn-only type; config immutable after create; scores/result separate from turn clock; migration preserves JSON shape or documents a version bump; tests updated.
- [x] **Note:** `MatchFlow` removed — `MatchState` holds `config`, `progress`, and `currentTurnState` directly; `coachA` lives on `MatchConfig`.

### TD.8 — Slim match-state JSON for clients

- [x] **Goal:** Retire fat `GET /api/matches/{id}` domain JSON in favor of client DTOs ([8.1](#task-81--piece-snapshot-read-api)–[8.4](#task-84--match-game-state-read-api)); flat drawing-friendly JSON, not `BoardSquare` sealed variants or stat hierarchies on the wire.
- [x] **Depends on:** [8.1](#task-81--piece-snapshot-read-api), [8.2](#task-82--pitch-layout-read-api), [8.3](#task-83--match-roster-read-api), [8.4](#task-84--match-game-state-read-api), [8.5](#task-85--slim-action-response-events--snapshot-no-board), [8.9](#task-89--slim-create-match-response)
- [x] **Files:** `api/MatchController.kt` (removed monolithic GET), integration tests use client DTOs + `loadMatch` for internal assertions; [8.11](#task-811--remove-jackson-wiring-from-types-off-the-wire) peeled model Jackson.
- [x] **Acceptance:** No public endpoint returns raw `MatchState` or `Board`; clients use `GET .../snapshot` (mutable), `pitch`, `roster`, plus action/create envelopes.
- [x] **Note:** Piece/HUD DTOs landed in 8.1–8.4; TD.8 removed the monolithic GET; [9.5](#task-95--mutable-sync-snapshot-hydrate-for-sse) consolidated mutable reads into snapshot.

### TD.6 — Clarify and subdivide packages *(2026-07)*

- [x] **Goal:** Package names tell you what lives where — split by role; each top-level package has a one-sentence charter.
- [x] **Shipped:** Layout + charters + create-match import path in [backend ARCHITECTURE — layering / package layout](../backend/ARCHITECTURE.md#layering-overview); removed stale `ActionCommand` / `ActionValidator` / `MatchCommandMapping` / `model/.../shared` / `domain/model/catalog` drift; `game/cards/` + `game/opponent/` documented; [kotlin-domain-files.mdc](../backend/.cursor/rules/kotlin-domain-files.mdc) aligned.
- [x] **Note:** No further mechanical package moves — structure already navigable.

### TD.10 — Extract `shared` package *(2026-07)*

- [x] **Goal:** Value types on both sides of the HTTP boundary live in `shared/` — not whole aggregates; documented inventory; no single-consumer types.
- [x] **Shipped:** Full inventory in [ARCHITECTURE — shared package inventory](../backend/ARCHITECTURE.md#shared-package-inventory) including `CoachControl`, `LegalActionId`, `DomainEventId`, `ThrowRange` (was mis-listed under `game/action/dto/`). Audit: every `shared/` type has multiple consumers.

### TD.3 — DI and class-shaped resolvers *(2026-07 triage)*

- [x] **Goal:** Wire Spring so controllers inject action/turn components; roll-owning resolvers own `DiceRoller`; no `MatchService` pass-through; domain model stays Spring-free.
- [x] **Shipped:** `@Component` classes with constructor-injected `DiceRoller`: `ActionResolver`, `BounceResolver`, `ThrowResolver`, `BlockResolver`, `NewBallResolver`, `TurnEngine`, `PlayerActionExecutor`. Controllers → `MatchGame` → executors/resolvers. No `MatchService`.
- [x] **Note:** Controllers inject `MatchGame` (persistence boundary), not resolvers directly — intentional layering.

### TD.4 — Standardize mutation/resolver return values *(2026-07 triage)*

- [x] **Goal:** One composable shape for board/match + ordered events instead of ad-hoc `*Result` / parallel `d8Rolls` tuples.
- [x] **Shipped (evolved design):** [`BoardMutator`](../backend/src/main/kotlin/com/mhaefele/gridiron/game/board/dto/BoardMutator.kt) / [`MatchMutator`](../backend/src/main/kotlin/com/mhaefele/gridiron/game/turn/MatchMutator.kt) accumulators append `DomainEvent`s. Gone: `BounceOutcome`, `PathMovementResult`, `BoardResolution`, parallel `d8Rolls`.
- [x] **Depends on:** [TD.2](#td2--board-mutation-vocabulary).
- [x] **Note:** Accumulator design superseded “same envelope type” acceptance ([immutability.mdc](../backend/.cursor/rules/immutability.mdc)).

### TD.7 — `StateResolution` for board + turn side effects *(2026-07 triage)*

- [x] **Goal:** Stop resolver out-params (`blockerTurnEnded`) leaking turn-flow mutations via caller `var`s.
- [x] **Shipped as `MatchMutator`:** `BlockResolver` calls `session.endPlayerTurn(...)`; no `blockerTurnEnded` out-params. Role of sketched `StateResolution` absorbed by mutator.
- [x] **Note:** Complements mutator dual-write; true event-fold ([TD.9](#td9--event-driven-turn-and-progress-updates-wont-do)) won't do.

### TD.9 — Event-driven turn and progress updates *(won't do)*

- [x] **Won't do (2026-07):** Original goal was resolvers emit board + events only, then a reducer folds events into `TurnState` / `MatchProgress`.
- [x] **Kept instead:** [`MatchMutator`](../backend/src/main/kotlin/com/mhaefele/gridiron/game/turn/MatchMutator.kt) dual-write — named verbs mutate turn/progress and append the matching `DomainEvent` in one place. That already fixed the old scattered turn patches / `blockerTurnEnded` out-params ([TD.7](#td7--stateresolution-for-board--turn-side-effects-2026-07-triage)).
- [x] **Note:** Stream consumers still fold events for the HUD; domain match state is not event-sourced from the log.



### PA.F.3 / PA.F.5 — War Machine block assist + Untouchable/Leap end squares *(2026-07)*

- [x] **PA.F.3 (block half):** War Machine blocks are never assisted (`isAssistedBlock`); covered by `AssistedBlockTest` + `PlayerAbilityApiTest`.
- [x] **PA.F.5:** Through-opponent runs must end on an empty, non-blocked square (`runPathEndsLegally` + `runPaths` filter); Wraith/Fun Hoppa unit + API tests.

### PA.F.3 — War Machine Run whilst Marked *(2026-07)*

- [x] **Goal:** Rattle Cart / War Machine may **Run whilst Marked** (UE card text); Run may finish Open or Marked; Block Actions cannot be Assisted. Catalog string corrected (had garbled “cannot be Assisted” on Run — Assist is a Block-only rule).
- [x] **Shipped:** `allowedActionTypes` grants `RUN` when Marked + War Machine; adjacent/end-Marked pathing already in `AbilityRunRules`; `isAssistedBlock` denies assist for any War Machine block. Tests: `ActionAvailabilityTest`, `LegalMovesTest`, `PlayerAbilityApiTest`.
- [x] **Note:** Former open half was mis-framed as “run assist”; there is no Run-assist mechanic in the rulebook.

### PA.F.4 — GUI ability name on pending prompt *(2026-07)*

- [x] **Goal:** Ability-driven prompts name the card ability in HUD / event log.
- [x] **Shipped:** `ability` (`PlayerAbility`) on `InputRequired.PlayerAction`; client maps enum → card label for HUD/event log; API coverage for Relentless, Safe Hands, Offensive Specialist.

### PA.F.2 — PA acceptance API tests *(2026-07)*

- [x] **Goal:** Close unchecked Acceptance under PA.1–PA.6 with focused API tests.
- [x] **Shipped:** `PlayerAbilityAcceptanceApiTest` (27 cases); dugout Skip fix in `LegalMoves` for Regenerate grants.

---

## Rulebook fidelity fixes (RF.1–RF.6)

> Digest audit vs [`reference/rulebook-digest.md`](../reference/rulebook-digest.md). Shipped 2026-07 — covered by [`RulebookFidelityApiTest`](../backend/src/test/kotlin/com/mhaefele/gridiron/integration/RulebookFidelityApiTest.kt) plus targeted unit/API tests.

### RF.1 — Marked players cannot Throw

- [x] **Goal:** Throw is an **Open**-only action. Marked carriers are not offered Throw; API rejects Marked throw POST with 400. Bonus **Nerves of Steel** (D1) is the later exception.
- [x] **Shipped:** [`ActionAvailability`](../backend/src/main/kotlin/com/mhaefele/gridiron/game/legalmoves/ActionAvailability.kt) adds `THROW` only for Open carriers.
- [x] **Rulebook:** pp. 15, 17

### RF.2 — Skip entire Pre-Turn on each coach’s first turn

- [x] **Goal:** On **each** coach’s first turn, skip the **whole** Pre-Turn Sequence (Refresh Challenges, No Ball in Play, Emergency Reserves). Coach A’s first turn also cannot claim challenges (cards phase) — claim skip is Coach A–only.
- [x] **Shipped:** [`TurnEngine.runPreTurn`](../backend/src/main/kotlin/com/mhaefele/gridiron/game/turn/TurnEngine.kt) skips pre-turn on Coach A turn 1 and Coach B turn 2; [backend ARCHITECTURE](../backend/ARCHITECTURE.md#turn-sequence-state-machine) updated.
- [x] **Rulebook:** p. 14; Emergency Reserves p. 19; No Ball p. 20

### RF.3 — Throw fumble on modified result of 1

- [x] **Goal:** Fumble when Throw Check is **1 before or after modifiers** → bounce from thrower. Natural 1 remains automatic fail/fumble; natural 2 + −1 (Long/Obstructed) that yields modified 1 also fumbles.
- [x] **Shipped:** [`throwCheck`](../backend/src/main/kotlin/com/mhaefele/gridiron/game/dice/DiceCheck.kt) returns `Fumble` for natural 1 and modified 1.
- [x] **Rulebook:** p. 17 Fumble; p. 8 modifier cap / natural 1–6

### RF.4 — Emergency Reserves: one free action only

- [x] **Goal:** When dugout count **≥** team ER at the Emergency Reserves step, the coach may make **one** free Reserves Action — not repeat until the dugout falls below ER.
- [x] **Shipped:** [`TurnEngine`](../backend/src/main/kotlin/com/mhaefele/gridiron/game/turn/TurnEngine.kt) queues a single ER decision in `runPreTurn`.
- [x] **Rulebook:** p. 19 Emergency Reserves

### RF.5 — Setup ball on trapdoor without bounce

- [x] **Goal:** After both teams finish initial End Zone placement, place a ball on a trapdoor (**no** Bounce roll). Multi-trapdoor pitches: pick randomly. Distinct from pre-turn **New Ball!** which always bounces.
- [x] **Shipped:** [`NewBallResolver.placeKickoffBall`](../backend/src/main/kotlin/com/mhaefele/gridiron/game/action/NewBallResolver.kt) when leaving `PIECE_PLACEMENT`.
- [x] **Rulebook:** p. 13 setup step 7; p. 20 New Ball!

### RF.6 — Coin-toss roles (Coach A = loser of toss)

- [x] **Goal:** Toss **winner** chooses pitch (and, for box teams, which team); toss **loser** sets up first and is **Coach A** (acts first). Pitch remains create-match client choice as product stand-in.
- [x] **Shipped:** [`MatchGame.coinToss`](../backend/src/main/kotlin/com/mhaefele/gridiron/api/MatchGame.kt) sets `coachA` to the toss loser; documented + tested.
- [x] **Rulebook:** pp. 12–14

### BG.1 — Physical Throw Ruler geometry *(2026-07)*

- [x] **Goal:** Map throw range and obstruction to the cardboard **Throw Ruler** (rulebook p. 17), not abstract Within alone.
- [x] **Shipped:** Euclidean centre-to-centre Short ≤4 / full ≤8 (`THROW_RULER_SHORT_MAX` / `THROW_RULER_MAX`); obstruction along centre-to-centre **supercover** grid line (interior blocked / Open opposition, or Marked catcher). Documented in [`docs/throw-ruler.md`](../docs/throw-ruler.md); constants in [`ThrowGeometry.kt`](../backend/src/main/kotlin/com/mhaefele/gridiron/game/action/utils/ThrowGeometry.kt).
- [x] **Closed:** Accepted as complete — no further cardboard band re-measure planned.
- [x] **Rulebook:** p. 17 — Throw Ruler

---

## Partial moves (PM.0–PM.4 / PM.5.1–PM.5.2 / PM.6)

> Shipped 2026-07. Remaining-move pending + Continue Run after ball; Mark/Sidestep end on ball (no continue); Random D6 roll-then-path; Mark forced-adjacent; Handling Mark/Sidestep pickup; Elusive/Little Creature forced adjacent end; GUI remaining-move / roll-then-path. Challenge-bonus mid-move (PM.5.3–5.4) shipped with [D1.E](#d1e--bonus-play-effects-by-timing-family-2026-07).

### PM.0 — Remaining-move infrastructure

- [x] **Goal:** After a Run lands on a ball with Move left, pause with `PendingDecision.ContinueRun` (squares remaining); legal-actions offer Run continue-path + Skip. Events stay in rulebook order (pickup/bounce → continue).
- [x] **Shipped:** [`PendingDecision.ContinueRun`](../backend/src/main/kotlin/com/mhaefele/gridiron/model/PendingDecision.kt); [`ActionResolver`](../backend/src/main/kotlin/com/mhaefele/gridiron/game/action/ActionResolver.kt) applies the Run as usual then maybe prepends continue; [`LegalMoves`](../backend/src/main/kotlin/com/mhaefele/gridiron/game/legalmoves/LegalMoves.kt) + [`PlayerActionExecutor`](../backend/src/main/kotlin/com/mhaefele/gridiron/game/action/PlayerActionExecutor.kt) resume without a second budget spend; ability hooks deferred until the Run fully resolves.
- [x] **Rulebook:** p. 16 movement; interrupt/resume framing p. 9 / glossary

### PM.1 — Continue Run after ball pickup

- [x] **Goal:** On Run, entering a loose-ball square picks up (when legal) **without ending the Run** if Move budget remains — pause after pickup events, then coach continues with remaining squares (or ends early).
- [x] **Acceptance:** API — mid-path pickup → `CONTINUE_RUN`; continue up to remaining Move; Skip ends early; one budget action; carrier onto a second ball bounces then may continue. [`MovementApiTest`](../backend/src/test/kotlin/com/mhaefele/gridiron/integration/MovementApiTest.kt).
- [x] **Rulebook:** p. 16 — Picking Up a Ball (pickup does not say the Run ends)
- [x] **Note:** Former Future **F.4** (Run half). Mark/Sidestep do **not** continue — see [PM.2](#pm2--mark--sidestep-end-on-ball-no-continue).

### PM.2 — Mark / Sidestep end on ball (no continue)

- [x] **Goal:** Mark or Sidestep may **end** on a loose-ball square (bounce unless Handling); action completes there. No continue after bounce. Sidestep is one square so ball entry always ends the move; open-ball Mark terminals stay illegal (must finish Marking).
- [x] **Acceptance:** Mark onto a Marked ball square bounces with no continue prompt; open ball is not a legal Mark end. Run continue remains PM.1.
- [x] **Rulebook:** pp. 16–17, 19 — Mark / Sidestep + ball bounce (engine: ball ends Mark/Sidestep; no mid-path Mark-through-ball resume)
- [x] **Note:** Deliberately stricter than full tabletop Mark-through-ball + continue. Handling pickup on Mark/Sidestep ball entry is [PA.F.6](#paf6--handling--mid-path-ball-2026-07) / [PM.5.1](#pm51--handling-skills-mid-path).

### PM.3 — Random D6 move (Run)

- [x] **Goal:** `MoveValue.RandomD6` — roll D6 at **start** of Run, emit roll, then pending offers path up to that many squares (tabletop: roll, then move). Not a separate `ActionType`.
- [x] **Shipped:** [`LegalAction.DeclareRun`](../backend/src/main/kotlin/com/mhaefele/gridiron/api/dto/response/LegalAction.kt) → `DiceRolled(MOVE)` → [`PendingDecision.RandomMoveRun`](../backend/src/main/kotlin/com/mhaefele/gridiron/model/PendingDecision.kt); paths capped at rolled Move (not truncated). Rattle Cart in catalog. [`MovementApiTest`](../backend/src/test/kotlin/com/mhaefele/gridiron/integration/MovementApiTest.kt).
- [x] **Rulebook:** p. 16 — Random Move Value
- [x] **Note:** Former Future **F.2**. War Machine Run whilst Marked shipped in [PA.F.3](#paf3--war-machine-run-whilst-marked-2026-07).

### PM.4 — Mark forced end when becoming adjacent

- [x] **Goal:** Mark “must finish this move as soon as” the mover becomes adjacent to standing opposition — pathfinder stops extending after first Marked square.
- [x] **Shipped:** [`markPaths`](../backend/src/main/kotlin/com/mhaefele/gridiron/game/legalmoves/MovePathFinder.kt) filters out 2-step paths whose first step is already Marked; [`MovePathFinderTest`](../backend/src/test/kotlin/com/mhaefele/gridiron/game/legalmoves/MovePathFinderTest.kt).
- [x] **Rulebook:** p. 16 — Mark Action

### PM.5.1 — Handling Skills mid-path

- [x] **Goal:** Handling picks up on ball entry during Mark/Sidestep (as though a Run). No continue after — action still ends on the ball square ([PM.2](#pm2--mark--sidestep-end-on-ball-no-continue)).
- [x] **Shipped:** Already in [`BoardMovement.picksUpLooseBallOnEntry`](../backend/src/main/kotlin/com/mhaefele/gridiron/game/action/BoardMovement.kt); acceptance [`PlayerAbilityAcceptanceApiTest`](../backend/src/test/kotlin/com/mhaefele/gridiron/integration/PlayerAbilityAcceptanceApiTest.kt) (`handling skills picks up on mark onto ball square`).
- [x] **Note:** Former PA.F.6.

### PM.5.2 — Elusive / Little Creature

- [x] **Goal:** Run may enter adjacent-to-opp but **must end** there — drop paths that continue past a first adjacent square.
- [x] **Shipped:** [`mustEndRunWhenAdjacentToOpponents`](../backend/src/main/kotlin/com/mhaefele/gridiron/game/action/ability/AbilityRunRules.kt) + filter in [`runPaths`](../backend/src/main/kotlin/com/mhaefele/gridiron/game/legalmoves/MovePathFinder.kt); [`MovePathFinderTest`](../backend/src/test/kotlin/com/mhaefele/gridiron/game/legalmoves/MovePathFinderTest.kt) / acceptance.
- [x] **Note:** Untouchable / Leap / War Machine keep adjacent entry without this forced end (different card text).

### PM.6 — GUI: remaining-move / roll-then-path

- [x] **Module:** frontend
- [x] **Goal:** When pending is continue-path or RandomD6-after-roll, HUD shows remaining Move (from max legal Run path); path picker reuses selection-first UX; declare-run / continue hints; event playback does not invent order.
- [x] **Shipped:** [`remainingMoveSquaresFromLegal`](../frontend/src/render/actionVisuals.ts) + HUD / Play wiring; SSE already refetches legal actions on `InputRequired` (no full page refresh). MOVE die shout in [`presentEvents`](../frontend/src/state/presentEvents.ts).
- [x] **Note:** Remaining budget inferred from legal Run paths (no extra wire field). Challenge-bonus mid-move (PM.5.3–5.4) shipped with [D1.E](#d1e--bonus-play-effects-by-timing-family-2026-07).

---

### PM.5.3 — Defensive Play (challenge bonus) *(2026-07)*

- [x] **Goal:** Ignore interference on Run but entering adjacent **ends** the Run immediately.
- [x] **Shipped with [D1.E.2](#d1e--bonus-play-effects-by-timing-family-2026-07):** turn-scope aura + pathing in `AbilityRunRules` / `MovePathFinder`.
- [x] **Note:** Same forced-end family as PM.5.2 / Dodge (PM.5.4).

### PM.5.4 — Dodge (challenge bonus) *(2026-07)*

- [x] **Goal:** Run may move adjacent but must end Open.
- [x] **Shipped with [D1.E.1](#d1e--bonus-play-effects-by-timing-family-2026-07):** `TurnBonusModifiers.dodge` + pathfinder `mustEndOpen`.
- [x] **Note:** Was parked under partial-moves until D1 bonus wiring landed.

---

## D1.A — Card model & catalog *(2026-07)*

> First slice of Phase D1 (remnants in [BACKLOG.md](../BACKLOG.md#phase-d1-challenge-cards-remnants)). Claim/refresh turn steps remain D1.B.

- [x] **D1.A.1 — `CardState` on match** — Tableau slots 1–3, ordered deck (top visible), discard, per-coach hands, `claimsThisTurn`. Seeded at create: shuffle 26 via `DiceRoller.shuffle`, deal top→slots.
- [x] **D1.A.2 — Catalog of 26** — [`ChallengeCardCatalog`](../backend/src/main/kotlin/com/mhaefele/gridiron/model/catalog/ChallengeCardCatalog.kt) + [`ChallengeCard`](../backend/src/main/kotlin/com/mhaefele/gridiron/model/catalog/ChallengeCard.kt) titles/bonuses/conditions from [`reference/challenge-cards.md`](../reference/challenge-cards.md) (provisional collage pairings).
- [x] **D1.A.3 — Claim condition primitives** — Pure [`ClaimEligibility`](../backend/src/main/kotlin/com/mhaefele/gridiron/game/cards/ClaimEligibility.kt) over `ActionClaimFacts` + `TurnClaimStats` + board.
- [x] **Acceptance:** Catalog tests list 26 ids; points/bonus keys match reference; setup places 3 actives + deck top; public `ChallengeTableauDto` has no bonus text; seat-private `toBonusHandDto` separate from shared snapshot.
- [x] **Also:** `MatchGameStateDto.challenges` public tableau; `ScriptedDiceRoller.shuffle` is identity (deterministic catalog deal in tests).

---

## D1.B — Turn sequence: refresh, claim, Clean Sweep *(2026-07)*

> Auto-pick for single eligible claim; coach pick for multi-claim + forced replace shipped as [D1.B.choice](#d1bchoice--coach-pick-claim--replace-slot-2026-07).

- [x] **D1.B.1 — Pre-turn Refresh / Replace** — Fill empty slots from deck; if still full, coach picks discard slot (`ReplaceChallenge`). Skipped on each coach’s first activation (RF.2).
- [x] **D1.B.2–3 — Claim step** — After each completed player action (incl. free) + TD; Coach A T1 skipped; single eligible auto-claim; multi → `ClaimChallenge`; slot left empty until Refresh; points + hand + events.
- [x] **D1.B.4 — Clean Sweep** — Third claim in a turn → +2.
- [x] **D1.B.5 — Hand limit** — End of turn trim to 3 (oldest discarded).
- [x] **Facts** — [`ClaimFactsFromAction`](../backend/src/main/kotlin/com/mhaefele/gridiron/game/cards/ClaimFactsFromAction.kt) folds action-bracket events + board (not hand-filled in resolvers).
- [x] **Wiring** — [`ChallengeTurnSteps`](../backend/src/main/kotlin/com/mhaefele/gridiron/game/cards/ChallengeTurnSteps.kt) from `TurnEngine` / `PlayerActionExecutor`.

### D1.B.choice — Coach pick claim / replace slot *(2026-07)*

- [x] **Goal:** When multiple actives are eligible, coach chooses one claim; on forced replace, coach chooses which slot to discard.
- [x] **UI:** ForcedDecisionDialog for claim/replace; bot auto-picks highest points / lowest slot.
- [x] **Note:** Single eligible claim still auto; hand trim still drops oldest.

---

## D1.C — Scoring & Points Victory *(2026-07)*

> Claim points already updated score in D1.B. This slice adds endgame.

- [x] **D1.C.1 — Challenge points on score** — Already shipped in D1.B (`claimChallengeCard` / `awardCleanSweep` → `progress.addPoints`); Sudden Death unchanged.
- [x] **D1.C.2 — Score track presentation** — Skipped (optional polish); integer score remains source of truth. Noted in [`docs/D1-open-questions.md`](../docs/D1-open-questions.md).
- [x] **D1.C.3 — Points Victory** — Last challenge dealt into an active slot (Refresh/Replace) arms `pointsVictoryTurnsRemaining = 2`; each coach close decrements; at 0 → `MatchResult.PointsVictory` (draw on tie). Sudden Death at coach hand-off still pre-empts. Optional `turnLimit` only applies when Points Victory is not armed.
- [x] **Events / API** — `PointsVictoryArmed`, `PointsVictoryCountdown`; `MatchEnded.endKind` (`suddenDeath` | `turnLimit` | `pointsVictory`); snapshot `pointsVictoryTurnsRemaining`.
- [x] **Acceptance:** [`PointsVictoryApiTest`](../backend/src/test/kotlin/com/mhaefele/gridiron/integration/PointsVictoryApiTest.kt); arming unit tests on [`ChallengeTurnStepsTest`](../backend/src/test/kotlin/com/mhaefele/gridiron/game/cards/ChallengeTurnStepsTest.kt).

---

## D1.D — Bonus play framework *(2026-07)*

> Timing windows + play rules; effects are no-ops until D1.E.

- [x] **D1.D.1 — Play timing windows** — [`BonusPlayTiming`](../backend/src/main/kotlin/com/mhaefele/gridiron/game/cards/BonusPlayTiming.kt) + [`BonusPlayWindows`](../backend/src/main/kotlin/com/mhaefele/gridiron/game/cards/BonusPlayWindows.kt); `PendingDecision.BonusPlayWindow` on the existing pending queue; `isInterrupt()` blocks nested interrupts.
- [x] **D1.D.2 — Hand secrecy & play rules** — One type per turn via `TurnState.bonusPlaysUsedThisTurn`; play → discard (`MatchMutator.playBonusCard`); hands stay seat-private.
- [x] **D1.D.3 — Legal PlayBonus** — [`legalMovesForBonusPlayWindow`](../backend/src/main/kotlin/com/mhaefele/gridiron/game/legalmoves/LegalMoves.kt); only matching timing + unused types.
- [x] **Hooks** — Before-action (TurnEngine cue), start-of-turn / after-pre-turn, after-own-action / Inspiration, after block dice (Do-Over pause + `ResumeBlockDice`).
- [x] **Acceptance:** [`BonusPlayFrameworkApiTest`](../backend/src/test/kotlin/com/mhaefele/gridiron/integration/BonusPlayFrameworkApiTest.kt) — Do-Over once with two copies; Skip resumes block; before-action Skip leaves budget.

---

## D1.E — Bonus play effects (by timing family) *(2026-07)*

> Open fidelity notes in [`docs/D1-open-questions.md`](../docs/D1-open-questions.md); API coverage in `BonusPlayEffectsApiTest`.

- [x] **D1.E.1 — Pre-action modifiers** — Sprint, Aggressive Mark, Dodge, Accurate Throw, Nerves of Steel, Reserves, Jump Up.
- [x] **D1.E.2 — Turn-scope auras** — Blocking Play; Defensive Play.
- [x] **D1.E.3 — After-own-action free grants** — Blitz!, Diving Tackle, Inspiration.
- [x] **D1.E.4 — Dice re-roll / block dice** — Do-Over; Underdog; Spiked Gauntlets.
- [x] **D1.E.5 — Throw specials** — Hail Mary Pass.
- [x] **D1.E.6 — Interrupts (opposition)** — Step Aside, Intervention, Tough Enough, Shadowing.
- [x] **Acceptance:** Per-bonus API tests in `BonusPlayEffectsApiTest`.
- [x] **Also:** [PM.5.3](#pm53--defensive-play-challenge-bonus-2026-07) / [PM.5.4](#pm54--dodge-challenge-bonus-2026-07) shipped with Dodge / Defensive Play.

---

## D1.F — API, events, GUI *(2026-07)*

- [x] **D1.F.1 — Snapshot / events** — Public actives + deck top + PV flag; seat-private hand endpoint.
- [x] **D1.F.2 — Endpoints / legal actions** — PlayBonus / Skip / ClaimChallenge / ReplaceChallenge via legal-actions submit.
- [x] **D1.F.3 — Tableau + hand UI** — Actives + deck + hand + play prompts (back-plate art until faces wired).
- [x] **Acceptance:** OpenAPI regen done; face art still polish.

### D1.F.4 — Annotate legal actions that could claim a challenge *(2026-07)*

- [x] **Goal:** Tag legal player actions that could earn an active challenge; expose title / points / slot / certainty on the wire.
- [x] **Shipped:** `PotentialChallengeClaim` + `ClaimCertainty` on `LegalAction`; [`ActionAttachedClaims`](../backend/src/main/kotlin/com/mhaefele/gridiron/game/cards/ActionAttachedClaims.kt) after legal-move enumeration; Coach A turn-1 gate matches claim step; [`ClaimAnnotationApiTest`](../backend/src/test/kotlin/com/mhaefele/gridiron/integration/ClaimAnnotationApiTest.kt).
- [x] **Acceptance:** Get Moving Runs → `CERTAIN`; Take Them Down Blocks → `POTENTIAL`; turn-1 Coach A unannotated; GUI cue [FF.10](#ff10--challenge-earn-cue-on-legal-actions-gui-2026-07).

### FF.10 — Challenge-earn cue on legal actions (GUI) *(2026-07)*

- [x] **Goal:** Badge / hover copy for `potentialChallengeClaims` on legal actions (display server fields only).
- [x] **Shipped:** Claim suffix in `describeAction`; red pitch highlight stroke; chooser “Challenge” badge; unit tests in `actionVisuals.test.ts`.
- [x] **Acceptance:** Tagged actions explain title/points on hover; untagged stay clean.

### FF.2 — Catalog roster detail for create-match lineup *(2026-07)*

- [x] **Goal:** Catalog payload exposes full roster/position info for the create-match lineup strip without hardcoding team position lists in the client.
- [x] **Shipped:** `GET /api/catalog/teams` returns `PlayerCardDto` roster slots (`CatalogController` / `TeamCatalogEntry`); `CreateMatch` `LineupStrip` renders from `team.roster` (commit `44ba120`). Asset registry still maps `teamId` + position slug → art (expected); unused `TEAM_ROSTER_POSITIONS` leftover may be deleted anytime.
- [x] **Acceptance:** Lineup strip renders from server data; no team-specific position lists drive the create UI.

### FF.4 — Action legend shows only legal types *(superseded 2026-07)*

- [x] **Goal:** Legend / hover affordances must not advertise illegal action types.
- [x] **Outcome:** Sticky full `ACTION_TYPE_COLORS` legend lived in `LegalActionPanel`; removed with the sidebar action list (`0e15792`). Pitch highlights and hover copy come only from `legalActions`. Dead `.action-legend` CSS may be deleted anytime. Restoring a **filtered** legend is optional polish, not open work.
- [x] **Acceptance:** With only Run legal, UI does not advertise Block/Throw as available (met by legal-only highlights + no full legend).

### FF.5 — Interpolated run-path slides *(2026-07)*

- [x] **Goal:** Finish [UX.4](#ux4--event-animations-lower-priority) path motion — token slides along run squares; `prefers-reduced-motion` skips.
- [x] **Shipped:** `pieceMotionFromEvents` + `usePathMotion` slide tokens along path; store `motionOff` from `prefers-reduced-motion`. Live SSE animates one `playerMoved` per frame (coalesce helper exists for tests). Same intent as archived UX.4.
- [x] **Acceptance:** Multi-square Runs animate along squares; reduced motion skips interpolation.

### FF.8 — Pitch / join chrome punch-up *(won't do)*

- [x] **Won't do (2026-07):** Light art pass on token/portrait scale, end-zone/trapdoor props, letterbox framing, join/kick-off chrome, and blank catalog teams.
- [x] **Kept instead:** Existing create/matchup art bar, art-first team sort, painted boards + OOB frame, and trapdoor decor from the UX/GFX pass. Further chrome polish is not planned.

### FF.1 — Local coach / seat identity *(2026-07)*

- [x] **Goal:** HUD / bonus hand / “Your turn” bind to this client’s seat; document hotseat vs vs-computer.
- [x] **Shipped:** `state/seatIdentity.ts` (`resolveLocalCoach`, `seatIsYou`, `?coach=HOME|AWAY`); store `preferredLocalCoach`; HUD You/Hotseat/CPU chips; bonus hand follows resolved seat; seat model in [frontend ARCHITECTURE](../frontend/ARCHITECTURE.md#seat-identity).
- [x] **Acceptance:** Vs-computer shows You on the human seat only; hotseat labels both Hotseat; optional `?coach=` pins a human seat.

### FF.3 — Seed event log from hydrate *(2026-07)*

- [x] **Goal:** Join/rehydrate is not stuck on “Waiting for match events…”.
- [x] **Shipped:** Hydrate seeds `Joined match — waiting for the next action.`; empty fallback copy updated in `EventLog`.
- [x] **Acceptance:** Fresh join shows a sensible log line before the first SSE action.

### FF.6 — Hero card image weight *(2026-07)*

- [x] **Goal:** Compress team hero art without visible quality loss.
- [x] **Shipped:** All `*-hero.png` re-encoded to WebP at quality **95** (~1.1MB → ~270–320KB each); `registry.ts` imports `.webp`; PNGs removed.
- [x] **Acceptance:** Create/matchup heroes still resolve; payload clearly smaller.

### FF.7 — Distinct block-die face glyphs *(2026-07)*

- [x] **Goal:** Block theatre readable without relying only on text labels.
- [x] **Shipped:** SVG glyphs in `ActionDice` for SMASH / FLATTEN / SHOVE / TACKLE / MISS; non-block dice still use text.
- [x] **Acceptance:** Block faces are distinct at a glance; outcome shout remains.

### FF.9 — Random team selection toggle *(2026-07)*

- [x] **Goal:** Random control on create-match team pick (prefer art-backed).
- [x] **Shipped:** Random button on home/away steps in `CreateMatch`; re-roll allowed; prefers `assetRegistry.teamCard` entries.
- [x] **Acceptance:** Sets current seat’s team id; works for both steps.

---

## D1.G — Challenge-gated player abilities *(2026-07)*

- [x] **D1.G.1 — Bloodlust (Block / Throw)**
- [x] **D1.G.2 — Minion** — auto-redirect; coach choice parked in [`docs/D1-open-questions.md`](../docs/D1-open-questions.md).

---

## D1.H — Computer opponent pursues active challenges *(2026-07)*

- [x] **Goal:** Scripted bot biases toward claiming actives (shallow heuristics).
- [x] **Shipped:** `ChallengePursuit`; `ChallengePursuitTest`.
- [x] **Acceptance:** Scenario unit tests green.

---

## F.5 — Open/Marked on piece snapshot and events *(won't do)*

- [x] **Won't do (2026-07):** `standingStatus` on piece snapshot + marking-change domain events so the GUI never re-derives adjacency.
- [x] **Why:** Open/Marked already gates play via `legal-actions`; rings would be teaching chrome only, not worth the snapshot+event contract.
- [x] **GUI pair:** [F.8](#f8--gui-openmarked-rings-wont-do)

### F.8 — GUI: Open/Marked rings *(won't do)*

- [x] **Won't do (2026-07):** Token rings/badges for Open vs Marked from server `standingStatus` + marking events.
- [x] **Depends on:** [F.5](#f5--openmarked-on-piece-snapshot-and-events-wont-do) (also won't do).

## F.10 — Tag events with active ability / card ability *(2026-07)*

- [x] **Shipped:** Optional `ability` / `bonusPlay` on `DiceRolled`, `BallPickedUp`, `PlayerMoved`.

---

## PA.F.1 — Enforcer full multi-die resolution *(2026-07)*

- [x] **Shipped:** [`EnforcerAbilityApiTest`](../backend/src/test/kotlin/com/mhaefele/gridiron/integration/EnforcerAbilityApiTest.kt).

---

## PA.F.6 — Handling + mid-path ball *(2026-07)*

- [x] **Shipped:** See [PM.5.1](#pm51--handling-skills-mid-path).



## D3.1 — Foul action *(2026-07)*

- [x] **Goal:** Open player adjacent to Prone opposition may Foul; one Block Die mapped to Foul chart (Miss→Spotted, Tackle→Take That, Shove→Slipped, Smash/Flatten→Got Them).
- [x] **Files:** `FoulResolver`, `FoulResult` / `foulResultFor`, `ActionType.FOUL`, `LegalAction.Foul`, `ActionAvailability` / `foulTargets`, claim facts for Send Them Off, frontend action visuals + OpenAPI regen; digest Foul section.
- [x] **Acceptance:** Legal foul only when Open + adjacent Prone opposition; Got Them / Take That injure without armour; Spotted ejects fouler; Slipped ends fouler turn; held balls bounce before dugout; `FoulApiTest` covers chart faces; Send Them Off accepts Foul injuries (not fouler send-off alone).
- [x] **Rulebook:** pp. 23–24; Get the Ref! remapping remains [D4](../BACKLOG.md#deferred--phase-d4-optional-rules).


---

## Quick TODO — pending-queue / bonus / shake-loose *(2026-08)*

> Shipped from the old active Quick TODO. Remnants live under [Parking lot → Engine](../BACKLOG.md#engine-parked): interrupt-vs-claim product decision; MatchMutator/TurnState consolidation.

### Shake-loose crashes

- [x] **Reserves onto a loose ball forgets to bounce.** Resume after Reserves bonus rebuilt placement without `ballEncounter` → crash in `applyReservesPlacement`. Fixed: persist `ballEncounter` on `ResumeCommittedBasicAction`.
- [x] **Headbutt free Block while already Prone** (Diving Tackle combo). Standing check at offer + drop grants when DT puts blocker Prone.

### Pending-queue / one-at-a-time

- [x] **One-at-a-time free follow-up grants.** Queue drains front-first; FSM stays on `EXECUTE_FREE_FOLLOW_UP` / `EXECUTE_AFTER_OWN` while grants remain.
- [x] **One-at-a-time Emergency Reserves dugout picks** — already a single pending; no change.
- [x] **Jump Up / Reserves (`_takeAction` dispatch).** Pop-first pending dispatch; `FreeAbilityGrant` → `FreeActionOffer`; named mid-action bonus pendings + `BonusOffers` / `BonusOfferHandler`.
- [x] **Dedicated pending for free Reserves bonus** — `OfferReservesCommitted` only; Play via committed offer.
- [x] **Stop exposing `MatchMutator.pendingDecisions` as a full list.** Added `pendingFront()` / `hasPendingFront` / `pendingQueueEmpty`; list getter remains for full-queue ops.

### Bonus / interrupt / ability timing

- [x] **`BonusPlayWindow` framework → named offers.** Killed `BonusPlayTiming` / resume bags; mid-block/throw state is real pendings; decline via `BonusOfferId`.
- [x] **Multi free-follow-up / after-own chains (FSM).** Stay while grants remain; after-own re-queries Block abilities (Hit and Run).
- [x] Don't offer bonus plays when there is no action possible (Blitz / Reserves / Step Aside gates; Diving Tackle already gated).
- [x] Free abilities before challenge cards. Engine offers `FREE_FOLLOW_UPS` before `AFTER_OWN_BONUSES`; FSM stay keeps grants from being skipped.
- [x] **Relentless-after-Mark tag clarification.** Two catalog constants share printed name "Relentless"; InputRequired uses each grant's own ability.
- [x] Diving Tackle × player-ability combos. Headbutt-after-DT-prone fixed; Safe Hands after DT prone covered in `BonusPlayEffectsApiTest`.
- [x] **Throw Team-mate → `OngoingThrowTeammate` + `ThrowTeammateContinuation`.** Select → landing uses the Throw/Block continuation pattern; wire `THROW_TEAMMATE_CONTINUE`.
- [x] **Sprint / Dodge / Aggressive Mark off turn gamestate.** Geometry via `ActionAttachedBonuses` + `requiredBonusCard` / ephemeral `PathBonusFlags`; Diving Tackle prone-after on `OngoingBlock`. `TurnBonusModifiers` keeps only Defensive Play / Blocking Play.

### MatchMutator / TurnState tracking

- [x] **Removed write-only `playerActionStillInProgress`** (+ `notePlayerActionStillInProgress` call sites). Consolidation of remaining dual fields parked in active backlog.

---

## PA.C — Align ability behavior with official card text *(2026-07)*

> Source of truth: [`PlayerAbility`](../backend/src/main/kotlin/com/mhaefele/gridiron/model/catalog/PlayerAbility.kt). API coverage in `PlayerAbilityAcceptanceApiTest`, `PlayerAbilityApiTest`, `EnforcerAbilityApiTest`, `MovePathFinderTest`, `ClaimEligibilityTest`.

- [x] **PA.C.1 — Hit and Run:** free Run ≤2; may end adjacent
- [x] **PA.C.2 — Defensive:** adjacent opposition cannot Assist (Block still legal)
- [x] **PA.C.3 — Colossus:** Tackle+Smash → Flatten!
- [x] **PA.C.4 — Mighty Blow:** Smash → Flatten! (then armour)
- [x] **PA.C.5 — Wild Ward:** target Blocks may not be Assisted (not nullified)
- [x] **PA.C.6 — Lithe Creature:** end Open; cannot Throw; can be Blocked
- [x] **PA.C.7 — Underfoot:** entering adjacent ends opposition Run; Tackle→Miss
- [x] **PA.C.8 — Elusive:** must end Run Open; may continue past adjacent
- [x] **PA.C.9 — Enforcer:** armour per KD; auto follow-up after Shove
- [x] **PA.C.10 — Violent:** Block or Foul; D6 on 6 skips resolving dice
- [x] **PA.C.11 — Ethereal:** through opp/teammates/obstructions; end Open
- [x] **PA.C.12 — Regenerate:** Prone free Stand Up after Pre-Turn
- [x] **PA.C.13 — Bomber:** Open required; Shove→Miss
- [x] **PA.C.14 — Leap:** through opp/teammates; end Open
- [x] **PA.C.15 — Wardance:** free Sidestep when Marked after an action
- [x] **PA.C.16 — Pick-me-up:** adjacent Prone teammates only
- [x] **Acceptance:** API/unit tests match card descriptions
- [x] **Rulebook / cards:** official team pages under `reference/html/`; p. 9 card text wins

---

## D1.C-fix — Challenge claim / bonus behavior vs official site *(2026-07)*

- [x] **D1.C-fix.1 — Show No Fear:** claim when a player ends **any Action** on a Trapdoor (`ClaimEligibilityTest`)
- [x] **D1.C-fix.2 — Pairings:** sync catalog to official Challenge ↔ Bonus table
- [x] **Acceptance:** claim/eligibility + catalog tests follow [`reference/challenge-cards.md`](../reference/challenge-cards.md)
- [x] **Rulebook:** pp. 21–22; official Cards page under `reference/html/`

---

## D3 — Advanced Actions *(closed)*

### D3.2 — Throw Team-mate *(2026-08)*

- [x] **Goal:** Throw Team-mate (Ogre/Troll/Oakwalker); occupied square landing; fumble injury rules.
- [x] **Shipped:** [`ThrowTeammateResolver`](../backend/src/main/kotlin/com/mhaefele/gridiron/game/action/ThrowTeammateResolver.kt), `LegalAction.ThrowTeammateSelect` / `ThrowTeammate` / `CancelThrowTeammate`, `PendingDecision.ThrowTeammateLanding`, [`ThrowTeammateApiTest`](../backend/src/test/kotlin/com/mhaefele/gridiron/integration/ThrowTeammateApiTest.kt); bot prefers ball-carrying teammate; claim facts treat Throw Team-mate as a Throw Action.
- [x] **Acceptance:** Two-step select → landing; cancel returns to select; occupied landing / fumble injury per pp. 23–24.
- [x] **Rulebook:** pp. 23–24 — Special Actions

> **D3 closed** for base advanced actions (Foul [D3.1](#d31--foul-action-2026-07) + Throw Team-mate). Get the Ref! foul remapping waits on [D4](../BACKLOG.md#deferred--phase-d4-optional-rules).

---

## AI1.6 — Lookahead over the legal move graph *(2026-08)*

- [x] **Goal:** Smarter scripted opponent: simulate candidate legal actions against a copy of match state, score resulting positions, pick from the evaluated move graph.
- [x] **Shipped:** [`LookaheadSearch`](../backend/src/main/kotlin/com/mhaefele/gridiron/game/opponent/lookahead/LookaheadSearch.kt), [`ActionSimulator`](../backend/src/main/kotlin/com/mhaefele/gridiron/game/opponent/simulator/ActionSimulator.kt), [`LookaheadOpponentStrategy`](../backend/src/main/kotlin/com/mhaefele/gridiron/game/opponent/lookahead/LookaheadOpponentStrategy.kt) (`@Primary`); private per-sample `ScriptedDiceRoller` (never live `MatchGame` roller); think budgets (~300ms vs human / ~100ms AI-vs-AI); dicey blocks/bombs still fall back to heuristic; prompt handling defers to [`ScriptedOpponentStrategy`](../backend/src/main/kotlin/com/mhaefele/gridiron/game/opponent/scripted/ScriptedOpponentStrategy.kt).
- [x] **Tests:** `LookaheadOpponentStrategyTest`, `HeuristicVsLookaheadTest`, arena runners.
- [x] **Acceptance:** Bot prefers better one-/short-N-ply outcomes in fixtures; search bounded; human play / SSE unchanged when bot not seated.
- [x] **Note:** Still heuristic search over the closed legal set — **not** [AI2](../BACKLOG.md#deferred--phase-ai2-trainable-model). Residual polish (e.g. singleton telemetry) is not open backlog work.
- [x] **Rulebook:** n/a

---

## AI2 — Headless binary harness (transport) *(won't do 2026-11)*

> Parked after [AI2.25](#ai225--measure-harness-submit-timing-2026-11) measured live eval timings. Short pointer in active [`ai/BACKLOG.md`](../ai/BACKLOG.md#headless-binary-harness-transport-wont-do). Re-open only if pack/wire/contention becomes hot again (e.g. much cheaper opponents, or scenario-only train where search is gone).

### Intent (what we scoped)

Faster PPO rollout by dropping localhost HTTP+JSON for train, without changing rules, obs packing ownership, or the SB3 harness.

| Today | Target we considered |
|-------|----------------------|
| 30 `SubprocVecEnv` workers → one Spring Boot on `:18080` | Same workers → **headless** Boot over **Unix-domain socket** |
| One `POST …/submit/packed` JSON per policy step | Length-prefixed **binary** frames (same tensors) |
| Opponent already in-process on `COMPUTER` | Unchanged |
| Expert dump is headless one-shot (`--export-expert`) | Train host would be headless **long-lived** UDS server |

**Two launch shapes:** game host = `./dev.sh` HTTP; train host = headless Boot + UDS. Shared `HarnessEpisode` facade so HTTP controllers and UDS call one episode path (no grading fork). Catalog stays Kotlin-owned.

**Why headless (vs UDS on the game host):** train saturates the JVM; Tomcat/SSE/human TTL are tax; separate heap/GC; no `:18080` fight. Expert export already proved headless Boot.

**Abandoned even before the gate:** JPype/JNI embed; rewriting the SB3 loop in Kotlin; UDS-on-`./dev.sh` as the primary train path.

**Planned work order (not started):** AI2.25 gate → AI2.26 facade → AI2.opt binary codec → AI2.27 headless UDS → AI2.28 Python client + scripts.

**Expected speed (pre-measure guesses):** binary ~1.2–2× on scenario if JSON hot; +UDS ~1.3–2.5×; vs LOOKAHEAD full games only ~1.05–1.3× if search dominates.

### AI2.25 — Measure harness submit timing *(2026-11)*

- [x] **Goal:** Go/no-go — proceed only if pack/wire/contention roughly **≥ ~30%** of scenario `submit` time.
- [x] **Evidence:** `./eval.sh` (baseline) + Spring `AiSubmitTiming` INFO every 400 submits.
- [x] **Python `submit_ms`:** vs-lookahead **35.9**; vs-heuristic **18.7**; short scenario drills **~3.5–6**; longer scenarios (deny_pickup d4 / throw_to_endzone d5) **~21**.
- [x] **Spring (LOOKAHEAD batches):** `total_ms` ~19–36; **`turnWait` ~70–80%** (`lookahead_ms` ~12–23; handoff-only ~60–140); `next_legals` ~2–3; `engine` ~0.5–1; **`pack_dto`+`pack_flat` ~0.4–0.7**.
- [x] **Spring (HEURISTIC / `lookahead_ms=0`):** `total_ms` ~6–9; handoffs ~12–16 ms; pack still sub-ms.
- [x] **Decision:** **No-go.** Absolute pack/wire savings ~1–2 ms on scenario steps; noise next to lookahead on full games. Lever = opponent mix / search budget, not sockets.
- [x] **Rulebook:** n/a

### AI2.26 / AI2.opt / AI2.27 / AI2.28 — *(won't do)*

- [x] **Won't do (2026-11):** `HarnessEpisode` facade, binary frame codec, headless UDS train host, Python UDS client + train script lifecycle — not worth it under current timings.
- [x] **Revisit if:** scenario-only or heuristic/ONNX-dominated train shows pack+HTTP as a large share of `submit_ms`, or worker count / JSON payload grow enough to change the gate.

---

## Frontend follow-ups — all shipped *(2026-07)*

> Active backlog had **no remaining FF items**. Archive: [FF.1](#ff1--local-coach--seat-identity-2026-07)–[FF.7](#ff7--distinct-block-die-face-glyphs-2026-07), [FF.9](#ff9--random-team-selection-toggle-2026-07)–[FF.10](#ff10--challenge-earn-cue-on-legal-actions-gui-2026-07); [FF.8](#ff8--pitch--join-chrome-punch-up-wont-do) won't do. Challenge face art on-screen via [GFX.14](#gfx14--challenge-card-art-pack) + D1 tableau.

---

## Phase D1 remnants — face art *(won't track)*

- [x] **Per-card challenge face art** — shipped in registry `CHALLENGE_FACES` (c01–c26) + tableau/celebration UI. No longer an open D1 polish item.
- [x] **Minion coach choice / opponent hand-count UI** — remain parked in [`docs/D1-open-questions.md`](../docs/D1-open-questions.md) only (not active backlog).


---

## Task 7.13 — Better API / domain test style *(partial ship 2026-08)*

> Practices and acceptance already landed. Remaining open work stays in [BACKLOG.md](../BACKLOG.md#task-713--better-api--domain-test-style).

- [x] **Goal:** Make geometry-sensitive and ability-gated API/domain tests readable, exact, and self-contained.
- [x] **Files:** helpers + ability / movement / pathfinder suites.
- [x] **Exact membership via helpers:** `assertAvailable` / `assertUnavailable` / `action` / `onlyAction` in [LegalActionAsserts.kt](../backend/src/test/kotlin/com/mhaefele/gridiron/support/LegalActionAsserts.kt); play via `api.play(...)`.
- [x] **Ability negative control:** same geometry twice — plain absent / ability present.
- [x] **ASCII diagrams when position matters.**
- [x] **Self-contained tiny pitches:** `GameScenario.tinyBoard(...)` / `sealedEndZones(width)`.
- [x] Support helpers: LegalActionAsserts, PathAsserts, tinyBoard / sealedEndZones, `GridironApiClient.play`.
- [x] Migrated trail: PlayerAbilityApiTest, ability sections of MovePathFinderTest + PlayerAbilityAcceptanceApiTest, first tests in MovementApiTest.

**Still open (active backlog):** `TeamCatalogTestHelper` migration of remaining roster-name hardcodes; avoid MatchBuilder pending/step inference; gradual migration of remaining `filterIsInstance` suites.

