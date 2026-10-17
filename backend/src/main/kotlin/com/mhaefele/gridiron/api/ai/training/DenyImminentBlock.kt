package com.mhaefele.gridiron.api.ai.training

import com.mhaefele.gridiron.api.dto.response.LegalAction
import com.mhaefele.gridiron.game.action.utils.distance
import com.mhaefele.gridiron.model.MatchState
import com.mhaefele.gridiron.model.board.Player
import com.mhaefele.gridiron.model.board.PlayerStatus
import com.mhaefele.gridiron.shared.TeamSide

/** Soft-mask helpers for deny drills where an adjacent opposition Blocker threatens next turn. */
internal object DenyImminentBlock {
    fun threatenedPlayers(state: MatchState, trainerSide: TeamSide, filter: (Player) -> Boolean = { true }): List<Player> =
        state.board.players.values.filter { player ->
            player.team == trainerSide && player.status == PlayerStatus.STANDING && filter(player) && adjacentThreat(state, player) != null
        }

    fun adjacentThreat(state: MatchState, threatened: Player): Player? {
        val sq = state.board.findSquare(threatened.id)
        return state.board.players.values.firstOrNull { opp ->
            opp.team != threatened.team && opp.status == PlayerStatus.STANDING && distance(sq, state.board.findSquare(opp.id), state.board) == 1
        }
    }

    fun isSoftMaskEscapeOrBlock(
        action: LegalAction,
        state: MatchState,
        trainerSide: TeamSide,
        threatenedFilter: (Player) -> Boolean = { true },
    ): Boolean {
        when (action) {
            is LegalAction.Block    -> {
                val targetId = state.board.squareAt(action.destination).playerId ?: return false
                val target = state.board.players[targetId] ?: return false
                if (target.team != trainerSide.opposite() || target.status != PlayerStatus.STANDING) return false
                return threatenedPlayers(state, trainerSide, threatenedFilter).any { adjacentThreat(state, it)?.id == target.id }
            }
            is LegalAction.Sidestep -> {
                val threatened = state.board.players[action.playerId] ?: return false
                if (threatened.team != trainerSide || !threatenedFilter(threatened)) return false
                val threat = adjacentThreat(state, threatened) ?: return false
                val dest = state.board.squareAt(action.path.squares.last())
                return distance(dest, state.board.findSquare(threat.id), state.board) > 1
            }
            else                    -> return false
        }
    }
}
