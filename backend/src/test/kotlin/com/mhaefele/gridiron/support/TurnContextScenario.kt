package com.mhaefele.gridiron.support

import com.mhaefele.gridiron.model.pending.RegularPlayerAction
import com.mhaefele.gridiron.model.TurnAction
import com.mhaefele.gridiron.model.TurnState
import com.mhaefele.gridiron.shared.ActionType
import com.mhaefele.gridiron.shared.PlayerActionStep
import com.mhaefele.gridiron.shared.PlayerId
import com.mhaefele.gridiron.shared.TeamSide
import com.mhaefele.gridiron.shared.TurnPhase

/** Fluent builder for [TurnState] — override only what the test cares about. */
class TurnContextScenario(
    private val activeCoach: TeamSide,
) {
    private var actionsRemaining: Int = 3
    private var actionsThisTurn: List<TurnAction> = emptyList()
    private var turnEndedPlayers: Set<PlayerId> = emptySet()

    fun withActionsRemaining(actionsRemaining: Int) = apply { this.actionsRemaining = actionsRemaining }

    fun noActionsRemaining() = withActionsRemaining(0)

    fun havingUsed(player: PlacedPlayer, vararg types: ActionType) = havingUsed(player.player.id, *types)

    fun havingUsed(playerId: PlayerId, vararg types: ActionType) = apply {
        actionsThisTurn = actionsThisTurn + types.map { TurnAction(playerId, it) }
    }

    fun havingRun(player: PlacedPlayer) = havingUsed(player, ActionType.RUN)

    fun turnEnded(player: PlacedPlayer) = apply { turnEndedPlayers = turnEndedPlayers + player.player.id }

    fun build(): TurnState {
        require(actionsRemaining in 0..3) { "actionsRemaining must be 0–3, was $actionsRemaining" }
        val pending = if (actionsRemaining == 0) null else RegularPlayerAction()
        return TurnState(
            phase = TurnPhase.PLAYER_ACTION,
            activeCoach = activeCoach,
            pendingDecision = pending,
            regularActionSlotsRemaining = actionsRemaining,
            actionsThisTurn = actionsThisTurn,
            turnEndedPlayers = turnEndedPlayers,
            step = PlayerActionStep.SELECT,
        )
    }
}
