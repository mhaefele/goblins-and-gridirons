package com.mhaefele.gridiron.integration

import com.mhaefele.gridiron.api.dto.response.LegalAction
import com.mhaefele.gridiron.game.legalmoves.LegalMovesGenerator
import com.mhaefele.gridiron.integration.support.BaseApiTest
import com.mhaefele.gridiron.integration.support.assertActionBudgetValid
import com.mhaefele.gridiron.integration.support.assertAtMostOneBallPerSquare
import com.mhaefele.gridiron.integration.support.assertOccupancyConsistent
import com.mhaefele.gridiron.support.GameScenario
import com.mhaefele.gridiron.support.matchState
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import org.springframework.beans.factory.annotation.Autowired
import java.util.stream.Stream

/** Broad invariant coverage — each legal move played through the HTTP API on a fresh match. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LegalMovesInvariantApiTest : BaseApiTest() {

    @Autowired
    private lateinit var legalMovesGenerator: LegalMovesGenerator

    @ParameterizedTest
    @MethodSource("legalMovesFromDeployedMatch")
    fun `legal move preserves board invariants`(action: LegalAction) {
        val match = match { bothTeamsDeployed() }
        dice.usePassThrough()

        val response = match.play(action)
        val pieces = match.pieces()

        assertOccupancyConsistent(pieces)
        assertAtMostOneBallPerSquare(pieces)
        assertActionBudgetValid(response.snapshot.gameState)
    }

    fun legalMovesFromDeployedMatch(): Stream<LegalAction> =
        legalMovesGenerator.forActingCoach(matchState(board = GameScenario.bothTeamsDeployed())).stream()
}
