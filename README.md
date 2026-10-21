# Goblins & Gridirons

**Goblins & Gridirons** is a fast fantasy football board game, implemented as a rules-faithful game engine, a browser client, built-in computer opponents, and a reinforcement-learning pipeline that trains neural-net opponents and serves them back inside the engine.

Built solo over ~3 months (July–October 2026, ~890 commits) during a deliberate skill-building break, using **[Cursor](https://cursor.com)** as the primary development environment, with its AI agent as a pair programmer throughout.

---

## Project summary

Three cooperating modules in one monorepo. The backend is the single source of truth for the rules. The browser client and the AI harness are both clients of its API, and neither one re-implements game logic.

| | **Rules engine + API** (`backend/`) | **Browser client** (`frontend/`) | **AI training harness** (`ai/`) |
|---|---|---|---|
| **What it is** | Core engine containing all game rules and native heuristics. Single source of truth. | Interactive web client consuming the backend API. | Trains PPO neural nets against the engine and exports ONNX models back to the backend. |
| **AI Methodology** | **Architect-in-the-Loop**<br>Strict, line-by-line code review and heavy refactoring in a domain/stack I know inside out. | **Pure Vibe Coding**<br>Deliberately zero-scrutiny delegation to see how far raw prompting gets you on a complex UI. | **AI-Tutored Exploration**<br>High-level architectural reviews and aggressive interrogation to learn an unfamiliar stack (RL/PyTorch). |
| **Stack** | Kotlin, Spring Boot 4, Java 21 | React 19, TypeScript (strict), Vite, Zustand | Python, Gymnasium, Stable-Baselines3, PyTorch, ONNX |
| **Size** | ~26k lines main, ~21k lines tests | ~14k lines | ~13k lines |

### Rules engine and API — `backend/`

*Kotlin · Spring Boot 4 · Java 21 · springdoc OpenAPI · ONNX Runtime*

**Rules coverage.** The engine models the whole game:
- movement and pathfinding, marking, and blocking with multi-die pools
- armour and injury
- throws: range, obstruction, bounce, and multi-ball
- touchdowns, sudden death, and points victory
- the full challenge / bonus card system and player abilities gated by cards
- fouls and Throw Team-mate
- all 23 team rosters

Every rule came from a hand-maintained rulebook digest, and every fidelity question goes back to that digest.

**Domain-driven design with strict layering.**
- `model/` and `shared/` are immutable, Spring-free aggregates (`MatchState` = `Board` + `MatchConfig` + `MatchProgress` + `TurnState` + `CardState`). `game/` holds the playable loop: legal-move generation, action resolvers, the turn state machine, cards, and opponents. `api/` is the HTTP and persistence boundary, and `spring/` holds the framework adapters.
- Persisted state and computed results are kept apart: legal moves and paths are recalculated on every read and never stored, so they can't go stale.
- A pure `BoardMutator` emits a sealed `DomainEvent` hierarchy. Event IDs are assigned at the persistence boundary, so domain code never needs to know about storage.
- Dice go through a `DiceRoller` port with a seedable test double, which makes complex rule sequences testable without randomness.

**Three API surfaces.**
- **Game API:** REST commands, a snapshot/hydrate endpoint, and **Server-Sent Events** with `Last-Event-ID` for live updates and reconnects. Published as an **OpenAPI 3** spec. Polymorphic types (`DomainEvent`, `LegalAction`) use `oneOf` + `kind` discriminators so typed clients get proper tagged unions.
- **Integration API** (`/api/integration/...`): lets outside agents play the game using the same DTOs as the browser.
- **Trainer API** (`/api/ai/...`): packed observation vectors, legal-action matrices, step/submit, and a catalog of training scenarios, built for the RL harness rather than for the GUI.

**Computer opponents behind an `OpponentStrategy` port.**
- A scripted opponent.
- A **lookahead search** opponent: deterministic action search over the legal-move graph, dry-run applies, a scorer, and candidate ranking, including chasing active challenge cards.
- An **ONNX opponent**: the trained PPO policy from `ai/`, exported to ONNX and run inside the JVM with ONNX Runtime.
- A multi-threaded **expert-export CLI** that plays the lookahead bot against itself to generate the AI's training data.

**Testing.** Whole-stack API tests (Spring boots once per run) plus pure domain unit tests, run with Maven. JUnit 5 runs them in parallel, and JaCoCo reports coverage. Tests make up ~21k lines against ~26k lines of main code.

### Browser client — `frontend/`

*React 19 · TypeScript (strict) · Vite · Zustand · Vitest · React Testing Library · Playwright*

- **No rules logic in the client.** It draws state and submits commands. What's legal always comes from the server's legal-actions endpoint.
- **Event-sourced view state:** a **pure reducer** (`applyEvent(view, event) → view`) folds the server's event stream into a board view, held in a thin Zustand store. The client hydrates from a snapshot, then subscribes to SSE and resumes from the last event ID after a reconnect.
- **Generated, drift-checked types:** TypeScript types are generated from the backend's OpenAPI spec with `openapi-typescript`. A check script fails when they fall behind the backend, so DTOs are never copied by hand.
- **Strict TypeScript:** `strict`, `noUncheckedIndexedAccess`, `exactOptionalPropertyTypes`, and lint rules that ban `any`. Backend sealed types become discriminated unions on `kind`.
- **SVG rendering** for the pitch, tokens, and legal-move highlights. It scales responsively through `viewBox`, and DOM hit-testing keeps it testable. Styling is plain CSS with custom-property theme tokens. Supports vs-computer and hotseat play.
- **Testing:** Vitest + React Testing Library, with exhaustive reducer tests. **Playwright** system tests start the real backend (with a seedable-RNG test profile) and the real frontend, then play full matches end-to-end with no mocks.

### AI training harness — `ai/`

*Python · Gymnasium · Stable-Baselines3 / sb3-contrib (MaskablePPO) · PyTorch · NumPy · TensorBoard · ONNX · pytest*

- **Pipeline:** expert dump → pack → behavior-cloning seed → two curriculum phases over server-defined training scenarios → **self-play** fine-tuning into specialist profiles (`general`, `spread`, `td`, `defense`) with per-profile reward shaping. Ends with ONNX export back into the Kotlin engine.
- **Expert data:** the lookahead bot plays every directed pairing of the 23 teams (23×22) plus the training scenarios. Its traces are written as `jsonl.gz`, then packed into `npz` with ragged legal-action sets.
- **Variable legal actions vs. a fixed action space:** Kotlin packs a fixed float observation vector plus a legal-action matrix, versioned by schema. Python slices that into a Gym `Dict`. Actions are factored into MultiDiscrete heads with server-supplied masks, then mapped back to a legal-action ID on submit.
- **Orchestration:** `train.sh` / `eval.sh` drive phase-gated training. Phases fill in whatever is missing, checkpoints can resume, `start_over.sh` rolls back to any phase, and metrics are logged per profile to TensorBoard.
- **Server owns the facts:** Python never hard-codes the engine's lists of scenarios or actions. It reads that metadata from the API.
- **Testing:** pytest coverage over the environment, the pipeline state, and the phase logic.

### How I used Cursor

I built the whole project in Cursor, with the agent as a constant collaborator. The focus was **keeping an AI pair programmer on track across a large, long-lived codebase**:

- **~40 scoped `.cursor/rules` files** spread across the root, backend, frontend, and AI modules. They encode architecture boundaries, code style, layout preservation, git workflow (auto-commit frontend-only changes; stage everything else for review), and scope discipline (push back when a "small" change would cut across layers).
- **Guardrail rules for known AI failure modes:** no copying backend enums into clients, "document the smell" whenever glue code has to reconcile two systems, and rules that keep training-data export honest so the expert labels can't quietly cheat.
- **Context routing:** the agent is pointed at a short rulebook digest and card-text references instead of the source PDFs, which keeps context small and rule answers consistent.
- **Docs as the agent's long-term memory:** layered `ARCHITECTURE.md` files, a single ordered `BACKLOG.md`, and AI-module design/ownership/roadmap docs serve the agent and me equally.
- Used it to ramp up on stacks outside my day-to-day: modern Kotlin/Spring Boot, React 19, and Python reinforcement learning (Stable-Baselines3, PPO, behavior cloning, ONNX).

### Lessons learned working with AI

*A living list — updated as the project goes.*

- **AI will trade clarity for delivery.** Left alone, it will give up clean organization to get a feature working. What helps most is setting up clear patterns and data structures up front and making it implement within them.
- **Code quality still matters, maybe more.** Past a certain size, an unpoliced codebase gets so tangled that the AI itself struggles to make progress. This project reached that point, which was good practice for real codebases. The many one-off game rules in particular kept piling up complexity until I added more structure around how rules get implemented.
- **Style guidance is hit or miss.** Rules files and style docs help, but too much guidance overwhelms the model, and it starts making bad choices. Its own earlier code that conflicts with the guidance also pulls it the wrong way. AI *should* be a great tool for keeping style and patterns consistent across a repo, and I haven't solved this yet. Post-processing for style has worked fairly well. On a team I'd probably try looser upfront rules so the AI can focus on solving the problem, then a second AI pass that applies our style preferences.
- **A well-reviewed backend made the frontend cheap.** Because the backend is closely reviewed and the frontend is barred from holding game logic, the frontend has needed almost no code review. It also hasn't become complex enough for the AI to start flailing.
- **Develop a sense for hallucinations, especially outside your expertise.** In the PPO work, the AI led me down paths that could never have worked. The fix is to keep asking questions until I'm convinced and actually understand the reasoning, not to settle for "it must know what it's doing."
- **Open question: why the PPO model underperforms.** I still haven't figured out why the trained model doesn't get better results. I suspect a fundamental mistake in how the AI and I designed it, possibly in the split action heads and their masking.
- **Open question: how to review this much AI-generated code.** I've produced a huge amount of code. If I were collaborating with someone, safe review would be the hard problem. I suspect the answer leans on code smells as triage signals: things like unexpected API contract changes or a pile-up of null checks say "this needs a deep review." Clean code with passing tests can get a lighter look.

---

## Repository layout

```text
goblins-and-gridirons/
├── backend/      Kotlin/Spring rules engine, REST + SSE API, computer opponents, expert exporter
├── frontend/     React/TS browser client, Playwright e2e
├── ai/           Python RL harness (SB3 MaskablePPO), training/eval scripts
├── reference/    Rulebook digest + card text (source of truth for rules questions)
├── docs/         Design notes
├── dev.sh        Start backend + frontend together
└── .cursor/      Shared Cursor agent rules
```

## Quick start

Prerequisites: JDK 21, Maven, Node 20.19+ (developed on Node 26), Python 3 (developed on 3.14; only needed for AI training).

```bash
./dev.sh            # backend on :18080, frontend on :15173 (opens the browser)
./dev.sh --no-ui    # backend only
```

Backend tests:

```bash
cd backend && mvn test
```

Frontend:

```bash
cd frontend
npm install
npm test            # Vitest
npm run e2e         # Playwright (boots backend + frontend)
npm run gen:api     # regenerate TS types from the backend OpenAPI spec
```

AI training (backend must be running):

```bash
cd ai
python3 -m venv .venv && .venv/bin/pip install -r requirements.txt
./train.sh          # expert dump → pack → seed → curriculum → self-play profiles
./eval.sh           # evaluate against the built-in opponents
```

API docs (with the backend running): Swagger UI at `http://localhost:18080/swagger-ui.html`.

## Documentation

| Doc | Covers |
|-----|--------|
| [ARCHITECTURE.md](ARCHITECTURE.md) | System overview, module boundaries, client ↔ engine contract |
| [backend/ARCHITECTURE.md](backend/ARCHITECTURE.md) | Domain model, layering, turn state machine, API, testing |
| [frontend/ARCHITECTURE.md](frontend/ARCHITECTURE.md) | Client stack, event-fold state, rendering, testing |
| [ai/README.md](ai/README.md) | Training pipeline, scripts, checkpoints |
| [ai/DESIGN.md](ai/DESIGN.md) | Locked RL contract decisions (obs, actions, reward) |
| [BACKLOG.md](BACKLOG.md) | Ordered implementation backlog |
| [reference/rulebook-digest.md](reference/rulebook-digest.md) | Game rules digest |

## Acknowledgements

Game mechanics are inspired by *Blitz Bowl* by Games Workshop. All names, rules text, and artwork in this project are original.
This is an unofficial, non-commercial fan project and is not affiliated with or endorsed by Games Workshop.
*Blitz Bowl* is a trademark of Games Workshop Limited.
