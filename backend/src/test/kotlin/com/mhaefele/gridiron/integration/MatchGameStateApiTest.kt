package com.mhaefele.gridiron.integration

import com.mhaefele.gridiron.api.dto.response.MatchResultKind
import com.mhaefele.gridiron.shared.CoachControl
import com.mhaefele.gridiron.shared.Coordinate
import com.mhaefele.gridiron.shared.TeamSide
import com.mhaefele.gridiron.shared.TurnPhase
import com.mhaefele.gridiron.api.dto.response.LegalAction
import com.mhaefele.gridiron.integration.support.BaseApiTest
import com.mhaefele.gridiron.support.TestProfiles
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

private val awayEndZone = Coordinate(5, 15)

class MatchGameStateApiTest : BaseApiTest() {

    @Test
    fun `fresh match exposes opening turn clock and zero scores`() {
        dice.queueD6(4) // AWAY wins toss → HOME is Coach A (loser)
        val match = api.createAndStartMatch(api.defaultCreateMatchRequest())

        placeAllInitialPieces(match)

        val snapshot = api.snapshot(match)

        assertThat(snapshot.gameState.turnNumber).isEqualTo(1)
        assertThat(snapshot.gameState.phase).isEqualTo(TurnPhase.PLAYER_ACTION)
        assertThat(snapshot.gameState.activeCoach).isEqualTo(TeamSide.HOME)
        assertThat(snapshot.gameState.actionsRemaining).isEqualTo(3)
        assertThat(snapshot.gameState.homeScore).isZero()
        assertThat(snapshot.gameState.awayScore).isZero()
        assertThat(snapshot.gameState.homeControlledBy).isEqualTo(CoachControl.HUMAN)
        assertThat(snapshot.gameState.awayControlledBy).isEqualTo(CoachControl.HUMAN)
        assertThat(snapshot.lastEventId).isPositive()
        assertThat(snapshot.gameState.result).isNull()
    }

    @Test
    fun `run decrements actions remaining on game state`() {
        dice.queueD6(4) // AWAY wins toss → HOME is Coach A
        val match = api.createAndStartMatch(api.defaultCreateMatchRequest())
        placeAllInitialPieces(match)

        val run = api.legalActions<LegalAction.Run>(match)
            .first()
        api.play(match, run)

        val state = api.gameState(match)
        assertThat(state.actionsRemaining).isEqualTo(2)
    }

    @Test
    fun `touchdown updates score on game state`() {
        val match = match {
            actionsRemaining(2)
            holdingBall(addPlayer(5, 14, profile = TestProfiles.withMove(2)))
        }

        val run = match.legalActions<LegalAction.Run>()
            .first { it.path.squares.last() == awayEndZone }
        match.play(run)

        val state = match.gameState()
        assertThat(state.homeScore).isEqualTo(4)
        assertThat(state.awayScore).isZero()
        assertThat(state.result).isNull()
    }

    @Test
    fun `points victory match exposes result on game state`() {
        val match = match {
            bothTeamsDeployed()
            addLooseBall(6, 8)
            actionsRemaining(1)
            scores(8, 4)
            pointsVictoryTurnsRemaining(1)
        }
        match.playAny<LegalAction.Run>()

        val state = match.gameState()
        assertThat(state.phase).isEqualTo(TurnPhase.GAME_OVER)
        assertThat(state.result).isNotNull
        assertThat(state.result!!.kind).isEqualTo(MatchResultKind.pointsVictory)
        assertThat(state.result!!.winner).isEqualTo(TeamSide.HOME)
    }
}
