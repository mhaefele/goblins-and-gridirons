package com.mhaefele.gridiron.api.mapping

import com.mhaefele.gridiron.api.dto.response.MatchGameStateDto
import com.mhaefele.gridiron.api.dto.response.MatchResultKind
import com.mhaefele.gridiron.api.dto.response.MatchResultDto
import com.mhaefele.gridiron.api.dto.response.MatchSnapshotDto
import com.mhaefele.gridiron.model.MatchResult
import com.mhaefele.gridiron.model.MatchState

fun toMatchGameStateDto(state: MatchState): MatchGameStateDto =
    MatchGameStateDto(
        turnNumber = state.progress.turnNumber,
        phase = state.currentTurnState.phase,
        activeCoach = state.currentTurnState.activeCoach,
        actionsRemaining = state.currentTurnState.actionsRemaining,
        homeScore = state.progress.homeScore.points,
        awayScore = state.progress.awayScore.points,
        homeControlledBy = state.config.homeControlledBy,
        awayControlledBy = state.config.awayControlledBy,
        result = toMatchResultDto(state.progress.result),
        challenges = toChallengeTableauDto(state.cards),
        pointsVictoryTurnsRemaining = state.progress.pointsVictoryTurnsRemaining,
    )

fun toMatchSnapshotDto(state: MatchState): MatchSnapshotDto =
    MatchSnapshotDto(
        lastEventId = state.lastEventId,
        gameState = toMatchGameStateDto(state),
        pieces = toPieceSnapshotDto(state.board),
    )

private fun toMatchResultDto(result: MatchResult?): MatchResultDto? =
    when (result) {
        null                       -> null
        is MatchResult.SuddenDeath -> MatchResultDto(
            kind = MatchResultKind.suddenDeath,
            winner = result.winner,
        )
        is MatchResult.PointsVictory -> MatchResultDto(
            kind = MatchResultKind.pointsVictory,
            winner = result.winner,
        )
    }
