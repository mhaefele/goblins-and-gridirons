# API design

Agent gate (short): [`.cursor/rules/api-design.mdc`](.cursor/rules/api-design.mdc).
Endpoints / transport summary: [ARCHITECTURE.md — API design](ARCHITECTURE.md#api-design).

## Not CRUD-by-default

Do not blindly map domain entities to generic CRUD resources. Design endpoints around **commands** (what the client wants to do) and **views** (what the client needs to read).

Game actions are verbs, not PATCH updates to a `Match` document.

## Reuse types when shapes align; split when they do not

**Prefer the same class for API payloads and repository/persisted types** when the field set and semantics are identical — no parallel `*View` or entity type, no mapper ceremony.

**Do not reuse if it requires mangling the shared class.** Forbidden compromises:

- API-only or DB-ignored fields bolted onto a persisted type “for convenience”
- Serialization annotations polluting domain model **when they paper over a shape mismatch** between HTTP input and persistence
- Nullable slots that exist only because one caller (HTTP in vs GET out) needs a subset
- `@JsonIgnore` / `@Transient` workarounds to paper over shape mismatch

When shapes diverge, split **deliberately**:

1. **Endpoint-specific types** — narrow request/response DTOs for that operation (`CreateMatchRequest`, `RunActionRequest`)
2. **Full DTO layer** — if many endpoints need different projections, add `api/dto/` and mappers rather than stretching domain or persistence types

```kotlin
// Good — shared value type everywhere
data class Coordinate(val x: Int, val y: Int)

// Good — GET returns domain aggregate as-is when JSON shape matches
@GetMapping("/{id}")
fun getMatch(@PathVariable id: String): MatchState = ...

// Good — create input differs from stored aggregate
data class CreateMatchRequest(val homeTeamId: String, val awayTeamId: String)

// Bad — mangled aggregate serving two masters
data class MatchState(
    val id: MatchId,
    val board: Board,
    val config: MatchConfig,
    val progress: MatchProgress,
    val currentTurnState: TurnState,
    @JsonIgnore val legalActionsHint: List<String>?,  // API-only, not persisted
)
```

## Separate input commands from output views

When input and output **do** differ, use dedicated types — do not reuse the response type for input with nullable fields stripped away.

- **Request/command DTOs** — only fields the server will use for that operation
- **Response types** — domain aggregate when it fits; otherwise a view DTO with only what the client needs

```kotlin
// Good — input differs from stored state
data class CreateMatchRequest(val homeTeamId: String, val awayTeamId: String)

// Good — output is the persisted aggregate (no separate MatchView needed)
fun createMatch(...): MatchState

// Good — output needs a different projection
data class LegalActionsView(val actions: List<LegalAction>)

// Bad — server-generated id on create input
data class CreateMatchRequest(val id: String?, ...)
```

## Input payloads: only used fields

Every field on a request body must be read and used (or explicitly optional with documented default). Omit:

- Server-generated ids on create
- Read-only computed fields (scores, phase, legal actions)
- Fields that belong on a different command

## Verb URLs for commands

Prefer **verbs in the path** for POST/PUT operations that perform an action, unless the endpoint is a pure resource create or full replace edit.

```text
Good:
  POST /api/matches/{matchId}/players/{playerId}/run
  POST /api/matches/{id}/throw
  POST /api/matches/{id}/block

Avoid:
  POST /api/matches/{id}/actions   # single endpoint, type discriminator in body
```

Pure create/read is fine without a verb:

```text
  POST /api/matches          → creates a match (resource birth)
  GET  /api/matches/{id}     → read current state
```

## Split endpoints; no conditional nulls

**Do not** combine similar-but-different commands into one DTO with “if type is X, field A is required, else null”. That is a design smell.

```kotlin
// Bad — conditional nulls, type discriminator
data class SubmitActionRequest(
    val type: ActionType,
    val playerId: String,
    val path: List<Coord>?,           // only for RUN/MARK
    val destination: CoordView?,     // only for THROW/BLOCK
)

// Good — one request type per endpoint
data class RunActionRequest(val playerId: String, val path: List<CoordView>)
data class ThrowActionRequest(val playerId: String, val targetPlayerId: String)
data class BlockActionRequest(val playerId: String, val destination: CoordinateView)
```

Split URLs + split request types. Controllers may delegate to a shared domain `ActionCommand` internally.

## Mapping to domain

API layer: command DTO → domain `ActionCommand` → service → `MatchState` (or view DTO when projection differs).

Keep conditional validation in the **domain** (single command type), not in a polymorphic HTTP body with nullable fields.

## Strong types in request DTOs

**Prefer domain value types over raw `String` in API request bodies and path variables** when the wire format is still a plain string (`"h0"`, `"dwarves"`). JSON shape stays the same; Kotlin types catch mix-ups at compile time.

- Request DTO fields: `PlayerId`, `TeamCatalogId`, `PitchDefinitionId`, etc. — deserialize via shared Jackson `DomainId` module (`spring/JacksonConfiguration.kt`).
- Path variables: register Spring `Converter<String, MatchId>` (etc.) so controllers take `MatchId`, not `String`.
- Map to domain commands in thin mapping functions — no `PlayerId(string)` ceremony at the controller if the DTO already carries `PlayerId`.

Do **not** weaken outgoing `MatchState` JSON just to avoid mixins — response types may still need Jackson adapters for sealed hierarchies and map keys. Request typing and response projection are separate concerns.

## Jackson wire shape — co-locate with the type

When a type is serialized to JSON as-is (`MatchState`, `LegalAction`, sealed stats, etc.):

1. **Prefer annotations on the type** — `@JsonProperty`, `@JsonTypeInfo`, `@JsonSubTypes`, `@JsonAutoDetect` live on the class or field being shaped, not in a separate mixin file.
2. **Reserve `spring/JacksonConfiguration`** for cross-cutting machinery annotations cannot express: shared `DomainId` ser/deser, map-key serializers, Spring module registration.
3. **Simple renames** (e.g. Kotlin `isFoo` → JSON `"isFoo"`) are always `@JsonProperty` on that field — never a mixin.

External `@JacksonMixin` classes are a last resort when the type cannot take a Jackson dependency (should not apply to our domain/legal types on the wire).

## Bean Validation on Kotlin DTOs

Do **not** add `@NotNull` on non-null Kotlin properties — the type already requires a value; Jackson fails on missing/null before validation runs.

Use Jakarta validation only for constraints **beyond** nullability: `@Min` / `@Max` on numeric ranges, `@NotEmpty` on collections, `@NotBlank` on nullable strings that must be non-empty when present.

**Do not** annotate nested domain types (`Coordinate`, `PlayerId`, etc.) with `@Valid` on every DTO field — cascade requires `@Valid` on each parent property and does not apply when placed on the child class. Game-rule checks (in-bounds, legal path, etc.) belong in domain validation, which already surfaces as 400 via [ApiExceptionHandler](src/main/kotlin/com/mhaefele/gridiron/api/ApiExceptionHandler.kt).

## Runtime validation: `require` vs `check`

At the HTTP boundary, prefer **`require`** for anything the **client could have sent wrong** (illegal command, bad id, wrong phase). Use **`check`** only after validation for **internal invariants** that should never fail on a well-formed request. See [code-style — require vs check](../.cursor/rules/code-style.mdc). Both exception types map to 400 today; the type still signals whether to blame the request or debug the engine.

Enable DTO validation with `@Valid` on controller `@RequestBody` parameters (Spring default — opt-in per parameter).
