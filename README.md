# Goblins & Gridirons

**Goblins & Gridirons** is a fast fantasy football board game, implemented as a rules-faithful game engine, a browser client, built-in computer opponents, and a reinforcement-learning pipeline that trains neural-net opponents and serves them back inside the engine via ONNX.

Built solo over ~3 months (July–October 2026, ~890 commits) during a deliberate skill-building break, using **[Cursor](https://cursor.com)** as the primary development environment, with its AI agent as a pair programmer throughout.

---

## Project summary

Three cooperating modules in one monorepo. The backend is the single source of truth for the rules. The browser client and the AI harness are both clients of its API, and neither one re-implements game logic.

Each module used a **different AI collaboration strategy** on purpose:

| | **Rules engine + API** (`backend/`) | **Browser client** (`frontend/`) | **AI training harness** (`ai/`) |
|---|---|---|---|
| **What it is** | Core engine containing all game rules and native heuristics. Single source of truth. | Interactive web client consuming the backend API. | Trains PPO neural nets against the engine and exports ONNX models back to the backend. |
| **AI Methodology** | **Architect-in-the-Loop**<br>Strict, line-by-line code review and heavy refactoring in a domain/stack I know inside out. | **Pure Vibe Coding**<br>Deliberately zero-scrutiny delegation to see how far raw prompting gets you on a complex UI. | **AI-Tutored Exploration**<br>High-level architectural reviews and aggressive interrogation to learn an unfamiliar stack (RL/PyTorch). |
| **Stack** | Kotlin, Spring Boot 4, Java 21, ONNX Runtime | React 19, TypeScript (strict), Vite, Zustand | Python, Gymnasium, Stable-Baselines3, PyTorch, ONNX |
| **Size** | ~26k lines main, ~21k lines tests | ~14k lines | ~13k lines |

---

## Architecture

### Rules engine and API — `backend/`

*Kotlin · Spring Boot 4 · Java 21 · springdoc OpenAPI · ONNX Runtime · JUnit 5 · JaCoCo*

- **Domain-driven, Spring-free core.** Immutable aggregates (`MatchState`, `Board`, `TurnState`, …) stay decoupled from Spring and persistence. A pure `BoardMutator` processes actions and emits a sealed `DomainEvent` hierarchy; event IDs are assigned at the persistence boundary.
- **Computed vs. persisted state.** Legal moves and paths are recalculated on every read and never stored, so they can't go stale.
- **Seedable dice.** Rolls go through a `DiceRoller` port with a test double, so complex rule sequences (armour, multi-ball bounce, card triggers) are testable without randomness.
- **Three API surfaces.**
  - **Game API:** REST commands, snapshot/hydrate, and **Server-Sent Events** with `Last-Event-ID` for live updates and reconnects. OpenAPI 3 with `oneOf` + `kind` discriminators for typed clients.
  - **Integration API:** same DTOs as the browser, for outside agents.
  - **Trainer API:** packed observation vectors, legal-action matrices, step/submit, and a catalog of training scenarios — built for the RL harness, not the GUI.
- **Opponents behind an `OpponentStrategy` port.** Scripted, deterministic **lookahead search** over the legal-move graph, and an **ONNX** policy from the trained PPO model. A multi-threaded expert-export CLI plays the lookahead bot against itself for training data.
- **Testing.** Whole-stack API tests (Spring boots once per run) plus pure domain unit tests, run with Maven. Tests make up ~21k lines against ~26k lines of main code.

Every rule came from a hand-maintained rulebook digest, and every fidelity question goes back to that digest.

### Browser client — `frontend/`

*React 19 · TypeScript (strict) · Vite · Zustand · Vitest · React Testing Library · Playwright*

- **No rules logic in the client.** It draws state and submits commands. What's legal always comes from the server.
- **Event-sourced view state.** A pure reducer (`applyEvent(view, event) → view`) folds the SSE stream into a board view held in a thin Zustand store. Hydrate from a snapshot, then resume from the last event ID after a reconnect.
- **Generated, drift-checked types.** TypeScript types come from the backend OpenAPI spec via `openapi-typescript`. A check fails when they fall behind.
- **Strict TypeScript.** `strict`, `noUncheckedIndexedAccess`, `exactOptionalPropertyTypes`, and lint rules that ban `any`. Backend sealed types become discriminated unions on `kind`.
- **SVG pitch** with tokens and legal-move highlights. Plain CSS with theme tokens. Vs-computer and hotseat.
- **Testing.** Vitest + React Testing Library (exhaustive reducer tests). **Playwright** boots the real backend (seedable RNG) and frontend and plays full matches with no mocks.

### AI training harness — `ai/`

*Python · Gymnasium · Stable-Baselines3 / sb3-contrib (MaskablePPO) · PyTorch · NumPy · TensorBoard · ONNX · pytest*

- **Pipeline:** expert dump → pack → behavior-cloning seed → two curriculum phases over server-defined scenarios → **self-play** into specialist profiles (`general`, `spread`, `td`, `defense`) with per-profile reward shaping. Ends with ONNX export back into the Kotlin engine.
- **Variable legal actions vs. a fixed action space.** Kotlin packs a float observation vector plus a legal-action matrix. Python slices that into a Gym `Dict`. Actions are factored into MultiDiscrete heads with server-supplied masks, then mapped back to a legal-action ID on submit.
- **Server owns the facts.** Python never hard-codes the engine's lists of scenarios or actions. It reads that metadata from the API.
- **Orchestration.** `train.sh` / `eval.sh` drive phase-gated training. Checkpoints can resume; `start_over.sh` rolls back to any phase. Metrics go to TensorBoard.

---

## How I used Cursor

The focus was **keeping an AI pair programmer on track across a large, long-lived codebase**:

- **~40 scoped `.cursor/rules` files** across root, backend, frontend, and AI. Architecture boundaries, code style, layout preservation, git workflow (auto-commit frontend-only; stage everything else for review), and scope discipline (push back when a "small" change would cut across layers).
- **Guardrails for known AI failure modes:** no copying backend enums into clients, "document the smell" whenever glue has to reconcile two systems, and rules that keep training-data export honest so expert labels can't quietly cheat.
- **Context routing:** a short rulebook digest and card-text references instead of the source PDFs — smaller context, more consistent rule answers.
- **Docs as long-term memory:** layered `ARCHITECTURE.md` files, a single ordered `BACKLOG.md`, and AI design/ownership/roadmap docs for the agent and me equally.

### Lessons learned

*A living list — updated as the project goes.*

- **AI will trade clarity for delivery.** Left alone, it gives up clean organization to get a feature working. What helps most is setting clear patterns and data structures up front and making it implement within them.
- **Code quality still matters, maybe more.** Past a certain size, an unpoliced codebase gets so tangled that the AI itself struggles. This project reached that point. The many one-off game rules kept piling up complexity until I added more structure around how rules get implemented.
- **Style guidance is hit or miss.** Rules files help, but too much guidance overwhelms the model, and its own earlier code that conflicts with the guidance pulls it the wrong way. Post-processing for style has worked fairly well. On a team I'd try looser upfront rules, then a second pass for style.
- **A well-reviewed backend made the frontend cheap.** Because the backend is closely reviewed and the frontend is barred from holding game logic, the frontend has needed almost no code review — and hasn't become complex enough for the AI to start flailing.
- **Develop a sense for hallucinations, especially outside your expertise.** In the PPO work, the AI led me down paths that could never have worked. Keep asking questions until you're convinced; don't settle for "it must know what it's doing."
- **Open question: why the PPO model underperforms.** I still haven't figured out why the trained model doesn't get better results. I suspect a fundamental mistake in how the AI and I designed it — possibly the split action heads and their masking.
- **Open question: how to review this much AI-generated code.** If I were collaborating with someone, safe review would be the hard problem. I suspect the answer leans on code smells as triage: unexpected API contract changes or a pile-up of null checks say "deep review." Clean code with passing tests can get a lighter look.

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
