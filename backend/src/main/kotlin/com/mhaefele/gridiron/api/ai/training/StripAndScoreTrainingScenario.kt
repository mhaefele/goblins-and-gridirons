package com.mhaefele.gridiron.api.ai.training

import com.mhaefele.gridiron.api.dto.response.LegalAction
import com.mhaefele.gridiron.model.MatchState
import com.mhaefele.gridiron.model.TrainingScenarioEnum
import com.mhaefele.gridiron.shared.TeamSide
import org.springframework.stereotype.Component

/** An opponent near their end zone holds the ball; a trainer player is adjacent. Strip and score. */
@Component
class StripAndScoreTrainingScenario : AbstractTrainingScenario() {
    override val scenario = TrainingScenarioEnum.STRIP_AND_SCORE

    override fun buildBoard(setup: TrainingBoardBuilder, trainerSide: TeamSide, difficulty: Int) =
        buildAdjacentCarrierBlockBoard(setup, trainerSide, trainerSide.opposite(), difficulty)

    override fun validateBoard(state: MatchState, trainerSide: TeamSide, legals: List<LegalAction>) =
        validateAdjacentCarrierBlock(state, trainerSide, legals)

    override fun isSoftMaskOpeningMove(action: LegalAction, state: MatchState, trainerSide: TeamSide): Boolean =
        isSoftMaskAdjacentCarrierBlock(action, state, trainerSide)
}
