package com.mhaefele.gridiron.model

import com.mhaefele.gridiron.shared.TeamSide

/** Touchdown points per rulebook — base game uses a single integer per team. */
data class Score(val points: Int = 0) {
    init {
        require(points >= 0) { "Score cannot be negative" }
    }

    fun addTouchdown(): Score = copy(points = points + 4)

    fun addPoints(n: Int): Score {
        require(n >= 0) { "Cannot add negative points" }
        return copy(points = points + n)
    }

    fun leadOver(other: Score): Int = points - other.points
}

sealed interface MatchResult {
    data class SuddenDeath(val winner: TeamSide) : MatchResult

    /** Highest score after the final-turns window that follows the last challenge deal. [winner] is null on a draw. */
    data class PointsVictory(val winner: TeamSide?) : MatchResult
}

/** Cumulative match snapshot — advances across turns until [result] ends the game. */
data class MatchProgress(
    val turnNumber: Int,
    val homeScore: Score = Score(),
    val awayScore: Score = Score(),
    /** Touchdowns scored this match (not recoverable from blended [Score.points]). */
    val homeTouchdowns: Int = 0,
    val awayTouchdowns: Int = 0,
    /** Challenge cards claimed this match (Clean Sweep bonus points are not a claim). */
    val homeChallengeCards: Int = 0,
    val awayChallengeCards: Int = 0,
    /** Null while the match is still in progress. */
    val result: MatchResult? = null,
    /**
     * Set to 2 when the last challenge card is dealt into an active slot.
     * Decremented when each coach closes their action window; at 0 → [MatchResult.PointsVictory].
     * Null means Points Victory has not armed yet.
     */
    val pointsVictoryTurnsRemaining: Int? = null,
) {
    init {
        require(turnNumber >= 1) { "Turn number must be at least 1" }
        require(homeTouchdowns >= 0) { "homeTouchdowns cannot be negative" }
        require(awayTouchdowns >= 0) { "awayTouchdowns cannot be negative" }
        require(homeChallengeCards >= 0) { "homeChallengeCards cannot be negative" }
        require(awayChallengeCards >= 0) { "awayChallengeCards cannot be negative" }
        require(pointsVictoryTurnsRemaining == null || pointsVictoryTurnsRemaining >= 0) { "pointsVictoryTurnsRemaining must be ≥ 0 when set, was $pointsVictoryTurnsRemaining" }
    }
}

fun MatchProgress.addTouchdown(team: TeamSide): MatchProgress =
    when (team) {
        TeamSide.HOME -> copy(homeScore = homeScore.addTouchdown(), homeTouchdowns = homeTouchdowns + 1)
        TeamSide.AWAY -> copy(awayScore = awayScore.addTouchdown(), awayTouchdowns = awayTouchdowns + 1)
    }

fun MatchProgress.addPoints(team: TeamSide, points: Int): MatchProgress =
    when (team) {
        TeamSide.HOME -> copy(homeScore = homeScore.addPoints(points))
        TeamSide.AWAY -> copy(awayScore = awayScore.addPoints(points))
    }

/** Claim an active challenge into hand — points plus career card count. */
fun MatchProgress.recordChallengeCardClaim(team: TeamSide, points: Int): MatchProgress =
    when (team) {
        TeamSide.HOME -> copy(
            homeScore = homeScore.addPoints(points),
            homeChallengeCards = homeChallengeCards + 1,
        )
        TeamSide.AWAY -> copy(
            awayScore = awayScore.addPoints(points),
            awayChallengeCards = awayChallengeCards + 1,
        )
    }
