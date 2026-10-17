package com.mhaefele.gridiron.game.action.pending.standup

import com.mhaefele.gridiron.api.dto.response.LegalAction
import com.mhaefele.gridiron.game.action.pending.PendingDecisionLegalMovesGenerator
import com.mhaefele.gridiron.game.legalmoves.playIfPresent
import com.mhaefele.gridiron.model.MatchState
import com.mhaefele.gridiron.model.catalog.BonusPlay
import com.mhaefele.gridiron.model.pending.StandUpContinuation
import org.springframework.stereotype.Component

/** Legal moves for [StandUpContinuation] (Back on Your Feet offer). */
@Component
class StandUpLegalMovesGenerator : PendingDecisionLegalMovesGenerator<StandUpContinuation>() {
    override fun generate(
        state: MatchState,
        pending: StandUpContinuation,
    ): List<LegalAction> {
        check(pending.ongoingStandUp.stage == OngoingStandUp.StandUpStage.HANDLE_BACK_ON_YOUR_FEET) { "StandUpContinuation legal moves unexpected stage: ${pending.ongoingStandUp.stage}" }
        val turn = state.currentTurnState
        return playIfPresent(state.cards, turn.activeCoach, turn, BonusPlay.`Back on Your Feet`) + LegalAction.DeclineBonus
    }
}
