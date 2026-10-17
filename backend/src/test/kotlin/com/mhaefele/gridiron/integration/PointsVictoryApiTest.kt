package com.mhaefele.gridiron.integration

import com.mhaefele.gridiron.api.dto.response.MatchResultDto
import com.mhaefele.gridiron.api.dto.response.MatchResultKind
import com.mhaefele.gridiron.api.dto.response.LegalAction
import com.mhaefele.gridiron.integration.support.BaseApiTest
import com.mhaefele.gridiron.integration.support.MatchApi
import com.mhaefele.gridiron.model.CardState
import com.mhaefele.gridiron.model.MatchResult
import com.mhaefele.gridiron.model.catalog.ChallengeCardCatalog
import com.mhaefele.gridiron.shared.TeamSide
import com.mhaefele.gridiron.shared.TurnPhase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * D1.C.3 — last challenge dealt → each coach one more turn → Points Victory (Sudden Death still wins).
 */
class PointsVictoryApiTest : BaseApiTest() {

    private fun playLastBudgetAction(match: MatchApi) {
        match.playAny<LegalAction.Run> { it.potentialChallengeClaims.isEmpty() }
    }

    /** One empty active slot and a single deck card — next Pre-Turn Refresh deals the last card. */
    private fun deckExhaustionCards(): CardState {
        val a = ChallengeCardCatalog.card(1)
        val b = ChallengeCardCatalog.card(2)
        val last = ChallengeCardCatalog.card(3)
        return CardState(
            activeSlots = listOf(a, b, null),
            deck = listOf(last),
        )
    }

    private fun emptyTableau(): CardState =
        CardState(activeSlots = listOf(null, null, null), deck = emptyList())

    private fun fullTableau(): CardState {
        val a = ChallengeCardCatalog.card(1)
        val b = ChallengeCardCatalog.card(2)
        val c = ChallengeCardCatalog.card(3)
        return CardState(activeSlots = listOf(a, b, c), deck = emptyList())
    }

    @Test
    fun `last challenge deal arms Points Victory then ends after each coach plays once`() {
        val match = match {
            bothTeamsDeployed()
            addLooseBall(6, 8)
            cards(deckExhaustionCards())
            actionsRemaining(1)
            scores(6, 2)
        }

        // HOME closes → AWAY Pre-Turn deals last card → Points Victory arms (2 turns).
        playLastBudgetAction(match)
        assertThat(match.gameState().pointsVictoryTurnsRemaining).isEqualTo(2)
        assertThat(match.gameState().activeCoach).isEqualTo(TeamSide.AWAY)
        assertThat(match.gameState().challenges.deckRemaining).isZero()
    }

    @Test
    fun `armed Points Victory ends after both coaches close`() {
        // AWAY closes with remaining=2 → 1; HOME closes with remaining=1 → PointsVictory.
        val match = match {
            bothTeamsDeployed()
            addLooseBall(6, 8)
            cards(emptyTableau())
            activeCoach(TeamSide.AWAY)
            actionsRemaining(1)
            turnNumber(5)
            scores(6, 2)
            pointsVictoryTurnsRemaining(2)
        }

        playLastBudgetAction(match)
        assertThat(match.gameState().pointsVictoryTurnsRemaining).isEqualTo(1)
        assertThat(match.gameState().activeCoach).isEqualTo(TeamSide.HOME)

        // Exhaust HOME without bonus interference (empty hands / empty actives).
        repeat(3) { playLastBudgetAction(match) }
        assertThat(match.gameState().phase).isEqualTo(TurnPhase.GAME_OVER)
        assertThat(loadMatch(match).progress.result).isEqualTo(MatchResult.PointsVictory(TeamSide.HOME))
        assertThat(match.gameState().result!!.kind).isEqualTo(MatchResultKind.pointsVictory)
        assertThat(match.gameState().result!!.winner).isEqualTo(TeamSide.HOME)
    }

    @Test
    fun `Points Victory draw when scores tied after final turns`() {
        // Empty actives so the closing action cannot claim and break the tie.
        val match = match {
            bothTeamsDeployed()
            addLooseBall(6, 8)
            cards(emptyTableau())
            actionsRemaining(1)
            turnNumber(5)
            scores(4, 4)
            pointsVictoryTurnsRemaining(1)
        }

        playLastBudgetAction(match)

        assertThat(match.gameState().result).isEqualTo(
            MatchResultDto(
                kind = MatchResultKind.pointsVictory,
                winner = null,
            ),
        )
    }

    @Test
    fun `Sudden Death still pre-empts during Points Victory window`() {
        // Armed with 2 turns left; AWAY closes (countdown → 1), then incoming HOME leads by ≥10
        // → Sudden Death wins before HOME's final Points Victory turn.
        val match = match {
            bothTeamsDeployed()
            addLooseBall(6, 8)
            cards(fullTableau())
            activeCoach(TeamSide.AWAY)
            actionsRemaining(1)
            turnNumber(5)
            scores(14, 3)
            pointsVictoryTurnsRemaining(2)
        }

        playLastBudgetAction(match)

        assertThat(match.gameState().phase).isEqualTo(TurnPhase.GAME_OVER)
        assertThat(loadMatch(match).progress.result).isEqualTo(MatchResult.SuddenDeath(TeamSide.HOME))
        assertThat(match.gameState().result!!.kind).isEqualTo(MatchResultKind.suddenDeath)
    }

    @Test
    fun `seeded armed countdown ends on next coach close`() {
        // Already armed with 1 turn left — closing the current coach ends the match.
        val match = match {
            bothTeamsDeployed()
            addLooseBall(6, 8)
            cards(fullTableau())
            actionsRemaining(1)
            turnNumber(5)
            scores(8, 3)
            pointsVictoryTurnsRemaining(1)
        }

        playLastBudgetAction(match)

        assertThat(loadMatch(match).progress.result).isEqualTo(MatchResult.PointsVictory(TeamSide.HOME))
        assertThat(match.gameState().result!!.kind).isEqualTo(MatchResultKind.pointsVictory)
    }
}
