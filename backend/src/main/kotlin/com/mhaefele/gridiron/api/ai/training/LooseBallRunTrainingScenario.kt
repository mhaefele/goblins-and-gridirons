package com.mhaefele.gridiron.api.ai.training

import com.mhaefele.gridiron.api.dto.response.BallEncounter
import com.mhaefele.gridiron.api.dto.response.LegalAction
import com.mhaefele.gridiron.model.MatchState
import com.mhaefele.gridiron.model.TrainingScenarioEnum
import com.mhaefele.gridiron.shared.TeamSide
import org.springframework.stereotype.Component

/** Soft-mask: no ball → pickup runs; has ball → carrier runs into the scoring end zone. */
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

    /** PASS only once carrying — pickup soft-masks but does not grade PASS. */
    override fun isPassingScenarioMove(action: LegalAction, state: MatchState, trainerSide: TeamSide): Boolean =
        trainerBallCarrierOrNull(state, trainerSide) != null && isLessonRun(action, state, trainerSide)

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
