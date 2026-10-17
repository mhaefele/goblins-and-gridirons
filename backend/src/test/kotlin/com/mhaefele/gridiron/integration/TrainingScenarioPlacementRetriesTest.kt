package com.mhaefele.gridiron.integration

import com.mhaefele.gridiron.api.MatchGame
import com.mhaefele.gridiron.integration.support.BaseApiTest
import com.mhaefele.gridiron.model.TrainingScenarioEnum
import com.mhaefele.gridiron.shared.CoachControl
import com.mhaefele.gridiron.shared.TrainingEpisodeMode
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.springframework.beans.factory.annotation.Autowired

/**
 * Harness placement may retry up to [TrainingScenarios.MAX_PLACEMENT_ATTEMPTS] (64). These tests
 * use the same loop but a much lower cap — geometry that routinely burns dozens of attempts is
 * a bug even when it would eventually succeed in production.
 *
 * Prefer constructive search (valid triangles / join casts / path-checked carriers) over
 * place-then-hope so first attempts usually succeed; retries stay for weird roster/pitch edges.
 */
class TrainingScenarioPlacementRetriesTest : BaseApiTest() {

    @Autowired
    private lateinit var matchGame: MatchGame

    @BeforeEach
    fun randomDice() {
        dice.usePassThrough()
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(TrainingScenarioEnum::class, names = ["CLAIM_.*"], mode = EnumSource.Mode.MATCH_ALL)
    fun `claim scenario placement succeeds within test retry budget at max difficulty`(scenario: TrainingScenarioEnum) {
        repeat(TRIALS) { matchGame.createScenarioMatch(scenario = scenario, difficulty = MAX_DIFFICULTY, episodeMode = TrainingEpisodeMode.TRAIN, awayControlledBy = CoachControl.COMPUTER, maxPlacementAttempts = TEST_MAX_PLACEMENT_ATTEMPTS,) }
    }

    private companion object {
        private const val TRIALS = 40
        private const val MAX_DIFFICULTY = 5
        /** Well below [TrainingScenarios.MAX_PLACEMENT_ATTEMPTS] — catches chronic scatter/filler leaks. */
        private const val TEST_MAX_PLACEMENT_ATTEMPTS = 8
    }
}
