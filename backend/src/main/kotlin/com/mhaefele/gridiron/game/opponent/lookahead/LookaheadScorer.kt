package com.mhaefele.gridiron.game.opponent.lookahead

import com.mhaefele.gridiron.model.MatchResult
import com.mhaefele.gridiron.model.MatchState
import com.mhaefele.gridiron.model.board.PlayerStatus
import com.mhaefele.gridiron.shared.Coordinate
import com.mhaefele.gridiron.shared.TeamSide
import kotlin.math.abs

/**
 * Leaf evaluation for lookahead — higher is better for [team].
 *
 * Chebyshev only (no pitch BFS). Decisive outcomes (TD / match win) dominate so search can
 * cut off early when a dry-run banks a touchdown.
 */
object LookaheadScorer {

    /** Score jump from banking a touchdown (+4 points × weight). */
    const val TOUCHDOWN_DELTA = 400.0

    /** Treat a leaf this far above the pre-action baseline as decisive (stop searching). */
    const val DECISIVE_MARGIN = 280.0

    fun score(state: MatchState, team: TeamSide): Double {
        state.progress.result?.let { result ->
            val winner = when (result) {
                is MatchResult.SuddenDeath   -> result.winner
                is MatchResult.PointsVictory -> result.winner
            }
            return when (winner) {
                team -> 50_000.0
                null -> 0.0
                else -> -50_000.0
            }
        }

        val board = state.board
        val us = points(state, team)
        val them = points(state, team.opposite())
        var total = (us - them) * 100.0

        val players = board.players.values
        val ourStanding = players.filter { it.team == team && it.status == PlayerStatus.STANDING }
        val ourCarrier = ourStanding.firstOrNull { it.heldBallId != null }
        val theirCarrier = players.firstOrNull { it.team != team && it.status == PlayerStatus.STANDING && it.heldBallId != null }

        when {
            ourCarrier != null   -> {
                total += 40.0
                val from = board.findSquare(ourCarrier.id).coordinate
                val endDist = minEndZoneDist(from, team.opposite(), board)
                if (endDist != null) {
                    // Imminent / banked scoring geometry — search cuts off on these leaves.
                    total += when (endDist) {
                        0    -> 120.0
                        1    -> 70.0
                        2    -> 40.0
                        else -> (10 - endDist.coerceAtMost(10)) * 3.5
                    }
                }
                total += state.currentTurnState.actionsRemaining * 3.0
                // Still our turn with the ball near the zone → keep pressing.
                if (state.currentTurnState.activeCoach == team && endDist != null && endDist <= 2) {
                    total += 25.0
                }
            }
            theirCarrier != null -> {
                total -= 35.0
                val ballAt = board.findSquare(theirCarrier.id).coordinate
                val theirEnd = minEndZoneDist(ballAt, team, board)
                if (theirEnd != null && theirEnd <= 2) total -= (3 - theirEnd) * 35.0
                val nearest = ourStanding.minOfOrNull { chebyshev(board.findSquare(it.id).coordinate, ballAt) }
                if (nearest != null) {
                    total -= nearest * 2.0
                    if (nearest <= 1) total += 12.0 // already on them
                }
            }
            else                 -> {
                val loose = board.allSquares().firstOrNull { it.looseBallId != null }?.coordinate
                if (loose != null) {
                    val nearest = ourStanding.minOfOrNull { chebyshev(board.findSquare(it.id).coordinate, loose) }
                    if (nearest != null) total += (10 - nearest.coerceAtMost(10)) * 2.0
                }
            }
        }

        total += players.count { it.team != team && it.status == PlayerStatus.PRONE } * 5.0
        total -= players.count { it.team == team && it.status == PlayerStatus.PRONE } * 3.0
        // Shared tableau: slight preference for higher-point actives as a tie-break only.
        // Keep this small vs banked points (*100/pt) so dry-run claim lines beat hoarding an
        // unclaimable 3-pt bait on forced replace (tableau-replace blind spot).
        total += state.cards.activeCards().sumOf { it.points } * 12.0
        return total
    }

    fun points(state: MatchState, team: TeamSide): Int =
        when (team) {
            TeamSide.HOME -> state.progress.homeScore.points
            TeamSide.AWAY -> state.progress.awayScore.points
        }

    private fun minEndZoneDist(from: Coordinate, endZoneTeam: TeamSide, board: com.mhaefele.gridiron.model.board.Board): Int? {
        var best: Int? = null
        for (sq in board.allSquares()) {
            if (!sq.isEndZone(endZoneTeam)) continue
            val d = chebyshev(from, sq.coordinate)
            if (best == null || d < best) best = d
        }
        return best
    }

    private fun chebyshev(a: Coordinate, b: Coordinate): Int =
        maxOf(abs(a.x - b.x), abs(a.y - b.y))
}
