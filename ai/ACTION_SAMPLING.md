# Action sampling — legal rows, factor pack, inventory

Source of truth for **how the policy encodes and samples actions**. Schema 16+
**join-scores complete legal rows** ([Policy action](#policy-action-schema-18));
there are no independent MultiDiscrete factor heads on the live path. Older
factor-head inventory below is **historical** (schema ≤15) for packing archaeology
only. Backlog stub: [AI2.11](BACKLOG.md#ai211--action-sampling--factor-space-inventory).
**Owners:** [`OWNERSHIP.md`](OWNERSHIP.md).

Mapping truth: [`AiLegalChoiceMapping.kt`](../backend/src/main/kotlin/com/mhaefele/gridiron/api/ai/mapping/AiLegalChoiceMapping.kt) (+ [`AiLegalKind`](../backend/src/main/kotlin/com/mhaefele/gridiron/api/ai/dto/AiLegalKind.kt)).

---

## Inventory

**Next steps to improve action representation & sampling** — what each packed
factor means, and how we pick among legal rows. Goal: make legal choices easier
to learn and score correctly, not richer board obs
([AI2.8](BACKLOG.md#ai28--evaluate-obs-enrichment-vs-fix-legals) /
[AI2.10](BACKLOG.md#ai210--legal-move-inputs--obs-dict-shape)) or reward
([AI2.7](BACKLOG.md#ai27--optional-reward-shaping-day-2)).

Two levers:

1. **Representation** — shrink or re-basis packed row factors so columns mean the
   right thing (pitch-only targets, bonus-play identity **once**, optional path
   merges / relative dest encoding).
2. **Sampling procedure** — **schema 16+:** join-score each legal row given the
   board latent (`Discrete(MAX_LEGAL_MOVES)` + MaskablePPO). Soft preference bias
   toward `expectedScenarioMove` rows (schema 12, row-level under 16). Factor
   MultiDiscrete remask / head conditioning is **gone** (schema 15 fork only).

Schema bumps + checkpoint breaks expected for representation items. Measure
before spending on sampling-procedure upgrades.

### Train mix vs shared pack / embeddings

The same legal-row pack and shared embeddings train across drills. Wrong mix →
wrong kind priors (e.g. throw/claim bleeding into TD_RUN). **Phase order, gates,
and checkpoints** live in [`TRAINING_ROADMAP.md`](TRAINING_ROADMAP.md)
(`scenario_curriculum.py` / `training_phases.py`) — not duplicated here.

Soft-mask (below) applies whenever the server flags preferred rows — board *and*
claim openings — without hard-removing distractors (hard mask already failed
transfer).

### Soft-mask preference

**Soft-mask ≠ reward.** Soft-mask only means **look at / sample these rows**
(logit bias). Good/bad is entirely the reward (curriculum slap/bonus, terminal, …).
Fail distractors on the soft-mask set are intentional exposure (“consider this”),
then reward grades the look — not “soft-mask = this pays.”

**Landed (schema 12; row-level under schema 16).** Gym key `preferred_legal` /
`soft_mask_legal` parallels `legal_moves` rows (**stable int8 0/1**). The
join-score policy adds `SOFT_MASK_LOGIT_BIAS × policy.soft_mask_strength` to
flagged **row** logits before the categorical over the menu. Illegal / pad rows
stay hard-masked at 0; non-flagged legals stay available (soft tilt, not a hard
filter).

**Do not** encode train juice (bottleneck strength, curriculum, debug) into the
`soft_mask_legal` Box — SB3 pickles `observation_space` into every checkpoint zip.
Strength is a live `LegalRowActorCriticPolicy.soft_mask_strength` attr (default `1.0`;
8× bottleneck sets `SOFT_MASK_BOTTLENECK_STRENGTH`).

**Train rollouts** pack preferred-row flags (`soft_mask_bias=True`). **Eval** follows
`--eval-episode-mode`: `EVAL` (default, curriculum gates) zeros `soft_mask_legal` for
unaided ranking; `TRAIN` keeps bias on (same tilt as rollouts) and enables claim
reachability soft-mask *search* on the server. Same bias on train sample and
`evaluate_actions` replay (flags ride in obs) so importance ratios stay consistent.

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

**Expert clone vs runtime preference:** behavioral clone copies lookahead **choices**
with soft-mask **off** (dump omits `softMaskLegal`; clone zeros `soft_mask_legal` in
obs). Soft-mask remains a **PPO train-time** tilt toward server-flagged rows. See
[`scenario_curriculum.py`](gridiron_ai/scenario_curriculum.py) gate/demotion policy separately.

### Strategic directions

For *this* problem (smarter action choices), almost everything collapses to two levers:

| Lever | What it changes | Inventory home |
|-------|-----------------|----------------|
| **1. Encode moves better** | What each pack column **means**, how wide its vocab is, deadweight removed, kinds merged | Yes-do + Maybes (path merge, relative dest, …) |
| **2. Sample among legal rows** | How we **pick** among packed rows — join-score + soft preferred bias | Soft-mask (landed); further row encoders / attention are optional |

**Default path:** Schema 10 packing (roster actor) → soft-mask (schema 12) →
**schema 19 legal-row join scores** (dest + source as pitch x/y). Measure
uniqueness before more representation shrinks.

**Landed:** score the legal set directly — join board latent to each encoded row
(`LegalRowActorCriticPolicy` / ONNX row scores). Remaining
[AI2.10](BACKLOG.md#ai210--legal-move-inputs--obs-dict-shape) work is whether the
value net / features should see richer legal-menu summaries beyond join-scoring
the action.

**Not substitutes for 1–2:** held mid-check rolls (**prime [AI2.12](BACKLOG.md#ai212--held-dice--mid-check-obs--scenarios)**), other board obs ([AI2.8](BACKLOG.md#ai28--evaluate-obs-enrichment-vs-fix-legals)), reward shaping ([AI2.7](BACKLOG.md#ai27--optional-reward-shaping-day-2)), more PPO steps alone. Train-*mix* / phase gates → [`TRAINING_ROADMAP.md`](TRAINING_ROADMAP.md), not pack-column geometry.

### Yes — do

Committed representation work. Engine wire kinds stay distinct for submit; policy
kinds / factors are the learning contract. Wording like “head” below is **historical
MultiDiscrete** naming from when that item landed.

- [x] **Pitch-only `boardTarget` pack column.** Landed (schema 8): target width `PITCH_SQUARE_COUNT+1` (**166**); actor was pitch+bench (**186**) until schema 10.
- [x] **Bonus plays: encode identity once; drop `HandSlot`.** Landed (schema 8, option **A**): per-play `AiLegalKind`s; empty factors on play-bonus rows; hand MultiDiscrete axis removed.
- [x] **Factors = free choices only; mark sugar; match on free factors.** Landed (schema 9): `AiLegalChoiceDto.factors` = free only; `sugarFactors` = pending-fixed (not MultiDiscrete-matched). `resolve_action` matches free factors → submit full `legalActionId` (sugar stays in the id).
- [x] **`declineOffer` coarse kind.** Landed (schema 9); **undone schema 16** — distinct `skip` / `declineBonus` / `continueBlock` / `keepOffer` again (join-score rows don’t need the MultiDiscrete merge).
- [x] **Exclude `cancelThrowTeammate` from the policy.** Landed (schema 9); **restored schema 16** — cancel is a legal-row kind again.
- [x] **Rerolls: one do-it kind + keep, no boolean.** Landed (schema 9): `Choose*Reroll(true)` → `reroll`; keep → `keepOffer` (was `declineOffer`). `AiFactor.Reroll` and the MultiDiscrete reroll axis removed.
- [x] **Combine Do-Over adjusts.** Landed (schema 9); **undone schema 16** — `adjustThrowByDoOver` / `adjustBlockByDoOver` / `adjustArmourByDoOver` are distinct kinds again.
- [x] **Actor by roster playerIndex** (schema 10). Joint `(actor, kind)` MultiDiscrete product landed then **superseded schema 16** — separate actor + kind pack columns; gym action is Discrete row index.

### Measure first (cheap stats → decide)

Evidence for whether representation merges are worth it. Hypotheses:
[Uniqueness hypotheses](#uniqueness-hypotheses-to-measure). Bucket **player
`[Actor, BoardTarget]` rows** separately from card/challenge/follow-up shapes.

- [ ] **`(actor, boardTarget) → #kinds`.** Expect **~1** for path/throw/block-style rows (run vs sidestep for the same end usually don’t both appear). Confirm rarer collisions.
- [ ] **`(actor, kind) → #targets`.** Expect **many** — main branching after actor+kind; join-score still has to rank many dests for the same verb.
- [ ] **`(actor, boardTarget)` or target → #kinds`.** Expect **few** (e.g. run vs sidestep by marked). Cross-check against first bullet.
- [ ] **Exclude / separately bucket** rows with no target or no player actor (`play*`, claim/replace, decline, reroll-only shapes) so they don’t distort the table.

### Evaluate / Maybe (preference / architecture)

Optional representation shrinks — pick after Measure first (and after Yes-do where noted).
**Won’t do under join-score:** resurrecting MultiDiscrete remask, sequential factor
heads, or “kind-conditioned target logits” as separate policy axes.

- [ ] **Bonus-play identity: kind vs factor (XOR).** Schema 8 chose **A** (per-play kinds, no hand/BonusPlay factor). Revisit only if we want **B** (one `playBonus` + `BonusPlay` pack column) instead — still XOR, not both.
- [ ] **Combine similar path / “move” kinds (`run` / `sidestep` / `mark`).** Same `[Actor, BoardTarget]` shape; engine already partitions by board (marked → sidestep, open → run, end adjacent enemy → mark). One `move` (or `path`) kind would shrink the kind vocab; verb becomes eligibility / obs rather than a packed class. Tradeoff: less direct “I want to mark” signal on the kind embedding — policy must recover intent from obs + destination. Extend only carefully to other near-synonyms (don’t merge throw/block).
- [ ] **Throw vs `quickReleaseThrow`.** Free factors differ: **throw** = actor + target; **quickReleaseThrow** = target only (actor sugar). Don’t merge. Quick Release is also an interrupt window — obs `phase` is only coarse `TurnPhase`, not `BlockStage.HANDLE_QUICK_RELEASE`.
- [x] **Actor by player id / roster slot instead of board index.** Landed (schema 10): `1..10` + `0` unused; cell `playerIndex` binds id↔square.
- [x] **Soft-mask preferred rows.** Landed (schema 12): `preferred_legal` + logit bias on values from `expectedScenarioMove` rows — see [Soft-mask preference](#soft-mask-preference). Not a hard legal filter.
- [ ] **Relative dest (Δx / Δy from actor) instead of absolute pitch `(x,y)`.** Could shrink *path* fan-out if the window tracks Move (e.g. ±4 → 9×9) and transfer “step forward” across the board. Costs: (1) end-zone / sideline geography leaves the packed dest; (2) throws / Hail Mary aim near board-wide, so a shared relative encoding needs a window ≈ full pitch (**no shrink**) or kind-specific dest encodings; (3) reserves / place-loose-ball / absolute tactics get awkward. Likely a win only for path-like kinds, not one global relative dest column.
- [x] ~~Sequential factor conditioning / kind-conditioned target logits / joint `(actor, kind)` MultiDiscrete~~ — **won’t do.** Schema 16 join-score replaced those sampling procedures.

### Out / don’t confuse with

- Naive flat MultiDiscrete catalog of all factor combos (~29M schema-9 pad product) — **historical**; live gym action is `Discrete(MAX_LEGAL_MOVES)`. See [Move space](#move-space).
- Board-obs enrichment or feeding the legal menu into the value net — [AI2.8](BACKLOG.md#ai28--evaluate-obs-enrichment-vs-fix-legals) / [AI2.10](BACKLOG.md#ai210--legal-move-inputs--obs-dict-shape).
- Reward shaping — [AI2.7](BACKLOG.md#ai27--optional-reward-shaping-day-2).
- Per-step max legal rows — mask/APS metric; not structural distinct-move size.

### Follow-up (after schema-19 dest xy)

- [ ] **Harness `legalRows` wrap `LegalAction`** — stop projecting into wire `AiFactor`s; packer maps `LegalAction` → ints; reward/eval read `action`. Backlog: [AI2.21](BACKLOG.md#ai221--harness-legalrows-wrap-legalaction).

---

## Move space

Three different notions — don’t mix them.

1. **Per-step legal menu** — how many rows are legal *right now* (`legal_move_count` / APS). **This is what the join-score policy ranks.**
2. **Structural distinct actions** — enumerate by kind using **only that kind’s free factors** (upper bound on encoding, not rules-legality).
3. **Historical MultiDiscrete product** — allocate all factor widths as if every action used every axis. Softmax fatness of the **old** policy; **not** the live action space.

### 1. Per-step legal set (what the policy samples)

Working intuition: tens–hundreds of legal rows per step (pad `MAX_LEGAL_MOVES` = 1024).
Soft-mask tilts preferred rows; hard mask zeros pad / illegal indices.

### 2. Distinct actions (encoding catalog swag)

Not “max legal rows in one step.” Here: **how many distinct choices exist if we
enumerated only the free factors each kind actually uses** — so `skip` /
`playHailMary` / `claimChallenge` are **not** multiplied by 165 targets.

#### Structural catalog (pad widths, unused axes omitted)

Sum over kinds of (product of that kind’s free factors). Still an **upper bound on
encoding**, not rules-legality — e.g. every actor index × every pitch square for
`throw`, including empty/illegal.

| Family | Shape | Swag count |
|--------|--------|----------:|
| ~14 `[Actor, BoardTarget]` kinds | ~10 × 165 each (roster actor) | ~23k |
| Target-only (`throwTeammate`, `quickReleaseThrow`, `placeLooseBall`) | 165 each | ~0.5k |
| Face-order | 6 | 6 |
| Play-bonus (**A:** per-play kinds) | 15 × 1 | 15 |
| Challenge claim/replace | 2 × 3 slots | 6 |
| Kind-only (`skip`, `keepOffer`, `reroll`, adjust*, …) | 1 each | few |
| **Structural total (order of magnitude)** | | **~10⁴–10⁵** |

Rules-reachable is **smaller still** (only occupied eligible actors, legal path ends, etc.).

#### Worth simulating?

| Question | Worth it? |
|----------|-----------|
| Structural sum above (by kind family) | Optional — refine from `AiLegalKind` + factor table. |
| **Union of distinct encoded tuples over N games / expert dump** | **Yes, cheap-medium** — best “actual support” number for training. |
| Max / p50 / p95 **per-step** legal rows | Yes — **mask pressure** for the join-score menu. |
| Exhaustive rules-reachable set over all states | **No.** |

### 3. Historical MultiDiscrete product (schema ≤15)

Fixed factor widths multiplied together. Almost every slot was masked every step;
this was **not** “how many moves the game has.” Kept only so old schema notes still
parse.

| Config | Widths | Product |
|--------|--------|--------:|
| Schema 10 (last MultiDiscrete-ish packing) | 374 × 166 × 4 × 7 | **~1.7M** |
| Schema 9 | 34 × 186 × 166 × 4 × 7 | ~29M |
| Schema 8 | 40 × 186 × 166 × 4 × 7 × 3 | ~104M |
| Schema 7 | 40 × 186 × 186 × 4 × 7 × 7 × 3 | ~814M |

Schema 16+ gym action width is **`MAX_LEGAL_MOVES` (1024)**, not these products.
Remaining representation Maybes matter for meaning / further shrink of **pack
columns**, not because a MultiDiscrete softmax is hundreds of millions wide.

## Policy action (schema 19)

**Gym action:** `Discrete(MAX_LEGAL_MOVES)` — index into this step’s packed `legal_moves` menu.
The policy **join-scores** each live row with the board latent (`LegalRowActorCriticPolicy`):
one preference over complete moves (not independent factor heads + remask).

**Menu order:** Kotlin `AiLegalChoiceMapper.toLegalChoices` keeps **generator / catalog
order** (no per-step shuffle). Packed `legalMoves`, harness `legalRows` (incl. soft-mask /
passing flags), expert `chosenFactors`, and the Discrete/ONNX **row index** share that
order — no separate remap table. Join-score is seat-invariant (shared row scorer); shuffle
was removed as non-load-bearing.

Each packed row is **thirteen** ints (storage / ONNX legal-matrix input). Python
`encode_legal_rows` embeds them the same way board channels are embedded
(shared `player_index` / challenge / block-face embs + pitch xy + raw ball flags)
— it does **not** re-pack:

| Slot | Factor | Role | Width | Who uses it |
|------|--------|------|------:|-------------|
| 0 | actor | **identity (who)** | 11 | roster `playerIndex` — same id as cell occupant; `0` when kind has no free actor |
| 1 | kind | identity | 41 | `AiLegalKind.ordinal` |
| 2 | targetX | identity (dest) | 12 | 0-based pitch x from Kotlin (`0..10` + unused) |
| 3 | targetY | identity (dest) | 16 | 0-based pitch y from Kotlin (`0..14` + unused) |
| 4 | challengeSlot | identity | 4 | claim / replace |
| 5 | attachedBonus | identity | 6 | Sprint/Weave/… twin (`0..4` + unused) |
| 6..8 | face0..2 | identity | 6 each | Enforcer order pack codes (`0` unused; `1..5` = face) |
| 9 | sourceX | **metadata** (tail) | 12 | 0-based pitch x from Kotlin (`0..10` + unused) |
| 10 | sourceY | **metadata** (tail) | 16 | 0-based pitch y from Kotlin (`0..14` + unused) |
| 11 | actorHasBall | **metadata** (tail) | 2 | `1` if actor holds the ball; `0` if no actor / no ball |
| 12 | targetHasBall | **metadata** (tail) | 2 | `1` if dest square has ball (loose or carrier); `0` if no dest / no ball |

Harness `legalRows` nest engine `LegalAction` (absolute coords) + AI flags only.
Kotlin `AiLegalPacker` maps action + perspective → the thirteen pack ints; Python only
pads for the gym Dict and embeds. Dest / source x/y in the pack are perspective
0-based; dugout / no-actor → unused pads. **Who** on a path/throw/block row is
always roster `actor`, never source coords. Obs `cells[]` still uses linear pitch
indices — board layout packing, not the legal-row dest encoding. Harness reward
board facts are `rewards.players` + single `ballCoordinate` (null if none on pitch).

Unused axes on a packed row use unused sentinels; the packer maps from `LegalAction`.

**Schema 21:** harness rewards = `players` + `ballCoordinate`; legal rows lean.
**Schema 20:** harness rows wrap `LegalAction` + flags; pack still perspective xy.
**Schema 19 (historical):** dest as pitch `(x,y)` on harness (drop linear index).
Schema 15 remains the only dual-path ONNX fork — bump/mutate 16+ freely (re-export ONNX).

**Schema 18 (historical):** source `(x,y)` metadata; dest still linear pitch index.

**Schema 17 (historical):** single `sourceSquare` board index + menu shuffle.

**Schema 16 (historical):** 8-col pack (no source); catalog menu order.

**Schema 15 (historical):** MultiDiscrete remask; joint actor×kind; 4-col pack;
faceOrder / cancel-TT omitted; Enforcer order humans-only.

### Legal-move information: available vs exposed

What the engine/GUI `LegalAction` (and AI harness sidecars) know, vs what the **join-score
row** / **board obs** actually see. Soft-mask is sampling bias, not a row feature.

| Signal | Where it lives | On packed legal row? | Elsewhere in model input? |
|--------|----------------|----------------------|---------------------------|
| Action kind (`run` / `throw` / …) | `LegalAction` / `AiLegalKind` | **Yes** — kind column | — |
| Acting player identity | `playerId` | **Yes** — roster `Actor` | Cell `playerIndex` |
| Destination square | path end / `targetCoordinate` | **Yes** — `targetX`/`targetY` (0-based pitch) | Pitch terrain / occupants |
| Source square (actor now) | board position of actor | **Yes** — `sourceX`/`sourceY` **metadata** (not who-id) | Same cell via `playerIndex` |
| Actor holds ball | `Player.holdsBall()` | **Yes** — `actorHasBall` **metadata** | Cell `hasBall` on actor square |
| Dest square has ball | loose / carrier on target | **Yes** — `targetHasBall` **metadata** | Cell `hasBall` on dest |
| Attached geometry bonus (Sprint/…) | `requiredBonusCard` | **Yes** — `attachedBonus` | — |
| Challenge claim / replace slot | claim/replace actions | **Yes** — `challengeSlot` | Challenges tensor |
| Enforcer face order | `ChooseBlockFaceOrder` | **Yes** — `face0..2` | `held_check` faces |
| Soft-mask preferred row | training opening flag | **No** (parallel `soft_mask_legal`) | Logit bias only |
| Passing / PASS-grade flag | training sidecar | **No** (harness / eval only) | — |
| Potential challenge claims | `potentialChallengeClaims` | **No** | Player affordance might/certain claim |
| Ball encounter (pickup/…) | `ballEncounter` on path | **No** (dest binding via `targetHasBall`) | Affordance `canPickupBall`; cell `hasBall` |
| Full path squares | `path` | **No** (end only as target) | — |
| Throw range / hand-off | throw DTOs | **No** | — |
| Available bonus cards (prompt set) | `availableBonusCards` | **No** | Hand tensor |
| “This run scores a TD” | derived (carrier + EZ dest) | **No** | Affordance `canScoreTouchdown` on **player**; scoring EZ terrain + `hasBall` |
| Submit id | `legalActionId` | **No** (sidecar) | Resolve after Discrete index |

### Factor-width audit (row packing)

| Factor | Width | Vs real rule/API max | Verdict |
|------|------:|----------------------|---------|
| actor | 11 | `0..10` roster pad | Exact. |
| kind | 41 | `AiLegalKind.entries` | Exact. |
| targetX / targetY | 12 / 16 | Pitch W/H + unused | 0-based pitch dest (+ pad). |
| challengeSlot | 4 | 3 active challenges + unused | Exact. |
| attachedBonus | 6 | 5 twins + unused | Exact. |
| face0..2 | 6 each | unused + 5 `BlockDieFace` | Exact; pad short perms with unused. |
| sourceX / sourceY | 12 / 16 | Pitch W/H + unused | 0-based pitch metadata (+ pad). |
| actorHasBall / targetHasBall | 2 each | boolean 0/1 | Same sense as cell `hasBall`; 0 when no actor/dest. |

**playerIndex:** `0` = empty / no free actor; `1..10` = roster slot + 1 (catalog max 10). Same id on the occupied cell (`AiCellPlayerObs.playerIndex`) and on `AiFactor.Actor`. Both teams reuse `1..10`; `teamRel` on the cell separates us/them.

---

## Uniqueness hypotheses (to measure)

Intuition for **player actions that carry both actor and boardTarget** (run / mark / sidestep / throw / block / …). Card / challenge rows are different — see below.

| Slice | Expected pattern | Why it matters |
|-------|------------------|----------------|
| **(actor, boardTarget) → kinds** | **Usually 1 kind** | Same square as destination rarely legal under two verbs at once. Classic near-miss: run vs sidestep for the *same* path end — engine usually offers one based on marked status, not both. |
| **(actor, kind) → targets** | **Many targets** | Path / throw / block fans out. Join-score still ranks many dests for the same verb. |
| **(boardTarget) → kinds** (given actor, or globally) | **Few kinds** | e.g. that square is a run *or* a sidestep depending on marked; not a long list of verbs. |

**Implication:** most of the hard ranking is among **destinations** for a given actor+kind (or among whole rows that differ mainly in dest). Bleed across kinds for the *same* `(actor, target)` is probably **rare** if the first row of the table holds — useful for whether path-kind merges are safe, not for resurrecting a target MultiDiscrete head.

**Non-player / no-target rows** (bonus play, challenge claim, decline, …) should be **excluded or bucketed separately** when measuring the table above — they often have no actor and/or no target.

---

## Straight free `Actor` + `target` kinds

Same shape; kind is the verb. Kotlin mapping uses roster `Actor` + row `target` (`AiPitchSquare` via `perspectivePitchXy0`).

| Kind(s) | Notes |
|---------|--------|
| `run` / `mark` / `sidestep` | `PathMovement` — path end → `target`. Eligibility partitions by board (marked → sidestep; open path → run; end adjacent enemy → mark). |
| `declareRun` | Declare then path; `target` = declared square. |
| `throw` | `target` = catcher. Normal throw while acting. |
| `throwTeammateSelect` | Free: thrower + teammate square. |
| `block` / `foul` | `target` = victim square. Free choice among legal victims. |
| `standUp` | Actor + `target` (stand destination / square). |
| `reserves` | Actor on **bench**; `target` = pitch entry square. |

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

| Kind | Free (`factors`) | `target` | Sugar |
|------|------------------|----------|-------|
| `run` / `mark` / `sidestep` / `declareRun` (4) | **Actor** | dest | — |
| `throw` / `block` / `foul` / `reserves` / `standUp` (5) | **Actor** | dest | — |
| `throwTeammateSelect` | **Actor** | dest | — |
| `quickReleaseThrow` / `throwTeammate` / `placeLooseBall` (3) | `[]` | dest | Actor |
| `skip` / `declineBonus` / `continueBlock` / `keepOffer` / `reroll` / adjust* / `cancelThrowTeammate` | **Kind only** (`[]`) | sometimes | Actor when wire had them |
| play-bonus kinds (15) | **Kind** among offered plays | — | — |
| `chooseBlockFaceOrder` | **FaceOrder** → pack face0..2 | — | Actor |
| `claimChallenge` / `replaceChallenge` (2) | **Challenge slot** | — | — |

**Implication:** uniqueness stats must use **free** factors only — Do-Over / decline / reroll are kind-only rows, not `actor×target` branching.

---

## Non-straightforward mappings (not plain actor + target)

How we map today — candidates to treat as separate buckets in stats.

### Target only (actor sugar)

| Kind | Free | `target` | Sugar | Notes |
|------|------|----------|-------|--------|
| `throwTeammate` | `[]` | dest | `[Actor]` | Landing after select. |
| `quickReleaseThrow` | `[]` | dest | `[Actor]` | Victim is fixed. |
| `placeLooseBall` | `[]` | dest | `[Actor]` | `from` / player from pending. |

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

Harness always emits **thirteen** ints; axes a kind does not use get unused sentinels
(`ATTACHED_BONUS_UNUSED`, `BLOCK_FACE_PACK_UNUSED`, actor `0`, target/source unused, …).
Soft-mask may further **boost** preferred **rows** (never removes a legal row).
Actor `0` means “no free actor” (kind-only / target-only free rows).
