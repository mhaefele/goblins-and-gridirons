package com.mhaefele.gridiron.api.ai.training

import com.mhaefele.gridiron.api.ai.training.claim.ChallengeClaimTableau
import com.mhaefele.gridiron.api.ai.training.claim.ClaimBoardPhrases
import com.mhaefele.gridiron.api.dto.response.LegalAction
import com.mhaefele.gridiron.model.MatchState
import com.mhaefele.gridiron.model.TrainingScenarioEnum
import com.mhaefele.gridiron.model.catalog.ChallengeClaimCondition.STRIP_IT
import com.mhaefele.gridiron.model.catalog.ChallengeClaimCondition.GRAB_IT
import com.mhaefele.gridiron.model.catalog.ChallengeClaimCondition.PLAY_TO_THE_STANDS
import com.mhaefele.gridiron.shared.TeamSide
import org.springframework.stereotype.Component

/**
 * Trainer carrier marked by an adjacent opposition Blocker (Strip It! active).
 * Opening lesson: Sidestep off or Block the threat before their strip ([ai/BLINDSPOTS.md]
 * `left-claim-open`).
 */
@Component
class DenyStripItTrainingScenario : AbstractTrainingScenario() {
    override val scenario = TrainingScenarioEnum.DENY_STRIP_IT

    override fun buildBoard(setup: TrainingBoardBuilder, trainerSide: TeamSide, difficulty: Int) {
        ClaimBoardPhrases.placeDenyImminentBlockThreat(setup, trainerSide, withBall = true)
        val clearAround = setup.scatterAvoidZones()
        scatterDifficultyExtras(setup, trainerSide, difficulty, opponentAvoid = clearAround, trainerAvoid = clearAround)
    }

    override fun configureEpisode(state: MatchState, trainerSide: TeamSide): MatchState =
        state.copy(cards = ChallengeClaimTableau.withFocus(STRIP_IT, GRAB_IT, PLAY_TO_THE_STANDS))

    override fun validateBoard(state: MatchState, trainerSide: TeamSide, legals: List<LegalAction>) {
        require(state.cards.activeCards().any { it.challenge == STRIP_IT }) { "${scenario.name}: Strip It! must be active" }
        require(state.board.players.values.any { it.team == trainerSide && it.holdsBall() }) { "${scenario.name}: trainer must hold the ball" }
        require(legals.any { isSoftMaskOpeningMove(it, state, trainerSide) }) { "${scenario.name}: no Sidestep escape or Block on the Free Up threat" }
    }

    override fun isSoftMaskOpeningMove(action: LegalAction, state: MatchState, trainerSide: TeamSide): Boolean =
        DenyImminentBlock.isSoftMaskEscapeOrBlock(action, state, trainerSide) { it.holdsBall() }
}
