package com.mhaefele.gridiron.integration.playthrough.dtos

import com.mhaefele.gridiron.model.MatchProgress
import com.mhaefele.gridiron.model.catalog.TeamCatalogId
import com.mhaefele.gridiron.shared.TeamSide

data class GameResult(
    val winner: TeamSide?,
    val homeTeamSetup: TeamSetup,
    val awayTeamSetup: TeamSetup,
    val matchProgress: MatchProgress,
    val gameStatistics: GameStatistics,
    /** One search total for the game. Home's lookahead seat when it has one, otherwise away's. Both lookahead: home only. */
    val searchMoves: Int,
    /** Average think time for [searchMoves] on that same seat. */
    val avgSearchThinkMs: Double,
) {
    val homeStrategy: String = homeTeamSetup.label
    val awayStrategy: String = awayTeamSetup.label
    val homeTeamId: TeamCatalogId = homeTeamSetup.team.id
    val awayTeamId: TeamCatalogId = awayTeamSetup.team.id
    val homeScore: Int = matchProgress.homeScore.points
    val awayScore: Int = matchProgress.awayScore.points
    val turnNumber: Int = matchProgress.turnNumber
    val actionsPlayed: Int = gameStatistics.actions
    val gameMs: Long = gameStatistics.gameMs
    /** Peak size of [com.mhaefele.gridiron.game.legalmoves.LegalMovesGenerator.forActingCoach] during the game. */
    val maxLegalActions: Int = Math.max(gameStatistics.homeStatistics.maxLegalActions, gameStatistics.awayStatistics.maxLegalActions)
    /** Slowest single legal-move enumeration (ms). */
    val maxLegalGenMs: Double = Math.max(gameStatistics.homeStatistics.maxLegalGenMs, gameStatistics.awayStatistics.maxLegalGenMs)
    /** Mean legal-move enumeration (ms) across choose-action calls. */
    val avgLegalGenMs: Double = (gameStatistics.homeStatistics.aveerageGenMs() + gameStatistics.awayStatistics.aveerageGenMs()) / 2.0
    /** Active team id when [maxLegalActions] was observed. */
    val maxLegalTeamId: TeamCatalogId =
        if (gameStatistics.homeStatistics.maxLegalActions > gameStatistics.awayStatistics.maxLegalActions) {homeTeamSetup.team.id} else {awayTeamSetup.team.id}
}