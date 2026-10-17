package com.mhaefele.gridiron.game.action.pending.undying

import com.mhaefele.gridiron.api.dto.response.LegalAction
import com.mhaefele.gridiron.game.action.pending.PendingDecisionLegalMovesGenerator
import com.mhaefele.gridiron.game.legalmoves.standUpMove
import com.mhaefele.gridiron.model.MatchState
import com.mhaefele.gridiron.model.pending.OfferUndying
import org.springframework.stereotype.Component

/** Legal moves for [OfferUndying]. */
@Component
class OfferUndyingLegalMovesGenerator : PendingDecisionLegalMovesGenerator<OfferUndying>() {
    override fun generate(
        state: MatchState,
        pending: OfferUndying,
    ): List<LegalAction> {
        val player = state.board.player(pending.playerId)
        val actions = standUpMove(player, state.board)
        return if (pending.optional) actions + LegalAction.Skip(player.id) else actions
    }
}
