package com.mhaefele.gridiron.game.action.pending.reserves

import com.mhaefele.gridiron.game.action.BoardMovement
import com.mhaefele.gridiron.api.dto.response.LegalAction
import com.mhaefele.gridiron.game.cards.BonusOffers
import com.mhaefele.gridiron.game.turn.MatchMutator
import com.mhaefele.gridiron.model.pending.ReservesContinuation
import com.mhaefele.gridiron.model.catalog.BonusPlay
import org.springframework.stereotype.Component
import com.mhaefele.gridiron.game.action.pending.PendingDecisionResolver

/**
 * Reserves open action (budgeted RegularPlayerAction). Bonus Reserves nests on
 * [ReservesStage.HANDLE_FRESH_LEGS_BONUS]. Emergency / initial placement are owned by their handlers.
 */
@Component
class ReservesResolver(
    private val boardMovement: BoardMovement,
) : PendingDecisionResolver<ReservesContinuation>() {
    fun beginReserves(session: MatchMutator, action: LegalAction.Reserves): ReservesContinuation {
        // TODO(pending): slot-return for the bonus offer is still RPA-scoped work that feels outside
        // a pure Reserves FSM (same smell as the old when-on-pending).
        return if (shouldOfferFreshLegsBonus(session, action)) {
            session.returnRegularActionSlot()
            ReservesContinuation(
                OngoingReserves(
                    action.playerId,
                    action.placement,
                    action.ballEncounter,
                    OngoingReserves.ReservesStage.HANDLE_FRESH_LEGS_BONUS,
                ),
            )
        } else {
            boardMovement.applyReservesPlacement(session.board, action)
            ReservesContinuation(
                OngoingReserves(
                    action.playerId,
                    action.placement,
                    action.ballEncounter,
                    OngoingReserves.ReservesStage.DONE,
                ),
            )
        }
    }

    override fun mutate(session: MatchMutator, pending: ReservesContinuation, command: LegalAction): ReservesContinuation {
        val ongoing = pending.ongoingReserves
        check(ongoing.stage == OngoingReserves.ReservesStage.HANDLE_FRESH_LEGS_BONUS) { "ReservesContinuation mutate unexpected stage: ${ongoing.stage}" }
        when (command) {
            is LegalAction.DeclineBonus -> session.consumeRegularActionSlot()
            is LegalAction.PlayFreshLegs -> {
                session.playBonusCard(session.activeCoach, command.bonusPlay)
                session.markCurrentPlayerActionFree()
            }
            else                        -> error("HANDLE_FRESH_LEGS_BONUS expects PlayFreshLegs or DeclineBonus, was ${command::class.simpleName}")
        }
        boardMovement.applyReservesPlacement(
            session.board,
            LegalAction.Reserves(ongoing.playerId, ongoing.placement, ballEncounter = ongoing.ballEncounter),
        )
        return ReservesContinuation(
            OngoingReserves(
                ongoing.playerId,
                ongoing.placement,
                ongoing.ballEncounter,
                OngoingReserves.ReservesStage.DONE,
            ),
        )
    }

    private fun shouldOfferFreshLegsBonus(session: MatchMutator, action: LegalAction.Reserves): Boolean {
        if (action.availableBonusCards.none { it.bonusPlay == BonusPlay.`Fresh Legs` }) return false
        return BonusOffers.hasPlayable(session, session.activeCoach, BonusPlay.`Fresh Legs`)
    }
}
