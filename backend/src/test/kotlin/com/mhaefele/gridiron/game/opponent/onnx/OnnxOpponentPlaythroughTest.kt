package com.mhaefele.gridiron.game.opponent.onnx

import com.mhaefele.gridiron.api.MatchGame
import com.mhaefele.gridiron.api.ai.training.TrainingScenarios
import com.mhaefele.gridiron.game.legalmoves.LegalMovesGenerator
import com.mhaefele.gridiron.game.opponent.scripted.ScriptedOpponentStrategy
import com.mhaefele.gridiron.integration.playthrough.SingleGameArenaRunner
import com.mhaefele.gridiron.integration.playthrough.dtos.TeamSetup
import com.mhaefele.gridiron.integration.support.BaseApiTest
import com.mhaefele.gridiron.model.TrainingDrillStatus
import com.mhaefele.gridiron.model.TrainingScenarioEnum
import com.mhaefele.gridiron.model.catalog.TeamCatalog
import com.mhaefele.gridiron.shared.CoachControl
import com.mhaefele.gridiron.shared.OnnxOpponentProfile
import com.mhaefele.gridiron.shared.TeamSide
import com.mhaefele.gridiron.shared.TrainingEpisodeMode
import com.mhaefele.gridiron.spring.InMemoryMatchRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import org.springframework.beans.factory.annotation.Autowired
import java.util.stream.Stream

/**
 * Smoke + solvability for each classpath ONNX profile under ``ai-opponent/{profile}/``.
 * Skips the class when no artifacts are present (run ``ai/export_opponent.sh``).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OnnxOpponentPlaythroughTest : BaseApiTest() {
    @Autowired
    private lateinit var onnxFactory: OnnxOpponentFactory

    @Autowired
    private lateinit var onnxPolicy: OnnxPolicySession

    @Autowired
    private lateinit var arena: SingleGameArenaRunner

    @Autowired
    private lateinit var heuristic: ScriptedOpponentStrategy

    @Autowired
    private lateinit var matchGame: MatchGame

    @Autowired
    private lateinit var repository: InMemoryMatchRepository

    @Autowired
    private lateinit var legalMovesGenerator: LegalMovesGenerator

    @Autowired
    private lateinit var trainingScenarios: TrainingScenarios

    @BeforeAll
    fun requireOnnxArtifacts() {
        assumeTrue(onnxPolicy.availableProfiles().isNotEmpty()) { "run ai/export_opponent.sh to populate classpath:ai-opponent/{profile}/" }
    }

    @BeforeEach
    fun randomDice() {
        dice.usePassThrough()
    }

    fun availableProfiles(): Stream<OnnxOpponentProfile> = onnxPolicy.availableProfiles().stream()

    @ParameterizedTest(name = "onnx/{0} full game vs heuristic")
    @MethodSource("availableProfiles")
    fun `plays one full game against heuristic`(profile: OnnxOpponentProfile) {
        val team = TeamCatalog.team("human")
        val onnx = TeamSetup(
            strategy = onnxFactory.forProfile(profile),
            team = team,
            label = "onnx-${profile.id}",
        )
        val scripted = TeamSetup(
            strategy = heuristic,
            team = team,
            label = "heuristic",
        )
        val result = arena.playGame(onnx, scripted, maxActions = 500)
        assertThat(result.actionsPlayed)
            .describedAs("onnx/${profile.id} should finish before maxActions (score=${result.homeScore}-${result.awayScore})")
            .isGreaterThan(0)
            .isLessThan(500)
        // PointsVictory.winner is null on a tied points finish; SuddenDeath always has a side.
        assertThat(result.matchProgress.result)
            .describedAs("onnx/${profile.id} should reach a MatchResult")
            .isNotNull()
    }

    @ParameterizedTest(name = "onnx/{0} TD_RUN d1")
    @MethodSource("availableProfiles")
    fun `solves TD_RUN at difficulty 1`(profile: OnnxOpponentProfile) {
        val script = trainingScenarios.implementation(TrainingScenarioEnum.TD_RUN)
        val state = matchGame.createScenarioMatch(
            scenario = TrainingScenarioEnum.TD_RUN,
            difficulty = 1,
            episodeMode = TrainingEpisodeMode.EVAL,
            awayControlledBy = CoachControl.TRAINER,
        )
        matchId = state.id
        val onnx = onnxFactory.forProfile(profile)
        try {
            var current = state
            val maxBudgeted = script.expectedBudgetedActions
            var budgeted = 0
            var steps = 0
            while (current.trainingEpisode!!.drillStatus != TrainingDrillStatus.PASS) {
                check(++steps <= 32) { "onnx/${profile.id} TD_RUN d1: too many steps\n${getDomainEventsString(state.id)}" }
                check(current.currentTurnState.activeCoach == TeamSide.HOME) { "onnx/${profile.id} TD_RUN d1: HOME lost the turn\n${getDomainEventsString(state.id)}" }
                val beforeActions = current.currentTurnState.actionsRemaining
                val beforeCards = current.progress.homeChallengeCards
                current = matchGame.submitActionOutcome(
                    current.id,
                    onnx.chooseAction(current, legalMovesGenerator.forActingCoach(current)),
                ).newState
                if (current.trainingEpisode!!.drillStatus == TrainingDrillStatus.PASS) break
                if (current.currentTurnState.actionsRemaining < beforeActions || current.progress.homeChallengeCards > beforeCards) {
                    budgeted++
                    check(budgeted <= maxBudgeted) { "onnx/${profile.id} TD_RUN d1: used $budgeted/$maxBudgeted budgeted actions\n${getDomainEventsString(state.id)}" }
                }
            }
            assertThat(current.trainingEpisode!!.drillStatus).isEqualTo(TrainingDrillStatus.PASS)
        } finally {
            repository.delete(state.id)
        }
    }
}
