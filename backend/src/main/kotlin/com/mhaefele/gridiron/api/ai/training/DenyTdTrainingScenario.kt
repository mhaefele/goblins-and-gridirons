package com.mhaefele.gridiron.api.ai.training

import com.mhaefele.gridiron.api.dto.response.LegalAction
import com.mhaefele.gridiron.model.MatchState
import com.mhaefele.gridiron.model.TrainingScenarioEnum
import com.mhaefele.gridiron.shared.TeamSide
import org.springframework.stereotype.Component

/**
 * Opponent carrier near **their** scoring end zone (trainer's end zone) with a trainer adjacent.
 * Opening lesson: Block the carrier before they walk in ([ai/BLINDSPOTS.md] `left-td-open`).
 *
 * Same helpers as [StripAndScoreTrainingScenario]; end zone is flipped (`trainerSide` vs opposite).
 */
@Component
class DenyTdTrainingScenario : AbstractTrainingScenario() {
    override val scenario = TrainingScenarioEnum.DENY_TD

    override fun buildBoard(setup: TrainingBoardBuilder, trainerSide: TeamSide, difficulty: Int) =
        buildAdjacentCarrierBlockBoard(setup, trainerSide, trainerSide, difficulty)

    override fun validateBoard(state: MatchState, trainerSide: TeamSide, legals: List<LegalAction>) =
        validateAdjacentCarrierBlock(state, trainerSide, legals)

    override fun isSoftMaskOpeningMove(action: LegalAction, state: MatchState, trainerSide: TeamSide): Boolean =
        isSoftMaskAdjacentCarrierBlock(action, state, trainerSide)
}
