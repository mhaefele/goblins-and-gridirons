package com.mhaefele.gridiron.api.ai.mapping

import com.mhaefele.gridiron.api.ai.dto.AiObsLayout
import com.mhaefele.gridiron.api.ai.dto.nested.AiPlayerAffordance
import com.mhaefele.gridiron.api.dto.response.BallEncounter
import com.mhaefele.gridiron.api.dto.response.ClaimCertainty
import com.mhaefele.gridiron.api.dto.response.LegalAction
import com.mhaefele.gridiron.model.MatchState
import com.mhaefele.gridiron.model.board.Board
import com.mhaefele.gridiron.shared.Coordinate
import com.mhaefele.gridiron.shared.PlayerId
import com.mhaefele.gridiron.shared.TeamSide

/**
 * Summarize current [LegalAction]s into per-roster [AiPlayerAffordance] for cell packing.
 * Reuses engine annotations (claims, [BallEncounter]) — does not re-run eligibility.
 */
object AiPlayerAffordanceAggregator {
    fun byPlayerIndex(
        state: MatchState,
        legalActions: List<LegalAction>,
        perspectiveSeat: TeamSide,
        playerIndexOf: (PlayerId) -> Int,
    ): Map<Int, AiPlayerAffordance> {
        if (legalActions.isEmpty()) return emptyMap()
        val board = state.board
        val looseBallCoords = looseBallCoordinates(board)
        val builders = mutableMapOf<Int, Builder>()

        for (action in legalActions) {
            val playerId = playerIdOrNull(action) ?: continue
            val index = playerIndexOf(playerId)
            val b = builders.getOrPut(index) { Builder() }

            for (claim in action.potentialChallengeClaims) {
                when (claim.certainty) {
                    ClaimCertainty.CERTAIN   -> b.certainClaimChallenge = true
                    ClaimCertainty.POTENTIAL -> b.mightClaimChallenge = true
                }
            }

            when (action) {
                is LegalAction.PathMovement -> {
                    val dest = action.targetCoordinate
                    b.noteReach(dest, perspectiveSeat)
                    if (dest in looseBallCoords) b.canReachBallSquare = true
                    if (action.ballEncounter == BallEncounter.PICK_UP) b.canPickupBall = true
                    if (canScoreTouchdown(board, playerId, dest)) b.canScoreTouchdown = true
                }
                is LegalAction.Reserves     -> {
                    val dest = action.targetCoordinate
                    b.noteReach(dest, perspectiveSeat)
                    if (dest in looseBallCoords) b.canReachBallSquare = true
                    if (action.ballEncounter == BallEncounter.PICK_UP) b.canPickupBall = true
                }
                else                        -> Unit
            }
        }

        return builders.mapValues { it.value.build() }
    }

    private fun playerIdOrNull(action: LegalAction): PlayerId? =
        when (action) {
            is LegalAction.PlayerLegalAction -> action.playerId
            else                             -> null
        }

    private fun canScoreTouchdown(board: Board, playerId: PlayerId, dest: Coordinate): Boolean {
        val player = board.player(playerId)
        if (!player.holdsBall()) return false
        return board.squareAt(dest).isEndZone(player.team.opposite())
    }

    private fun looseBallCoordinates(board: Board): Set<Coordinate> =
        board.allSquares().mapNotNull { square ->
            if (square.looseBallId != null) square.coordinate else null
        }.toSet()

    private class Builder {
        var canScoreTouchdown = false
        var mightClaimChallenge = false
        var certainClaimChallenge = false
        var canReachBallSquare = false
        var canPickupBall = false
        private var hasReach = false
        private var reachMinX = Int.MAX_VALUE
        private var reachMaxX = Int.MIN_VALUE
        private var reachMinY = Int.MAX_VALUE
        private var reachMaxY = Int.MIN_VALUE

        fun noteReach(absolute: Coordinate, perspectiveSeat: TeamSide) {
            val absoluteIndex = AiObsLayout.squareIndex(absolute.x, absolute.y)
            val perspectiveIndex = AiObsLayout.toPerspectiveIndex(absoluteIndex, perspectiveSeat)
            val (x, y) = AiObsLayout.coordinate(perspectiveIndex)
            hasReach = true
            if (x < reachMinX) reachMinX = x
            if (x > reachMaxX) reachMaxX = x
            if (y < reachMinY) reachMinY = y
            if (y > reachMaxY) reachMaxY = y
        }

        fun build(): AiPlayerAffordance =
            AiPlayerAffordance(
                canScoreTouchdown = canScoreTouchdown,
                mightClaimChallenge = mightClaimChallenge,
                certainClaimChallenge = certainClaimChallenge,
                canReachBallSquare = canReachBallSquare,
                canPickupBall = canPickupBall,
                reachMinX = if (hasReach) reachMinX else AiObsLayout.SENTINEL,
                reachMaxX = if (hasReach) reachMaxX else AiObsLayout.SENTINEL,
                reachMinY = if (hasReach) reachMinY else AiObsLayout.SENTINEL,
                reachMaxY = if (hasReach) reachMaxY else AiObsLayout.SENTINEL,
            )
    }
}
