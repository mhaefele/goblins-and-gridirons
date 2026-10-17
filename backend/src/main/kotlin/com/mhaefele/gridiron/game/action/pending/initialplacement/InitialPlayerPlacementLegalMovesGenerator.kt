package com.mhaefele.gridiron.game.action.pending.initialplacement

import com.mhaefele.gridiron.api.dto.response.LegalAction
import com.mhaefele.gridiron.game.action.pending.PendingDecisionLegalMovesGenerator
import com.mhaefele.gridiron.game.legalmoves.reservesPlacements
import com.mhaefele.gridiron.model.MatchState
import com.mhaefele.gridiron.model.pending.InitialPlayerPlacement
import org.springframework.stereotype.Component

/** Legal moves for [InitialPlayerPlacement]. */
@Component
class InitialPlayerPlacementLegalMovesGenerator : PendingDecisionLegalMovesGenerator<InitialPlayerPlacement>() {
    override fun generate(
        state: MatchState,
        pending: InitialPlayerPlacement,
    ): List<LegalAction> {
        val player = state.board.player(pending.playerId)
        val actions = reservesPlacements(player, state.board).map { LegalAction.Reserves(player.id, it) }
        return if (pending.optional) actions + LegalAction.Skip(player.id) else actions
    }
}
