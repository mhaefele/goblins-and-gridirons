# Model blind spots (vs advisor)

Diagnostic harness: Kotlin [`BlindSpotArena`](../backend/src/test/kotlin/com/mhaefele/gridiron/integration/playthrough/arena/blindspot/BlindSpotArena.kt) under `integration/playthrough/arena/blindspot/` (surefire-excluded with playthrough).

**How to run**

```bash
# Both advisor modes (fat lookahead + heuristic)
cd backend && mvn test -Dtest=BlindSpotArenaTest -Dsurefire.useFile=false

# Fat lookahead advisor/opponent only
mvn test -Dtest='BlindSpotArenaTest#scan general vs fat lookahead for scoring blind spots' -Dsurefire.useFile=false

# Heuristic advisor/opponent only
mvn test -Dtest='BlindSpotArenaTest#scan general vs heuristic for scoring blind spots' -Dsurefire.useFile=false
```

ONNX `general` plays one seat; an **advisor** policy advises on every model decision and plays the opponent. Default advisor is fat lookahead (~1s think). With `useHeuristicAdvisorOpponent`, both advisor and opponent are [`ScriptedOpponentStrategy`](../backend/src/main/kotlin/com/mhaefele/gridiron/game/opponent/scripted/ScriptedOpponentStrategy.kt) (greedy ball-chase / mark — **not** an oracle). On disagreement, both continuations finish the turn (+ opponent turn) under **identical scripted dice**, then we keep findings where the advisor path is better on own-turn points/diff and/or after-opp differential.

Log lines include `tags=…`. **Family tags** separate *we failed to score* from *we failed to stop them*:

| Family | Meaning | Leaf tags |
|--------|---------|-----------|
| `offense-miss` | Advisor fork earned more **us** TD / claims / Clean Sweep | `missed-td`, `missed-claim`, `missed-clean-sweep` |
| `defense-leak` | Model fork allowed more **them** TD / claims | `left-td-open`, `left-claim-open` |

Structural tags (`tableau-replace`, `bonus-choice`) are orthogonal — not “scoring type,” but decision-class at the split.

**Reading heuristic results:** high interesting/disagreement rate ⇒ the model often loses shared-dice turn forks to the scripted bot (decision-quality gaps). Low interesting rate but weak full-game `eval_vs-heuristic` ⇒ style / long-horizon / opponent-induced positions, not single-step “heuristic pick scored better.” Slightly worse full-game winrate vs heuristic than vs lookahead is plausible (expert/LA training style-matches the model; heuristic creates different trees).

