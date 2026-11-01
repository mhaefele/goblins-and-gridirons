# Action sampling — heads, factor maps, inventory

Source of truth for **how the policy encodes and samples actions**. Schema 16+ scores complete legal rows ([Policy action](#policy-action-schema-16)); older factor-head inventory below is historical context for packing widths. Backlog stub: [AI2.11](BACKLOG.md#ai211--action-sampling--factor-space-inventory). **Owners:** [`OWNERSHIP.md`](OWNERSHIP.md).

Mapping truth: [`AiLegalChoiceMapping.kt`](../backend/src/main/kotlin/com/mhaefele/gridiron/api/ai/mapping/AiLegalChoiceMapping.kt) (+ [`AiLegalKind`](../backend/src/main/kotlin/com/mhaefele/gridiron/api/ai/dto/AiLegalKind.kt)).

---

## Inventory

**Next steps to improve action representation & sampling** — what each packed factor means, and how we pick among legal rows. Goal: make legal choices easier to learn and score correctly, not richer board obs ([AI2.8](BACKLOG.md#ai28--evaluate-obs-enrichment-vs-truncated-legals) / [AI2.10](BACKLOG.md#ai210--legal-move-inputs--obs-dict-shape)) or reward ([AI2.7](BACKLOG.md#ai27--optional-reward-shaping-day-2)).

Two levers:

1. **Representation** — shrink or re-basis packed row factors so indices mean the right thing (pitch-only targets, bonus-play identity **once**, optional path merges / roster actor).
2. **Sampling procedure** — **schema 16:** join-score each legal row given the board latent (`Discrete` + MaskablePPO). Soft preference bias toward `expectedScenarioMove` rows (schema 12, row-level under 16). Factor MultiDiscrete remask / head conditioning was removed.

Schema bumps + checkpoint breaks expected for representation items. Measure before spending on sampling-procedure upgrades.

### Train mix vs action heads

Action heads are shared across drills. Wrong mix → wrong kind priors (e.g. throw/claim
bleeding into TD_RUN). **Phase order, gates, and checkpoints** live in
[`TRAINING_ROADMAP.md`](TRAINING_ROADMAP.md) (`scenario_curriculum.py` /
`training_phases.py`) — not duplicated here.

Soft-mask (below) applies whenever the server flags preferred rows — board *and* claim
openings — without hard-removing distractors (hard mask already failed transfer).

### Soft-mask preference

**Landed (schema 12; row-level under schema 16).** Gym key `preferred_legal` /
`soft_mask_legal` parallels `legal_moves` rows (`1` = `expectedScenarioMove`). The
join-score policy adds `SOFT_MASK_LOGIT_BIAS` (**2.5**) to preferred **row** logits
before the categorical. Illegal / pad rows stay hard-masked at 0; non-preferred
legals stay available (soft tilt, not a hard filter).

Same bias on rollout sample and `evaluate_actions` replay (flags ride in obs) so
importance ratios stay consistent.

### Legality mask vs soft preference (do not merge)

Three separate ideas — conflating them makes throw drills look “broken” when they are
working as designed.

| Mechanism | What it does | Example |
|-----------|----------------|---------|
| **Legality mask (hard)** | Only indices `0 .. legal_move_count-1` are available | A square with no legal run is not in the menu |
| **Soft preference** | Preferred rows get a logit bias; **does not remove** other legal rows | TD_RUN: one flagged run into the EZ among distractors |
| **Ambiguous opening (no preference)** | Server leaves all opening flags false; episode graded by outcome (TD, pickup, …) | Throw drills: many legal throw targets stay in the menu — “multi-throw” exposure is intentional |

**Throws:** keep the full legal throw set (legality mask only). Do **not** hard-filter
to one target. Optional `expectedScenarioMove` on throws would be a separate, explicit
Kotlin choice — not the same knob as soft-mask on unambiguous board/claim openings.

**Expert clone vs runtime preference:** behavioral clone copies lookahead **choices**;
soft-mask is a **training-time tilt** toward server-flagged rows. See
[`scenario_curriculum.py`](gridiron_ai/scenario_curriculum.py) gate/demotion policy separately.

### Strategic directions

For *this* problem (smarter action choices), almost everything collapses to two levers:

| Lever | What it changes | Inventory home |
|-------|-----------------|----------------|
| **1. Encode moves better** | What each head/index **means**, how wide it is, deadweight removed, kinds merged | Yes-do + most Maybes (path merge, roster actor, relative target, joint heads as re-indexing) |
| **2. Sample among legal rows** | How we **pick** among packed rows — schema 16 join-score + soft preferred bias | Soft-mask (landed); further row encoders / attention are optional |

**Default path:** Schema 10 packing (roster actor) → soft-mask (schema 12) → **schema 16 legal-row join scores + separate actor/kind pack** (landed; replaces factor remask / joint index). Measure uniqueness before more representation shrinks.

**Landed (was “third direction”):** score the legal set directly — join board latent to each encoded row (`LegalRowActorCriticPolicy` / ONNX row scores). Remaining [AI2.10](BACKLOG.md#ai210--legal-move-inputs--obs-dict-shape) work is whether the value net / features should see richer legal-menu summaries beyond the action head.

**Not substitutes for 1–2:** held mid-check rolls (**prime [AI2.12](BACKLOG.md#ai212--held-dice--mid-check-obs--scenarios)**), other board obs ([AI2.8](BACKLOG.md#ai28--evaluate-obs-enrichment-vs-truncated-legals)), reward shaping ([AI2.7](BACKLOG.md#ai27--optional-reward-shaping-day-2)), more PPO steps alone. Train-*mix* / phase gates → [`TRAINING_ROADMAP.md`](TRAINING_ROADMAP.md), not action-head geometry.

### Yes — do

Committed representation work. Engine wire kinds stay distinct for submit; policy kinds / factors are the learning contract.

- [x] **Pitch-only `boardTarget` head.** Landed (schema 8): target width `PITCH_SQUARE_COUNT+1` (**166**); actor was pitch+bench (**186**) until schema 10.
- [x] **Bonus plays: encode identity once; drop `HandSlot`.** Landed (schema 8, option **A**): per-play `AiLegalKind`s; empty factors on play-bonus rows; hand MultiDiscrete axis removed.
- [x] **Factors = free choices only; mark sugar; match on free factors.** Landed (schema 9): `AiLegalChoiceDto.factors` = free only; `sugarFactors` = pending-fixed (not MultiDiscrete-matched). `resolve_action` matches free factors → submit full `legalActionId` (sugar stays in the id).
- [x] **`declineOffer` coarse kind.** Landed (schema 9); **undone schema 16** — distinct `skip` / `declineBonus` / `continueBlock` / `keepOffer` again (join-score rows don’t need the MultiDiscrete merge).
- [x] **Exclude `cancelThrowTeammate` from the policy.** Landed (schema 9); **restored schema 16** — cancel is a legal-row kind again.
- [x] **Rerolls: one do-it kind + keep, no boolean.** Landed (schema 9): `Choose*Reroll(true)` → `reroll`; keep → `keepOffer` (was `declineOffer`). `AiFactor.Reroll` and the MultiDiscrete reroll head removed.
- [x] **Combine Do-Over adjusts.** Landed (schema 9); **undone schema 16** — `adjustThrowByDoOver` / `adjustBlockByDoOver` / `adjustArmourByDoOver` are distinct kinds again.
- [x] **Actor by roster playerIndex + joint `(actor, kind)` head.** Landed (schema 10); **superseded schema 16** — separate actor + kind pack columns; gym action is Discrete row index.
### Measure first (cheap stats → decide)

Evidence for whether representation merges or sampling-procedure upgrades are worth it. Hypotheses: [Uniqueness hypotheses](#uniqueness-hypotheses-to-measure). Bucket **player `[Actor, BoardTarget]` rows** separately from card/challenge/follow-up shapes.

- [ ] **`(actor, boardTarget) → #kinds`.** Expect **~1** for path/throw/block-style rows (run vs sidestep for the same end usually don’t both appear). Confirm rarer collisions.
- [ ] **`(actor, kind) → #targets`.** Expect **many** — main branching after actor+kind; shared target-logit quality matters here.
- [ ] **`(actor, boardTarget)` or target → #kinds`.** Expect **few** (e.g. run vs sidestep by marked). Cross-check against first bullet.
- [ ] **Exclude / separately bucket** rows with no target or no player actor (`play*`, claim/replace, decline, reroll-only shapes) so they don’t distort the table.

### Evaluate / Maybe (preference / architecture)

Optional representation shrinks and sampling-procedure upgrades — pick after Measure first (and after Yes-do where noted).

- [ ] **Bonus-play identity: kind vs factor (XOR).** Schema 8 chose **A** (per-play kinds, no hand/BonusPlay factor). Revisit only if we want **B** (one `playBonus` + `BonusPlay` head) instead — still XOR, not both.
- [ ] **Combine similar path / “move” kinds (`run` / `sidestep` / `mark`).** Same `[Actor, BoardTarget]` shape; engine already partitions by board (marked → sidestep, open → run, end adjacent enemy → mark). One `move` (or `path`) kind would shrink the kind head and treat verb as masked eligibility rather than a learned class. Tradeoff: less direct “I want to mark” prior on the kind head — policy must recover intent from obs + destination. Extend the idea only carefully to other near-synonyms (don’t merge throw/block).
- [ ] **Throw vs `quickReleaseThrow`.** Free factors differ: **throw** = actor + target; **quickReleaseThrow** = target only (actor sugar). Don’t merge. Quick Release is also an interrupt window — obs `phase` is only coarse `TurnPhase`, not `BlockStage.HANDLE_QUICK_RELEASE`.
- [x] **Actor by player id / roster slot instead of board index.** Landed (schema 10): `1..10` + `0` unused; cell `playerIndex` binds id↔square.
- [ ] **Sequential conditioning vs masking.** Today: remask + soft preferred bias. Upgrade path: feed prior picks so later heads score `P(factor \| …)` (full autoregressive, or cheaper kind-sliced target logits). Cost: APS, `evaluate_actions` teacher-force, ONNX, checkpoint break. Prefer after uniqueness stats.
- [x] **Joint `(actor, kind)` head.** Landed (schema 10): fixed product `11×34=374` (roster actor × kinds), not board×kind. Sample/mask that axis first, then target / challenge / face.
- [x] **Soft-mask preferred rows.** Landed (schema 12): `preferred_legal` + logit bias on values from `expectedScenarioMove` rows — see [Soft-mask preference](#soft-mask-preference). Not a hard legal filter.
- [ ] **Kind-conditioned target logits** (middle ground). Emit per-kind (or kind-embedded) target scores without a full sequential trunk. Higher relevance if `(actor, kind)` often has many targets (see Measure first).
- [ ] **Relative `boardTarget` (Δx / Δy from actor) instead of absolute pitch index.** Shrinks the *path* fan-out if the window tracks Move (e.g. ±4 → 9×9=81, or ±7 → 15×15=225) and can transfer “step forward” across the board. Costs: (1) model clarity — end-zone / sideline geography is no longer in the action index; (2) **throws and Hail Mary** can already aim near board-wide, so a shared relative head needs a window ≈ full pitch (**no shrink** vs pitch-166) or kind-specific target encodings; (3) reserves entry / place-loose-ball / absolute tactics get awkward. Likely a win only for path-like kinds, not one global relative target head.

### Out / don’t confuse with

- Naive flat catalog of all factor combos (~29M schema-9 pad product) — not a goal. See [Move space](#move-space).
- Board-obs enrichment or feeding the legal menu into the net — [AI2.8](BACKLOG.md#ai28--evaluate-obs-enrichment-vs-truncated-legals) / [AI2.10](BACKLOG.md#ai210--legal-move-inputs--obs-dict-shape) (the “third direction” above).
- Reward shaping — [AI2.7](BACKLOG.md#ai27--optional-reward-shaping-day-2).
- Per-step max legal rows — mask/APS metric; not structural distinct-move size.

---

## Move space

Three different notions — don’t mix them.

1. **Naive head product** — allocate all MultiDiscrete widths as if every action used every axis (**~29M** schema 9; was ~104M in schema 8). Softmax fatness, not game size.
2. **Structural distinct actions** — enumerate by kind using **only that kind’s free factors** (~0.5M swag with pad actor×pitch). Kind-only declines / bonus / challenge are not ×165.
3. **Per-step legal set size** — how many rows are legal *right now* (mask / APS). Separate question.

### 1. Naive head product (allocation size)

Fixed MultiDiscrete widths multiplied together. Almost every slot is masked every step; this is **not** “how many moves the game has.” Useful only as “how fat is the softmax we allocate.”

| Config | Widths | Product |
|--------|--------|--------:|
| **Current (schema 10)** | 374 × 166 × 4 × 7 | **~1.7M** |
| Schema 9 (historical) | 34 × 186 × 166 × 4 × 7 | ~29M |
| Schema 8 (historical) | 40 × 186 × 166 × 4 × 7 × 3 | ~104M |
| Schema 7 (historical) | 40 × 186 × 186 × 4 × 7 × 7 × 3 | ~814M |
| **B:** one `playBonus` + `BonusPlay`~21 | 26 × 11 × 166 × 4 × 21 × 7 | (if kinds shrink) |
| Relative target swag (±4 → 9×9) on schema 10 | 374 × **81** × 4 × 7 | ~0.85M |

Notes:

- Schema 10: joint actor×kind (roster `0..10` × 34 kinds), pitch-only target, free-factor matching, no reroll/hand heads.
- Schema 9 baseline: free-factor matching, kind merges, separate actor board index (186).
- Further shrinks: path-kind merge, relative target (path-only), option **B**.

### 2. Distinct actions (what “actual move space” means here)

Not “max legal rows in one step.” That is a different statistic (mask width / APS). Here: **how many distinct choices exist if we enumerated only the free factors each kind actually uses** — so `skip` / `playHailMary` / `claimChallenge` are **not** multiplied by 165 targets.

#### Structural catalog (pad widths, unused axes omitted)

Sum over kinds of (product of that kind’s free factors). Still an **upper bound on encoding**, not rules-legality — e.g. every actor index × every pitch square for `throw`, including empty/illegal.

| Family | Shape | Swag count |
|--------|--------|----------:|
| ~14 `[Actor, BoardTarget]` kinds | 186 × 165 each | ~430k |
| Target-only (`throwTeammate`, `quickReleaseThrow`, `placeLooseBall`) | 165 each | ~0.5k |
| Face-order | 6 | 6 |
| Play-bonus (**A:** per-play kinds) | 15 × 1 | 15 |
| Challenge claim/replace | 2 × 3 slots | 6 |
| Kind-only (`skip`, `keepOffer`, `reroll`, adjust*, …) | 1 each | few |
| **Structural total (order of magnitude)** | | **~0.4M** |

Vs naive head product **~29M** because the product pretends every kind always has actor × target × face × ….

With roster actor (~10) instead of 186, the same structural sum drops further (almost all still `[Actor, BoardTarget]`).

Rules-reachable is **smaller still** (only occupied eligible actors, legal path ends, etc.).

#### Worth simulating?

| Question | Worth it? |
|----------|-----------|
| Structural sum above (by kind family) | Optional — can refine from `AiLegalKind` + factor table; the ~0.4M swag is enough to see the gap vs ~29M. |
| **Union of distinct encoded tuples over N games / expert dump** | **Yes, cheap-medium** — best “actual support” number for training. |
| Max / p50 / p95 **per-step** legal rows | Yes, but that’s **mask pressure**, not distinct-move catalog size. |
| Exhaustive rules-reachable set over all states | **No.** |

Working intuition: learning ranks among **tens–hundreds of legal rows per step**, drawn from a structural catalog on the order of **~10⁵** padded combinations (or ~10⁴ with roster actor) — not the ~29M pad product. Remaining representation Maybes matter for meaning / further shrink, not because softmax is hundreds of millions wide anymore.

### 3. After further inventory reductions

Baseline is schema 9 (~29M product). Remaining Maybes relative to that:

| Change | Naive product | Structural distinct (swag) | Notes |
|--------|---------------|----------------------------|-------|
| Free factors + kind merges + drop reroll head | — | — | **Done** (schema 9). |
| Pitch-only target + drop hand | — | — | **Done** (schema 8). |
| **B** instead of **A** | Can rise | ~same (15 vs ~20) | Only if we want one `playBonus` kind. |
| Combine path kinds | Modest | fewer AT-kinds | Evaluate. |
| Roster actor | → **~1.6M** | much smaller | Biggest remaining allocation win. |
| Relative target | e.g. ~14M with 9×9 | Path shrink; throws need large window | Kind-split or skip for throws. |
| Sequential conditioning | Unchanged | Unchanged | Preferences among the same set. |

---

## Policy action (schema 16)

**Gym action:** `Discrete(MAX_LEGAL_MOVES)` — index into this step’s packed `legal_moves` menu.
The policy **join-scores** each live row with the board latent (`LegalRowActorCriticPolicy`):
one preference over complete moves (not independent factor heads + remask).

Each packed row is **eight** ints (storage / ONNX legal-matrix input). Python
`encode_legal_rows` embeds them the same way board channels are embedded
(shared `player_index` / challenge / block-face embs + pitch xy) — it does **not**
re-pack:

| Slot | Factor | Width | Who uses it |
|------|--------|------:|-------------|
| 0 | actor | 11 | roster `playerIndex` — `0` when kind has no free actor |
| 1 | kind | 41 | `AiLegalKind.ordinal` |
| 2 | boardTarget | 166 | **pitch only** `0..164` + unused |
| 3 | challengeSlot | 4 | claim / replace |
| 4 | attachedBonus | 6 | Sprint/Weave/… twin (`0..4` + unused) |
| 5..7 | face0..2 | 6 each | Enforcer order pack codes (`0` unused; `1..5` = face) |

Wire still sends `kind` + `kindIndex` + typed factors (`Actor.playerIndex`, …).
Kotlin `AiLegalPacker` writes the eight ints; Python only pads for the gym Dict
and embeds.

Unused axes on a row are unused sentinels; the Kotlin DTO **omits** unused free
factors (pending-fixed values stay in `sugarFactors`).

**Schema 16:** legal-row join scores; 8-col pack (actor/kind/target/challenge/bonus/faces);
unmerged decline/adjust kinds; `cancelThrowTeammate` + `chooseBlockFaceOrder` restored;
Enforcer face-order offered to all seats when >1 perm; soft-mask biases preferred **rows**.
ONNX: packed obs + padded legal matrix → **chosen row index** (re-export required).

**Schema 15 (historical):** MultiDiscrete remask; joint actor×kind; 4-col pack;
faceOrder / cancel-TT omitted; Enforcer order humans-only.

### Factor-width audit (row packing)

| Factor | Width | Vs real rule/API max | Verdict |
|------|------:|----------------------|---------|
| actor | 11 | `0..10` roster pad | Exact. |
| kind | 41 | `AiLegalKind.entries` | Exact. |
| boardTarget | 166 | Pitch 165 + unused | Exact pitch (+ pad). |
| challengeSlot | 4 | 3 active challenges + unused | Exact. |
| attachedBonus | 6 | 5 twins + unused | Exact. |
| face0..2 | 6 each | unused + 5 `BlockDieFace` | Exact; pad short perms with unused. |

**playerIndex:** `0` = empty / no free actor; `1..10` = roster slot + 1 (catalog max 10). Same id on the occupied cell (`AiCellPlayerObs.playerIndex`) and on `AiFactor.Actor`. Both teams reuse `1..10`; `teamRel` on the cell separates us/them.

---

## Uniqueness hypotheses (to measure)

Intuition for **player actions that carry both actor and boardTarget** (run / mark / sidestep / throw / block / …). Card / challenge rows are different — see below.

| Slice | Expected pattern | Why it matters |
|-------|------------------|----------------|
| **(actor, boardTarget) → kinds** | **Usually 1 kind** | Same square as destination rarely legal under two verbs at once. Classic near-miss: run vs sidestep for the *same* path end — engine usually offers one based on marked status, not both. |
| **(actor, kind) → targets** | **Many targets** | Path / throw / block fans out. This is where most of the branching lives after actor+kind. |
| **(boardTarget) → kinds** (given actor, or globally) | **Few kinds** | e.g. that square is a run *or* a sidestep depending on marked; not a long list of verbs. |

**Implication for shared target logits:** after actor→kind remask, the target head often still has a **large** legal set — preference among targets *does* matter. Bleed across kinds for the *same* `(actor, target)` is probably **rare** if the first row of the table holds.

**Non-player / no-target rows** (bonus play, challenge claim, decline, …) should be **excluded or bucketed separately** when measuring the table above — they often have no actor and/or no target.

---

## Straight free `[Actor, BoardTarget]` kinds

Same free-factor shape; kind is the verb. Kotlin mapping uses `perspectiveBoardIndexOf` (actor) + `perspectivePitchIndex` (target, **pitch only**).

| Kind(s) | Notes |
|---------|--------|
| `run` / `mark` / `sidestep` | `PathMovement` — path end → target. Eligibility partitions by board (marked → sidestep; open path → run; end adjacent enemy → mark). |
| `declareRun` | Declare then path; target = declared square. |
| `throw` | Target = catcher. Normal throw while acting. |
| `throwTeammateSelect` | Free: thrower + teammate square. |
| `block` / `foul` | Target = victim square. Free choice among legal victims. |
| `standUp` | Actor + target (stand destination / square). |
| `reserves` | Actor on **bench**; target = pitch entry square. |

Target-only free (actor sugar): `throwTeammate`, `quickReleaseThrow`, `placeLooseBall` — see [free choices](#which-factors-are-free-choices).

---

## Pass / decline & related

Schema 16 keeps wire declines as **distinct kinds** (no `declineOffer` merge):

| Wire kind | AI kind | Free factors | Sugar |
|-----------|---------|--------------|-------|
| `declineBonus` | `declineBonus` | `[]` | — |
| `skip` | `skip` | `[]` | Actor |
| `continueBlock` | `continueBlock` | `[]` | Actor + block destination |
| `Choose*Reroll(false)` | `keepOffer` | `[]` | Actor |
| `Choose*Reroll(true)` | `reroll` | `[]` | Actor |
| `cancelThrowTeammate` | `cancelThrowTeammate` | `[]` | Actor |

**Quick Release interrupt in obs?** Backend pending stage is `BlockStage.HANDLE_QUICK_RELEASE`. AI obs exposes coarse `TurnPhase` only — interrupt is implied by legal kinds (`quickReleaseThrow` + `skip` / keep). Free choice still differs from normal throw (target-only vs actor+target) — don’t merge those kinds.

---

## Which factors are free choices?

Schema 9: free → `factors` (matched); pending-fixed → `sugarFactors` (not matched). `LegalActionId` still carries full identity for submit.

| Kind | Free (`factors`) | Sugar |
|------|------------------|-------|
| `run` / `mark` / `sidestep` / `declareRun` (4) | **Actor + target** | — |
| `throw` / `block` / `foul` / `reserves` / `standUp` (5) | **Actor + target** | — |
| `throwTeammateSelect` | **Actor + target** | — |
| `quickReleaseThrow` / `throwTeammate` / `placeLooseBall` (3) | **Target** | Actor |
| `skip` / `declineBonus` / `continueBlock` / `keepOffer` / `reroll` / adjust* / `cancelThrowTeammate` | **Kind only** (`[]`) | Actor (± target) when wire had them |
| play-bonus kinds (15) | **Kind** among offered plays | — |
| `chooseBlockFaceOrder` | **FaceOrder** → pack face0..2 | Actor |
| `claimChallenge` / `replaceChallenge` (2) | **Challenge slot** | — |

**Implication:** uniqueness stats must use **free** factors only — Do-Over / decline / reroll are kind-only rows, not `actor×target` branching.

---

## Non-straightforward mappings (not plain actor + target)

How we map today — candidates to treat as separate buckets in stats.

### Target only (actor sugar)

| Kind | Free | Sugar | Notes |
|------|------|-------|--------|
| `throwTeammate` | `[BoardTarget]` | `[Actor]` | Landing after select. |
| `quickReleaseThrow` | `[BoardTarget]` | `[Actor]` | Victim is fixed. |
| `placeLooseBall` | `[BoardTarget]` | `[Actor]` | `from` / player from pending. |

### Kind only (empty free factors)

| Kind | Sugar | Notes |
|------|-------|--------|
| `skip` / `declineBonus` / `continueBlock` / `keepOffer` | often `[Actor]` (± target) | Pass / keep / don’t dump. |
| `reroll` | `[Actor]` | Spend OS / EP reroll. |
| `adjustThrowByDoOver` / `adjustBlockByDoOver` / `adjustArmourByDoOver` | `[Actor]` (± destination) | Distinct kinds (schema 16). |
| `cancelThrowTeammate` | `[Actor]` | Abort TT select. |
| play-bonus (15) | — | Identity in kind. |

### Other

| Kind | Free | Sugar | Notes |
|------|------|-------|--------|
| `chooseBlockFaceOrder` | `[FaceOrder]` | `[Actor]` | ≤6 perms; packed into face0..2. |
| `claimChallenge` / `replaceChallenge` | `[ChallengeSlot]` | — | Active challenge row. |

### Pad / unused on the wire

Harness always emits **eight** ints; axes a kind does not use get unused sentinels
(`ATTACHED_BONUS_UNUSED`, `BLOCK_FACE_PACK_UNUSED`, actor `0`, …). Soft-mask may
further **boost** preferred **rows** (never removes a legal row). Actor `0` means
“no free actor” (kind-only / target-only free rows).
