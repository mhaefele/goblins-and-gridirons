package com.mhaefele.gridiron.api.ai.training

import com.mhaefele.gridiron.api.ai.training.claim.ChallengeClaimTableau
import com.mhaefele.gridiron.api.ai.training.claim.ClaimBoardPhrases
import com.mhaefele.gridiron.api.dto.response.LegalAction
import com.mhaefele.gridiron.model.MatchState
import com.mhaefele.gridiron.model.TrainingScenarioEnum
import com.mhaefele.gridiron.model.catalog.ChallengeClaimCondition.GRAB_IT
import com.mhaefele.gridiron.model.catalog.ChallengeClaimCondition.PLAY_TO_THE_STANDS
import com.mhaefele.gridiron.model.catalog.ChallengeClaimCondition.DROP_THEM
import com.mhaefele.gridiron.shared.TeamSide
import org.springframework.stereotype.Component

/**
 * Trainer marked by an adjacent opposition Blocker (Drop Them! active). Opening lesson:
 * Sidestep off or Block the threat before their knockdown ([ai/BLINDSPOTS.md] `left-claim-open`).
 */
@Component
class DenyDropThemTrainingScenario : AbstractTrainingScenario() {
    override val scenario = TrainingScenarioEnum.DENY_DROP_THEM

    override fun buildBoard(setup: TrainingBoardBuilder, trainerSide: TeamSide, difficulty: Int) {
        ClaimBoardPhrases.placeDenyImminentBlockThreat(setup, trainerSide, withBall = false)
        val clearAround = setup.scatterAvoidZones()
        scatterDifficultyExtras(setup, trainerSide, difficulty, opponentAvoid = clearAround, trainerAvoid = clearAround)
    }

    override fun configureEpisode(state: MatchState, trainerSide: TeamSide): MatchState =
        state.copy(cards = ChallengeClaimTableau.withFocus(DROP_THEM, GRAB_IT, PLAY_TO_THE_STANDS))

    override fun validateBoard(state: MatchState, trainerSide: TeamSide, legals: List<LegalAction>) {
        require(state.cards.activeCards().any { it.challenge == DROP_THEM }) { "${scenario.name}: Drop Them! must be active" }
        require(legals.any { isSoftMaskOpeningMove(it, state, trainerSide) }) { "${scenario.name}: no Sidestep escape or Block on the Drop Them threat" }
    }

    override fun isSoftMaskOpeningMove(action: LegalAction, state: MatchState, trainerSide: TeamSide): Boolean =
        DenyImminentBlock.isSoftMaskEscapeOrBlock(action, state, trainerSide)
}
