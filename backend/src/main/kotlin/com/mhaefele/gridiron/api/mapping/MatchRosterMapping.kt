package com.mhaefele.gridiron.api.mapping

import com.mhaefele.gridiron.api.dto.response.MatchRosterPlayerDto
import com.mhaefele.gridiron.api.dto.response.MatchRosterDto
import com.mhaefele.gridiron.model.board.Player
import com.mhaefele.gridiron.model.MatchState
import com.mhaefele.gridiron.model.catalog.TeamCatalogId
import com.mhaefele.gridiron.shared.TeamSide

fun toMatchRosterDto(state: MatchState): MatchRosterDto {
    val teamIdBySide = mapOf(
        TeamSide.HOME to state.config.homeTeamId,
        TeamSide.AWAY to state.config.awayTeamId,
    )
    return MatchRosterDto(
        players = state.board.players.values
            .sortedBy { it.id.value }
            .map { toMatchRosterPlayerDto(it, teamIdBySide.getValue(it.team)) },
    )
}

private fun toMatchRosterPlayerDto(player: Player, teamId: TeamCatalogId): MatchRosterPlayerDto =
    MatchRosterPlayerDto(
        playerId = player.id,
        playerCard = toPlayerCardDto(player.profile, teamId),
    )
