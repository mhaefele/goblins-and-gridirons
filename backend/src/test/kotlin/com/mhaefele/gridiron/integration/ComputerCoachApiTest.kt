package com.mhaefele.gridiron.integration

import com.mhaefele.gridiron.api.dto.response.CreateMatchResponse
import com.mhaefele.gridiron.game.opponent.onnx.OnnxPolicySession
import com.mhaefele.gridiron.integration.support.BaseApiTest
import com.mhaefele.gridiron.shared.ActionType
import com.mhaefele.gridiron.shared.CoachControl
import com.mhaefele.gridiron.shared.ComputerOpponentKind
import com.mhaefele.gridiron.shared.TeamSide
import com.mhaefele.gridiron.shared.TurnPhase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.time.Duration

class ComputerCoachApiTest : BaseApiTest() {

    @Autowired
    private lateinit var onnxPolicy: OnnxPolicySession

    @Test
    fun `create match stores computer seats on match config`() {
        dice.usePassThrough()
        val match = api.createAndStartMatch(
            api.defaultCreateMatchRequest().copy(
                homeControlledBy = CoachControl.HUMAN,
                awayControlledBy = CoachControl.COMPUTER,
            ),
        )

        val gameState = api.gameState(match)
        assertThat(gameState.homeControlledBy).isEqualTo(CoachControl.HUMAN)
        assertThat(gameState.awayControlledBy).isEqualTo(CoachControl.COMPUTER)
        assertThat(loadMatch(match).config.controlledBy(TeamSide.AWAY)).isEqualTo(CoachControl.COMPUTER)
    }

    @Test
    fun `computer seat plays when it is the active coach`() {
        dice.queueD6(2) // HOME wins toss → AWAY is Coach A (loser)
        val match = api.createAndStartMatch(
            api.defaultCreateMatchRequest().copy(
                homeControlledBy = CoachControl.HUMAN,
                awayControlledBy = CoachControl.COMPUTER,
            ),
        )
        dice.usePassThrough()
        assertThat(loadMatch(match).config.coachA).isEqualTo(TeamSide.AWAY)
        placeHumanSetupPieces(match)

        val deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos()
        while (System.nanoTime() < deadline) {
            val state = api.gameState(match)
            if (state.activeCoach == TeamSide.HOME || state.phase == TurnPhase.GAME_OVER) break
            Thread.sleep(50)
        }

        val state = api.gameState(match)
        assertThat(state.activeCoach == TeamSide.HOME || state.phase == TurnPhase.GAME_OVER).isTrue()
    }

    @Test
    fun `create accepts heuristic computerOpponent without changing seats`() {
        dice.usePassThrough()
        val match = api.createAndStartMatch(
            api.defaultCreateMatchRequest().copy(
                homeControlledBy = CoachControl.HUMAN,
                awayControlledBy = CoachControl.COMPUTER,
                computerOpponent = ComputerOpponentKind.HEURISTIC,
            ),
        )
        val gameState = api.gameState(match)
        assertThat(gameState.awayControlledBy).isEqualTo(CoachControl.COMPUTER)
        assertThat(loadMatch(match).config.controlledBy(TeamSide.AWAY)).isEqualTo(CoachControl.COMPUTER)
    }

    @Test
    fun `create accepts onnx computerOpponent without changing seats`() {
        org.junit.jupiter.api.Assumptions.assumeTrue(onnxPolicy.artifactsPresent(OnnxPolicySession.DEFAULT_PROFILE)) { "run ai/export_opponent.sh to populate classpath:ai-opponent/${OnnxPolicySession.DEFAULT_PROFILE.id}/" }
        dice.usePassThrough()
        val match = api.createAndStartMatch(
            api.defaultCreateMatchRequest().copy(
                homeControlledBy = CoachControl.HUMAN,
                awayControlledBy = CoachControl.COMPUTER,
                computerOpponent = ComputerOpponentKind.ONNX,
                onnxProfile = OnnxPolicySession.DEFAULT_PROFILE.id,
            ),
        )
        assertThat(api.gameState(match).awayControlledBy).isEqualTo(CoachControl.COMPUTER)
    }

    /** Computer places Coach A's roster; only submit RESERVES when the human seat is to act. */
    private fun placeHumanSetupPieces(match: CreateMatchResponse) {
        val deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos()
        while (System.nanoTime() < deadline) {
            if (api.gameState(match).phase != TurnPhase.PIECE_PLACEMENT) return
            val reserves = api.legalActions(match).filter { it.actionType == ActionType.RESERVES }
            if (reserves.isNotEmpty()) {
                api.play(match, reserves.first())
            } else {
                Thread.sleep(20)
            }
        }
        error("piece placement did not finish")
    }
}
