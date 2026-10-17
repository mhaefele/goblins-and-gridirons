package com.mhaefele.gridiron.game.cards

import com.mhaefele.gridiron.model.TurnAction
import com.mhaefele.gridiron.model.board.BallId
import com.mhaefele.gridiron.model.board.Board
import com.mhaefele.gridiron.model.board.PlayerStatus
import com.mhaefele.gridiron.model.catalog.ChallengeCardDefinition
import com.mhaefele.gridiron.model.catalog.ChallengeClaimCondition
import com.mhaefele.gridiron.game.action.utils.neighbors
import com.mhaefele.gridiron.game.action.utils.playerMarksOpponent
import com.mhaefele.gridiron.shared.ActionType
import com.mhaefele.gridiron.shared.Coordinate
import com.mhaefele.gridiron.shared.PlayerId
import com.mhaefele.gridiron.shared.TeamSide

/**
 * Facts from the player action just completed — inputs to claim eligibility.
 * Prefer [ClaimFactsFromAction.build] from the action bracket's events + board; tests may construct directly.
 */
data class ActionClaimFacts(
    val team: TeamSide,
    val actionType: ActionType,
    val actingPlayerId: PlayerId,
    /** Budgeted Player Actions count toward Star Turn; free grants do not. */
    val isFreeAction: Boolean = false,
    /** Balls this team gained possession of during the action. */
    val ballsGained: Set<BallId> = emptySet(),
    /** Balls the opposition lost possession of as a result of the action. */
    val oppositionBallsLost: Set<BallId> = emptySet(),
    /**
     * Push Forward! — squares the ball moved further from this team's End Zone (needs ≥ 4).
     * Null when the action didn't move team possession, the team no longer holds the ball, or distance is 0.
     */
    val pushForwardDistance: Int? = null,
    val knockDownOccurred: Boolean = false,
    val injuryOccurred: Boolean = false,
    val touchdownScored: Boolean = false,
    val throwSucceeded: Boolean = false,
    val throwWasHandOff: Boolean = false,
    /** Applied throw-check modifier (clamped ±1 in play); negative enables Thread the Needle!. */
    val throwModifier: Int = 0,
    /** Acting player ended the action on a Hazard (trapdoor). */
    val endedOnHazard: Boolean = false,
)

/**
 * Turn-scoped counters for claim conditions that look beyond the action just completed.
 * [actionsThisTurn] should already include the action that just finished.
 */
data class TurnClaimStats(
    val actionsThisTurn: List<TurnAction> = emptyList(),
    /** Balls this team possessed at the start of the turn (Grab It!). */
    val ballsPossessedAtTurnStart: Set<BallId> = emptySet(),
) {
    val runActionCount: Int
        get() = actionsThisTurn.count { it.actionType == ActionType.RUN }

    fun budgetedActionsFor(playerId: PlayerId): List<ActionType> =
        actionsThisTurn
            .filter { it.playerId == playerId && !it.isFree && it.countsTowardSameActionOnce }
            .map { it.actionType }
}

/**
 * Pure claim eligibility: which active challenge cards may be claimed after an action.
 *
 * Claim rule (reference/cards.md § Rules reminders): conditions must be met **and** at least one condition fulfilled by the
 * action just completed. Turn-aggregate cards (Keep Running!, All Hands!, …) qualify only
 * when this action contributed to crossing the threshold.
 */
object ClaimEligibility {
    fun eligibleCards(
        activeCards: List<ChallengeCardDefinition>,
        facts: ActionClaimFacts,
        turnStats: TurnClaimStats,
        board: Board,
    ): List<ChallengeCardDefinition> =
        activeCards.filter { meets(it.challenge, facts, turnStats, board) }

    fun meets(
        condition: ChallengeClaimCondition,
        facts: ActionClaimFacts,
        turnStats: TurnClaimStats,
        board: Board,
    ): Boolean =
        when (condition) {
            ChallengeClaimCondition.GRAB_IT           -> meetsGrabIt(facts, turnStats)
            ChallengeClaimCondition.HUDDLE_UP           -> meetsHuddleUp(facts, board)
            ChallengeClaimCondition.PUSH_FORWARD          -> meetsPushForward(facts)
            ChallengeClaimCondition.FAN_OUT             -> meetsFanOut(facts, board)
            ChallengeClaimCondition.PLAY_TO_THE_STANDS -> facts.touchdownScored
            ChallengeClaimCondition.CONNECT   -> isThrowAction(facts.actionType) && facts.throwSucceeded && !facts.throwWasHandOff
            ChallengeClaimCondition.STRIP_IT       -> facts.oppositionBallsLost.isNotEmpty()
            ChallengeClaimCondition.KEEP_RUNNING             -> facts.actionType == ActionType.RUN && turnStats.runActionCount >= 3
            ChallengeClaimCondition.HOLD_THE_PIT           -> facts.endedOnHazard && oppositionHoldsAnyBall(board, facts.team)
            ChallengeClaimCondition.THREAD_THE_NEEDLE     -> isThrowAction(facts.actionType) && facts.throwSucceeded && facts.throwModifier < 0
            ChallengeClaimCondition.ALL_HANDS         -> meetsAllHands(facts, turnStats)
            ChallengeClaimCondition.STAR_TURN -> meetsStarTurn(facts, turnStats)
            ChallengeClaimCondition.DROP_THEM         -> facts.actionType == ActionType.BLOCK && facts.knockDownOccurred
            ChallengeClaimCondition.LOCK_THEM_DOWN            -> meetsLockThemDown(facts, board)
            ChallengeClaimCondition.SEND_THEM_OFF       -> facts.actionType in setOf(ActionType.BLOCK, ActionType.FOUL) && facts.injuryOccurred
        }

