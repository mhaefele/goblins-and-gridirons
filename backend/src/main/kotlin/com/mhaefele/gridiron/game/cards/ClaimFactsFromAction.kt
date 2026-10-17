package com.mhaefele.gridiron.game.cards

import com.mhaefele.gridiron.game.action.dto.DiceCheckResult
import com.mhaefele.gridiron.game.action.dto.DiceRollPurpose
import com.mhaefele.gridiron.game.action.dto.DomainEvent
import com.mhaefele.gridiron.model.OpenPlayerAction
import com.mhaefele.gridiron.model.board.BallId
import com.mhaefele.gridiron.model.board.Board
import com.mhaefele.gridiron.model.board.PlayerStatus
import com.mhaefele.gridiron.shared.ActionType
import com.mhaefele.gridiron.shared.Coordinate
import com.mhaefele.gridiron.shared.TeamSide

/**
 * Builds [ActionClaimFacts] from the action bracket's domain events plus the post-action board.
 * Prefer this over hand-filling the facts bag in resolvers.
 */
object ClaimFactsFromAction {
    fun build(
        completed: OpenPlayerAction,
        team: TeamSide,
        board: Board,
        events: List<DomainEvent>,
        ballsHeldAtActionStart: Map<TeamSide, Map<BallId, Coordinate>> = emptyMap(),
    ): ActionClaimFacts {
        val after = possessionByTeam(board)
        val before = ballsHeldAtActionStart.mapValues { (_, balls) -> balls.keys }
        val teamHeldAtStart = ballsHeldAtActionStart[team].orEmpty()
        val opp = team.opposite()
        val ballsGained = after[team].orEmpty() - before[team].orEmpty()
        val oppositionBallsLost = before[opp].orEmpty() - after[opp].orEmpty()

        val knockDownOccurred = events.any { event ->
            event is DomainEvent.PlayerStatusChanged && event.to == PlayerStatus.PRONE && board.players[event.playerId]?.team == opp
        }
        // Card text cares about the *target* being injured (Send Them Off). Fouler send-off
        // (Spotted / Take That) also emits PlayerInjured — count only opposition injuries.
        val injuryOccurred = events.any { event ->
            event is DomainEvent.PlayerInjured && board.players[event.playerId]?.team == opp
        }

        val throwRoll = events.filterIsInstance<DomainEvent.DiceRolled>()
            .lastOrNull { it.purpose == DiceRollPurpose.THROW }
        val throwSucceeded = when (completed.actionType) {
            ActionType.THROW          -> events.any { it is DomainEvent.BallThrown }
            ActionType.THROW_TEAMMATE -> throwRoll?.checkResult == DiceCheckResult.PASS
            else                      -> false
        }
        val throwWasHandOff = completed.actionType == ActionType.THROW && throwSucceeded && throwRoll == null
        val throwModifier = throwRoll?.modifier ?: 0

        val acting = board.players[completed.playerId]
        val endedOnHazard = acting?.status == PlayerStatus.STANDING &&
            runCatching { board.findSquare(completed.playerId).isTrapdoor() }.getOrDefault(false)

        val pushForwardDistance = pushForwardDistance(
            completed = completed,
            team = team,
            board = board,
            events = events,
            teamHeldAtStart = teamHeldAtStart,
            ballsGained = ballsGained,
        )

        return ActionClaimFacts(
            team = team,
            actionType = completed.actionType,
            actingPlayerId = completed.playerId,
            isFreeAction = completed.origin == OpenPlayerAction.Origin.FREE,
            ballsGained = ballsGained,
            oppositionBallsLost = oppositionBallsLost,
            pushForwardDistance = pushForwardDistance,
            knockDownOccurred = knockDownOccurred,
            injuryOccurred = injuryOccurred,
            touchdownScored = events.any { it is DomainEvent.TouchdownScored },
            throwSucceeded = throwSucceeded,
            throwWasHandOff = throwWasHandOff,
            throwModifier = throwModifier,
            endedOnHazard = endedOnHazard,
        )
    }

    /**
     * Push Forward! — throw or run only.
     * Start = where the ball was when possession began for this action; end = holder's square now.
     */
    private fun pushForwardDistance(
        completed: OpenPlayerAction,
        team: TeamSide,
        board: Board,
        events: List<DomainEvent>,
        teamHeldAtStart: Map<BallId, Coordinate>,
        ballsGained: Set<BallId>,
    ): Int? {
        if (completed.actionType !in setOf(ActionType.THROW, ActionType.THROW_TEAMMATE, ActionType.RUN)) {
            return null
        }

        val holder = board.players.values.firstOrNull { it.team == team && it.holdsBall() }
            ?: return null
        val ballId = holder.heldBallId!!
        val endCoord = board.findSquare(holder.id).coordinate

        // Gained balls: start = where *team* possession began (first pickup). Pass on the Run may
        // BallThrown to another holder — do not require BallPickedUp on the final carrier.
        val startCoord = when {
            ballId in ballsGained     ->
                events.filterIsInstance<DomainEvent.BallPickedUp>()
                    .firstOrNull { board.players[it.playerId]?.team == team }?.playerAt ?: error("Team gained $ballId during ${completed.actionType} but no BallPickedUp for $team")
            ballId in teamHeldAtStart -> teamHeldAtStart.getValue(ballId)
            else                      -> error("Team holds $ballId after ${completed.actionType} but it was neither held at action start nor gained")
        }

        return distanceGained(board, team, startCoord, endCoord)
    }

    private fun distanceGained(
        board: Board,
        team: TeamSide,
        from: Coordinate,
        to: Coordinate,
    ): Int =
        distanceFromOwnEndZone(board, team, to) - distanceFromOwnEndZone(board, team, from)

    private fun possessionByTeam(board: Board): Map<TeamSide, Set<BallId>> =
        board.players.values
            .mapNotNull { player -> player.heldBallId?.let { player.team to it } }
            .groupBy({ it.first }, { it.second })
            .mapValues { (_, ids) -> ids.toSet() }
}
