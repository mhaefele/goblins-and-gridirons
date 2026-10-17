package com.mhaefele.gridiron.game.action.pending.sidestep

import com.mhaefele.gridiron.api.dto.response.LegalAction
import com.mhaefele.gridiron.game.action.pending.PendingDecisionLegalMovesGenerator
import com.mhaefele.gridiron.game.legalmoves.playIfPresent
import com.mhaefele.gridiron.model.MatchState
import com.mhaefele.gridiron.model.catalog.BonusPlay
import com.mhaefele.gridiron.model.pending.SidestepContinuation
import org.springframework.stereotype.Component

/** Legal moves for [SidestepContinuation]. */
@Component
class SidestepLegalMovesGenerator : PendingDecisionLegalMovesGenerator<SidestepContinuation>() {
    override fun generate(
        state: MatchState,
        pending: SidestepContinuation,
    ): List<LegalAction> {
        val ongoing = pending.ongoingSidestep
        return when (ongoing.stage) {
            OngoingSidestep.SidestepStage.HANDLE_STICK_CLOSE -> {
                val coach = checkNotNull(ongoing.respondingCoach) { "HANDLE_STICK_CLOSE missing respondingCoach" }
                playIfPresent(state.cards, coach, state.currentTurnState, BonusPlay.`Stick Close`) + LegalAction.DeclineBonus
            }
            OngoingSidestep.SidestepStage.OFFER_STICK_CLOSE,
            OngoingSidestep.SidestepStage.DONE,
            -> error("SidestepContinuation legal moves unexpected stage ${ongoing.stage}")
        }
    }
}
