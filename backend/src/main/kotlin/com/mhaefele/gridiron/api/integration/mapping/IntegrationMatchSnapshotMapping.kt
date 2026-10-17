package com.mhaefele.gridiron.api.integration.mapping

import com.mhaefele.gridiron.api.integration.dto.IntegrationMatchSnapshotDto
import com.mhaefele.gridiron.api.mapping.toBonusHandDto
import com.mhaefele.gridiron.api.mapping.toMatchSnapshotDto
import com.mhaefele.gridiron.model.MatchState
import com.mhaefele.gridiron.shared.TeamSide

fun toIntegrationMatchSnapshotDto(state: MatchState, viewerSeat: TeamSide): IntegrationMatchSnapshotDto =
    IntegrationMatchSnapshotDto(
        snapshot = toMatchSnapshotDto(state),
        bonusHand = toBonusHandDto(state.cards, viewerSeat),
    )
