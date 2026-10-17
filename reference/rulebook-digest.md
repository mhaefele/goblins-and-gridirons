# Goblins & Gridirons — rules digest

Working rules reference for this repo. Prefer this file for **rules questions** and rulebook-fidelity decisions. Code KDocs cite sections here as `digest § <heading>`.

- **Card text** (challenges, bonus plays, random events): [`cards.md`](cards.md) — generated from the Kotlin catalogs
- **Encoded team/pitch data:** `GridironTeamCatalog`, `PlayerAbility`, `PitchCatalog` in the backend
- **Engine design / domain / API:** [`backend/ARCHITECTURE.md`](../backend/ARCHITECTURE.md)

When card or ability text conflicts with this digest, **card text wins**; when something says a player **cannot**, that beats a **can**.

---

## Pitch and board

- Play area is a rectangular grid of squares. This product’s default layouts are **11×15** playable squares (`PitchCatalog`).
- **End zones:** one full-width row at each end (teams set up here; Touchdowns scored in the **opponent’s** End Zone).
- **Adjacency** is eight directions (orthogonal + diagonal).
- At most one standing/prone player per square. Only one ball per square; a player may hold only one ball.
- **Blocked** squares (red border) cannot be entered.
- **Trapdoor** squares: where a new ball may enter play (then bounce). Trapdoors do **not** block movement.
- Off-pitch / out-of-bounds is impassable for movement and bounce landing.
- Score Track and Bounce Chart are printed on the board.

---

## Player stats and team cards

Each position has Move, Block (number of Block Dice), Throw, and Armour.

| Stat | Meaning |
|------|---------|
| Move | Max squares on a Run. If shown as **D6**, roll D6 for range each Run Action |
| Block | How many Block Dice the player rolls when Blocking |
| Throw | Target number on a Throw Check (e.g. 4+). **`-`:** cannot take possession of a ball; if they enter a ball square or a ball would bounce onto them, it bounces again from their square |
| Armour | Target number on an Armour Check. **`-`:** Armour Check auto-fails (do not roll) |

Emergency Reserves (ER) is a team value used in pre-turn (below).

Box teams in the base engine: **Dwarves** and **Orcs** — see `GridironTeamCatalog`. Full 23-team set is deferred.

---

## Checks

- Roll **D6** vs a threshold (Throw Value / Armour Value). Equal or higher succeeds.
- Apply modifiers to the dice result **before** comparing to the threshold.
- Modifiers from all sources are capped at **±1 total**.
- **Unmodified** natural **1** always fails; **unmodified** natural **6** always succeeds.
- Armour `-`: auto-fail, no roll. Throw `-`: cannot hold the ball (see above).
- Re-rolls: must take the new result; cannot re-roll a re-roll; only your own rolls.

---

## Marking and status

| Condition | Status |
|-----------|--------|
| Standing, no adjacent standing opposition | **Open** |
| Standing, adjacent to ≥1 non-Prone opposition | **Marked** (mutual: both Mark each other) |
| Laying down | **Prone** — cannot Mark or be Marked; if holding a ball when becoming Prone, ball bounces |
| In dugout | **Reserves** |

---

## Actions by status

| Status | Allowed actions |
|--------|-----------------|
| Open | Run, Mark, **Throw** (if holding the ball), **Throw Team-mate** (Ogre/Troll/Oakwalker, no ball), **Foul** (adjacent Prone opposition) |
| Marked | Block, Sidestep (**not** Throw / Throw Team-mate / Foul unless Ice Cold for throws) |
| Prone | Stand Up only |
| Reserves | Reserves only |

### Turn budget

- Each coach gets **3** player actions per turn (may split across players).
- The same player may not repeat the **same action type** in one turn (different types OK).
- **Exception after a touchdown:** after scoring, the miniature returns via Reserves as a “new” player and may use action types again that turn if actions remain.
- **Free actions** (abilities/cards): same once-per-type restrictions, do **not** spend the 3-action budget, must be taken when granted. Engine kinds:
  - **Budgeted / regular** — costs one of the three Player Actions; runs in `IN_ACTION`.
  - **Standalone free** — Extra Effort (extra slot), Back on Your Feet / Reserves (convert-in-place free, then same after-action path as Extra Effort); runs in `IN_ACTION`; **own** Claim Challenge Card step.
  - **Add-ons** (Relentless, Strike and Fade, Momentum, Catch and Go, All-In Tackle, …) — hang off the granting action; run in `EXECUTE_FREE_FOLLOW_UP` / `EXECUTE_AFTER_OWN`; **no** own claim step (outcomes may fulfill the parent turn-slot claim).
  - **Opposition interrupts** (Cut Off, Slip Away, …) — nest in the interrupted action’s `IN_ACTION`; **no** claim for the interrupter.
  - Rulebook “accompanied by a claim step” is read as: turn-slot actions (budgeted + standalone free) get a claim; add-on *outcomes* may fulfill that claim — not that every Relentless/interrupt inserts an extra claim slot. See [`cards.md`](cards.md).

