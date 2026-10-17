package com.mhaefele.gridiron.api.ai.training

import com.mhaefele.gridiron.api.dto.response.LegalAction
import com.mhaefele.gridiron.model.CardState
import com.mhaefele.gridiron.model.MatchState
import com.mhaefele.gridiron.model.TrainingScenarioEnum
import com.mhaefele.gridiron.model.TurnState
import com.mhaefele.gridiron.model.catalog.ChallengeCardCatalog
import com.mhaefele.gridiron.model.catalog.ChallengeClaimCondition
import com.mhaefele.gridiron.model.pending.ReplaceChallenge
import com.mhaefele.gridiron.shared.PreTurnStep
import com.mhaefele.gridiron.shared.TeamSide
import com.mhaefele.gridiron.shared.TurnPhase
import org.springframework.stereotype.Component

/**
 * Full challenge tableau; episode opens on forced Pre-Turn replace.
 *
 * **Exposure only** ([gatesProgress] / [lookaheadSolveEval] false): random three titles, soft-mask
 * any discard. No trusted keep-set oracle yet (own-turn claim search still missing).
 */
@Component
class ChallengeReplaceTrainingScenario : AbstractTrainingScenario() {
    override val scenario = TrainingScenarioEnum.CHALLENGE_REPLACE
    override val gatesProgress = false
    override val lookaheadSolveEval = false

    override fun buildBoard(setup: TrainingBoardBuilder, trainerSide: TeamSide, difficulty: Int) {
        setup.placeRandomReserve(trainerSide, setup::emptyPlayable)
        setup.placeRandomReserve(trainerSide.opposite(), setup::emptyPlayable)
        scatterDifficultyExtras(setup, trainerSide, difficulty)
    }

    override fun configureEpisode(state: MatchState, trainerSide: TeamSide): MatchState {
        val episode = requireTrainingEpisode(state)
        val openingTurn = 3
        val titles = ChallengeClaimCondition.entries.shuffled().take(3)
        val actives = ChallengeCardCatalog.cardsForTitles(titles)
        val used = actives.map { it.id }.toSet()
        return state.copy(
            progress = state.progress.copy(turnNumber = openingTurn),
            cards = CardState(
                activeSlots = actives,
                deck = ChallengeCardCatalog.allCards().filter { it.id !in used },
            ),
            currentTurnState = TurnState(
                phase = TurnPhase.PRE_TURN,
                step = PreTurnStep.REPLACE_CHALLENGE,
                activeCoach = trainerSide,
                pendingDecision = ReplaceChallenge(),
            ),
            trainingEpisode = episode.copy(openingTurnNumber = openingTurn),
        )
    }

    override fun validateBoard(state: MatchState, trainerSide: TeamSide, legals: List<LegalAction>) {
        val replaces = legals.filterIsInstance<LegalAction.ReplaceChallenge>()
        require(replaces.size == 3) { "CHALLENGE_REPLACE: expected 3 ReplaceChallenge legals, was ${replaces.size}" }
    }

    override fun isSoftMaskOpeningMove(action: LegalAction, state: MatchState, trainerSide: TeamSide): Boolean =
        action is LegalAction.ReplaceChallenge
}
