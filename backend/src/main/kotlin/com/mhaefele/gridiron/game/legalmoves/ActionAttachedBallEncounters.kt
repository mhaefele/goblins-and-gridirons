package com.mhaefele.gridiron.game.legalmoves

import com.mhaefele.gridiron.api.dto.response.BallEncounter
import com.mhaefele.gridiron.api.dto.response.LegalAction
import com.mhaefele.gridiron.game.action.ballEncounterOnLooseBallEntry
import com.mhaefele.gridiron.model.board.Board

/**
 * Annotates path / reserves legal actions with [BallEncounter] when they enter a loose-ball square.
 */
object ActionAttachedBallEncounters {
    fun annotate(actions: List<LegalAction>, board: Board): List<LegalAction> =
        actions.map { withBallEncounter(it, board) }

    fun withBallEncounter(action: LegalAction, board: Board): LegalAction {
        return when (action) {
            is LegalAction.PathMovement -> {
                val player = board.player(action.playerId)
                val landing = action.path.squares.last()
                val hasLooseBall = board.squareAt(landing).looseBallId != null
                val encounter = if(hasLooseBall) ballEncounterOnLooseBallEntry(player, action.actionType) else null
                if (encounter == action.ballEncounter) action
                else withPathBallEncounter(action, encounter)
            }
            is LegalAction.Reserves     -> {
                val hasLooseBall = board.squareAt(action.placement).looseBallId != null
                val encounter = if (hasLooseBall) BallEncounter.BOUNCE else null
                if (encounter == action.ballEncounter) action
                else action.copy(ballEncounter = encounter)
            }
            else                        -> action
        }
    }

    private fun withPathBallEncounter(
        action: LegalAction.PathMovement,
        encounter: BallEncounter?,
    ): LegalAction.PathMovement =
        when (action) {
            is LegalAction.Run      -> action.copy(ballEncounter = encounter)
            is LegalAction.Mark     -> action.copy(ballEncounter = encounter)
            is LegalAction.Sidestep -> action.copy(ballEncounter = encounter)
        }
}
