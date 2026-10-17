package com.mhaefele.gridiron.game.cards

import com.mhaefele.gridiron.game.turn.MatchMutator
import com.mhaefele.gridiron.model.OpenPlayerAction
import com.mhaefele.gridiron.model.pending.ReplaceChallenge
import com.mhaefele.gridiron.model.pending.ClaimChallenge

/**
 * Challenge turn beats for D1.B — refresh/replace, claim, hand limit.
 * Does **not** write [MatchMutator] pending — callers [MatchMutator.setPendingDecision] when a
 * replace/claim offer is returned.
 *
 * **Coach choice (D1.B.choice):**
 * - Claim: when ≥2 actives are eligible, return [ClaimChallenge]; single eligible still auto
 * - Forced replace: return true from [refreshAndMaybeOfferReplace]; caller parks [ReplaceChallenge]
 */
object ChallengeTurnSteps {
    /**
     * Pre-Turn Refresh, then signal forced Replace when the tableau was already full
     * (no empty slots to fill — coach must discard one and draw).
     * @return true when the caller should park a [ReplaceChallenge] (pause Pre-Turn).
     */
    fun refreshAndMaybeOfferReplace(session: MatchMutator): Boolean {
        val alreadyFull = session.cardState.activeSlots.all { it != null }
        session.refreshChallengeTableau()
        return alreadyFull
    }

    /**
     * After a turn-slot Player Action (budgeted / Extra Effort) and its add-on chain, or a
     * standalone free action (Back on Your Feet, Reserves bonus). [completed] is the claim-subject
     * (often an add-on) whose outcomes fulfill the card; add-ons do not get their own claim step.
     * No-op on Coach A's first turn.
     *
     * @return [ClaimChallenge] when ≥2 slots are eligible (caller parks it);
     *   null when nothing to offer or a single eligible was auto-claimed.
     */
    fun maybeClaimAfterAction(session: MatchMutator, completed: OpenPlayerAction): ClaimChallenge? {
        if (isCoachAFirstTurn(session)) return null
        // Add-ons nest under the turn-slot action; current is never an add-on here.

        val team = session.activeCoach
        val facts = ClaimFactsFromAction.build(
            completed = completed,
            team = team,
            board = session.board.board,
            events = session.actionBracketEvents(),
            ballsHeldAtActionStart = session.ballsHeldAtActionStart(),
        )
        val turnStats = TurnClaimStats(
            actionsThisTurn = session.actionsThisTurn,
            ballsPossessedAtTurnStart = session.cardState.ballsPossessedAtTurnStart,
        )
        val eligible = ClaimEligibility.eligibleCards(
            activeCards = session.cardState.activeCards(),
            facts = facts,
            turnStats = turnStats,
            board = session.board.board,
        )
        if (eligible.isEmpty()) return null

        val eligibleSlots = eligible.map { card ->
            val slot = session.cardState.activeSlots.indexOfFirst { it == card }
            check(slot >= 0) { "Eligible card ${card.id.value} not in active slots" }
            slot
        }.distinct().sorted()

        if (eligibleSlots.size >= 2) {
            return ClaimChallenge(eligibleSlotIndexes = eligibleSlots)
        }

        applyClaim(session, team, eligibleSlots.single())
        return null
    }

    /** Resolve a coach-picked claim slot (Clean Sweep if third claim). */
    fun applyClaim(session: MatchMutator, team: com.mhaefele.gridiron.shared.TeamSide, slotIndex: Int) {
        session.claimChallengeCard(team, slotIndex)
        if (session.cardState.claimsThisTurn == 3) {
            session.awardCleanSweep(team)
        }
    }

    /** End of active coach's turn — hand limit then clear claim counters. */
    fun endOfTurnCleanup(session: MatchMutator) {
        session.trimChallengeHand(session.activeCoach)
        session.resetClaimsThisTurn()
    }

    fun openActionWindow(session: MatchMutator) {
        session.snapshotBallsPossessedAtTurnStart(session.activeCoach)
    }

    private fun isCoachAFirstTurn(session: MatchMutator): Boolean =
        session.turnNumber == 1 && session.activeCoach == session.config.coachA
}
