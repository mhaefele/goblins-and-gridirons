package com.mhaefele.gridiron.api.ai.training

import com.mhaefele.gridiron.api.dto.response.BallEncounter
import com.mhaefele.gridiron.api.dto.response.LegalAction
import com.mhaefele.gridiron.model.MatchState
import com.mhaefele.gridiron.model.TrainingDrillStatus
import com.mhaefele.gridiron.model.TrainingScenarioEnum
import com.mhaefele.gridiron.shared.TeamSide
import org.springframework.stereotype.Component

/**
 * Soft-mask / expected: no ball → pickup; has ball → carrier EZ run.
 * Drill PASSes on touchdown only — pickup is expected but must not grade PASS.
 */
@Component
class LooseBallRunTrainingScenario : AbstractLooseBallTrainingScenario(contested = false) {
    override val scenario = TrainingScenarioEnum.LOOSE_BALL_RUN

    override fun validateBoard(state: MatchState, trainerSide: TeamSide, legals: List<LegalAction>) {
        require(
            legals.filterIsInstance<LegalAction.Run>().any { run ->
                run.ballEncounter == BallEncounter.PICK_UP && state.board.players[run.playerId]?.team == trainerSide
            },
        ) { "LOOSE_BALL_RUN: no legal trainer run picks up the loose ball" }
    }

    override fun isSoftMaskScenarioMove(action: LegalAction, state: MatchState, trainerSide: TeamSide): Boolean =
        isLessonRun(action, state, trainerSide)

    override fun isPassingScenarioMove(action: LegalAction, state: MatchState, trainerSide: TeamSide): Boolean =
        isLessonRun(action, state, trainerSide)

    override fun drillStatus(before: MatchState, after: MatchState, action: LegalAction): TrainingDrillStatus {
        if (scoredTouchdown(before, after, trainerSide(before))) return TrainingDrillStatus.PASS
        return TrainingDrillStatus.STILL_IN_PROGRESS
    }

    private fun isLessonRun(action: LegalAction, state: MatchState, trainerSide: TeamSide): Boolean {
        if (action !is LegalAction.Run || state.board.players[action.playerId]?.team != trainerSide) return false
        val carrier = trainerBallCarrierOrNull(state, trainerSide)
        return if (carrier == null) {
            action.ballEncounter == BallEncounter.PICK_UP
        } else {
            action.playerId == carrier.id && state.board.squareAt(action.path.squares.last()).isEndZone(trainerSide.opposite())
        }
    }
}
