package com.mhaefele.gridiron.integration.playthrough.dtos

import com.mhaefele.gridiron.game.opponent.OpponentStrategy
import com.mhaefele.gridiron.game.opponent.lookahead.LookaheadOpponentStrategy
import com.mhaefele.gridiron.model.catalog.TeamCard

data class TeamSetup(
    val strategy: OpponentStrategy,
    val team: TeamCard,
    val label: String,
    val telemetry: LookaheadOpponentStrategy.SearchTelemetry? // null if not lookahead away
)