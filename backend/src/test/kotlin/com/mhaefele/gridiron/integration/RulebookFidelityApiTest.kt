package com.mhaefele.gridiron.integration

import com.mhaefele.gridiron.api.dto.response.LegalAction
import com.mhaefele.gridiron.game.action.pending.regular.RegularPlayerActionLegalMovesGenerator
import com.mhaefele.gridiron.integration.support.BaseApiTest
import com.mhaefele.gridiron.shared.ActionType
import com.mhaefele.gridiron.shared.Coordinate
import com.mhaefele.gridiron.shared.TeamSide
import com.mhaefele.gridiron.shared.TurnPhase
import com.mhaefele.gridiron.support.GameScenario
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

class RulebookFidelityApiTest : BaseApiTest() {

    @Autowired
    private lateinit var regularPlayerActionLegalMovesGenerator: RegularPlayerActionLegalMovesGenerator

    @Test
    fun `marked ball carrier has no throw and forged throw is rejected`() {
        val board = GameScenario.board()
        val carrier = board.holdingBall(board.addPlayer(5, 6))
        board.addPlayer(5, 4, team = carrier.team)
        val openThrow = regularPlayerActionLegalMovesGenerator.geometryMoves(carrier.player, board.board)
            .filterIsInstance<LegalAction.Throw>()
            .single()

        board.addPlayer(6, 6, team = carrier.team.opposite())
        val match = match { fromBoard(board.board) }

        assertThat(match.legalActions()).noneMatch { it.actionType == ActionType.THROW }
        assertBadRequest { match.play(openThrow) }
    }

    @Test
    fun `kickoff places setup ball on trapdoor without bounce and coach A skips first pre-turn`() {
        dice.queueD6(4) // AWAY wins toss → HOME is Coach A (loser)
        val match = api.createAndStartMatch(api.defaultCreateMatchRequest())
        placeAllInitialPieces(match)

        val state = api.gameState(match)
        assertThat(state.activeCoach).isEqualTo(TeamSide.HOME)
        assertThat(state.phase).isEqualTo(TurnPhase.PLAYER_ACTION)
        assertThat(state.turnNumber).isEqualTo(1)
        assertThat(state.actionsRemaining).isEqualTo(3)
        assertThat(api.legalActions(match)).noneMatch { it is LegalAction.Reserves }

        // Still on the trapdoor ⇒ kickoff placement did not bounce (New Ball! would).
        assertThat(api.pieces(match).looseBallLocations).containsExactly(Coordinate(6, 8))
        assertThat(api.pieces(match).players.none { it.holdsBall }).isTrue()
    }

    @Test
    fun `coach B first activation skips pre-turn`() {
        val match = match {
            bothTeamsDeployed()
            addLooseBall(6, 8)
            turnNumber(1)
            actionsRemaining(1)
        }
        match.playAny<LegalAction.Run>()

        val state = match.gameState()
        assertThat(state.activeCoach).isEqualTo(TeamSide.AWAY)
        assertThat(state.turnNumber).isEqualTo(2)
        assertThat(state.phase).isEqualTo(TurnPhase.PLAYER_ACTION)
        assertThat(state.actionsRemaining).isEqualTo(3)
        assertThat(match.legalActions()).noneMatch { it is LegalAction.Reserves }
        assertThat(match.pieces().looseBallLocations).containsExactly(Coordinate(6, 8))
    }

    @Test
    fun `coin toss loser is Coach A`() {
        dice.queueD6(2) // HOME wins 1–3 → AWAY is Coach A
        val awayIsA = api.createAndStartMatch(api.defaultCreateMatchRequest())
        placeAllInitialPieces(awayIsA)
        assertThat(api.gameState(awayIsA).activeCoach).isEqualTo(TeamSide.AWAY)
        assertThat(loadMatch(awayIsA).config.coachA).isEqualTo(TeamSide.AWAY)

        dice.reset()
        dice.queueD6(5) // AWAY wins 4–6 → HOME is Coach A
        val homeIsA = api.createAndStartMatch(api.defaultCreateMatchRequest())
        placeAllInitialPieces(homeIsA)
        assertThat(api.gameState(homeIsA).activeCoach).isEqualTo(TeamSide.HOME)
        assertThat(loadMatch(homeIsA).config.coachA).isEqualTo(TeamSide.HOME)
    }
}
