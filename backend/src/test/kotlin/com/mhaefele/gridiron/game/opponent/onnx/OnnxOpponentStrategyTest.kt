package com.mhaefele.gridiron.game.opponent.onnx

import com.mhaefele.gridiron.api.dto.response.LegalAction
import com.mhaefele.gridiron.game.legalmoves.LegalMovesGenerator
import com.mhaefele.gridiron.integration.support.BaseApiTest
import com.mhaefele.gridiron.shared.Coordinate
import com.mhaefele.gridiron.shared.TeamSide
import com.mhaefele.gridiron.support.GameScenario
import com.mhaefele.gridiron.support.TestProfiles
import com.mhaefele.gridiron.support.matchState
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

class OnnxOpponentStrategyTest : BaseApiTest() {
    @Autowired
    private lateinit var legalMovesGenerator: LegalMovesGenerator

    @Autowired
    private lateinit var onnxFactory: OnnxOpponentFactory

    @Autowired
    private lateinit var policy: OnnxPolicySession

    @Test
    fun `chooseAction returns a legal action from the packed set`() {
        assumeTrue(policy.isSeatable(OnnxPolicySession.DEFAULT_PROFILE)) { "run ai/export_opponent.sh (compatible schema) for classpath:ai-opponent/${OnnxPolicySession.DEFAULT_PROFILE.id}/" }

        val scenario = GameScenario.board()
        scenario.addPlayer(Coordinate(5, 6), profile = TestProfiles.withMove(4))
        scenario.addLooseBall(Coordinate(8, 6))
        val state = matchState(
            board = scenario.board,
            activeCoach = TeamSide.HOME,
            actionsRemaining = 3,
        )
        val legal = legalMovesGenerator.forActingCoach(state)
        assertThat(legal).isNotEmpty()

        val chosen = onnxFactory.forProfile(OnnxPolicySession.DEFAULT_PROFILE).chooseAction(state, legal)
        assertThat(legal.map { it.id }).contains(chosen.id)
        assertThat(chosen).isInstanceOfAny(
            LegalAction.PathMovement::class.java,
            LegalAction.Skip::class.java,
            LegalAction.Block::class.java,
            LegalAction.Mark::class.java,
            LegalAction.StandUp::class.java,
        )
    }
}