Backlog follow-ups: [AI2.17](BACKLOG.md#ai217--blind-spot-remediation-from-arena-diagnostics) (and children).

---

## Sample run — fat lookahead (snapshot)

Captured from `BlindSpotArenaTest` fat-lookahead defaults (`games=30`, `seed=42`, ONNX `general`, advisor think 1s, model seat HOME). Re-run to refresh — live-match dice still vary; fork compares stay dice-fair.

| Metric | Value |
|--------|------:|
| Games | 30 |
| Action disagreements (model ≠ advisor) | 1203 |
| Interesting findings | 469 |
| Interesting / disagreements | 39% |

### Offense vs defense (mutually exclusive family split)

Among the 469 interesting findings:

| Bucket | Count | Share |
|--------|------:|------:|
| **Offense only** (`offense-miss`, no `defense-leak`) | 205 | 44% |
| **Defense only** (`defense-leak`, no `offense-miss`) | 135 | 29% |
| **Both** (missed our score *and* leaked theirs) | 128 | 27% |
| Neither family (structural / catch-all only) | 1 | &lt;1% |

So roughly **7 in 10** interesting splits involve us under-scoring vs the advisor; **over half** involve letting them score more; **about a quarter** are both at once.

### Leaf + family tag rates

Shares are of the 469 interesting findings. Tags stack (213 findings had ≥2 leaf tags). Family rows count any leaf in that family.

| Tag | Count | Share | Role |
|-----|------:|------:|------|
| `offense-miss` | 333 | 71% | family |
| `defense-leak` | 263 | 56% | family |
| `missed-claim` | 295 | 63% | offense — we missed a claim |
| `left-claim-open` | 230 | 49% | defense — they claimed more on our fork |
| `tableau-replace` | 74 | 16% | structural — wrong challenge discard |
| `missed-td` | 59 | 13% | offense — we missed a TD |
| `left-td-open` | 47 | 10% | defense — they TD’d on our fork |
| `bonus-choice` | 15 | 3% | structural — Play vs Decline / adjust |
| `missed-clean-sweep` | 10 | 2% | offense — we missed the +2 sweep |
| `turn-shape` | 1 | &lt;1% | catch-all |

**Read:** Claim economy dominates both sides (missed-claim + left-claim-open). TD misses and TD leaks are smaller but clear and separate. Tableau replace is common (~1/6) and is **not** a scoring-type miss — it changes which cards exist for later claim fights.

### Remediation SWAG (fix type × difficulty)

Difficulty is a **rough IC-week SWAG** for a first useful improvement (not “fully solved”). Prefer the cheapest lever that moves the BlindSpotArena tag rate. Backlog children are ordered the same way in [AI2.17](BACKLOG.md#ai217--blind-spot-remediation-from-arena-diagnostics).

#### Scenario / curriculum first

| Tag | Fix type (primary → secondary) | Diff | Why that swag | Backlog |
|-----|--------------------------------|------|---------------|---------|
| `missed-td` | **Scenario/curriculum** → light TD shaping audit | **S** | TD_RUN-style boards already exist; extend geometry / difficulty scatter + retrain slice. | [AI2.17a](BACKLOG.md#ai217a--missed-touchdown) |
| `tableau-replace` | **Scenario/curriculum** — claim drills open on forced replace (keep claimable; discard bait). Standalone `CHALLENGE_REPLACE` still exposure-only. | **S–M** | Lookahead deepens claim lines after replace; tableau leaf weight kept small vs banked points. | [AI2.17e](BACKLOG.md#ai217e--challenge-tableau-replace) |
| `missed-claim` | **Scenario/curriculum** (claim families) → expert weight / soft-mask | **M** | Many titles × board setups; highest volume. | [AI2.17b](BACKLOG.md#ai217b--missed-challenge-claims) |
| `left-claim-open` | **Scenario/curriculum** (deny their claim) → defense shaping | **M–L** | High volume + fuzzy “don’t give them facts.” Gated: `DENY_STRIP_IT`, `DENY_DROP_THEM`. Exposure-only: `DENY_PICKUP`, `DENY_LOCK_THEM_DOWN`. | [AI2.17d](BACKLOG.md#ai217d--defense-leaks-td--claims) |

#### Reward / shaping first

| Tag | Fix type (primary → secondary) | Diff | Why that swag | Backlog |
|-----|--------------------------------|------|---------------|---------|
| `missed-clean-sweep` | **Reward/shaping** (`claimsThisTurn`) → one multi-claim scenario | **S** | Rare leaf; tiny shaping term + one graded drill. | [AI2.17c](BACKLOG.md#ai217c--missed-clean-sweep) |
| `left-td-open` | **Reward/shaping** (defense / opp-carrier) → `DENY_TD` landed (board drill) | **M** | Careful shaping so offense doesn’t collapse. | [AI2.17d](BACKLOG.md#ai217d--defense-leaks-td--claims) |

#### Mid-check / obs (blocked)

| Tag | Fix type (primary → secondary) | Diff | Why that swag | Backlog |
|-----|--------------------------------|------|---------------|---------|
| `bonus-choice` | **Mid-check/obs** ([AI2.12](BACKLOG.md#ai212--held-dice--mid-check-obs--scenarios)) → bonus scenarios | **M** (blocked) | Wrong layer until held-dice scenarios are green. | AI2.12 |

**Fix-type legend**

| Type | What you actually change |
|------|---------------------------|
| Scenario/curriculum | New/extended Kotlin `TrainingScenario` boards + grades; unlock in curriculum |
| Reward/shaping | `reward.py` profile knobs / terms (margin still dominant) |
| Expert / soft-mask | Import weights, soft-mask bias on legal rows (not hard mask) |
| Obs / legal hints | Stronger claim/TD affordances on DTO/legals ([AI2.8](BACKLOG.md#ai28--evaluate-obs-enrichment-vs-truncated-legals)) — only if board-only is proven blind |
| Mid-check / bonus | Held-check obs + bonus-offer drills ([AI2.12](BACKLOG.md#ai212--held-dice--mid-check-obs--scenarios)) |

---

## Sample run — heuristic (placeholder)

Run `BlindSpotArenaTest#scan general vs heuristic for scoring blind spots` (`games=30`, `seed=42`, `useHeuristicAdvisorOpponent=true`) and paste the family/tag tables here. Same taxonomy as fat lookahead; advisor is the scripted bot, so “interesting” means the heuristic fork scored better under shared dice — not that fat LA would agree.

---

## Failure taxonomy

### A. Offense — we missed scoring (`offense-miss`)

#### A1. Missed touchdown (`missed-td`) — [AI2.17a](BACKLOG.md#ai217a--missed-touchdown)

Advisor fork banks `+4us Touchdown`; model path does not on the same shared-dice window. Often early-game carrier path / endzone finish.

- **Fix type:** Scenario/curriculum (primary), shaping audit (secondary)
- **Difficulty:** **S**

#### A2. Missed challenge claim (`missed-claim`) — [AI2.17b](BACKLOG.md#ai217b--missed-challenge-claims)

Advisor earns named claims the model never banks (many **auto-claims** on the completing action). Highest-volume leaf in the 30-game sample (~63%).

- **Fix type:** Scenario/curriculum by claim title → expert/soft-mask
- **Difficulty:** **M**

#### A3. Missed Clean Sweep (`missed-clean-sweep`) — [AI2.17c](BACKLOG.md#ai217c--missed-clean-sweep)

Advisor banks three claims + `+2us Clean Sweep` in the window; model stops short. Rare but high leverage (~2%).

- **Fix type:** Reward/shaping → one multi-claim scenario
- **Difficulty:** **S**

---

### B. Defense — we failed to stop them scoring (`defense-leak`)

#### B1. Left touchdown open (`left-td-open`) — [AI2.17d](BACKLOG.md#ai217d--defense-leaks-td--claims)

**They** score `+4them Touchdown` on the model fork more than on the advisor fork (~10%). Soft marks / wrong tempo after our action.

- **Fix type:** Reward/shaping (defense profile) → deny-TD scenarios
- **Difficulty:** **M**
- **Landed drill:** `DENY_TD` — Block opponent carrier near their scoring end ([`DenyTdTrainingScenario`](../backend/src/main/kotlin/com/mhaefele/gridiron/api/ai/training/DenyTdTrainingScenario.kt))

#### B2. Left claim open (`left-claim-open`) — [AI2.17d](BACKLOG.md#ai217d--defense-leaks-td--claims)

**They** bank more / different challenge claims on the model fork (~49%). Often paired with offense misses (same soft turn shape).

- **Fix type:** Scenario/curriculum (deny their claim facts) → defense shaping
- **Difficulty:** **M–L**
- **Landed drills (gated):** `DENY_STRIP_IT`, `DENY_DROP_THEM` — soft-mask disrupt while the focus claim is active; included in lookahead-solve + curriculum gates.
- **Exposure-only:** `DENY_PICKUP` (LA usually Blocks but not d0–d5 reliable), `DENY_LOCK_THEM_DOWN` (LA Marks to claim the focus). Still run under `TrainingScenarioLookaheadTest` without a PASS assert.

---

### C. Structural (not “missed a TD” — different decision class)

#### C1. Tableau replace (`tableau-replace`) — [AI2.17e](BACKLOG.md#ai217e--challenge-tableau-replace)

Split on `Replace("…")` — which active challenge to discard. ~16% of interesting findings. Orthogonal to TD/claim leaves: wrong keep-set changes *future* claim eligibility. Treat separately from `missed-td` / `missed-claim`.

- **Fix type:** Scenario/curriculum (forced Replace) → expert keep-set prior
- **Difficulty:** **S–M**
- **Landed:** Replace is searchable (not fixed slot 0). Claim drills open on forced replace with a keep-set soft-mask (focus / Clean Sweep trio vs bait). Standalone `CHALLENGE_REPLACE` remains exposure-only. Lookahead deepens claim lines after replace; tableau leaf weight is small vs banked points.

#### C2. Bonus / interrupt choice (`bonus-choice`)

Play* vs Decline / Adjust*. Small share (~3%); mostly mid-check / bonus heads — prefer finishing [AI2.12](BACKLOG.md#ai212--held-dice--mid-check-obs--scenarios).

- **Fix type:** Mid-check/obs + bonus scenarios (after AI2.12)
- **Difficulty:** **M** (blocked)

---

### D. Catch-alls (`turn-shape`, `after-opp-only`)

Advisor better without a clear offense/defense leaf. Almost empty once families are tagged; inspect before promoting.

- **Fix type:** n/a until promoted
- **Difficulty:** —

---

## Reading a finding

```text
g=1 turn=1 split=5 tags=missed-td,offense-miss model=RUN_h5_… advisor=RUN_h4_…
  active: "Keep Running!"(+2), "Fan Out!"(+1), "Play to the Stands!"(+1)
  ownPts m=0 a=4 ownDiff m=0 a=4 oppDiff m=0 a=4
  model scored through opp: (none)
  advisor scored through opp: +4us Touchdown
```

- `active:` = challenge tableau at the split (slot order; `—` = empty) — useful for “did we Foul / Block because Send Them Off was up?”
- Step paren notes include `pickup h4` / `them pickup a2` when a loose ball is claimed on that apply (throw catches omitted — prior carrier).
- `m=` / `a=` = model fork vs advisor fork (not time).
- Prefer reading **family** first (`offense-miss` vs `defense-leak`), then the leaf.
- Shared dice ⇒ gaps are from decisions (different trees still consume the roll stream at different events).

---

## Harness notes

| Piece | Role |
|-------|------|
| `BlindSpotArena` | Live match loop + fork compare; `Config.useHeuristicAdvisorOpponent` swaps advisor/opponent to heuristic |
| `TurnForkPlayer` | In-process apply under `DiceRollerContext` |
| `SharedDiceFactory` | Identical D6/D8/block queues per split |
| `LookaheadSearch.apply` | Only production hook (single-action dry-run; unused in heuristic mode) |

Do not treat lookahead’s search-time optimistic dice as live outcomes — forks use the shared scripted stream above.
