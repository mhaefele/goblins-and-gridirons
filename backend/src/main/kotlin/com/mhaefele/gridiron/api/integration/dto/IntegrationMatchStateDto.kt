package com.mhaefele.gridiron.api.integration.dto

import com.mhaefele.gridiron.api.dto.response.LegalAction
import com.mhaefele.gridiron.game.action.dto.StoredDomainEvent
import com.mhaefele.gridiron.shared.LegalActionId
import com.mhaefele.gridiron.shared.TeamSide
import io.swagger.v3.oas.annotations.media.Schema

@Schema(description = "Current match frame for an external agent — board, legal moves, and optional move/event deltas.")
data class IntegrationMatchStateDto(
    @field:Schema(description = "Board, scores, turn clock, and your hidden bonus hand (`agentSeat`). Player positions are in `pieces`; event cursor is `lastEventId`.")
    val snapshot: IntegrationMatchSnapshotDto,
    @field:Schema(description = "Legal moves for whoever must act now. Each row includes an `id` — POST that string to `…/play`.")
    val legalActions: List<LegalAction>,
    @field:Schema(description = "Seat that must submit next (`HOME` or `AWAY`). Null when the match is over.", nullable = true)
    val actingSeat: TeamSide?,
    @field:Schema(description = "Set on `play` only — the legal-action id you just submitted. Null on `GET …/state`.", nullable = true)
    val chosenLegalActionId: LegalActionId? = null,
    @field:Schema(description = "Resolution log from `play` only — domain events appended since before your pick. Empty on `GET …/state`.")
    val events: List<StoredDomainEvent> = emptyList(),
)
