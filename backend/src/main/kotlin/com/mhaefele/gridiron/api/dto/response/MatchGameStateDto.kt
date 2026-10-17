package com.mhaefele.gridiron.api.dto.response

import com.mhaefele.gridiron.shared.CoachControl
import com.mhaefele.gridiron.shared.TeamSide
import com.mhaefele.gridiron.shared.TurnPhase
import io.swagger.v3.oas.annotations.media.Schema

@Schema(description = "Match HUD — scores, turn clock, seat control, and game-over.")
data class MatchGameStateDto(
    @field:Schema(description = "Completed coach turns so far this match.")
    val turnNumber: Int,
    @field:Schema(description = "Current turn phase (setup, player action, pre-turn refresh, …).")
    val phase: TurnPhase,
    @field:Schema(description = "Coach whose turn budget is active (may differ from who must act during follow-ups).")
    val activeCoach: TeamSide,
    @field:Schema(description = "Actions remaining in the active coach's budget this turn (0–3).")
    val actionsRemaining: Int,
    @field:Schema(description = "HOME score (touchdowns × 7 + challenge card points).")
    val homeScore: Int,
    @field:Schema(description = "AWAY score (touchdowns × 7 + challenge card points).")
    val awayScore: Int,
    @field:Schema(description = "Who submits actions for HOME — human client or in-process computer.")
    val homeControlledBy: CoachControl,
    @field:Schema(description = "Who submits actions for AWAY — human client or in-process computer.")
    val awayControlledBy: CoachControl,
    @field:Schema(description = "Set when the match is over; null while in progress.", nullable = true)
    val result: MatchResultDto?,
    @field:Schema(description = "Public active challenge slots and deck/discard counts.")
    val challenges: ChallengeTableauDto,
    @field:Schema(description = "Coach-turn windows left before Points Victory; null until the tableau is full.", nullable = true)
    val pointsVictoryTurnsRemaining: Int? = null,
)

@Schema(description = "Final match outcome.")
data class MatchResultDto(
    @field:Schema(description = "Sudden death (touchdown ends it) or points victory (score after timer).")
    val kind: MatchResultKind,
    @field:Schema(description = "Winning seat; null on a points-victory tie.", nullable = true)
    val winner: TeamSide?,
)

@Schema(description = "How a finished match was decided.")
enum class MatchResultKind {
    suddenDeath,
    pointsVictory,
}
