package com.mhaefele.gridiron.game.action.pending.dancestep

import com.mhaefele.gridiron.api.dto.response.LegalAction
import com.mhaefele.gridiron.game.action.pending.PendingDecisionLegalMovesGenerator
import com.mhaefele.gridiron.game.action.pending.sidestep.SidestepLegalMovesGenerator
import com.mhaefele.gridiron.game.legalmoves.sidestepMoves
import com.mhaefele.gridiron.model.MatchState
import com.mhaefele.gridiron.model.pending.OfferDanceStep
import org.springframework.stereotype.Component

/**
 * Legal moves for [OfferDanceStep].
 * With a [OfferDanceStep.delegate], follows [SidestepLegalMovesGenerator]; otherwise Sidestep + Skip.
 */
@Component
class OfferDanceStepLegalMovesGenerator(
    private val sidestepLegalMovesGenerator: SidestepLegalMovesGenerator,
) : PendingDecisionLegalMovesGenerator<OfferDanceStep>() {
    override fun generate(state: MatchState, pending: OfferDanceStep): List<LegalAction> {
        val child = pending.delegate
        if (child != null) return sidestepLegalMovesGenerator.generate(state, child)
        val player = state.board.player(pending.playerId)
        return sidestepMoves(player, state.board) + LegalAction.Skip(pending.playerId)
    }
}
