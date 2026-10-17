package com.mhaefele.gridiron.integration.playthrough.dtos

import com.mhaefele.gridiron.api.dto.response.LegalAction
import com.mhaefele.gridiron.shared.TeamSide
import kotlin.system.measureNanoTime

class GameStatistics() {
    var gameMs: Long = 0
    val homeStatistics = TeamStatistics()
    val awayStatistics = TeamStatistics()
    val actions get() = homeStatistics.actions + awayStatistics.actions

    fun getTeamStatistics(team: TeamSide) = when (team) {
        TeamSide.HOME -> homeStatistics
        TeamSide.AWAY -> awayStatistics
    }

    class TeamStatistics {
        var actions = 0
        var maxLegalActions = 0
        var maxLegalGenMs = 0.0
        var legalGenNanos = 0L
        var legalCalls = 0

        fun collectLegalMovesStatistics(
            legalMovesQuery: () -> List<LegalAction>,
        ): List<LegalAction> {
            lateinit var legal: List<LegalAction>
            val nanos = measureNanoTime { legal = legalMovesQuery() }

            this.legalCalls++
            this.legalGenNanos += nanos
            val ms = nanos / 1_000_000.0
            if (ms > this.maxLegalGenMs) this.maxLegalGenMs = ms
            if (legal.size > this.maxLegalActions) {
                this.maxLegalActions = legal.size
            }
            return legal
        }

        fun aveerageGenMs() = if (legalCalls == 0) 0.0 else legalGenNanos / 1_000_000.0 / legalCalls
    }

    fun collectLegalMovesStatistics(
        activeCoach: TeamSide,
        legalMovesQuery: () -> List<LegalAction>,
    ) = getTeamStatistics(activeCoach).collectLegalMovesStatistics(legalMovesQuery)
}