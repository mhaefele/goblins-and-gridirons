package com.mhaefele.gridiron.game.opponent.lookahead

import com.mhaefele.gridiron.api.dto.response.LegalAction
import com.mhaefele.gridiron.game.action.utils.isAssistedBlock
import com.mhaefele.gridiron.game.action.utils.standingStatus
import com.mhaefele.gridiron.game.dice.throwSuccessChanceFromNeeded
import com.mhaefele.gridiron.model.MatchState
import com.mhaefele.gridiron.model.board.Board
import com.mhaefele.gridiron.model.board.PlayerStatus
import com.mhaefele.gridiron.model.catalog.canHoldBall
import com.mhaefele.gridiron.shared.Coordinate
import com.mhaefele.gridiron.shared.PlayerId
import com.mhaefele.gridiron.shared.StandingStatus
import com.mhaefele.gridiron.shared.TeamSide
import kotlin.math.abs

/**
 * Cheap pre-sim move ordering for the deepen beam.
 *
 * Claim annotations outrank unlock/ball noise (a banked challenge point is real scoreboard EV);
 * imminent TD paths can still win. Marked-carrier Sidestep is only a nudge — strategy already
 * short-circuits the must-sidestep case.
 */
object LookaheadCandidateRanker {

    fun top(
        state: MatchState,
        candidates: List<LegalAction>,
        limit: Int,
        team: TeamSide = state.currentTurnState.activeCoach,
    ): List<LegalAction> {
        if (candidates.isEmpty()) return emptyList()
        val ctx = Context.from(state, team)
        return candidates
            .sortedWith(
                compareByDescending<LegalAction> { weight(it, ctx) }
                    // Flat weights: prefer ending closer to the loose ball / ball carrier.
                    .thenBy { ballDistanceAfter(it, ctx) ?: Int.MAX_VALUE }
                    .thenBy { it.id.value },
            )
            .take(limit.coerceAtLeast(1))
    }

    internal fun weight(action: LegalAction, ctx: Context): Double {
        // 1pt annotated ≈ 280 — above unlock Sidestep / pickup noise; below raw EZ-landing (~280+).
        var w = (action.potentialChallengeClaims.maxOfOrNull { it.points } ?: 0) * 280.0
        when (action) {
            is LegalAction.Run              -> w += pathBias(action.playerId, action.path.squares.last(), pickup = true, ctx)
            is LegalAction.Mark             -> {
                w += pathBias(action.playerId, action.path.squares.last(), pickup = false, ctx)
                ctx.theirCarrierAt?.let { if (chebyshev(action.path.squares.last(), it) == 1) w += 22.0 }
            }
            is LegalAction.Sidestep         -> {
                w += pathBias(action.playerId, action.path.squares.last(), pickup = false, ctx)
                ctx.board.players[action.playerId]?.let { player ->
                    if (player.heldBallId != null && standingStatus(player, ctx.board) == StandingStatus.MARKED) w += 90.0
                }
                ctx.theirCarrierAt?.let { if (chebyshev(action.path.squares.last(), it) == 1) w += 22.0 }
            }
            is LegalAction.Throw            -> {
                w += 10.0
                w += throwSuccessChanceFromNeeded(action.target.throwCheckNeeded) * 36.0
                w += progress(action.target.destination, ctx) * 2.0
                if (ctx.board.squareAt(action.target.destination).isEndZone(ctx.team.opposite())) w += 80.0
                // Hand/throw to a teammate closer to the scoring end zone.
                ctx.ourCarrierAt?.let { from ->
                    val before = endZoneDist(from, ctx) ?: 99
                    val after = endZoneDist(action.target.destination, ctx) ?: 99
                    if (after < before) w += (before - after) * 8.0
                }
            }
            is LegalAction.Block            -> {
                w += 12.0
                val blocker = ctx.board.players[action.playerId]
                val targetId = ctx.board.squareAt(action.destination).playerId
                val target = targetId?.let { ctx.board.players[it] }
                if (blocker != null && target != null && isAssistedBlock(blocker, target, ctx.board)) w += 35.0
                ctx.ballAt?.let { ball ->
                    if (chebyshev(action.destination, ball) == 1) {
                        val occupant = ctx.board.squareAt(action.destination).playerId?.let { ctx.board.players[it] }
                        if (occupant != null && occupant.team != ctx.team) w += 45.0
                    }
                }
                ctx.theirCarrierAt?.let { carrier ->
                    if (action.destination == carrier) w += 55.0
                    else w += (8 - chebyshev(action.destination, carrier).coerceAtMost(8)) * 2.5
                }
            }
            is LegalAction.Foul             -> w += 14.0
            is LegalAction.StandUp          -> {
                w += 16.0
                ctx.ballAt?.let { w +=(8 - chebyshev(ctx.board.findSquare(action.playerId).coordinate, it).coerceAtMost(8)) * 2.0 }
            }
            is LegalAction.Reserves         -> w += 4.0
            is LegalAction.DeclareRun       -> w += 8.0
            is LegalAction.ReplaceChallenge ->
                // Prefer dumping low-point actives as a cheap prior; search + claim deepen override.
                w += (4 - action.claimCondition.points) * 40.0
            else                            -> Unit
        }
        return w
    }

