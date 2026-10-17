package com.mhaefele.gridiron.integration

import com.mhaefele.gridiron.api.ai.training.TrainingScenarios
import com.mhaefele.gridiron.api.dto.response.LegalAction
import com.mhaefele.gridiron.game.opponent.lookahead.LookaheadOpponentStrategy
import com.mhaefele.gridiron.game.opponent.lookahead.LookaheadScorer
import com.mhaefele.gridiron.integration.support.BaseApiTest
import com.mhaefele.gridiron.model.TrainingDrillStatus
import com.mhaefele.gridiron.model.TrainingScenarioEnum
import com.mhaefele.gridiron.shared.CoachControl
import com.mhaefele.gridiron.shared.TeamSide
import com.mhaefele.gridiron.shared.TrainingEpisodeMode
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import java.util.stream.Stream

/**
 * Invariant for eval drills (no [com.mhaefele.gridiron.api.ai.training.AbstractTrainingScenario.difficultyProxy]):
 * at every difficulty 0–5, real [LookaheadOpponentStrategy] plays on the first dice draw
 * (no [BaseApiTest.withRandomDiceRetries]). Grading is each scenario's
 * [com.mhaefele.gridiron.api.ai.training.AbstractTrainingScenario.drillStatus]; action budget
 * is [com.mhaefele.gridiron.api.ai.training.AbstractTrainingScenario.expectedBudgetedActions].
 *
 * - [AbstractTrainingScenario.lookaheadSolveEval] true → must [TrainingDrillStatus.PASS]
 *   (same oracle as expert export).
 * - false → still choose→submit (exercise search on the board); do **not** require PASS.
 *
 * On budget / solve failure, HOME's opening turn is played out before the assert so the event log
 * shows what search chased. Decisive search leaves and banked points are appended to the failure
 * message only (quiet on PASS).
 *
 * Proxy drills (e.g. [TrainingScenarioEnum.LOOSE_BALL_BOUNCE] → [TrainingScenarioEnum.LOOSE_BALL_RUN])
 * are omitted — difficulty tracks the proxy target.
 *
 * Match create / legal menu / submit go through HTTP ([api]). Only [LookaheadOpponentStrategy.chooseAction]
 * is in-process — there is no “choose” wire for the oracle.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TrainingScenarioLookaheadTest : BaseApiTest() {
    private val log = LoggerFactory.getLogger(javaClass)

    @Autowired
    private lateinit var trainingScenarios: TrainingScenarios

    @Autowired
    private lateinit var lookahead: LookaheadOpponentStrategy

    @BeforeEach
    fun randomDice() {
        dice.usePassThrough()
    }

    @ParameterizedTest(name = "{0} d{1}")
    @MethodSource("scenarioDifficulties")
    fun `lookahead plays every non-proxy scenario`(scenario: TrainingScenarioEnum, difficulty: Int) {
        val script = trainingScenarios.implementation(scenario)
        val requirePass = script.lookaheadSolveEval
        val created = api.createScenarioMatch(
            scenarioMatchRequest(
                scenario = scenario,
                difficulty = difficulty,
                episodeMode = TrainingEpisodeMode.EVAL,
                awayControlledBy = CoachControl.COMPUTER,
            ),
        )
        matchId = created.matchId
        log.info(
            "Starting claim cards: {}",
            created.snapshot.gameState.challenges.activeSlots.joinToString { it?.challengeCondition?.toString() ?: "empty" },
        )
        var current = loadMatch(created.matchId)
        val maxBudgeted = script.expectedBudgetedActions
        var budgeted = 0
        var steps = 0
        var submits = 0
        var deferredFailure: String? = null
        val highNotes = mutableListOf<String>()
        // Keep playing HOME's opening turn after a budget miss so the event log includes the chase
        // (dump focus → TD, etc.). Stop early only on clean PASS.
        while (current.currentTurnState.activeCoach == TeamSide.HOME && steps < 48) {
            steps++
            if (deferredFailure == null && current.trainingEpisode!!.drillStatus == TrainingDrillStatus.PASS) break
            val beforeActions = current.currentTurnState.actionsRemaining
            val beforeCards = current.progress.homeChallengeCards
            val beforePoints = LookaheadScorer.points(current, TeamSide.HOME)
            val legals = api.legalActions(created.matchId)
            val pick = lookahead.chooseAction(current, legals)
            noteDecisiveSearch(highNotes, pick)
            api.play(created.matchId, pick)
            current = loadMatch(created.matchId)
            submits++
            val afterPoints = LookaheadScorer.points(current, TeamSide.HOME)
            if (afterPoints > beforePoints) {
                highNotes +=
                    "banked +${afterPoints - beforePoints} pts ($beforePoints→$afterPoints) after ${describePick(pick)}"
            }
            if (deferredFailure == null && current.trainingEpisode!!.drillStatus == TrainingDrillStatus.PASS) break
            if (current.currentTurnState.actionsRemaining < beforeActions || current.progress.homeChallengeCards > beforeCards) {
                budgeted++
                if (requirePass && budgeted > maxBudgeted && deferredFailure == null) {
                    deferredFailure =
                        "$scenario d$difficulty: used $budgeted/$maxBudgeted budgeted actions\n" + "Remaining challenge claims: ${current.cards.activeChallengeClaims()}"
                }
            }
        }
        assertThat(submits).isGreaterThan(0)
        if (requirePass && deferredFailure == null) {
            when {
                current.currentTurnState.activeCoach != TeamSide.HOME             -> deferredFailure = "$scenario d$difficulty: HOME lost the turn\nRemaining challenge claims: ${current.cards.activeChallengeClaims()}"
                current.trainingEpisode!!.drillStatus != TrainingDrillStatus.PASS -> deferredFailure = "$scenario d$difficulty: expected PASS, was ${current.trainingEpisode!!.drillStatus}\n" + "Remaining challenge claims: ${current.cards.activeChallengeClaims()}"
            }
        }
        if (deferredFailure != null) {
            val highs =
                if (highNotes.isEmpty()) ""
                else "\nHigh EV while missing the lesson:\n" + highNotes.joinToString("\n") { "  - $it" }
            error("$deferredFailure$highs\n${getDomainEventsString(created.matchId)}")
        }
        if (requirePass) {
            check(steps <= 48) { "$scenario d$difficulty: too many Lookahead steps\n${getDomainEventsString(created.matchId)}" }
            assertThat(current.trainingEpisode!!.drillStatus).isEqualTo(TrainingDrillStatus.PASS)
        }
    }

    private fun noteDecisiveSearch(highNotes: MutableList<String>, pick: LegalAction) {
        val search = lookahead.lastSearch
        if (!search.decisive) return
        highNotes +=
            "LA leaf baseline=${"%.1f".format(search.baseline)} best=${"%.1f".format(search.bestScore)} " + "(+${"%.1f".format(search.bestScore - search.baseline)}) pick=${describePick(pick)}"
    }

    private fun describePick(pick: LegalAction): String =
        when (pick) {
            is LegalAction.ReplaceChallenge -> "Replace(slot=${pick.slotIndex}, discard=${pick.claimCondition})"
            is LegalAction.Run              -> "Run(${pick.playerId.value}→${pick.path.squares.last()}${pick.ballEncounter?.let { "/$it" } ?: ""})"
            is LegalAction.Mark             -> "Mark(${pick.playerId.value}→${pick.path.squares.last()})"
            is LegalAction.Throw            -> "Throw(${pick.playerId.value}→${pick.target.destination})"
            is LegalAction.Block            -> "Block(${pick.playerId.value}@${pick.destination})"
            else                            -> pick::class.simpleName ?: pick.toString()
        }

    fun scenarioDifficulties(): Stream<Arguments> =
        TrainingScenarioEnum.entries
            .filter { trainingScenarios.implementation(it).difficultyProxy == null }
            .flatMap { scenario -> (0..5).map { difficulty -> Arguments.of(scenario, difficulty) } }
            .stream()
}