---

## Movement actions

### Run (Open)

- Move up to Move value into adjacent squares.
- Cannot enter occupied (standing or prone) or blocked squares; cannot leave the pitch.
- Cannot enter a square adjacent to an **Open or Marked** opposition player; **may** move adjacent to **Prone** opposition.
- Moving into a square containing a ball during a Run → **pick up** (possession). Moving onto a ball by any other means (Mark, Sidestep, shove, etc.) → ball **bounces** (no pickup).
- Carrier moving onto another ball → the other ball bounces from their square.

### Mark (Open)

- Move up to **2** squares using Run movement rules, **except**:
  - May move adjacent to Open or Marked opposition.
  - **Must finish Marking** an opposition player (adjacent to standing opposition).
  - **Must end the move as soon as** they become adjacent to an Open or Marked opposition player (cannot keep walking after first contact).
- Entering a ball square → bounce (not pickup).

### Sidestep (Marked)

- Move **1** square; must finish **Open**.
- Onto a ball → bounce.

### Stand Up (Prone)

- Stand in the current square.

### Reserves (Reserves)

- Place into own End Zone from dugout.
- Cannot place adjacent to a standing opposition player, or on a ball, **unless no other option**.
- If forced onto a ball → ball immediately bounces.
- If every End Zone square is occupied → no Reserves Actions.

---

## Throw

- **Open** player in possession throws to a Team-mate on the pitch who is **not Prone**.
- Range via Throw Ruler: **Short** (black) or **Long** (red). Obstructions on the ruler do **not** prevent attempting the throw.
- **Hand-off:** target already adjacent → automatic success (no Throw Check).
- Throw Check (see Checks). Subtract **1** from the result if **either** applies (still capped at −1 total):
  - **Long Throw**
  - **Obstructed:** target is Marked, **or** ruler path between thrower and target crosses a blocked square or a square with an **Open** opposition player
- Success → target gains possession.
- Fail → bounce from the **target’s** square — **except Fumble**.
- **Fumble:** if the Throw Check result is **1 before or after modifiers**, bounce from the **thrower’s** square instead.

---

## Throw Team-mate

- An **Open** Ogre, Troll, or Oakwalker who does **not** hold a ball may throw an **adjacent standing** Team-mate of a **different position** (Open or Marked).
- Counts as a Throw Action for challenge claims. Ice Cold may allow it while Marked.
- Pick a landing square in Throw Ruler range, then make a Throw Check (same Long / path-obstruction −1 as Throw; never a Hand-off).
- **Success + empty square:** thrown Player lands Standing (keeps any ball).
- **Fail + empty square:** lands Prone (no Armour Check); held ball bounces from the target.
- **Occupied (not Fumble):** occupant Prone + Armour Check (−1 if the Throw Check succeeded); their ball bounces from the target; thrown Player is Injured (ball bounces from the target).
- **Fumble:** thrown Player Injured; their ball bounces from the thrower’s square.
- Landing on a loose ball → that ball bounces from the target.

---

## Foul

- An **Open** player adjacent to a **Prone** opposition player may Foul that opponent.
- Roll **one** Block Die and apply the Foul result for that face (same physical die as Block):

| Block face | Foul result | Effect |
|------------|-------------|--------|
| **Miss** | **Spotted!** | Target unaffected. Fouler ejected to Dugout (Reserves). |
| **Tackle** | **Take That!** | Target Injured (Dugout). Fouler also Spotted (ejected). |
| **Shove** | **Slipped!** | Target unaffected. Fouler cannot make any more actions (including free) this turn. |
| **Smash** or **Flatten!** | **Got Them!** | Target Injured (Dugout). |

- Injury from Foul is immediate (no Armour Check). Held balls bounce from the injured/ejected player’s square before they leave the pitch.
- Challenge **Send Them Off!** counts Block **or** Foul that Injures the target.
- Optional Random Event **Blind Ref** (D4, [`cards.md`](cards.md)): remap Spotted→Slipped and Take That→Got Them for the rest of the game.

