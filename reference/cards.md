# Cards — challenge deck, bonus plays, random events

Tracked card reference for the engine, in our own words. The Kotlin catalogs are the source of truth:
[`ChallengeCard.kt`](../backend/src/main/kotlin/com/mhaefele/gridiron/model/catalog/ChallengeCard.kt) (claim / bonus text)
and [`ChallengeCardCatalog.kt`](../backend/src/main/kotlin/com/mhaefele/gridiron/model/catalog/ChallengeCardCatalog.kt) (pairings).
The tables below were generated from them; `ChallengeCardCatalogTest` guards the counts and pairings.
Core rules: [`rulebook-digest.md`](rulebook-digest.md).

## Deck composition

26 cards. Each card has a challenge face and a bonus play on the back.

| Challenge | Points | Copies |
|---|---|---|
| Grab It! | 1 | 2 |
| Huddle Up! | 1 | 1 |
| Push Forward! | 1 | 2 |
| Fan Out! | 1 | 1 |
| Play to the Stands! | 1 | 1 |
| Connect! | 1 | 2 |
| Strip It! | 2 | 2 |
| Keep Running! | 2 | 2 |
| Hold the Pit! | 2 | 1 |
| Thread the Needle! | 2 | 1 |
| All Hands! | 2 | 3 |
| Star Turn! | 2 | 2 |
| Drop Them! | 2 | 2 |
| Lock Them Down! | 2 | 2 |
| Send Them Off! | 3 | 2 |
| **Total** | | **26** |

## Challenge faces (claim text)

