package com.mhaefele.gridiron.integration.support

import com.mhaefele.gridiron.api.dto.response.MatchGameStateDto
import com.mhaefele.gridiron.api.dto.response.PieceSnapshotDto
import com.mhaefele.gridiron.api.dto.response.PlayerPieceDto
import com.mhaefele.gridiron.shared.Coordinate
import com.mhaefele.gridiron.model.board.PlayerStatus
import com.mhaefele.gridiron.shared.PlayerId
import com.mhaefele.gridiron.support.PlacedPlayer
import org.assertj.core.api.Assertions.assertThat

fun player(pieces: PieceSnapshotDto, id: PlacedPlayer): PlayerPieceDto =
    pieces.players.single { it.id == id.player.id }

fun player(pieces: PieceSnapshotDto, id: PlayerId): PlayerPieceDto =
    pieces.players.single { it.id == id }

data class PiecePlacement(val status: PlayerStatus, val at: Coordinate?)

private fun assertPiecePlacement(
    pieces: PieceSnapshotDto,
    id: PlayerId,
    status: PlayerStatus,
    at: Coordinate?,
) {
    val piece = player(pieces, id)
    assertThat(PiecePlacement(piece.status, piece.at)).isEqualTo(PiecePlacement(status, at))
}

fun assertPiece(pieces: PieceSnapshotDto, id: PlayerId, status: PlayerStatus, at: Coordinate) =
    assertPiecePlacement(pieces, id, status, at)

fun assertPiece(pieces: PieceSnapshotDto, id: PlayerId, status: PlayerStatus, x: Int, y: Int) =
    assertPiecePlacement(pieces, id, status, Coordinate(x, y))

fun assertPiece(pieces: PieceSnapshotDto, id: PlayerId, status: PlayerStatus) =
    assertPiecePlacement(pieces, id, status, at = null)

fun assertPiece(pieces: PieceSnapshotDto, id: PlayerId, at: Coordinate) {
    assertThat(player(pieces, id).at).isEqualTo(at)
}

fun assertPiece(pieces: PieceSnapshotDto, id: PlayerId, x: Int, y: Int) =
    assertPiece(pieces, id, Coordinate(x, y))

fun assertActionBudgetValid(gameState: MatchGameStateDto) {
    if (gameState.result != null) return
    assertThat(gameState.actionsRemaining).isBetween(0, 3)
}

fun assertOccupancyConsistent(pieces: PieceSnapshotDto) {
    val onPitch = pieces.players.filter { it.at != null }
    assertThat(onPitch.mapNotNull { it.at }).doesNotHaveDuplicates()
}

fun assertAtMostOneBallPerSquare(pieces: PieceSnapshotDto) {
    val heldAt = pieces.players.filter { it.holdsBall }.mapNotNull { it.at }
    assertThat(heldAt).doesNotHaveDuplicates()
    heldAt.forEach { coord ->
        assertThat(pieces.looseBallLocations).doesNotContain(coord)
    }
    assertThat(pieces.looseBallLocations).doesNotHaveDuplicates()
}
