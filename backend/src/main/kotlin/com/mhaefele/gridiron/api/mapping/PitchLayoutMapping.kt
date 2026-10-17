package com.mhaefele.gridiron.api.mapping

import com.mhaefele.gridiron.api.dto.response.PitchLayoutDto
import com.mhaefele.gridiron.model.board.BoardDefinition
import com.mhaefele.gridiron.model.catalog.PitchCatalog
import com.mhaefele.gridiron.model.catalog.PitchDefinitionId
import com.mhaefele.gridiron.model.MatchState

fun toPitchLayoutDto(state: MatchState): PitchLayoutDto =
    toPitchLayoutDto(state.config.pitchDefinitionId, PitchCatalog.definition(state.config.pitchDefinitionId))

fun toPitchLayoutDto(pitchDefinitionId: PitchDefinitionId, definition: BoardDefinition): PitchLayoutDto =
    PitchLayoutDto(
        pitchDefinitionId = pitchDefinitionId,
        playWidth = definition.playWidth,
        playHeight = definition.playHeight,
        homeEndZoneRow = 1,
        awayEndZoneRow = definition.playHeight,
        trapdoors = definition.trapdoors.sortedWith(compareBy({ it.x }, { it.y })),
        blockedSquares = definition.blockedSquares.sortedWith(compareBy({ it.x }, { it.y })),
    )
