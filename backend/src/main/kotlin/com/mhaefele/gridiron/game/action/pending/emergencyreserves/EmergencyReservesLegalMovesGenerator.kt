package com.mhaefele.gridiron.game.action.pending.emergencyreserves

import com.mhaefele.gridiron.api.dto.response.LegalAction
import com.mhaefele.gridiron.game.action.pending.PendingDecisionLegalMovesGenerator
import com.mhaefele.gridiron.game.legalmoves.reservesPlacements
import com.mhaefele.gridiron.model.MatchState
import com.mhaefele.gridiron.model.board.Board
import com.mhaefele.gridiron.model.pending.EmergencyReserves
import com.mhaefele.gridiron.shared.TeamSide
import org.springframework.stereotype.Component

/** Legal moves for [EmergencyReserves] — dugout → end-zone placements. */
@Component
class EmergencyReservesLegalMovesGenerator : PendingDecisionLegalMovesGenerator<EmergencyReserves>() {
    override fun generate(state: MatchState, pending: EmergencyReserves): List<LegalAction> =
        placements(state.currentTurnState.activeCoach, state.board)

    private fun placements(team: TeamSide, board: Board): List<LegalAction> =
        board.reserves.getValue(team).playerIds.flatMap { playerId ->
            val player = board.player(playerId)
            reservesPlacements(player, board).map { LegalAction.Reserves(playerId, it) }
        }
}
