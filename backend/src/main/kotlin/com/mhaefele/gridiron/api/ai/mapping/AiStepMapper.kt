package com.mhaefele.gridiron.api.ai.mapping

import com.mhaefele.gridiron.api.ai.dto.AiHarnessStepDto
import com.mhaefele.gridiron.api.ai.dto.nested.AiNestedStepDto
import com.mhaefele.gridiron.api.ai.dto.nested.AiNestedStepPolicyDto
import com.mhaefele.gridiron.api.ai.training.TrainingEpisodeEnd
import com.mhaefele.gridiron.api.dto.response.LegalAction
import com.mhaefele.gridiron.model.MatchState
import com.mhaefele.gridiron.model.board.Board
import com.mhaefele.gridiron.model.board.PlayerStatus
import com.mhaefele.gridiron.shared.TeamSide
import org.springframework.stereotype.Component

/**
 * AI step envelope + live-legal invariants. Injected into controllers / expert export.
 */
@Component
class AiStepMapper(
    private val gameState: AiGameStateMapper,
    private val legalChoices: AiLegalChoiceMapper,
    private val legalPacker: AiLegalPacker,
) {
    /**
     * Full AI step envelope: policy obs + factor matrix + harness meta/rewards/legals.
     *
     * @param perspectiveSeat defaults to the acting seat when omitted by the caller.
     */
    fun toAiStepDto(
        state: MatchState,
        legalActions: List<LegalAction>,
        perspectiveSeat: TeamSide = state.actingSeat(),
    ): AiNestedStepDto {
        checkLiveAiLegals(state, legalActions)
        val acting = state.actingSeat()
        val choices = legalChoices.toLegalChoices(legalActions, perspectiveSeat, state)
        val (resultKind, resultWinnerRel) = gameState.matchResultForHarness(state, perspectiveSeat)
        return AiNestedStepDto(
            policy = AiNestedStepPolicyDto(
                obs = gameState.toAiNestedObsDto(state, perspectiveSeat, legalActions),
                legalMoves = legalPacker.legalMoves(choices, state, perspectiveSeat),
            ),
            harness = AiHarnessStepDto(
                actingSeat = acting,
                perspectiveSeat = perspectiveSeat,
                resultKind = resultKind,
                resultWinnerRel = resultWinnerRel,
                trainingEpisodeEnded = TrainingEpisodeEnd.trainingEpisodeEnded(state),
                trainingOutcome = TrainingEpisodeEnd.trainingOutcome(state),
                rewards = gameState.toHarnessRewardSnapshot(state, perspectiveSeat),
                legalRows = choices,
            ),
        )
    }

    /**
     * Live AI decisions must have a non-empty legal set; terminal matches must have none.
     * Fail here (HTTP / expert export) instead of letting the gym harness discover empty choices mid-step.
     */
    fun checkLiveAiLegals(state: MatchState, legalActions: List<LegalAction>) {
        // Same terminal set as AiMatchController.harnessEpisodeOver (window + scenario early-end).
        if (state.isGameEnded() || TrainingEpisodeEnd.trainingEpisodeEnded(state)) {
            check(legalActions.isEmpty()) { "terminal match must have empty AI legals got=${legalActions.size} ${aiLegalDebug(state)}" }
        } else {
            check(legalActions.isNotEmpty()) { "No legal actions on a live match ${aiLegalDebug(state)}" }
        }
    }

    /** Dense one-line dump for empty-legal / terminal-legal invariant failures. */
    fun aiLegalDebug(state: MatchState): String {
        val turn = state.currentTurnState
        // TODO(pending): debug dump peeks nullable pending — fine for logging; keep out of game logic.
        val pending = turn.pendingDecision
        return "matchId=${state.id.value}" +
            " turn=${state.progress.turnNumber}" +
            " result=${state.progress.result}" +
            " pvTurns=${state.progress.pointsVictoryTurnsRemaining}" +
            " score=${state.progress.homeScore.points}-${state.progress.awayScore.points}" +
            " td=${state.progress.homeTouchdowns}-${state.progress.awayTouchdowns}" +
            " cards=${state.progress.homeChallengeCards}-${state.progress.awayChallengeCards}" +
            " phase=${turn.phase}" +
            " step=${turn.step}" +
            " activeCoach=${turn.activeCoach}" +
            " acting=${state.actingSeat()}" +
            " slotsRemaining=${turn.regularActionSlotsRemaining}" +
            " pending=${pending?.let { "${it.playDecisionType}/${it::class.simpleName} responding=${it.respondingCoach}" }}" +
            " currentPlayerAction=${turn.currentPlayerAction}" +
            " turnEndedPlayers=${turn.turnEndedPlayers}" +
            " actionsThisTurn=${turn.actionsThisTurn}" +
            " bonusPlaysUsed=${turn.bonusPlaysUsedThisTurn}" +
            " lastEventId=${state.lastEventId}" +
            " players=[${aiLegalPlayers(state.board)}]"
    }

    /** `h1@STANDING(2,15)+ball,h2@RESERVES,a1@PRONE(3,4)` — dugout has no coords. */
    private fun aiLegalPlayers(board: Board): String {
        val onPitch = board.allSquares()
            .mapNotNull { square -> square.playerId?.let { id -> id to square.coordinate } }
            .toMap()
        return board.players.values
            .sortedWith(compareBy({ it.team }, { it.id.value }))
            .joinToString(",") { player ->
                val ball = if (player.holdsBall()) "+ball" else ""
                val at = onPitch[player.id]
                when {
                    at != null                             -> "${player.id.value}@${player.status.name}$ball(${at.x},${at.y})"
                    player.status == PlayerStatus.RESERVES -> "${player.id.value}@RESERVES$ball"
                    else                                   -> "${player.id.value}@${player.status.name}$ball"
                }
            }
    }
}