    /** Throw Team-mate counts as a Throw Action for challenge claims (digest § Throw Team-mate). */
    private fun isThrowAction(actionType: ActionType): Boolean =
        actionType == ActionType.THROW || actionType == ActionType.THROW_TEAMMATE

    private fun meetsGrabIt(facts: ActionClaimFacts, turnStats: TurnClaimStats): Boolean {
        val newlyGained = facts.ballsGained - turnStats.ballsPossessedAtTurnStart
        return newlyGained.isNotEmpty()
    }

    private fun meetsHuddleUp(facts: ActionClaimFacts, board: Board): Boolean {
        if (facts.actionType !in setOf(ActionType.RUN, ActionType.MARK, ActionType.SIDESTEP)) return false
        return adjacentTeammateCount(board, facts.actingPlayerId) >= 2
    }

    private fun meetsPushForward(facts: ActionClaimFacts): Boolean =
        facts.pushForwardDistance != null && facts.pushForwardDistance >= 4

    private fun meetsFanOut(facts: ActionClaimFacts, board: Board): Boolean {
        if (facts.actionType !in setOf(ActionType.RUN, ActionType.SIDESTEP)) return false
        return !anyTeammatesAdjacent(board, facts.team)
    }

    private fun meetsAllHands(facts: ActionClaimFacts, turnStats: TurnClaimStats): Boolean {
        // This action must be part of the turn log already.
        val players = turnStats.actionsThisTurn.map { it.playerId }.toSet()
        val types = turnStats.actionsThisTurn.map { it.actionType }.toSet()
        return players.size >= 3 &&
            types.size >= 3 &&
            turnStats.actionsThisTurn.any { it.playerId == facts.actingPlayerId && it.actionType == facts.actionType }
    }

    private fun meetsStarTurn(facts: ActionClaimFacts, turnStats: TurnClaimStats): Boolean {
        if (facts.isFreeAction) return false
        val types = turnStats.budgetedActionsFor(facts.actingPlayerId).toSet()
        return types.size >= 3
    }

    private fun meetsLockThemDown(facts: ActionClaimFacts, board: Board): Boolean {
        if (facts.actionType != ActionType.MARK) return false
        val marker = board.player(facts.actingPlayerId)
        if (marker.status != PlayerStatus.STANDING) return false
        val uniquelyMarked = board.players.values.count { target ->
            target.team != facts.team &&
                target.status == PlayerStatus.STANDING &&
                playerMarksOpponent(marker, target, board) &&
                board.players.values.none { other ->
                    other.id != marker.id && other.team == facts.team && other.status == PlayerStatus.STANDING && playerMarksOpponent(other, target, board)
                }
        }
        return uniquelyMarked >= 2
    }

    private fun oppositionHoldsAnyBall(board: Board, team: TeamSide): Boolean =
        board.players.values.any { it.team != team && it.holdsBall() }

    private fun adjacentTeammateCount(board: Board, playerId: PlayerId): Int {
        val player = board.players.getValue(playerId)
        if (player.status != PlayerStatus.STANDING) return 0
        val square = board.findSquare(playerId)
        return neighbors(square, board)
            .mapNotNull { it.playerId?.let(board.players::get) }
            .count { it.team == player.team && it.status == PlayerStatus.STANDING }
    }

    private fun anyTeammatesAdjacent(board: Board, team: TeamSide): Boolean {
        val standing = board.players.values.filter { it.team == team && it.status == PlayerStatus.STANDING }
        return standing.any { player ->
            val square = board.findSquare(player.id)
            neighbors(square, board)
                .mapNotNull { it.playerId?.let(board.players::get) }
                .any { other -> other.team == team && other.status == PlayerStatus.STANDING && other.id != player.id }
        }
    }
}

/** Orthogonal row distance from [coordinate] to [team]'s End Zone (Push Forward!). */
fun distanceFromOwnEndZone(board: Board, team: TeamSide, coordinate: Coordinate): Int {
    val endZoneY = board.allSquares()
        .filter { it.isEndZone(team) }
        .map { it.coordinate.y }
        .distinct()
        .single()
    return kotlin.math.abs(coordinate.y - endZoneY)
}