    private fun pathBias(playerId: PlayerId, end: Coordinate, pickup: Boolean, ctx: Context): Double {
        var w = 0.0
        val endSq = ctx.board.squareAt(end)
        val holding = ctx.board.players[playerId]?.heldBallId != null
        val canHold = ctx.board.players[playerId]?.profile?.stats?.throwStat?.canHoldBall() == true

        if (endSq.looseBallId != null) {
            w += if (pickup && canHold) 60.0 else 30.0
        }
        if (holding) {
            w += 30.0
            w += progress(end, ctx) * 4.0
            val ez = endZoneDist(end, ctx)
            when (ez) {
                0    -> w += 250.0 // likely touchdown this path
                1    -> w += 120.0
                2    -> w += 55.0
                else -> if (ez != null) w += (10 - ez.coerceAtMost(10)) * 3.0
            }
        } else {
            // No ball yet: absolute closeness beats empty-EZ jogs that only “improve” by 1.
            ctx.ballAt?.let { ball ->
                val from = ctx.board.findSquare(playerId).coordinate
                val before = chebyshev(from, ball)
                val after = chebyshev(end, ball)
                w += (10 - after.coerceAtMost(10)) * 8.0
                if (after < before) w += (before - after) * 5.0
                if (after == 0) w += 15.0
            }
            ctx.theirCarrierAt?.let { carrier ->
                val from = ctx.board.findSquare(playerId).coordinate
                val before = chebyshev(from, carrier)
                val after = chebyshev(end, carrier)
                if (after < before) w += (before - after) * 3.5
            }
            // Ball-less scoring-EZ landing is never the lesson — demote vs same-distance approach.
            if (endSq.isEndZone(ctx.team.opposite())) w -= 40.0
        }
        return w
    }

    /** Chebyshev from path/block end to [Context.ballAt], or null when the action has no dest. */
    internal fun ballDistanceAfter(action: LegalAction, ctx: Context): Int? {
        val ball = ctx.ballAt ?: return null
        val end = when (action) {
            is LegalAction.Run      -> action.path.squares.last()
            is LegalAction.Mark     -> action.path.squares.last()
            is LegalAction.Sidestep -> action.path.squares.last()
            is LegalAction.Block    -> action.destination
            is LegalAction.Foul     -> action.destination
            else                    -> return null
        }
        return chebyshev(end, ball)
    }

    private fun endZoneDist(coord: Coordinate, ctx: Context): Int? {
        var best: Int? = null
        val endTeam = ctx.team.opposite()
        for (sq in ctx.board.allSquares()) {
            if (!sq.isEndZone(endTeam)) continue
            val d = chebyshev(coord, sq.coordinate)
            if (best == null || d < best) best = d
        }
        return best
    }

    private fun progress(coord: Coordinate, ctx: Context): Double =
        when (ctx.team) {
            TeamSide.HOME -> coord.y.toDouble()
            TeamSide.AWAY -> -coord.y.toDouble()
        }

    private fun chebyshev(a: Coordinate, b: Coordinate): Int =
        maxOf(abs(a.x - b.x), abs(a.y - b.y))

    data class Context(
        val team: TeamSide,
        val board: Board,
        val ballAt: Coordinate?,
        val ourCarrierAt: Coordinate?,
        val theirCarrierAt: Coordinate?,
    ) {
        companion object {
            fun from(state: MatchState, team: TeamSide): Context {
                val board = state.board
                val loose = board.allSquares().firstOrNull { it.looseBallId != null }?.coordinate
                val ourCarrier = board.players.values.firstOrNull { it.team == team && it.status == PlayerStatus.STANDING && it.heldBallId != null }
                val theirCarrier = board.players.values.firstOrNull { it.team != team && it.status == PlayerStatus.STANDING && it.heldBallId != null }
                val ourAt = ourCarrier?.let { board.findSquare(it.id).coordinate }
                val theirAt = theirCarrier?.let { board.findSquare(it.id).coordinate }
                return Context(
                    team = team,
                    board = board,
                    ballAt = loose ?: ourAt ?: theirAt,
                    ourCarrierAt = ourAt,
                    theirCarrierAt = theirAt,
                )
            }
        }
    }
}
