package com.mhaefele.gridiron.api.ai.training

import com.mhaefele.gridiron.api.dto.response.BallEncounter
import com.mhaefele.gridiron.api.dto.response.LegalAction
import com.mhaefele.gridiron.model.MatchState
import com.mhaefele.gridiron.model.TrainingScenarioEnum
import com.mhaefele.gridiron.shared.TeamSide
import org.springframework.stereotype.Component

@Component
class LooseBallRunTrainingScenario : AbstractLooseBallTrainingScenario(contested = false) {
    override val scenario = TrainingScenarioEnum.LOOSE_BALL_RUN
    override val expectedBudgetedActions = 2

    override fun validateBoard(state: MatchState, trainerSide: TeamSide, legals: List<LegalAction>) {
        require(
            legals.filterIsInstance<LegalAction.Run>().any { run ->
                run.ballEncounter == BallEncounter.PICK_UP && state.board.players[run.playerId]?.team == trainerSide
            },
        ) { "LOOSE_BALL_RUN: no legal trainer run picks up the loose ball" }
    }

    override fun isSoftMaskOpeningMove(action: LegalAction, state: MatchState, trainerSide: TeamSide): Boolean =
        action is LegalAction.Run && action.ballEncounter == BallEncounter.PICK_UP && state.board.players[action.playerId]?.team == trainerSide
}
