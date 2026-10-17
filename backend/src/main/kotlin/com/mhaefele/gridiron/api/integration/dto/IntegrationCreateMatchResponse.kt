package com.mhaefele.gridiron.api.integration.dto

import com.mhaefele.gridiron.shared.MatchId
import com.mhaefele.gridiron.shared.TeamSide
import io.swagger.v3.oas.annotations.media.Schema

@Schema(description = "New integration match — id, your seat, and the first decision frame.")
data class IntegrationCreateMatchResponse(
    @field:Schema(description = "Use in all later `/api/integration/matches/{matchId}/…` calls.")
    val matchId: MatchId,
    @field:Schema(description = "Your seat. Matches the default `agentSeat` query param on `state` / `play` (`HOME`).")
    val agentSeat: TeamSide,
    @field:Schema(description = "Board and legal moves when it is your seat's turn to act.")
    val state: IntegrationMatchStateDto,
)
