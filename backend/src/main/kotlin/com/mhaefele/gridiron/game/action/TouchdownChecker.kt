package com.mhaefele.gridiron.game.action

import com.mhaefele.gridiron.game.action.utils.standingStatus
import com.mhaefele.gridiron.game.turn.MatchMutator
import com.mhaefele.gridiron.model.board.Board
import com.mhaefele.gridiron.model.board.PlayerStatus
import com.mhaefele.gridiron.shared.PlayerId
import com.mhaefele.gridiron.shared.StandingStatus
import com.mhaefele.gridiron.shared.TeamSide

fun scoreTouchdownIfPossible(session: MatchMutator) {
    touchdownScorer(session.board.board, session.activeCoach)?.let { scorerId ->
        session.scoreTouchdown(scorerId)
    }
}

/** Detects touchdowns after a player action (digest § Touchdown). Resolution lives on [com.mhaefele.gridiron.game.turn.MatchMutator.scoreTouchdown]. */
fun touchdownScorer(board: Board, activeCoach: TeamSide): PlayerId? =
    board.players.values
        .asSequence()
        .filter { player ->
            player.team == activeCoach && player.status == PlayerStatus.STANDING && player.holdsBall()
        }
        .firstOrNull { player ->
            val square = board.findSquare(player.id)
            square.isEndZone(activeCoach.opposite()) && standingStatus(player, board) == StandingStatus.OPEN
        }
        ?.id