---

## Bounce

1. Roll **D8**; use the board Bounce Chart for the adjacent square.
2. **Empty** → ball stays loose there.
3. **Open** player → that player gains possession.
4. **Marked** or **Prone** player → bounce again from that square.
5. Obstructed square or off the board → try the next **clockwise** Bounce Chart result (1–8) until a legal on-pitch non-obstructed square.

### New ball

During **No Ball in Play** (pre-turn): if no ball is on the pitch, a ball emerges from a trapdoor (only one trapdoor → that one; multiple → choose randomly).

- Player standing on that trapdoor → immediately **Injured** (to dugout); if they held a ball, it is removed with them.
- Another ball already on that trapdoor → remove it from play.
- Place the new ball on the trapdoor square → it **immediately bounces**.

Multiple balls: one ball per square; one ball per carrier; extras bounce as above.

---

## Block

- Choose an Opposition Player you are **Marking**; roll Block Dice equal to Block value.
- Multiple dice → attacker chooses **one** face to apply.
- **Assisted:** if the target is also Marked by ≥1 of the blocker’s Team-mates, roll **+1** Block Die, then choose one result.

| Face | Effect |
|------|--------|
| **Smash** | Knock Down → Prone; held ball bounces; then Armour Check (success → stay Prone; fail → **Injured** → dugout as Reserves) |
| **Flatten!** | As Smash; Armour Check result at **−1** (min 1) |
| **Shove** | Push 1 square **directly away** if possible; blocker may follow-up into vacated square. If push would enter occupied/blocked/off-board → Knock Down (Smash) instead |
| **Tackle** | Knock Down (Smash); blocker **cannot make any more Actions this turn**, including free actions |
| **Miss** | No effect; blocker **cannot make any more Actions this turn**, including free actions |

(Die faces include Shove ×2 among the standard set.)

---

## Touchdown

After completing a Player Action (before Claim Challenge Card when cards are in play), if a player from **your** team:

- is in a square in the **other team’s End Zone**,
- is in possession of a ball, and
- is **Open**,

then: that ball is removed from play, **+4** points, scorer to dugout (**Reserves**). Remaining actions in the turn may still be taken.

**Engine order (intentional):** one touchdown check per turn-slot beat, after free follow-ups and after-own bonuses (`EXECUTE_AFTER_OWN` → `TOUCHDOWN` → claim) — not immediately after `COMPLETE`, and not again after each add-on.

---

## Turn sequence

Coach who set up first is **Coach A** (takes first turn); other is Coach B. Alternate until the game ends.

Each turn:

1. **Sudden Death check** — at the **start** of the turn, **before** Pre-Turn: if you lead by **≥10**, you win immediately.
2. **Pre-Turn Sequence** — **skipped on each coach’s first turn** (both A and B):
   1. Refresh Challenge Cards
   2. No Ball in Play / New Ball
   3. Emergency Reserves — if dugout count **≥** team ER, may make **one free** Reserves Action
3. **Three Player Actions**, each followed by Claim Challenge Card when cards are active.
4. Opponent’s turn.

Extra: Challenge Cards **cannot be claimed during Coach A’s first turn**.

### Victory

| Condition | Result |
|-----------|--------|
| Sudden Death (≥10 lead at start of your turn, before Pre-Turn) | You win |
| Touchdown | +4 points |
| Challenge claims / Clean Sweep (+2) / bonus plays | Per card text ([`cards.md`](cards.md)) |
| Points Victory | After the **last** Challenge Card is dealt and becomes active, **each team gets one more turn**, then highest score wins; equal = draw |

Score track uses marker + team coin at +10/+20/+30; the engine may store a single integer per team until the full track is needed.

---

## Possession summary

A player takes possession when:

- They move onto a ball during a **Run**,
- They are the target of a successful Throw or Hand-off,
- A ball bounces into their square and they are **Open**,
- Or an ability / Bonus Play says so.

---

## Deferred / not summarized here

Encode when those phases start; do not invent from memory:

- Special balls, optional arenas / Random Events (incl. Blind Ref foul remapping)
- Full challenge refresh / hand-limit / bonus-play timing beyond card text + the turn-sequence outline above
- Diagrams (bounce rose, throw ruler art, setup maps) — a human verifies against the physical game if needed
