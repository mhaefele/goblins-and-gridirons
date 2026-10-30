package com.mhaefele.gridiron.game.action.pending.standup

import com.mhaefele.gridiron.game.action.BoardMovement
import com.mhaefele.gridiron.api.dto.response.LegalAction
import com.mhaefele.gridiron.game.cards.BonusOffers
import com.mhaefele.gridiron.game.turn.MatchMutator
import com.mhaefele.gridiron.model.pending.StandUpContinuation
import com.mhaefele.gridiron.model.catalog.BonusPlay
import org.springframework.stereotype.Component
import com.mhaefele.gridiron.game.action.pending.PendingDecisionResolver

/**
 * Stand Up open action (budgeted RegularPlayerAction). Back on Your Feet nests on [StandUpStage.HANDLE_BACK_ON_YOUR_FEET].
 * Pre-turn free Stand Up is owned by OfferUndyingResolver.
 */
@Component
class StandUpResolver(
    private val boardMovement: BoardMovement,
) : PendingDecisionResolver<StandUpContinuation>() {
    fun beginStandUp(session: MatchMutator, action: LegalAction.StandUp): StandUpContinuation {
        return if (shouldOfferBackOnYourFeet(session, action)) {
            session.returnRegularActionSlot()
            StandUpContinuation(
                OngoingStandUp(action.playerId, action.targetCoordinate, OngoingStandUp.StandUpStage.HANDLE_BACK_ON_YOUR_FEET),
            )
        } else {
            boardMovement.applyStandUp(session.board, action)
            StandUpContinuation(
                OngoingStandUp(action.playerId, action.targetCoordinate, OngoingStandUp.StandUpStage.DONE),
            )
        }
    }

    override fun mutate(session: MatchMutator, pending: StandUpContinuation, command: LegalAction): StandUpContinuation {
        val ongoing = pending.ongoingStandUp
        check(ongoing.stage == OngoingStandUp.StandUpStage.HANDLE_BACK_ON_YOUR_FEET) { "StandUpContinuation mutate unexpected stage: ${ongoing.stage}" }
        when (command) {
            is LegalAction.DeclineBonus       -> session.consumeRegularActionSlot()
            is LegalAction.PlayBackOnYourFeet -> {
                // TODO: can we make this require a card and follow that path instead of implicitly playing the card based on the action?
                session.playBonusCard(session.activeCoach, command.bonusPlay)
                session.markCurrentPlayerActionFree()
            }
            else                              -> error("HANDLE_BACK_ON_YOUR_FEET expects PlayBackOnYourFeet or DeclineBonus, was ${command::class.simpleName}")
        }
        boardMovement.applyStandUp(
            session.board,
            LegalAction.StandUp(ongoing.playerId, ongoing.targetCoordinate),
        )
        return StandUpContinuation(
            OngoingStandUp(ongoing.playerId, ongoing.targetCoordinate, OngoingStandUp.StandUpStage.DONE),
        )
    }

    private fun shouldOfferBackOnYourFeet(session: MatchMutator, action: LegalAction.StandUp): Boolean {
        if (action.availableBonusCards.none { it == BonusPlay.`Back on Your Feet` }) return false
        return BonusOffers.hasPlayable(session, session.activeCoach, BonusPlay.`Back on Your Feet`)
    }
}
