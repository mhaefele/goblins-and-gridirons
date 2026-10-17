package com.mhaefele.gridiron.integration

import com.mhaefele.gridiron.api.dto.response.LegalAction
import com.mhaefele.gridiron.api.mapping.toChallengeTableauDto
import com.mhaefele.gridiron.game.opponent.ComputerCoachRegistry
import com.mhaefele.gridiron.integration.support.BaseApiTest
import com.mhaefele.gridiron.model.CardState
import com.mhaefele.gridiron.shared.CoachControl
import com.mhaefele.gridiron.shared.TeamSide
import com.mhaefele.gridiron.shared.TurnPhase
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpStatus
import org.springframework.web.client.HttpStatusCodeException

class MatchApiTest : BaseApiTest() {

    @Autowired
    private lateinit var computerCoachRegistry: ComputerCoachRegistry

    @Test
    fun `create match returns create response without board`() {
        dice.queueD6(4) // AWAY wins toss → HOME is Coach A
        val response = api.createAndStartMatch(api.defaultCreateMatchRequest())

        val matchId = response.matchId
        assertThat(matchId.value).isNotBlank()
        val gameState = response.snapshot.gameState
        assertThat(gameState.turnNumber).isEqualTo(1)
        assertThat(gameState.phase).isEqualTo(TurnPhase.PIECE_PLACEMENT)
        assertThat(gameState.activeCoach).isEqualTo(TeamSide.HOME)
        assertThat(gameState.actionsRemaining).isZero()
        assertThat(gameState.homeScore).isZero()
        assertThat(gameState.awayScore).isZero()
        assertThat(gameState.homeControlledBy).isEqualTo(CoachControl.HUMAN)
        assertThat(gameState.awayControlledBy).isEqualTo(CoachControl.HUMAN)
        assertThat(gameState.result).isNull()
        assertThat(gameState.challenges).isEqualTo(toChallengeTableauDto(CardState.dealInCatalogOrder()))

        placeAllInitialPieces(matchId)

        val afterPlacement = api.gameState(matchId)
        assertThat(afterPlacement.phase).isEqualTo(TurnPhase.PLAYER_ACTION)
        assertThat(afterPlacement.actionsRemaining).isEqualTo(3)
        assertThat(afterPlacement.challenges).isEqualTo(toChallengeTableauDto(CardState.dealInCatalogOrder()))

        assertThat(api.pieces(response).players).hasSize(12)
    }

    @Test
    fun `create match and submit a legal run`() {
        dice.queueD6(4) // AWAY wins toss → HOME is Coach A
        val match = api.createAndStartMatch(api.defaultCreateMatchRequest())

        placeAllInitialPieces(match.matchId)

        val run = api.legalActions<LegalAction.Run>(match).first()

        val response = api.play(match, run)

        assertThat(response.snapshot.gameState.actionsRemaining).isEqualTo(2)
        assertThat(api.gameState(match)).isEqualTo(response.snapshot.gameState)
    }

    @Test
    fun `delete match frees snapshot and is idempotent`() {
        dice.queueD6(4)
        val match = api.createAndStartMatch(
            api.defaultCreateMatchRequest().copy(
                awayControlledBy = CoachControl.COMPUTER,
            ),
        )
        assertThat(computerCoachRegistry.hasAgents(match.matchId)).isTrue()

        api.deleteMatch(match)
        assertThat(computerCoachRegistry.hasAgents(match.matchId)).isFalse()
        assertThatThrownBy { api.snapshot(match) }
            .isInstanceOf(HttpStatusCodeException::class.java)
            .extracting { (it as HttpStatusCodeException).statusCode }
            .isEqualTo(HttpStatus.NOT_FOUND)

        api.deleteMatch(match) // second delete is a no-op
        assertThat(computerCoachRegistry.hasAgents(match.matchId)).isFalse()
    }
}
