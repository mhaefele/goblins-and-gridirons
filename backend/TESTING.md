# Testing strategy

Agent gate (short): [`.cursor/rules/testing-strategy.mdc`](.cursor/rules/testing-strategy.mdc).
Harness layout notes also summarized in [ARCHITECTURE.md — Testing strategy](ARCHITECTURE.md#testing-strategy).

## Absolute rules

1. **Never write unit tests.** Do not add new tests that call production code without HTTP. Features go through [BaseApiTest](src/test/kotlin/com/mhaefele/gridiron/integration/support/BaseApiTest.kt) + [GridironApiClient](src/test/kotlin/com/mhaefele/gridiron/integration/support/GridironApiClient.kt). Existing domain tests may stay; do not extend them instead of an API test.
2. **API test mandatory for features.** If the product behavior is reachable over HTTP, that HTTP test is required — not optional, not “also add a unit test instead,” not “and a bean-direct test for the same story.”
3. **Default for everything else is API.** Turn execution (`TurnEngine` / `PlayerActionExecutor` / `MatchGame`), setup, legal moves as the client sees them, HUD/stream folds, match over — **all of that is API-tested.** Mid-game awkwardness is solved with `seedMatch(...)` + `api.play`, not by calling the crank bean.
4. **Forbidden without an explicit, agreed exception in conversation first:** `@Autowired` / bean-direct tests of orchestrators (`TurnEngine`, `PlayerActionExecutor`, `RegularActionHandler`, `MatchGame`, …). “HTTP is awkward,” “I already have the session,” and “this is the fundamental turn loop” are **not** exceptions. Raise it, wait for agreement, write the API test first, then (rarely) add a bean test only if something still cannot be observed over HTTP.
5. **Never do our own DI to wire up a Spring bean.** Do not `Foo(Bar(), Baz())` (or manual graphs) for types that are `@Component` / Spring-managed. Go through HTTP.
6. **Never unit-test Spring beans that inject other Spring beans.** Orchestrators are composition — vacuum tests are low value and usually mean hand-wiring or mocks. Prefer API.

Calling Spring beans from tests to exercise product behavior is an anti-pattern here. It is one of the most frequent causes of brittle and useless tests. Treat “direct bean call” as a last resort that needs a written reason, not a convenience.

### Gate — before any non-API test

| Kind | When |
|------|------|
| **API test** | **Always** — features, turn flow, mapping, anything a client/coach can observe |
| **New unit test** | **Never** |
| **Direct Spring-bean test** | **Banned.** Only after API coverage exists **and** the user explicitly agreed in chat that HTTP cannot assert the thing |

### Concrete anti-patterns (do not reintroduce)

- Catalog `definition(id)` / list / unknown id — `GET /api/catalog/...` (and bad create-match)
- `MatchBootstrap` / `MatchGame` persist or setup — create + snapshot over HTTP
- Coin-toss → Coach A — create match with queued dice
- Calling `turnEngine.advance` / `playerActionExecutor.takeAction` / `actionResolver.apply` in a test because seeding + `api.play` felt longer
- A `*Test` that `@Autowired`s `TurnEngine` (or siblings) to prove turn hand-off, pre-turn, sudden death, Points Victory, or HUD events — those are [TurnAdvanceApiTest](src/test/kotlin/com/mhaefele/gridiron/integration/TurnAdvanceApiTest.kt) / stream API territory
- Hand-constructing `@Component` graphs in tests (`TurnEngine(...)` with local deps)
- Hand-building `LegalAction` / DTO lists and calling mapper beans in a unit test — seed a match and `GET` the AI step

## Feature API tests — the real thing

**Goal: test features and observable outcomes, not implementation details.**

- Real Spring container once ([BaseApiTest](src/test/kotlin/com/mhaefele/gridiron/integration/support/BaseApiTest.kt)) — not sliced “application layer” tests
- Real HTTP — typed Kotlin in/out; non-2xx throws `HttpStatusCodeException`, not MockMvc
- Real persistence adapter (`InMemoryMatchRepository` today; **Testcontainers + real DB** when we add a database)

Boundary doubles replace **external systems** only (e.g. `ScriptedDiceRoller` for RNG) — not repositories, validators, or domain resolvers we own.

Most production bugs live **outside** pure domain math (wiring, validation, JSON, persistence). Skipping the HTTP stack misses where issues show up. One shared Spring context keeps the suite fast enough.

### Stable tests survive refactors

Assert **what the user/system sees** (HTTP response, persisted state, legal actions) — not how beans call each other.

**Never write tests that:**

- Assert which dependency methods were invoked (`verify(mock)`, spy on DAOs)
- Require a bean to have a specific dependency graph
- Duplicate an API scenario at a lower layer “for coverage”
- Unit-test or hand-wire a Spring `@Component`

**Do write tests that:**

- `POST .../run` with a legal path → `GET` shows the runner moved
- Invalid command → 4xx with a useful message, state unchanged
- Turn ends → next `GET` shows coach swapped and action budget reset

## Do not add unit tests

Do not add new `*Test` classes that call production functions without HTTP. Existing domain tests (pathfinding, marking, topology, dice math) may stay; do not grow them as a substitute for API coverage.

### Spring boots once — do not restart between tests

The Spring context must **start once** and be **reused for the entire test suite**. Restarting Spring per test is too slow and must be avoided.

Implications:

- **One parent owns `@SpringBootTest`:** [BaseApiTest](src/test/kotlin/com/mhaefele/gridiron/integration/support/BaseApiTest.kt) (`RANDOM_PORT` + `test` profile + HTTP helpers). Never redeclare `@SpringBootTest` / `@ActiveProfiles` / competing `@Import`s on a subclass.
- Rely on Spring's context cache (identical config → same context). [SingleSpringContextGuard](src/test/kotlin/com/mhaefele/gridiron/support/SingleSpringContextGuard.kt) fails the suite if a second fingerprint boots.
- Define boundary test doubles **once at class level** (test profile + `@TestConfiguration`), not per test method.
- **Never** use `@MockBean` on individual tests or configs that differ per test — that creates a new context and restarts Spring Boot.
- **Never** use `@DirtiesContext` routinely.
- Per-test variation (e.g. dice rolls): configure the **same bean instance** before each test (`ScriptedDiceRoller.reset(); queueD6(4, 6, 1)`) — do not swap bean definitions.

## Never: mock-heavy “unit tests”

**Traditional tests where every dependency is mocked must never be used.** Same spirit as “don’t unit-test orchestrator beans” and “never hand-wire DI.”

Forbidden:

- `@MockBean` / `@Mock` / Mockito on services, resolvers, validators, repositories, DAOs
- Instantiating a Spring `@Component` with mocked or hand-built constructor dependencies
- `verify(mock)` instead of asserting real outcomes
- Per-test Spring bean overrides that restart the context

If widespread mocking seems necessary, use an API test.

## Boundary test doubles (class-level only)

Full replacement implementations at system edges — registered once in test profile:

| OK | Example |
|----|---------|
| RNG / dice | `ScriptedDiceRoller` as `DiceRoller`; `reset()` / `queueD6`/`queueD8`/`queueBlockDice` in `@BeforeEach` |
| External HTTP APIs | Fake client in `@TestConfiguration` on base test class |

Not Mockito. Same bean for all tests in the class; reconfigure behavior, don't redefine the bean.

## Test-driven development

**Prefer TDD when practical:** write a failing test first, then implement until it passes.

- **Features:** failing [BaseApiTest](src/test/kotlin/com/mhaefele/gridiron/integration/support/BaseApiTest.kt) first — mandatory
- Skip TDD theater for pure scaffolding (empty packages, stub types)

## Test data: APIs when real; helpers when not

**Use public HTTP APIs to set up data when the product would allow that operation.**

- API tests: `POST /api/matches`, then play via verb action POSTs → assert via `GET`
- Do **not** invent admin/debug/setup endpoints just to make tests easier

**Use test helpers when the API would not (and should not) expose that setup.**

**Fixture builders may call APIs internally** when that is the natural setup path — the test stays fluent; the builder hides HTTP or repository details.

Examples that belong in test helpers, not new API surface:

- Mid-game `Board` / `MatchState` with arbitrary piece placement
- Skipping turn sequence to a specific phase with N actions remaining
- Injecting state via test helpers that delegate to `MatchGame` (or wrappers like `MatchBootstrap`) when HTTP cannot reach that state reasonably — **tests do not call `repository.save()` directly**

Never stub `findById` / `save` with canned data.

## Test fixtures: convention over configuration

Prefer **fluent builders with sensible defaults** — override only what the test cares about.

```kotlin
// Good — defaults cover irrelevant detail; test names the meaningful delta
getDefaultBoardSetup()
    .withHomePlayer(status = Standing)
    .withAwayPlayerAdjacentToHome()
    .build()

GameScenario.match()
    .withActionsRemaining(2)
    .withActiveCoach(TeamSide.HOME)
    .build()

// Good — square only matters when the test is about geometry
getDefaultBoardSetup()
    .withHomePlayer(at = Coordinate(3, 4))  // explicit because path test depends on it
    .build()

// Bad — re-specifying the whole board when only marking matters
Board(
    pitch = customPitch,
    players = mapOf(
        PlayerId("h1") to Player(..., position = Coordinate(3, 4), ...),
        PlayerId("a1") to Player(..., position = Coordinate(4, 4), ...),
    ),
    ...
)
```

Rules:

- **Default layout** — shared `getDefaultBoardSetup()` / `getDefaultMatchState()` (or builder entry points) with valid, legal baseline positions
- **Hardcode arbitrary values** for coordinates, ids, and roster slots when the test does not depend on them — do not make every test pick squares
- **Override only meaningful fields** — `.withActionsRemaining(0)`, `.withPlayerMarked()`, `.withBallAt(coord)` when that is what under test
- **Persisted fixture insert** — mid-game API tests use helpers that save through `MatchGame` / `MatchBootstrap`, not raw `repository.save()` in test bodies
- **API-backed builders** — OK for fixtures to call `POST /api/matches` and verb actions under the hood when setup is reachable that way; e.g. `matchViaApi().homeRunsToMarkOpponent().build()`

Place shared builders in `backend/src/test/kotlin/.../support/` (e.g. `BoardFixtures.kt`, `MatchFixtures.kt`, `MatchScenario.kt`).

### Fixture construction stays Spring-free

**Default: build test data without Spring.** [GameScenario](src/test/kotlin/com/mhaefele/gridiron/support/GameScenario.kt), [BoardScenario](src/test/kotlin/com/mhaefele/gridiron/support/BoardScenario.kt), `matchState()`, and `MatchState` copy helpers assemble `Board` / `MatchState` directly — no `@Component`, no context.

| Need | Entry point | Spring? |
|------|-------------|---------|
| Board layout (players, ball, marking) | `GameScenario` / `BoardScenario` | No |
| Turn context around a board | `matchState()` + `.withX()` | No |
| Resolve action / advance turn | `seedMatch` + `api.play` / `api.legalActions` via [BaseApiTest](src/test/kotlin/com/mhaefele/gridiron/integration/support/BaseApiTest.kt) | Yes — HTTP execution |
| Full match from catalog with trapdoor ball roll | [MatchBootstrap](src/test/kotlin/com/mhaefele/gridiron/support/MatchBootstrap.kt) / create-match over HTTP | Yes — dice-dependent kickoff only |

A test may extend `BaseApiTest` and still use Spring-free fixtures for **setup**: `val state = matchState(board = …)` then `seedMatch(state)` then `api.play`. Spring beans are for the running server, not for the test to drive turn execution.

**Keep fixtures Spring-free unless maintaining that split gets genuinely painful** (e.g. setup logic only exists on a wired bean and duplicating it in fixtures would drift). When that happens, prefer a thin Spring helper (`MatchBootstrap`) over hand-constructing `@Component` graphs in tests.

### API tests: typed client, not MockMvc

REST is transport. API tests call endpoints like regular code — no `perform` / `andExpect` / `jsonPath`.

- **[ApiTestConfiguration](src/test/kotlin/com/mhaefele/gridiron/support/ApiTestConfiguration.kt)** — `RANDOM_PORT` server; `RestTemplate` from Boot's `RestTemplateBuilder` rooted at that port (not fault-tolerant `TestRestTemplate`)
- Do **not** assert `status().isOk` on success paths — a bad status should fail by throwing
- Assert on response **objects** and persisted state via follow-up GETs, not HTTP mechanics

**Inject properties; don't reach for `Environment`.** Prefer `@LocalServerPort`, `@Value`, or typed `@Bean` / constructor parameters. Use `Environment` only when the key is truly dynamic. Example — `@LocalServerPort port: Int` on the `restTemplate` `@Bean`, not `environment.getProperty("local.server.port")`.

```kotlin
@Test
fun `illegal run is rejected`() {
    val match = api.post<MatchState>("/api/matches", createRequest)

    assertThatThrownBy { api.post<MatchState>("/api/matches/${match.id}/run", badRun) }
        .isInstanceOf(HttpStatusCodeException::class.java)
        .satisfies { ex ->
            assertThat((ex as HttpStatusCodeException).statusCode.value()).isEqualTo(400)
        }
}
```

## GameScenario / BoardScenario — start here

**Default for most tests:** start from [GameScenario](src/test/kotlin/com/mhaefele/gridiron/support/GameScenario.kt), not raw `Board(...)` constructors or hand-built player maps.

| Need | Entry point |
|------|-------------|
| Empty pitch | `GameScenario.emptyBoard()` or `GameScenario.board()` |
| Both teams on the board | `GameScenario.bothTeamsDeployed()` |
| Custom piece layout | `GameScenario.board().addPlayer(...)` → [BoardScenario](src/test/kotlin/com/mhaefele/gridiron/support/BoardScenario.kt) |
| Full match / HTTP flow | Same harness; API-backed wrappers land in Task 7.0 |

Domain tests call `runner.player` (from `addPlayer`) when invoking pure functions. API tests reuse the same states — only the outer wrapper differs.

### Never hardcode player or ball ids in tests

[BoardScenario](src/test/kotlin/com/mhaefele/gridiron/support/BoardScenario.kt) generates ids (`h0`, `h1`, `a0`, …; `ball0`, `ball1`, …). Use the returned [PlacedPlayer](src/test/kotlin/com/mhaefele/gridiron/support/BoardScenario.kt) or the [Ball] from `addLooseBall` — do **not** sprinkle `PlayerId("h1")` or `BallId("ball0")` literals that can drift from the scenario.

```kotlin
// Good — ids come from the scenario
val runner = board.addPlayer(5, 6)
val looseBall = board.addLooseBall(5, 6)
movementPaths(runner.player, board.board, 1)

// Bad — hardcoded ids may not match what the board actually has
movementPaths(Player(id = PlayerId("h0"), ...), board, 1)
assertThat(events).contains(DomainEvent.BallBounced(BallId("ball0"), ...))
```

When placing opposition relative to a player already on the board, chain off `PlacedPlayer` (`runner.team.opposite()`, `runner.player`, etc.) instead of inlining team/id constants.

Low-level model tests (`PlayerTest`, `Board.withSquareAt` mechanics) may still construct ids directly when the test is about the type itself, not a game layout.

### New model fields — defaults in scenarios, not test churn

Adding a field to a domain type (e.g. `Player.profile`) **should not require editing existing tests**. Those tests could not have depended on a field that did not exist when they were written.

- **Put sensible defaults in [BoardScenario](src/test/kotlin/com/mhaefele/gridiron/support/BoardScenario.kt) / [GameScenario](src/test/kotlin/com/mhaefele/gridiron/support/GameScenario.kt)** (or shared `TestProfiles`-style helpers) so `addPlayer(...)` and similar entry points stay one-liners.
- **Existing tests stay unchanged** unless they assert on the new field or need a non-default value for the behavior under test.
- **Add new tests** only when the new field introduces behavior worth asserting (validation, derived rules, catalog accuracy) — not to re-plumb every layout test.
- **Override in the scenario** when a test cares: `addPlayer(coord, profile = orcLineman)` — same pattern as status and team.

```kotlin
// Good — Group A lands; pathfinding tests untouched
val runner = board.addPlayer(5, 6)  // profile defaults to lineman in BoardScenario

// Good — test is about unthrowable + ball; override profile explicitly
val lineman = PlaceholderTeamCatalog.orcs().roster.first { it.name == "Lineman" }
board.addPlayer(coord, profile = lineman)

// Bad — sprinkling new required fields into every test that places a player
Player(id, team, TestProfiles.lineman(), status, heldBallId = null)
```

### Fluent helpers — overload aggressively

Scenario and fixture methods should read like the test story. **Add overloads** so callers pass the natural type — never force `.x` / `.y` unpacking or repeat boilerplate constants.

```kotlin
// Good — overload accepts Coordinate when the test already has one
val start = Coordinate(5, 6)
board.addPlayer(start)

// Bad — leaking components when a Coordinate is in hand
board.addPlayer(start.x, start.y)
```

When adding a new helper, ask what reads most naturally in the test body, then overload until it does: `Coordinate` vs `(x, y)`, player-relative placement (`adjacentTo(runner)`), team shorthands, etc. Prefer chaining (`board.addPlayer(...).marking(...)`) over multi-step setup with locals.

## Readable, fluent tests

Tests should be **short and read like English** — how you'd describe the scenario aloud. Gherkin-shaped flow is fine; separate `.feature` files and rigid Given/When/Then ceremony are not required.

```kotlin
@Test
fun `marked dwarf can block the orc it is marking`() {
    val state = defaultMatch()
        .withHomePlayerMarkedAgainstAway()
        .build()

    val actions = legalMovesForActiveCoach(state)

    assertThat(actions).contains(block(homeBlitzer, awayLineman))
}

@Test
fun `hand off to adjacent teammate succeeds without rolling`() = apiTest {
    val match = createMatch(dwarves vs orcs)
    match.homeCarrierAtDefault().adjacentAwayReceiver()

    match.handOff(from = carrier, to = receiver)

    match.shouldHaveBallHeldBy(receiver)
}
```

- **Name tests as behavior** — backtick names or clear method names, not `testCase7`
- **Fluent helpers** — chain setup and actions; hide incidental HTTP, ids, and coordinates
- **Assert the story** — one or two meaningful expectations, not every field on the response

## Parameterized tests

**Strongly prefer `@ParameterizedTest`** when the same logic applies across many inputs — table-driven cases, rulebook examples, edge variants.

```kotlin
@ParameterizedTest
@CsvSource(
    "1, 3, true",   // natural 6 always succeeds
    "6, 3, true",
    "1, 4, false",  // natural 1 always fails
)
fun throwCheck(roll: Int, threshold: Int, expected: Boolean) {
    assertThat(passesCheck(roll, threshold, modifiers = 0)).isEqualTo(expected)
}
```

Use parameterized tests for: dice thresholds, path legality matrices, modifier combinations, status→allowed-action tables. One-off scenarios stay as single `@Test`.

When iterating many similar cases (e.g. every legal move), **prefer `@ParameterizedTest` over a `for` loop** so failures identify the exact case. Reserve loops for dynamically generated collections where flattening to parameters is awkward.

## Broad invariant tests

Favor tests that **cover a lot of ground with a simple invariant** instead of dozens of nearly identical examples.

**Prefer `@ParameterizedTest` over a plain `for` loop** when iterating cases — each input becomes a named row so failures point at the exact case (JUnit shows parameter values in the report). Use a loop only when cases are generated dynamically and parameter sources would be awkward.

```kotlin
@ParameterizedTest
@MethodSource("allLegalMovesFromDefaultDeployment")
fun `legal move preserves occupancy invariants`(action: LegalAction) {
    val state = defaultMatch().fullyDeployed().build()
    val next = resolve(action, state, deterministicDice)

    assertOccupancyConsistent(next.board)
    assertAtMostOneBallPerSquare(next.board)
}

// Plain @Test + for-loop: OK when enumeration is dynamic and not worth flattening
@Test
fun `every generated run path ends on pitch`() {
    val paths = runPaths(player, board)  // count unknown upfront
    for (path in paths) {
        assertThat(path.last()).isInBounds(board.pitch)
    }
}
```

Good invariant targets: occupancy consistency, action budget never negative, resolved state always passes validator, legal-move generator never returns duplicate action types per player, scores monotonic except touchdowns.

These complement — do not replace — targeted tests for specific rulebook drills.

## Test data: insert, then use (summary)

| Setup need | Approach |
|------------|----------|
| New match from scratch | `POST /api/matches` (directly or via fixture builder) |
| Reach state through gameplay | HTTP action sequence or fluent `matchViaApi()` builder |
| Mid-game / arbitrary layout | Test fixture builder → `MatchGame` / `MatchBootstrap` persist, or pure domain build |
| Dice outcomes | `ScriptedDiceRoller.queueD6` / `queueD8` / `queueBlockDice` — not new API |

## Layout

- `backend/src/test/kotlin/.../game/` — action, turn, legalmoves, board tests (no Spring)
- `backend/src/test/kotlin/.../support/` — **[GameScenario](src/test/kotlin/com/mhaefele/gridiron/support/GameScenario.kt)** (growing game harness; every test eventually starts here) and fluent builders as they land (Task 7.0)
- `backend/src/test/kotlin/.../integration/` — API / system tests (extend shared `@SpringBootTest` base in `integration/support/`); [LivingGamePlaythroughTest](src/test/kotlin/com/mhaefele/gridiron/integration/LivingGamePlaythroughTest.kt) for manual visual stepping (script in `scenario/LivingGamePlaythrough.kt`)
- `backend/src/test/kotlin/.../spring/` — test double tests if needed

## Checklist

1. Can this be a pure function test with fixture state? → Prefer that for move/path/marking logic.
2. Does this need HTTP + persistence + turn flow? → API test on shared Spring context.
3. Am I adding `@MockBean` or per-test bean overrides? → **Stop.**
4. Will this restart Spring? → Restructure (shared base class, reconfigure dice bean).
5. Can this state be reached via real API calls? → Use HTTP. If not, use fixture builders — do not add setup endpoints.
6. Does the test only care about one or two facts? → Convention-over-configuration builder; hardcode the rest.
7. Implementing new behavior? → Write the failing test first when practical.
8. Same assertion across many inputs? → `@ParameterizedTest`.
9. Can one loop over many cases check an invariant? → Broad coverage test; **parameterize the cases** when sensible so failures name the row.
10. Is the test readable aloud? → Fluent builders; English-like naming; no boilerplate ceremony.
11. Starting a new test? → `GameScenario` / `BoardScenario` first; ids from `PlacedPlayer`, not literals.
12. Helper takes `x, y` but callers have `Coordinate` (or similar)? → Overload; don't make tests unpack value objects.
13. Adding a required field to a domain model? → Default it in `GameScenario` / `BoardScenario`; don't touch existing tests unless they assert on that field.
14. Match API exists and behavior is "submit action → see result/error"? → API test on shared Spring context; skip a Spring-free duplicate.
15. Building test data? → `GameScenario` / `matchState()` first (Spring-free). Spring only for bean execution or dice-dependent full bootstrap (`MatchBootstrap`).
16. Asserting on HTTP/persisted outcome, not which bean method ran? → Feature test. Fails on refactor with same behavior? → Smell; rewrite.
17. Need a config/port/property in a Spring test? → Inject it (`@LocalServerPort`, `@Value`, `@Bean` param). Not `Environment.getProperty()` unless the key is dynamic.
