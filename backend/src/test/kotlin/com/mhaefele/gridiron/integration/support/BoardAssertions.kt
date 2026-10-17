package com.mhaefele.gridiron.integration.support

import com.mhaefele.gridiron.model.board.Board
import com.mhaefele.gridiron.model.MatchState
import com.mhaefele.gridiron.model.board.PlayerStatus
import org.assertj.core.api.Assertions.assertThat

fun assertOccupancyConsistent(board: Board) {
    val onPitch = board.players.values.filter { it.status == PlayerStatus.STANDING }
    onPitch.forEach { player ->
        val square = board.findSquare(player.id)
        assertThat(square.playerId).isEqualTo(player.id)
    }
    board.allSquares()
        .mapNotNull { it.playerId }
        .forEach { playerId ->
            assertThat(board.players).containsKey(playerId)
        }
}

fun assertAtMostOneBallPerSquare(board: Board) {
    val looseBySquare = board.allSquares().mapNotNull { it.looseBallId }
    assertThat(looseBySquare).doesNotHaveDuplicates()
    board.players.values.mapNotNull { it.heldBallId }.forEach { heldId ->
        assertThat(looseBySquare).doesNotContain(heldId)
    }
}

fun assertActionBudgetValid(state: MatchState) {
    if (state.progress.result != null) return
    assertThat(state.currentTurnState.actionsRemaining).isBetween(0, 3)
}