- **Grab It!** (1 pt) — Claim if one of your players gained possession of a ball that your team did not hold when your turn began.
- **Huddle Up!** (1 pt) — Claim after one of your players makes a Run, Mark or Sidestep Action and ends next to at least two team-mates.
- **Push Forward!** (1 pt) — Claim if, during one of your Throw or Run Actions, a ball moved at least 4 squares farther from your own End Zone and one of your players still has it.
- **Fan Out!** (1 pt) — Claim after one of your players makes a Run or Sidestep Action if none of your players is next to any team-mate.
- **Play to the Stands!** (1 pt) — Claim if an Action by one of your players led to your team scoring a Touchdown.
- **Connect!** (1 pt) — Claim if one of your players completed a Throw Action (Hand-offs don't count).
- **Strip It!** (2 pts) — Claim if an opponent held a ball and, after one of your players' Actions, the opposing team no longer has it.
- **Keep Running!** (2 pts) — Claim if one of your players made a Run Action and your team has now made at least three Run Actions this turn.
- **Hold the Pit!** (2 pts) — Claim if one of your players finishes an Action on a Trapdoor while an opponent holds a ball.
- **Thread the Needle!** (2 pts) — Claim if one of your players completed a Throw Action despite a negative modifier on the Throw Check.
- **All Hands!** (2 pts) — Claim if, this turn, your team has made at least three different kinds of Action using at least three different players.
- **Star Turn!** (2 pts) — Claim if one of your players made an Action and has now made at least three different Player Actions this turn (free Actions don't count).
- **Drop Them!** (2 pts) — Claim if one of your players made a Block Action that Knocked the target Down.
- **Lock Them Down!** (2 pts) — Claim if one of your players made a Mark Action and is now Marking two or more opponents that no other player of yours is Marking.
- **Send Them Off!** (3 pts) — Claim if a Block or Foul Action by one of your players Injured the target.

## Bonus plays (effect text)

| Bonus play | Copies | Effect |
|---|---|---|
| Stick Close | 1 | Interrupt an opponent's Sidestep Action. Choose one of your players who was Marking that opponent before the Sidestep; move them into the square the opponent just left, next to the opponent. |
| Slip Away | 2 | Interrupt right after an opponent's Mark Action. Choose one of your players that opponent is now Marking; they may take a free Sidestep Action. |
| Fresh Legs | 2 | Before one of your Player Actions, a player from your Dugout may take a free Reserves Action. |
| Weave | 1 | Before one of your Run Actions: during that Run the player may pass next to opponents, but must still finish Open. |
| Do-Over | 3 | Right after your team rolls an Armour Check, a Throw Check, or the Block Dice for one of your Block Actions: re-roll those dice. |
| Hail Mary Pass | 1 | When one of your players takes a Throw Action (not Throw Team-mate): any team-mate on the board can be the target, and the Throw Check passes on 4+ with no modifiers. |
| Extra Effort | 2 | After the Claim Challenge Card step of your third Player Action: take a fourth Player Action. It is still part of your turn and uses all the normal rules for Actions and claiming Challenge Cards. |
| Ice Cold | 1 | Before one of your Player Actions: for that Action only, the player may Throw (including Throw Team-mate) while Marked. |
| Momentum | 1 | After one of your players makes a Mark Action, if they already took a Run Action this turn: they may take a free Block Action against an opponent they are Marking. |
| Cut Off | 1 | Interrupt right after an opponent's Run Action. One of your Open players may take a free Mark Action, which must end next to the opponent who ran. |
| Sprint | 1 | Before one of your Run Actions: the player gets +2 Move for that Run. |
| Press | 2 | Before one of your Mark Actions: for that Action the player may move up to 4 squares rather than 2. |
| Back on Your Feet | 1 | Choose one of your Prone players; they may take a free Stand Up Action. |
| Dead Eye | 1 | Before one of your Throw Actions: the thrower's Throw value counts as 2+ for that Throw. |
| Brace | 1 | Interrupt when one of your players is picked as a Block target, before the dice are rolled: that Block can't be Assisted, and Shove results count as Miss. |
| Spiked Gauntlets | 1 | After an opponent is Knocked Down by a Block Action: their Armour value is 6+ for that Armour Check. |
| Underdog | 1 | When one of your players makes an unassisted Block Action: roll 3 Block Dice for it. |
| All-In Tackle | 1 | After one of your players makes a Mark Action: they may take a free Block Action, then are placed Prone (no Armour Check for them). |
| Hold the Line | 1 | After your Pre-Turn Sequence, before your first Player Action: this turn your players may Run into squares next to opponents, but doing so ends that Run immediately. |
| Gang Up | 1 | At the start of your turn, before the Pre-Turn Sequence: every Block Action your players make this turn counts as Assisted. |

## Pairings (deck index)

Card instance ids are `c01`–`c26` (`ChallengeCardCatalog`).

| # | Challenge | Bonus play |
|---|---|---|
| 01 | Grab It! | Stick Close |
| 02 | Grab It! | Slip Away |
| 03 | Huddle Up! | Fresh Legs |
| 04 | Push Forward! | Weave |
| 05 | Push Forward! | Do-Over |
| 06 | Fan Out! | Fresh Legs |
| 07 | Play to the Stands! | Hail Mary Pass |
| 08 | All Hands! | Press |
| 09 | Connect! | Extra Effort |
| 10 | Connect! | Ice Cold |
| 11 | Strip It! | Momentum |
| 12 | Strip It! | Cut Off |
| 13 | Keep Running! | Sprint |
| 14 | Keep Running! | Press |
| 15 | Hold the Pit! | Back on Your Feet |
| 16 | Thread the Needle! | Dead Eye |
| 17 | All Hands! | Brace |
| 18 | All Hands! | Extra Effort |
| 19 | Star Turn! | Spiked Gauntlets |
| 20 | Star Turn! | Underdog |
| 21 | Drop Them! | Gang Up |
| 22 | Drop Them! | All-In Tackle |
| 23 | Lock Them Down! | Do-Over |
| 24 | Lock Them Down! | Hold the Line |
| 25 | Send Them Off! | Slip Away |
| 26 | Send Them Off! | Do-Over |

## Rules reminders (for implementers)

- The first coach's first turn has no claim, no refresh and no replace.
- After each **turn-slot** Player Action (budgeted actions, the fourth action from Extra Effort, a standalone Back on Your Feet / Fresh Legs free action) comes the Claim Challenge Card step: at most one claim, and its condition must be met at least in part by the action just finished — including results of **add-on** free actions in the same beat (e.g. a Relentless free Block).
- **Add-on** free actions (Relentless, Strike and Fade, Momentum, Catch and Go, …) get no claim step of their own.
- Free actions taken as an **interrupt on the opponent's turn** never claim (it is not the interrupter's turn).
- Pre-turn refresh: fill empty active slots from the deck. If all three slots are still filled (and it is not either coach's first turn), the active coach **must** discard one and replace it from the deck.
- Clean Sweep: claiming all three active challenges in one turn scores +2.
- Hand limit: at the end of a turn, discard down to 3 bonus plays (cards already played in front of you don't count).
- A coach can't play two copies of the **same bonus play** in one turn.
- Interrupt: pause the current action step, resolve the interrupt, then resume. An interrupt can't be interrupted.
- Points victory: once the last challenge becomes active, each team gets one more turn and the higher score wins (tie = draw). Sudden death (a lead of 10+ at the start of a turn) still applies.

## Random events (optional rule — not implemented)

Spec for deferred Phase D4. When used, the 12 event cards are shuffled into the challenge deck; a revealed event resolves immediately and never becomes an active challenge. "Rest of the game" effects stay beside the pitch as reminders.
Cross-refs: new ball, multiple balls in play and Foul results are in [`rulebook-digest.md`](rulebook-digest.md).

| Event | Copies | Effect |
|---|---|---|
| Cracked Turf | 1 | Rest of the game: every Armour Check caused by a Block Action takes −1. |
| Ball Rush | 2 | If a ball is in play, add one new ball; if none is, add two. Use the new-ball and multiple-ball rules. |
| Pit Lurker | 1 | Pick a trapdoor at random. A player standing on it is Injured; every Standing or Marked player next to it goes Prone (no Armour Check). Any ball they held Bounces. |
| Shake-Up | 2 | Discard every active challenge, then deal three fresh ones from the deck. |
| Slippery Patch | 1 | Each coach picks one Standing, un-Marked opponent (if any); that player goes Prone with no Armour Check, and a ball they held Bounces. |
| Blind Ref | 1 | Rest of the game: on Foul Actions, Spotted! counts as Slipped! and Take That! counts as Got Them!. *(Source line was partly unreadable — confirm before building.)* |
| Crowd Pleaser | 1 | Rest of the game: each claimed challenge and each Touchdown scores 1 extra point. |
| Fresh Bodies | 1 | Every player in the Dugout may take a free Reserves Action. |
| Stampede | 1 | Each coach rolls one Block Die for every Standing, Marked or Prone opposing player. On Flatten! that player is Injured and goes to the Dugout (a held ball Bounces); other faces do nothing. |
| Restart | 1 | Starting with the coach whose turn it is, both coaches move every player (pitch and Dugout) back to their End Zone using the setup rules. Play then resumes with the same coach. |
| **Total** | **12** | |
