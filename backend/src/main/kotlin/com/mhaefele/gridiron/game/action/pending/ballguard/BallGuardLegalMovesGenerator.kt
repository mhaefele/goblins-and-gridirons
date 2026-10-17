package com.mhaefele.gridiron.game.action.pending.ballguard

import com.mhaefele.gridiron.api.dto.response.LegalAction
import com.mhaefele.gridiron.game.action.pending.PendingDecisionLegalMovesGenerator
import com.mhaefele.gridiron.game.action.utils.neighbors
import com.mhaefele.gridiron.model.MatchState
import com.mhaefele.gridiron.model.board.Board
import com.mhaefele.gridiron.model.pending.BallGuardContinuation
import com.mhaefele.gridiron.shared.Coordinate
import org.springframework.stereotype.Component

/** Legal moves for [BallGuardContinuation] — PlaceLooseBall onto adjacent bounce squares. */
@Component
class BallGuardLegalMovesGenerator : PendingDecisionLegalMovesGenerator<BallGuardContinuation>() {
    override fun generate(
        state: MatchState,
        pending: BallGuardContinuation,
    ): List<LegalAction> {
        val ongoing = pending.ongoingBallGuard
        check(ongoing.stage == OngoingBallGuard.BallGuardStage.HANDLE_PLACE) { "BallGuardContinuation legal moves unexpected stage ${ongoing.stage}" }
        return bounceSquares(ongoing.dropAt, state.board).map { LegalAction.PlaceLooseBall(ongoing.playerId, ongoing.dropAt, it) }
    }

    private fun bounceSquares(at: Coordinate, board: Board): List<Coordinate> =
        neighbors(board.squareAt(at), board)
            .filter { neighbor -> !neighbor.isBlocked() }
            .map { it.coordinate }
}
