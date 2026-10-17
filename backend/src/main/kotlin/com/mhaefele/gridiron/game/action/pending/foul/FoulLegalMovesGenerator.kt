package com.mhaefele.gridiron.game.action.pending.foul

import com.mhaefele.gridiron.api.dto.response.LegalAction
import com.mhaefele.gridiron.game.action.pending.PendingDecisionLegalMovesGenerator
import com.mhaefele.gridiron.model.MatchState
import com.mhaefele.gridiron.model.pending.FoulContinuation
import org.springframework.stereotype.Component

/** Legal moves for [FoulContinuation] — finished at DONE; TurnEngine should COMPLETE. */
@Component
class FoulLegalMovesGenerator : PendingDecisionLegalMovesGenerator<FoulContinuation>() {
    override fun generate(
        state: MatchState,
        pending: FoulContinuation,
    ): List<LegalAction> =
        error("FoulContinuation at DONE has no legal moves — TurnEngine should COMPLETE")
}
