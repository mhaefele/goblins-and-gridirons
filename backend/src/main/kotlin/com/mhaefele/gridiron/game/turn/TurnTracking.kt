package com.mhaefele.gridiron.game.turn

import com.mhaefele.gridiron.shared.PlayerId
import com.mhaefele.gridiron.model.TurnState

/**
 * Clears same-action-once for a scorer sent to the dugout after a touchdown so they may repeat
 * action types (e.g. Run) if they return via Reserves the same turn (digest § Reserves).
 *
 * Entries stay in [TurnState.actionsThisTurn] for challenge aggregates (Keep Running!, …).
 */
fun clearActionsUsedForPlayer(turn: TurnState, playerId: PlayerId): TurnState =
    turn.copy(
        actionsThisTurn = turn.actionsThisTurn.map { action ->
            if (action.playerId == playerId) action.copy(countsTowardSameActionOnce = false) else action
        },
    )
