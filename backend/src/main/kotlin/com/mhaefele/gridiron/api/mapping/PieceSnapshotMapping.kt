package com.mhaefele.gridiron.api.mapping

import com.mhaefele.gridiron.api.dto.response.PieceSnapshotDto
import com.mhaefele.gridiron.api.dto.response.PlayerPieceDto
import com.mhaefele.gridiron.model.board.Board
import com.mhaefele.gridiron.model.board.PlayerStatus

fun toPieceSnapshotDto(board: Board): PieceSnapshotDto =
    PieceSnapshotDto(
        players = board.players.values
            .sortedBy { it.id.value }
            .map { player ->
                PlayerPieceDto(
                    id = player.id,
                    team = player.team,
                    status = player.status,
                    at = when (player.status) {
                        PlayerStatus.RESERVES -> null
                        else                  -> board.findSquare(player.id).coordinate
                    },
                    holdsBall = player.holdsBall()
                )
            },
        looseBallLocations = board.allSquares()
            .filter { it.looseBallId != null }
            .map { it.coordinate }
            .sortedWith(compareBy({ it.x }, { it.y })),
    )
