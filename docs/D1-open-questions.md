# D1 open questions (review later)

Captured while implementing Phase D1. No blocking decisions requested at implementation time — choices below are what landed (or was deferred) and why.

## D1.C — Scoring & Points Victory

1. **D1.C.1 claim→score** — Already shipped in D1.B (`MatchMutator.claimChallengeCard` / `awardCleanSweep`). Marked done; no rework.
2. **D1.C.2 score-track coins** — Skipped as optional polish (integer score remains source of truth).
3. **When does “last card dealt” arm Points Victory?** — When a draw from the deck into an active slot leaves the deck empty (Refresh or Replace). Initial setup deal does not arm (deck still has cards).
4. **What counts as “one more turn each”?** — Armed with `pointsVictoryTurnsRemaining = 2` at Pre-Turn draw time; decremented when each coach closes their action window. The coach whose Pre-Turn dealt the last card uses one of those two turns immediately.
5. **`turnLimit` removed** — Invented stand-in deleted once Points Victory shipped. Matches end only via Sudden Death or Points Victory.
6. **`MatchEnded` wire shape** — Replaced boolean `bySuddenDeath` with `endKind` (`suddenDeath` | `pointsVictory`) so the event matches how we talk about results. Clients must fold on `endKind`.

## D1.B.choice — Coach pick claim / replace

**Shipped.** Multi-eligible claim → `PendingDecision.ClaimChallenge` + `LegalAction.ClaimChallenge`; forced Pre-Turn replace → `ReplaceChallenge`. Single eligible claim still auto. Hand trim still oldest-first. Bot: highest points / lowest slot.

## D1.D–E — Bonus framework / effects

1. **BonusPlayWindow** — Team-level pending (like EmergencyReserves), not PlayerSpecificAction. Optional Skip + PlayBonus per eligible type.
2. **One type per turn** — Tracked on `TurnState.bonusPlaysUsedThisTurn` (cleared at coach hand-off). Duplicate copies in hand → one PlayBonus offer.
3. **Effects** — `BonusPlayEffectApplier` + `TurnBonusModifiers` on `TurnState`.
4. **Do-Over** — Block, throw, and armour AfterDice windows; re-roll at play site.
5. **Cannot interrupt an interrupt** — `PendingDecision.isInterrupt()` + guard in `BonusPlayWindows.offer`.
6. **Bot** — Plays useful own-turn bonuses via `ChallengePursuit`; Skips interrupts / unknown.
7. **Do-Over vs Second Swing** — Bonus window queued in front of OS reroll; Skip then OS.
8. **Back on Your Feet** — Reuses `FreeAbilityGrant(RALLY)` legal-move shape for free Stand Up of first prone teammate.
9. **Shipped modifiers / effects:** Sprint, Weave, Hold the Line, Gang Up, Ice Cold, Dead Eye, Press, Underdog, Hail Mary, Extra Effort, Reserves, Back on Your Feet, Do-Over (block/throw/armour), Momentum, All-In Tackle, Bladed Knuckle, Brace, Slip Away, Cut Off (runner-adjacency), Stick Close (mid-Sidestep).
10. **Still open / parked:**
    - **Devoted** coach choice (auto-redirect remains).
    - Opponent hand count UI (seat-private hand only today).
    - ~~Per-card face art~~ — shipped (`CHALLENGE_FACES` + tableau).

## D1.F — API / GUI

1. **Seat-private hand** — `GET /api/matches/{id}/bonus-hand?seat=` shipped.
2. **Snapshot** — Public tableau + Points Victory flag on `MatchGameStateDto.challenges`.
3. **Tableau + hand UI** — Shipped (back-plate art; per-card faces still `challengeCardFace` → back).
4. **OpenAPI regen** — Regenerated with Enforcer / F.10 / D1.E wire.
5. **F.10 card/ability tags on events** — Optional `ability` / `bonusPlay` on DiceRolled, BallPickedUp, PlayerMoved.
6. **Opponent hand as count only** — Not shown in UI yet (hand is seat-private only).

## D1.G — Thirst / Devoted

1. **Thirst** — After claim step, Miss on block / fail|fumble on throw → drop ball + bounce + injure.
2. **Devoted** — Auto-redirects injury to the first adjacent Devoted teammate. **Coach choice not offered**.

## D1.H — Challenge-aware bot

Shipped shallow `ChallengePursuit` + unit tests (`ChallengePursuitTest`).