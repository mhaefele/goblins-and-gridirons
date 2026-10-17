package com.mhaefele.gridiron.game.action.pending.reserves

import com.mhaefele.gridiron.api.dto.response.LegalAction
import com.mhaefele.gridiron.game.action.pending.PendingDecisionLegalMovesGenerator
import com.mhaefele.gridiron.game.legalmoves.playIfPresent
import com.mhaefele.gridiron.model.MatchState
import com.mhaefele.gridiron.model.catalog.BonusPlay
import com.mhaefele.gridiron.model.pending.ReservesContinuation
import org.springframework.stereotype.Component

/** Legal moves for [ReservesContinuation] (Reserves bonus offer). */
@Component
class ReservesLegalMovesGenerator : PendingDecisionLegalMovesGenerator<ReservesContinuation>() {
    override fun generate(
        state: MatchState,
        pending: ReservesContinuation,
    ): List<LegalAction> {
        check(pending.ongoingReserves.stage == OngoingReserves.ReservesStage.HANDLE_FRESH_LEGS_BONUS) { "ReservesContinuation legal moves unexpected stage: ${pending.ongoingReserves.stage}" }
        val turn = state.currentTurnState
        return playIfPresent(state.cards, turn.activeCoach, turn, BonusPlay.`Fresh Legs`) + LegalAction.DeclineBonus
    }
}
