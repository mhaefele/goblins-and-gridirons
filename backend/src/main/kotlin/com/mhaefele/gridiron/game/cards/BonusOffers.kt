package com.mhaefele.gridiron.game.cards

import com.mhaefele.gridiron.game.turn.MatchMutator
import com.mhaefele.gridiron.model.catalog.BonusPlay
import com.mhaefele.gridiron.shared.TeamSide

/**
 * Named bonus-offer eligibility — one check per pause in the turn/action FSM.
 * Phase offers: callers [MatchMutator.setPendingDecision] via the matching resolver's `begin`
 * (resolvers never write the slot). Ongoing* stages use [shouldOffer*] / [hasPlayable] only.
 *
 * Callers own “offer once” (TurnEngine advances past the offer step); do not peek the pending slot here.
 */
object BonusOffers {
    /** In hand and unused this turn (ready to offer / attach). */
    fun hasPlayable(session: MatchMutator, coach: TeamSide, bonus: BonusPlay): Boolean {
        if (bonus in session.bonusPlaysUsedThisTurn) return false
        return session.cardState.hasBonusInHand(coach, bonus)
    }

    fun canOfferGangUp(session: MatchMutator): Boolean =
        hasPlayable(session, session.activeCoach, BonusPlay.`Gang Up`)

    fun canOfferHoldTheLine(session: MatchMutator): Boolean =
        hasPlayable(session, session.activeCoach, BonusPlay.`Hold the Line`)

    fun canOfferExtraEffort(session: MatchMutator): Boolean =
        hasPlayable(session, session.activeCoach, BonusPlay.`Extra Effort`)

    fun shouldOfferBeforeThrowCheck(session: MatchMutator): Boolean {
        val coach = session.activeCoach
        return hasPlayable(session, coach, BonusPlay.`Dead Eye`) || hasPlayable(session, coach, BonusPlay.`Hail Mary Pass`)
    }

    fun shouldOfferBrace(session: MatchMutator, targetTeam: TeamSide): Boolean =
        hasPlayable(session, targetTeam, BonusPlay.Brace)

    fun shouldOfferUnderdog(session: MatchMutator): Boolean =
        hasPlayable(session, session.activeCoach, BonusPlay.Underdog)

    fun shouldOfferSpikedGauntlets(session: MatchMutator): Boolean =
        hasPlayable(session, session.activeCoach, BonusPlay.`Spiked Gauntlets`)
}
